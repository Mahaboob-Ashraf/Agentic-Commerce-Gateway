package dev.agenticcommerce.gateway.commerce;

import dev.agenticcommerce.gateway.intent.BuyerException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = TransactionAuthorityApiController.class)
public class TransactionAuthorityExceptionHandler {
    @ExceptionHandler(TransactionAuthorityException.class)
    ResponseEntity<Map<String, String>> transaction(TransactionAuthorityException exception) {
        return ResponseEntity.status(exception.status())
                .body(Map.of("code", exception.code(), "message", exception.getMessage()));
    }

    @ExceptionHandler(BuyerException.class)
    ResponseEntity<Map<String, String>> buyer(BuyerException exception) {
        return ResponseEntity.status(exception.status())
                .body(Map.of("code", exception.code(), "message", exception.getMessage()));
    }
}
