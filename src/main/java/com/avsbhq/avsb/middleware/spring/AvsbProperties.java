package com.avsbhq.avsb.middleware.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Spring Boot configuration properties for the A vs B SDK.
 *
 * <p>Configure in {@code application.properties} or {@code application.yml}. Only the key is
 * required: the datafile URL is derived from it through the endpoint contract.
 * <pre>
 * avsb.sdk-key=sdk_production_ttqm0eaj4vth1krcb2xn
 * # Optional, for a self-hosted proxy or local development:
 * avsb.datafile-url=https://cdn.avsb.cloud/sdk_production_ttqm0eaj4vth1krcb2xn/datafile.json
 * avsb.fetch-timeout=10s
 * </pre>
 */
@ConfigurationProperties(prefix = "avsb")
public class AvsbProperties {

    /** Required: your environment SDK key. */
    private String sdkKey;

    /** Optional: override the datafile URL. Defaults to the CDN URL for the key. */
    private String datafileUrl;

    /** Optional: HTTP fetch timeout. Defaults to 10 seconds. */
    private Duration fetchTimeout = Duration.ofSeconds(10);

    /** Optional: if true, logs evaluation details at DEBUG level. */
    private boolean debugEvaluation = false;

    public String getSdkKey() { return sdkKey; }
    public void setSdkKey(String sdkKey) { this.sdkKey = sdkKey; }

    public String getDatafileUrl() { return datafileUrl; }
    public void setDatafileUrl(String datafileUrl) { this.datafileUrl = datafileUrl; }

    public Duration getFetchTimeout() { return fetchTimeout; }
    public void setFetchTimeout(Duration fetchTimeout) { this.fetchTimeout = fetchTimeout; }

    public boolean isDebugEvaluation() { return debugEvaluation; }
    public void setDebugEvaluation(boolean debugEvaluation) { this.debugEvaluation = debugEvaluation; }
}
