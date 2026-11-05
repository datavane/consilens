package com.consilens.cli.command;

import com.consilens.cluster.api.ClusterSubmission;
import com.consilens.cluster.api.ClusterSubmitRequest;
import com.consilens.cluster.api.ClusterSubmitter;
import com.consilens.cluster.api.YarnSubmissionSpec;
import com.consilens.connector.api.planner.ExecutionMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubmitYarnCommandTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldBuildRedactedRequestAndPrintApplicationId() throws Exception {
        Path configurationFile = temporaryDirectory.resolve("compare.yaml");
        String password = "password-that-must-not-be-published";
        Files.writeString(configurationFile, configuration(password));
        AtomicReference<ClusterSubmitRequest> captured = new AtomicReference<>();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        SubmitYarnCommand command = command(captured, () -> ClusterSubmission.builder()
                .submissionId("submission-yarn-1")
                .executionMode(ExecutionMode.YARN)
                .submittedAt(Instant.parse("2026-08-23T00:00:00Z"))
                .clusterApplicationId("application_42_0001")
                .build());
        CommandLine commandLine = commandLine(command, output, error);

        int exitCode = commandLine.execute("-c", configurationFile.toString(),
                "--runtime-archive", "hdfs://namenode/apps/consilens/runtime.zip",
                "--descriptor-uri", "hdfs://namenode/apps/consilens/submission.json",
                "--queue", "data-quality", "--name", "orders-compare",
                "--am-memory", "2g", "--am-vcores", "2",
                "--conf", "consilens.yarn.maxAppAttempts=3");

        ClusterSubmitRequest request = captured.get();
        assertEquals(0, exitCode);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("application_42_0001"));
        assertEquals(ExecutionMode.YARN, request.getExecution().getExecutionMode());
        assertEquals("hdfs://namenode/apps/consilens/runtime.zip", request.getYarnSubmission().getRuntimeArchiveUri());
        assertEquals("hdfs://namenode/apps/consilens/submission.json", request.getYarnSubmission().getDescriptorUri());
        assertEquals("orders-compare", request.getYarnSubmission().getApplicationName());
        assertEquals(2048, request.getYarnSubmission().getAmMemoryMb());
        assertEquals(2, request.getYarnSubmission().getAmVCores());
        assertEquals(3, request.getExecution().getMaxAttempts());
        assertFalse(output.toString(StandardCharsets.UTF_8).contains(password));
        assertFalse(error.toString(StandardCharsets.UTF_8).contains(password));
        assertFalse(serialized(request).contains(password));
    }

    @Test
    void shouldUseClusterComparisonCoordinatorByDefault() throws Exception {
        Path configurationFile = temporaryDirectory.resolve("compare.yaml");
        Files.writeString(configurationFile, configuration("${env.CLUSTER_ONLY_PASSWORD}"));
        AtomicReference<ClusterSubmitRequest> captured = new AtomicReference<>();

        int exitCode = commandLine(command(captured, () -> ClusterSubmission.builder()
                .submissionId("submission-yarn-1")
                .executionMode(ExecutionMode.YARN)
                .clusterApplicationId("application_42_0001")
                .build()), new ByteArrayOutputStream(), new ByteArrayOutputStream()).execute(
                "-c", configurationFile.toString(),
                "--runtime-archive", "hdfs://namenode/apps/consilens/runtime.zip",
                "--descriptor-uri", "hdfs://namenode/apps/consilens/submission.yaml");

        assertEquals(0, exitCode);
        assertEquals("com.consilens.cluster.application.ClusterComparisonCoordinator",
                captured.get().getYarnSubmission().getAmMainClass());
    }

    @Test
    void shouldRejectMissingDescriptorBeforeSubmitterCall() throws Exception {
        Path configurationFile = temporaryDirectory.resolve("compare.yaml");
        Files.writeString(configurationFile, configuration("password"));
        AtomicReference<Boolean> called = new AtomicReference<>(false);
        SubmitYarnCommand command = new SubmitYarnCommand(
                () -> request -> {
                    called.set(true);
                    return ClusterSubmission.builder().build();
                }, () -> "submission-yarn-2");
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exitCode = commandLine(command, new ByteArrayOutputStream(), error)
                .execute("-c", configurationFile.toString(), "--runtime-archive", "hdfs://namenode/runtime.zip");

        assertEquals(1, exitCode);
        assertFalse(called.get());
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("descriptor"));
    }

    @Test
    void shouldRejectInvalidMemoryFormatBeforeSubmitterCall() throws Exception {
        Path configurationFile = temporaryDirectory.resolve("compare.yaml");
        Files.writeString(configurationFile, configuration("password"));
        AtomicReference<Boolean> called = new AtomicReference<>(false);
        SubmitYarnCommand command = new SubmitYarnCommand(
                () -> request -> {
                    called.set(true);
                    return ClusterSubmission.builder().build();
                }, () -> "submission-yarn-2");
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exitCode = commandLine(command, new ByteArrayOutputStream(), error).execute(
                "-c", configurationFile.toString(),
                "--am-memory", "2xg",
                "--runtime-archive", "hdfs://namenode/runtime.zip",
                "--descriptor-uri", "hdfs://namenode/submission.json");

        assertEquals(1, exitCode);
        assertFalse(called.get());
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("invalid memory format"));
    }

    @Test
    void shouldHideUnderlyingSecretWhenSubmitterFails() throws Exception {
        Path configurationFile = temporaryDirectory.resolve("compare.yaml");
        String password = "password-that-must-not-be-logged";
        Files.writeString(configurationFile, configuration(password));
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        SubmitYarnCommand command = new SubmitYarnCommand(
                () -> request -> {
                    throw new IllegalStateException(password);
                }, () -> "submission-yarn-3");

        int exitCode = commandLine(command, new ByteArrayOutputStream(), error).execute(
                "-c", configurationFile.toString(),
                "--runtime-archive", "hdfs://namenode/runtime.zip",
                "--descriptor-uri", "hdfs://namenode/submission.json");

        assertEquals(1, exitCode);
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("YARN submission failed"));
        assertFalse(error.toString(StandardCharsets.UTF_8).contains(password));
    }

    @Test
    void shouldPreflightInvalidYarnParametersBeforeCreatingSubmitter() throws Exception {
        Path configurationFile = temporaryDirectory.resolve("compare.yaml");
        Files.writeString(configurationFile, configuration("password"));
        AtomicBoolean factoryCalled = new AtomicBoolean();
        SubmitYarnCommand command = new SubmitYarnCommand(
                () -> {
                    factoryCalled.set(true);
                    return request -> ClusterSubmission.builder().build();
                }, () -> "submission-yarn-4");
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int invalidUriExitCode = commandLine(command, new ByteArrayOutputStream(), error).execute(
                "-c", configurationFile.toString(),
                "--runtime-archive", "hdfs://namenode/runtime.zip",
                "--descriptor-uri", "hdfs://namenode/submission.json?token=secret");

        assertEquals(1, invalidUriExitCode);
        assertFalse(factoryCalled.get());
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("YARN submission failed"));

        SubmitYarnCommand attemptsCommand = new SubmitYarnCommand(
                () -> {
                    factoryCalled.set(true);
                    return request -> ClusterSubmission.builder().build();
                }, () -> "submission-yarn-5");
        int invalidAttemptsExitCode = commandLine(attemptsCommand, new ByteArrayOutputStream(),
                new ByteArrayOutputStream()).execute(
                "-c", configurationFile.toString(),
                "--runtime-archive", "hdfs://namenode/runtime.zip",
                "--descriptor-uri", "hdfs://namenode/submission.json",
                "--conf", "consilens.yarn.maxAppAttempts=0");

        assertEquals(1, invalidAttemptsExitCode);
        assertFalse(factoryCalled.get());
    }

    @Test
    void shouldHideUnderlyingSecretWhenSubmitterFactoryFails() throws Exception {
        Path configurationFile = temporaryDirectory.resolve("compare.yaml");
        String password = "password-that-must-not-be-logged-during-initialization";
        Files.writeString(configurationFile, configuration(password));
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        SubmitYarnCommand command = new SubmitYarnCommand(
                () -> {
                    throw new IllegalStateException(password);
                }, () -> "submission-yarn-6");

        int exitCode = commandLine(command, new ByteArrayOutputStream(), error).execute(
                "-c", configurationFile.toString(),
                "--runtime-archive", "hdfs://namenode/runtime.zip",
                "--descriptor-uri", "hdfs://namenode/submission.json");

        assertEquals(1, exitCode);
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("YARN submission failed"));
        assertFalse(error.toString(StandardCharsets.UTF_8).contains(password));
    }

    @Test
    void shouldAcceptPositionalDescriptorAndConfStagingLikeSparkSubmit() throws Exception {
        Path runtime = temporaryDirectory.resolve("consilens-runtime.zip");
        Path descriptor = temporaryDirectory.resolve("compare.yaml");
        Files.write(runtime, new byte[]{1, 2, 3});
        Files.writeString(descriptor, configuration("${env.SOURCE_PASSWORD}"));
        AtomicReference<ClusterSubmitRequest> captured = new AtomicReference<>();

        int exitCode = commandLine(command(captured, () -> ClusterSubmission.builder()
                .submissionId("submission-yarn-1")
                .executionMode(ExecutionMode.YARN)
                .clusterApplicationId("application_42_0001")
                .build()), new ByteArrayOutputStream(), new ByteArrayOutputStream()).execute(
                "--runtime-archive", runtime.toString(),
                "--conf", "consilens.yarn.stagingDir=hdfs://namenode/apps/staging/consilens",
                descriptor.toString());

        assertEquals(0, exitCode);
        YarnSubmissionSpec spec = captured.get().getYarnSubmission();
        assertEquals(runtime.toString(), spec.getRuntimeArchiveUri());
        assertEquals(descriptor.toString(), spec.getDescriptorUri());
        assertEquals("hdfs://namenode/apps/staging/consilens", spec.getStagingUri());
        // defaults: --am-memory 1g, --am-vcores 1
        assertEquals(1024, spec.getAmMemoryMb());
        assertEquals(1, spec.getAmVCores());
    }

    @Test
    void shouldApplyPropertiesFileDefaultsUnderCliFlags() throws Exception {
        Path propertiesFile = temporaryDirectory.resolve("consilens-defaults.conf");
        Files.writeString(propertiesFile, "consilens.yarn.queue=data-quality\n"
                + "consilens.yarn.amMemory=512m\n"
                + "consilens.yarn.tags=consilens,nightly\n");
        Path descriptor = temporaryDirectory.resolve("compare.yaml");
        Files.writeString(descriptor, configuration("password"));
        AtomicReference<ClusterSubmitRequest> captured = new AtomicReference<>();

        int exitCode = commandLine(command(captured, () -> ClusterSubmission.builder()
                .submissionId("submission-yarn-1")
                .executionMode(ExecutionMode.YARN)
                .clusterApplicationId("application_42_0001")
                .build()), new ByteArrayOutputStream(), new ByteArrayOutputStream()).execute(
                "--properties-file", propertiesFile.toString(),
                "--queue", "adhoc",
                "--runtime-archive", "hdfs://namenode/apps/consilens/runtime.zip",
                descriptor.toString());

        assertEquals(0, exitCode);
        YarnSubmissionSpec spec = captured.get().getYarnSubmission();
        // CLI flag wins over the properties file, properties file wins over defaults.
        assertEquals("adhoc", spec.getQueue());
        assertEquals(512, spec.getAmMemoryMb());
        assertEquals(2, spec.getTags().size());
        assertTrue(spec.getTags().contains("nightly"));
    }

    @Test
    void shouldPassFilesAndJarsThroughToSubmission() throws Exception {
        Path descriptor = temporaryDirectory.resolve("compare.yaml");
        Files.writeString(descriptor, configuration("password"));
        Path extraJar = temporaryDirectory.resolve("driver.jar");
        Files.write(extraJar, new byte[]{1});
        AtomicReference<ClusterSubmitRequest> captured = new AtomicReference<>();

        int exitCode = commandLine(command(captured, () -> ClusterSubmission.builder()
                .submissionId("submission-yarn-1")
                .executionMode(ExecutionMode.YARN)
                .clusterApplicationId("application_42_0001")
                .build()), new ByteArrayOutputStream(), new ByteArrayOutputStream()).execute(
                "--runtime-archive", "hdfs://namenode/apps/consilens/runtime.zip",
                "--files", "hdfs://namenode/conf/extra.json#settings.json",
                "--jars", extraJar.toString(),
                descriptor.toString());

        assertEquals(0, exitCode);
        YarnSubmissionSpec spec = captured.get().getYarnSubmission();
        assertEquals(List.of("hdfs://namenode/conf/extra.json#settings.json"), spec.getFiles());
        assertEquals(List.of(extraJar.toString()), spec.getJars());
    }

    private SubmitYarnCommand command(AtomicReference<ClusterSubmitRequest> captured,
                                      Supplier<ClusterSubmission> receiptFactory) {
        return new SubmitYarnCommand(
                () -> new RecordingSubmitter(captured, receiptFactory), () -> "submission-yarn-1");
    }

    private CommandLine commandLine(SubmitYarnCommand command,
                                    ByteArrayOutputStream output,
                                    ByteArrayOutputStream error) {
        CommandLine commandLine = new CommandLine(command);
        commandLine.setOut(new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), true));
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

    private String serialized(ClusterSubmitRequest request) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ObjectOutputStream objectOutput = new ObjectOutputStream(output)) {
            objectOutput.writeObject(request);
        }
        return output.toString(StandardCharsets.ISO_8859_1);
    }

    private static final class RecordingSubmitter implements ClusterSubmitter {

        private final AtomicReference<ClusterSubmitRequest> captured;
        private final Supplier<ClusterSubmission> receiptFactory;

        private RecordingSubmitter(AtomicReference<ClusterSubmitRequest> captured,
                                   Supplier<ClusterSubmission> receiptFactory) {
            this.captured = captured;
            this.receiptFactory = receiptFactory;
        }

        @Override
        public ClusterSubmission submit(ClusterSubmitRequest request) {
            request.validate();
            request.getYarnSubmission().validate();
            captured.set(request);
            return receiptFactory.get();
        }
    }
}
