package dev.agenticcommerce.gateway.intent;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BuyerVoicePreferenceService {
    public static final String DEFAULT_VOICE = "Kore";
    public static final List<String> CURATED_VOICES = List.of("Kore", "Aoede", "Puck", "Charon");
    private static final Set<String> ALLOWED_VOICES = Set.copyOf(CURATED_VOICES);

    private final BuyerVoicePreferenceRepository repository;

    public BuyerVoicePreferenceService(BuyerVoicePreferenceRepository repository) {
        this.repository = repository;
    }

    public VoicePreference get(UUID buyerActorId) {
        return repository.find(buyerActorId)
                .orElseGet(() -> new VoicePreference(DEFAULT_VOICE, 0, null));
    }

    @Transactional
    public VoicePreference save(UUID buyerActorId, VoicePreferenceInput input) {
        String voiceName = input == null ? null : input.voiceName();
        if (!ALLOWED_VOICES.contains(voiceName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "BUYER_VOICE_NOT_SUPPORTED");
        }
        return repository.save(buyerActorId, voiceName, Instant.now());
    }

    public record VoicePreference(String voiceName, int version, Instant updatedAt) {}
    public record VoicePreferenceInput(String voiceName) {}
}
