package dev.agenticcommerce.gateway.lifecycle;
import java.util.UUID;
public interface MerchantLifecycleGateway {
    Result cancel(UUID merchantId,String merchantOrderId,String operationId,String customerReference);
    Result requestFullReturn(UUID merchantId,String merchantOrderId,String operationId,String customerReference);
    record Result(boolean success,boolean retryable,String state,String reference,String evidenceHash,String errorCode){}
}
