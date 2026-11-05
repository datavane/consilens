package com.consilens.cli.connector;

import com.consilens.cli.command.ConnectorCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectorCertifyCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldCertifyBothFileSides() throws Exception {
        Path config = config(true);

        Result result = execute(config, "--format", "json");

        assertThat(result.exitCode).isZero();
        assertThat(result.output).contains("\"passed\" : true");
        assertThat(result.output).contains("\"side\" : \"source\"");
        assertThat(result.output).contains("\"side\" : \"target\"");
        assertThat(result.output).contains("\"recordsRead\" : 2");
    }

    @Test
    void shouldFailWhenSourceFileIsMissing() throws Exception {
        Path config = config(false);

        Result result = execute(config, "--side", "source", "--format", "json");

        assertThat(result.exitCode).isEqualTo(1);
        assertThat(result.output).contains("\"passed\" : false");
        assertThat(result.output).contains("dataset.open");
    }

    private Result execute(Path config, String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine commandLine = new CommandLine(new ConnectorCommand());
        commandLine.setOut(new PrintWriter(out));
        commandLine.setErr(new PrintWriter(err));
        String[] all = new String[args.length + 3];
        all[0] = "certify";
        all[1] = "-c";
        all[2] = config.toString();
        System.arraycopy(args, 0, all, 3, args.length);
        int exitCode = commandLine.execute(all);
        return new Result(exitCode, out.toString(), err.toString());
    }

    private Path config(boolean createSource) throws Exception {
        Path source = tempDir.resolve("source.csv");
        Path target = tempDir.resolve("target.csv");
        if (createSource) {
            Files.writeString(source, "id,name\n1,alice\n2,bob\n");
        }
        Files.writeString(target, "id,name\n1,alice\n2,bob\n");
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
                + "    source: [name]\n"
                + "    target: [name]\n");
        return config;
    }

    private static final class Result {
        private final int exitCode;
        private final String output;
        private final String error;

        private Result(int exitCode, String output, String error) {
            this.exitCode = exitCode;
            this.output = output;
            this.error = error;
        }
    }
}
