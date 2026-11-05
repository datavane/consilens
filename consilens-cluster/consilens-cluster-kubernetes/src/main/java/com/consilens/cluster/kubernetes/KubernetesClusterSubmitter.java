package com.consilens.cluster.kubernetes;

import com.consilens.cluster.api.ClusterApplicationResult;
import com.consilens.cluster.api.ClusterComparisonDescription;
import com.consilens.cluster.api.ClusterExecutionDescription;
import com.consilens.cluster.api.ClusterSubmission;
import com.consilens.cluster.api.ClusterSubmissionContext;
import com.consilens.cluster.api.ClusterSubmissionParameters;
import com.consilens.cluster.api.ClusterSubmitRequest;
import com.consilens.cluster.api.ClusterSubmitter;
import com.consilens.cluster.api.KubernetesSecretKeyRef;
import com.consilens.cluster.api.KubernetesSubmissionSpec;
import com.consilens.cluster.kubernetes.gateway.Fabric8KubernetesSubmissionGateway;
import com.consilens.cluster.kubernetes.gateway.KubernetesSubmissionGateway;
import com.consilens.connector.api.planner.ExecutionMode;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.KeyToPathBuilder;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Kubernetes implementation of the cluster submission SPI, following the
 * spark-on-k8s / flink-on-k8s application model: the runtime ships inside the
 * container image (there is deliberately no jar-upload side channel), the
 * descriptor travels as a ConfigMap the way Flink ships its configuration, and
 * the client blocks until the Job completes unless waitAppCompletion is
 * disabled.
 */
public class KubernetesClusterSubmitter implements ClusterSubmitter, AutoCloseable {

    private static final String COORDINATOR_NAME = "consilens-coordinator";
    private static final String DESCRIPTOR_VOLUME = "comparison-descriptor";
    /** 本系统生成的 Job 名称前缀；killStale 只清扫该前缀的应用。 */
    static final String DEFAULT_JOB_NAME_PREFIX = "consilens-";
    private static final Pattern SAFE_SUBMISSION_ID = Pattern.compile("[A-Za-z0-9_-]+");
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(KubernetesClusterSubmitter.class);

    /**
     * Gateway creation is deferred until the first real submission so that
     * ServiceLoader discovery (which instantiates every provider to read its
     * platform) does not build a Kubernetes client for an unused backend.
     */
    private final Supplier<KubernetesSubmissionGateway> gatewayFactory;
    private KubernetesSubmissionGateway gateway;

    /**
     * Poll interval captured from the submitting spec; the interface's
     * {@link #awaitCompletion} has no request access, so the value is remembered
     * from the latest {@link #submit} call. Defaults to the historical 3 seconds.
     */
    private volatile long completionPollSeconds = KubernetesSubmissionSpec.DEFAULT_COMPLETION_POLL_SECONDS;

    /** clusterApplicationId -> descriptor ConfigMap 名称，终态/kill 时用于清理。 */
    private final Map<String, String> descriptorConfigMaps = new java.util.concurrent.ConcurrentHashMap<>();

    public KubernetesClusterSubmitter() {
        this(KubernetesClusterSubmitter::defaultGateway);
    }

    public KubernetesClusterSubmitter(KubernetesSubmissionGateway gateway) {
        this(supplierOf(gateway));
    }

    private KubernetesClusterSubmitter(Supplier<KubernetesSubmissionGateway> gatewayFactory) {
        this.gatewayFactory = Objects.requireNonNull(gatewayFactory, "gatewayFactory");
    }

    private static KubernetesSubmissionGateway defaultGateway() {
        return new Fabric8KubernetesSubmissionGateway();
    }

    private static Supplier<KubernetesSubmissionGateway> supplierOf(KubernetesSubmissionGateway gateway) {
        Objects.requireNonNull(gateway, "gateway");
        return () -> gateway;
    }

