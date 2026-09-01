package dev.agenticcommerce.gateway.agentization.api;
import dev.agenticcommerce.gateway.agentization.service.AgentizationGoalService;import dev.agenticcommerce.gateway.agentization.service.AgentizationGoalService.*;import dev.agenticcommerce.gateway.identity.authentication.VerifiedActorPrincipal;
import java.util.UUID;import org.springframework.http.HttpStatus;import org.springframework.security.core.annotation.AuthenticationPrincipal;import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/merchants/{merchantId}/agentization/goals")
public class AgentizationGoalApiController {private final AgentizationGoalService service;public AgentizationGoalApiController(AgentizationGoalService s){service=s;}
 @PostMapping @ResponseStatus(HttpStatus.CREATED) GoalView start(@AuthenticationPrincipal VerifiedActorPrincipal p,@PathVariable UUID merchantId,@RequestBody StartGoal r){return service.start(p.actorId(),merchantId,r);}
 @GetMapping("/{id}") GoalView get(@AuthenticationPrincipal VerifiedActorPrincipal p,@PathVariable UUID merchantId,@PathVariable UUID id){return service.require(p.actorId(),merchantId,id);}
 @PostMapping("/{id}/advance") GoalView advance(@AuthenticationPrincipal VerifiedActorPrincipal p,@PathVariable UUID merchantId,@PathVariable UUID id){return service.advance(p.actorId(),merchantId,id);}}
