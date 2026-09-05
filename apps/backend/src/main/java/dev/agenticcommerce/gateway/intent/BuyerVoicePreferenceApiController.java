package dev.agenticcommerce.gateway.intent;

import dev.agenticcommerce.gateway.identity.authentication.VerifiedActorPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/buyer/settings/voice")
public class BuyerVoicePreferenceApiController {
    private final BuyerVoicePreferenceService service;

    public BuyerVoicePreferenceApiController(BuyerVoicePreferenceService service) {
        this.service = service;
    }

    @GetMapping
    public BuyerVoicePreferenceService.VoicePreference get(
            @AuthenticationPrincipal VerifiedActorPrincipal principal) {
        return service.get(principal.actorId());
    }

    @PutMapping
    public BuyerVoicePreferenceService.VoicePreference save(
            @AuthenticationPrincipal VerifiedActorPrincipal principal,
            @RequestBody BuyerVoicePreferenceService.VoicePreferenceInput input) {
        return service.save(principal.actorId(), input);
    }
}
