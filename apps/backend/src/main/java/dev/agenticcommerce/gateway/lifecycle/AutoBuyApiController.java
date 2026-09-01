package dev.agenticcommerce.gateway.lifecycle;
import static dev.agenticcommerce.gateway.lifecycle.AutoBuyModels.*;
import dev.agenticcommerce.gateway.authorization.AuthorizationService;import dev.agenticcommerce.gateway.identity.authentication.VerifiedActorPrincipal;
import jakarta.servlet.http.HttpSession;import java.util.*;import org.springframework.security.core.annotation.AuthenticationPrincipal;import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/buyer/autobuy")
public class AutoBuyApiController {
 private final AutoBuyService service;private final AuthorizationService authorization;public AutoBuyApiController(AutoBuyService s,AuthorizationService a){service=s;authorization=a;}
 @PostMapping("/plans") PlanView create(@AuthenticationPrincipal VerifiedActorPrincipal p,@RequestBody PlanInput i){return service.create(p.actorId(),i);}
 @GetMapping("/plans") List<PlanView> list(@AuthenticationPrincipal VerifiedActorPrincipal p){return service.list(p.actorId());}
 @GetMapping("/plans/{id}") PlanView get(@AuthenticationPrincipal VerifiedActorPrincipal p,@PathVariable UUID id){return service.require(p.actorId(),id);}
 @PutMapping("/plans/{id}") PlanView update(@AuthenticationPrincipal VerifiedActorPrincipal p,@PathVariable UUID id,@RequestBody PlanInput i){return service.update(p.actorId(),id,i);}
 @PostMapping("/plans/{id}/pause") PlanView pause(@AuthenticationPrincipal VerifiedActorPrincipal p,@PathVariable UUID id){return service.pause(p.actorId(),id);}
 @PostMapping("/plans/{id}/resume") PlanView resume(@AuthenticationPrincipal VerifiedActorPrincipal p,@PathVariable UUID id){return service.resume(p.actorId(),id);}
 @PostMapping("/plans/{id}/revoke") PlanView revoke(@AuthenticationPrincipal VerifiedActorPrincipal p,@PathVariable UUID id){return service.revoke(p.actorId(),id);}
 @PostMapping("/plans/{id}/evaluations") Evaluation evaluate(@AuthenticationPrincipal VerifiedActorPrincipal p,@PathVariable UUID id,@RequestBody TriggerRequest r,HttpSession session){return service.evaluate(p.actorId(),id,r.triggerId(),authorization.bindSession(session.getId()));}
 @GetMapping("/plans/{id}/evaluations/{triggerId}") Evaluation evaluation(@AuthenticationPrincipal VerifiedActorPrincipal p,@PathVariable UUID id,@PathVariable String triggerId){return service.evaluation(p.actorId(),id,triggerId);}
}
