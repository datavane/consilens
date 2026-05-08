package com.consilens.cli.ai;

import com.consilens.ai.config.model.AIConfigDraft;
import com.consilens.ai.config.model.DatasetDraft;
import com.consilens.ai.config.model.MappingDraft;
import com.consilens.ai.config.model.StrategyDraft;
import com.consilens.cli.model.CliConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AIConfigCompilerTest {

    private final AIConfigCompiler compiler = new AIConfigCompiler();

    @Test
    void shouldCompileDraftToCanonicalCliConfiguration() {
        CliConfiguration config = compiler.compile(draft());

        assertDoesNotThrow(config::validate);
        assertEquals("mysql", config.getSource().getType());
        assertEquals("users", config.getSource().getResource().getName());
        assertEquals("${env.SOURCE_USER}", config.getSource().getUsername());
        assertEquals("xor", config.getStrategy().getAlgorithm());
    }

    @Test
    void shouldSerializeCanonicalYamlShape() {
        String yaml = compiler.toYaml(compiler.compile(draft()));

        assertTrue(yaml.contains("source:"));
        assertTrue(yaml.contains("connection:"));
        assertTrue(yaml.contains("resource:"));
        assertTrue(yaml.contains("comparison:"));
        assertTrue(yaml.contains("strategy:"));
        assertTrue(yaml.contains("result:"));
    }

    private AIConfigDraft draft() {
        return AIConfigDraft.builder()
                .source(dataset("mysql", "source-mysql", "jdbc:mysql://localhost:3306/source",
                        "SOURCE_USER", "SOURCE_PASSWORD"))
                .target(dataset("postgresql", "target-postgresql", "jdbc:postgresql://localhost:5432/target",
                        "TARGET_USER", "TARGET_PASSWORD"))
                .mapping(MappingDraft.builder()
                        .sourceKeys(List.of("id"))
                        .targetKeys(List.of("id"))
                        .sourceFields(List.of("name", "email"))
                        .targetFields(List.of("name", "email"))
                        .build())
                .strategy(StrategyDraft.builder().mode("checksum").algorithm("xor").build())
                .build();
    }

    private DatasetDraft dataset(String type, String name, String url, String userEnv, String passwordEnv) {
        return DatasetDraft.builder()
                .type(type)
                .name(name)
                .jdbcUrl(url)
                .usernameEnv(userEnv)
                .passwordEnv(passwordEnv)
                .resourceType("table")
                .resourceName("users")
                .build();
    }
}