    private KubernetesSubmissionGateway gateway() {
        if (gateway == null) {
            gateway = Objects.requireNonNull(gatewayFactory.get(), "gateway");
        }
        return gateway;
    }

    @Override
    public String platform() {
        return "kubernetes";
    }

    @Override
    public boolean descriptorRedactsPasswords() {
        return true;
    }

    @Override
    public ClusterSubmitRequest buildRequest(ClusterSubmissionContext context) {
        Map<String, Object> params = context.getProperties();
        String descriptor = context.getDescriptor();
        String submissionId = context.getSubmissionId();
        String jobName = ClusterSubmissionParameters.orDefault(
                params, "jobName", defaultJobName(submissionId));
        Map<String, KubernetesSecretKeyRef> secretEnv = secretEnvsOf(params.get("secretEnv"));
        requirePasswordSecret(descriptor, secretEnv, "SOURCE_PASSWORD");
        requirePasswordSecret(descriptor, secretEnv, "TARGET_PASSWORD");
        // 表 sink 的连接密码同样不能写进描述符（ConfigMap 明文可读）
        requirePasswordSecret(descriptor, secretEnv, "SINK_PASSWORD");
        String descriptorReference = "configmap:" + descriptorConfigMapName(jobName);
        return ClusterSubmitRequest.builder()
                .submissionId(submissionId)
                .comparison(ClusterComparisonDescription.builder()
                        .sourceConfigRef("descriptor:" + descriptorReference)
                        .targetConfigRef("descriptor:" + descriptorReference)
                        .build())
                .execution(ClusterExecutionDescription.builder()
                        .executionMode(ExecutionMode.KUBERNETES)
                        .maxAttempts(ClusterSubmissionParameters.positiveInteger(
                                params.get("maxAppAttempts"), "maxAppAttempts"))
                        .build())
                .kubernetesSubmission(KubernetesSubmissionSpec.builder()
                        .namespace(ClusterSubmissionParameters.orDefault(
                                params, "namespace", KubernetesSubmissionSpec.DEFAULT_NAMESPACE))
                        .jobName(jobName)
                        .image(ClusterSubmissionParameters.required(params, "image", "kubernetes.image"))
                        .descriptorData(Map.of("comparison.yaml", descriptor))
                        .descriptorConfigMapName(descriptorConfigMapName(jobName))
                        .descriptorMountPath(KubernetesSubmissionSpec.DEFAULT_DESCRIPTOR_MOUNT_PATH)
                        .memoryMiB(ClusterSubmissionParameters.parseMemoryMb(
                                ClusterSubmissionParameters.orDefault(params, "memory", "1g")))
                        .cpuMilli(ClusterSubmissionParameters.parseCpuMilli(
                                ClusterSubmissionParameters.orDefault(params, "cpu", "0.5")))
                        .serviceAccountName(stringParam(params, "serviceAccount"))
                        .imagePullSecrets(ClusterSubmissionParameters.splitCsv(
                                (String) params.get("imagePullSecrets")))
                        .envs(ClusterSubmissionParameters.stringMap(params.get("envs")))
                        .secretEnv(secretEnv)
                        .build())
                .build();
    }

    @Override
    public ClusterSubmission submit(ClusterSubmitRequest request) {
        KubernetesSubmissionSpec spec = validateAndExtractSpec(request);
        completionPollSeconds = spec.getCompletionPollSeconds();
        int maxAttempts = maxAttemptsOrDefault(request);
        createDescriptorConfigMapIfNeeded(spec);
        Job created;
        try {
            created = gateway().create(buildJob(request, spec, maxAttempts));
        } catch (RuntimeException exception) {
            // Job 没创建成功时 descriptor ConfigMap 已无用途；只清理本次真正创建的
            if (hasDescriptorData(spec)) {
                deleteDescriptorConfigMap(spec.getNamespace(), spec.getDescriptorConfigMapName());
            }
            throw exception;
        }
        String applicationId = spec.getNamespace() + "/" + created.getMetadata().getName();
        if (hasDescriptorData(spec) && !isBlank(spec.getDescriptorConfigMapName())) {
            descriptorConfigMaps.put(applicationId, spec.getDescriptorConfigMapName());
        }
        return ClusterSubmission.builder()
                .submissionId(request.getSubmissionId())
                .executionMode(ExecutionMode.KUBERNETES)
                .submittedAt(Instant.now())
                .clusterApplicationId(applicationId)
                .build();
    }

