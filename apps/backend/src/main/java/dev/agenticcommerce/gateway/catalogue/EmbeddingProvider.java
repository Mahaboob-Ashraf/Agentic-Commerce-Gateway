package dev.agenticcommerce.gateway.catalogue;

import java.util.List;

/** Typed embedding boundary. Failed calls must throw; callers persist a lexical-fallback state. */
public interface EmbeddingProvider {
    String MODEL = "gemini-embedding-2";
    int OUTPUT_DIMENSIONS = 768;

    List<Float> embed(String input);
}
