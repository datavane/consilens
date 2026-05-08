package com.consilens.ai.config;

import com.consilens.ai.config.model.AIConfigDraft;
import com.consilens.ai.config.model.AIConfigIssue;
import com.consilens.ai.config.model.DatasetDraft;
import com.consilens.ai.config.model.MappingDraft;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates the minimal structured draft accepted from AI or CLI hints.
 */
public class AIConfigDraftValidator {

    public List<AIConfigIssue> validate(AIConfigDraft draft) {
        List<AIConfigIssue> issues = new ArrayList<>();
        if (draft == null) {
            issues.add(error("", "AI_CONFIG_DRAFT_MISSING", "AI config draft is required"));
            return issues;
        }
        validateDataset("source", draft.getSource(), issues);
        validateDataset("target", draft.getTarget(), issues);
        validateMapping(draft.getMapping(), issues);
        return issues;
    }

    public boolean hasErrors(List<AIConfigIssue> issues) {
        return issues != null && issues.stream()
                .anyMatch(issue -> issue.getSeverity() == AIConfigIssue.Severity.ERROR);
    }

    private void validateDataset(String side, DatasetDraft dataset, List<AIConfigIssue> issues) {
        if (dataset == null) {
            issues.add(error(side, "AI_CONFIG_" + side.toUpperCase() + "_MISSING", side + " dataset is required"));
            return;
        }
        require(dataset.getType(), side + ".type", "AI_CONFIG_DATASET_TYPE_MISSING",
                side + " dataset type is required", issues);
        require(dataset.getJdbcUrl(), side + ".jdbcUrl", "AI_CONFIG_JDBC_URL_MISSING",
                side + " JDBC URL is required", issues);
        if (!blank(dataset.getJdbcUrl()) && !dataset.getJdbcUrl().startsWith("jdbc:")) {
            issues.add(error(side + ".jdbcUrl", "AI_CONFIG_JDBC_URL_INVALID",
                    side + " JDBC URL must start with jdbc:"));
        }
        String resourceType = blank(dataset.getResourceType()) ? "table" : dataset.getResourceType().trim();
        if (!"table".equalsIgnoreCase(resourceType) && !"sql".equalsIgnoreCase(resourceType)) {
            issues.add(error(side + ".resourceType", "AI_CONFIG_UNSUPPORTED_RESOURCE_TYPE",
                    side + " resourceType must be table or sql"));
        }
        if ("sql".equalsIgnoreCase(resourceType)) {
            require(dataset.getQuery(), side + ".query", "AI_CONFIG_QUERY_MISSING",
                    side + " query is required when resourceType=sql", issues);
            validateSql(side + ".query", dataset.getQuery(), issues);
        } else {
            require(dataset.getResourceName(), side + ".resourceName", "AI_CONFIG_RESOURCE_NAME_MISSING",
                    side + " table name is required", issues);
        }
        require(dataset.getUsernameEnv(), side + ".usernameEnv", "AI_CONFIG_USERNAME_ENV_MISSING",
                side + " username env variable name is required", issues);
        require(dataset.getPasswordEnv(), side + ".passwordEnv", "AI_CONFIG_PASSWORD_ENV_MISSING",
                side + " password env variable name is required", issues);
    }

    private void validateMapping(MappingDraft mapping, List<AIConfigIssue> issues) {
        if (mapping == null) {
            issues.add(error("mapping", "AI_CONFIG_MAPPING_MISSING", "mapping is required"));
            return;
        }
        if (mapping.getSourceKeys() == null || mapping.getSourceKeys().isEmpty()
                || mapping.getTargetKeys() == null || mapping.getTargetKeys().isEmpty()) {
            issues.add(error("mapping.keys", "AI_CONFIG_KEY_MISSING",
                    "sourceKeys and targetKeys are required"));
            return;
        }
        if (mapping.getSourceKeys().size() != mapping.getTargetKeys().size()) {
            issues.add(error("mapping.keys", "AI_CONFIG_KEY_MAPPING_INVALID",
                    "sourceKeys and targetKeys must have the same size"));
        }
        if (mapping.getSourceFields() != null && !mapping.getSourceFields().isEmpty()
                && (mapping.getTargetFields() == null
                || mapping.getSourceFields().size() != mapping.getTargetFields().size())) {
            issues.add(error("mapping.fields", "AI_CONFIG_FIELD_MAPPING_INVALID",
                    "sourceFields and targetFields must have the same size"));
        }
    }

    private void validateSql(String path, String sql, List<AIConfigIssue> issues) {
        if (blank(sql)) {
            return;
        }
        String value = sql.trim();
        if (!(value.regionMatches(true, 0, "select ", 0, 7)
                || value.regionMatches(true, 0, "with ", 0, 5))) {
            issues.add(error(path, "AI_CONFIG_QUERY_INVALID", "query must start with SELECT or WITH"));
        }
        if (value.contains(";") || value.contains("--") || value.contains("/*") || value.contains("*/")) {
            issues.add(error(path, "AI_CONFIG_QUERY_UNSAFE", "query contains disallowed SQL fragments"));
        }
    }

    private void require(String value, String path, String code, String message, List<AIConfigIssue> issues) {
        if (blank(value)) {
            issues.add(error(path, code, message));
        }
    }

    private boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private AIConfigIssue error(String path, String code, String message) {
        return AIConfigIssue.builder()
                .severity(AIConfigIssue.Severity.ERROR)
                .path(path)
                .code(code)
                .message(message)
                .build();
    }
}
