package com.consilens.sink.table;

import com.consilens.sink.api.model.ColumnMapping;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TableColumnNamesTest {

    @Test
    void prefixesIdentifiersThatWouldStartWithADigit() {
        assertEquals("c_1abc", TableColumnNames.sanitize("1abc"));
        assertEquals("c_123", TableColumnNames.sanitize("123"));
    }

    @Test
    void replacesUnsafeCharacters() {
        assertEquals("a_b", TableColumnNames.sanitize("a-b"));
        assertEquals("_x", TableColumnNames.sanitize(" x"));
    }

    @Test
    void rejectsBlankNames() {
        assertThrows(IllegalArgumentException.class, () -> TableColumnNames.sanitize(null));
        assertThrows(IllegalArgumentException.class, () -> TableColumnNames.sanitize("  "));
    }

    @Test
    void rejectsDuplicateOrNamelessCustomColumns() {
        assertThrows(IllegalArgumentException.class, () -> TableColumnNames.validateUniqueSanitizedColumns(
                List.of(mapping("a-b"), mapping("a_b")), "test"));
        assertThrows(IllegalArgumentException.class, () -> TableColumnNames.validateUniqueSanitizedColumns(
                List.of(mapping(null)), "test"));
    }

    private ColumnMapping mapping(String name) {
        ColumnMapping mapping = new ColumnMapping();
        mapping.setName(name);
        return mapping;
    }
}
