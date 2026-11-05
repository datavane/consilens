package com.consilens.cli.command;

import com.consilens.cluster.api.ClusterApplicationResult;
import com.consilens.cluster.api.ClusterComparisonDescription;
import com.consilens.cluster.api.ClusterExecutionDescription;
import com.consilens.cluster.api.ClusterSubmission;
import com.consilens.cluster.api.ClusterSubmitRequest;
import com.consilens.cluster.api.ClusterSubmitter;
import com.consilens.cluster.api.KubernetesSecretKeyRef;
import com.consilens.cluster.api.KubernetesSubmissionSpec;
import com.consilens.connector.api.planner.ExecutionMode;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Submits one comparison as a Kubernetes Job through the public cluster SPI,
 * adopting the spark-on-k8s / flink-on-k8s patterns that fit a comparison job:
 * the runtime ships inside {@code --image} (there is deliberately no local jar
 * upload side channel), the descriptor is passed positionally like a Spark
 * application resource and travels as a ConfigMap, container resources use the
 * ecosystem memory format ({@code --memory 1g}, {@code --cpu 0.5}), and the
 * client blocks until the Job completes. Defaults can be centralised through
 * {@code --properties-file}/{@code --conf} with {@code consilens.kubernetes.*}
 * keys, exactly like the YARN subcommand.
 */
@Command(
        name = "kubernetes",
        aliases = "k8s",
        description = "Submit a comparison to Kubernetes (consilens submit kubernetes [options] <descriptor>)",
        mixinStandardHelpOptions = true
)
public class SubmitKubernetesCommand implements Callable<Integer> {

    static final String CONF_PREFIX = "consilens.kubernetes.";

    private final Supplier<ClusterSubmitter> submitterFactory;
    private final Supplier<String> submissionIdFactory;

    @Option(names = "--image",
            description = "Container image containing the consilens coordinator runtime at /opt/consilens/runtime "
                    + "(or consilens.kubernetes.image in --properties-file)")
    private String image;

    @Option(names = "--descriptor-uri",
            description = "HTTP(S) URI of the redacted submission descriptor; alternative to passing the descriptor positionally")
    private String descriptorUri;

    @Option(names = "--descriptor-config-map",
            description = "ConfigMap name for a local descriptor (default: <job>-descriptor)")
    private String descriptorConfigMapName;

    @Option(names = "--namespace", description = "Kubernetes namespace (default: default)")
    private String namespace;

    @Option(names = "--name", description = "Job name (default: consilens-<submission id>)")
    private String jobName;

    @Option(names = "--memory", description = "Coordinator memory in spark format: 1g, 2048m (default: 1g)")
    private String memory;

    @Option(names = "--cpu", description = "Coordinator CPU cores, e.g. 0.5 or 1 (default: 0.5)")
    private String cpu;

    @Option(names = "--service-account", description = "Pod service account name")
    private String serviceAccountName;

    @Option(names = "--image-pull-policy", description = "Image pull policy: Always, IfNotPresent, or Never (default: IfNotPresent)")
    private String imagePullPolicy;

    @Option(names = "--image-pull-secret", description = "Image pull secret name; repeatable")
    private List<String> imagePullSecrets;

    @Option(names = "--secret-env", description = "Environment mapping in ENVIRONMENT=secret-name/secret-key form; repeatable")
    private Map<String, String> secretEnvironmentReferences = new LinkedHashMap<>();

    @Option(names = "--env", description = "Plain environment variable in KEY=value form; repeatable")
    private Map<String, String> environments = new LinkedHashMap<>();

    @Option(names = "--label", description = "Job label in key=value form; repeatable")
    private Map<String, String> labels = new LinkedHashMap<>();

    @Option(names = "--max-attempts", description = "Maximum total Job attempts (default: 1)")
    private Integer maximumAttempts;

    @Option(names = "--properties-file",
            description = "Properties file with consilens.kubernetes.* defaults; CLI flags override these values")
    private String propertiesFile;

    @Option(names = "--conf", description = "Override a single consilens.kubernetes.* property as key=value; repeatable")
    private Map<String, String> confOverrides = new LinkedHashMap<>();

