package com.consilens.connector.file;

import java.io.InputStream;
import java.util.List;

/**
 * Minimal S3 operations used by the file connector. Kept as an interface so the
 * downloader can be tested without network access.
 */
interface S3Gateway extends AutoCloseable {

    List<String> list(String bucket, String prefix);

    InputStream get(String bucket, String key);

    @Override
    default void close() {
    }
}
