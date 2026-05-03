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

    private final DatabaseDialect dialect;

    public JdbcTableWriteCompiler(DatabaseDialect dialect) {
        this.dialect = dialect;
    }

    @Override
    public TableWritePlan compile(TableWriteCompileRequest request) {
        List<WriteColumnSpec> columns = new ArrayList<>();
        StringBuilder ddl = new StringBuilder("CREATE TABLE IF NOT EXISTS ")
                .append(request.getTableName())
                .append(" (");
        StringBuilder insert = new StringBuilder("INSERT INTO ")
                .append(request.getTableName())
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
                "DROP TABLE IF EXISTS " + request.getTableName(),
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

    private void bindValue(PreparedStatement preparedStatement, int index, Object value, Integer jdbcType) throws SQLException {
        int effectiveJdbcType = jdbcType != null ? jdbcType : java.sql.Types.VARCHAR;
        if (value == null) {
            preparedStatement.setNull(index, effectiveJdbcType);
            return;
        }
        switch (effectiveJdbcType) {
            case java.sql.Types.SMALLINT:
                preparedStatement.setShort(index, ((Number) value).shortValue());
                return;
            case java.sql.Types.INTEGER:
                preparedStatement.setInt(index, ((Number) value).intValue());
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
                return "postgresql".equals(dialect.getConnectorType()) ? java.sql.Types.OTHER : java.sql.Types.VARCHAR;
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
            case BINARY_TYPE:
            case NULL_TYPE:
            case UNKNOWN_TYPE:
            default:
                return rawValue.toString();
        }
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

    private String extractTypeDefinition(String declaredColumnType) {
        String normalized = declaredColumnType.trim();
        String upper = normalized.toUpperCase(Locale.ROOT);
        for (String keyword : List.of(" PRIMARY KEY", " NOT NULL", " NULL", " UNIQUE", " DEFAULT ", " CHECK ", " REFERENCES ")) {
            int index = upper.indexOf(keyword);
            if (index >= 0) {
                return normalized.substring(0, index).trim();
            }
        }
        return normalized;
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