    @Override
    public ClusterApplicationResult awaitCompletion(ClusterSubmission submission, Duration timeout) {
        if (submission == null || submission.getClusterApplicationId() == null) {
            return null;
        }
        String[] namespaceAndJob = submission.getClusterApplicationId().split("/", 2);
        if (namespaceAndJob.length != 2) {
            return null;
        }
        long deadlineNanos = timeout == null ? Long.MAX_VALUE : System.nanoTime() + timeout.toNanos();
        try {
            while (System.nanoTime() < deadlineNanos) {
                String status;
                try {
                    status = gateway().jobCompletionStatus(namespaceAndJob[0], namespaceAndJob[1]).orElse(null);
                } catch (RuntimeException exception) {
                    // 5xx/网络抖动只是“暂时不知道状态”，不能因此 kill 仍在正常运行的 Job
                    log.warn("failed to poll kubernetes job {}/{}: {}",
                            namespaceAndJob[0], namespaceAndJob[1], exception.getMessage());
                    status = null;
                }
                if ("Complete".equals(status) || "Failed".equals(status)) {
                    // 与 YARN 的 AM diagnostics 对齐：成功时把 coordinator 打印的摘要 JSON
                    // 回传给服务端还原统计；失败时回传 Kubernetes 记录的失败原因和日志尾部，
                    // 否则服务端只能看到一句 "finished with FAILED"。
                    String logs = null;
                    try {
                        logs = gateway().jobLogs(namespaceAndJob[0], namespaceAndJob[1]).orElse(null);
                    } catch (RuntimeException exception) {
                        log.warn("failed to read logs for kubernetes job {}/{}: {}",
                                namespaceAndJob[0], namespaceAndJob[1], exception.getMessage());
                    }
                    String diagnostics;
                    if ("Complete".equals(status)) {
                        diagnostics = extractSummaryJson(logs);
                    } else {
                        String failureDetails = null;
                        try {
                            failureDetails = gateway().jobFailureDetails(
                                    namespaceAndJob[0], namespaceAndJob[1]).orElse(null);
                        } catch (RuntimeException exception) {
                            log.warn("failed to read failure details for kubernetes job {}/{}: {}",
                                    namespaceAndJob[0], namespaceAndJob[1], exception.getMessage());
                        }
                        diagnostics = failureDiagnostics(logs, failureDetails);
                    }
                    // 作业已终态，descriptor ConfigMap 不再被挂载，立即清理
                    deleteDescriptorConfigMap(namespaceAndJob[0],
                            descriptorConfigMaps.remove(submission.getClusterApplicationId()));
                    return new ClusterApplicationResult(submission.getClusterApplicationId(),
                            "Complete".equals(status) ? "SUCCEEDED" : "FAILED", null, diagnostics);
                }
                Thread.sleep(pollSleepMillis(deadlineNanos));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    /** 单次轮询不能睡过调用方给的 timeout，否则配置的超时会被轮询间隔绕过。 */
    private long pollSleepMillis(long deadlineNanos) {
        long pollMillis = completionPollSeconds > Long.MAX_VALUE / 1000L
                ? Long.MAX_VALUE : completionPollSeconds * 1000L;
        if (deadlineNanos == Long.MAX_VALUE) {
            return pollMillis;
        }
        long remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000L;
        return Math.max(1L, Math.min(pollMillis, remainingMillis));
    }

    /**
     * 从 Pod 日志里取最后一行带 {@code "submissionId"} 的 JSON 对象（coordinator 摘要），
     * 与服务端 {@code RunTaskHandler.parseClusterStatistics} 的输入格式保持一致。
     */
    static String extractSummaryJson(String logs) {
        if (logs == null || logs.isBlank()) {
            return null;
        }
        String[] lines = logs.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.startsWith("{") && line.endsWith("}") && line.contains("\"submissionId\"")) {
                return line.length() > 4000 ? line.substring(0, 4000) : line;
            }
        }
        return null;
    }

    /**
     * 失败诊断 = Kubernetes 记录的结构化原因 + coordinator 日志尾部。
     * 只截断末尾会丢掉堆栈根因，因此日志保留最后一段而不是开头。
     */
    static String failureDiagnostics(String logs, String jobDetails) {
        String details = jobDetails == null || jobDetails.isBlank() ? null : jobDetails.trim();
        if (details != null && details.length() > 1200) {
            details = details.substring(0, 1200) + "...";
        }
        String logTail = failureLogTail(logs);
        if (details == null) {
            return logTail;
        }
        if (logTail == null) {
            return details;
        }
        return details + "\n" + logTail;
    }

    /** 取最后一个 "Cluster comparison failed" 块；没有该标记时取末尾 40 行。 */
    static String failureLogTail(String logs) {
        if (logs == null || logs.isBlank()) {
            return null;
        }
        String text = logs.stripTrailing();
        int marker = text.lastIndexOf("Cluster comparison failed");
        String tail = marker >= 0 ? text.substring(marker).trim() : lastLines(text, 40);
        if (tail.isBlank()) {
            return null;
        }
        int max = 3000;
        return tail.length() <= max ? tail : "..." + tail.substring(tail.length() - max);
    }

    private static String lastLines(String text, int maxLines) {
        int index = text.length();
        int lines = 0;
        while (index > 0 && lines < maxLines) {
            index = text.lastIndexOf('\n', index - 1);
            if (index < 0) {
                index = 0;
                break;
            }
            lines++;
        }
        return text.substring(index).trim();
    }

    @Override
    public void killStale(ClusterSubmitRequest request) {
        // 与 submit 同一套 namespace/jobName（由 ClusterSubmitSupport 写入 spec），
        // 不在此处重新推导：推导规则两处各写一份必然漂移。
        KubernetesSubmissionSpec spec = request.getKubernetesSubmission();
        if (spec == null || spec.getJobName() == null || spec.getJobName().isBlank()) {
            throw new IllegalArgumentException(
                    "kubernetesSubmission.jobName is required to sweep stale applications");
        }
        String namespace = spec.getNamespace() != null && !spec.getNamespace().isBlank()
                ? spec.getNamespace()
                : KubernetesSubmissionSpec.DEFAULT_NAMESPACE;
        // jobName 可由定义 properties 自定义，无条件删除会允许调用方删掉同 namespace 内的任意 Job。
        // 但自定义名也必须能清扫：否则同名 Job 第二次提交必然 AlreadyExists 失败。
        // 因此按"前缀命中"或"Job 带本系统 application label"判定归属。
        if (!ownedByUs(namespace, spec)) {
            log.warn("skip stale sweep for job not created by consilens: {}", spec.getJobName());
            return;
        }
        // best-effort：删除即返回，不阻塞新提交（等待删除不计入运行超时窗口；
        // 且 fabric8 对 404 同样返回空结果，等待只会拖慢每次首次提交）。
        gateway().deleteJob(namespace, spec.getJobName());
        String configMapName = spec.getDescriptorConfigMapName();
        if (hasDescriptorData(spec) && !isBlank(configMapName)) {
            descriptorConfigMaps.values().removeIf(configMapName::equals);
            deleteDescriptorConfigMap(namespace, configMapName);
        }
    }

    private boolean ownedByUs(String namespace, KubernetesSubmissionSpec spec) {
        if (spec.getJobName().startsWith(DEFAULT_JOB_NAME_PREFIX)) {
            return true;
        }
        String expectedLabel = applicationLabel(spec);
        return gateway().jobLabels(namespace, spec.getJobName())
                .map(labels -> expectedLabel.equals(labels.get("app.kubernetes.io/name")))
                .orElse(false);
    }

    @Override
    public void kill(ClusterSubmission submission) {
        if (submission == null || submission.getClusterApplicationId() == null) {
            return;
        }
        String[] namespaceAndJob = submission.getClusterApplicationId().split("/", 2);
        if (namespaceAndJob.length == 2) {
            gateway().deleteJob(namespaceAndJob[0], namespaceAndJob[1]);
            // 只删除本次提交记录在案的 ConfigMap；远端 descriptor 从未创建过 ConfigMap
            String configMapName = descriptorConfigMaps.remove(submission.getClusterApplicationId());
            if (configMapName != null) {
                deleteDescriptorConfigMap(namespaceAndJob[0], configMapName);
            }
        }
    }

    @Override
    public void close() {
        if (gateway != null) {
            gateway.close();
        }
    }

    private KubernetesSubmissionSpec validateAndExtractSpec(ClusterSubmitRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Kubernetes submission request is required");
        }
        request.validate();
        if (request.getExecution().getExecutionMode() != ExecutionMode.KUBERNETES) {
            throw new IllegalArgumentException("Kubernetes submitter only supports KUBERNETES execution mode");
        }
        KubernetesSubmissionSpec spec = request.getKubernetesSubmission();
        if (spec == null) {
            throw new IllegalArgumentException("kubernetesSubmission is required for Kubernetes submission");
        }
        spec.validate();
        if (!SAFE_SUBMISSION_ID.matcher(request.getSubmissionId()).matches()) {
            throw new IllegalArgumentException("submissionId must match [A-Za-z0-9_-]+");
        }
        return spec;
    }

