package dev.agenticcommerce.gateway.agentization.api;

import dev.agenticcommerce.gateway.agentization.service.AgentizationException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {AgentizationApiController.class,AgentizationGoalApiController.class})
public class AgentizationExceptionHandler {

    @ExceptionHandler(AgentizationException.class)
    public ResponseEntity<Map<String, String>> handle(AgentizationException exception) {
        return ResponseEntity.status(exception.status())
                .body(Map.of("error", exception.code(), "message", exception.getMessage()));
    }
}
