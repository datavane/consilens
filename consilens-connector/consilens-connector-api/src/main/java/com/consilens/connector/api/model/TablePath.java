package com.consilens.connector.api.model;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

import java.util.*;

/**
 * Unified table path model representing a table location in the database.
 */
@Getter
@ToString
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class TablePath {

    private final List<String> pathComponents;
    private final String delimiter;

    /**
     * Create table path with components
     */
    public static TablePath of(String... components) {
        if (components == null || components.length == 0) {
            throw new IllegalArgumentException("Table path components cannot be null or empty");
        }

        // Validate components
        for (String component : components) {
            if (component == null || component.trim().isEmpty()) {
                throw new IllegalArgumentException("Table path component cannot be null or empty");
            }
        }

        return new TablePath(List.of(components), ".");
    }

    /**
     * Create table path from list of components
     */
    public static TablePath of(List<String> components) {
        if (components == null || components.isEmpty()) {
            throw new IllegalArgumentException("Table path components cannot be null or empty");
        }

        // Validate components
        for (String component : components) {
            if (component == null || component.trim().isEmpty()) {
                throw new IllegalArgumentException("Table path component cannot be null or empty");
            }
        }

        return new TablePath(List.copyOf(components), ".");
    }

    /**
     * Create table path from dot-separated string
     */
    public static TablePath fromString(String pathString) {
        if (pathString == null || pathString.trim().isEmpty()) {
            throw new IllegalArgumentException("Path string cannot be null or empty");
        }

        String[] components = pathString.split("\\.");
        return TablePath.of(components);
    }

    /**
     * Get the table name (last component of the path).
     */
    public String getTableName() {
        return pathComponents.get(pathComponents.size() - 1);
    }

    /**
     * Get all components as a list.
     */
    public List<String> getComponents() {
        return new ArrayList<>(pathComponents);
    }

    /**
     * Check if the path is empty.
     */
    public boolean isEmpty() {
        return pathComponents.isEmpty();
    }

    /**
     * Get the schema name (second to last component, if available).
     */
    public Optional<String> getSchema() {
        if (pathComponents.size() >= 2) {
            return Optional.of(pathComponents.get(pathComponents.size() - 2));
        }
        return Optional.empty();
    }

    /**
     * Get the catalog/database name (first component, if available).
     */
    public Optional<String> getCatalog() {
        if (pathComponents.size() >= 3) {
            return Optional.of(pathComponents.get(0));
        }
        return Optional.empty();
    }

    /**
     * Get all path components.
     */
    public List<String> getPathComponents() {
        return Collections.unmodifiableList(pathComponents);
    }

    /**
     * Get the full path as a string with delimiter.
     */
    public String getFullPath() {
        StringJoiner joiner = new StringJoiner(delimiter);
        pathComponents.forEach(joiner::add);
        return joiner.toString();
    }

    /**
     * Get the number of components in the path.
     */
    public int getComponentCount() {
        return pathComponents.size();
    }

    /**
     * Check if this is a qualified path (has schema).
     */
    public boolean isQualified() {
        return getSchema().isPresent();
    }

    /**
     * Check if this is a fully qualified path (has catalog and schema).
     */
    public boolean isFullyQualified() {
        return getCatalog().isPresent() && getSchema().isPresent();
    }

    /**
     * Create a new path with a different table name.
     */
    public TablePath withTableName(String newTableName) {
        if (newTableName == null || newTableName.trim().isEmpty()) {
            throw new IllegalArgumentException("Table name cannot be null or empty");
        }

        List<String> newComponents = new ArrayList<>(pathComponents);
        newComponents.set(newComponents.size() - 1, newTableName);
        return new TablePath(newComponents, delimiter);
    }

    /**
     * Create a new path with a different schema.
     */
    public TablePath withSchema(String newSchemaName) {
        if (newSchemaName == null || newSchemaName.trim().isEmpty()) {
            throw new IllegalArgumentException("Schema name cannot be null or empty");
        }

        if (pathComponents.size() < 2) {
            // Add schema to path
            List<String> newComponents = new ArrayList<>(pathComponents);
            newComponents.add(0, newSchemaName);
            return new TablePath(newComponents, delimiter);
        } else {
            // Replace existing schema
            List<String> newComponents = new ArrayList<>(pathComponents);
            newComponents.set(newComponents.size() - 2, newSchemaName);
            return new TablePath(newComponents, delimiter);
        }
    }

    /**
     * Create a new path with additional prefix component (e.g., catalog).
     */
    public TablePath withPrefix(String prefixComponent) {
        if (prefixComponent == null || prefixComponent.trim().isEmpty()) {
            throw new IllegalArgumentException("Prefix component cannot be null or empty");
        }

        List<String> newComponents = new ArrayList<>();
        newComponents.add(prefixComponent);
        newComponents.addAll(pathComponents);
        return new TablePath(newComponents, delimiter);
    }

    /**
     * Get quoted version of the path for SQL queries.
     */
    public String getQuotedPath(String quoteChar) {
        StringJoiner joiner = new StringJoiner(quoteChar + "." + quoteChar);
        pathComponents.forEach(component -> joiner.add(quoteChar + component + quoteChar));
        return joiner.toString();
    }

    /**
     * Create a parent path (remove the last component).
     */
    public Optional<TablePath> getParent() {
        if (pathComponents.size() <= 1) {
            return Optional.empty();
        }

        List<String> parentComponents = pathComponents.subList(0, pathComponents.size() - 1);
        return Optional.of(new TablePath(parentComponents, delimiter));
    }

    /**
     * Check if this path starts with the given prefix.
     */
    public boolean startsWith(TablePath prefix) {
        if (prefix.getComponentCount() > this.getComponentCount()) {
            return false;
        }

        for (int i = 0; i < prefix.getComponentCount(); i++) {
            if (!this.pathComponents.get(i).equals(prefix.pathComponents.get(i))) {
                return false;
            }
        }

        return true;
    }

    /**
     * Get relative path from the given parent path.
     */
    public Optional<TablePath> getRelativePath(TablePath parent) {
        if (!this.startsWith(parent)) {
            return Optional.empty();
        }

        if (parent.getComponentCount() >= this.getComponentCount()) {
            return Optional.empty();
        }

        List<String> relativeComponents = this.pathComponents.subList(
                parent.getComponentCount(),
                this.pathComponents.size()
        );

        return Optional.of(new TablePath(relativeComponents, delimiter));
    }

    /**
     * Create a deep copy of this table path.
     */
    public TablePath copy() {
        return new TablePath(new ArrayList<>(pathComponents), delimiter);
    }

    @Override
    public int hashCode() {
        return pathComponents.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        TablePath that = (TablePath) obj;
        return pathComponents.equals(that.pathComponents);
    }
}