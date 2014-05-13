/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron;

import org.midonet.cluster.DataClient;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.zkManagers.BridgeZkManager;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.UUID;


public interface NeutronPlugin extends DataClient {

    /**
     * Create a new network data in the data store.  StatePathExistsException
     * thrown if the network with the same ID already exists.
     *
     * @param network Network object to create
     * @return Created Network object
     */
    public Network createNetwork(@Nonnull Network network)
            throws StateAccessException, SerializationException;

    /**
     * Delete a network.  Nothing happens if the resource does not exist.
     *
     * @param id ID of the Network object to delete
     */
    public void deleteNetwork(@Nonnull UUID id)
            throws StateAccessException, SerializationException;

    /**
     * Retrieve a network.  Returns null if the resource does not exist.
     *
     * @param id ID of the Network object to delete
     * @return Network object
     */
    public Network getNetwork(@Nonnull UUID id)
            throws StateAccessException, SerializationException;

    /**
     * Get all the networks.
     *
     * @return List of Network objects.
     */
    public List<Network> getNetworks()
            throws StateAccessException, SerializationException;

    /**
     * Update a network.  NoStatePathException is thrown if the resource does
     * not exist.
     *
     * @param id ID of the Network object to update
     * @return Updated Network object
     */
    public Network updateNetwork(@Nonnull UUID id, @Nonnull Network network)
            throws StateAccessException, SerializationException,
            BridgeZkManager.VxLanPortIdUpdateException;

    /**
     * Create a new subnet data in the data store.  StatePathExistsException
     * thrown if the subnet with the same ID already exists.
     *
     * @param subnet Network object to create
     * @return Created Subnet object
     */
    public Subnet createSubnet(@Nonnull Subnet subnet)
            throws StateAccessException, SerializationException;

    /**
     * Delete a subnet.  Nothing happens if the resource does not exist.
     *
     * @param id ID of the Subnet object to delete
     */
    public void deleteSubnet(@Nonnull UUID id)
            throws StateAccessException, SerializationException;

    /**
     * Retrieve a subnet.  Returns null if the resource does not exist.
     *
     * @param id ID of the Subnet object to delete
     * @return Subnet object
     */
    public Subnet getSubnet(@Nonnull UUID id)
            throws StateAccessException, SerializationException;

    /**
     * Get all the subnets.
     *
     * @return List of Subnet objects.
     */
    public List<Subnet> getSubnets()
            throws StateAccessException, SerializationException;

    /**
     * Update a subnet.  NoStatePathException is thrown if the resource does
     * not exist.
     *
     * @param id ID of the Subnet object to update
     * @return Updated Subnet object
     */
    public Subnet updateSubnet(@Nonnull UUID id, @Nonnull Subnet subnet)
            throws StateAccessException, SerializationException;

}