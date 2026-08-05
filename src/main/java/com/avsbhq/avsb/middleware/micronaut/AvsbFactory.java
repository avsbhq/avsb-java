package com.avsbhq.avsb.middleware.micronaut;

import com.avsbhq.avsb.AvsbServer;
import com.avsbhq.avsb.Logger;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

/**
 * Micronaut factory that exposes an {@link AvsbServer} singleton bean.
 *
 * <p>Configure in {@code application.yml}. Only the key is required: the datafile URL is
 * derived from it through the endpoint contract.
 * <pre>
 * avsb:
 *   sdk-key: sdk_production_ttqm0eaj4vth1krcb2xn
 *   # Optional, for a self-hosted proxy or local development:
 *   datafile-url: https://cdn.avsb.cloud/sdk_production_ttqm0eaj4vth1krcb2xn/datafile.json
 * </pre>
 */
@Factory
public class AvsbFactory {

    @Singleton
    public AvsbServer avsbServer(
            @Value("${avsb.sdk-key}") String sdkKey,
            @Value("${avsb.datafile-url:}") String datafileUrl
    ) {
        AvsbServer.ServerBuilder builder = AvsbServer.builder(sdkKey).logger(Logger.console());
        if (datafileUrl != null && !datafileUrl.isBlank()) {
            builder.datafileUrl(datafileUrl);
        }
        AvsbServer server = builder.build();
        server.init();
        return server;
    }
}
