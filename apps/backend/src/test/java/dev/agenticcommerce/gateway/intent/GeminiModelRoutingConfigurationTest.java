package dev.agenticcommerce.gateway.intent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GeminiModelRoutingConfigurationTest {
    @Test void workloadSpecificModelsWinAndGenericModelIsOnlyBackwardCompatibleFallback()throws Exception{
        String configuration=Files.readString(Path.of("src","main","resources","application.yml"));
        assertThat(configuration).contains(
                "${GEMINI_BUYER_INTENT_MODEL:gemini-3.1-flash-lite}",
                "${GEMINI_VISION_MODEL:gemini-3.5-flash-lite}",
                "${GEMINI_AGENTIZATION_MODEL:${GEMINI_MODEL:gemini-3.5-flash-lite}}");
        assertThat(configuration).doesNotContain("${GEMINI_MODEL:gemini-3.6-flash}");
    }
}
