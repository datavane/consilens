package com.consilens.connector.iceberg;

import com.consilens.connector.api.model.ResourceLocator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IcebergMetadataLocationTest {

    @TempDir
    Path warehouse;

    @Test
    void shouldPickHighestVersionInsteadOfLexicographicMaximum() throws Exception {
        Path metadataDir = Files.createDirectories(warehouse.resolve("orders").resolve("metadata"));
        for (int version = 1; version <= 12; version++) {
            Files.writeString(metadataDir.resolve("v" + version + ".metadata.json"), "{}");
        }

        ResourceLocator resource = ResourceLocator.builder()
                .path(warehouse.resolve("orders").toString())
                .build();

        String resolved = IcebergDatasetHandle.metadataLocation(resource, null);

        // 字典序会把 v9 当成最新（"v9" > "v10"），表提交十次以后就会读到旧快照
        assertEquals(metadataDir.resolve("v12.metadata.json").toString(), resolved);
    }

    @Test
    void shouldFallBackToLexicographicOrderForUuidMetadataNames() throws Exception {
        Path metadataDir = Files.createDirectories(warehouse.resolve("legacy").resolve("metadata"));
        Files.writeString(metadataDir.resolve("00000-aaa.metadata.json"), "{}");
        Files.writeString(metadataDir.resolve("00001-bbb.metadata.json"), "{}");

        ResourceLocator resource = ResourceLocator.builder()
                .path(warehouse.resolve("legacy").toString())
                .build();

        assertEquals(metadataDir.resolve("00001-bbb.metadata.json").toString(),
                IcebergDatasetHandle.metadataLocation(resource, null));
    }
}
