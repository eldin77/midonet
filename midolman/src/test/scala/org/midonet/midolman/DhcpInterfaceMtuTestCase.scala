/*
 * Copyright 2012 Midokura
 */
package org.midonet.midolman

import scala.collection.JavaConversions._
import scala.sys.process._
import java.nio.ByteBuffer

import collection.mutable
import akka.testkit.TestProbe
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory

import org.midonet.midolman.topology.VirtualToPhysicalMapper._
import org.midonet.midolman.guice.actors.OutgoingMessage
import org.midonet.cluster.data.zones._
import layer3.Route
import layer3.Route.NextHop
import topology.LocalPortActive
import util.SimulationHelper
import util.RouterHelper
import org.midonet.packets._
import org.midonet.cluster.data.dhcp.Opt121
import org.midonet.cluster.data.dhcp.Subnet
import org.midonet.cluster.data.ports.MaterializedBridgePort
import org.midonet.odp.flows.{FlowActionOutput, FlowAction}
import host.interfaces.InterfaceDescription
import org.midonet.midolman.PacketWorkflowActor.PacketIn
import org.midonet.midolman.DeduplicationActor.EmitGeneratedPacket
import org.midonet.midolman.DatapathController.TunnelChangeEvent

@RunWith(classOf[JUnitRunner])
class DhcpInterfaceMtuTestCase extends MidolmanTestCase with
          VirtualConfigurationBuilders with SimulationHelper with RouterHelper {

    private final val log = LoggerFactory.getLogger(classOf[DhcpInterfaceMtuTestCase])

    val routerIp1 = IntIPv4.fromString("192.168.111.1", 24)
    val routerMac1 = MAC.fromString("22:aa:aa:ff:ff:ff")
    val routerIp2 = IntIPv4.fromString("192.168.222.1", 24)
    val routerMac2 = MAC.fromString("22:ab:cd:ff:ff:ff")
    val vmMac = MAC.fromString("02:23:24:25:26:27")
    var brPort : MaterializedBridgePort = null
    var vmIP : IntIPv4 = null
    val vmPortName = "VirtualMachine"
    var vmPortNumber = 0
    var intfMtu = 0

    private var packetsEventsProbe: TestProbe = null
    private var tunnelChangeProbe : TestProbe = null

    override def beforeTest() {
        packetsEventsProbe = newProbe()
        tunnelChangeProbe = newProbe()
        actors().eventStream.subscribe(packetsEventsProbe.ref, classOf[PacketsExecute])
        actors().eventStream.subscribe(tunnelChangeProbe.ref, classOf[TunnelChangeEvent])

        val host = newHost("myself", hostId())
        host should not be null
        val host2 = newHost("someone else")
        host2 should not be null
        val router = newRouter("router")
        router should not be null
        val bridge = newBridge("bridge")
        bridge should not be null

        val cmdline_ip = ( "/sbin/ifconfig"
                            + "| grep -w inet | grep -vw 127.0.0.1"
                            + "| egrep -o '((1?[0-9]{1,2}|2([0-5]){2})\\.?){4}'"
                            + "| sed -n 1p" )

        log.debug("looking for ip address with command {}", cmdline_ip)

        val ipString: String = Seq("sh", "-c", cmdline_ip).!!.trim

        log.debug("ipString is {}", ipString)

        // try to catch the mtu var around the ip captured by cmdline_ip
        // it should be 3 lines above on OSX and 2 lines below on linux
        val cmdline_mtu = ( "/sbin/ifconfig"
                            + "| grep -A 2 -B 3 " + ipString
                            + "| egrep -o -i 'mtu(:| )[0-9]+'"
                            + "| cut -c 5-" )

        log.debug("looking for MTU with command {}", cmdline_mtu)

        val intfMtu_string: String = Seq("sh", "-c", cmdline_mtu).!!.trim

        log.debug("MTU is {}", intfMtu_string)

        // store original interface MTU
        // TODO(guillermo) use pino's mock interface scanner when merged.
        intfMtu = intfMtu_string.toInt

        vmIP = IntIPv4.fromString(ipString, 24)

        // add this interface in MockInterfaceScanner list
        val intf = new InterfaceDescription("My Interface")
        intf.setInetAddress(ipString)
        intf.setMtu(intfMtu)
        interfaceScanner.addInterface(intf)

        val greZone = greTunnelZone("default")

        val myGreConfig = new GreTunnelZoneHost(host.getId)
            .setIp(IntIPv4.fromString(ipString))

        val peerGreConfig = new GreTunnelZoneHost(host2.getId)
            .setIp(IntIPv4.fromString("192.168.200.1"))

        clusterDataClient().tunnelZonesAddMembership(greZone.getId, peerGreConfig)
        clusterDataClient().tunnelZonesAddMembership(greZone.getId, myGreConfig)

        initializeDatapath() should not be (null)
        requestOfType[HostRequest](vtpProbe())
        requestOfType[OutgoingMessage](vtpProbe())

        val rtrPort1 = newExteriorRouterPort(router, routerMac1,
            routerIp1.toUnicastString,
            routerIp1.toNetworkAddress.toUnicastString,
            routerIp1.getMaskLength)
        rtrPort1 should not be null
        materializePort(rtrPort1, host, "RouterPort1")
        val portEvent = requestOfType[LocalPortActive](portsProbe)
        portEvent.active should be(true)
        portEvent.portID should be(rtrPort1.getId)

        newRoute(router, "0.0.0.0", 0,
            routerIp1.toNetworkAddress.toUnicastString, routerIp1.getMaskLength,
            NextHop.PORT, rtrPort1.getId,
            new IntIPv4(Route.NO_GATEWAY).toUnicastString, 10)

        val rtrPort2 = newInteriorRouterPort(router, routerMac2,
            routerIp2.toUnicastString,
            routerIp2.toNetworkAddress.toUnicastString,
            routerIp2.getMaskLength)
        rtrPort2 should not be null

        val brPort1 = newInteriorBridgePort(bridge)
        brPort1 should not be null
        clusterDataClient().portsLink(rtrPort2.getId, brPort1.getId)

        val brPort2 = newExteriorBridgePort(bridge)
        brPort2 should not be null

        val tzRequest = fishForRequestOfType[TunnelZoneRequest](vtpProbe())
        tzRequest.zoneId should be === greZone.getId

        var tunnelEvent = requestOfType[TunnelChangeEvent](tunnelChangeProbe)
        tunnelEvent.op should be(TunnelChangeEventOperation.Established)
        tunnelEvent.peer.getId should be(host2.getId)

        var opt121Obj = (new Opt121()
                        .setGateway(routerIp2)
                        .setRtDstSubnet(routerIp1.toNetworkAddress))
        var opt121Routes: List[Opt121] = List(opt121Obj)
        var dhcpSubnet = (new Subnet()
                       .setSubnetAddr(routerIp2.toNetworkAddress)
                       .setDefaultGateway(routerIp2)
                       .setOpt121Routes(opt121Routes))
        addDhcpSubnet(bridge, dhcpSubnet)

        materializePort(brPort2, host, vmPortName)
        requestOfType[LocalPortActive](portsProbe)

        val dhcpHost = (new org.midonet.cluster.data.dhcp.Host()
                       .setMAC(vmMac)
                       .setIp(vmIP))
        addDhcpHost(bridge, dhcpSubnet, dhcpHost)

        flowProbe().expectMsgType[DatapathController.DatapathReady].datapath should not be (null)
        drainProbes()
    }

    private def expectPacketOut(portNum : Int): Ethernet = {
        val pktOut = requestOfType[PacketsExecute](packetsEventsProbe).packet
        pktOut should not be null
        pktOut.getData should not be null
        log.debug("Packet execute: {}", pktOut)

        pktOut.getActions.size should equal (1)

        pktOut.getActions.toList map { action =>
            action.getKey should be === FlowAction.FlowActionAttr.OUTPUT
            action.getValue.getClass() should be === classOf[FlowActionOutput]
            action.getValue.asInstanceOf[FlowActionOutput].getPortNumber
        } should contain (portNum)

        Ethernet.deserialize(pktOut.getData)
    }

    private def injectDhcpDiscover(portName: String, srcMac : MAC) {
        val dhcpDiscover = new DHCP()
        dhcpDiscover.setOpCode(0x01)
        dhcpDiscover.setHardwareType(0x01)
        dhcpDiscover.setHardwareAddressLength(6)
        dhcpDiscover.setClientHardwareAddress(srcMac)
        var options = mutable.ListBuffer[DHCPOption]()
        options.add(new DHCPOption(DHCPOption.Code.DHCP_TYPE.value,
                           DHCPOption.Code.DHCP_TYPE.length,
                           Array[Byte](DHCPOption.MsgType.DISCOVER.value)))
        dhcpDiscover.setOptions(options)
        val udp = new UDP()
        udp.setSourcePort((68).toShort)
        udp.setDestinationPort((67).toShort)
        udp.setPayload(dhcpDiscover)
        val eth = new Ethernet()
        eth.setSourceMACAddress(srcMac)
        eth.setDestinationMACAddress(MAC.fromString("ff:ff:ff:ff:ff:ff"))
        eth.setEtherType(IPv4.ETHERTYPE)
        eth.setPayload(new IPv4().setSourceAddress(0).
                                  setDestinationAddress(0xffffffff).
                                  setProtocol(UDP.PROTOCOL_NUMBER).
                                  setPayload(udp))
        triggerPacketIn(portName, eth)
    }

    private def extractInterfaceMtuDhcpReply(ethPkt : Ethernet) : Short = {
        ethPkt.getEtherType should be === IPv4.ETHERTYPE
        val ipPkt = ethPkt.getPayload.asInstanceOf[IPv4]
        ipPkt.getProtocol should be === UDP.PROTOCOL_NUMBER
        val udpPkt = ipPkt.getPayload.asInstanceOf[UDP]
        udpPkt.getSourcePort() should be === 67
        udpPkt.getDestinationPort() should be === 68
        val dhcpPkt = udpPkt.getPayload.asInstanceOf[DHCP]
        val replyOptions = mutable.HashMap[Byte, DHCPOption]()
        val replyCodes = mutable.Set[Byte]()
        for (opt <- dhcpPkt.getOptions) {
            val code = opt.getCode
            replyOptions.put(code, opt)
            code match {
                case v if (v == DHCPOption.Code.INTERFACE_MTU.value) =>
                    if (opt.getLength != 2) {
                        fail("DHCP option interface mtu value invalid length")
                    }
                    val mtu : Short = ByteBuffer.wrap(opt.getData).getShort
                    log.debug("extractInterfaceMtuDhcpReply got data {} and value {}", opt.getData, mtu)
                    return mtu
                case _ => 0
            }
        }
        0
    }

    def test() {
        injectDhcpDiscover(vmPortName, vmMac)
        requestOfType[PacketIn](packetInProbe)
        val returnPkt = requestOfType[EmitGeneratedPacket](dedupProbe()).eth
        val interfaceMtu = extractInterfaceMtuDhcpReply(returnPkt)
        log.info("Returning interface MTU is {}", interfaceMtu)
        intfMtu -= (new GreTunnelZone).getTunnelOverhead()
        interfaceMtu should equal (intfMtu)
    }
}