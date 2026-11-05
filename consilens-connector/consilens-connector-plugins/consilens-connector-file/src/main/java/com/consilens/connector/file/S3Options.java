package com.consilens.connector.file;

import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.model.ResourceLocator;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * S3 client options parsed from {@code resource.options}:
 *
 * <pre>
 *   s3Region: eu-central-1
 *   s3Endpoint: http://127.0.0.1:9000   # MinIO / local S3
 *   s3AccessKey: ...
 *   s3SecretKey: ...
 *   s3PathStyle: true
 * </pre>
 *
 * <p>Region falls back to {@code AWS_REGION}, then {@code us-east-1}.
 * Credentials fall back to the AWS default provider chain.
 */
final class S3Options {

    /** 单个对象上限（2 GiB）：一个异常的超大对象不该把磁盘写满。 */
    static final long DEFAULT_MAX_OBJECT_BYTES = 2L * 1024 * 1024 * 1024;
    /** 单次下载总量上限（8 GiB）。 */
    static final long DEFAULT_MAX_TOTAL_BYTES = 8L * 1024 * 1024 * 1024;

    private final String region;
    private final String endpoint;
    private final String accessKey;
    private final String secretKey;
    private final boolean pathStyle;

    private S3Options(String region, String endpoint, String accessKey,
                      String secretKey, boolean pathStyle) {
        this.region = region;
        this.endpoint = endpoint;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.pathStyle = pathStyle;
    }

    static S3Options from(ResourceLocator resource) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (resource != null && resource.getOptions() != null) {
            merged.putAll(resource.getOptions());
        }
        String region = text(merged.get("s3Region"));
        if (region == null) {
            region = text(System.getenv("AWS_REGION"));
        }
        if (region == null) {
            region = "us-east-1";
        }
        String accessKey = text(merged.get("s3AccessKey"));
        String secretKey = text(merged.get("s3SecretKey"));
        if ((accessKey == null) != (secretKey == null)) {
            throw new ConnectorException("s3AccessKey and s3SecretKey must be configured together");
        }
        return new S3Options(region, text(merged.get("s3Endpoint")), accessKey, secretKey,
                bool(merged.get("s3PathStyle")));
    }

    static long maxObjectBytes(ResourceLocator resource) {
        return limit(resource, "s3MaxObjectBytes", DEFAULT_MAX_OBJECT_BYTES);
    }

    static long maxTotalBytes(ResourceLocator resource) {
        return limit(resource, "s3MaxTotalBytes", DEFAULT_MAX_TOTAL_BYTES);
    }

    private static long limit(ResourceLocator resource, String key, long fallback) {
        if (resource == null || resource.getOptions() == null) {
            return fallback;
        }
        Object raw = resource.getOptions().get(key);
        if (raw == null) {
            return fallback;
        }
        long value;
        if (raw instanceof Number) {
            value = ((Number) raw).longValue();
        } else {
            try {
                value = Long.parseLong(String.valueOf(raw).trim());
            } catch (NumberFormatException exception) {
                throw new ConnectorException(key + " must be a number of bytes, got: " + raw);
            }
        }
        if (value <= 0) {
            throw new ConnectorException(key + " must be positive, got: " + value);
        }
        return value;
    }

    S3Client buildClient() {
        S3ClientBuilder builder = S3Client.builder().region(Region.of(region));
        if (endpoint != null) {
            builder.endpointOverride(URI.create(endpoint));
        }
        if (pathStyle) {
            builder.forcePathStyle(true);
        }
        if (accessKey != null) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey)));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }
        return builder.build();
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static boolean bool(Object value) {
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return "true".equals(text) || "1".equals(text) || "yes".equals(text);
    }
}
