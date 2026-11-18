package com.karimmajed.flowforge.controller;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Map;

final class JsonObjectBody {

    static final int MAX_PAYLOAD_BYTES = 64 * 1024;

    private JsonObjectBody() {
    }

    static byte[] readLimited(HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readNBytes(MAX_PAYLOAD_BYTES + 1);
        if (body.length > MAX_PAYLOAD_BYTES) {
            throw new ResponseStatusException(
                    HttpStatus.CONTENT_TOO_LARGE,
                    "Payload is too large");
        }
        return body;
    }

    static Map<String, Object> parse(byte[] body, JsonMapper jsonMapper) {
        JsonNode root;
        try {
            root = jsonMapper.readTree(body);
        } catch (JacksonException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Malformed JSON payload",
                    ex);
        }
        if (root == null || !root.isObject()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A JSON object is required");
        }
        return jsonMapper.convertValue(root, new TypeReference<Map<String, Object>>() {});
    }
}