    @Parameters(index = "0", arity = "0..1",
            description = "Descriptor file or HTTP(S) URI, the consilens equivalent of the spark application resource")
    private String descriptorParameter;

    /** resolveDescriptor() 的结果：位置参数可能是本地文件，--descriptor-uri 始终按远端处理。 */
    private boolean descriptorIsRemote;

    @Spec
    private CommandSpec commandSpec;

    public SubmitKubernetesCommand() {
        this(SubmitKubernetesCommand::createDefaultSubmitter, () -> UUID.randomUUID().toString());
    }

    SubmitKubernetesCommand(Supplier<ClusterSubmitter> submitterFactory,
                            Supplier<String> submissionIdFactory) {
        this.submitterFactory = submitterFactory;
        this.submissionIdFactory = submissionIdFactory;
    }

    @Override
    public Integer call() {
        ClusterSubmitter submitter = null;
        try {
            Map<String, String> settings = loadSettings();
            String descriptor = resolveDescriptor();
            requireValue(descriptor != null, "descriptor is required, like the spark application resource");
            // --image 与 properties-file 的 consilens.kubernetes.image 等价，CLI 优先
            String resolvedImage = firstNonBlank(image, settings.get("image"));
            requireValue(resolvedImage != null,
                    "image is required: pass --image or consilens.kubernetes.image in --properties-file");
            String submissionId = submissionIdFactory.get();
            int maxAttempts = integerSetting(maximumAttempts, settings.get("max-attempts"), 1);
            String resolvedJobName = firstNonBlank(jobName, settings.get("name"), "consilens-" + submissionId);
            String resolvedConfigMapName = descriptorConfigMapNameOrDefault(resolvedJobName);
            ClusterSubmitRequest submissionRequest = ClusterSubmitRequest.builder()
                    .submissionId(submissionId)
                    .comparison(ClusterComparisonDescription.builder()
                            .sourceConfigRef(descriptorReference(descriptor, resolvedConfigMapName))
                            .targetConfigRef(descriptorReference(descriptor, resolvedConfigMapName))
                            .build())
                    .execution(ClusterExecutionDescription.builder()
                            .executionMode(ExecutionMode.KUBERNETES)
                            .maxAttempts(maxAttempts)
                            .kubernetesNamespace(stringSetting(namespace, settings.get("namespace"),
                                    KubernetesSubmissionSpec.DEFAULT_NAMESPACE))
                            .kubernetesImage(resolvedImage)
                            .build())
                    .build();
            submissionRequest.setKubernetesSubmission(KubernetesSubmissionSpec.builder()
                    .namespace(stringSetting(namespace, settings.get("namespace"),
                            KubernetesSubmissionSpec.DEFAULT_NAMESPACE))
                    .jobName(resolvedJobName)
                    .image(resolvedImage)
                    // 位置参数为 HTTP(S) URI 时 descriptorData 为空，必须把它作为 descriptorUri，
                    // 否则校验报 "descriptor is required"（--descriptor-uri 才是显式远端引用）
                    .descriptorUri(descriptorIsRemote ? descriptor : descriptorUri)
                    .descriptorData(descriptorData(descriptor))
                    .descriptorConfigMapName(resolvedConfigMapName)
                    .descriptorMountPath("/opt/consilens/descriptor")
                    .memoryMiB(memorySetting(settings))
                    .cpuMilli(cpuSetting(settings))
                    .serviceAccountName(firstNonBlank(serviceAccountName, settings.get("serviceAccount")))
                    .imagePullPolicy(firstNonBlank(imagePullPolicy, settings.get("imagePullPolicy"), "IfNotPresent"))
                    .imagePullSecrets(listSetting(imagePullSecrets, settings.get("imagePullSecrets")))
                    .labels(labels)
                    .envs(environments)
                    .secretEnv(secretEnvironment())
                    .build());
            validateSubmissionRequest(submissionRequest, maxAttempts);
            submitter = submitterFactory.get();
            ClusterSubmission submission = submitter.submit(submissionRequest);
            commandSpec.commandLine().getOut().println(
                    "Kubernetes Job submitted: " + submission.getClusterApplicationId());
            return waitForCompletion(settings, submitter, submission);
        } catch (Exception e) {
            PrintWriter errorWriter = commandSpec.commandLine().getErr();
            if (e instanceof IllegalArgumentException) {
                // Validation failures carry our own static messages before any
                // credentials enter the flow.
                errorWriter.println("Kubernetes submission failed: " + e.getMessage());
                if (System.getenv("CONSILENS_DEBUG") != null) {
                    e.printStackTrace(errorWriter);
                }
            } else {
                // Runtime exception messages may embed connector credentials; only
                // the class name is echoed, the full stack needs CONSILENS_DEBUG.
                errorWriter.println("Kubernetes submission failed: " + e.getClass().getName());
                if (System.getenv("CONSILENS_DEBUG") != null) {
                    e.printStackTrace(errorWriter);
                }
            }
            return 1;
        } finally {
            closeQuietly(submitter);
        }
    }

