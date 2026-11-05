package com.consilens.conncetor.base.write;

import com.consilens.common.enums.DataType;
import com.consilens.common.type.TypeDescriptor;
import com.consilens.common.type.Types;
import com.consilens.connector.api.DatabaseDialect;
import com.consilens.connector.api.write.OutputColumnSpec;
import com.consilens.connector.api.write.PreparedWriteRow;
import com.consilens.connector.api.write.PreparedWriteValue;
import com.consilens.connector.api.write.TableWriteCompileRequest;
import com.consilens.connector.api.write.TableWriteCompiler;
import com.consilens.connector.api.write.TableWritePlan;
import com.consilens.connector.api.write.TypedOutputRow;
import com.consilens.connector.api.write.TypedOutputValue;
import com.consilens.connector.api.write.WriteColumnSpec;
import com.consilens.connector.api.model.ConnectorNativeType;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class JdbcTableWriteCompiler implements TableWriteCompiler {

    /** 出现在类型定义之后、属于列约束的关键字（大小写不敏感、词边界匹配）。 */
    private static final List<String> TYPE_CONSTRAINT_KEYWORDS = List.of(
            "PRIMARY KEY", "NOT NULL", "NULL", "UNIQUE", "DEFAULT", "CHECK", "REFERENCES",
            "AUTO_INCREMENT", "AUTOINCREMENT", "GENERATED", "COMMENT", "COLLATE", "CHARSET",
            "CHARACTER SET", "ZEROFILL", "ON UPDATE");

    /** 表名直接拼入 DDL/INSERT，必须是普通标识符（允许 schema.table 形式）。 */
    private static final java.util.regex.Pattern SAFE_IDENTIFIER =
            java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_$#]*");

    private final DatabaseDialect dialect;

    public JdbcTableWriteCompiler(DatabaseDialect dialect) {
        this.dialect = dialect;
    }

    private String validatedTableName(String tableName) {
        String value = tableName == null ? "" : tableName.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("tableName is required");
        }
        for (String part : value.split("\\.", -1)) {
            if (!SAFE_IDENTIFIER.matcher(part).matches()) {
                throw new IllegalArgumentException(
                        "tableName contains invalid characters: " + tableName);
            }
        }
        return value;
    }

    @Override
    public TableWritePlan compile(TableWriteCompileRequest request) {
        List<WriteColumnSpec> columns = new ArrayList<>();
        String tableName = validatedTableName(request.getTableName());
        StringBuilder ddl = new StringBuilder("CREATE TABLE IF NOT EXISTS ")
                .append(tableName)
                .append(" (");
        StringBuilder insert = new StringBuilder("INSERT INTO ")
                .append(tableName)
                .append(" (");
        StringBuilder values = new StringBuilder(" VALUES (");

        for (int i = 0; i < request.getColumns().size(); i++) {
            OutputColumnSpec column = request.getColumns().get(i);
            TypeDescriptor systemType = column.getSystemType() != null ? column.getSystemType() : Types.TEXT();
            TypeDescriptor targetType = resolveTargetType(column, systemType);
            String typeDeclaration = resolveDdlDeclaration(column, targetType);
            int jdbcType = jdbcType(targetType);
            WriteColumnSpec writeColumn = new WriteColumnSpec(
                    column.getColumnName(),
                    systemType,
                    targetType,
                    ConnectorNativeType.builder()
                            .connectorType(dialect.getConnectorType())
                            .name(baseTypeName(typeDeclaration))
                            .declaration(typeDeclaration)
                            .jdbcType(jdbcType)
                            .nullable(column.isNullable())
                            .attributes(Map.of())
                            .build(),
                    column.isNullable()
            );
            columns.add(writeColumn);

            if (i > 0) {
                ddl.append(", ");
                insert.append(", ");
                values.append(",");
            }
            ddl.append(column.getColumnName())
                    .append(" ")
                    .append(typeDeclaration);
            if (!column.isNullable()) {
                ddl.append(" NOT NULL");
            }
            insert.append(column.getColumnName());
            values.append("?");
        }
        ddl.append(")");
        insert.append(")");
        values.append(")");
        return new TableWritePlan(
                dialect.getConnectorType(),
                "DROP TABLE IF EXISTS " + tableName,
                ddl.toString(),
                insert.append(values).toString(),
                columns
        );
    }

    @Override
    public PreparedWriteRow prepareRow(TypedOutputRow row, TableWritePlan plan) {
        List<PreparedWriteValue> values = new ArrayList<>();
        for (WriteColumnSpec column : plan.getColumns()) {
            TypedOutputValue value = row.getValue(column.getColumnName());
            if (value == null) {
                throw new IllegalArgumentException("Missing typed output value for column " + column.getColumnName());
            }
            values.add(prepareValue(value.getValue(), column));
        }
        return new PreparedWriteRow(values);
    }

    private PreparedWriteValue prepareValue(Object rawValue, WriteColumnSpec column) {
        TypeDescriptor targetType = column.getTargetTypeDescriptor();
        int jdbcType = column.getTargetType().getJdbcType() != null
                ? column.getTargetType().getJdbcType()
                : jdbcType(targetType);
        if (rawValue == null) {
            return new PreparedWriteValue(null, jdbcType, this::bindValue);
        }
        Object converted = convertValue(rawValue, targetType);
        return new PreparedWriteValue(converted, jdbcType, this::bindValue);
    }

    /**
     * 收窄到 SMALLINT/INTEGER 时必须是**精确**转换。
     *
     * <p>{@code Number.shortValue()} 会把 70000 静默变成 4464、把 1.5 变成 1：行照样插入成功，
     * 但落库的值已经错了（而且只有事后比对才会发现）。这里超出范围或带小数直接报错。
     */
    static long exactIntegral(Object value, long min, long max, String target) {
        BigDecimal decimal;
        if (value instanceof BigDecimal) {
            decimal = (BigDecimal) value;
        } else if (value instanceof Number) {
            decimal = new BigDecimal(value.toString());
        } else {
            decimal = new BigDecimal(String.valueOf(value));
        }
        BigDecimal stripped = decimal.stripTrailingZeros();
        if (stripped.scale() > 0) {
            throw new IllegalArgumentException("value " + decimal + " has a fractional part and cannot be"
                    + " written to a " + target + " column");
        }
        long narrowed;
        try {
            narrowed = stripped.longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("value " + decimal + " does not fit into a "
                    + target + " column", exception);
        }
        if (narrowed < min || narrowed > max) {
            throw new IllegalArgumentException("value " + decimal + " is out of range for a "
                    + target + " column");
        }
        return narrowed;
    }

    private void bindValue(PreparedStatement preparedStatement, int index, Object value, Integer jdbcType) throws SQLException {
        int effectiveJdbcType = jdbcType != null ? jdbcType : java.sql.Types.VARCHAR;
        if (value == null) {
            preparedStatement.setNull(index, effectiveJdbcType);
            return;
        }
        switch (effectiveJdbcType) {
            case java.sql.Types.SMALLINT:
                preparedStatement.setShort(index, (short) exactIntegral(value, Short.MIN_VALUE,
                        Short.MAX_VALUE, "SMALLINT"));
                return;
            case java.sql.Types.INTEGER:
                preparedStatement.setInt(index, (int) exactIntegral(value, Integer.MIN_VALUE,
                        Integer.MAX_VALUE, "INTEGER"));
                return;
            case java.sql.Types.BIGINT:
                preparedStatement.setLong(index, ((Number) value).longValue());
                return;
            case java.sql.Types.FLOAT:
            case java.sql.Types.REAL:
                preparedStatement.setFloat(index, ((Number) value).floatValue());
                return;
            case java.sql.Types.DOUBLE:
                preparedStatement.setDouble(index, ((Number) value).doubleValue());
                return;
            case java.sql.Types.NUMERIC:
            case java.sql.Types.DECIMAL:
                preparedStatement.setBigDecimal(index, (BigDecimal) value);
                return;
            case java.sql.Types.BOOLEAN:
            case java.sql.Types.BIT:
                preparedStatement.setBoolean(index, (Boolean) value);
                return;
            case java.sql.Types.DATE:
                preparedStatement.setDate(index, (Date) value);
                return;
            case java.sql.Types.TIME:
                preparedStatement.setTime(index, (Time) value);
                return;
            case java.sql.Types.TIMESTAMP:
                preparedStatement.setTimestamp(index, (Timestamp) value);
                return;
            case java.sql.Types.TIMESTAMP_WITH_TIMEZONE:
            case java.sql.Types.TIME_WITH_TIMEZONE:
                preparedStatement.setObject(index, value);
                return;
            default:
                preparedStatement.setObject(index, value, effectiveJdbcType);
        }
    }

    private TypeDescriptor resolveTargetType(OutputColumnSpec column, TypeDescriptor systemType) {
        if (column.getDeclaredColumnType() != null && !column.getDeclaredColumnType().isBlank()) {
            TypeDescriptor descriptor = dialect.getDataTypeHandler().convertToTypeDescriptor(extractTypeDefinition(column.getDeclaredColumnType()));
            if (descriptor.getType() == DataType.UNKNOWN_TYPE) {
                throw new IllegalArgumentException("Unsupported target columnType: " + column.getDeclaredColumnType());
            }
            return descriptor.toBuilder().nullable(column.isNullable()).build();
        }
        return systemType.toBuilder().nullable(column.isNullable()).build();
    }

    private String resolveDdlDeclaration(OutputColumnSpec column, TypeDescriptor targetType) {
        if (column.getDeclaredColumnType() != null && !column.getDeclaredColumnType().isBlank()) {
            String declaredColumnType = column.getDeclaredColumnType().trim();
            String declaredTypeDefinition = extractTypeDefinition(declaredColumnType);
            TypeDescriptor ddlType = targetType.toBuilder().originType(null).build();
            String normalizedTypeDeclaration = dialect.getDataTypeHandler().convertToOriginType(ddlType);
            String suffix = extractTypeConstraintSuffix(declaredColumnType, declaredTypeDefinition);
            return suffix.isBlank()
                    ? normalizedTypeDeclaration
                    : normalizedTypeDeclaration + " " + suffix;
        }
        return dialect.getDataTypeHandler().convertToOriginType(targetType);
    }

    private int jdbcType(TypeDescriptor typeDescriptor) {
        switch (typeDescriptor.getType()) {
            case BOOLEAN_TYPE:
                return java.sql.Types.BOOLEAN;
            case INTEGER_TYPE:
                Integer bitWidth = typeDescriptor.getBitWidth();
                if (bitWidth != null && bitWidth <= 16) {
                    return java.sql.Types.SMALLINT;
                }
                if (bitWidth != null && bitWidth >= 64) {
                    return java.sql.Types.BIGINT;
                }
                return java.sql.Types.INTEGER;
            case FLOAT_TYPE:
                return java.sql.Types.REAL;
            case DOUBLE_TYPE:
                return java.sql.Types.DOUBLE;
            case DECIMAL_TYPE:
                return java.sql.Types.NUMERIC;
            case DATE_TYPE:
                return java.sql.Types.DATE;
            case TIME_TYPE:
                return typeDescriptor.isWithTimezone() ? java.sql.Types.TIME_WITH_TIMEZONE : java.sql.Types.TIME;
            case TIMESTAMP_TYPE:
                return typeDescriptor.isWithTimezone() ? java.sql.Types.TIMESTAMP_WITH_TIMEZONE : java.sql.Types.TIMESTAMP;
            case JSON_TYPE:
            case UUID_TYPE:
                return dialect.getStructuredColumnJdbcType();
            case BINARY_TYPE:
                return java.sql.Types.BINARY;
            case STRING_TYPE:
            case XML_TYPE:
            case ENUM_TYPE:
            case OBJECT_TYPE:
            case ARRAY_TYPE:
            case MAP_TYPE:
            case STRUCT_TYPE:
            case GEOMETRY_TYPE:
            case INTERVAL_TYPE:
            case NULL_TYPE:
            case UNKNOWN_TYPE:
            default:
                return java.sql.Types.VARCHAR;
        }
    }

    private Object convertValue(Object rawValue, TypeDescriptor targetType) {
        switch (targetType.getType()) {
            case BOOLEAN_TYPE:
                return convertBoolean(rawValue);
            case INTEGER_TYPE:
                return convertInteger(rawValue, targetType);
            case FLOAT_TYPE:
                return convertFloat(rawValue);
            case DOUBLE_TYPE:
                return convertDouble(rawValue);
            case DECIMAL_TYPE:
                return convertDecimal(rawValue);
            case DATE_TYPE:
                return convertDate(rawValue);
            case TIME_TYPE:
                return targetType.isWithTimezone() ? convertOffsetTime(rawValue) : convertTime(rawValue);
            case TIMESTAMP_TYPE:
                return targetType.isWithTimezone() ? convertOffsetDateTime(rawValue) : convertTimestamp(rawValue);
            case UUID_TYPE:
                return convertUuid(rawValue);
            case STRING_TYPE:
            case JSON_TYPE:
            case XML_TYPE:
            case ENUM_TYPE:
            case OBJECT_TYPE:
            case ARRAY_TYPE:
            case MAP_TYPE:
            case STRUCT_TYPE:
            case GEOMETRY_TYPE:
            case INTERVAL_TYPE:
                // 这些列在 JDBC 侧都是 VARCHAR/JSON：必须写字符串。把 String 先转成 byte[]
                // 会以字节数组绑定参数，PostgreSQL 会报 "column is of type varchar but
                // expression is of type bytea"，MySQL 则按二进制写入。
                return convertText(rawValue);
            case BINARY_TYPE:
                // byte[] 的 toString() 是 "[B@xxxx"，会把二进制写成无意义文本
                return convertBinary(rawValue);
            case NULL_TYPE:
            case UNKNOWN_TYPE:
            default:
                return rawValue.toString();
        }
    }

    /**
     * 文本列：保留字符串，byte[]/ByteBuffer 严格按 UTF-8 解码，其余走 toString()。
     *
     * <p>用 {@code new String(bytes, UTF_8)} 会把非法字节静默换成 {@code U+FFFD}：
     * {@code [0xC3,0x28]} 和 {@code [0xEF,0xBF,0xBD]} 都会解成同一个替换字符，
     * 于是两条不同的值在目标库写成同一个字符串（静默串数据）。解码失败时退化成十六进制。
     */
    private Object convertText(Object rawValue) {
        if (rawValue == null) {
            return null;
        }
        if (rawValue instanceof byte[]) {
            return decodeUtf8OrHex((byte[]) rawValue);
        }
        if (rawValue instanceof java.nio.ByteBuffer) {
            java.nio.ByteBuffer buffer = ((java.nio.ByteBuffer) rawValue).duplicate();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return decodeUtf8OrHex(bytes);
        }
        return rawValue.toString();
    }

    private static String decodeUtf8OrHex(byte[] bytes) {
        try {
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
        } catch (java.nio.charset.CharacterCodingException exception) {
            return toHex(bytes);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            hex.append(Character.forDigit((value >> 4) & 0xF, 16));
            hex.append(Character.forDigit(value & 0xF, 16));
        }
        return hex.toString().toUpperCase(java.util.Locale.ROOT);
    }

    private Object convertBinary(Object rawValue) {
        if (rawValue instanceof byte[]) {
            return rawValue;
        }
        if (rawValue instanceof java.nio.ByteBuffer) {
            java.nio.ByteBuffer buffer = ((java.nio.ByteBuffer) rawValue).duplicate();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        }
        if (rawValue instanceof String) {
            return ((String) rawValue).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        return rawValue.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private Object convertInteger(Object rawValue, TypeDescriptor targetType) {
        long value = rawValue instanceof Number
                ? ((Number) rawValue).longValue()
                : Long.parseLong(rawValue.toString().trim());
        Integer bitWidth = targetType.getBitWidth();
        if (bitWidth != null && bitWidth <= 16) {
            return (short) value;
        }
        if (bitWidth != null && bitWidth < 64) {
            return (int) value;
        }
        return value;
    }

    private BigDecimal convertDecimal(Object rawValue) {
        return rawValue instanceof BigDecimal
                ? (BigDecimal) rawValue
                : new BigDecimal(rawValue.toString().trim());
    }

    private Float convertFloat(Object rawValue) {
        return rawValue instanceof Number
                ? ((Number) rawValue).floatValue()
                : Float.parseFloat(rawValue.toString().trim());
    }

    private Double convertDouble(Object rawValue) {
        return rawValue instanceof Number
                ? ((Number) rawValue).doubleValue()
                : Double.parseDouble(rawValue.toString().trim());
    }

    private Boolean convertBoolean(Object rawValue) {
        if (rawValue instanceof Boolean) {
            return (Boolean) rawValue;
        }
        String normalized = rawValue.toString().trim().toLowerCase(Locale.ROOT);
        switch (normalized) {
            case "1":
            case "t":
            case "true":
            case "y":
            case "yes":
                return true;
            case "0":
            case "f":
            case "false":
            case "n":
            case "no":
                return false;
            default:
                throw new IllegalArgumentException("Unsupported boolean literal: " + rawValue);
        }
    }

    private Date convertDate(Object rawValue) {
        if (rawValue instanceof Date) {
            return (Date) rawValue;
        }
        if (rawValue instanceof LocalDate) {
            return Date.valueOf((LocalDate) rawValue);
        }
        if (rawValue instanceof Instant) {
            return Date.valueOf(((Instant) rawValue).atZone(ZoneOffset.UTC).toLocalDate());
        }
        return Date.valueOf(LocalDate.parse(rawValue.toString().trim()));
    }

    private Time convertTime(Object rawValue) {
        if (rawValue instanceof Time) {
            return (Time) rawValue;
        }
        if (rawValue instanceof LocalTime) {
            return Time.valueOf((LocalTime) rawValue);
        }
        return Time.valueOf(LocalTime.parse(rawValue.toString().trim()));
    }

    private OffsetTime convertOffsetTime(Object rawValue) {
        if (rawValue instanceof OffsetTime) {
            return (OffsetTime) rawValue;
        }
        if (rawValue instanceof LocalTime) {
            return ((LocalTime) rawValue).atOffset(ZoneOffset.UTC);
        }
        return OffsetTime.parse(rawValue.toString().trim());
    }

    private Timestamp convertTimestamp(Object rawValue) {
        if (rawValue instanceof Timestamp) {
            return (Timestamp) rawValue;
        }
        if (rawValue instanceof Instant) {
            return Timestamp.from((Instant) rawValue);
        }
        if (rawValue instanceof LocalDateTime) {
            return Timestamp.valueOf((LocalDateTime) rawValue);
        }
        if (rawValue instanceof OffsetDateTime) {
            return Timestamp.from(((OffsetDateTime) rawValue).toInstant());
        }
        try {
            return Timestamp.from(Instant.parse(rawValue.toString().trim()));
        } catch (DateTimeParseException ignore) {
            return Timestamp.valueOf(LocalDateTime.parse(rawValue.toString().trim().replace(" ", "T")));
        }
    }

    private OffsetDateTime convertOffsetDateTime(Object rawValue) {
        if (rawValue instanceof OffsetDateTime) {
            return (OffsetDateTime) rawValue;
        }
        if (rawValue instanceof Instant) {
            return ((Instant) rawValue).atOffset(ZoneOffset.UTC);
        }
        if (rawValue instanceof Timestamp) {
            return ((Timestamp) rawValue).toInstant().atOffset(ZoneOffset.UTC);
        }
        try {
            return OffsetDateTime.parse(rawValue.toString().trim());
        } catch (DateTimeParseException ignore) {
            return LocalDateTime.parse(rawValue.toString().trim().replace(" ", "T")).atOffset(ZoneOffset.UTC);
        }
    }

    private UUID convertUuid(Object rawValue) {
        return rawValue instanceof UUID ? (UUID) rawValue : UUID.fromString(rawValue.toString().trim());
    }

    /**
     * 从 {@code columnType} 声明里切出类型定义（去掉列约束）。
     *
     * <p>取**最靠左**的约束关键字，而不是按关键字列表顺序取第一个命中的：否则
     * {@code INT DEFAULT 5 NOT NULL} 会先命中 {@code NOT NULL}，把 {@code DEFAULT 5} 当成
     * 类型的一部分；{@code VARCHAR(20) DEFAULT NULL} 会先命中 {@code NULL}，切出
     * {@code VARCHAR(20) DEFAULT}。括号内与引号内的文本不参与匹配，{@code ENUM('PRIMARY KEY')}
     * 这类声明不会被截断。
     */
    String extractTypeDefinition(String declaredColumnType) {
        String normalized = declaredColumnType.trim();
        int cut = firstConstraintIndex(normalized);
        return cut < 0 ? normalized : normalized.substring(0, cut).trim();
    }

    /** 列约束关键字的合法起始位置（词边界、括号与引号外）；没有则返回 -1。 */
    private static int firstConstraintIndex(String declaration) {
        int depth = 0;
        boolean quoted = false;
        for (int index = 0; index < declaration.length(); index++) {
            char current = declaration.charAt(index);
            if (quoted) {
                if (current == '\'') {
                    quoted = false;
                }
                continue;
            }
            if (current == '\'') {
                quoted = true;
                continue;
            }
            if (current == '(') {
                depth++;
                continue;
            }
            if (current == ')') {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (depth > 0 || index == 0 || !Character.isWhitespace(current)) {
                continue;
            }
            for (String keyword : TYPE_CONSTRAINT_KEYWORDS) {
                if (matchesKeywordAt(declaration, index + 1, keyword)) {
                    return index;
                }
            }
        }
        return -1;
    }

    private static boolean matchesKeywordAt(String declaration, int start, String keyword) {
        int end = start + keyword.length();
        if (end > declaration.length()) {
            return false;
        }
        if (!declaration.regionMatches(true, start, keyword, 0, keyword.length())) {
            return false;
        }
        return end == declaration.length() || Character.isWhitespace(declaration.charAt(end))
                || declaration.charAt(end) == '(';
    }

    private String extractTypeConstraintSuffix(String declaredColumnType, String typeDefinition) {
        String normalized = declaredColumnType.trim();
        if (typeDefinition == null || typeDefinition.isBlank()) {
            return "";
        }
        if (normalized.length() <= typeDefinition.length()) {
            return "";
        }
        return normalized.substring(typeDefinition.length()).trim();
    }

    private String baseTypeName(String declaration) {
        return extractTypeDefinition(declaration);
    }
}
