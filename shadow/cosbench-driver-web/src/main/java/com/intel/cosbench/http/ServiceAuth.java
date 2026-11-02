package com.intel.cosbench.http;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.io.JsonStringEncoder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/** Independent service credential carried by Controller-to-Driver requests. */
public record ServiceAuth(String token) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ServiceAuth {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("service token must not be blank");
        }
        token = token.trim();
    }

    public boolean matches(String presented) {
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    public String principalId() {
        return RequestFingerprint.sha256("service-token", token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public String redact(String value) {
        if (value == null) {
            return null;
        }
        String escaped = new String(JsonStringEncoder.getInstance().quoteAsString(token));
        if (!value.contains(token) && !value.contains(escaped)) {
            return value;
        }
        String replacement = "[REDACTED]".contains(token) ? ("***".contains(token) ? "" : "***") : "[REDACTED]";
        try {
            JsonNode original = MAPPER.readTree(value);
            if (original != null) {
                JsonNode redacted = redactNode(original.deepCopy(), replacement, false, false);
                return redacted.equals(original) ? value : MAPPER.writeValueAsString(redacted);
            }
        } catch (JsonProcessingException ignored) {
            // Some adapters return plain text rather than JSON.
        }
        return value.replace(escaped, replacement).replace(token, replacement);
    }

    private JsonNode redactNode(JsonNode node, String replacement, boolean sensitive, boolean identifier) {
        if (node.isTextual()) {
            return sensitive || !identifier
                    ? TextNode.valueOf(node.textValue().replace(token, replacement))
                    : node;
        }
        if (node instanceof ObjectNode object) {
            object.fields().forEachRemaining(field -> {
                String name = field.getKey();
                object.set(name, redactNode(field.getValue(), replacement,
                        sensitive || isDiagnosticField(name), "id".equals(name)));
            });
        } else if (node instanceof ArrayNode array) {
            for (int index = 0; index < array.size(); index++) {
                array.set(index, redactNode(array.get(index), replacement, sensitive, identifier));
            }
        }
        return node;
    }

    private static boolean isDiagnosticField(String name) {
        return "error".equals(name) || "driverLog".equals(name) || "scriptLog".equals(name);
    }

    @Override
    public String toString() {
        return "ServiceAuth[token=<redacted>]";
    }
}
