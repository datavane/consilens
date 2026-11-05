package org.apache.iceberg;

/**
 * Test-only bridge: {@link DataFiles#data(PartitionSpec, String)} is public but
 * the class is package-private to expose other helpers.
 */
public final class DataFilesBridge {

    private DataFilesBridge() {
    }

    public static PartitionData data(PartitionSpec spec, String partitionPath) {
        return DataFiles.data(spec, partitionPath);
    }

    public static PartitionData empty(PartitionSpec spec) {
        return new PartitionData(spec.partitionType());
    }

    public static PartitionData partition(PartitionSpec spec, String partitionPath) {
        return DataFiles.data(spec, partitionPath);
    }
}
