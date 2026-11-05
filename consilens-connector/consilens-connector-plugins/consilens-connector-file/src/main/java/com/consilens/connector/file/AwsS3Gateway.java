package com.consilens.connector.file;

import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.model.ResourceLocator;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * AWS SDK v2 backed gateway.
 */
final class AwsS3Gateway implements S3Gateway {

    private static final int MAX_OBJECTS = 10_000;

    private final S3Client client;

    AwsS3Gateway(S3Client client) {
        this.client = client;
    }

    static AwsS3Gateway from(ResourceLocator resource) {
        ensureSdkPresent();
        return new AwsS3Gateway(S3Options.from(resource).buildClient());
    }

    private static void ensureSdkPresent() {
        try {
            Class.forName("software.amazon.awssdk.services.s3.S3Client");
        } catch (ClassNotFoundException | LinkageError error) {
            throw new ConnectorException("S3 support requires software.amazon.awssdk:s3"
                    + " on the classpath", error);
        }
    }

    @Override
    public List<String> list(String bucket, String prefix) {
        List<String> keys = new ArrayList<>();
        String token = null;
        do {
            ListObjectsV2Request request = ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .prefix(prefix == null ? "" : prefix)
                    .continuationToken(token)
                    .build();
            ListObjectsV2Response response = client.listObjectsV2(request);
            if (response.contents() != null) {
                for (S3Object object : response.contents()) {
                    keys.add(object.key());
                    if (keys.size() > MAX_OBJECTS) {
                        throw new ConnectorException("S3 prefix contains more than "
                                + MAX_OBJECTS + " objects; narrow the prefix or glob");
                    }
                }
            }
            token = Boolean.TRUE.equals(response.isTruncated())
                    ? response.nextContinuationToken() : null;
        } while (token != null);
        return keys;
    }

    @Override
    public InputStream get(String bucket, String key) {
        return client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public void close() {
        client.close();
    }
}
