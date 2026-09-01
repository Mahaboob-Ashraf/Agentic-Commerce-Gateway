package dev.agenticcommerce.gateway.lifecycle;
import static dev.agenticcommerce.gateway.lifecycle.AutoBuyModels.*;
import java.sql.*;import java.time.*;import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;import org.springframework.stereotype.Repository;import tools.jackson.databind.*;
@Repository
public class AutoBuyRepository {
 private final JdbcClient jdbc;private final ObjectMapper mapper;public AutoBuyRepository(JdbcClient j,ObjectMapper m){jdbc=j;mapper=m;}
 public PlanView create(UUID buyer,PlanInput i,String hash,Instant now){UUID id=jdbc.sql("""
   INSERT INTO autobuy_plan(buyer_actor_id,merchant_id,status,created_at,updated_at) VALUES(:buyer,:merchant,'ACTIVE',:now,:now)
   RETURNING autobuy_plan_id""").param("buyer",buyer).param("merchant",i.merchantId()).param("now",utc(now),Types.TIMESTAMP_WITH_TIMEZONE).query(UUID.class).single();
   insertVersion(id,buyer,1,i,hash,now);return get(buyer,id).orElseThrow();}
 public Optional<PlanView> get(UUID buyer,UUID id){Plan p=jdbc.sql("SELECT * FROM autobuy_plan WHERE buyer_actor_id=:buyer AND autobuy_plan_id=:id")
   .param("buyer",buyer).param("id",id).query(this::plan).optional().orElse(null);if(p==null)return Optional.empty();
   PlanVersion v=jdbc.sql("SELECT * FROM autobuy_plan_version WHERE autobuy_plan_id=:id AND version=:version")
   .param("id",id).param("version",p.currentVersion()).query(this::version).single();return Optional.of(new PlanView(p,v));}
 public List<PlanView> list(UUID buyer){return jdbc.sql("SELECT autobuy_plan_id FROM autobuy_plan WHERE buyer_actor_id=:buyer ORDER BY created_at")
   .param("buyer",buyer).query(UUID.class).list().stream().map(id->get(buyer,id).orElseThrow()).toList();}
 @org.springframework.transaction.annotation.Transactional public PlanView update(UUID buyer,UUID id,PlanInput i,String hash,Instant now){
   Plan current=jdbc.sql("SELECT * FROM autobuy_plan WHERE buyer_actor_id=:buyer AND autobuy_plan_id=:id FOR UPDATE")
    .param("buyer",buyer).param("id",id).query(this::plan).optional().orElseThrow();int next=current.currentVersion()+1;
   insertVersion(id,buyer,next,i,hash,now);jdbc.sql("UPDATE autobuy_plan SET merchant_id=:merchant,current_version=:version,status='ACTIVE',pause_reason=NULL,updated_at=:now WHERE autobuy_plan_id=:id")
    .param("merchant",i.merchantId()).param("version",next).param("now",utc(now),Types.TIMESTAMP_WITH_TIMEZONE).param("id",id).update();return get(buyer,id).orElseThrow();}
 private void insertVersion(UUID id,UUID buyer,int v,PlanInput i,String hash,Instant now){jdbc.sql("""
   INSERT INTO autobuy_plan_version(autobuy_plan_id,version,buyer_actor_id,merchant_id,merchant_account_link_id,address_id,
    product_constraints,maximum_amount_minor,trigger_description,substitution_policy,hard_safety_constraints,plan_hash,created_at)
   VALUES(:id,:version,:buyer,:merchant,:link,:address,CAST(:products AS jsonb),:maximum,:trigger,:substitution,CAST(:safety AS jsonb),:hash,:now)
   """).param("id",id).param("version",v).param("buyer",buyer).param("merchant",i.merchantId()).param("link",i.merchantAccountLinkId())
    .param("address",i.addressId()).param("products",mapper.writeValueAsString(i.productConstraints())).param("maximum",i.maximumAmountMinor())
    .param("trigger",i.triggerDescription()).param("substitution",i.substitutionPolicy()).param("safety",mapper.writeValueAsString(i.hardSafetyConstraints()))
    .param("hash",hash).param("now",utc(now),Types.TIMESTAMP_WITH_TIMEZONE).update();}
 public PlanView status(UUID buyer,UUID id,String status,String reason,Instant now){int n=jdbc.sql("UPDATE autobuy_plan SET status=:status,pause_reason=:reason,updated_at=:now WHERE buyer_actor_id=:buyer AND autobuy_plan_id=:id")
   .param("status",status).param("reason",reason).param("now",utc(now),Types.TIMESTAMP_WITH_TIMEZONE).param("buyer",buyer).param("id",id).update();if(n!=1)throw new NoSuchElementException();return get(buyer,id).orElseThrow();}
 public Optional<Evaluation> evaluation(UUID buyer,UUID plan,String trigger){return jdbc.sql("SELECT * FROM autobuy_evaluation WHERE buyer_actor_id=:buyer AND autobuy_plan_id=:plan AND trigger_id=:trigger")
   .param("buyer",buyer).param("plan",plan).param("trigger",trigger).query(this::evaluation).optional();}
 public Evaluation saveEvaluation(PlanView p,String trigger,Outcome outcome,String reason,JsonNode evidence,UUID proposal,String hash,Instant now){jdbc.sql("""
   INSERT INTO autobuy_evaluation(autobuy_plan_id,plan_version,buyer_actor_id,trigger_id,outcome,reason_code,fresh_evidence,
    proposal_id,checkout_authorization_required,evaluation_hash,evaluated_at)
   VALUES(:plan,:version,:buyer,:trigger,:outcome,:reason,CAST(:evidence AS jsonb),:proposal,TRUE,:hash,:now)
   ON CONFLICT(autobuy_plan_id,trigger_id) DO NOTHING
   """).param("plan",p.plan().id()).param("version",p.version().version()).param("buyer",p.plan().buyerId()).param("trigger",trigger)
   .param("outcome",outcome.name()).param("reason",reason).param("evidence",mapper.writeValueAsString(evidence)).param("proposal",proposal)
   .param("hash",hash).param("now",utc(now),Types.TIMESTAMP_WITH_TIMEZONE).update();return evaluation(p.plan().buyerId(),p.plan().id(),trigger).orElseThrow();}
 private Plan plan(ResultSet r,int row)throws SQLException{return new Plan(r.getObject("autobuy_plan_id",UUID.class),r.getObject("buyer_actor_id",UUID.class),r.getObject("merchant_id",UUID.class),r.getInt("current_version"),PlanStatus.valueOf(r.getString("status")),r.getString("pause_reason"),instant(r,"created_at"),instant(r,"updated_at"));}
 private PlanVersion version(ResultSet r,int row)throws SQLException{return new PlanVersion(r.getObject("autobuy_plan_id",UUID.class),r.getInt("version"),r.getObject("buyer_actor_id",UUID.class),r.getObject("merchant_id",UUID.class),r.getObject("merchant_account_link_id",UUID.class),r.getObject("address_id",UUID.class),mapper.readTree(r.getString("product_constraints")),r.getLong("maximum_amount_minor"),r.getString("trigger_description"),r.getString("substitution_policy"),mapper.readTree(r.getString("hard_safety_constraints")),r.getString("plan_hash").strip(),instant(r,"created_at"));}
 private Evaluation evaluation(ResultSet r,int row)throws SQLException{return new Evaluation(r.getObject("autobuy_evaluation_id",UUID.class),r.getObject("autobuy_plan_id",UUID.class),r.getInt("plan_version"),r.getObject("buyer_actor_id",UUID.class),r.getString("trigger_id"),Outcome.valueOf(r.getString("outcome")),r.getString("reason_code"),mapper.readTree(r.getString("fresh_evidence")),r.getObject("proposal_id",UUID.class),r.getObject("execution_id",UUID.class),r.getString("provider_order_id"),r.getBoolean("checkout_authorization_required"),r.getString("evaluation_hash").strip(),instant(r,"evaluated_at"));}
 private static OffsetDateTime utc(Instant v){return v==null?null:v.atOffset(ZoneOffset.UTC);}private static Instant instant(ResultSet r,String c)throws SQLException{OffsetDateTime v=r.getObject(c,OffsetDateTime.class);return v==null?null:v.toInstant();}
}
