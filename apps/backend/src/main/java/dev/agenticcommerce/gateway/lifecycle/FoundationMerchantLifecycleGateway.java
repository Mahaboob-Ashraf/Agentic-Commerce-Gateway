package dev.agenticcommerce.gateway.lifecycle;
import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import java.util.UUID;
import org.springframework.stereotype.Component;
@Component public class FoundationMerchantLifecycleGateway implements MerchantLifecycleGateway {
    private final CanonicalJsonService canonical; public FoundationMerchantLifecycleGateway(CanonicalJsonService c){canonical=c;}
    public Result cancel(UUID m,String o,String op,String c){return new Result(false,false,null,null,null,"CANCEL_ADAPTER_UNAVAILABLE");}
    public Result requestFullReturn(UUID m,String o,String op,String c){return new Result(false,false,null,null,null,"RETURN_ADAPTER_UNAVAILABLE");}
}
