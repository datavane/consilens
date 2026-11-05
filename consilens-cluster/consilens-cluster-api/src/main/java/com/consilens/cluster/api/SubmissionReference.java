package com.consilens.cluster.api;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Normalizes a local path or remote URI supplied for a cluster artifact.
 */
public final class SubmissionReference {

    private SubmissionReference() {
    }

    public static boolean isLocal(URI uri) {
        String scheme = uri.getScheme();
        return scheme == null || "file".equalsIgnoreCase(scheme);
    }

    public static Path toLocalPath(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("artifact reference is required");
        }
        try {
            URI uri = URI.create(value);
            if (!isLocal(uri)) {
                throw new IllegalArgumentException("expected a local path or file URI: " + value);
            }
            Path path = uri.getScheme() == null ? Paths.get(value) : Paths.get(uri);
            if (!Files.isRegularFile(path)) {
                throw new IllegalArgumentException("local artifact is not a regular file: " + path);
            }
            return path.toAbsolutePath().normalize();
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("local artifact")) {
                throw e;
            }
            throw new IllegalArgumentException("invalid local artifact reference: " + value, e);
        }
    }
}
