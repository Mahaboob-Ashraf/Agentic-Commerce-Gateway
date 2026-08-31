package dev.agenticcommerce.gateway.catalogue;

import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class CatalogueProviderConfiguration {
    @Bean
    @ConditionalOnMissingBean(CatalogueProvider.class)
    CatalogueProvider unavailableCatalogueProvider() {
        return barcode -> Optional.empty();
    }

    @Bean
    @ConditionalOnMissingBean(EmbeddingProvider.class)
    EmbeddingProvider unavailableEmbeddingProvider() {
        return input -> { throw new IllegalStateException("EMBEDDING_PROVIDER_UNAVAILABLE"); };
    }
}
