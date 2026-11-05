package com.consilens.connector.file;

import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.model.DataType;
import com.consilens.connector.api.model.FieldDescriptor;
import com.consilens.connector.api.model.SchemaDescriptor;
import com.consilens.connector.api.record.CanonicalRecord;
import com.consilens.connector.api.record.CanonicalValue;
import com.consilens.connector.api.record.RecordKey;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.orc.OrcFile;
import org.apache.orc.Reader;
import org.apache.orc.RecordReader;
import org.apache.orc.TypeDescription;
import org.apache.orc.storage.ql.exec.vector.VectorizedRowBatch;
import org.apache.orc.storage.ql.exec.vector.BytesColumnVector;
import org.apache.orc.storage.ql.exec.vector.ColumnVector;
import org.apache.orc.storage.ql.exec.vector.DecimalColumnVector;
import org.apache.orc.storage.ql.exec.vector.DoubleColumnVector;
import org.apache.orc.storage.ql.exec.vector.LongColumnVector;
import org.apache.orc.storage.ql.exec.vector.ListColumnVector;
import org.apache.orc.storage.ql.exec.vector.MapColumnVector;
import org.apache.orc.storage.ql.exec.vector.StructColumnVector;
import org.apache.orc.storage.ql.exec.vector.TimestampColumnVector;
import org.apache.orc.storage.ql.exec.vector.UnionColumnVector;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ORC reader backed by Apache ORC. ORC is an optional capability: the library is
 * optional on the classpath and missing support produces an explicit error.
 */
final class OrcSupport {

    private OrcSupport() {
    }

    static SchemaDescriptor inferSchema(List<java.nio.file.Path> files) {
        try {
            try (Reader reader = open(files.get(0))) {
            TypeDescription schema = reader.getSchema();
            List<FieldDescriptor> fields = new ArrayList<>();
            List<String> columns = new ArrayList<>();
            List<DataType> types = new ArrayList<>();
            for (int index = 0; index < schema.getChildren().size(); index++) {
                TypeDescription child = schema.getChildren().get(index);
                String name = schema.getFieldNames().get(index);
                DataType type = mapType(child);
                fields.add(FieldDescriptor.builder()
                        .name(name)
                        .canonicalType(type.name())
                        .nullable(true)
                        .ordinal(index + 1)
                        .build());
                columns.add(name);
                types.add(type);
            }
            Map<String, FieldDescriptor> fieldMap = new java.util.LinkedHashMap<>();
            for (FieldDescriptor field : fields) {
                fieldMap.put(field.getName(), field);
            }
            return SchemaDescriptor.builder().fields(fields).fieldMap(fieldMap).build();
            }
        } catch (Exception exception) {
            throw new ConnectorException("Failed to read ORC schema from " + files
                    + ": " + exception.getMessage(), exception);
        }
    }

    private static DataType mapType(TypeDescription type) {
        switch (type.getCategory()) {
            case BOOLEAN:
                return DataType.BOOLEAN;
            case BYTE:
            case SHORT:
            case INT:
            case LONG:
                return DataType.BIGINT;
            case FLOAT:
            case DOUBLE:
                return DataType.DOUBLE;
            case DECIMAL:
                return DataType.DECIMAL;
            case DATE:
                return DataType.DATE;
            case TIMESTAMP:
            case TIMESTAMP_INSTANT:
                return DataType.TIMESTAMP;
            case STRING:
            case VARCHAR:
            case CHAR:
                return DataType.VARCHAR;
            case BINARY:
                return DataType.BLOB;
            default:
                return DataType.VARCHAR;
        }
    }

    static List<CanonicalRecord> load(List<java.nio.file.Path> files, SchemaDescriptor schema) {
        List<FieldDescriptor> fields = schema.getFields() == null ? List.of() : schema.getFields();
        List<CanonicalRecord> records = new ArrayList<>();
        try {
            for (java.nio.file.Path file : files) {
                // 必须 try-with-resources：任一文件读到一半抛异常（stripe 截断、类型不支持）时，
                // 手工 close 的写法会把文件句柄和 ORC buffer pool 一起泄漏，服务端重试还会再漏一份
                try (Reader reader = open(file);
                     RecordReader rows = reader.rows(reader.options())) {
                VectorizedRowBatch batch = reader.getSchema().createRowBatch(1024);
                while (rows.nextBatch(batch)) {
                    for (int row = 0; row < batch.size; row++) {
                        Map<String, CanonicalValue> values = new java.util.LinkedHashMap<>();
                        for (int index = 0; index < fields.size(); index++) {
                            FieldDescriptor field = fields.get(index);
                            int column = reader.getSchema().getFieldNames()
                                    .indexOf(field.getName());
                            if (column < 0 || column >= batch.cols.length) {
                                values.put(field.getName(), CanonicalValue.builder()
                                        .type(type(field).name()).value(null).build());
                                continue;
                            }
                            Object raw = scalar(batch.cols[column], row, batch,
                                    reader.getSchema().getChildren().get(column));
                            DataType type = type(field);
                            values.put(field.getName(), CanonicalValue.builder()
                                    .type(type.name()).value(FileValueParser.parse(raw, type, null)).build());
                        }
                        records.add(new OrcRecord(values));
                    }
                }
                }
            }
        } catch (Exception exception) {
            throw new ConnectorException("Failed to read ORC files: " + exception.getMessage(), exception);
        }
        return records;
    }

