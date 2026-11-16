package com.karimmajed.flowforge.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
public class RabbitManagementDeadLetterClient implements DeadLetterPolicySource {

    private final String managementUrl;

    private final RestClient client;

    public RabbitManagementDeadLetterClient(
            @Value("${flowforge.rabbitmq.management-url:http://localhost:15672}")
            String managementUrl,
            @Value("${spring.rabbitmq.username}")
            String username,
            @Value("${spring.rabbitmq.password}")
            String password) {

        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(5));

        this.managementUrl = managementUrl;
        this.client = RestClient.builder()
                .baseUrl(managementUrl)
                .defaultHeaders(headers -> headers.setBasicAuth(username, password))
                .requestFactory(factory)
                .build();
    }

    @Override
    public BrokerDeadLetterView read() {
        String queueJson = get(queueUri(
                clientBaseUrl(),
                RabbitMqConfig.QUEUE));
        String bindingJson = get(bindingUri(
                clientBaseUrl(),
                RabbitMqConfig.DEAD_LETTER_EXCHANGE,
                RabbitMqConfig.DEAD_LETTER_QUEUE));
        return parse(queueJson, bindingJson);
    }

    static URI queueUri(String root, String queue) {
        return managementUri(root, "api", "queues", "/", queue);
    }

    static URI bindingUri(String root, String exchange, String queue) {
        return managementUri(root, "api", "bindings", "/", "e", exchange, "q", queue);
    }

    private static URI managementUri(String root, String... segments) {
        StringBuilder path = new StringBuilder(stripTrailingSlash(root));
        for (String segment : segments) {
            path.append('/')
                    .append(URLEncoder.encode(segment, StandardCharsets.UTF_8)
                            .replace("+", "%20"));
        }
        return URI.create(path.toString());
    }

    private static String stripTrailingSlash(String root) {
        if (root.endsWith("/")) {
            return root.substring(0, root.length() - 1);
        }
        return root;
    }

    static BrokerDeadLetterView parse(String queueJson, String bindingJson) {
        JsonMapper mapper = JsonMapper.builder().build();
        JsonNode queue = mapper.readTree(queueJson);
        JsonNode definition = queue.path("effective_policy_definition");
        JsonNode bindings = mapper.readTree(bindingJson);
        boolean bound = false;

        if (bindings.isArray()) {
            for (JsonNode binding : bindings) {
                if (RabbitMqConfig.DEAD_LETTER_ROUTING_KEY.equals(
                        binding.path("routing_key").asString())) {
                    bound = true;
                    break;
                }
            }
        }

        return new BrokerDeadLetterView(
                text(queue, "policy"),
                text(definition, "dead-letter-exchange"),
                text(definition, "dead-letter-routing-key"),
                bound);
    }

    private String clientBaseUrl() {
        return managementUrl;
    }

    private String get(URI uri) {
        return client.get()
                .uri(uri)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new IllegalStateException(
                            "RabbitMQ management returned "
                                    + response.getStatusCode().value());
                })
                .body(String.class);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isString()) {
            return null;
        }
        return value.asString();
    }
}
