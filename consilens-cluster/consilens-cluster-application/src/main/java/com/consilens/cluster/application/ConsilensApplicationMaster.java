package com.consilens.cluster.application;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.yarn.api.records.FinalApplicationStatus;
import org.apache.hadoop.yarn.client.api.AMRMClient;
import org.apache.hadoop.yarn.conf.YarnConfiguration;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * YARN ApplicationMaster wrapper, following the same shape as Spark's
 * {@code org.apache.spark.deploy.yarn.ApplicationMaster}: register with the
 * ResourceManager, keep the application alive with allocate heartbeats, launch
 * the coordinator's main class in a child thread, and finish the application
 * with an explicit final status derived from the coordinator process outcome.
 * The Hadoop configuration (yarn-site.xml, core-site.xml) is shipped into this
 * container by the submitter as localized resources, so the client-side and
 * AM-side views of the cluster endpoints stay identical.
 */
public class ConsilensApplicationMaster {

    private static final long HEARTBEAT_SECONDS = 5;

    private final String[] arguments;
    private final AmClient client;
    private final PrintStream error;
    private final AtomicBoolean finished = new AtomicBoolean();
    private final ScheduledExecutorService heartbeatExecutor;
    /**
     * coordinator 写到 stderr 的失败原因。System.exit 会跳过 run() 的收尾，
     * 所以 shutdown hook 必须能从实例字段里读到这份内容。
     */
    private final ByteArrayOutputStream coordinatorErrorBuffer = new ByteArrayOutputStream();

    interface AmClient extends AutoCloseable {
        void register(String host);

        void allocate();

        void unregister(FinalApplicationStatus status, String diagnostics);

        @Override
        void close();
    }

    public static void main(String[] args) {
        try {
            new ConsilensApplicationMaster(args, new AmRmClientAdapter(new YarnConfiguration()), System.err).run();
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid ApplicationMaster arguments: " + e.getMessage());
            System.exit(2);
        } catch (Exception e) {
            System.err.println("ApplicationMaster setup failed: " + e.getClass().getName());
            System.exit(3);
        }
    }

