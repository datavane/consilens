package com.consilens.connector.clickhouse;

import com.consilens.common.type.StructField;
import com.consilens.common.type.TypeDescriptor;
import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.model.DataType;
import com.consilens.conncetor.base.BaseDataTypeHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ClickHouse data type handler.
 * 
 * <p>
 * Provides ClickHouse-specific data type normalization and conversion:
 * <ul>
 * <li>Column normalization for checksum calculation</li>
 * <li>Data type mapping from source to ClickHouse types</li>
 * <li>Timestamp formatting for ClickHouse</li>
 * </ul>
 * 
 * @since 1.0.0
 */
public class ClickHouseDataTypeHandler extends BaseDataTypeHandler {

    public ClickHouseDataTypeHandler(CapabilityProvider capabilityProvider) {
        this(capabilityProvider, null);
    }
    
    /**
     * Constructs a new ClickHouse data type handler with normalization configuration.
     * 
     * @param capabilityProvider capability provider
     * @param normalizationConfig normalization configuration map
     */
    public ClickHouseDataTypeHandler(CapabilityProvider capabilityProvider, Map<String, ?> normalizationConfig) {
        super(capabilityProvider, normalizationConfig);
    }

    /**
     * 科学计数渲染：ClickHouse {@code printf('%.6e', v)}（C/Java 同形）。
     *
     * <p>必须包成 {@code Nullable(String)}：{@code if(cond, a, b)} 会统一两个分支的类型，
     * 科学计数分支为 Nullable、定点分支为 String 时，遇 NULL 会报
     * “Cannot convert NULL value to non-Nullable type”（列本身是 Nullable(Float64)）。
     */
    @Override
    protected String scientificFloatExpression(String quotedCol) {
        return "CAST(printf('%.6e', " + quotedCol + ") AS Nullable(String))";
    }


