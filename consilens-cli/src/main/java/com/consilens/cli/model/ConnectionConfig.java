package com.consilens.cli.model;

import com.consilens.core.validation.ValidationException;
import com.consilens.core.validation.ValidationFramework;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Set;

/**
 * Source or target connection configuration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class ConnectionConfig {

    private static final String OPTIONS_KEY = "options";

    /** Connector types whose resources are addressed by path rather than JDBC connection. */
    private static final Set<String> CONNECTION_OPTIONAL_TYPES =
            Set.of("file", "iceberg");

    @JsonProperty("type")
    private String type;

    @JsonProperty("name")
    private String name;

    @JsonProperty("connection")
    private ConnectorConnectionProperties connection;

    @JsonProperty("resource")
    private ResourceConfig resource;

    @JsonProperty("readOptions")
    private Map<String, Object> readOptions;

    @JsonIgnore
    public Map<String, Object> toConnectionMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (connection != null) {
            result.putAll(connection.asMap());
        }
        return result;
    }

    @JsonIgnore
    public String getUrl() {
        return connection != null ? connection.getUrl() : null;
    }

    @JsonIgnore
    public String getUsername() {
        return connection != null ? connection.getUsername() : null;
    }

    @JsonIgnore
    public String getPassword() {
        return connection != null ? connection.getPassword() : null;
    }

    public void validate(String fieldName) throws ValidationException {
        ValidationFramework.forContext(fieldName)
                .notEmpty(type, fieldName + ".type")
                .required(resource, fieldName + ".resource", fieldName + ".resource 配置不能为空")
                .throwIfInvalid();

        resource.validate(fieldName + ".resource", type);

        if (isConnectionOptionalType()) {
            // 文件/Iceberg 数据源不需要 connection 块；位置在 resource.name/path 中
            return;
        }

        if (requiresJdbcValidation()) {
            ValidationFramework.forContext(fieldName)
                    .notEmpty(getUrl(), fieldName + ".connection.url")
                    .validJdbcUrl(getUrl(), fieldName + ".connection.url")
                    .notEmpty(getUsername(), fieldName + ".connection.username")
                    .throwIfInvalid();
            return;
        }

        if (toConnectionMap().isEmpty()) {
            throw ValidationException.simple("CONFIGURATION_VALIDATION", fieldName + ".connection 配置不能为空");
        }
    }

    private boolean requiresJdbcValidation() {
        String effectiveUrl = getUrl();
        return effectiveUrl != null && effectiveUrl.startsWith("jdbc:");
    }

    /**
     * Connectors that read from a location instead of a JDBC connection, so a
     * {@code connection} block is not required in the CLI configuration.
     */
    private boolean isConnectionOptionalType() {
        if (type == null) {
            return false;
        }
        String normalized = type.trim().toLowerCase(Locale.ROOT);
        return CONNECTION_OPTIONAL_TYPES.contains(normalized);
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public static class ConnectorConnectionProperties {

        @JsonProperty("url")
        @JsonAlias({"jdbcUrl"})
        private String url;

        @JsonProperty("username")
        private String username;

        @JsonProperty("password")
        private String password;

        @JsonIgnore
        @Builder.Default
        private Map<String, Object> properties = new LinkedHashMap<>();

        @JsonAnySetter
        public void addProperty(String key, Object value) {
            properties.put(key, value);
        }

        @JsonAnyGetter
        public Map<String, Object> getProperties() {
            return properties;
        }

        @JsonIgnore
        public Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>(properties);
            if (url != null && !url.trim().isEmpty()) {
                result.put("url", url);
            }
            if (username != null && !username.trim().isEmpty()) {
                result.put("username", username);
            }
            if (password != null && !password.trim().isEmpty()) {
                result.put("password", password);
            }
            return result;
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    public static class ResourceConfig {

        @JsonProperty("type")
        private String type;

        @JsonProperty("name")
        private String name;

        @JsonProperty("path")
        private String path;

        @JsonIgnore
        @Builder.Default
        private Map<String, Object> options = new LinkedHashMap<>();

        /**
         * Resource options are accepted both flat and nested under an {@code options:}
         * block. The nested form is used by the S3 (docs/13) and Iceberg (docs/15)
         * documentation; without flattening those keys were silently dropped, so
         * {@code snapshotId}/{@code s3Endpoint}/... fell back to defaults and the
         * comparison used the wrong snapshot or the AWS default credential chain.
         */
        @JsonAnySetter
        public void addOption(String key, Object value) {
            if (OPTIONS_KEY.equals(key) && value instanceof Map) {
                flattenOptions((Map<?, ?>) value);
                return;
            }
            options.put(key, value);
        }

        private void flattenOptions(Map<?, ?> nested) {
            for (Map.Entry<?, ?> entry : nested.entrySet()) {
                String nestedKey = String.valueOf(entry.getKey());
                Object nestedValue = entry.getValue();
                if (OPTIONS_KEY.equals(nestedKey) && nestedValue instanceof Map) {
                    flattenOptions((Map<?, ?>) nestedValue);
                    continue;
                }
                options.put(nestedKey, nestedValue);
            }
        }

        @JsonAnyGetter
        public Map<String, Object> getOptions() {
            return options;
        }

        public void validate(String fieldName) throws ValidationException {
            validate(fieldName, null);
        }

        /**
         * @param connectorType connector id of the owning {@code source}/{@code target}
         *                      block; a resource may use the same type (for example
         *                      {@code iceberg}) instead of table/sql/file.
         */
        public void validate(String fieldName, String connectorType) throws ValidationException {
            ValidationFramework.forContext(fieldName)
                    .notEmpty(type, fieldName + ".type")
                    .throwIfInvalid();

            String normalizedType = type.trim().toLowerCase(Locale.ROOT);
            switch (normalizedType) {
                case "table":
                    if (isBlank(name)) {
                        throw ValidationException.simple("CONFIGURATION_VALIDATION",
                                fieldName + " type=table 时必须配置 name");
                    }
                    if (!isBlank(path)) {
                        throw ValidationException.simple("CONFIGURATION_VALIDATION",
                                fieldName + " type=table 时不应配置 path");
                    }
                    break;
                case "sql":
                    if (isBlank(path)) {
                        throw ValidationException.simple("CONFIGURATION_VALIDATION",
                                fieldName + " type=sql 时必须配置 path");
                    }
                    validateTrustedSql(fieldName + ".path", path);
                    break;
                case "file":
                    if (isBlank(name) && isBlank(path)) {
                        throw ValidationException.simple("CONFIGURATION_VALIDATION",
                                fieldName + " type=file 时必须配置 name 或 path（文件路径）");
                    }
                    break;
                default:
                    if (connectorType != null
                            && normalizedType.equals(connectorType.trim().toLowerCase(Locale.ROOT))) {
                        if (isBlank(name) && isBlank(path)) {
                            throw ValidationException.simple("CONFIGURATION_VALIDATION",
                                    fieldName + " type=" + normalizedType
                                            + " 时必须配置 name 或 path（资源位置）");
                        }
                        break;
                    }
                    throw ValidationException.simple("CONFIGURATION_VALIDATION",
                            fieldName + ".type 仅支持 table、sql 或 file，"
                                    + "或与连接器类型同名（如 iceberg）；当前为 " + type);
            }
        }

        private void validateTrustedSql(String fieldName, String sql) {
            String value = sql.trim();
            if (!(value.regionMatches(true, 0, "select ", 0, 7)
                    || value.regionMatches(true, 0, "with ", 0, 5))) {
                throw ValidationException.simple("CONFIGURATION_VALIDATION",
                        fieldName + " 必须是 SELECT 或 WITH 开头的查询");
            }
            if (value.contains(";") || value.contains("--") || value.contains("/*") || value.contains("*/")) {
                throw ValidationException.simple("CONFIGURATION_VALIDATION",
                        fieldName + " 包含不允许的 SQL 片段");
            }
        }

        private boolean isBlank(String value) {
            return value == null || value.trim().isEmpty();
        }
    }
}
