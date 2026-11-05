package com.consilens.server.infrastructure.storage;

import com.consilens.server.boot.ConsilensServerProperties;
import com.consilens.server.domain.enums.ArtifactKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFileArtifactContentStoreTest {

    @TempDir
    private Path tempDir;

    @Test
    void shouldSanitizeUnsafeArtifactFormatExtension() {
        ConsilensServerProperties properties = new ConsilensServerProperties();
        properties.getArtifact().setLocalBaseDir(tempDir.toString());
        LocalFileArtifactContentStore store = new LocalFileArtifactContentStore(properties);

        StoredArtifactContent content = store.write("artifact-test",
                ArtifactKind.RUN_RESULT,
                "../json",
                "{}".getBytes());

        Path storagePath = Path.of(content.getStorageUri());
        assertThat(storagePath.getParent()).isEqualTo(tempDir.toAbsolutePath());
        assertThat(storagePath.getFileName().toString()).isEqualTo("artifact-test.bin");
    }

    @Test
    void shouldRejectUnsafeArtifactId() {
        LocalFileArtifactContentStore store = store();

        assertThatThrownBy(() -> store.write("../artifact-test",
                ArtifactKind.RUN_RESULT,
                "json",
                "{}".getBytes()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to persist artifact content");
    }

    @Test
    void shouldNotOverwriteExistingArtifactFile() {
        LocalFileArtifactContentStore store = store();
        store.write("artifact-test", ArtifactKind.RUN_RESULT, "json", "first".getBytes());

        assertThatThrownBy(() -> store.write("artifact-test", ArtifactKind.RUN_RESULT, "json", "second".getBytes()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to persist artifact content");
    }

    @Test
    void shouldRejectOversizedArtifactContentOnWrite() {
        ConsilensServerProperties properties = properties();
        properties.getArtifact().setMaxContentBytes(4L);
        LocalFileArtifactContentStore store = new LocalFileArtifactContentStore(properties);

        assertThatThrownBy(() -> store.write("artifact-large", ArtifactKind.RUN_RESULT, "json", "large".getBytes()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to persist artifact content");
    }

    @Test
    void shouldRejectReadOutsideLocalStorageDirectory() throws Exception {
        LocalFileArtifactContentStore store = store();
        Path outside = Files.createTempFile("consilens-outside-", ".txt");

        assertThatThrownBy(() -> store.read(outside.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to read artifact content");
    }

    @Test
    void shouldReadStoredArtifactContent() {
        LocalFileArtifactContentStore store = store();
        StoredArtifactContent content = store.write("artifact-readable",
                ArtifactKind.RUN_RESULT,
                "json",
                "{\"ok\":true}".getBytes());

        assertThat(store.read(content.getStorageUri())).isEqualTo("{\"ok\":true}".getBytes());
    }

    @Test
    void shouldRejectOversizedArtifactContentOnRead() throws Exception {
        ConsilensServerProperties properties = properties();
        properties.getArtifact().setMaxContentBytes(4L);
        LocalFileArtifactContentStore store = new LocalFileArtifactContentStore(properties);
        Path storagePath = tempDir.resolve("artifact-large.json");
        Files.write(storagePath, "large".getBytes());

        assertThatThrownBy(() -> store.read(storagePath.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to read artifact content");
    }

    private LocalFileArtifactContentStore store() {
        return new LocalFileArtifactContentStore(properties());
    }

    private ConsilensServerProperties properties() {
        ConsilensServerProperties properties = new ConsilensServerProperties();
        properties.getArtifact().setLocalBaseDir(tempDir.toString());
        return properties;
    }
}
