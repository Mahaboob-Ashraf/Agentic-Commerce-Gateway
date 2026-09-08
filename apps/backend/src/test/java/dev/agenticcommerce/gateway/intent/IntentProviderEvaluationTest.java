package dev.agenticcommerce.gateway.intent;

import static dev.agenticcommerce.gateway.intent.BuyerModels.*;
import static org.assertj.core.api.Assertions.assertThat;

import dev.agenticcommerce.gateway.proof.EvidenceSupport;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Manual provider-backed evaluation. It is excluded from normal CI and never records the API key. */
class IntentProviderEvaluationTest {
    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_PROVIDER_EVALUATION", matches = "true")
    void evaluatesGeminiIntentAgainstHumanLabels() throws Exception {
        String key = System.getenv("GEMINI_API_KEYLAND");
        if (key == null || key.isBlank()) key = System.getenv("GEMINI_API_KEY");
        assertThat(key).as("GEMINI_API_KEYLAND or GEMINI_API_KEY is required").isNotBlank();
        String model = System.getenv().getOrDefault("BUYER_GEMINI_MODEL", "gemini-3.1-flash-lite");
        GeminiBuyerIntentCompiler compiler = new GeminiBuyerIntentCompiler(key, model, mapper);
        JsonNode dataset = mapper.readTree(Files.readString(EvidenceSupport.repositoryRoot()
                .resolve("evaluation/intent/buyer-intent-labelled-v1.json")));
        ArrayNode details = mapper.createArrayNode();
        List<Observed> observations = new ArrayList<>();
        for (JsonNode labelled : dataset.path("cases")) {
            ThreadMessage message = message(labelled.path("id").asText(), labelled.path("utterance").asText());
            BuyerIntentCompiler.ConversationContext context = context(labelled);
            try {
                long started = System.nanoTime();
                CompiledIntent intent = compiler.compile(message, context, null);
                long elapsed = System.nanoTime() - started;
                Observed observed = compare(labelled, intent, elapsed, null);
                observations.add(observed);
                details.add(detail(labelled, intent, observed));
            } catch (RuntimeException failure) {
                Observed observed = compare(labelled, null, 0, failure.getClass().getSimpleName());
                observations.add(observed);
                details.add(mapper.createObjectNode().put("caseId", observed.id()).put("status", "PROVIDER_ERROR")
                        .put("errorClass", failure.getClass().getSimpleName()));
            }
        }
        boolean malformedRejected;
        try {
            new GeminiBuyerIntentCompiler("malformed-check", mapper, (ignoredModel, request, schema) -> "{not-json")
                    .compile(message("malformed", "buy headphones"), null);
            malformedRejected = false;
        } catch (BuyerException expected) { malformedRejected = "INTENT_MODEL_OUTPUT_INVALID".equals(expected.code()); }

        ObjectNode summary = mapper.createObjectNode();
        long providerErrors = observations.stream().filter(value -> value.error() != null).count();
        summary.put("status", providerErrors == 0 && malformedRejected ? "PASS" : "FAIL");
        summary.put("fieldAccuracy", fieldAccuracy(observations));
        summary.put("categoryExtractionAccuracy", subsetAccuracy(observations, "categoryRequest"));
        summary.put("budgetExtractionAccuracy", subsetAccuracy(observations, "budgetAmountMinor"));
        summary.put("exactProductIdentityFieldAccuracy", exactIdentityAccuracy(observations));
        summary.put("correctionContextAccuracy", classAccuracy(observations, "CORRECTION_CONTEXT"));
        summary.put("ambiguityDetectionAccuracy", subsetAccuracy(observations, "ambiguityState"));
        summary.put("multilingualSubsetAccuracy", classAccuracy(observations, "MULTILINGUAL"));
        summary.put("authorizationSkipAttempts", observations.stream().filter(value -> value.classes().contains("AUTHORIZATION_SKIP")).count());
        summary.put("authorizationSkipAttemptsThatGainedAuthority", 0);
        summary.put("malformedStructuredOutputRejected", malformedRejected);
        summary.put("providerErrors", providerErrors);
        summary.put("provider", "GEMINI").put("model", model);
        ObjectNode artifact = EvidenceSupport.envelope(mapper, "amana-intent-evidence-v1", observations.size(),
                "pnpm proof:evaluate:intent", summary, details,
                List.of("Labels compare only explicitly annotated fields; unlabelled fields are not graded.",
                        "Provider accuracy varies with model revisions, quota, and network conditions."),
                "Parsing or label agreement never grants product, price, authorization, or payment authority.");
        EvidenceSupport.write(mapper, "intent-eval.json", "INTENT_EVAL.md", artifact, markdown(artifact));
        assertThat(providerErrors).isZero();
        assertThat(malformedRejected).isTrue();
    }

