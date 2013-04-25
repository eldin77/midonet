/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package org.midonet.midolman

import collection.immutable.HashMap
import collection.mutable

import akka.testkit.TestProbe
import akka.util.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.apache.commons.configuration.HierarchicalConfiguration
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.DeduplicationActor.DiscardPacket
import org.midonet.midolman.FlowController.{RemoveWildcardFlow,
    WildcardFlowAdded, WildcardFlowRemoved, InvalidateFlowsByTag}
import org.midonet.midolman.PacketWorkflowActor.PacketIn
import org.midonet.midolman.layer3.Route._
import org.midonet.midolman.topology.LocalPortActive
import org.midonet.midolman.topology.RouterManager.RouterInvTrieTagCountModified
import org.midonet.midolman.util.{RouterHelper, TestHelpers}
import org.midonet.cluster.data.host.Host
import org.midonet.cluster.data.ports.MaterializedRouterPort
import org.midonet.cluster.data.Router
import org.midonet.odp.Datapath
import org.midonet.odp.flows.{FlowAction, FlowActions}
import org.midonet.packets._


@RunWith(classOf[JUnitRunner])
class RouterFlowInvalidationTest extends MidolmanTestCase with VirtualConfigurationBuilders
                       with RouterHelper{

    var eventProbe: TestProbe = null
    var tagEventProbe: TestProbe = null
    var datapath: Datapath = null

    val ipInPort = "10.10.0.1"
    val ipOutPort = "11.11.0.10"
    // this is the network reachable from outPort
    val networkToReach = "11.11.0.0"
    val networkToReachLength = 16
    val ipSource = "20.20.0.20"
    val macInPort = "02:11:22:33:44:10"
    val macOutPort = "02:11:22:33:46:10"

    val macSource = "02:11:22:33:44:11"

    var routeId: UUID = null
    val inPortName = "inPort"
    val outPortName = "outPort"
    var clusterRouter: Router = null
    var host: Host = null
    val ttl: Byte = 17
    var outPort: MaterializedRouterPort = null
    var inPort: MaterializedRouterPort = null
    var mapPortNameShortNumber: Map[String,Short] = new HashMap[String, Short]()



    override def fillConfig(config: HierarchicalConfiguration) = {
        config.setProperty("midolman.midolman_root_key", "/test/v3/midolman")
        //config.setProperty("datapath.max_flow_count", 3)
        //config.setProperty("midolman.check_flow_expiration_interval", 10)
        config.setProperty("midolman.enable_monitoring", "false")
        config.setProperty("cassandra.servers", "localhost:9171")
        config
    }

    override def beforeTest() {
        eventProbe = newProbe()
        tagEventProbe = newProbe()

        drainProbes()
        drainProbe(eventProbe)

        host = newHost("myself", hostId())
        clusterRouter = newRouter("router")
        clusterRouter should not be null

        actors().eventStream.subscribe(eventProbe.ref, classOf[LocalPortActive])
        actors().eventStream.subscribe(tagEventProbe.ref, classOf[RouterInvTrieTagCountModified])
        initializeDatapath() should not be (null)

        flowProbe().expectMsgType[DatapathController.DatapathReady].datapath should not be (null)

        inPort = newExteriorRouterPort(clusterRouter, MAC.fromString(macInPort),
            ipInPort, ipInPort, 32)
        inPort should not be null

        outPort = newExteriorRouterPort(clusterRouter, MAC.fromString(macOutPort),
            ipOutPort, networkToReach, networkToReachLength)

        //requestOfType[WildcardFlowAdded](flowProbe)
        materializePort(inPort, host, inPortName)
        mapPortNameShortNumber += inPortName -> 1
        requestOfType[LocalPortActive](eventProbe)
        materializePort(outPort, host, outPortName)
        mapPortNameShortNumber += outPortName -> 2
        requestOfType[LocalPortActive](eventProbe)

        // this is added when the port becomes active. A flow that takes care of
        // the tunnelled packets to this port
        wflowAddedProbe.expectMsgPF(Duration(3, TimeUnit.SECONDS),
            "WildcardFlowAdded")(TestHelpers.matchActionsFlowAddedOrRemoved(
            mutable.Buffer[FlowAction[_]](FlowActions.output(mapPortNameShortNumber(inPortName)))))
        wflowAddedProbe.expectMsgPF(Duration(3, TimeUnit.SECONDS),
            "WildcardFlowAdded")(TestHelpers.matchActionsFlowAddedOrRemoved(
            mutable.Buffer[FlowAction[_]](FlowActions.output(mapPortNameShortNumber(outPortName)))))

    }

    // Characters of this test
    // inPort: it's the port that receives the inject packets
    // outPort: the port assigned as next hop in the routes
    // ipSource, macSource: the source of the packets we inject, we imagine packets arrive
    // from a remote source
    // ipToReach, macToReach: it's the Ip that is the destination of the injected packets
    def testRouteRemovedInvalidation() {
        drainProbes()

        val ipToReach = "11.11.0.2"
        val macToReach = "02:11:22:33:48:10"
        // add a route from ipSource to ipToReach/32, next hop is outPort
        routeId = newRoute(clusterRouter, ipSource, 32, ipToReach, 32,
            NextHop.PORT, outPort.getId, new IntIPv4(NO_GATEWAY).toString,
            2)
        // packet from ipSource to ipToReach enters from inPort
        triggerPacketIn(inPortName, TestHelpers.createUdpPacket(macSource, ipSource,
            macInPort, ipToReach))
        // we trigger the learning of macToReach
        feedArpCache(outPortName,
            IntIPv4.fromString(ipToReach).addressAsInt,
            MAC.fromString(macToReach),
            IntIPv4.fromString(ipOutPort).addressAsInt,
            MAC.fromString(macOutPort))

        fishForRequestOfType[PacketIn](packetInProbe)
        fishForRequestOfType[PacketIn](packetInProbe)

        fishForRequestOfType[DiscardPacket](discardPacketProbe)

        wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded])
        tagEventProbe.expectMsg(new RouterInvTrieTagCountModified(
            IPv4Addr.fromString(ipToReach), 1))

        // when we delete the routes we expect the flow to be invalidated
        clusterDataClient().routesDelete(routeId)
        wflowRemovedProbe.expectMsgClass(classOf[WildcardFlowRemoved])
        tagEventProbe.expectMsg(new RouterInvTrieTagCountModified(
            IPv4Addr.fromString(ipToReach), 0))

    }

    // We add a route to 11.11.0.0/16 reachable from outPort, we create 3 flows
    // from ipSource to 11.1.1.2
    // from ipSource to 11.1.1.3
    // from ipSource to 11.11.2.4
    // We add a new route to 11.11.1.0/24 and we expect only the flow to 11.1.1.2
    // and 11.1.1.3 to be invalidated because they belong to 11.11.1.0/24
    def testAddRouteInvalidation() {

        val ipVm1 = "11.11.1.2"
        val macVm1 = "11:22:33:44:55:02"
        val ipVm2 = "11.11.1.3"
        val macVm2 = "11:22:33:44:55:03"

        // this vm is on a different sub network
        val ipVm3 = "11.11.2.4"
        val macVm3 = "11:22:33:44:55:04"

        // create a route to the network to reach
        newRoute(clusterRouter, ipSource, 32, networkToReach, networkToReachLength,
            NextHop.PORT, outPort.getId, new IntIPv4(NO_GATEWAY).toString,
            2)
        triggerPacketIn(inPortName, TestHelpers.createUdpPacket(macSource, ipSource, macInPort, ipVm1))
        feedArpCache(outPortName,
            IntIPv4.fromString(ipVm1).addressAsInt,
            MAC.fromString(macVm1),
            IntIPv4.fromString(ipOutPort).addressAsInt,
            MAC.fromString(macOutPort))

        triggerPacketIn(inPortName, TestHelpers.createUdpPacket(macSource, ipSource, macInPort, ipVm2))
        feedArpCache(outPortName,
            IntIPv4.fromString(ipVm2).addressAsInt,
            MAC.fromString(macVm2),
            IntIPv4.fromString(ipOutPort).addressAsInt,
            MAC.fromString(macOutPort))
        triggerPacketIn(inPortName, TestHelpers.createUdpPacket(macSource, ipSource, macInPort, ipVm3))
        feedArpCache(outPortName,
            IntIPv4.fromString(ipVm3).addressAsInt,
            MAC.fromString(macVm3),
            IntIPv4.fromString(ipOutPort).addressAsInt,
            MAC.fromString(macOutPort))
        wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded])
        wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded])
        wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded])


        tagEventProbe.expectMsgClass(classOf[RouterInvTrieTagCountModified])
        tagEventProbe.expectMsgClass(classOf[RouterInvTrieTagCountModified])
        tagEventProbe.expectMsgClass(classOf[RouterInvTrieTagCountModified])

        newRoute(clusterRouter, ipSource, 32, "11.11.1.0", networkToReachLength+8,
            NextHop.PORT, outPort.getId, new IntIPv4(NO_GATEWAY).toString,
            2)

        wflowRemovedProbe.fishForMessage(Duration(3, TimeUnit.SECONDS),
            "WildcardFlowRemoved")(TestHelpers.getMatchFlowRemovedPacketPartialFunction)

        wflowRemovedProbe.fishForMessage(Duration(3, TimeUnit.SECONDS),
            "WildcardFlowRemoved")(TestHelpers.getMatchFlowRemovedPacketPartialFunction)

    }
    // Same scenario of the previous test.
    // We add a route to 11.11.0.0/16 reachable from outPort, we create 2 flows
    // from ipSource to 11.1.1.2
    // from ipSource to 11.1.1.3
    // and other 2
    // from ipSource2 to 11.1.1.2
    // from ipSource2 to 11.1.1.3
    // we delete them and we expect to get the notification of the removal
    // of the correspondent tag
    def testCleanUpInvalidationTrie() {
        val ipVm1 = "11.11.1.2"
        val macVm1 = "11:22:33:44:55:02"
        val ipVm2 = "11.11.1.3"
        val macVm2 = "11:22:33:44:55:03"

        val ipVm1AsInt = IntIPv4.fromString(ipVm1).addressAsInt()
        val ipVm2AsInt = IntIPv4.fromString(ipVm2).addressAsInt()

        // create a route to the network to reach
        newRoute(clusterRouter, "0.0.0.0", 0, networkToReach, networkToReachLength,
            NextHop.PORT, outPort.getId, new IntIPv4(NO_GATEWAY).toString,
            2)
        triggerPacketIn(inPortName, TestHelpers.createUdpPacket(macSource, ipSource, macInPort, ipVm1))
        feedArpCache(outPortName,
            ipVm1AsInt,
            MAC.fromString(macVm1),
            IntIPv4.fromString(ipOutPort).addressAsInt,
            MAC.fromString(macOutPort))

        val flowTag1 = wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded])
        tagEventProbe.expectMsg(new RouterInvTrieTagCountModified(
                new IPv4Addr().setIntAddress(ipVm1AsInt), 1))

        triggerPacketIn(inPortName, TestHelpers.createUdpPacket(macSource, ipSource, macInPort, ipVm2))
        feedArpCache(outPortName,
            ipVm2AsInt,
            MAC.fromString(macVm2),
            IntIPv4.fromString(ipOutPort).addressAsInt,
            MAC.fromString(macOutPort))

        val flowTag2 = wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded])
        tagEventProbe.expectMsg(new RouterInvTrieTagCountModified(
                new IPv4Addr().setIntAddress(ipVm2AsInt), 1))


        // delete one flow for tag vmIp1, check that the corresponding tag gets removed
        flowProbe().testActor ! new RemoveWildcardFlow(flowTag1.f)
        wflowRemovedProbe.expectMsgClass(classOf[WildcardFlowRemoved])
        tagEventProbe.expectMsg(new RouterInvTrieTagCountModified(
                new IPv4Addr().setIntAddress(ipVm1AsInt), 0))

        val ipSource2 = "20.20.0.40"

        // create another flow for tag ipVm1
        triggerPacketIn(inPortName, TestHelpers.createUdpPacket(macSource, ipSource2, macInPort, ipVm1))
        wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded])
        tagEventProbe.expectMsg(new RouterInvTrieTagCountModified(
                new IPv4Addr().setIntAddress(ipVm1AsInt), 1))

        // create another flow for tag ipVm2
        triggerPacketIn(inPortName, TestHelpers.createUdpPacket(macSource, ipSource2, macInPort, ipVm2))
        val flow2Tag2 = wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded])
        tagEventProbe.expectMsg(new RouterInvTrieTagCountModified(
                new IPv4Addr().setIntAddress(ipVm2AsInt), 2))

        // remove 1 flow for tag ipVm2
        flowProbe().testActor ! new RemoveWildcardFlow(flowTag2.f)
        wflowRemovedProbe.expectMsgClass(classOf[WildcardFlowRemoved])
        tagEventProbe.expectMsg(new RouterInvTrieTagCountModified(
                new IPv4Addr().setIntAddress(ipVm2AsInt), 1))

        // remove the remaining flow for ipVm2
        flowProbe().testActor ! new RemoveWildcardFlow(flow2Tag2.f)
        wflowRemovedProbe.expectMsgClass(classOf[WildcardFlowRemoved])
        tagEventProbe.expectMsg(new RouterInvTrieTagCountModified(
                new IPv4Addr().setIntAddress(ipVm2AsInt), 0))

    }

    def testArpTableUpdateTest() {
        drainProbes()

        val ipToReach = "11.11.0.2"
        val firstMac = "02:11:22:33:48:10"
        val secondMac = "02:11:44:66:96:20"
        // add a route from ipSource to ipToReach/32, next hop is outPort
        routeId = newRoute(clusterRouter, ipSource, 32, ipToReach, 32,
            NextHop.PORT, outPort.getId, new IntIPv4(NO_GATEWAY).toString,
            2)
        // packet from ipSource to ipToReach enters from inPort
        triggerPacketIn(inPortName, TestHelpers.createUdpPacket(macSource, ipSource,
            macInPort, ipToReach))
        // we trigger the learning of firstMac
        feedArpCache(outPortName,
            IntIPv4.fromString(ipToReach).addressAsInt,
            MAC.fromString(firstMac),
            IntIPv4.fromString(ipOutPort).addressAsInt,
            MAC.fromString(macOutPort))

        fishForRequestOfType[PacketIn](packetInProbe)
        fishForRequestOfType[PacketIn](packetInProbe)

        fishForRequestOfType[DiscardPacket](discardPacketProbe)
        wflowAddedProbe.expectMsgClass(classOf[WildcardFlowAdded])

        // we trigger the learning of firstMac
        feedArpCache(outPortName,
            IntIPv4.fromString(ipToReach).addressAsInt,
            MAC.fromString(secondMac),
            IntIPv4.fromString(ipOutPort).addressAsInt,
            MAC.fromString(macOutPort))

        // when we update the ARP table we expect the flow to be invalidated
        requestOfType[InvalidateFlowsByTag](flowProbe())
        wflowRemovedProbe.expectMsgClass(classOf[WildcardFlowRemoved])
    }
}