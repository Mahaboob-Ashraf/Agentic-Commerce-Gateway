package dev.agenticcommerce.gateway.intent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class BuyerVoicePreferenceServiceTest {
    private final BuyerVoicePreferenceRepository repository=mock(BuyerVoicePreferenceRepository.class);
    private final BuyerVoicePreferenceService service=new BuyerVoicePreferenceService(repository);

    @Test void missingPreferenceUsesStableKoreDefaultWithoutCreatingBrowserOnlyState(){
        UUID buyer=UUID.randomUUID();when(repository.find(buyer)).thenReturn(Optional.empty());
        assertThat(service.get(buyer).voiceName()).isEqualTo("Kore");
        verify(repository,never()).save(any(),any(),any());
    }

    @Test void curatedVoicePersistsAndUnsupportedVoiceFailsBeforeRepositoryMutation(){
        UUID buyer=UUID.randomUUID();var stored=new BuyerVoicePreferenceService.VoicePreference("Puck",2,java.time.Instant.now());
        when(repository.save(eq(buyer),eq("Puck"),any())).thenReturn(stored);
        assertThat(service.save(buyer,new BuyerVoicePreferenceService.VoicePreferenceInput("Puck"))).isEqualTo(stored);
        assertThatThrownBy(()->service.save(buyer,new BuyerVoicePreferenceService.VoicePreferenceInput("NotARealVoice")))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("BUYER_VOICE_NOT_SUPPORTED");
        verify(repository).save(eq(buyer),eq("Puck"),any());
        verifyNoMoreInteractions(repository);
    }
}
