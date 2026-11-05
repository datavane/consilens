package com.consilens.connector.api;

import com.consilens.common.type.TypeDescriptor;
import com.consilens.connector.api.model.DataType;

/**
 * Interface for database data type handling operations.
 * 
 * <p>
 * This interface provides methods for data type mapping, value conversion,
 * and column normalization for consistent data handling across different
 * databases.
 * It is a standalone component obtained from
 */
public interface DataTypeHandler extends TypeConverter {

    /**
     * Normalizes a column value for consistent checksum calculation.
     * 
     * <p>
     * This method generates a SQL expression that converts the column value to a
     * standardized string format. The normalization ensures:
     * <ul>
     * <li>Strings: trimmed, NULL → empty string</li>
     * <li>Integers: no decimals, NULL → '0'</li>
     * <li>Decimals: 4 decimal places, NULL → '0.0000'</li>
     * <li>Booleans: '0' or '1'</li>
     * <li>Dates: 'YYYY-MM-DD'</li>
     * <li>Times: 'HH24:MI:SS'</li>
     * <li>DateTimes: 'YYYY-MM-DD HH24:MI:SS'</li>
     * </ul>
     * 
     * @param columnName the name of the column to normalize
     * @param dataType   the data type of the column (using DataType enum)
     * @return SQL expression that normalizes the column value to a consistent
     *         string format
     */
    String normalizeColumn(String columnName, DataType dataType);

    /**
     * 用**已成形的列表达式**（可带表别名/引号）构造归一化表达式，不做任何额外引用包装。
     *
     * <p>{@link #normalizeColumn} 会把入参当裸列名自行加引号，join 等带别名场景必须用本方法，
     * 否则会出现 `t1.`col`` 这类被重复引用的非法 SQL。
     *
     * <p>默认实现抛出 {@link UnsupportedOperationException}（fail-fast）：仅实现旧接口
     * {@link #normalizeColumn} 的第三方 handler 若静默回退为原表达式，会产出未归一化的
     * join 谓词（比较口径与 checksum 不一致）。实现类必须覆盖本方法，或改为继承
     * {@code BaseDataTypeHandler}（其默认实现委托给 {@link #normalizeColumn}）。
     */
    default String normalizeExpression(String columnExpression, DataType dataType) {
        throw new UnsupportedOperationException(
                getClass().getSimpleName() + " does not implement normalizeExpression; "
                        + "override it (or extend BaseDataTypeHandler) to normalize join predicates");
    }

    /**
     * Convert source data type to internal DataType enum.
     *
     * @param sourceType the source data type string
     * @return the corresponding DataType enum
     */
    DataType convertToDataType(String sourceType);

    /**
     * Normalize a value into the representation used for in-memory comparison.
     *
     * <p>Most dialects return the JDBC value unchanged. Dialects whose driver
     * renders a logical type differently from its peers override this to map it
     * onto the common representation (e.g. MySQL YEAR arrives as
     * {@link java.sql.Date} while PostgreSQL exposes it as an integer).
     *
     * @param value    value read from the database
     * @param dataType logical column type, or {@link DataType#UNKNOWN} when unavailable
     * @return value in comparison form
     */
    default Object normalizeValueForComparison(Object value, DataType dataType) {
        return value;
    }


    @Override
    TypeDescriptor convertToTypeDescriptor(String originType);

    /**
     * Format a DataType into a database-specific type definition string.
     *
     * @param dataType  the internal DataType enum
     * @param length    the data type length (0 if not applicable)
     * @param precision the numeric precision (0 if not applicable)
     * @param scale     the numeric scale (0 if not applicable)
     * @return the formatted database-specific type string
     */
    String formatDataType(DataType dataType, int length, int precision, int scale);

    /**
     * Get database-specific data type mapping.
     * <p>
     * This method is now a convenience wrapper that calls
     * {@link #convertToDataType(String)}
     * and
     * {@link #formatDataType(DataType, int, int, int)}.
     *
     * @param sourceType the source data type
     * @param length     the data type length (0 if not applicable)
     * @param precision  the numeric precision (0 if not applicable)
     * @param scale      the numeric scale (0 if not applicable)
     * @return the mapped database-specific data type
     */
    default String getDataTypeMapping(String sourceType, int length, int precision, int scale) {
        return formatDataType(convertToDataType(sourceType), length, precision, scale);
    }

    /**
     * Convert a value to the appropriate database format.
     * 
     * @param value      the value to convert
     * @param targetType the target data type
     * @return the converted value
     */
    Object convertValue(Object value, String targetType);

    /**
     * Format a timestamp value according to database requirements.
     * 
     * @param timestamp the timestamp value
     * @return formatted timestamp string
     */
    String formatTimestampValue(Object timestamp);

    /**
     * Parse a timestamp value from database result.
     * 
     * @param value the value from database
     * @return parsed timestamp object
     */
    Object parseTimestampValue(Object value);
}
