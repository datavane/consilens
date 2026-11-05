package com.consilens.connector.iceberg;

import com.consilens.connector.api.ConnectorException;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.ManifestFile;
import org.apache.iceberg.ManifestFiles;
import org.apache.iceberg.ManifestReader;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.io.FileIO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves the data files of one Iceberg snapshot from a local metadata JSON.
 * Manifest lists and manifests are read through {@link LocalFileIO}; data files
 * are handed to the file connector after position and equality deletes are
 * resolved.
 */
final class IcebergTableResolver {

    private IcebergTableResolver() {
    }

    static ResolvedTable resolve(String metadataLocation, Long snapshotId, FileIO io,
                                 Map<String, String> partitionFilters) {
        TableMetadata metadata;
        try {
            metadata = TableMetadataParser.read(io, metadataLocation);
        } catch (RuntimeException exception) {
            throw new ConnectorException("Failed to read Iceberg metadata " + metadataLocation
                    + ": " + exception.getMessage(), exception);
        }
        Snapshot snapshot = snapshotId == null
                ? metadata.currentSnapshot()
                : metadata.snapshot(snapshotId);
        if (snapshot == null) {
            throw new ConnectorException(snapshotId == null
                    ? "Iceberg table has no current snapshot: " + metadataLocation
                    : "Iceberg snapshot not found: " + snapshotId);
        }
        Map<Integer, PartitionSpec> specs = metadata.specsById();
        List<PositionDelete> positionDeletes = new ArrayList<>();
        List<EqualityDelete> equalityDeletes = new ArrayList<>();
        for (ManifestFile manifest : snapshot.deleteManifests(io)) {
            try (ManifestReader<DeleteFile> reader =
                         ManifestFiles.readDeleteManifest(manifest, io, specs)) {
                for (DeleteFile delete : reader) {
                    boolean equality = delete.equalityFieldIds() != null
                            && !delete.equalityFieldIds().isEmpty();
                    if (delete.format() != FileFormat.PARQUET
                            && delete.format() != FileFormat.AVRO
                            && delete.format() != FileFormat.ORC) {
                        throw new ConnectorException("Unsupported Iceberg delete file format "
                                + delete.format() + " for " + delete.path()
                                + " (supported: PARQUET/AVRO/ORC equality deletes, PARQUET"
                                + " position deletes)");
                    }
                    if (equality) {
                        if (delete.content() != org.apache.iceberg.FileContent.EQUALITY_DELETES) {
                            throw new ConnectorException("Iceberg delete file " + delete.path()
                                    + " has equality field IDs but content "
                                    + delete.content());
                        }
                        equalityDeletes.add(new EqualityDelete(
                                delete.path().toString(),
                                delete.specId(),
                                partitionType(specs, delete.specId()),
                                delete.partition(),
                                sequence(delete.dataSequenceNumber(), manifest.sequenceNumber()),
                                new ArrayList<>(delete.equalityFieldIds()),
                                delete.format()));
                        continue;
                    }
                    if (delete.content() != org.apache.iceberg.FileContent.POSITION_DELETES) {
                        throw new ConnectorException("Unsupported Iceberg delete content "
                                + delete.content() + " for " + delete.path());
                    }
                    if (delete.format() != FileFormat.PARQUET
                            && delete.format() != FileFormat.AVRO
                            && delete.format() != FileFormat.ORC) {
                        throw new ConnectorException("Unsupported Iceberg position delete"
                                + " file format " + delete.format() + " for " + delete.path()
                                + " (supported: PARQUET/AVRO/ORC)");
                    }
                    positionDeletes.add(new PositionDelete(
                            delete.path().toString(),
                            sequence(delete.dataSequenceNumber(), manifest.sequenceNumber()),
                            delete.format()));
                }
            } catch (java.io.IOException exception) {
                throw new ConnectorException("Failed to read Iceberg delete manifest "
                        + manifest.path() + ": " + exception.getMessage(), exception);
            }
        }

        List<DataFileInfo> dataFiles = new ArrayList<>();
        for (ManifestFile manifest : snapshot.dataManifests(io)) {
            try (ManifestReader<DataFile> reader = ManifestFiles.read(manifest, io)) {
                for (DataFile file : reader) {
                    if (!matchesPartitions(file, specs, partitionFilters)) {
                        continue;
                    }
                    if (file.format() != FileFormat.PARQUET && file.format() != FileFormat.ORC) {
                        throw new ConnectorException("Unsupported Iceberg data file format "
                                + file.format() + " for " + file.path()
                                + " (supported: PARQUET, ORC)");
                    }
                    dataFiles.add(new DataFileInfo(
                            file.path().toString(),
                            file.specId(),
                            partitionType(specs, file.specId()),
                            file.partition(),
                            sequence(file.dataSequenceNumber(), manifest.sequenceNumber()),
                            file.format()));
                }
            } catch (java.io.IOException exception) {
                throw new ConnectorException("Failed to read Iceberg manifest "
                        + manifest.path() + ": " + exception.getMessage(), exception);
            }
        }
        validateDeleteSequences(equalityDeletes, positionDeletes, dataFiles);
        org.apache.iceberg.Schema snapshotSchema = snapshotSchema(metadata, snapshot);
        Map<Integer, String> fieldNames = fieldNames(snapshotSchema);
        return new ResolvedTable(metadata.location(), snapshot.snapshotId(), dataFiles,
                positionDeletes, equalityDeletes, fieldNames, snapshotSchema);
    }

