package com.karimmajed.flowforge.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpsGetExecutorTest {

    private static final String TOKEN = "super-secret-token";

    @Test
    void successfulGetReturnsStatusAndDurationWithoutTheBody() throws Exception {
        ScriptedTransport transport = new ScriptedTransport();
        transport.respond(200, TOKEN.getBytes());
        HttpsGetExecutor executor = executor(
                List.of("allowed.example"),
                transport,
                publicOnly("allowed.example"));

        HttpGetResult result = executor.get(
                "https://allowed.example/v1?access_token=" + TOKEN);

        assertThat(result.outcome()).isEqualTo(HttpGetResult.Outcome.SUCCESS);
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.duration()).isGreaterThanOrEqualTo(Duration.ZERO);
        assertThat(result.toString()).doesNotContain(TOKEN);
        assertThat(transport.methods).containsExactly(HttpMethod.GET);
        assertThat(transport.uris).containsExactly(
                URI.create("https://allowed.example/v1?access_token=" + TOKEN));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 500})
    void nonSuccessStatusIsNotASuccessfulGet(int status) throws Exception {
        ScriptedTransport transport = new ScriptedTransport();
        transport.respond(status, "failure-body".getBytes());
        HttpsGetExecutor executor = executor(
                List.of("allowed.example"),
                transport,
                publicOnly("allowed.example"));

        HttpGetResult result = executor.get("https://allowed.example/resource");

        assertThat(result.outcome()).isEqualTo(HttpGetResult.Outcome.HTTP_STATUS);
        assertThat(result.statusCode()).isEqualTo(status);
        assertThat(result.toString()).doesNotContain("failure-body");
    }

    @Test
    void timeoutIsDistinctFromANetworkFailure() throws Exception {
        ScriptedTransport timeout = new ScriptedTransport();
        timeout.fail(new ResourceAccessException(
                "timed out",
                new HttpTimeoutException("request timed out")));
        HttpsGetExecutor timeoutExecutor = executor(
                List.of("allowed.example"),
                timeout,
                publicOnly("allowed.example"));

        HttpGetResult timedOut = timeoutExecutor.get("https://allowed.example/slow");

        assertThat(timedOut.outcome()).isEqualTo(HttpGetResult.Outcome.TIMEOUT);
        assertThat(timedOut.statusCode()).isNull();

        ScriptedTransport reset = new ScriptedTransport();
        reset.fail(new ResourceAccessException(
                "reset",
                new java.net.ConnectException("connection refused")));
        HttpsGetExecutor networkExecutor = executor(
                List.of("allowed.example"),
                reset,
                publicOnly("allowed.example"));

        HttpGetResult failed = networkExecutor.get("https://allowed.example/down");

        assertThat(failed.outcome()).isEqualTo(HttpGetResult.Outcome.NETWORK);
        assertThat(failed.statusCode()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://127.0.0.1/",
            "https://127.0.0.1/latest/meta-data",
            "https://[::1]/",
            "https://10.0.0.5/",
            "https://192.168.1.10/",
            "https://169.254.169.254/latest/meta-data/",
            "https://localhost/",
            "https://localhost/admin",
            "https://metadata.google.internal/computeMetadata/v1/",
            "https://service.local/health",
            "http://allowed.example/",
            "https://allowed.example:8443/",
            "https://user:password@allowed.example/"
    })
    void internalAndNonHttpsUrlsAreBlockedBeforeARequest(String url)
            throws Exception {

        ScriptedTransport transport = new ScriptedTransport();
        RecordingLookup lookup = publicOnly("allowed.example");
        lookup.allow("127.0.0.1", "8.8.8.8");
        HttpsGetExecutor executor = executor(
                List.of(
                        "127.0.0.1",
                        "::1",
                        "10.0.0.5",
                        "192.168.1.10",
                        "169.254.169.254",
                        "localhost",
                        "metadata.google.internal",
                        "service.local",
                        "allowed.example"),
                transport,
                lookup);

        HttpGetResult result = executor.get(url);

        assertThat(result.outcome()).isEqualTo(HttpGetResult.Outcome.BLOCKED);
        assertThat(transport.uris).isEmpty();
        assertThat(lookup.lookedUp).isEmpty();
    }

    @Test
    void allowlistedNameThatResolvesToAnInternalAddressIsBlocked()
            throws Exception {

        ScriptedTransport transport = new ScriptedTransport();
        RecordingLookup lookup = new RecordingLookup();
        lookup.allow("rebind.example", "8.8.8.8", "10.1.1.1");
        HttpsGetExecutor executor = executor(
                List.of("rebind.example"),
                transport,
                lookup);

        HttpGetResult result = executor.get("https://rebind.example/admin");

        assertThat(result.outcome()).isEqualTo(HttpGetResult.Outcome.BLOCKED);
        assertThat(transport.uris).isEmpty();
        assertThat(lookup.lookedUp).containsExactly("rebind.example");
    }

    @Test
    void hostsOutsideTheAllowlistAreBlocked() throws Exception {
        ScriptedTransport transport = new ScriptedTransport();
        RecordingLookup lookup = publicOnly("httpbin.org");
        HttpsGetExecutor executor = executor(
                List.of("httpbin.org"),
                transport,
                lookup);

        assertThat(executor.get("https://example.com/get").outcome())
                .isEqualTo(HttpGetResult.Outcome.BLOCKED);
        assertThat(executor.get("https://evilhttpbin.org/get").outcome())
                .isEqualTo(HttpGetResult.Outcome.BLOCKED);
        assertThat(executor.get("https://httpbin.org.evil.com/get").outcome())
                .isEqualTo(HttpGetResult.Outcome.BLOCKED);
        assertThat(executor.get("https://not.httpbin.org/get").outcome())
                .isEqualTo(HttpGetResult.Outcome.BLOCKED);
        assertThat(transport.uris).isEmpty();
        assertThat(lookup.lookedUp).isEmpty();
    }

    @Test
    void httpbinIsDeniedUntilItIsConfigured() throws Exception {
        ScriptedTransport transport = new ScriptedTransport();
        RecordingLookup lookup = publicOnly("httpbin.org");
        HttpsGetExecutor closed = executor(List.of(), transport, lookup);

        assertThat(closed.get("https://httpbin.org/get").outcome())
                .isEqualTo(HttpGetResult.Outcome.BLOCKED);
        assertThat(lookup.lookedUp).isEmpty();

        transport.respond(200, "ok".getBytes());
        HttpsGetExecutor opened = executor(
                List.of("httpbin.org"),
                transport,
                lookup);

        HttpGetResult result = opened.get("https://Httpbin.Org/get");

        assertThat(result.outcome()).isEqualTo(HttpGetResult.Outcome.SUCCESS);
        assertThat(lookup.lookedUp).containsExactly("httpbin.org");
        assertThat(transport.uris).containsExactly(
                URI.create("https://Httpbin.Org/get"));
    }

    @Test
    void redirectIsReturnedAndNotFollowed() throws Exception {
        ScriptedTransport transport = new ScriptedTransport();
        MockClientHttpResponse redirect = new MockClientHttpResponse(new byte[0], 302);
        redirect.getHeaders().setLocation(URI.create("https://evil.example/steal"));
        transport.respond(redirect);
        transport.respond(200, "followed".getBytes());
        HttpsGetExecutor executor = executor(
                List.of("allowed.example"),
                transport,
                publicOnly("allowed.example"));

        HttpGetResult result = executor.get("https://allowed.example/start");

        assertThat(result.outcome()).isEqualTo(HttpGetResult.Outcome.HTTP_STATUS);
        assertThat(result.statusCode()).isEqualTo(302);
        assertThat(transport.uris).containsExactly(
                URI.create("https://allowed.example/start"));
        assertThat(transport.remainingResponses()).isEqualTo(1);
        assertThat(result.toString()).doesNotContain("evil.example");
    }

    @Test
    void oversizedResponseStopsReading() throws Exception {
        CountingStream body = new CountingStream(10_000);
        ScriptedTransport transport = new ScriptedTransport();
        transport.respond(new MockClientHttpResponse(body, 200));
        HttpGetProperties properties = properties(List.of("allowed.example"));
        properties.setMaxResponseBytes(32);
        HttpsGetExecutor executor = new HttpsGetExecutor(
                properties,
                RestClient.builder().requestFactory(transport).build(),
                publicOnly("allowed.example"));

        HttpGetResult result = executor.get("https://allowed.example/huge");

        assertThat(result.outcome())
                .isEqualTo(HttpGetResult.Outcome.RESPONSE_TOO_LARGE);
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(body.read).isEqualTo(33);
    }

    @Test
    void productionClientDisablesRedirectsAndUsesShortTimeouts()
            throws Exception {

        HttpGetProperties properties = new HttpGetProperties();
        HttpClient client = HttpsGetClientFactory.httpClient(
                properties.getConnectTimeout());
        var factory = HttpsGetClientFactory.requestFactory(properties);

        assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
        assertThat(client.connectTimeout()).contains(Duration.ofSeconds(2));
        assertThat(readTimeout(factory)).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.getAllowedHosts()).isEmpty();
        assertThat(properties.getConnectTimeout()
                .plus(properties.getConnectTimeout())
                .plus(properties.getRequestTimeout()))
                .isLessThan(com.karimmajed.flowforge.service.ExecutionCheckpointStore.LEASE);
    }

    @Test
    void dnsLookupCannotOutliveTheConnectTimeout() throws Exception {
        HttpGetProperties properties = properties(List.of("allowed.example"));
        properties.setConnectTimeout(Duration.ofMillis(150));
        properties.setRequestTimeout(Duration.ofSeconds(1));
        ScriptedTransport transport = new ScriptedTransport();
        HttpsGetExecutor executor = new HttpsGetExecutor(
                properties,
                RestClient.builder().requestFactory(transport).build(),
                host -> {
                    try {
                        Thread.sleep(5_000);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    return new InetAddress[] { InetAddress.getByName("8.8.8.8") };
                });

        long started = System.nanoTime();
        HttpGetResult result = executor.get("https://allowed.example/slow-dns");

        assertThat(result.outcome()).isEqualTo(HttpGetResult.Outcome.TIMEOUT);
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isLessThan(Duration.ofSeconds(2));
        assertThat(transport.uris).isEmpty();
    }

    @Test
    void configuredBudgetMustFinishBeforeTheStepLease() {
        HttpGetProperties properties = properties(List.of("allowed.example"));
        properties.setConnectTimeout(Duration.ofSeconds(30));
        properties.setRequestTimeout(Duration.ofMinutes(2));

        assertThatThrownBy(() -> new HttpsGetExecutor(
                properties,
                RestClient.builder().build(),
                host -> new InetAddress[] { InetAddress.getByName("8.8.8.8") }))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @Timeout(10)
    void slowResponseBodyIsClosedWithinTheRequestTimeout() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicBoolean accepted = new AtomicBoolean();
        server.createContext("/slow", exchange -> {
            accepted.set(true);
            exchange.sendResponseHeaders(200, 0);
            OutputStream body = exchange.getResponseBody();
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    body.write(1);
                    body.flush();
                    Thread.sleep(200);
                }
            } catch (Exception ignored) {
                // The client closes the stream when its deadline fires.
            } finally {
                body.close();
            }
        });
        server.start();
        try {
            HttpGetProperties properties = new HttpGetProperties();
            properties.setConnectTimeout(Duration.ofSeconds(1));
            properties.setRequestTimeout(Duration.ofMillis(400));
            RestClient client = HttpsGetClientFactory.restClient(properties);
            long started = System.nanoTime();

            assertThatThrownBy(() -> client.get()
                    .uri("http://127.0.0.1:" + server.getAddress().getPort() + "/slow")
                    .exchange((request, response) -> {
                        try (response) {
                            InputStream body = response.getBody();
                            byte[] buffer = new byte[8];
                            while (body.read(buffer) >= 0) {
                                // A trickle must not keep this read open.
                            }
                            return null;
                        }
                    }))
                    .isInstanceOf(ResourceAccessException.class);

            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(Duration.ofSeconds(3));
            assertThat(accepted).isTrue();
        } finally {
            server.stop(0);
        }
    }

    private static Duration readTimeout(
            org.springframework.http.client.JdkClientHttpRequestFactory factory)
            throws Exception {

        Field field = factory.getClass().getDeclaredField("readTimeout");
        field.setAccessible(true);
        return (Duration) field.get(factory);
    }

    private static HttpsGetExecutor executor(
            List<String> allowedHosts,
            ScriptedTransport transport,
            AddressLookup lookup) {

        return new HttpsGetExecutor(
                properties(allowedHosts),
                RestClient.builder().requestFactory(transport).build(),
                lookup);
    }

    private static HttpGetProperties properties(List<String> allowedHosts) {
        HttpGetProperties properties = new HttpGetProperties();
        properties.setAllowedHosts(allowedHosts);
        return properties;
    }

    private static RecordingLookup publicOnly(String host) throws Exception {
        RecordingLookup lookup = new RecordingLookup();
        lookup.allow(host, "8.8.8.8");
        return lookup;
    }

    private static final class RecordingLookup implements AddressLookup {

        private final Map<String, InetAddress[]> answers = new HashMap<>();

        private final List<String> lookedUp = new ArrayList<>();

        void allow(String host, String... addresses) throws UnknownHostException {
            InetAddress[] resolved = new InetAddress[addresses.length];
            for (int i = 0; i < addresses.length; i++) {
                resolved[i] = InetAddress.getByName(addresses[i]);
            }
            answers.put(host, resolved);
        }

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            lookedUp.add(host);
            InetAddress[] answer = answers.get(host);
            if (answer == null) {
                throw new UnknownHostException(host);
            }
            return answer;
        }
    }

    private static final class ScriptedTransport implements ClientHttpRequestFactory {

        private final Queue<ClientHttpResponse> responses = new ArrayDeque<>();

        private final List<URI> uris = new ArrayList<>();

        private final List<HttpMethod> methods = new ArrayList<>();

        private RuntimeException failure;

        void respond(int status, byte[] body) {
            responses.add(new MockClientHttpResponse(body, status));
        }

        void respond(ClientHttpResponse response) {
            responses.add(response);
        }

        void fail(RuntimeException failure) {
            this.failure = failure;
        }

        int remainingResponses() {
            return responses.size();
        }

        @Override
        public ClientHttpRequest createRequest(URI uri, HttpMethod method) {
            uris.add(uri);
            methods.add(method);
            if (failure != null) {
                throw failure;
            }
            MockClientHttpRequest request = new MockClientHttpRequest(method, uri);
            request.setResponse(responses.remove());
            return request;
        }
    }

    private static final class CountingStream extends InputStream {

        private int remaining;

        private int read;

        private CountingStream(int size) {
            this.remaining = size;
        }

        @Override
        public int read() {
            if (remaining == 0) {
                return -1;
            }
            remaining--;
            read++;
            return 1;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (remaining == 0) {
                return -1;
            }
            int count = Math.min(length, remaining);
            remaining -= count;
            read += count;
            return count;
        }
    }
}
