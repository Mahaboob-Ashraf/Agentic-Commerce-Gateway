package dev.agenticcommerce.gateway.intent;

import static dev.agenticcommerce.gateway.intent.BuyerModels.*;

import dev.agenticcommerce.gateway.agentization.execution.ApprovedMerchantExecutor;
import dev.agenticcommerce.gateway.agentization.execution.MerchantExecutionMode;
import dev.agenticcommerce.gateway.agentization.model.CapabilityMappingProposal;
import dev.agenticcommerce.gateway.agentization.model.CanonicalCapability;
import dev.agenticcommerce.gateway.agentization.model.MappingTransformation;
import dev.agenticcommerce.gateway.agentization.persistence.CapabilityMappingProposalRepository;
import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class AuthoritativeQuoteService {
    private final ApprovedMerchantExecutor executor;private final CapabilityMappingProposalRepository mappings;
    private final BuyerRepository repository;private final CanonicalJsonService canonical;private final ObjectMapper mapper;
    public AuthoritativeQuoteService(ApprovedMerchantExecutor executor,CapabilityMappingProposalRepository mappings,
            BuyerRepository repository,CanonicalJsonService canonical,ObjectMapper mapper){this.executor=executor;this.mappings=mappings;this.repository=repository;this.canonical=canonical;this.mapper=mapper;}
    public MerchantQuote quote(CandidateCart cart,MerchantCandidate merchant){if(!cart.merchantId().equals(merchant.merchantId()))invalid("QUOTE_MERCHANT_MISMATCH","Cart and READY merchant differ");
        if(merchant.quoteMappingId()==null)throw new BuyerException("GET_QUOTE_NOT_READY",HttpStatus.CONFLICT,"Merchant GET_QUOTE is not READY and advertised");
        CapabilityMappingProposal mapping=mappings.findByMerchantAndId(cart.merchantId(),merchant.quoteMappingId()).orElseThrow(()->new BuyerException("QUOTE_MAPPING_NOT_FOUND",HttpStatus.CONFLICT,"READY quote mapping was not found"));
        if(mapping.capability()!=CanonicalCapability.GET_QUOTE||!"VALID".equals(mapping.validationStatus()))invalid("QUOTE_MAPPING_INVALID","Manifest mapping is not a valid GET_QUOTE contract");
        byte[] body=request(cart);var response=executor.execute(cart.merchantId(),mapping,Map.of(),body,MerchantExecutionMode.RUNTIME);
        if(response.statusCode()<200||response.statusCode()>=300)invalid("QUOTE_HTTP_STATUS","Merchant quote returned a non-success status");
        if(response.contentType()==null||!response.contentType().toLowerCase(java.util.Locale.ROOT).startsWith("application/json"))invalid("QUOTE_CONTENT_TYPE","Merchant quote must be JSON");
        JsonNode value;try{value=mapper.readTree(response.body());}catch(RuntimeException e){throw new BuyerException("QUOTE_JSON_INVALID",HttpStatus.UNPROCESSABLE_ENTITY,"Merchant quote JSON is invalid");}
        boolean cartBound=cart.cartId().toString().equals(value.path("cartId").asText())||cart.cartHash().equals(value.path("cartHash").asText());
        if(!cartBound)invalid("QUOTE_CART_MISMATCH","Merchant quote is not bound to the exact candidate cart");
        String quoteId=text(extract(value,mapping.responseBindings().path("quoteId").asText("body.quoteId")));if(quoteId==null)invalid("QUOTE_ID_MISSING","Merchant quote identity is required");
        String currency=text(extract(value,currencyBinding(mapping)));Long total=money(extract(value,mapping.responseBindings().path("amount").asText("body.finalAmountMinor")),mapping);
        if(total!=null&&(!"INR".equals(currency)))invalid("QUOTE_CURRENCY_INVALID","P0 authoritative quote currency must be INR");
        Instant expires=parseInstant(value.path("expiresAt").asText(null));if(expires==null||!expires.isAfter(Instant.now()))invalid("QUOTE_EXPIRED","Merchant quote expiry is missing or expired");
        List<MerchantQuoteItem> items=parseItems(cart,value.path("lineItems"));Instant observed=Instant.now();
        var evidence=mapper.createObjectNode();evidence.put("merchantId",cart.merchantId().toString());evidence.put("cartId",cart.cartId().toString());evidence.put("cartHash",cart.cartHash());
        evidence.put("mappingId",mapping.mappingProposalId().toString());evidence.set("merchantResponse",value);String hash=canonical.hash(evidence);
        return repository.createQuote(cart,mapping.mappingProposalId(),bounded(quoteId,256),nullableText(value,"quoteVersion"),
                integral(value,"subtotalMinor"),integral(value,"taxMinor"),integral(value,"deliveryMinor"),integral(value,"feesMinor"),
                total,total==null?null:currency,expires,nullableBoolean(value,"stockGuaranteed"),nullableBoolean(value,"priceGuaranteed"),hash,observed,items);}
    private byte[] request(CandidateCart cart){var root=mapper.createObjectNode();root.put("cartId",cart.cartId().toString());root.put("cartHash",cart.cartHash());var lines=root.putArray("lineItems");
        cart.items().forEach(i->{var n=lines.addObject();n.put("productId",i.productId().toString());n.put("merchantSku",i.merchantSku());n.put("quantity",i.quantity());});return mapper.writeValueAsBytes(root);}
    private List<MerchantQuoteItem> parseItems(CandidateCart cart,JsonNode rows){if(!rows.isArray()||rows.size()!=cart.items().size())invalid("QUOTE_LINE_ITEMS_INVALID","Quote must return every exact cart line item");
        List<MerchantQuoteItem> result=new ArrayList<>();Set<String> seen=new HashSet<>();for(JsonNode row:rows){String sku=row.path("merchantSku").asText(null);if(sku==null||!seen.add(sku))invalid("QUOTE_LINE_ITEMS_INVALID","Quote line item identity is missing or duplicated");
            CandidateCartItem expected=cart.items().stream().filter(i->i.merchantSku().equals(sku)).findFirst().orElseThrow(()->new BuyerException("QUOTE_PRODUCT_SUBSTITUTION",HttpStatus.UNPROCESSABLE_ENTITY,"Merchant quote substituted an unexpected product"));
            int quantity=row.path("quantity").asInt(-1);if(quantity!=expected.quantity())invalid("QUOTE_QUANTITY_MISMATCH","Merchant quote quantity differs from the exact cart");
            result.add(new MerchantQuoteItem(null,expected.productId(),sku,quantity,integral(row,"unitAmountMinor"),integral(row,"lineAmountMinor")));}
        return List.copyOf(result);}
    private static Long money(JsonNode node,CapabilityMappingProposal mapping){if(node==null||node.isMissingNode()||node.isNull())return null;if(!node.isIntegralNumber()||!node.canConvertToLong()||node.longValue()<0)invalid("QUOTE_AMOUNT_INVALID","Final amount must be non-negative integer money");
        long raw=node.longValue();try{return MappingTransformation.valueOf(mapping.transformations().path("amount").asText("IDENTITY"))==MappingTransformation.MONEY_RUPEES_TO_PAISE?Math.multiplyExact(raw,100):raw;}catch(RuntimeException e){invalid("QUOTE_AMOUNT_INVALID","Final amount normalization failed");return null;}}
    private static String currencyBinding(CapabilityMappingProposal mapping){String value=mapping.responseBindings().path("currency").asText(null);if(value!=null)return value;String field=mapping.currencyInterpretation().path("field").asText("currency");return field.startsWith("body.")?field:"body."+field;}
    private static JsonNode extract(JsonNode root,String binding){if(binding==null||!binding.startsWith("body."))return null;JsonNode current=root;for(String part:binding.substring(5).split("\\."))current=current.path(part);return current;}
    private static Long integral(JsonNode n,String field){JsonNode v=n.path(field);if(v.isMissingNode()||v.isNull())return null;if(!v.isIntegralNumber()||!v.canConvertToLong()||v.longValue()<0)invalid("QUOTE_AMOUNT_INVALID",field+" must be non-negative integer money");return v.longValue();}
    private static String text(JsonNode value){return value!=null&&value.isTextual()&&!value.asText().isBlank()?value.asText():null;}
    private static String nullableText(JsonNode value,String field){return text(value.path(field));}
    private static Boolean nullableBoolean(JsonNode value,String field){return value.path(field).isBoolean()?value.path(field).booleanValue():null;}
    private static Instant parseInstant(String value){try{return value==null?null:Instant.parse(value);}catch(RuntimeException e){return null;}}
    private static String bounded(String v,int max){return v.length()<=max?v:v.substring(0,max);}
    private static void invalid(String code,String message){throw new BuyerException(code,HttpStatus.UNPROCESSABLE_ENTITY,message);}
}
