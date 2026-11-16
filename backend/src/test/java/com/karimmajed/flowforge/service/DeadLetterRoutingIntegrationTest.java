package com.karimmajed.flowforge.service;

import com.karimmajed.flowforge.config.RabbitMqConfig;
import com.karimmajed.flowforge.http.HttpGetProperties;
import com.karimmajed.flowforge.http.HttpsGetExecutor;
import com.karimmajed.flowforge.messaging.ExecutionMessageListener;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListenerContainer;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Publishes a duplicate delivery through RabbitMQ and expects the broker
 * policy, not a queue argument, to dead-letter it.
 *
 * <p>The missing-policy startup guard is not repeated here.
 * {@code ExecutionConsumerStartupTest.missingPolicyRefusesToStartTheConsumer}
 * covers that without a broker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DeadLetterRoutingIntegrationTest {

    private static final String USER = "flowforge-test";

    private static final String PASSWORD = "flowforge-test";

    private static final int DEV_POSTGRES_PORT = 5433;

    private static final int DEV_AMQP_PORT = 5672;

    private static final int DEV_MANAGEMENT_PORT = 15672;

    private static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine");

    private static final GenericContainer<?> rabbit =
            new GenericContainer<>("rabbitmq:4-management-alpine")
                    .withEnv("RABBITMQ_DEFAULT_USER", USER)
                    .withEnv("RABBITMQ_DEFAULT_PASS", PASSWORD)
                    .withExposedPorts(DEV_AMQP_PORT, DEV_MANAGEMENT_PORT)
                    .waitingFor(Wait.forHttp("/api/overview")
                            .forPort(DEV_MANAGEMENT_PORT)
                            .withBasicCredentials(USER, PASSWORD)
                            .forStatusCode(200)
                            .withStartupTimeout(Duration.ofMinutes(3)));

    static {
        postgres.start();
        rabbit.start();
        refuseDevelopmentPorts();
        System.setProperty("DB_URL", postgres.getJdbcUrl());
        System.setProperty("DB_USERNAME", postgres.getUsername());
        System.setProperty("DB_PASSWORD", postgres.getPassword());
        System.setProperty("RABBITMQ_USERNAME", USER);
        System.setProperty("RABBITMQ_PASSWORD", PASSWORD);
        try {
            provisionDeadLetterTopology();
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Could not provision the dead-letter policy",
                    ex);
        }
    }

    @AfterAll
    static void clearPlaceholderProperties() {
        System.clearProperty("DB_URL");
        System.clearProperty("DB_USERNAME");
        System.clearProperty("DB_PASSWORD");
        System.clearProperty("RABBITMQ_USERNAME");
        System.clearProperty("RABBITMQ_PASSWORD");
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        refuseDevelopmentPorts();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", () -> rabbit.getMappedPort(DEV_AMQP_PORT));
        registry.add("spring.rabbitmq.username", () -> USER);
        registry.add("spring.rabbitmq.password", () -> PASSWORD);
        registry.add(
                "flowforge.rabbitmq.management-url",
                DeadLetterRoutingIntegrationTest::managementRoot);
        registry.add("flowforge.rabbitmq.policy-wait", () -> "20s");
        registry.add("flowforge.consumer.enabled", () -> "true");
        registry.add("flowforge.recovery.enabled", () -> "false");
        registry.add("flowforge.outbox.poll-interval-ms", () -> "600000");
        registry.add("server.port", () -> "0");
    }

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitListenerEndpointRegistry registry;

    @Autowired
    private WorkflowExecutionProcessor processor;

    private final ScriptedHttp transport = new ScriptedHttp();

    private final AtomicInteger listenerAttempts = new AtomicInteger();

    @BeforeEach
    void resetDatabase() {
        assertIsolatedContainers();
        jdbc.update("DELETE FROM execution_admin_audit");
        jdbc.update("DELETE FROM execution_step_checkpoints");
        jdbc.update("DELETE FROM execution_outbox");
        jdbc.update("DELETE FROM workflow_executions");
        jdbc.update("DELETE FROM workflow_steps");
        jdbc.update("DELETE FROM workflows");
        transport.uris.clear();
        listenerAttempts.set(0);
        processor.setBeforeClaim(listenerAttempts::incrementAndGet);
        processor.setHttpGet(scriptedExecutor());
        rabbitTemplate.execute(channel -> {
            channel.queuePurge(RabbitMqConfig.QUEUE);
            channel.queuePurge(RabbitMqConfig.DEAD_LETTER_QUEUE);
            return null;
        });
    }

    @Test
    @Timeout(60)
    void duplicateRunningDeliveryIsDeadLetteredWithoutAnotherHttpCall() throws Exception {
        MessageListenerContainer container = registry.getListenerContainer(
                ExecutionMessageListener.LISTENER_ID);
        assertThat(container).isNotNull();
        assertThat(container.isRunning()).isTrue();
        assertThat(pendingQueueUsesTheBrokerPolicy()).isTrue();

        long workflowId = insertWorkflow();
        long stepId = insertHttpStep(workflowId);
        long executionId = insertExecution(workflowId, "RUNNING");
        String token = "running-attempt-token";
        checkpoint(executionId, stepId, token, 1);

        rabbitTemplate.convertAndSend(
                RabbitMqConfig.EXCHANGE,
                RabbitMqConfig.ROUTING_KEY,
                Long.toString(executionId));

        Message dead = rabbitTemplate.receive(
                RabbitMqConfig.DEAD_LETTER_QUEUE,
                15_000);
        assertThat(dead).isNotNull();
        assertThat(rabbitTemplate.getMessageConverter().fromMessage(dead))
                .isEqualTo(Long.toString(executionId));
        assertThat(listenerAttempts).hasValue(1);
        assertThat(transport.uris).isEmpty();

        Map<String, ?> death = dead.getMessageProperties().getXDeathHeader().get(0);
        assertThat(death.get("reason")).isEqualTo("rejected");
        assertThat(death.get("queue")).isEqualTo(RabbitMqConfig.QUEUE);
        assertThat(death.get("exchange")).isEqualTo(RabbitMqConfig.EXCHANGE);
        assertThat(((Number) death.get("count")).longValue()).isEqualTo(1L);
        assertUnchanged(executionId, stepId, token, 1);

        long validId = insertExecution(workflowId, "QUEUED");
        transport.respond(200, new byte[0]);
        rabbitTemplate.convertAndSend(
                RabbitMqConfig.EXCHANGE,
                RabbitMqConfig.ROUTING_KEY,
                Long.toString(validId));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(status(validId)).isEqualTo("SUCCEEDED"));
        assertThat(transport.uris).containsExactly(
                URI.create("https://allowed.example/orders"));
        assertThat(listenerAttempts).hasValue(2);
        assertThat(rabbitTemplate.receive(RabbitMqConfig.DEAD_LETTER_QUEUE, 500))
                .isNull();
        assertUnchanged(executionId, stepId, token, 1);
    }

    private void assertIsolatedContainers() {
        String jdbcUrl = environment.getProperty("spring.datasource.url");
        String amqpPort = environment.getProperty("spring.rabbitmq.port");
        String managementUrl = environment.getProperty("flowforge.rabbitmq.management-url");
        int postgresPort = postgres.getMappedPort(5432);
        int rabbitPort = rabbit.getMappedPort(DEV_AMQP_PORT);
        int managementPort = rabbit.getMappedPort(DEV_MANAGEMENT_PORT);

        assertThat(jdbcUrl).contains(":" + postgresPort).doesNotContain(":" + DEV_POSTGRES_PORT);
        assertThat(amqpPort).isEqualTo(Integer.toString(rabbitPort));
        assertThat(rabbitPort).isNotEqualTo(DEV_AMQP_PORT);
        assertThat(managementUrl).isEqualTo(managementRoot());
        assertThat(managementPort).isNotEqualTo(DEV_MANAGEMENT_PORT);

        String liveUrl = jdbc.execute((ConnectionCallback<String>) connection ->
                connection.getMetaData().getURL());
        assertThat(liveUrl).contains(":" + postgresPort);
    }

    private boolean pendingQueueUsesTheBrokerPolicy() throws Exception {
        JsonNode queue = JsonMapper.builder().build().readTree(get(
                "/api/queues/%2F/" + RabbitMqConfig.QUEUE));
        JsonNode arguments = queue.path("arguments");
        assertThat(arguments.path("x-dead-letter-exchange").isMissingNode()
                || arguments.path("x-dead-letter-exchange").isNull()).isTrue();
        assertThat(arguments.path("x-dead-letter-routing-key").isMissingNode()
                || arguments.path("x-dead-letter-routing-key").isNull()).isTrue();
        assertThat(queue.path("policy").asString()).isEqualTo("flowforge-dlx");
        JsonNode definition = queue.path("effective_policy_definition");
        assertThat(definition.path("dead-letter-exchange").asString())
                .isEqualTo(RabbitMqConfig.DEAD_LETTER_EXCHANGE);
        assertThat(definition.path("dead-letter-routing-key").asString())
                .isEqualTo(RabbitMqConfig.DEAD_LETTER_ROUTING_KEY);
        return true;
    }

    private void assertUnchanged(
            long executionId,
            long stepId,
            String token,
            int attemptNumber) {

        assertThat(status(executionId)).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("""
                SELECT attempt_token
                FROM execution_step_checkpoints
                WHERE execution_id = ?
                  AND workflow_step_id = ?
                """,
                String.class,
                executionId,
                stepId)).isEqualTo(token);
        assertThat(jdbc.queryForObject("""
                SELECT attempt_number
                FROM execution_step_checkpoints
                WHERE execution_id = ?
                  AND workflow_step_id = ?
                """,
                Integer.class,
                executionId,
                stepId)).isEqualTo(attemptNumber);
        assertThat(jdbc.queryForObject("""
                SELECT status
                FROM execution_step_checkpoints
                WHERE execution_id = ?
                  AND workflow_step_id = ?
                """,
                String.class,
                executionId,
                stepId)).isEqualTo("RUNNING");
    }

    private HttpsGetExecutor scriptedExecutor() {
        HttpGetProperties properties = new HttpGetProperties();
        properties.setAllowedHosts(List.of("allowed.example"));
        properties.setConnectTimeout(Duration.ofSeconds(2));
        properties.setRequestTimeout(Duration.ofSeconds(5));
        InetAddress publicAddress;
        try {
            publicAddress = InetAddress.getByName("8.8.8.8");
        } catch (java.net.UnknownHostException ex) {
            throw new IllegalStateException(ex);
        }
        return HttpsGetExecutor.withTransport(
                properties,
                transport,
                host -> new InetAddress[] { publicAddress });
    }

    private long insertWorkflow() {
        return jdbc.queryForObject("""
                INSERT INTO workflows (name, active)
                VALUES (?, TRUE)
                RETURNING id
                """,
                Long.class,
                "Dead letter " + UUID.randomUUID());
    }

    private long insertHttpStep(long workflowId) {
        return jdbc.queryForObject("""
                INSERT INTO workflow_steps (
                    workflow_id, name, step_type, position, configuration
                )
                VALUES (?, 'Notify', 'HTTP_REQUEST', 1, CAST(? AS jsonb))
                RETURNING id
                """,
                Long.class,
                workflowId,
                "{\"method\":\"GET\",\"url\":\"https://allowed.example/orders\"}");
    }

    private long insertExecution(long workflowId, String status) {
        return jdbc.queryForObject("""
                INSERT INTO workflow_executions (
                    workflow_id, status, payload, idempotency_key
                )
                VALUES (?, ?, CAST('{}' AS jsonb), ?)
                RETURNING id
                """,
                Long.class,
                workflowId,
                status,
                "dlq-" + UUID.randomUUID());
    }

    private void checkpoint(
            long executionId,
            long stepId,
            String token,
            int attemptNumber) {

        jdbc.update("""
                INSERT INTO execution_step_checkpoints (
                    execution_id,
                    workflow_step_id,
                    status,
                    attempt_token,
                    attempt_number,
                    lease_until
                )
                VALUES (
                    ?, ?, 'RUNNING', ?, ?,
                    CURRENT_TIMESTAMP + INTERVAL '2 minutes'
                )
                """,
                executionId,
                stepId,
                token,
                attemptNumber);
    }

    private String status(long executionId) {
        return jdbc.queryForObject(
                "SELECT status FROM workflow_executions WHERE id = ?",
                String.class,
                executionId);
    }

    private static void refuseDevelopmentPorts() {
        int postgresPort = postgres.getMappedPort(5432);
        int rabbitPort = rabbit.getMappedPort(DEV_AMQP_PORT);
        int managementPort = rabbit.getMappedPort(DEV_MANAGEMENT_PORT);
        if (postgresPort == DEV_POSTGRES_PORT
                || rabbitPort == DEV_AMQP_PORT
                || managementPort == DEV_MANAGEMENT_PORT) {
            throw new IllegalStateException(
                    "Refusing to use the development database or broker ports");
        }
    }

    private static void provisionDeadLetterTopology() throws Exception {
        put("/api/exchanges/%2F/" + RabbitMqConfig.DEAD_LETTER_EXCHANGE, """
                {
                  "type": "direct",
                  "durable": true,
                  "auto_delete": false,
                  "internal": false,
                  "arguments": {}
                }
                """);
        put("/api/queues/%2F/" + RabbitMqConfig.DEAD_LETTER_QUEUE, """
                {
                  "durable": true,
                  "auto_delete": false,
                  "arguments": {}
                }
                """);
        post("/api/bindings/%2F/e/"
                + RabbitMqConfig.DEAD_LETTER_EXCHANGE
                + "/q/"
                + RabbitMqConfig.DEAD_LETTER_QUEUE, """
                {
                  "routing_key": "execution.failed",
                  "arguments": {}
                }
                """);
        put("/api/policies/%2F/flowforge-dlx", """
                {
                  "pattern": "^flowforge[.]executions[.]pending$",
                  "definition": {
                    "dead-letter-exchange": "flowforge.executions.dlx",
                    "dead-letter-routing-key": "execution.failed"
                  },
                  "priority": 1,
                  "apply-to": "queues"
                }
                """);
    }

    private static String get(String path) throws Exception {
        return send("GET", path, null);
    }

    private static void put(String path, String json) throws Exception {
        send("PUT", path, json);
    }

    private static void post(String path, String json) throws Exception {
        send("POST", path, json);
    }

    private static String send(String method, String path, String json) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(managementRoot() + path))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        (USER + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8)));
        if (json == null) {
            request.GET();
        } else {
            request.header("Content-Type", "application/json");
            if ("POST".equals(method)) {
                request.POST(HttpRequest.BodyPublishers.ofString(json));
            } else {
                request.PUT(HttpRequest.BodyPublishers.ofString(json));
            }
        }
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        IOException last = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                HttpResponse<String> response = client.send(
                        request.build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 != 2) {
                    throw new IllegalStateException(
                            "RabbitMQ management returned " + response.statusCode()
                                    + " for " + path);
                }
                return response.body();
            } catch (IOException ex) {
                last = ex;
                Thread.sleep(200L * attempt);
            }
        }
        throw last;
    }

    private static String managementRoot() {
        return "http://" + rabbit.getHost() + ":" + rabbit.getMappedPort(DEV_MANAGEMENT_PORT);
    }

    private static final class ScriptedHttp implements ClientHttpRequestFactory {

        private final List<URI> uris = new CopyOnWriteArrayList<>();

        private final Queue<org.springframework.http.client.ClientHttpResponse> responses =
                new ConcurrentLinkedQueue<>();

        void respond(int status, byte[] body) {
            responses.add(new MockClientHttpResponse(body, status));
        }

        @Override
        public ClientHttpRequest createRequest(URI uri, HttpMethod method) {
            uris.add(uri);
            org.springframework.http.client.ClientHttpResponse response = responses.poll();
            if (response == null) {
                throw new IllegalStateException("HTTP transport was called without a scripted response");
            }
            MockClientHttpRequest request = new MockClientHttpRequest(method, uri);
            request.setResponse(response);
            return request;
        }
    }
}
