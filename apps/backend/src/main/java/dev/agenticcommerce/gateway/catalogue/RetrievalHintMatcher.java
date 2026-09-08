package dev.agenticcommerce.gateway.catalogue;

import dev.agenticcommerce.gateway.catalogue.CatalogueModels.Product;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Bounded equivalence for untrusted discovery hints. It never resolves authoritative product identity. */
public final class RetrievalHintMatcher {
    private static final Map<String,List<String>> PRODUCT_TYPE_ALIASES = aliases();

    private RetrievalHintMatcher() {}

    public static boolean categoryCompatible(String requested, Product product) {
        if (blank(requested)) return true;
        String request = normalize(requested);
        String category = normalize(product.category());
        if (request.equals(category)) return true;
        String requestedType = productType(request);
        String productMaterial = normalize(String.join(" ", List.of(
                value(product.canonicalName()), value(product.category()), value(product.description()))));
        if (requestedType != null) return requestedType.equals(productType(productMaterial));
        Set<String> requestedTokens = tokens(request);
        Set<String> productTokens = tokens(productMaterial);
        return !requestedTokens.isEmpty() && productTokens.containsAll(requestedTokens);
    }

    /** Allows only orthographic spacing/punctuation equivalence, never edit-distance or substring brand fuzzing. */
    public static boolean brandEquivalent(String requested, String authoritative) {
        if (blank(requested)) return true;
        if (blank(authoritative)) return false;
        String left = normalize(requested);
        String right = normalize(authoritative);
        return left.equals(right) || compact(left).equals(compact(right));
    }

    private static String productType(String value) {
        for (var entry : PRODUCT_TYPE_ALIASES.entrySet()) {
            if (entry.getValue().stream().anyMatch(alias -> containsPhrase(value, alias))) return entry.getKey();
        }
        return null;
    }

    private static boolean containsPhrase(String material, String phrase) {
        return (" " + material + " ").contains(" " + phrase + " ");
    }

    private static Map<String,List<String>> aliases() {
        Map<String,List<String>> values = new LinkedHashMap<>();
        values.put("EARPHONES", List.of("earphones", "earphone", "earbuds", "earbud", "buds", "in ear audio", "in ear headphones"));
        values.put("HEADPHONES", List.of("headphones", "headphone", "over ear audio", "over ear headphones", "anc headphones"));
        values.put("LIFESTYLE_SNEAKERS", List.of("lifestyle sneakers", "lifestyle sneaker", "casual shoes", "casual shoe", "white sneakers", "low top sneakers", "sneakers", "sneaker"));
        values.put("RUNNING_SHOES", List.of("runner shoes", "running shoes", "running shoe"));
        values.put("KITCHEN_SCALE", List.of("digital kitchen scale", "kitchen scale", "weighing scale"));
        values.put("FRYING_PAN", List.of("stainless steel frying pan", "frying pan", "fry pan"));
        values.put("FACE_WASH", List.of("face wash", "skin cleanser", "facial cleanser"));
        values.put("LOTION", List.of("moisturising lotion", "moisturizing lotion", "body lotion"));
        values.put("YOGA_MAT", List.of("yoga mat", "exercise mat", "workout mat"));
        values.put("KETTLE", List.of("electric kettle", "water kettle", "kettle"));
        values.put("LAPTOP", List.of("laptop", "notebook computer"));
        values.put("SMARTPHONE", List.of("smartphone", "smart phone", "mobile phone"));
        values.put("TABLE_LAMP", List.of("table lamp", "bedside lamp"));
        return Map.copyOf(values);
    }

    private static Set<String> tokens(String value) {
        return value.isBlank() ? Set.of() : Set.copyOf(List.of(value.split("\\s+")));
    }

    private static String compact(String value) { return value.replace(" ", ""); }
    private static String value(String value) { return value == null ? "" : value; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String normalize(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }
}
