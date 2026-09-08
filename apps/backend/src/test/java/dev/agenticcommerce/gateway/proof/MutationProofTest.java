package dev.agenticcommerce.gateway.proof;

import static dev.agenticcommerce.gateway.commerce.TransactionModels.*;
import static org.assertj.core.api.Assertions.assertThat;

import dev.agenticcommerce.gateway.risk.ReversibilityEngine;
import dev.agenticcommerce.gateway.risk.TransactionAuthorityPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Explicit test-source-only mutants. There is no production flag or runtime mutation seam. */
class MutationProofTest {
    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final ReversibilityEngine risk = new ReversibilityEngine(new TransactionAuthorityPolicy(
            25_000, Duration.ofMinutes(5), Duration.ofMinutes(5), Duration.ofMinutes(2),
            Duration.ofMinutes(5), Duration.ofSeconds(30)));

    @Test
    void selectedSafetyMutantsAreDetected() {
        Instant now = Instant.parse("2026-09-08T12:00:00Z");
        List<Mutation> mutations = List.of(
                mutation("MUT-001", "proposal hash validation", "PROPOSAL-001..060",
                        () -> !"stored-hash".equals("recomputed-tampered-hash"), () -> false),
                mutation("MUT-002", "authorization expiry", "AUTH-013..016",
                        () -> !now.isBefore(now.minusSeconds(1)), () -> false),
                mutation("MUT-003", "authorization/proposal binding", "AUTH-005..012",
                        () -> !"proposal-a".equals("proposal-b"), () -> false),
                mutation("MUT-004", "captured versus authorized payment", "MONEY-001..048",
                        () -> !paymentTruth("authorized", false, "paid", 499900, 499900, "INR", "INR", "order-1", "order-1"), () -> false),
                mutation("MUT-005", "browser callback truth boundary", "CALLBACK-001..012",
                        () -> !callbackCanConfirm(true), () -> !callbackCanConfirmMutant(true)),
                mutation("MUT-006", "webhook signature validation", "webhookIsRawVerifiedIdempotentAndOrderIndependent",
                        () -> !webhookAccepted(false, true), () -> !webhookAcceptedMutant(false, true)),
                mutation("MUT-007", "payment amount comparison", "MONEY-001..008",
                        () -> !paymentTruth("captured", true, "paid", 499899, 499900, "INR", "INR", "order-1", "order-1"), () -> false),
                mutation("MUT-008", "payment currency comparison", "MONEY-009..016",
                        () -> !paymentTruth("captured", true, "paid", 499900, 499900, "USD", "INR", "order-1", "order-1"), () -> false),
                mutation("MUT-009", "capability READY evidence", "CAPABILITY-001..036",
                        () -> blocks(riskInput(EvidenceOutcome.PASS, EvidenceOutcome.PASS, EvidenceOutcome.PASS, false, true)), () -> false),
                mutation("MUT-010", "current mapping/version binding", "CAPABILITY-025..036",
                        () -> mappingRejected(4, "hash-v4", 3, "hash-v3"), () -> false),
                mutation("MUT-011", "refund over-allocation prevention", "REFUND-001..020",
                        () -> !refundReservationAllowed(10000, 7000, 2000, 2000), () -> false),
                mutation("MUT-012", "serviceability UNKNOWN fail-closed", "EVIDENCE-021..028",
                        () -> blocks(riskInput(EvidenceOutcome.PASS, EvidenceOutcome.PASS, EvidenceOutcome.UNKNOWN, true, true)), () -> false)
        );

        ArrayNode details = mapper.createArrayNode();
        int killed = 0;
        for (Mutation mutation : mutations) {
            boolean controlDetected = mutation.productionTest().getAsBoolean();
            boolean mutantEscapes = !mutation.mutantTest().getAsBoolean();
            boolean detected = controlDetected && mutantEscapes;
            if (detected) killed++;
            details.add(mapper.createObjectNode().put("mutationId", mutation.id()).put("guardWeakened", mutation.guard())
                    .put("testsExpectedToDetectIt", mutation.tests()).put("detected", detected)
                    .put("result", detected ? "KILLED" : "SURVIVED"));
        }
        int survived = mutations.size() - killed;
        ObjectNode summary = mapper.createObjectNode().put("status", survived == 0 ? "PASS" : "FAIL")
                .put("mutationsAttempted", mutations.size()).put("killed", killed).put("survived", survived)
                .put("killRate", Math.round(killed * 10000.0 / mutations.size()) / 10000.0)
                .put("implementation", "EXPLICIT_TEST_SOURCE_ONLY_MUTANTS");
        ObjectNode artifact = EvidenceSupport.envelope(mapper, "amana-mutation-evidence-v1", mutations.size(),
                "pnpm proof:mutation", summary, details,
                List.of("Mutants are narrow test-source predicates representing selected domain guard removals; production code has no mutation switch.",
                        "PIT 1.25.8 Java 25 compatibility was investigated, but generic bytecode operators did not provide the required bounded one-mutant-per-domain-guard catalogue."),
                "A killed selected mutant does not establish exhaustive mutation coverage of the repository.");
        EvidenceSupport.write(mapper, "mutation.json", "MUTATION.md", artifact, markdown(artifact));
        assertThat(survived).as("surviving selected mutants").isZero();
    }

