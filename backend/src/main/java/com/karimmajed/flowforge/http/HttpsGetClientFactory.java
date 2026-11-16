package com.karimmajed.flowforge.http;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

class HttpsGetClientFactory {

    private HttpsGetClientFactory() {
    }

    static HttpClient httpClient(Duration connectTimeout) {
        return HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    static JdkClientHttpRequestFactory requestFactory(
            HttpGetProperties properties) {

        JdkClientHttpRequestFactory factory =
                new JdkClientHttpRequestFactory(
                        httpClient(properties.getConnectTimeout()));
        factory.setReadTimeout(properties.getRequestTimeout());
        return factory;
    }

    static RestClient restClient(HttpGetProperties properties) {
        return RestClient.builder()
                .requestFactory(requestFactory(properties))
                .build();
    }
}
