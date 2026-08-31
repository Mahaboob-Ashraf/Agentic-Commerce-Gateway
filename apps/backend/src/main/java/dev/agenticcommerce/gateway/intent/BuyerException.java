package dev.agenticcommerce.gateway.intent;

import org.springframework.http.HttpStatus;

public class BuyerException extends RuntimeException {
    private final String code;
    private final HttpStatus status;
    public BuyerException(String code,HttpStatus status,String message){super(message);this.code=code;this.status=status;}
    public String code(){return code;}
    public HttpStatus status(){return status;}
}
