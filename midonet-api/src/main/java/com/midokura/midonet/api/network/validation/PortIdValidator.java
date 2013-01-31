/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.api.network.validation;

import com.google.inject.Inject;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midonet.cluster.DataClient;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;
import java.util.UUID;

public class PortIdValidator implements
        ConstraintValidator<IsValidPortId, UUID> {

    private final DataClient dataClient;

    @Inject
    public PortIdValidator(DataClient dataClient) {
        this.dataClient = dataClient;
    }

    @Override
    public void initialize(IsValidPortId constraintAnnotation) {
    }

    @Override
    public boolean isValid(UUID value, ConstraintValidatorContext context) {

        if (value == null) {
            return false;
        }

        try {
            return dataClient.portsExists(value);
        } catch (StateAccessException e) {
            throw new RuntimeException("Error while validation port");
        }

    }
}