package com.consilens.connector.iceberg;

import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.config.ReadOptions;
import com.consilens.connector.api.dataset.DatasetHandle;
import com.consilens.connector.api.dataset.DatasetMetadata;
import com.consilens.connector.api.dataset.FilterPushdownProvider;
import com.consilens.connector.api.dataset.HashProvider;
import com.consilens.connector.api.dataset.KeyLookupProvider;
import com.consilens.connector.api.dataset.RecordScanner;
import com.consilens.connector.api.dataset.SnapshotProvider;
import com.consilens.connector.api.dataset.SplitPlanner;
import com.consilens.connector.api.model.ResourceLocator;
import com.consilens.connector.api.model.SchemaDescriptor;
import com.consilens.connector.file.FileDatasetHandle;
import com.consilens.connector.file.S3ObjectStore;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.avro.Avro;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.avro.DataReader;
import org.apache.iceberg.data.parquet.GenericParquetWriter;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.FileAppender;
import org.apache.iceberg.parquet.Parquet;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Iceberg table snapshot exposed as a set of local Parquet files. The actual
 * schema inference and scanning are delegated to {@link FileDatasetHandle}.
 */
final class IcebergDatasetHandle implements DatasetHandle {

    private final ResourceLocator resource;
    private final ReadOptions readOptions;
    private final IcebergTableResolver.ResolvedTable table;
    private final Path tempDir;
    private final S3ObjectStore s3Store;
    private final FileDatasetHandle delegate;

    IcebergDatasetHandle(ResourceLocator resource, ReadOptions readOptions) {
        this(resource, readOptions, null);
    }

