package com.karimmajed.flowforge.validation;

import com.karimmajed.flowforge.enums.StepType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class StepConfigurationValidator {

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

        try {
            URI uri = URI.create(urlString);

            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null) {
                throw badRequest("A valid HTTPS URL is required");
            }

        } catch (IllegalArgumentException ex) {
            throw badRequest("Invalid URL");
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            message
        );
    }
}
