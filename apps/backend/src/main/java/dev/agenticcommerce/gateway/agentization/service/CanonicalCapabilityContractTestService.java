package dev.agenticcommerce.gateway.agentization.service;

import dev.agenticcommerce.gateway.agentization.execution.ApprovedMerchantExecutor;
import dev.agenticcommerce.gateway.agentization.execution.MerchantExecutionException;
import dev.agenticcommerce.gateway.agentization.execution.MerchantExecutionMode;
import dev.agenticcommerce.gateway.agentization.model.*;
import dev.agenticcommerce.gateway.agentization.persistence.CapabilityContractTestRunRepository;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Bounded deterministic smoke contracts for canonical capabilities other than the richer quote contract. */
@Service
public class CanonicalCapabilityContractTestService {
    public static final String TEST_CASE="canonical-capability-v1";
    private final GetQuoteContractTestService quotes;private final ApprovedMerchantExecutor executor;
    private final CapabilityContractTestRunRepository repository;private final CanonicalJsonService canonical;
    private final ObjectMapper mapper;
    public CanonicalCapabilityContractTestService(GetQuoteContractTestService quotes,ApprovedMerchantExecutor executor,
            CapabilityContractTestRunRepository repository,CanonicalJsonService canonical,ObjectMapper mapper){this.quotes=quotes;
        this.executor=executor;this.repository=repository;this.canonical=canonical;this.mapper=mapper;}

    public CapabilityContractTestRun runCanonical(AgentizationRun run,CapabilityMappingProposal mapping,String requested){
        if(mapping.capability()==CanonicalCapability.GET_QUOTE)return quotes.runCanonical(run,mapping,requested);
        if(requested!=null&&!TEST_CASE.equals(requested))throw new AgentizationException("CONTRACT_TEST_CASE_NOT_ALLOWED",
                org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,"Only the registered deterministic contract test case can be selected");
        Instant started=Instant.now();ObjectNode evidence=mapper.createObjectNode().put("testCaseId",TEST_CASE).put("testVersion",1)
                .put("capability",mapping.capability().name());ContractTestOutcome outcome;String failure=null,responseHash=null;
        try{Request request=request(run,mapping.capability());var response=executor.execute(run.merchantId(),mapping,request.path(),
                    mapper.writeValueAsBytes(request.body()),MerchantExecutionMode.CONTRACT_TEST);
            evidence.put("httpStatus",response.statusCode()).put("contentType",response.contentType());
            if(response.statusCode()<200||response.statusCode()>=300){outcome=ContractTestOutcome.FAIL;failure="HTTP_STATUS_UNEXPECTED";}
            else if(response.contentType()==null||!response.contentType().toLowerCase(java.util.Locale.ROOT).startsWith("application/json")){outcome=ContractTestOutcome.FAIL;failure="RESPONSE_CONTENT_TYPE_INVALID";}
            else{JsonNode body=mapper.readTree(response.body());responseHash=canonical.hash(body);String required=requiredField(mapping.capability());
                if(required!=null&&!body.hasNonNull(required)){outcome=ContractTestOutcome.FAIL;failure="REQUIRED_RESPONSE_FIELD_MISSING";}
                else{outcome=ContractTestOutcome.PASS;evidence.put("requiredField",required==null?"json":required);}}
        }catch(RuntimeException ex){outcome=ContractTestOutcome.UNKNOWN;failure=ex instanceof MerchantExecutionException me?me.code():"CONTRACT_EXECUTION_FAILED";evidence.put("failureCode",failure);}
        if(failure!=null)evidence.put("failureCode",failure);String evidenceHash=canonical.hash(evidence);
        String signature=outcome==ContractTestOutcome.PASS?null:canonical.hashText(mapping.mappingProposalId()+"|"+TEST_CASE+"|"+failure+"|"+evidenceHash);
        return repository.createCompleted(run.merchantId(),run.runId(),mapping,TEST_CASE,1,started,outcome,failure,evidence,responseHash,evidenceHash,signature);}

    private Request request(AgentizationRun run,CanonicalCapability capability){ObjectNode body=mapper.createObjectNode();Map<String,String> path=Map.of();
        switch(capability){
            case SEARCH_PRODUCTS -> body.put("query","contract smoke test").put("limit",1);
            case GET_AVAILABILITY -> body.put("merchantId",run.merchantId().toString()).put("productId","demo-product").put("merchantSku","DEMO-SKU").put("requestedQuantity",1);
            case PLACE_ORDER -> body.put("merchantOperationId","contract-test-"+run.runId()).put("contractTest",true).putArray("lineItems").addObject().put("productId","demo-product").put("merchantSku","DEMO-SKU").put("quantity",1);
            case GET_ORDER_STATE,CANCEL_ORDER,RETURN_ITEM -> path=Map.of("orderId","contract-order");
            default -> throw new AgentizationException("CONTRACT_TEST_CAPABILITY_UNSUPPORTED",org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,"Capability has no deterministic P0 contract");
        }return new Request(path,body);}
    private static String requiredField(CanonicalCapability capability){return switch(capability){case SEARCH_PRODUCTS->"classification";case GET_AVAILABILITY->"available";case PLACE_ORDER,GET_ORDER_STATE,CANCEL_ORDER,RETURN_ITEM->"orderId";default->null;};}
    private record Request(Map<String,String> path,ObjectNode body){}
}
