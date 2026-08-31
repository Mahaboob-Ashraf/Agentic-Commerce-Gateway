package dev.agenticcommerce.gateway.agentization.inspection;

import dev.agenticcommerce.gateway.agentization.model.OpenApiArtifact;
import dev.agenticcommerce.gateway.agentization.service.AgentizationException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class OpenApiInspectionService {

    public static final int MAX_OPERATION_RESULTS = 50;
    public static final int MAX_SCHEMA_DEPTH = 5;
    public static final int MAX_SCHEMA_FIELDS = 100;
    public static final int MAX_STRUCTURED_OUTPUT_CHARACTERS = 32_000;
    private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "patch", "delete");

    private final ObjectMapper objectMapper;

    public OpenApiInspectionService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public SpecInspectionResult inspectSpec(
            OpenApiArtifact artifact,
            String pathFilter,
            String methodFilter,
            String operationFilter,
            Integer requestedMaximumResults) {
        int maximumResults = bounded(requestedMaximumResults, 20, 1, MAX_OPERATION_RESULTS);
        String normalizedMethod = methodFilter == null ? null : methodFilter.toLowerCase(Locale.ROOT);
        if (normalizedMethod != null
                && !normalizedMethod.isBlank()
                && !HTTP_METHODS.contains(normalizedMethod)) {
            throw new AgentizationException(
                    "INVALID_HTTP_METHOD_FILTER", HttpStatus.BAD_REQUEST,
                    "Inspection HTTP method filter is unsupported");
        }
        List<OperationObservation> observations = new ArrayList<>();
        boolean[] truncated = {false};
        JsonNode paths = artifact.document().path("paths");
        paths.propertyNames().stream().sorted().forEach(path -> {
            if (truncated[0] || !matches(path, pathFilter)) {
                return;
            }
            JsonNode pathItem = paths.path(path);
            HTTP_METHODS.stream().sorted().forEach(method -> {
                if (truncated[0] || (normalizedMethod != null && !normalizedMethod.equals(method))) {
                    return;
                }
                JsonNode operation = pathItem.get(method);
                if (operation == null || !operation.isObject()) {
                    return;
                }
                String operationId = safeText(operation.path("operationId").asText(""), 256);
                if (!matches(operationId, operationFilter)) {
                    return;
                }
                if (observations.size() >= maximumResults) {
                    truncated[0] = true;
                    return;
                }
                observations.add(toObservation(path, method, pathItem, operation));
            });
        });
        SpecInspectionResult result = new SpecInspectionResult(
                safeText(artifact.document().path("openapi").asText(""), 32),
                List.copyOf(observations),
                truncated[0]);
        enforceOutputLimit(result);
        return result;
    }

    public SchemaInspectionResult inspectSchema(
            OpenApiArtifact artifact,
            String schemaReference,
            Integer requestedDepth,
            Integer requestedFields) {
        requireLocalSchemaReference(schemaReference);
        int maximumDepth = bounded(requestedDepth, 3, 0, MAX_SCHEMA_DEPTH);
        int maximumFields = bounded(requestedFields, 50, 1, MAX_SCHEMA_FIELDS);
        List<SchemaFieldObservation> fields = new ArrayList<>();
        boolean[] truncated = {false};
        inspectSchemaNode(
                artifact.document(),
                resolve(artifact.document(), schemaReference),
                schemaReference,
                "",
                false,
                0,
                maximumDepth,
                maximumFields,
                fields,
                new HashSet<>(),
                truncated);
        SchemaInspectionResult result = new SchemaInspectionResult(
                schemaReference, List.copyOf(fields), maximumDepth, truncated[0]);
        enforceOutputLimit(result);
        return result;
    }

    private OperationObservation toObservation(
            String path, String method, JsonNode pathItem, JsonNode operation) {
        List<ParameterObservation> parameters = new ArrayList<>();
        addParameters(parameters, pathItem.path("parameters"));
        addParameters(parameters, operation.path("parameters"));
        if (parameters.size() > 50) {
            parameters = new ArrayList<>(parameters.subList(0, 50));
        }
        Map<String, String> responseReferences = new LinkedHashMap<>();
        JsonNode responses = operation.path("responses");
        responses.propertyNames().stream().sorted().limit(20).forEach(status -> {
            String reference = schemaReferenceFromContent(responses.path(status));
            if (reference != null) {
                responseReferences.put(safeText(status, 32), reference);
            }
        });
        List<String> tags = new ArrayList<>();
        operation.path("tags").forEach(tag -> {
            if (tags.size() < 10) {
                tags.add(safeText(tag.asText(""), 128));
            }
        });
        return new OperationObservation(
                safeText(path, 1024),
                method.toUpperCase(Locale.ROOT),
                safeText(operation.path("operationId").asText(""), 256),
                safeText(operation.path("summary").asText(""), 500),
                safeText(operation.path("description").asText(""), 1_000),
                List.copyOf(parameters),
                schemaReferenceFromContent(operation.path("requestBody")),
                Map.copyOf(responseReferences),
                List.copyOf(tags));
    }

    private static void addParameters(List<ParameterObservation> target, JsonNode parameters) {
        if (!parameters.isArray()) {
            return;
        }
        parameters.forEach(parameter -> {
            if (target.size() < 100 && parameter.isObject()) {
                target.add(new ParameterObservation(
                        safeText(parameter.path("name").asText(""), 128),
                        safeText(parameter.path("in").asText(""), 32),
                        safeText(parameter.path("schema").path("type").asText("unknown"), 64),
                        parameter.path("required").asBoolean(false)));
            }
        });
    }

    private static String schemaReferenceFromContent(JsonNode container) {
        JsonNode schema = container.path("content").path("application/json").path("schema");
        String reference = schema.path("$ref").asText("");
        if (!reference.isBlank()) {
            requireLocalSchemaReference(reference);
            return reference;
        }
        String type = schema.path("type").asText("");
        return type.isBlank() ? null : "inline:" + safeText(type, 64);
    }

    private void inspectSchemaNode(
            JsonNode document,
            JsonNode schema,
            String currentReference,
            String parentPath,
            boolean required,
            int depth,
            int maximumDepth,
            int maximumFields,
            List<SchemaFieldObservation> fields,
            Set<String> activeReferences,
            boolean[] truncated) {
        if (fields.size() >= maximumFields) {
            truncated[0] = true;
            return;
        }
        if (depth > maximumDepth) {
            truncated[0] = true;
            return;
        }
        String reference = schema.path("$ref").asText("");
        if (!reference.isBlank()) {
            requireLocalSchemaReference(reference);
            if (!activeReferences.add(reference)) {
                truncated[0] = true;
                return;
            }
            inspectSchemaNode(
                    document, resolve(document, reference), reference, parentPath, required,
                    depth, maximumDepth, maximumFields, fields, activeReferences, truncated);
            activeReferences.remove(reference);
            return;
        }

        JsonNode properties = schema.path("properties");
        Set<String> requiredNames = new LinkedHashSet<>();
        schema.path("required").forEach(name -> requiredNames.add(name.asText("")));
        if (!properties.isObject()) {
            return;
        }
        for (String name : properties.propertyNames().stream().sorted().toList()) {
            if (fields.size() >= maximumFields) {
                truncated[0] = true;
                return;
            }
            JsonNode property = properties.path(name);
            String path = parentPath.isBlank()
                    ? safeText(name, 128)
                    : parentPath + "." + safeText(name, 128);
            String propertyReference = nestedReference(property);
            fields.add(new SchemaFieldObservation(
                    path,
                    propertyType(property),
                    requiredNames.contains(name),
                    propertyReference,
                    enumValues(property.path("enum")),
                    integerOrNull(property.get("minLength")),
                    integerOrNull(property.get("maxLength")),
                    decimalOrNull(property.get("minimum")),
                    decimalOrNull(property.get("maximum"))));
            JsonNode nested = property.path("type").asText("").equals("array")
                    ? property.path("items") : property;
            if (propertyReference != null) {
                inspectSchemaNode(
                        document, nested, propertyReference, path, requiredNames.contains(name),
                        depth + 1, maximumDepth, maximumFields, fields, activeReferences, truncated);
            } else if (nested.path("properties").isObject()) {
                inspectSchemaNode(
                        document, nested, currentReference, path, requiredNames.contains(name),
                        depth + 1, maximumDepth, maximumFields, fields, activeReferences, truncated);
            }
        }
    }

    private static String nestedReference(JsonNode property) {
        String direct = property.path("$ref").asText("");
        String array = property.path("items").path("$ref").asText("");
        String reference = direct.isBlank() ? array : direct;
        if (reference.isBlank()) {
            return null;
        }
        requireLocalSchemaReference(reference);
        return reference;
    }

    private static String propertyType(JsonNode property) {
        String type = property.path("type").asText("");
        if (!type.isBlank()) {
            return safeText(type, 64);
        }
        return property.has("$ref") ? "object" : "unknown";
    }

    private static List<String> enumValues(JsonNode enumNode) {
        List<String> values = new ArrayList<>();
        enumNode.forEach(value -> {
            if (values.size() < 20) {
                values.add(safeText(value.asText(""), 128));
            }
        });
        return List.copyOf(values);
    }

    private static JsonNode resolve(JsonNode document, String reference) {
        JsonNode resolved = document.at(reference.substring(1));
        if (resolved.isMissingNode() || resolved.isNull()) {
            throw new AgentizationException(
                    "LOCAL_SCHEMA_NOT_FOUND", HttpStatus.UNPROCESSABLE_ENTITY,
                    "The requested local component schema was not found");
        }
        return resolved;
    }

    private void enforceOutputLimit(Object value) {
        if (objectMapper.writeValueAsString(value).length() > MAX_STRUCTURED_OUTPUT_CHARACTERS) {
            throw new AgentizationException(
                    "INSPECTION_OUTPUT_LIMIT", HttpStatus.UNPROCESSABLE_ENTITY,
                    "Structured inspection output exceeds the safety limit");
        }
    }

    private static void requireLocalSchemaReference(String reference) {
        if (reference == null
                || !reference.startsWith("#/components/schemas/")
                || reference.length() > 512) {
            throw new AgentizationException(
                    "EXTERNAL_REFERENCE_UNSUPPORTED", HttpStatus.UNPROCESSABLE_ENTITY,
                    "Only local component schema references are supported");
        }
    }

    private static boolean matches(String value, String filter) {
        return filter == null || filter.isBlank()
                || value.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT));
    }

    private static String safeText(String value, int maximumLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.strip();
        return normalized.length() <= maximumLength
                ? normalized : normalized.substring(0, maximumLength);
    }

    private static int bounded(Integer requested, int fallback, int minimum, int maximum) {
        int value = requested == null ? fallback : requested;
        if (value < minimum || value > maximum) {
            throw new AgentizationException(
                    "INVALID_INSPECTION_LIMIT", HttpStatus.BAD_REQUEST,
                    "Inspection limit is outside the allowed range");
        }
        return value;
    }

    private static Integer integerOrNull(JsonNode value) {
        return value == null || !value.isNumber() ? null : value.asInt();
    }

    private static BigDecimal decimalOrNull(JsonNode value) {
        return value == null || !value.isNumber() ? null : value.decimalValue();
    }
}