    @Override
    public TypeDescriptor convertToTypeDescriptor(String originType) {
        if (originType == null || originType.isBlank()) {
            return TypeDescriptor.builder(com.consilens.common.enums.DataType.UNKNOWN_TYPE)
                    .originType(originType)
                    .build();
        }

        String upperType = normalizeTypeExpression(originType);
        if (upperType.startsWith("NULLABLE(") && upperType.endsWith(")")) {
            TypeDescriptor inner = convertToTypeDescriptor(originType.substring(originType.indexOf('(') + 1, originType.lastIndexOf(')')));
            return inner.toBuilder().originType(originType).nullable(true).build();
        }
        if (upperType.startsWith("LOWCARDINALITY(") && upperType.endsWith(")")) {
            TypeDescriptor inner = convertToTypeDescriptor(originType.substring(originType.indexOf('(') + 1, originType.lastIndexOf(')')));
            return inner.toBuilder().originType(originType).build();
        }
        if (upperType.startsWith("ARRAY(") && upperType.endsWith(")")) {
            TypeDescriptor elementType = convertToTypeDescriptor(originType.substring(originType.indexOf('(') + 1, originType.lastIndexOf(')')));
            return TypeDescriptor.builder(com.consilens.common.enums.DataType.ARRAY_TYPE)
                    .originType(originType)
                    .elementType(elementType)
                    .build();
        }
        if (upperType.startsWith("MAP(") && upperType.endsWith(")")) {
            List<String> arguments = splitTopLevel(originType.substring(originType.indexOf('(') + 1, originType.lastIndexOf(')')));
            TypeDescriptor keyType = arguments.size() > 0 ? convertToTypeDescriptor(arguments.get(0)) : TypeDescriptor.builder(com.consilens.common.enums.DataType.UNKNOWN_TYPE).build();
            TypeDescriptor valueType = arguments.size() > 1 ? convertToTypeDescriptor(arguments.get(1)) : TypeDescriptor.builder(com.consilens.common.enums.DataType.UNKNOWN_TYPE).build();
            return TypeDescriptor.builder(com.consilens.common.enums.DataType.MAP_TYPE)
                    .originType(originType)
                    .keyType(keyType)
                    .valueType(valueType)
                    .build();
        }
        if (upperType.startsWith("TUPLE(") && upperType.endsWith(")")) {
            List<StructField> fields = new ArrayList<>();
            List<String> parts = splitTopLevel(originType.substring(originType.indexOf('(') + 1, originType.lastIndexOf(')')));
            for (int i = 0; i < parts.size(); i++) {
                String part = parts.get(i).trim();
                int separator = findTopLevelCharacter(part, ' ');
                if (separator > 0) {
                    fields.add(StructField.builder(part.substring(0, separator).trim().replace("`", "").replace("\"", ""),
                            convertToTypeDescriptor(part.substring(separator + 1).trim())).build());
                } else {
                    fields.add(StructField.builder("field_" + i, convertToTypeDescriptor(part)).build());
                }
            }
            return TypeDescriptor.builder(com.consilens.common.enums.DataType.STRUCT_TYPE)
                    .originType(originType)
                    .fields(fields)
                    .build();
        }

        String baseType = extractBaseType(upperType);
        switch (baseType) {
            case "INT8":
            case "TINYINT":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.INTEGER_TYPE).originType(originType).bitWidth(8).build();
            case "INT16":
            case "SMALLINT":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.INTEGER_TYPE).originType(originType).bitWidth(16).build();
            case "INT32":
            case "INT":
            case "INTEGER":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.INTEGER_TYPE).originType(originType).bitWidth(32).build();
            case "INT64":
            case "BIGINT":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.INTEGER_TYPE).originType(originType).bitWidth(64).build();
            case "INT128":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.INTEGER_TYPE).originType(originType).bitWidth(128).build();
            case "INT256":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.INTEGER_TYPE).originType(originType).bitWidth(256).build();
            case "UINT8":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.INTEGER_TYPE).originType(originType).bitWidth(8).unsigned(true).build();
            case "UINT16":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.INTEGER_TYPE).originType(originType).bitWidth(16).unsigned(true).build();
            case "UINT32":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.INTEGER_TYPE).originType(originType).bitWidth(32).unsigned(true).build();
            case "UINT64":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.INTEGER_TYPE).originType(originType).bitWidth(64).unsigned(true).build();
            case "UINT128":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.INTEGER_TYPE).originType(originType).bitWidth(128).unsigned(true).build();
            case "UINT256":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.INTEGER_TYPE).originType(originType).bitWidth(256).unsigned(true).build();
            case "FLOAT32":
            case "FLOAT":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.FLOAT_TYPE).originType(originType).build();
            case "FLOAT64":
            case "DOUBLE":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.DOUBLE_TYPE).originType(originType).build();
            case "DECIMAL":
            case "DECIMAL32":
            case "DECIMAL64":
            case "DECIMAL128":
            case "DECIMAL256": {
                Integer[] precisionScale = extractPrecisionScale(upperType);
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.DECIMAL_TYPE)
                        .originType(originType)
                        .numericPrecision(precisionScale[0] != null ? precisionScale[0] : 10)
                        .numericScale(precisionScale[1] != null ? precisionScale[1] : 0)
                        .build();
            }
            case "BOOL":
            case "BOOLEAN":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.BOOLEAN_TYPE).originType(originType).build();
            case "STRING":
            case "TEXT":
            case "LONGTEXT":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.STRING_TYPE).originType(originType).textType(true).build();
            case "FIXEDSTRING":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.STRING_TYPE).originType(originType).length(extractLength(upperType)).build();
            case "DATE":
            case "DATE32":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.DATE_TYPE).originType(originType).build();
            case "DATETIME":
            case "DATETIME64":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.TIMESTAMP_TYPE)
                        .originType(originType)
                        .timePrecision(extractLength(upperType))
                        .build();
            case "UUID":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.UUID_TYPE).originType(originType).build();
            case "ENUM8":
            case "ENUM16":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.ENUM_TYPE).originType(originType).build();
            case "IPV4":
            case "IPV6":
                return TypeDescriptor.builder(com.consilens.common.enums.DataType.STRING_TYPE).originType(originType).build();
            default:
                return super.convertToTypeDescriptor(originType);
        }
    }

    @Override
    public String convertToOriginType(TypeDescriptor typeDescriptor) {
        if (typeDescriptor == null) {
            return "String";
        }
        if (typeDescriptor.getOriginType() != null && !typeDescriptor.getOriginType().isBlank()) {
            return typeDescriptor.getOriginType();
        }
        switch (typeDescriptor.getType()) {
            case INTEGER_TYPE: {
                int bitWidth = typeDescriptor.getBitWidth() != null ? typeDescriptor.getBitWidth() : 32;
                boolean unsigned = typeDescriptor.isUnsigned();
                if (bitWidth <= 8) return unsigned ? "UInt8" : "Int8";
                if (bitWidth <= 16) return unsigned ? "UInt16" : "Int16";
                if (bitWidth <= 32) return unsigned ? "UInt32" : "Int32";
                if (bitWidth <= 64) return unsigned ? "UInt64" : "Int64";
                if (bitWidth <= 128) return unsigned ? "UInt128" : "Int128";
                return unsigned ? "UInt256" : "Int256";
            }
            case FLOAT_TYPE:
                return "Float32";
            case DOUBLE_TYPE:
                return "Float64";
            case DECIMAL_TYPE:
                return "Decimal(" + (typeDescriptor.getNumericPrecision() != null ? typeDescriptor.getNumericPrecision() : 10)
                        + "," + (typeDescriptor.getNumericScale() != null ? typeDescriptor.getNumericScale() : 0) + ")";
            case BOOLEAN_TYPE:
                return "Bool";
            case STRING_TYPE:
                if (typeDescriptor.getLength() != null && !typeDescriptor.isTextType()) {
                    return "FixedString(" + typeDescriptor.getLength() + ")";
                }
                return "String";
            case ENUM_TYPE:
                return "String";
            case BINARY_TYPE:
                return "String";
            case DATE_TYPE:
                return "Date";
            case TIMESTAMP_TYPE:
                return typeDescriptor.getTimePrecision() != null && typeDescriptor.getTimePrecision() > 0
                        ? "DateTime64(" + typeDescriptor.getTimePrecision() + ")"
                        : "DateTime";
            case UUID_TYPE:
                return "UUID";
            case ARRAY_TYPE:
                return "Array(" + convertToOriginType(typeDescriptor.getElementType()) + ")";
            case MAP_TYPE:
                return "Map(" + convertToOriginType(typeDescriptor.getKeyType()) + ", "
                        + convertToOriginType(typeDescriptor.getValueType()) + ")";
            case STRUCT_TYPE: {
                StringBuilder builder = new StringBuilder("Tuple(");
                List<StructField> fields = typeDescriptor.getFields();
                for (int i = 0; i < fields.size(); i++) {
                    if (i > 0) {
                        builder.append(", ");
                    }
                    builder.append(fields.get(i).getName()).append(' ')
                            .append(convertToOriginType(fields.get(i).getTypeDescriptor()));
                }
                return builder.append(')').toString();
            }
            default:
                return super.convertToOriginType(typeDescriptor);
        }
    }

    /**
     * ClickHouse-specific string normalization.
     * Use trim to remove leading/trailing spaces for cross-database consistency.
     *
     * <p>必须先 CAST 成 String：FixedString 以 NUL 补齐（'S00001' 存成 'S00001\0\0'），
     * 而 ClickHouse 的 trim 只去空格、不去 NUL，本地路径（Java String.trim 会去掉 ≤U+0020）
     * 与校验和/join 两侧口径就会不一致——实测 join 把 FixedString 列的每一行都判成差异
     * （typediff 200 MM，期望 1）。FixedString→String 的转换会去掉尾部 NUL 补齐。
     */
    @Override
    protected String normalizeString(String quotedCol) {
        return "COALESCE(trim(CAST(" + quotedCol + " AS Nullable(String))), " + stringNullReplacement() + ")";
    }

    /**
     * ClickHouse-specific integer normalization.
     * NOTE: ClickHouse is strict about type compatibility in WHERE clauses.
     * When a SELECT alias matches a column name, ClickHouse may resolve WHERE
     * references to the SELECT expression. If we CAST integers to String here,
     * range comparisons like {@code order_id >= 1} fail with NO_COMMON_TYPE.
     * To avoid this, return the original column and let the query generator
     * apply toString() explicitly inside concat() for checksum calculation.
     */
    @Override
    protected String normalizeInteger(String quotedCol) {
        // NULL 归一为 '0'，与 MySQL 端 COALESCE(TRIM(CAST(x AS CHAR)), '') 对齐；
        // 裸列会让 Nullable 整数保持 NULL，本地取值路径渲染为 null 造成假差异。
        return "COALESCE(trim(toString(" + quotedCol + ")), '')";
    }

    /**
     * ClickHouse-specific decimal normalization with configurable decimal places.
     * Manually formats decimal values to ensure trailing zeros for cross-database consistency.
     * Note: ClickHouse 23.3 has neither printf (added in 23.7) nor formatDecimal (removed in 23.3).
     * Uses trunc + lpad approach: split into integer and fractional parts, pad with trailing zeros.
     */
    @Override
    protected String normalizeDecimal(String quotedCol) {
        // Get precision from config, default to 4
        int precision = getPrecision("decimal", 4);
        // Get rounding config, default to true (round half up)
        boolean rounding = getRounding("decimal", true);

        // If precision is 0, return integer format without decimal point
        if (precision == 0) {
            if (rounding) {
                return "COALESCE(trim(CAST(round(" + quotedCol + ", 0) AS String)), '')";
            } else {
                return "COALESCE(trim(CAST(truncate(" + quotedCol + ", 0) AS String)), '')";
            }
        }

        String roundedCol = rounding
                ? "round(" + quotedCol + ", " + precision + ")"
                : "truncate(" + quotedCol + ", " + precision + ")";

        // Format: sign integer_part '.' fractional_part_padded_to_precision
        // 符号取自舍入后的值：trunc() 对 (-1,0) 区间返回 -0，toString(-0) 丢符号
        // （-0.5 渲染成 0.5000）；而 Float64 的 -0.0 又保留符号（-0.0000）。
        // 以 round(x,p) < 0 判定符号与 MySQL FORMAT 一致，且舍入到 0 时自然无符号。
        return "COALESCE(concat(if(" + roundedCol + " < 0, '-', ''), toString(abs(trunc(" + roundedCol
                + "))), '.', lpad(toString(round(abs(" + roundedCol + " - trunc(" + roundedCol
                + ")) * " + pow10(precision) + ", 0)), " + precision + ", '0')), '')";
    }

    /**
     * ClickHouse-specific float normalization with configurable decimal places.
     * CRITICAL: FLOAT is single-precision and may have precision issues.
     * Cast to Float64 (DOUBLE) first to ensure consistent formatting.
     * Uses trunc + lpad approach for ClickHouse 23.3 compatibility.
     */
    @Override
    protected String normalizeFloat(String quotedCol) {
        return "CAST(" + normalizeFloatRaw(quotedCol) + " AS Nullable(String))";
    }

    /** 定点 6 位小数渲染（未做 Nullable 包装）。 */
    protected String normalizeFloatRaw(String quotedCol) {
        // Get precision from config, default to 6
        int precision = getPrecision("float", 6);
        // Get rounding config, default to true (round half up)
        boolean rounding = getRounding("float", true);

        // If precision is 0, return integer format without decimal point
        if (precision == 0) {
            if (rounding) {
                return "COALESCE(trim(CAST(round(CAST(" + quotedCol + " AS Nullable(Float64)), 0) AS String)), '')";
            } else {
                return "COALESCE(trim(CAST(truncate(CAST(" + quotedCol + " AS Nullable(Float64)), 0) AS String)), '')";
            }
        }

        String roundedCol = rounding
                ? "round(CAST(" + quotedCol + " AS Nullable(Float64)), " + precision + ")"
                : "truncate(CAST(" + quotedCol + " AS Nullable(Float64)), " + precision + ")";

        // Same sign rule as normalizeDecimal: sign of the ROUNDED value, so
        // -0.0 renders '0.0000' matching MySQL FORMAT.
        return "COALESCE(concat(if(" + roundedCol + " < 0, '-', ''), toString(abs(trunc(" + roundedCol
                + "))), '.', lpad(toString(round(abs(" + roundedCol + " - trunc(" + roundedCol
                + ")) * " + pow10(precision) + ", 0)), " + precision + ", '0')), '')";
    }

    /**
     * Returns 10^n as a string for use in SQL.
     * Used to multiply fractional part by 10^precision to get integer representation.
     */
    private String pow10(int n) {
        StringBuilder sb = new StringBuilder("1");
        for (int i = 0; i < n; i++) {
            sb.append("0");
        }
        return sb.toString();
    }

    /**
     * ClickHouse-specific date normalization.
     * NOTE: Returns original column to avoid type mismatch in WHERE clauses.
     * Formatting is applied in the query generator's checksum SQL.
     */
    @Override
    protected String normalizeDate(String quotedCol) {
        // 必须渲染成字符串并 COALESCE：直接返回原列时 NULL 会在 checksum 的 concat/MD5 中传播，
        // 与其它方言的 NULL → '' 不一致（实测跨源全 NULL 行的 c_date 误报）。
        // 委托 checksum 格式化，保证 join/local 路径与 checksum 路径构造上同形。
        return formatDateForChecksum(quotedCol);
    }

    /**
     * ClickHouse-specific time normalization.
     * NOTE: Returns original column to avoid type mismatch in WHERE clauses.
     */
    @Override
    protected String normalizeTime(String quotedCol) {
        // 与 formatDateTimeForChecksum 的 time 分支同口径（构造上委托，防止两路漂移）。
        return formatDateTimeForChecksum(quotedCol, "time");
    }

    @Override
    protected String normalizeTimeWithTimezone(String quotedCol) {
        return formatDateTimeForChecksum(quotedCol, "time_with_timezone");
    }

    /**
     * ClickHouse-specific datetime normalization.
     * Converts to UTC and formats as string to stay consistent with MySQL's
     * CONVERT_TZ-based normalization and the checksum SQL path.
     */
    @Override
    protected String normalizeDateTime(String quotedCol) {
        return "COALESCE(formatDateTime(toTimeZone(" + quotedCol + ", '"
                + resolveClickHouseTimezone("datetime", "UTC") + "'), '"
                + resolveClickHouseTemporalFormat("datetime", "%Y-%m-%d %H:%i:%S", "%Y-%m-%d") + "'), '')";
    }

    /**
     * ClickHouse-specific timestamp normalization.
     * Converts to UTC and formats as string to stay consistent with MySQL's
     * CONVERT_TZ-based normalization and the checksum SQL path.
     */
    @Override
    protected String normalizeTimestamp(String quotedCol) {
        return "COALESCE(formatDateTime(toTimeZone(" + quotedCol + ", '"
                + resolveClickHouseTimezone("timestamp", "UTC") + "'), '"
                + resolveClickHouseTemporalFormat("timestamp", "%Y-%m-%d %H:%i:%S", "%Y-%m-%d") + "'), '')";
    }

    /**
     * ClickHouse-specific timestamp with timezone normalization.
     * Converts to UTC and formats as string to stay consistent with MySQL's
     * CONVERT_TZ-based normalization and the checksum SQL path.
     */
    @Override
    protected String normalizeTimestampWithTimezone(String quotedCol) {
        return "COALESCE(formatDateTime(toTimeZone(" + quotedCol + ", '"
                + resolveClickHouseTimezone("timestamp_with_timezone", "UTC") + "'), '"
                + resolveClickHouseTemporalFormat("timestamp_with_timezone", "%Y-%m-%d %H:%i:%S", "%Y-%m-%d") + "'), '')";
    }

    /**
     * Returns the formatted SQL expression for date columns in checksum calculation.
     * Called by ClickHouseSqlQueryGenerator to format Date columns for concat().
     */
    public String formatDateForChecksum(String quotedCol) {
        return "COALESCE(toString(" + quotedCol + "), '')";
    }

    /**
     * Returns the formatted SQL expression for datetime/timestamp columns in checksum calculation.
     * Applies timezone conversion and formatting.
     */
    public String formatDateTimeForChecksum(String quotedCol, String dataType) {
        if ("timestamp".equals(dataType) || "timestamp_with_timezone".equals(dataType)) {
            return "COALESCE(formatDateTime(toTimeZone(" + quotedCol + ", '"
                    + resolveClickHouseTimezone(dataType, "UTC") + "'), '" + resolveClickHouseTemporalFormat(dataType,
                    "%Y-%m-%d %H:%i:%S", "%Y-%m-%d") + "'), '')";
        }
        return "COALESCE(formatDateTime(" + quotedCol + ", '" + resolveClickHouseTemporalFormat(dataType,
                "%Y-%m-%d %H:%i:%S", "%Y-%m-%d") + "'), '')";
    }

    private String resolveClickHouseTimezone(String dataTypeName, String defaultTimezone) {
        return escapeSqlLiteral(getTimezone(dataTypeName, defaultTimezone));
    }

    private String resolveClickHouseTemporalFormat(String dataTypeName, String defaultFormat, String dateOnlyDefaultFormat) {
        return getNativeTemporalFormat("ClickHouse", dataTypeName, defaultFormat, dateOnlyDefaultFormat,
                clickHouseTemporalTokens(dataTypeName));
    }

    private Set<String> clickHouseTemporalTokens(String dataTypeName) {
        Set<String> dateTokens = temporalTokens("%Y", "%y", "%m", "%d", "%e", "%F");
        Set<String> timeTokens = temporalTokens("%H", "%I", "%i", "%S", "%f", "%p", "%R", "%T");
        if ("date".equals(dataTypeName)) {
            return dateTokens;
        }
        if ("time".equals(dataTypeName) || "time_with_timezone".equals(dataTypeName)) {
            return timeTokens;
        }
        dateTokens.addAll(timeTokens);
        return dateTokens;
    }

    /**
     * ClickHouse-specific boolean normalization: '0' or '1'.
     */
    @Override
    protected String normalizeBoolean(String quotedCol) {
        return "CASE WHEN " + quotedCol + " IS NULL THEN '' WHEN " + quotedCol + " = 1 THEN '1' ELSE '0' END";
    }

    /**
     * ClickHouse-specific blob normalization: convert to uppercase hexadecimal.
     */
    @Override
    protected String normalizeBlob(String quotedCol) {
        return "COALESCE(upper(hex(" + quotedCol + ")), '')";
    }

    /**
     * ClickHouse-specific JSON normalization: convert to string.
     */
    @Override
    protected String normalizeJson(String quotedCol) {
        // 规范 JSON 形：ClickHouse 的 String 列保留原文（键序、空格），需经 JSON 类型
        // 规范化后再紧凑输出——`toJSONString(CAST(s AS JSON))` 会按键排序并紧凑渲染
        // （实测 `{"b":2,"a":1}` → `{"a":1,"b":2}`），与 MySQL/PG 的规范形一致。
        return "COALESCE(toJSONString(CAST(" + quotedCol + " AS Nullable(JSON))), '')";
    }

    /**
     * ClickHouse-specific default normalization.
     */
    @Override
    protected String normalizeDefault(String quotedCol) {
        return "COALESCE(trim(CAST(" + quotedCol + " AS String)), '')";
    }

    @Override
    public DataType convertToDataType(String sourceType) {
        if (sourceType == null) {
            return DataType.UNKNOWN;
        }
        String type = sourceType.trim().toLowerCase();
        if ((type.startsWith("nullable(") || type.startsWith("lowcardinality(")) && type.endsWith(")")) {
            return convertToDataType(type.substring(type.indexOf('(') + 1, type.length() - 1));
        }

        switch (type) {
            case "int8":
            case "tinyint":
                return DataType.TINYINT;
            case "int16":
            case "smallint":
                return DataType.SMALLINT;
            case "int32":
            case "int":
            case "integer":
                return DataType.INTEGER;
            case "int64":
            case "bigint":
                return DataType.BIGINT;
            case "uint8":
                return DataType.TINYINT;
            case "uint16":
                return DataType.SMALLINT;
            case "uint32":
                return DataType.INTEGER;
            case "uint64":
                return DataType.BIGINT;
            case "decimal":
            case "numeric":
                return DataType.DECIMAL;
            case "float32":
            case "float":
            case "real":
                return DataType.FLOAT;
            case "float64":
            case "double":
                return DataType.DOUBLE;
            case "string":
            case "varchar":
                return DataType.VARCHAR;
            case "fixedstring":
            case "char":
                return DataType.CHAR;
            case "text":
                return DataType.TEXT;
            case "date":
                return DataType.DATE;
            case "datetime":
            case "datetime64":
            case "timestamp":
                return DataType.TIMESTAMP;
            case "boolean":
            case "bool":
                return DataType.BOOLEAN;
            case "uuid":
                return DataType.VARCHAR;
            case "array":
            case "map":
            case "tuple":
                return DataType.TEXT;
            default:
                return super.convertToDataType(sourceType);
        }
    }

    @Override
    public String formatDataType(DataType dataType, int length, int precision, int scale) {
        switch (dataType) {
            case VARCHAR:
                return length > 0 ? "VARCHAR(" + length + ")" : "VARCHAR(255)";
            case CHAR:
                return length > 0 ? "CHAR(" + length + ")" : "CHAR(1)";
            case TEXT:
                return "TEXT";
            case TINYINT:
                return "TINYINT";
            case SMALLINT:
                return "SMALLINT";
            case INTEGER:
                return "INTEGER";
            case BIGINT:
                return "BIGINT";
            case DECIMAL:
                if (precision > 0) {
                    return "DECIMAL(" + precision + (scale > 0 ? "," + scale : "") + ")";
                }
                return "DECIMAL";
            case FLOAT:
                return "REAL";
            case DOUBLE:
                return "DOUBLE PRECISION";
            case DATE:
                return "DATE";
            case TIMESTAMP:
                return "TIMESTAMP";
            case BOOLEAN:
                return "BOOLEAN";
            case JSON:
                return "JSON";
            case JSONB:
                return "JSONB";
            default:
                return super.formatDataType(dataType, length, precision, scale);
        }
    }

    @Override
    public String formatTimestampValue(Object timestamp) {
        if (timestamp == null) {
            return "NULL";
        }
        return timestamp.toString();
    }

    @Override
    public Object parseTimestampValue(Object value) {
        return value;
    }
}
