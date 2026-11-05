package com.consilens.cluster.yarn;

import com.consilens.cluster.api.ClusterApplicationResult;
import com.consilens.cluster.api.ClusterComparisonDescription;
import com.consilens.cluster.api.ClusterExecutionDescription;
import com.consilens.cluster.api.ClusterSubmission;
import com.consilens.cluster.api.ClusterSubmissionContext;
import com.consilens.cluster.api.ClusterSubmissionParameters;
import com.consilens.cluster.api.ClusterSubmitRequest;
import com.consilens.cluster.api.ClusterSubmitter;
import com.consilens.cluster.api.SubmissionReference;
import com.consilens.cluster.api.YarnSubmissionSpec;
import com.consilens.cluster.yarn.gateway.HadoopYarnSubmissionGateway;
import com.consilens.cluster.yarn.gateway.YarnSubmissionGateway;
import com.consilens.connector.api.planner.ExecutionMode;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationSubmissionContext;
import org.apache.hadoop.yarn.api.records.ContainerLaunchContext;
import org.apache.hadoop.yarn.api.records.LocalResource;
import org.apache.hadoop.yarn.api.records.LocalResourceType;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * YARN implementation of the cluster submission SPI, modelled on Spark's yarn
 * {@code Client}: the client-side Hadoop configuration directory is localized
 * into the AM container (the {@code __spark_conf__} pattern), artifacts are
 * staged under a per-application staging directory ({@code <staging>/<appId>/}),
 * and the AM command launches a fixed ApplicationMaster wrapper —
 * {@link com.consilens.cluster.application.ConsilensApplicationMaster} — with
 * the coordinator class supplied as an argument, mirroring how
 * {@code ApplicationMaster --class <userClass>} wraps the Spark driver.
 * Only the runtime archive and the redacted descriptor URI are exposed as
 * local resources; CLI YAML content is never uploaded.
 */