    private Job buildJob(ClusterSubmitRequest request, KubernetesSubmissionSpec spec, int maxAttempts) {
        Map<String, String> labels = new LinkedHashMap<>(spec.sanitizedLabels());
        labels.put("app.kubernetes.io/name", applicationLabel(spec));
        labels.put("app.kubernetes.io/instance", spec.getJobName());

        Container container = new ContainerBuilder()
                .withName(COORDINATOR_NAME)
                .withImage(spec.getImage())
                .withImagePullPolicy(spec.getImagePullPolicy())
                .withCommand(containerCommand(spec))
                .withArgs(commandArguments(request, spec))
                .withEnv(containerEnvironment(spec))
                .withVolumeMounts(descriptorVolumeMounts(spec))
                .withResources(new ResourceRequirementsBuilder()
                        .addToRequests("memory", new Quantity(spec.getMemoryMiB() + "Mi"))
                        .addToRequests("cpu", new Quantity(spec.getCpuMilli() + "m"))
                        .addToLimits("memory", new Quantity(spec.getMemoryMiB() + "Mi"))
                        .addToLimits("cpu", new Quantity(spec.getCpuMilli() + "m"))
                        .build())
                .build();

        PodSpecBuilder podSpec = new PodSpecBuilder()
                .withRestartPolicy("Never")
                .withVolumes(descriptorVolumes(spec))
                .withContainers(container);
        if (spec.getServiceAccountName() != null) {
            podSpec.withServiceAccountName(spec.getServiceAccountName());
        }
        if (!spec.sanitizedImagePullSecrets().isEmpty()) {
            podSpec.withImagePullSecrets(spec.sanitizedImagePullSecrets().stream()
                    .map(secretName -> new LocalObjectReference(secretName))
                    .collect(Collectors.toList()));
        }

        return new JobBuilder()
                .withNewMetadata()
                .withName(spec.getJobName())
                .withNamespace(spec.getNamespace())
                .withLabels(labels)
                .endMetadata()
                .withNewSpec()
                .withBackoffLimit(maxAttempts - 1)
                .withTemplate(new PodTemplateSpecBuilder()
                        .withNewMetadata()
                        .withLabels(labels)
                        .endMetadata()
                        .withSpec(podSpec.build())
                        .build())
                .endSpec()
                .build();
    }

