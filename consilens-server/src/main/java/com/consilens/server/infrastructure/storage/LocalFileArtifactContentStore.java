package com.consilens.server.infrastructure.storage;

import com.consilens.common.io.PathSupport;
import com.consilens.server.boot.ConsilensServerProperties;
import com.consilens.server.domain.enums.ArtifactKind;
import com.consilens.server.support.hash.Sha256Support;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@Component
public class LocalFileArtifactContentStore implements ArtifactContentStore {

    private final ConsilensServerProperties properties;

    public LocalFileArtifactContentStore(ConsilensServerProperties properties) {
        this.properties = properties;
    }

    @Override
    public StoredArtifactContent write(String artifactId, ArtifactKind artifactKind, String format, byte[] content) {
        try {
            validateContentSize(content.length);
            String safeArtifactId = safeArtifactId(artifactId);
            Path baseDir = baseDir();
            PathSupport.ensureDirectory(baseDir);
            Path file = baseDir.resolve(safeArtifactId + extension(format)).normalize();
            if (!file.startsWith(baseDir)) {
                throw new IllegalArgumentException("Artifact path escapes local storage directory");
            }
            Files.write(file, content, StandardOpenOption.CREATE_NEW);
            return StoredArtifactContent.builder()
                    .storageType("LOCAL_FILE")
                    .storageUri(file.toString())
                    .sha256(Sha256Support.hex(content))
                    .build();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to persist artifact content for " + artifactId, exception);
        }
    }

    @Override
    public byte[] read(String storageUri) {
        try {
            Path baseDir = baseDir().toRealPath();
            Path file = Path.of(storageUri).toAbsolutePath().normalize().toRealPath();
            if (!file.startsWith(baseDir)) {
                throw new IllegalArgumentException("Artifact path escapes local storage directory");
            }
            validateContentSize(Files.size(file));
            return Files.readAllBytes(file);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read artifact content from " + storageUri, exception);
        }
    }

    @Override
    public long size(String storageUri) {
        try {
            Path baseDir = baseDir().toRealPath();
            Path file = Path.of(storageUri).toAbsolutePath().normalize().toRealPath();
            if (!file.startsWith(baseDir)) {
                throw new IllegalArgumentException("Artifact path escapes local storage directory");
            }
            return Files.size(file);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read artifact size from " + storageUri, exception);
        }
    }

    private Path baseDir() {
        return Path.of(properties.getArtifact().getLocalBaseDir()).toAbsolutePath().normalize();
    }

    private String safeArtifactId(String artifactId) {
        if (artifactId == null || !artifactId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Invalid artifact id");
        }
        return artifactId;
    }

    private String extension(String format) {
        if (format == null || format.isBlank()) {
            return ".txt";
        }
        String normalized = format.trim().toLowerCase();
        if (!normalized.matches("[a-z0-9_-]{1,16}")) {
            return ".bin";
        }
        return "." + normalized;
    }

    private void validateContentSize(long contentSize) {
        long maxContentBytes = properties.getArtifact().getMaxContentBytes();
        if (contentSize > maxContentBytes) {
            throw new IllegalArgumentException("Artifact content exceeds max size: " + contentSize
                    + " > " + maxContentBytes);
        }
    }
}
