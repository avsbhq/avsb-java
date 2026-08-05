package com.avsbhq.avsb.middleware.quarkus;

import com.avsbhq.avsb.AvsbServer;
import com.avsbhq.avsb.Logger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Quarkus CDI producer that exposes an {@link AvsbServer} application-scoped bean.
 *
 * <p>Configure in {@code application.properties}. Only the key is required: the datafile URL
 * is derived from it through the endpoint contract.
 * <pre>
 * avsb.sdk-key=sdk_production_ttqm0eaj4vth1krcb2xn
 * # Optional, for a self-hosted proxy or local development:
 * avsb.datafile-url=https://cdn.avsb.cloud/sdk_production_ttqm0eaj4vth1krcb2xn/datafile.json
 * </pre>
 */
@ApplicationScoped
public class AvsbProducer {

    @ConfigProperty(name = "avsb.sdk-key")
    String sdkKey;

    @ConfigProperty(name = "avsb.datafile-url", defaultValue = "")
    String datafileUrl;

    @Produces
    @ApplicationScoped
    public AvsbServer avsbServer() {
        AvsbServer.ServerBuilder builder = AvsbServer.builder(sdkKey).logger(Logger.console());
        if (datafileUrl != null && !datafileUrl.isBlank()) {
            builder.datafileUrl(datafileUrl);
        }
        AvsbServer server = builder.build();
        server.init();
        return server;
    }
}