    /**
     * @param injectedStore optional S3 store for tests; production passes null
     */
    IcebergDatasetHandle(ResourceLocator resource, ReadOptions readOptions,
                         S3ObjectStore injectedStore) {
        if (resource == null) {
            throw new ConnectorException("Iceberg resource is required");
        }
        this.resource = resource;
        this.readOptions = readOptions;
        Long snapshotId = snapshotId(resource);

        Path temp;
        try {
            temp = Files.createTempDirectory("consilens-iceberg-");
        } catch (IOException exception) {
            throw new ConnectorException("Failed to create Iceberg temp directory: "
                    + exception.getMessage(), exception);
        }
        S3ObjectStore store = injectedStore != null ? injectedStore : S3ObjectStore.create(resource);
        String metadataLocation = metadataLocation(resource, store);
        Map<String, String> partitionFilters = partitionFilters(resource);
        org.apache.iceberg.io.FileIO io = new DownloadingFileIO(store, temp);
        try {
            this.table = IcebergTableResolver.resolve(metadataLocation, snapshotId, io,
                    partitionFilters);
            if (table.dataFiles().isEmpty()) {
                throw new ConnectorException("Iceberg snapshot " + table.snapshotId()
                        + " has no data files");
            }
            List<String> dataPaths = new ArrayList<>();
            for (IcebergTableResolver.DataFileInfo info : table.dataFiles()) {
                dataPaths.add(info.path());
            }
            List<Path> localFiles = localize(dataPaths, temp, store);
            List<String> positionPaths = new ArrayList<>();
            for (IcebergTableResolver.PositionDelete delete : table.positionDeletes()) {
                positionPaths.add(delete.path());
            }
            Path positionDir = temp.resolve("position-deletes");
            Files.createDirectories(positionDir);
            List<Path> deleteLocal = positionPaths.isEmpty()
                    ? List.of() : localize(positionPaths, positionDir, store);
            List<Path> effectiveDeleteLocal = new ArrayList<>();
            for (int index = 0; index < table.positionDeletes().size(); index++) {
                IcebergTableResolver.PositionDelete delete = table.positionDeletes().get(index);
                Path local = deleteLocal.get(index);
                if (delete.format() == FileFormat.PARQUET) {
                    effectiveDeleteLocal.add(local);
                    continue;
                }
                Path converted = positionDir.resolve("converted-" + index + "-"
                        + baseName(delete.path()).replaceAll("(?i)\\.(avro|orc)$", ".parquet"));
                convertPositionDeleteToParquet(delete, local, converted);
                effectiveDeleteLocal.add(converted);
            }
            List<PositionDeleteRows> positions = readPositions(table.positionDeletes(),
                    effectiveDeleteLocal);
            List<Path> scanFiles = localFiles;
            if (!positions.isEmpty()) {
                // Position deletes are applied via DuckDB on Parquet; convert ORC
                // data files up front so the snapshot stays a single format.
                scanFiles = new ArrayList<>();
                for (int index = 0; index < localFiles.size(); index++) {
                    Path local = localFiles.get(index);
                    if (table.dataFiles().get(index).format() != FileFormat.ORC) {
                        scanFiles.add(local);
                        continue;
                    }
                    Path converted = temp.resolve("orc-position-" + index + "-"
                            + local.getFileName().toString()
                                    .replaceAll("(?i)\\.orc$", ".parquet"));
                    OrcIcebergSupport.convertToParquet(local, table.schema(), converted);
                    scanFiles.add(converted);
                }
            }
            scanFiles = applyPositionDeletes(scanFiles, table.dataFiles(),
                    positions, temp);
            if (!table.equalityDeletes().isEmpty()) {
                scanFiles = applyEqualityDeletes(scanFiles, table, temp, store);
            }
            String dataFormat = dataFormat(scanFiles);
            Path scanDir = temp.resolve("scan");
            Files.createDirectories(scanDir);
            List<Path> scanReady = new ArrayList<>();
            for (int index = 0; index < scanFiles.size(); index++) {
                Path source = scanFiles.get(index);
                Path target = scanDir.resolve(String.format("%04d-%s", index,
                        source.getFileName().toString()));
                if (source.equals(localFiles.get(index))) {
                    try {
                        Files.createSymbolicLink(target, source.toAbsolutePath());
                    } catch (UnsupportedOperationException | IOException linkFailure) {
                        Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                } else {
                    Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                scanReady.add(target);
            }
            ResourceLocator fileResource = ResourceLocator.builder()
                    .type("file")
                    .path(scanDir.toString())
                    .options(Map.of("format", dataFormat))
                    .build();
            this.tempDir = temp;
            this.s3Store = store;
            this.delegate = new FileDatasetHandle(fileResource, readOptions);
        } catch (IOException exception) {
            store.close();
            deleteRecursively(temp);
            throw new ConnectorException("Failed to prepare Iceberg scan directory: "
                    + exception.getMessage(), exception);
        } catch (RuntimeException exception) {
            store.close();
            deleteRecursively(temp);
            throw exception;
        }
    }

    /**
     * Position delete files are read as {@code _file_path}/{@code _pos}; Avro/ORC
     * files are first projected into a Parquet file with those columns.
     */
    private static void convertPositionDeleteToParquet(
            IcebergTableResolver.PositionDelete delete, Path localDelete, Path targetParquet) {
        org.apache.iceberg.Schema schema = positionDeleteSchema();
        if (delete.format() == FileFormat.ORC) {
            OrcIcebergSupport.convertToParquet(localDelete, schema, targetParquet);
            return;
        }
        try (CloseableIterable<Record> rows = Avro.read(
                        org.apache.iceberg.Files.localInput(localDelete.toAbsolutePath().toString()))
                .project(schema)
                .createReaderFunc(fileSchema -> DataReader.create(schema, fileSchema))
                .build();
             FileAppender<Record> appender = Parquet.write(
                             org.apache.iceberg.Files.localOutput(
                                     targetParquet.toAbsolutePath().toString()))
                     .schema(schema)
                     .createWriterFunc(GenericParquetWriter::create)
                     .build()) {
            for (Record row : rows) {
                appender.add(row);
            }
        } catch (IOException exception) {
            throw new ConnectorException("Failed to convert Iceberg position delete "
                    + delete.path() + " to Parquet: " + exception.getMessage(), exception);
        }
    }

    private static org.apache.iceberg.Schema positionDeleteSchema() {
        return new org.apache.iceberg.Schema(java.util.List.of(
                org.apache.iceberg.types.Types.NestedField.required(1, "_file_path",
                        org.apache.iceberg.types.Types.StringType.get()),
                org.apache.iceberg.types.Types.NestedField.required(2, "_pos",
                        org.apache.iceberg.types.Types.LongType.get())));
    }

    /**
     * Reads each Iceberg position delete file via DuckDB and maps
     * {@code _file_path -> set of _pos} (0-based row positions), keeping the
     * delete file's sequence number so stale deletes are not applied to files
     * re-added later.
     */
    private static List<PositionDeleteRows> readPositions(
            List<IcebergTableResolver.PositionDelete> deletes, List<Path> localFiles) {
        if (deletes == null || deletes.isEmpty()) {
            return List.of();
        }
        try {
            Class.forName("org.duckdb.DuckDBDriver");
        } catch (ClassNotFoundException | LinkageError error) {
            throw new ConnectorException("Iceberg position deletes require"
                    + " org.duckdb:duckdb_jdbc", error);
        }
        List<PositionDeleteRows> result = new ArrayList<>();
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection("jdbc:duckdb:")) {
            for (int index = 0; index < deletes.size(); index++) {
                IcebergTableResolver.PositionDelete delete = deletes.get(index);
                Map<String, java.util.Set<Long>> positions = new java.util.LinkedHashMap<>();
                String sql = "SELECT _file_path, _pos FROM read_parquet('"
                        + literal(localFiles.get(index).toAbsolutePath().toString()) + "')";
                try (java.sql.Statement statement = connection.createStatement();
                     java.sql.ResultSet rows = statement.executeQuery(sql)) {
                    while (rows.next()) {
                        String dataPath = rows.getString(1);
                        long position = rows.getLong(2);
                        positions.computeIfAbsent(dataPath == null ? "" : dataPath,
                                        key -> new java.util.HashSet<>())
                                .add(position);
                    }
                } catch (java.sql.SQLException exception) {
                    throw new ConnectorException("Failed to read Iceberg position delete "
                            + delete.path() + ": " + exception.getMessage(), exception);
                }
                result.add(new PositionDeleteRows(delete.sequenceNumber(), positions));
            }
            return result;
        } catch (java.sql.SQLException exception) {
            throw new ConnectorException("Failed to read Iceberg position deletes: "
                    + exception.getMessage(), exception);
        }
    }

    /**
     * Rewrites Parquet data files that have applicable position deletes. A
     * delete applies when its sequence number is greater than or equal to the
     * data file's sequence number (Iceberg v2 semantics). Unaffected files are
     * returned unchanged; ORC data files with deletes are rejected.
     */
    private static List<Path> applyPositionDeletes(
            List<Path> localFiles,
            List<IcebergTableResolver.DataFileInfo> dataFiles,
            List<PositionDeleteRows> positionRows,
            Path tempDir) {
        List<Path> result = new ArrayList<>();
        int[] filteredCounter = {0};
        for (int index = 0; index < localFiles.size(); index++) {
            Path local = localFiles.get(index);
            IcebergTableResolver.DataFileInfo data = dataFiles.get(index);
            String remotePath = data.path();
            java.util.Set<Long> deletedPositions = new java.util.HashSet<>();
            for (PositionDeleteRows rows : positionRows) {
                if (rows.sequenceNumber < data.sequenceNumber()) {
                    continue;
                }
                java.util.Set<Long> positions = rows.positionsByPath.get(remotePath);
                if (positions != null) {
                    deletedPositions.addAll(positions);
                }
            }
            if (deletedPositions.isEmpty()) {
                result.add(local);
                continue;
            }
            if (local.toString().toLowerCase(Locale.ROOT).endsWith(".orc")) {
                throw new ConnectorException("Iceberg position deletes on ORC data files"
                        + " are not supported: " + remotePath);
            }
            Path filtered = tempDir.resolve("filtered-" + filteredCounter[0]
                    + "-" + local.getFileName().toString());
            filteredCounter[0]++;
            StringBuilder positionsSql = new StringBuilder();
            for (Long position : deletedPositions) {
                if (positionsSql.length() > 0) {
                    positionsSql.append(',');
                }
                positionsSql.append(position);
            }
            String sql = "COPY (SELECT * EXCLUDE (file_row_number) FROM read_parquet('"
                    + local.toAbsolutePath().toString().replace("'", "''")
                    + "', file_row_number=true) WHERE file_row_number NOT IN ("
                    + positionsSql + ")) TO '"
                    + filtered.toAbsolutePath().toString().replace("'", "''")
                    + "' (FORMAT PARQUET)";
            try (java.sql.Connection connection = java.sql.DriverManager.getConnection("jdbc:duckdb:");
                 java.sql.Statement statement = connection.createStatement()) {
                statement.execute(sql);
            } catch (java.sql.SQLException exception) {
                throw new ConnectorException("Failed to apply Iceberg position deletes to "
                        + remotePath + ": " + exception.getMessage(), exception);
            }
            result.add(filtered);
        }
        return result;
    }

    /**
     * Applies Iceberg equality deletes by rewriting each affected data file with
     * an anti-join on the delete keys. Delete files are matched by sequence number,
     * partition spec and partition values; keys use {@code IS NOT DISTINCT FROM}
     * so NULL-valued keys delete rows with NULLs as Iceberg specifies.
     *
     * <p>Key columns are resolved by Parquet field ID first, so renamed columns
     * still line up; files without field IDs fall back to snapshot schema names.</p>
     *
     * <p>Delete keys are loaded once into DuckDB temporary tables so N data files
     * do not re-read every delete file.</p>
     */
    private static List<Path> applyEqualityDeletes(List<Path> dataFiles,
                                                   IcebergTableResolver.ResolvedTable table,
                                                   Path tempDir,
                                                   S3ObjectStore store) {
        Path deleteDir = tempDir.resolve("equality-deletes");
        try {
            Files.createDirectories(deleteDir);
        } catch (IOException exception) {
            throw new ConnectorException("Failed to create Iceberg equality delete directory: "
                    + exception.getMessage(), exception);
        }
        List<String> deletePaths = new ArrayList<>();
        for (IcebergTableResolver.EqualityDelete delete : table.equalityDeletes()) {
            deletePaths.add(delete.path());
        }
        List<Path> localDeletes = localize(deletePaths, deleteDir, store);
        List<Path> effectiveDeletes = new ArrayList<>();
        for (int index = 0; index < table.equalityDeletes().size(); index++) {
            IcebergTableResolver.EqualityDelete delete = table.equalityDeletes().get(index);
            if (delete.format() == FileFormat.AVRO || delete.format() == FileFormat.ORC) {
                Path converted = deleteDir.resolve("converted-" + index + "-"
                        + baseName(delete.path()).replaceAll("(?i)\\.(avro|orc)$", ".parquet"));
                convertDeleteToParquet(delete, localDeletes.get(index), table, converted);
                effectiveDeletes.add(converted);
            } else {
                effectiveDeletes.add(localDeletes.get(index));
            }
        }

        List<EqualityDeleteTable> deleteTables = new ArrayList<>();
        for (int index = 0; index < table.equalityDeletes().size(); index++) {
            IcebergTableResolver.EqualityDelete delete = table.equalityDeletes().get(index);
            boolean applicable = false;
            for (IcebergTableResolver.DataFileInfo data : table.dataFiles()) {
                if (IcebergTableResolver.equalityDeleteApplies(data, delete)) {
                    applicable = true;
                    break;
                }
            }
            if (!applicable) {
                continue;
            }
            List<Integer> fieldIds = delete.equalityFieldIds();
            if (fieldIds.isEmpty()) {
                throw new ConnectorException("Iceberg equality delete " + delete.path()
                        + " has no equality field IDs");
            }
            Map<Integer, String> deleteFileIds = parquetFieldIds(effectiveDeletes.get(index));
            Map<Integer, String> deleteColumns = new java.util.LinkedHashMap<>();
            for (Integer fieldId : fieldIds) {
                String snapshotName = snapshotColumnName(delete.path(), fieldId,
                        table.fieldNames());
                deleteColumns.put(fieldId,
                        deleteFileIds.getOrDefault(fieldId, snapshotName));
            }
            deleteTables.add(new EqualityDeleteTable(delete, fieldIds, deleteColumns,
                    "eq_delete_" + index, effectiveDeletes.get(index)));
        }
        if (deleteTables.isEmpty()) {
            return dataFiles;
        }

        List<Path> effectiveDataFiles = new ArrayList<>();
        for (int index = 0; index < dataFiles.size(); index++) {
            IcebergTableResolver.DataFileInfo data = table.dataFiles().get(index);
            Path local = dataFiles.get(index);
            // Position deletes may already have converted ORC data files to Parquet.
            boolean orcLocal = data.format() == FileFormat.ORC
                    && local.toString().toLowerCase(Locale.ROOT).endsWith(".orc");
            if (!orcLocal) {
                effectiveDataFiles.add(local);
                continue;
            }
            Path converted = tempDir.resolve("orc-data-" + index + "-"
                    + local.getFileName().toString().replaceAll("(?i)\\.orc$", ".parquet"));
            OrcIcebergSupport.convertToParquet(local, table.schema(), converted);
            effectiveDataFiles.add(converted);
        }

        try {
            Class.forName("org.duckdb.DuckDBDriver");
        } catch (ClassNotFoundException | LinkageError error) {
            throw new ConnectorException("Iceberg equality deletes require"
                    + " org.duckdb:duckdb_jdbc", error);
        }
        List<Path> result = new ArrayList<>();
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection("jdbc:duckdb:");
             java.sql.Statement statement = connection.createStatement()) {
            for (EqualityDeleteTable delete : deleteTables) {
                String sql = "CREATE TEMP TABLE " + delete.tableName + " AS SELECT DISTINCT "
                        + delete.selectColumns() + " FROM read_parquet('"
                        + literal(delete.local.toAbsolutePath().toString()) + "')";
                try {
                    statement.execute(sql);
                } catch (java.sql.SQLException exception) {
                    throw new ConnectorException("Failed to load Iceberg equality delete "
                            + delete.delete.path() + " keys ("
                            + delete.deleteColumns.values() + "): "
                            + exception.getMessage(), exception);
                }
            }
            for (int index = 0; index < dataFiles.size(); index++) {
                IcebergTableResolver.DataFileInfo data = table.dataFiles().get(index);
                Map<Integer, String> dataFileIds = parquetFieldIds(effectiveDataFiles.get(index));
                List<String> conditions = new ArrayList<>();
                for (EqualityDeleteTable delete : deleteTables) {
                    if (IcebergTableResolver.equalityDeleteApplies(data, delete.delete)) {
                        conditions.add(delete.condition(dataColumns(delete, table.fieldNames(),
                                dataFileIds)));
                    }
                }
                if (conditions.isEmpty()) {
                    result.add(effectiveDataFiles.get(index));
                    continue;
                }
                Path filtered = tempDir.resolve("equality-" + index + "-"
                        + effectiveDataFiles.get(index).getFileName());
                String sql = "COPY (SELECT * FROM read_parquet('"
                        + literal(effectiveDataFiles.get(index).toAbsolutePath().toString())
                        + "') AS t WHERE " + String.join(" AND ", conditions) + ") TO '"
                        + literal(filtered.toAbsolutePath().toString())
                        + "' (FORMAT PARQUET)";
                try {
                    statement.execute(sql);
                } catch (java.sql.SQLException exception) {
                    throw new ConnectorException("Failed to apply Iceberg equality deletes to "
                            + data.path() + ": " + exception.getMessage(), exception);
                }
                result.add(filtered);
            }
            return result;
        } catch (java.sql.SQLException exception) {
            throw new ConnectorException("Failed to apply Iceberg equality deletes: "
                    + exception.getMessage(), exception);
        }
    }

