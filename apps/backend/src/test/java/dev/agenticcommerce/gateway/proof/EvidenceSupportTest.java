package dev.agenticcommerce.gateway.proof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvidenceSupportTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void discoversRepositoryFromRootAndBackendWorkingDirectories() throws Exception {
        Path repository = temporaryDirectory.resolve("Agentic-Commerce-Gateway");
        Files.createDirectories(repository.resolve("apps/backend"));
        Files.createDirectories(repository.resolve("apps/web"));
        Files.createDirectories(repository.resolve("proof"));
        Files.writeString(repository.resolve("package.json"), "{}");
        Files.writeString(repository.resolve("apps/backend/pom.xml"), "<project />");

        assertThat(EvidenceSupport.repositoryRoot(repository)).isEqualTo(repository);
        assertThat(EvidenceSupport.repositoryRoot(repository.resolve("apps/backend"))).isEqualTo(repository);
    }

    @Test
    void rejectsDirectoriesWithOnlyOneAccidentalMarker() throws Exception {
        Path notRepository = temporaryDirectory.resolve("not-a-repository");
        Files.createDirectories(notRepository.resolve("nested"));
        Files.writeString(notRepository.resolve("package.json"), "{}");

        assertThatThrownBy(() -> EvidenceSupport.repositoryRoot(notRepository.resolve("nested")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Repository root not found");
    }
}
