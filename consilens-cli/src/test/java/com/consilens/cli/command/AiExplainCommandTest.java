package com.consilens.cli.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiExplainCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldExplainGeneratedConfigWithoutResolvingEnvPlaceholders() throws Exception {
        Path config = tempDir.resolve("config.yaml");
        Files.writeString(config,
                "source:\n"
                        + "  type: mysql\n"
                        + "  connection:\n"
                        + "    url: jdbc:mysql://localhost:3306/source\n"
                        + "    username: ${env.MYSQL_USER}\n"
                        + "    password: ${env.MYSQL_PASSWORD}\n"
                        + "  resource:\n"
                        + "    type: table\n"
                        + "    name: users\n"
                        + "target:\n"
                        + "  type: postgresql\n"
                        + "  connection:\n"
                        + "    url: jdbc:postgresql://localhost:5432/target\n"
                        + "    username: ${env.PG_USER}\n"
                        + "    password: ${env.PG_PASSWORD}\n"
                        + "  resource:\n"
                        + "    type: table\n"
                        + "    name: users\n"
                        + "comparison:\n"
                        + "  keys:\n"
                        + "    source: [id]\n"
                        + "    target: [id]\n"
                        + "strategy:\n"
                        + "  mode: checksum\n"
                        + "  algorithm: xor\n"
                        + "result:\n"
                        + "  sinks:\n"
                        + "    - format: console\n"
                        + "      type: result\n");

        int exitCode = new CommandLine(new AiExplainCommand()).execute("-c", config.toString());

        assertEquals(0, exitCode);
    }
}
