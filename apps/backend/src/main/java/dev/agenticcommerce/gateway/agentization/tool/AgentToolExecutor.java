package dev.agenticcommerce.gateway.agentization.tool;

import dev.agenticcommerce.gateway.agentization.inspection.OpenApiInspectionService;
import dev.agenticcommerce.gateway.agentization.authority.AuthorityModels.ClarificationKind;
import dev.agenticcommerce.gateway.agentization.authority.MerchantAuthorityService;
import dev.agenticcommerce.gateway.agentization.authority.PolicyAuthorityService;
import dev.agenticcommerce.gateway.agentization.model.AgentizationRun;
import dev.agenticcommerce.gateway.agentization.model.CapabilityContractTestRun;
import dev.agenticcommerce.gateway.agentization.model.CapabilityMappingProposal;
import dev.agenticcommerce.gateway.agentization.model.ContractTestOutcome;
import dev.agenticcommerce.gateway.agentization.model.MappingTransformation;
import dev.agenticcommerce.gateway.agentization.model.OpenApiArtifact;
import dev.agenticcommerce.gateway.agentization.persistence.CapabilityContractTestRunRepository;
import dev.agenticcommerce.gateway.agentization.persistence.CapabilityMappingProposalRepository;
import dev.agenticcommerce.gateway.agentization.service.AgentizationException;
import dev.agenticcommerce.gateway.agentization.service.ExecutableMappingValidator;
import dev.agenticcommerce.gateway.agentization.service.CanonicalCapabilityContractTestService;
import dev.agenticcommerce.gateway.agentization.service.OpenApiArtifactService;
import dev.agenticcommerce.gateway.catalogue.CatalogueService;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class AgentToolExecutor {

    private static final int MAX_MAPPING_JSON_CHARACTERS = 16_000;
    private final OpenApiArtifactService artifactService;
    private final OpenApiInspectionService inspectionService;
    private final CapabilityMappingProposalRepository mappingRepository;
    private final CapabilityContractTestRunRepository contractTestRepository;
    private final ExecutableMappingValidator mappingValidator;
    private final CanonicalCapabilityContractTestService contractTestService;
    private final MerchantAuthorityService merchantAuthorityService;
    private final PolicyAuthorityService policyAuthorityService;
    private final CatalogueService catalogueService;
    private final ObjectMapper objectMapper;

    public AgentToolExecutor(
            OpenApiArtifactService artifactService,
            OpenApiInspectionService inspectionService,
            CapabilityMappingProposalRepository mappingRepository,
            CapabilityContractTestRunRepository contractTestRepository,
            ExecutableMappingValidator mappingValidator,
            CanonicalCapabilityContractTestService contractTestService,
            MerchantAuthorityService merchantAuthorityService,
            PolicyAuthorityService policyAuthorityService,
            CatalogueService catalogueService,
            ObjectMapper objectMapper) {
        this.artifactService = artifactService;
        this.inspectionService = inspectionService;
        this.mappingRepository = mappingRepository;
        this.contractTestRepository = contractTestRepository;
        this.mappingValidator = mappingValidator;
        this.contractTestService = contractTestService;
        this.merchantAuthorityService = merchantAuthorityService;
        this.policyAuthorityService = policyAuthorityService;
        this.catalogueService = catalogueService;
        this.objectMapper = objectMapper;
    }

    public ToolExecutionResult execute(AgentizationRun run, NextAgentAction action) {
        if (action == null || action.tool() == null || action.artifactId() == null) {
            throw invalid("INVALID_TOOL_ARGUMENTS", "Tool action and artifact ID are required");
        }
        if (!run.sourceArtifactId().equals(action.artifactId())) {
            throw invalid("ARTIFACT_CONTEXT_MISMATCH", "Tool artifact does not match the run artifact");
        }
        OpenApiArtifact artifact = artifactService.requireArtifact(run.merchantId(), action.artifactId());
        return switch (action.tool()) {
            case INSPECT_SPEC -> ToolExecutionResult.simple(
                    objectMapper.valueToTree(inspectionService.inspectSpec(
                            artifact, action.pathFilter(), action.methodFilter(),
                            action.operationFilter(), action.maximumResults())),
                    "SPEC_INSPECTED");
            case INSPECT_SCHEMA -> ToolExecutionResult.simple(
                    objectMapper.valueToTree(inspectionService.inspectSchema(
                            artifact, action.schemaReference(), action.maximumSchemaDepth(),
                            action.maximumSchemaFields())),
                    "SCHEMA_INSPECTED");
            case PROPOSE_MAPPING -> proposeMapping(run, artifact, action.mappingProposal());
            case VALIDATE_MAPPING -> validateMapping(run, action.mappingProposalId());
            case RUN_CONTRACT_TEST -> runContractTest(run, action.mappingProposalId(), action.testCaseId());
            case INSPECT_TEST_FAILURE -> inspectFailure(run, action.contractTestRunId());
            case REVISE_MAPPING -> reviseMapping(run, action.mappingRevision());
            case INSPECT_POLICY -> inspectPolicy(run, action.policyDocumentId());
            case EXTRACT_POLICY_RULES -> extractPolicy(run, action.policyDocumentId());
            case INSPECT_CATALOG_SAMPLE -> ToolExecutionResult.simple(
                    objectMapper.valueToTree(catalogueService.inspect(run.merchantId(),
                            action.maximumResults() == null ? 10 : Math.min(Math.max(action.maximumResults(), 1), 20))),
                    "CATALOG_SAMPLE_INSPECTED");
            case REQUEST_MERCHANT_CLARIFICATION -> requestClarification(run, action);
            case REQUEST_MERCHANT_APPROVAL -> requestApproval(run, action);
            case PUBLISH_MANIFEST_CANDIDATE -> ToolExecutionResult.simple(
                    objectMapper.createObjectNode().put("reducerRequested", true),
                    "MANIFEST_REDUCTION_REQUESTED");
        };
    }

    private ToolExecutionResult inspectPolicy(AgentizationRun run, java.util.UUID documentId) {
        if (documentId == null) throw invalid("POLICY_DOCUMENT_ID_REQUIRED", "A policy document ID is required");
        var document = policyAuthorityService.document(run.createdByActorId(), run.merchantId(), documentId);
        var result = objectMapper.createObjectNode();
        result.put("policyDocumentId", document.policyDocumentId().toString());
        result.put("documentType", document.documentType().name()); result.put("documentVersion", document.documentVersion());
        result.put("contentHash", document.contentHash());
        result.put("boundedPolicyText", document.normalizedContent().length() <= 12_000
                ? document.normalizedContent() : document.normalizedContent().substring(0, 12_000));
        return ToolExecutionResult.simple(result, "POLICY_INSPECTED");
    }

    private ToolExecutionResult extractPolicy(AgentizationRun run, java.util.UUID documentId) {
        if (documentId == null) throw invalid("POLICY_DOCUMENT_ID_REQUIRED", "A policy document ID is required");
        var proposals = policyAuthorityService.extract(run.createdByActorId(), run.merchantId(), documentId);
        return ToolExecutionResult.simple(objectMapper.valueToTree(proposals), "POLICY_RULES_PROPOSED");
    }

    private ToolExecutionResult requestClarification(AgentizationRun run, NextAgentAction action) {
        String question = action.pathFilter() == null ? action.conciseReason() : action.pathFilter();
        var evidence = objectMapper.createArrayNode().add("agentization-run:" + run.runId()
                + ":step:" + (run.stepCount() + 1));
        var continuation = switch (run.state()) {
            case DIAGNOSING_FAILURE -> dev.agenticcommerce.gateway.agentization.model.AgentizationState.DIAGNOSING_FAILURE;
            case REVISING_MAPPING -> dev.agenticcommerce.gateway.agentization.model.AgentizationState.REVISING_MAPPING;
            case EXTRACTING_POLICY -> dev.agenticcommerce.gateway.agentization.model.AgentizationState.EXTRACTING_POLICY;
            default -> dev.agenticcommerce.gateway.agentization.model.AgentizationState.READY_CANDIDATE;
        };
        var clarification = merchantAuthorityService.createAgentClarification(run, action.mappingProposalId(),
                action.policyDocumentId(), null, question, evidence, ClarificationKind.MAPPING, continuation);
        return ToolExecutionResult.simple(objectMapper.valueToTree(clarification), "MERCHANT_CLARIFICATION_REQUESTED");
    }

    private ToolExecutionResult requestApproval(AgentizationRun run, NextAgentAction action) {
        java.util.UUID mappingId = action.mappingProposalId();
        if (mappingId == null) {
            if (action.policyDocumentId() == null) {
                throw invalid("APPROVAL_SUBJECT_REQUIRED", "A mapping or policy document subject is required");
            }
            var rules = policyAuthorityService.rules(
                    run.createdByActorId(), run.merchantId(), action.policyDocumentId());
            if (rules.isEmpty()) throw invalid("POLICY_RULES_REQUIRED", "Extracted policy proposals are required");
            return ToolExecutionResult.simple(objectMapper.valueToTree(rules), "MERCHANT_POLICY_APPROVAL_REQUESTED");
        }
        CapabilityMappingProposal mapping = requireMapping(run, mappingId);
        try {
            mapping = mappingRepository.markAwaitingApproval(run.merchantId(), run.runId(), mappingId);
        } catch (IllegalStateException exception) {
            throw invalid("MAPPING_NOT_ELIGIBLE_FOR_APPROVAL", exception.getMessage());
        }
        return new ToolExecutionResult(objectMapper.valueToTree(mapping), "MERCHANT_MAPPING_APPROVAL_REQUESTED",
                mapping, null, mapping.mappingVersion(), mapping.mappingVersion());
    }

    private ToolExecutionResult proposeMapping(
            AgentizationRun run, OpenApiArtifact artifact, MappingProposalInput proposal) {
        validateMappingInput(proposal);
        if (!artifact.artifactId().equals(proposal.artifactId())) {
            throw invalid("ARTIFACT_CONTEXT_MISMATCH", "Mapping artifact does not match the run artifact");
        }
        boolean found = inspectionService.inspectSpec(
                        artifact, proposal.pathTemplate(), proposal.httpMethod(), proposal.operationId(),
                        OpenApiInspectionService.MAX_OPERATION_RESULTS)
                .operations().stream()
                .anyMatch(operation -> operation.path().equals(proposal.pathTemplate())
                        && operation.method().equalsIgnoreCase(proposal.httpMethod())
                        && (proposal.operationId() == null || proposal.operationId().isBlank()
                                || operation.operationId().equals(proposal.operationId())));
        if (!found) {
            throw invalid("SOURCE_OPERATION_NOT_FOUND", "Mapping source operation was not found");
        }
        CapabilityMappingProposal created = mappingRepository.create(
                run.merchantId(), run.runId(), run.currentCapability(), proposal);
        return new ToolExecutionResult(
                objectMapper.valueToTree(created), "MAPPING_PROPOSED", created, null,
                run.currentMappingVersion(), created.mappingVersion());
    }

    private ToolExecutionResult validateMapping(AgentizationRun run, java.util.UUID mappingId) {
        CapabilityMappingProposal mapping = requireMapping(run, mappingId);
        var validation = mappingValidator.validate(mapping);
        CapabilityMappingProposal validated = "VALID".equals(mapping.validationStatus())
                ? mapping
                : mappingRepository.markValidation(run.merchantId(), run.runId(), mappingId, true);
        return new ToolExecutionResult(
                objectMapper.valueToTree(validation), "MAPPING_VALIDATED", validated, null,
                run.currentMappingVersion(), validated.mappingVersion());
    }

    private ToolExecutionResult runContractTest(
            AgentizationRun run, java.util.UUID mappingId, String testCaseId) {
        CapabilityMappingProposal mapping = requireMapping(run, mappingId);
        if (!"VALID".equals(mapping.validationStatus())) {
            throw invalid("MAPPING_NOT_VALIDATED", "Only a deterministically validated mapping can be tested");
        }
        if (run.currentMappingVersion() == null || run.currentMappingVersion() != mapping.mappingVersion()) {
            throw invalid("MAPPING_NOT_CURRENT", "Contract test mapping is not the run's current mapping");
        }
        CapabilityContractTestRun testRun = contractTestService.runCanonical(run, mapping, testCaseId);
        return new ToolExecutionResult(
                testRun.structuredEvidence(),
                testRun.outcome() == ContractTestOutcome.PASS ? "CONTRACT_TEST_PASSED" : testRun.failureCode(),
                mapping, testRun, mapping.mappingVersion(), mapping.mappingVersion());
    }

    private ToolExecutionResult inspectFailure(AgentizationRun run, java.util.UUID testRunId) {
        CapabilityContractTestRun testRun = contractTestRepository.findByMerchantRunAndId(
                        run.merchantId(), run.runId(), testRunId)
                .orElseThrow(() -> invalid("CONTRACT_TEST_NOT_FOUND", "Contract test evidence was not found"));
        if (testRun.outcome() == ContractTestOutcome.PASS) {
            throw invalid("CONTRACT_TEST_NOT_FAILED", "Passing test evidence cannot be diagnosed as a failure");
        }
        CapabilityMappingProposal mapping = requireMapping(run, testRun.mappingProposalId());
        var result = objectMapper.createObjectNode();
        result.put("capability", testRun.capability().name());
        result.put("mappingVersion", testRun.mappingVersion());
        result.put("testCaseId", testRun.testCaseId());
        result.put("testVersion", testRun.testVersion());
        result.put("failureCode", testRun.failureCode());
        result.set("boundedEvidence", testRun.structuredEvidence());
        result.set("previousTransformations", mapping.transformations());
        result.put("previousRepairAttempts", Math.max(0, mapping.mappingVersion() - 1));
        result.put("evidenceHash", testRun.evidenceHash());
        return new ToolExecutionResult(
                result, "TEST_FAILURE_INSPECTED", mapping, testRun,
                mapping.mappingVersion(), mapping.mappingVersion());
    }

    private ToolExecutionResult reviseMapping(AgentizationRun run, MappingRevisionInput revision) {
        if (revision == null || revision.previousMappingProposalId() == null
                || revision.evidenceContractTestRunId() == null || revision.transformation() == null
                || revision.revisionReason() == null || revision.revisionReason().isBlank()) {
            throw invalid("MAPPING_REVISION_INVALID", "Typed revision fields are required");
        }
        CapabilityMappingProposal previous = requireMapping(run, revision.previousMappingProposalId());
        CapabilityContractTestRun evidence = contractTestRepository.findByMerchantRunAndId(
                        run.merchantId(), run.runId(), revision.evidenceContractTestRunId())
                .orElseThrow(() -> invalid("REVISION_EVIDENCE_NOT_FOUND", "Revision evidence was not found"));
        if (!evidence.mappingProposalId().equals(previous.mappingProposalId())
                || evidence.outcome() != ContractTestOutcome.FAIL
                || !"MONEY_UNIT_MISMATCH".equals(evidence.failureCode())
                || !"amount".equals(revision.responseField())
                || revision.transformation() != MappingTransformation.MONEY_RUPEES_TO_PAISE) {
            throw invalid("MAPPING_REVISION_NOT_SUPPORTED", "Evidence does not support the requested bounded revision");
        }
        var transformations = (tools.jackson.databind.node.ObjectNode) previous.transformations().deepCopy();
        transformations.put("amount", MappingTransformation.MONEY_RUPEES_TO_PAISE.name());
        CapabilityMappingProposal revised = mappingRepository.createRevision(
                previous, transformations, revision.revisionReason(), evidence.contractTestRunId(),
                revision.modelProvider(), revision.modelName());
        return new ToolExecutionResult(
                objectMapper.valueToTree(revised), "MAPPING_REVISED", revised, evidence,
                previous.mappingVersion(), revised.mappingVersion());
    }

    private CapabilityMappingProposal requireMapping(AgentizationRun run, java.util.UUID mappingId) {
        if (mappingId == null) throw invalid("MAPPING_ID_REQUIRED", "A mapping ID is required");
        return mappingRepository.findByMerchantRunAndId(run.merchantId(), run.runId(), mappingId)
                .orElseThrow(() -> invalid("MAPPING_NOT_FOUND", "Tenant-owned mapping was not found"));
    }

    private void validateMappingInput(MappingProposalInput proposal) {
        if (proposal == null || proposal.artifactId() == null || proposal.mappingVersion() < 1
                || proposal.httpMethod() == null || proposal.pathTemplate() == null
                || proposal.pathTemplate().isBlank() || proposal.pathTemplate().length() > 1024) {
            throw invalid("INVALID_MAPPING_PROPOSAL", "Mapping proposal fields are invalid");
        }
        String method = proposal.httpMethod().toUpperCase(Locale.ROOT);
        if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(method)) {
            throw invalid("INVALID_MAPPING_METHOD", "Mapping HTTP method is unsupported");
        }
        validateJsonObject(proposal.requestBindings(), "requestBindings");
        validateJsonObject(proposal.responseBindings(), "responseBindings");
        validateJsonObject(proposal.transformations(), "transformations");
        validateJsonObject(proposal.amountInterpretation(), "amountInterpretation");
        validateJsonObject(proposal.currencyInterpretation(), "currencyInterpretation");
    }

    private void validateJsonObject(JsonNode value, String name) {
        if (value == null || !value.isObject()
                || objectMapper.writeValueAsString(value).length() > MAX_MAPPING_JSON_CHARACTERS) {
            throw invalid("INVALID_MAPPING_" + name.toUpperCase(Locale.ROOT),
                    name + " must be a bounded JSON object");
        }
    }

    private static AgentizationException invalid(String code, String message) {
        return new AgentizationException(code, HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