    private Integer waitForCompletion(Map<String, String> settings,
                                      ClusterSubmitter submitter,
                                      ClusterSubmission submission) {
        if (!booleanSetting(settings.get("submit.waitAppCompletion"), true)) {
            return 0;
        }
        ClusterApplicationResult result = submitter.awaitCompletion(submission, null);
        if (result == null) {
            return 0;
        }
        PrintWriter out = commandSpec.commandLine().getOut();
        out.println("Kubernetes Job finished: " + result.getApplicationId()
                + " finalStatus=" + result.getFinalStatus());
        if (!result.succeeded()) {
            commandSpec.commandLine().getErr().println(
                    "Kubernetes Job did not succeed: finalStatus=" + result.getFinalStatus());
            return 1;
        }
        return 0;
    }

    private Map<String, String> loadSettings() throws java.io.IOException {
        Map<String, String> settings = new LinkedHashMap<>();
        if (propertiesFile != null) {
            java.util.Properties properties = new java.util.Properties();
            try (java.io.InputStream inputStream = Files.newInputStream(Path.of(propertiesFile))) {
                properties.load(inputStream);
            }
            properties.forEach((name, value) -> {
                String key = String.valueOf(name);
                if (key.startsWith(CONF_PREFIX)) {
                    settings.put(key.substring(CONF_PREFIX.length()), String.valueOf(value).trim());
                }
            });
        }
        confOverrides.forEach((key, value) -> {
            if (key != null && key.startsWith(CONF_PREFIX)) {
                settings.put(key.substring(CONF_PREFIX.length()), value == null ? null : value.trim());
            }
        });
        return settings;
    }

    /**
     * The positional descriptor doubles as the remote form: a value carrying a
     * URI scheme (for example {@code https://}) is used as-is, exactly like a
     * remote spark application resource.
     */
    private String resolveDescriptor() {
        if (descriptorParameter != null && descriptorUri != null) {
            throw new IllegalArgumentException("descriptor supplied both positionally and via --descriptor-uri");
        }
        if (descriptorParameter != null) {
            descriptorIsRemote = isUri(descriptorParameter);
            return descriptorParameter;
        }
        // --descriptor-uri 显式声明远端引用，交给 K8s spec 校验 scheme 是否合法。
        descriptorIsRemote = descriptorUri != null;
        return descriptorUri;
    }

    private String descriptorReference(String descriptor, String configMapName) {
        if (descriptorIsRemote) {
            return "descriptor:" + descriptor;
        }
        String fileName = Paths.get(descriptor).getFileName().toString();
        return "configmap:" + configMapName + ":" + fileName;
    }

    /** Kubernetes descriptor 只支持 HTTP(S) 远端；Windows 盘符等本地路径不能被误判成 URI。 */
    private boolean isUri(String value) {
        return value != null
                && (value.regionMatches(true, 0, "http://", 0, 7)
                || value.regionMatches(true, 0, "https://", 0, 8));
    }