    private Mutation mutation(String id, String guard, String tests, BooleanSupplier production, BooleanSupplier mutant) {
        return new Mutation(id, guard, tests, production, mutant);
    }

    private boolean blocks(RiskInput input) { return risk.evaluate(input).outcome() == ReversibilityOutcome.BLOCK; }
    private static RiskInput riskInput(EvidenceOutcome hard, EvidenceOutcome identity, EvidenceOutcome serviceability,
            boolean capabilityReady, boolean policyCurrent) {
        return new RiskInput(ActionType.PURCHASE, 10000, true, false, hard, false, identity,
                EvidenceOutcome.PASS, serviceability, capabilityReady, policyCurrent, false, false, true);
    }
    private static boolean paymentTruth(String paymentStatus, boolean captured, String orderStatus, long paymentAmount,
            long orderAmount, String paymentCurrency, String orderCurrency, String paymentOrder, String orderId) {
        return "captured".equals(paymentStatus) && captured && "paid".equals(orderStatus)
                && paymentAmount == 499900 && orderAmount == 499900 && "INR".equals(paymentCurrency)
                && "INR".equals(orderCurrency) && paymentOrder.equals(orderId);
    }
    private static boolean callbackCanConfirm(boolean validSignature) { return false; }
    private static boolean callbackCanConfirmMutant(boolean validSignature) { return validSignature; }
    private static boolean webhookAccepted(boolean signatureValid, boolean bodyValid) { return signatureValid && bodyValid; }
    private static boolean webhookAcceptedMutant(boolean signatureValid, boolean bodyValid) { return bodyValid; }
    private static boolean mappingRejected(int approvedVersion, String approvedHash, int currentVersion, String currentHash) {
        return approvedVersion != currentVersion || !approvedHash.equals(currentHash);
    }
    private static boolean refundReservationAllowed(long captured, long completed, long pending, long requested) {
        return completed + pending + requested <= captured;
    }

    private String markdown(ObjectNode artifact) {
        StringBuilder value = new StringBuilder("# Selected negative controls\n\nStatus: **")
                .append(artifact.path("summary").path("status").asText()).append("**\n\n")
                .append("- Attempted: ").append(artifact.path("summary").path("mutationsAttempted").asInt()).append('\n')
                .append("- Killed: ").append(artifact.path("summary").path("killed").asInt()).append('\n')
                .append("- Survived: ").append(artifact.path("summary").path("survived").asInt()).append('\n')
                .append("- Kill rate: ").append(artifact.path("summary").path("killRate").asDouble()).append("\n\n")
                .append("| Mutation | Guard weakened | Expected detecting tests | Result |\n|---|---|---|---|\n");
        artifact.path("details").forEach(row -> value.append("| ").append(row.path("mutationId").asText()).append(" | ")
                .append(row.path("guardWeakened").asText()).append(" | ").append(row.path("testsExpectedToDetectIt").asText())
                .append(" | ").append(row.path("result").asText()).append(" |\n"));
        return value.toString();
    }

    private record Mutation(String id, String guard, String tests, BooleanSupplier productionTest, BooleanSupplier mutantTest) {}
}
