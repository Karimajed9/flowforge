package com.karimmajed.flowforge.validation;

import com.karimmajed.flowforge.enums.StepType;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class StepConfigurationValidator {

    @FunctionalInterface
    interface AddressLookup {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final AddressLookup addressLookup;

    public StepConfigurationValidator() {
        this(InetAddress::getAllByName);
    }

    StepConfigurationValidator(AddressLookup addressLookup) {
        this.addressLookup = addressLookup;
    }

    public void validate(
            StepType stepType,
            Map<String, Object> configuration) {

        if (configuration == null) {
            throw badRequest("Configuration is required");
        }

        switch (stepType) {
            case VALIDATE_FIELDS ->
                validateFields(configuration);

            case HTTP_REQUEST ->
                validateHttpRequest(configuration);
        }
    }

    private void validateFields(Map<String, Object> config) {

        if (!config.keySet().equals(Set.of("requiredFields"))) {
            throw badRequest(
                "VALIDATE_FIELDS requires only 'requiredFields'"
            );
        }

        Object fields = config.get("requiredFields");

        if (!(fields instanceof List<?> list) || list.isEmpty()) {
            throw badRequest(
                "requiredFields must be a non-empty array"
            );
        }

        for (Object field : list) {
            if (!(field instanceof String name) || name.isBlank()) {
                throw badRequest(
                    "Every required field must be a non-empty string"
                );
            }
        }
    }

    private void validateHttpRequest(Map<String, Object> config) {

        if (!config.keySet().equals(Set.of("method", "url"))) {
            throw badRequest(
                "HTTP_REQUEST requires 'method' and 'url'"
            );
        }

        Object method = config.get("method");
        Object url = config.get("url");

        if (!(method instanceof String httpMethod)
                || !Set.of("GET", "POST").contains(httpMethod)) {
            throw badRequest("Method must be GET or POST");
        }

        if (!(url instanceof String urlString)
                || urlString.length() > 2048) {
            throw badRequest("Invalid URL");
        }

        URI uri;

        try {
            uri = URI.create(urlString);
        } catch (IllegalArgumentException ex) {
            throw badRequest("Invalid URL");
        }

        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null
                || uri.getPort() > 65535) {

            throw badRequest("A valid HTTPS URL is required");
        }

        validateHostname(uri.getHost());

        // Additional defense: check DNS resolution.
        validateResolvedAddresses(uri.getHost());
    }

    private void validateHostname(String host) {

        String normalized = host.toLowerCase(Locale.ROOT);

        if (normalized.endsWith(".")) {
            normalized = normalized.substring(
                0, normalized.length() - 1
            );
        }

        if (normalized.equals("localhost")
                || normalized.endsWith(".localhost")
                || normalized.equals("local")
                || normalized.endsWith(".local")
                || normalized.equals("internal")
                || normalized.endsWith(".internal")) {

            throw badRequest(
                "Local or internal hostnames are not allowed"
            );
        }

        if (normalized.startsWith("[")
                || normalized.matches("[0-9.]+")
                || normalized.startsWith("0x")) {

            throw badRequest(
                "Direct IP addresses are not allowed"
            );
        }

        if (!normalized.contains(".")
                || normalized.startsWith(".")
                || normalized.endsWith(".")) {

            throw badRequest(
                "A valid public hostname is required"
            );
        }
    }

    private void validateResolvedAddresses(String host) {

        try {
            InetAddress[] addresses = addressLookup.resolve(host);

            if (addresses.length == 0) {
                throw badRequest(
                    "Hostname could not be resolved"
                );
            }

            for (InetAddress address : addresses) {

                if (isUnsafeAddress(address)) {
                    throw badRequest(
                        "URL resolves to a restricted IP address"
                    );
                }
            }

        } catch (UnknownHostException ex) {
            throw badRequest(
                "Hostname could not be resolved"
            );
        }
    }

    static boolean isUnsafeAddress(InetAddress address) {

        // Reject standard non-public address categories.
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }

        byte[] bytes = address.getAddress();

        if (bytes.length == 4) {

            int a = Byte.toUnsignedInt(bytes[0]);
            int b = Byte.toUnsignedInt(bytes[1]);
            int c = Byte.toUnsignedInt(bytes[2]);

            // Additional special-use IPv4 ranges.
            return a == 0
                || a >= 224
                || (a == 100 && b >= 64 && b <= 127)
                || (a == 192 && b == 0 && c == 0)
                || (a == 192 && b == 0 && c == 2)
                || (a == 192 && b == 88 && c == 99)
                || (a == 198 && (b == 18 || b == 19))
                || (a == 198 && b == 51 && c == 100)
                || (a == 203 && b == 0 && c == 113);
        }

        if (bytes.length == 16) {

            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);

            // Only consider IPv6 global unicast space.
            if ((first & 0xE0) != 0x20) {
                return true;
            }

            // Reject the IPv6 documentation range.
            if (first == 0x20
                    && second == 0x01
                    && Byte.toUnsignedInt(bytes[2]) == 0x0D
                    && Byte.toUnsignedInt(bytes[3]) == 0xB8) {
                return true;
            }

            return false;
        }

        return true;
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            message
        );
    }
}