    /**
     * Reads an Avro/ORC equality delete file and writes the projected keys as
     * Parquet (field IDs preserved) so the existing DuckDB anti-join pipeline
     * can consume it.
     */
    private static void convertDeleteToParquet(IcebergTableResolver.EqualityDelete delete,
                                               Path localDelete,
                                               IcebergTableResolver.ResolvedTable table,
                                               Path targetParquet) {
        List<String> keyNames = new ArrayList<>();
        for (Integer fieldId : delete.equalityFieldIds()) {
            keyNames.add(snapshotColumnName(delete.path(), fieldId, table.fieldNames()));
        }
        org.apache.iceberg.Schema projected = table.schema().select(keyNames);
        if (delete.format() == FileFormat.ORC) {
            OrcIcebergSupport.convertToParquet(localDelete, projected, targetParquet);
            return;
        }
        try (CloseableIterable<Record> rows = Avro.read(
                        org.apache.iceberg.Files.localInput(localDelete.toAbsolutePath().toString()))
                .project(projected)
                .createReaderFunc(fileSchema -> DataReader.create(projected, fileSchema))
                .build();
             FileAppender<Record> appender = Parquet.write(
                             org.apache.iceberg.Files.localOutput(
                                     targetParquet.toAbsolutePath().toString()))
                     .schema(projected)
                     .createWriterFunc(GenericParquetWriter::create)
                     .build()) {
            for (Record row : rows) {
                appender.add(row);
            }
        } catch (IOException exception) {
            throw new ConnectorException("Failed to convert Iceberg Avro equality delete "
                    + delete.path() + " to Parquet: " + exception.getMessage(), exception);
        }
    }

