package dev.agenticcommerce.gateway.payment;

import static dev.agenticcommerce.gateway.payment.PaymentModels.*;

import dev.agenticcommerce.gateway.identity.authentication.VerifiedActorPrincipal;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/buyer/threads/{threadId}/transaction/proposals/{proposalId}")
public class PaymentApiController {
    private final PaymentControlService payments;

    public PaymentApiController(PaymentControlService payments) { this.payments = payments; }

    @PostMapping("/payment/order")
    public PaymentStateView initiate(
            @AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID threadId, @PathVariable UUID proposalId) {
        return payments.initiate(principal.actorId(), threadId, proposalId);
    }

    @GetMapping("/payment/checkout")
    public CheckoutInitialization checkout(
            @AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID threadId, @PathVariable UUID proposalId) {
        return payments.checkout(principal.actorId(), threadId, proposalId);
    }

    @PostMapping("/payment/callback")
    public CallbackResult callback(
            @AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID threadId, @PathVariable UUID proposalId,
            @RequestBody CallbackSubmission submission) {
        return payments.callback(principal.actorId(), threadId, proposalId, submission);
    }

    @GetMapping("/payment")
    public PaymentStateView state(
            @AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID threadId, @PathVariable UUID proposalId) {
        return payments.state(principal.actorId(), threadId, proposalId);
    }

    @PostMapping("/payment/reconcile")
    public ReconciliationResult reconcile(
            @AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID threadId, @PathVariable UUID proposalId) {
        return payments.reconcile(principal.actorId(), threadId, proposalId);
    }

    @GetMapping("/fulfillment")
    public FulfillmentView fulfillment(
            @AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID threadId, @PathVariable UUID proposalId) {
        return payments.fulfillment(principal.actorId(), threadId, proposalId);
    }
}
