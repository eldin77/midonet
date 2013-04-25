/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package org.midonet.midolman.topology.builders

import akka.actor.ActorRef
import collection.mutable
import collection.mutable.{Map => MMap}
import java.util.{Map => JMap, UUID}

import org.slf4j.LoggerFactory

import org.midonet.cluster.client.{Ip4MacMap, BridgeBuilder, MacLearningTable,
    SourceNatResource}
import org.midonet.midolman.FlowController
import org.midonet.midolman.topology.{BridgeConfig, BridgeManager, FlowTagger}
import org.midonet.packets.{IPAddr, MAC}
import org.midonet.util.functors.Callback3


/**
 *  This class will be called by the Client to notify
 *  updates in the bridge configuration. Since it will be executed
 *  by the Client's thread, this class mustn't pass
 *  any modifiable object to the BridgeManager, otherwise it would
 *  break the actors model
 */

class BridgeBuilderImpl(val id: UUID, val flowController: ActorRef,
                        val bridgeMgr: ActorRef) extends BridgeBuilder {
    final val log =
        LoggerFactory.getLogger(classOf[BridgeBuilderImpl])

    private var cfg = new BridgeConfig
    private var macPortMap: MacLearningTable = null
    private var ip4MacMap: Ip4MacMap = null
    private var rtrMacToLogicalPortId: MMap[MAC, UUID] = null
    private var rtrIpToMac: MMap[IPAddr, MAC] = null


    def setTunnelKey(key: Long) {
        cfg = cfg.copy(tunnelKey = key.toInt)
    }

    def setMacLearningTable(table: MacLearningTable) {
        // check if we should overwrite it
        if (table != null) {
            macPortMap = table
            macPortMap.notify(new MacTableNotifyCallBack)
        }
    }

    override def setIp4MacMap(m: Ip4MacMap) {
        if (m != null) {
            ip4MacMap = m
            // TODO(pino): set the notification callback on this map?
        }
    }

    def setSourceNatResource(resource: SourceNatResource) {}

    def setID(id: UUID) = null //useless TODO(ross): delete it

    def setInFilter(filterID: UUID) = {
        cfg = cfg.copy(inboundFilter = filterID)
        this
    }

    def setOutFilter(filterID: UUID) = {
        cfg = cfg.copy(outboundFilter = filterID)
        this
    }

    def start() = null

    def setLogicalPortsMap(newRtrMacToLogicalPortId: JMap[MAC, UUID],
                           newRtrIpToMac: JMap[IPAddr, MAC]) {
        import collection.JavaConversions._
        log.debug("Diffing the maps.")
        // calculate diff between the 2 maps
        if (null != rtrMacToLogicalPortId) {
            val deletedPortMap =
                rtrMacToLogicalPortId -- (newRtrMacToLogicalPortId.keys)
            // invalidate all the Unicast flows to the logical port
            for ((mac, portId) <- deletedPortMap) {
                flowController !
                    FlowController.InvalidateFlowsByTag(
                        FlowTagger.invalidateFlowsByLogicalPort(id, portId))
            }
            // 1. Invalidate all arp requests
            // 2. Invalidate all flooded flows to the router port's specific MAC
            // We don't expect MAC migration in this case otherwise we'd be
            // invalidating Unicast flows to the router port's MAC
            val addedPortMap = newRtrMacToLogicalPortId -- rtrMacToLogicalPortId.keys
            if (addedPortMap.size > 0)
                flowController !
                FlowController.InvalidateFlowsByTag(
                FlowTagger.invalidateArpRequests(id))
            for ((mac, portId) <- addedPortMap) {
                flowController !
                    FlowController.InvalidateFlowsByTag(
                        FlowTagger.invalidateFloodedFlowsByDstMac(id, mac))
            }
        }
        rtrMacToLogicalPortId = newRtrMacToLogicalPortId
        rtrIpToMac = newRtrIpToMac
    }

    def build() {
        log.debug("Building the bridge for {}", id)
        // send messages to the BridgeManager
        // Convert the mutable map to immutable
        bridgeMgr ! BridgeManager.TriggerUpdate(cfg, macPortMap, ip4MacMap,
            collection.immutable.HashMap(rtrMacToLogicalPortId.toSeq: _*),
            collection.immutable.HashMap(rtrIpToMac.toSeq: _*))
    }

    private class MacTableNotifyCallBack extends Callback3[MAC, UUID, UUID] {
        final val log =
            LoggerFactory.getLogger(classOf[MacTableNotifyCallBack])

        def call(mac: MAC, oldPort: UUID, newPort: UUID) {
            log.debug("Mac-Port mapping for MAC {} was updated from {} to {}",
                Array(mac, oldPort, newPort))

            //1. MAC was removed from port
            if (newPort == null && oldPort != null) {
                flowController ! FlowController.InvalidateFlowsByTag(
                    FlowTagger.invalidateFlowsByPort(id, mac, oldPort))
            }
            //2. MAC moved from port-x to port-y
            if (newPort != null && oldPort != null && !newPort.equals(oldPort)) {
                flowController ! FlowController.InvalidateFlowsByTag(
                    FlowTagger.invalidateFlowsByPort(id, mac, oldPort))
            }
            //3. MAC was added -> invalidate flooded flows. Now we have the MAC
            // entry in the table so we can deliver it to the proper port instead
            // of flooding it.
            // As regards broadcast or arp requests:
            //   1. If this port was just added to the PortSet, the invalidation
            //    will occur in the VirtualToPhysicalMapper.
            //   2. If we just forgot the MAC port association, no need of invalidating,
            //    the port was in the PortSet, broadcast and ARP requests were
            //    correctly delivered.
            if (newPort != null && oldPort == null){
                flowController ! FlowController.InvalidateFlowsByTag(
                    FlowTagger.invalidateFloodedFlowsByDstMac(id, mac)
                )
            }
        }
    }

}
