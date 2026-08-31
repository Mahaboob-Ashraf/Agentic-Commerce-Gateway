package dev.agenticcommerce.gateway.payment;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {PaymentApiController.class, RazorpayWebhookController.class})
public class PaymentControlExceptionHandler {
    @ExceptionHandler(PaymentControlException.class)
    ResponseEntity<Map<String, String>> payment(PaymentControlException exception) {
        return ResponseEntity.status(exception.status())
                .body(Map.of("code", exception.code(), "message", exception.getMessage()));
    }
}
