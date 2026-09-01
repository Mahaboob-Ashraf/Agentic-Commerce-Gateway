package dev.agenticcommerce.gateway.lifecycle;
import org.springframework.http.HttpStatus;
public final class LifecycleException extends RuntimeException {private final String code;private final HttpStatus status;
 public LifecycleException(String c,HttpStatus s,String m){super(m);code=c;status=s;}public String code(){return code;}public HttpStatus status(){return status;}}
