/*
* Copyright 2013-2014 Midokura Europe SARL
*/
package org.midonet.midolman.simulation

import scala.concurrent.Await
import scala.concurrent.duration._

import akka.util.Timeout
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import org.midonet.cache.MockCache
import org.midonet.cluster.data.l4lb
import org.midonet.cluster.data.ports.RouterPort
import org.midonet.cluster.data.{Router => ClusterRouter, Entity, Port}
import org.midonet.midolman.PacketWorkflow.{AddVirtualWildcardFlow, SendPacket, SimulationResult}
import org.midonet.midolman._
import org.midonet.midolman.layer3.Route
import org.midonet.midolman.services.MessageAccumulator
import org.midonet.midolman.topology.{FlowTagger, VirtualTopologyActor}
import org.midonet.packets._
import org.midonet.packets.util.PacketBuilder._
import org.midonet.sdn.flows.WildcardMatch
import org.midonet.odp.flows.{FlowKeyIPv4, FlowActionSetKey}
import scala.collection.mutable

@RunWith(classOf[JUnitRunner])
class PoolTest extends FeatureSpec
with Matchers
with GivenWhenThen
with CustomMatchers
with MockMidolmanActors
with VirtualConfigurationBuilders
with MidolmanServices
with VirtualTopologyHelper
with OneInstancePerTest {

    implicit val askTimeout: Timeout = 1 second

    override def registerActors = List(
        VirtualTopologyActor -> (() => new VirtualTopologyActor
            with MessageAccumulator)
    )

    /*
     * The topology for this test consists of one router with one port
     * for the external client, and three backend ports for pool members.
     */

    val numBackends = 3

    val vipIp = new IPv4Subnet("200.200.200.200", 32)
    val badIp = new IPv4Subnet("111.111.111.111", 32)

    val vipPort: Short = 22
    val clientSrcPort: Short = 5000

    val ipClientSide = new IPv4Subnet("100.100.100.64", 24)
    val ipsBackendSide = (0 until numBackends) map {n => new IPv4Subnet(s"10.0.$n.1", 24)}

    val macClientSide = MAC.random
    val macsBackendSide = (0 until numBackends) map {n => MAC.random}

    val routerClientExteriorPortIp = new IPv4Subnet("100.100.100.254", 24)
    val routerBackendExteriorPortIps = (0 until numBackends) map {
        n => new IPv4Subnet(s"10.0.$n.254", 24)}

    var router: ClusterRouter = _
    var loadBalancer: l4lb.LoadBalancer = _
    var poolMembers: Seq[l4lb.PoolMember] = _
    var exteriorClientPort: RouterPort = _
    var exteriorBackendPorts: Seq[RouterPort] = _

    lazy val fromClientToVip = (exteriorClientPort, clientToVipPkt)
    lazy val fromClientToBadIp = (exteriorClientPort, clientToBadIpPkt)

    lazy val clientToVipPkt: Ethernet = clientToVipPkt(clientSrcPort)

    lazy val clientToBadIpPkt: Ethernet =
        { eth src macClientSide dst exteriorClientPort.getHwAddr } <<
            { ip4 src ipClientSide.toUnicastString dst badIp.toUnicastString } <<
            { tcp src clientSrcPort dst vipPort }

    lazy val fromBackendToClient = (0 until numBackends) map {
        n => (exteriorBackendPorts(n), backendToClientPkt(n))}
    lazy val backendToClientPkt: Seq[Ethernet] = (0 until numBackends) map {
        n => ({ eth src macsBackendSide(n) dst exteriorBackendPorts(n).getHwAddr } <<
            { ip4 src ipsBackendSide(n).toUnicastString dst ipClientSide.toUnicastString } <<
            { tcp src vipPort dst clientSrcPort }).packet
    }

    lazy val nattedToBackendPkt: Seq[Ethernet] = (0 until numBackends) map {
        n => ({ eth src exteriorBackendPorts(n).getHwAddr dst macsBackendSide(n) } <<
            { ip4 src ipClientSide.toUnicastString dst ipsBackendSide(n).toUnicastString }).packet
    }
    lazy val responseToClientPkt: Ethernet =
        { eth src exteriorClientPort.getHwAddr dst macClientSide } <<
            { ip4 src vipIp.toUnicastString dst ipClientSide.toUnicastString }

    override def beforeTest() {
        newHost("myself", hostId)

        router = newRouter("router0")

        exteriorClientPort = newRouterPort(router,
            MAC.random(),
            routerClientExteriorPortIp.toUnicastString,
            routerClientExteriorPortIp.toNetworkAddress.toString,
            routerClientExteriorPortIp.getPrefixLen)

        exteriorBackendPorts = (0 until numBackends) map {
            n => newRouterPort(router,
                MAC.random(),
                routerBackendExteriorPortIps(n).toUnicastString,
                routerBackendExteriorPortIps(n).toNetworkAddress.toString,
                routerBackendExteriorPortIps(n).getPrefixLen)
        }

        // Materialize ports
        materializePort(exteriorClientPort, hostId, "portClient")
        (0 until numBackends) foreach {
            n => materializePort(exteriorBackendPorts(n), hostId, s"port$n)")
        }

        // Set up routes
        newRoute(router,
            "0.0.0.0", 0,
            ipClientSide.toUnicastString, ipClientSide.getPrefixLen,
            Route.NextHop.PORT, exteriorClientPort.getId,
            new IPv4Addr(Route.NO_GATEWAY).toString, 10)

        (0 until numBackends) foreach {
            n => newRoute(router,
                "0.0.0.0", 0,
                ipsBackendSide(n).toUnicastString, ipsBackendSide(n).getPrefixLen,
                Route.NextHop.PORT, exteriorBackendPorts(n).getId,
                new IPv4Addr(Route.NO_GATEWAY).toString, 10)
        }

        // Create loadbalancer topology
        loadBalancer = createLoadBalancer()
        setLoadBalancerOnRouter(loadBalancer, router)
        loadBalancer.setRouterId(router.getId)
        val vip = createVipOnLoadBalancer(loadBalancer, vipIp.toUnicastString, vipPort)
        val pool = createPool()
        setVipPool(vip, pool)
        poolMembers = (0 until numBackends) map {
            n => createPoolMember(pool, ipsBackendSide(n).toUnicastString,
                vipPort)
        }

        // Set all but one pool member down
        (1 until numBackends) foreach {
            n => setPoolMemberAdminStateUp(poolMembers(n), false)
        }

        // Load topology
        val itemsToLoad: List[Entity.Base[_,_,_]] =
            router :: exteriorClientPort :: exteriorBackendPorts.toList
        val topo = fetchTopologyList(itemsToLoad)

        // Seed the ARP table
        val arpTable = topo.collect({ case r: Router => r.arpTable}).head
        arpTable.set(ipClientSide.getAddress, macClientSide)
        (0 until numBackends) foreach {
            n => arpTable.set(ipsBackendSide(n).getAddress, macsBackendSide(n))
        }
    }

    feature("When loadbalancer is admin state down, behaves as if no loadbalancer present") {
        scenario("Packets to unknown IPs get dropped") {
            Given("loadbalancer set to admin state down")

            setLoadBalancerDown(loadBalancer)

            When("a packet is sent to unknown IP")

            val flow = sendPacket (fromClientToBadIp)

            Then("a drop flow should be installed")

            flow should be (dropped {FlowTagger.invalidateFlowsByDevice(router.getId)})
        }

        scenario("Packets to VIP gets dropped when no loadbalancer") {
            Given("loadbalancer set to admin state down")

            setLoadBalancerDown(loadBalancer)

            When("a packet is sent to VIP")

            val flow = sendPacket (fromClientToVip)

            Then("a drop flow should be installed")

            flow should be (dropped {FlowTagger.invalidateFlowsByDevice(router.getId)})
        }
    }

    feature("When all pool members are in admin state down, behaves as if no loadbalancer present") {
        scenario("Packets to unknown IPs get dropped") {
            Given("active pool member set to admin state down")

            setPoolMemberAdminStateUp(poolMembers(0), false)

            When("a packet is sent to unknown IP")

            val flow = sendPacket (fromClientToBadIp)

            Then("a drop flow should be installed")

            flow should be (dropped {FlowTagger.invalidateFlowsByDevice(router.getId)})
        }

        scenario("Packets to VIP gets dropped") {
            Given("active pool member set to admin state down")

            setPoolMemberAdminStateUp(poolMembers(0), false)

            When("a packet is sent to VIP")

            val flow = sendPacket (fromClientToVip)

            Then("a drop flow should be installed")

            flow should be (dropped {FlowTagger.invalidateFlowsByDevice(router.getId)})
        }
    }

    feature("With one backend, traffic is loadbalanced to that backend") {

        scenario("Packets to VIP get loadbalanced to one backend") {
            Given("only one pool member up")

            When("a packet is sent to VIP")

            val flow = sendPacket (fromClientToVip)

            Then("packet should be sent to out port of the one backend")

            flow should be (toPort(exteriorBackendPorts(0).getId) {
                FlowTagger.invalidateFlowsByDevice(router.getId)})

            And("Should be NATted correctly")

            flow should be (flowMatching (nattedToBackendPkt(0)))

            Then("Backend sends a return packet to the client")

            val returnFlow = sendPacket (fromBackendToClient(0))

            Then("packet should be sent to out port of the client")

            returnFlow should be (toPort(exteriorClientPort.getId) {
                FlowTagger.invalidateFlowsByDevice(router.getId)})

            And("Should be reverse NATted correctly")

            returnFlow should be (flowMatching (responseToClientPkt))
        }

        scenario("Packets to VIP get loadbalanced to different backend") {
            Given("different pool member up")
            setPoolMemberAdminStateUp(poolMembers(0), false)
            setPoolMemberAdminStateUp(poolMembers(1), true)

            When("a packet is sent to VIP")

            val flow = sendPacket (fromClientToVip)

            Then("packet should be sent to out port of the one available backend")

            flow should be (toPort(exteriorBackendPorts(1).getId) {
                FlowTagger.invalidateFlowsByDevice(router.getId)})

            And("Should be NATted correctly")

            flow should be (flowMatching (nattedToBackendPkt(1)))

            Then("Backend sends a return packet to the client")

            val returnFlow = sendPacket (fromBackendToClient(1))

            Then("packet should be sent to out port of the client")

            returnFlow should be (toPort(exteriorClientPort.getId) {
                FlowTagger.invalidateFlowsByDevice(router.getId)})

            And("Should be reverse NATted correctly")

            returnFlow should be (flowMatching (responseToClientPkt))
        }
    }

    feature("Weighted selection of pool members") {
        scenario("Pool with all members having weight 0 should be inactive") {
            Given("a pool with all members up and having weight 0")
            poolMembers.foreach(updatePoolMember(_, adminStateUp = Some(true),
                                                    weight = Some(0)))

             When("a packet is sent to VIP")
             val flow = sendPacket (fromClientToVip)

             Then("a drop flow should be installed")
             flow should be (dropped {FlowTagger.invalidateFlowsByDevice(router.getId)})
        }

        scenario("Pool balances members with different weights correctly") {
            Given("a pool with members of weights 1, 2, and 4")
            for (i <- 0 until numBackends) {
                updatePoolMember(poolMembers(i), adminStateUp = Some(true),
                                 weight = Some(math.pow(2, i).toInt))
            }

            val dstCounts = Array.fill(numBackends)(0)
            for (i <- 1 to 1000) {
                val srcPort = (10000 + i).toShort
                val flow = sendPacket(
                    (exteriorClientPort, clientToVipPkt(srcPort)))
                val wc = flow.asInstanceOf[AddVirtualWildcardFlow]
                val action = wc.flow.actions(1).asInstanceOf[FlowActionSetKey]
                val ip = action.getFlowKey.asInstanceOf[FlowKeyIPv4].getDst
                val index = (ip & 0xff00) >> 8
                dstCounts(index) += 1
            }

            // Due to the amount of time it takes to send a packet, we
            // can't get a very big sample, so tolerances have to be
            // wide to avoid spurious failures.
            //
            // These tolerances assume 3 backends and give a one-in-a-million
            // chance of spurious failure.
            numBackends shouldBe 3
            val acceptableRanges = Array((93, 198), (219, 355), (497, 645))
            for (i <- 0 until numBackends) {
                val range = acceptableRanges(i)
                dstCounts(i) should (be > range._1 and be < range._2)
            }
        }
    }

    private[this] def sendPacket(t: (Port[_,_], Ethernet)): SimulationResult =
        Await.result(new Coordinator(
            makeWMatch(t._1, t._2),
            t._2,
            Some(1),
            None,
            0,
            new MockCache(),
            new MockCache(),
            new MockCache(),
            None,
            Nil)
            .simulate(), Duration.Inf)

    private[this] def makeWMatch(port: Port[_,_], pkt: Ethernet) =
        WildcardMatch.fromEthernetPacket(pkt)
            .setInputPortUUID(port.getId)

    private def clientToVipPkt(srcTpPort: Short): Ethernet =
        { eth src macClientSide dst exteriorClientPort.getHwAddr } <<
                { ip4 src ipClientSide.toUnicastString dst vipIp.toUnicastString } <<
                { tcp src srcTpPort dst vipPort }
}