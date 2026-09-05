package dev.agenticcommerce.gateway.intent;

import static dev.agenticcommerce.gateway.intent.BuyerModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.genai.errors.ClientException;
import com.google.genai.types.GenerateContentConfig;
import dev.agenticcommerce.gateway.agentization.service.CanonicalJsonService;
import dev.agenticcommerce.gateway.catalogue.CatalogueModels.Availability;
import dev.agenticcommerce.gateway.catalogue.CatalogueModels.Product;
import dev.agenticcommerce.gateway.catalogue.CatalogueRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class GeminiBuyerIntentCompilerTest {
    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void genericElectronicsCompilesIntoCanonicalPurchaseIntent() {
        ThreadMessage message = message("Find good wireless earphones under 3000 rupees");
        CompiledIntent modelOutput = intent("wireless earphones", 300000L, null, null, null,
                List.of("GOOD_QUALITY"), List.of(
                        field(message, "CATEGORY", ConstraintClassification.HARD, "wireless earphones"),
                        field(message, "BUDGET", ConstraintClassification.HARD, "3000"),
                        field(message, "PREFERENCES", ConstraintClassification.SOFT, "good")));
        AtomicReference<String> prompt = new AtomicReference<>();
        IntentCompilerService.Compiled compiled = service((model, request, schema) -> {
            prompt.set(request);
            return modelJson(modelOutput);
        }).compile(message);

        assertThat(compiled.intent().goal()).isEqualTo(IntentGoal.PURCHASE_PRODUCT);
        assertThat(compiled.intent().categoryRequest()).isEqualTo("wireless earphones");
        assertThat(compiled.intent().budgetAmountMinor()).isEqualTo(300000L);
        assertThat(compiled.intent().currency()).isEqualTo("INR");
        assertThat(compiled.intent().softPreferences()).containsExactly("GOOD_QUALITY");
        assertThat(prompt.get()).contains(
                "materialFieldClassifications",
                "CLEAR requires clarificationQuestion=null",
                "requiredOutputJsonSchema",
                "Return exactly one JSON object",
                "no additional fields");
    }

    @Test
    void providerConfigRequestsJsonWithoutProviderEnforcedSchema() {
        GenerateContentConfig config = GeminiBuyerIntentCompiler.generationConfig();

        assertThat(config.responseMimeType()).contains("application/json");
        assertThat(config.responseSchema()).isEmpty();
        assertThat(config.responseJsonSchema()).isEmpty();
        assertThat(config.temperature()).contains(0.0f);
        assertThat(config.maxOutputTokens()).contains(4096);
    }

    @Test
    void merchantQualifiedNameCannotBecomeProductBrandAndTriggersBoundedRepair() {
        ThreadMessage message = message("Find Auralink Buds Bluetooth Earphones from Amazing");
        CompiledIntent invalid = intent(null, null, "Amazing", "Auralink Buds Bluetooth Earphones", null,
                List.of(), List.of(
                        field(message, "BRAND", ConstraintClassification.HARD, "Amazing"),
                        field(message, "VARIANT", ConstraintClassification.HARD, "Auralink Buds Bluetooth Earphones")));
        CompiledIntent repaired = intent("Earphones", null, "Auralink", "Buds Pro", null,
                List.of(), List.of(
                        field(message, "CATEGORY", ConstraintClassification.HARD, "Earphones"),
                        field(message, "BRAND", ConstraintClassification.HARD, "Auralink"),
                        field(message, "VARIANT", ConstraintClassification.HARD, "Buds Bluetooth Earphones")));
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<String> repairPrompt = new AtomicReference<>();

        CompiledIntent result = service((model, prompt, schema) -> {
            if (attempts.incrementAndGet() == 1) return modelJson(invalid);
            repairPrompt.set(prompt);
            return modelJson(repaired);
        }).compile(message).intent();

        assertThat(attempts).hasValue(2);
        assertThat(result.exactBrand()).isEqualTo("Auralink");
        assertThat(result.exactVariant()).isEqualTo("Buds Pro");
        assertThat(repairPrompt.get()).contains(
                "Store or merchant names introduced by phrases",
                "schema has no merchant-selection field",
                "BRAND evidence identifies a merchant/store phrase");
    }

    @Test
    void followUpPromptCarriesPersistedThreadIntentAndMakesNewestCorrectionAuthoritative() {
        ThreadMessage original = message("Find Ora Link buds bluetooth earphones from Amazing");
        CompiledIntent prior = intent("bluetooth earphones", null, "Ora Link", "Buds", null,
                List.of(), List.of(
                        field(original, "CATEGORY", ConstraintClassification.HARD, "bluetooth earphones"),
                        field(original, "BRAND", ConstraintClassification.HARD, "Ora Link"),
                        field(original, "VARIANT", ConstraintClassification.HARD, "buds")));
        ThreadMessage correction = message("AURALINK no space in between");
        CompiledIntent corrected = intent("bluetooth earphones", null, "Auralink", "Buds", null,
                List.of(), List.of(
                        field(correction, "CATEGORY", ConstraintClassification.HARD, "AURALINK"),
                        field(correction, "BRAND", ConstraintClassification.HARD, "AURALINK"),
                        field(correction, "VARIANT", ConstraintClassification.HARD, "AURALINK")));
        AtomicReference<String> prompt = new AtomicReference<>();
        var compiler = new GeminiBuyerIntentCompiler("gemini-test", mapper, (model, request, schema) -> {
            prompt.set(request);
            return modelJson(corrected);
        });

        CompiledIntent result = new IntentCompilerService(compiler,new CanonicalJsonService(mapper),mapper)
                .compile(correction,new BuyerIntentCompiler.ConversationContext(
                        List.of(original.normalizedText()),prior)).intent();

        assertThat(result.exactBrand()).isEqualTo("Auralink");
        assertThat(prompt.get()).contains("conversationContext","priorCurrentIntent","Ora Link",
                "The newest inputText is authoritative","AURALINK no space in between");
    }

    @Test
    void visualCategoryIsOnlyRetrievalContextWhileExplicitBudgetRemainsHard() {
        ThreadMessage message=message("Find something like this under ₹4,000");
        CompiledIntent output=intent("low-top lifestyle sneakers",400_000L,null,null,null,List.of(),List.of(
                field(message,"CATEGORY",ConstraintClassification.HARD,"this"),
                field(message,"BUDGET",ConstraintClassification.HARD,"4,000")));
        AtomicReference<String> prompt=new AtomicReference<>();
        var compiler=new GeminiBuyerIntentCompiler("gemini-3.1-flash-lite",mapper,(model,request,schema)->{
            prompt.set(request);ObjectNode json=(ObjectNode)mapper.readTree(modelJson(output));
            ((ObjectNode)json.path("materialFields").get(0)).put("source","VISUAL_HYPOTHESIS");return mapper.writeValueAsString(json);});
        var observation=new VisualCommerceModels.VisionObservation("Footwear","low-top sneaker",null,null,
                List.of("white"),List.of("canvas"),List.of("lifestyle"),List.of(),new BigDecimal("0.86"),List.of("brand unknown"));

        CompiledIntent compiled=new IntentCompilerService(compiler,new CanonicalJsonService(mapper),mapper)
                .compile(message,new BuyerIntentCompiler.ConversationContext(List.of(),null,observation)).intent();

        assertThat(compiled.materialFields()).filteredOn(field->field.field().equals("CATEGORY"))
                .allMatch(field->field.classification()==ConstraintClassification.SOFT);
        assertThat(compiled.materialFields()).filteredOn(field->field.field().equals("BUDGET"))
                .allMatch(field->field.classification()==ConstraintClassification.HARD);
        assertThat(prompt.get()).contains("visualHypothesis","Explicit inputText constraints always dominate","visibleText is observed pixels only");
    }

    @Test
    void explicitMaterialExclusionIsHardAndCannotBeIntroducedByVision() {
        ThreadMessage message=message("Find something like this, but not leather");
        CompiledIntent output=intent("low-top sneaker",null,null,null,null,List.of(),List.of(
                field(message,"CATEGORY",ConstraintClassification.HARD,"this")));
        var compiler=new GeminiBuyerIntentCompiler("gemini-test",mapper,(model,request,schema)->{ObjectNode json=(ObjectNode)mapper.readTree(modelJson(output));
            ((ObjectNode)json.path("materialFields").get(0)).put("source","VISUAL_HYPOTHESIS");ObjectNode excluded=((ArrayNode)json.path("materialFields")).addObject();
            excluded.put("field","NO_MATERIAL");excluded.put("source","EXPLICIT_TEXT");excluded.put("value","leather");excluded.put("minorValue",0);
            excluded.put("startOffset",message.normalizedText().indexOf("leather"));excluded.put("endOffset",message.normalizedText().indexOf("leather")+7);excluded.put("modelSignal",1);
            return mapper.writeValueAsString(json);});
        var observation=new VisualCommerceModels.VisionObservation("Footwear","sneaker",null,null,List.of("white"),List.of("leather-like"),List.of(),List.of(),BigDecimal.ONE,List.of());
        CompiledIntent compiled=new IntentCompilerService(compiler,new CanonicalJsonService(mapper),mapper)
                .compile(message,new BuyerIntentCompiler.ConversationContext(List.of(),null,observation)).intent();
        assertThat(compiled.excludedMaterials()).containsExactly("leather");
        assertThat(compiled.materialFields()).filteredOn(field->field.field().equals("EXCLUDED_MATERIAL"))
                .allMatch(field->field.classification()==ConstraintClassification.HARD);
    }

    @Test
    void exactElectronicsUsesHardEvidenceForEveryExplicitIdentityField() {
        ThreadMessage message = message("Synthetic Sonic A1 black earphones under 5000 rupees");
        CompiledIntent output = intent("earphones", 500000L, "Synthetic", "Sonic A1", "black",
                List.of(), List.of(
                        field(message, "CATEGORY", ConstraintClassification.HARD, "earphones"),
                        field(message, "BRAND", ConstraintClassification.HARD, "Synthetic"),
                        field(message, "VARIANT", ConstraintClassification.HARD, "Sonic A1"),
                        field(message, "COLOUR", ConstraintClassification.HARD, "black"),
                        field(message, "BUDGET", ConstraintClassification.HARD, "5000")));

        CompiledIntent compiled = service(response(output)).compile(message).intent();
        assertThat(compiled.materialFields()).extracting(MaterialField::field)
                .containsExactly("CATEGORY", "BRAND", "VARIANT", "COLOUR", "BUDGET");
        assertThat(compiled.materialFields()).allMatch(f -> f.classification() == ConstraintClassification.HARD);
    }

    @Test
    void exactProductWithoutCategoryOrBudgetCompilesFromBrandAndVariantIdentity() {
        ThreadMessage message = message("Buy one Synthetic Sonic A1 black");
        CompiledIntent output = new CompiledIntent(IntentGoal.PURCHASE_PRODUCT, null, null, null,
                null, null, "Synthetic", "Sonic A1", null, "black", null, null, 1, null,
                SubstitutionPolicy.UNKNOWN, null, List.of(), List.of(
                        ambiguousField(message, "BRAND", "Synthetic"),
                        ambiguousField(message, "VARIANT", "Sonic A1"),
                        ambiguousField(message, "COLOUR", "black")),
                AmbiguityState.AMBIGUOUS,
                "Could you please specify the product category for the Synthetic Sonic A1 black?", null, null);
        CatalogueRepository catalogues=mock(CatalogueRepository.class);
        when(catalogues.exactIdentityCandidates(anyString(),anyInt())).thenReturn(List.of(new Product(
                UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"SYN-ELEC-SONIC-A1-BLK","8909001000001",
                "Synthetic Sonic","Sonic A1 Wireless Earphones","sonic a1 wireless earphones","A1","Standard",
                "Black","Earphones","Authoritative catalogue product",true,"source",449900L,"INR",14L,
                Availability.IN_STOCK,Instant.parse("2026-08-15T00:00:00Z"))));
        var compiler = new GeminiBuyerIntentCompiler("gemini-test", mapper, response(output));
        var resolver = new ExactProductIdentityResolver(catalogues);

        CompiledIntent compiled = new IntentCompilerService(compiler,new CanonicalJsonService(mapper),mapper,resolver)
                .compile(message).intent();

        assertThat(compiled.categoryRequest()).isNull();
        assertThat(compiled.budgetAmountMinor()).isNull();
        assertThat(compiled.exactBrand()).isEqualTo("Synthetic Sonic");
        assertThat(compiled.exactVariant()).isEqualTo("A1");
        assertThat(compiled.exactColour()).isEqualTo("Black");
        assertThat(compiled.quantity()).isEqualTo(1);
        assertThat(compiled.ambiguityState()).isEqualTo(AmbiguityState.CLEAR);
        assertThat(compiled.clarificationQuestion()).isNull();
        assertThat(compiled.materialFields()).extracting(MaterialField::field)
                .containsExactly("BRAND", "VARIANT", "COLOUR");
        assertThat(compiled.materialFields()).allMatch(field->field.ambiguity()==AmbiguityState.CLEAR);
    }

    @Test
    void softQualityPreferenceDoesNotInvalidatePurchaseIntent() {
        ThreadMessage message = message("Find good earphones");
        CompiledIntent output = intent("earphones", null, null, null, null,
                List.of("GOOD_QUALITY"), List.of(
                        field(message, "CATEGORY", ConstraintClassification.HARD, "earphones"),
                        field(message, "PREFERENCES", ConstraintClassification.SOFT, "good")));

        assertThat(service(response(output)).compile(message).intent().ambiguityState())
                .isEqualTo(AmbiguityState.CLEAR);
    }

    @Test
    void invalidModelOffsetsAreReboundInsideTheActualSourceMessage() {
        ThreadMessage message = message("Find wireless earphones under 3000 rupees");
        MaterialField invalidSpan = new MaterialField("CATEGORY", ConstraintClassification.HARD,
                new EvidenceSpan(UUID.randomUUID(), -40, 9000), BigDecimal.ONE, AmbiguityState.CLEAR);
        CompiledIntent output = intent("wireless earphones", null, null, null, null,
                List.of(), List.of(invalidSpan));

        MaterialField repaired = service(response(output)).compile(message).intent().materialFields().getFirst();
        assertThat(repaired.evidence().sourceMessageId()).isEqualTo(message.messageId());
        assertThat(repaired.evidence().startOffset()).isGreaterThanOrEqualTo(0);
        assertThat(repaired.evidence().endOffset()).isLessThanOrEqualTo(message.normalizedText().length());
        assertThat(message.normalizedText().substring(
                repaired.evidence().startOffset(), repaired.evidence().endOffset()))
                .isEqualTo("wireless earphones");
    }

    @Test
    void unknownMaterialFieldCannotEnterTheDomain() {
        ThreadMessage message = message("Find earphones");
        CompiledIntent output = intent("earphones", null, null, null, null, List.of(), List.of(
                field(message, "PRODUCT_TYPE", ConstraintClassification.HARD, "earphones")));
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> service((model, prompt, schema) -> {
            attempts.incrementAndGet();
            return modelJson(output);
        }).compile(message)).isInstanceOfSatisfying(BuyerException.class,
                failure -> assertThat(failure.code()).isEqualTo("INVALID_BUYER_INTENT"));
        assertThat(attempts).hasValue(2);
    }

    @Test
    void providerFailureIsNotReportedAsInvalidBuyerIntent() {
        ThreadMessage message = message("Find earphones");
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> service((model, prompt, schema) -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("provider details must remain private");
        }).compile(message)).isInstanceOfSatisfying(BuyerException.class, failure -> {
            assertThat(failure.code()).isEqualTo("AI_PROVIDER_UNAVAILABLE");
            assertThat(failure.getMessage()).doesNotContain("provider details");
        });
        assertThat(attempts).hasValue(1);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void providerFailureLogsTypedSanitizedDiagnosticsWithoutLeakingApiKey(CapturedOutput output) {
        ThreadMessage message = message("Find earphones");
        String apiKey = "AIzaSyTask0132SecretCredential123456789";

        assertThatThrownBy(() -> service((model, prompt, schema) -> {
            throw new ClientException(429, "RESOURCE_EXHAUSTED",
                    "Quota exceeded; x-goog-api-key=" + apiKey);
        }).compile(message)).isInstanceOfSatisfying(BuyerException.class,
                failure -> assertThat(failure.code()).isEqualTo("AI_PROVIDER_RATE_LIMITED"));

        assertThat(output.getAll())
                .contains("exceptionClass=com.google.genai.errors.ClientException")
                .contains("httpStatus=429")
                .contains("providerCode=RESOURCE_EXHAUSTED")
                .contains("providerMessage=Quota exceeded; x-goog-api-key=[REDACTED]")
                .contains("model=gemini-test")
                .doesNotContain(apiKey);
    }

    @Test
    void malformedModelOutputRetriesOnceThenFailsClosed() {
        ThreadMessage message = message("Find earphones");
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> service((model, prompt, schema) -> {
            attempts.incrementAndGet();
            return "{";
        }).compile(message)).isInstanceOfSatisfying(BuyerException.class,
                failure -> assertThat(failure.code()).isEqualTo("INVALID_BUYER_INTENT"));
        assertThat(attempts).hasValue(2);
    }

    @Test
    void missingRequiredOutputFieldsRetryOnceThenFailClosed() {
        ThreadMessage message = message("Find earphones");
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> service((model, prompt, schema) -> {
            attempts.incrementAndGet();
            return "{\"goal\":\"PURCHASE_PRODUCT\"}";
        }).compile(message)).isInstanceOfSatisfying(BuyerException.class,
                failure -> assertThat(failure.code()).isEqualTo("INVALID_BUYER_INTENT"));
        assertThat(attempts).hasValue(2);
    }

    @Test
    void semanticallyOutOfBoundsOutputRetriesOnceThenFailsClosed() {
        ThreadMessage message = message("Find 101 earphones");
        CompiledIntent output = intent("earphones", null, null, null, null, List.of(), List.of(
                field(message, "CATEGORY", ConstraintClassification.HARD, "earphones")));
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> service((model, prompt, schema) -> {
            attempts.incrementAndGet();
            ObjectNode json = (ObjectNode) mapper.readTree(modelJson(output));
            json.put("quantity", 101);
            return mapper.writeValueAsString(json);
        }).compile(message)).isInstanceOfSatisfying(BuyerException.class,
                failure -> assertThat(failure.code()).isEqualTo("INVALID_BUYER_INTENT"));
        assertThat(attempts).hasValue(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaUsesCanonicalFieldsAndDomainNumericBounds() {
        Map<String, Object> properties = (Map<String, Object>) GeminiBuyerIntentCompiler.schema().get("properties");
        Map<String, Object> materialFields = (Map<String, Object>) properties.get("materialFields");
        Map<String, Object> item = (Map<String, Object>) materialFields.get("items");
        Map<String, Object> itemProperties = (Map<String, Object>) item.get("properties");
        Map<String, Object> field = (Map<String, Object>) itemProperties.get("field");
        Map<String, Object> integerValue = (Map<String, Object>) itemProperties.get("minorValue");
        Map<String, Object> quantity = (Map<String, Object>) properties.get("quantity");

        assertThat((List<String>) field.get("enum")).containsExactly(
                "BUD", "CAT", "SKU", "GTIN", "BRAND", "VAR",
                "SIZE", "COLOR", "VEG", "ALLERGEN", "NO_MATERIAL", "PREF");
        assertThat(integerValue.get("minimum")).isEqualTo(0);
        assertThat(quantity).containsEntry("minimum", 1).containsEntry("maximum", 100);
    }

    private IntentCompilerService service(GeminiBuyerIntentCompiler.IntentContentGenerator generator) {
        var compiler = new GeminiBuyerIntentCompiler("gemini-test", mapper, generator);
        return new IntentCompilerService(compiler, new CanonicalJsonService(mapper), mapper);
    }

    private GeminiBuyerIntentCompiler.IntentContentGenerator response(CompiledIntent intent) {
        return (model, prompt, schema) -> modelJson(intent);
    }

    private String modelJson(CompiledIntent intent) {
        ObjectNode root = mapper.createObjectNode();
        root.put("goal", intent.goal().name());
        root.put("currency", "INR");
        if (intent.quantity() == null) root.putNull("quantity"); else root.put("quantity", intent.quantity());
        if (intent.people() == null) root.putNull("people"); else root.put("people", intent.people());
        root.put("substitutionPolicy", intent.substitutionPolicy().name());
        if (intent.deliveryHint() == null) root.putNull("deliveryHint"); else root.put("deliveryHint", intent.deliveryHint());
        root.put("sourceMessageId", intent.materialFields().getFirst().evidence().sourceMessageId().toString());
        ArrayNode materialFields = root.putArray("materialFields");
        intent.materialFields().forEach(material -> {
            ObjectNode field = materialFields.addObject();
            field.put("field", providerFieldCode(material.field()));
            field.put("source", "EXPLICIT_TEXT");
            field.put("minorValue", "BUDGET".equals(material.field()) && intent.budgetAmountMinor() != null
                    ? intent.budgetAmountMinor() : 0L);
            if ("PREFERENCES".equals(material.field())) field.put("value", String.join("|", intent.softPreferences()));
            else if ("BUDGET".equals(material.field())) field.put("value", Long.toString(intent.budgetAmountMinor() / 100));
            else if ("VEGETARIAN".equals(material.field())) field.put("value", Boolean.toString(intent.vegetarian()));
            else field.put("value", textValue(intent, material.field()));
            field.put("startOffset", material.evidence().startOffset());
            field.put("endOffset", material.evidence().endOffset());
            field.put("modelSignal", material.modelSignal());
        });
        root.put("ambiguityState", intent.ambiguityState().name());
        if (intent.clarificationQuestion() == null) root.putNull("clarificationQuestion");
        else root.put("clarificationQuestion", intent.clarificationQuestion());
        return mapper.writeValueAsString(root);
    }

    private static String textValue(CompiledIntent intent, String field) {
        return switch (field) {
            case "CATEGORY" -> intent.categoryRequest();
            case "MERCHANT_SKU" -> intent.exactMerchantSku();
            case "GTIN" -> intent.exactGtin();
            case "BRAND" -> intent.exactBrand();
            case "VARIANT" -> intent.exactVariant();
            case "SIZE_STORAGE" -> intent.exactSizeStorage();
            case "COLOUR" -> intent.exactColour();
            case "ALLERGEN" -> intent.prohibitedAllergen();
            default -> "";
        };
    }

    private static String providerFieldCode(String canonicalField) {
        return switch (canonicalField) {
            case "BUDGET" -> "BUD";
            case "CATEGORY" -> "CAT";
            case "MERCHANT_SKU" -> "SKU";
            case "GTIN" -> "GTIN";
            case "BRAND" -> "BRAND";
            case "VARIANT" -> "VAR";
            case "SIZE_STORAGE" -> "SIZE";
            case "COLOUR" -> "COLOR";
            case "VEGETARIAN" -> "VEG";
            case "ALLERGEN" -> "ALLERGEN";
            case "PREFERENCES" -> "PREF";
            default -> canonicalField;
        };
    }

    private static ThreadMessage message(String text) {
        return new ThreadMessage(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                "TEXT", text, "hash", Instant.parse("2026-09-02T00:00:00Z"));
    }

    private static MaterialField field(ThreadMessage message, String key,
            ConstraintClassification classification, String evidence) {
        int start = message.normalizedText().indexOf(evidence);
        return new MaterialField(key, classification,
                new EvidenceSpan(message.messageId(), start, start + evidence.length()),
                BigDecimal.ONE, AmbiguityState.CLEAR);
    }

    private static MaterialField ambiguousField(ThreadMessage message,String key,String evidence) {
        int start=message.normalizedText().indexOf(evidence);
        return new MaterialField(key,ConstraintClassification.HARD,
                new EvidenceSpan(message.messageId(),start,start+evidence.length()),BigDecimal.ONE,AmbiguityState.AMBIGUOUS);
    }

    private static CompiledIntent intent(String category, Long budget, String brand, String variant,
            String colour, List<String> preferences, List<MaterialField> fields) {
        return new CompiledIntent(IntentGoal.PURCHASE_PRODUCT, category, budget,
                budget == null ? null : "INR", null, null, brand, variant, null, colour,
                null, null, null, null, SubstitutionPolicy.UNKNOWN, null, preferences,
                fields, AmbiguityState.CLEAR, null, null, null);
    }
}
