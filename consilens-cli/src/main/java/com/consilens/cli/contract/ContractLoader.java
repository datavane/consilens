package com.consilens.cli.contract;

import com.consilens.cli.config.CliObjectMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Loads a minimal schema view from ODCS v3 or dbt {@code schema.yml}.
 *
 * <p>ODCS: {@code schema} or {@code dataset} as an object or array;
 * {@code properties} or {@code fields} as an array or map.
 *
 * <p>dbt: {@code models[]} and {@code sources[].tables[]}; column type comes
 * from {@code data_type}, constraints from {@code constraints}, {@code tests}
 * and {@code meta}. When a file contains multiple models, pass a model name.
 */
public final class ContractLoader {

    private static final ObjectMapper YAML = CliObjectMappers.yaml();

    private ContractLoader() {
    }

    public static DataContract load(Path file) {
        return load(file, null);
    }

    public static DataContract load(Path file, String modelName) {
        JsonNode root;
        try {
            root = YAML.readTree(Files.newInputStream(file));
        } catch (IOException exception) {
            throw new IllegalArgumentException("Failed to read contract " + file + ": "
                    + exception.getMessage(), exception);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Contract must be a YAML/JSON object: " + file);
        }
        if (root.has("models") || root.has("sources")) {
            return loadDbt(root, file, modelName);
        }
        return loadOdcs(root, file);
    }

