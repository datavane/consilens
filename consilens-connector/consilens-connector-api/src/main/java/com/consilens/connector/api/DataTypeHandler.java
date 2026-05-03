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
     * Convert source data type to internal DataType enum.
     *
     * @param sourceType the source data type string
     * @return the corresponding DataType enum
     */
    DataType convertToDataType(String sourceType);

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