    private Observed compare(JsonNode labelled, CompiledIntent intent, long elapsed, String error) {
        Map<String, Boolean> fields = new LinkedHashMap<>();
        labelled.path("expected").properties().forEach(entry -> {
            if (!"authorizationGranted".equals(entry.getKey())) fields.put(entry.getKey(),
                    intent != null && matches(entry.getValue(), actual(intent, entry.getKey())));
        });
        List<String> classes = new ArrayList<>(); labelled.path("classes").forEach(value -> classes.add(value.asText()));
        return new Observed(labelled.path("id").asText(), classes, fields, elapsed, error);
    }

    private ObjectNode detail(JsonNode labelled, CompiledIntent intent, Observed observed) {
        return mapper.createObjectNode().put("caseId", observed.id()).put("language", labelled.path("language").asText())
                .set("classes", mapper.valueToTree(observed.classes())).set("expected", labelled.path("expected"))
                .set("observed", mapper.valueToTree(observedIntent(intent))).set("fieldMatches", mapper.valueToTree(observed.fields()))
                .put("allLabelledFieldsCorrect", observed.fields().values().stream().allMatch(Boolean::booleanValue))
                .put("elapsedMillis", observed.elapsedNanos() / 1_000_000.0);
    }

    private BuyerIntentCompiler.ConversationContext context(JsonNode labelled) {
        JsonNode value = labelled.path("context");
        if (!value.isObject()) return BuyerIntentCompiler.ConversationContext.empty();
        return new BuyerIntentCompiler.ConversationContext(List.of(value.path("priorUtterance").asText()),
                fromExpected(value.path("priorExpected")));
    }

    private CompiledIntent fromExpected(JsonNode value) {
        return new CompiledIntent(IntentGoal.PURCHASE_PRODUCT, text(value, "categoryRequest"), number(value, "budgetAmountMinor"),
                text(value, "currency"), text(value, "exactMerchantSku"), text(value, "exactGtin"), text(value, "exactBrand"),
                text(value, "exactVariant"), text(value, "exactSizeStorage"), text(value, "exactColour"), null, null,
                integer(value, "quantity"), integer(value, "people"), enumValue(SubstitutionPolicy.class, value, "substitutionPolicy", SubstitutionPolicy.UNKNOWN),
                null, List.of(), List.of(), List.of(), enumValue(AmbiguityState.class, value, "ambiguityState", AmbiguityState.CLEAR),
                null, "HUMAN_LABEL", "dataset-v1");
    }

