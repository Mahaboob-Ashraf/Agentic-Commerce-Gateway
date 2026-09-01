package dev.agenticcommerce.gateway.lifecycle;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
public final class AutoBuyModels {
 private AutoBuyModels(){}
 public enum PlanStatus{ACTIVE,PAUSED,REVOKED} public enum Outcome{AUTO_EXECUTE,CONFIRM,PAUSED,BLOCKED}
 public record Plan(UUID id,UUID buyerId,UUID merchantId,int currentVersion,PlanStatus status,String pauseReason,Instant createdAt,Instant updatedAt){}
 public record PlanVersion(UUID planId,int version,UUID buyerId,UUID merchantId,UUID linkId,UUID addressId,
        JsonNode productConstraints,long maximumAmountMinor,String triggerDescription,String substitutionPolicy,
        JsonNode hardSafetyConstraints,String planHash,Instant createdAt){}
 public record PlanView(Plan plan,PlanVersion version){}
 public record PlanInput(UUID merchantId,UUID merchantAccountLinkId,UUID addressId,JsonNode productConstraints,
        long maximumAmountMinor,String triggerDescription,String substitutionPolicy,JsonNode hardSafetyConstraints){}
 public record Evaluation(UUID id,UUID planId,int planVersion,UUID buyerId,String triggerId,Outcome outcome,
        String reasonCode,JsonNode freshEvidence,UUID proposalId,UUID executionId,String providerOrderId,
        boolean checkoutAuthorizationRequired,String evaluationHash,Instant evaluatedAt){}
 public record TriggerRequest(String triggerId){}
}
