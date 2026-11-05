package com.consilens.connector.testkit;

import com.consilens.connector.api.config.ConnectorConfig;
import com.consilens.connector.api.config.ReadOptions;
import com.consilens.connector.api.dataset.DatasetHandle;
import com.consilens.connector.api.dataset.RecordScanner;
import com.consilens.connector.api.model.ResourceLocator;
import com.consilens.connector.api.planner.CompareExecutionOptions;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.connector.api.planner.CompareSegment;
import com.consilens.connector.api.record.CanonicalRecord;
import com.consilens.connector.api.record.CloseableIterator;
import com.consilens.connector.api.spi.ConnectorAdapter;
import com.consilens.connector.api.spi.ConnectorProvider;
import org.junit.jupiter.api.Test;

import java.io.Closeable;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the stable connector SPI and request model. Additive changes are fine;
 * deleting/renaming methods requires a major version bump.
 */
class SpiApiCompatibilityTest {

    @Test
    void connectorProviderContractShouldStayStable() throws Exception {
        assertThat(ConnectorProvider.class.getMethod("getType").getReturnType())
                .isEqualTo(String.class);
        assertThat(ConnectorProvider.class.getMethod("create", ConnectorConfig.class)).isNotNull();
        assertThat(ConnectorAdapter.class.getMethod("getType").getReturnType())
                .isEqualTo(String.class);
        assertThat(ConnectorAdapter.class.getMethod("getName").getReturnType())
                .isEqualTo(String.class);
        assertThat(ConnectorAdapter.class.getMethod("openDataset",
                ResourceLocator.class, ReadOptions.class)).isNotNull();
        assertThat(AutoCloseable.class.isAssignableFrom(ConnectorAdapter.class)).isTrue();
    }

    @Test
    void datasetAndScannerContractShouldStayStable() throws Exception {
        assertThat(AutoCloseable.class.isAssignableFrom(DatasetHandle.class)).isTrue();
        assertThat(DatasetHandle.class.getMethod("getResource").getReturnType())
                .isEqualTo(ResourceLocator.class);
        assertThat(DatasetHandle.class.getMethod("getSchema")).isNotNull();
        assertThat(DatasetHandle.class.getMethod("getRecordScanner").getReturnType())
                .isEqualTo(Optional.class);
        assertThat(DatasetHandle.class.getMethod("getSplitPlanner").getReturnType())
                .isEqualTo(Optional.class);
        assertThat(DatasetHandle.class.getMethod("getHashProvider").getReturnType())
                .isEqualTo(Optional.class);
        assertThat(DatasetHandle.class.getMethod("getKeyLookupProvider").getReturnType())
                .isEqualTo(Optional.class);
        assertThat(DatasetHandle.class.getMethod("getSnapshotProvider").getReturnType())
                .isEqualTo(Optional.class);
        assertThat(DatasetHandle.class.getMethod("getFilterPushdownProvider").getReturnType())
                .isEqualTo(Optional.class);
        assertThat(DatasetHandle.class.getMethod("getSupport", Class.class).getReturnType())
                .isEqualTo(Optional.class);
        assertThat(RecordScanner.class.getMethod("scan", CompareSegment.class).getReturnType())
                .isEqualTo(CloseableIterator.class);
        assertThat(CloseableIterator.class.isAssignableFrom(
                RecordScanner.class.getMethod("scan", CompareSegment.class).getReturnType()))
                .isTrue();
        assertThat(CloseableIterator.class.getTypeParameters()).hasSize(1);
        assertThat(Closeable.class.isAssignableFrom(CloseableIterator.class)).isTrue();
        assertThat(CanonicalRecord.class.getMethod("getValues").getReturnType()).isNotNull();
    }

    @Test
    void requestModelShouldStayStable() throws Exception {
        assertThat(CompareRequest.class.getMethod("getSource").getReturnType())
                .isEqualTo(ConnectorConfig.class);
        assertThat(CompareRequest.class.getMethod("getTarget").getReturnType())
                .isEqualTo(ConnectorConfig.class);
        assertThat(CompareRequest.class.getMethod("getSourceKeySpec")).isNotNull();
        assertThat(CompareRequest.class.getMethod("getTargetKeySpec")).isNotNull();
        assertThat(CompareRequest.class.getMethod("getSourceComparisons")).isNotNull();
        assertThat(CompareRequest.class.getMethod("getTargetComparisons")).isNotNull();
        assertThat(CompareRequest.class.getMethod("getSourceFilter")).isNotNull();
        assertThat(CompareRequest.class.getMethod("getTargetFilter")).isNotNull();
        assertThat(CompareRequest.class.getMethod("getNormalizationSpec")).isNotNull();
        assertThat(CompareRequest.class.getMethod("getExecutionOptions")).isNotNull();
        assertThat(CompareRequest.class.getMethod("getClusterExecutionSpec")).isNotNull();
        assertThat(CompareExecutionOptions.class.getMethod("getMaxDifferences")).isNotNull();
        assertThat(CompareExecutionOptions.class.getMethod("getMaxDurationMs")).isNotNull();
        assertThat(CompareExecutionOptions.class.getMethod("getMaxRowsScanned")).isNotNull();
        assertThat(CompareExecutionOptions.class.getMethod("getMaxBytesFetched")).isNotNull();
        assertThat(CompareExecutionOptions.class.getMethod("getProgressListener")).isNotNull();
    }
}
