package dev.agenticcommerce.gateway.agentization.tool;

import dev.agenticcommerce.gateway.agentization.model.AgentToolName;
import java.util.UUID;

/** Schema-constrained next action. Arbitrary tool names cannot cross this enum boundary. */
public record NextAgentAction(
        AgentToolName tool,
        UUID artifactId,
        String pathFilter,
        String methodFilter,
        String operationFilter,
        Integer maximumResults,
        String schemaReference,
        Integer maximumSchemaDepth,
        Integer maximumSchemaFields,
        MappingProposalInput mappingProposal,
        UUID mappingProposalId,
        UUID contractTestRunId,
        UUID policyDocumentId,
        String testCaseId,
        MappingRevisionInput mappingRevision,
        String conciseReason) {

    public NextAgentAction {
        requireMaximumLength(pathFilter, 1_024, "pathFilter");
        requireMaximumLength(methodFilter, 16, "methodFilter");
        requireMaximumLength(operationFilter, 256, "operationFilter");
        requireMaximumLength(schemaReference, 512, "schemaReference");
        requireMaximumLength(conciseReason, 512, "conciseReason");
        requireMaximumLength(testCaseId, 128, "testCaseId");
    }

    public static NextAgentAction inspectSpec(UUID artifactId, int maximumResults, String reason) {
        return new NextAgentAction(
                AgentToolName.INSPECT_SPEC, artifactId, null, null, null, maximumResults,
                null, null, null, null, null, null, null, null, null, reason);
    }

    public static NextAgentAction inspectSchema(
            UUID artifactId, String reference, int maximumDepth, int maximumFields, String reason) {
        return new NextAgentAction(
                AgentToolName.INSPECT_SCHEMA, artifactId, null, null, null, null,
                reference, maximumDepth, maximumFields, null, null, null, null, null, null, reason);
    }

    public static NextAgentAction proposeMapping(MappingProposalInput proposal, String reason) {
        return new NextAgentAction(
                AgentToolName.PROPOSE_MAPPING, proposal.artifactId(), null, null, null, null,
                null, null, null, proposal, null, null, null, null, null, reason);
    }

    public static NextAgentAction validateMapping(UUID artifactId, UUID mappingProposalId, String reason) {
        return new NextAgentAction(
                AgentToolName.VALIDATE_MAPPING, artifactId, null, null, null, null,
                null, null, null, null, mappingProposalId, null, null, null, null, reason);
    }

    public static NextAgentAction runContractTest(
            UUID artifactId, UUID mappingProposalId, String testCaseId, String reason) {
        return new NextAgentAction(
                AgentToolName.RUN_CONTRACT_TEST, artifactId, null, null, null, null,
                null, null, null, null, mappingProposalId, null, null, testCaseId, null, reason);
    }

    public static NextAgentAction inspectTestFailure(
            UUID artifactId, UUID contractTestRunId, String reason) {
        return new NextAgentAction(
                AgentToolName.INSPECT_TEST_FAILURE, artifactId, null, null, null, null,
                null, null, null, null, null, contractTestRunId, null, null, null, reason);
    }

    public static NextAgentAction reviseMapping(
            UUID artifactId, MappingRevisionInput revision, String reason) {
        return new NextAgentAction(
                AgentToolName.REVISE_MAPPING, artifactId, null, null, null, null,
                null, null, null, null, revision.previousMappingProposalId(),
                revision.evidenceContractTestRunId(), null, null, revision, reason);
    }

    public static NextAgentAction inspectPolicy(UUID artifactId, UUID policyDocumentId, String reason) {
        return new NextAgentAction(AgentToolName.INSPECT_POLICY, artifactId, null, null, null, null,
                null, null, null, null, null, null, policyDocumentId, null, null, reason);
    }

    public static NextAgentAction inspectCatalogSample(UUID artifactId, int maximumResults, String reason) {
        return new NextAgentAction(AgentToolName.INSPECT_CATALOG_SAMPLE, artifactId, null, null, null,
                maximumResults, null, null, null, null, null, null, null, null, null, reason);
    }

    public static NextAgentAction extractPolicyRules(UUID artifactId, UUID policyDocumentId, String reason) {
        return new NextAgentAction(AgentToolName.EXTRACT_POLICY_RULES, artifactId, null, null, null, null,
                null, null, null, null, null, null, policyDocumentId, null, null, reason);
    }

    public static NextAgentAction requestMerchantApproval(UUID artifactId, UUID mappingId, String reason) {
        return new NextAgentAction(AgentToolName.REQUEST_MERCHANT_APPROVAL, artifactId, null, null, null, null,
                null, null, null, null, mappingId, null, null, null, null, reason);
    }

    public static NextAgentAction requestPolicyApproval(UUID artifactId, UUID policyDocumentId, String reason) {
        return new NextAgentAction(AgentToolName.REQUEST_MERCHANT_APPROVAL, artifactId, null, null, null, null,
                null, null, null, null, null, null, policyDocumentId, null, null, reason);
    }

    public static NextAgentAction requestClarification(UUID artifactId, UUID mappingId, String question) {
        return new NextAgentAction(AgentToolName.REQUEST_MERCHANT_CLARIFICATION, artifactId, question,
                null, null, null, null, null, null, null, mappingId, null, null, null, null,
                "Request evidence-driven merchant clarification");
    }

    public static NextAgentAction publishManifestCandidate(UUID artifactId, String reason) {
        return new NextAgentAction(AgentToolName.PUBLISH_MANIFEST_CANDIDATE, artifactId, null, null, null, null,
                null, null, null, null, null, null, null, null, null, reason);
    }

    private static void requireMaximumLength(String value, int maximum, String field) {
        if (value != null && value.length() > maximum) {
            throw new IllegalArgumentException(field + " exceeds the decision-schema limit");
        }
    }
}
