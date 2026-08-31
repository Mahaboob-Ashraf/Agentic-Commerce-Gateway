package dev.agenticcommerce.gateway.payment;

import dev.agenticcommerce.gateway.payment.PaymentModels.WebhookResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments/razorpay")
public class RazorpayWebhookController {
    private final RazorpayWebhookService webhooks;
    public RazorpayWebhookController(RazorpayWebhookService webhooks) { this.webhooks = webhooks; }

    @PostMapping("/webhook")
    public ResponseEntity<WebhookResult> webhook(
            @RequestBody byte[] rawBody,
            @RequestHeader("X-Razorpay-Signature") String signature,
            @RequestHeader(value = "X-Razorpay-Event-Id", required = false) String eventId) {
        return ResponseEntity.accepted().body(webhooks.ingest(rawBody, signature, eventId));
    }
}
