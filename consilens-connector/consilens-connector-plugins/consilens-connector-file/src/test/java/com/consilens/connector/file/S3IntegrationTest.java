package com.consilens.connector.file;

import com.consilens.connector.api.model.ResourceLocator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the real AWS SDK path (signing, XML list parsing, GET) against a
 * tiny local S3-compatible HTTP server.
 */
class S3IntegrationTest {

    @Test
    void shouldListAndDownloadThroughAwsSdk() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handle(exchange));
        server.start();
        int port = server.getAddress().getPort();

        ResourceLocator resource = ResourceLocator.builder()
                .type("file")
                .path("s3://bucket/data/")
                .options(Map.of(
                        "format", "csv",
                        "s3Region", "us-east-1",
                        "s3Endpoint", "http://127.0.0.1:" + port,
                        "s3PathStyle", "true",
                        "s3AccessKey", "key",
                        "s3SecretKey", "secret"))
                .build();

        try {
            FileDatasetHandle handle = new FileDatasetHandle(resource, null);
            Path downloaded = handle.getPath();
            assertTrue(Files.exists(downloaded));
            assertEquals(2, handle.getSchema().getFields().size());
            int rows = 0;
            try (var iterator = handle.getRecordScanner().get().scan(null)) {
                while (iterator.hasNext()) {
                    iterator.next();
                    rows++;
                }
            }
            assertEquals(2, rows);
            handle.close();
            assertFalse(Files.exists(downloaded));
        } finally {
            server.stop(0);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();
        if ("/bucket".equals(path) && query != null && query.contains("list-type=2")) {
            byte[] body = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
                    + "<Name>bucket</Name><Prefix>data/</Prefix><KeyCount>1</KeyCount>"
                    + "<MaxKeys>1000</MaxKeys><IsTruncated>false</IsTruncated>"
                    + "<Contents><Key>data/users.csv</Key>"
                    + "<LastModified>2026-01-01T00:00:00.000Z</LastModified>"
                    + "<ETag>\"etag\"</ETag><Size>22</Size>"
                    + "<StorageClass>STANDARD</StorageClass></Contents>"
                    + "</ListBucketResult>").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/xml");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
            return;
        }
        if ("/bucket/data/users.csv".equals(path)) {
            byte[] body = "id,name\n1,alice\n2,bob\n".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/csv");
            exchange.getResponseHeaders().add("ETag", "\"etag\"");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(404, -1);
        exchange.close();
    }
}
