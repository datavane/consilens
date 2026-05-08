package com.consilens.ai.config;

import com.consilens.ai.config.model.AIConfigDraft;
import com.consilens.ai.config.model.AIConfigIssue;
import com.consilens.ai.config.model.DatasetDraft;
import com.consilens.ai.config.model.MappingDraft;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AIConfigDraftValidatorTest {

    private final AIConfigDraftValidator validator = new AIConfigDraftValidator();

    @Test
    void shouldAcceptMinimalValidDraft() {
        List<AIConfigIssue> issues = validator.validate(validDraft());

        assertFalse(validator.hasErrors(issues));
    }

    @Test
    void shouldRejectDraftWithoutKeys() {
        AIConfigDraft draft = validDraft();
        draft.setMapping(MappingDraft.builder().build());

        List<AIConfigIssue> issues = validator.validate(draft);

        assertTrue(validator.hasErrors(issues));
        assertTrue(issues.stream().anyMatch(issue -> "AI_CONFIG_KEY_MISSING".equals(issue.getCode())));
    }

    @Test
    void shouldRejectUnsafeSqlResource() {
        AIConfigDraft draft = validDraft();
        draft.getSource().setResourceType("sql");
        draft.getSource().setResourceName(null);
        draft.getSource().setQuery("select * from users; drop table users");

        List<AIConfigIssue> issues = validator.validate(draft);

        assertTrue(validator.hasErrors(issues));
        assertTrue(issues.stream().anyMatch(issue -> "AI_CONFIG_QUERY_UNSAFE".equals(issue.getCode())));
    }

    private AIConfigDraft validDraft() {
        return AIConfigDraft.builder()
                .source(dataset("mysql", "jdbc:mysql://localhost:3306/source", "users"))
                .target(dataset("postgresql", "jdbc:postgresql://localhost:5432/target", "users"))
                .mapping(MappingDraft.builder()
                        .sourceKeys(List.of("id"))
                        .targetKeys(List.of("id"))
                        .build())
                .build();
    }

    private DatasetDraft dataset(String type, String url, String table) {
        return DatasetDraft.builder()
                .type(type)
                .jdbcUrl(url)
                .usernameEnv("DB_USER")
                .passwordEnv("DB_PASSWORD")
                .resourceType("table")
                .resourceName(table)
                .build();
    }
}
