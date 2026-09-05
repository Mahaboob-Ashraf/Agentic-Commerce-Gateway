package dev.agenticcommerce.gateway.intent;

import static dev.agenticcommerce.gateway.intent.BuyerModels.*;

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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class BuyerRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public BuyerRepository(JdbcClient jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}

    public CommerceThread createThread(UUID buyerId,String title,Instant deadline){
        return jdbc.sql("""
                INSERT INTO commerce_thread(buyer_actor_id,title,wall_clock_deadline)
                VALUES(:buyer,:title,:deadline) RETURNING *
                """).param("buyer",buyerId).param("title",title)
                .param("deadline",utc(deadline),Types.TIMESTAMP_WITH_TIMEZONE).query(this::thread).single();
    }
    public Optional<CommerceThread> findThread(UUID buyerId,UUID threadId){return jdbc.sql("SELECT * FROM commerce_thread WHERE thread_id=:id AND buyer_actor_id=:buyer")
            .param("id",threadId).param("buyer",buyerId).query(this::thread).optional();}
    public Optional<CommerceThread> findThreadForUpdate(UUID buyerId,UUID threadId){return jdbc.sql("SELECT * FROM commerce_thread WHERE thread_id=:id AND buyer_actor_id=:buyer FOR UPDATE")
            .param("id",threadId).param("buyer",buyerId).query(this::thread).optional();}
    public List<CommerceThread> listThreads(UUID buyerId){return jdbc.sql("SELECT * FROM commerce_thread WHERE buyer_actor_id=:buyer ORDER BY updated_at DESC,thread_id")
            .param("buyer",buyerId).query(this::thread).list();}

    public ThreadMessage appendMessage(CommerceThread thread,String source,String text,String hash){
        return jdbc.sql("""
                INSERT INTO commerce_thread_message(thread_id,buyer_actor_id,message_number,input_source,normalized_text,content_hash)
                VALUES(:thread,:buyer,(SELECT COALESCE(MAX(message_number),0)+1 FROM commerce_thread_message WHERE thread_id=:thread),:source,:text,:hash)
                RETURNING *
                """).param("thread",thread.threadId()).param("buyer",thread.buyerActorId()).param("source",source)
                .param("text",text).param("hash",hash).query(this::message).single();
    }
    public Optional<ThreadMessage> latestMessage(UUID buyerId,UUID threadId){return jdbc.sql("SELECT * FROM commerce_thread_message WHERE thread_id=:thread AND buyer_actor_id=:buyer ORDER BY message_number DESC LIMIT 1")
            .param("thread",threadId).param("buyer",buyerId).query(this::message).optional();}
    public List<ThreadMessage> messages(UUID buyerId,UUID threadId){return jdbc.sql("SELECT * FROM commerce_thread_message WHERE thread_id=:thread AND buyer_actor_id=:buyer ORDER BY message_number")
            .param("thread",threadId).param("buyer",buyerId).query(this::message).list();}

    public BuyerIntent createIntent(CommerceThread thread,ThreadMessage source,CompiledIntent value,String outputHash,String intentHash){
        return jdbc.sql("""
                INSERT INTO buyer_intent(thread_id,buyer_actor_id,intent_version,source_message_id,goal,
                  category_request,budget_amount_minor,currency,exact_merchant_sku,exact_gtin,exact_brand,exact_variant,exact_size_storage,exact_colour,
                  vegetarian,prohibited_allergen,quantity,people,substitution_policy,delivery_hint,
                  excluded_materials,soft_preferences,material_fields,ambiguity_state,clarification_question,compiler_provider,
                  compiler_model,model_output_hash,intent_hash)
                VALUES(:thread,:buyer,(SELECT COALESCE(MAX(intent_version),0)+1 FROM buyer_intent WHERE thread_id=:thread),:message,:goal,
                  :category,:budget,:currency,:sku,:gtin,:brand,:variant,:size,:colour,:vegetarian,:allergen,:quantity,:people,:substitution,:delivery,
                  CAST(:excludedMaterials AS jsonb),CAST(:preferences AS jsonb),CAST(:fields AS jsonb),:ambiguity,:question,:provider,:model,:outputHash,:intentHash)
                RETURNING *
                """).param("thread",thread.threadId()).param("buyer",thread.buyerActorId()).param("message",source.messageId())
                .param("goal",value.goal().name()).param("category",value.categoryRequest()).param("budget",value.budgetAmountMinor())
                .param("currency",value.currency()).param("sku",value.exactMerchantSku()).param("gtin",value.exactGtin())
                .param("brand",value.exactBrand()).param("variant",value.exactVariant()).param("size",value.exactSizeStorage()).param("colour",value.exactColour())
                .param("vegetarian",value.vegetarian()).param("allergen",value.prohibitedAllergen())
                .param("quantity",value.quantity()).param("people",value.people()).param("substitution",value.substitutionPolicy().name())
                .param("delivery",value.deliveryHint()).param("excludedMaterials",mapper.writeValueAsString(value.excludedMaterials())).param("preferences",mapper.writeValueAsString(value.softPreferences()))
                .param("fields",mapper.writeValueAsString(value.materialFields())).param("ambiguity",value.ambiguityState().name())
                .param("question",value.clarificationQuestion()).param("provider",value.provider()).param("model",value.model())
                .param("outputHash",outputHash).param("intentHash",intentHash).query(this::intent).single();
    }
    public Optional<BuyerIntent> latestIntent(UUID buyerId,UUID threadId){return jdbc.sql("SELECT * FROM buyer_intent WHERE thread_id=:thread AND buyer_actor_id=:buyer ORDER BY intent_version DESC LIMIT 1")
            .param("thread",threadId).param("buyer",buyerId).query(this::intent).optional();}

    public List<MerchantCandidate> eligibleMerchants(){return jdbc.sql("""
            WITH latest_manifest AS (
              SELECT DISTINCT ON (merchant_id) manifest_id,merchant_id,manifest_version,catalogue_version
              FROM agent_commerce_manifest ORDER BY merchant_id,manifest_version DESC
            ), latest_catalogue AS (
              SELECT DISTINCT ON (merchant_id) catalogue_version_id,merchant_id,version_number,content_hash
              FROM catalogue_version WHERE status='PUBLISHED' ORDER BY merchant_id,version_number DESC
            )
            SELECT m.merchant_id,m.display_name,lm.manifest_id,lm.manifest_version,
              lc.catalogue_version_id,'v'||lc.version_number||':'||lc.content_hash catalogue_version,
              quote.executable_mapping_proposal_id quote_mapping_id
            FROM latest_manifest lm JOIN merchant m ON m.merchant_id=lm.merchant_id
            JOIN latest_catalogue lc ON lc.merchant_id=lm.merchant_id
            JOIN agent_commerce_manifest_capability search ON search.manifest_id=lm.manifest_id
              AND search.merchant_id=lm.merchant_id AND search.capability='SEARCH_PRODUCTS'
              AND search.advertised AND search.readiness='READY'
            LEFT JOIN agent_commerce_manifest_capability quote ON quote.manifest_id=lm.manifest_id
              AND quote.merchant_id=lm.merchant_id AND quote.capability='GET_QUOTE'
              AND quote.advertised AND quote.readiness='READY' AND quote.executable_mapping_proposal_id IS NOT NULL
            ORDER BY m.merchant_id
            """).query((rs,n)->new MerchantCandidate(rs.getObject("merchant_id",UUID.class),rs.getString("display_name"),
            rs.getObject("manifest_id",UUID.class),rs.getInt("manifest_version"),rs.getObject("catalogue_version_id",UUID.class),
            rs.getString("catalogue_version").strip(),rs.getObject("quote_mapping_id",UUID.class),false)).list();}

    public MerchantDiscovery createDiscovery(CommerceThread thread,BuyerIntent intent,DiscoveryOutcome outcome,
            List<String> required,List<MerchantCandidate> candidates,List<String> refs,String hash){
        return jdbc.sql("""
                INSERT INTO merchant_discovery_evidence(thread_id,buyer_actor_id,intent_id,intent_version,outcome,
                  required_capabilities,eligible_merchants,evidence_references,discovery_hash)
                VALUES(:thread,:buyer,:intent,:version,:outcome,CAST(:required AS jsonb),CAST(:eligible AS jsonb),CAST(:refs AS jsonb),:hash)
                RETURNING *
                """).param("thread",thread.threadId()).param("buyer",thread.buyerActorId()).param("intent",intent.intentId())
                .param("version",intent.intentVersion()).param("outcome",outcome.name()).param("required",mapper.writeValueAsString(required))
                .param("eligible",mapper.writeValueAsString(candidates)).param("refs",mapper.writeValueAsString(refs)).param("hash",hash)
                .query(this::discovery).single();
    }
    public Optional<MerchantDiscovery> latestDiscovery(UUID buyerId,UUID threadId,UUID intentId){return jdbc.sql("SELECT * FROM merchant_discovery_evidence WHERE thread_id=:thread AND buyer_actor_id=:buyer AND intent_id=:intent ORDER BY created_at DESC LIMIT 1")
            .param("thread",threadId).param("buyer",buyerId).param("intent",intentId).query(this::discovery).optional();}

    public CandidateCart createCart(CommerceThread thread,BuyerIntent intent,MerchantCandidate merchant,
            List<CandidateCartItem> items,List<String> refs,JsonNode alternatives,String hash){
        CandidateCart base=jdbc.sql("""
                INSERT INTO candidate_cart(thread_id,buyer_actor_id,intent_id,intent_version,merchant_id,cart_version,
                  catalogue_version_id,selection_evidence_references,alternatives,cart_hash)
                VALUES(:thread,:buyer,:intent,:intentVersion,:merchant,(SELECT COALESCE(MAX(cart_version),0)+1 FROM candidate_cart WHERE thread_id=:thread),
                  :catalogue,CAST(:refs AS jsonb),CAST(:alternatives AS jsonb),:hash) RETURNING *
                """).param("thread",thread.threadId()).param("buyer",thread.buyerActorId()).param("intent",intent.intentId())
                .param("intentVersion",intent.intentVersion()).param("merchant",merchant.merchantId()).param("catalogue",merchant.catalogueVersionId())
                .param("refs",mapper.writeValueAsString(refs)).param("alternatives",mapper.writeValueAsString(alternatives)).param("hash",hash)
                .query((rs,n)->cart(rs,List.of())).single();
        for(CandidateCartItem item:items) jdbc.sql("""
                INSERT INTO candidate_cart_item(cart_id,thread_id,buyer_actor_id,merchant_id,catalogue_version_id,
                  product_id,merchant_sku,variant,quantity,selection_rationale,evidence_references)
                VALUES(:cart,:thread,:buyer,:merchant,:catalogue,:product,:sku,:variant,:quantity,:rationale,CAST(:refs AS jsonb))
                """).param("cart",base.cartId()).param("thread",thread.threadId()).param("buyer",thread.buyerActorId())
                .param("merchant",merchant.merchantId()).param("catalogue",merchant.catalogueVersionId()).param("product",item.productId())
                .param("sku",item.merchantSku()).param("variant",item.variant()).param("quantity",item.quantity())
                .param("rationale",item.selectionRationale()).param("refs",mapper.writeValueAsString(item.evidenceReferences())).update();
        return findCart(thread.buyerActorId(),thread.threadId(),base.cartId()).orElseThrow();
    }
    public Optional<CandidateCart> currentCart(UUID buyerId,UUID threadId){return jdbc.sql("SELECT c.* FROM candidate_cart c JOIN commerce_thread t ON t.thread_id=c.thread_id AND t.buyer_actor_id=c.buyer_actor_id WHERE c.thread_id=:thread AND c.buyer_actor_id=:buyer AND c.cart_version=t.current_cart_version")
            .param("thread",threadId).param("buyer",buyerId).query((rs,n)->cart(rs,List.of())).optional().map(c->withItems(c,cartItems(c)));}
    public Optional<CandidateCart> findCart(UUID buyerId,UUID threadId,UUID cartId){return jdbc.sql("SELECT * FROM candidate_cart WHERE cart_id=:cart AND thread_id=:thread AND buyer_actor_id=:buyer")
            .param("cart",cartId).param("thread",threadId).param("buyer",buyerId).query((rs,n)->cart(rs,List.of())).optional().map(c->withItems(c,cartItems(c)));}
    public List<UUID> priorCartProductIds(UUID buyerId,UUID threadId){return jdbc.sql("SELECT DISTINCT i.product_id FROM candidate_cart_item i JOIN candidate_cart c ON c.cart_id=i.cart_id WHERE c.thread_id=:thread AND c.buyer_actor_id=:buyer")
            .param("thread",threadId).param("buyer",buyerId).query(UUID.class).list();}

    public MerchantQuote createQuote(CandidateCart cart,UUID mappingId,String merchantQuoteId,String quoteVersion,
            Long subtotal,Long tax,Long delivery,Long fees,Long total,String currency,Instant expires,
            Boolean stockGuaranteed,Boolean priceGuaranteed,String evidenceHash,Instant observed,List<MerchantQuoteItem> items){
        MerchantQuote base=jdbc.sql("""
                INSERT INTO merchant_quote(merchant_id,thread_id,buyer_actor_id,cart_id,cart_version,cart_hash,
                  merchant_quote_id,merchant_quote_version,subtotal_minor,tax_minor,delivery_minor,fees_minor,
                  final_amount_minor,currency,expires_at,stock_guaranteed,price_guaranteed,
                  executable_mapping_proposal_id,evidence_hash,observed_at)
                VALUES(:merchant,:thread,:buyer,:cart,:cartVersion,:cartHash,:quoteId,:quoteVersion,:subtotal,:tax,:delivery,:fees,
                  :total,:currency,:expires,:stock,:price,:mapping,:hash,:observed) RETURNING *
                """).param("merchant",cart.merchantId()).param("thread",cart.threadId()).param("buyer",cart.buyerActorId())
                .param("cart",cart.cartId()).param("cartVersion",cart.cartVersion()).param("cartHash",cart.cartHash())
                .param("quoteId",merchantQuoteId).param("quoteVersion",quoteVersion).param("subtotal",subtotal).param("tax",tax)
                .param("delivery",delivery).param("fees",fees).param("total",total).param("currency",currency)
                .param("expires",utc(expires),Types.TIMESTAMP_WITH_TIMEZONE).param("stock",stockGuaranteed).param("price",priceGuaranteed)
                .param("mapping",mappingId).param("hash",evidenceHash).param("observed",utc(observed),Types.TIMESTAMP_WITH_TIMEZONE)
                .query((rs,n)->quote(rs,List.of())).single();
        for(MerchantQuoteItem item:items) jdbc.sql("""
                INSERT INTO merchant_quote_item(quote_record_id,thread_id,buyer_actor_id,product_id,merchant_sku,
                  quantity,unit_amount_minor,line_amount_minor)
                VALUES(:quote,:thread,:buyer,:product,:sku,:quantity,:unit,:line)
                """).param("quote",base.quoteRecordId()).param("thread",cart.threadId()).param("buyer",cart.buyerActorId())
                .param("product",item.productId()).param("sku",item.merchantSku()).param("quantity",item.quantity())
                .param("unit",item.unitAmountMinor()).param("line",item.lineAmountMinor()).update();
        return findQuote(cart.buyerActorId(),cart.threadId(),base.quoteRecordId()).orElseThrow();
    }
    public Optional<MerchantQuote> currentQuote(UUID buyerId,UUID threadId){return jdbc.sql("SELECT q.* FROM merchant_quote q JOIN commerce_thread t ON t.current_quote_id=q.quote_record_id AND t.thread_id=q.thread_id AND t.buyer_actor_id=q.buyer_actor_id WHERE t.thread_id=:thread AND t.buyer_actor_id=:buyer")
            .param("thread",threadId).param("buyer",buyerId).query((rs,n)->quote(rs,List.of())).optional().map(q->withItems(q,quoteItems(q)));}
    public Optional<MerchantQuote> findQuote(UUID buyerId,UUID threadId,UUID quoteId){return jdbc.sql("SELECT * FROM merchant_quote WHERE quote_record_id=:quote AND thread_id=:thread AND buyer_actor_id=:buyer")
            .param("quote",quoteId).param("thread",threadId).param("buyer",buyerId).query((rs,n)->quote(rs,List.of())).optional().map(q->withItems(q,quoteItems(q)));}

    public ConstraintCertificate createCertificate(CommerceThread thread,BuyerIntent intent,CandidateCart cart,
            MerchantQuote quote,UUID snapshotId,UUID availabilityRefreshId,String availabilityHash,
            UUID serviceabilityEvidenceId,String serviceabilityHash,boolean executable,
            JsonNode freshness,List<String> refs,ConstraintOutcome overall,
            String certificateHash,Instant evaluated,List<ConstraintResult> results){
        ConstraintCertificate base=jdbc.sql("""
                INSERT INTO constraint_certificate(thread_id,buyer_actor_id,certificate_version,intent_id,intent_version,
                  intent_hash,cart_id,cart_version,cart_hash,quote_record_id,quote_hash,catalogue_version_id,
                  merchant_id,policy_snapshot_id,availability_refresh_id,availability_evidence_hash,
                  serviceability_evidence_id,serviceability_evidence_hash,executable,
                  source_freshness,evidence_references,overall_result,certificate_hash,evaluated_at)
                VALUES(:thread,:buyer,(SELECT COALESCE(MAX(certificate_version),0)+1 FROM constraint_certificate WHERE thread_id=:thread),
                  :intent,:intentVersion,:intentHash,:cart,:cartVersion,:cartHash,:quote,:quoteHash,:catalogue,
                  :merchant,:snapshot,:availability,:availabilityHash,:serviceability,:serviceabilityHash,:executable,
                  CAST(:freshness AS jsonb),CAST(:refs AS jsonb),:overall,:hash,:evaluated) RETURNING *
                """).param("thread",thread.threadId()).param("buyer",thread.buyerActorId()).param("intent",intent.intentId())
                .param("intentVersion",intent.intentVersion()).param("intentHash",intent.intentHash()).param("cart",cart.cartId())
                .param("cartVersion",cart.cartVersion()).param("cartHash",cart.cartHash()).param("quote",quote.quoteRecordId())
                .param("quoteHash",quote.evidenceHash()).param("catalogue",cart.catalogueVersionId())
                .param("merchant",cart.merchantId()).param("snapshot",snapshotId)
                .param("availability",availabilityRefreshId).param("availabilityHash",availabilityHash)
                .param("serviceability",serviceabilityEvidenceId).param("serviceabilityHash",serviceabilityHash)
                .param("executable",executable)
                .param("freshness",mapper.writeValueAsString(freshness)).param("refs",mapper.writeValueAsString(refs))
                .param("overall",overall.name()).param("hash",certificateHash)
                .param("evaluated",utc(evaluated),Types.TIMESTAMP_WITH_TIMEZONE).query((rs,n)->certificate(rs,List.of())).single();
        for(ConstraintResult result:results) jdbc.sql("""
                INSERT INTO constraint_result(certificate_id,thread_id,buyer_actor_id,constraint_key,constraint_type,
                  normalized_requirement,result,safety_critical,evidence_references,evaluated_at)
                VALUES(:certificate,:thread,:buyer,:key,:type,CAST(:requirement AS jsonb),:result,:safety,CAST(:refs AS jsonb),:evaluated)
                """).param("certificate",base.certificateId()).param("thread",thread.threadId()).param("buyer",thread.buyerActorId())
                .param("key",result.constraintKey()).param("type",result.constraintType().name())
                .param("requirement",mapper.writeValueAsString(result.normalizedRequirement())).param("result",result.result().name())
                .param("safety",result.safetyCritical()).param("refs",mapper.writeValueAsString(result.evidenceReferences()))
                .param("evaluated",utc(evaluated),Types.TIMESTAMP_WITH_TIMEZONE).update();
        return findCertificate(thread.buyerActorId(),thread.threadId(),base.certificateId()).orElseThrow();
    }
    public Optional<ConstraintCertificate> currentCertificate(UUID buyerId,UUID threadId){return jdbc.sql("SELECT c.* FROM constraint_certificate c JOIN commerce_thread t ON t.current_certificate_id=c.certificate_id AND t.thread_id=c.thread_id AND t.buyer_actor_id=c.buyer_actor_id WHERE t.thread_id=:thread AND t.buyer_actor_id=:buyer")
            .param("thread",threadId).param("buyer",buyerId).query((rs,n)->certificate(rs,List.of())).optional().map(c->withResults(c,results(c)));}
    public Optional<ConstraintCertificate> findCertificate(UUID buyerId,UUID threadId,UUID certificateId){return jdbc.sql("SELECT * FROM constraint_certificate WHERE certificate_id=:certificate AND thread_id=:thread AND buyer_actor_id=:buyer")
            .param("certificate",certificateId).param("thread",threadId).param("buyer",buyerId).query((rs,n)->certificate(rs,List.of())).optional().map(c->withResults(c,results(c)));}

    public CommerceThread advance(CommerceThread thread,BuyerState state,Integer intentVersion,Integer cartVersion,
            UUID quoteId,UUID certificateId,int repeatedFailures){
        return jdbc.sql("""
                UPDATE commerce_thread SET current_state=:state,current_intent_version=:intentVersion,
                  current_cart_version=:cartVersion,current_quote_id=:quote,current_certificate_id=:certificate,
                  step_count=step_count+1,repeated_failure_count=:repeated,lock_version=lock_version+1,updated_at=CURRENT_TIMESTAMP
                WHERE thread_id=:thread AND buyer_actor_id=:buyer AND lock_version=:lock RETURNING *
                """).param("state",state.name()).param("intentVersion",intentVersion).param("cartVersion",cartVersion)
                .param("quote",quoteId).param("certificate",certificateId).param("repeated",repeatedFailures)
                .param("thread",thread.threadId()).param("buyer",thread.buyerActorId()).param("lock",thread.lockVersion())
                .query(this::thread).optional().orElseThrow(()->new IllegalStateException("Buyer thread changed concurrently"));
    }
    public CommerceThread resumeForMessage(CommerceThread thread){return jdbc.sql("""
                UPDATE commerce_thread SET current_state='UNDERSTANDING',current_cart_version=NULL,current_quote_id=NULL,current_certificate_id=NULL,
                  current_authority_refresh_id=NULL,current_proposal_id=NULL,current_reversibility_evaluation_id=NULL,
                  current_authorization_id=NULL,current_execution_id=NULL,
                  lock_version=lock_version+1,updated_at=CURRENT_TIMESTAMP
                WHERE thread_id=:thread AND buyer_actor_id=:buyer AND lock_version=:lock RETURNING *
                """).param("thread",thread.threadId()).param("buyer",thread.buyerActorId()).param("lock",thread.lockVersion())
                .query(this::thread).optional().orElseThrow();}
    public BuyerAgentAction createAction(CommerceThread before,CommerceThread after,BuyerTool tool,String inputHash,
            List<String> refs,String rationale,ActionOutcome outcome,String signature,String provider,String model){
        return jdbc.sql("""
                INSERT INTO buyer_agent_action(thread_id,buyer_actor_id,step_number,state_before,state_after,selected_tool,
                  input_hash,result_evidence_references,concise_rationale,outcome,action_signature,provider_name,provider_model)
                VALUES(:thread,:buyer,:step,:before,:after,:tool,:input,CAST(:refs AS jsonb),:rationale,:outcome,:signature,:provider,:model)
                RETURNING *
                """).param("thread",before.threadId()).param("buyer",before.buyerActorId()).param("step",after.stepCount())
                .param("before",before.state().name()).param("after",after.state().name()).param("tool",tool.name())
                .param("input",inputHash).param("refs",mapper.writeValueAsString(refs)).param("rationale",rationale)
                .param("outcome",outcome.name()).param("signature",signature).param("provider",provider).param("model",model)
                .query(this::action).single();
    }
    public Optional<BuyerAgentAction> latestAction(UUID buyerId,UUID threadId){return jdbc.sql("SELECT * FROM buyer_agent_action WHERE thread_id=:thread AND buyer_actor_id=:buyer ORDER BY step_number DESC LIMIT 1")
            .param("thread",threadId).param("buyer",buyerId).query(this::action).optional();}
    public List<BuyerAgentAction> actions(UUID buyerId,UUID threadId){return jdbc.sql("SELECT * FROM buyer_agent_action WHERE thread_id=:thread AND buyer_actor_id=:buyer ORDER BY step_number")
            .param("thread",threadId).param("buyer",buyerId).query(this::action).list();}

    private List<CandidateCartItem> cartItems(CandidateCart cart){return jdbc.sql("SELECT * FROM candidate_cart_item WHERE cart_id=:cart AND thread_id=:thread AND buyer_actor_id=:buyer ORDER BY merchant_sku")
            .param("cart",cart.cartId()).param("thread",cart.threadId()).param("buyer",cart.buyerActorId()).query((rs,n)->new CandidateCartItem(
                    rs.getObject("cart_item_id",UUID.class),rs.getObject("product_id",UUID.class),rs.getString("merchant_sku"),rs.getString("variant"),
                    rs.getInt("quantity"),rs.getString("selection_rationale"),strings(rs.getString("evidence_references")))).list();}
    private List<MerchantQuoteItem> quoteItems(MerchantQuote quote){return jdbc.sql("SELECT * FROM merchant_quote_item WHERE quote_record_id=:quote AND thread_id=:thread AND buyer_actor_id=:buyer ORDER BY merchant_sku")
            .param("quote",quote.quoteRecordId()).param("thread",quote.threadId()).param("buyer",quote.buyerActorId()).query((rs,n)->new MerchantQuoteItem(
                    rs.getObject("quote_item_id",UUID.class),rs.getObject("product_id",UUID.class),rs.getString("merchant_sku"),rs.getInt("quantity"),
                    (Long)rs.getObject("unit_amount_minor"),(Long)rs.getObject("line_amount_minor"))).list();}
    private List<ConstraintResult> results(ConstraintCertificate certificate){return jdbc.sql("SELECT * FROM constraint_result WHERE certificate_id=:certificate AND thread_id=:thread AND buyer_actor_id=:buyer ORDER BY constraint_key")
            .param("certificate",certificate.certificateId()).param("thread",certificate.threadId()).param("buyer",certificate.buyerActorId()).query((rs,n)->new ConstraintResult(
                    rs.getObject("constraint_result_id",UUID.class),rs.getString("constraint_key"),ConstraintType.valueOf(rs.getString("constraint_type")),
                    mapper.readTree(rs.getString("normalized_requirement")),ConstraintOutcome.valueOf(rs.getString("result")),rs.getBoolean("safety_critical"),
                    strings(rs.getString("evidence_references")),instant(rs,"evaluated_at"))).list();}

    private CommerceThread thread(ResultSet rs,int n)throws SQLException{return new CommerceThread(rs.getObject("thread_id",UUID.class),rs.getObject("buyer_actor_id",UUID.class),rs.getString("title"),
            BuyerState.valueOf(rs.getString("current_state")),(Integer)rs.getObject("current_intent_version"),(Integer)rs.getObject("current_cart_version"),
            rs.getObject("current_quote_id",UUID.class),rs.getObject("current_certificate_id",UUID.class),rs.getObject("current_authority_refresh_id",UUID.class),
            rs.getObject("current_proposal_id",UUID.class),rs.getObject("current_reversibility_evaluation_id",UUID.class),rs.getObject("current_authorization_id",UUID.class),
            rs.getObject("current_execution_id",UUID.class),rs.getInt("step_count"),rs.getInt("maximum_steps"),
            rs.getInt("repeated_failure_count"),instant(rs,"wall_clock_deadline"),rs.getLong("lock_version"),instant(rs,"created_at"),instant(rs,"updated_at"));}
    private ThreadMessage message(ResultSet rs,int n)throws SQLException{return new ThreadMessage(rs.getObject("message_id",UUID.class),rs.getObject("thread_id",UUID.class),rs.getObject("buyer_actor_id",UUID.class),
            rs.getInt("message_number"),rs.getString("input_source"),rs.getString("normalized_text"),rs.getString("content_hash").strip(),instant(rs,"created_at"));}
    private BuyerIntent intent(ResultSet rs,int n)throws SQLException{CompiledIntent compiled=new CompiledIntent(IntentGoal.valueOf(rs.getString("goal")),rs.getString("category_request"),(Long)rs.getObject("budget_amount_minor"),
            rs.getString("currency"),(String)rs.getObject("exact_merchant_sku"),(String)rs.getObject("exact_gtin"),(String)rs.getObject("exact_brand"),(String)rs.getObject("exact_variant"),
            (String)rs.getObject("exact_size_storage"),(String)rs.getObject("exact_colour"),(Boolean)rs.getObject("vegetarian"),
            rs.getString("prohibited_allergen"),(Integer)rs.getObject("quantity"),(Integer)rs.getObject("people"),SubstitutionPolicy.valueOf(rs.getString("substitution_policy")),
            rs.getString("delivery_hint"),strings(rs.getString("excluded_materials")),strings(rs.getString("soft_preferences")),mapper.readValue(rs.getString("material_fields"),new TypeReference<List<MaterialField>>(){}),
            AmbiguityState.valueOf(rs.getString("ambiguity_state")),rs.getString("clarification_question"),rs.getString("compiler_provider"),rs.getString("compiler_model"));
        return new BuyerIntent(rs.getObject("intent_id",UUID.class),rs.getObject("thread_id",UUID.class),rs.getObject("buyer_actor_id",UUID.class),rs.getInt("intent_version"),
                rs.getObject("source_message_id",UUID.class),compiled,rs.getString("model_output_hash").strip(),rs.getString("intent_hash").strip(),instant(rs,"created_at"));}
    private MerchantDiscovery discovery(ResultSet rs,int n)throws SQLException{return new MerchantDiscovery(rs.getObject("discovery_id",UUID.class),rs.getObject("thread_id",UUID.class),rs.getObject("buyer_actor_id",UUID.class),
            rs.getObject("intent_id",UUID.class),rs.getInt("intent_version"),DiscoveryOutcome.valueOf(rs.getString("outcome")),strings(rs.getString("required_capabilities")),
            mapper.readValue(rs.getString("eligible_merchants"),new TypeReference<List<MerchantCandidate>>(){}),strings(rs.getString("evidence_references")),rs.getString("discovery_hash").strip(),instant(rs,"created_at"));}
    private CandidateCart cart(ResultSet rs,List<CandidateCartItem> items)throws SQLException{return new CandidateCart(rs.getObject("cart_id",UUID.class),rs.getObject("thread_id",UUID.class),rs.getObject("buyer_actor_id",UUID.class),
            rs.getObject("intent_id",UUID.class),rs.getInt("intent_version"),rs.getObject("merchant_id",UUID.class),rs.getInt("cart_version"),rs.getObject("catalogue_version_id",UUID.class),
            strings(rs.getString("selection_evidence_references")),mapper.readTree(rs.getString("alternatives")),rs.getString("cart_hash").strip(),instant(rs,"created_at"),items);}
    private MerchantQuote quote(ResultSet rs,List<MerchantQuoteItem> items)throws SQLException{return new MerchantQuote(rs.getObject("quote_record_id",UUID.class),rs.getObject("merchant_id",UUID.class),rs.getObject("thread_id",UUID.class),
            rs.getObject("buyer_actor_id",UUID.class),rs.getObject("cart_id",UUID.class),rs.getInt("cart_version"),rs.getString("cart_hash").strip(),rs.getString("merchant_quote_id"),rs.getString("merchant_quote_version"),
            (Long)rs.getObject("subtotal_minor"),(Long)rs.getObject("tax_minor"),(Long)rs.getObject("delivery_minor"),(Long)rs.getObject("fees_minor"),(Long)rs.getObject("final_amount_minor"),rs.getString("currency"),
            instant(rs,"expires_at"),(Boolean)rs.getObject("stock_guaranteed"),(Boolean)rs.getObject("price_guaranteed"),rs.getObject("executable_mapping_proposal_id",UUID.class),rs.getString("evidence_hash").strip(),
            instant(rs,"observed_at"),instant(rs,"created_at"),items);}
    private ConstraintCertificate certificate(ResultSet rs,List<ConstraintResult> results)throws SQLException{return new ConstraintCertificate(rs.getObject("certificate_id",UUID.class),rs.getObject("thread_id",UUID.class),rs.getObject("buyer_actor_id",UUID.class),
            rs.getInt("certificate_version"),rs.getObject("intent_id",UUID.class),rs.getInt("intent_version"),rs.getString("intent_hash").strip(),rs.getObject("cart_id",UUID.class),rs.getInt("cart_version"),
            rs.getString("cart_hash").strip(),rs.getObject("quote_record_id",UUID.class),rs.getString("quote_hash").strip(),rs.getObject("catalogue_version_id",UUID.class),
            rs.getObject("merchant_id",UUID.class),rs.getObject("policy_snapshot_id",UUID.class),rs.getObject("availability_refresh_id",UUID.class),
            strip(rs.getString("availability_evidence_hash")),rs.getObject("serviceability_evidence_id",UUID.class),strip(rs.getString("serviceability_evidence_hash")),rs.getBoolean("executable"),
            mapper.readTree(rs.getString("source_freshness")),strings(rs.getString("evidence_references")),ConstraintOutcome.valueOf(rs.getString("overall_result")),rs.getString("certificate_hash").strip(),instant(rs,"evaluated_at"),results);}
    private BuyerAgentAction action(ResultSet rs,int n)throws SQLException{return new BuyerAgentAction(rs.getObject("action_id",UUID.class),rs.getObject("thread_id",UUID.class),rs.getObject("buyer_actor_id",UUID.class),rs.getInt("step_number"),
            BuyerState.valueOf(rs.getString("state_before")),BuyerState.valueOf(rs.getString("state_after")),BuyerTool.valueOf(rs.getString("selected_tool")),rs.getString("input_hash").strip(),strings(rs.getString("result_evidence_references")),
            rs.getString("concise_rationale"),ActionOutcome.valueOf(rs.getString("outcome")),rs.getString("action_signature").strip(),rs.getString("provider_name"),rs.getString("provider_model"),instant(rs,"created_at"));}
    private CandidateCart withItems(CandidateCart c,List<CandidateCartItem> items){return new CandidateCart(c.cartId(),c.threadId(),c.buyerActorId(),c.intentId(),c.intentVersion(),c.merchantId(),c.cartVersion(),c.catalogueVersionId(),c.selectionEvidenceReferences(),c.alternatives(),c.cartHash(),c.createdAt(),items);}
    private MerchantQuote withItems(MerchantQuote q,List<MerchantQuoteItem> items){return new MerchantQuote(q.quoteRecordId(),q.merchantId(),q.threadId(),q.buyerActorId(),q.cartId(),q.cartVersion(),q.cartHash(),q.merchantQuoteId(),q.merchantQuoteVersion(),q.subtotalMinor(),q.taxMinor(),q.deliveryMinor(),q.feesMinor(),q.finalAmountMinor(),q.currency(),q.expiresAt(),q.stockGuaranteed(),q.priceGuaranteed(),q.executableMappingProposalId(),q.evidenceHash(),q.observedAt(),q.createdAt(),items);}
    private ConstraintCertificate withResults(ConstraintCertificate c,List<ConstraintResult> results){return new ConstraintCertificate(c.certificateId(),c.threadId(),c.buyerActorId(),c.certificateVersion(),c.intentId(),c.intentVersion(),c.intentHash(),c.cartId(),c.cartVersion(),c.cartHash(),c.quoteRecordId(),c.quoteHash(),c.catalogueVersionId(),c.merchantId(),c.policySnapshotId(),c.availabilityRefreshId(),c.availabilityEvidenceHash(),c.serviceabilityEvidenceId(),c.serviceabilityEvidenceHash(),c.executable(),c.sourceFreshness(),c.evidenceReferences(),c.overallResult(),c.certificateHash(),c.evaluatedAt(),results);}
    private List<String> strings(String json){try{return mapper.readValue(json,new TypeReference<List<String>>(){});}catch(RuntimeException e){throw e;}}
    private static Instant instant(ResultSet rs,String column)throws SQLException{OffsetDateTime value=rs.getObject(column,OffsetDateTime.class);return value==null?null:value.toInstant();}
    private static OffsetDateTime utc(Instant value){return value==null?null:value.atOffset(ZoneOffset.UTC);}
    private static String strip(String value){return value==null?null:value.strip();}
}
