package com.consilens.connector.iceberg;

import com.consilens.connector.api.ConnectorException;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.data.GenericRecord;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.data.parquet.GenericParquetWriter;
import org.apache.iceberg.io.FileAppender;
import org.apache.iceberg.parquet.Parquet;
import org.apache.iceberg.types.Types;
import org.apache.orc.OrcFile;
import org.apache.orc.Reader;
import org.apache.orc.RecordReader;
import org.apache.orc.TypeDescription;
import org.apache.orc.storage.ql.exec.vector.BytesColumnVector;
import org.apache.orc.storage.ql.exec.vector.ColumnVector;
import org.apache.orc.storage.ql.exec.vector.DecimalColumnVector;
import org.apache.orc.storage.ql.exec.vector.DoubleColumnVector;
import org.apache.orc.storage.ql.exec.vector.LongColumnVector;
import org.apache.orc.storage.ql.exec.vector.TimestampColumnVector;
import org.apache.orc.storage.ql.exec.vector.VectorizedRowBatch;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Converts ORC files (Iceberg data files or equality delete files) into Parquet
 * with Iceberg field IDs so the DuckDB-based delete pipeline can consume them.
 *
 * <p>ORC is an optional capability: {@code org.apache.orc:orc-core} must be on
 * the classpath (the distribution ships it via {@code consilens-connector-all}).
 * Field IDs are read from the {@code iceberg.id} ORC attribute when present and
 * fall back to column names.</p>
 */
final class OrcIcebergSupport {

    private static final String FIELD_ID_ATTRIBUTE = "iceberg.id";

    private OrcIcebergSupport() {
    }

    static void convertToParquet(Path orcFile, org.apache.iceberg.Schema schema, Path target) {
        // Reader 也必须关闭：ORC reader 持有文件句柄/缓冲池，转换 N 个 data/delete 文件就会泄漏 N 次
        try (Reader reader = open(orcFile)) {
            TypeDescription orcSchema = reader.getSchema();
            List<String> orcNames = orcSchema.getFieldNames();
            List<TypeDescription> orcChildren = orcSchema.getChildren();
            int[] columnIndexes = new int[schema.columns().size()];
            for (int index = 0; index < schema.columns().size(); index++) {
                columnIndexes[index] = childIndex(orcFile, schema.columns().get(index),
                        orcNames, orcChildren);
            }
            RecordReader rows = reader.rows(reader.options());
            try (FileAppender<Record> appender = Parquet.write(
                            org.apache.iceberg.Files.localOutput(target.toAbsolutePath().toString()))
                    .schema(schema)
                    .createWriterFunc(GenericParquetWriter::create)
                    .build()) {
                VectorizedRowBatch batch = orcSchema.createRowBatch(1024);
                while (rows.nextBatch(batch)) {
                    for (int row = 0; row < batch.size; row++) {
                        Record record = GenericRecord.create(schema);
                        for (int column = 0; column < schema.columns().size(); column++) {
                            Types.NestedField field = schema.columns().get(column);
                            int orcIndex = columnIndexes[column];
                            record.setField(field.name(), orcIndex < 0 ? null
                                    : value(batch.cols[orcIndex], row, orcChildren.get(orcIndex),
                                            field, orcFile));
                        }
                        appender.add(record);
                    }
                    batch.reset();
                }
            } finally {
                rows.close();
            }
        } catch (IOException exception) {
            throw new ConnectorException("Failed to convert ORC file " + orcFile
                    + " to Parquet: " + exception.getMessage(), exception);
        }
    }

    private static Reader open(Path orcFile) {
        try {
            Class.forName("org.apache.orc.OrcFile");
        } catch (ClassNotFoundException | LinkageError error) {
            throw new ConnectorException("Iceberg ORC conversion requires"
                    + " org.apache.orc:orc-core", error);
        }
        try {
            return OrcFile.createReader(new org.apache.hadoop.fs.Path(orcFile.toUri()),
                    OrcFile.readerOptions(new Configuration()));
        } catch (IOException exception) {
            throw new ConnectorException("Failed to open ORC file " + orcFile + ": "
                    + exception.getMessage(), exception);
        }
    }

    private static int childIndex(Path orcFile, Types.NestedField field,
                                  List<String> orcNames, List<TypeDescription> orcChildren) {
        String expectedFieldId = String.valueOf(field.fieldId());
        for (int index = 0; index < orcChildren.size(); index++) {
            TypeDescription child = orcChildren.get(index);
            String fieldId = child.getAttributeValue(FIELD_ID_ATTRIBUTE);
            if (expectedFieldId.equals(fieldId)) {
                return index;
            }
        }
        for (int index = 0; index < orcChildren.size(); index++) {
            if (field.name().equals(orcNames.get(index))) {
                return index;
            }
        }
        // Columns added after the file was written are absent; null-fill them.
        return -1;
    }

    private static Object value(ColumnVector vector, int row, TypeDescription orcType,
                                Types.NestedField field, Path orcFile) {
        if (vector == null) {
            return null;
        }
        if (vector.isRepeating) {
            row = 0;
        }
        if (!vector.noNulls && vector.isNull[row]) {
            return null;
        }
        switch (orcType.getCategory()) {
            case BOOLEAN:
                return ((LongColumnVector) vector).vector[row] != 0;
            case BYTE:
            case SHORT:
            case INT:
            case LONG:
                return ((LongColumnVector) vector).vector[row];
            case FLOAT:
            case DOUBLE:
                return ((DoubleColumnVector) vector).vector[row];
            case DATE:
                requireIcebergType(orcFile, field, "DATE", Types.DateType.get());
                return LocalDate.ofEpochDay(((LongColumnVector) vector).vector[row]);
            case TIMESTAMP:
                requireIcebergType(orcFile, field, "TIMESTAMP",
                        Types.TimestampType.withoutZone());
                return new java.sql.Timestamp(
                        ((TimestampColumnVector) vector).time[row]).toLocalDateTime();
            case TIMESTAMP_INSTANT:
                requireIcebergType(orcFile, field, "TIMESTAMP_INSTANT",
                        Types.TimestampType.withZone());
                return Instant.ofEpochMilli(((TimestampColumnVector) vector).time[row])
                        .atOffset(ZoneOffset.UTC);
            case STRING:
            case VARCHAR:
            case CHAR:
                BytesColumnVector bytes = (BytesColumnVector) vector;
                return new String(bytes.vector[row], bytes.start[row], bytes.length[row],
                        java.nio.charset.StandardCharsets.UTF_8);
            case BINARY:
                BytesColumnVector binary = (BytesColumnVector) vector;
                return java.util.Arrays.copyOfRange(binary.vector[row], binary.start[row],
                        binary.start[row] + binary.length[row]);
            case DECIMAL:
                BigDecimal decimal = ((DecimalColumnVector) vector).vector[row]
                        .getHiveDecimal().bigDecimalValue();
                if (field.type() instanceof Types.DecimalType) {
                    decimal = decimal.setScale(((Types.DecimalType) field.type()).scale());
                }
                return decimal;
            default:
                throw new ConnectorException("Unsupported ORC column type " + orcType
                        + " for " + orcFile + " column " + field.name()
                        + " (supported: BOOLEAN/numeric/DATE/TIMESTAMP/string/binary/DECIMAL)");
        }
    }

    private static void requireIcebergType(Path orcFile, Types.NestedField field,
                                           String orcType, org.apache.iceberg.types.Type expected) {
        if (!expected.equals(field.type())) {
            throw new ConnectorException("ORC type " + orcType + " does not match Iceberg type "
                    + field.type() + " for " + orcFile + " column " + field.name());
        }
    }
}
