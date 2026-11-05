package com.consilens.connector.file;

import com.consilens.connector.api.ConnectorException;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads logical CSV records, including quoted fields that span multiple
 * physical lines. Line endings inside quoted fields are normalized to {@code \n}.
 */
final class CsvRecordReader {

    private final PushbackReader reader;
    private final char delimiter;

    CsvRecordReader(Reader reader, char delimiter) {
        this.reader = new PushbackReader(reader, 1);
        this.delimiter = delimiter;
    }

    /**
     * @return the next record, or {@code null} at end of input
     */
    Record read() throws IOException {
        int first = reader.read();
        if (first == -1) {
            return null;
        }
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean anyQuote = false;
        int ch = first;
        while (true) {
            if (quoted) {
                if (ch == -1) {
                    throw new ConnectorException("CSV record ends inside a quoted field");
                }
                if (ch == '"') {
                    int next = reader.read();
                    if (next == '"') {
                        current.append('"');
                        ch = reader.read();
                    } else {
                        quoted = false;
                        ch = next;
                    }
                } else if (ch == '\r' || ch == '\n') {
                    if (ch == '\r') {
                        int next = reader.read();
                        if (next != '\n' && next != -1) {
                            reader.unread(next);
                        }
                    }
                    current.append('\n');
                    ch = reader.read();
                } else {
                    current.append((char) ch);
                    ch = reader.read();
                }
                continue;
            }

            if (ch == -1 || ch == '\n' || ch == '\r') {
                if (ch == '\r') {
                    int next = reader.read();
                    if (next != '\n' && next != -1) {
                        reader.unread(next);
                    }
                }
                fields.add(current.toString());
                boolean blank = !anyQuote && fields.size() == 1 && fields.get(0).isBlank();
                return new Record(fields, blank);
            }
            if (ch == delimiter) {
                fields.add(current.toString());
                current.setLength(0);
                ch = reader.read();
                continue;
            }
            if (ch == '"' && current.length() == 0) {
                quoted = true;
                anyQuote = true;
                ch = reader.read();
                continue;
            }
            current.append((char) ch);
            ch = reader.read();
        }
    }

    static final class Record {

        private final List<String> fields;
        private final boolean blank;

        Record(List<String> fields, boolean blank) {
            this.fields = fields;
            this.blank = blank;
        }

        List<String> fields() {
            return fields;
        }

        boolean blank() {
            return blank;
        }
    }
}
