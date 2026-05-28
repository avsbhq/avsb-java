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
 * <p>Configure in {@code application.properties}:
 * <pre>
 * avsb.sdk-key=sdk-my-key
 * avsb.datafile-url=https://cdn.avsb.cloud/datafiles/sdk-my-key.json
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