    private List<String> commandArguments(ClusterSubmitRequest request, KubernetesSubmissionSpec spec) {
        return List.of(
                // 堆只占容器内存的 70%：剩下留给 metaspace/线程栈/直接缓冲区，否则容易 OOMKilled
                "-Xmx" + ClusterSubmissionParameters.coordinatorHeapMb(spec.getMemoryMiB()) + "m",
                "-cp",
                runtimeClasspath(spec),
                spec.getCoordinatorMainClass(),
                request.getSubmissionId(),
                descriptorPath(spec));
    }

    private String runtimeClasspath(KubernetesSubmissionSpec spec) {
        return isBlank(spec.getRuntimeClasspath())
                ? KubernetesSubmissionSpec.DEFAULT_RUNTIME_CLASSPATH
                : spec.getRuntimeClasspath();
    }

    private String containerCommand(KubernetesSubmissionSpec spec) {
        return isBlank(spec.getContainerCommand())
                ? KubernetesSubmissionSpec.DEFAULT_CONTAINER_COMMAND
                : spec.getContainerCommand();
    }

    private String applicationLabel(KubernetesSubmissionSpec spec) {
        return isBlank(spec.getApplicationLabel())
                ? KubernetesSubmissionSpec.DEFAULT_APPLICATION_LABEL
                : spec.getApplicationLabel();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String descriptorPath(KubernetesSubmissionSpec spec) {
        if (spec.getDescriptorData() == null || spec.getDescriptorData().isEmpty()) {
            return spec.getDescriptorUri();
        }
        String fileName = spec.getDescriptorData().keySet().iterator().next();
        return mountPath(spec) + "/" + fileName;
    }

    private String mountPath(KubernetesSubmissionSpec spec) {
        return isBlank(spec.getDescriptorMountPath())
                ? KubernetesSubmissionSpec.DEFAULT_DESCRIPTOR_MOUNT_PATH
                : spec.getDescriptorMountPath();
    }

    private List<VolumeMount> descriptorVolumeMounts(KubernetesSubmissionSpec spec) {
        if (spec.getDescriptorData() == null || spec.getDescriptorData().isEmpty()) {
            return List.of();
        }
        return List.of(new VolumeMountBuilder()
                .withName(DESCRIPTOR_VOLUME)
                .withMountPath(mountPath(spec))
                .withReadOnly(true)
                .build());
    }

    private List<Volume> descriptorVolumes(KubernetesSubmissionSpec spec) {
        if (spec.getDescriptorData() == null || spec.getDescriptorData().isEmpty()) {
            return List.of();
        }
        List<KeyToPathBuilder> items = spec.getDescriptorData().keySet().stream()
                .map(name -> new KeyToPathBuilder().withKey(name).withPath(name))
                .collect(Collectors.toList());
        return List.of(new VolumeBuilder()
                .withName(DESCRIPTOR_VOLUME)
                .withNewConfigMap()
                .withName(spec.getDescriptorConfigMapName())
                .withItems(items.stream().map(builder -> builder.build()).collect(Collectors.toList()))
                .endConfigMap()
                .build());
    }

    private void createDescriptorConfigMapIfNeeded(KubernetesSubmissionSpec spec) {
        if (!hasDescriptorData(spec)) {
            return;
        }
        ConfigMap configMap = new ConfigMapBuilder()
                .withNewMetadata()
                .withName(spec.getDescriptorConfigMapName())
                .withNamespace(spec.getNamespace())
                .endMetadata()
                .withData(spec.sanitizedDescriptorData())
                .build();
        gateway().createConfigMap(configMap);
    }

    private boolean hasDescriptorData(KubernetesSubmissionSpec spec) {
        return spec.getDescriptorData() != null && !spec.getDescriptorData().isEmpty();
    }

    private void deleteDescriptorConfigMap(String namespace, String configMapName) {
        if (isBlank(configMapName)) {
            return;
        }
        try {
            gateway().deleteConfigMap(namespace, configMapName);
        } catch (RuntimeException exception) {
            // 清理失败不影响任务结果，下次提交 createOrReplace 会覆盖同名 ConfigMap
            log.warn("failed to delete descriptor configmap {}/{}: {}",
                    namespace, configMapName, exception.getMessage());
        }
    }

    private List<EnvVar> containerEnvironment(KubernetesSubmissionSpec spec) {
        List<EnvVar> envVars = new ArrayList<>();
        spec.sanitizedEnvs().forEach((name, value) -> envVars.add(
                new EnvVarBuilder().withName(name).withValue(value).build()));
        spec.sanitizedSecretEnv().forEach((name, secretRef) -> envVars.add(
                secretEnvironment(name, secretRef)));
        return envVars;
    }

    private EnvVar secretEnvironment(String environmentName, KubernetesSecretKeyRef secretRef) {
        return new EnvVarBuilder()
                .withName(environmentName)
                .withNewValueFrom()
                .withNewSecretKeyRef(secretRef.getSecretKey(), secretRef.getSecretName(), false)
                .endValueFrom()
                .build();
    }

    private int maxAttemptsOrDefault(ClusterSubmitRequest request) {
        Integer maxAttempts = request.getExecution().getMaxAttempts();
        if (maxAttempts == null) {
            return 1;
        }
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive for Kubernetes submission");
        }
        return maxAttempts;
    }

