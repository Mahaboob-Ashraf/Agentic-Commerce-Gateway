package dev.agenticcommerce.gateway.agentization.tool;

import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import dev.agenticcommerce.gateway.agentization.model.AgentObservation;
import dev.agenticcommerce.gateway.agentization.service.AgentizationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Live reasoning boundary. The SDK can select only schema-enumerated typed actions. */
@Component
@Primary
@ConditionalOnProperty(prefix = "agentization.gemini", name = "enabled", havingValue = "true")
public class GeminiAgentizationDecisionProvider implements AgentizationDecisionProvider {

    private static final int MAX_PROMPT_CHARACTERS = 32_000;
    private final Client client;
    private final String model;
    private final ObjectMapper applicationMapper;
    private final ObjectMapper strictMapper;

    public GeminiAgentizationDecisionProvider(
            @Value("${agentization.gemini.api-key}") String apiKey,
            @Value("${agentization.gemini.model:gemini-3.6-flash}") String model,
            ObjectMapper applicationMapper) {
        this.client = Client.builder().apiKey(apiKey).build();
        this.model = model;
        this.applicationMapper = applicationMapper;
        this.strictMapper = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    @Override
    public NextAgentAction chooseNextAction(AgentDecisionContext context) {
        String feedback = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                String prompt = prompt(context, feedback);
                var response = client.models.generateContent(model, prompt, GenerateContentConfig.builder()
                        .temperature(0.0f)
                        .maxOutputTokens(2048)
                        .responseMimeType("application/json")
                        .responseJsonSchema(responseSchema(context))
                        .build());
                NextAgentAction action = strictMapper.readValue(response.text(), NextAgentAction.class);
                validateAction(context, action);
                return action;
            } catch (RuntimeException exception) {
                feedback = "The prior response was invalid. Return only the schema-defined action. Validation: "
                        + bounded(exception.getMessage(), 400);
            }
        }
        throw new AgentizationException(
                "INVALID_AGENT_DECISION", HttpStatus.UNPROCESSABLE_ENTITY,
                "Gemini did not return a valid bounded agent action after one retry");
    }

    private String prompt(AgentDecisionContext context, String feedback) {
        var prompt = new LinkedHashMap<String, Object>();
        prompt.put("instruction", "Select exactly one permitted typed tool. Do not execute HTTP, alter test truth, "
                + "approve a mapping, publish readiness, provide code, or invent tools. Give only a concise rationale.");
        prompt.put("runId", context.runId());
        prompt.put("artifactId", context.artifactId());
        prompt.put("capability", context.targetCapability());
        prompt.put("state", context.state());
        prompt.put("step", context.stepCount());
        prompt.put("maximumSteps", context.maximumSteps());
        prompt.put("permittedTools", context.permittedTools());
        prompt.put("recentEvidence", context.recentObservations().stream()
                .map(this::boundedObservation)
                .toList());
        if (feedback != null) prompt.put("validationFeedback", feedback);
        String serialized = applicationMapper.writeValueAsString(prompt);
        if (serialized.length() > MAX_PROMPT_CHARACTERS) {
            throw new AgentizationException(
                    "AGENT_CONTEXT_TOO_LARGE", HttpStatus.UNPROCESSABLE_ENTITY,
                    "Bounded agent decision context exceeds its limit");
        }
        return serialized;
    }

    private Map<String, Object> boundedObservation(AgentObservation observation) {
        return Map.of(
                "step", observation.stepNumber(),
                "tool", observation.toolName(),
                "outcome", observation.outcome(),
                "reasonCode", observation.reasonCode(),
                "result", bounded(observation.structuredResult().toString(), 2500));
    }

    private static Map<String, Object> responseSchema(AgentDecisionContext context) {
        Map<String, Object> string = Map.of("type", "string");
        Map<String, Object> nullableString = Map.of("anyOf", List.of(string, Map.of("type", "null")));
        var properties = new LinkedHashMap<String, Object>();
        properties.put("tool", Map.of(
                "type", "string",
                "enum", context.permittedTools().stream().map(Enum::name).toList()));
        properties.put("artifactId", Map.of("type", "string", "format", "uuid"));
        for (String name : List.of("pathFilter", "methodFilter", "operationFilter", "schemaReference",
                "testCaseId", "conciseReason")) {
            properties.put(name, nullableString);
        }
        for (String name : List.of("maximumResults", "maximumSchemaDepth", "maximumSchemaFields")) {
            properties.put(name, Map.of("anyOf", List.of(Map.of("type", "integer"), Map.of("type", "null"))));
        }
        for (String name : List.of("mappingProposalId", "contractTestRunId", "policyDocumentId")) {
            properties.put(name, nullableString);
        }
        properties.put("mappingProposal", Map.of("anyOf", List.of(Map.of("type", "object"), Map.of("type", "null"))));
        properties.put("mappingRevision", Map.of("anyOf", List.of(Map.of("type", "object"), Map.of("type", "null"))));
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", properties,
                "required", List.of("tool", "artifactId", "conciseReason"));
    }

    private static void validateAction(AgentDecisionContext context, NextAgentAction action) {
        if (action == null || action.tool() == null || !context.permittedTools().contains(action.tool())
                || !context.artifactId().equals(action.artifactId())) {
            throw new IllegalArgumentException("Action is outside the persisted run/tool context");
        }
        switch (action.tool()) {
            case PROPOSE_MAPPING -> require(action.mappingProposal() != null, "mappingProposal");
            case VALIDATE_MAPPING, RUN_CONTRACT_TEST -> require(action.mappingProposalId() != null, "mappingProposalId");
            case INSPECT_TEST_FAILURE -> require(action.contractTestRunId() != null, "contractTestRunId");
            case REVISE_MAPPING -> require(action.mappingRevision() != null, "mappingRevision");
            case INSPECT_POLICY, EXTRACT_POLICY_RULES -> require(action.policyDocumentId() != null, "policyDocumentId");
            case REQUEST_MERCHANT_APPROVAL -> require(
                    action.mappingProposalId() != null || action.policyDocumentId() != null,
                    "mappingProposalId or policyDocumentId");
            case REQUEST_MERCHANT_CLARIFICATION -> require(action.pathFilter() != null, "pathFilter(question)");
            default -> {
                // Inspection arguments are independently bounded by NextAgentAction.
            }
        }
    }

    private static void require(boolean condition, String field) {
        if (!condition) throw new IllegalArgumentException(field + " is required for this tool");
    }

    private static String bounded(String value, int maximum) {
        if (value == null) return "unspecified";
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }
}
