package com.consilens.cli.command;

import com.consilens.ai.model.AnalysisResult;
import com.consilens.ai.model.BackendInfo;
import com.consilens.ai.model.ChatMessage;
import com.consilens.ai.model.FunctionDefinition;
import com.consilens.ai.model.LLMResponse;
import com.consilens.ai.spi.AIAnalyzer;
import com.consilens.ai.spi.LLMBackend;
import com.consilens.cli.ai.AIBackendOptions;
import com.consilens.core.diff.DiffResult;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiDoctorCommandTest {

    @Test
    void shouldPassOfflineDefaultChecks() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CommandLine commandLine = commandLine(new AiDoctorCommand(
                () -> Set.of("rulebased"),
                () -> Set.of("noop", "openai"),
                TestAnalyzer::new,
                options -> new TestBackend("noop", "none", true),
                options -> "noop",
                name -> null,
                new com.fasterxml.jackson.databind.ObjectMapper()), out, new ByteArrayOutputStream());

        int exitCode = commandLine.execute();

        assertEquals(0, exitCode);
        String output = out.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("# AI Doctor"));
        assertTrue(output.contains("Status: WARN"));
        assertTrue(output.contains("onlineAvailability: SKIP"));
    }

    @Test
    void shouldFailWhenCloudBackendHasNoApiKey() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CommandLine commandLine = commandLine(new AiDoctorCommand(
                () -> Set.of("rulebased"),
                () -> Set.of("openai"),
                TestAnalyzer::new,
                options -> new TestBackend("openai", "gpt-test", true),
                options -> "openai",
                name -> null,
                new com.fasterxml.jackson.databind.ObjectMapper()), out, new ByteArrayOutputStream());

        int exitCode = commandLine.execute("--backend", "openai");

        assertEquals(1, exitCode);
        String output = out.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("credentials: FAIL"));
        assertTrue(output.contains("OPENAI_API_KEY"));
    }

    @Test
    void shouldPassCloudBackendWithApiKeyAndJsonOutput() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CommandLine commandLine = commandLine(new AiDoctorCommand(
                () -> Set.of("rulebased"),
                () -> Set.of("deepseek"),
                TestAnalyzer::new,
                options -> new TestBackend("deepseek", "deepseek-chat", true),
                options -> "deepseek",
                Map.of("DEEPSEEK_API_KEY", "test-key")::get,
                new com.fasterxml.jackson.databind.ObjectMapper()), out, new ByteArrayOutputStream());

        int exitCode = commandLine.execute("--backend", "deepseek", "--format", "json");

        assertEquals(0, exitCode);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("\"status\":\"PASS\""));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("\"credentials\""));
    }

    @Test
    void shouldRejectUnsupportedFormat() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CommandLine commandLine = commandLine(new AiDoctorCommand(
                () -> Set.of("rulebased"),
                () -> Set.of("noop"),
                TestAnalyzer::new,
                options -> new TestBackend("noop", "none", true),
                options -> "noop",
                name -> null,
                new com.fasterxml.jackson.databind.ObjectMapper()), new ByteArrayOutputStream(), err);

        int exitCode = commandLine.execute("--format", "xml");

        assertEquals(2, exitCode);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("Unsupported format: xml"));
    }

    @Test
    void shouldRunOnlineCheckOnlyWhenRequested() {
        AtomicBoolean availabilityCalled = new AtomicBoolean(false);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CommandLine commandLine = commandLine(new AiDoctorCommand(
                () -> Set.of("rulebased"),
                () -> Set.of("ollama"),
                TestAnalyzer::new,
                options -> new TestBackend("ollama", "qwen-test", true, availabilityCalled),
                options -> "ollama",
                name -> null,
                new com.fasterxml.jackson.databind.ObjectMapper()), out, new ByteArrayOutputStream());

        int exitCode = commandLine.execute("--backend", "ollama", "--online");

        assertEquals(0, exitCode);
        assertTrue(availabilityCalled.get());
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("onlineAvailability: PASS"));
    }

    @Test
    void shouldNotRunOnlineCheckByDefault() {
        AtomicBoolean availabilityCalled = new AtomicBoolean(false);
        CommandLine commandLine = commandLine(new AiDoctorCommand(
                () -> Set.of("rulebased"),
                () -> Set.of("ollama"),
                TestAnalyzer::new,
                options -> new TestBackend("ollama", "qwen-test", true, availabilityCalled),
                options -> "ollama",
                name -> null,
                new com.fasterxml.jackson.databind.ObjectMapper()), new ByteArrayOutputStream(), new ByteArrayOutputStream());

        int exitCode = commandLine.execute("--backend", "ollama");

        assertEquals(0, exitCode);
        assertFalse(availabilityCalled.get());
    }

    private CommandLine commandLine(AiDoctorCommand command, ByteArrayOutputStream out, ByteArrayOutputStream err) {
        CommandLine commandLine = new CommandLine(command);
        commandLine.setOut(new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), true));
        commandLine.setErr(new PrintWriter(new OutputStreamWriter(err, StandardCharsets.UTF_8), true));
        return commandLine;
    }

    private static class TestAnalyzer implements AIAnalyzer {

        private final String name;

        private TestAnalyzer(String name) {
            this.name = name;
        }

        @Override
        public AnalysisResult analyze(DiffResult diffResult) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String explainResult(DiffResult diffResult) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }

    private static class TestBackend implements LLMBackend {

        private final String name;
        private final String model;
        private final boolean available;
        private final AtomicBoolean availabilityCalled;

        private TestBackend(String name, String model, boolean available) {
            this(name, model, available, new AtomicBoolean(false));
        }

        private TestBackend(String name, String model, boolean available, AtomicBoolean availabilityCalled) {
            this.name = name;
            this.model = model;
            this.available = available;
            this.availabilityCalled = availabilityCalled;
        }

        @Override
        public LLMResponse chat(String systemPrompt, List<ChatMessage> messages, List<FunctionDefinition> functions) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String complete(String prompt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isAvailable() {
            availabilityCalled.set(true);
            return available;
        }

        @Override
        public BackendInfo info() {
            return BackendInfo.builder()
                    .name(name)
                    .model(model)
                    .build();
        }
    }
}