    private String defaultJobName(String submissionId) {
        String suffix = submissionId.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]", "-")
                .replaceAll("^-+|-+$", "");
        if (suffix.isEmpty()) {
            suffix = "run";
        }
        return (DEFAULT_JOB_NAME_PREFIX + suffix)
                .substring(0, Math.min(52, DEFAULT_JOB_NAME_PREFIX.length() + suffix.length()))
                .replaceAll("-+$", "");
    }

    private String descriptorConfigMapName(String jobName) {
        return (jobName + "-descriptor")
                .substring(0, Math.min(63, jobName.length() + "-descriptor".length()))
                .replaceAll("-+$", "");
    }

    private Map<String, KubernetesSecretKeyRef> secretEnvsOf(Object raw) {
        if (!(raw instanceof Map)) {
            return Map.of();
        }
        Map<String, KubernetesSecretKeyRef> result = new LinkedHashMap<>();
        ((Map<?, ?>) raw).forEach((environmentName, reference) -> {
            if (!(reference instanceof Map)) {
                throw new IllegalArgumentException("secretEnv." + environmentName + " must be an object");
            }
            Map<?, ?> values = (Map<?, ?>) reference;
            result.put(String.valueOf(environmentName), KubernetesSecretKeyRef.builder()
                    .secretName(ClusterSubmissionParameters.stringValue(values.get("secretName")))
                    .secretKey(ClusterSubmissionParameters.stringValue(values.get("secretKey")))
                    .build());
        });
        return result;
    }

    private void requirePasswordSecret(String descriptor,
                                       Map<String, KubernetesSecretKeyRef> secretEnv,
                                       String environmentName) {
        if (descriptor != null && descriptor.contains("${env." + environmentName + "}")
                && !secretEnv.containsKey(environmentName)) {
            throw new IllegalArgumentException("secretEnv." + environmentName
                    + " is required when the Kubernetes descriptor contains a database password");
        }
    }

    private String stringParam(Map<String, Object> params, String key) {
        return ClusterSubmissionParameters.stringValue(params.get(key));
    }
}
