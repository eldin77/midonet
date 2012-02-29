/*
 * @(#)TestCorsHeaders        1.6 12/3/27
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_TENANT_JSON;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;


public class TestCorsHeaders extends JerseyTest {

    private final static Logger log =
        LoggerFactory.getLogger(TestCorsHeaders.class);

    private WebResource resource;
    private ClientResponse response;


    public TestCorsHeaders() {
        super(FuncTest.appDesc);
    }

    @Test
    public void testCorsHeaders() {
        resource = resource().path("tenants");
        // Test OPTIONS method returns expected response headers.
        response = resource.options(ClientResponse.class);
        Map<String, List<String>> headers = response.getHeaders();
        List<String> origin = headers.get("Access-Control-Allow-Origin");
        log.debug("Access-Control-Allow-Origin: {}", origin);
        assertTrue(origin.contains("*"));
        String allowHeaders = headers.get("Access-Control-Allow-Headers")
                                     .get(0);
        log.debug("Access-Control-Allow-Headers: {}", allowHeaders);
        assertTrue(allowHeaders.contains("Origin"));
        assertTrue(allowHeaders.contains("HTTP_X_AUTH_TOKEN"));
        assertTrue(allowHeaders.contains("Content-Type"));
        assertTrue(allowHeaders.contains("Accept"));
        String allowMethods = headers.get("Access-Control-Allow-Methods")
                                     .get(0);
        assertTrue(allowMethods.contains("GET"));
        assertTrue(allowMethods.contains("POST"));
        assertTrue(allowMethods.contains("PUT"));
        assertTrue(allowMethods.contains("DELETE"));
        assertTrue(allowMethods.contains("OPTIONS"));
        String exposeHeaders = headers.get("Access-Control-Expose-Headers")
                                      .get(0);
        assertTrue(exposeHeaders.contains("Location"));

        // Test GET method returns expected response headers.
        response = resource.type(APPLICATION_TENANT_JSON)
                           .get(ClientResponse.class);
        String body = response.getEntity(String.class);
        log.debug("status: {}", response.getStatus());
        log.debug("body: {}", body);
        assertEquals(200, response.getStatus());
        headers = response.getHeaders();
        origin = headers.get("Access-Control-Allow-Origin");
        log.debug("Access-Control-Allow-Origin: {}", origin);
        assertTrue(origin.contains("*"));
        allowMethods = headers.get("Access-Control-Allow-Methods").get(0);
        assertTrue(allowMethods.contains("GET"));
        assertTrue(allowMethods.contains("POST"));
        assertTrue(allowMethods.contains("PUT"));
        assertTrue(allowMethods.contains("DELETE"));
        assertTrue(allowMethods.contains("OPTIONS"));
        exposeHeaders = headers.get("Access-Control-Expose-Headers").get(0);
        assertTrue(exposeHeaders.contains("Location"));
    }
}
