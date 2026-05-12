package com.consilens.cli.command;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiProvidersCommandTest {

    @Test
    void shouldListInjectedProviders() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CommandLine commandLine = new CommandLine(new AiProvidersCommand(
                () -> Set.of("rulebased"),
                () -> Set.of("openai", "deepseek", "noop")));
        commandLine.setOut(new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), true));

        int exitCode = commandLine.execute();

        assertEquals(0, exitCode);
        String output = out.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("# AI Providers"));
        assertTrue(output.contains("Analyzers:"));
        assertTrue(output.contains("  - rulebased"));
        assertTrue(output.contains("LLM Backends:"));
        assertTrue(output.contains("  - deepseek"));
        assertTrue(output.contains("  - noop"));
        assertTrue(output.contains("  - openai"));
    }

    @Test
    void shouldListProvidersAsJson() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CommandLine commandLine = new CommandLine(new AiProvidersCommand(
                () -> Set.of("rulebased"),
                () -> Set.of("openai", "deepseek")));
        commandLine.setOut(new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), true));

        int exitCode = commandLine.execute("--format", "json");

        assertEquals(0, exitCode);
        assertEquals("{\"analyzers\":[\"rulebased\"],\"llmBackends\":[\"deepseek\",\"openai\"]}\n",
                out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void shouldRejectUnsupportedFormat() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CommandLine commandLine = new CommandLine(new AiProvidersCommand(
                () -> Set.of("rulebased"),
                () -> Set.of("openai")));
        commandLine.setErr(new PrintWriter(new OutputStreamWriter(err, StandardCharsets.UTF_8), true));

        int exitCode = commandLine.execute("--format", "xml");

        assertEquals(2, exitCode);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("Unsupported format: xml"));
    }

    @Test
    void shouldExecuteWithDiscoveredProviders() {
        int exitCode = new CommandLine(new AiProvidersCommand()).execute();

        assertEquals(0, exitCode);
    }
}