    private static Reader open(java.nio.file.Path path) throws Exception {
        try {
            Class.forName("org.apache.orc.OrcFile");
        } catch (ClassNotFoundException | LinkageError error) {
            throw new ConnectorException("ORC support requires org.apache.orc:orc-core", error);
        }
        return OrcFile.createReader(new Path(path.toUri()),
                OrcFile.readerOptions(new Configuration()));
    }

    private static Object scalar(ColumnVector vector, int row, VectorizedRowBatch batch,
                                TypeDescription type) {
        if (vector == null) {
            return null;
        }
        if (vector.isRepeating) {
            row = 0;
        }
        if (!vector.noNulls && vector.isNull[row]) {
            return null;
        }
        switch (type.getCategory()) {
            case BOOLEAN:
                return ((LongColumnVector) vector).vector[row] == 1;
            case BYTE:
            case SHORT:
            case INT:
            case LONG:
                return ((LongColumnVector) vector).vector[row];
            case BINARY:
                // 盲转 LongColumnVector 会 ClassCastException；BLOB 列必须保留字节
                BytesColumnVector binary = (BytesColumnVector) vector;
                return java.util.Arrays.copyOfRange(binary.vector[row], binary.start[row],
                        binary.start[row] + binary.length[row]);
            case LIST:
            case MAP:
            case STRUCT:
            case UNION:
                // 嵌套/半结构化列在 schema 里是 VARCHAR：渲染成稳定的文本形式再比较
                return text(vector, row, type);
            case DATE:
                // ORC 的 DATE 列在 VectorizedRowBatch 里是 epoch-day（LongColumnVector）。
                // 直接返回 long 会让归一化把它当成 1970-01-01 起的天数/毫秒数，
                // 与 Parquet/CSV 侧的 DATE 永远不相等。这里必须还原成 LocalDate。
                return java.time.LocalDate.ofEpochDay(((LongColumnVector) vector).vector[row]);
            case DOUBLE:
            case FLOAT:
                return ((DoubleColumnVector) vector).vector[row];
            case DECIMAL:
                DecimalColumnVector decimal = (DecimalColumnVector) vector;
                return decimal.vector[row].getHiveDecimal().bigDecimalValue();
            case TIMESTAMP:
            case TIMESTAMP_INSTANT:
                // timestamptz 的 ORC category 是 TIMESTAMP_INSTANT（向量类型与 TIMESTAMP 相同）。
                // 漏掉它会让整列静默变成 null：schema 声明 TIMESTAMP，值却全是空。
                TimestampColumnVector timestamp = (TimestampColumnVector) vector;
                return new java.sql.Timestamp(timestamp.time[row]);
            case STRING:
            case VARCHAR:
            case CHAR:
                BytesColumnVector bytes = (BytesColumnVector) vector;
                return new String(bytes.vector[row], bytes.start[row], bytes.length[row],
                        java.nio.charset.StandardCharsets.UTF_8);
            default:
                // 未知类别不再盲转：返回 null 比抛 ClassCastException 更可诊断
                return null;
        }
    }

