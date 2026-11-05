package com.consilens.common.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Filesystem helpers that tolerate symlinked directories.
 *
 * <p>{@link Files#createDirectories(Path)} throws
 * {@code FileAlreadyExistsException} when the target is an existing symlink to
 * a directory (for example {@code /tmp} on macOS). Checking
 * {@link Files#isDirectory(Path)} first (which follows links) avoids that.
 */
public final class PathSupport {

    private PathSupport() {
    }

    /**
     * Creates the directory (and missing parents) unless it already exists.
     */
    public static void ensureDirectory(Path directory) throws IOException {
        if (directory != null && !Files.isDirectory(directory)) {
            Files.createDirectories(directory);
        }
    }

    /**
     * Creates the parent of a file path unless it already exists.
     */
    public static void ensureParent(Path file) throws IOException {
        if (file != null) {
            ensureDirectory(file.toAbsolutePath().getParent());
        }
    }
}
