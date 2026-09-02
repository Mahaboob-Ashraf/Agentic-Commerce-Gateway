package dev.agenticcommerce.gateway.agentization.service;

import dev.agenticcommerce.gateway.agentization.execution.MerchantEndpointSafetyService;
import dev.agenticcommerce.gateway.agentization.model.ApprovedMerchantEndpoint;
import dev.agenticcommerce.gateway.agentization.persistence.ApprovedMerchantEndpointRepository;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApprovedMerchantEndpointService {

    private static final Set<String> SUPPORTED_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private final MerchantAgentizationAccessService accessService;
    private final MerchantEndpointSafetyService safetyService;
    private final ApprovedMerchantEndpointRepository repository;

    public ApprovedMerchantEndpointService(
            MerchantAgentizationAccessService accessService,
            MerchantEndpointSafetyService safetyService,
            ApprovedMerchantEndpointRepository repository) {
        this.accessService = accessService;
        this.safetyService = safetyService;
        this.repository = repository;
    }

    @Transactional
    public ApprovedMerchantEndpoint registerAndApprove(
            UUID actorId, UUID merchantId, String baseUri, Set<String> methods, List<String> paths) {
        return registerAndApprove(actorId, merchantId, baseUri, methods, paths, null);
    }

    @Transactional
    public ApprovedMerchantEndpoint registerAndApprove(
            UUID actorId, UUID merchantId, String baseUri, Set<String> methods, List<String> paths,
            String credentialReference) {
        accessService.requireMerchantAdmin(actorId, merchantId);
        var resolution = safetyService.validateAndResolve(baseUri);
        Set<String> normalizedMethods = methods.stream()
                .map(method -> method.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        if (normalizedMethods.isEmpty() || normalizedMethods.size() > 5
                || !SUPPORTED_METHODS.containsAll(normalizedMethods)) {
            throw invalid("ENDPOINT_METHOD_SCOPE_INVALID", "Approved method scope is invalid");
        }
        if (paths.isEmpty() || paths.size() > 32) {
            throw invalid("ENDPOINT_PATH_SCOPE_INVALID", "Approved path scope is invalid");
        }
        List<String> normalizedPaths = paths.stream().map(ApprovedMerchantEndpointService::validatePath).toList();
        return repository.createApproved(
                merchantId,
                actorId,
                resolution.baseUri().toString(),
                resolution.hostname(),
                resolution.port(),
                normalizedMethods,
                normalizedPaths,
                resolution.addresses().stream().map(address -> address.getHostAddress()).toList(),
                validateCredentialReference(credentialReference));
    }

    public ApprovedMerchantEndpoint requireApproved(UUID merchantId, UUID endpointId) {
        return repository.findByMerchantAndId(merchantId, endpointId)
                .orElseThrow(() -> new AgentizationException(
                        "APPROVED_ENDPOINT_NOT_FOUND", HttpStatus.NOT_FOUND,
                "Approved merchant endpoint was not found"));
    }

    /** Reuses only the same approved endpoint identity and exact bounded execution scope. */
    @Transactional
    public ApprovedMerchantEndpoint registerOrReuseApproved(
            UUID actorId, UUID merchantId, String baseUri, Set<String> methods, List<String> paths,
            String credentialReference) {
        accessService.requireMerchantAdmin(actorId, merchantId);
        var resolution = safetyService.validateAndResolve(baseUri);
        Set<String> normalizedMethods = methods.stream()
                .map(method -> method.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        List<String> normalizedPaths = paths.stream().map(ApprovedMerchantEndpointService::validatePath).toList();
        String normalizedCredential = validateCredentialReference(credentialReference);
        var existing = repository.findApprovedByIdentity(
                merchantId, resolution.baseUri().toString(), normalizedCredential);
        if (existing.isPresent()
                && existing.get().hostname().equals(resolution.hostname())
                && existing.get().port() == resolution.port()
                && existing.get().approvedMethods().equals(normalizedMethods)
                && existing.get().approvedPathTemplates().equals(normalizedPaths)) {
            return existing.get();
        }
        return registerAndApprove(
                actorId, merchantId, baseUri, normalizedMethods, normalizedPaths, normalizedCredential);
    }

    private static String validatePath(String path) {
        if (path == null || path.isBlank() || !path.startsWith("/") || path.length() > 1024
                || path.contains("..") || path.contains("\\") || path.contains("?") || path.contains("#")) {
            throw invalid("ENDPOINT_PATH_SCOPE_INVALID", "Approved path template is invalid");
        }
        return path;
    }

    private static String validateCredentialReference(String reference) {
        if (reference == null) return null;
        String normalized = reference.strip();
        if (normalized.isEmpty() || normalized.length() > 256) {
            throw invalid("ENDPOINT_CREDENTIAL_REFERENCE_INVALID", "Credential reference is invalid");
        }
        return normalized;
    }

    private static AgentizationException invalid(String code, String message) {
        return new AgentizationException(code, HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