    private static DataContract loadOdcs(JsonNode root, Path file) {
        List<ContractColumn> columns = new ArrayList<>();
        Set<String> primaryKeys = new LinkedHashSet<>();
        JsonNode schema = first(root, "schema", "dataset");
        if (schema != null) {
            if (schema.isArray()) {
                for (JsonNode item : schema) {
                    collectOdcs(item, columns, primaryKeys);
                }
            } else if (schema.isObject()) {
                collectOdcs(schema, columns, primaryKeys);
            }
        }
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("Contract has no schema properties: " + file);
        }
        if (!primaryKeys.isEmpty()) {
            for (ContractColumn column : columns) {
                if (primaryKeys.stream().anyMatch(key -> key.equalsIgnoreCase(column.getName()))) {
                    column.setPrimaryKey(Boolean.TRUE);
                }
            }
        }
        return DataContract.builder()
                .id(text(root, "id"))
                .name(text(root, "name"))
                .version(text(root, "version"))
                .columns(columns)
                .build();
    }

    private static void collectOdcs(JsonNode schema, List<ContractColumn> columns,
                                    Set<String> primaryKeys) {
        JsonNode keys = schema.get("primaryKey");
        if (keys != null) {
            if (keys.isArray()) {
                for (JsonNode key : keys) {
                    if (key.isTextual()) {
                        primaryKeys.add(key.asText());
                    }
                }
            } else if (keys.isTextual()) {
                primaryKeys.add(keys.asText());
            }
        }
        JsonNode properties = first(schema, "properties", "fields");
        if (properties == null) {
            return;
        }
        if (properties.isArray()) {
            for (JsonNode property : properties) {
                addOdcsColumn(property, null, columns);
            }
        } else if (properties.isObject()) {
            properties.fields().forEachRemaining(entry ->
                    addOdcsColumn(entry.getValue(), entry.getKey(), columns));
        }
    }

    private static void addOdcsColumn(JsonNode property, String fallbackName,
                                      List<ContractColumn> columns) {
        if (property == null || !property.isObject()) {
            return;
        }
        String name = name(property, fallbackName);
        if (name == null) {
            return;
        }
        Boolean required = bool(property, "required");
        Boolean nullable = bool(property, "nullable");
        if (required == null && nullable != null) {
            required = !nullable;
        }
        String keyType = text(property, "keyType");
        boolean primaryKey = Boolean.TRUE.equals(bool(property, "primaryKey"))
                || "primary".equalsIgnoreCase(keyType)
                || "primary_key".equalsIgnoreCase(keyType);
        columns.add(ContractColumn.builder()
                .name(name)
                .logicalType(text(property, "logicalType"))
                .physicalType(text(property, "physicalType"))
                .required(required)
                .unique(bool(property, "unique"))
                .primaryKey(primaryKey)
                .build());
    }

    private static DataContract loadDbt(JsonNode root, Path file, String modelName) {
        List<JsonNode> candidates = dbtModels(root);
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("dbt schema has no models or source tables: " + file);
        }
        JsonNode model = selectDbtModel(candidates, modelName, file);
        List<ContractColumn> columns = new ArrayList<>();
        JsonNode columnNodes = first(model, "columns");
        if (columnNodes != null) {
            if (columnNodes.isArray()) {
                for (JsonNode column : columnNodes) {
                    addDbtColumn(column, null, columns);
                }
            } else if (columnNodes.isObject()) {
                columnNodes.fields().forEachRemaining(entry ->
                        addDbtColumn(entry.getValue(), entry.getKey(), columns));
            }
        }
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("dbt model '" + name(model, null)
                    + "' has no columns: " + file);
        }
        return DataContract.builder()
                .id(text(model, "unique_id") != null ? text(model, "unique_id") : name(model, null))
                .name(name(model, null))
                .version(text(root, "version"))
                .columns(columns)
                .build();
    }

    private static List<JsonNode> dbtModels(JsonNode root) {
        List<JsonNode> result = new ArrayList<>();
        JsonNode models = root.get("models");
        if (models != null && models.isArray()) {
            for (JsonNode model : models) {
                if (model.isObject()) {
                    result.add(model);
                }
            }
        }
        JsonNode sources = root.get("sources");
        if (sources != null && sources.isArray()) {
            for (JsonNode source : sources) {
                JsonNode tables = source.get("tables");
                if (tables != null && tables.isArray()) {
                    for (JsonNode table : tables) {
                        if (table.isObject()) {
                            result.add(table);
                        }
                    }
                }
            }
        }
        return result;
    }

    private static JsonNode selectDbtModel(List<JsonNode> candidates, String modelName, Path file) {
        if (modelName != null && !modelName.isBlank()) {
            for (JsonNode candidate : candidates) {
                if (modelName.equals(name(candidate, null))) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("dbt model '" + modelName + "' not found in " + file
                    + " (available: " + availableNames(candidates) + ")");
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        throw new IllegalArgumentException("dbt schema contains multiple models; pass a model name"
                + " (available: " + availableNames(candidates) + ")");
    }

    private static String availableNames(List<JsonNode> candidates) {
        return candidates.stream()
                .map(model -> name(model, null))
                .filter(name -> name != null)
                .collect(Collectors.joining(", "));
    }

    private static void addDbtColumn(JsonNode column, String fallbackName,
                                     List<ContractColumn> columns) {
        if (column == null || !column.isObject()) {
            return;
        }
        String name = name(column, fallbackName);
        if (name == null) {
            return;
        }
        Boolean required = null;
        Boolean unique = null;
        Boolean primaryKey = null;

        JsonNode constraints = column.get("constraints");
        if (constraints != null && constraints.isArray()) {
            for (JsonNode constraint : constraints) {
                String type = text(constraint, "type");
                required = merge(required, "not_null".equalsIgnoreCase(type));
                unique = merge(unique, "unique".equalsIgnoreCase(type));
                primaryKey = merge(primaryKey, "primary_key".equalsIgnoreCase(type));
            }
        }
        JsonNode tests = column.get("tests");
        if (tests != null && tests.isArray()) {
            for (JsonNode test : tests) {
                if (test.isTextual()) {
                    required = merge(required, "not_null".equalsIgnoreCase(test.asText()));
                    unique = merge(unique, "unique".equalsIgnoreCase(test.asText()));
                    primaryKey = merge(primaryKey, "primary_key".equalsIgnoreCase(test.asText()));
                } else if (test.isObject()) {
                    java.util.Iterator<String> names = test.fieldNames();
                    while (names.hasNext()) {
                        String testName = names.next();
                        required = merge(required, "not_null".equalsIgnoreCase(testName));
                        unique = merge(unique, "unique".equalsIgnoreCase(testName));
                        primaryKey = merge(primaryKey, "primary_key".equalsIgnoreCase(testName));
                    }
                }
            }
        }
        JsonNode meta = column.get("meta");
        if (meta != null && meta.isObject()) {
            required = merge(required, Boolean.TRUE.equals(bool(meta, "required")));
            unique = merge(unique, Boolean.TRUE.equals(bool(meta, "unique")));
            primaryKey = merge(primaryKey, Boolean.TRUE.equals(bool(meta, "primary_key")));
        }
        Boolean explicitRequired = bool(column, "required");
        if (explicitRequired != null) {
            required = explicitRequired;
        }
        columns.add(ContractColumn.builder()
                .name(name)
                .logicalType(text(column, "logicalType"))
                .physicalType(firstText(column, "data_type", "dataType", "physicalType"))
                .required(required)
                .unique(unique)
                .primaryKey(primaryKey)
                .build());
    }

    private static Boolean merge(Boolean current, boolean value) {
        if (value) {
            return Boolean.TRUE;
        }
        return current;
    }

    private static String name(JsonNode node, String fallback) {
        String name = text(node, "name");
        if (name == null || name.isBlank()) {
            name = fallback;
        }
        return name == null || name.isBlank() ? null : name;
    }

    private static JsonNode first(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private static String firstText(JsonNode node, String... names) {
        for (String name : names) {
            String value = text(node, name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Boolean bool(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        String text = value.asText().trim().toLowerCase(Locale.ROOT);
        if ("true".equals(text) || "yes".equals(text) || "1".equals(text)) {
            return Boolean.TRUE;
        }
        if ("false".equals(text) || "no".equals(text) || "0".equals(text)) {
            return Boolean.FALSE;
        }
        return null;
    }
}
