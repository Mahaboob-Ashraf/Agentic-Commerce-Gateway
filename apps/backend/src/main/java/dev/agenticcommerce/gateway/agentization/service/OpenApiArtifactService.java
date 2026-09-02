package dev.agenticcommerce.gateway.agentization.service;

import dev.agenticcommerce.gateway.agentization.model.OpenApiArtifact;
import dev.agenticcommerce.gateway.agentization.persistence.ApprovedMerchantEndpointRepository;
import dev.agenticcommerce.gateway.agentization.persistence.OpenApiArtifactRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
public class OpenApiArtifactService {

    private final ApprovedMerchantEndpointRepository endpointRepository;
    private final OpenApiArtifactRepository artifactRepository;
    private final CanonicalJsonService canonicalJsonService;

    public OpenApiArtifactService(
            ApprovedMerchantEndpointRepository endpointRepository,
            OpenApiArtifactRepository artifactRepository,
            CanonicalJsonService canonicalJsonService) {
        this.endpointRepository = endpointRepository;
        this.artifactRepository = artifactRepository;
        this.canonicalJsonService = canonicalJsonService;
    }

    @Transactional
    public OpenApiArtifact register(
            UUID merchantId, UUID endpointId, String artifactVersion, JsonNode document) {
        endpointRepository.findByMerchantAndId(merchantId, endpointId)
                .orElseThrow(() -> new AgentizationException(
                        "APPROVED_ENDPOINT_NOT_FOUND", HttpStatus.NOT_FOUND,
                        "Approved merchant endpoint was not found"));
        validateDocument(document);
        String canonical = canonicalJsonService.canonicalize(document);
        return artifactRepository.create(
                merchantId,
                endpointId,
                requireBoundedText(artifactVersion, 64, "artifactVersion"),
                canonicalJsonService.hashText(canonical),
                canonical);
    }

    public OpenApiArtifact requireArtifact(UUID merchantId, UUID artifactId) {
        return artifactRepository.findByMerchantAndId(merchantId, artifactId)
                .orElseThrow(() -> new AgentizationException(
                        "OPENAPI_ARTIFACT_NOT_FOUND", HttpStatus.NOT_FOUND,
                "OpenAPI artifact was not found"));
    }

    @Transactional
    public OpenApiArtifact registerOrReuse(
            UUID merchantId, UUID endpointId, String artifactVersion, JsonNode document) {
        endpointRepository.findByMerchantAndId(merchantId, endpointId)
                .orElseThrow(() -> new AgentizationException(
                        "APPROVED_ENDPOINT_NOT_FOUND", HttpStatus.NOT_FOUND,
                        "Approved merchant endpoint was not found"));
        validateDocument(document);
        String normalizedVersion = requireBoundedText(artifactVersion, 64, "artifactVersion");
        String canonical = canonicalJsonService.canonicalize(document);
        String contentHash = canonicalJsonService.hashText(canonical);
        return artifactRepository.findByApprovedContent(
                        merchantId, endpointId, normalizedVersion, contentHash)
                .orElseGet(() -> artifactRepository.create(
                        merchantId, endpointId, normalizedVersion, contentHash, canonical));
    }

    private static void validateDocument(JsonNode document) {
        if (document == null || !document.isObject()) {
            throw new AgentizationException(
                    "INVALID_OPENAPI_DOCUMENT", HttpStatus.BAD_REQUEST,
                    "OpenAPI document must be a JSON object");
        }
        String version = document.path("openapi").asText("");
        if (version.isBlank() || version.length() > 32 || !document.path("paths").isObject()) {
            throw new AgentizationException(
                    "INVALID_OPENAPI_DOCUMENT", HttpStatus.BAD_REQUEST,
                    "OpenAPI version and paths are required");
        }
        validateReferences(document, 0);
    }

    private static void validateReferences(JsonNode node, int depth) {
        if (depth > 100) {
            throw new AgentizationException(
                    "OPENAPI_DOCUMENT_TOO_DEEP", HttpStatus.BAD_REQUEST,
                    "OpenAPI document nesting exceeds the safety limit");
        }
        if (node.isObject()) {
            JsonNode reference = node.get("$ref");
            if (reference != null) {
                String value = reference.asText("");
                if (!value.startsWith("#/components/schemas/") || value.length() > 512) {
                    throw new AgentizationException(
                            "EXTERNAL_REFERENCE_UNSUPPORTED", HttpStatus.BAD_REQUEST,
                            "Only bounded local component schema references are supported");
                }
            }
        }
        node.forEach(child -> validateReferences(child, depth + 1));
    }

    private static String requireBoundedText(String value, int maximumLength, String field) {
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new AgentizationException(
                    "INVALID_" + field.toUpperCase(), HttpStatus.BAD_REQUEST,
                    field + " is required and must be bounded");
        }
        return value.strip();
    }
}
