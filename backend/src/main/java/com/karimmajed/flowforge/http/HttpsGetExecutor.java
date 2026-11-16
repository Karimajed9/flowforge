package com.karimmajed.flowforge.http;

import com.karimmajed.flowforge.service.ExecutionCheckpointStore;
import com.karimmajed.flowforge.validation.StepConfigurationValidator;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * HTTPS GET client used by workflow HTTP_REQUEST steps.
 *
 * <p>Destinations are default-deny. {@code httpbin.org} is reachable only
 * when {@code flowforge.http-get.allowed-hosts} lists that exact host.
 *
 * <p>DNS is checked before the call, but the JDK client resolves the
 * hostname again when it connects. Pinning that socket to the validated
 * address would drop TLS hostname verification, so this client does not
 * do it. A rebinding between the check and the connect is not closed.
 * The allowlist is what keeps the destination set small.
 */
@Component
@EnableConfigurationProperties(HttpGetProperties.class)
public class HttpsGetExecutor {

    private static final Set<String> INTERNAL_SUFFIXES = Set.of(
            ".localhost",
            ".local",
            ".internal"
    );

    private static final Set<String> INTERNAL_NAMES = Set.of(
            "localhost",
            "local",
            "internal"
    );

    private static final Duration LEASE_MARGIN = Duration.ofSeconds(15);