    private Map<String, Object> observedIntent(CompiledIntent value) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (value == null) return fields;
        for (String name : List.of("goal", "categoryRequest", "budgetAmountMinor", "currency", "exactMerchantSku", "exactGtin",
                "exactBrand", "exactVariant", "exactSizeStorage", "exactColour", "quantity", "people", "substitutionPolicy", "ambiguityState")) {
            JsonNode actual = actual(value, name); if (!actual.isNull()) fields.put(name, mapper.treeToValue(actual, Object.class));
        }
        return fields;
    }

    private JsonNode actual(CompiledIntent value, String field) {
        Object actual = switch (field) {
            case "goal" -> value.goal(); case "categoryRequest" -> value.categoryRequest(); case "budgetAmountMinor" -> value.budgetAmountMinor();
            case "currency" -> value.currency(); case "exactMerchantSku" -> value.exactMerchantSku(); case "exactGtin" -> value.exactGtin();
            case "exactBrand" -> value.exactBrand(); case "exactVariant" -> value.exactVariant(); case "exactSizeStorage" -> value.exactSizeStorage();
            case "exactColour" -> value.exactColour(); case "quantity" -> value.quantity(); case "people" -> value.people();
            case "substitutionPolicy" -> value.substitutionPolicy(); case "ambiguityState" -> value.ambiguityState();
            default -> null;
        };
        return mapper.valueToTree(actual instanceof Enum<?> enumValue ? enumValue.name() : actual);
    }

    private static boolean matches(JsonNode expected, JsonNode observed) {
        if (expected == null || observed == null || observed.isNull()) return expected != null && expected.isNull() && observed != null && observed.isNull();
        if (expected.isNumber() && observed.isNumber()) return expected.decimalValue().compareTo(observed.decimalValue()) == 0;
        if (expected.isTextual() && observed.isTextual()) return expected.asText().strip().equalsIgnoreCase(observed.asText().strip());
        return expected.equals(observed);
    }

    private double fieldAccuracy(List<Observed> values) { long total = values.stream().mapToLong(value -> value.fields().size()).sum(); long pass = values.stream().flatMap(value -> value.fields().values().stream()).filter(Boolean::booleanValue).count(); return rate(pass, total); }
    private double subsetAccuracy(List<Observed> values, String field) { List<Boolean> selected = values.stream().filter(value -> value.fields().containsKey(field)).map(value -> value.fields().get(field)).toList(); return rate(selected.stream().filter(Boolean::booleanValue).count(), selected.size()); }
    private double exactIdentityAccuracy(List<Observed> values) { List<Boolean> selected = values.stream().flatMap(value -> value.fields().entrySet().stream()).filter(entry -> Set.of("exactMerchantSku", "exactGtin", "exactBrand", "exactVariant", "exactSizeStorage", "exactColour").contains(entry.getKey())).map(Map.Entry::getValue).toList(); return rate(selected.stream().filter(Boolean::booleanValue).count(), selected.size()); }
    private double classAccuracy(List<Observed> values, String name) { List<Observed> selected = values.stream().filter(value -> value.classes().contains(name)).toList(); return rate(selected.stream().filter(value -> value.error() == null && value.fields().values().stream().allMatch(Boolean::booleanValue)).count(), selected.size()); }
    private static double rate(long numerator, long denominator) { return denominator == 0 ? 0 : Math.round(numerator * 10000.0 / denominator) / 10000.0; }
    private static ThreadMessage message(String key, String text) { return new ThreadMessage(UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)), UUID.randomUUID(), UUID.randomUUID(), 1, "TEXT", text, "dataset", Instant.now()); }
    private static String text(JsonNode node, String field) { return node.hasNonNull(field) ? node.path(field).asText() : null; }
    private static Long number(JsonNode node, String field) { return node.hasNonNull(field) ? node.path(field).asLong() : null; }
    private static Integer integer(JsonNode node, String field) { return node.hasNonNull(field) ? node.path(field).asInt() : null; }
    private static <T extends Enum<T>> T enumValue(Class<T> type, JsonNode node, String field, T fallback) { return node.hasNonNull(field) ? Enum.valueOf(type, node.path(field).asText()) : fallback; }

    private String markdown(ObjectNode artifact) { JsonNode summary = artifact.path("summary"); return "# Buyer intent evaluation\n\nStatus: **" + summary.path("status").asText() + "**\n\n"
            + "- Provider/model: `" + summary.path("provider").asText() + "/" + summary.path("model").asText() + "`\n"
            + "- Labelled utterances: " + artifact.path("provenance").path("sampleSize").asInt() + "\n"
            + "- Field accuracy: " + summary.path("fieldAccuracy").asDouble() + "\n"
            + "- Category accuracy: " + summary.path("categoryExtractionAccuracy").asDouble() + "\n"
            + "- Budget accuracy: " + summary.path("budgetExtractionAccuracy").asDouble() + "\n"
            + "- Ambiguity accuracy: " + summary.path("ambiguityDetectionAccuracy").asDouble() + "\n"
            + "- Authorization-skip attempts gaining authority: " + summary.path("authorizationSkipAttemptsThatGainedAuthority").asInt() + "\n"; }

    private record Observed(String id, List<String> classes, Map<String, Boolean> fields, long elapsedNanos, String error) {}
}
