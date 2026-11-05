package com.consilens.connector.file;

import com.consilens.connector.api.ConnectorException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CsvParserTest {

    @Test
    void shouldParseQuotedFieldsAndEscapedQuotes() {
        List<String> fields = CsvParser.parseLine("1,\"alice, smith\",\"say \"\"hi\"\"\"", ',');
        assertEquals(List.of("1", "alice, smith", "say \"hi\""), fields);
    }

    @Test
    void shouldKeepEmptyTrailingField() {
        assertEquals(List.of("a", ""), CsvParser.parseLine("a,", ','));
    }

    @Test
    void shouldSupportCustomDelimiter() {
        assertEquals(List.of("a", "b"), CsvParser.parseLine("a|b", '|'));
    }

    @Test
    void shouldRejectUnterminatedQuotedField() {
        ConnectorException exception = assertThrows(ConnectorException.class,
                () -> CsvParser.parseLine("1,\"hello", ','));

        assertEquals(true, exception.getMessage().contains("multi-line quoted fields"));
    }
}