    private static String snapshotColumnName(String deletePath, Integer fieldId,
                                             Map<Integer, String> fieldNames) {
        String name = fieldNames.get(fieldId);
        if (name == null) {
            throw new ConnectorException("Iceberg equality delete " + deletePath
                    + " references field ID " + fieldId
                    + " that is not present in the snapshot schema");
        }
        return name;
    }

    /**
     * Maps Parquet column names to Iceberg field IDs via {@code parquet_schema}.
     * Files rewritten by DuckDB (position deletes) do not carry field IDs; the
     * caller falls back to snapshot schema column names.
     */
    private static Map<Integer, String> parquetFieldIds(Path parquetFile) {
        Map<Integer, String> fieldIds = new java.util.LinkedHashMap<>();
        String sql = "SELECT name, field_id FROM parquet_schema('"
                + literal(parquetFile.toAbsolutePath().toString())
                + "') WHERE field_id IS NOT NULL";
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection("jdbc:duckdb:");
             java.sql.Statement statement = connection.createStatement();
             java.sql.ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                String name = rows.getString(1);
                long fieldId = rows.getLong(2);
                if (name != null && !rows.wasNull()) {
                    fieldIds.put((int) fieldId, name);
                }
            }
            return fieldIds;
        } catch (java.sql.SQLException exception) {
            throw new ConnectorException("Failed to read Parquet field IDs from "
                    + parquetFile + ": " + exception.getMessage(), exception);
        }
    }

    private static Map<Integer, String> dataColumns(EqualityDeleteTable delete,
                                                    Map<Integer, String> fieldNames,
                                                    Map<Integer, String> dataFileIds) {
        Map<Integer, String> columns = new java.util.LinkedHashMap<>();
        for (Integer fieldId : delete.fieldIds) {
            String snapshotName = snapshotColumnName(delete.delete.path(), fieldId, fieldNames);
            columns.put(fieldId, dataFileIds.getOrDefault(fieldId, snapshotName));
        }
        return columns;
    }

    private static final class EqualityDeleteTable {

        private final IcebergTableResolver.EqualityDelete delete;
        private final List<Integer> fieldIds;
        private final Map<Integer, String> deleteColumns;
        private final String tableName;
        private final Path local;

        private EqualityDeleteTable(IcebergTableResolver.EqualityDelete delete,
                                    List<Integer> fieldIds,
                                    Map<Integer, String> deleteColumns,
                                    String tableName, Path local) {
            this.delete = delete;
            this.fieldIds = fieldIds;
            this.deleteColumns = deleteColumns;
            this.tableName = tableName;
            this.local = local;
        }

        private String selectColumns() {
            return fieldIds.stream()
                    .map(fieldId -> identifier(deleteColumns.get(fieldId))
                            + " AS " + identifier("k" + fieldId))
                    .collect(java.util.stream.Collectors.joining(", "));
        }

        private String condition(Map<Integer, String> dataColumns) {
            StringBuilder condition = new StringBuilder("NOT EXISTS (SELECT 1 FROM ")
                    .append(tableName).append(" AS d WHERE ");
            for (int index = 0; index < fieldIds.size(); index++) {
                if (index > 0) {
                    condition.append(" AND ");
                }
                Integer fieldId = fieldIds.get(index);
                condition.append("d.").append(identifier("k" + fieldId))
                        .append(" IS NOT DISTINCT FROM t.")
                        .append(identifier(dataColumns.get(fieldId)));
            }
            return condition.append(')').toString();
        }
    }

    private static final class PositionDeleteRows {

        private final long sequenceNumber;
        private final Map<String, java.util.Set<Long>> positionsByPath;

        private PositionDeleteRows(long sequenceNumber,
                                   Map<String, java.util.Set<Long>> positionsByPath) {
            this.sequenceNumber = sequenceNumber;
            this.positionsByPath = positionsByPath;
        }
    }

    private static String literal(String value) {
        return value.replace("'", "''");
    }

    private static String identifier(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    static String metadataLocation(ResourceLocator resource, S3ObjectStore store) {
        String location = resource.getPath() != null && !resource.getPath().isBlank()
                ? resource.getPath() : resource.getName();
        if (location == null || location.isBlank()) {
            throw new ConnectorException("Iceberg resource.path must point to a metadata JSON"
                    + " or a table directory");
        }
        if (isS3(location)) {
            if (location.toLowerCase(Locale.ROOT).endsWith(".json")) {
                return location;
            }
            String prefix = location.endsWith("/") ? location : location + "/";
            String latest = null;
            for (String candidate : store.list(prefix)) {
                if (candidate.endsWith(".metadata.json")) {
                    latest = newerMetadata(candidate, latest);
                }
            }
            if (latest == null) {
                throw new ConnectorException("No .metadata.json found under " + prefix);
            }
            return latest;
        }
        Path path = location.startsWith("file:")
                ? Paths.get(URI.create(location)) : Paths.get(location);
        if (Files.isDirectory(path)) {
            Path metadataDir = path.resolve("metadata");
            if (!Files.isDirectory(metadataDir)) {
                throw new ConnectorException("Iceberg table directory has no metadata/: " + path);
            }
            try (Stream<Path> stream = Files.list(metadataDir)) {
                String latest = null;
                for (Path file : stream.collect(java.util.stream.Collectors.toList())) {
                    if (file.getFileName().toString().endsWith(".metadata.json")) {
                        latest = newerMetadata(file.toString(), latest);
                    }
                }
                if (latest == null) {
                    throw new ConnectorException("No .metadata.json found under " + metadataDir);
                }
                return latest;
            } catch (IOException exception) {
                throw new ConnectorException("Failed to list Iceberg metadata under "
                        + metadataDir + ": " + exception.getMessage(), exception);
            }
        }
        if (!Files.isRegularFile(path)) {
            throw new ConnectorException("Iceberg metadata file does not exist: " + path);
        }
        return path.toString();
    }

    private static final java.util.regex.Pattern METADATA_VERSION =
            java.util.regex.Pattern.compile("(?:^|/)v(\\d+)\\.metadata\\.json$");

    /**
     * 选"更新"的 metadata：先比 {@code v<N>} 里的 N，再退回文件名比较。
     *
     * <p>纯字典序会让 {@code v10.metadata.json} 输给 {@code v9.metadata.json}，
     * 表提交超过 9 次后就会静默读到旧快照。
     */
    private static String newerMetadata(String candidate, String current) {
        if (current == null) {
            return candidate;
        }
        long left = metadataVersion(candidate);
        long right = metadataVersion(current);
        if (left != right) {
            return left > right ? candidate : current;
        }
        return candidate.compareTo(current) > 0 ? candidate : current;
    }

    private static long metadataVersion(String path) {
        java.util.regex.Matcher matcher = METADATA_VERSION.matcher(path.replace('\\', '/'));
        return matcher.find() ? Long.parseLong(matcher.group(1)) : -1L;
    }

    static Map<String, String> partitionFilters(ResourceLocator resource) {
        Map<String, Object> options = resource.getOptions() == null ? Map.of() : resource.getOptions();
        Map<String, String> filters = new java.util.LinkedHashMap<>();
        options.forEach((key, value) -> {
            if (key != null && key.startsWith("partition.") && value != null) {
                filters.put(key.substring("partition.".length()), partitionFilterText(value));
            }
        });
        return filters;
    }

    /**
     * 分区过滤值统一成 ISO 文本。
     *
     * <p>YAML 会把 {@code 2024-01-01} 解析成 {@code java.util.Date}，直接
     * {@code String.valueOf} 会得到 {@code Mon Jan 01 00:00:00 UTC 2024}，按日期比较时永远匹配不到。
     *
     * <p>这里**不能**把零点裁剪成纯日期：同一个值可能是 DATE 分区，也可能是 TIMESTAMP 分区，
     * 只输出 {@code 2024-01-01} 会让时间戳分区一个文件都匹配不上。类型相关的解释
     * （纯日期怎么当时间戳用）放在 {@link IcebergTableResolver} 里按分区字段类型处理。
     */
    static String partitionFilterText(Object value) {
        if (value instanceof java.util.Date) {
            return java.time.LocalDateTime.ofInstant(
                    ((java.util.Date) value).toInstant(), java.time.ZoneOffset.UTC).toString();
        }
        if (value instanceof java.time.LocalDate || value instanceof java.time.LocalDateTime) {
            return value.toString();
        }
        return String.valueOf(value);
    }

    private static Long snapshotId(ResourceLocator resource) {
        Map<String, Object> options = resource.getOptions() == null ? Map.of() : resource.getOptions();
        Object value = options.get("snapshotId");
        if (value == null) {
            value = options.get("snapshot-id");
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException exception) {
            throw new ConnectorException("Invalid Iceberg snapshotId: " + value, exception);
        }
    }

    private static List<Path> localize(List<String> dataFiles, Path tempDir,
                                       S3ObjectStore store) {
        List<Path> result = new ArrayList<>();
        int index = 0;
        for (String location : dataFiles) {
            if (location == null || location.isBlank()) {
                continue;
            }
            if (isS3(location)) {
                Path target = tempDir.resolve(String.format("%04d-%s", index++,
                        baseName(location)));
                store.download(location, target);
                result.add(target);
                continue;
            }
            Path source = location.startsWith("file:")
                    ? Paths.get(URI.create(location)) : Paths.get(location);
            if (!Files.isRegularFile(source)) {
                throw new ConnectorException("Iceberg data file does not exist: " + source);
            }
            Path target = tempDir.resolve(String.format("%04d-%s", index++,
                    source.getFileName().toString()));
            try {
                try {
                    Files.createSymbolicLink(target, source.toAbsolutePath());
                } catch (UnsupportedOperationException | IOException linkFailure) {
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException exception) {
                throw new ConnectorException("Failed to stage Iceberg data file " + source
                        + ": " + exception.getMessage(), exception);
            }
            result.add(target);
        }
        if (result.isEmpty()) {
            throw new ConnectorException("Iceberg snapshot produced no data files");
        }
        return result;
    }

    /**
     * Iceberg snapshots must contain a single data format; mixed formats are
     * rejected because the delegate scans with one reader.
     */
    private static String dataFormat(List<Path> localFiles) {
        boolean orc = false;
        boolean parquet = false;
        for (Path file : localFiles) {
            String lower = file.toString().toLowerCase(Locale.ROOT);
            if (lower.endsWith(".orc")) {
                orc = true;
            } else if (lower.endsWith(".parquet")) {
                parquet = true;
            }
        }
        if (orc && parquet) {
            throw new ConnectorException("Iceberg snapshot mixes Parquet and ORC data files;"
                    + " only a single format is supported per snapshot");
        }
        return orc ? "orc" : "parquet";
    }

    private static boolean isS3(String location) {
        String lower = location.toLowerCase(Locale.ROOT);
        return lower.startsWith("s3://") || lower.startsWith("s3a://");
    }

    private static String baseName(String location) {
        int slash = location.lastIndexOf('/');
        String name = slash < 0 ? location : location.substring(slash + 1);
        return name.isBlank() ? "data.parquet" : name;
    }

    @Override
    public ResourceLocator getResource() {
        return resource;
    }

    @Override
    public DatasetMetadata getMetadata() {
        DatasetMetadata delegateMetadata = delegate.getMetadata();
        Map<String, Object> attributes = new java.util.LinkedHashMap<>();
        if (delegateMetadata != null && delegateMetadata.getAttributes() != null) {
            attributes.putAll(delegateMetadata.getAttributes());
        }
        attributes.put("iceberg.snapshotId", table.snapshotId());
        attributes.put("iceberg.tableLocation", table.location());
        return DatasetMetadata.builder()
                .logicalName(logicalName())
                .capabilities(delegateMetadata != null ? delegateMetadata.getCapabilities() : null)
                .executionDomainId(delegateMetadata != null ? delegateMetadata.getExecutionDomainId() : null)
                .attributes(attributes)
                .build();
    }

    private String logicalName() {
        String path = resource.getPath() != null && !resource.getPath().isBlank()
                ? resource.getPath() : resource.getName();
        if (path == null || path.isBlank()) {
            return "iceberg";
        }
        try {
            Path candidate = path.startsWith("file:") ? Paths.get(URI.create(path)) : Paths.get(path);
            Path name = candidate.getFileName();
            return name != null ? name.toString() : path;
        } catch (RuntimeException ignored) {
            return path;
        }
    }

    @Override
    public SchemaDescriptor getSchema() {
        return delegate.getSchema();
    }

    @Override
    public Optional<RecordScanner> getRecordScanner() {
        return delegate.getRecordScanner();
    }

    @Override
    public Optional<SplitPlanner> getSplitPlanner() {
        return delegate.getSplitPlanner();
    }

    @Override
    public Optional<HashProvider> getHashProvider() {
        return delegate.getHashProvider();
    }

    @Override
    public Optional<KeyLookupProvider> getKeyLookupProvider() {
        return delegate.getKeyLookupProvider();
    }

    @Override
    public Optional<SnapshotProvider> getSnapshotProvider() {
        return delegate.getSnapshotProvider();
    }

    @Override
    public Optional<FilterPushdownProvider> getFilterPushdownProvider() {
        return delegate.getFilterPushdownProvider();
    }

    @Override
    public void close() {
        try {
            delegate.close();
        } finally {
            try {
                s3Store.close();
            } finally {
                deleteRecursively(tempDir);
            }
        }
    }

    long snapshotId() {
        return table.snapshotId();
    }

    private static void deleteRecursively(Path directory) {
        if (directory == null) {
            return;
        }
        try (Stream<Path> stream = Files.walk(directory)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            });
        } catch (IOException ignored) {
            // best effort cleanup
        }
    }
}
