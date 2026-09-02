package dev.agenticcommerce.gateway.lifecycle;

import dev.agenticcommerce.gateway.agentization.authority.MerchantAuthorityService;
import dev.agenticcommerce.gateway.agentization.execution.ApprovedMerchantExecutor;
import dev.agenticcommerce.gateway.agentization.execution.MerchantExecutionException;
import dev.agenticcommerce.gateway.agentization.execution.MerchantExecutionMode;
import dev.agenticcommerce.gateway.agentization.model.CanonicalCapability;
import dev.agenticcommerce.gateway.agentization.persistence.CapabilityMappingProposalRepository;
import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import dev.agenticcommerce.gateway.agentization.service.AgentizationException;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Executes lifecycle mutations only through an approved, current-hash merchant HTTP mapping. */
@Service
public class ApprovedMerchantLifecycleGateway implements MerchantLifecycleGateway {
    private final JdbcClient jdbc;private final CapabilityMappingProposalRepository mappings;
    private final MerchantAuthorityService authority;private final ApprovedMerchantExecutor executor;
    private final ObjectMapper mapper;private final CanonicalJsonService canonical;
    public ApprovedMerchantLifecycleGateway(JdbcClient jdbc,CapabilityMappingProposalRepository mappings,
            MerchantAuthorityService authority,ApprovedMerchantExecutor executor,ObjectMapper mapper,CanonicalJsonService canonical){
        this.jdbc=jdbc;this.mappings=mappings;this.authority=authority;this.executor=executor;this.mapper=mapper;this.canonical=canonical;}
    @Override public Result cancel(UUID merchantId,String merchantOrderId,String operationId,String customerReference){return invoke(merchantId,merchantOrderId,operationId,customerReference,CanonicalCapability.CANCEL_ORDER);}
    @Override public Result requestFullReturn(UUID merchantId,String merchantOrderId,String operationId,String customerReference){return invoke(merchantId,merchantOrderId,operationId,customerReference,CanonicalCapability.RETURN_ITEM);}
    private Result invoke(UUID merchant,String orderId,String operation,String customer,CanonicalCapability capability){try{
        Binding binding=jdbc.sql("""
                SELECT mapping.mapping_proposal_id,approval.mapping_content_hash FROM capability_mapping_proposal mapping
                JOIN mapping_approval_decision approval ON approval.mapping_proposal_id=mapping.mapping_proposal_id
                  AND approval.merchant_id=mapping.merchant_id AND approval.decision='APPROVE'
                WHERE mapping.merchant_id=:m AND mapping.capability=:c AND mapping.validation_status='VALID'
                ORDER BY approval.decided_at DESC LIMIT 1
                """).param("m",merchant).param("c",capability.name()).query((rs,n)->new Binding(rs.getObject(1,UUID.class),rs.getString(2).strip())).optional().orElse(null);
        if(binding==null)return new Result(false,false,null,null,null,"LIFECYCLE_CONTRACT_NOT_APPROVED");
        var mapping=mappings.findByMerchantAndId(merchant,binding.mappingId()).orElse(null);
        if(mapping==null||mapping.capability()!=capability||!binding.hash().equals(authority.mappingHash(mapping)))return new Result(false,false,null,null,null,"LIFECYCLE_APPROVAL_STALE");
        var request=mapper.createObjectNode().put("merchantOperationId",operation).put("merchantCustomerReference",customer);
        var response=executor.execute(merchant,mapping,Map.of("orderId",orderId),mapper.writeValueAsBytes(request),MerchantExecutionMode.RUNTIME);
        boolean retryable=response.statusCode()==408||response.statusCode()==429||response.statusCode()>=500;
        if(response.statusCode()<200||response.statusCode()>=300)return new Result(false,retryable,null,null,null,"MERCHANT_LIFECYCLE_REJECTED");
        var body=mapper.readTree(response.body());String state=body.path("state").asText("");String returned=body.path("orderId").asText("");
        if(!orderId.equals(returned)||state.isBlank())return new Result(false,false,null,null,null,"MERCHANT_LIFECYCLE_RESPONSE_INVALID");
        return new Result(true,false,state,operation,canonical.hash(body),null);
    }catch(MerchantExecutionException failure){boolean transientFailure="MERCHANT_TIMEOUT".equals(failure.code())
                ||"MERCHANT_TRANSPORT_FAILURE".equals(failure.code());
        return new Result(false,transientFailure,null,null,null,failure.code());
    }catch(AgentizationException failure){return new Result(false,false,null,null,null,failure.code());
    }catch(RuntimeException failure){return new Result(false,false,null,null,null,"MERCHANT_LIFECYCLE_RESPONSE_INVALID");}}
    private record Binding(UUID mappingId,String hash){}
}
