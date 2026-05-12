package com.consilens.cli.config;

import com.consilens.cli.model.CliConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.stream.Collectors;
import java.util.Map;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExampleConfigurationCompatibilityTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("exampleConfigurationPaths")
    void shouldLoadExampleConfigurations(Path configPath) throws Exception {
        ConfigurationManager configurationManager = new ConfigurationManager(testEnvironment());

        CliConfiguration config = configurationManager.loadConfiguration(configPath.toString(), false);

        assertNotNull(config);
        assertNotNull(config.getSource());
        assertNotNull(config.getTarget());
        assertNotNull(config.getSource().getResource());
        assertNotNull(config.getTarget().getResource());
        assertNotNull(config.getComparison());
        assertNotNull(config.getComparison().getKeys());
    }

    @Test
    void shouldCoverStructuredConfigurationOptionsAcrossExamples() throws Exception {
        List<String> exampleTexts = exampleConfigurationPaths()
                .map(path -> {
                    try {
                        return Files.readString(path);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                .collect(Collectors.toList());

        Map<String, Predicate<String>> requiredPatterns = new LinkedHashMap<>();
        requiredPatterns.put("comparison.mappings", text -> text.contains("mappings:") || text.contains("\"mappings\""));
        requiredPatterns.put("comparison.extraColumns", text -> text.contains("extraColumns:") || text.contains("\"extraColumns\""));
        requiredPatterns.put("source/target.readOptions", text -> text.contains("readOptions:") || text.contains("\"readOptions\""));
        requiredPatterns.put("strategy.mode=join", text -> text.contains("mode: join") || text.contains("\"mode\": \"join\""));
        requiredPatterns.put("strategy.algorithm=concat", text -> text.contains("algorithm: concat")
                || text.contains("\"algorithm\": \"concat\""));
        requiredPatterns.put("strategy.localCompare.mode=row-hash", text -> text.contains("mode: row-hash")
                || text.contains("\"mode\": \"row-hash\""));
        requiredPatterns.put("strategy.maxDifferences", text -> text.contains("maxDifferences:")
                || text.contains("\"maxDifferences\""));
        requiredPatterns.put("normalization", text -> text.contains("normalization:") || text.contains("\"normalization\""));
        requiredPatterns.put("result.failOnSinkError", text -> text.contains("failOnSinkError:")
                || text.contains("\"failOnSinkError\""));
        requiredPatterns.put("result.sinks[].enabled", text -> text.contains("enabled: false")
                || text.contains("\"enabled\": false"));

        for (Map.Entry<String, Predicate<String>> entry : requiredPatterns.entrySet()) {
            assertTrue(exampleTexts.stream().anyMatch(entry.getValue()),
                    "examples 目录缺少 " + entry.getKey() + " 的覆盖示例");
        }
    }

    private static Stream<Path> exampleConfigurationPaths() throws IOException {
        Path examplesDirectory = Paths.get("..", "examples").toAbsolutePath().normalize();
        List<Path> paths;
        try (Stream<Path> stream = Files.list(examplesDirectory)) {
            paths = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String fileName = path.getFileName().toString();
                        return fileName.endsWith(".yaml")
                                || fileName.endsWith(".yml")
                                || fileName.endsWith(".json");
                    })
                    .sorted()
                    .collect(Collectors.toList());
        }
        return paths.stream();
    }

    private Map<String, String> testEnvironment() {
        Map<String, String> env = new HashMap<>();
        env.put("MYSQL_USER", "test_user");
        env.put("MYSQL_PASSWORD", "test_password");
        env.put("PG_USER", "test_user");
        env.put("PG_PASSWORD", "test_password");
        env.put("STARROCKS_USER", "test_user");
        env.put("STARROCKS_PASSWORD", "test_password");
        env.put("DORIS_USER", "test_user");
        env.put("DORIS_PASSWORD", "test_password");
        return env;
    }
}
