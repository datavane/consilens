package com.consilens.cli.command;

import com.consilens.ai.spi.AIAnalyzerManager;
import com.consilens.cli.ai.AIDiagnoseService;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.function.Function;

/**
 * Diagnoses existing diff evidence with deterministic rule-based analysis.
 */
@Slf4j
@Command(
    name = "diagnose",
    description = "Diagnose an existing diff result or diff-record JSON file",
    mixinStandardHelpOptions = true
)
public class AiDiagnoseCommand implements Callable<Integer> {

    private static final String DEFAULT_ANALYZER = "rulebased";

    private final Function<String, AIDiagnoseService> diagnoseServiceFactory;

    @Option(names = "--result", required = true, description = "Path to DiffResult JSON or diff-record JSON array")
    private String resultPath;

    @Option(names = "--analyzer", description = "Analyzer provider name. Defaults to CONSILENS_AI_ANALYZER or rulebased")
    private String analyzer;

    @Option(names = {"-o", "--output"}, description = "Write diagnosis report to a file instead of stdout")
    private String output;

    public AiDiagnoseCommand() {
        this(name -> new AIDiagnoseService(AIAnalyzerManager.getInstance().create(name)));
    }

    AiDiagnoseCommand(Function<String, AIDiagnoseService> diagnoseServiceFactory) {
        this.diagnoseServiceFactory = diagnoseServiceFactory;
    }

    @Override
    public Integer call() {
        try {
            String report = diagnoseServiceFactory.apply(resolveAnalyzer()).diagnose(resultPath);
            if (output == null || output.isBlank()) {
                System.out.print(report);
            } else {
                Path outputPath = Path.of(output).toAbsolutePath().normalize();
                if (outputPath.getParent() != null) {
                    Files.createDirectories(outputPath.getParent());
                }
                Files.writeString(outputPath, report);
                System.out.println("[AI DIAGNOSE] report=" + outputPath);
            }
            return 0;
        } catch (Exception e) {
            log.error("AI diagnose failed", e);
            System.err.println("[AI DIAGNOSE ERROR] " + e.getMessage());
            return 1;
        }
    }

    private String resolveAnalyzer() {
        if (analyzer != null && !analyzer.isBlank()) {
            return analyzer.trim();
        }
        String envAnalyzer = System.getenv("CONSILENS_AI_ANALYZER");
        if (envAnalyzer != null && !envAnalyzer.isBlank()) {
            return envAnalyzer.trim();
        }
        return DEFAULT_ANALYZER;
    }
}
