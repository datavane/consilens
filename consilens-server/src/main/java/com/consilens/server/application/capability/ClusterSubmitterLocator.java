package com.consilens.server.application.capability;

import com.consilens.cluster.api.ClusterSubmitter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * 按 platform 查找 {@link ClusterSubmitter}。实现通过
 * {@code META-INF/services} 注册并由 {@link ServiceLoader} 发现；submitter
 * 内部延迟创建 Hadoop/Fabric8 客户端，因此 local 平台无需相关依赖即可运行，
 * 未选中的后端也不会建立集群连接。
 */
@Component
public class ClusterSubmitterLocator {

    public ClusterSubmitter locate(String platform) {
        String normalized = platform == null ? "" : platform.trim().toLowerCase(Locale.ROOT);
        List<String> available = new ArrayList<>();
        try {
            for (ClusterSubmitter submitter : ServiceLoader.load(ClusterSubmitter.class)) {
                String name = submitter.platform();
                if (name == null || name.isBlank()) {
                    continue;
                }
                String candidate = name.trim().toLowerCase(Locale.ROOT);
                available.add(candidate);
                if (candidate.equals(normalized)) {
                    return submitter;
                }
            }
        } catch (ServiceConfigurationError | LinkageError e) {
            throw new IllegalStateException("cluster submitter is unavailable on the classpath: " + normalized
                    + " (add consilens-cluster-yarn / consilens-cluster-kubernetes and their runtime "
                    + "dependencies to the server distribution)", e);
        }
        if (available.isEmpty()) {
            throw new IllegalStateException("cluster submitter is unavailable on the classpath: " + normalized
                    + " (no ClusterSubmitter providers found; add consilens-cluster-yarn / "
                    + "consilens-cluster-kubernetes to the server distribution)");
        }
        throw new IllegalStateException("unsupported cluster platform: " + normalized
                + " (available cluster submitters: " + available + ")");
    }
}
