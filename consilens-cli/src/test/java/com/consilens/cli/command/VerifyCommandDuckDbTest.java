package com.consilens.cli.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class VerifyCommandDuckDbTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRunVerifyWithDuckDbEngine() throws Exception {
        Path source = tempDir.resolve("source.csv");
        Path target = tempDir.resolve("target.csv");
        Files.writeString(source, "id,name,amount\n1,alice,10\n2,bob,20\n");
        Files.writeString(target, "id,name,amount\n1,alice,10\n2,bob,21\n");
        Path config = config(source, target);
        Path reportDir = tempDir.resolve("report");

        int exitCode = new CommandLine(new VerifyCommand()).execute(
                "-c", config.toString(),
                "--engine", "duckdb",
                "--fail-on", "any",
                "--format", "json",
                "--output-dir", reportDir.toString());

        assertThat(exitCode).isEqualTo(1);
        Path report = reportDir.resolve("verify-result.json");
        assertThat(report).exists();
        assertThat(Files.readString(report)).contains("FAIL");
    }

    @Test
    void shouldReturnConfigurationErrorForUnknownEngine() throws Exception {
        Path source = tempDir.resolve("source.csv");
        Path target = tempDir.resolve("target.csv");
        Files.writeString(source, "id,name,amount\n1,alice,10\n");
        Files.writeString(target, "id,name,amount\n1,alice,10\n");
        Path config = config(source, target);

        int exitCode = new CommandLine(new VerifyCommand()).execute(
                "-c", config.toString(),
                "--engine", "spark",
                "--format", "json",
                "--output-dir", tempDir.resolve("report2").toString());

        assertThat(exitCode).isEqualTo(2);
    }

    private Path config(Path source, Path target) throws Exception {
        Path config = tempDir.resolve("compare.yaml");
        Files.writeString(config, "source:\n"
                + "  type: file\n"
                + "  name: source\n"
                + "  resource:\n"
                + "    type: file\n"
                + "    name: source.csv\n"
                + "    path: " + source.toAbsolutePath() + "\n"
                + "    format: csv\n"
                + "target:\n"
                + "  type: file\n"
                + "  name: target\n"
                + "  resource:\n"
                + "    type: file\n"
                + "    name: target.csv\n"
                + "    path: " + target.toAbsolutePath() + "\n"
                + "    format: csv\n"
                + "comparison:\n"
                + "  keys:\n"
                + "    source: [id]\n"
                + "    target: [id]\n"
                + "  fields:\n"
                + "    source: [name, amount]\n"
                + "    target: [name, amount]\n");
        return config;
    }
}
