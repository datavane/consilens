package com.consilens.server.application.artifact;

import com.consilens.server.api.dto.DiffPageDto;
import com.consilens.server.domain.enums.ArtifactKind;
import com.consilens.server.domain.model.ArtifactRecord;
import com.consilens.server.domain.repository.ArtifactRepository;
import com.consilens.server.infrastructure.storage.ArtifactContentStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArtifactQueryServiceImplTest {

    private final ArtifactRepository repository = mock(ArtifactRepository.class);
    private final ArtifactContentStore contentStore = mock(ArtifactContentStore.class);

    private ArtifactQueryServiceImpl service() {
        return new ArtifactQueryServiceImpl(repository, contentStore, new ObjectMapper());
    }

    @Test
    void shouldPageThroughJsonlDifferences() throws Exception {
        Path file = Files.createTempFile("diff-test", ".jsonl");
        Files.write(file, List.of(
                "{\"operation\":\"MISMATCH\",\"primaryKey\":[\"1\"],\"metadata\":{}}",
                "{\"operation\":\"SOURCE_MISSING\",\"primaryKey\":[\"2\"],\"metadata\":{}}",
                "{\"operation\":\"TARGET_MISSING\",\"primaryKey\":[\"3\"],\"metadata\":{}}"), StandardCharsets.UTF_8);
        when(repository.findById("a-1")).thenReturn(Optional.of(ArtifactRecord.builder()
                .id("a-1")
                .artifactType(ArtifactKind.RUN_RESULT)
                .artifactFormat("ndjson")
                .differencesUri(file.toString())
                .differenceRows(3L)
                .differenceTruncated(false)
                .build()));

        DiffPageDto page = service().listDifferences("a-1", 1, 2, null, "trace");

        assertEquals(3, page.getTotal());
        assertEquals(2, page.getRows());
        assertEquals(false, page.isHasMore());
        assertEquals("SOURCE_MISSING", page.getItems().get(0).get("operation"));
        assertEquals(List.of("2"), page.getItems().get(0).get("primaryKey"));
        Files.deleteIfExists(file);
    }

    @Test
    void shouldTrustDifferenceRowsAndStopAfterOnePage() throws Exception {
        Path file = Files.createTempFile("diff-big", ".jsonl");
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (int index = 0; index < 200; index++) {
            lines.add("{\"operation\":\"MISMATCH\",\"primaryKey\":[\"" + index + "\"],\"metadata\":{}}");
        }
        Files.write(file, lines, StandardCharsets.UTF_8);
        when(repository.findById("a-big")).thenReturn(Optional.of(ArtifactRecord.builder()
                .id("a-big")
                .artifactType(ArtifactKind.RUN_RESULT)
                .artifactFormat("ndjson")
                .differencesUri(file.toString())
                .differenceRows(200L)
                .differenceTruncated(false)
                .build()));

        DiffPageDto page = service().listDifferences("a-big", 0, 5, null, "trace");

        // 总数取工件元数据，取满一页即止；hasMore 由总数推出，不再依赖"多读一行"
        assertEquals(200, page.getTotal());
        assertEquals(5, page.getRows());
        assertEquals(true, page.isHasMore());
        assertEquals(List.of("0"), page.getItems().get(0).get("primaryKey"));
        Files.deleteIfExists(file);
    }

    @Test
    void shouldNotParseLinesBeyondTheRequestedPage() throws Exception {
        // 第 6 行是坏数据：只要实现真的"取满一页就停"，本页就不会碰到它
        Path file = Files.createTempFile("diff-lazy", ".jsonl");
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (int index = 0; index < 5; index++) {
            lines.add("{\"operation\":\"MISMATCH\",\"primaryKey\":[\"" + index + "\"],\"metadata\":{}}");
        }
        lines.add("{not-json");
        Files.write(file, lines, StandardCharsets.UTF_8);
        when(repository.findById("a-lazy")).thenReturn(Optional.of(ArtifactRecord.builder()
                .id("a-lazy")
                .artifactType(ArtifactKind.RUN_RESULT)
                .artifactFormat("ndjson")
                .differencesUri(file.toString())
                .differenceRows(5L)
                .differenceTruncated(false)
                .build()));

        DiffPageDto page = service().listDifferences("a-lazy", 0, 5, null, "trace");

        assertEquals(5, page.getRows());
        assertEquals(5, page.getTotal());
        assertEquals(false, page.isHasMore());
        Files.deleteIfExists(file);
    }

    @Test
    void shouldFallBackToScannedTotalWhenMetadataOverstates() throws Exception {
        Path file = Files.createTempFile("diff-short", ".jsonl");
        Files.write(file, List.of(
                "{\"operation\":\"MISMATCH\",\"primaryKey\":[\"1\"],\"metadata\":{}}",
                "{\"operation\":\"MISMATCH\",\"primaryKey\":[\"2\"],\"metadata\":{}}"), StandardCharsets.UTF_8);
        when(repository.findById("a-short")).thenReturn(Optional.of(ArtifactRecord.builder()
                .id("a-short")
                .artifactType(ArtifactKind.RUN_RESULT)
                .artifactFormat("ndjson")
                .differencesUri(file.toString())
                .differenceRows(50L)
                .differenceTruncated(false)
                .build()));

        DiffPageDto page = service().listDifferences("a-short", 0, 10, null, "trace");

        // 读到文件末尾就以实际行数为准，不能相信元数据里的 50
        assertEquals(2, page.getTotal());
        assertEquals(false, page.isHasMore());
        Files.deleteIfExists(file);
    }

    @Test
    void shouldFilterJsonlDifferencesByOperation() throws Exception {
        Path file = Files.createTempFile("diff-filter", ".jsonl");
        Files.write(file, List.of(
                "{\"operation\":\"mismatch\",\"primaryKey\":[\"1\"],\"metadata\":{}}",
                "{\"operation\":\"source_missing\",\"primaryKey\":[\"2\"],\"metadata\":{}}",
                "{\"operation\":\"mismatch\",\"primaryKey\":[\"3\"],\"metadata\":{}}",
                "{\"operation\":\"target_missing\",\"primaryKey\":[\"4\"],\"metadata\":{}}"), StandardCharsets.UTF_8);
        when(repository.findById("a-filter")).thenReturn(Optional.of(ArtifactRecord.builder()
                .id("a-filter")
                .artifactType(ArtifactKind.RUN_RESULT)
                .artifactFormat("ndjson")
                .differencesUri(file.toString())
                .differenceRows(4L)
                .differenceTruncated(false)
                .build()));

        // 只取 mismatch：total 应为过滤后数量，offset/limit 作用于过滤结果
        DiffPageDto page = service().listDifferences("a-filter", 0, 10, "mismatch", "trace");

        assertEquals(2, page.getTotal());
        assertEquals(2, page.getRows());
        assertEquals(false, page.isHasMore());
        assertEquals("mismatch", page.getItems().get(0).get("operation"));
        assertEquals("mismatch", page.getItems().get(1).get("operation"));
        assertEquals(List.of("1"), page.getItems().get(0).get("primaryKey"));

        // 过滤 + 分页：跳过第一条 mismatch
        DiffPageDto page2 = service().listDifferences("a-filter", 1, 10, "mismatch", "trace");
        assertEquals(2, page2.getTotal());
        assertEquals(1, page2.getRows());
        assertEquals(List.of("3"), page2.getItems().get(0).get("primaryKey"));

        // 过滤 source_missing：只有 1 条
        DiffPageDto page3 = service().listDifferences("a-filter", 0, 10, "source_missing", "trace");
        assertEquals(1, page3.getTotal());
        assertEquals(List.of("2"), page3.getItems().get(0).get("primaryKey"));

        // 多选：mismatch + target_missing 共 3 条
        DiffPageDto page4 = service().listDifferences("a-filter", 0, 10, "mismatch,target_missing", "trace");
        assertEquals(3, page4.getTotal());
        assertEquals(3, page4.getRows());

        Files.deleteIfExists(file);
    }

    @Test
    void shouldReadLegacyJsonDifferences() throws Exception {
        String content = "{\"hasDifferences\":true,\"differenceCount\":2,"
                + "\"differences\":[{\"operation\":\"MISMATCH\",\"primaryKey\":[\"1\"]},"
                + "{\"operation\":\"MISMATCH\",\"primaryKey\":[\"2\"]}]}";
        when(repository.findById("a-old")).thenReturn(Optional.of(ArtifactRecord.builder()
                .id("a-old")
                .artifactType(ArtifactKind.RUN_RESULT)
                .artifactFormat("json")
                .storageUri("storage://a-old")
                .build()));
        when(contentStore.read("storage://a-old")).thenReturn(content.getBytes(StandardCharsets.UTF_8));

        DiffPageDto page = service().listDifferences("a-old", 0, 1, null, "trace");

        assertEquals(2, page.getTotal());
        assertEquals(1, page.getRows());
        assertTrue(page.isHasMore());
        assertEquals("MISMATCH", page.getItems().get(0).get("operation"));
    }

    @Test
    void shouldReportTruncatedFlagFromJsonlMeta() throws Exception {
        Path file = Files.createTempFile("diff-trunc", ".jsonl");
        Files.write(file, List.of("{\"operation\":\"MISMATCH\",\"primaryKey\":[\"1\"],\"metadata\":{}}"),
                StandardCharsets.UTF_8);
        when(repository.findById("a-t")).thenReturn(Optional.of(ArtifactRecord.builder()
                .id("a-t")
                .artifactType(ArtifactKind.RUN_RESULT)
                .artifactFormat("ndjson")
                .differencesUri(file.toString())
                .differenceRows(10000L)
                .differenceTruncated(true)
                .build()));

        DiffPageDto page = service().listDifferences("a-t", 0, 10, null, "trace");

        assertTrue(page.isTruncated());
        // total 为文件实际行数（不再信任 DB 中的旧截断计数）
        assertEquals(1, page.getTotal());
        Files.deleteIfExists(file);
    }

    @Test
    void shouldDegradeToEmptyPageWhenDiffFileMissing() {
        when(repository.findById("a-missing")).thenReturn(Optional.of(ArtifactRecord.builder()
                .id("a-missing")
                .artifactType(ArtifactKind.RUN_RESULT)
                .artifactFormat("json")
                .differencesUri("/nonexistent/run-result-missing.jsonl")
                .differenceRows(7L)
                .differenceTruncated(true)
                .build()));

        DiffPageDto page = service().listDifferences("a-missing", 0, 10, null, "trace");

        assertEquals(7, page.getTotal());
        assertEquals(0, page.getRows());
        assertEquals(false, page.isHasMore());
        assertTrue(page.getItems().isEmpty());
    }

    @Test
    void shouldNotOverflowHasMoreWhenOffsetHuge() throws Exception {
        Path file = Files.createTempFile("diff-huge", ".jsonl");
        Files.write(file, List.of("{\"operation\":\"MISMATCH\",\"primaryKey\":[\"1\"],\"metadata\":{}}"),
                StandardCharsets.UTF_8);
        when(repository.findById("a-huge")).thenReturn(Optional.of(ArtifactRecord.builder()
                .id("a-huge")
                .artifactType(ArtifactKind.RUN_RESULT)
                .artifactFormat("json")
                .differencesUri(file.toString())
                .differenceRows(3L)
                .differenceTruncated(false)
                .build()));

        DiffPageDto page = service().listDifferences("a-huge", Long.MAX_VALUE, 10, null, "trace");

        assertEquals(0, page.getRows());
        assertEquals(false, page.isHasMore());
        Files.deleteIfExists(file);
    }
}
