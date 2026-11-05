package com.consilens.connector.file;

import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.model.ResourceLocator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * AWS SDK backed store; the client is built on first download so purely local
 * tables never require S3 credentials or the SDK.
 */
final class LazyAwsS3ObjectStore implements S3ObjectStore {

    private final ResourceLocator resource;
    private S3Gateway gateway;

    LazyAwsS3ObjectStore(ResourceLocator resource) {
        this.resource = resource;
    }

    @Override
    public synchronized void download(String location, Path target) {
        S3Location parsed = S3Location.parse(location);
        try {
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (InputStream input = gateway().get(parsed.bucket(), parsed.key())) {
                if (input == null) {
                    throw new ConnectorException("S3 returned no content for " + parsed.display());
                }
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new ConnectorException("Failed to download " + parsed.display() + ": "
                    + exception.getMessage(), exception);
        }
    }

    @Override
    public synchronized java.util.List<String> list(String location) {
        S3Location parsed = S3Location.parse(location);
        java.util.List<String> keys = gateway().list(parsed.bucket(), parsed.key());
        java.util.List<String> result = new java.util.ArrayList<>(keys.size());
        for (String key : keys) {
            result.add("s3://" + parsed.bucket() + "/" + key);
        }
        return result;
    }

    private S3Gateway gateway() {
        if (gateway == null) {
            gateway = AwsS3Gateway.from(resource);
        }
        return gateway;
    }

    @Override
    public synchronized void close() {
        if (gateway != null) {
            gateway.close();
            gateway = null;
        }
    }
}
