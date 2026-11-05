package com.consilens.cluster.yarn;

import com.consilens.cluster.api.ClusterComparisonDescription;
import com.consilens.cluster.api.ClusterExecutionDescription;
import com.consilens.cluster.api.ClusterSubmission;
import com.consilens.cluster.api.ClusterSubmitRequest;
import com.consilens.cluster.api.YarnSubmissionSpec;
import com.consilens.cluster.yarn.gateway.YarnSubmissionGateway;
import com.consilens.connector.api.planner.ExecutionMode;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationSubmissionContext;
import org.apache.hadoop.yarn.api.records.ContainerLaunchContext;
import org.apache.hadoop.yarn.api.records.LocalResource;
import org.apache.hadoop.yarn.api.records.LocalResourceType;
import org.apache.hadoop.yarn.api.records.LocalResourceVisibility;
import org.apache.hadoop.yarn.api.records.URL;
import org.apache.hadoop.yarn.util.Records;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YarnClusterSubmitterTest {

    @TempDir
    Path temporaryDirectory;

    @TempDir
    Path hadoopConfDirectory;

    @BeforeEach
    void seedHadoopConfDirectory() throws Exception {
        Files.writeString(hadoopConfDirectory.resolve("yarn-site.xml"), "<configuration/>");
    }

    @Test
    void shouldMapCompleteSubmissionContextThroughGateway() throws Exception {
        RecordingYarnGateway gateway = new RecordingYarnGateway();
        Files.writeString(hadoopConfDirectory.resolve("yarn-site.xml"), "<configuration/>");
        Files.writeString(hadoopConfDirectory.resolve("core-site.xml"), "<configuration/>");
        ClusterSubmitRequest request = yarnRequest(YarnSubmissionSpec.builder()
                .runtimeArchiveUri("hdfs://namenode/apps/consilens/consilens-runtime.zip")
                .descriptorUri("hdfs://namenode/apps/consilens/submission-descriptor.json")
                .amMainClass("com.consilens.cluster.application.ClusterComparisonCoordinator")
                .applicationName("consilens-compare-orders")
                .queue("data-quality")
                .amMemoryMb(2048)
                .amVCores(2)
                .tags(List.of("consilens", "compare"))
                .build());

        ClusterSubmission submission = new YarnClusterSubmitter(gateway, hadoopConfDirectory).submit(request);

        ApplicationSubmissionContext context = gateway.submittedContext;
        assertEquals("consilens-compare-orders", context.getApplicationName());
        assertEquals("data-quality", context.getQueue());
        assertEquals(2048, context.getResource().getMemory());
        assertEquals(2, context.getResource().getVirtualCores());
        assertEquals(2, context.getMaxAppAttempts());
        assertEquals(Set.of("consilens", "compare", "consilens-run-submission-yarn-1"), context.getApplicationTags());

        Map<String, LocalResource> localResources = context.getAMContainerSpec().getLocalResources();
        assertTrue(localResources.containsKey("consilens-runtime"));
        assertTrue(localResources.containsKey("hadoop-conf/yarn-site.xml"));
        assertTrue(localResources.containsKey("hadoop-conf/core-site.xml"));
        assertEquals(LocalResourceType.ARCHIVE, localResources.get("consilens-runtime").getType());
        assertEquals(LocalResourceType.FILE, localResources.get("hadoop-conf/yarn-site.xml").getType());
        assertEquals(LocalResourceVisibility.APPLICATION, localResources.get("consilens-runtime").getVisibility());
        assertEquals("/apps/consilens/consilens-runtime.zip",
                localResources.get("consilens-runtime").getResource().getFile());
        assertEquals(LocalResourceType.FILE, localResources.get("submission-descriptor.json").getType());

        List<String> commands = context.getAMContainerSpec().getCommands();
        assertEquals(1, commands.size());
        String command = commands.get(0);
        assertTrue(command.startsWith("$JAVA_HOME/bin/java -server -Xmx1433m"), command);
        assertTrue(command.contains("-cp \"$PWD:hadoop-conf:consilens-runtime/*\""));
        assertTrue(command.contains(YarnClusterSubmitter.AM_MAIN_CLASS));
        assertTrue(command.contains("--coordinator-class com.consilens.cluster.application.ClusterComparisonCoordinator"));
        assertTrue(command.contains("--descriptor submission-descriptor.json"));
        assertFalse(command.contains("password"));
        // No custom AM environment: JAVA_HOME comes from the cluster-provided
        // container environment, endpoints from the shipped Hadoop conf.
        Map<String, String> amEnvironment = context.getAMContainerSpec().getEnvironment();
        assertTrue(amEnvironment == null || amEnvironment.isEmpty());

        assertEquals(context.getApplicationId().toString(), submission.getClusterApplicationId());
        assertEquals(ExecutionMode.YARN, submission.getExecutionMode());
        assertTrue(gateway.createCalls >= 1);
        assertEquals(1, gateway.submitCalls);
    }

    @Test
    void shouldPreserveDescriptorFormatForYamlAndYml() {
        RecordingYarnGateway gateway = new RecordingYarnGateway();
        YarnSubmissionSpec spec = validYarnSpec();
        spec.setDescriptorUri("hdfs://namenode/apps/consilens/submission.yaml");

        new YarnClusterSubmitter(gateway, hadoopConfDirectory).submit(yarnRequest(spec));

        ContainerLaunchContext context = gateway.submittedContext.getAMContainerSpec();
        assertTrue(context.getLocalResources().containsKey("submission-descriptor.yaml"));
        assertTrue(context.getCommands().get(0).contains("--descriptor submission-descriptor.yaml"));
    }

    @Test
    void shouldRejectPercentEncodedShellMetacharactersInDerivedJarName() {
        RecordingYarnGateway gateway = new RecordingYarnGateway();
        YarnSubmissionSpec spec = validYarnSpec();
        // URI.getPath() 会解码 %22/%3B，派生文件名必须重新校验字符集
        spec.setJars(List.of("hdfs://namenode/libs/x%22%3Btouch%20/tmp%3B%22.jar"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new YarnClusterSubmitter(gateway, hadoopConfDirectory).submit(yarnRequest(spec)));

        assertTrue(error.getMessage().contains("destination name"), error.getMessage());
        assertEquals(0, gateway.submitCalls);
    }

    @Test
    void shouldRejectNonYarnModeBeforeContactingGateway() {
        RecordingYarnGateway gateway = new RecordingYarnGateway();
        ClusterSubmitRequest request = ClusterSubmitRequest.builder()
                .submissionId("submission-local-1")
                .comparison(ClusterComparisonDescription.builder()
                        .sourceConfigRef("datasource/source-orders")
                        .targetConfigRef("datasource/target-orders")
                        .build())
                .execution(ClusterExecutionDescription.builder().executionMode(ExecutionMode.LOCAL).build())
                .yarnSubmission(validYarnSpec())
                .build();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new YarnClusterSubmitter(gateway, hadoopConfDirectory).submit(request));

        assertTrue(error.getMessage().contains("only supports YARN"));
        assertEquals(0, gateway.createCalls);
        assertEquals(0, gateway.submitCalls);
    }

    @Test
    void shouldRejectMissingYarnSpecBeforeContactingGateway() {
        RecordingYarnGateway gateway = new RecordingYarnGateway();
        ClusterSubmitRequest request = yarnRequest(null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new YarnClusterSubmitter(gateway, hadoopConfDirectory).submit(request));

        assertTrue(error.getMessage().contains("yarnSubmission is required"));
        assertEquals(0, gateway.submitCalls);
    }

    @Test
    void shouldRejectInvalidReferenceAndResourceParametersBeforeContactingGateway() {
        assertRejectedBeforeGateway(YarnSubmissionSpec.builder()
                .runtimeArchiveUri("relative/runtime.zip")
                .descriptorUri("hdfs://namenode/apps/consilens/descriptor.json")
                .amMainClass("com.consilens.cluster.application.ClusterComparisonCoordinator")
                .applicationName("consilens")
                .amMemoryMb(1024)
                .amVCores(1)
                .build());
        assertRejectedBeforeGateway(YarnSubmissionSpec.builder()
                .runtimeArchiveUri("hdfs://namenode/apps/consilens/runtime.zip")
                .descriptorUri("hdfs://namenode/apps/consilens/descriptor.json")
                .amMainClass("ping -p")
                .applicationName("consilens")
                .amMemoryMb(1024)
                .amVCores(1)
                .build());
        assertRejectedBeforeGateway(YarnSubmissionSpec.builder()
                .runtimeArchiveUri("hdfs://namenode/apps/consilens/runtime.zip")
                .descriptorUri("hdfs://namenode/apps/consilens/descriptor.json")
                .amMainClass("com.consilens.cluster.application.ClusterComparisonCoordinator")
                .applicationName("consilens")
                .amMemoryMb(0)
                .amVCores(1)
                .build());
        assertRejectedBeforeGateway(YarnSubmissionSpec.builder()
                .runtimeArchiveUri("hdfs://namenode/apps/consilens/runtime.zip")
                .descriptorUri("hdfs://namenode/apps/consilens/descriptor.json")
                .amMainClass("com.consilens.cluster.application.ClusterComparisonCoordinator")
                .applicationName("consilens")
                .amMemoryMb(1024)
                .amVCores(-1)
                .build());
        assertRejectedBeforeGateway(YarnSubmissionSpec.builder()
                .runtimeArchiveUri("hdfs://user:password@namenode/apps/consilens/runtime.zip")
                .descriptorUri("hdfs://namenode/apps/consilens/descriptor.json")
                .amMainClass("com.consilens.cluster.application.ClusterComparisonCoordinator")
                .applicationName("consilens")
                .amMemoryMb(1024)
                .amVCores(1)
                .build());
    }

    @Test
    void shouldRejectNonPositiveMaxAttemptsBeforeContactingGateway() {
        assertRejectedAttemptsBeforeGateway(0);
        assertRejectedAttemptsBeforeGateway(-2);
    }

    @Test
    void shouldRejectUnsafeSubmissionIdUsedInStagingPath() {
        RecordingYarnGateway gateway = new RecordingYarnGateway();
        ClusterSubmitRequest request = yarnRequest(validYarnSpec());
        request.setSubmissionId("unsafe id; rm -rf");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new YarnClusterSubmitter(gateway, hadoopConfDirectory).submit(request));

        assertTrue(error.getMessage().contains("submissionId"));
        assertEquals(0, gateway.submitCalls);
    }

    @Test
    void shouldUseDefaultsForQueueAndMissingMaxAttempts() {
        RecordingYarnGateway gateway = new RecordingYarnGateway();
        ClusterSubmitRequest request = yarnRequest(YarnSubmissionSpec.builder()
                .runtimeArchiveUri("hdfs://namenode/apps/consilens/runtime.zip")
                .descriptorUri("hdfs://namenode/apps/consilens/descriptor.json")
                .amMainClass("com.consilens.cluster.application.ClusterComparisonCoordinator")
                .applicationName("consilens")
                .amMemoryMb(1024)
                .amVCores(1)
                .build());
        request.getExecution().setMaxAttempts(null);

        new YarnClusterSubmitter(gateway, hadoopConfDirectory).submit(request);

        assertEquals("default", gateway.submittedContext.getQueue());
        // -1 keeps the cluster-wide RM default, like spark.yarn.maxAppAttempts.
        assertEquals(0, gateway.submittedContext.getMaxAppAttempts());
    }

    @Test
    void shouldNotLeakTagOrQueueSecretsIntoCommand() {
        RecordingYarnGateway gateway = new RecordingYarnGateway();
        ClusterSubmitRequest request = yarnRequest(YarnSubmissionSpec.builder()
                .runtimeArchiveUri("hdfs://namenode/apps/consilens/runtime.zip")
                .descriptorUri("hdfs://namenode/apps/consilens/descriptor.json")
                .amMainClass("com.consilens.cluster.application.ClusterComparisonCoordinator")
                .applicationName("consilens")
                .amMemoryMb(1024)
                .amVCores(1)
                .tags(List.of("secret-tag-abc"))
                .build());

        new YarnClusterSubmitter(gateway, hadoopConfDirectory).submit(request);

        String command = gateway.submittedContext.getAMContainerSpec().getCommands().get(0);
        assertFalse(command.contains("secret-tag-abc"));
    }

    @Test
    void shouldStageLocalRuntimeAndDescriptorUnderApplicationDirectory() throws Exception {
        RecordingYarnGateway gateway = new RecordingYarnGateway();
        Path runtime = temporaryDirectory.resolve("runtime.zip");
        Path descriptor = temporaryDirectory.resolve("descriptor.json");
        Files.write(runtime, new byte[]{1, 2, 3});
        Files.write(descriptor, new byte[]{4, 5, 6});
        YarnSubmissionSpec spec = YarnSubmissionSpec.builder()
                .runtimeArchiveUri(runtime.toString())
                .descriptorUri(descriptor.toString())
                .stagingUri("hdfs://namenode/apps/staging/consilens")
                .amMainClass("com.consilens.cluster.application.ClusterComparisonCoordinator")
                .applicationName("consilens")
                .amMemoryMb(1024)
                .amVCores(1)
                .build();

        ClusterSubmission submission = new YarnClusterSubmitter(gateway, hadoopConfDirectory).submit(yarnRequest(spec));

        // runtime + descriptor + HADOOP_CONF_DIR 下的 conf 文件都要跨节点分发
        assertEquals(3, gateway.stagedLocals.size());
        assertTrue(gateway.stagedLocals.get(0).toString().startsWith("file:"));
        assertTrue(gateway.stagedLocals.get(1).toString().startsWith("file:"));
        assertTrue(gateway.stagedLocals.get(2).toString().endsWith("yarn-site.xml"));
        Map<String, LocalResource> resources = gateway.submittedContext.getAMContainerSpec().getLocalResources();
        assertEquals("hdfs", resources.get("consilens-runtime").getResource().getScheme());
        assertEquals("hdfs", resources.get("hadoop-conf/yarn-site.xml").getResource().getScheme());
        assertEquals("/apps/staging/consilens/submission-yarn-1/runtime.zip",
                resources.get("consilens-runtime").getResource().getFile());
        assertEquals("/apps/staging/consilens/submission-yarn-1/descriptor.json",
                resources.get("submission-descriptor.json").getResource().getFile());
        String command = gateway.submittedContext.getAMContainerSpec().getCommands().get(0);
        assertTrue(command.endsWith("--staging-dir 'hdfs://namenode/apps/staging/consilens/submission-yarn-1'"), command);
        assertEquals("submission-yarn-1", submission.getSubmissionId());
    }

    @Test
    void shouldHonorAmJavaHomeInContainerCommand() {
        RecordingYarnGateway gateway = new RecordingYarnGateway();
        YarnSubmissionSpec spec = validYarnSpec();
        spec.setAmJavaHome("/opt/java/openjdk");

        new YarnClusterSubmitter(gateway, hadoopConfDirectory).submit(yarnRequest(spec));

        String command = gateway.submittedContext.getAMContainerSpec().getCommands().get(0);
        assertTrue(command.startsWith("/opt/java/openjdk/bin/java -server"), command);
    }

    /**
     * 无 staging 目录（默认 staging 也不可用）时不得 NPE：远端产物 + 本地 conf 仍可提交，
     * conf 文件退回本地资源（单节点可用），多节点需要显式配置 stagingDir。
     */
    @Test
    void shouldFallBackToLocalConfResourcesWithoutStagingDirectory() {
        RecordingYarnGateway gateway = new RecordingYarnGateway();
        gateway.defaultStagingAvailable = false;

        new YarnClusterSubmitter(gateway, hadoopConfDirectory).submit(yarnRequest(validYarnSpec()));

        Map<String, LocalResource> resources = gateway.submittedContext.getAMContainerSpec().getLocalResources();
        assertEquals("file", resources.get("hadoop-conf/yarn-site.xml").getResource().getScheme());
    }

    /**
     * 本地产物无法分发时必须明确失败，而不是静默按本地路径提交（其他 NodeManager 读不到）。
     */
    @Test
    void shouldRejectLocalArtifactsWhenNoStagingDirectoryIsAvailable() throws Exception {
        RecordingYarnGateway gateway = new RecordingYarnGateway();
        gateway.defaultStagingAvailable = false;
        Path runtime = temporaryDirectory.resolve("runtime.zip");
        Files.write(runtime, new byte[]{1});
        YarnSubmissionSpec spec = validYarnSpec();
        spec.setRuntimeArchiveUri(runtime.toString());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new YarnClusterSubmitter(gateway, hadoopConfDirectory).submit(yarnRequest(spec)));

        assertTrue(error.getMessage().contains("staging directory"), error.getMessage());
    }

    @Test
    void shouldFallBackToUserHomeStagingWhenNotConfigured() throws Exception {
        RecordingYarnGateway gateway = new RecordingYarnGateway();
        Path runtime = temporaryDirectory.resolve("runtime.zip");
        Path descriptor = temporaryDirectory.resolve("comparison.yaml");
        Files.write(runtime, new byte[]{1, 2, 3});
        Files.write(descriptor, new byte[]{4, 5, 6});
        YarnSubmissionSpec spec = YarnSubmissionSpec.builder()
                .runtimeArchiveUri(runtime.toString())
                .descriptorUri(descriptor.toString())
                .amMainClass("com.consilens.cluster.application.ClusterComparisonCoordinator")
                .applicationName("consilens")
                .amMemoryMb(1024)
                .amVCores(1)
                .build();

        new YarnClusterSubmitter(gateway, hadoopConfDirectory).submit(yarnRequest(spec));

        Map<String, LocalResource> resources = gateway.submittedContext.getAMContainerSpec().getLocalResources();
        assertEquals("/user/root/.consilens/staging/submission-yarn-1/runtime.zip",
                resources.get("consilens-runtime").getResource().getFile());
        assertEquals("/user/root/.consilens/staging/submission-yarn-1/comparison.yaml",
                resources.get("submission-descriptor.yaml").getResource().getFile());
        String command = gateway.submittedContext.getAMContainerSpec().getCommands().get(0);
        assertTrue(command.endsWith("--staging-dir 'hdfs://namenode/user/root/.consilens/staging/submission-yarn-1'"), command);
    }

    @Test
    void shouldLocalizeUserFilesAndJarsLikeSparkSubmit() throws Exception {
        RecordingYarnGateway gateway = new RecordingYarnGateway();
        Path driverJar = temporaryDirectory.resolve("extra-driver.jar");
        Files.write(driverJar, new byte[]{9, 9, 9});
        YarnSubmissionSpec spec = validYarnSpec();
        spec.setFiles(List.of("hdfs://namenode/apps/conf/extra.json#settings.json", driverJar.toString()));
        spec.setJars(List.of("hdfs://namenode/libs/other.jar"));

        new YarnClusterSubmitter(gateway, hadoopConfDirectory).submit(yarnRequest(spec));

        Map<String, LocalResource> resources = gateway.submittedContext.getAMContainerSpec().getLocalResources();
        // Aliased remote file keeps its alias, remote jar keeps its base name,
        // local jar is staged into the application staging directory first.
        assertEquals(LocalResourceType.FILE, resources.get("settings.json").getType());
        assertEquals("/apps/conf/extra.json", resources.get("settings.json").getResource().getFile());
        assertEquals(LocalResourceType.FILE, resources.get("other.jar").getType());
        assertEquals("/libs/other.jar", resources.get("other.jar").getResource().getFile());
        assertEquals(LocalResourceType.FILE, resources.get("extra-driver.jar").getType());
        assertEquals("/user/root/.consilens/staging/submission-yarn-1/extra-driver.jar",
                resources.get("extra-driver.jar").getResource().getFile());
        // jars join the AM classpath after the runtime; plain files do not.
        String command = gateway.submittedContext.getAMContainerSpec().getCommands().get(0);
        assertTrue(command.contains("-cp \"$PWD:hadoop-conf:consilens-runtime/*:other.jar\""));
        assertFalse(command.contains("settings.json"));
    }

    @Test
    void shouldRejectFilesOverlappingReservedNames() {
        RecordingYarnGateway gateway = new RecordingYarnGateway();
        YarnSubmissionSpec spec = validYarnSpec();
        spec.setFiles(List.of("hdfs://namenode/apps/whatever#consilens-runtime"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new YarnClusterSubmitter(gateway, hadoopConfDirectory).submit(yarnRequest(spec)));

        assertTrue(error.getMessage().contains("reserved localization name"));
    }

    @Test
    void shouldAppendDeterministicRunTagForRecoverySweep() {
        RecordingYarnGateway gateway = new RecordingYarnGateway();
        ClusterSubmitRequest request = yarnRequest(validYarnSpec());
        request.getExecution().setMaxAttempts(null);

        new YarnClusterSubmitter(gateway, hadoopConfDirectory).submit(request);

        Set<String> tags = gateway.submittedContext.getApplicationTags();
        assertTrue(tags.contains("consilens-run-submission-yarn-1"));
    }

    @Test
    void shouldSweepStaleApplicationsByRunTagOfRequestSubmissionId() {
        RecordingYarnGateway gateway = new RecordingYarnGateway();

        new YarnClusterSubmitter(gateway, hadoopConfDirectory).killStale(yarnRequest(validYarnSpec()));

        assertEquals("consilens-run-submission-yarn-1", gateway.killedTag);
    }

    @Test
    void shouldTreatJarRuntimeAsFileLocalResource() {
        RecordingYarnGateway gateway = new RecordingYarnGateway();
        YarnSubmissionSpec spec = validYarnSpec();
        spec.setRuntimeArchiveUri("hdfs://namenode/apps/consilens/consilens-runtime.jar");

        new YarnClusterSubmitter(gateway, hadoopConfDirectory).submit(yarnRequest(spec));

        Map<String, LocalResource> resources = gateway.submittedContext.getAMContainerSpec().getLocalResources();
        assertEquals(LocalResourceType.FILE, resources.get("consilens-runtime.jar").getType());
        assertTrue(gateway.submittedContext.getAMContainerSpec().getCommands().get(0)
                .contains("-cp \"$PWD:hadoop-conf:consilens-runtime.jar\""));
        assertFalse(gateway.submittedContext.getAMContainerSpec().getCommands().get(0)
                .contains("consilens-runtime/*"));
    }

    @Test
    void shouldFailFastWithoutHadoopConfDirectory() {
        RecordingYarnGateway gateway = new RecordingYarnGateway();
        ClusterSubmitRequest request = yarnRequest(validYarnSpec());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new YarnClusterSubmitter(gateway, temporaryDirectory.resolve("missing-conf")).submit(request));

        assertTrue(error.getMessage().contains("Hadoop configuration directory"));
        assertEquals(0, gateway.submitCalls);
    }

    private void assertRejectedBeforeGateway(YarnSubmissionSpec spec) {
        RecordingYarnGateway gateway = new RecordingYarnGateway();
        assertThrows(IllegalArgumentException.class,
                () -> new YarnClusterSubmitter(gateway, hadoopConfDirectory).submit(yarnRequest(spec)));
        assertEquals(0, gateway.createCalls);
        assertEquals(0, gateway.submitCalls);
    }

    private void assertRejectedAttemptsBeforeGateway(int maxAttempts) {
        RecordingYarnGateway gateway = new RecordingYarnGateway();
        ClusterSubmitRequest request = yarnRequest(validYarnSpec());
        request.getExecution().setMaxAttempts(maxAttempts);

        assertThrows(IllegalArgumentException.class,
                () -> new YarnClusterSubmitter(gateway, hadoopConfDirectory).submit(request));
        assertEquals(0, gateway.createCalls);
        assertEquals(0, gateway.submitCalls);
    }

    private YarnSubmissionSpec validYarnSpec() {
        return YarnSubmissionSpec.builder()
                .runtimeArchiveUri("hdfs://namenode/apps/consilens/runtime.zip")
                .descriptorUri("hdfs://namenode/apps/consilens/descriptor.json")
                .amMainClass("com.consilens.cluster.application.ClusterComparisonCoordinator")
                .applicationName("consilens")
                .amMemoryMb(1024)
                .amVCores(1)
                .build();
    }

    private ClusterSubmitRequest yarnRequest(YarnSubmissionSpec spec) {
        return ClusterSubmitRequest.builder()
                .submissionId("submission-yarn-1")
                .comparison(ClusterComparisonDescription.builder()
                        .sourceConfigRef("datasource/source-orders")
                        .targetConfigRef("datasource/target-orders")
                        .build())
                .execution(ClusterExecutionDescription.builder()
                        .executionMode(ExecutionMode.YARN)
                        .maxAttempts(2)
                        .build())
                .yarnSubmission(spec)
                .build();
    }

    @Test
    void shouldTolerateTransientFailureWhilePollingApplicationState() throws Exception {
        RecordingYarnGateway gateway = new RecordingYarnGateway();
        gateway.failFirstStateQuery = true;
        YarnSubmissionSpec spec = validYarnSpec();
        spec.setCompletionPollSeconds(1);
        YarnClusterSubmitter submitter = new YarnClusterSubmitter(gateway, hadoopConfDirectory);

        ClusterSubmission submission = submitter.submit(yarnRequest(spec));
        com.consilens.cluster.api.ClusterApplicationResult result =
                submitter.awaitCompletion(submission, java.time.Duration.ofSeconds(5));

        // 第一次查询抛异常只应被记日志并继续轮询，不能冒泡到上层触发 kill。
        assertNotNull(result);
        assertEquals("FINISHED", result.getFinalStatus());
        assertTrue(gateway.stateCalls >= 2);
    }

    @Test
    void shouldPassApplicationMasterDiagnosticsToCaller() throws Exception {
        RecordingYarnGateway gateway = new RecordingYarnGateway();
        gateway.finalStatus = "FAILED";
        gateway.diagnostics = "Coordinator failed: java.lang.IllegalStateException: boom";
        YarnClusterSubmitter submitter = new YarnClusterSubmitter(gateway, hadoopConfDirectory);

        ClusterSubmission submission = submitter.submit(yarnRequest(validYarnSpec()));
        com.consilens.cluster.api.ClusterApplicationResult result =
                submitter.awaitCompletion(submission, java.time.Duration.ofSeconds(5));

        assertNotNull(result);
        assertEquals("FAILED", result.getFinalStatus());
        assertTrue(result.getDiagnostics().contains("boom"), result.getDiagnostics());
    }

    private static final class RecordingYarnGateway implements YarnSubmissionGateway {

        private int createCalls;
        private int submitCalls;
        private int stateCalls;
        private boolean failFirstStateQuery;
        private boolean defaultStagingAvailable = true;
        private ApplicationSubmissionContext submittedContext;
        private final List<URI> stagedLocals = new ArrayList<>();
        private String killedTag;
        private String finalStatus;
        private String diagnostics;

        @Override
        public ApplicationSubmissionContext createSubmissionContext() {
            createCalls++;
            ApplicationSubmissionContext context = Records.newRecord(ApplicationSubmissionContext.class);
            context.setApplicationId(ApplicationId.newInstance(0L, 42));
            return context;
        }

        @Override
        public LocalResource createLocalResource(URI resourceUri, LocalResourceType type) {
            URL url = URL.fromURI(resourceUri);
            return LocalResource.newInstance(url, type, LocalResourceVisibility.APPLICATION, 0L, 0L);
        }

        @Override
        public URI stageLocalFile(URI localUri, URI stagingBase) {
            stagedLocals.add(localUri);
            String fileName = URI.create(localUri.toString()).getPath();
            int separator = fileName.lastIndexOf('/');
            String normalizedBase = stagingBase.toString().endsWith("/") ? stagingBase.toString() : stagingBase.toString() + "/";
            return URI.create(normalizedBase).resolve((separator >= 0 ? fileName.substring(separator + 1) : fileName));
        }

        @Override
        public ApplicationId submit(ApplicationSubmissionContext context) {
            submitCalls++;
            submittedContext = context;
            return context.getApplicationId();
        }

        @Override
        public java.util.Optional<String> applicationState(String applicationId) {
            stateCalls++;
            if (failFirstStateQuery && stateCalls == 1) {
                throw new RuntimeException("transient resource manager error");
            }
            return java.util.Optional.of("FINISHED");
        }

        @Override
        public java.util.Optional<String> applicationFinalStatus(String applicationId) {
            return java.util.Optional.ofNullable(finalStatus);
        }

        @Override
        public java.util.Optional<String> applicationDiagnostics(String applicationId) {
            return java.util.Optional.ofNullable(diagnostics);
        }

        @Override
        public String defaultStagingBase() {
            return defaultStagingAvailable ? "hdfs://namenode/user/root/.consilens/staging" : null;
        }

        @Override
        public int killByTag(String tag) {
            killedTag = tag;
            return 1;
        }

        @Override
        public void close() {
            // Recording gateway holds no external resource.
        }
    }
}
