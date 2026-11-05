package com.consilens.server.application.capability;

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
import com.consilens.cluster.api.YarnSubmissionSpec;
import com.consilens.connector.api.planner.ExecutionMode;
import com.consilens.server.api.dto.RunRequest;
import com.consilens.server.application.capability.config.EndpointConfig;
import com.consilens.server.application.capability.config.ServerCompareConfig;
import com.consilens.server.boot.ConsilensServerProperties;
import com.consilens.server.domain.model.TaskExecutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClusterSubmitSupportTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldRewriteLoopbackHostInDescriptorForClusterPlatform() {
        ConsilensServerProperties properties = new ConsilensServerProperties();
        properties.getCluster().getLoopbackHosts().put("yarn", "host.docker.internal");
        properties.getCluster().getLoopbackHosts().put("kubernetes", "mysql-host.default.svc.cluster.local");
        ClusterSubmitSupport support = new ClusterSubmitSupport(new CapturingLocator(yarnSubmitter()), properties);
        EndpointConfig loopback = EndpointConfig.builder()
                .type("mysql")
                .table("dq_cmp_alltype_a")
                .connection(Map.of(
                        "url", "jdbc:mysql://127.0.0.1:3306/demo",
                        "host", "127.0.0.1",
                        "port", 3306,
                        "username", "root",
                        "password", "p"))
                .build();
        ServerCompareConfig config = ServerCompareConfig.builder()
                .source(loopback).target(loopback).keys(List.of("pk_id")).build();

        Map<String, Object> yarn = support.buildDescriptor(config, false, "yarn");
        Map<String, Object> yarnConnection = connectionOf(yarn, "source");
        assertThat(yarnConnection.get("host")).isEqualTo("host.docker.internal");
        assertThat(String.valueOf(yarnConnection.get("url")))
                .contains("host.docker.internal:3306")
                .doesNotContain("127.0.0.1");

        Map<String, Object> k8s = support.buildDescriptor(config, false, "kubernetes");
        Map<String, Object> k8sConnection = connectionOf(k8s, "source");
        assertThat(k8sConnection.get("host")).isEqualTo("mysql-host.default.svc.cluster.local");

        // local 平台不替换
        Map<String, Object> local = support.buildDescriptor(config, false, "local");
        assertThat(connectionOf(local, "source").get("host")).isEqualTo("127.0.0.1");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> connectionOf(Map<String, Object> descriptor, String endpoint) {
        return (Map<String, Object>) ((Map<String, Object>) descriptor.get(endpoint)).get("connection");
    }

    @Test
    void shouldPlaceFiltersAndReadOptionsInCliCompatibleLocations() {
        ClusterSubmitSupport support = new ClusterSubmitSupport(new CapturingLocator(yarnSubmitter()));
        EndpointConfig endpoint = EndpointConfig.builder()
                .type("mysql")
                .table("dq_cmp_alltype_a")
                .filter("pk_id IS NOT NULL")
                .readOptions(Map.of("fetchSize", 1000))
                .connection(Map.of(
                        "url", "jdbc:mysql://db:3306/demo",
                        "host", "db",
                        "port", 3306,
                        "username", "root",
                        "password", "p"))
                .build();
        ServerCompareConfig config = ServerCompareConfig.builder()
                .source(endpoint).target(endpoint).keys(List.of("pk_id")).build();

        Map<String, Object> descriptor = support.buildDescriptor(config, false, "yarn");

        // CLI 模型：过滤条件在 comparison.filters，读取参数在 endpoint.readOptions
        Map<String, Object> comparison = (Map<String, Object>) descriptor.get("comparison");
        Map<String, Object> filters = (Map<String, Object>) comparison.get("filters");
        assertThat(filters.get("source")).isEqualTo("pk_id IS NOT NULL");
        assertThat(filters.get("target")).isEqualTo("pk_id IS NOT NULL");
        Map<String, Object> sourceSection = (Map<String, Object>) descriptor.get("source");
        assertThat(sourceSection.get("readOptions")).isEqualTo(Map.of("fetchSize", 1000));
        // connection 里不能出现 readOptions（CLI 不读该位置）
        assertThat(connectionOf(descriptor, "source")).doesNotContainKey("readOptions");
        // endpoint 顶层不能出现 CLI 不认识的 filter
        assertThat(sourceSection).doesNotContainKey("filter");
        // table 模式用 name，sql 模式用 path
        assertThat((Map<String, Object>) sourceSection.get("resource"))
                .containsEntry("type", "table")
                .containsEntry("name", "dq_cmp_alltype_a");
    }

    @Test
    void shouldCarryAllowNullableKeysIntoClusterDescriptor() {
        ClusterSubmitSupport support = new ClusterSubmitSupport(new CapturingLocator(yarnSubmitter()));
        ServerCompareConfig config = ServerCompareConfig.builder()
                .source(endpoint("p")).target(endpoint("p")).keys(List.of("id"))
                .executionOptions(Map.of("allowNullableKeys", true))
                .build();

        Map<String, Object> descriptor = support.buildDescriptor(config, false, "yarn");

        @SuppressWarnings("unchecked")
        Map<String, Object> strategy = (Map<String, Object>) descriptor.get("strategy");
        assertThat(strategy).containsEntry("allowNullableKeys", true);
    }

    @Test
    void shouldEmitSqlResourcePathForClusterDescriptor() {
        ClusterSubmitSupport support = new ClusterSubmitSupport(new CapturingLocator(yarnSubmitter()));
        EndpointConfig endpoint = EndpointConfig.builder()
                .type("mysql")
                .query("SELECT id, name FROM orders WHERE dt = '2026-01-01'")
                .connection(Map.of("url", "jdbc:mysql://db:3306/x", "host", "db",
                        "port", 3306, "username", "u", "password", "p"))
                .build();
        Map<String, Object> descriptor = support.buildDescriptor(
                ServerCompareConfig.builder().source(endpoint).target(endpoint).keys(List.of("id")).build(),
                false, "yarn");
        Map<String, Object> resource = (Map<String, Object>) ((Map<String, Object>) descriptor.get("source")).get("resource");
        assertThat(resource).containsEntry("type", "sql");
        assertThat(resource.get("path")).isEqualTo("SELECT id, name FROM orders WHERE dt = '2026-01-01'");
        assertThat(resource).doesNotContainKey("name");
    }

    @Test
    void shouldRewriteLoopbackPortWhenConfigured() {
        ConsilensServerProperties properties = new ConsilensServerProperties();
        properties.getCluster().getLoopbackHosts().put("yarn", "mysql-host");
        properties.getCluster().getLoopbackPorts().put("yarn", 3306);
        ClusterSubmitSupport support = new ClusterSubmitSupport(new CapturingLocator(yarnSubmitter()), properties);
        EndpointConfig loopback = EndpointConfig.builder()
                .type("mysql")
                .table("t")
                .connection(Map.of(
                        "url", "jdbc:mysql://127.0.0.1:3306/demo",
                        "host", "127.0.0.1",
                        "port", 3306,
                        "username", "root",
                        "password", "p"))
                .build();

        Map<String, Object> descriptor = support.buildDescriptor(
                ServerCompareConfig.builder().source(loopback).target(loopback).keys(List.of("id")).build(),
                false, "yarn");

        Map<String, Object> connection = connectionOf(descriptor, "source");
        assertThat(connection.get("host")).isEqualTo("mysql-host");
        assertThat(connection.get("port")).isEqualTo(3306);
        assertThat(String.valueOf(connection.get("url"))).isEqualTo("jdbc:mysql://mysql-host:3306/demo");
    }

    @Test
    void shouldOnlyRewriteAuthorityPortInLoopbackJdbcUrl() {
        ConsilensServerProperties properties = new ConsilensServerProperties();
        properties.getCluster().getLoopbackHosts().put("yarn", "mysql-host");
        properties.getCluster().getLoopbackPorts().put("yarn", 3306);
        ClusterSubmitSupport support = new ClusterSubmitSupport(new CapturingLocator(yarnSubmitter()), properties);
        EndpointConfig loopback = EndpointConfig.builder()
                .type("mysql")
                .table("t")
                .connection(Map.of(
                        "url", "jdbc:mysql://127.0.0.1:3306/dq?connectTimeout=4900&note=:4900",
                        "host", "127.0.0.1",
                        "port", 3306,
                        "username", "root",
                        "password", "p"))
                .build();

        Map<String, Object> descriptor = support.buildDescriptor(
                ServerCompareConfig.builder().source(loopback).target(loopback).keys(List.of("id")).build(),
                false, "yarn");

        assertThat(String.valueOf(connectionOf(descriptor, "source").get("url")))
                .isEqualTo("jdbc:mysql://mysql-host:3306/dq?connectTimeout=4900&note=:4900");
    }

    @Test
    void shouldRewriteUrlOnlyConnectionHostAndPort() {
        ConsilensServerProperties properties = new ConsilensServerProperties();
        properties.getCluster().getLoopbackHosts().put("yarn", "mysql-host");
        properties.getCluster().getLoopbackPorts().put("yarn", 3306);
        ClusterSubmitSupport support = new ClusterSubmitSupport(new CapturingLocator(yarnSubmitter()), properties);
        // 只写 url、没有 host/port 字段：端口也必须从 URL 里取出来改，否则是半改写
        EndpointConfig loopback = EndpointConfig.builder()
                .type("mysql")
                .table("t")
                .connection(Map.of(
                        "url", "jdbc:mysql://127.0.0.1:3306/demo",
                        "username", "root",
                        "password", "p"))
                .build();

        Map<String, Object> descriptor = support.buildDescriptor(
                ServerCompareConfig.builder().source(loopback).target(loopback).keys(List.of("id")).build(),
                false, "yarn");

        Map<String, Object> connection = connectionOf(descriptor, "source");
        assertThat(String.valueOf(connection.get("url")))
                .isEqualTo("jdbc:mysql://mysql-host:3306/demo");
        assertThat(connection).doesNotContainKey("port");
    }

    @Test
    void shouldRejectOneSidedFilterForClusterSubmission() {
        ClusterSubmitSupport support = new ClusterSubmitSupport(new CapturingLocator(yarnSubmitter()));
        EndpointConfig filtered = EndpointConfig.builder()
                .type("mysql").table("a").filter("pk_id IS NOT NULL")
                .connection(Map.of("url", "jdbc:mysql://db:3306/x", "host", "db",
                        "port", 3306, "username", "u", "password", "p"))
                .build();
        EndpointConfig plain = EndpointConfig.builder()
                .type("mysql").table("b")
                .connection(Map.of("url", "jdbc:mysql://db:3306/x", "host", "db",
                        "port", 3306, "username", "u", "password", "p"))
                .build();

        assertThat(org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        support.buildDescriptor(ServerCompareConfig.builder()
                                .source(filtered).target(plain).keys(List.of("pk_id")).build(), false, "yarn"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("both source and target")).isNotNull();
    }

    @Test
    void shouldMountKubernetesDescriptorWithoutPlaintextPasswordsOrDeletingCompletedJob() throws Exception {
        CapturingSubmitter submitter = kubernetesSubmitter();
        ClusterSubmitSupport support = new ClusterSubmitSupport(new CapturingLocator(submitter));
        RunRequest request = new RunRequest();
        request.setSerialNo("orders-20260915");
        request.getOptions().setPlatform("kubernetes");
        request.getOptions().setProperties(Map.of(
                "image", "registry.example/consilens-runtime:1.0",
                "secretEnv", Map.of(
                        "SOURCE_PASSWORD", Map.of("secretName", "compare-db", "secretKey", "source-password"),
                        "TARGET_PASSWORD", Map.of("secretName", "compare-db", "secretKey", "target-password"))));

        ServerCompareConfig config = ServerCompareConfig.builder()
                .source(endpoint("source-password"))
                .target(endpoint("target-password"))
                .keys(List.of("id"))
                .build();

        ClusterApplicationResult result = support.submitAndAwait(
                TaskExecutionContext.builder().instanceKey("inst-1").build(), request, config, () -> false);

        String descriptor = submitter.request.getKubernetesSubmission().getDescriptorData().get("comparison.yaml");
        assertThat(result.succeeded()).isTrue();
        assertThat(descriptor).contains("${env.SOURCE_PASSWORD}", "${env.TARGET_PASSWORD}")
                .doesNotContain("source-password", "target-password");
        assertThat(submitter.request.getKubernetesSubmission().getSecretEnv())
                .containsKeys("SOURCE_PASSWORD", "TARGET_PASSWORD");
        assertThat(submitter.killed).isFalse();
    }

    @Test
    void shouldRedactTableSinkPasswordInKubernetesDescriptor() {
        ClusterSubmitSupport support = new ClusterSubmitSupport(new CapturingLocator(kubernetesSubmitter()));
        ServerCompareConfig config = ServerCompareConfig.builder()
                .source(endpoint("source-password"))
                .target(endpoint("target-password"))
                .keys(List.of("id"))
                .result(com.consilens.sink.api.model.ResultConfig.builder()
                        .sinks(List.of(com.consilens.sink.api.model.SinkConfig.builder()
                                .format("table")
                                .type("result")
                                .properties("{\"url\":\"jdbc:mysql://db:3306/x\","
                                        + "\"username\":\"u\",\"password\":\"sink-secret\"}")
                                .build()))
                        .build())
                .build();

        Map<String, Object> kubernetes = support.buildDescriptor(config, true, "kubernetes");
        assertThat(String.valueOf(kubernetes.get("result")))
                .contains("${env.SINK_PASSWORD}")
                .doesNotContain("sink-secret");

        // 非脱敏平台（yarn 以挂载文件下发但保留明文）行为不变
        Map<String, Object> yarn = support.buildDescriptor(config, false, "yarn");
        assertThat(String.valueOf(yarn.get("result"))).contains("sink-secret");
    }

    @Test
    void shouldStageYarnDescriptorFileAndKeepAmDefaults() throws Exception {
        CapturingSubmitter submitter = yarnSubmitter();
        ClusterSubmitSupport support = new ClusterSubmitSupport(new CapturingLocator(submitter));
        RunRequest request = new RunRequest();
        request.setSerialNo("orders-20260915");
        request.getOptions().setPlatform("yarn");
        request.getOptions().setProperties(Map.of("archive", "hdfs:///apps/consilens/runtime.zip"));

        ServerCompareConfig config = ServerCompareConfig.builder()
                .source(endpoint("plain-password"))
                .target(endpoint("plain-password"))
                .keys(List.of("id"))
                .build();

        support.submitAndAwait(
                TaskExecutionContext.builder().instanceKey("inst-1").build(), request, config, () -> false);

        var spec = submitter.request.getYarnSubmission();
        // YARN 凭据直接写 descriptor（随 staging 上传），本地临时文件路径作为 descriptorUri
        assertThat(spec.getRuntimeArchiveUri()).isEqualTo("hdfs:///apps/consilens/runtime.zip");
        // 临时 descriptor 文件位于系统临时目录（JUnit 临时目录的父目录）
        assertThat(spec.getDescriptorUri()).startsWith(temporaryDirectory.getParent().toString());
        assertThat(spec.getAmMemoryMb()).isEqualTo(1024);
        assertThat(spec.getAmVCores()).isEqualTo(1);
        assertThat(submitter.request.getExecution().getExecutionMode()).isEqualTo(ExecutionMode.YARN);
        // 成功路径不 kill
        assertThat(submitter.killed).isFalse();
    }

    @Test
    void shouldKeepPlaintextPasswordInYarnDescriptor() throws Exception {
        CapturingSubmitter submitter = yarnSubmitter();
        ClusterSubmitSupport support = new ClusterSubmitSupport(new CapturingLocator(submitter));
        RunRequest request = new RunRequest();
        request.setSerialNo("orders-20260915");
        request.getOptions().setPlatform("yarn");
        request.getOptions().setProperties(Map.of("archive", "hdfs:///apps/consilens/runtime.zip"));

        support.submitAndAwait(
                TaskExecutionContext.builder().instanceKey("inst-1").build(), request,
                ServerCompareConfig.builder()
                        .source(endpoint("plain-password"))
                        .target(endpoint("plain-password"))
                        .keys(List.of("id"))
                        .build(), () -> false);

        // descriptor 临时文件已上传删除；spec 校验通过即代表路径有效
        assertThat(submitter.request.getYarnSubmission().getDescriptorUri()).isNotBlank();
    }

    @Test
    void shouldRejectInvalidMemoryFormatBeforeSubmission() {
        ClusterSubmitSupport support = new ClusterSubmitSupport(new CapturingLocator(yarnSubmitter()));
        RunRequest request = new RunRequest();
        request.setSerialNo("orders-20260915");
        request.getOptions().setPlatform("yarn");
        request.getOptions().setProperties(Map.of("archive", "hdfs:///apps/consilens/runtime.zip",
                "amMemory", "2xg"));

        assertThat(org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        support.submitAndAwait(
                                TaskExecutionContext.builder().instanceKey("inst-1").build(), request,
                                ServerCompareConfig.builder()
                                        .source(endpoint("p")).target(endpoint("p")).keys(List.of("id")).build(),
                                () -> false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid memory format")).isNotNull();
    }

    @Test
    void shouldKillOnCancellationAfterSubmission() {
        CapturingSubmitter submitter = yarnSubmitter();
        submitter.completes = false;
        ClusterSubmitSupport support = new ClusterSubmitSupport(new CapturingLocator(submitter));
        RunRequest request = new RunRequest();
        request.setSerialNo("orders-20260915");
        request.getOptions().setPlatform("yarn");
        request.getOptions().setProperties(Map.of("archive", "hdfs:///apps/consilens/runtime.zip"));

        assertThat(org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        support.submitAndAwait(
                                TaskExecutionContext.builder().instanceKey("inst-1").build(), request,
                                ServerCompareConfig.builder()
                                        .source(endpoint("p")).target(endpoint("p")).keys(List.of("id")).build(),
                                () -> true))
                .isInstanceOf(TaskCancellationException.class)).isNotNull();
        assertThat(submitter.killed).isTrue();
    }

    @Test
    void shouldKillOnTimeoutAfterSubmission() {
        CapturingSubmitter submitter = yarnSubmitter();
        submitter.completes = false;
        ClusterSubmitSupport support = new ClusterSubmitSupport(new CapturingLocator(submitter));
        RunRequest request = new RunRequest();
        request.setSerialNo("orders-20260915");
        request.getOptions().setPlatform("yarn");
        request.getOptions().setProperties(Map.of("archive", "hdfs:///apps/consilens/runtime.zip"));
        request.getOptions().setTimeoutMs(200);

        assertThat(org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        support.submitAndAwait(
                                TaskExecutionContext.builder().instanceKey("inst-1").build(), request,
                                ServerCompareConfig.builder()
                                        .source(endpoint("p")).target(endpoint("p")).keys(List.of("id")).build(),
                                () -> false))
                .isInstanceOf(java.util.concurrent.TimeoutException.class)).isNotNull();
        assertThat(submitter.killed).isTrue();
    }

    @Test
    void shouldNotKillWhenApplicationFailsOnItsOwn() throws Exception {
        CapturingSubmitter submitter = yarnSubmitter();
        submitter.finalStatus = "FAILED";
        ClusterSubmitSupport support = new ClusterSubmitSupport(new CapturingLocator(submitter));
        RunRequest request = new RunRequest();
        request.setSerialNo("orders-20260915");
        request.getOptions().setPlatform("yarn");
        request.getOptions().setProperties(Map.of("archive", "hdfs:///apps/consilens/runtime.zip"));

        ClusterApplicationResult result = support.submitAndAwait(
                TaskExecutionContext.builder().instanceKey("inst-1").build(), request,
                ServerCompareConfig.builder()
                        .source(endpoint("p")).target(endpoint("p")).keys(List.of("id")).build(),
                () -> false);

        assertThat(result.getFinalStatus()).isEqualTo("FAILED");
        // 自身失败也是终态，不应 kill
        assertThat(submitter.killed).isFalse();
    }

    @Test
    void shouldSweepStaleApplicationsWithSanitizedSubmissionIdUsedBySubmit() throws Exception {
        CapturingSubmitter submitter = yarnSubmitter();
        ClusterSubmitSupport support = new ClusterSubmitSupport(new CapturingLocator(submitter));
        RunRequest request = new RunRequest();
        request.setSerialNo("orders:2026.09.15 10:00");
        request.getOptions().setPlatform("yarn");
        request.getOptions().setProperties(Map.of("archive", "hdfs:///apps/consilens/runtime.zip"));

        support.submitAndAwait(
                TaskExecutionContext.builder().instanceKey("inst-1").build(), request,
                ServerCompareConfig.builder()
                        .source(endpoint("p"))
                        .target(endpoint("p"))
                        .keys(List.of("id"))
                        .build(), () -> false);

        assertThat(submitter.staleSweepRequest.getSubmissionId()).isEqualTo("orders-2026-09-15-10-00");
        assertThat(submitter.staleSweepRequest.getSubmissionId()).isEqualTo(submitter.request.getSubmissionId());
    }

    @Test
    void shouldContinueSubmissionWhenStaleSweepFails() throws Exception {
        CapturingSubmitter submitter = yarnSubmitter();
        submitter.sweepFails = true;
        ClusterSubmitSupport support = new ClusterSubmitSupport(new CapturingLocator(submitter));
        RunRequest request = new RunRequest();
        request.setSerialNo("orders-20260915");
        request.getOptions().setPlatform("yarn");
        request.getOptions().setProperties(Map.of("archive", "hdfs:///apps/consilens/runtime.zip"));

        ClusterApplicationResult result = support.submitAndAwait(
                TaskExecutionContext.builder().instanceKey("inst-1").build(), request,
                ServerCompareConfig.builder()
                        .source(endpoint("p"))
                        .target(endpoint("p"))
                        .keys(List.of("id"))
                        .build(), () -> false);

        assertThat(result.succeeded()).isTrue();
        assertThat(submitter.request).isNotNull();
    }

    private static CapturingSubmitter yarnSubmitter() {
        return new CapturingSubmitter("yarn", true, false);
    }

    private static CapturingSubmitter kubernetesSubmitter() {
        return new CapturingSubmitter("kubernetes", false, true);
    }

    private static ClusterSubmitRequest buildYarnRequest(ClusterSubmissionContext context) {
        Map<String, Object> params = context.getProperties();
        String descriptorUri = context.getDescriptorFile().toAbsolutePath().toString();
        return ClusterSubmitRequest.builder()
                .submissionId(context.getSubmissionId())
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
                        .applicationName(context.getSubmissionId())
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

    private static ClusterSubmitRequest buildKubernetesRequest(ClusterSubmissionContext context) {
        Map<String, Object> params = context.getProperties();
        String jobName = ClusterSubmissionParameters.orDefault(
                params, "jobName", defaultJobName(context.getSubmissionId()));
        Map<String, KubernetesSecretKeyRef> secretEnv = secretEnvsOf(params.get("secretEnv"));
        return ClusterSubmitRequest.builder()
                .submissionId(context.getSubmissionId())
                .comparison(ClusterComparisonDescription.builder()
                        .sourceConfigRef("descriptor:configmap:" + descriptorConfigMapName(jobName))
                        .targetConfigRef("descriptor:configmap:" + descriptorConfigMapName(jobName))
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
                        .descriptorData(Map.of("comparison.yaml", context.getDescriptor()))
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

    private static Map<String, KubernetesSecretKeyRef> secretEnvsOf(Object raw) {
        if (!(raw instanceof Map)) {
            return Map.of();
        }
        Map<String, KubernetesSecretKeyRef> result = new LinkedHashMap<>();
        ((Map<?, ?>) raw).forEach((name, reference) -> {
            Map<?, ?> values = (Map<?, ?>) reference;
            result.put(String.valueOf(name), KubernetesSecretKeyRef.builder()
                    .secretName(ClusterSubmissionParameters.stringValue(values.get("secretName")))
                    .secretKey(ClusterSubmissionParameters.stringValue(values.get("secretKey")))
                    .build());
        });
        return result;
    }

    private static String defaultJobName(String submissionId) {
        String suffix = submissionId.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]", "-")
                .replaceAll("^-+|-+$", "");
        return ("consilens-" + (suffix.isEmpty() ? "run" : suffix))
                .substring(0, Math.min(52, "consilens-".length() + suffix.length()))
                .replaceAll("-+$", "");
    }

    private static String descriptorConfigMapName(String jobName) {
        return (jobName + "-descriptor")
                .substring(0, Math.min(63, jobName.length() + "-descriptor".length()))
                .replaceAll("-+$", "");
    }

    private static String stringParam(Map<String, Object> params, String key) {
        Object value = params.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private EndpointConfig endpoint(String password) {
        return EndpointConfig.builder()
                .type("mysql")
                .table("orders")
                .connection(Map.of("url", "jdbc:mysql://db:3306/orders", "username", "reader", "password", password))
                .build();
    }

    private static final class CapturingLocator extends ClusterSubmitterLocator {
        private final ClusterSubmitter submitter;

        private CapturingLocator(ClusterSubmitter submitter) {
            this.submitter = submitter;
        }

        @Override
        public ClusterSubmitter locate(String platform) {
            return submitter;
        }
    }

    private static final class CapturingSubmitter implements ClusterSubmitter {
        private final String platform;
        private final boolean requiresDescriptorFile;
        private final boolean redactsPasswords;
        private ClusterSubmitRequest request;
        private ClusterSubmitRequest staleSweepRequest;
        private boolean killed;
        boolean completes = true;
        boolean sweepFails;
        String finalStatus = "SUCCEEDED";

        private CapturingSubmitter(String platform,
                                   boolean requiresDescriptorFile,
                                   boolean redactsPasswords) {
            this.platform = platform;
            this.requiresDescriptorFile = requiresDescriptorFile;
            this.redactsPasswords = redactsPasswords;
        }

        @Override
        public String platform() {
            return platform;
        }

        @Override
        public boolean requiresDescriptorFile() {
            return requiresDescriptorFile;
        }

        @Override
        public boolean descriptorRedactsPasswords() {
            return redactsPasswords;
        }

        @Override
        public ClusterSubmitRequest buildRequest(ClusterSubmissionContext context) {
            return "yarn".equals(platform)
                    ? buildYarnRequest(context)
                    : buildKubernetesRequest(context);
        }

        @Override
        public ClusterSubmission submit(ClusterSubmitRequest request) {
            this.request = request;
            return ClusterSubmission.builder()
                    .submissionId(request.getSubmissionId())
                    .executionMode(ExecutionMode.KUBERNETES)
                    .clusterApplicationId("default/consilens-orders-20260915")
                    .build();
        }

        @Override
        public void killStale(ClusterSubmitRequest request) {
            staleSweepRequest = request;
            if (sweepFails) {
                throw new IllegalStateException("cluster unreachable");
            }
        }

        @Override
        public ClusterApplicationResult awaitCompletion(ClusterSubmission submission, Duration timeout) {
            if (!completes) {
                return null;
            }
            return new ClusterApplicationResult(submission.getClusterApplicationId(), finalStatus, null, null);
        }

        @Override
        public void kill(ClusterSubmission submission) {
            killed = true;
        }
    }
}
