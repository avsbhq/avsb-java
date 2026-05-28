package com.avsbhq.avsb.middleware.spring;

import com.avsbhq.avsb.AvsbServer;
import com.avsbhq.avsb.Logger;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot 3 auto-configuration for the A vs B SDK.
 *
 * <p>Registers an {@link AvsbServer} singleton bean when {@code avsb.sdk-key}
 * is set. The bean is skipped when the application already declares its own
 * {@link AvsbServer} bean.
 *
 * <p>Registered via
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * for zero-config Spring Boot 3 discovery.
 */
@AutoConfiguration
@EnableConfigurationProperties(AvsbProperties.class)
@ConditionalOnProperty(prefix = "avsb", name = "sdk-key")
public class AvsbAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AvsbServer avsbServer(AvsbProperties props) {
        AvsbServer.ServerBuilder builder = AvsbServer.builder(props.getSdkKey())
                .logger(Logger.console())
                .fetchTimeout(props.getFetchTimeout());
        if (props.getDatafileUrl() != null) {
            builder.datafileUrl(props.getDatafileUrl());
        }
        AvsbServer server = builder.build();
        server.init();
        return server;
    }
}
