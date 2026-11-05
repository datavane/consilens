package com.consilens.cluster.application;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Opens descriptors that have been localized by YARN, mounted into a container,
 * or published through an HTTP(S) endpoint. Object-store and HDFS transport are
 * deliberately not guessed here; they need deployment-specific clients.
 */
class UriComparisonDescriptorOpener implements ComparisonDescriptorOpener {

    @Override
    public ComparisonDescriptor open(String reference) throws IOException {
        if (reference == null || reference.trim().isEmpty()) {
            throw new IllegalArgumentException("comparison descriptor is required");
        }
        URI uri = toUri(reference);
        String scheme = uri.getScheme();
        if (scheme == null) {
            Path path = Path.of(reference);
            return descriptor(Files.newInputStream(path), path.getFileName().toString());
        }
        if ("file".equalsIgnoreCase(scheme)) {
            Path path = Path.of(uri);
            return descriptor(Files.newInputStream(path), path.getFileName().toString());
        }
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            URLConnection connection = uri.toURL().openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(30_000);
            return descriptor(connection.getInputStream(), Path.of(uri.getPath()).getFileName().toString());
        }
        throw new IllegalArgumentException("Unsupported comparison descriptor URI scheme: " + scheme);
    }

    private URI toUri(String reference) {
        try {
            return new URI(reference);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid comparison descriptor URI", e);
        }
    }

    private ComparisonDescriptor descriptor(InputStream inputStream, String fileName) {
        return new ComparisonDescriptor() {
            @Override
            public InputStream open() {
                return inputStream;
            }

            @Override
            public String format() {
                String lowerCaseName = fileName.toLowerCase(java.util.Locale.ROOT);
                if (lowerCaseName.endsWith(".yaml") || lowerCaseName.endsWith(".yml")) {
                    return "yaml";
                }
                if (lowerCaseName.endsWith(".json")) {
                    return "json";
                }
                throw new IllegalArgumentException("Unsupported comparison descriptor format: " + fileName);
            }
        };
    }
}
