package com.consilens.cluster.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Typed YARN submission settings. The payload is limited to references and resource
 * capabilities and never carries connector configuration or passwords.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class YarnSubmissionSpec implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final Pattern JAVA_CLASS_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$.]*");
    private static final Pattern YARN_QUEUE_NAME = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final Pattern SAFE_ALIAS = Pattern.compile("[A-Za-z0-9._-]+");

    /**
     * Coordinator launched inside the ApplicationMaster; an implementation
     * detail that is not user-selectable, so a blank value is defaulted here
     * rather than exposed as a CLI option.
     */
    public static final String DEFAULT_AM_MAIN_CLASS =
            "com.consilens.cluster.application.ClusterComparisonCoordinator";

    /** 默认 YARN 队列，与 YARN 集群的 {@code default} 队列保持一致。 */
    public static final String DEFAULT_QUEUE = "default";

    /** 默认完成状态轮询间隔（秒），与既有提交器实现保持一致。 */
    public static final long DEFAULT_COMPLETION_POLL_SECONDS = 3L;

    /** 运行时以单个 jar 分发时使用的本地资源名/类路径名。 */
    public static final String DEFAULT_RUNTIME_JAR_DESTINATION = "consilens-runtime.jar";

    /** 用于恢复清扫的确定性运行标签前缀。 */
    public static final String DEFAULT_RUN_TAG_PREFIX = "consilens-run-";

    /** 未显式指定 {@code amJavaHome} 时使用的 NodeManager 默认 JVM 目录。 */
    public static final String DEFAULT_AM_JAVA_HOME = "$JAVA_HOME";

    private String runtimeArchiveUri;

    private String descriptorUri;

    private String stagingUri;

    private String amMainClass;

    private String applicationName;

    /**
     * YARN 队列；为空时提交器回退到 {@link #DEFAULT_QUEUE}。
     */
    private String queue;

    /**
     * 等待应用进入终态的轮询间隔（秒），默认
     * {@link #DEFAULT_COMPLETION_POLL_SECONDS}。必须为正数。
     */
    @Builder.Default
    private long completionPollSeconds = DEFAULT_COMPLETION_POLL_SECONDS;

    /**
     * 运行时以 jar 分发时在 AM 工作目录中的名称，默认
     * {@link #DEFAULT_RUNTIME_JAR_DESTINATION}；为空时提交器使用默认值。
     */
    @Builder.Default
    private String runtimeJarDestination = DEFAULT_RUNTIME_JAR_DESTINATION;

    /**
     * 恢复清扫所用运行标签的前缀，默认 {@link #DEFAULT_RUN_TAG_PREFIX}；
     * 为空时提交器使用默认值。完整标签为前缀加 submissionId。
     */
    @Builder.Default
    private String runTagPrefix = DEFAULT_RUN_TAG_PREFIX;

    /**
     * Optional JVM home used to launch the ApplicationMaster (shell-safe path).
     * YARN containers inherit the NodeManager's JAVA_HOME, which may be older
     * than the build target; clusters can point this at an alternate JDK.
     * 为空时回退到 {@link #defaultAmJavaHome}。
     */
    private String amJavaHome;

    /**
     * 未显式给出 {@link #amJavaHome} 时使用的 JVM 目录，默认
     * {@link #DEFAULT_AM_JAVA_HOME}（沿用 NodeManager 的 JAVA_HOME）。
     * 可配置为 {@code $JAVA_HOME} 或镜像内的绝对路径。
     */
    @Builder.Default
    private String defaultAmJavaHome = DEFAULT_AM_JAVA_HOME;

    private Integer amMemoryMb;

    private Integer amVCores;

    private List<String> tags;

    /**
     * Extra files localized into the AM container working directory and
     * reachable by their file name, mirroring spark-submit {@code --files}.
     * Entries accept an optional {@code #alias} suffix; remote URIs are used
     * in place, local paths are staged first.
     */
    private List<String> files;

    /**
     * Extra jars localized into the AM container and appended to the AM
     * classpath after the runtime archive, mirroring spark-submit
     * {@code --jars}.
     */
    private List<String> jars;

    public void validate() {
        requireArtifactUri("runtimeArchiveUri", runtimeArchiveUri);
        requireArtifactUri("descriptorUri", descriptorUri);
        requireDescriptorFormat(descriptorUri);
        if (stagingUri != null) {
            requireRemoteUri("stagingUri", stagingUri);
        }
        validateReferences(files, "files");
        validateReferences(jars, "jars");
        if (isBlank(amMainClass)) {
            amMainClass = DEFAULT_AM_MAIN_CLASS;
        }
        if (!JAVA_CLASS_NAME.matcher(amMainClass).matches()) {
            throw new IllegalArgumentException("amMainClass must be a valid Java class name");
        }
        if (isBlank(applicationName)) {
            throw new IllegalArgumentException("applicationName is required for YARN submission");
        }
        if (queue != null && !YARN_QUEUE_NAME.matcher(queue).matches()) {
            throw new IllegalArgumentException("queue must match [A-Za-z0-9_.-]+ when provided");
        }
        if (amMemoryMb == null || amMemoryMb <= 0) {
            throw new IllegalArgumentException("amMemoryMb must be a positive integer for YARN submission");
        }
        if (amVCores == null || amVCores <= 0) {
            throw new IllegalArgumentException("amVCores must be a positive integer for YARN submission");
        }
        if (completionPollSeconds <= 0) {
            throw new IllegalArgumentException("completionPollSeconds must be positive for YARN submission");
        }
        if (isBlank(runtimeJarDestination)) {
            runtimeJarDestination = DEFAULT_RUNTIME_JAR_DESTINATION;
        }
        // 该名字会进入 AM 启动命令的 -cp 参数（双引号内），必须限制字符集
        if (!SAFE_ALIAS.matcher(runtimeJarDestination).matches()) {
            throw new IllegalArgumentException(
                    "runtimeJarDestination must match [A-Za-z0-9._-]+: " + runtimeJarDestination);
        }
        if (tags != null) {
            for (String tag : tags) {
                if (tag == null || tag.trim().isEmpty()) {
                    throw new IllegalArgumentException("YARN application tags must not be empty");
                }
            }
        }
    }

    public List<String> sanitizedTags() {
        if (tags == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(tags);
    }

    /**
     * URI part of a {@code uri#alias} reference (or the entry itself).
     */
    public static String referenceUri(String reference) {
        int separator = reference.indexOf('#');
        return separator >= 0 ? reference.substring(0, separator) : reference;
    }

    /**
     * Alias part of a {@code uri#alias} reference, or null when absent.
     */
    public static String referenceAlias(String reference) {
        int separator = reference.indexOf('#');
        return separator >= 0 ? reference.substring(separator + 1) : null;
    }

    private void validateReferences(List<String> references, String name) {
        if (references == null) {
            return;
        }
        for (String reference : references) {
            if (isBlank(reference)) {
                throw new IllegalArgumentException(name + " entries must not be blank");
            }
            String alias = referenceAlias(reference);
            if (alias != null && isBlank(alias)) {
                throw new IllegalArgumentException(name + " entry has a blank alias: " + reference);
            }
            // alias 会进入 AM 启动命令的 -cp 参数：限制字符集，避免 shell 注入
            if (alias != null && !SAFE_ALIAS.matcher(alias).matches()) {
                throw new IllegalArgumentException(
                        name + " alias must match [A-Za-z0-9._-]+: " + alias);
            }
            String uriPart = referenceUri(reference);
            URI uri;
            try {
                uri = URI.create(uriPart);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(name + " entry is not a valid URI or local path: " + uriPart, e);
            }
            if (uri.getUserInfo() != null || uri.getQuery() != null) {
                throw new IllegalArgumentException(name + " must not contain credentials or query parameters");
            }
            if (!SubmissionReference.isLocal(uri) && !uri.isAbsolute()) {
                throw new IllegalArgumentException(name + " entry must be a local path or absolute URI: " + uriPart);
            }
            String destinationName = alias != null ? alias : baseName(uriPart);
            if (isBlank(destinationName)) {
                throw new IllegalArgumentException(name + " entry has no file name: " + uriPart);
            }
            // 派生文件名同样会进入 -cp 双引号参数；URI.getPath() 会解码 %22/%3B 等，
            // 只校验显式 alias 不够，必须对最终落盘名统一限制字符集。
            if (!SAFE_ALIAS.matcher(destinationName).matches()) {
                throw new IllegalArgumentException(
                        name + " destination name must match [A-Za-z0-9._-]+: " + destinationName);
            }
            if (RESERVED_DESTINATION_NAMES.contains(destinationName) || destinationName.startsWith("submission-descriptor.")) {
                throw new IllegalArgumentException(name + " entry overlaps a reserved localization name: " + destinationName);
            }
        }
    }

    private String baseName(String uriPart) {
        String path = URI.create(uriPart).getPath();
        if (path == null) {
            return "";
        }
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static final java.util.Set<String> RESERVED_DESTINATION_NAMES = java.util.Set.of(
            "consilens-runtime", "consilens-runtime.jar", "submission-secrets.properties", "hadoop-conf");

    private void requireArtifactUri(String name, String value) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(name + " is required for YARN submission");
        }
        try {
            URI uri = URI.create(value);
            if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException(name + " must not contain credentials, query parameters, or fragments");
            }
            if (SubmissionReference.isLocal(uri)) {
                // A missing stagingUri is acceptable here: the submitter falls
                // back to the user's HDFS home staging directory, the same way
                // spark-submit stages under spark.yarn.stagingDir by default.
            } else if (!uri.isAbsolute()) {
                throw new IllegalArgumentException(name + " must be an absolute URI or a local path");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(name + " must be an absolute URI or a local path", e);
        }
    }

    private void requireRemoteUri(String name, String value) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(name + " is required for YARN submission");
        }
        try {
            URI uri = URI.create(value);
            if (!uri.isAbsolute() || SubmissionReference.isLocal(uri)) {
                throw new IllegalArgumentException(name + " must be an absolute remote URI");
            }
            if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException(name + " must not contain credentials, query parameters, or fragments");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(name + " must be an absolute remote URI", e);
        }
    }

    private void requireDescriptorFormat(String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        String path = URI.create(value).getPath();
        String lowerCasePath = path == null ? "" : path.toLowerCase(Locale.ROOT);
        if (!(lowerCasePath.endsWith(".yaml") || lowerCasePath.endsWith(".yml") || lowerCasePath.endsWith(".json"))) {
            throw new IllegalArgumentException("descriptorUri must reference a YAML or JSON descriptor");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
