/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.cluster.client;

import java.util.Map;
import java.util.UUID;

import org.midonet.packets.IPAddr;
import org.midonet.packets.MAC;

public interface BridgeBuilder extends ForwardingElementBuilder {
    void setTunnelKey(long key);
    void setMacLearningTable(MacLearningTable table);
    void setIp4MacMap(Ip4MacMap m);
    void setLogicalPortsMap(Map<MAC, UUID> rtrMacToLogicalPortId,
                            Map<IPAddr, MAC> rtrIpToMac);
}