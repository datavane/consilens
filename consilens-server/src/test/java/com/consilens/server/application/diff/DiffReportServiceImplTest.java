package com.consilens.server.application.diff;

import com.consilens.server.api.dto.DiffReportDto;
import com.consilens.core.diff.SchemaCompatibility;
import com.consilens.server.domain.enums.ArtifactKind;
import com.consilens.server.domain.enums.TaskStatus;
import com.consilens.server.domain.exception.ResourceNotFoundException;
import com.consilens.server.domain.model.ArtifactRecord;
import com.consilens.server.domain.model.TaskInstanceRecord;
import com.consilens.server.domain.repository.ArtifactRepository;
import com.consilens.server.domain.repository.TaskRepository;
import com.consilens.server.infrastructure.storage.ArtifactContentStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiffReportServiceImplTest {

    private static final String TASK_KEY = "task-1";
    private static final long TASK_ID = 42L;

    private TaskRepository taskRepository;
    private ArtifactRepository artifactRepository;
    private ArtifactContentStore contentStore;
    private com.consilens.server.application.artifact.ArtifactQueryService artifactQueryService;
    private DiffReportServiceImpl service;

    @BeforeEach
    void setUp() {
        taskRepository = mock(TaskRepository.class);
        artifactRepository = mock(ArtifactRepository.class);
        contentStore = mock(ArtifactContentStore.class);
        artifactQueryService = mock(com.consilens.server.application.artifact.ArtifactQueryService.class);
        service = new DiffReportServiceImpl(taskRepository, artifactRepository, contentStore,
                artifactQueryService,
                new ObjectMapper());
    }

    @Test
    void shouldBuildDiffReportFromRunResultContent() throws Exception {
        TaskInstanceRecord task = task(TaskStatus.SUCCEEDED);
        when(taskRepository.resolveByRef(TASK_KEY)).thenReturn(Optional.of(task));
        ArtifactRecord runResult = runResult("storage://run-result-1");
        when(artifactRepository.listByTaskId(TASK_ID)).thenReturn(List.of(runResult));
        when(contentStore.read("storage://run-result-1"))
                .thenReturn(content("{\n"
                        + "  \"success\": true,\n"
                        + "  \"differenceCount\": 3,\n"
                        + "  \"differenceSampleSize\": 3,\n"
                        + "  \"differenceSampleTruncated\": false,\n"
                        + "  \"statistics\": {\n"
                        + "    \"sourceRowCount\": 100, \"targetRowCount\": 100,\n"
                        + "    \"sourceMissingCount\": 1, \"targetMissingCount\": 0, \"mismatchCount\": 2,\n"
                        + "    \"unchangedCount\": 97, \"totalDifferences\": 3,\n"
                        + "    \"differencePercentage\": 3.0, \"processingTimeMs\": 12\n"
                        + "  },\n"
                        + "  \"differences\": [\n"
                        + "    {\"operation\": \"MISMATCH\", \"primaryKey\": [\"1001\"],\n"
                        + "     \"metadata\": {\"changedColumns1\": [\"price\"], \"changedColumns2\": [\"price\", \"qty\"]}},\n"
                        + "    {\"operation\": \"MISMATCH\", \"primaryKey\": [\"1002\"],\n"
                        + "     \"metadata\": {\"changedColumns1\": [\"status\"]}},\n"
                        + "    {\"operation\": \"SOURCE_MISSING\", \"primaryKey\": [\"1003\"], \"metadata\": {}}\n"
                        + "  ]\n"
                        + "}"));

        DiffReportDto report = service.getDiffReport(TASK_KEY);

        assertEquals(TASK_KEY, report.getTaskId());
        assertEquals("GOOD", report.getStatus());
        assertEquals(97.0, report.getMatchScore());
        assertEquals(3L, report.getTotalDifferenceCount());
        assertEquals(3, report.getSampleSize());
        assertEquals(false, report.getSampleTruncated());
        assertEquals(3, report.getStatistics().get("totalDifferences"));
        assertNull(report.getResultSchemaVersion());

        assertEquals(3, report.getColumns().size());
        DiffReportDto.ColumnStat price = report.getColumns().get(0);
        assertEquals("price", price.getName());
        assertEquals(1L, price.getUpdateCount());
        assertEquals(1L, price.getDifferenceCount());
        assertEquals(50.0, price.getDifferencePercentage());
        assertEquals(true, price.getAggregatedFromSamples());
        assertEquals(0L, price.getInsertCount());
        assertEquals(0L, price.getDeleteCount());

        assertEquals(3, report.getSamples().size());
        DiffReportDto.SampleDiff first = report.getSamples().get(0);
        assertEquals("MISMATCH", first.getOperation());
        assertEquals(List.of("1001"), first.getPrimaryKey());
        assertEquals(List.of("price", "qty"), first.getChangedColumns());
        assertNotNull(first.getMetadata());

        DiffReportDto.SampleDiff sourceMissing = report.getSamples().get(2);
        assertEquals("SOURCE_MISSING", sourceMissing.getOperation());
        assertTrue(sourceMissing.getChangedColumns().isEmpty());
    }

    @Test
    void shouldReadResultSchemaVersionFromArtifactMetadata() throws Exception {
        TaskInstanceRecord task = task(TaskStatus.SUCCEEDED);
        when(taskRepository.resolveByRef(TASK_KEY)).thenReturn(Optional.of(task));
        ArtifactRecord runResult = runResult("storage://versioned");
        runResult.setMetadataJson("{\"source\":\"run\",\"resultSchemaVersion\":1}");
        when(artifactRepository.listByTaskId(TASK_ID)).thenReturn(List.of(runResult));
        when(contentStore.read("storage://versioned")).thenReturn(content("{\n"
                + "  \"success\": true,\n"
                + "  \"differenceCount\": 0,\n"
                + "  \"statistics\": {\n"
                + "    \"sourceRowCount\": 10, \"targetRowCount\": 10,\n"
                + "    \"totalDifferences\": 0, \"differencePercentage\": 0\n"
                + "  },\n"
                + "  \"differences\": []\n"
                + "}"));

        DiffReportDto report = service.getDiffReport(TASK_KEY);

        assertEquals(1, report.getResultSchemaVersion());
    }

    @Test
    void shouldReadLegacyArtifactWithoutStatisticsOrNewFields() throws Exception {
        TaskInstanceRecord task = task(TaskStatus.SUCCEEDED);
        when(taskRepository.resolveByRef(TASK_KEY)).thenReturn(Optional.of(task));
        when(artifactRepository.listByTaskId(TASK_ID))
                .thenReturn(List.of(runResult("storage://legacy-v0")));
        when(contentStore.read("storage://legacy-v0")).thenReturn(content("{\n"
                + "  \"success\": true,\n"
                + "  \"differenceCount\": 2,\n"
                + "  \"differences\": [\n"
                + "    {\"operation\": \"MISMATCH\", \"primaryKey\": [\"1\"],\n"
                + "     \"metadata\": {\"changedColumns1\": [\"amount\"]}},\n"
                + "    {\"operation\": \"SOURCE_MISSING\", \"primaryKey\": [\"2\"], \"metadata\": {}}\n"
                + "  ]\n"
                + "}"));

        DiffReportDto report = service.getDiffReport(TASK_KEY);

        assertEquals(2L, report.getTotalDifferenceCount());
        assertNull(report.getResultSchemaVersion());
        assertEquals(1, report.getColumns().size());
        assertEquals(2, report.getSamples().size());
        assertNotNull(report.getStatus());
    }

    @Test
    void shouldPreserveUnknownFutureVersionAndFields() throws Exception {
        TaskInstanceRecord task = task(TaskStatus.SUCCEEDED);
        when(taskRepository.resolveByRef(TASK_KEY)).thenReturn(Optional.of(task));
        ArtifactRecord runResult = runResult("storage://future");
        runResult.setMetadataJson("{\"source\":\"run\",\"resultSchemaVersion\":99,"
                + "\"futureField\":{\"a\":1}}");
        when(artifactRepository.listByTaskId(TASK_ID)).thenReturn(List.of(runResult));
        when(contentStore.read("storage://future")).thenReturn(content("{\n"
                + "  \"success\": true,\n"
                + "  \"differenceCount\": 0,\n"
                + "  \"futureContent\": {\"x\": 1},\n"
                + "  \"statistics\": {\n"
                + "    \"sourceRowCount\": 10, \"targetRowCount\": 10,\n"
                + "    \"totalDifferences\": 0, \"differencePercentage\": 0\n"
                + "  },\n"
                + "  \"differences\": []\n"
                + "}"));

        DiffReportDto report = service.getDiffReport(TASK_KEY);

        assertEquals(99, report.getResultSchemaVersion());
        assertEquals("EXCELLENT", report.getStatus());
        assertEquals(0L, report.getTotalDifferenceCount());
    }

    @Test
    void shouldTreatMalformedMetadataAsUnversioned() throws Exception {
        TaskInstanceRecord task = task(TaskStatus.SUCCEEDED);
        when(taskRepository.resolveByRef(TASK_KEY)).thenReturn(Optional.of(task));
        ArtifactRecord runResult = runResult("storage://bad-metadata");
        runResult.setMetadataJson("not-json");
        when(artifactRepository.listByTaskId(TASK_ID)).thenReturn(List.of(runResult));
        when(contentStore.read("storage://bad-metadata")).thenReturn(content("{\n"
                + "  \"success\": true,\n"
                + "  \"differenceCount\": 0,\n"
                + "  \"statistics\": {\n"
                + "    \"sourceRowCount\": 10, \"targetRowCount\": 10,\n"
                + "    \"totalDifferences\": 0, \"differencePercentage\": 0\n"
                + "  },\n"
                + "  \"differences\": []\n"
                + "}"));

        DiffReportDto report = service.getDiffReport(TASK_KEY);

        assertNull(report.getResultSchemaVersion());
        assertEquals("EXCELLENT", report.getStatus());
    }

    @Test
    void shouldPreferExactColumnStatsWhenPresent() throws Exception {
        TaskInstanceRecord task = task(TaskStatus.SUCCEEDED);
        when(taskRepository.resolveByRef(TASK_KEY)).thenReturn(Optional.of(task));
        when(artifactRepository.listByTaskId(TASK_ID))
                .thenReturn(List.of(runResult("storage://exact-columns")));
        when(contentStore.read("storage://exact-columns")).thenReturn(content("{\n"
                + "  \"success\": true,\n"
                + "  \"differenceCount\": 3,\n"
                + "  \"statistics\": {\n"
                + "    \"sourceRowCount\": 100, \"targetRowCount\": 100,\n"
                + "    \"sourceMissingCount\": 1, \"targetMissingCount\": 0, \"mismatchCount\": 2,\n"
                + "    \"totalDifferences\": 3, \"differencePercentage\": 3.0\n"
                + "  },\n"
                + "  \"columnStats\": [{\n"
                + "    \"name\": \"amount\", \"sourceName\": \"amount\", \"targetName\": \"amount\",\n"
                + "    \"insertCount\": 1, \"updateCount\": 2, \"deleteCount\": 0,\n"
                + "    \"differenceCount\": 3, \"differencePercentage\": 100.0,\n"
                + "    \"differenceRate\": 1.5, \"aggregatedFromSamples\": false, \"truncated\": false\n"
                + "  }],\n"
                + "  \"schemaCompatibility\": {\n"
                + "    \"status\": \"COMPATIBLE_WITH_NORMALIZATION\", \"columns\": [], \"warnings\": []\n"
                + "  },\n"
                + "  \"differences\": []\n"
                + "}"));

        DiffReportDto report = service.getDiffReport(TASK_KEY);

        assertEquals(1, report.getColumns().size());
        DiffReportDto.ColumnStat amount = report.getColumns().get(0);
        assertEquals("amount", amount.getName());
        assertEquals(1L, amount.getInsertCount());
        assertEquals(2L, amount.getUpdateCount());
        assertEquals(0L, amount.getDeleteCount());
        assertEquals(3L, amount.getDifferenceCount());
        assertEquals(1.5, amount.getDifferenceRate());
        assertEquals(false, amount.getAggregatedFromSamples());
        assertEquals(false, amount.getTruncated());
        assertEquals(SchemaCompatibility.Status.COMPATIBLE_WITH_NORMALIZATION,
                report.getSchemaCompatibility().getStatus());
        assertTrue(report.getSamples().isEmpty());
    }

    @Test
    void shouldReportExcellentWhenNoDifferences() throws Exception {
        TaskInstanceRecord task = task(TaskStatus.SUCCEEDED);
        when(taskRepository.resolveByRef(TASK_KEY)).thenReturn(Optional.of(task));
        when(artifactRepository.listByTaskId(TASK_ID))
                .thenReturn(List.of(runResult("storage://clean")));
        when(contentStore.read("storage://clean")).thenReturn(content("{\n"
                + "  \"success\": true,\n"
                + "  \"differenceCount\": 0,\n"
                + "  \"differenceSampleSize\": 0,\n"
                + "  \"statistics\": {\n"
                + "    \"sourceRowCount\": 100, \"targetRowCount\": 100,\n"
                + "    \"mismatchCount\": 0, \"unchangedCount\": 100, \"totalDifferences\": 0,\n"
                + "    \"differencePercentage\": 0.0\n"
                + "  },\n"
                + "  \"differences\": []\n"
                + "}"));

        DiffReportDto report = service.getDiffReport(TASK_KEY);

        assertEquals("EXCELLENT", report.getStatus());
        assertEquals(100.0, report.getMatchScore());
        assertEquals(0L, report.getTotalDifferenceCount());
        assertTrue(report.getColumns().isEmpty());
        assertTrue(report.getSamples().isEmpty());
    }

    @Test
    void shouldDerivePercentageFromCountsWhenMissing() throws Exception {
        TaskInstanceRecord task = task(TaskStatus.SUCCEEDED);
        when(taskRepository.resolveByRef(TASK_KEY)).thenReturn(Optional.of(task));
        when(artifactRepository.listByTaskId(TASK_ID))
                .thenReturn(List.of(runResult("storage://no-pct")));
        when(contentStore.read("storage://no-pct")).thenReturn(content("{\n"
                + "  \"success\": true,\n"
                + "  \"differenceCount\": 10,\n"
                + "  \"statistics\": {\"sourceRowCount\": 100, \"targetRowCount\": 200, \"totalDifferences\": 10},\n"
                + "  \"differences\": []\n"
                + "}"));

        DiffReportDto report = service.getDiffReport(TASK_KEY);

        // 分母取两侧行数之和：10 / (100 + 200) = 0.033 -> score 96.7 -> GOOD
        assertEquals(96.7, report.getMatchScore());
        assertEquals("GOOD", report.getStatus());
    }

    @Test
    void shouldNotInferPerfectScoreWhenStatisticsLackTotalDifferences() {
        when(taskRepository.resolveByRef(TASK_KEY)).thenReturn(Optional.of(task(TaskStatus.SUCCEEDED)));
        ArtifactRecord runResult = ArtifactRecord.builder()
                .id("result-partial")
                .taskId(TASK_ID)
                .artifactType(ArtifactKind.RUN_RESULT)
                .artifactFormat("json")
                .storageUri("storage://partial")
                .statisticsJson("{\"sourceRowCount\":100,\"targetRowCount\":100}")
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .metadataJson("{}")
                .build();
        when(artifactRepository.listByTaskId(TASK_ID)).thenReturn(List.of(runResult));
        when(contentStore.read("storage://partial")).thenReturn(content("{\n"
                + "  \"success\": true,\n"
                + "  \"statistics\": {\"sourceRowCount\": 100, \"targetRowCount\": 100}\n"
                + "}"));

        DiffReportDto report = service.getDiffReport(TASK_KEY);

        assertEquals("NONE", report.getStatus());
        assertNull(report.getMatchScore());
        assertNull(report.getTotalDifferenceCount());
    }

    @Test
    void shouldUsePersistedDifferenceCountWhenStatisticsLackTotalDifferences() {
        when(taskRepository.resolveByRef(TASK_KEY)).thenReturn(Optional.of(task(TaskStatus.SUCCEEDED)));
        ArtifactRecord runResult = ArtifactRecord.builder()
                .id("result-persisted-count")
                .taskId(TASK_ID)
                .artifactType(ArtifactKind.RUN_RESULT)
                .artifactFormat("json")
                .storageUri("storage://persisted-count")
                .statisticsJson("{\"sourceRowCount\":100,\"targetRowCount\":100,"
                        + "\"differencePercentage\":3.0}")
                .differenceCount(3L)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .metadataJson("{}")
                .build();
        when(artifactRepository.listByTaskId(TASK_ID)).thenReturn(List.of(runResult));
        when(contentStore.read("storage://persisted-count")).thenReturn(content("{\n"
                + "  \"success\": true,\n"
                + "  \"statistics\": {\"sourceRowCount\": 100, \"targetRowCount\": 100}\n"
                + "}"));

        DiffReportDto report = service.getDiffReport(TASK_KEY);

        assertEquals(3L, report.getTotalDifferenceCount());
        assertEquals(97.0, report.getMatchScore());
        assertEquals("GOOD", report.getStatus());
    }

    @Test
    void shouldReturnNoneForNonSucceededTask() {
        when(taskRepository.resolveByRef(TASK_KEY)).thenReturn(Optional.of(task(TaskStatus.RUNNING)));

        DiffReportDto report = service.getDiffReport(TASK_KEY);

        assertEquals("NONE", report.getStatus());
        assertNull(report.getMatchScore());
        assertNull(report.getTotalDifferenceCount());
        assertTrue(report.getSamples().isEmpty());
        verify(artifactRepository, never()).listByTaskId(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void shouldReturnNoneWhenNoRunResultArtifact() {
        when(taskRepository.resolveByRef(TASK_KEY)).thenReturn(Optional.of(task(TaskStatus.SUCCEEDED)));
        when(artifactRepository.listByTaskId(TASK_ID)).thenReturn(List.of(
                ArtifactRecord.builder().id("config-1").artifactType(ArtifactKind.CONFIG).build()));

        DiffReportDto report = service.getDiffReport(TASK_KEY);

        assertEquals("NONE", report.getStatus());
        verify(contentStore, never()).read(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void shouldReturnNoneWhenContentHasNoStatistics() throws Exception {
        when(taskRepository.resolveByRef(TASK_KEY)).thenReturn(Optional.of(task(TaskStatus.SUCCEEDED)));
        when(artifactRepository.listByTaskId(TASK_ID))
                .thenReturn(List.of(runResult("storage://bare")));
        when(contentStore.read("storage://bare")).thenReturn(content("{\n"
                + "  \"success\": true,\n"
                + "  \"differenceCount\": 3,\n"
                + "  \"differences\": [{\"operation\": \"MISMATCH\", \"primaryKey\": [\"1\"],\n"
                + "                    \"metadata\": {\"changedColumns1\": [\"price\"]}}]\n"
                + "}"));

        DiffReportDto report = service.getDiffReport(TASK_KEY);

        assertEquals("NONE", report.getStatus());
        assertNull(report.getMatchScore());
        assertEquals(3L, report.getTotalDifferenceCount());
        // columns can still be aggregated from samples
        assertEquals(1, report.getColumns().size());
        assertEquals(1, report.getSamples().size());
    }

    @Test
    void shouldReturnNoneWhenLegacyDifferenceCountIsNotNumeric() {
        when(taskRepository.resolveByRef(TASK_KEY)).thenReturn(Optional.of(task(TaskStatus.SUCCEEDED)));
        when(artifactRepository.listByTaskId(TASK_ID))
                .thenReturn(List.of(runResult("storage://null-count")));
        when(contentStore.read("storage://null-count")).thenReturn(content("{\n"
                + "  \"success\": true,\n"
                + "  \"differenceCount\": null,\n"
                + "  \"differences\": []\n"
                + "}"));

        DiffReportDto report = service.getDiffReport(TASK_KEY);

        assertEquals("NONE", report.getStatus());
        assertNull(report.getMatchScore());
        assertNull(report.getTotalDifferenceCount());
    }

    @Test
    void shouldThrowNotFoundForUnknownTask() {
        when(taskRepository.resolveByRef(TASK_KEY)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getDiffReport(TASK_KEY));
    }

    @Test
    void shouldPickLatestRunResultArtifact() throws Exception {
        when(taskRepository.resolveByRef(TASK_KEY)).thenReturn(Optional.of(task(TaskStatus.SUCCEEDED)));
        ArtifactRecord older = ArtifactRecord.builder()
                .id("result-old")
                .artifactType(ArtifactKind.RUN_RESULT)
                .storageUri("storage://old")
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
        ArtifactRecord newer = ArtifactRecord.builder()
                .id("result-new")
                .artifactType(ArtifactKind.RUN_RESULT)
                .storageUri("storage://new")
                .createdAt(Instant.parse("2026-01-02T00:00:00Z"))
                .build();
        when(artifactRepository.listByTaskId(TASK_ID)).thenReturn(List.of(older, newer));
        when(contentStore.read("storage://new")).thenReturn(content("{\n"
                + "  \"success\": true,\n"
                + "  \"statistics\": {\"totalDifferences\": 1, \"differencePercentage\": 0.0},\n"
                + "  \"differences\": []\n"
                + "}"));

        DiffReportDto report = service.getDiffReport(TASK_KEY);

        assertEquals(1L, report.getTotalDifferenceCount());
        assertEquals("EXCELLENT", report.getStatus());
        verify(contentStore, never()).read("storage://old");
    }

    @Test
    void shouldPassColumnValuesThroughFromJsonlDifferences() {
        TaskInstanceRecord task = task(TaskStatus.SUCCEEDED);
        when(taskRepository.resolveByRef(TASK_KEY)).thenReturn(Optional.of(task));
        ArtifactRecord runResult = ArtifactRecord.builder()
                .id("artifact-1")
                .taskId(TASK_ID)
                .artifactType(ArtifactKind.RUN_RESULT)
                .artifactFormat("jsonl")
                .storageUri("storage://run-result-1")
                .statisticsJson("{\"sourceRowCount\":10,\"targetRowCount\":10,"
                        + "\"mismatchCount\":1,\"totalDifferences\":1,\"differencePercentage\":10.0}")
                .differencesUri("/tmp/run-result-1.jsonl")
                .differenceRows(1L)
                .metadataJson("{}")
                .build();
        when(artifactRepository.listByTaskId(TASK_ID)).thenReturn(List.of(runResult));
        when(artifactQueryService.listDifferences("artifact-1", 0, 100, null, "diff-report"))
                .thenReturn(com.consilens.server.api.dto.DiffPageDto.builder()
                        .artifactId("artifact-1")
                        .total(1L)
                        .rows(1)
                        .hasMore(false)
                        .items(List.of(java.util.Map.of(
                                "operation", "MISMATCH",
                                "primaryKey", List.of("1001"),
                                "columnNames", List.of("name", "amount"),
                                "sourceValues", List.of("Alice", "100.00"),
                                "targetValues", List.of("Alicia", "101.00"),
                                "metadata", Map.of("changedColumnIndices", List.of(0, 1)))))
                        .build());

        DiffReportDto report = service.getDiffReport(TASK_KEY);

        assertEquals(1, report.getSamples().size());
        DiffReportDto.SampleDiff sample = report.getSamples().get(0);
        assertEquals(List.of("name", "amount"), sample.getColumnNames());
        assertEquals(List.of("Alice", "100.00"), sample.getSourceValues());
        assertEquals(List.of("Alicia", "101.00"), sample.getTargetValues());
    }

    private TaskInstanceRecord task(TaskStatus status) {
        return TaskInstanceRecord.builder()
                .id(TASK_ID)
                .instanceKey(TASK_KEY)
                .status(status)
                .build();
    }

    private ArtifactRecord runResult(String storageUri) {
        return ArtifactRecord.builder()
                .id("result-1")
                .taskId(TASK_ID)
                .artifactType(ArtifactKind.RUN_RESULT)
                .artifactFormat("json")
                .storageUri(storageUri)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .metadataJson("{}")
                .build();
    }

    private byte[] content(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
