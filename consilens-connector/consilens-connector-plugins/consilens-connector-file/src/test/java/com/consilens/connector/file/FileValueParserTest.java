package com.consilens.connector.file;

import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.model.DataType;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileValueParserTest {

    @Test
    void shouldUnwrapJsonTextWithoutQuotes() {
        assertEquals("alice",
                FileValueParser.parse(TextNode.valueOf("alice"), DataType.VARCHAR, null));
    }

    @Test
    void shouldUnwrapJsonNumbersAndBooleans() {
        assertEquals(7L, FileValueParser.parse(IntNode.valueOf(7), DataType.BIGINT, null));
        assertEquals(Boolean.TRUE,
                FileValueParser.parse(BooleanNode.TRUE, DataType.BOOLEAN, null));
    }

    @Test
    void shouldMapJsonNullToNull() {
        assertNull(FileValueParser.parse(NullNode.getInstance(), DataType.VARCHAR, null));
    }

    @Test
    void shouldRejectInvalidNumericValue() {
        ConnectorException exception = assertThrows(ConnectorException.class,
                () -> FileValueParser.parse(TextNode.valueOf("abc"), DataType.BIGINT, null));
        assertEquals(true, exception.getMessage().contains("not a valid BIGINT"));
    }

    @Test
    void shouldRejectInvalidBooleanValue() {
        ConnectorException exception = assertThrows(ConnectorException.class,
                () -> FileValueParser.parse(TextNode.valueOf("maybe"), DataType.BOOLEAN, null));
        assertEquals(true, exception.getMessage().contains("not a valid BOOLEAN"));
    }

    @Test
    void shouldKeepDateTextForLaterNormalization() {
        assertEquals("2024-01-01",
                FileValueParser.parse(TextNode.valueOf("2024-01-01"), DataType.DATE, null));
    }

    @Test
    void shouldReadJdbcBlobAsBytesInsteadOfDriverToString() throws Exception {
        byte[] payload = new byte[]{1, 2, 3};

        Object parsed = FileValueParser.parse(
                new javax.sql.rowset.serial.SerialBlob(payload), DataType.BLOB, null);

        // DuckDB 的 Parquet BLOB 是 Blob 对象，toString() 只有 "DuckDBBlobResult{buffer=...}"：
        // 既不含内容（与 ORC/HEX 口径永远不等），又让长度相同的不同值判等
        assertEquals(byte[].class, parsed.getClass());
        org.junit.jupiter.api.Assertions.assertArrayEquals(payload, (byte[]) parsed);
    }

    @Test
    void shouldReadByteBufferAsBytes() {
        Object parsed = FileValueParser.parse(
                java.nio.ByteBuffer.wrap(new byte[]{4, 5}), DataType.BLOB, null);

        org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[]{4, 5}, (byte[]) parsed);
    }
}
