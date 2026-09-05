package dev.agenticcommerce.gateway.intent;

import dev.agenticcommerce.gateway.identity.authentication.VerifiedActorPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/buyer/commerce-requests")
public class CommerceRequestApiController {
    private final CommerceRequestService service;
    public CommerceRequestApiController(CommerceRequestService service){this.service=service;}
    @PostMapping public CommerceRequestModels.CommerceRequestResult create(@AuthenticationPrincipal VerifiedActorPrincipal principal,
            @Valid @RequestBody Request request){return service.execute(principal.actorId(),request.requestId(),request.threadId(),request.text());}
    @PostMapping(path="/visual",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public CommerceRequestModels.CommerceRequestResult createVisual(@AuthenticationPrincipal VerifiedActorPrincipal principal,
            @RequestParam UUID requestId,@RequestParam(required=false) UUID threadId,
            @RequestParam(required=false) @Size(max=4000) String text,@RequestParam("image") MultipartFile image){
        return service.executeVisual(principal.actorId(),requestId,threadId,text,image);}
    @GetMapping("/{requestId}") public CommerceRequestModels.CommerceRequestResult get(@AuthenticationPrincipal VerifiedActorPrincipal principal,
            @PathVariable UUID requestId){return service.get(principal.actorId(),requestId);}
    @GetMapping("/thread/{threadId}") public CommerceRequestModels.CommerceRequestResult latestForThread(
            @AuthenticationPrincipal VerifiedActorPrincipal principal,@PathVariable UUID threadId){
        return service.latestForThread(principal.actorId(),threadId);
    }
    public record Request(@NotNull UUID requestId,UUID threadId,@NotBlank @Size(max=4000) String text) {}
}
