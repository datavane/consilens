package com.consilens.cli.command;

import com.consilens.cli.config.ConfigurationManager;
import com.consilens.cli.model.CliConfiguration;
import com.consilens.cli.service.CompareRequestFactory;
import com.consilens.cluster.api.ClusterComparisonDescription;
import com.consilens.cluster.api.ClusterSubmitRequest;
import com.consilens.cluster.api.ClusterSubmission;
import com.consilens.cluster.runtime.LocalClusterSubmitter;
import com.consilens.cluster.runtime.LocalSimulationRequest;
import com.consilens.cluster.runtime.LocalSimulationResult;
import com.consilens.cluster.runtime.LocalSubmissionTaskProvider;
import com.consilens.cluster.runtime.LocalSplitTask;
import com.consilens.connector.api.planner.ClusterExecutionSpec;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.connector.api.planner.ExecutionMode;
import com.consilens.core.compare.CompareRuntime;
import com.consilens.core.compare.DefaultCompareRuntime;
import com.consilens.core.diff.DiffResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Executes one compare request through the Phase 0 local Coordinator/Worker simulator.
 */
@Command(
        name = "local",
        description = "Submit a comparison to the local simulator",
        mixinStandardHelpOptions = true
)
public class SubmitLocalCommand implements Callable<Integer> {

    private final ConfigurationManager configurationManager;
    private final CompareRequestFactory compareRequestFactory;
    private final Supplier<CompareRuntime> compareRuntimeFactory;
    private final Function<LocalSubmissionTaskProvider, LocalClusterSubmitter> submitterFactory;
    private final Supplier<String> submissionIdFactory;

    @Option(names = {"-c", "--config"}, required = true, description = "Configuration file path")
    private String configFile;

    @Option(names = "--manifest-dir", defaultValue = "./consilens-submissions",
            description = "Directory for the completed submission manifest")
    private Path manifestDirectory;

    @Option(names = "--max-attempts", defaultValue = "1",
            description = "Maximum attempts for each simulated split")
    private int maximumAttempts;

    @Spec
    private CommandSpec commandSpec;

    public SubmitLocalCommand() {
        this(new ConfigurationManager(), new CompareRequestFactory(), DefaultCompareRuntime::new,
                LocalClusterSubmitter::new, () -> UUID.randomUUID().toString());
    }

    SubmitLocalCommand(ConfigurationManager configurationManager,
                       CompareRequestFactory compareRequestFactory,
                       Supplier<CompareRuntime> compareRuntimeFactory,
                       Function<LocalSubmissionTaskProvider, LocalClusterSubmitter> submitterFactory,
                       Supplier<String> submissionIdFactory) {
        this.configurationManager = configurationManager;
        this.compareRequestFactory = compareRequestFactory;
        this.compareRuntimeFactory = compareRuntimeFactory;
        this.submitterFactory = submitterFactory;
        this.submissionIdFactory = submissionIdFactory;
    }

    @Override
    public Integer call() {
        try {
            CliConfiguration configuration = configurationManager.loadConfiguration(configFile);
            CompareRequest request = compareRequestFactory.create(configuration);
            request.setClusterExecutionSpec(ClusterExecutionSpec.builder()
                    .executionMode(ExecutionMode.LOCAL)
                    .maxAttempts(maximumAttempts)
                    .build());
            String submissionId = submissionIdFactory.get();
            CompareRuntime compareRuntime = compareRuntimeFactory.get();
            ClusterSubmitRequest submissionRequest = ClusterSubmitRequest.from(
                    submissionId,
                    request,
                    ClusterComparisonDescription.builder()
                            .sourceConfigRef("in-process/source")
                            .targetConfigRef("in-process/target")
                            .build());
            LocalClusterSubmitter submitter = submitterFactory.apply(submission -> LocalSimulationRequest.builder()
                    .submissionId(submission.getSubmissionId())
                    .executionSpec(request.getClusterExecutionSpec())
                    .splitTasks(List.of(new CompareRequestSplitTask(request, compareRuntime)))
                    .manifestDirectory(manifestDirectory)
                    .build());
            ClusterSubmission submission = submitter.submit(submissionRequest);
            LocalSimulationResult result = submitter.findResult(submission.getSubmissionId())
                    .orElseThrow(() -> new IllegalStateException("Local submission completed without a result"));
            commandSpec.commandLine().getOut().println("Local submission completed: " + result.getManifestPath());
            return 0;
        } catch (Exception e) {
            java.io.PrintWriter errorWriter = commandSpec.commandLine().getErr();
            if (e instanceof IllegalArgumentException
                    || e instanceof com.consilens.cli.config.ConfigurationException) {
                // 配置/校验错误的 message 不含凭据，直接展示便于定位
                errorWriter.println("Local submission failed: " + e.getMessage());
            } else {
                // 运行时异常可能内嵌连接 URL/凭据，只输出类型；完整堆栈需显式开启
                errorWriter.println("Local submission failed: " + e.getClass().getName());
            }
            if (System.getenv("CONSILENS_DEBUG") != null) {
                e.printStackTrace(errorWriter);
            }
            return 1;
        }
    }

    private static final class CompareRequestSplitTask implements LocalSplitTask {

        private final CompareRequest request;
        private final CompareRuntime compareRuntime;

        private CompareRequestSplitTask(CompareRequest request, CompareRuntime compareRuntime) {
            this.request = request;
            this.compareRuntime = compareRuntime;
        }

        @Override
        public String getSplitId() {
            return "local-compare";
        }

        @Override
        public DiffResult execute() throws Exception {
            return compareRuntime.execute(request);
        }
    }
}
