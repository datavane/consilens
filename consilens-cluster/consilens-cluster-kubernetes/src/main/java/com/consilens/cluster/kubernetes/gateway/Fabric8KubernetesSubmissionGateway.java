package com.consilens.cluster.kubernetes.gateway;

import io.fabric8.kubernetes.api.model.ContainerStateTerminated;
import io.fabric8.kubernetes.api.model.ContainerStateWaiting;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Production gateway using the standard Fabric8 client configuration chain.
 */
public class Fabric8KubernetesSubmissionGateway implements KubernetesSubmissionGateway {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(Fabric8KubernetesSubmissionGateway.class);

    private final KubernetesClient client;

    public Fabric8KubernetesSubmissionGateway() {
        this(new KubernetesClientBuilder().build());
    }

    Fabric8KubernetesSubmissionGateway(KubernetesClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public Job create(Job job) {
        return client.batch().v1().jobs()
                .inNamespace(job.getMetadata().getNamespace())
                .resource(job)
                .create();
    }

    @Override
    public ConfigMap createConfigMap(ConfigMap configMap) {
        return client.configMaps()
                .inNamespace(configMap.getMetadata().getNamespace())
                .resource(configMap)
                .createOrReplace();
    }

    @Override
    public java.util.Optional<String> jobCompletionStatus(String namespace, String jobName) {
        Job job = client.batch().v1().jobs()
                .inNamespace(namespace)
                .withName(jobName)
                .get();
        if (job == null || job.getStatus() == null || job.getStatus().getConditions() == null) {
            return java.util.Optional.empty();
        }
        return job.getStatus().getConditions().stream()
                .filter(condition -> Boolean.parseBoolean(condition.getStatus()))
                .filter(condition -> "Complete".equals(condition.getType())
                        || "Failed".equals(condition.getType()))
                .map(io.fabric8.kubernetes.api.model.batch.v1.JobCondition::getType)
                .findFirst();
    }

    @Override
    public java.util.Optional<String> jobLogs(String namespace, String jobName) {
        try {
            List<Pod> pods = listJobPods(namespace, jobName);
            if (pods.isEmpty()) {
                return java.util.Optional.empty();
            }
            StringBuilder logs = new StringBuilder();
            for (Pod pod : pods) {
                if (pod.getMetadata() == null || pod.getMetadata().getName() == null) {
                    continue;
                }
                try {
                    String log = client.pods()
                            .inNamespace(namespace)
                            .withName(pod.getMetadata().getName())
                            .getLog();
                    if (log != null && !log.isBlank()) {
                        logs.append(log).append('\n');
                    }
                } catch (RuntimeException exception) {
                    // 单个 Pod 日志不可用（未启动/已被清理）不能丢弃其它 Pod 的日志
                    log.debug("skip logs for pod {}: {}", pod.getMetadata().getName(),
                            exception.getMessage());
                }
            }
            return logs.length() == 0 ? java.util.Optional.empty()
                    : java.util.Optional.of(logs.toString());
        } catch (RuntimeException exception) {
            // 日志是"有则更好"的附属信息，不能因为读日志失败把成功任务判成失败
            return java.util.Optional.empty();
        }
    }

    @Override
    public java.util.Optional<String> jobFailureDetails(String namespace, String jobName) {
        try {
            List<String> details = new ArrayList<>();
            Job job = client.batch().v1().jobs()
                    .inNamespace(namespace)
                    .withName(jobName)
                    .get();
            if (job != null && job.getStatus() != null && job.getStatus().getConditions() != null) {
                job.getStatus().getConditions().stream()
                        .filter(condition -> Boolean.parseBoolean(condition.getStatus()))
                        .filter(condition -> "Failed".equals(condition.getType()))
                        .forEach(condition -> addDetail(details, "job",
                                text(condition.getReason(), "Failed"), condition.getMessage()));
            }
            for (Pod pod : listJobPods(namespace, jobName)) {
                if (pod.getStatus() == null) {
                    continue;
                }
                String podName = pod.getMetadata() == null || pod.getMetadata().getName() == null
                        ? "pod" : pod.getMetadata().getName();
                addDetail(details, "pod " + podName,
                        pod.getStatus().getReason(), pod.getStatus().getMessage());
                for (ContainerStatus container : safe(pod.getStatus().getInitContainerStatuses())) {
                    addContainerDetail(details, podName, container);
                }
                for (ContainerStatus container : safe(pod.getStatus().getContainerStatuses())) {
                    addContainerDetail(details, podName, container);
                }
            }
            if (details.isEmpty()) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(limit(String.join("; ", details), 4000));
        } catch (RuntimeException exception) {
            // 失败详情是"有则更好"的附属信息：查询失败不能覆盖 Job 的终态
            log.warn("failed to read failure details for kubernetes job {}/{}: {}",
                    namespace, jobName, exception.getMessage());
            return java.util.Optional.empty();
        }
    }

    private List<Pod> listJobPods(String namespace, String jobName) {
        // Job 控制器打的标签在不同 K8s 版本里是 job-name 或 batch.kubernetes.io/job-name
        List<Pod> pods = client.pods()
                .inNamespace(namespace)
                .withLabel("job-name", jobName)
                .list()
                .getItems();
        if (pods == null || pods.isEmpty()) {
            pods = client.pods()
                    .inNamespace(namespace)
                    .withLabel("batch.kubernetes.io/job-name", jobName)
                    .list()
                    .getItems();
        }
        return pods == null ? List.of() : pods;
    }

    private static void addContainerDetail(List<String> details, String podName,
                                           ContainerStatus container) {
        if (container == null || container.getState() == null) {
            return;
        }
        String source = "pod " + podName + "/" + text(container.getName(), "container");
        ContainerStateWaiting waiting = container.getState().getWaiting();
        if (waiting != null) {
            addDetail(details, source + " waiting", waiting.getReason(), waiting.getMessage());
        }
        ContainerStateTerminated terminated = container.getState().getTerminated();
        if (terminated != null) {
            String message = terminated.getMessage();
            if (terminated.getExitCode() != null) {
                message = message == null || message.isBlank()
                        ? "exitCode=" + terminated.getExitCode()
                        : message + " (exitCode=" + terminated.getExitCode() + ")";
            }
            addDetail(details, source + " terminated", terminated.getReason(), message);
        }
    }

    private static void addDetail(List<String> details, String source,
                                  String reason, String message) {
        if ((reason == null || reason.isBlank()) && (message == null || message.isBlank())) {
            return;
        }
        StringBuilder detail = new StringBuilder(source);
        if (reason != null && !reason.isBlank()) {
            detail.append(": ").append(reason.trim());
        }
        if (message != null && !message.isBlank()) {
            detail.append(" - ").append(message.trim());
        }
        details.add(detail.toString());
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String limit(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    @Override
    public java.util.Optional<java.util.Map<String, String>> jobLabels(String namespace, String jobName) {
        Job job = client.batch().v1().jobs().inNamespace(namespace).withName(jobName).get();
        if (job == null || job.getMetadata() == null || job.getMetadata().getLabels() == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(job.getMetadata().getLabels());
    }

    @Override
    public void deleteJob(String namespace, String jobName) {
        client.batch().v1().jobs().inNamespace(namespace).withName(jobName).delete();
    }

    @Override
    public void deleteConfigMap(String namespace, String configMapName) {
        client.configMaps().inNamespace(namespace).withName(configMapName).delete();
    }

    @Override
    public void close() {
        client.close();
    }
}
