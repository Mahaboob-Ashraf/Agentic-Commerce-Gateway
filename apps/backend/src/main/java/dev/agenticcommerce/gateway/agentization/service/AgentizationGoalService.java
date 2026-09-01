package dev.agenticcommerce.gateway.agentization.service;
import dev.agenticcommerce.gateway.agentization.model.*;
import java.sql.*;import java.time.*;import java.util.*;
import org.springframework.http.HttpStatus;import org.springframework.jdbc.core.simple.JdbcClient;import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;
@Service
public class AgentizationGoalService {
 public static final String GOAL="AGENTIZE_STORE_FOR_PURCHASE_AND_LIFECYCLE";
 private static final List<CanonicalCapability> TARGETS=List.of(CanonicalCapability.SEARCH_PRODUCTS,CanonicalCapability.GET_AVAILABILITY,
  CanonicalCapability.GET_QUOTE,CanonicalCapability.PLACE_ORDER,CanonicalCapability.GET_ORDER_STATE,CanonicalCapability.CANCEL_ORDER,
  CanonicalCapability.RETURN_ITEM,CanonicalCapability.REFUND);
 private final JdbcClient jdbc;private final MerchantAgentizationAccessService access;private final OpenApiArtifactService artifacts;
 private final AgentizationRunService runs;private final AgentizationOrchestrationService orchestration;
 public AgentizationGoalService(JdbcClient j,MerchantAgentizationAccessService a,OpenApiArtifactService artifacts,AgentizationRunService r,AgentizationOrchestrationService o){jdbc=j;access=a;this.artifacts=artifacts;runs=r;orchestration=o;}
 @Transactional public GoalView start(UUID actor,UUID merchant,StartGoal request){access.requireMerchantAdmin(actor,merchant);if(request==null||request.sourceArtifactId()==null||request.maximumSteps()<TARGETS.size()||request.maximumSteps()>500)throw invalid("AGENTIZATION_GOAL_INVALID");artifacts.requireArtifact(merchant,request.sourceArtifactId());Instant now=Instant.now();
  UUID id=jdbc.sql("""
   INSERT INTO agentization_goal(merchant_id,created_by_actor_id,source_artifact_id,goal_type,status,max_step_budget,created_at,updated_at)
   VALUES(:merchant,:actor,:artifact,:goal,'ACTIVE',:budget,:now,:now) RETURNING agentization_goal_id
   """).param("merchant",merchant).param("actor",actor).param("artifact",request.sourceArtifactId()).param("goal",GOAL).param("budget",request.maximumSteps()).param("now",utc(now),Types.TIMESTAMP_WITH_TIMEZONE).query(UUID.class).single();
  for(var target:TARGETS)jdbc.sql("INSERT INTO agentization_goal_target(agentization_goal_id,capability,status) VALUES(:id,:capability,'PENDING')").param("id",id).param("capability",target.name()).update();return require(actor,merchant,id);}
 public GoalView require(UUID actor,UUID merchant,UUID id){access.requireMerchantAdmin(actor,merchant);Goal goal=jdbc.sql("SELECT * FROM agentization_goal WHERE merchant_id=:merchant AND agentization_goal_id=:id").param("merchant",merchant).param("id",id).query(this::goal).optional().orElseThrow(()->invalid("AGENTIZATION_GOAL_NOT_FOUND"));return new GoalView(goal,targets(id));}
 @Transactional public GoalView advance(UUID actor,UUID merchant,UUID id){GoalView view=require(actor,merchant,id);if(!"ACTIVE".equals(view.goal().status()))return view;refreshReadiness(merchant,id);view=require(actor,merchant,id);
  Target active=view.targets().stream().filter(t->"IN_PROGRESS".equals(t.status())).findFirst().orElse(null);
  if(active!=null){var advanced=orchestration.advance(actor,merchant,active.runId());AgentizationState state=advanced.run().state();String status=state==AgentizationState.COMPLETE?"UNTESTED":state==AgentizationState.BLOCKED||state==AgentizationState.FAILED?"BLOCKED":state==AgentizationState.WAITING_FOR_MERCHANT_APPROVAL||state==AgentizationState.WAITING_FOR_MERCHANT_CLARIFICATION?"WAITING_FOR_MERCHANT":"IN_PROGRESS";updateTarget(id,active.capability(),status,active.runId());if("WAITING_FOR_MERCHANT".equals(status))updateGoal(id,"WAITING_FOR_MERCHANT");return require(actor,merchant,id);}
  if(view.targets().stream().allMatch(t->"READY".equals(t.status()))){updateGoal(id,"COMPLETED");return require(actor,merchant,id);}
  Target next=view.targets().stream().filter(t->Set.of("PENDING","UNTESTED").contains(t.status())).findFirst().orElse(null);if(next==null){updateGoal(id,"BLOCKED");return require(actor,merchant,id);}
  if(view.goal().consumedSteps()>=view.goal().maxSteps()){updateGoal(id,"BUDGET_EXHAUSTED");return require(actor,merchant,id);}
  AgentizationRun run=runs.start(actor,merchant,view.goal().artifactId(),CanonicalCapability.valueOf(next.capability()),Math.min(100,Math.max(10,view.goal().maxSteps()/TARGETS.size())),Instant.now().plus(Duration.ofHours(1)));
  jdbc.sql("UPDATE agentization_goal_target SET status='IN_PROGRESS',agentization_run_id=:run WHERE agentization_goal_id=:id AND capability=:capability").param("run",run.runId()).param("id",id).param("capability",next.capability()).update();jdbc.sql("UPDATE agentization_goal SET consumed_steps=consumed_steps+1,updated_at=CURRENT_TIMESTAMP WHERE agentization_goal_id=:id").param("id",id).update();return require(actor,merchant,id);}
 private void refreshReadiness(UUID merchant,UUID goal){jdbc.sql("""
   UPDATE agentization_goal_target target SET status='READY',readiness_evaluation_id=latest.readiness_evaluation_id
   FROM (SELECT DISTINCT ON (capability) capability,readiness_evaluation_id,readiness FROM capability_readiness_evaluation WHERE merchant_id=:merchant ORDER BY capability,evaluated_at DESC) latest
   WHERE target.agentization_goal_id=:goal AND target.capability=latest.capability AND latest.readiness='READY'
   """).param("merchant",merchant).param("goal",goal).update();}
 private List<Target> targets(UUID id){return jdbc.sql("SELECT * FROM agentization_goal_target WHERE agentization_goal_id=:id ORDER BY capability").param("id",id).query((r,row)->new Target(r.getString("capability"),r.getString("status"),r.getObject("agentization_run_id",UUID.class),r.getObject("readiness_evaluation_id",UUID.class))).list();}
 private void updateTarget(UUID id,String c,String s,UUID run){jdbc.sql("UPDATE agentization_goal_target SET status=:status,agentization_run_id=:run WHERE agentization_goal_id=:id AND capability=:capability").param("status",s).param("run",run).param("id",id).param("capability",c).update();}
 private void updateGoal(UUID id,String s){jdbc.sql("UPDATE agentization_goal SET status=:status,updated_at=CURRENT_TIMESTAMP WHERE agentization_goal_id=:id").param("status",s).param("id",id).update();}
 private Goal goal(ResultSet r,int row)throws SQLException{return new Goal(r.getObject("agentization_goal_id",UUID.class),r.getObject("merchant_id",UUID.class),r.getObject("created_by_actor_id",UUID.class),r.getObject("source_artifact_id",UUID.class),r.getString("goal_type"),r.getString("status"),r.getInt("max_step_budget"),r.getInt("consumed_steps"),instant(r,"created_at"),instant(r,"updated_at"));}
 private static java.time.OffsetDateTime utc(Instant v){return v.atOffset(ZoneOffset.UTC);}private static Instant instant(ResultSet r,String c)throws SQLException{return r.getObject(c,java.time.OffsetDateTime.class).toInstant();}
 private static AgentizationException invalid(String c){return new AgentizationException(c,HttpStatus.BAD_REQUEST,"High-level agentization goal is invalid or unavailable");}
 public record StartGoal(UUID sourceArtifactId,int maximumSteps){}public record Goal(UUID id,UUID merchantId,UUID actorId,UUID artifactId,String type,String status,int maxSteps,int consumedSteps,Instant createdAt,Instant updatedAt){}public record Target(String capability,String status,UUID runId,UUID readinessEvaluationId){}public record GoalView(Goal goal,List<Target> targets){}
}
