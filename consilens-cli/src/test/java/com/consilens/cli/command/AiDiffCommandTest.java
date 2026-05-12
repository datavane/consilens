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

class AiDiffCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldGenerateConfigWithoutExecutingDiff() throws Exception {
        Path output = tempDir.resolve("ai-diff.yaml");

        int exitCode = new CommandLine(new AiDiffCommand()).execute(
                "--no-llm",
                "--source-type", "mysql",
                "--source-url", "jdbc:mysql://localhost:3306/source",
                "--source-table", "orders",
                "--source-user-env", "MYSQL_USER",
                "--source-password-env", "MYSQL_PASSWORD",
                "--target-type", "postgresql",
                "--target-url", "jdbc:postgresql://localhost:5432/target",
                "--target-table", "orders",
                "--target-user-env", "PG_USER",
                "--target-password-env", "PG_PASSWORD",
                "--keys", "id",
                "--output", output.toString());

        assertEquals(0, exitCode);
        assertTrue(Files.exists(output));
        CliConfiguration config = new ObjectMapper(new YAMLFactory()).readValue(output.toFile(), CliConfiguration.class);
        assertEquals("orders", config.getSource().getResource().getName());
    }

    @Test
    void shouldRejectExecuteFlag() {
        Path output = tempDir.resolve("ai-diff.yaml");

        int exitCode = new CommandLine(new AiDiffCommand()).execute("--execute", "--output", output.toString());

        assertEquals(2, exitCode);
        assertFalse(Files.exists(output));
    }
}
