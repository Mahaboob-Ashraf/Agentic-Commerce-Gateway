package dev.agenticcommerce.gateway.catalogue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Fixed-origin, exact-barcode Open Food Facts v2 reader. */
@Component
@ConditionalOnProperty(name = "catalogue.open-food-facts.enabled", havingValue = "true")
public class OpenFoodFactsCatalogueProvider implements CatalogueProvider {
    private static final Pattern BARCODE = Pattern.compile("[0-9]{8,14}");
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;
    private final HttpClient client;
    private final ObjectMapper mapper;

    public OpenFoodFactsCatalogueProvider(ObjectMapper mapper) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build(), mapper);
    }

    OpenFoodFactsCatalogueProvider(HttpClient client, ObjectMapper mapper) {
        this.client = client; this.mapper = mapper;
    }

    @Override
    public Optional<ExternalProduct> lookupExactBarcode(String barcode) {
        if (barcode == null || !BARCODE.matcher(barcode).matches()) return Optional.empty();
        URI uri = URI.create("https://world.openfoodfacts.org/api/v2/product/" + barcode
                + ".json?fields=code,brands,product_name,quantity,ingredients_text,allergens_tags,labels_tags,nutriments,image_front_url,last_modified_t");
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(4))
                .header("Accept", "application/json").header("User-Agent", "Agentic-Commerce-Gateway/1.0")
                .GET().build();
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 404) return Optional.empty();
            if (response.statusCode() != 200) throw new IllegalStateException("OPEN_FOOD_FACTS_HTTP_" + response.statusCode());
            byte[] body = response.body();
            if (body.length > MAX_RESPONSE_BYTES) throw new IllegalStateException("OPEN_FOOD_FACTS_RESPONSE_TOO_LARGE");
            JsonNode root = mapper.readTree(body);
            if (root.path("status").asInt() != 1) return Optional.empty();
            JsonNode p = root.path("product");
            String returnedBarcode = text(p, "code");
            if (!barcode.equals(returnedBarcode)) throw new IllegalStateException("OPEN_FOOD_FACTS_BARCODE_MISMATCH");
            List<String> allergens = stringArray(p.path("allergens_tags"));
            List<String> labels = stringArray(p.path("labels_tags"));
            Boolean vegetarian = labels.stream().anyMatch(v -> v.endsWith("vegetarian")) ? Boolean.TRUE : null;
            double protein = p.path("nutriments").path("proteins_100g").asDouble(Double.NaN);
            long modified = p.path("last_modified_t").asLong(0);
            return Optional.of(new ExternalProduct(returnedBarcode, returnedBarcode, text(p,"brands"),
                    text(p,"product_name"), null, text(p,"quantity"),
                    splitIngredients(text(p,"ingredients_text")), allergens, vegetarian,
                    Double.isNaN(protein) ? null : protein, text(p,"image_front_url"), "OFF-v2",
                    modified > 0 ? Instant.ofEpochSecond(modified) : Instant.now()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OPEN_FOOD_FACTS_INTERRUPTED", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("OPEN_FOOD_FACTS_IO_FAILURE", exception);
        }
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText("").strip();
        return value.isEmpty() ? null : value.substring(0, Math.min(value.length(), 4000));
    }
    private static List<String> stringArray(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node.isArray()) node.forEach(v -> { if (values.size() < 64) values.add(v.asText().replaceFirst("^[a-z]{2}:", "")); });
        return List.copyOf(values);
    }
    private static List<String> splitIngredients(String input) {
        if (input == null) return List.of();
        return java.util.Arrays.stream(input.split(",")).map(String::strip).filter(v -> !v.isEmpty())
                .limit(64).toList();
    }
}