    /**
     * 嵌套/半结构化列的稳定文本形式（JSON 风格）：字符串加引号并转义，{@code null} 用不带引号的
     * {@code null}。
     *
     * <p>直接拼接会把不同值渲染成同一串——{@code ["a,b"]} 与 {@code ["a","b"]} 都是
     * {@code [a,b]}，{@code null} 元素与字符串 {@code "null"} 也不可区分，这些文本正是判等依据，
     * 因此必须可单射。Map 条目按 key 排序，保证两侧同一份数据渲染一致。
     */
    private static String text(ColumnVector vector, int row, TypeDescription type) {
        if (vector == null) {
            return "null";
        }
        if (vector.isRepeating) {
            row = 0;
        }
        if (!vector.noNulls && vector.isNull[row]) {
            return "null";
        }
        switch (type.getCategory()) {
            case LIST: {
                ListColumnVector list = (ListColumnVector) vector;
                int offset = (int) list.offsets[row];
                int length = (int) list.lengths[row];
                StringBuilder builder = new StringBuilder("[");
                for (int index = 0; index < length; index++) {
                    if (index > 0) {
                        builder.append(',');
                    }
                    builder.append(text(list.child, offset + index, type.getChildren().get(0)));
                }
                return builder.append(']').toString();
            }
            case MAP: {
                MapColumnVector map = (MapColumnVector) vector;
                int offset = (int) map.offsets[row];
                int length = (int) map.lengths[row];
                // 不能直接用 TreeMap：ORC 允许 null key（Spark/Hive 写得出来），TreeMap.put(null,..) 会 NPE；
                // 重复 key 也会被合并而丢条目，所以按渲染后的 key 排序成列表。
                java.util.List<String[]> entries = new java.util.ArrayList<>(length);
                for (int index = 0; index < length; index++) {
                    entries.add(new String[]{
                            text(map.keys, offset + index, type.getChildren().get(0)),
                            text(map.values, offset + index, type.getChildren().get(1))});
                }
                entries.sort(java.util.Comparator.comparing(entry -> entry[0]));
                StringBuilder builder = new StringBuilder("{");
                for (int index = 0; index < entries.size(); index++) {
                    if (index > 0) {
                        builder.append(',');
                    }
                    builder.append(entries.get(index)[0]).append(':').append(entries.get(index)[1]);
                }
                return builder.append('}').toString();
            }
            case STRUCT: {
                StructColumnVector struct = (StructColumnVector) vector;
                StringBuilder builder = new StringBuilder("{");
                List<String> names = type.getFieldNames();
                for (int index = 0; index < struct.fields.length; index++) {
                    if (index > 0) {
                        builder.append(',');
                    }
                    String name = names != null && index < names.size() ? names.get(index) : String.valueOf(index);
                    builder.append(quote(name)).append(':')
                            .append(text(struct.fields[index], row, type.getChildren().get(index)));
                }
                return builder.append('}').toString();
            }
            case UNION: {
                UnionColumnVector union = (UnionColumnVector) vector;
                int tag = union.tags[row];
                if (tag < 0 || tag >= union.fields.length) {
                    return "null";
                }
                return text(union.fields[tag], row, type.getChildren().get(tag));
            }
            default:
                return leafText(vector, row, type);
        }
    }

    /** 标量在嵌套结构里的渲染：字符串走 {@link #quote(String)}，其余与标量的文本形态一致。 */
    private static String leafText(ColumnVector vector, int row, TypeDescription type) {
        if (type.getCategory() == org.apache.orc.TypeDescription.Category.STRING
                || type.getCategory() == org.apache.orc.TypeDescription.Category.VARCHAR
                || type.getCategory() == org.apache.orc.TypeDescription.Category.CHAR) {
            BytesColumnVector bytes = (BytesColumnVector) vector;
            return quote(new String(bytes.vector[row], bytes.start[row], bytes.length[row],
                    java.nio.charset.StandardCharsets.UTF_8));
        }
        Object value = scalar(vector, row, null, type);
        if (value == null) {
            return "null";
        }
        if (value instanceof java.sql.Timestamp || value instanceof java.time.LocalDate) {
            return quote(String.valueOf(value));
        }
        return String.valueOf(value);
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder builder = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"':
                    builder.append("\\\"");
                    break;
                case '\\':
                    builder.append("\\\\");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                default:
                    builder.append(current);
            }
        }
        return builder.append('"').toString();
    }

    static void sortByKey(List<CanonicalRecord> records, com.consilens.connector.api.planner.CompareSegment segment,
                          SchemaDescriptor schema) {
        ParquetSupport.sortByKey(records, segment, schema);
    }

    static List<CanonicalRecord> applySplit(List<CanonicalRecord> records,
                                            com.consilens.connector.api.planner.CompareSegment segment) {
        return ParquetSupport.applySplit(records, segment);
    }

    private static DataType type(FieldDescriptor field) {
        try {
            return DataType.valueOf(field.getCanonicalType().toUpperCase(Locale.ROOT));
        } catch (Exception exception) {
            return DataType.VARCHAR;
        }
    }

    private static final class OrcRecord implements CanonicalRecord {

        private final RecordKey key = RecordKey.builder().parts(Collections.emptyList()).build();
        private final Map<String, CanonicalValue> values;

        private OrcRecord(Map<String, CanonicalValue> values) {
            this.values = values;
        }

        @Override
        public RecordKey getKey() {
            return key;
        }

        @Override
        public Map<String, CanonicalValue> getValues() {
            return values;
        }
    }
}
