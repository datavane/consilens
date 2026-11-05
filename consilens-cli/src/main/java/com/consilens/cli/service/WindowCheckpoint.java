package com.consilens.cli.service;

import com.consilens.common.io.PathSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Small watermark file used by incremental runs.
 *
 * <p>The file contains the exclusive upper bound ({@code to}) of the last
 * successful run. Writes are atomic so a crashed run cannot advance the
 * watermark halfway.
 */
public final class WindowCheckpoint {

    private WindowCheckpoint() {
    }

    public static String read(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            String value = Files.readString(file).trim();
            return value.isEmpty() ? null : value;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Failed to read checkpoint " + file + ": "
                    + exception.getMessage(), exception);
        }
    }

    public static void write(Path file, String watermark) {
        if (file == null) {
            return;
        }
        if (watermark == null || watermark.isBlank()) {
            throw new IllegalArgumentException("checkpoint watermark must not be blank");
        }
        try {
            Path absolute = file.toAbsolutePath();
            PathSupport.ensureParent(absolute);
            Path parent = absolute.getParent();
            Path temp = Files.createTempFile(parent, "consilens-checkpoint-", ".tmp");
            Files.writeString(temp, watermark.trim() + System.lineSeparator());
            try {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Failed to write checkpoint " + file + ": "
                    + exception.getMessage(), exception);
        }
    }
}
