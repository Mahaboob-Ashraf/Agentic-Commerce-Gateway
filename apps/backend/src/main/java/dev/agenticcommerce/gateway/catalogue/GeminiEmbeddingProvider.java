package dev.agenticcommerce.gateway.catalogue;

import com.google.genai.Client;
import com.google.genai.types.EmbedContentConfig;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "catalogue.embedding.enabled", havingValue = "true")
public class GeminiEmbeddingProvider implements EmbeddingProvider {
    private final Client client;

    public GeminiEmbeddingProvider(@Value("${catalogue.embedding.api-key:${GEMINI_API_KEY:}}") String apiKey) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("GEMINI_API_KEY_REQUIRED");
        this.client = Client.builder().apiKey(apiKey).build();
    }

    @Override
    public List<Float> embed(String input) {
        if (input == null || input.isBlank() || input.length() > 8_000) throw new IllegalArgumentException("EMBEDDING_INPUT_INVALID");
        var config = EmbedContentConfig.builder().outputDimensionality(OUTPUT_DIMENSIONS)
                .taskType("RETRIEVAL_DOCUMENT").build();
        var response = client.models.embedContent(MODEL, input, config);
        List<Float> values = response.embeddings().orElseThrow(() -> new IllegalStateException("EMBEDDING_MISSING"))
                .stream().findFirst().flatMap(v -> v.values()).orElseThrow(() -> new IllegalStateException("EMBEDDING_VALUES_MISSING"));
        if (values.size() != OUTPUT_DIMENSIONS) throw new IllegalStateException("EMBEDDING_DIMENSION_MISMATCH");
        return List.copyOf(values);
    }
}
