package dev.agenticcommerce.gateway.intent;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes=BuyerApiController.class)
public class BuyerExceptionHandler {
    @ExceptionHandler(BuyerException.class) ResponseEntity<Map<String,String>> buyer(BuyerException e){return ResponseEntity.status(e.status()).body(Map.of("code",e.code(),"message",e.getMessage()));}
    @ExceptionHandler(MethodArgumentNotValidException.class) ResponseEntity<Map<String,String>> validation(){return ResponseEntity.badRequest().body(Map.of("code","BUYER_REQUEST_INVALID","message","Buyer request validation failed"));}
}