    /**
     * Deletes are ordered by sequence number. An unassigned sequence cannot be
     * compared safely, so reject rather than silently applying or dropping rows
     * incorrectly.
     */
    private static void validateDeleteSequences(List<EqualityDelete> equalityDeletes,
                                                List<PositionDelete> positionDeletes,
                                                List<DataFileInfo> dataFiles) {
        if (equalityDeletes.isEmpty() && positionDeletes.isEmpty()) {
            return;
        }
        for (EqualityDelete delete : equalityDeletes) {
            if (delete.sequenceNumber() < 0) {
                throw new ConnectorException("Iceberg equality delete " + delete.path()
                        + " has no sequence number; cannot apply deletes safely");
            }
        }
        for (PositionDelete delete : positionDeletes) {
            if (delete.sequenceNumber() < 0) {
                throw new ConnectorException("Iceberg position delete " + delete.path()
                        + " has no sequence number; cannot apply deletes safely");
            }
        }
        for (DataFileInfo data : dataFiles) {
            if (data.sequenceNumber() < 0) {
                throw new ConnectorException("Iceberg data file " + data.path()
                        + " has no sequence number; cannot apply deletes safely");
            }
        }
    }

    private static long sequence(Long fileSequence, long manifestSequence) {
        return fileSequence != null ? fileSequence : manifestSequence;
    }

    private static org.apache.iceberg.types.Types.StructType partitionType(
            Map<Integer, PartitionSpec> specs, int specId) {
        PartitionSpec spec = specs.get(specId);
        return spec == null ? null : spec.partitionType();
    }

    /**
     * Equality delete field IDs must be resolved against the schema used by the
     * snapshot. Dropped fields are not present in any readable schema, so a delete
     * that references them cannot be applied safely.
     */
    private static org.apache.iceberg.Schema snapshotSchema(TableMetadata metadata,
                                                            Snapshot snapshot) {
        org.apache.iceberg.Schema schema = metadata.schema();
        Integer schemaId = snapshot.schemaId();
        if (schemaId != null) {
            org.apache.iceberg.Schema snapshotSchema = metadata.schemasById().get(schemaId);
            if (snapshotSchema != null) {
                schema = snapshotSchema;
            }
        }
        return schema;
    }

    private static Map<Integer, String> fieldNames(org.apache.iceberg.Schema schema) {
        Map<Integer, String> names = new LinkedHashMap<>();
        for (org.apache.iceberg.types.Types.NestedField field : schema.columns()) {
            names.put(field.fieldId(), field.name());
        }
        return names;
    }

