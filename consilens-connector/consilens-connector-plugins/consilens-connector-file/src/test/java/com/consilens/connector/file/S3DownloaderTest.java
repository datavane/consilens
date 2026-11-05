package com.consilens.connector.file;

import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.model.ResourceLocator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3DownloaderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldDownloadSingleObject() throws Exception {
        FakeGateway gateway = new FakeGateway()
                .put("data/users.csv", "id,name\n1,alice\n");

        List<Path> files = new S3Downloader(gateway)
                .download("s3://bucket/data/users.csv", tempDir);

        assertEquals(1, files.size());
        assertEquals("id,name\n1,alice\n", Files.readString(files.get(0)));
    }

    @Test
    void shouldDownloadPrefixDataFilesOnly() throws Exception {
        FakeGateway gateway = new FakeGateway()
                .put("data/a.csv", "id\n1\n")
                .put("data/b.jsonl", "{\"id\":1}\n")
                .put("data/notes.txt", "ignore")
                .put("other/c.csv", "id\n3\n");

        List<Path> files = new S3Downloader(gateway).download("s3://bucket/data/", tempDir);

        assertEquals(2, files.size());
        assertTrue(files.stream().allMatch(path -> {
            String name = path.getFileName().toString();
            return name.endsWith(".csv") || name.endsWith(".jsonl");
        }));
    }

    @Test
    void shouldApplyGlob() throws Exception {
        FakeGateway gateway = new FakeGateway()
                .put("data/part-01.parquet", "p1")
                .put("data/part-02.parquet", "p2")
                .put("data/part-03.csv", "id\n3\n");

        List<Path> files = new S3Downloader(gateway)
                .download("s3://bucket/data/*.parquet", tempDir);

        assertEquals(2, files.size());
    }

    @Test
    void shouldRejectEmptyMatch() {
        FakeGateway gateway = new FakeGateway().put("data/a.csv", "id\n1\n");

        assertThrows(ConnectorException.class, () -> new S3Downloader(gateway)
                .download("s3://bucket/missing/", tempDir));
    }

    @Test
    void shouldScanDownloadedObjectAndCleanUp() throws Exception {
        FakeGateway gateway = new FakeGateway()
                .put("data/users.csv", "id,name\n1,alice\n2,bob\n");
        ResourceLocator resource = ResourceLocator.builder()
                .type("file")
                .path("s3://bucket/data/users.csv")
                .options(Map.of("format", "csv"))
                .build();
        FileDatasetHandle handle = new FileDatasetHandle(resource, null, gateway);
        Path downloaded = handle.getPath();

        assertTrue(Files.exists(downloaded));
        assertEquals(2, handle.getSchema().getFields().size());
        int rows = 0;
        try (var iterator = handle.getRecordScanner().get().scan(null)) {
            while (iterator.hasNext()) {
                String name = iterator.next().getValues().get("name").getValue().toString();
                assertTrue("alice".equals(name) || "bob".equals(name));
                rows++;
            }
        }
        assertEquals(2, rows);

        handle.close();
        assertFalse(Files.exists(downloaded));
    }

    @Test
    void shouldParseS3Locations() {
        S3Location location = S3Location.parse("s3a://bucket/dir/file.parquet");
        assertEquals("bucket", location.bucket());
        assertEquals("dir/file.parquet", location.key());
        assertTrue(location.isSingleObject());
        assertFalse(S3Location.parse("s3://bucket/dir/*.parquet").isSingleObject());
    }

    @Test
    void shouldBuildAwsS3ClientWithExplicitOptions() {
        S3Options options = S3Options.from(ResourceLocator.builder()
                .type("file")
                .options(Map.of(
                        "s3Region", "us-east-1",
                        "s3Endpoint", "http://127.0.0.1:1",
                        "s3AccessKey", "key",
                        "s3SecretKey", "secret",
                        "s3PathStyle", "true"))
                .build());

        try (software.amazon.awssdk.services.s3.S3Client client = options.buildClient()) {
            assertTrue(client != null);
        }
    }

    private static final class FakeGateway implements S3Gateway {

        private final Map<String, byte[]> objects = new LinkedHashMap<>();

        private FakeGateway put(String key, String content) {
            objects.put(key, content.getBytes(StandardCharsets.UTF_8));
            return this;
        }

        @Override
        public List<String> list(String bucket, String prefix) {
            List<String> keys = new ArrayList<>();
            for (String key : objects.keySet()) {
                if (key.startsWith(prefix)) {
                    keys.add(key);
                }
            }
            return keys;
        }

        @Override
        public InputStream get(String bucket, String key) {
            byte[] content = objects.get(key);
            if (content == null) {
                throw new ConnectorException("Missing key: " + key);
            }
            return new ByteArrayInputStream(content);
        }
    }

    @Test
    void shouldRejectObjectsLargerThanTheConfiguredCap() {
        FakeGateway gateway = new FakeGateway()
                .put("data/big.csv", "id,name\n1,alice\n2,bob\n");

        ConnectorException failure = assertThrows(ConnectorException.class,
                () -> new S3Downloader(gateway, 8L, 0L).download("s3://bucket/data/big.csv", tempDir));

        assertTrue(failure.getMessage().contains("s3MaxObjectBytes"));
        // 半成品不能留下：否则后续扫描会把它当成完整文件读
        assertTrue(Files.notExists(tempDir.resolve("0-big.csv")));
    }

    @Test
    void shouldRejectDownloadsAboveTheTotalCap() {
        FakeGateway gateway = new FakeGateway()
                .put("data/a.csv", "id\n1\n")
                .put("data/b.csv", "id\n2\n");

        ConnectorException failure = assertThrows(ConnectorException.class,
                () -> new S3Downloader(gateway, 0L, 8L).download("s3://bucket/data/", tempDir));

        assertTrue(failure.getMessage().contains("s3MaxTotalBytes"));
    }
}
