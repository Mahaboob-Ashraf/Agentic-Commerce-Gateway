package dev.agenticcommerce.gateway.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.genai.errors.ClientException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class GeminiBuyerDecisionProviderTest {
    @Test
    void providerFailureIsSanitizedClassifiedUnavailableAndNotRepaired(CapturedOutput output) {
        AtomicInteger calls = new AtomicInteger();
        String apiKey = "AIzaSyTask0133SecretCredential123456789";
        var provider = new GeminiBuyerDecisionProvider("gemini-test", JsonMapper.builder().build(),
                (model, prompt, schema) -> {
                    calls.incrementAndGet();
                    throw new ClientException(429, "RESOURCE_EXHAUSTED",
                            "Quota exceeded; x-goog-api-key=" + apiKey);
                });
        UUID productId=UUID.randomUUID();var context=new BuyerDecisionProvider.CandidateDecisionContext(List.of(
                new BuyerDecisionProvider.CandidateOption(productId,UUID.randomUUID(),"Grounded product","Brand",
                        "Model",null,null,"Category",100L,"INR",0.9)),List.of("GOOD"),List.of("product:"+productId));

        assertThatThrownBy(() -> provider.chooseCandidate(context,null))
                .isInstanceOfSatisfying(BuyerException.class, failure ->
                        assertThat(failure.code()).isEqualTo("BUYER_DECISION_UNAVAILABLE"));

        assertThat(calls).hasValue(1);
        assertThat(output.getAll())
                .contains("exceptionClass=com.google.genai.errors.ClientException")
                .contains("httpStatus=429")
                .contains("providerCode=RESOURCE_EXHAUSTED")
                .doesNotContain(apiKey);
    }
}
