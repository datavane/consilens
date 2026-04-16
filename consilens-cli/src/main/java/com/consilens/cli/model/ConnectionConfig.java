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

/**
 * Source or target connection configuration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class ConnectionConfig {

    @JsonProperty("type")
    private String type;

    @JsonProperty("name")
    private String name;

    @JsonProperty("url")
    private String url;

    @JsonProperty("username")
    private String username;

    @JsonProperty("password")
    private String password;

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
        putIfPresent(result, "url", url);
        putIfPresent(result, "username", username);
        putIfPresent(result, "password", password);
        return result;
    }

    public String getUrl() {
        if (url != null && !url.trim().isEmpty()) {
            return url;
        }
        return connection != null ? connection.getUrl() : null;
    }

    public String getUsername() {
        if (username != null && !username.trim().isEmpty()) {
            return username;
        }
        return connection != null ? connection.getUsername() : null;
    }

    public String getPassword() {
        if (password != null && !password.trim().isEmpty()) {
            return password;
        }
        return connection != null ? connection.getPassword() : null;
    }

    public void validate(String fieldName) throws ValidationException {
        ValidationFramework.forContext(fieldName)
                .notEmpty(type, fieldName + ".type")
                .throwIfInvalid();

        if (requiresJdbcValidation()) {
            ValidationFramework.forContext(fieldName)
                    .notEmpty(getUrl(), fieldName + ".url")
                    .validJdbcUrl(getUrl(), fieldName + ".url")
                    .notEmpty(getUsername(), fieldName + ".username")
                    .throwIfInvalid();
            return;
        }

        if (toConnectionMap().isEmpty()) {
            throw ValidationException.simple("CONFIGURATION_VALIDATION", fieldName + ".connection 配置不能为空");
        }
    }

    private boolean requiresJdbcValidation() {
        String effectiveUrl = getUrl();
        if (effectiveUrl != null && effectiveUrl.startsWith("jdbc:")) {
            return true;
        }
        if (type == null) {
            return false;
        }
        switch (type.trim().toLowerCase(Locale.ROOT)) {
            case "mysql":
            case "postgresql":
            case "oracle":
            case "sqlserver":
            case "presto":
            case "trino":
            case "doris":
            case "starrocks":
            case "clickhouse":
            case "tidb":
                return true;
            default:
                return false;
        }
    }

    private void putIfPresent(Map<String, Object> result, String key, String value) {
        if (value != null && !value.trim().isEmpty()) {
            result.put(key, value);
        }
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

        @JsonAnySetter
        public void addOption(String key, Object value) {
            options.put(key, value);
        }

        @JsonAnyGetter
        public Map<String, Object> getOptions() {
            return options;
        }
    }
}
