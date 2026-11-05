package com.consilens.cli.command;

import com.consilens.cli.config.ConfigurationManager;
import com.consilens.cli.service.CompareRequestFactory;
import com.consilens.core.diff.DiffResult;
import com.consilens.core.diff.DiffRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubmitCommandTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void localSubmissionFailureShowsConfigReason() {
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        String missing = temporaryDirectory.resolve("missing.yaml").toString();

        int exitCode = commandLine(error).execute("local", "-c", missing);

        assertEquals(1, exitCode);
        String text = error.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("Local submission failed:"), text);
        assertTrue(text.contains("not found"), text);
    }

    @Test
    void shouldRequireYarnSubmissionOptions() {
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        CommandLine commandLine = commandLine(error);

        int exitCode = commandLine.execute("yarn");

        assertEquals(1, exitCode);
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("descriptor"));
    }

    @Test
    void shouldRequireKubernetesSubmissionOptions() {
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        CommandLine commandLine = commandLine(error);

        int exitCode = commandLine.execute("kubernetes");

        // --image 现在可由 properties-file 提供，缺 descriptor 仍应在提交前失败
        assertEquals(1, exitCode);
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("descriptor is required"));
    }

    @Test
    void shouldRunLocalSubmissionFromConfigurationAndPublishRedactedManifest() throws Exception {
        Path configurationFile = temporaryDirectory.resolve("compare.yaml");
        String password = "password-that-must-not-be-published";
        Files.writeString(configurationFile, configuration(password));
        Path manifestDirectory = temporaryDirectory.resolve("manifest");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        SubmitLocalCommand localCommand = new SubmitLocalCommand(
                new ConfigurationManager(),
                new CompareRequestFactory(),
                () -> request -> DiffResult.of(List.of(DiffRow.modified(List.of(3L),
                        List.of("source"), List.of("target"), List.of("value"), List.of("value"))),
                        com.consilens.connector.api.model.TablePath.of("source_orders"),
                        com.consilens.connector.api.model.TablePath.of("target_orders")),
                com.consilens.cluster.runtime.LocalClusterSubmitter::new,
                () -> "local-submission-1");
        CommandLine commandLine = commandLine(error, localCommand);
        commandLine.setOut(new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), true));

        int exitCode = commandLine.execute("local", "-c", configurationFile.toString(),
                "--manifest-dir", manifestDirectory.toString());

        Path manifest = manifestDirectory.resolve("manifest.json");
        assertEquals(0, exitCode);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("Local submission completed"));
        assertTrue(Files.isRegularFile(manifest));
        assertFalse(Files.readString(manifest).contains(password));
        assertFalse(error.toString(StandardCharsets.UTF_8).contains(password));
    }

    @Test
    void shouldNotExposeConfigurationPasswordWhenLocalSubmissionFails() throws Exception {
        Path configurationFile = temporaryDirectory.resolve("failed-compare.yaml");
        String password = "password-that-must-not-be-logged";
        Files.writeString(configurationFile, configuration(password));
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        SubmitLocalCommand localCommand = new SubmitLocalCommand(
                new ConfigurationManager(),
                new CompareRequestFactory(),
                () -> request -> {
                    throw new IllegalStateException(password);
                },
                com.consilens.cluster.runtime.LocalClusterSubmitter::new,
                () -> "local-submission-2");
        CommandLine commandLine = commandLine(error, localCommand);

        int exitCode = commandLine.execute("local", "-c", configurationFile.toString(),
                "--manifest-dir", temporaryDirectory.resolve("failed-manifest").toString());

        assertEquals(1, exitCode);
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("Local submission failed"));
        assertFalse(error.toString(StandardCharsets.UTF_8).contains(password));
    }

    private CommandLine commandLine(ByteArrayOutputStream error) {
        CommandLine commandLine = new CommandLine(new SubmitCommand());
        commandLine.setErr(new PrintWriter(new OutputStreamWriter(error, StandardCharsets.UTF_8), true));
        return commandLine;
    }

    private CommandLine commandLine(ByteArrayOutputStream error, SubmitLocalCommand localCommand) {
        CommandLine.IFactory factory = new CommandLine.IFactory() {
            @Override
            public <K> K create(Class<K> type) throws Exception {
                if (type == SubmitLocalCommand.class) {
                    return type.cast(localCommand);
                }
                return CommandLine.defaultFactory().create(type);
            }
        };
        CommandLine commandLine = new CommandLine(new SubmitCommand(), factory);
        commandLine.setErr(new PrintWriter(new OutputStreamWriter(error, StandardCharsets.UTF_8), true));
        return commandLine;
    }

    private String configuration(String password) {
        return "source:\n"
                + "  type: mysql\n"
                + "  connection:\n"
                + "    url: jdbc:mysql://localhost:3306/source_db\n"
                + "    username: source_user\n"
                + "    password: " + password + "\n"
                + "  resource:\n"
                + "    type: table\n"
                + "    name: source_orders\n"
                + "target:\n"
                + "  type: mysql\n"
                + "  connection:\n"
                + "    url: jdbc:mysql://localhost:3306/target_db\n"
                + "    username: target_user\n"
                + "    password: " + password + "\n"
                + "  resource:\n"
                + "    type: table\n"
                + "    name: target_orders\n"
                + "comparison:\n"
                + "  keys:\n"
                + "    source: [id]\n"
                + "    target: [id]\n"
                + "  fields:\n"
                + "    source: [value]\n"
                + "    target: [value]\n"
                + "strategy:\n"
                + "  mode: checksum\n"
                + "  algorithm: concat\n"
                + "  bisectionFactor: 4\n"
                + "  bisectionThreshold: 1000\n"
                + "  batchSize: 100\n"
                + "  localCompare:\n"
                + "    mode: full\n";
    }
}
