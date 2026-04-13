package com.consilens.cli.model;

import com.consilens.connector.api.enums.DatabaseType;
import com.consilens.core.validation.ValidationException;
import com.consilens.core.validation.ValidationFramework;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Source or target connection configuration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
public class ConnectionConfig {

    private static final String SQLITE_JDBC_PREFIX = "jdbc:sqlite:";

    @JsonProperty("type")
    private String type;

    @JsonProperty("url")
    private String url;

    @JsonProperty("username")
    private String username;

    @JsonProperty("password")
    private String password;

    public void validate(String fieldName) throws ValidationException {
        ValidationFramework.forContext(fieldName)
                .notEmpty(url, fieldName + ".url")
                .validJdbcUrl(url, fieldName + ".url")
                .throwIfInvalid();

        DatabaseType explicitType = resolveExplicitType(fieldName);
        boolean sqliteJdbcUrl = isSqliteJdbcUrl(url);
        DatabaseType detectedType = sqliteJdbcUrl ? DatabaseType.SQLITE : DatabaseType.fromJdbcUrl(url);

        if (explicitType == DatabaseType.SQLITE) {
            if (!sqliteJdbcUrl) {
                throw ValidationException.simple("CONFIGURATION_VALIDATION",
                        fieldName + ".type requires " + fieldName + ".url to start with " + SQLITE_JDBC_PREFIX);
            }
            validateSqliteUrl(fieldName);
            return;
        }

        if (explicitType != DatabaseType.UNKNOWN) {
            if (sqliteJdbcUrl) {
                throw ValidationException.simple("CONFIGURATION_VALIDATION",
                        fieldName + ".type does not match " + fieldName + ".url: SQLite JDBC URLs require type sqlite or omitted type");
            }
            if (detectedType != DatabaseType.UNKNOWN && explicitType != detectedType) {
                throw ValidationException.simple("CONFIGURATION_VALIDATION",
                        fieldName + ".type does not match " + fieldName + ".url");
            }
        }

        if (sqliteJdbcUrl) {
            validateSqliteUrl(fieldName);
            return;
        }

        ValidationFramework.forContext(fieldName)
                .notEmpty(username, fieldName + ".username")
                .throwIfInvalid();
    }

    private DatabaseType resolveExplicitType(String fieldName) {
        if (!hasText(type)) {
            return DatabaseType.UNKNOWN;
        }

        DatabaseType explicitType = DatabaseType.fromString(type);
        if (explicitType == DatabaseType.UNKNOWN) {
            throw ValidationException.simple("CONFIGURATION_VALIDATION",
                    fieldName + ".type is not a supported database type: " + type);
        }
        return explicitType;
    }

    private void validateSqliteUrl(String fieldName) {
        String sqliteDataSource = url.substring(SQLITE_JDBC_PREFIX.length());
        if (!hasText(sqliteDataSource)) {
            throw ValidationException.simple("CONFIGURATION_VALIDATION",
                    fieldName + ".url must include a SQLite data source path or :memory:");
        }
    }

    private boolean isSqliteJdbcUrl(String jdbcUrl) {
        return jdbcUrl != null
                && jdbcUrl.regionMatches(true, 0, SQLITE_JDBC_PREFIX, 0, SQLITE_JDBC_PREFIX.length());
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
