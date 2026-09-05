package dev.agenticcommerce.gateway.intent;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class VisualCommerceModels {
    private VisualCommerceModels() {}

    public static final long MAX_IMAGE_BYTES = 5L * 1024L * 1024L;
    public static final int MAX_IMAGE_DIMENSION = 8192;
    public static final long MAX_IMAGE_PIXELS = 25_000_000L;

    /** Untrusted semantic hints only. This type deliberately has no SKU, price, stock, or authority fields. */
    public record VisionObservation(String category,String productType,String brandCandidate,
            String modelCandidate,List<String> colors,List<String> materials,
            List<String> styleDescriptors,List<String> visibleText,BigDecimal confidence,
            List<String> ambiguities) {
        public VisionObservation {
            category=required(category,"category",128);
            productType=required(productType,"productType",128);
            brandCandidate=optional(brandCandidate,"brandCandidate",128);
            modelCandidate=optional(modelCandidate,"modelCandidate",128);
            colors=bounded(colors,"colors",8,64);
            materials=bounded(materials,"materials",8,64);
            styleDescriptors=bounded(styleDescriptors,"styleDescriptors",12,96);
            visibleText=bounded(visibleText,"visibleText",12,160);
            ambiguities=bounded(ambiguities,"ambiguities",12,160);
            if(confidence==null||confidence.compareTo(BigDecimal.ZERO)<0||confidence.compareTo(BigDecimal.ONE)>0)
                throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
    }

    public record ValidatedImage(byte[] bytes,String mimeType,String originalFilename,long sizeBytes,
            int width,int height,String sha256) {
        public ValidatedImage { bytes=bytes.clone(); }
        @Override public byte[] bytes(){return bytes.clone();}
    }

    public record StoredVisionObservation(UUID observationId,UUID requestId,UUID threadId,
            UUID sourceMessageId,String mimeType,String originalFilename,long sizeBytes,int width,
            int height,String imageSha256,VisionObservation observation,String observationHash,
            String provider,String model) {}

    public record VisionObservationView(String category,String productType,String brandCandidate,
            String modelCandidate,List<String> colors,List<String> materials,
            List<String> styleDescriptors,List<String> visibleText,BigDecimal confidence,
            List<String> ambiguities,String mimeType,String originalFilename,long sizeBytes,
            int width,int height,String observationHash,String provider,String model) {}

    private static String required(String value,String field,int maximum){String normalized=optional(value,field,maximum);
        if(normalized==null)throw new IllegalArgumentException(field+" is required");return normalized;}
    private static String optional(String value,String field,int maximum){if(value==null)return null;String normalized=value.strip();
        if(normalized.isEmpty())return null;if(normalized.length()>maximum)throw new IllegalArgumentException(field+" is too long");return normalized;}
    private static List<String> bounded(List<String> values,String field,int maximumItems,int maximumLength){
        if(values==null)return List.of();if(values.size()>maximumItems)throw new IllegalArgumentException(field+" has too many values");
        return values.stream().map(value->required(value,field,maximumLength)).distinct().toList();}
}
