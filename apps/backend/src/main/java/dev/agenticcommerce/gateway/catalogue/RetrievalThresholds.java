package dev.agenticcommerce.gateway.catalogue;

/** Centralized open-set and candidate limits; changes require retrieval evaluation evidence. */
public final class RetrievalThresholds {
    private RetrievalThresholds() {}
    public static final double VALID_MATCH = 0.42;
    public static final double RELATED_ALTERNATIVE = 0.24;
    public static final double MINIMUM_TRIGRAM = 0.18;
    public static final int MAX_CANDIDATES = 50;
    public static final int MAX_RESULTS = 20;
}
