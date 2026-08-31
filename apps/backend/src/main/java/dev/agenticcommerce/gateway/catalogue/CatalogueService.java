package dev.agenticcommerce.gateway.catalogue;

import static dev.agenticcommerce.gateway.catalogue.CatalogueModels.*;

import dev.agenticcommerce.gateway.agentization.service.AgentizationException;
import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import dev.agenticcommerce.gateway.identity.service.MerchantAdministrationAccessService;
import java.text.Normalizer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class CatalogueService {
    public static final int MAX_ROWS = 5_000;
    private static final Pattern GTIN = Pattern.compile("[0-9]{8,14}");
    private final CatalogueRepository repository;
    private final CatalogueProvider catalogueProvider;
    private final EmbeddingProvider embeddingProvider;
    private final MerchantAdministrationAccessService access;
    private final CanonicalJsonService canonical;
    private final ObjectMapper mapper;

    public CatalogueService(CatalogueRepository repository,CatalogueProvider catalogueProvider,
            EmbeddingProvider embeddingProvider,MerchantAdministrationAccessService access,
            CanonicalJsonService canonical,ObjectMapper mapper){this.repository=repository;this.catalogueProvider=catalogueProvider;
        this.embeddingProvider=embeddingProvider;this.access=access;this.canonical=canonical;this.mapper=mapper;}

    @Transactional
    public IngestionResult ingest(UUID actorId,UUID merchantId,String format,String payload){
        requireAdmin(actorId,merchantId);
        if(payload==null||payload.isBlank()||payload.length()>1_000_000)throw invalid("CATALOGUE_PAYLOAD_INVALID","Catalogue payload is empty or exceeds 1 MB");
        String normalizedFormat=format==null?"JSON":format.strip().toUpperCase(Locale.ROOT);
        if(!Set.of("JSON","CSV").contains(normalizedFormat))throw invalid("CATALOGUE_FORMAT_INVALID","Only JSON and CSV are supported");
        String sourceHash=canonical.hashText(payload);
        CatalogueVersion draft=repository.createVersion(merchantId,actorId,normalizedFormat,sourceHash);
        List<ProductInput> rows=parse(normalizedFormat,payload);
        if(rows.size()>MAX_ROWS)throw invalid("CATALOGUE_TOO_MANY_ROWS","Catalogue exceeds 5000 rows");
        List<RowRejection> rejections=new ArrayList<>();List<Product> accepted=new ArrayList<>();
        Set<String> skus=new LinkedHashSet<>(),gtins=new LinkedHashSet<>();int enriched=0,unresolved=0;
        for(int i=0;i<rows.size();i++){
            ProductInput input;
            try{input=normalize(rows.get(i),i+1);}catch(IllegalArgumentException e){rejections.add(new RowRejection(i+1,"MALFORMED_ROW",e.getMessage()));continue;}
            if(!skus.add(input.merchantSku().toLowerCase(Locale.ROOT))){rejections.add(new RowRejection(i+1,"DUPLICATE_SKU","merchantSku is duplicated"));continue;}
            if(input.gtin()!=null&&!gtins.add(input.gtin())){rejections.add(new RowRejection(i+1,"DUPLICATE_GTIN","gtin is duplicated"));continue;}
            Product product=repository.insertProduct(merchantId,draft.id(),input,normalizeText(input.canonicalName()));accepted.add(product);
            ObjectNode merchantMatch=mapper.createObjectNode().put("merchantSku",input.merchantSku());
            if(input.gtin()!=null)merchantMatch.put("gtin",input.gtin());
            String merchantResolutionHash=canonical.hash(merchantMatch);
            repository.insertResolution(merchantId,draft.id(),product.id(),"MERCHANT",input.sourceRecordId(),
                    IdentityOutcome.EXACT,merchantMatch,mapper.createObjectNode(),merchantResolutionHash);
            boolean productEnriched=false;
            if(input.gtin()!=null){
                try{
                    var external=catalogueProvider.lookupExactBarcode(input.gtin());
                    if(external.isPresent()){
                        var resolution=resolve(product,external.get());
                        var persisted=repository.insertResolution(merchantId,draft.id(),product.id(),"OPEN_FOOD_FACTS",
                                external.get().sourceRecordId(),resolution.outcome(),resolution.matched(),resolution.conflicts(),resolution.hash());
                        if(resolution.outcome()==IdentityOutcome.EXACT){persistExternalFacts(merchantId,draft.id(),product,persisted.id(),external.get());productEnriched=true;}
                        else unresolved++;
                    } else unresolved++;
                }catch(RuntimeException ignored){unresolved++;}
            }else unresolved++;
            if(productEnriched)enriched++;
            String embeddingInput=embeddingInput(product);String inputHash=canonical.hashText(embeddingInput);
            try{List<Float> values=embeddingProvider.embed(embeddingInput);if(values.size()!=EmbeddingProvider.OUTPUT_DIMENSIONS)throw new IllegalStateException("EMBEDDING_DIMENSION_MISMATCH");
                repository.insertEmbedding(merchantId,draft.id(),product.id(),inputHash,values,null);
            }catch(RuntimeException failure){repository.insertEmbedding(merchantId,draft.id(),product.id(),inputHash,null,safeFailure(failure));}
        }
        if(accepted.isEmpty())throw invalid("CATALOGUE_HAS_NO_VALID_PRODUCTS","No valid catalogue row can be published");
        ArrayNode content=mapper.createArrayNode();accepted.stream().sorted(java.util.Comparator.comparing(Product::merchantSku))
                .forEach(p->content.add(contentHashMaterial(p)));
        String contentHash=canonical.hash(content);ObjectNode evidence=mapper.createObjectNode();
        evidence.put("sourceHash",sourceHash).put("contentHash",contentHash).put("normalizerVersion","catalogue-v1")
                .put("embeddingModel",EmbeddingProvider.MODEL).put("embeddingDimensions",768);
        CatalogueVersion published=repository.publish(merchantId,draft.id(),contentHash,accepted.size(),rejections.size(),enriched,unresolved,evidence);
        persistPublicationEvidence(published,accepted);
        return new IngestionResult(published,List.copyOf(rejections));
    }

    public List<CatalogueVersion> versions(UUID actorId,UUID merchantId){requireAdmin(actorId,merchantId);return repository.versions(merchantId);}
    public CatalogueHealth health(UUID actorId,UUID merchantId){requireAdmin(actorId,merchantId);return health(merchantId);}
    public CatalogueHealth health(UUID merchantId){var v=requirePublished(merchantId);return repository.health(merchantId,v.id(),v.version());}
    public List<Product> products(UUID actorId,UUID merchantId,UUID versionId,int limit){requireAdmin(actorId,merchantId);return repository.products(merchantId,versionId,limit);}
    @Transactional
    public EnrichmentStatus enrich(UUID actorId,UUID merchantId,UUID versionId,UUID productId){requireAdmin(actorId,merchantId);
        Product product=repository.findProduct(merchantId,versionId,productId).orElseThrow(()->invalid("PRODUCT_NOT_FOUND","Tenant-owned product was not found"));
        if(product.gtin()==null)return new EnrichmentStatus(productId,IdentityOutcome.UNRESOLVED,0,"BARCODE_REQUIRED");
        var external=catalogueProvider.lookupExactBarcode(product.gtin());if(external.isEmpty())return new EnrichmentStatus(productId,IdentityOutcome.UNRESOLVED,0,"EXTERNAL_PRODUCT_NOT_FOUND");
        var resolution=resolve(product,external.get());var persisted=repository.insertResolution(merchantId,versionId,productId,"OPEN_FOOD_FACTS",
                external.get().sourceRecordId(),resolution.outcome(),resolution.matched(),resolution.conflicts(),resolution.hash());
        int before=repository.facts(merchantId,versionId,productId,"BARCODE").size();if(resolution.outcome()==IdentityOutcome.EXACT)persistExternalFacts(merchantId,versionId,product,persisted.id(),external.get());
        int attached=repository.facts(merchantId,versionId,productId,"BARCODE").size()-before;
        return new EnrichmentStatus(productId,resolution.outcome(),Math.max(attached,0),resolution.outcome()==IdentityOutcome.EXACT?"FACTS_ATTACHED":"HARD_IDENTITY_MISMATCH");}
    public InspectionSample inspect(UUID merchantId,int limit){var v=requirePublished(merchantId);var health=repository.health(merchantId,v.id(),v.version());
        return new InspectionSample("v"+v.version()+":"+v.contentHash(),health,repository.products(merchantId,v.id(),Math.min(limit,20)),
                List.of("merchant identity and commerce records","Open Food Facts only after EXACT identity","Gemini "+EmbeddingProvider.MODEL+" vector(768); lexical fallback explicit"));}

    CatalogueVersion requirePublished(UUID merchantId){return repository.latestPublished(merchantId)
            .orElseThrow(()->invalid("PUBLISHED_CATALOGUE_NOT_FOUND","Merchant has no published catalogue"));}

    private ProductInput normalize(ProductInput p,int row){
        if(p==null)throw new IllegalArgumentException("row is null");
        String sku=bounded(p.merchantSku(),128,true,"merchantSku");String name=bounded(p.canonicalName(),512,true,"canonicalName");
        String gtin=bounded(p.gtin(),14,false,"gtin");if(gtin!=null&&!GTIN.matcher(gtin).matches())throw new IllegalArgumentException("gtin must contain 8 to 14 digits");
        String currency=bounded(p.currency(),3,false,"currency");if(currency!=null&&!currency.toUpperCase(Locale.ROOT).matches("[A-Z]{3}"))throw new IllegalArgumentException("currency must be an ISO-style 3-letter code");
        if(p.priceMinor()!=null&&p.priceMinor()<0)throw new IllegalArgumentException("priceMinor cannot be negative");
        if(p.stockQuantity()!=null&&p.stockQuantity()<0)throw new IllegalArgumentException("stockQuantity cannot be negative");
        return new ProductInput(sku,gtin,bounded(p.brand(),256,false,"brand"),name,bounded(p.variant(),256,false,"variant"),
                bounded(p.sizeStorage(),128,false,"sizeStorage"),bounded(p.colour(),128,false,"colour"),bounded(p.category(),256,false,"category"),
                bounded(p.description(),4000,false,"description"),p.active()==null?true:p.active(),
                bounded(p.sourceRecordId()==null?"row-"+row:p.sourceRecordId(),256,true,"sourceRecordId"),p.priceMinor(),
                currency==null?null:currency.toUpperCase(Locale.ROOT),p.stockQuantity(),p.availability()==null?Availability.UNKNOWN:p.availability(),
                bounded(p.observationSource()==null?"CATALOGUE_UPLOAD":p.observationSource(),128,true,"observationSource"),
                bounded(p.sourceVersion(),128,false,"sourceVersion"),p.observedAt()==null?Instant.now():p.observedAt());
    }

    private Resolution resolve(Product p,CatalogueProvider.ExternalProduct x){
        ObjectNode matched=mapper.createObjectNode(),conflicts=mapper.createObjectNode();
        if(p.gtin()!=null&&p.gtin().equals(x.barcode()))matched.put("barcode",p.gtin());else conflicts.put("barcode",String.valueOf(x.barcode()));
        compareHard("brand",p.brand(),x.brand(),matched,conflicts);compareHard("variant",p.variant(),x.variant(),matched,conflicts);
        compareHard("size",p.sizeStorage(),x.size(),matched,conflicts);
        IdentityOutcome outcome=!conflicts.isEmpty()?IdentityOutcome.CONFLICT:matched.has("barcode")?IdentityOutcome.EXACT:
                matched.size()>=3?IdentityOutcome.PROBABLE:IdentityOutcome.UNRESOLVED;
        ObjectNode evidence=mapper.createObjectNode();evidence.put("productId",p.id().toString()).put("externalRecord",x.sourceRecordId()).put("outcome",outcome.name());
        evidence.set("matched",matched);evidence.set("conflicts",conflicts);return new Resolution(outcome,matched,conflicts,canonical.hash(evidence));
    }
    private void compareHard(String field,String merchant,String external,ObjectNode matched,ObjectNode conflicts){
        if(merchant==null||external==null)return;if(normalizeText(merchant).equals(normalizeText(external)))matched.put(field,merchant);else conflicts.put(field,external);
    }
    private void persistExternalFacts(UUID merchantId,UUID versionId,Product p,UUID resolutionId,CatalogueProvider.ExternalProduct x){
        Instant expires=x.observedAt().plus(180,ChronoUnit.DAYS);
        fact(merchantId,versionId,p.id(),resolutionId,"BARCODE",mapper.getNodeFactory().textNode(x.barcode()),x,expires);
        if(x.brand()!=null)fact(merchantId,versionId,p.id(),resolutionId,"BRAND",mapper.getNodeFactory().textNode(x.brand()),x,expires);
        if(!x.ingredients().isEmpty())fact(merchantId,versionId,p.id(),resolutionId,"INGREDIENTS",mapper.valueToTree(x.ingredients()),x,expires);
        for(String allergen:x.allergens())fact(merchantId,versionId,p.id(),resolutionId,"ALLERGEN",mapper.getNodeFactory().textNode(normalizeText(allergen)),x,expires);
        if(x.vegetarian()!=null)fact(merchantId,versionId,p.id(),resolutionId,"VEGETARIAN",mapper.getNodeFactory().booleanNode(x.vegetarian()),x,expires);
        if(x.proteinGramsPer100g()!=null)fact(merchantId,versionId,p.id(),resolutionId,"PROTEIN",mapper.getNodeFactory().numberNode(x.proteinGramsPer100g()),x,expires);
        if(x.imageUrl()!=null)fact(merchantId,versionId,p.id(),resolutionId,"IMAGE",mapper.getNodeFactory().textNode(x.imageUrl()),x,expires);
    }
    private void fact(UUID m,UUID v,UUID p,UUID r,String type,JsonNode value,CatalogueProvider.ExternalProduct x,Instant expires){
        ObjectNode material=mapper.createObjectNode();material.put("type",type).set("value",value);material.put("source",x.sourceRecordId());
        repository.insertFact(m,v,p,r,type,value,"OPEN_FOOD_FACTS",x.sourceRecordId(),x.sourceVersion(),"SECONDARY","ACTIVE",x.observedAt(),expires,canonical.hash(material));
    }
    private void persistPublicationEvidence(CatalogueVersion v,List<Product> products){
        ObjectNode schema=mapper.createObjectNode().put("version",v.version()).put("accepted",v.accepted());ArrayNode refs=mapper.createArrayNode().add("catalogue:"+v.id());
        boolean schemaPass=repository.health(v.merchantId(),v.id(),v.version()).products()==products.size();
        repository.insertEvidence(v.merchantId(),v.id(),"CATALOGUE_SCHEMA",schemaPass?"PASS":"FAIL",schema,refs,canonical.hash(schema));
        Product first=products.getFirst();ObjectNode exact=mapper.createObjectNode().put("sku",first.merchantSku()).put("expectedProduct",first.id().toString());
        var exactCandidates=repository.lexicalCandidates(v.merchantId(),v.id(),normalizeText(first.merchantSku()),first.merchantSku(),null,null,null,null,5);
        boolean exactPass=!exactCandidates.isEmpty()&&exactCandidates.getFirst().product().id().equals(first.id())&&exactCandidates.getFirst().exact()==1.0;
        repository.insertEvidence(v.merchantId(),v.id(),"EXACT_PRODUCT_RETRIEVAL",exactPass?"PASS":"FAIL",exact,mapper.createArrayNode().add("product:"+first.id()),canonical.hash(exact));
        ObjectNode noMatch=mapper.createObjectNode().put("query","__catalogue_open_set_sentinel__").put("expected","NO_TRUSTWORTHY_MATCH");
        boolean noMatchPass=repository.lexicalCandidates(v.merchantId(),v.id(),"catalogue open set sentinel zxqv",null,null,null,null,null,5).isEmpty();
        repository.insertEvidence(v.merchantId(),v.id(),"NO_MATCH",noMatchPass?"PASS":"FAIL",noMatch,mapper.createArrayNode(),canonical.hash(noMatch));
        ObjectNode gate=mapper.createObjectNode().put("gate","exact merchant SKU/GTIN and hard-field mismatch rejection").put("expected","PASS");
        boolean gatePass=repository.latestIdentity(v.merchantId(),v.id(),first.id())==IdentityOutcome.EXACT;
        repository.insertEvidence(v.merchantId(),v.id(),"IDENTITY_GATE",gatePass?"PASS":"FAIL",gate,refs,canonical.hash(gate));
    }

    private List<ProductInput> parse(String format,String payload){
        try{if("JSON".equals(format)){JsonNode root=mapper.readTree(payload);JsonNode rows=root.isArray()?root:root.path("products");
            if(!rows.isArray())throw new IllegalArgumentException("JSON must be an array or contain a products array");
            List<ProductInput> values=new ArrayList<>();rows.forEach(n->values.add(mapper.treeToValue(n,ProductInput.class)));return values;}
            return parseCsv(payload);
        }catch(RuntimeException e){throw invalid("CATALOGUE_PARSE_FAILED",e.getMessage());}
    }
    private List<ProductInput> parseCsv(String payload){String[] lines=payload.replace("\r","").split("\n");if(lines.length<2)throw new IllegalArgumentException("CSV requires a header and data row");
        List<String> headers=csvLine(lines[0]);List<ProductInput> result=new ArrayList<>();for(int i=1;i<lines.length;i++){if(lines[i].isBlank())continue;List<String> cells=csvLine(lines[i]);
            ObjectNode n=mapper.createObjectNode();for(int c=0;c<headers.size()&&c<cells.size();c++){String value=cells.get(c);if(!value.isBlank()){
                if(Set.of("priceMinor","stockQuantity").contains(headers.get(c)))n.put(headers.get(c),Long.parseLong(value));
                else if("active".equals(headers.get(c)))n.put(headers.get(c),Boolean.parseBoolean(value));else n.put(headers.get(c),value);}}
            result.add(mapper.treeToValue(n,ProductInput.class));}return result;}
    private static List<String> csvLine(String line){List<String> cells=new ArrayList<>();StringBuilder cell=new StringBuilder();boolean quoted=false;
        for(int i=0;i<line.length();i++){char ch=line.charAt(i);if(ch=='"'){if(quoted&&i+1<line.length()&&line.charAt(i+1)=='"'){cell.append('"');i++;}else quoted=!quoted;}else if(ch==','&&!quoted){cells.add(cell.toString().strip());cell.setLength(0);}else cell.append(ch);}if(quoted)throw new IllegalArgumentException("Unterminated CSV quote");cells.add(cell.toString().strip());return cells;}
    private void requireAdmin(UUID actor,UUID merchant){if(!access.canAdminister(actor,merchant))throw new AgentizationException("MERCHANT_ADMIN_REQUIRED",HttpStatus.FORBIDDEN,"Actor cannot administer this merchant");}
    private static String bounded(String value,int max,boolean required,String field){if(value==null||value.isBlank()){if(required)throw new IllegalArgumentException(field+" is required");return null;}String v=value.strip();if(v.length()>max)throw new IllegalArgumentException(field+" exceeds "+max+" characters");return v;}
    static String normalizeText(String value){if(value==null)return "";return Normalizer.normalize(value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+"," ").strip();}
    private static String embeddingInput(Product p){return String.join(" | ",List.of(p.canonicalName(),p.brand()==null?"":p.brand(),p.variant()==null?"":p.variant(),p.sizeStorage()==null?"":p.sizeStorage(),p.category()==null?"":p.category(),p.description()==null?"":p.description()));}
    private ObjectNode contentHashMaterial(Product p){ObjectNode value=mapper.createObjectNode();
        value.put("merchantSku",p.merchantSku());value.put("gtin",p.gtin());value.put("brand",p.brand());
        value.put("canonicalName",p.canonicalName());value.put("normalizedName",p.normalizedName());
        value.put("variant",p.variant());value.put("sizeStorage",p.sizeStorage());value.put("colour",p.colour());
        value.put("category",p.category());value.put("description",p.description());value.put("active",p.active());
        value.put("sourceRecordId",p.sourceRecordId());if(p.priceMinor()==null)value.putNull("priceMinor");else value.put("priceMinor",p.priceMinor());
        value.put("currency",p.currency());if(p.stockQuantity()==null)value.putNull("stockQuantity");else value.put("stockQuantity",p.stockQuantity());
        value.put("availability",p.availability().name());return value;}
    private static String safeFailure(RuntimeException failure){String n=failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage();return n.replaceAll("[^A-Za-z0-9_-]","_").substring(0,Math.min(n.length(),120));}
    private static AgentizationException invalid(String code,String message){return new AgentizationException(code,HttpStatus.UNPROCESSABLE_ENTITY,message==null?code:message);}
    private record Resolution(IdentityOutcome outcome,ObjectNode matched,ObjectNode conflicts,String hash){}
    public record EnrichmentStatus(UUID productId,IdentityOutcome identityOutcome,int newlyAttachedBarcodeFacts,String status){}
}
