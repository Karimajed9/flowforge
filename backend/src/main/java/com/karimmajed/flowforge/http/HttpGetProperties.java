package com.karimmajed.flowforge.http;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "flowforge.http-get")
public class HttpGetProperties {

    private Duration connectTimeout = Duration.ofSeconds(2);

    private Duration requestTimeout = Duration.ofSeconds(5);

    private int maxResponseBytes = 65_536;

    private List<String> allowedHosts = new ArrayList<>();

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public int getMaxResponseBytes() {
        return maxResponseBytes;
    }

    public void setMaxResponseBytes(int maxResponseBytes) {
        this.maxResponseBytes = maxResponseBytes;
    }

    public List<String> getAllowedHosts() {
        return List.copyOf(allowedHosts);
    }

    public void setAllowedHosts(List<String> allowedHosts) {
        this.allowedHosts = allowedHosts == null
                ? new ArrayList<>()
                : new ArrayList<>(allowedHosts);
    }
}
