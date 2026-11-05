package com.consilens.connector.file;

import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.model.DataType;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;

/**
 * Value conversion for file records plus a key-normalization form that matches
 * the merge comparator's canonical key strings.
 */
final class FileValueParser {

    private FileValueParser() {
    }

    static Object parse(Object raw, DataType type, String nullValue) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof JsonNode) {
            JsonNode node = (JsonNode) raw;
            if (node.isNull()) {
                return null;
            }
            // TextNode.toString() adds JSON quotes; use the plain text so DATE/
            // TIMESTAMP normalization and comparisons see the actual value.
            raw = node.isTextual() ? node.textValue() : node.toString();
        }
        if (raw instanceof String) {
            String text = (String) raw;
            if (text.isEmpty() || (nullValue != null && nullValue.equals(text))) {
                return null;
            }
            text = text.trim();
            if (text.isEmpty()) {
                return null;
            }
            switch (type) {
                case BIGINT:
                case INTEGER:
                case SMALLINT:
                case TINYINT:
                    try {
                        return Long.parseLong(text);
                    } catch (NumberFormatException exception) {
                        throw invalid(type, text);
                    }
                case DOUBLE:
                case FLOAT:
                case REAL:
                    try {
                        return Double.parseDouble(text);
                    } catch (NumberFormatException exception) {
                        throw invalid(type, text);
                    }
                case DECIMAL:
                case NUMERIC:
                    // 定点数不能经 double：18 位有效数字以上的值会在解析阶段就被抹平，
                    // 之后无论怎么归一化都区分不出两个不同的 DECIMAL。
                    try {
                        return new java.math.BigDecimal(text);
                    } catch (NumberFormatException exception) {
                        throw invalid(type, text);
                    }
                case BOOLEAN:
                case BIT:
                    Boolean parsed = parseBoolean(text);
                    if (parsed == null) {
                        throw invalid(type, text);
                    }
                    return parsed;
                default:
                    return text;
            }
        }
        if (raw instanceof Boolean) {
            return raw;
        }
        if (raw instanceof byte[]) {
            // BLOB/BINARY 列必须保持字节：toString() 会变成 "[B@xxxx"
            return type != null && type.isBinary() ? raw : raw.toString();
        }
        if (raw instanceof java.sql.Blob) {
            // Parquet/ORC 的 BLOB 由 JDBC 驱动包成 Blob：直接 toString() 只会得到
            // "DuckDBBlobResult{buffer=...}"，既不含内容又让所有二进制列判等
            try {
                java.sql.Blob blob = (java.sql.Blob) raw;
                return blob.getBytes(1L, (int) blob.length());
            } catch (java.sql.SQLException exception) {
                throw new ConnectorException("Failed to read BLOB value: " + exception.getMessage(),
                        exception);
            }
        }
        if (raw instanceof java.nio.ByteBuffer) {
            java.nio.ByteBuffer buffer = ((java.nio.ByteBuffer) raw).duplicate();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        }
        if (raw instanceof Number) {
            if (type == DataType.DECIMAL || type == DataType.NUMERIC) {
                return toBigDecimal((Number) raw);
            }
            if (type == DataType.DOUBLE || type == DataType.FLOAT || type == DataType.REAL) {
                return ((Number) raw).doubleValue();
            }
            return ((Number) raw).longValue();
        }
        return raw.toString();
    }

    /** DECIMAL 列保持 BigDecimal：BigInteger/Long 精确，浮点数按其最短十进制表示。 */
    private static java.math.BigDecimal toBigDecimal(Number raw) {
        if (raw instanceof java.math.BigDecimal) {
            return (java.math.BigDecimal) raw;
        }
        if (raw instanceof java.math.BigInteger) {
            return new java.math.BigDecimal((java.math.BigInteger) raw);
        }
        if (raw instanceof Long || raw instanceof Integer || raw instanceof Short || raw instanceof Byte) {
            return java.math.BigDecimal.valueOf(raw.longValue());
        }
        return new java.math.BigDecimal(raw.toString());
    }

    private static Boolean parseBoolean(String text) {
        String normalized = text.toLowerCase(Locale.ROOT);
        if ("true".equals(normalized) || "1".equals(normalized)
                || "y".equals(normalized) || "yes".equals(normalized)) {
            return Boolean.TRUE;
        }
        if ("false".equals(normalized) || "0".equals(normalized)
                || "n".equals(normalized) || "no".equals(normalized)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static ConnectorException invalid(DataType type, String text) {
        return new ConnectorException("Value '" + text + "' is not a valid " + type.name());
    }
}
