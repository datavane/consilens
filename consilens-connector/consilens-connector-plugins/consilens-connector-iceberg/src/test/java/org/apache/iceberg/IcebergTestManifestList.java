package org.apache.iceberg;

import org.apache.iceberg.io.OutputFile;

import java.io.IOException;

/**
 * Test-only bridge for writing a manifest list. {@link ManifestLists} is
 * package-private, so this class must stay in the Iceberg package.
 */
public final class IcebergTestManifestList {

    private IcebergTestManifestList() {
    }

    public static void write(int formatVersion, OutputFile outputFile, long snapshotId,
                             Long parentSnapshotId, long sequenceNumber, Long firstRowId,
                             Iterable<ManifestFile> manifests) throws IOException {
        try (ManifestListWriter writer = ManifestLists.write(formatVersion, outputFile,
                snapshotId, parentSnapshotId, sequenceNumber, firstRowId)) {
            writer.addAll(manifests);
        }
    }
}
