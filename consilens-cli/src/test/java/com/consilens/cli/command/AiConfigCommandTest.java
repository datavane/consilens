package com.consilens.cli.command;

import com.consilens.cli.model.CliConfiguration;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiConfigCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldGenerateYamlFileFromExplicitHints() throws Exception {
        Path output = tempDir.resolve("ai-config.yaml");

        int exitCode = new CommandLine(new AiConfigCommand()).execute(
                "--no-llm",
                "--source-type", "mysql",
                "--source-url", "jdbc:mysql://localhost:3306/source",
                "--source-table", "users",
                "--source-user-env", "MYSQL_USER",
                "--source-password-env", "MYSQL_PASSWORD",
                "--target-type", "postgresql",
                "--target-url", "jdbc:postgresql://localhost:5432/target",
                "--target-table", "users",
                "--target-user-env", "PG_USER",
                "--target-password-env", "PG_PASSWORD",
                "--keys", "id",
                "--fields", "name,email",
                "--output", output.toString());

        assertEquals(0, exitCode);
        assertTrue(Files.exists(output));
        String yaml = Files.readString(output);
        assertTrue(yaml.contains("connection:"));
        assertFalse(yaml.contains("comparisons:"));

        CliConfiguration config = new ObjectMapper(new YAMLFactory()).readValue(output.toFile(), CliConfiguration.class);
        assertEquals("mysql", config.getSource().getType());
        assertEquals("id", config.getComparison().getKeys().getSource().get(0));
    }

    @Test
    void shouldFailWhenRequiredHintsAreMissingWithoutWritingFile() {
        Path output = tempDir.resolve("missing.yaml");

        int exitCode = new CommandLine(new AiConfigCommand()).execute("--no-llm", "--output", output.toString());

        assertEquals(1, exitCode);
        assertFalse(Files.exists(output));
    }
}