    private static final ExecutorService LOOKUPS = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "flowforge-http-get-dns");
        thread.setDaemon(true);
        return thread;
    });

    private final HttpGetProperties properties;

    private final RestClient restClient;

    private final AddressLookup addressLookup;

    @Autowired
    public HttpsGetExecutor(HttpGetProperties properties) {
        this(
                properties,
                HttpsGetClientFactory.restClient(properties),
                InetAddress::getAllByName
        );
    }

    HttpsGetExecutor(
            HttpGetProperties properties,
            RestClient restClient,
            AddressLookup addressLookup) {

        if (properties.getConnectTimeout() == null
                || properties.getConnectTimeout().isNegative()
                || properties.getConnectTimeout().isZero()
                || properties.getRequestTimeout() == null
                || properties.getRequestTimeout().isNegative()
                || properties.getRequestTimeout().isZero()
                || properties.getMaxResponseBytes() <= 0) {

            throw new IllegalArgumentException(
                    "HTTP GET timeouts and the response cap must be positive");
        }

        Duration budget = properties.getConnectTimeout()
                .plus(properties.getConnectTimeout())
                .plus(properties.getRequestTimeout());
        Duration limit = ExecutionCheckpointStore.LEASE.minus(LEASE_MARGIN);

        if (budget.compareTo(limit) > 0) {
            throw new IllegalArgumentException(
                    "HTTP GET DNS, connect, and request timeouts must finish before the step lease");
        }

        this.properties = properties;
        this.restClient = restClient;
        this.addressLookup = addressLookup;
    }

    public static HttpsGetExecutor withTransport(
            HttpGetProperties properties,
            ClientHttpRequestFactory transport,
            Function<String, InetAddress[]> lookup) {

        return new HttpsGetExecutor(
                properties,
                RestClient.builder().requestFactory(transport).build(),
                host -> {
                    InetAddress[] addresses = lookup.apply(host);
                    if (addresses == null) {
                        throw new UnknownHostException(host);
                    }
                    return addresses;
                });
    }

    public HttpGetResult get(String url) {
        long started = System.nanoTime();

        URI uri;

        try {
            uri = parse(url);
        } catch (IllegalArgumentException ex) {
            return blocked(started);
        }

        Screen screen = screen(uri);

        if (screen == Screen.BLOCKED) {
            return blocked(started);
        }

        if (screen == Screen.NETWORK) {
            return network(started);
        }

        if (screen == Screen.TIMEOUT) {
            return timeout(started);
        }

        try {
            return exchange(uri, started);
        } catch (ResourceAccessException ex) {
            if (isTimeout(ex)) {
                return timeout(started);
            }
            return network(started);
        }
    }

    private HttpGetResult exchange(URI uri, long started) {
        return restClient.get()
                .uri(uri)
                .exchange((request, response) -> {
                    try (ClientHttpResponse ignored = response) {
                        int status = response.getStatusCode().value();
                        boolean tooLarge = exceedsCap(
                                response.getBody(),
                                properties.getMaxResponseBytes());
                        Duration duration = elapsed(started);

                        if (tooLarge) {
                            return new HttpGetResult(
                                    HttpGetResult.Outcome.RESPONSE_TOO_LARGE,
                                    status,
                                    duration);
                        }

                        if (status >= 200 && status < 300) {
                            return new HttpGetResult(
                                    HttpGetResult.Outcome.SUCCESS,
                                    status,
                                    duration);
                        }

                        return new HttpGetResult(
                                HttpGetResult.Outcome.HTTP_STATUS,
                                status,
                                duration);
                    }
                });
    }

    private Screen screen(URI uri) {
        if (uri == null
                || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null
                || !"https".equalsIgnoreCase(uri.getScheme())) {
            return Screen.BLOCKED;
        }

        int port = uri.getPort();

        if (port != -1 && port != 443) {
            return Screen.BLOCKED;
        }

        String host = normalizeHost(uri.getHost());

        if (host == null || host.isBlank()) {
            return Screen.BLOCKED;
        }

        if (isIpLiteral(host) || isInternalName(host)) {
            return Screen.BLOCKED;
        }

        if (!isAllowlisted(host)) {
            return Screen.BLOCKED;
        }

        return resolve(host);
    }

    private Screen resolve(String host) {
        Future<InetAddress[]> future = LOOKUPS.submit(() -> addressLookup.resolve(host));

        try {
            InetAddress[] addresses = future.get(
                    properties.getConnectTimeout().toMillis(),
                    TimeUnit.MILLISECONDS);

            if (addresses == null || addresses.length == 0) {
                return Screen.NETWORK;
            }

            for (InetAddress address : addresses) {
                if (address == null
                        || StepConfigurationValidator.isUnsafeAddress(address)) {
                    return Screen.BLOCKED;
                }
            }

            return Screen.ALLOWED;

        } catch (TimeoutException ex) {
            future.cancel(true);
            return Screen.TIMEOUT;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return Screen.TIMEOUT;
        } catch (ExecutionException ex) {
            future.cancel(true);
            if (ex.getCause() instanceof UnknownHostException) {
                return Screen.NETWORK;
            }
            if (ex.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            return Screen.NETWORK;
        }
    }

    private boolean isAllowlisted(String host) {
        for (String allowed : properties.getAllowedHosts()) {
            if (allowed != null && host.equals(normalizeHost(allowed))) {
                return true;
            }
        }
        return false;
    }

    private static URI parse(String url) {
        if (url == null || url.isBlank() || url.length() > 2048) {
            throw new IllegalArgumentException("Invalid URL");
        }
        return URI.create(url);
    }

    static String normalizeHost(String host) {
        if (host == null) {
            return null;
        }

        String normalized = host.toLowerCase(Locale.ROOT);

        if (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        return normalized;
    }

    private static boolean isInternalName(String host) {
        if (INTERNAL_NAMES.contains(host)) {
            return true;
        }

        for (String suffix : INTERNAL_SUFFIXES) {
            if (host.endsWith(suffix)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isIpLiteral(String host) {
        String value = host;

        if (value.startsWith("[") && value.endsWith("]") && value.length() > 2) {
            value = value.substring(1, value.length() - 1);
        }

        if (value.indexOf(':') >= 0
                || value.startsWith("0x")
                || value.chars().allMatch(ch -> ch == '.' || Character.isDigit(ch))) {
            return true;
        }

        return false;
    }

    private static boolean exceedsCap(InputStream body, int maxBytes)
            throws IOException {

        if (body == null) {
            return false;
        }

        byte[] buffer = new byte[4096];
        long total = 0;
        long limit = maxBytes + 1L;

        while (total < limit) {
            int want = (int) Math.min(buffer.length, limit - total);
            int read = body.read(buffer, 0, want);

            if (read < 0) {
                return false;
            }

            if (read == 0) {
                continue;
            }

            total += read;
        }

        return true;
    }

    private static boolean isTimeout(Throwable failure) {
        Throwable current = failure;

        while (current != null) {
            if (current instanceof HttpTimeoutException
                    || current instanceof java.net.SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }

        return false;
    }

    private static HttpGetResult blocked(long started) {
        return new HttpGetResult(
                HttpGetResult.Outcome.BLOCKED,
                null,
                elapsed(started));
    }

    private static HttpGetResult timeout(long started) {
        return new HttpGetResult(
                HttpGetResult.Outcome.TIMEOUT,
                null,
                elapsed(started));
    }

    private static HttpGetResult network(long started) {
        return new HttpGetResult(
                HttpGetResult.Outcome.NETWORK,
                null,
                elapsed(started));
    }

    private static Duration elapsed(long started) {
        return Duration.ofNanos(Math.max(0, System.nanoTime() - started));
    }

    private enum Screen {
        ALLOWED,
        BLOCKED,
        NETWORK,
        TIMEOUT
    }
}

@FunctionalInterface
interface AddressLookup {
    InetAddress[] resolve(String host) throws UnknownHostException;
}
