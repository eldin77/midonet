/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.guice;

import com.google.inject.Inject;
import com.google.inject.PrivateModule;
import com.google.inject.Provider;

import org.midonet.config.ConfigProvider;
import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.services.DatapathConnectionService;
import org.midonet.midolman.services.MidolmanActorsService;
import org.midonet.midolman.services.MidolmanService;
import org.midonet.midolman.simulation.Chain;
import org.midonet.cluster.Client;

/**
 * Main midolman configuration module
 */
public class MidolmanModule extends PrivateModule {

    @Override
    protected void configure() {
        binder().requireExplicitBindings();

        requireBinding(ConfigProvider.class);
        requireBinding(Client.class);
        requireBinding(DatapathConnectionService.class);
        requireBinding(MidolmanActorsService.class);

        bind(MidolmanService.class).asEagerSingleton();
        expose(MidolmanService.class);

        bind(MidolmanConfig.class)
            .toProvider(MidolmanConfigProvider.class)
            .asEagerSingleton();
        expose(MidolmanConfig.class);

        requestStaticInjection(Chain.class);

    }

    /**
     * A {@link Provider} of {@link MidolmanConfig} instances which uses an
     * existing {@link ConfigProvider} as the configuration backend.
     */
    public static class MidolmanConfigProvider
        implements Provider<MidolmanConfig> {
        @Inject
        ConfigProvider configProvider;

        @Override
        public MidolmanConfig get() {
            return configProvider.getConfig(MidolmanConfig.class);
        }
    }

}