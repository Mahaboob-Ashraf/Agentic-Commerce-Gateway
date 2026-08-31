package dev.agenticcommerce.gateway.agentization.service;

import dev.agenticcommerce.gateway.agentization.model.CapabilityMappingProposal;
import dev.agenticcommerce.gateway.agentization.model.MappingTransformation;
import dev.agenticcommerce.gateway.agentization.model.MappingValidationResult;
import dev.agenticcommerce.gateway.agentization.persistence.ApprovedMerchantEndpointRepository;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class ExecutableMappingValidator {

    private static final Pattern BINDING = Pattern.compile("body(?:\\.[A-Za-z_][A-Za-z0-9_]*){1,8}");
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private final ApprovedMerchantEndpointRepository endpointRepository;

    public ExecutableMappingValidator(ApprovedMerchantEndpointRepository endpointRepository) {
        this.endpointRepository = endpointRepository;
    }

    public MappingValidationResult validate(CapabilityMappingProposal mapping) {
        var endpoint = endpointRepository.findByMerchantAndId(mapping.merchantId(), mapping.endpointId())
                .orElseThrow(() -> invalid("MAPPING_ENDPOINT_NOT_APPROVED", "Mapping endpoint is not approved"));
        if (!METHODS.contains(mapping.httpMethod()) || !endpoint.approvedMethods().contains(mapping.httpMethod())) {
            throw invalid("MAPPING_METHOD_NOT_APPROVED", "Mapping method is outside endpoint approval scope");
        }
        if (!endpoint.approvedPathTemplates().contains(mapping.pathTemplate())) {
            throw invalid("MAPPING_PATH_NOT_APPROVED", "Mapping path is outside endpoint approval scope");
        }
        validateBindings(mapping.requestBindings(), "REQUEST");
        validateBindings(mapping.responseBindings(), "RESPONSE");
        validateTransformations(mapping.transformations(), mapping.responseBindings());
        String unit = mapping.amountInterpretation().path("unit").asText("");
        if (!Set.of("minor", "rupees").contains(unit)) {
            throw invalid("MAPPING_MONEY_UNIT_INVALID", "Money interpretation must be minor or rupees");
        }
        String currencyField = mapping.currencyInterpretation().path("field").asText("");
        if (!currencyField.isBlank() && !isBinding(currencyField.startsWith("body.")
                ? currencyField : "body." + currencyField)) {
            throw invalid("MAPPING_CURRENCY_BINDING_INVALID", "Currency binding is malformed");
        }
        return new MappingValidationResult(
                mapping.mappingProposalId(), mapping.mappingVersion(), true, "MAPPING_VALID");
    }

    private static void validateBindings(JsonNode bindings, String kind) {
        if (bindings == null || !bindings.isObject()) {
            throw invalid("MAPPING_" + kind + "_BINDINGS_INVALID", "Bindings must be an object");
        }
        Iterator<Map.Entry<String, JsonNode>> fields = bindings.properties().iterator();
        while (fields.hasNext()) {
            var field = fields.next();
            if (!field.getValue().isTextual() || !isBinding(field.getValue().asText())) {
                throw invalid("MAPPING_" + kind + "_BINDING_INVALID", "Binding expression is malformed");
            }
        }
    }

    private static void validateTransformations(JsonNode transformations, JsonNode responseBindings) {
        if (transformations == null || !transformations.isObject()) {
            throw invalid("MAPPING_TRANSFORMATIONS_INVALID", "Transformations must be an object");
        }
        Iterator<Map.Entry<String, JsonNode>> fields = transformations.properties().iterator();
        while (fields.hasNext()) {
            var field = fields.next();
            if (!responseBindings.has(field.getKey()) || !field.getValue().isTextual()) {
                throw invalid("MAPPING_TRANSFORMATION_TARGET_INVALID", "Transformation target is not a response binding");
            }
            try {
                MappingTransformation.valueOf(field.getValue().asText());
            } catch (IllegalArgumentException exception) {
                throw invalid("MAPPING_TRANSFORMATION_UNSUPPORTED", "Transformation is not in the safe vocabulary");
            }
        }
    }

    private static boolean isBinding(String value) {
        return value != null && BINDING.matcher(value).matches();
    }

    private static AgentizationException invalid(String code, String message) {
        return new AgentizationException(code, HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
