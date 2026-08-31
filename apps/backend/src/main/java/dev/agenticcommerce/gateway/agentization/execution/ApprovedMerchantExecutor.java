package dev.agenticcommerce.gateway.agentization.execution;

import dev.agenticcommerce.gateway.agentization.model.CapabilityMappingProposal;
import dev.agenticcommerce.gateway.agentization.persistence.ApprovedMerchantEndpointRepository;
import dev.agenticcommerce.gateway.agentization.service.AgentizationException;
import dev.agenticcommerce.gateway.agentization.service.ExecutableMappingValidator;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ApprovedMerchantExecutor {

    private static final Pattern PATH_PARAMETER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*)}");
    private final ApprovedMerchantEndpointRepository endpointRepository;
    private final MerchantEndpointSafetyService safetyService;
    private final ExecutableMappingValidator mappingValidator;
    private final MerchantTransport transport;

    public ApprovedMerchantExecutor(
            ApprovedMerchantEndpointRepository endpointRepository,
            MerchantEndpointSafetyService safetyService,
            ExecutableMappingValidator mappingValidator,
            MerchantTransport transport) {
        this.endpointRepository = endpointRepository;
        this.safetyService = safetyService;
        this.mappingValidator = mappingValidator;
        this.transport = transport;
    }

    public MerchantTransportResponse execute(
            UUID merchantId,
            CapabilityMappingProposal mapping,
            Map<String, String> pathParameters,
            byte[] jsonBody,
            MerchantExecutionMode mode) {
        if (!mapping.merchantId().equals(merchantId)) {
            throw denied("MAPPING_TENANT_MISMATCH", "Mapping does not belong to merchant");
        }
        if (!"VALID".equals(mapping.validationStatus())) {
            throw denied("MAPPING_NOT_VALIDATED", "Mapping has not passed deterministic validation");
        }
        mappingValidator.validate(mapping);
        var endpoint = endpointRepository.findByMerchantAndId(merchantId, mapping.endpointId())
                .orElseThrow(() -> denied("ENDPOINT_NOT_APPROVED", "Endpoint is not approved"));
        if (endpoint.credentialReference() != null) {
            throw denied("ENDPOINT_CREDENTIAL_UNAVAILABLE", "Credential reference execution is not configured");
        }
        if (mode == null) {
            throw denied("EXECUTION_MODE_REQUIRED", "Execution mode is required");
        }
        byte[] boundedBody = jsonBody == null ? new byte[0] : jsonBody.clone();
        if (boundedBody.length > mapping.maximumRequestBytes()) {
            throw new MerchantExecutionException("MERCHANT_REQUEST_TOO_LARGE", "Request exceeds mapping limit");
        }
        String path = bindPath(mapping.pathTemplate(), pathParameters == null ? Map.of() : pathParameters);
        ValidatedEndpointResolution resolution = safetyService.validateAndResolve(endpoint.baseUri());
        if (!resolution.hostname().equals(endpoint.hostname()) || resolution.port() != endpoint.port()) {
            throw denied("ENDPOINT_RUNTIME_IDENTITY_MISMATCH", "Endpoint identity changed since approval");
        }
        URI target = targetUri(resolution.baseUri(), path);
        return transport.execute(resolution, new MerchantTransportRequest(
                target,
                mapping.httpMethod(),
                boundedBody,
                mapping.connectTimeoutMs(),
                mapping.requestTimeoutMs(),
                mapping.maximumResponseBytes()));
    }

    private static String bindPath(String template, Map<String, String> parameters) {
        Matcher matcher = PATH_PARAMETER.matcher(template);
        StringBuffer rendered = new StringBuffer();
        int used = 0;
        while (matcher.find()) {
            String value = parameters.get(matcher.group(1));
            if (value == null) {
                throw denied("PATH_PARAMETER_MISSING", "Approved path parameter is missing");
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(percentEncodeSegment(value)));
            used++;
        }
        matcher.appendTail(rendered);
        if (used != parameters.size() || rendered.toString().contains("..") || rendered.toString().contains("\\")) {
            throw denied("PATH_PARAMETER_INVALID", "Path parameters do not match the approved template");
        }
        return rendered.toString();
    }

    private static String percentEncodeSegment(String value) {
        if (value.isBlank() || value.length() > 256) {
            throw denied("PATH_PARAMETER_INVALID", "Path parameter is invalid");
        }
        StringBuilder result = new StringBuilder();
        for (byte current : value.getBytes(StandardCharsets.UTF_8)) {
            int unsigned = Byte.toUnsignedInt(current);
            if ((unsigned >= 'a' && unsigned <= 'z') || (unsigned >= 'A' && unsigned <= 'Z')
                    || (unsigned >= '0' && unsigned <= '9') || "-._~".indexOf(unsigned) >= 0) {
                result.append((char) unsigned);
            } else {
                result.append('%').append(String.format("%02X", unsigned));
            }
        }
        return result.toString();
    }

    private static URI targetUri(URI base, String path) {
        String basePath = base.getRawPath() == null ? "" : base.getRawPath();
        try {
            return new URI("https", null, base.getHost(), base.getPort(), basePath + path, null, null);
        } catch (URISyntaxException exception) {
            throw denied("EXECUTION_PATH_INVALID", "Approved execution path is invalid");
        }
    }

    private static AgentizationException denied(String code, String message) {
        return new AgentizationException(code, HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