    ConsilensApplicationMaster(String[] arguments, AmClient client, PrintStream error) {
        this.arguments = arguments == null ? new String[0] : arguments;
        this.client = client;
        this.error = error;
        this.heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "consilens-am-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    void run() throws Exception {
        AmOptions options = AmOptions.parse(arguments);
        if (options.secrets != null) {
            error.println("--secrets is accepted for CLI compatibility but ignored: "
                    + "credentials must be supplied via container environment variables");
        }
        // coordinator 若仍调用 System.exit，由这个 hook 兜底上报 FAILED；
        // 诊断信息带上已捕获的 stderr，避免只上报一句通用原因。
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            finish(FinalApplicationStatus.FAILED,
                    failureDiagnostics("Coordinator process exited with a non-zero status"));
            // coordinator 的 System.exit 会跳过 run() 末尾的清理；staging 内含明文密码 descriptor
            cleanupStaging(options.stagingDir);
        }, "consilens-am-shutdown"));
        client.register(containerHost());
        heartbeatExecutor.scheduleWithFixedDelay(this::heartbeat,
                HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);

        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger exitCode =
                new java.util.concurrent.atomic.AtomicInteger();
        // 捕获 coordinator 的 stdout/stderr：stdout 的摘要 JSON 是服务端还原统计的来源，
        // stderr 是失败时唯一能拿到的异常原因。两路都回写原始流，容器日志不丢内容。
        ByteArrayOutputStream summaryBuffer = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        System.setOut(tee(originalOut, summaryBuffer));
        System.setErr(tee(originalErr, coordinatorErrorBuffer));
        Thread coordinatorThread;
        try {
            coordinatorThread = launchCoordinator(options, failure, exitCode);
            coordinatorThread.join();
        } finally {
            System.out.flush();
            System.err.flush();
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        Throwable error = failure.get();
        if (error != null) {
            // 协调器失败时绝不能报 SUCCEEDED：那会让服务端把空统计当成"零差异"，
            // 是"看起来成功、实际没比"的最危险形态。
            Throwable cause = error instanceof java.lang.reflect.InvocationTargetException
                    && error.getCause() != null ? error.getCause() : error;
            finish(FinalApplicationStatus.FAILED,
                    failureDiagnostics("Coordinator failed: " + cause.getClass().getName()
                            + (cause.getMessage() != null ? ": " + cause.getMessage() : "")));
        } else if (exitCode.get() != 0) {
            // coordinator 有 execute(String[]) 时不再 System.exit，AM 能拿到真实退出码。
            finish(FinalApplicationStatus.FAILED,
                    failureDiagnostics("Coordinator exited with code " + exitCode.get()));
        } else {
            finish(FinalApplicationStatus.SUCCEEDED, extractSummary(summaryBuffer.toString(StandardCharsets.UTF_8)));
        }
        cleanupStaging(options.stagingDir);
    }

    private static PrintStream tee(PrintStream original, ByteArrayOutputStream buffer) {
        return new PrintStream(new java.io.OutputStream() {
            @Override
            public void write(int b) {
                original.write(b);
                buffer.write(b);
            }

            @Override
            public void write(byte[] bytes, int offset, int length) {
                original.write(bytes, offset, length);
                buffer.write(bytes, offset, length);
            }
        }, true, StandardCharsets.UTF_8);
    }

    private String failureDiagnostics(String reason) {
        String captured = coordinatorErrorBuffer.toString(StandardCharsets.UTF_8);
        if (captured.isBlank()) {
            return reason;
        }
        String tail = captured.stripTrailing();
        int max = 3000;
        if (tail.length() > max) {
            tail = "..." + tail.substring(tail.length() - max);
        }
        return reason + "\n" + tail;
    }

    private Thread launchCoordinator(AmOptions options,
                                     java.util.concurrent.atomic.AtomicReference<Throwable> failure,
                                     java.util.concurrent.atomic.AtomicInteger exitCode) {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean launched = new AtomicBoolean();
        Thread coordinatorThread = new Thread(() -> {
            try {
                Class<?> coordinatorClass = Class.forName(options.coordinatorClass);
                // 必须先置 launched 再放行等待方：反序会让主线程在 countDown 后看到
                // launched=false，误判“启动失败”而 System.exit(3)。
                launched.set(true);
                started.countDown();
                Method execute = executeMethod(coordinatorClass);
                if (execute != null) {
                    Object result = execute.invoke(null, (Object) options.coordinatorArguments());
                    if (result instanceof Number) {
                        exitCode.set(((Number) result).intValue());
                    }
                } else {
                    Method main = coordinatorClass.getMethod("main", String[].class);
                    main.invoke(null, (Object) options.coordinatorArguments());
                }
            } catch (Throwable e) {
                failure.set(e);
                started.countDown();
                if (!launched.get()) {
                    error.println("Unable to launch coordinator " + options.coordinatorClass
                            + ": " + e.getClass().getName());
                }
            }
        }, "consilens-coordinator");
        coordinatorThread.start();
        try {
            started.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.exit(3);
        }
        if (!launched.get()) {
            System.exit(3);
        }
        return coordinatorThread;
    }

    private static Method executeMethod(Class<?> coordinatorClass) {
        try {
            Method method = coordinatorClass.getMethod("execute", String[].class);
            Class<?> returnType = method.getReturnType();
            return returnType == int.class || returnType == Integer.class ? method : null;
        } catch (NoSuchMethodException ignored) {
            // 兼容只提供 main 的 coordinator
            return null;
        }
    }

    /** 取 stdout 里最后一行形如 {"submissionId":...} 的摘要；最多保留 4000 字符。 */
    static String extractSummary(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        String summary = null;
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("{") && trimmed.contains("submissionId")) {
                summary = trimmed;
            }
        }
        if (summary == null) {
            return null;
        }
        return summary.length() > 4000 ? summary.substring(0, 4000) : summary;
    }

    private void heartbeat() {
        try {
            client.allocate();
        } catch (Exception e) {
            // The next heartbeat retries; a single failed RPC must not kill the AM.
            error.println("AM heartbeat failed: " + e.getClass().getName());
        }
    }

    private void finish(FinalApplicationStatus status, String diagnostics) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        heartbeatExecutor.shutdownNow();
        client.unregister(status, diagnostics);
        client.close();
    }

    private void cleanupStaging(String stagingDir) {
        if (stagingDir == null || stagingDir.isBlank()) {
            return;
        }
        try {
            Path path = new Path(stagingDir);
            FileSystem fileSystem = path.getFileSystem(new Configuration());
            fileSystem.delete(path, true);
        } catch (Exception e) {
            // Cleanup is best effort; a failed delete must not mask the result.
            error.println("Staging cleanup failed: " + e.getClass().getName());
        }
    }

    private String containerHost() {
        String nodeHost = System.getenv("NM_HOST");
        if (nodeHost != null && !nodeHost.trim().isEmpty()) {
            return nodeHost.trim();
        }
        try {
            return InetAddress.getLocalHost().getCanonicalHostName();
        } catch (Exception e) {
            return "localhost";
        }
    }

    private static final class AmOptions {
        private final String coordinatorClass;
        private final String descriptor;
        private final String stagingDir;
        private final String secrets;

        private AmOptions(String coordinatorClass, String descriptor, String stagingDir, String secrets) {
            this.coordinatorClass = coordinatorClass;
            this.descriptor = descriptor;
            this.stagingDir = stagingDir;
            this.secrets = secrets;
        }

        static AmOptions parse(String[] arguments) {
            String coordinatorClass = null;
            String descriptor = null;
            String stagingDir = null;
            String secrets = null;
            for (int i = 0; i < arguments.length; i += 2) {
                if (i + 1 >= arguments.length) {
                    throw new IllegalArgumentException("option without value: " + arguments[i]);
                }
                String value = arguments[i + 1];
                if ("--coordinator-class".equals(arguments[i])) {
                    coordinatorClass = value;
                } else if ("--descriptor".equals(arguments[i])) {
                    descriptor = value;
                } else if ("--staging-dir".equals(arguments[i])) {
                    stagingDir = value;
                } else if ("--secrets".equals(arguments[i])) {
                    // 兼容提交端下发的命令行：凭据实际来自容器环境变量，这里只接受参数，
                    // 避免文档里的 AM 命令因未知参数直接启动失败。
                    secrets = value;
                } else {
                    throw new IllegalArgumentException("unknown option: " + arguments[i]);
                }
            }
            if (coordinatorClass == null || descriptor == null) {
                throw new IllegalArgumentException("--coordinator-class and --descriptor are required");
            }
            return new AmOptions(coordinatorClass, descriptor, stagingDir, secrets);
        }

        String[] coordinatorArguments() {
            return new String[]{"consilens", descriptor};
        }
    }

    static final class AmRmClientAdapter implements AmClient {

        private final AMRMClient<AMRMClient.ContainerRequest> delegate;

        AmRmClientAdapter(Configuration configuration) {
            this.delegate = AMRMClient.createAMRMClient();
            delegate.init(configuration);
            delegate.start();
        }

        @Override
        public void register(String host) {
            try {
                delegate.registerApplicationMaster(host, -1, null);
            } catch (Exception e) {
                throw new IllegalStateException("Unable to register with the ResourceManager", e);
            }
        }

        @Override
        public void allocate() {
            try {
                delegate.allocate(0);
            } catch (Exception e) {
                throw new IllegalStateException("AM allocate failed", e);
            }
        }

        @Override
        public void unregister(FinalApplicationStatus status, String diagnostics) {
            try {
                delegate.unregisterApplicationMaster(status, diagnostics, null);
            } catch (Exception e) {
                // The attempt outcome is already decided; a failed RPC must not mask it.
                System.err.println("AM unregister failed: " + e.getClass().getName());
            }
        }

        @Override
        public void close() {
            delegate.stop();
        }
    }
}
