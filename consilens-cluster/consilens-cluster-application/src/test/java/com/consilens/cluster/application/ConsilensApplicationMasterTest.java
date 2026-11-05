package com.consilens.cluster.application;

import org.apache.hadoop.yarn.api.records.FinalApplicationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsilensApplicationMasterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldRegisterLaunchCoordinatorAndReportSuccess() throws Exception {
        Path descriptor = temporaryDirectory.resolve("submission-descriptor.yaml");
        Files.writeString(descriptor, "source: {}\n");
        RecordingAmClient client = new RecordingAmClient();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        ConsilensApplicationMaster master = new ConsilensApplicationMaster(new String[]{
                "--coordinator-class", TestCoordinator.class.getName(),
                "--descriptor", descriptor.toString(),
                "--staging-dir", "hdfs://namenode/apps/consilens/staging/submission-1",
        }, client, new PrintStream(error));

        master.run();

        assertTrue(client.registered);
        assertEquals(FinalApplicationStatus.SUCCEEDED, client.finishedStatus);
        assertTrue(client.closed);
        String errors = error.toString(StandardCharsets.UTF_8);
        assertFalse(errors.contains("Unable to launch coordinator"));
        assertFalse(errors.contains("AM heartbeat failed"));
    }

    @Test
    void shouldReportFailedWhenCoordinatorThrows() throws Exception {
        Path descriptor = temporaryDirectory.resolve("submission-descriptor.yaml");
        Files.writeString(descriptor, "source: {}\n");
        RecordingAmClient client = new RecordingAmClient();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        ConsilensApplicationMaster master = new ConsilensApplicationMaster(new String[]{
                "--coordinator-class", FailingCoordinator.class.getName(),
                "--descriptor", descriptor.toString(),
        }, client, new PrintStream(error));

        master.run();

        assertEquals(FinalApplicationStatus.FAILED, client.finishedStatus);
        assertTrue(client.diagnostics != null && client.diagnostics.contains("boom"),
                "失败原因必须进入 diagnostics，而不是只报 SUCCEEDED");
    }

    @Test
    void shouldReportCoordinatorExitCodeAndCapturedStderr() throws Exception {
        Path descriptor = temporaryDirectory.resolve("submission-descriptor.yaml");
        Files.writeString(descriptor, "source: {}\n");
        RecordingAmClient client = new RecordingAmClient();
        ConsilensApplicationMaster master = new ConsilensApplicationMaster(new String[]{
                "--coordinator-class", ExitCodeCoordinator.class.getName(),
                "--descriptor", descriptor.toString(),
        }, client, new PrintStream(new ByteArrayOutputStream()));

        master.run();

        assertEquals(FinalApplicationStatus.FAILED, client.finishedStatus);
        assertTrue(client.diagnostics != null && client.diagnostics.contains("code 2"),
                client.diagnostics);
        assertTrue(client.diagnostics.contains("database unreachable"),
                "coordinator stderr 必须进入 diagnostics: " + client.diagnostics);
    }

    @Test
    void shouldUseExecuteEntryPointWithoutTerminatingJvm() throws Exception {
        Path descriptor = temporaryDirectory.resolve("submission-descriptor.yaml");
        Files.writeString(descriptor, "source: {}\n");
        RecordingAmClient client = new RecordingAmClient();
        ConsilensApplicationMaster master = new ConsilensApplicationMaster(new String[]{
                "--coordinator-class", ExecuteCoordinator.class.getName(),
                "--descriptor", descriptor.toString(),
        }, client, new PrintStream(new ByteArrayOutputStream()));

        master.run();

        assertEquals(FinalApplicationStatus.SUCCEEDED, client.finishedStatus);
        assertTrue(client.diagnostics != null && client.diagnostics.contains("sub-execute"),
                client.diagnostics);
    }

    @Test
    void shouldAcceptSecretsOptionForCliCompatibility() throws Exception {
        Path descriptor = temporaryDirectory.resolve("submission-descriptor.yaml");
        Files.writeString(descriptor, "source: {}\n");
        RecordingAmClient client = new RecordingAmClient();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        ConsilensApplicationMaster master = new ConsilensApplicationMaster(new String[]{
                "--coordinator-class", TestCoordinator.class.getName(),
                "--descriptor", descriptor.toString(),
                "--secrets", "submission-secrets.properties",
        }, client, new PrintStream(error));

        master.run();

        assertEquals(FinalApplicationStatus.SUCCEEDED, client.finishedStatus);
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("--secrets is accepted"));
    }

    @Test
    void shouldExtractSummaryLineFromCoordinatorOutput() {
        String output = "noise\n"
                + "{\"submissionId\":\"sub-7\",\"statistics\":{\"totalDifferences\":2}}\n";

        String summary = ConsilensApplicationMaster.extractSummary(output);

        assertTrue(summary != null && summary.contains("sub-7"));
    }

    @Test
    void shouldIgnoreOutputWithoutSummaryLine() {
        assertEquals(null, ConsilensApplicationMaster.extractSummary("plain log line"));
        assertEquals(null, ConsilensApplicationMaster.extractSummary(null));
    }

    @Test
    void shouldRejectUnknownOrIncompleteArguments() {
        RecordingAmClient client = new RecordingAmClient();

        assertThrows(IllegalArgumentException.class, () -> new ConsilensApplicationMaster(
                new String[]{"--descriptor", "x.yaml"}, client, new PrintStream(new ByteArrayOutputStream())).run());
        assertThrows(IllegalArgumentException.class, () -> new ConsilensApplicationMaster(
                new String[]{"--coordinator-class", "a.B", "--descriptor", "x.yaml", "--bogus", "v"},
                client, new PrintStream(new ByteArrayOutputStream())).run());
        assertEquals(0, client.allocations);
    }

    /** Coordinator main that throws; the AM must report FAILED. */
    public static final class FailingCoordinator {
        public static void main(String[] args) {
            throw new IllegalStateException("boom");
        }
    }

    /** Coordinator execute that fails with a non-zero code and writes the reason to stderr. */
    public static final class ExitCodeCoordinator {
        public static int execute(String[] args) {
            System.err.println("database unreachable");
            return 2;
        }
    }

    /** Coordinator execute that reports success through the captured stdout summary. */
    public static final class ExecuteCoordinator {
        public static int execute(String[] args) {
            System.out.println("{\"submissionId\":\"sub-execute\",\"statistics\":{}}");
            return 0;
        }
    }

    /** Coordinator main that returns normally, i.e. exit code zero. */
    public static final class TestCoordinator {
        public static void main(String[] args) {
            // normal return signals success
        }
    }

    /** Coordinator main that records the localized file arguments it received. */
    public static final class RecordingCoordinator {
        static List<String> receivedArguments;

        public static void main(String[] args) {
            receivedArguments = List.of(args[1], args[2]);
        }
    }

    private static final class RecordingAmClient implements ConsilensApplicationMaster.AmClient {
        private boolean registered;
        private int allocations;
        private FinalApplicationStatus finishedStatus;
        private String diagnostics;
        private boolean closed;

        @Override
        public void register(String host) {
            registered = true;
        }

        @Override
        public void allocate() {
            allocations++;
        }

        @Override
        public void unregister(FinalApplicationStatus status, String diagnostics) {
            finishedStatus = status;
            this.diagnostics = diagnostics;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
