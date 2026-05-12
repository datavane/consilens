package com.consilens.cli.command;

import com.consilens.ai.model.AnalysisResult;
import com.consilens.ai.spi.AIAnalyzer;
import com.consilens.cli.ai.AIDiagnoseService;
import com.consilens.core.diff.DiffResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiDiagnoseCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldDiagnoseDiffRecordArray() throws Exception {
        Path result = tempDir.resolve("diff-records.json");
        Files.writeString(result,
                "[{"
                        + "\"operation\":\"MISMATCH\","
                        + "\"primaryKey\":[1],"
                        + "\"sourceValues\":[\"Alice\"],"
                        + "\"targetValues\":[\"\"],"
                        + "\"columnNames1\":[\"name\"],"
                        + "\"columnNames2\":[\"name\"]"
                        + "}]");

        int exitCode = new CommandLine(new AiDiagnoseCommand()).execute("--result", result.toString());

        assertEquals(0, exitCode);
    }

    @Test
    void shouldFailWhenResultDoesNotContainDiffEvidence() throws Exception {
        Path result = tempDir.resolve("stats-only.json");
        Files.writeString(result, "{\"differenceCount\":1}");

        int exitCode = new CommandLine(new AiDiagnoseCommand()).execute("--result", result.toString());

        assertEquals(1, exitCode);
    }

    @Test
    void shouldUseInjectedDiagnoseService() {
        AtomicReference<String> analyzer = new AtomicReference<>();
        AtomicReference<String> resultPath = new AtomicReference<>();

        int exitCode = new CommandLine(new AiDiagnoseCommand(name -> {
            analyzer.set(name);
            return new AIDiagnoseService(new TestAnalyzer()) {
                @Override
                public String diagnose(String path) {
                    resultPath.set(path);
                    return "diagnosed";
                }
            };
        })).execute("--result", "diff.json", "--analyzer", "custom");

        assertEquals(0, exitCode);
        assertEquals("custom", analyzer.get());
        assertEquals("diff.json", resultPath.get());
    }

    @Test
    void shouldUseRuleBasedAnalyzerByDefault() {
        AtomicReference<String> analyzer = new AtomicReference<>();

        int exitCode = new CommandLine(new AiDiagnoseCommand(name -> {
            analyzer.set(name);
            return new AIDiagnoseService(new TestAnalyzer()) {
                @Override
                public String diagnose(String path) {
                    return "diagnosed";
                }
            };
        })).execute("--result", "diff.json");

        assertEquals(0, exitCode);
        assertEquals("rulebased", analyzer.get());
    }

    @Test
    void shouldWriteDiagnosisReportToOutputFile() throws Exception {
        Path output = tempDir.resolve("reports/diagnose.md");

        int exitCode = new CommandLine(new AiDiagnoseCommand(name -> new AIDiagnoseService(new TestAnalyzer()) {
            @Override
            public String diagnose(String path) {
                return "# report";
            }
        })).execute("--result", "diff.json", "--output", output.toString());

        assertEquals(0, exitCode);
        assertTrue(Files.exists(output));
        assertEquals("# report", Files.readString(output));
    }

    private static class TestAnalyzer implements AIAnalyzer {

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
            return "test";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }
}
