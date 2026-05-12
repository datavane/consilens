package com.consilens.cli.ai;

import com.consilens.ai.spi.AIAnalyzerManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AIDiagnoseServiceTest {

    @TempDir
    Path tempDir;

    private final AIDiagnoseService service =
            new AIDiagnoseService(AIAnalyzerManager.getInstance().create("rulebased"));

    @Test
    void shouldDiagnoseDiffRecordArray() throws Exception {
        Path result = write("diff-records.json",
                "[{"
                        + "\"operation\":\"mismatch\","
                        + "\"primaryKey\":\"1\","
                        + "\"sourceValues\":[\"Alice\"],"
                        + "\"targetValues\":[\"\"],"
                        + "\"columnNames1\":[\"name\"],"
                        + "\"columnNames2\":[\"name\"]"
                        + "}]");

        String report = service.diagnose(result.toString());

        assertTrue(report.contains("# AI Diagnose"));
        assertTrue(report.contains("differences=1"));
        assertTrue(report.contains("NULL_HANDLING"));
    }

    @Test
    void shouldDiagnoseObjectWithDifferencesArray() throws Exception {
        Path result = write("diff-result.json",
                "{\"differences\":[{"
                        + "\"operation\":\"MISMATCH\","
                        + "\"primaryKey\":[1],"
                        + "\"sourceValues\":[\"abcdef\"],"
                        + "\"targetValues\":[\"abc\"],"
                        + "\"columnNames1\":[\"code\"],"
                        + "\"columnNames2\":[\"code\"]"
                        + "}]}");

        String report = service.diagnose(result.toString());

        assertTrue(report.contains("TRUNCATION"));
    }

    @Test
    void shouldRejectStatsOnlyJson() throws Exception {
        Path result = write("stats-only.json", "{\"differenceCount\":1}");

        assertThrows(IOException.class, () -> service.diagnose(result.toString()));
    }

    private Path write(String fileName, String content) throws IOException {
        Path path = tempDir.resolve(fileName);
        Files.writeString(path, content);
        return path;
    }
}
