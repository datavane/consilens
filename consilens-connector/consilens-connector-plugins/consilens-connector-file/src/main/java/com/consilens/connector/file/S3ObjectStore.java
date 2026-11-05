package com.consilens.connector.file;

import java.nio.file.Path;
import java.util.List;

/**
 * Minimal object download surface shared by file and Iceberg connectors.
 * Implementations must be safe to close without a prior download.
 */
public interface S3ObjectStore extends AutoCloseable {

    /**
     * Downloads one {@code s3://} or {@code s3a://} object to a local file,
     * creating parent directories.
     */
    void download(String location, Path target);

    /**
     * Lists object locations under an {@code s3://bucket/prefix} location.
     */
    List<String> list(String location);

    static S3ObjectStore create(com.consilens.connector.api.model.ResourceLocator resource) {
        return new LazyAwsS3ObjectStore(resource);
    }

    @Override
    default void close() {
    }
}
