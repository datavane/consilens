package com.consilens.cluster.kubernetes;

import com.consilens.cluster.api.ClusterApplicationResult;
import com.consilens.cluster.api.ClusterComparisonDescription;
import com.consilens.cluster.api.ClusterExecutionDescription;
import com.consilens.cluster.api.ClusterSubmission;
import com.consilens.cluster.api.ClusterSubmitRequest;
import com.consilens.cluster.api.KubernetesSecretKeyRef;
import com.consilens.cluster.api.KubernetesSubmissionSpec;
import com.consilens.cluster.kubernetes.gateway.KubernetesSubmissionGateway;
import com.consilens.connector.api.planner.ExecutionMode;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KubernetesClusterSubmitterTest {

    @Test
    void shouldMapCompleteJobThroughGateway() {
        RecordingGateway gateway = new RecordingGateway();
        KubernetesSubmissionSpec spec = KubernetesSubmissionSpec.builder()
                .namespace("data-quality")
                .jobName("consilens-orders-compare")
                .image("registry.example/consilens:1.0.0")
                .descriptorUri("https://quality-configs.example/consilens/orders.json")
                .coordinatorMainClass("com.consilens.runtime.KubernetesCoordinator")
                .memoryMiB(2048)
                .cpuMilli(750)
                .serviceAccountName("consilens-runner")
                .imagePullPolicy("IfNotPresent")
                .labels(Map.of("example.com/team", "quality"))
                .secretEnv(Map.of("SOURCE_PASSWORD", KubernetesSecretKeyRef.builder()
                        .secretName("consilens-database")
                        .secretKey("source-password")
                        .build()))
                .build();

        ClusterSubmission submission = new KubernetesClusterSubmitter(gateway).submit(kubernetesRequest(spec, 3));

        Job job = gateway.createdJob;
        assertEquals("data-quality", job.getMetadata().getNamespace());
        assertEquals("consilens-orders-compare", job.getMetadata().getName());
        assertEquals("quality", job.getMetadata().getLabels().get("example.com/team"));
        assertEquals("consilens", job.getMetadata().getLabels().get("app.kubernetes.io/name"));
        assertEquals(2, job.getSpec().getBackoffLimit());
        assertEquals("Never", job.getSpec().getTemplate().getSpec().getRestartPolicy());
        assertEquals("consilens-runner", job.getSpec().getTemplate().getSpec().getServiceAccountName());

        Container container = job.getSpec().getTemplate().getSpec().getContainers().get(0);
        assertEquals("consilens-coordinator", container.getName());
        assertEquals("registry.example/consilens:1.0.0", container.getImage());
        assertEquals("IfNotPresent", container.getImagePullPolicy());
        assertEquals(List.of("java"), container.getCommand());
        // 堆只占容器内存的 70%（2048Mi → 1433m），剩余留给非堆内存
        assertTrue(container.getArgs().contains("-Xmx1433m"), container.getArgs().toString());
        assertTrue(container.getArgs().contains("com.consilens.runtime.KubernetesCoordinator"));
        assertTrue(container.getArgs().contains("submission-kubernetes-1"));
        assertTrue(container.getArgs().contains("https://quality-configs.example/consilens/orders.json"));
        assertEquals("consilens-database", container.getEnv().get(0).getValueFrom().getSecretKeyRef().getName());
        assertEquals("source-password", container.getEnv().get(0).getValueFrom().getSecretKeyRef().getKey());
        assertEquals("2048", container.getResources().getRequests().get("memory").getAmount());
        assertEquals("Mi", container.getResources().getRequests().get("memory").getFormat());
        assertEquals("750", container.getResources().getLimits().get("cpu").getAmount());
        assertEquals("m", container.getResources().getLimits().get("cpu").getFormat());
        assertFalse(container.getArgs().toString().contains("password"));

        assertEquals("data-quality/consilens-orders-compare", submission.getClusterApplicationId());
        assertEquals(ExecutionMode.KUBERNETES, submission.getExecutionMode());
        assertEquals(1, gateway.createCalls);
    }

    @Test
    void shouldRejectNonKubernetesModeBeforeContactingGateway() {
        RecordingGateway gateway = new RecordingGateway();
        ClusterSubmitRequest request = kubernetesRequest(validSpec(), 1);
        request.getExecution().setExecutionMode(ExecutionMode.YARN);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new KubernetesClusterSubmitter(gateway).submit(request));

        assertTrue(error.getMessage().contains("only supports KUBERNETES"));
        assertEquals(0, gateway.createCalls);
    }

    @Test
    void shouldRejectInvalidSpecAndAttemptsBeforeContactingGateway() {
        KubernetesSubmissionSpec invalidUri = validSpec();
        invalidUri.setDescriptorUri("s3a://quality-configs/orders.json");
        assertRejectedBeforeGateway(kubernetesRequest(invalidUri, 1));

        KubernetesSubmissionSpec invalidClass = validSpec();
        invalidClass.setCoordinatorMainClass("java -jar runtime");
        assertRejectedBeforeGateway(kubernetesRequest(invalidClass, 1));

        KubernetesSubmissionSpec invalidMemory = validSpec();
        invalidMemory.setMemoryMiB(0);
        assertRejectedBeforeGateway(kubernetesRequest(invalidMemory, 1));

        KubernetesSubmissionSpec invalidLabel = validSpec();
        invalidLabel.setLabels(Map.of("invalid..prefix/team", "quality"));
        assertRejectedBeforeGateway(kubernetesRequest(invalidLabel, 1));

        assertRejectedBeforeGateway(kubernetesRequest(validSpec(), 0));
        assertRejectedBeforeGateway(kubernetesRequest(validSpec(), -1));
    }

    @Test
    void shouldUseOneBackoffRetryForTwoAttemptsAndNotLeakLabelsIntoCommand() {
        RecordingGateway gateway = new RecordingGateway();
        KubernetesSubmissionSpec spec = validSpec();
        spec.setLabels(Map.of("secret-label", "secret-value"));

        new KubernetesClusterSubmitter(gateway).submit(kubernetesRequest(spec, 2));

        assertEquals(1, gateway.createdJob.getSpec().getBackoffLimit());
        assertFalse(gateway.createdJob.getSpec().getTemplate().getSpec().getContainers().get(0).getArgs().toString()
                .contains("secret-value"));
    }

    @Test
    void shouldCreateConfigMapAndMountLocalDescriptor() {
        RecordingGateway gateway = new RecordingGateway();
        Map<String, String> descriptorData = new LinkedHashMap<>();
        descriptorData.put("comparison.yaml", "source:\n  password: ${env.SOURCE_PASSWORD}\n");
        KubernetesSubmissionSpec spec = validSpec();
        spec.setDescriptorData(descriptorData);
        spec.setDescriptorConfigMapName("consilens-orders-descriptor");
        spec.setDescriptorMountPath("/opt/consilens/descriptor");

        new KubernetesClusterSubmitter(gateway).submit(kubernetesRequest(spec, 1));

        assertEquals(1, gateway.createConfigMapCalls);
        assertEquals("consilens-orders-descriptor", gateway.createdConfigMap.getMetadata().getName());
        assertEquals("comparison.yaml", gateway.createdConfigMap.getData().keySet().iterator().next());
        String arg = gateway.createdJob.getSpec().getTemplate().getSpec().getContainers().get(0).getArgs().toString();
        assertTrue(gateway.createdJob.getSpec().getTemplate().getSpec().getContainers().get(0).getArgs()
                .contains("/opt/consilens/descriptor/comparison.yaml"));
        assertFalse(arg.contains("${env.SOURCE_PASSWORD}"));
    }

    @Test
    void shouldAddPlainEnvsSecretEnvsAndImagePullSecrets() {
        RecordingGateway gateway = new RecordingGateway();
        KubernetesSubmissionSpec spec = validSpec();
        spec.setEnvs(Map.of("JDBC_DRIVER", "org.postgresql.Driver"));
        spec.setSecretEnv(Map.of("SOURCE_PASSWORD", KubernetesSecretKeyRef.builder()
                .secretName("consilens-database")
                .secretKey("source-password")
                .build()));
        spec.setImagePullSecrets(List.of("registry-credentials"));

        new KubernetesClusterSubmitter(gateway).submit(kubernetesRequest(spec, 1));

        var podSpec = gateway.createdJob.getSpec().getTemplate().getSpec();
        assertEquals(0, podSpec.getInitContainers().size());
        assertEquals(List.of("registry-credentials"),
                podSpec.getImagePullSecrets().stream()
                        .map(ref -> ref.getName())
                        .collect(Collectors.toList()));
        var envVars = podSpec.getContainers().get(0).getEnv();
        assertEquals("org.postgresql.Driver", envVars.stream()
                .filter(env -> "JDBC_DRIVER".equals(env.getName()))
                .findFirst().orElseThrow().getValue());
        assertEquals("consilens-database", envVars.stream()
                .filter(env -> "SOURCE_PASSWORD".equals(env.getName()))
                .findFirst().orElseThrow().getValueFrom().getSecretKeyRef().getName());
    }

    @Test
    void shouldSweepStaleJobFromRequestNamespaceAndJobNameOverride() {
        RecordingGateway gateway = new RecordingGateway();
        KubernetesSubmissionSpec spec = validSpec();
        spec.setNamespace("prod");
        spec.setJobName("consilens-orders-prod");

        new KubernetesClusterSubmitter(gateway).killStale(kubernetesRequest(spec, 1));

        assertEquals("prod", gateway.deletedNamespace);
        assertEquals("consilens-orders-prod", gateway.deletedJobName);
    }

    @Test
    void shouldSweepStaleJobWithCustomNameWhenJobCarriesConsilensLabel() {
        RecordingGateway gateway = new RecordingGateway();
        KubernetesSubmissionSpec spec = validSpec();
        spec.setJobName("orders-nightly");
        gateway.jobLabels = java.util.Map.of(
                "app.kubernetes.io/name", KubernetesSubmissionSpec.DEFAULT_APPLICATION_LABEL);

        new KubernetesClusterSubmitter(gateway).killStale(kubernetesRequest(spec, 1));

        // 自定义 jobName 也必须清扫，否则同名 Job 第二次提交会 AlreadyExists
        assertEquals("orders-nightly", gateway.deletedJobName);
    }

    @Test
    void shouldNotSweepCustomJobNameOwnedBySomebodyElse() {
        RecordingGateway gateway = new RecordingGateway();
        KubernetesSubmissionSpec spec = validSpec();
        spec.setJobName("orders-nightly");
        gateway.jobLabels = java.util.Map.of("app.kubernetes.io/name", "other-app");

        new KubernetesClusterSubmitter(gateway).killStale(kubernetesRequest(spec, 1));

        assertNull(gateway.deletedJobName);
    }

    @Test
    void shouldNotSweepUnknownCustomJobName() {
        RecordingGateway gateway = new RecordingGateway();
        KubernetesSubmissionSpec spec = validSpec();
        spec.setJobName("orders-nightly");

        new KubernetesClusterSubmitter(gateway).killStale(kubernetesRequest(spec, 1));

        assertNull(gateway.deletedJobName);
    }

    /**
     * 清扫用的 namespace/jobName 必须来自提交请求里的 spec：与 submit 使用同一套值，
     * 不能在这里另写一份推导规则（规则漂移会扫错对象）。缺 spec 时明确拒绝。
     */
    @Test
    void shouldRejectStaleSweepWithoutKubernetesSubmissionSpec() {
        RecordingGateway gateway = new RecordingGateway();
        ClusterSubmitRequest request = kubernetesRequest(null, 1);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new KubernetesClusterSubmitter(gateway).killStale(request));

        assertTrue(error.getMessage().contains("jobName"), error.getMessage());
        assertNull(gateway.deletedJobName);
    }

    @Test
    void shouldDeleteDescriptorConfigMapAfterJobCompletes() {
        RecordingGateway gateway = new RecordingGateway();
        KubernetesSubmissionSpec spec = KubernetesSubmissionSpec.builder()
                .namespace("default")
                .jobName("consilens-descriptor-cleanup")
                .image("registry.example/consilens:latest")
                .descriptorData(Map.of("comparison.yaml", "source: {}"))
                .descriptorConfigMapName("consilens-descriptor-cleanup-descriptor")
                .descriptorMountPath("/opt/consilens/descriptor")
                .coordinatorMainClass("com.consilens.runtime.KubernetesCoordinator")
                .memoryMiB(1024)
                .cpuMilli(500)
                .build();
        KubernetesClusterSubmitter submitter = new KubernetesClusterSubmitter(gateway);

        ClusterSubmission submission = submitter.submit(kubernetesRequest(spec, 1));
        gateway.completionStatus = "Complete";
        submitter.awaitCompletion(submission, java.time.Duration.ofMillis(200));

        assertEquals("default", gateway.deletedConfigMapNamespace);
        assertEquals("consilens-descriptor-cleanup-descriptor", gateway.deletedConfigMapName);
    }

    @Test
    void shouldPassCoordinatorSummaryFromPodLogsAsDiagnostics() {
        RecordingGateway gateway = new RecordingGateway();
        gateway.completionStatus = "Complete";
        gateway.logs = "starting coordinator\n"
                + "{\"submissionId\":\"submission-kubernetes-1\",\"statistics\":{\"sourceRowCount\":3,\"targetRowCount\":3}}\n"
                + "done";

        ClusterApplicationResult result = new KubernetesClusterSubmitter(gateway)
                .awaitCompletion(submission("default/consilens-compare"), java.time.Duration.ofMillis(200));

        assertNotNull(result);
        assertEquals("SUCCEEDED", result.getFinalStatus());
        assertNotNull(result.getDiagnostics());
        assertTrue(result.getDiagnostics().contains("\"statistics\""), result.getDiagnostics());
    }

    @Test
    void shouldNotInventDiagnosticsWhenPodLogsHaveNoSummary() {
        RecordingGateway gateway = new RecordingGateway();
        gateway.completionStatus = "Complete";
        gateway.logs = "plain log line without json";

        ClusterApplicationResult result = new KubernetesClusterSubmitter(gateway)
                .awaitCompletion(submission("default/consilens-compare"), java.time.Duration.ofMillis(200));

        assertNotNull(result);
        assertNull(result.getDiagnostics());
    }

    @Test
    void shouldNotFailCompletedJobWhenLogRetrievalFails() {
        RecordingGateway gateway = new RecordingGateway();
        gateway.completionStatus = "Complete";
        gateway.failLogs = true;

        ClusterApplicationResult result = new KubernetesClusterSubmitter(gateway)
                .awaitCompletion(submission("default/consilens-compare"), java.time.Duration.ofMillis(200));

        assertNotNull(result);
        assertEquals("SUCCEEDED", result.getFinalStatus());
        assertNull(result.getDiagnostics());
    }

    @Test
    void shouldPassFailureLogTailAsDiagnostics() {
        RecordingGateway gateway = new RecordingGateway();
        gateway.completionStatus = "Failed";
        gateway.logs = "starting coordinator\n"
                + "Cluster comparison failed: java.net.ConnectException: Connection refused\n"
                + "\tat example.Coordinator.run(Coordinator.java:1)\n"
                + "\tat example.Coordinator.main(Coordinator.java:2)\n";

        ClusterApplicationResult result = new KubernetesClusterSubmitter(gateway)
                .awaitCompletion(submission("default/consilens-compare"), java.time.Duration.ofMillis(200));

        assertNotNull(result);
        assertEquals("FAILED", result.getFinalStatus());
        assertNotNull(result.getDiagnostics());
        assertTrue(result.getDiagnostics().contains("Cluster comparison failed"), result.getDiagnostics());
        assertTrue(result.getDiagnostics().contains("ConnectException"), result.getDiagnostics());
        assertTrue(result.getDiagnostics().contains("Coordinator.main"), result.getDiagnostics());
    }

    @Test
    void shouldIncludeJobAndPodFailureDetailsWhenLogsAreUnavailable() {
        RecordingGateway gateway = new RecordingGateway();
        gateway.completionStatus = "Failed";
        gateway.failureDetails = "job: BackoffLimitExceeded - Job has reached the specified backoff limit; "
                + "pod compare/worker terminated: OOMKilled - exitCode=137";
        gateway.failLogs = true;

        ClusterApplicationResult result = new KubernetesClusterSubmitter(gateway)
                .awaitCompletion(submission("default/consilens-compare"), java.time.Duration.ofMillis(200));

        assertNotNull(result);
        assertEquals("FAILED", result.getFinalStatus());
        assertNotNull(result.getDiagnostics());
        assertTrue(result.getDiagnostics().contains("BackoffLimitExceeded"), result.getDiagnostics());
        assertTrue(result.getDiagnostics().contains("OOMKilled"), result.getDiagnostics());
    }

    @Test
    void shouldCombineJobFailureDetailsWithLogTail() {
        String diagnostics = KubernetesClusterSubmitter.failureDiagnostics(
                "noise\nCluster comparison failed: boom\n\tat example.Main.run(Main.java:1)",
                "job: BackoffLimitExceeded - limit reached");

        assertTrue(diagnostics.startsWith("job: BackoffLimitExceeded"), diagnostics);
        assertTrue(diagnostics.contains("Cluster comparison failed: boom"), diagnostics);
        assertTrue(diagnostics.contains("example.Main.run"), diagnostics);
    }

    @Test
    void shouldFallBackToLogTailWithoutFailureMarker() {
        String diagnostics = KubernetesClusterSubmitter.failureDiagnostics(
                "line-1\nline-2\njava.lang.IllegalStateException: still visible", null);

        assertTrue(diagnostics.contains("IllegalStateException"), diagnostics);
    }

    private ClusterSubmission submission(String applicationId) {
        return ClusterSubmission.builder()
                .submissionId("submission-kubernetes-1")
                .clusterApplicationId(applicationId)
                .submittedAt(java.time.Instant.now())
                .build();
    }

    private KubernetesSubmissionSpec validSpec() {
        return KubernetesSubmissionSpec.builder()
                .namespace("default")
                .jobName("consilens-compare")
                .image("registry.example/consilens:latest")
                .descriptorUri("https://quality-configs.example/consilens/descriptor.json")
                .coordinatorMainClass("com.consilens.runtime.KubernetesCoordinator")
                .memoryMiB(1024)
                .cpuMilli(500)
                .build();
    }

    private void assertRejectedBeforeGateway(ClusterSubmitRequest request) {
        RecordingGateway gateway = new RecordingGateway();
        assertThrows(IllegalArgumentException.class, () -> new KubernetesClusterSubmitter(gateway).submit(request));
        assertEquals(0, gateway.createCalls);
    }

    private ClusterSubmitRequest kubernetesRequest(KubernetesSubmissionSpec spec, int maxAttempts) {
        return ClusterSubmitRequest.builder()
                .submissionId("submission-kubernetes-1")
                .comparison(ClusterComparisonDescription.builder()
                        .sourceConfigRef("descriptor/source")
                        .targetConfigRef("descriptor/target")
                        .build())
                .execution(ClusterExecutionDescription.builder()
                        .executionMode(ExecutionMode.KUBERNETES)
                        .maxAttempts(maxAttempts)
                        .build())
                .kubernetesSubmission(spec)
                .build();
    }

    private static final class RecordingGateway implements KubernetesSubmissionGateway {

        private int createCalls;
        private Job createdJob;
        private int createConfigMapCalls;
        private ConfigMap createdConfigMap;
        private String deletedNamespace;
        private String deletedJobName;

        @Override
        public Job create(Job job) {
            createCalls++;
            createdJob = job;
            return job;
        }

        @Override
        public ConfigMap createConfigMap(ConfigMap configMap) {
            createConfigMapCalls++;
            createdConfigMap = configMap;
            return configMap;
        }

        private String completionStatus;
        private String logs;
        private String failureDetails;
        private boolean failLogs;

        @Override
        public java.util.Optional<String> jobCompletionStatus(String namespace, String jobName) {
            return java.util.Optional.ofNullable(completionStatus);
        }

        @Override
        public java.util.Optional<String> jobLogs(String namespace, String jobName) {
            if (failLogs) {
                throw new RuntimeException("log backend unavailable");
            }
            return java.util.Optional.ofNullable(logs);
        }

        @Override
        public java.util.Optional<String> jobFailureDetails(String namespace, String jobName) {
            return java.util.Optional.ofNullable(failureDetails);
        }

        private java.util.Map<String, String> jobLabels;

        @Override
        public java.util.Optional<java.util.Map<String, String>> jobLabels(String namespace, String jobName) {
            return java.util.Optional.ofNullable(jobLabels);
        }

        @Override
        public void deleteJob(String namespace, String jobName) {
            deletedNamespace = namespace;
            deletedJobName = jobName;
        }

        private String deletedConfigMapNamespace;
        private String deletedConfigMapName;

        @Override
        public void deleteConfigMap(String namespace, String configMapName) {
            deletedConfigMapNamespace = namespace;
            deletedConfigMapName = configMapName;
        }

        @Override
        public void close() {
            // Recording gateway owns no external client.
        }
    }
}
