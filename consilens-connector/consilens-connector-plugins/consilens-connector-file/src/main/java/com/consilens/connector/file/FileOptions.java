package com.consilens.connector.file;

import com.consilens.connector.api.ConnectorException;
import com.consilens.connector.api.config.ReadOptions;
import com.consilens.connector.api.model.DataType;
import com.consilens.connector.api.model.ResourceLocator;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parsed options for the file connector. Values may come from
 * {@code resource.options} or {@code readOptions.options}; resource options
 * take precedence.
 *
 * <p>Supported keys: format, delimiter, hasHeader, charset, nullValue,
 * skipRows, columns, types.
 */
public final class FileOptions {

    public static final String FORMAT = "format";
    public static final String DELIMITER = "delimiter";
    public static final String HAS_HEADER = "hasHeader";
    public static final String CHARSET = "charset";
    public static final String NULL_VALUE = "nullValue";
    public static final String SKIP_ROWS = "skipRows";
    public static final String COLUMNS = "columns";
    public static final String TYPES = "types";

    private final FileFormat format;
    private final boolean explicitFormat;
    private final char delimiter;
    private final boolean hasHeader;
    private final Charset charset;
    private final String nullValue;
    private final int skipRows;
    private final List<String> explicitColumns;
    private final Map<String, DataType> explicitTypes;

    private FileOptions(FileFormat format, boolean explicitFormat, char delimiter,
                        boolean hasHeader, Charset charset, String nullValue, int skipRows,
                        List<String> explicitColumns, Map<String, DataType> explicitTypes) {
        this.format = format;
        this.explicitFormat = explicitFormat;
        this.delimiter = delimiter;
        this.hasHeader = hasHeader;
        this.charset = charset;
        this.nullValue = nullValue;
        this.skipRows = skipRows;
        this.explicitColumns = explicitColumns;
        this.explicitTypes = explicitTypes;
    }

    public static FileOptions from(ResourceLocator resource, ReadOptions readOptions) {
        String location = resource == null ? null
                : resource.getPath() != null && !resource.getPath().isBlank()
                ? resource.getPath() : resource.getName();
        return from(resource, readOptions, location);
    }

    /**
     * @param fallbackLocation used to infer the format when the resource location
     *                         is a directory or glob (for example the first
     *                         resolved file)
     */
    public static FileOptions from(ResourceLocator resource, ReadOptions readOptions,
                                   String fallbackLocation) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (readOptions != null && readOptions.getOptions() != null) {
            merged.putAll(readOptions.getOptions());
        }
        if (resource != null && resource.getOptions() != null) {
            merged.putAll(resource.getOptions());
        }
        String configuredFormat = string(merged.get(FORMAT));
        boolean explicitFormat = configuredFormat != null && !configuredFormat.isBlank();
        FileFormat format = FileFormat.from(configuredFormat, fallbackLocation);
        char delimiter = delimiter(string(merged.get(DELIMITER)));
        boolean hasHeader = bool(merged.get(HAS_HEADER), true);
        Charset charset = charset(string(merged.get(CHARSET)));
        String nullValue = string(merged.get(NULL_VALUE));
        int skipRows = integer(merged.get(SKIP_ROWS), 0);
        List<String> columns = list(merged.get(COLUMNS));
        validateColumns(columns);
        Map<String, DataType> types = types(merged.get(TYPES));
        return new FileOptions(format, explicitFormat, delimiter, hasHeader, charset, nullValue,
                Math.max(0, skipRows), columns, types);
    }

    private static char delimiter(String value) {
        if (value == null || value.isEmpty()) {
            return ',';
        }
        if ("\\t".equals(value) || "tab".equalsIgnoreCase(value)) {
            return '\t';
        }
        return value.charAt(0);
    }

    private static Charset charset(String value) {
        if (value == null || value.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(value.trim());
        } catch (Exception exception) {
            throw new ConnectorException("Unsupported file charset: " + value);
        }
    }

    private static void validateColumns(List<String> columns) {
        Set<String> seen = new LinkedHashSet<>();
        for (String column : columns) {
            if (!seen.add(column.toLowerCase(Locale.ROOT))) {
                throw new ConnectorException("Duplicate column in resource.columns: " + column);
            }
        }
    }

    private static List<String> list(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List) {
            List<String> result = new ArrayList<>();
            for (Object item : (List<?>) value) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    result.add(String.valueOf(item).trim());
                }
            }
            return result;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : text.split(",")) {
            if (!part.isBlank()) {
                result.add(part.trim());
            }
        }
        return result;
    }

    private static Map<String, DataType> types(Object value) {
        List<String> entries = list(value);
        Map<String, DataType> result = new LinkedHashMap<>();
        for (String entry : entries) {
            int colon = entry.indexOf(':');
            if (colon <= 0 || colon == entry.length() - 1) {
                throw new ConnectorException("Invalid resource.types entry '" + entry
                        + "' (expected column:TYPE)");
            }
            String column = entry.substring(0, colon).trim();
            String type = entry.substring(colon + 1).trim().toUpperCase(Locale.ROOT);
            try {
                result.put(column, DataType.valueOf(type));
            } catch (IllegalArgumentException exception) {
                throw new ConnectorException("Unsupported type '" + type
                        + "' in resource.types entry '" + entry + "'");
            }
        }
        return result;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean bool(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(String.valueOf(value).trim());
    }

    private static int integer(Object value, int defaultValue) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    public FileFormat format() {
        return format;
    }

    /**
     * Whether {@code format} was configured explicitly instead of inferred from
     * the first resolved file.
     */
    public boolean explicitFormat() {
        return explicitFormat;
    }

    /**
     * Copy with a format detected from the complete file list.
     */
    public FileOptions withFormat(FileFormat detected) {
        return new FileOptions(detected, true, delimiter, hasHeader, charset, nullValue,
                skipRows, explicitColumns, explicitTypes);
    }

    public char delimiter() {
        return delimiter;
    }

    public boolean hasHeader() {
        return hasHeader;
    }

    public Charset charset() {
        return charset;
    }

    public String nullValue() {
        return nullValue;
    }

    public int skipRows() {
        return skipRows;
    }

    public List<String> explicitColumns() {
        return explicitColumns;
    }

    public Map<String, DataType> explicitTypes() {
        return explicitTypes;
    }
}
