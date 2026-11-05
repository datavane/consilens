package com.consilens.connector.file;

import com.consilens.connector.api.ConnectorException;

import java.util.Locale;

/**
 * Parsed S3 object location.
 */
final class S3Location {

    private final String bucket;
    private final String key;

    private S3Location(String bucket, String key) {
        this.bucket = bucket;
        this.key = key;
    }

    static boolean isS3(String location) {
        if (location == null) {
            return false;
        }
        String lower = location.toLowerCase(Locale.ROOT);
        return lower.startsWith("s3://") || lower.startsWith("s3a://");
    }

    static S3Location parse(String location) {
        if (!isS3(location)) {
            throw new ConnectorException("Not an S3 location: " + location);
        }
        int scheme = location.indexOf("://");
        String remainder = location.substring(scheme + 3);
        int slash = remainder.indexOf('/');
        String bucket = slash < 0 ? remainder : remainder.substring(0, slash);
        String key = slash < 0 ? "" : remainder.substring(slash + 1);
        if (bucket.isBlank()) {
            throw new ConnectorException("S3 location requires a bucket: " + location);
        }
        return new S3Location(bucket, key);
    }

    String bucket() {
        return bucket;
    }

    String key() {
        return key;
    }

    String display() {
        return "s3://" + bucket + (key.isEmpty() ? "" : "/" + key);
    }

    boolean isSingleObject() {
        return !key.isEmpty() && !key.endsWith("/")
                && key.indexOf('*') < 0 && key.indexOf('?') < 0
                && key.indexOf('[') < 0 && key.indexOf('{') < 0;
    }
}
