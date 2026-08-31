package dev.agenticcommerce.gateway.catalogue;

import static dev.agenticcommerce.gateway.catalogue.CatalogueModels.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class CatalogueRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    public CatalogueRepository(JdbcClient jdbc, ObjectMapper mapper) { this.jdbc=jdbc; this.mapper=mapper; }

    public CatalogueVersion createVersion(UUID merchantId, UUID actorId, String format, String sourceHash) {
        return jdbc.sql("""
                INSERT INTO catalogue_version(merchant_id,version_number,source_format,source_hash,uploaded_by_actor_id)
                VALUES(:m,(SELECT coalesce(max(version_number),0)+1 FROM catalogue_version WHERE merchant_id=:m),:f,:h,:a)
                RETURNING *
                """).param("m",merchantId).param("f",format).param("h",sourceHash).param("a",actorId)
                .query(this::version).single();
    }

    public Product insertProduct(UUID merchantId, UUID versionId, ProductInput p, String normalized) {
        UUID id=jdbc.sql("""
                INSERT INTO merchant_product(merchant_id,catalogue_version_id,merchant_sku,gtin,brand,canonical_name,
                  normalized_name,variant,size_storage,colour,category,description,active,source_record_id)
                VALUES(:m,:v,:sku,:gtin,:brand,:name,:normalized,:variant,:size,:colour,:category,:description,:active,:source)
                RETURNING product_id
                """).param("m",merchantId).param("v",versionId).param("sku",p.merchantSku()).param("gtin",p.gtin())
                .param("brand",p.brand()).param("name",p.canonicalName()).param("normalized",normalized)
                .param("variant",p.variant()).param("size",p.sizeStorage()).param("colour",p.colour())
                .param("category",p.category()).param("description",p.description()).param("active",p.active())
                .param("source",p.sourceRecordId()).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO merchant_product_commerce_state(merchant_id,catalogue_version_id,product_id,price_minor,
                  currency,stock_quantity,availability,observation_source,source_version,observed_at)
                VALUES(:m,:v,:p,:price,:currency,:stock,:availability,:source,:sourceVersion,:observed)
                """).param("m",merchantId).param("v",versionId).param("p",id).param("price",p.priceMinor())
                .param("currency",p.currency()).param("stock",p.stockQuantity()).param("availability",p.availability().name())
                .param("source",p.observationSource()).param("sourceVersion",p.sourceVersion())
                .param("observed",utc(p.observedAt()),Types.TIMESTAMP_WITH_TIMEZONE).update();
        return findProduct(merchantId,versionId,id).orElseThrow();
    }

    public IdentityResolution insertResolution(UUID merchantId, UUID versionId, UUID productId,
            String source, String externalId, IdentityOutcome outcome, JsonNode matched, JsonNode conflicts, String hash) {
        return jdbc.sql("""
                INSERT INTO product_identity_resolution(merchant_id,catalogue_version_id,product_id,external_source,
                  external_record_id,outcome,matched_fields,conflicting_fields,evidence_hash)
                VALUES(:m,:v,:p,:s,:external,:outcome,CAST(:matched AS jsonb),CAST(:conflicts AS jsonb),:hash)
                RETURNING identity_resolution_id,outcome,matched_fields::text,conflicting_fields::text,evidence_hash
                """).param("m",merchantId).param("v",versionId).param("p",productId).param("s",source)
                .param("external",externalId).param("outcome",outcome.name()).param("matched",matched.toString())
                .param("conflicts",conflicts.toString()).param("hash",hash).query((rs,n)->new IdentityResolution(
                        rs.getObject(1,UUID.class),IdentityOutcome.valueOf(rs.getString(2)),mapper.readTree(rs.getString(3)),
                        mapper.readTree(rs.getString(4)),rs.getString(5).strip())).single();
    }

    public void insertFact(UUID merchantId, UUID versionId, UUID productId, UUID resolutionId,
            String type, JsonNode value, String source, String record, String sourceVersion,
            String authority, String state, Instant observed, Instant expires, String hash) {
        jdbc.sql("""
                INSERT INTO product_external_fact(merchant_id,catalogue_version_id,product_id,identity_resolution_id,
                  fact_type,normalized_value,source,source_record_id,source_version,authority_tier,resolution_state,
                  observed_at,expires_at,fact_hash)
                VALUES(:m,:v,:p,:r,:type,CAST(:value AS jsonb),:source,:record,:sourceVersion,:authority,:state,:observed,:expires,:hash)
                ON CONFLICT DO NOTHING
                """).param("m",merchantId).param("v",versionId).param("p",productId).param("r",resolutionId)
                .param("type",type).param("value",value.toString()).param("source",source).param("record",record)
                .param("sourceVersion",sourceVersion).param("authority",authority).param("state",state)
                .param("observed",utc(observed),Types.TIMESTAMP_WITH_TIMEZONE)
                .param("expires",utc(expires),Types.TIMESTAMP_WITH_TIMEZONE).param("hash",hash).update();
    }

    public void insertEmbedding(UUID merchantId,UUID versionId,UUID productId,String inputHash,List<Float> values,String failure) {
        String vector=values==null?null:vector(values);
        jdbc.sql("""
                INSERT INTO product_embedding(merchant_id,catalogue_version_id,product_id,model_name,output_dimensions,
                  input_hash,embedding,indexing_state,failure_code)
                VALUES(:m,:v,:p,:model,768,CAST(:hash AS char(64)),CAST(:embedding AS vector),:state,:failure)
                """).param("m",merchantId).param("v",versionId).param("p",productId).param("model",EmbeddingProvider.MODEL)
                .param("hash",inputHash).param("embedding",vector).param("state",values==null?"FAILED":"READY")
                .param("failure",failure).update();
    }

    public CatalogueVersion publish(UUID merchantId,UUID versionId,String contentHash,int accepted,int rejected,int enriched,int unresolved,JsonNode evidence) {
        return jdbc.sql("""
                UPDATE catalogue_version SET status='PUBLISHED',content_hash=:hash,accepted_count=:accepted,
                  rejected_count=:rejected,enriched_count=:enriched,unresolved_count=:unresolved,
                  evidence=CAST(:evidence AS jsonb),published_at=CURRENT_TIMESTAMP
                WHERE merchant_id=:m AND catalogue_version_id=:v AND status='DRAFT'
                RETURNING *
                """).param("hash",contentHash).param("accepted",accepted).param("rejected",rejected)
                .param("enriched",enriched).param("unresolved",unresolved).param("evidence",evidence.toString())
                .param("m",merchantId).param("v",versionId).query(this::version).single();
    }

    public Optional<CatalogueVersion> latestPublished(UUID merchantId) {
        return jdbc.sql("SELECT * FROM catalogue_version WHERE merchant_id=:m AND status='PUBLISHED' ORDER BY version_number DESC LIMIT 1")
                .param("m",merchantId).query(this::version).optional();
    }
    public List<CatalogueVersion> versions(UUID merchantId) {
        return jdbc.sql("SELECT * FROM catalogue_version WHERE merchant_id=:m ORDER BY version_number DESC")
                .param("m",merchantId).query(this::version).list();
    }
    public List<Product> products(UUID merchantId,UUID versionId,int limit) {
        return jdbc.sql(productSelect()+" WHERE p.merchant_id=:m AND p.catalogue_version_id=:v ORDER BY p.merchant_sku LIMIT :limit")
                .param("m",merchantId).param("v",versionId).param("limit",Math.min(Math.max(limit,1),100)).query(this::product).list();
    }
    public Optional<Product> findProduct(UUID merchantId,UUID versionId,UUID productId) {
        return jdbc.sql(productSelect()+" WHERE p.merchant_id=:m AND p.catalogue_version_id=:v AND p.product_id=:p")
                .param("m",merchantId).param("v",versionId).param("p",productId).query(this::product).optional();
    }

    public List<ScoredProduct> lexicalCandidates(UUID merchantId,UUID versionId,String query,String sku,String gtin,
            String category,Long minPrice,Long maxPrice,int limit) {
        return jdbc.sql("SELECT "+productColumns()+"""
                , CASE WHEN (:sku IS NOT NULL AND lower(p.merchant_sku)=lower(:sku)) OR (:gtin IS NOT NULL AND p.gtin=:gtin) THEN 1.0 ELSE 0.0 END exact_score,
                  LEAST(1.0,ts_rank_cd(p.search_document,websearch_to_tsquery('simple',:query))*4.0) fts_score,
                  LEAST(1.0,similarity(p.normalized_name,lower(:query))*2.0) trigram_score
                """+productFrom()+"""
                WHERE p.merchant_id=:m AND p.catalogue_version_id=:v AND p.active
                  AND (:category IS NULL OR lower(p.category)=lower(:category))
                  AND (:minPrice IS NULL OR c.price_minor>=:minPrice) AND (:maxPrice IS NULL OR c.price_minor<=:maxPrice)
                  AND ((:sku IS NOT NULL AND lower(p.merchant_sku)=lower(:sku)) OR (:gtin IS NOT NULL AND p.gtin=:gtin)
                    OR p.search_document @@ websearch_to_tsquery('simple',:query) OR similarity(p.normalized_name,lower(:query))>=0.18)
                ORDER BY exact_score DESC,fts_score DESC,trigram_score DESC,p.merchant_sku LIMIT :limit
                """).param("sku",sku,Types.VARCHAR).param("gtin",gtin,Types.VARCHAR)
                .param("query",query).param("m",merchantId).param("v",versionId)
                .param("category",category,Types.VARCHAR).param("minPrice",minPrice,Types.BIGINT)
                .param("maxPrice",maxPrice,Types.BIGINT).param("limit",limit)
                .query((rs,n)->new ScoredProduct(product(rs,n),rs.getDouble("exact_score"),rs.getDouble("fts_score"),rs.getDouble("trigram_score"),0)).list();
    }

    public List<VectorScore> vectorCandidates(UUID merchantId,UUID versionId,List<Float> embedding,int limit) {
        if(embedding==null)return List.of();
        return jdbc.sql("""
                SELECT product_id,1-(embedding <=> CAST(:embedding AS vector)) score
                FROM product_embedding WHERE merchant_id=:m AND catalogue_version_id=:v AND indexing_state='READY'
                ORDER BY embedding <=> CAST(:embedding AS vector),product_id LIMIT :limit
                """).param("embedding",vector(embedding)).param("m",merchantId).param("v",versionId).param("limit",limit)
                .query((rs,n)->new VectorScore(rs.getObject(1,UUID.class),rs.getDouble(2))).list();
    }

    public List<FactValue> facts(UUID merchantId,UUID versionId,UUID productId,String type) {
        return jdbc.sql("""
                SELECT normalized_value::text,authority_tier,resolution_state,observed_at,expires_at,source
                FROM product_external_fact WHERE merchant_id=:m AND catalogue_version_id=:v AND product_id=:p AND fact_type=:type
                ORDER BY CASE authority_tier WHEN 'PRIMARY' THEN 0 ELSE 1 END,observed_at DESC
                """).param("m",merchantId).param("v",versionId).param("p",productId).param("type",type)
                .query((rs,n)->new FactValue(mapper.readTree(rs.getString(1)),rs.getString(2),rs.getString(3),
                        rs.getObject(4,OffsetDateTime.class).toInstant(),rs.getObject(5,OffsetDateTime.class)==null?null:rs.getObject(5,OffsetDateTime.class).toInstant(),rs.getString(6))).list();
    }

    public IdentityOutcome latestIdentity(UUID merchantId,UUID versionId,UUID productId) {
        return jdbc.sql("SELECT outcome FROM product_identity_resolution WHERE merchant_id=:m AND catalogue_version_id=:v AND product_id=:p ORDER BY resolved_at DESC LIMIT 1")
                .param("m",merchantId).param("v",versionId).param("p",productId).query(String.class).optional()
                .map(IdentityOutcome::valueOf).orElse(IdentityOutcome.UNRESOLVED);
    }

    public CatalogueHealth health(UUID merchantId,UUID versionId,int version) {
        return jdbc.sql("""
                SELECT count(DISTINCT p.product_id) products,count(DISTINCT p.product_id) FILTER(WHERE p.active) active,
                  count(DISTINCT r.product_id) FILTER(WHERE r.outcome='EXACT') exact,
                  count(DISTINCT r.product_id) FILTER(WHERE r.outcome IN('UNRESOLVED','CONFLICT')) unresolved,
                  count(DISTINCT f.product_id) enriched,
                  count(DISTINCT e.product_id) FILTER(WHERE e.indexing_state='READY') ready_embeddings,
                  count(DISTINCT e.product_id) FILTER(WHERE e.indexing_state='FAILED') failed_embeddings,
                  count(DISTINCT f.external_fact_id) FILTER(WHERE f.resolution_state='STALE' OR f.expires_at<=CURRENT_TIMESTAMP) stale,
                  count(DISTINCT f.external_fact_id) FILTER(WHERE f.resolution_state='CONFLICT') conflicts
                FROM merchant_product p
                LEFT JOIN product_identity_resolution r ON r.merchant_id=p.merchant_id AND r.catalogue_version_id=p.catalogue_version_id AND r.product_id=p.product_id
                LEFT JOIN product_external_fact f ON f.merchant_id=p.merchant_id AND f.catalogue_version_id=p.catalogue_version_id AND f.product_id=p.product_id
                LEFT JOIN product_embedding e ON e.merchant_id=p.merchant_id AND e.catalogue_version_id=p.catalogue_version_id AND e.product_id=p.product_id
                WHERE p.merchant_id=:m AND p.catalogue_version_id=:v
                """).param("m",merchantId).param("v",versionId).query((rs,n)->new CatalogueHealth(merchantId,versionId,version,
                        rs.getInt("products"),rs.getInt("active"),rs.getInt("exact"),rs.getInt("unresolved"),
                        rs.getInt("enriched"),rs.getInt("ready_embeddings"),rs.getInt("failed_embeddings"),
                        rs.getInt("stale"),rs.getInt("conflicts"))).single();
    }

    public void insertEvidence(UUID merchantId,UUID versionId,String type,String outcome,JsonNode query,JsonNode refs,String hash){
        jdbc.sql("""
                INSERT INTO catalogue_retrieval_evidence(merchant_id,catalogue_version_id,evidence_type,outcome,query_evidence,result_references,evidence_hash)
                VALUES(:m,:v,:type,:outcome,CAST(:query AS jsonb),CAST(:refs AS jsonb),:hash)
                """).param("m",merchantId).param("v",versionId).param("type",type).param("outcome",outcome)
                .param("query",query.toString()).param("refs",refs.toString()).param("hash",hash).update();
    }

    public List<String> passingEvidenceTypes(UUID merchantId,UUID versionId){
        return jdbc.sql("""
                SELECT evidence_type FROM (
                  SELECT DISTINCT ON(evidence_type) evidence_type,outcome FROM catalogue_retrieval_evidence
                  WHERE merchant_id=:m AND catalogue_version_id=:v ORDER BY evidence_type,evaluated_at DESC,retrieval_evidence_id DESC
                ) latest WHERE outcome='PASS'
                """).param("m",merchantId).param("v",versionId).query(String.class).list();
    }

    private String productSelect(){return "SELECT "+productColumns()+productFrom();}
    private String productColumns(){return """
            p.product_id,p.merchant_id,p.catalogue_version_id,p.merchant_sku,p.gtin,p.brand,p.canonical_name,
              p.normalized_name,p.variant,p.size_storage,p.colour,p.category,p.description,p.active,p.source_record_id,
              c.price_minor,c.currency,c.stock_quantity,c.availability,c.observed_at
            """;}
    private String productFrom(){return """
            FROM merchant_product p JOIN merchant_product_commerce_state c ON c.merchant_id=p.merchant_id
              AND c.catalogue_version_id=p.catalogue_version_id AND c.product_id=p.product_id
            """;}
    private Product product(ResultSet rs,int n)throws SQLException{return new Product(rs.getObject("product_id",UUID.class),
            rs.getObject("merchant_id",UUID.class),rs.getObject("catalogue_version_id",UUID.class),rs.getString("merchant_sku"),
            rs.getString("gtin"),rs.getString("brand"),rs.getString("canonical_name"),rs.getString("normalized_name"),
            rs.getString("variant"),rs.getString("size_storage"),rs.getString("colour"),rs.getString("category"),
            rs.getString("description"),rs.getBoolean("active"),rs.getString("source_record_id"),
            (Long)rs.getObject("price_minor"),rs.getString("currency"),(Long)rs.getObject("stock_quantity"),
            Availability.valueOf(rs.getString("availability")),rs.getObject("observed_at",OffsetDateTime.class).toInstant());}
    private CatalogueVersion version(ResultSet rs,int n)throws SQLException{return new CatalogueVersion(rs.getObject("catalogue_version_id",UUID.class),
            rs.getObject("merchant_id",UUID.class),rs.getInt("version_number"),VersionStatus.valueOf(rs.getString("status")),
            rs.getString("source_format"),rs.getString("source_hash").strip(),rs.getString("content_hash")==null?null:rs.getString("content_hash").strip(),
            rs.getInt("accepted_count"),rs.getInt("rejected_count"),rs.getInt("enriched_count"),rs.getInt("unresolved_count"),
            mapper.readTree(rs.getString("evidence")),rs.getObject("created_at",OffsetDateTime.class).toInstant(),
            rs.getObject("published_at",OffsetDateTime.class)==null?null:rs.getObject("published_at",OffsetDateTime.class).toInstant());}
    private static String vector(List<Float> values){if(values.size()!=768)throw new IllegalArgumentException("Vector must have 768 dimensions");return values.toString();}
    private static OffsetDateTime utc(Instant value){return value==null?null:value.atOffset(ZoneOffset.UTC);}

    public record ScoredProduct(Product product,double exact,double fts,double trigram,double vector){}
    public record VectorScore(UUID productId,double score){}
    public record FactValue(JsonNode value,String authority,String state,Instant observedAt,Instant expiresAt,String source){}
}
