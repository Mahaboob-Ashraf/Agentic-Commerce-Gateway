package dev.agenticcommerce.gateway.intent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class GeminiBuyerIntentCompilerContextTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues(
                    "buyer.gemini.enabled=true",
                    "buyer.gemini.api-key=context-startup-test-key",
                    "buyer.gemini.model=context-startup-test-model")
            .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
            .withUserConfiguration(GeminiBuyerIntentCompiler.class);

    @Test
    void startsContextWithGeminiEnabledAndProductionDependenciesInjected() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(GeminiBuyerIntentCompiler.class);
        });
    }
}
