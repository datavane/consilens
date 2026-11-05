package com.consilens.server.boot;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@ConfigurationProperties(prefix = "consilens.server")
public class ConsilensServerProperties {

    private final Node node = new Node();
    private final Scheduler scheduler = new Scheduler();
    private final Cluster cluster = new Cluster();
    private final Artifact artifact = new Artifact();
    private final Database database = new Database();
    private final Security security = new Security();
    private final Api api = new Api();
    private final RunLog runLog = new RunLog();

    /**
     * 集群提交：把数据源里的 loopback 地址替换成集群内可达的地址。
     * 本机开发时数据源统一写 127.0.0.1（宿主机可直连），而 YARN AM / K8s Pod
     * 需要 host.docker.internal / 集群 Service 名，提交 descriptor 时按平台替换。
     */
    @Data
    public static class Cluster {
        /** 平台（yarn/kubernetes）→ 替代 host。 */
        private Map<String, String> loopbackHosts = new LinkedHashMap<>();
        /** 平台（yarn/kubernetes）→ 替代端口（集群内容器/Service 的端口可能与宿主机不同）。 */
        private Map<String, Integer> loopbackPorts = new LinkedHashMap<>();
    }

    /**
     * 运行实例日志：按 instanceKey 落盘为独立文件，供实例详情页的“运行日志”Tab 读取。
     */
    @Data
    public static class RunLog {
        private boolean enabled = true;
        private String dir = "./.consilens-server/logs";
    }

    @Data
    public static class Node {
        private String nodeKey;
        private int heartbeatIntervalSeconds = 2;
        private int expireSeconds = 30;
    }

    @Data
    public static class Scheduler {
        private boolean enabled = true;
        private int commandPollIntervalMs = 1000;
        private int claimLeaseSeconds = 15;
        private int executeThreads = 2;
        private int executeQueueCapacity = 1024;
        private int recoveryBatchSize = 64;
        private int maxRetryCount = 3;
    }

    @Data
    public static class Artifact {
        private String storageType = "local-file";
        private String localBaseDir = "./.consilens-server/artifacts";
        private long maxContentBytes = 50L * 1024L * 1024L;
        /** 运行结果 artifact 内联的差异样本行数上限。 */
        private int diffSampleRows = 1000;
    }

    @Data
    public static class Database {
        private boolean allowEmbedded = false;
        private String timeZone = "";
    }

    @Data
    public static class Security {
        private boolean enabled = true;
        private String apiKey;
    }

    @Data
    public static class Api {
        private long maxRequestBodyBytes = 10L * 1024L * 1024L;
    }
}
