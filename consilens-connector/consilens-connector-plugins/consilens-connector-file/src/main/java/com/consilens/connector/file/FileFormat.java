package com.consilens.connector.file;

import java.util.Locale;

/**
 * Supported local file formats for the file connector.
 */
public enum FileFormat {
    CSV,
    JSONL,
    PARQUET,
    ORC;

    public static FileFormat from(String value, String path) {
        String candidate = value;
        if (candidate == null || candidate.isBlank()) {
            candidate = extension(FileReaders.stripGzip(path));
        }
        if (candidate == null || candidate.isBlank()) {
            return CSV;
        }
        switch (candidate.trim().toLowerCase(Locale.ROOT)) {
            case "csv":
                return CSV;
            case "jsonl":
            case "ndjson":
                return JSONL;
            case "parquet":
                return PARQUET;
            case "orc":
                return ORC;
            default:
                throw new IllegalArgumentException("Unsupported file format: " + value
                        + " (supported: csv, jsonl, parquet, orc)");
        }
    }

    private static String extension(String path) {
        if (path == null) {
            return null;
        }
        int dot = path.lastIndexOf('.');
        return dot < 0 ? null : path.substring(dot + 1);
    }
}
