package com.consilens.cluster.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.util.ArrayList;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Typed Kubernetes Job submission settings. It carries runtime references,
 * resource capabilities and optional descriptor content where secrets are
 * represented as environment placeholders, never as plaintext passwords.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KubernetesSubmissionSpec implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final Pattern DNS_LABEL = Pattern.compile("[a-z0-9]([-a-z0-9]*[a-z0-9])?");
    private static final Pattern JAVA_CLASS_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$.]*");
    private static final Pattern IMAGE_REFERENCE = Pattern.compile("\\S+");
    private static final Pattern LABEL_NAME = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9_.-]*[A-Za-z0-9])?");
    private static final Pattern LABEL_VALUE = Pattern.compile("([A-Za-z0-9]([A-Za-z0-9_.-]*[A-Za-z0-9])?)?");
    private static final Pattern DNS_SUBDOMAIN = Pattern.compile(
            "[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*");
    private static final Pattern ENVIRONMENT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern SECRET_KEY = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern DESCRIPTOR_FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Set<String> IMAGE_PULL_POLICIES = Set.of("Always", "IfNotPresent", "Never");

    /** 默认协调器运行时类路径，沿用镜像内 {@code /opt/consilens/runtime} 布局。 */
    public static final String DEFAULT_RUNTIME_CLASSPATH = "/opt/consilens/runtime/*";

    /** 默认 descriptor 挂载目录，沿用既有镜像约定。 */
    public static final String DEFAULT_DESCRIPTOR_MOUNT_PATH = "/opt/consilens/descriptor";

    /** 默认 Kubernetes namespace，与 kubectl 的默认行为一致。 */
    public static final String DEFAULT_NAMESPACE = "default";

    /** 默认完成状态轮询间隔（秒），与既有提交器实现保持一致。 */
    public static final long DEFAULT_COMPLETION_POLL_SECONDS = 3L;

    /** 默认 {@code app.kubernetes.io/name} 标签值。 */
    public static final String DEFAULT_APPLICATION_LABEL = "consilens";

    /** 默认容器启动命令，依赖镜像内 PATH 中的 JVM。 */
    public static final String DEFAULT_CONTAINER_COMMAND = "java";

    private String namespace;

    private String jobName;

    private String image;

    private String descriptorUri;

    private Map<String, String> descriptorData;

    private String descriptorConfigMapName;

    /**
     * descriptor 在容器内的挂载目录。为空时提交器回退到
     * {@link #DEFAULT_DESCRIPTOR_MOUNT_PATH}（沿用既有镜像约定）。
     */
    private String descriptorMountPath;

    /**
     * 协调器容器的运行时类路径。为空时回退到
     * {@link #DEFAULT_RUNTIME_CLASSPATH}（镜像内 {@code /opt/consilens/runtime}）。
     */
    @Builder.Default
    private String runtimeClasspath = DEFAULT_RUNTIME_CLASSPATH;

    /**
     * 容器启动命令（可执行文件名）。为空时回退到
     * {@link #DEFAULT_CONTAINER_COMMAND}（镜像 PATH 中的 {@code java}）。
     */
    @Builder.Default
    private String containerCommand = DEFAULT_CONTAINER_COMMAND;

    /**
     * 写入 {@code app.kubernetes.io/name} 标签的应用标识，默认
     * {@link #DEFAULT_APPLICATION_LABEL}；为空时提交器使用默认值。
     */
    @Builder.Default
    private String applicationLabel = DEFAULT_APPLICATION_LABEL;

    /**
     * 等待 Job 进入终态的轮询间隔（秒），默认
     * {@link #DEFAULT_COMPLETION_POLL_SECONDS}。必须为正数。
     */
    @Builder.Default
    private long completionPollSeconds = DEFAULT_COMPLETION_POLL_SECONDS;

    private String coordinatorMainClass;

    private Integer memoryMiB;

    private Integer cpuMilli;

    private String serviceAccountName;

    private String imagePullPolicy;

    private Map<String, String> labels;

    private Map<String, KubernetesSecretKeyRef> secretEnv;

    /**
     * Plain environment variables for the coordinator container, the
     * spark-on-k8s {@code spark.kubernetes.driver.env.[Name]} analog.
     */
    private Map<String, String> envs;

    /**
     * Image pull secret names attached to the Job pod, the
     * {@code spark.kubernetes.imagePullSecrets} analog.
     */
    private List<String> imagePullSecrets;

    public void validate() {
        requireDnsLabel("namespace", namespace);
        requireDnsLabel("jobName", jobName);
        if (isBlank(image) || !IMAGE_REFERENCE.matcher(image).matches()) {
            throw new IllegalArgumentException("image must be a non-blank container image reference");
        }
        validateDescriptorSource();
        if (isBlank(coordinatorMainClass)) {
            coordinatorMainClass = YarnSubmissionSpec.DEFAULT_AM_MAIN_CLASS;
        }
        if (!JAVA_CLASS_NAME.matcher(coordinatorMainClass).matches()) {
            throw new IllegalArgumentException("coordinatorMainClass must be a valid Java class name");
        }
        if (memoryMiB == null || memoryMiB <= 0) {
            throw new IllegalArgumentException("memoryMiB must be a positive integer for Kubernetes submission");
        }
        if (cpuMilli == null || cpuMilli <= 0) {
            throw new IllegalArgumentException("cpuMilli must be a positive integer for Kubernetes submission");
        }
        if (completionPollSeconds <= 0) {
            throw new IllegalArgumentException(
                    "completionPollSeconds must be positive for Kubernetes submission");
        }
        if (serviceAccountName != null) {
            requireDnsLabel("serviceAccountName", serviceAccountName);
        }
        if (imagePullPolicy != null && !IMAGE_PULL_POLICIES.contains(imagePullPolicy)) {
            throw new IllegalArgumentException("imagePullPolicy must be Always, IfNotPresent, or Never");
        }
        if (labels != null) {
            labels.forEach((key, value) -> {
                if (!isValidLabelKey(key)) {
                    throw new IllegalArgumentException("Kubernetes label keys must be valid qualified label names");
                }
                if (value == null || value.length() > 63 || !LABEL_VALUE.matcher(value).matches()) {
                    throw new IllegalArgumentException("Kubernetes label values must be valid label values");
                }
            });
        }
        if (secretEnv != null) {
            secretEnv.forEach((environmentName, secretRef) -> {
                if (environmentName == null || !ENVIRONMENT_NAME.matcher(environmentName).matches()) {
                    throw new IllegalArgumentException("Kubernetes secret environment names must be valid environment names");
                }
                if (secretRef == null) {
                    throw new IllegalArgumentException("Kubernetes secret environment reference is required");
                }
                requireDnsLabel("secretName", secretRef.getSecretName());
                if (isBlank(secretRef.getSecretKey()) || !SECRET_KEY.matcher(secretRef.getSecretKey()).matches()) {
                    throw new IllegalArgumentException("secretKey must contain only letters, digits, '.', '_' or '-'");
                }
            });
        }
        if (envs != null) {
            envs.forEach((environmentName, value) -> {
                if (environmentName == null || !ENVIRONMENT_NAME.matcher(environmentName).matches()) {
                    throw new IllegalArgumentException("Kubernetes environment names must be valid environment names");
                }
            });
        }
        if (imagePullSecrets != null) {
            imagePullSecrets.forEach(secretName -> requireDnsLabel("imagePullSecrets", secretName));
        }
    }

    public Map<String, String> sanitizedLabels() {
        return labels == null ? new LinkedHashMap<>() : new LinkedHashMap<>(labels);
    }

    /**
     * Fails fast on malformed YAML/JSON before a ConfigMap reaches the cluster.
     */
    private void requireParsableDescriptor(String name, String content) {
        String trimmed = content.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("descriptorData content is required");
        }
        try {
            if (name.toLowerCase(Locale.ROOT).endsWith(".json")) {
                new ObjectMapper().readTree(content);
            } else {
                new ObjectMapper(new YAMLFactory()).readTree(content);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("descriptorData must be valid YAML or JSON: " + name, e);
        }
    }

    public Map<String, KubernetesSecretKeyRef> sanitizedSecretEnv() {
        return secretEnv == null ? new LinkedHashMap<>() : new LinkedHashMap<>(secretEnv);
    }

    public Map<String, String> sanitizedEnvs() {
        return envs == null ? new LinkedHashMap<>() : new LinkedHashMap<>(envs);
    }

    public List<String> sanitizedImagePullSecrets() {
        return imagePullSecrets == null ? new ArrayList<>() : new ArrayList<>(imagePullSecrets);
    }

    public Map<String, String> sanitizedDescriptorData() {
        return descriptorData == null ? new LinkedHashMap<>() : new LinkedHashMap<>(descriptorData);
    }

    private void validateDescriptorSource() {
        boolean hasData = descriptorData != null && !descriptorData.isEmpty();
        if (hasData) {
            if (descriptorData.size() != 1) {
                throw new IllegalArgumentException("descriptorData must contain exactly one descriptor file");
            }
            if (isBlank(descriptorConfigMapName) || !DNS_LABEL.matcher(descriptorConfigMapName).matches()) {
                throw new IllegalArgumentException("descriptorConfigMapName must be a Kubernetes DNS label");
            }
            if (isBlank(descriptorMountPath)) {
                throw new IllegalArgumentException("descriptorMountPath is required for ConfigMap descriptor");
            }
            descriptorData.forEach((name, content) -> {
                if (isBlank(name) || !DESCRIPTOR_FILE_NAME.matcher(name).matches()) {
                    throw new IllegalArgumentException("descriptorData names must be simple file names");
                }
                if (content == null) {
                    throw new IllegalArgumentException("descriptorData content is required");
                }
                requireParsableDescriptor(name, content);
            });
        } else {
            requireHttpDescriptorUri(descriptorUri);
        }
    }

    private void requireDnsLabel(String name, String value) {
        if (isBlank(value) || value.length() > 63 || !DNS_LABEL.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a lowercase Kubernetes DNS label up to 63 characters");
        }
    }

    private void requireHttpDescriptorUri(String value) {
        if (isBlank(value)) {
            throw new IllegalArgumentException("descriptorUri is required for Kubernetes submission");
        }
        try {
            URI uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("descriptorUri must be an HTTP(S) secret-free URI");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("descriptorUri must be an HTTP(S) secret-free URI", e);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private boolean isValidLabelKey(String key) {
        if (key == null) {
            return false;
        }
        int separator = key.indexOf('/');
        if (separator < 0) {
            return isValidLabelName(key);
        }
        if (separator == 0 || separator != key.lastIndexOf('/') || separator == key.length() - 1) {
            return false;
        }
        String prefix = key.substring(0, separator);
        return prefix.length() <= 253 && DNS_SUBDOMAIN.matcher(prefix).matches()
                && isValidLabelName(key.substring(separator + 1));
    }

    private boolean isValidLabelName(String value) {
        return value.length() <= 63 && LABEL_NAME.matcher(value).matches();
    }
}