    private Map<String, String> descriptorData(String descriptor) {
        if (descriptor == null || descriptorIsRemote) {
            return new LinkedHashMap<>();
        }
        Path path = Paths.get(descriptor).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("local descriptor is not a regular file: " + path);
        }
        String fileName = path.getFileName().toString().toLowerCase();
        if (!(fileName.endsWith(".yaml") || fileName.endsWith(".yml") || fileName.endsWith(".json"))) {
            throw new IllegalArgumentException("local descriptor must be a YAML or JSON file");
        }
        try {
            Map<String, String> data = new LinkedHashMap<>();
            data.put(path.getFileName().toString(), Files.readString(path));
            return data;
        } catch (Exception e) {
            throw new IllegalArgumentException("unable to read local descriptor: " + path, e);
        }
    }

    private String descriptorConfigMapNameOrDefault(String jobName) {
        if (descriptorConfigMapName != null && !descriptorConfigMapName.trim().isEmpty()) {
            return descriptorConfigMapName;
        }
        return jobName + "-descriptor";
    }

    private String stringSetting(String cliValue, String confValue, String fallback) {
        String value = firstNonBlank(cliValue, confValue);
        return value != null ? value : fallback;
    }

    private int memorySetting(Map<String, String> settings) {
        String value = firstNonBlank(memory, settings.get("memory"), "1g");
        return SubmitYarnCommand.parseSparkMemoryToMb(value);
    }

    private int cpuSetting(Map<String, String> settings) {
        String value = firstNonBlank(cpu, settings.get("cpu"), "0.5");
        double cores = Double.parseDouble(value.trim());
        if (cores <= 0) {
            throw new IllegalArgumentException("--cpu must be a positive core count, e.g. 0.5 or 1");
        }
        return (int) Math.round(cores * 1000);
    }

    private int integerSetting(Integer cliValue, String confValue, int fallback) {
        String value = cliValue != null ? String.valueOf(cliValue) : confValue;
        return value == null || value.isBlank() ? fallback : Integer.valueOf(value.trim());
    }

    private boolean booleanSetting(String confValue, boolean fallback) {
        return confValue == null || confValue.isBlank() ? fallback : Boolean.parseBoolean(confValue.trim());
    }

    private List<String> listSetting(List<String> cliValue, String confValue) {
        if (cliValue != null && !cliValue.isEmpty()) {
            return cliValue;
        }
        if (confValue == null || confValue.isBlank()) {
            return null;
        }
        return new ArrayList<>(Arrays.stream(confValue.split(",")).map(String::trim)
                .filter(v -> !v.isEmpty()).collect(Collectors.toList()));
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    private void requireValue(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private void validateSubmissionRequest(ClusterSubmitRequest submissionRequest, int maxAttempts) {
        submissionRequest.validate();
        submissionRequest.getKubernetesSubmission().validate();
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("max-attempts must be positive for Kubernetes submission");
        }
    }

    private Map<String, KubernetesSecretKeyRef> secretEnvironment() {
        Map<String, KubernetesSecretKeyRef> result = new LinkedHashMap<>();
        secretEnvironmentReferences.forEach((environmentName, reference) -> {
            int separator = reference == null ? -1 : reference.indexOf('/');
            if (separator <= 0 || separator != reference.lastIndexOf('/') || separator == reference.length() - 1) {
                throw new IllegalArgumentException("secret environment reference must be secret-name/secret-key");
            }
            result.put(environmentName, KubernetesSecretKeyRef.builder()
                    .secretName(reference.substring(0, separator))
                    .secretKey(reference.substring(separator + 1))
                    .build());
        });
        return result;
    }

    private static ClusterSubmitter createDefaultSubmitter() {
        try {
            Class<?> type = Class.forName("com.consilens.cluster.kubernetes.KubernetesClusterSubmitter");
            return (ClusterSubmitter) type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("Kubernetes client runtime is unavailable; add Fabric8 client libraries", e);
        }
    }

    private void closeQuietly(ClusterSubmitter submitter) {
        if (submitter instanceof AutoCloseable) {
            try {
                ((AutoCloseable) submitter).close();
            } catch (Exception ignored) {
                // Submission outcome is already reported; cleanup failure is not fatal.
            }
        }
    }
}
