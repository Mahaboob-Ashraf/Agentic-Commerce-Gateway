package dev.agenticcommerce.gateway.lifecycle;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestControllerAdvice(assignableTypes={LifecycleApiController.class,AutoBuyApiController.class})
public class LifecycleExceptionHandler {
 @ExceptionHandler(LifecycleException.class) ResponseEntity<Map<String,String>> lifecycle(LifecycleException e){return ResponseEntity.status(e.status()).body(Map.of("code",e.code(),"message",e.getMessage()));}
}
