package com.consilens.connector.file;

import com.consilens.connector.api.model.DataType;

import java.math.BigDecimal;

/**
 * Mirrors ValueNormalizer.normalizeKey for the scalar types the file connector
 * infers, so the scanner's sort order matches StreamingMergePlan's comparator.
 * String formatting is intentionally locale-independent.
 */
final class FileKeyNormalizer {

    private FileKeyNormalizer() {
    }

    static String normalizeKey(Object value, DataType type) {
        if (value == null) {
            return "";
        }
        switch (type) {
            case BIGINT:
            case INTEGER:
            case SMALLINT:
            case TINYINT:
                return value instanceof Number
                        ? String.valueOf(((Number) value).longValue())
                        : String.valueOf(value).trim();
            case DOUBLE:
            case FLOAT:
            case REAL:
                if (!(value instanceof Number)) {
                    return String.valueOf(value).trim();
                }
                double d = ((Number) value).doubleValue();
                if (Math.abs(d) > 1e10 || (Math.abs(d) < 1e-10 && d != 0)) {
                    return String.format(java.util.Locale.ROOT, "%.6e", d);
                }
                return String.format(java.util.Locale.ROOT, "%.6f", d);
            case DECIMAL:
            case NUMERIC:
                try {
                    BigDecimal decimal = new BigDecimal(String.valueOf(value)).stripTrailingZeros();
                    return decimal.signum() == 0 ? "0" : decimal.toPlainString();
                } catch (NumberFormatException exception) {
                    return String.valueOf(value);
                }
            case BOOLEAN:
            case BIT:
                if (value instanceof Boolean) {
                    return ((Boolean) value) ? "1" : "0";
                }
                return String.valueOf(value);
            default:
                return String.valueOf(value).trim();
        }
    }
}
