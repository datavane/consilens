package com.consilens.core.compare.executor;

import com.consilens.common.enums.ChecksumAlgorithm;
import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.SqlQueryGenerator;
import com.consilens.connector.api.dataset.DatasetHandle;
import com.consilens.connector.api.dataset.RelationalDatasetSupport;
import com.consilens.connector.api.planner.CompareExecutionOptions;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.connector.api.planner.CompareSegment;
import com.consilens.core.compare.CompareExecutionSettings;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChecksumPlanExecutorTest {

    @Test
    void shouldDowngradeXorWhenOneSideDoesNotSupportIt() {
        CompareExecutionSettings executionSettings = CompareExecutionSettings.fromRequest(CompareRequest.builder()
                .executionOptions(CompareExecutionOptions.builder().checksumAlgorithm("xor").build())
                .build());

        CompareExecutionSettings effectiveSettings = ChecksumPlanExecutor.resolveEffectiveExecutionSettings(
                executionSettings,
                segmentWithChecksumSupport("mysql-source", true),
                segmentWithChecksumSupport("starrocks-target", false));

        assertEquals(ChecksumAlgorithm.CONCAT, effectiveSettings.getChecksumAlgorithm());
    }

    @Test
    void shouldKeepXorWhenBothSidesSupportIt() {
        CompareExecutionSettings executionSettings = CompareExecutionSettings.fromRequest(CompareRequest.builder()
                .executionOptions(CompareExecutionOptions.builder().checksumAlgorithm("xor").build())
                .build());

        CompareExecutionSettings effectiveSettings = ChecksumPlanExecutor.resolveEffectiveExecutionSettings(
                executionSettings,
                segmentWithChecksumSupport("mysql-source", true),
                segmentWithChecksumSupport("postgres-target", true));

        assertEquals(ChecksumAlgorithm.XOR, effectiveSettings.getChecksumAlgorithm());
    }

    private CompareSegment segmentWithChecksumSupport(String supportName, boolean supportsXor) {
        SqlQueryGenerator generator = mock(SqlQueryGenerator.class);
        when(generator.supportsChecksumAlgorithm(ChecksumAlgorithm.XOR)).thenReturn(supportsXor);

        DatabaseDialect dialect = mock(DatabaseDialect.class);
        when(dialect.getSqlQueryGenerator()).thenReturn(generator);

        RelationalDatasetSupport support = mock(RelationalDatasetSupport.class);
        when(support.getName()).thenReturn(supportName);
        when(support.getDialect()).thenReturn(dialect);

        DatasetHandle dataset = mock(DatasetHandle.class);
        when(dataset.getSupport(RelationalDatasetSupport.class)).thenReturn(Optional.of(support));

        return CompareSegment.builder().dataset(dataset).build();
    }
}
