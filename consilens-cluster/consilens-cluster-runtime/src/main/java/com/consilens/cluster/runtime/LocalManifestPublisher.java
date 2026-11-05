package com.consilens.cluster.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Writes the final manifest through a same-directory temporary file.
 */
public class LocalManifestPublisher {

    private static final String MANIFEST_FILE_NAME = "manifest.json";

    private final ObjectMapper objectMapper;

    public LocalManifestPublisher() {
        this(new ObjectMapper());
    }

    LocalManifestPublisher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Path publish(Path manifestDirectory, LocalExecutionManifest manifest) throws IOException {
        if (manifestDirectory == null) {
            throw new IllegalArgumentException("manifestDirectory must not be null");
        }
        Files.createDirectories(manifestDirectory);
        Path target = manifestDirectory.resolve(MANIFEST_FILE_NAME);
        Path temporary = Files.createTempFile(manifestDirectory, "manifest-", ".tmp");
        try {
            objectMapper.writeValue(temporary.toFile(), manifest);
            moveIntoPlace(temporary, target);
            return target;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void moveIntoPlace(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
