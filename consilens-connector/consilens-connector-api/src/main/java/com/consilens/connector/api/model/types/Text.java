package com.consilens.connector.api.model.types;

import lombok.Getter;

import java.util.Objects;

/**
 * Text/string type implementation.
 */
@Getter
public class Text implements ColType, IKey {

    private final int length;
    private final boolean binary;

    public Text() {
        this(-1, false); // Unbounded text
    }

    public Text(int length) {
        this(length, false);
    }

    public Text(int length, boolean binary) {
        this.length = length;
        this.binary = binary;
    }

    @Override
    public String toString() {
        if (binary) {
            if (length <= 0) {
                return "BLOB";
            } else if (length <= 255) {
                return "BINARY(" + length + ")";
            } else if (length <= 65535) {
                return "VARBINARY(" + length + ")";
            } else {
                return "LONGBLOB";
            }
        } else {
            if (length <= 0) {
                return "TEXT";
            } else if (length <= 255) {
                return "CHAR(" + length + ")";
            } else if (length <= 65535) {
                return "VARCHAR(" + length + ")";
            } else {
                return "LONGTEXT";
            }
        }
    }

    @Override
    public String toSql() {
        return toString();
    }

    @Override
    public Object makeValue(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof String) {
            String str = (String) value;
            // Trim if length is specified
            if (length > 0 && str.length() > length) {
                return str.substring(0, length);
            }
            return str;
        }

        return value.toString();
    }

    @Override
    public Object getMinValue() {
        return "";
    }

    @Override
    public Object getMaxValue() {
        // Return a string of maximum length with highest characters
        if (length <= 0) {
            return new String(new char[1000]).replace('\0', '\uFFFF');
        } else {
            return new String(new char[length]).replace('\0', '\uFFFF');
        }
    }

    @Override
    public Object parseValue(String stringValue) {
        if (stringValue == null) {
            return null;
        }

        if (length > 0 && stringValue.length() > length) {
            return stringValue.substring(0, length);
        }

        return stringValue;
    }

    @Override
    public boolean isCompatible(ColType other) {
        if (this.equals(other)) {
            return true;
        }

        // All text types are compatible with each other
        return other instanceof Text;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Text text = (Text) obj;
        return length == text.length && binary == text.binary;
    }

    @Override
    public int hashCode() {
        return Objects.hash(length, binary);
    }

}