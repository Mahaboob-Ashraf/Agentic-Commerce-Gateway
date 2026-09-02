package dev.agenticcommerce.gateway.demo;

import static dev.agenticcommerce.gateway.catalogue.CatalogueModels.*;
import static dev.agenticcommerce.gateway.demo.DemoMerchantModels.*;

import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import dev.agenticcommerce.gateway.catalogue.HybridCatalogueRetrievalService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class DemoMerchantService {
    private final DemoMerchantRepository repository;private final HybridCatalogueRetrievalService retrieval;
    private final CanonicalJsonService canonical;private final ObjectMapper mapper;
    public DemoMerchantService(DemoMerchantRepository repository,HybridCatalogueRetrievalService retrieval,
            CanonicalJsonService canonical,ObjectMapper mapper){this.repository=repository;this.retrieval=retrieval;
        this.canonical=canonical;this.mapper=mapper;}

    public JsonNode search(String key,JsonNode body){Profile p=profile(key);
        if(body.path("contractTest").asBoolean(false))return mapper.valueToTree(new SearchResponse(
                MatchClassification.NO_TRUSTWORTHY_MATCH,List.of(),List.of(),false,"contract-test",
                List.of("DETERMINISTIC_DEMO_CONTRACT_TEST")));
        String query=text(body,"query",512,false);
        SearchRequest request=new SearchRequest(query,nullable(body,"merchantSku",128),nullable(body,"gtin",14),
                nullable(body,"brand",256),nullable(body,"variant",256),nullable(body,"sizeStorage",128),
                nullable(body,"colour",128),nullable(body,"category",256),number(body,"minimumPriceMinor"),
                number(body,"maximumPriceMinor"),body.path("vegetarian").isBoolean()?body.path("vegetarian").booleanValue():null,
                nullable(body,"prohibitedAllergen",64),Math.min(Math.max(body.path("limit").asInt(10),1),20));
        return mapper.valueToTree(retrieval.search(p.merchantId(),request));}

    public JsonNode availability(String key,JsonNode body){Profile p=profile(key);if("demo-product".equals(body.path("productId").asText())){Instant now=Instant.now();return mapper.createObjectNode()
                .put("merchantId",p.merchantId().toString()).put("productId","demo-product").put("merchantSku","DEMO-SKU")
                .putNull("variant").put("requestedQuantity",1).put("available",true).put("availableQuantity",10)
                .put("observedAt",now.toString()).put("expiresAt",now.plusSeconds(120).toString());}InventoryProduct product=requireProduct(p,body,false);
        int requested=quantity(body,"requestedQuantity");Instant now=Instant.now();ObjectNode result=mapper.createObjectNode();
        result.put("merchantId",p.merchantId().toString()).put("productId",product.productId().toString())
                .put("merchantSku",product.merchantSku());if(product.variant()==null)result.putNull("variant");else result.put("variant",product.variant());
        result.put("requestedQuantity",requested).put("available",product.availableQuantity()>=requested)
                .put("availableQuantity",product.availableQuantity()).put("observedAt",now.toString())
                .put("expiresAt",now.plusSeconds(120).toString());return result;}

    public JsonNode quote(String key,JsonNode body){Profile p=profile(key);
        if("demo-product".equals(body.path("productId").asText()))return mapper.createObjectNode()
                .put("quoteId","contract-quote-v1").put("finalAmountMinor",49900).put("currency","INR")
                .put("expiresAt",Instant.now().plusSeconds(300).toString());
        JsonNode input=body.path("lineItems");if(!input.isArray()||input.isEmpty()||input.size()>100)bad("lineItems required");
        ArrayNode lines=mapper.createArrayNode();long subtotal=0;String currency=null;for(JsonNode row:input){InventoryProduct product=requireProduct(p,row,false);
            int quantity=quantity(row,"quantity");if(product.availableQuantity()<quantity)throw new ResponseStatusException(HttpStatus.CONFLICT,"insufficient stock");
            if(currency!=null&&!currency.equals(product.currency()))throw new ResponseStatusException(HttpStatus.CONFLICT,"mixed currencies are unsupported");
            currency=product.currency();
            long line=Math.multiplyExact(product.priceMinor(),quantity);subtotal=Math.addExact(subtotal,line);lines.addObject()
                    .put("productId",product.productId().toString()).put("merchantSku",product.merchantSku())
                    .put("quantity",quantity).put("unitAmountMinor",product.priceMinor()).put("lineAmountMinor",line);}
        long delivery=deliveryMinor(p,subtotal);ObjectNode result=mapper.createObjectNode();
        result.put("cartId",body.path("cartId").asText()).put("cartHash",body.path("cartHash").asText())
                .put("quoteId","dq_"+UUID.randomUUID().toString().replace("-","")).put("quoteVersion","demo-v1")
                .put("subtotalMinor",subtotal).put("taxMinor",0).put("deliveryMinor",delivery).put("feesMinor",0)
                .put("finalAmountMinor",Math.addExact(subtotal,delivery)).put("currency",currency)
                .put("expiresAt",Instant.now().plusSeconds(300).toString()).put("stockGuaranteed",false).put("priceGuaranteed",true);
        result.set("lineItems",lines);return result;}

    @Transactional
    public JsonNode placeOrder(String key,JsonNode body){Profile p=profile(key);String operation=text(body,"merchantOperationId",256,true);
        if(body.path("contractTest").asBoolean(false))return mapper.createObjectNode().put("orderId","contract-order")
                .put("merchantOperationId",operation).put("state","PLACED").put("totalMinor",0).put("currency","INR");
        String hash=canonical.hash(body);var prior=repository.orderByOperation(p.merchantId(),operation,true);
        if(prior.isPresent()){if(!prior.get().requestHash().equals(hash))throw new ResponseStatusException(HttpStatus.CONFLICT,"idempotency identity reused with different request");return orderResponse(prior.get());}
        if(!body.path("amountMinor").isIntegralNumber()||!body.path("amountMinor").canConvertToLong()||body.path("amountMinor").longValue()<0)bad("amountMinor invalid");
        long suppliedAmount=body.path("amountMinor").longValue();String suppliedCurrency=text(body,"currency",3,true);
        if(!suppliedCurrency.matches("[A-Z]{3}"))bad("currency invalid");
        JsonNode input=body.path("lineItems");if(!input.isArray()||input.isEmpty()||input.size()>100)bad("lineItems required");
        List<JsonNode> sorted=new ArrayList<>();input.forEach(sorted::add);sorted.sort(Comparator.comparing(v->v.path("productId").asText()));
        List<PreparedOrderLine> prepared=new ArrayList<>();long subtotal=0;for(JsonNode row:sorted){InventoryProduct product=requireProduct(p,row,true);int quantity=quantity(row,"quantity");
            if(row.has("unitAmountMinor")&&row.path("unitAmountMinor").asLong(-1)!=product.priceMinor())throw new ResponseStatusException(HttpStatus.CONFLICT,"price changed");
            if(!suppliedCurrency.equals(product.currency()))throw new ResponseStatusException(HttpStatus.CONFLICT,"currency changed");
            if(product.availableQuantity()<quantity)throw new ResponseStatusException(HttpStatus.CONFLICT,"insufficient stock");
            long line=Math.multiplyExact(product.priceMinor(),quantity);subtotal=Math.addExact(subtotal,line);
            prepared.add(new PreparedOrderLine(product,quantity,line));}
        long expectedTotal=Math.addExact(subtotal,deliveryMinor(p,subtotal));
        if(suppliedAmount!=expectedTotal)throw new ResponseStatusException(HttpStatus.CONFLICT,"authoritative amount mismatch");
        ArrayNode persisted=mapper.createArrayNode();for(PreparedOrderLine line:prepared){InventoryProduct product=line.product();repository.decrement(p.merchantId(),product.productId(),line.quantity());
            persisted.addObject().put("productId",product.productId().toString()).put("merchantSku",product.merchantSku()).put("quantity",line.quantity())
                    .put("unitAmountMinor",product.priceMinor()).put("lineAmountMinor",line.lineAmountMinor());}
        String orderId="dmo_"+UUID.randomUUID().toString().replace("-","");Order order=repository.createOrder(p.merchantId(),operation,orderId,hash,
                nullable(body,"merchantCustomerReference",256),persisted,expectedTotal,suppliedCurrency);return orderResponse(order);}

    public JsonNode order(String key,String orderId){Profile p=profile(key);if("contract-order".equals(orderId))return mapper.createObjectNode().put("orderId",orderId).put("state","PLACED");return orderResponse(repository.orderByIdentity(p.merchantId(),orderId,false)
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"order not found")));}
    @Transactional public JsonNode cancel(String key,String orderId){Profile p=profile(key);if("contract-order".equals(orderId))return mapper.createObjectNode().put("orderId",orderId).put("state","CANCELLED");if(!p.cancellationAllowed())throw new ResponseStatusException(HttpStatus.CONFLICT,"merchant cancellation policy denies this request");
        Order order=requireOrder(p,orderId);if(order.state().equals("CANCELLED"))return orderResponse(order);if(!order.state().equals("PLACED"))throw new ResponseStatusException(HttpStatus.CONFLICT,"order cannot be cancelled");
        repository.restore(p.merchantId(),order.lineItems());return orderResponse(repository.updateState(order,"CANCELLED",true));}
    @Transactional public JsonNode requestReturn(String key,String orderId){Profile p=profile(key);if("contract-order".equals(orderId))return mapper.createObjectNode().put("orderId",orderId).put("state","RETURN_REQUESTED");if(!p.returnsAllowed())throw new ResponseStatusException(HttpStatus.CONFLICT,"merchant return policy denies this request");
        Order order=requireOrder(p,orderId);if(order.state().equals("RETURN_REQUESTED"))return orderResponse(order);
        if(p.profileCode().equals("FRESH_BASKET")&&!p.perishableReturnsAllowed())throw new ResponseStatusException(HttpStatus.CONFLICT,"food returns are restricted");
        if(!order.state().equals("PLACED"))throw new ResponseStatusException(HttpStatus.CONFLICT,"order cannot be returned");return orderResponse(repository.updateState(order,"RETURN_REQUESTED",false));}

    private Profile profile(String key){if(key==null||!key.matches("[a-z0-9-]{1,64}"))bad("merchant key invalid");return repository.profile(key)
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"demo merchant not found"));}
    private InventoryProduct requireProduct(Profile p,JsonNode body,boolean lock){String sku=nullable(body,"merchantSku",128);UUID id=null;
        try{if(body.hasNonNull("productId")&&!body.path("productId").asText().isBlank())id=UUID.fromString(body.path("productId").asText());}catch(IllegalArgumentException e){bad("productId invalid");}
        if(sku==null&&id==null)bad("product identity required");return repository.product(p.merchantId(),sku,id,lock)
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"product not found"));}
    private Order requireOrder(Profile p,String id){if(id==null||id.length()>64)bad("order identity invalid");return repository.orderByIdentity(p.merchantId(),id,true)
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"order not found"));}
    private ObjectNode orderResponse(Order o){ObjectNode n=mapper.createObjectNode();n.put("orderId",o.merchantOrderId()).put("merchantOperationId",o.operationId())
            .put("state",o.state()).put("totalMinor",o.totalMinor()).put("currency",o.currency()).put("createdAt",o.createdAt().toString());n.set("lineItems",o.lineItems());return n;}
    private static int quantity(JsonNode body,String field){int q=body.path(field).asInt(-1);if(q<1||q>100)bad(field+" invalid");return q;}
    private static long deliveryMinor(Profile profile,long subtotal){return subtotal>=50000?0:(profile.profileCode().equals("FRESH_BASKET")?4900:7900);}
    private static Long number(JsonNode body,String field){return body.path(field).isIntegralNumber()?body.path(field).longValue():null;}
    private static String nullable(JsonNode body,String field,int max){return body.hasNonNull(field)&&!body.path(field).asText().isBlank()?text(body,field,max,true):null;}
    private static String text(JsonNode body,String field,int max,boolean required){String v=body.path(field).asText("").strip();if((required&&v.isBlank())||v.length()>max)bad(field+" invalid");return v;}
    private static void bad(String message){throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,message);}
    private record PreparedOrderLine(InventoryProduct product,int quantity,long lineAmountMinor){}
}
