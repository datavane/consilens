package com.consilens.connector.iceberg;

import com.consilens.connector.api.config.ConnectorConfig;
import com.consilens.connector.api.model.ComparisonSpec;
import com.consilens.connector.api.model.KeySpec;
import com.consilens.connector.api.model.ResourceLocator;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.connector.file.S3ObjectStore;
import com.consilens.core.compare.DefaultCompareRuntime;
import com.consilens.core.diff.DiffResult;
import org.apache.iceberg.avro.Avro;
import org.apache.iceberg.avro.GenericAvroWriter;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.parquet.GenericParquetWriter;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.ManifestFiles;
import org.apache.iceberg.ManifestWriter;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.io.FileAppender;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.parquet.Parquet;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IcebergConnectorIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldPassConnectorCertification() throws Exception {
        Path metadata = createTable(tempDir.resolve("certified-table"), 10.0, 20.0);
        ConnectorConfig config = ConnectorConfig.builder()
                .type("iceberg")
                .name("iceberg-certification")
                .resource(ResourceLocator.builder()
                        .type("iceberg")
                        .path(metadata.toString())
                        .build())
                .build();

        com.consilens.connector.testkit.CertificationReport report =
                com.consilens.connector.testkit.ConnectorCertification.verify(
                        new IcebergConnectorProvider(), config,
                        KeySpec.builder().fields(List.of("id")).build(), 10);

        assertEquals(List.of(), report.failures());
        assertTrue(report.isPassed());
    }

    @Test
    void shouldDownloadS3DataFileThroughStore() throws Exception {
        Path tableDir = tempDir.resolve("s3-data-table");
        Path metadata = createTable(tableDir, 10.0, 20.0,
                "s3://bucket/warehouse/data/data.parquet");
        FakeS3Store store = new FakeS3Store()
                .put("s3://bucket/warehouse/data/data.parquet",
                        tableDir.resolve("data/data.parquet"));
        ResourceLocator resource = ResourceLocator.builder()
                .type("iceberg")
                .path(metadata.toString())
                .build();

        IcebergDatasetHandle handle = new IcebergDatasetHandle(resource, null, store);
        try {
            int rows = 0;
            try (var iterator = handle.getRecordScanner().get().scan(null)) {
                while (iterator.hasNext()) {
                    iterator.next();
                    rows++;
                }
            }
            assertEquals(2, rows);
        } finally {
            handle.close();
        }
    }

    @Test
    void shouldDownloadS3MetadataThroughStore() throws Exception {
        Path tableDir = tempDir.resolve("s3-metadata-table");
        Path metadata = createTable(tableDir, 10.0, 20.0);
        FakeS3Store store = new FakeS3Store()
                .put("s3://bucket/warehouse/v1.metadata.json", metadata);
        ResourceLocator resource = ResourceLocator.builder()
                .type("iceberg")
                .path("s3://bucket/warehouse/v1.metadata.json")
                .build();

        IcebergDatasetHandle handle = new IcebergDatasetHandle(resource, null, store);
        try {
            assertEquals(3, handle.getSchema().getFields().size());
        } finally {
            handle.close();
        }
    }

    @Test
    void shouldDiscoverLatestS3MetadataFromDirectory() throws Exception {
        Path tableDir = tempDir.resolve("s3-dir-table");
        Path metadata = createTable(tableDir, 10.0, 20.0);
        FakeS3Store store = new FakeS3Store()
                .put("s3://bucket/warehouse/v1.metadata.json", metadata);
        ResourceLocator resource = ResourceLocator.builder()
                .type("iceberg")
                .path("s3://bucket/warehouse/")
                .build();

        IcebergDatasetHandle handle = new IcebergDatasetHandle(resource, null, store);
        try {
            assertEquals(3, handle.getSchema().getFields().size());
        } finally {
            handle.close();
        }
    }

    @Test
    void shouldPruneIcebergPartitions() throws Exception {
        Path metadata = createPartitionedTable(tempDir.resolve("partitioned"));
        ResourceLocator resource = ResourceLocator.builder()
                .type("iceberg")
                .path(metadata.toString())
                .options(Map.of("partition.name", "alice"))
                .build();

        IcebergDatasetHandle handle = new IcebergDatasetHandle(resource, null);
        try {
            int rows = 0;
            try (var iterator = handle.getRecordScanner().get().scan(null)) {
                while (iterator.hasNext()) {
                    assertEquals("alice", iterator.next().getValues().get("name").getValue());
                    rows++;
                }
            }
            assertEquals(1, rows);
        } finally {
            handle.close();
        }
    }

    @Test
    void shouldPruneDatePartitionedTableByIsoLiteral() throws Exception {
        // DATE 分区在 manifest 里是"距 1970 的天数"：写 partition.dt=2024-01-01 必须能命中
        Path metadata = createDatePartitionedTable(tempDir.resolve("date-partitioned"));
        ResourceLocator resource = ResourceLocator.builder()
                .type("iceberg")
                .path(metadata.toString())
                .options(Map.of("partition.dt", "2024-01-01"))
                .build();

        IcebergDatasetHandle handle = new IcebergDatasetHandle(resource, null);
        try {
            int rows = 0;
            try (var iterator = handle.getRecordScanner().get().scan(null)) {
                while (iterator.hasNext()) {
                    assertEquals("2024-01-01", String.valueOf(iterator.next().getValues().get("dt").getValue()));
                    rows++;
                }
            }
            assertEquals(1, rows);
        } finally {
            handle.close();
        }
    }

    @Test
    void shouldApplyIcebergPositionDeletes() throws Exception {
        Path metadata = createPositionDeleteTable(tempDir.resolve("delete-table"), 1L, 1L);
        ResourceLocator resource = ResourceLocator.builder()
                .type("iceberg")
                .path(metadata.toString())
                .build();
        IcebergDatasetHandle handle = new IcebergDatasetHandle(resource, null);
        try {
            List<String> names = new java.util.ArrayList<>();
            try (var iterator = handle.getRecordScanner().get().scan(null)) {
                while (iterator.hasNext()) {
                    names.add(String.valueOf(iterator.next().getValues().get("name").getValue()));
                }
            }
            assertEquals(List.of("alice"), names);
            assertEquals(2, handle.getSchema().getFields().size());
            assertTrue(handle.getSchema().getFieldMap().keySet().stream()
                    .noneMatch(name -> name.contains("file_row_number")));
        } finally {
            handle.close();
        }
    }

    @Test
    void shouldIgnoreStalePositionDeleteForReaddedDataFile() throws Exception {
        Path metadata = createPositionDeleteTable(tempDir.resolve("delete-stale"), 2L, 1L);
        assertEquals(List.of("alice", "bob"), scanNames(metadata));
    }

    @Test
    void shouldApplyPositionDeleteFromOrcFile() throws Exception {
        Path metadata = createPositionDeleteTable(
                tempDir.resolve("position-orc-delete"), 1L, 1L, FileFormat.ORC);
        assertEquals(List.of("alice"), scanNames(metadata));
    }

    @Test
    void shouldApplyPositionDeleteFromAvroFile() throws Exception {
        Path metadata = createPositionDeleteTable(
                tempDir.resolve("position-avro-delete"), 1L, 1L, FileFormat.AVRO);
        assertEquals(List.of("alice"), scanNames(metadata));
    }

    private Path createPositionDeleteTable(Path tableDir, long dataSequence,
                                           long deleteSequence) throws Exception {
        return createPositionDeleteTable(tableDir, dataSequence, deleteSequence,
                FileFormat.PARQUET);
    }

    private Path createPositionDeleteTable(Path tableDir, long dataSequence,
                                           long deleteSequence,
                                           FileFormat deleteFormat) throws Exception {
        Path dataDir = tableDir.resolve("data");
        Path metadataDir = tableDir.resolve("metadata");
        Files.createDirectories(dataDir);
        Files.createDirectories(metadataDir);
        // Same basename as the delete file exercises temp-directory isolation.
        Path dataFile = dataDir.resolve("delete-data.parquet");
        writeParquet(dataFile, "SELECT 1 AS id, 'alice' AS name UNION ALL SELECT 2, 'bob'");

        LocalFileIO io = new LocalFileIO();
        Schema schema = new Schema(
                Types.NestedField.required(1, "id", Types.LongType.get()),
                Types.NestedField.required(2, "name", Types.StringType.get()));
        PartitionSpec spec = PartitionSpec.unpartitioned();
        Path deleteData = metadataDir.resolve("delete-data."
                + deleteFormat.name().toLowerCase(java.util.Locale.ROOT));
        if (deleteFormat == FileFormat.ORC) {
            org.apache.orc.TypeDescription deleteSchema =
                    org.apache.orc.TypeDescription.fromString(
                            "struct<_file_path:string,_pos:bigint>");
            writeOrcRows(deleteData, deleteSchema, java.util.List.<Object[]>of(
                    new Object[]{dataFile.toUri().toString(), 1L}));
        } else if (deleteFormat == FileFormat.AVRO) {
            Schema deleteSchema = new Schema(
                    Types.NestedField.required(1, "_file_path", Types.StringType.get()),
                    Types.NestedField.required(2, "_pos", Types.LongType.get()));
            org.apache.avro.Schema avroSchema =
                    org.apache.iceberg.avro.AvroSchemaUtil.convert(deleteSchema, "delete");
            org.apache.avro.generic.GenericData.Record deleteRow =
                    new org.apache.avro.generic.GenericData.Record(avroSchema);
            deleteRow.put("_file_path", dataFile.toUri().toString());
            deleteRow.put("_pos", 1L);
            writeAvroWithFieldIds(deleteData, deleteSchema, List.of(deleteRow));
        } else {
            writeParquet(deleteData, "SELECT '" + dataFile.toUri()
                    + "' AS _file_path, 1 AS _pos");
        }

        ManifestWriter<org.apache.iceberg.DeleteFile> deleteWriter =
                ManifestFiles.writeDeleteManifest(2, spec,
                        io.newOutputFile(metadataDir.resolve("delete-manifest-1.avro").toString()),
                        2L);
        deleteWriter.add(newPositionDelete(spec, deleteData.toUri().toString(),
                Files.size(deleteData), 2L, deleteFormat), deleteSequence);
        deleteWriter.close();
        ManifestFile deleteManifest = deleteWriter.toManifestFile();

        ManifestWriter<DataFile> dataWriter = ManifestFiles.write(2, spec,
                io.newOutputFile(metadataDir.resolve("manifest-1.avro").toString()), 2L);
        DataFile dataEntry = DataFiles.builder(spec)
                .withPath(dataFile.toUri().toString())
                .withFileSizeInBytes(Files.size(dataFile))
                .withRecordCount(2)
                .withFormat(FileFormat.PARQUET)
                .build();
        dataWriter.add(dataEntry, dataSequence);
        dataWriter.close();
        ManifestFile dataManifest = dataWriter.toManifestFile();

        Path manifestList = metadataDir.resolve("snap-1.avro");
        org.apache.iceberg.IcebergTestManifestList.write(2, io.newOutputFile(manifestList.toString()),
                2L, null, 2L, null, List.of(dataManifest, deleteManifest));
        Snapshot snapshot = new SimpleSnapshot(2L, dataManifest, dataEntry,
                manifestList.toString(), deleteManifest);
        TableMetadata metadata = TableMetadata.buildFromEmpty()
                .assignUUID()
                .upgradeFormatVersion(2)
                .setLocation(tableDir.toUri().toString())
                .setCurrentSchema(schema, 0)
                .addPartitionSpec(spec)
                .setDefaultPartitionSpec(spec)
                .addSortOrder(org.apache.iceberg.SortOrder.unsorted())
                .setDefaultSortOrder(org.apache.iceberg.SortOrder.unsorted())
                .setBranchSnapshot(snapshot, "main")
                .build();
        Path metadataFile = metadataDir.resolve("v1.metadata.json");
        TableMetadataParser.write(metadata, io.newOutputFile(metadataFile.toString()));
        return metadataFile;
    }

    @Test
    void shouldApplyIcebergEqualityDeletes() throws Exception {
        Path metadata = createEqualityDeleteTable(tempDir.resolve("equality-table"), 1L, 2L);
        assertEquals(List.of("bob"), scanNames(metadata));
    }

    @Test
    void shouldIgnoreIcebergEqualityDeleteAtSameSequence() throws Exception {
        Path metadata = createEqualityDeleteTable(tempDir.resolve("equality-stale"), 1L, 1L);
        assertEquals(List.of("alice", "bob"), scanNames(metadata));
    }

    @Test
    void shouldReadS3EqualityDeleteFile() throws Exception {
        Path tableDir = tempDir.resolve("equality-s3");
        String location = "s3://bucket/equality-delete.parquet";
        Path metadata = createEqualityDeleteTable(tableDir, 1L, 2L, List.of(1L), location);
        FakeS3Store store = new FakeS3Store().put(location,
                tableDir.resolve("metadata/equality-delete.parquet"));
        ResourceLocator resource = ResourceLocator.builder()
                .type("iceberg")
                .path(metadata.toString())
                .build();
        IcebergDatasetHandle handle = new IcebergDatasetHandle(resource, null, store);
        try {
            List<String> names = new java.util.ArrayList<>();
            try (var iterator = handle.getRecordScanner().get().scan(null)) {
                while (iterator.hasNext()) {
                    names.add(String.valueOf(iterator.next().getValues().get("name").getValue()));
                }
            }
            assertEquals(List.of("bob"), names);
        } finally {
            handle.close();
        }
    }

    @Test
    void shouldApplyPositionAndEqualityDeletesTogether() throws Exception {
        Path metadata = createMixedDeleteTable(tempDir.resolve("mixed-deletes"));
        assertEquals(List.of("carol"), scanNames(metadata));
    }

    @Test
    void shouldApplyEqualityDeleteWithRenamedKeyColumns() throws Exception {
        Path metadata = createRenamedEqualityDeleteTable(
                tempDir.resolve("equality-renamed"));
        assertEquals(List.of("bob"), scanNames(metadata));
    }

    /**
     * Data and delete files are written with Parquet field IDs and renamed
     * columns: the snapshot schema says {@code id}/{@code name} (field IDs 1/2),
     * the data file stores {@code customer_id}/{@code customer_name}, and the
     * delete file stores {@code pk_id}. Deletion must match by field ID.
     */
    private Path createRenamedEqualityDeleteTable(Path tableDir) throws Exception {
        Path dataDir = tableDir.resolve("data");
        Path metadataDir = tableDir.resolve("metadata");
        Files.createDirectories(dataDir);
        Files.createDirectories(metadataDir);

        Schema dataWriteSchema = new Schema(
                Types.NestedField.required(1, "customer_id", Types.LongType.get()),
                Types.NestedField.required(2, "customer_name", Types.StringType.get()));
        Schema deleteWriteSchema = new Schema(
                Types.NestedField.required(1, "pk_id", Types.LongType.get()));
        Record alice = GenericRecord.create(dataWriteSchema);
        alice.setField("customer_id", 1L);
        alice.setField("customer_name", "alice");
        Record bob = GenericRecord.create(dataWriteSchema);
        bob.setField("customer_id", 2L);
        bob.setField("customer_name", "bob");
        Record deleteKey = GenericRecord.create(deleteWriteSchema);
        deleteKey.setField("pk_id", 1L);

        Path dataFile = dataDir.resolve("data.parquet");
        writeParquetWithFieldIds(dataFile, dataWriteSchema, List.of(alice, bob));
        Path deleteData = metadataDir.resolve("equality-delete.parquet");
        writeParquetWithFieldIds(deleteData, deleteWriteSchema, List.of(deleteKey));

        LocalFileIO io = new LocalFileIO();
        Schema snapshotSchema = new Schema(
                Types.NestedField.required(1, "id", Types.LongType.get()),
                Types.NestedField.required(2, "name", Types.StringType.get()));
        PartitionSpec spec = PartitionSpec.unpartitioned();

        ManifestWriter<DataFile> dataWriter = ManifestFiles.write(2, spec,
                io.newOutputFile(metadataDir.resolve("manifest-1.avro").toString()), 2L);
        DataFile dataEntry = DataFiles.builder(spec)
                .withPath(dataFile.toUri().toString())
                .withFileSizeInBytes(Files.size(dataFile))
                .withRecordCount(2)
                .withFormat(FileFormat.PARQUET)
                .build();
        dataWriter.add(dataEntry, 1L);
        dataWriter.close();
        ManifestFile dataManifest = dataWriter.toManifestFile();

        ManifestWriter<org.apache.iceberg.DeleteFile> deleteWriter =
                ManifestFiles.writeDeleteManifest(2, spec,
                        io.newOutputFile(metadataDir.resolve("delete-manifest-1.avro").toString()),
                        2L);
        org.apache.iceberg.DeleteFile equalityDelete = newEqualityDelete(spec,
                deleteData.toUri().toString(), Files.size(deleteData), List.of(1));
        deleteWriter.add(equalityDelete, 2L);
        deleteWriter.close();
        ManifestFile deleteManifest = deleteWriter.toManifestFile();

        Path manifestList = metadataDir.resolve("snap-1.avro");
        org.apache.iceberg.IcebergTestManifestList.write(2, io.newOutputFile(manifestList.toString()),
                2L, null, 2L, null, List.of(dataManifest, deleteManifest));
        Snapshot snapshot = new SimpleSnapshot(2L, dataManifest, dataEntry,
                manifestList.toString(), deleteManifest);
        TableMetadata metadata = TableMetadata.buildFromEmpty()
                .assignUUID()
                .upgradeFormatVersion(2)
                .setLocation(tableDir.toUri().toString())
                .setCurrentSchema(snapshotSchema, 0)
                .addPartitionSpec(spec)
                .setDefaultPartitionSpec(spec)
                .addSortOrder(org.apache.iceberg.SortOrder.unsorted())
                .setDefaultSortOrder(org.apache.iceberg.SortOrder.unsorted())
                .setBranchSnapshot(snapshot, "main")
                .build();
        Path metadataFile = metadataDir.resolve("v1.metadata.json");
        TableMetadataParser.write(metadata, io.newOutputFile(metadataFile.toString()));
        return metadataFile;
    }

    private void writeParquetWithFieldIds(Path path, Schema schema,
                                          List<Record> rows) throws Exception {
        try (FileAppender<Record> appender = Parquet.write(
                        org.apache.iceberg.Files.localOutput(path.toString()))
                .schema(schema)
                .createWriterFunc(GenericParquetWriter::create)
                .build()) {
            for (Record row : rows) {
                appender.add(row);
            }
        }
    }

    @Test
    void shouldApplyEqualityDeleteFromAvroFile() throws Exception {
        Path metadata = createAvroEqualityDeleteTable(
                tempDir.resolve("equality-avro"));
        assertEquals(List.of("bob"), scanNames(metadata));
    }

    /**
     * The delete file is written as Avro with a renamed key column; the converter
     * must project it by field ID and apply it against the renamed data file.
     */
    private Path createAvroEqualityDeleteTable(Path tableDir) throws Exception {
        Path dataDir = tableDir.resolve("data");
        Path metadataDir = tableDir.resolve("metadata");
        Files.createDirectories(dataDir);
        Files.createDirectories(metadataDir);

        Schema dataWriteSchema = new Schema(
                Types.NestedField.required(1, "customer_id", Types.LongType.get()),
                Types.NestedField.required(2, "customer_name", Types.StringType.get()));
        Schema deleteWriteSchema = new Schema(
                Types.NestedField.required(1, "pk_id", Types.LongType.get()));
        Record alice = GenericRecord.create(dataWriteSchema);
        alice.setField("customer_id", 1L);
        alice.setField("customer_name", "alice");
        Record bob = GenericRecord.create(dataWriteSchema);
        bob.setField("customer_id", 2L);
        bob.setField("customer_name", "bob");
        org.apache.avro.Schema deleteAvroSchema =
                org.apache.iceberg.avro.AvroSchemaUtil.convert(deleteWriteSchema, "delete");
        org.apache.avro.generic.GenericData.Record deleteKey =
                new org.apache.avro.generic.GenericData.Record(deleteAvroSchema);
        deleteKey.put("pk_id", 1L);

        Path dataFile = dataDir.resolve("data.parquet");
        writeParquetWithFieldIds(dataFile, dataWriteSchema, List.of(alice, bob));
        Path deleteData = metadataDir.resolve("equality-delete.avro");
        writeAvroWithFieldIds(deleteData, deleteWriteSchema, List.of(deleteKey));

        LocalFileIO io = new LocalFileIO();
        Schema snapshotSchema = new Schema(
                Types.NestedField.required(1, "id", Types.LongType.get()),
                Types.NestedField.required(2, "name", Types.StringType.get()));
        PartitionSpec spec = PartitionSpec.unpartitioned();

        ManifestWriter<DataFile> dataWriter = ManifestFiles.write(2, spec,
                io.newOutputFile(metadataDir.resolve("manifest-1.avro").toString()), 2L);
        DataFile dataEntry = DataFiles.builder(spec)
                .withPath(dataFile.toUri().toString())
                .withFileSizeInBytes(Files.size(dataFile))
                .withRecordCount(2)
                .withFormat(FileFormat.PARQUET)
                .build();
        dataWriter.add(dataEntry, 1L);
        dataWriter.close();
        ManifestFile dataManifest = dataWriter.toManifestFile();

        ManifestWriter<org.apache.iceberg.DeleteFile> deleteWriter =
                ManifestFiles.writeDeleteManifest(2, spec,
                        io.newOutputFile(metadataDir.resolve("delete-manifest-1.avro").toString()),
                        2L);
        org.apache.iceberg.DeleteFile equalityDelete = newEqualityDelete(spec,
                deleteData.toUri().toString(), Files.size(deleteData), List.of(1),
                null, FileFormat.AVRO);
        deleteWriter.add(equalityDelete, 2L);
        deleteWriter.close();
        ManifestFile deleteManifest = deleteWriter.toManifestFile();

        Path manifestList = metadataDir.resolve("snap-1.avro");
        org.apache.iceberg.IcebergTestManifestList.write(2, io.newOutputFile(manifestList.toString()),
                2L, null, 2L, null, List.of(dataManifest, deleteManifest));
        Snapshot snapshot = new SimpleSnapshot(2L, dataManifest, dataEntry,
                manifestList.toString(), deleteManifest);
        TableMetadata metadata = TableMetadata.buildFromEmpty()
                .assignUUID()
                .upgradeFormatVersion(2)
                .setLocation(tableDir.toUri().toString())
                .setCurrentSchema(snapshotSchema, 0)
                .addPartitionSpec(spec)
                .setDefaultPartitionSpec(spec)
                .addSortOrder(org.apache.iceberg.SortOrder.unsorted())
                .setDefaultSortOrder(org.apache.iceberg.SortOrder.unsorted())
                .setBranchSnapshot(snapshot, "main")
                .build();
        Path metadataFile = metadataDir.resolve("v1.metadata.json");
        TableMetadataParser.write(metadata, io.newOutputFile(metadataFile.toString()));
        return metadataFile;
    }

    private void writeAvroWithFieldIds(Path path, Schema schema,
                                       List<org.apache.avro.generic.IndexedRecord> rows)
            throws Exception {
        org.apache.avro.Schema avroSchema =
                org.apache.iceberg.avro.AvroSchemaUtil.convert(schema, "delete");
        try (FileAppender<org.apache.avro.generic.IndexedRecord> appender = Avro.write(
                        org.apache.iceberg.Files.localOutput(path.toString()))
                .schema(schema)
                .createWriterFunc(GenericAvroWriter::create)
                .build()) {
            for (org.apache.avro.generic.IndexedRecord row : rows) {
                appender.add(row);
            }
        }
    }

    @Test
    void shouldApplyPositionDeletesToOrcData() throws Exception {
        Path metadata = createOrcPositionDeleteTable(
                tempDir.resolve("position-orc-data"));
        assertEquals(List.of("alice"), scanNames(metadata));
    }

    /** ORC data file + PARQUET position delete file. */
    private Path createOrcPositionDeleteTable(Path tableDir) throws Exception {
        Path dataDir = tableDir.resolve("data");
        Path metadataDir = tableDir.resolve("metadata");
        Files.createDirectories(dataDir);
        Files.createDirectories(metadataDir);
        org.apache.orc.TypeDescription orcSchema = org.apache.orc.TypeDescription.fromString(
                "struct<id:bigint,name:string>");
        orcSchema.getChildren().get(0).setAttribute("iceberg.id", "1");
        orcSchema.getChildren().get(1).setAttribute("iceberg.id", "2");
        Path dataFile = dataDir.resolve("data.orc");
        writeOrcRows(dataFile, orcSchema,
                java.util.List.<Object[]>of(new Object[]{1L, "alice"}, new Object[]{2L, "bob"}));
        Path deleteData = metadataDir.resolve("delete-data.parquet");
        writeParquet(deleteData, "SELECT '" + dataFile.toUri() + "' AS _file_path, 1 AS _pos");

        LocalFileIO io = new LocalFileIO();
        Schema schema = new Schema(
                Types.NestedField.required(1, "id", Types.LongType.get()),
                Types.NestedField.required(2, "name", Types.StringType.get()));
        PartitionSpec spec = PartitionSpec.unpartitioned();

        ManifestWriter<DataFile> dataWriter = ManifestFiles.write(2, spec,
                io.newOutputFile(metadataDir.resolve("manifest-1.avro").toString()), 2L);
        DataFile dataEntry = DataFiles.builder(spec)
                .withPath(dataFile.toUri().toString())
                .withFileSizeInBytes(Files.size(dataFile))
                .withRecordCount(2)
                .withFormat(FileFormat.ORC)
                .build();
        dataWriter.add(dataEntry, 1L);
        dataWriter.close();
        ManifestFile dataManifest = dataWriter.toManifestFile();

        ManifestWriter<org.apache.iceberg.DeleteFile> deleteWriter =
                ManifestFiles.writeDeleteManifest(2, spec,
                        io.newOutputFile(metadataDir.resolve("delete-manifest-1.avro").toString()),
                        2L);
        deleteWriter.add(newPositionDelete(spec, deleteData.toUri().toString(),
                Files.size(deleteData), 1L), 2L);
        deleteWriter.close();
        ManifestFile deleteManifest = deleteWriter.toManifestFile();

        Path manifestList = metadataDir.resolve("snap-1.avro");
        org.apache.iceberg.IcebergTestManifestList.write(2, io.newOutputFile(manifestList.toString()),
                2L, null, 2L, null, List.of(dataManifest, deleteManifest));
        Snapshot snapshot = new SimpleSnapshot(2L, dataManifest, dataEntry,
                manifestList.toString(), deleteManifest);
        TableMetadata metadata = TableMetadata.buildFromEmpty()
                .assignUUID()
                .upgradeFormatVersion(2)
                .setLocation(tableDir.toUri().toString())
                .setCurrentSchema(schema, 0)
                .addPartitionSpec(spec)
                .setDefaultPartitionSpec(spec)
                .addSortOrder(org.apache.iceberg.SortOrder.unsorted())
                .setDefaultSortOrder(org.apache.iceberg.SortOrder.unsorted())
                .setBranchSnapshot(snapshot, "main")
                .build();
        Path metadataFile = metadataDir.resolve("v1.metadata.json");
        TableMetadataParser.write(metadata, io.newOutputFile(metadataFile.toString()));
        return metadataFile;
    }

    @Test
    void shouldApplyEqualityDeleteFromOrcFile() throws Exception {
        Path metadata = createOrcEqualityDeleteTable(
                tempDir.resolve("equality-orc-delete-file"), false);
        assertEquals(List.of("bob"), scanNames(metadata));
    }

    @Test
    void shouldApplyEqualityDeletesToOrcData() throws Exception {
        Path metadata = createOrcEqualityDeleteTable(
                tempDir.resolve("equality-orc-data"), true);
        assertEquals(List.of("bob"), scanNames(metadata));
    }

    /**
     * Writes the data file and equality delete as ORC (field IDs carried in the
     * {@code iceberg.id} attribute) or keeps the data file as Parquet, then wires
     * them into a v2 table snapshot.
     */
    private Path createOrcEqualityDeleteTable(Path tableDir, boolean orcData) throws Exception {
        Path dataDir = tableDir.resolve("data");
        Path metadataDir = tableDir.resolve("metadata");
        Files.createDirectories(dataDir);
        Files.createDirectories(metadataDir);

        org.apache.orc.TypeDescription dataSchema = org.apache.orc.TypeDescription.fromString(
                "struct<customer_id:bigint,customer_name:string>");
        dataSchema.getChildren().get(0).setAttribute("iceberg.id", "1");
        dataSchema.getChildren().get(1).setAttribute("iceberg.id", "2");
        Path dataFile = dataDir.resolve(orcData ? "data.orc" : "data.parquet");
        if (orcData) {
            writeOrcRows(dataFile, dataSchema,
                    java.util.List.<Object[]>of(
                            new Object[]{1L, "alice"}, new Object[]{2L, "bob"}));
        } else {
            Schema dataWriteSchema = new Schema(
                    Types.NestedField.required(1, "customer_id", Types.LongType.get()),
                    Types.NestedField.required(2, "customer_name", Types.StringType.get()));
            Record alice = GenericRecord.create(dataWriteSchema);
            alice.setField("customer_id", 1L);
            alice.setField("customer_name", "alice");
            Record bob = GenericRecord.create(dataWriteSchema);
            bob.setField("customer_id", 2L);
            bob.setField("customer_name", "bob");
            writeParquetWithFieldIds(dataFile, dataWriteSchema, List.of(alice, bob));
        }

        org.apache.orc.TypeDescription deleteSchema = org.apache.orc.TypeDescription.fromString(
                "struct<pk_id:bigint>");
        deleteSchema.getChildren().get(0).setAttribute("iceberg.id", "1");
        Path deleteData = metadataDir.resolve("equality-delete.orc");
        writeOrcRows(deleteData, deleteSchema,
                java.util.List.<Object[]>of(new Object[]{1L}));

        LocalFileIO io = new LocalFileIO();
        Schema snapshotSchema = new Schema(
                Types.NestedField.required(1, "id", Types.LongType.get()),
                Types.NestedField.required(2, "name", Types.StringType.get()));
        PartitionSpec spec = PartitionSpec.unpartitioned();

        ManifestWriter<DataFile> dataWriter = ManifestFiles.write(2, spec,
                io.newOutputFile(metadataDir.resolve("manifest-1.avro").toString()), 2L);
        DataFile dataEntry = DataFiles.builder(spec)
                .withPath(dataFile.toUri().toString())
                .withFileSizeInBytes(Files.size(dataFile))
                .withRecordCount(2)
                .withFormat(orcData ? FileFormat.ORC : FileFormat.PARQUET)
                .build();
        dataWriter.add(dataEntry, 1L);
        dataWriter.close();
        ManifestFile dataManifest = dataWriter.toManifestFile();

        ManifestWriter<org.apache.iceberg.DeleteFile> deleteWriter =
                ManifestFiles.writeDeleteManifest(2, spec,
                        io.newOutputFile(metadataDir.resolve("delete-manifest-1.avro").toString()),
                        2L);
        org.apache.iceberg.DeleteFile equalityDelete = newEqualityDelete(spec,
                deleteData.toUri().toString(), Files.size(deleteData), List.of(1),
                null, FileFormat.ORC);
        deleteWriter.add(equalityDelete, 2L);
        deleteWriter.close();
        ManifestFile deleteManifest = deleteWriter.toManifestFile();

        Path manifestList = metadataDir.resolve("snap-1.avro");
        org.apache.iceberg.IcebergTestManifestList.write(2, io.newOutputFile(manifestList.toString()),
                2L, null, 2L, null, List.of(dataManifest, deleteManifest));
        Snapshot snapshot = new SimpleSnapshot(2L, dataManifest, dataEntry,
                manifestList.toString(), deleteManifest);
        TableMetadata metadata = TableMetadata.buildFromEmpty()
                .assignUUID()
                .upgradeFormatVersion(2)
                .setLocation(tableDir.toUri().toString())
                .setCurrentSchema(snapshotSchema, 0)
                .addPartitionSpec(spec)
                .setDefaultPartitionSpec(spec)
                .addSortOrder(org.apache.iceberg.SortOrder.unsorted())
                .setDefaultSortOrder(org.apache.iceberg.SortOrder.unsorted())
                .setBranchSnapshot(snapshot, "main")
                .build();
        Path metadataFile = metadataDir.resolve("v1.metadata.json");
        TableMetadataParser.write(metadata, io.newOutputFile(metadataFile.toString()));
        return metadataFile;
    }

    private void writeOrcRows(Path path, org.apache.orc.TypeDescription schema,
                              List<Object[]> rows) throws Exception {
        org.apache.hadoop.conf.Configuration conf = new org.apache.hadoop.conf.Configuration();
        try (org.apache.orc.Writer writer = org.apache.orc.OrcFile.createWriter(
                new org.apache.hadoop.fs.Path(path.toUri()),
                org.apache.orc.OrcFile.writerOptions(conf).setSchema(schema))) {
            org.apache.orc.storage.ql.exec.vector.VectorizedRowBatch batch =
                    schema.createRowBatch(rows.size());
            for (int row = 0; row < rows.size(); row++) {
                Object[] values = rows.get(row);
                for (int column = 0; column < values.length; column++) {
                    org.apache.orc.storage.ql.exec.vector.ColumnVector vector = batch.cols[column];
                    Object value = values[column];
                    if (value == null) {
                        vector.noNulls = false;
                        vector.isNull[row] = true;
                        continue;
                    }
                    switch (schema.getChildren().get(column).getCategory()) {
                        case BOOLEAN:
                            ((org.apache.orc.storage.ql.exec.vector.LongColumnVector) vector)
                                    .vector[row] = (Boolean) value ? 1 : 0;
                            break;
                        case BYTE:
                        case SHORT:
                        case INT:
                        case LONG:
                            ((org.apache.orc.storage.ql.exec.vector.LongColumnVector) vector)
                                    .vector[row] = ((Number) value).longValue();
                            break;
                        case STRING:
                        case VARCHAR:
                        case CHAR:
                            ((org.apache.orc.storage.ql.exec.vector.BytesColumnVector) vector)
                                    .setVal(row, value.toString()
                                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            break;
                        default:
                            throw new IllegalStateException("Unsupported test ORC type "
                                    + schema.getChildren().get(column));
                    }
                }
            }
            batch.size = rows.size();
            writer.addRowBatch(batch);
        }
    }

    @Test
    void shouldApplyMultipleEqualityDeleteFilesWithDifferentKeys() throws Exception {
        Path metadata = createMultiEqualityDeleteTable(
                tempDir.resolve("equality-multi"));
        assertEquals(List.of("carol"), scanNames(metadata));
    }

    @Test
    void shouldKeepSchemaWhenEqualityDeleteRemovesAllRows() throws Exception {
        Path metadata = createEqualityDeleteTable(tempDir.resolve("equality-all"),
                1L, 2L, List.of(1L, 2L));
        ResourceLocator resource = ResourceLocator.builder()
                .type("iceberg")
                .path(metadata.toString())
                .build();
        IcebergDatasetHandle handle = new IcebergDatasetHandle(resource, null);
        try {
            int rows = 0;
            try (var iterator = handle.getRecordScanner().get().scan(null)) {
                while (iterator.hasNext()) {
                    iterator.next();
                    rows++;
                }
            }
            assertEquals(0, rows);
            assertEquals(2, handle.getSchema().getFields().size());
        } finally {
            handle.close();
        }
    }

    @Test
    void shouldApplyEqualityDeleteOnlyToMatchingPartition() throws Exception {
        Path metadata = createPartitionedEqualityDeleteTable(
                tempDir.resolve("equality-partitioned"), "name=alice");
        assertEquals(List.of("bob"), scanNames(metadata));
    }

    @Test
    void shouldNotApplyEqualityDeleteToDifferentPartition() throws Exception {
        Path metadata = createPartitionedEqualityDeleteTable(
                tempDir.resolve("equality-other-partition"), "name=bob");
        assertEquals(List.of("alice", "bob"), scanNames(metadata));
    }

    private Path createPartitionedEqualityDeleteTable(Path tableDir,
                                                      String deletePartition) throws Exception {
        Path dataDir = tableDir.resolve("data");
        Path metadataDir = tableDir.resolve("metadata");
        Files.createDirectories(dataDir);
        Files.createDirectories(metadataDir);
        Path aliceFile = dataDir.resolve("alice.parquet");
        Path bobFile = dataDir.resolve("bob.parquet");
        writeParquet(aliceFile, "SELECT 1 AS id, 'alice' AS name, 10.0 AS amount");
        writeParquet(bobFile, "SELECT 2 AS id, 'bob' AS name, 20.0 AS amount");
        Path deleteData = metadataDir.resolve("equality-delete.parquet");
        writeParquet(deleteData, "SELECT 1 AS id");

        LocalFileIO io = new LocalFileIO();
        Schema schema = new Schema(
                Types.NestedField.required(1, "id", Types.LongType.get()),
                Types.NestedField.required(2, "name", Types.StringType.get()),
                Types.NestedField.required(3, "amount", Types.DoubleType.get()));
        PartitionSpec spec = PartitionSpec.builderFor(schema).identity("name").build();
        DataFile alice = DataFiles.builder(spec)
                .withPath(aliceFile.toUri().toString())
                .withFileSizeInBytes(Files.size(aliceFile))
                .withRecordCount(1)
                .withFormat(FileFormat.PARQUET)
                .withPartitionPath("name=alice")
                .build();
        DataFile bob = DataFiles.builder(spec)
                .withPath(bobFile.toUri().toString())
                .withFileSizeInBytes(Files.size(bobFile))
                .withRecordCount(1)
                .withFormat(FileFormat.PARQUET)
                .withPartitionPath("name=bob")
                .build();
        ManifestWriter<DataFile> dataWriter = ManifestFiles.write(2, spec,
                io.newOutputFile(metadataDir.resolve("manifest-1.avro").toString()), 2L);
        dataWriter.add(alice, 1L);
        dataWriter.add(bob, 1L);
        dataWriter.close();
        ManifestFile dataManifest = dataWriter.toManifestFile();

        ManifestWriter<org.apache.iceberg.DeleteFile> deleteWriter =
                ManifestFiles.writeDeleteManifest(2, spec,
                        io.newOutputFile(metadataDir.resolve("delete-manifest-1.avro").toString()),
                        2L);
        org.apache.iceberg.DeleteFile equalityDelete = newEqualityDelete(spec,
                deleteData.toUri().toString(), Files.size(deleteData), List.of(1),
                deletePartition);
        deleteWriter.add(equalityDelete, 2L);
        deleteWriter.close();
        ManifestFile deleteManifest = deleteWriter.toManifestFile();

        Path manifestList = metadataDir.resolve("snap-1.avro");
        org.apache.iceberg.IcebergTestManifestList.write(2, io.newOutputFile(manifestList.toString()),
                2L, null, 2L, null, List.of(dataManifest, deleteManifest));
        Snapshot snapshot = new SimpleSnapshot(2L, dataManifest, alice,
                manifestList.toString(), deleteManifest);
        TableMetadata metadata = TableMetadata.buildFromEmpty()
                .assignUUID()
                .upgradeFormatVersion(2)
                .setLocation(tableDir.toUri().toString())
                .setCurrentSchema(schema, 0)
                .addPartitionSpec(spec)
                .setDefaultPartitionSpec(spec)
                .addSortOrder(org.apache.iceberg.SortOrder.unsorted())
                .setDefaultSortOrder(org.apache.iceberg.SortOrder.unsorted())
                .setBranchSnapshot(snapshot, "main")
                .build();
        Path metadataFile = metadataDir.resolve("v1.metadata.json");
        TableMetadataParser.write(metadata, io.newOutputFile(metadataFile.toString()));
        return metadataFile;
    }

    private Path createMixedDeleteTable(Path tableDir) throws Exception {
        Path dataDir = tableDir.resolve("data");
        Path metadataDir = tableDir.resolve("metadata");
        Files.createDirectories(dataDir);
        Files.createDirectories(metadataDir);
        Path dataFile = dataDir.resolve("data.parquet");
        writeParquet(dataFile, "SELECT 1 AS id, 'alice' AS name"
                + " UNION ALL SELECT 2, 'bob' UNION ALL SELECT 3, 'carol'");
        Path positionDelete = metadataDir.resolve("position-delete.parquet");
        writeParquet(positionDelete, "SELECT '" + dataFile.toUri()
                + "' AS _file_path, 1 AS _pos");
        Path equalityDelete = metadataDir.resolve("equality-delete.parquet");
        writeParquet(equalityDelete, "SELECT 1 AS id");

        LocalFileIO io = new LocalFileIO();
        Schema schema = new Schema(
                Types.NestedField.required(1, "id", Types.LongType.get()),
                Types.NestedField.required(2, "name", Types.StringType.get()));
        PartitionSpec spec = PartitionSpec.unpartitioned();
        ManifestWriter<DataFile> dataWriter = ManifestFiles.write(2, spec,
                io.newOutputFile(metadataDir.resolve("manifest-1.avro").toString()), 2L);
        DataFile dataEntry = DataFiles.builder(spec)
                .withPath(dataFile.toUri().toString())
                .withFileSizeInBytes(Files.size(dataFile))
                .withRecordCount(3)
                .withFormat(FileFormat.PARQUET)
                .build();
        dataWriter.add(dataEntry, 1L);
        dataWriter.close();
        ManifestFile dataManifest = dataWriter.toManifestFile();

        ManifestWriter<org.apache.iceberg.DeleteFile> deleteWriter =
                ManifestFiles.writeDeleteManifest(2, spec,
                        io.newOutputFile(metadataDir.resolve("delete-manifest-1.avro").toString()),
                        2L);
        deleteWriter.add(newPositionDelete(spec, positionDelete.toUri().toString(),
                Files.size(positionDelete), 1L), 2L);
        deleteWriter.add(newEqualityDelete(spec, equalityDelete.toUri().toString(),
                Files.size(equalityDelete), List.of(1)), 2L);
        deleteWriter.close();
        ManifestFile deleteManifest = deleteWriter.toManifestFile();

        Path manifestList = metadataDir.resolve("snap-1.avro");
        org.apache.iceberg.IcebergTestManifestList.write(2, io.newOutputFile(manifestList.toString()),
                2L, null, 2L, null, List.of(dataManifest, deleteManifest));
        Snapshot snapshot = new SimpleSnapshot(2L, dataManifest, dataEntry,
                manifestList.toString(), deleteManifest);
        TableMetadata metadata = TableMetadata.buildFromEmpty()
                .assignUUID()
                .upgradeFormatVersion(2)
                .setLocation(tableDir.toUri().toString())
                .setCurrentSchema(schema, 0)
                .addPartitionSpec(spec)
                .setDefaultPartitionSpec(spec)
                .addSortOrder(org.apache.iceberg.SortOrder.unsorted())
                .setDefaultSortOrder(org.apache.iceberg.SortOrder.unsorted())
                .setBranchSnapshot(snapshot, "main")
                .build();
        Path metadataFile = metadataDir.resolve("v1.metadata.json");
        TableMetadataParser.write(metadata, io.newOutputFile(metadataFile.toString()));
        return metadataFile;
    }

    private Path createMultiEqualityDeleteTable(Path tableDir) throws Exception {
        Path dataDir = tableDir.resolve("data");
        Path metadataDir = tableDir.resolve("metadata");
        Files.createDirectories(dataDir);
        Files.createDirectories(metadataDir);
        Path dataFile = dataDir.resolve("data.parquet");
        writeParquet(dataFile, "SELECT 1 AS id, 'alice' AS name"
                + " UNION ALL SELECT 2, 'bob' UNION ALL SELECT 3, 'carol'");
        Path deleteId = metadataDir.resolve("delete-id.parquet");
        writeParquet(deleteId, "SELECT 1 AS id");
        Path deleteName = metadataDir.resolve("delete-name.parquet");
        writeParquet(deleteName, "SELECT 'bob' AS name");

        LocalFileIO io = new LocalFileIO();
        Schema schema = new Schema(
                Types.NestedField.required(1, "id", Types.LongType.get()),
                Types.NestedField.required(2, "name", Types.StringType.get()));
        PartitionSpec spec = PartitionSpec.unpartitioned();
        ManifestWriter<DataFile> dataWriter = ManifestFiles.write(2, spec,
                io.newOutputFile(metadataDir.resolve("manifest-1.avro").toString()), 2L);
        DataFile dataEntry = DataFiles.builder(spec)
                .withPath(dataFile.toUri().toString())
                .withFileSizeInBytes(Files.size(dataFile))
                .withRecordCount(3)
                .withFormat(FileFormat.PARQUET)
                .build();
        dataWriter.add(dataEntry, 1L);
        dataWriter.close();
        ManifestFile dataManifest = dataWriter.toManifestFile();

        ManifestWriter<org.apache.iceberg.DeleteFile> deleteWriter =
                ManifestFiles.writeDeleteManifest(2, spec,
                        io.newOutputFile(metadataDir.resolve("delete-manifest-1.avro").toString()),
                        2L);
        deleteWriter.add(newEqualityDelete(spec, deleteId.toUri().toString(),
                Files.size(deleteId), List.of(1)), 2L);
        deleteWriter.add(newEqualityDelete(spec, deleteName.toUri().toString(),
                Files.size(deleteName), List.of(2)), 2L);
        deleteWriter.close();
        ManifestFile deleteManifest = deleteWriter.toManifestFile();

        Path manifestList = metadataDir.resolve("snap-1.avro");
        org.apache.iceberg.IcebergTestManifestList.write(2, io.newOutputFile(manifestList.toString()),
                2L, null, 2L, null, List.of(dataManifest, deleteManifest));
        Snapshot snapshot = new SimpleSnapshot(2L, dataManifest, dataEntry,
                manifestList.toString(), deleteManifest);
        TableMetadata metadata = TableMetadata.buildFromEmpty()
                .assignUUID()
                .upgradeFormatVersion(2)
                .setLocation(tableDir.toUri().toString())
                .setCurrentSchema(schema, 0)
                .addPartitionSpec(spec)
                .setDefaultPartitionSpec(spec)
                .addSortOrder(org.apache.iceberg.SortOrder.unsorted())
                .setDefaultSortOrder(org.apache.iceberg.SortOrder.unsorted())
                .setBranchSnapshot(snapshot, "main")
                .build();
        Path metadataFile = metadataDir.resolve("v1.metadata.json");
        TableMetadataParser.write(metadata, io.newOutputFile(metadataFile.toString()));
        return metadataFile;
    }

    private Path createOrcDataEqualityDeleteTable(Path tableDir) throws Exception {
        Path dataDir = tableDir.resolve("data");
        Path metadataDir = tableDir.resolve("metadata");
        Files.createDirectories(dataDir);
        Files.createDirectories(metadataDir);
        Path dataFile = dataDir.resolve("data.orc");
        writeOrcFile(dataFile);
        Path deleteData = metadataDir.resolve("equality-delete.parquet");
        writeParquet(deleteData, "SELECT 1 AS id");

        LocalFileIO io = new LocalFileIO();
        Schema schema = new Schema(
                Types.NestedField.required(1, "id", Types.LongType.get()),
                Types.NestedField.required(2, "name", Types.StringType.get()));
        PartitionSpec spec = PartitionSpec.unpartitioned();
        ManifestWriter<DataFile> dataWriter = ManifestFiles.write(2, spec,
                io.newOutputFile(metadataDir.resolve("manifest-1.avro").toString()), 2L);
        DataFile dataEntry = DataFiles.builder(spec)
                .withPath(dataFile.toUri().toString())
                .withFileSizeInBytes(Files.size(dataFile))
                .withRecordCount(2)
                .withFormat(FileFormat.ORC)
                .build();
        dataWriter.add(dataEntry, 1L);
        dataWriter.close();
        ManifestFile dataManifest = dataWriter.toManifestFile();

        ManifestWriter<org.apache.iceberg.DeleteFile> deleteWriter =
                ManifestFiles.writeDeleteManifest(2, spec,
                        io.newOutputFile(metadataDir.resolve("delete-manifest-1.avro").toString()),
                        2L);
        org.apache.iceberg.DeleteFile equalityDelete = newEqualityDelete(spec,
                deleteData.toUri().toString(), Files.size(deleteData), List.of(1));
        deleteWriter.add(equalityDelete, 2L);
        deleteWriter.close();
        ManifestFile deleteManifest = deleteWriter.toManifestFile();

        Path manifestList = metadataDir.resolve("snap-1.avro");
        org.apache.iceberg.IcebergTestManifestList.write(2, io.newOutputFile(manifestList.toString()),
                2L, null, 2L, null, List.of(dataManifest, deleteManifest));
        Snapshot snapshot = new SimpleSnapshot(2L, dataManifest, dataEntry,
                manifestList.toString(), deleteManifest);
        TableMetadata metadata = TableMetadata.buildFromEmpty()
                .assignUUID()
                .upgradeFormatVersion(2)
                .setLocation(tableDir.toUri().toString())
                .setCurrentSchema(schema, 0)
                .addPartitionSpec(spec)
                .setDefaultPartitionSpec(spec)
                .addSortOrder(org.apache.iceberg.SortOrder.unsorted())
                .setDefaultSortOrder(org.apache.iceberg.SortOrder.unsorted())
                .setBranchSnapshot(snapshot, "main")
                .build();
        Path metadataFile = metadataDir.resolve("v1.metadata.json");
        TableMetadataParser.write(metadata, io.newOutputFile(metadataFile.toString()));
        return metadataFile;
    }

    private List<String> scanNames(Path metadata) throws Exception {
        ResourceLocator resource = ResourceLocator.builder()
                .type("iceberg")
                .path(metadata.toString())
                .build();
        IcebergDatasetHandle handle = new IcebergDatasetHandle(resource, null);
        try {
            List<String> names = new java.util.ArrayList<>();
            try (var iterator = handle.getRecordScanner().get().scan(null)) {
                while (iterator.hasNext()) {
                    var values = iterator.next().getValues();
                    var nameValue = values.get("name") != null
                            ? values.get("name") : values.get("customer_name");
                    names.add(String.valueOf(nameValue.getValue()));
                }
            }
            return names;
        } finally {
            handle.close();
        }
    }

    private Path createEqualityDeleteTable(Path tableDir, long dataSequence,
                                           long deleteSequence) throws Exception {
        return createEqualityDeleteTable(tableDir, dataSequence, deleteSequence,
                List.of(1L));
    }

    private Path createEqualityDeleteTable(Path tableDir, long dataSequence,
                                           long deleteSequence,
                                           List<Long> deleteIds) throws Exception {
        return createEqualityDeleteTable(tableDir, dataSequence, deleteSequence,
                deleteIds, null);
    }

    private Path createEqualityDeleteTable(Path tableDir, long dataSequence,
                                           long deleteSequence, List<Long> deleteIds,
                                           String deleteLocation) throws Exception {
        Path dataDir = tableDir.resolve("data");
        Path metadataDir = tableDir.resolve("metadata");
        Files.createDirectories(dataDir);
        Files.createDirectories(metadataDir);
        Path dataFile = dataDir.resolve("data.parquet");
        writeParquet(dataFile, "SELECT 1 AS id, 'alice' AS name UNION ALL SELECT 2, 'bob'");
        Path deleteData = metadataDir.resolve("equality-delete.parquet");
        String deleteSelect = deleteIds.stream()
                .map(id -> "SELECT " + id + " AS id")
                .collect(java.util.stream.Collectors.joining(" UNION ALL "));
        writeParquet(deleteData, deleteSelect);

        LocalFileIO io = new LocalFileIO();
        Schema schema = new Schema(
                Types.NestedField.required(1, "id", Types.LongType.get()),
                Types.NestedField.required(2, "name", Types.StringType.get()));
        PartitionSpec spec = PartitionSpec.unpartitioned();

        ManifestWriter<DataFile> dataWriter = ManifestFiles.write(2, spec,
                io.newOutputFile(metadataDir.resolve("manifest-1.avro").toString()), 2L);
        DataFile dataEntry = DataFiles.builder(spec)
                .withPath(dataFile.toUri().toString())
                .withFileSizeInBytes(Files.size(dataFile))
                .withRecordCount(2)
                .withFormat(FileFormat.PARQUET)
                .build();
        dataWriter.add(dataEntry, dataSequence);
        dataWriter.close();
        ManifestFile dataManifest = dataWriter.toManifestFile();

        ManifestWriter<org.apache.iceberg.DeleteFile> deleteWriter =
                ManifestFiles.writeDeleteManifest(2, spec,
                        io.newOutputFile(metadataDir.resolve("delete-manifest-1.avro").toString()),
                        2L);
        org.apache.iceberg.DeleteFile equalityDelete = newEqualityDelete(spec,
                deleteLocation != null ? deleteLocation : deleteData.toUri().toString(),
                Files.size(deleteData), List.of(1));
        deleteWriter.add(equalityDelete, deleteSequence);
        deleteWriter.close();
        ManifestFile deleteManifest = deleteWriter.toManifestFile();

        Path manifestList = metadataDir.resolve("snap-1.avro");
        org.apache.iceberg.IcebergTestManifestList.write(2, io.newOutputFile(manifestList.toString()),
                2L, null, 2L, null, List.of(dataManifest, deleteManifest));
        Snapshot snapshot = new SimpleSnapshot(2L, dataManifest, dataEntry,
                manifestList.toString(), deleteManifest);
        TableMetadata metadata = TableMetadata.buildFromEmpty()
                .assignUUID()
                .upgradeFormatVersion(2)
                .setLocation(tableDir.toUri().toString())
                .setCurrentSchema(schema, 0)
                .addPartitionSpec(spec)
                .setDefaultPartitionSpec(spec)
                .addSortOrder(org.apache.iceberg.SortOrder.unsorted())
                .setDefaultSortOrder(org.apache.iceberg.SortOrder.unsorted())
                .setBranchSnapshot(snapshot, "main")
                .build();
        Path metadataFile = metadataDir.resolve("v1.metadata.json");
        TableMetadataParser.write(metadata, io.newOutputFile(metadataFile.toString()));
        return metadataFile;
    }

    private org.apache.iceberg.DeleteFile newEqualityDelete(PartitionSpec spec,
                                                            String deletePath,
                                                            long fileSize,
                                                            List<Integer> fieldIds) throws Exception {
        return newEqualityDelete(spec, deletePath, fileSize, fieldIds, null);
    }

    private org.apache.iceberg.DeleteFile newEqualityDelete(PartitionSpec spec,
                                                            String deletePath,
                                                            long fileSize,
                                                            List<Integer> fieldIds,
                                                            String partitionPath) throws Exception {
        return newEqualityDelete(spec, deletePath, fileSize, fieldIds, partitionPath,
                FileFormat.PARQUET);
    }

    private org.apache.iceberg.DeleteFile newEqualityDelete(PartitionSpec spec,
                                                            String deletePath,
                                                            long fileSize,
                                                            List<Integer> fieldIds,
                                                            String partitionPath,
                                                            FileFormat format) throws Exception {
        Class<?> clazz = Class.forName("org.apache.iceberg.GenericDeleteFile");
        java.lang.reflect.Constructor<?> ctor = clazz.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object deleteFile = ctor.newInstance();
        Class<?> base = clazz.getSuperclass();
        set(base, deleteFile, "content", org.apache.iceberg.FileContent.EQUALITY_DELETES);
        set(base, deleteFile, "filePath", deletePath);
        set(base, deleteFile, "format", format);
        set(base, deleteFile, "recordCount", 1L);
        set(base, deleteFile, "fileSizeInBytes", fileSize);
        set(base, deleteFile, "partitionSpecId", spec.specId());
        set(base, deleteFile, "partitionData", partitionPath == null
                ? org.apache.iceberg.DataFilesBridge.empty(spec)
                : org.apache.iceberg.DataFilesBridge.partition(spec, partitionPath));
        set(base, deleteFile, "equalityIds",
                fieldIds.stream().mapToInt(Integer::intValue).toArray());
        return (org.apache.iceberg.DeleteFile) deleteFile;
    }

    /**
     * Test-only DeleteFile construction: GenericDeleteFile is package-private in
     * Iceberg, so fields are populated reflectively.
     */
    private org.apache.iceberg.DeleteFile newPositionDelete(PartitionSpec spec,
                                                            String dataPath,
                                                            long fileSize,
                                                            long recordCount) throws Exception {
        return newPositionDelete(spec, dataPath, fileSize, recordCount, FileFormat.PARQUET);
    }

    private org.apache.iceberg.DeleteFile newPositionDelete(PartitionSpec spec,
                                                            String dataPath,
                                                            long fileSize,
                                                            long recordCount,
                                                            FileFormat format) throws Exception {
        Class<?> clazz = Class.forName("org.apache.iceberg.GenericDeleteFile");
        java.lang.reflect.Constructor<?> ctor = clazz.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object deleteFile = ctor.newInstance();
        Class<?> base = clazz.getSuperclass();
        set(base, deleteFile, "content", org.apache.iceberg.FileContent.POSITION_DELETES);
        set(base, deleteFile, "filePath", dataPath);
        set(base, deleteFile, "format", format);
        set(base, deleteFile, "recordCount", recordCount);
        set(base, deleteFile, "fileSizeInBytes", fileSize);
        set(base, deleteFile, "partitionSpecId", 0);
        set(base, deleteFile, "partitionData", org.apache.iceberg.DataFilesBridge.empty(spec));
        return (org.apache.iceberg.DeleteFile) deleteFile;
    }

    private static void set(Class<?> type, Object target, String field, Object value) throws Exception {
        java.lang.reflect.Field declared = type.getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(target, value);
    }

    @Test
    void shouldScanIcebergOrcSnapshot() throws Exception {
        Path tableDir = tempDir.resolve("orc-table");
        Path dataDir = tableDir.resolve("data");
        Path metadataDir = tableDir.resolve("metadata");
        Files.createDirectories(dataDir);
        Files.createDirectories(metadataDir);
        Path dataFile = dataDir.resolve("data.orc");
        writeOrcFile(dataFile);

        LocalFileIO io = new LocalFileIO();
        Schema schema = new Schema(
                Types.NestedField.required(1, "id", Types.LongType.get()),
                Types.NestedField.required(2, "name", Types.StringType.get()));
        PartitionSpec spec = PartitionSpec.unpartitioned();
        Path manifestPath = metadataDir.resolve("manifest-1.avro");
        ManifestWriter<DataFile> manifestWriter = ManifestFiles.write(2, spec,
                io.newOutputFile(manifestPath.toString()), 1L);
        DataFile file = DataFiles.builder(spec)
                .withPath(dataFile.toUri().toString())
                .withFileSizeInBytes(Files.size(dataFile))
                .withRecordCount(2)
                .withFormat(FileFormat.ORC)
                .build();
        manifestWriter.add(file);
        manifestWriter.close();
        ManifestFile manifest = manifestWriter.toManifestFile();
        Path manifestList = metadataDir.resolve("snap-1.avro");
        org.apache.iceberg.IcebergTestManifestList.write(2, io.newOutputFile(manifestList.toString()),
                1L, null, 1L, null, List.of(manifest));
        Snapshot snapshot = new SimpleSnapshot(1, manifest, file, manifestList.toString());
        TableMetadata metadata = TableMetadata.buildFromEmpty()
                .assignUUID()
                .upgradeFormatVersion(2)
                .setLocation(tableDir.toUri().toString())
                .setCurrentSchema(schema, 0)
                .addPartitionSpec(spec)
                .setDefaultPartitionSpec(spec)
                .addSortOrder(org.apache.iceberg.SortOrder.unsorted())
                .setDefaultSortOrder(org.apache.iceberg.SortOrder.unsorted())
                .setBranchSnapshot(snapshot, "main")
                .build();
        Path metadataFile = metadataDir.resolve("v1.metadata.json");
        TableMetadataParser.write(metadata, io.newOutputFile(metadataFile.toString()));

        ResourceLocator resource = ResourceLocator.builder()
                .type("iceberg")
                .path(metadataFile.toString())
                .build();
        IcebergDatasetHandle handle = new IcebergDatasetHandle(resource, null);
        try {
            int rows = 0;
            try (var iterator = handle.getRecordScanner().get().scan(null)) {
                while (iterator.hasNext()) {
                    iterator.next();
                    rows++;
                }
            }
            assertEquals(2, rows);
        } finally {
            handle.close();
        }
    }

    @Test
    void shouldScanIcebergSnapshotThroughFileConnector() throws Exception {
        Path metadata = createTable(tempDir.resolve("table"), 10.0, 20.0);

        ResourceLocator resource = ResourceLocator.builder()
                .type("iceberg")
                .path(metadata.toString())
                .options(Map.of("snapshotId", "1"))
                .build();
        IcebergDatasetHandle handle = new IcebergDatasetHandle(resource, null);
        try {
            assertEquals(3, handle.getSchema().getFields().size());
            handle.getSchema().getFieldMap().forEach((name, field) ->
                    assertTrue(List.of("id", "name", "amount").contains(name)));
            int rows = 0;
            try (var iterator = handle.getRecordScanner().get().scan(null)) {
                while (iterator.hasNext()) {
                    iterator.next();
                    rows++;
                }
            }
            assertEquals(2, rows);
            assertEquals(1L, handle.snapshotId());
        } finally {
            handle.close();
        }
    }

    @Test
    void shouldDiffIcebergEqualityDeletesAgainstCsv() throws Exception {
        Path metadata = createEqualityDeleteTable(tempDir.resolve("equality-diff"), 1L, 2L);
        Path target = tempDir.resolve("equality-target.csv");
        Files.writeString(target, "id,name\n2,bob\n");

        CompareRequest request = CompareRequest.builder()
                .source(ConnectorConfig.builder()
                        .type("iceberg")
                        .name("source")
                        .resource(ResourceLocator.builder()
                                .type("iceberg")
                                .path(metadata.toString())
                                .build())
                        .build())
                .target(ConnectorConfig.builder()
                        .type("file")
                        .name("target")
                        .resource(ResourceLocator.builder()
                                .type("file")
                                .path(target.toString())
                                .options(Map.of("format", "csv"))
                                .build())
                        .build())
                .sourceKeySpec(KeySpec.builder().fields(List.of("id")).build())
                .targetKeySpec(KeySpec.builder().fields(List.of("id")).build())
                .sourceComparisons(ComparisonSpec.builder().fields(List.of("name")).build())
                .targetComparisons(ComparisonSpec.builder().fields(List.of("name")).build())
                .build();

        DiffResult result = new DefaultCompareRuntime().execute(request);
        assertEquals(0L, result.getStatistics().getMismatchCount());
    }

    @Test
    void shouldDiffIcebergAgainstCsv() throws Exception {
        Path metadata = createTable(tempDir.resolve("source-table"), 10.0, 20.0);
        Path target = tempDir.resolve("target.csv");
        Files.writeString(target, "id,name,amount\n1,alice,10.0\n2,bob,21.0\n");

        CompareRequest request = CompareRequest.builder()
                .source(ConnectorConfig.builder()
                        .type("iceberg")
                        .name("source")
                        .resource(ResourceLocator.builder()
                                .type("iceberg")
                                .path(metadata.toString())
                                .build())
                        .build())
                .target(ConnectorConfig.builder()
                        .type("file")
                        .name("target")
                        .resource(ResourceLocator.builder()
                                .type("file")
                                .path(target.toString())
                                .options(Map.of("format", "csv"))
                                .build())
                        .build())
                .sourceKeySpec(KeySpec.builder().fields(List.of("id")).build())
                .targetKeySpec(KeySpec.builder().fields(List.of("id")).build())
                .sourceComparisons(ComparisonSpec.builder().fields(List.of("name", "amount")).build())
                .targetComparisons(ComparisonSpec.builder().fields(List.of("name", "amount")).build())
                .build();

        DiffResult result = new DefaultCompareRuntime().execute(request);

        assertEquals(1L, result.getStatistics().getMismatchCount());
        assertEquals("2", String.valueOf(result.getDifferences().get(0).getPrimaryKey().get(0)));
    }

    private void writeOrcFile(Path path) throws Exception {
        org.apache.hadoop.conf.Configuration conf = new org.apache.hadoop.conf.Configuration();
        org.apache.orc.TypeDescription schema = org.apache.orc.TypeDescription.fromString(
                "struct<id:bigint,name:string>");
        try (org.apache.orc.Writer orcWriter = org.apache.orc.OrcFile.createWriter(
                new org.apache.hadoop.fs.Path(path.toUri()),
                org.apache.orc.OrcFile.writerOptions(conf).setSchema(schema))) {
            org.apache.orc.storage.ql.exec.vector.VectorizedRowBatch batch =
                    schema.createRowBatch(2);
            org.apache.orc.storage.ql.exec.vector.LongColumnVector idVector =
                    (org.apache.orc.storage.ql.exec.vector.LongColumnVector) batch.cols[0];
            org.apache.orc.storage.ql.exec.vector.BytesColumnVector nameVector =
                    (org.apache.orc.storage.ql.exec.vector.BytesColumnVector) batch.cols[1];
            idVector.vector[0] = 1;
            idVector.vector[1] = 2;
            byte[] alice = "alice".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            nameVector.setRef(0, alice, 0, alice.length);
            byte[] bob = "bob".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            nameVector.setRef(1, bob, 0, bob.length);
            batch.size = 2;
            orcWriter.addRowBatch(batch);
        }
    }

    private Path createPartitionedTable(Path tableDir) throws Exception {
        Path dataDir = tableDir.resolve("data");
        Path metadataDir = tableDir.resolve("metadata");
        Files.createDirectories(dataDir);
        Files.createDirectories(metadataDir);
        Path aliceFile = dataDir.resolve("alice.parquet");
        Path bobFile = dataDir.resolve("bob.parquet");
        writeParquet(aliceFile, "SELECT 1 AS id, 'alice' AS name, 10.0 AS amount");
        writeParquet(bobFile, "SELECT 2 AS id, 'bob' AS name, 20.0 AS amount");

        LocalFileIO io = new LocalFileIO();
        Schema schema = new Schema(
                Types.NestedField.required(1, "id", Types.LongType.get()),
                Types.NestedField.required(2, "name", Types.StringType.get()),
                Types.NestedField.required(3, "amount", Types.DoubleType.get()));
        PartitionSpec spec = PartitionSpec.builderFor(schema).identity("name").build();
        DataFile alice = DataFiles.builder(spec)
                .withPath(aliceFile.toUri().toString())
                .withFileSizeInBytes(Files.size(aliceFile))
                .withRecordCount(1)
                .withFormat(FileFormat.PARQUET)
                .withPartitionPath("name=alice")
                .build();
        DataFile bob = DataFiles.builder(spec)
                .withPath(bobFile.toUri().toString())
                .withFileSizeInBytes(Files.size(bobFile))
                .withRecordCount(1)
                .withFormat(FileFormat.PARQUET)
                .withPartitionPath("name=bob")
                .build();
        Path manifestPath = metadataDir.resolve("manifest-1.avro");
        ManifestWriter<DataFile> writer = ManifestFiles.write(2, spec,
                io.newOutputFile(manifestPath.toString()), 1L);
        writer.add(alice);
        writer.add(bob);
        writer.close();
        ManifestFile manifest = writer.toManifestFile();
        Path manifestList = metadataDir.resolve("snap-1.avro");
        org.apache.iceberg.IcebergTestManifestList.write(2, io.newOutputFile(manifestList.toString()),
                1L, null, 1L, null, List.of(manifest));
        Snapshot snapshot = new SimpleSnapshot(1, manifest, alice, manifestList.toString());
        TableMetadata metadata = TableMetadata.buildFromEmpty()
                .assignUUID()
                .upgradeFormatVersion(2)
                .setLocation(tableDir.toUri().toString())
                .setCurrentSchema(schema, 0)
                .addPartitionSpec(spec)
                .setDefaultPartitionSpec(spec)
                .addSortOrder(org.apache.iceberg.SortOrder.unsorted())
                .setDefaultSortOrder(org.apache.iceberg.SortOrder.unsorted())
                .setBranchSnapshot(snapshot, "main")
                .build();
        Path metadataFile = metadataDir.resolve("v1.metadata.json");
        TableMetadataParser.write(metadata, io.newOutputFile(metadataFile.toString()));
        return metadataFile;
    }

    /** 单字段 DATE 分区值：manifest 里存的是距 1970 的天数。 */
    private static org.apache.iceberg.StructLike datePartition(int epochDay) {
        return new org.apache.iceberg.StructLike() {
            @Override
            public int size() {
                return 1;
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T get(int pos, Class<T> javaClass) {
                return (T) Integer.valueOf(epochDay);
            }

            @Override
            public <T> void set(int pos, T value) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private Path createDatePartitionedTable(Path tableDir) throws Exception {
        Path dataDir = tableDir.resolve("data");
        Path metadataDir = tableDir.resolve("metadata");
        Files.createDirectories(dataDir);
        Files.createDirectories(metadataDir);
        Path first = dataDir.resolve("dt=2024-01-01.parquet");
        Path second = dataDir.resolve("dt=2024-01-02.parquet");
        writeParquet(first, "SELECT 1 AS id, DATE '2024-01-01' AS dt, 10.0 AS amount");
        writeParquet(second, "SELECT 2 AS id, DATE '2024-01-02' AS dt, 20.0 AS amount");

        LocalFileIO io = new LocalFileIO();
        Schema schema = new Schema(
                Types.NestedField.required(1, "id", Types.LongType.get()),
                Types.NestedField.required(2, "dt", Types.DateType.get()),
                Types.NestedField.required(3, "amount", Types.DoubleType.get()));
        PartitionSpec spec = PartitionSpec.builderFor(schema).identity("dt").build();
        DataFile firstFile = DataFiles.builder(spec)
                .withPath(first.toUri().toString())
                .withFileSizeInBytes(Files.size(first))
                .withRecordCount(1)
                .withFormat(FileFormat.PARQUET)
                .withPartition(datePartition(
                        (int) java.time.LocalDate.parse("2024-01-01").toEpochDay()))
                .build();
        DataFile secondFile = DataFiles.builder(spec)
                .withPath(second.toUri().toString())
                .withFileSizeInBytes(Files.size(second))
                .withRecordCount(1)
                .withFormat(FileFormat.PARQUET)
                .withPartition(datePartition(
                        (int) java.time.LocalDate.parse("2024-01-02").toEpochDay()))
                .build();
        Path manifestPath = metadataDir.resolve("manifest-1.avro");
        ManifestWriter<DataFile> writer = ManifestFiles.write(2, spec,
                io.newOutputFile(manifestPath.toString()), 1L);
        writer.add(firstFile);
        writer.add(secondFile);
        writer.close();
        ManifestFile manifest = writer.toManifestFile();
        Path manifestList = metadataDir.resolve("snap-1.avro");
        org.apache.iceberg.IcebergTestManifestList.write(2, io.newOutputFile(manifestList.toString()),
                1L, null, 1L, null, List.of(manifest));
        Snapshot snapshot = new SimpleSnapshot(1, manifest, firstFile, manifestList.toString());
        TableMetadata metadata = TableMetadata.buildFromEmpty()
                .assignUUID()
                .upgradeFormatVersion(2)
                .setLocation(tableDir.toUri().toString())
                .setCurrentSchema(schema, 0)
                .addPartitionSpec(spec)
                .setDefaultPartitionSpec(spec)
                .addSortOrder(org.apache.iceberg.SortOrder.unsorted())
                .setDefaultSortOrder(org.apache.iceberg.SortOrder.unsorted())
                .setBranchSnapshot(snapshot, "main")
                .build();
        Path metadataFile = metadataDir.resolve("v1.metadata.json");
        TableMetadataParser.write(metadata, io.newOutputFile(metadataFile.toString()));
        return metadataFile;
    }

    private Path createTable(Path tableDir, double firstAmount, double secondAmount) throws Exception {
        return createTable(tableDir, firstAmount, secondAmount, null);
    }

    private void writeParquet(Path path, String select) throws Exception {
        writeFile(path, select, FileFormat.PARQUET);
    }

    private void writeFile(Path path, String select, FileFormat format) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
             Statement statement = connection.createStatement()) {
            statement.execute("COPY (" + select + ") TO '"
                    + path.toAbsolutePath().toString().replace("'", "''")
                    + "' (FORMAT " + format.name() + ")");
        }
    }

    private Path createTable(Path tableDir, double firstAmount, double secondAmount,
                             String dataPathOverride) throws Exception {
        Path dataDir = tableDir.resolve("data");
        Path metadataDir = tableDir.resolve("metadata");
        Files.createDirectories(dataDir);
        Files.createDirectories(metadataDir);
        Path dataFile = dataDir.resolve("data.parquet");
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
             Statement statement = connection.createStatement()) {
            statement.execute("COPY (SELECT 1 AS id, 'alice' AS name, CAST(" + firstAmount
                    + " AS DOUBLE) AS amount UNION ALL SELECT 2, 'bob', CAST(" + secondAmount
                    + " AS DOUBLE)) TO '" + dataFile.toAbsolutePath().toString()
                    .replace("'", "''") + "' (FORMAT PARQUET)");
        }

        LocalFileIO io = new LocalFileIO();
        Schema schema = new Schema(
                Types.NestedField.required(1, "id", Types.LongType.get()),
                Types.NestedField.required(2, "name", Types.StringType.get()),
                Types.NestedField.required(3, "amount", Types.DoubleType.get()));
        PartitionSpec spec = PartitionSpec.unpartitioned();
        Path manifestPath = metadataDir.resolve("manifest-1.avro");
        ManifestWriter<DataFile> writer = ManifestFiles.write(2, spec,
                io.newOutputFile(manifestPath.toString()), 1L);
        DataFile file = DataFiles.builder(spec)
                .withPath(dataPathOverride != null ? dataPathOverride : dataFile.toUri().toString())
                .withFileSizeInBytes(Files.size(dataFile))
                .withRecordCount(2)
                .withFormat(FileFormat.PARQUET)
                .build();
        writer.add(file);
        writer.close();
        ManifestFile manifest = writer.toManifestFile();
        Path manifestList = metadataDir.resolve("snap-1.avro");
        org.apache.iceberg.IcebergTestManifestList.write(2, io.newOutputFile(manifestList.toString()),
                1L, null, 1L, null, List.of(manifest));

        Snapshot snapshot = new SimpleSnapshot(1, manifest, file,
                manifestList.toString());
        TableMetadata metadata = TableMetadata.buildFromEmpty()
                .assignUUID()
                .upgradeFormatVersion(2)
                .setLocation(tableDir.toUri().toString())
                .setCurrentSchema(schema, 0)
                .addPartitionSpec(spec)
                .setDefaultPartitionSpec(spec)
                .addSortOrder(org.apache.iceberg.SortOrder.unsorted())
                .setDefaultSortOrder(org.apache.iceberg.SortOrder.unsorted())
                .setBranchSnapshot(snapshot, "main")
                .build();
        Path metadataFile = metadataDir.resolve("v1.metadata.json");
        TableMetadataParser.write(metadata, io.newOutputFile(metadataFile.toString()));
        return metadataFile;
    }

    private static final class DeleteSnapshot implements Snapshot {

        private final ManifestFile deleteManifest;
        private final String manifestList;

        private DeleteSnapshot(ManifestFile deleteManifest, String manifestList) {
            this.deleteManifest = deleteManifest;
            this.manifestList = manifestList;
        }

        @Override
        public long sequenceNumber() {
            return 1;
        }

        @Override
        public long snapshotId() {
            return 1;
        }

        @Override
        public Long parentId() {
            return null;
        }

        @Override
        public long timestampMillis() {
            return System.currentTimeMillis();
        }

        @Override
        public List<ManifestFile> allManifests(FileIO io) {
            return List.of(deleteManifest);
        }

        @Override
        public List<ManifestFile> dataManifests(FileIO io) {
            return List.of();
        }

        @Override
        public List<ManifestFile> deleteManifests(FileIO io) {
            return List.of(deleteManifest);
        }

        @Override
        public String operation() {
            return "delete";
        }

        @Override
        public Map<String, String> summary() {
            return Map.of("deleted-data-files", "1");
        }

        @Override
        public Iterable<DataFile> addedDataFiles(FileIO io) {
            return List.of();
        }

        @Override
        public Iterable<DataFile> removedDataFiles(FileIO io) {
            return List.of();
        }

        @Override
        public String manifestListLocation() {
            return manifestList;
        }

        @Override
        public Integer schemaId() {
            return 0;
        }
    }

    private static final class SimpleSnapshot implements Snapshot {

        private final long id;
        private final ManifestFile manifest;
        private final DataFile dataFile;
        private final String manifestList;
        private final ManifestFile deleteManifest;

        private SimpleSnapshot(long id, ManifestFile manifest, DataFile dataFile,
                               String manifestList) {
            this(id, manifest, dataFile, manifestList, null);
        }

        private SimpleSnapshot(long id, ManifestFile manifest, DataFile dataFile,
                               String manifestList, ManifestFile deleteManifest) {
            this.id = id;
            this.manifest = manifest;
            this.dataFile = dataFile;
            this.manifestList = manifestList;
            this.deleteManifest = deleteManifest;
        }

        @Override
        public long sequenceNumber() {
            return 1;
        }

        @Override
        public long snapshotId() {
            return id;
        }

        @Override
        public Long parentId() {
            return null;
        }

        @Override
        public long timestampMillis() {
            return System.currentTimeMillis();
        }

        @Override
        public List<ManifestFile> allManifests(FileIO io) {
            return List.of(manifest);
        }

        @Override
        public List<ManifestFile> dataManifests(FileIO io) {
            return List.of(manifest);
        }

        @Override
        public List<ManifestFile> deleteManifests(FileIO io) {
            return deleteManifest == null ? List.of() : List.of(deleteManifest);
        }

        @Override
        public String operation() {
            return "append";
        }

        @Override
        public Map<String, String> summary() {
            return Map.of("added-data-files", "1");
        }

        @Override
        public Iterable<DataFile> addedDataFiles(FileIO io) {
            return List.of(dataFile);
        }

        @Override
        public Iterable<DataFile> removedDataFiles(FileIO io) {
            return List.of();
        }

        @Override
        public String manifestListLocation() {
            return manifestList;
        }

        @Override
        public Integer schemaId() {
            return 0;
        }
    }

    private static final class FakeS3Store implements S3ObjectStore {

        private final Map<String, Path> objects = new java.util.LinkedHashMap<>();

        private FakeS3Store put(String location, Path localFile) {
            objects.put(location, localFile);
            return this;
        }

        @Override
        public void download(String location, Path target) {
            Path source = objects.get(location);
            if (source == null) {
                throw new IllegalStateException("Missing fake S3 object: " + location);
            }
            try {
                Path parent = target.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.io.IOException exception) {
                throw new IllegalStateException("Failed to fake-download " + location, exception);
            }
        }

        @Override
        public List<String> list(String location) {
            return objects.keySet().stream()
                    .filter(key -> key.startsWith(location))
                    .sorted()
                    .collect(java.util.stream.Collectors.toList());
        }
    }
}
