package com.consilens.cli.command;

import com.consilens.cluster.api.ClusterSubmission;
import com.consilens.cluster.api.ClusterSubmitRequest;
import com.consilens.cluster.api.ClusterSubmitter;
import com.consilens.cluster.api.KubernetesSubmissionSpec;
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
import java.util.List;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubmitKubernetesCommandTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldBuildRedactedRequestAndPrintJobReceipt() throws Exception {
        String password = "password-that-must-not-be-published";
        AtomicReference<ClusterSubmitRequest> captured = new AtomicReference<>();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        SubmitKubernetesCommand command = command(captured, () -> ClusterSubmission.builder()
                .submissionId("submission-kubernetes-1")
                .executionMode(ExecutionMode.KUBERNETES)
                .submittedAt(Instant.parse("2026-08-25T00:00:00Z"))
                .clusterApplicationId("data-quality/consilens-orders")
                .build());

        int exitCode = commandLine(command, output, error).execute(
                "--image", "registry.example/consilens:1.0.0",
                "--descriptor-uri", "https://quality-configs.example/consilens/orders.json",
                "--namespace", "data-quality", "--name", "consilens-orders",
                "--memory", "2g", "--cpu", "0.75", "--max-attempts", "3",
                "--service-account", "consilens-runner", "--label", "team=quality",
                "--label", "app.kubernetes.io/part-of=quality-platform",
                "--secret-env", "SOURCE_PASSWORD=consilens-database/source-password");

        ClusterSubmitRequest request = captured.get();
        assertEquals(0, exitCode);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("data-quality/consilens-orders"));
        assertEquals(ExecutionMode.KUBERNETES, request.getExecution().getExecutionMode());
        assertEquals("registry.example/consilens:1.0.0", request.getKubernetesSubmission().getImage());
        assertEquals("data-quality", request.getKubernetesSubmission().getNamespace());
        assertEquals("consilens-orders", request.getKubernetesSubmission().getJobName());
        assertEquals(2048, request.getKubernetesSubmission().getMemoryMiB());
        assertEquals(750, request.getKubernetesSubmission().getCpuMilli());
        assertEquals("quality", request.getKubernetesSubmission().getLabels().get("team"));
        assertEquals("quality-platform", request.getKubernetesSubmission().getLabels()
                .get("app.kubernetes.io/part-of"));
        assertEquals("consilens-database", request.getKubernetesSubmission().getSecretEnv()
                .get("SOURCE_PASSWORD").getSecretName());
        assertEquals("source-password", request.getKubernetesSubmission().getSecretEnv()
                .get("SOURCE_PASSWORD").getSecretKey());
        assertFalse(serialized(request).contains(password));
        assertFalse(output.toString(StandardCharsets.UTF_8).contains(password));
        assertFalse(error.toString(StandardCharsets.UTF_8).contains(password));
    }

    @Test
    void shouldUseClusterComparisonCoordinatorByDefault() throws Exception {
        AtomicReference<ClusterSubmitRequest> captured = new AtomicReference<>();

        int exitCode = commandLine(command(captured, () -> ClusterSubmission.builder()
                .submissionId("submission-kubernetes-1")
                .executionMode(ExecutionMode.KUBERNETES)
                .clusterApplicationId("default/consilens-submission-kubernetes-1")
                .build()), new ByteArrayOutputStream(), new ByteArrayOutputStream()).execute(
                "--image", "registry.example/consilens:1.0.0",
                "--descriptor-uri", "https://configs.example/consilens/submission.yaml");

        assertEquals(0, exitCode);
        assertEquals("com.consilens.cluster.application.ClusterComparisonCoordinator",
                captured.get().getKubernetesSubmission().getCoordinatorMainClass());
    }

    @Test
    void shouldReadImageFromPropertiesFile() throws Exception {
        Path properties = temporaryDirectory.resolve("kubernetes.properties");
        Files.writeString(properties,
                "consilens.kubernetes.image=registry.example/consilens:2.0.0\n");
        AtomicReference<ClusterSubmitRequest> captured = new AtomicReference<>();

        int exitCode = commandLine(command(captured, () -> ClusterSubmission.builder()
                .submissionId("submission-kubernetes-1")
                .executionMode(ExecutionMode.KUBERNETES)
                .clusterApplicationId("default/consilens-submission-kubernetes-1")
                .build()), new ByteArrayOutputStream(), new ByteArrayOutputStream()).execute(
                "--properties-file", properties.toString(),
                "--descriptor-uri", "https://configs.example/consilens/submission.yaml");

        assertEquals(0, exitCode);
        assertEquals("registry.example/consilens:2.0.0",
                captured.get().getKubernetesSubmission().getImage());
    }

    @Test
    void shouldRejectMissingRequiredKubernetesOptionsBeforeSubmitterCall() throws Exception {
        AtomicBoolean called = new AtomicBoolean();
        SubmitKubernetesCommand command = new SubmitKubernetesCommand(
                () -> {
                    called.set(true);
                    return request -> ClusterSubmission.builder().build();
                }, () -> "submission-kubernetes-2");
        ByteArrayOutputStream error = new ByteArrayOutputStream();

        int exitCode = commandLine(command, new ByteArrayOutputStream(), error)
                .execute("--image", "registry.example/consilens:1.0.0");

        assertEquals(1, exitCode);
        assertFalse(called.get());
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("Kubernetes submission failed"));
    }

    @Test
    void shouldPreflightInvalidKubernetesParametersBeforeCreatingSubmitter() throws Exception {
        AtomicBoolean factoryCalled = new AtomicBoolean();
        SubmitKubernetesCommand command = new SubmitKubernetesCommand(
                () -> {
                    factoryCalled.set(true);
                    return request -> ClusterSubmission.builder().build();
                }, () -> "submission-kubernetes-3");

        int invalidUriExitCode = commandLine(command, new ByteArrayOutputStream(), new ByteArrayOutputStream()).execute(
                "--image", "registry.example/consilens:1.0.0",
                "--descriptor-uri", "s3a://quality-configs/orders.json");

        assertEquals(1, invalidUriExitCode);
        assertFalse(factoryCalled.get());

        SubmitKubernetesCommand attemptsCommand = new SubmitKubernetesCommand(() -> {
                    factoryCalled.set(true);
                    return request -> ClusterSubmission.builder().build();
                }, () -> "submission-kubernetes-4");
        int invalidAttemptsExitCode = commandLine(attemptsCommand, new ByteArrayOutputStream(),
                new ByteArrayOutputStream()).execute(
                "--image", "registry.example/consilens:1.0.0",
                "--descriptor-uri", "https://quality-configs.example/orders.json",
                "--max-attempts", "0");

        assertEquals(1, invalidAttemptsExitCode);
        assertFalse(factoryCalled.get());
    }

    @Test
    void shouldHideSecretWhenSubmitterFactoryFails() throws Exception {
        String password = "password-that-must-not-be-logged";
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        SubmitKubernetesCommand command = new SubmitKubernetesCommand(
                () -> {
                    throw new IllegalStateException(password);
                }, () -> "submission-kubernetes-5");

        int exitCode = commandLine(command, new ByteArrayOutputStream(), error).execute(
                "--image", "registry.example/consilens:1.0.0",
                "--descriptor-uri", "https://quality-configs.example/orders.json");

        assertEquals(1, exitCode);
        assertTrue(error.toString(StandardCharsets.UTF_8).contains("Kubernetes submission failed"));
        assertFalse(error.toString(StandardCharsets.UTF_8).contains(password));
    }

    @Test
    void shouldUploadLocalDescriptorAsConfigMap() throws Exception {
        String localDescriptor = "source:\n  type: mysql\n  connection:\n    password: ${env.SOURCE_PASSWORD}\n"
                + "target:\n  type: mysql\n  connection:\n    password: ${env.TARGET_PASSWORD}\n";
        Path descriptorFile = temporaryDirectory.resolve("local-comparison.yaml");
        Files.writeString(descriptorFile, localDescriptor);
        AtomicReference<ClusterSubmitRequest> captured = new AtomicReference<>();

        int exitCode = commandLine(command(captured, () -> ClusterSubmission.builder()
                .submissionId("submission-kubernetes-1")
                .executionMode(ExecutionMode.KUBERNETES)
                .clusterApplicationId("default/consilens-local")
                .build()), new ByteArrayOutputStream(), new ByteArrayOutputStream()).execute(
                "--image", "registry.example/consilens:1.0.0",
                "--namespace", "default",
                "--name", "consilens-local",
                descriptorFile.toString());

        assertEquals(0, exitCode);
        KubernetesSubmissionSpec spec = captured.get().getKubernetesSubmission();
        assertEquals("local-comparison.yaml", spec.getDescriptorData().keySet().iterator().next());
        assertEquals("consilens-local-descriptor", spec.getDescriptorConfigMapName());
        assertEquals("/opt/consilens/descriptor", spec.getDescriptorMountPath());
        assertTrue(spec.getDescriptorData().get("local-comparison.yaml").contains("${env.SOURCE_PASSWORD}"));
    }

    @Test
    void shouldPassEnvironmentAndPullSecretsAndSparkStyleResources() throws Exception {
        Path descriptor = temporaryDirectory.resolve("compare.yaml");
        Files.writeString(descriptor, configuration("${env.SOURCE_PASSWORD}"));
        AtomicReference<ClusterSubmitRequest> captured = new AtomicReference<>();

        int exitCode = commandLine(command(captured, () -> ClusterSubmission.builder()
                .submissionId("submission-kubernetes-1")
                .executionMode(ExecutionMode.KUBERNETES)
                .clusterApplicationId("default/consilens-local")
                .build()), new ByteArrayOutputStream(), new ByteArrayOutputStream()).execute(
                "--image", "registry.example/consilens:1.0.0",
                "--memory", "2g", "--cpu", "0.75",
                "--env", "JDBC_DRIVER=org.postgresql.Driver",
                "--image-pull-secret", "registry-credentials",
                descriptor.toString());

        assertEquals(0, exitCode);
        KubernetesSubmissionSpec spec = captured.get().getKubernetesSubmission();
        assertEquals(2048, spec.getMemoryMiB());
        assertEquals(750, spec.getCpuMilli());
        assertEquals("org.postgresql.Driver", spec.getEnvs().get("JDBC_DRIVER"));
        assertEquals(List.of("registry-credentials"), spec.getImagePullSecrets());
        // positional descriptor becomes ConfigMap data, like the flink conf ConfigMap
        assertTrue(spec.getDescriptorData().containsKey("compare.yaml"));
    }

    @Test
    void shouldAcceptJsonDescriptorWithEnvironmentPlaceholder() throws Exception {
        Path descriptorFile = temporaryDirectory.resolve("placeholder.json");
        Files.writeString(descriptorFile,
                "{\n  \"source\": {\n    \"password\": \"${env.SOURCE_PASSWORD}\"\n  }\n}");
        AtomicReference<ClusterSubmitRequest> captured = new AtomicReference<>();

        int exitCode = commandLine(command(captured, () -> ClusterSubmission.builder()
                .submissionId("submission-kubernetes-9")
                .executionMode(ExecutionMode.KUBERNETES)
                .clusterApplicationId("default/consilens-placeholder")
                .build()), new ByteArrayOutputStream(), new ByteArrayOutputStream()).execute(
                "--image", "registry.example/consilens:1.0.0",
                "--namespace", "default",
                "--name", "consilens-placeholder",
                descriptorFile.toString());

        assertEquals(0, exitCode);
        assertTrue(captured.get().getKubernetesSubmission().getDescriptorData()
                .get("placeholder.json").contains("${env.SOURCE_PASSWORD}"));
    }

    @Test
    void shouldAcceptCompactJsonDescriptorWithEnvironmentPlaceholder() throws Exception {
        Path descriptorFile = temporaryDirectory.resolve("compact-placeholder.json");
        Files.writeString(descriptorFile, "{\"source\":{\"password\":\"${env.SOURCE_PASSWORD}\"}}");
        AtomicReference<ClusterSubmitRequest> captured = new AtomicReference<>();

        int exitCode = commandLine(command(captured, () -> ClusterSubmission.builder()
                .submissionId("submission-kubernetes-11")
                .executionMode(ExecutionMode.KUBERNETES)
                .clusterApplicationId("default/consilens-compact")
                .build()), new ByteArrayOutputStream(), new ByteArrayOutputStream()).execute(
                "--image", "registry.example/consilens:1.0.0",
                "--namespace", "default",
                "--name", "consilens-compact",
                descriptorFile.toString());

        assertEquals(0, exitCode);
        assertTrue(captured.get().getKubernetesSubmission().getDescriptorData()
                .get("compact-placeholder.json").contains("${env.SOURCE_PASSWORD}"));
    }

    @Test
    void shouldRejectMalformedDescriptorBeforeSubmitterCall() throws Exception {
        Path descriptorFile = temporaryDirectory.resolve("malformed.yaml");
        Files.writeString(descriptorFile, "source: [not: valid: yaml");
        AtomicBoolean factoryCalled = new AtomicBoolean();
        SubmitKubernetesCommand command = new SubmitKubernetesCommand(
                () -> {
                    factoryCalled.set(true);
                    return request -> ClusterSubmission.builder().build();
                }, () -> "submission-kubernetes-14");

        int exitCode = commandLine(command, new ByteArrayOutputStream(), new ByteArrayOutputStream()).execute(
                "--image", "registry.example/consilens:1.0.0",
                descriptorFile.toString());

        assertEquals(1, exitCode);
        assertFalse(factoryCalled.get());
    }

    private SubmitKubernetesCommand command(AtomicReference<ClusterSubmitRequest> captured,
                                             Supplier<ClusterSubmission> receiptFactory) {
        return new SubmitKubernetesCommand(
                () -> new RecordingSubmitter(captured, receiptFactory), () -> "submission-kubernetes-1");
    }

    private CommandLine commandLine(SubmitKubernetesCommand command,
                                    ByteArrayOutputStream output,
                                    ByteArrayOutputStream error) {
        CommandLine commandLine = new CommandLine(command);
        commandLine.setOut(new PrintWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), true));
        commandLine.setErr(new PrintWriter(new OutputStreamWriter(error, StandardCharsets.UTF_8), true));
        return commandLine;
    }

    private String serialized(ClusterSubmitRequest request) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ObjectOutputStream objectOutput = new ObjectOutputStream(output)) {
            objectOutput.writeObject(request);
        }
        return output.toString(StandardCharsets.ISO_8859_1);
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
            request.getKubernetesSubmission().validate();
            captured.set(request);
            return receiptFactory.get();
        }
    }
}
