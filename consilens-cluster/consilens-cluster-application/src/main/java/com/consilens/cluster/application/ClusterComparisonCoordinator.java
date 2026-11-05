package com.consilens.cluster.application;

import com.consilens.cli.config.ConfigurationManager;
import com.consilens.cli.model.CliConfiguration;
import com.consilens.cli.service.CompareRequestFactory;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.core.compare.CompareRuntime;
import com.consilens.core.compare.DefaultCompareRuntime;
import com.consilens.core.diff.DiffResult;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Process entry point used by a YARN ApplicationMaster or Kubernetes Job. It is
 * a plain main class and knows nothing about the platform that launched it: the
 * YARN {@link ConsilensApplicationMaster} wrapper handles RM registration and
 * final-status reporting, exactly as Spark's ApplicationMaster wraps the user's
 * driver. Credentials are resolved from the application process environment
 * while loading the descriptor, so they never pass through the cluster
 * submission envelope.
 */
public class ClusterComparisonCoordinator {

    private final ComparisonDescriptorOpener descriptorOpener;
    private final ConfigurationManager configurationManager;
    private final CompareRequestFactory compareRequestFactory;
    private final CompareRuntime compareRuntime;
    private final ObjectMapper objectMapper;
    private final PrintStream output;
    private final PrintStream error;

    public ClusterComparisonCoordinator() {
        this(new UriComparisonDescriptorOpener(), new ConfigurationManager(), new CompareRequestFactory(),
                new DefaultCompareRuntime(), new ObjectMapper(), System.out, System.err);
    }

    ClusterComparisonCoordinator(ComparisonDescriptorOpener descriptorOpener,
                                 ConfigurationManager configurationManager,
                                 CompareRequestFactory compareRequestFactory,
                                 CompareRuntime compareRuntime,
                                 ObjectMapper objectMapper,
                                 PrintStream output,
                                 PrintStream error) {
        this.descriptorOpener = Objects.requireNonNull(descriptorOpener, "descriptorOpener");
        this.configurationManager = Objects.requireNonNull(configurationManager, "configurationManager");
        this.compareRequestFactory = Objects.requireNonNull(compareRequestFactory, "compareRequestFactory");
        this.compareRuntime = Objects.requireNonNull(compareRuntime, "compareRuntime");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.output = Objects.requireNonNull(output, "output");
        this.error = Objects.requireNonNull(error, "error");
    }

    /**
     * 不终止 JVM 的执行入口：YARN AM 通过反射调用它拿到退出码，再自行
     * unregister，避免 coordinator 的 System.exit 绕过 AM 的失败上报。
     */
    public static int execute(String[] args) {
        return new ClusterComparisonCoordinator().run(args);
    }

    public static void main(String[] args) {
        int exitCode = execute(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    public int run(String[] args) {
        if (args == null || args.length != 2) {
            error.println("Cluster comparison failed.");
            return 2;
        }
        try {
            ComparisonDescriptor descriptor = descriptorOpener.open(args[1]);
            CliConfiguration configuration;
            try (InputStream inputStream = descriptor.open()) {
                configuration = configurationManager.loadConfiguration(inputStream, descriptor.format());
            }
            CompareRequest request = compareRequestFactory.create(configuration);
            DiffResult result = compareRuntime.execute(request);
            output.println(objectMapper.writeValueAsString(summary(args[0], result)));
            return 0;
        } catch (Exception e) {
            // 集群容器里没有交互式排查手段：只打印一行 "failed" 会让线上失败无从定位，
            // 必须把异常类型、消息与堆栈完整写入 stderr（YARN 会聚合到容器日志）。
            error.println("Cluster comparison failed: " + e);
            e.printStackTrace(error);
            return 1;
        }
    }

    private Map<String, Object> summary(String submissionId, DiffResult result) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("submissionId", submissionId);
        summary.put("statistics", result.getStatisticsMap());
        return summary;
    }

}
