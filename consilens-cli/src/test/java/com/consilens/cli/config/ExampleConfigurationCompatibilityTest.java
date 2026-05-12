package com.consilens.cli.config;

import com.consilens.cli.model.CliConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.stream.Collectors;
import java.util.Map;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;

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