public class YarnClusterSubmitter implements ClusterSubmitter, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(YarnClusterSubmitter.class);

    static final String AM_MAIN_CLASS = "com.consilens.cluster.application.ConsilensApplicationMaster";
    private static final String RUNTIME_ARCHIVE_DESTINATION = "consilens-runtime";
    private static final String DESCRIPTOR_DESTINATION_PREFIX = "submission-descriptor";
    private static final String HADOOP_CONF_DESTINATION_PREFIX = "hadoop-conf/";
    private static final Pattern SAFE_SUBMISSION_ID = Pattern.compile("[A-Za-z0-9_-]+");

    /**
     * Gateway creation is deferred until the first real submission so that
     * ServiceLoader discovery (which instantiates every provider to read its
     * platform) does not start a YARN client for an unused backend.
     */
    private final Supplier<YarnSubmissionGateway> gatewayFactory;
    private final Path hadoopConfDirectory;
    private YarnSubmissionGateway gateway;

    /**
     * Poll interval captured from the submitting spec; the interface's
     * {@link #awaitCompletion} has no request access, so the value is remembered
     * from the latest {@link #submit} call. Defaults to the historical 3 seconds.
     */
    private volatile long completionPollSeconds = YarnSubmissionSpec.DEFAULT_COMPLETION_POLL_SECONDS;

    public YarnClusterSubmitter() {
        this(YarnClusterSubmitter::defaultGateway, null);
    }

    public YarnClusterSubmitter(YarnSubmissionGateway gateway) {
        this(gateway, null);
    }

    YarnClusterSubmitter(YarnSubmissionGateway gateway, Path hadoopConfDirectory) {
        this(supplierOf(gateway), hadoopConfDirectory);
    }

    private YarnClusterSubmitter(Supplier<YarnSubmissionGateway> gatewayFactory,
                                 Path hadoopConfDirectory) {
        this.gatewayFactory = Objects.requireNonNull(gatewayFactory, "gatewayFactory");
        this.hadoopConfDirectory = hadoopConfDirectory;
    }

    private static YarnSubmissionGateway defaultGateway() {
        return new HadoopYarnSubmissionGateway(new YarnConfiguration());
    }

    private static Supplier<YarnSubmissionGateway> supplierOf(YarnSubmissionGateway gateway) {
        Objects.requireNonNull(gateway, "gateway");
        return () -> gateway;
    }

    private YarnSubmissionGateway gateway() {
        if (gateway == null) {
            gateway = Objects.requireNonNull(gatewayFactory.get(), "gateway");
        }
        return gateway;
    }

    @Override
    public String platform() {
        return "yarn";
    }

    @Override
    public boolean requiresDescriptorFile() {
        return true;
    }

    @Override
    public ClusterSubmitRequest buildRequest(ClusterSubmissionContext context) {
        Path descriptorFile = context.getDescriptorFile();
        if (descriptorFile == null) {
            throw new IllegalArgumentException("descriptorFile is required for YARN submission");
        }
        Map<String, Object> params = context.getProperties();
        String submissionId = context.getSubmissionId();
        String descriptorUri = descriptorFile.toAbsolutePath().toString();
        return ClusterSubmitRequest.builder()
                .submissionId(submissionId)
                .comparison(ClusterComparisonDescription.builder()
                        .sourceConfigRef("descriptor:" + descriptorUri)
                        .targetConfigRef("descriptor:" + descriptorUri)
                        .build())
                .execution(ClusterExecutionDescription.builder()
                        .executionMode(ExecutionMode.YARN)
                        .maxAttempts(ClusterSubmissionParameters.positiveInteger(
                                params.get("maxAppAttempts"), "maxAppAttempts"))
                        .build())
                .yarnSubmission(YarnSubmissionSpec.builder()
                        .runtimeArchiveUri(ClusterSubmissionParameters.required(
                                params, "archive", "yarn.archive"))
                        .descriptorUri(descriptorUri)
                        .stagingUri((String) params.get("stagingDir"))
                        .applicationName(submissionId)
                        .queue((String) params.get("queue"))
                        .amJavaHome((String) params.get("amJavaHome"))
                        .amMemoryMb(ClusterSubmissionParameters.parseMemoryMb(
                                ClusterSubmissionParameters.orDefault(params, "amMemory", "1g")))
                        .amVCores(ClusterSubmissionParameters.intOrDefault(params, "amVCores", 1))
                        .tags(ClusterSubmissionParameters.splitCsv((String) params.get("tags")))
                        .files(ClusterSubmissionParameters.splitCsv((String) params.get("files")))
                        .jars(ClusterSubmissionParameters.splitCsv((String) params.get("jars")))
                        .build())
                .build();
    }

    @Override
    public ClusterSubmission submit(ClusterSubmitRequest request) {
        YarnSubmissionSpec spec = validateAndExtractSpec(request);
        completionPollSeconds = spec.getCompletionPollSeconds();
        appendRunTag(spec, request.getSubmissionId());
        int maxAttempts = maxAttemptsOrDefault(request);
        String applicationStagingDir = applicationStagingDir(request.getSubmissionId(), spec);
        try {
            YarnSubmissionSpec resolvedSpec = StageResolver.resolve(spec, applicationStagingDir, gateway());
            ApplicationSubmissionContext context = gateway().createSubmissionContext();
            mapSubmissionContext(context, request, resolvedSpec, maxAttempts, applicationStagingDir);
            ApplicationId applicationId = gateway().submit(context);
            return ClusterSubmission.builder()
                    .submissionId(request.getSubmissionId())
                    .executionMode(ExecutionMode.YARN)
                    .submittedAt(Instant.now())
                    .clusterApplicationId(applicationId.toString())
                    .build();
        } catch (RuntimeException exception) {
            // RM 拒绝/网络失败时 AM 不会启动，没有人会清理已写入的 staging（含明文密码 descriptor）
            if (applicationStagingDir != null) {
                try {
                    gateway().deleteStagingDirectory(URI.create(applicationStagingDir));
                } catch (RuntimeException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            throw exception;
        }
    }

    @Override
    public ClusterApplicationResult awaitCompletion(ClusterSubmission submission, Duration timeout) {
        if (submission == null || submission.getClusterApplicationId() == null) {
            return null;
        }
        String applicationId = submission.getClusterApplicationId();
        long deadlineNanos = timeout == null ? Long.MAX_VALUE : System.nanoTime() + timeout.toNanos();
        try {
            while (System.nanoTime() < deadlineNanos) {
                // RM/网络抖动只是“暂时不知道状态”，不能因此让上层 kill 仍在运行的
                // application（K8s 提交器同样按此处理）。
                String state = bestEffort("application state for " + applicationId,
                        () -> gateway().applicationState(applicationId).orElse(null), null);
                if ("FINISHED".equals(state) || "FAILED".equals(state) || "KILLED".equals(state)) {
                    String finalStatus = bestEffort("final status for " + applicationId,
                            () -> gateway().applicationFinalStatus(applicationId).orElse(state), state);
                    String trackingUrl = bestEffort("tracking URL for " + applicationId,
                            () -> gateway().trackingUrl(applicationId).orElse(null), null);
                    String diagnostics = bestEffort("diagnostics for " + applicationId,
                            () -> gateway().applicationDiagnostics(applicationId).orElse(null), null);
                    return new ClusterApplicationResult(applicationId, finalStatus,
                            trackingUrl, diagnostics);
                }
                Thread.sleep(pollSleepMillis(deadlineNanos));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    /**
     * 单次轮询不能睡过调用方给的 timeout：否则配置了很长的 completionPollSeconds
     * 时会绕过上层运行超时，任务迟迟不返回。
     */
    private long pollSleepMillis(long deadlineNanos) {
        long pollMillis = completionPollSeconds > Long.MAX_VALUE / 1000L
                ? Long.MAX_VALUE : completionPollSeconds * 1000L;
        if (deadlineNanos == Long.MAX_VALUE) {
            return pollMillis;
        }
        long remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000L;
        return Math.max(1L, Math.min(pollMillis, remainingMillis));
    }

    private <T> T bestEffort(String description, Supplier<T> query, T fallback) {
        try {
            return query.get();
        } catch (RuntimeException exception) {
            log.warn("failed to query {}: {}", description, exception.getMessage());
            return fallback;
        }
    }

    @Override
    public void kill(ClusterSubmission submission) {
        if (submission != null && submission.getClusterApplicationId() != null) {
            gateway().kill(submission.getClusterApplicationId());
        }
    }

    @Override
    public void killStale(ClusterSubmitRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("YARN stale-kill request is required");
        }
        String submissionId = request.getSubmissionId();
        if (submissionId == null || submissionId.trim().isEmpty()) {
            // 空 submissionId 会拼出 consilens-run-null 这样的标签，误杀其它运行
            throw new IllegalArgumentException(
                    "submissionId is required to derive the YARN stale-kill run tag");
        }
        YarnSubmissionSpec spec = request.getYarnSubmission();
        String runTag = spec == null ? staleTag(submissionId) : runTag(spec, submissionId);
        gateway().killByTag(runTag);
    }

    /**
     * Deterministic run tag carrying the submissionId, letting a recovery
     * restart sweep stale applications of the same logical run.
     */
    static String staleTag(String submissionId) {
        return YarnSubmissionSpec.DEFAULT_RUN_TAG_PREFIX + submissionId;
    }

    private void appendRunTag(YarnSubmissionSpec spec, String submissionId) {
        List<String> tags = new ArrayList<>(spec.sanitizedTags());
        String runTag = runTag(spec, submissionId);
        if (!tags.contains(runTag)) {
            tags.add(runTag);
        }
        spec.setTags(tags);
    }

    private String runTag(YarnSubmissionSpec spec, String submissionId) {
        return runTagPrefix(spec) + submissionId;
    }

    private String runTagPrefix(YarnSubmissionSpec spec) {
        return isBlank(spec.getRunTagPrefix())
                ? YarnSubmissionSpec.DEFAULT_RUN_TAG_PREFIX
                : spec.getRunTagPrefix();
    }

    @Override
    public void close() {
        if (gateway != null) {
            gateway.close();
        }
    }

    private YarnSubmissionSpec validateAndExtractSpec(ClusterSubmitRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("YARN submission request is required");
        }
        request.validate();
        if (request.getExecution().getExecutionMode() != ExecutionMode.YARN) {
            throw new IllegalArgumentException("Yarn submitter only supports YARN execution mode");
        }
        YarnSubmissionSpec spec = request.getYarnSubmission();
        if (spec == null) {
            throw new IllegalArgumentException("yarnSubmission is required for YARN submission");
        }
        applyDefaultStaging(spec);
        spec.validate();
        if (!SAFE_SUBMISSION_ID.matcher(request.getSubmissionId()).matches()) {
            throw new IllegalArgumentException("submissionId must match [A-Za-z0-9_-]+");
        }
        return spec;
    }

    private void mapSubmissionContext(ApplicationSubmissionContext context,
                                      ClusterSubmitRequest request,
                                      YarnSubmissionSpec spec,
                                      int maxAttempts,
                                      String applicationStagingDir) {
        context.setApplicationName(spec.getApplicationName());
        context.setQueue(queueOrDefault(spec));
        context.setResource(Resource.newInstance(spec.getAmMemoryMb(), spec.getAmVCores()));
        if (maxAttempts > 0) {
            context.setMaxAppAttempts(maxAttempts);
        }
        // -1 keeps the cluster-wide RM default (spark.yarn.maxAppAttempts behaviour).
        context.setApplicationTags(Set.copyOf(spec.sanitizedTags()));
        context.setAMContainerSpec(buildContainerLaunchContext(request, spec, applicationStagingDir));
    }

    private ContainerLaunchContext buildContainerLaunchContext(ClusterSubmitRequest request,
                                                              YarnSubmissionSpec spec,
                                                              String applicationStagingDir) {
        Map<String, LocalResource> localResources = new LinkedHashMap<>();
        String runtimeDestination = runtimeDestination(spec);
        localResources.put(runtimeDestination, gateway().createLocalResource(
                URI.create(spec.getRuntimeArchiveUri()), runtimeLocalResourceType(spec.getRuntimeArchiveUri())));
        String descriptorDestination = descriptorDestination(spec);
        localResources.put(descriptorDestination, gateway().createLocalResource(
                URI.create(spec.getDescriptorUri()), LocalResourceType.FILE));
        localizeReferences(localResources, spec.getFiles());
        localizeReferences(localResources, spec.getJars());
        localizeHadoopConfiguration(localResources, applicationStagingDir);
        List<String> commands = List.of(buildAmCommand(request, spec, descriptorDestination, applicationStagingDir));
        return ContainerLaunchContext.newInstance(localResources, null, commands, null, null, null);
    }

    /**
     * Localizes the client-side Hadoop configuration into the AM container so it
     * sees the same ResourceManager endpoints as the submitter, exactly like
     * Spark ships {@code HADOOP_CONF_DIR} as the {@code __spark_conf__} resource.
     */
    private void localizeHadoopConfiguration(Map<String, LocalResource> localResources, String applicationStagingDir) {
        Path confDirectory = resolveHadoopConfDirectory();
        if (confDirectory == null) {
            throw new IllegalStateException("Hadoop configuration directory is required for YARN submission; "
                    + "set HADOOP_CONF_DIR or put yarn-site.xml on the classpath");
        }
        File[] confFiles = confDirectory.toFile().listFiles(File::isFile);
        if (confFiles == null || confFiles.length == 0) {
            throw new IllegalStateException("Hadoop configuration directory contains no files: " + confDirectory);
        }
        for (File confFile : confFiles) {
            // conf 文件优先经 staging 分发：file:// 本地资源只在提交节点存在，
            // 其他 NodeManager 节点无法读取（多节点集群会直接 FileNotFoundException）。
            // HADOOP_CONF_DIR 的文件恒为本地文件，直接 stage 即可。
            // 无 staging 目录（全远端产物且未解析到默认 staging）时退回本地资源：
            // 单节点可用，多节点需要在参数里给出 stagingDir。
            if (applicationStagingDir == null) {
                log.warn("No staging directory resolved; shipping {} as a local resource. "
                        + "Multi-node clusters need a stagingDir to read Hadoop configuration "
                        + "on every NodeManager.", confFile);
                localResources.put(HADOOP_CONF_DESTINATION_PREFIX + confFile.getName(),
                        gateway().createLocalResource(confFile.toURI(), LocalResourceType.FILE));
                continue;
            }
            URI staged = gateway().stageLocalFile(confFile.toURI(), URI.create(applicationStagingDir));
            localResources.put(HADOOP_CONF_DESTINATION_PREFIX + confFile.getName(), gateway().createLocalResource(
                    staged, LocalResourceType.FILE));
        }
    }

    private Path resolveHadoopConfDirectory() {
        if (hadoopConfDirectory != null) {
            return hadoopConfDirectory;
        }
        String configuredDirectory = System.getenv("HADOOP_CONF_DIR");
        if (configuredDirectory != null && !configuredDirectory.trim().isEmpty()) {
            return Path.of(configuredDirectory.trim());
        }
        URL yarnSite = Thread.currentThread().getContextClassLoader().getResource("yarn-site.xml");
        if (yarnSite != null && "file".equals(yarnSite.getProtocol())) {
            return Path.of(URI.create(yarnSite.toString()).getPath()).getParent();
        }
        return null;
    }

    private static final String JAVA_HOME_PATTERN = "[A-Za-z0-9_./-]+";

    /**
     * ApplicationMaster JVM home: an explicit {@code amJavaHome} submission
     * property (validated, shell-safe) or the NodeManager default JAVA_HOME.
     */
    private String amJavaHomeOrDefault(YarnSubmissionSpec spec) {
        String amJavaHome = spec.getAmJavaHome();
        if (amJavaHome == null || amJavaHome.isBlank()) {
            return defaultAmJavaHomeOrDefault(spec);
        }
        if (!amJavaHome.matches(JAVA_HOME_PATTERN) || amJavaHome.contains("..")) {
            throw new IllegalArgumentException("amJavaHome must be an absolute path without shell metacharacters");
        }
        return amJavaHome;
    }

    private String defaultAmJavaHomeOrDefault(YarnSubmissionSpec spec) {
        String defaultAmJavaHome = spec.getDefaultAmJavaHome();
        if (isBlank(defaultAmJavaHome)) {
            return YarnSubmissionSpec.DEFAULT_AM_JAVA_HOME;
        }
        if (!YarnSubmissionSpec.DEFAULT_AM_JAVA_HOME.equals(defaultAmJavaHome)
                && (!defaultAmJavaHome.matches(JAVA_HOME_PATTERN) || defaultAmJavaHome.contains(".."))) {
            throw new IllegalArgumentException(
                    "defaultAmJavaHome must be $JAVA_HOME or an absolute path without shell metacharacters");
        }
        return defaultAmJavaHome;
    }

    private String buildAmCommand(ClusterSubmitRequest request,
                                  YarnSubmissionSpec spec,
                                  String descriptorDestination,
                                  String applicationStagingDir) {
        String javaHome = amJavaHomeOrDefault(spec);
        // 堆只占容器内存的 70%：YARN 容器上限同样包含 metaspace/线程栈等非堆开销
        return javaHome + "/bin/java -server -Xmx"
                + com.consilens.cluster.api.ClusterSubmissionParameters
                        .coordinatorHeapMb(spec.getAmMemoryMb())
                + "m -cp \"$PWD:hadoop-conf:"
                + runtimeClasspath(spec) + "\" " + AM_MAIN_CLASS
                + " --coordinator-class " + spec.getAmMainClass()
                + " --descriptor " + descriptorDestination
                + stagingDirArgument(applicationStagingDir);
    }

    private String stagingDirArgument(String applicationStagingDir) {
        if (applicationStagingDir == null) {
            return "";
        }
        // 单引号包裹并转义，避免 staging URI 中的 shell 元字符被执行
        return " --staging-dir '" + applicationStagingDir.replace("'", "'\\''") + "'";
    }

    /**
     * Per-application staging directory {@code <staging>/<submissionId>/}, the
     * same layout Spark uses under {@code spark.yarn.stagingDir}. The
     * ApplicationMaster deletes it after a successful finish.
     */
    private String applicationStagingDir(String submissionId, YarnSubmissionSpec spec) {
        if (spec.getStagingUri() == null) {
            return null;
        }
        String base = spec.getStagingUri();
        return (base.endsWith("/") ? base : base + "/") + submissionId;
    }

    /**
     * Falls back to the submitting user's HDFS home staging directory when a
     * local artifact <em>or the local Hadoop configuration</em> needs
     * distribution and no explicit staging URI was provided, mirroring
     * spark-submit which stages under {@code spark.yarn.stagingDir} in the user's
     * home by default. Fully remote submissions without a local Hadoop
     * configuration skip staging entirely.
     */
    private void applyDefaultStaging(YarnSubmissionSpec spec) {
        if (spec.getStagingUri() != null
                || (!hasLocalArtifacts(spec) && resolveHadoopConfDirectory() == null)) {
            return;
        }
        String defaultBase = gateway().defaultStagingBase();
        if (defaultBase != null) {
            spec.setStagingUri(defaultBase);
        }
    }

    private boolean hasLocalArtifacts(YarnSubmissionSpec spec) {
        if (isLocalReference(spec.getRuntimeArchiveUri())
                || isLocalReference(spec.getDescriptorUri())) {
            return true;
        }
        return anyLocalReference(spec.getFiles()) || anyLocalReference(spec.getJars());
    }

    private boolean anyLocalReference(List<String> references) {
        if (references == null) {
            return false;
        }
        for (String reference : references) {
            if (isLocalReference(YarnSubmissionSpec.referenceUri(reference))) {
                return true;
            }
        }
        return false;
    }

    private boolean isLocalReference(String reference) {
        if (reference == null || reference.isBlank()) {
            return false;
        }
        try {
            return SubmissionReference.isLocal(URI.create(reference));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Localizes {@code --files}/{@code --jars} references by their file name
     * (or {@code #alias}), the same visibility rule as spark-submit: the files
     * appear in the container working directory under that name.
     */
    private void localizeReferences(Map<String, LocalResource> localResources, List<String> references) {
        if (references == null) {
            return;
        }
        for (String reference : references) {
            localResources.put(localizedFileName(reference), gateway().createLocalResource(
                    URI.create(YarnSubmissionSpec.referenceUri(reference)), LocalResourceType.FILE));
        }
    }

    private String localizedFileName(String reference) {
        String alias = YarnSubmissionSpec.referenceAlias(reference);
        if (alias != null && !alias.isBlank()) {
            return alias;
        }
        String path = URI.create(YarnSubmissionSpec.referenceUri(reference)).getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private String runtimeDestination(YarnSubmissionSpec spec) {
        return isJar(spec.getRuntimeArchiveUri())
                ? runtimeJarDestination(spec)
                : RUNTIME_ARCHIVE_DESTINATION;
    }

    private String runtimeJarDestination(YarnSubmissionSpec spec) {
        return isBlank(spec.getRuntimeJarDestination())
                ? YarnSubmissionSpec.DEFAULT_RUNTIME_JAR_DESTINATION
                : spec.getRuntimeJarDestination();
    }

    private LocalResourceType runtimeLocalResourceType(String runtimeUri) {
        return isJar(runtimeUri) ? LocalResourceType.FILE : LocalResourceType.ARCHIVE;
    }

    private String runtimeClasspath(YarnSubmissionSpec spec) {
        String runtime = isJar(spec.getRuntimeArchiveUri())
                ? runtimeJarDestination(spec) : RUNTIME_ARCHIVE_DESTINATION + "/*";
        // User jars are appended after the runtime so runtime classes win,
        // mirroring how spark-submit loads user jars below its own libraries.
        StringBuilder classpath = new StringBuilder(runtime);
        if (spec.getJars() != null) {
            for (String jar : spec.getJars()) {
                classpath.append(':').append(localizedFileName(jar));
            }
        }
        return classpath.toString();
    }

    private boolean isJar(String runtimeUri) {
        String path = URI.create(runtimeUri).getPath();
        return path != null && path.toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    private String descriptorDestination(YarnSubmissionSpec spec) {
        String path = URI.create(spec.getDescriptorUri()).getPath();
        String lowerCasePath = path.toLowerCase(Locale.ROOT);
        if (lowerCasePath.endsWith(".yaml")) {
            return DESCRIPTOR_DESTINATION_PREFIX + ".yaml";
        }
        if (lowerCasePath.endsWith(".yml")) {
            return DESCRIPTOR_DESTINATION_PREFIX + ".yml";
        }
        return DESCRIPTOR_DESTINATION_PREFIX + ".json";
    }

    private String queueOrDefault(YarnSubmissionSpec spec) {
        return isBlank(spec.getQueue()) ? YarnSubmissionSpec.DEFAULT_QUEUE : spec.getQueue();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private int maxAttemptsOrDefault(ClusterSubmitRequest request) {
        Integer maxAttempts = request.getExecution().getMaxAttempts();
        if (maxAttempts == null) {
            return -1;
        }
        if (maxAttempts == 0 || maxAttempts < -1) {
            throw new IllegalArgumentException("maxAttempts must be positive or -1 to use the cluster default");
        }
        return maxAttempts;
    }

    /**
     * Replaces local artifact references with their staged remote URIs before a
     * submission context reaches the ResourceManager. Staged files keep their
     * original names inside the per-application staging directory.
     */
    private static final class StageResolver {

        private StageResolver() {
        }

        private static YarnSubmissionSpec resolve(YarnSubmissionSpec spec,
                                                  String applicationStagingDir,
                                                  YarnSubmissionGateway gateway) {
            ensureUniqueStagingNames(spec);
            YarnSubmissionSpec staged = YarnSubmissionSpec.builder()
                    .runtimeArchiveUri(stageIfLocal(spec.getRuntimeArchiveUri(), applicationStagingDir, gateway))
                    .descriptorUri(stageIfLocal(spec.getDescriptorUri(), applicationStagingDir, gateway))
                    .stagingUri(spec.getStagingUri())
                    .amMainClass(spec.getAmMainClass())
                    .applicationName(spec.getApplicationName())
                    .queue(spec.getQueue())
                    .completionPollSeconds(spec.getCompletionPollSeconds())
                    .runtimeJarDestination(spec.getRuntimeJarDestination())
                    .runTagPrefix(spec.getRunTagPrefix())
                    .amJavaHome(spec.getAmJavaHome())
                    .defaultAmJavaHome(spec.getDefaultAmJavaHome())
                    .amMemoryMb(spec.getAmMemoryMb())
                    .amVCores(spec.getAmVCores())
                    .tags(spec.sanitizedTags())
                    .files(stageReferences(spec.getFiles(), applicationStagingDir, gateway))
                    .jars(stageReferences(spec.getJars(), applicationStagingDir, gateway))
                    .build();
            staged.validate();
            return staged;
        }

        /**
         * Stages the URI part of local {@code uri#alias} references and keeps
         * the alias suffix intact, so remote references pass through untouched.
         */
        private static List<String> stageReferences(List<String> references,
                                                    String applicationStagingDir,
                                                    YarnSubmissionGateway gateway) {
            if (references == null || references.isEmpty()) {
                return references;
            }
            List<String> staged = new ArrayList<>();
            for (String reference : references) {
                String uriPart = YarnSubmissionSpec.referenceUri(reference);
                String alias = YarnSubmissionSpec.referenceAlias(reference);
                String resolved = stageIfLocal(uriPart, applicationStagingDir, gateway);
                staged.add(alias == null ? resolved : resolved + "#" + alias);
            }
            return staged;
        }

        /**
         * staging 目录按原始文件名落盘；同名文件会互相覆盖（后写的 user file 可能
         * 覆盖 descriptor/runtime）。提交前先拒绝重名，避免 AM 读到错误内容。
         */
        private static void ensureUniqueStagingNames(YarnSubmissionSpec spec) {
            Set<String> names = new java.util.HashSet<>();
            addStagedName(names, spec.getRuntimeArchiveUri(), "runtime archive");
            addStagedName(names, spec.getDescriptorUri(), "descriptor");
            addReferenceNames(names, spec.getFiles(), "files");
            addReferenceNames(names, spec.getJars(), "jars");
        }

        private static void addReferenceNames(Set<String> names, List<String> references, String kind) {
            if (references == null) {
                return;
            }
            for (String reference : references) {
                addStagedName(names, reference, kind);
            }
        }

        private static void addStagedName(Set<String> names, String reference, String kind) {
            if (reference == null || reference.isBlank()) {
                return;
            }
            String alias = YarnSubmissionSpec.referenceAlias(reference);
            String name;
            if (alias != null && !alias.isBlank()) {
                name = alias;
            } else {
                String path = URI.create(YarnSubmissionSpec.referenceUri(reference)).getPath();
                name = path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
            }
            if (name.isEmpty()) {
                return;
            }
            if (!names.add(name)) {
                throw new IllegalStateException(
                        "YARN staging basename collision: " + name + " (" + kind + ")");
            }
        }

        private static String stageIfLocal(String value, String applicationStagingDir, YarnSubmissionGateway gateway) {
            URI uri = URI.create(value);
            if (!SubmissionReference.isLocal(uri)) {
                return value;
            }
            if (applicationStagingDir == null) {
                // 本地产物必须经 staging 分发才能被其他 NodeManager 读到；
                // 既没显式 stagingDir、默认 staging 也解析不到时只能明确失败。
                throw new IllegalStateException("Local artifact " + value + " requires a staging directory; "
                        + "set yarn.stagingDir or ensure the default staging base resolves");
            }
            URI localPath = SubmissionReference.toLocalPath(value).toUri();
            return gateway.stageLocalFile(localPath, URI.create(applicationStagingDir)).toString();
        }
    }
}
