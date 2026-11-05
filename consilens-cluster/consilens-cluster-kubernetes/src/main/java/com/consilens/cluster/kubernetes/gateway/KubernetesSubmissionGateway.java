package com.consilens.cluster.kubernetes.gateway;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.batch.v1.Job;

/**
 * Boundary around Fabric8 so submission mapping can be tested without an API server.
 */
public interface KubernetesSubmissionGateway extends AutoCloseable {

    Job create(Job job);

    ConfigMap createConfigMap(ConfigMap configMap);

    /**
     * Terminal completion status of the Job ({@code Complete} or
     * {@code Failed}), or empty while the Job is still running or unknown,
     * so the client can block like spark-submit on Kubernetes does.
     */
    default java.util.Optional<String> jobCompletionStatus(String namespace, String jobName) {
        return java.util.Optional.empty();
    }

    /**
     * 作业 Pod 的 stdout 尾部（用于回传 coordinator 摘要 JSON）。不可用或实现
     * 不支持时返回 empty，调用方据此把统计标为 unavailable，而不是当成零差异。
     */
    default java.util.Optional<String> jobLogs(String namespace, String jobName) {
        return java.util.Optional.empty();
    }

    /**
     * Job 失败时 Kubernetes 自己记录的原因：Job condition 的 reason/message
     * 以及 Pod 容器的 waiting/terminated 状态。覆盖 coordinator 还没启动
     * （镜像拉取、调度、OOM）因而没有日志可读的失败。
     */
    default java.util.Optional<String> jobFailureDetails(String namespace, String jobName) {
        return java.util.Optional.empty();
    }

    /**
     * Job 的 labels；Job 不存在或实现不支持时返回 empty。
     *
     * <p>用于清扫陈旧作业前判断"这个 Job 是不是本系统创建的"：jobName 可以由
     * 调用方自定义，不能靠前缀猜归属。
     */
    default java.util.Optional<java.util.Map<String, String>> jobLabels(String namespace, String jobName) {
        return java.util.Optional.empty();
    }

    /**
     * Deletes the Job (cascading to pods); idempotent.
     */
    default void deleteJob(String namespace, String jobName) {
    }

    /**
     * Deletes the descriptor ConfigMap; idempotent. Called once the Job is in a
     * terminal state (or killed) so descriptor data does not accumulate.
     */
    default void deleteConfigMap(String namespace, String configMapName) {
    }

    @Override
    void close();
}