    private static boolean matchesPartitions(DataFile file, Map<Integer, PartitionSpec> specs,
                                             Map<String, String> filters) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        PartitionSpec spec = specs.get(file.specId());
        if (spec == null || file.partition() == null) {
            return false;
        }
        StructLike partition = file.partition();
        for (Map.Entry<String, String> filter : filters.entrySet()) {
            int position = position(spec, filter.getKey());
            Object value = partition.get(position, Object.class);
            org.apache.iceberg.types.Type fieldType = spec.partitionType().fields().get(position).type();
            if (!partitionValueMatches(value, filter.getValue(), fieldType)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 按分区字段类型比较分区值与用户写的字面量。
     *
     * <p>直接比 {@code String.valueOf(value)} 会漏掉所有非字符串分区：Iceberg 的 DATE 分区值
     * 是"距 1970 的天数"（{@code 19723}），时间戳是 UTC micros，DECIMAL 是 {@code BigDecimal}，
     * 于是 {@code partition.dt: 2024-01-01} 永远匹配不到任何数据文件（表现为"快照没有数据文件"）。
     */
    static boolean partitionValueMatches(Object actual, String expected,
                                         org.apache.iceberg.types.Type type) {
        String text = expected == null ? "" : expected.trim();
        if (actual == null) {
            return text.isEmpty() || "null".equalsIgnoreCase(text);
        }
        String typeName = type == null ? "" : type.typeId().name();
        switch (typeName) {
            case "DATE":
                return datePartitionEquals(actual, text);
            case "TIMESTAMP":
            case "TIMESTAMP_NANO":
                return timestampPartitionEquals(actual, text);
            case "DECIMAL":
                return decimalPartitionEquals(actual, text);
            case "INTEGER":
            case "LONG":
            case "FLOAT":
            case "DOUBLE":
                return numberPartitionEquals(actual, text);
            case "BOOLEAN":
                return String.valueOf(actual).equalsIgnoreCase(text);
            case "BINARY":
            case "FIXED":
                return bytesPartitionEquals(actual, text);
            default:
                return String.valueOf(actual).equals(text);
        }
    }

    private static boolean datePartitionEquals(Object actual, String text) {
        if (!(actual instanceof Integer)) {
            return String.valueOf(actual).equals(text);
        }
        // 容忍写成零点时间戳的日期字面量（2024-01-01T00:00:00 也是"2024-01-01"这一天）
        String dateText = text.length() > 10 ? text.substring(0, 10) : text;
        try {
            return ((Integer) actual) == (int) java.time.LocalDate.parse(dateText).toEpochDay();
        } catch (java.time.format.DateTimeParseException exception) {
            return String.valueOf(actual).equals(text);
        }
    }

    private static boolean timestampPartitionEquals(Object actual, String text) {
        if (!(actual instanceof Long)) {
            return String.valueOf(actual).equals(text);
        }
        String normalized = text.replace(' ', 'T');
        // 纯日期按当天零点解释；否则 TIMESTAMP 分区遇到 partition.ts=2024-01-01 会剪掉全部文件，
        // 报错还是语焉不详的 "snapshot has no data files"
        if (normalized.length() == 10) {
            normalized = normalized + "T00:00:00";
        }
        try {
            java.time.LocalDateTime local = java.time.LocalDateTime.parse(normalized);
            long micros = local.toEpochSecond(java.time.ZoneOffset.UTC) * 1_000_000L
                    + local.getNano() / 1_000L;
            return ((Long) actual) == micros;
        } catch (java.time.format.DateTimeParseException ignored) {
            // 带时区的写法（2024-01-01T10:00:00Z）再试一次
        }
        try {
            java.time.Instant instant = java.time.Instant.parse(normalized);
            long micros = instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1_000L;
            return ((Long) actual) == micros;
        } catch (java.time.format.DateTimeParseException exception) {
            return String.valueOf(actual).equals(text);
        }
    }

    private static boolean decimalPartitionEquals(Object actual, String text) {
        try {
            java.math.BigDecimal expected = new java.math.BigDecimal(text);
            // DECIMAL 用 compareTo：1.5 与 1.50 是同一个分区值
            return new java.math.BigDecimal(String.valueOf(actual)).compareTo(expected) == 0;
        } catch (NumberFormatException exception) {
            return String.valueOf(actual).equals(text);
        }
    }

    private static boolean numberPartitionEquals(Object actual, String text) {
        try {
            return new java.math.BigDecimal(String.valueOf(actual))
                    .compareTo(new java.math.BigDecimal(text)) == 0;
        } catch (NumberFormatException exception) {
            return String.valueOf(actual).equals(text);
        }
    }

    private static boolean bytesPartitionEquals(Object actual, String text) {
        if (!(actual instanceof java.nio.ByteBuffer)) {
            return String.valueOf(actual).equals(text);
        }
        java.nio.ByteBuffer buffer = ((java.nio.ByteBuffer) actual).duplicate();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        try {
            return java.util.Arrays.equals(bytes, java.util.Base64.getDecoder().decode(text));
        } catch (IllegalArgumentException exception) {
            return java.util.Arrays.equals(bytes,
                    text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static int position(PartitionSpec spec, String field) {
        List<org.apache.iceberg.types.Types.NestedField> fields =
                spec.partitionType().fields();
        for (int index = 0; index < fields.size(); index++) {
            if (fields.get(index).name().equalsIgnoreCase(field)) {
                return index;
            }
        }
        throw new ConnectorException("Iceberg partition field not found: " + field);
    }

    static final class ResolvedTable {

        private final String location;
        private final long snapshotId;
        private final List<DataFileInfo> dataFiles;
        private final List<PositionDelete> positionDeletes;
        private final List<EqualityDelete> equalityDeletes;
        private final Map<Integer, String> fieldNames;
        private final org.apache.iceberg.Schema schema;

        private ResolvedTable(String location, long snapshotId, List<DataFileInfo> dataFiles,
                              List<PositionDelete> positionDeletes,
                              List<EqualityDelete> equalityDeletes,
                              Map<Integer, String> fieldNames,
                              org.apache.iceberg.Schema schema) {
            this.location = location;
            this.snapshotId = snapshotId;
            this.dataFiles = dataFiles;
            this.positionDeletes = positionDeletes;
            this.equalityDeletes = equalityDeletes;
            this.fieldNames = fieldNames;
            this.schema = schema;
        }

        String location() {
            return location;
        }

        long snapshotId() {
            return snapshotId;
        }

        List<DataFileInfo> dataFiles() {
            return dataFiles;
        }

        List<PositionDelete> positionDeletes() {
            return positionDeletes;
        }

        List<EqualityDelete> equalityDeletes() {
            return equalityDeletes;
        }

        Map<Integer, String> fieldNames() {
            return fieldNames;
        }

        org.apache.iceberg.Schema schema() {
            return schema;
        }
    }

    static final class DataFileInfo {

        private final String path;
        private final int specId;
        private final org.apache.iceberg.types.Types.StructType partitionType;
        private final StructLike partition;
        private final long sequenceNumber;
        private final FileFormat format;

        DataFileInfo(String path, int specId,
                             org.apache.iceberg.types.Types.StructType partitionType,
                             StructLike partition,
                             long sequenceNumber, FileFormat format) {
            this.path = path;
            this.specId = specId;
            this.partitionType = partitionType;
            this.partition = partition;
            this.sequenceNumber = sequenceNumber;
            this.format = format;
        }

        String path() {
            return path;
        }

        int specId() {
            return specId;
        }

        org.apache.iceberg.types.Types.StructType partitionType() {
            return partitionType;
        }

        StructLike partition() {
            return partition;
        }

        long sequenceNumber() {
            return sequenceNumber;
        }

        FileFormat format() {
            return format;
        }
    }

    static final class PositionDelete {

        private final String path;
        private final long sequenceNumber;
        private final FileFormat format;

        private PositionDelete(String path, long sequenceNumber, FileFormat format) {
            this.path = path;
            this.sequenceNumber = sequenceNumber;
            this.format = format;
        }

        String path() {
            return path;
        }

        long sequenceNumber() {
            return sequenceNumber;
        }

        FileFormat format() {
            return format;
        }
    }

    static final class EqualityDelete {

        private final String path;
        private final int specId;
        private final org.apache.iceberg.types.Types.StructType partitionType;
        private final StructLike partition;
        private final long sequenceNumber;
        private final List<Integer> equalityFieldIds;
        private final FileFormat format;

        EqualityDelete(String path, int specId,
                               org.apache.iceberg.types.Types.StructType partitionType,
                               StructLike partition,
                               long sequenceNumber, List<Integer> equalityFieldIds,
                               FileFormat format) {
            this.path = path;
            this.specId = specId;
            this.partitionType = partitionType;
            this.partition = partition;
            this.sequenceNumber = sequenceNumber;
            this.equalityFieldIds = equalityFieldIds;
            this.format = format;
        }

        String path() {
            return path;
        }

        int specId() {
            return specId;
        }

        org.apache.iceberg.types.Types.StructType partitionType() {
            return partitionType;
        }

        StructLike partition() {
            return partition;
        }

        long sequenceNumber() {
            return sequenceNumber;
        }

        List<Integer> equalityFieldIds() {
            return equalityFieldIds;
        }

        FileFormat format() {
            return format;
        }
    }

    /**
     * Equality deletes apply only to data files committed before the delete and
     * belonging to the same partition values. Spec IDs may drift across partition
     * evolution: if the two partition types still have the same shape, values are
     * comparable and the delete applies; otherwise fail fast rather than silently
     * skipping a delete.
     */
    static boolean equalityDeleteApplies(DataFileInfo data, EqualityDelete delete) {
        if (data.sequenceNumber() >= delete.sequenceNumber()) {
            return false;
        }
        if (data.specId() != delete.specId()) {
            org.apache.iceberg.types.Types.StructType dataType = data.partitionType();
            org.apache.iceberg.types.Types.StructType deleteType = delete.partitionType();
            if (dataType == null || deleteType == null || !samePartitionShape(dataType, deleteType)) {
                throw new ConnectorException("Iceberg equality delete " + delete.path()
                        + " uses partition spec " + delete.specId()
                        + " but data file " + data.path() + " uses spec " + data.specId()
                        + " with a different partition type; cannot apply deletes safely");
            }
        }
        return partitionEquals(data.partition(), delete.partition());
    }

    /**
     * Partition field names may be renamed across spec evolution; only the value
     * types must line up positionally.
     */
    private static boolean samePartitionShape(org.apache.iceberg.types.Types.StructType left,
                                              org.apache.iceberg.types.Types.StructType right) {
        if (left.fields().size() != right.fields().size()) {
            return false;
        }
        for (int index = 0; index < left.fields().size(); index++) {
            if (!left.fields().get(index).type().equals(right.fields().get(index).type())) {
                return false;
            }
        }
        return true;
    }

    private static boolean partitionEquals(StructLike left, StructLike right) {
        if (left == null || right == null) {
            return left == right || sizeOf(left) == 0 && sizeOf(right) == 0;
        }
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            if (!valueEquals(left.get(index, Object.class), right.get(index, Object.class))) {
                return false;
            }
        }
        return true;
    }

    private static int sizeOf(StructLike value) {
        return value == null ? 0 : value.size();
    }

    private static boolean valueEquals(Object left, Object right) {
        if (left instanceof byte[] && right instanceof byte[]) {
            return java.util.Arrays.equals((byte[]) left, (byte[]) right);
        }
        if (left instanceof StructLike && right instanceof StructLike) {
            return partitionEquals((StructLike) left, (StructLike) right);
        }
        return Objects.equals(left, right);
    }
}
