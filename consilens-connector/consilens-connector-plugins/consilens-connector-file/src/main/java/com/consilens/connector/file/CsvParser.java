package com.consilens.connector.file;

import com.consilens.connector.api.ConnectorException;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses a single physical CSV line: quoted fields, escaped quotes and custom
 * delimiters. Multi-line records are handled by {@link CsvRecordReader}; this
 * method still rejects a line that ends inside a quoted field.
 */
public final class CsvParser {

    private CsvParser() {
    }

    public static List<String> parseLine(String line, char delimiter) {
        List<String> fields = new ArrayList<>();
        if (line == null) {
            return fields;
        }
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char ch = line.charAt(index);
            if (quoted) {
                if (ch == '"') {
                    if (index + 1 < line.length() && line.charAt(index + 1) == '"') {
                        current.append('"');
                        index++;
                    } else {
                        quoted = false;
                    }
                } else {
                    current.append(ch);
                }
            } else if (ch == '"' && current.length() == 0) {
                quoted = true;
            } else if (ch == delimiter) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        if (quoted) {
            throw new ConnectorException("CSV record ends inside a quoted field;"
                    + " multi-line quoted fields are not supported");
        }
        fields.add(current.toString());
        return fields;
    }
}
