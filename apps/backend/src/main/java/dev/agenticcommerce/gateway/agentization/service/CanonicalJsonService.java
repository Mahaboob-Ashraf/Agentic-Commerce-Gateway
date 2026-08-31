package dev.agenticcommerce.gateway.agentization.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class CanonicalJsonService {

    public static final int MAX_ARTIFACT_CHARACTERS = 1_000_000;

    private final ObjectMapper objectMapper;

    public CanonicalJsonService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String canonicalize(JsonNode value) {
        String canonical = objectMapper.writeValueAsString(sort(value));
        if (canonical.length() > MAX_ARTIFACT_CHARACTERS) {
            throw new AgentizationException(
                    "ARTIFACT_TOO_LARGE", HttpStatus.BAD_REQUEST, "OpenAPI artifact exceeds the limit");
        }
        return canonical;
    }

    public String hash(JsonNode value) {
        return sha256(canonicalize(value));
    }

    public String hashText(String value) {
        return sha256(value);
    }

    public JsonNode sort(JsonNode value) {
        if (value == null || value.isNull() || value.isValueNode()) {
            return value;
        }
        if (value.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            value.forEach(item -> result.add(sort(item)));
            return result;
        }
        ObjectNode result = objectMapper.createObjectNode();
        var names = new ArrayList<>(value.propertyNames());
        names.sort(Comparator.naturalOrder());
        names.forEach(name -> result.set(name, sort(value.get(name))));
        return result;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JVM", impossible);
        }
    }
}
