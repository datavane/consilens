package com.consilens.connector.file;

import com.consilens.connector.api.ConnectorException;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvRecordReaderTest {

    @Test
    void shouldReadQuotedMultilineField() throws Exception {
        CsvRecordReader reader = reader("1,\"hello\nworld\",10\n2,b,20\n");

        CsvRecordReader.Record first = reader.read();
        assertEquals(List.of("1", "hello\nworld", "10"), first.fields());
        assertEquals(List.of("2", "b", "20"), reader.read().fields());
        assertNull(reader.read());
    }

    @Test
    void shouldNormalizeCrLfInsideQuotes() throws Exception {
        CsvRecordReader reader = reader("1,\"a\r\nb\"\r\n");

        assertEquals(List.of("1", "a\nb"), reader.read().fields());
    }

    @Test
    void shouldHandleEscapedQuotesAcrossLines() throws Exception {
        CsvRecordReader reader = reader("1,\"say \"\"hi\"\"\nbye\"\n");

        assertEquals(List.of("1", "say \"hi\"\nbye"), reader.read().fields());
    }

    @Test
    void shouldSkipBlankLines() throws Exception {
        CsvRecordReader reader = reader("\n  \n1,a\n");

        assertTrue(reader.read().blank());
        assertTrue(reader.read().blank());
        assertEquals(List.of("1", "a"), reader.read().fields());
    }

    @Test
    void shouldRejectUnterminatedQuotedField() {
        CsvRecordReader reader = reader("1,\"hello\nworld");

        assertThrows(ConnectorException.class, reader::read);
    }

    private CsvRecordReader reader(String text) {
        return new CsvRecordReader(new StringReader(text), ',');
    }
}
