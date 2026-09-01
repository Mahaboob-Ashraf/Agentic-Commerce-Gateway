package dev.agenticcommerce.gateway.onboarding;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestControllerAdvice(assignableTypes=OnboardingApiController.class)
public class OnboardingExceptionHandler {
    @ExceptionHandler(OnboardingException.class) ResponseEntity<Map<String,String>> handle(OnboardingException e){
        return ResponseEntity.status(e.status()).body(Map.of("code",e.code(),"message",e.getMessage()));}
}
