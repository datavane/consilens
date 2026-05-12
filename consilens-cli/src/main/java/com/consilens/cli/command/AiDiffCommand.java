package com.consilens.cli.command;

import com.consilens.ai.config.model.AIConfigIssue;
import com.consilens.cli.ai.AIConfigResult;
import com.consilens.cli.ai.AIConfigService;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Safe AI diff entrypoint that generates a config, but does not execute real diff yet.
 */
@Slf4j
@Command(
    name = "diff",
    description = "Generate a validated diff config from AI input without executing the real diff",
    mixinStandardHelpOptions = true
)
public class AiDiffCommand implements Callable<Integer> {

    private final AIConfigService configService;

    @Mixin
    private AIConfigCliOptions options = new AIConfigCliOptions();

    @Option(names = {"-o", "--output"}, required = true, description = "Output YAML file")
    private String output;

    @Option(names = "--execute", description = "Execute diff after generation. Not supported yet.")
    private boolean execute;

    public AiDiffCommand() {
        this(new AIConfigService());
    }

    AiDiffCommand(AIConfigService configService) {
        this.configService = configService;
    }

    @Override
    public Integer call() {
        if (execute) {
            System.err.println("`consilens ai diff --execute` is not supported yet. Generate YAML first, then run `consilens diff -c <file>` explicitly.");
            return 2;
        }
        try {
            AIConfigResult result = configService.generate(options.toRequest());
            if (!result.isValid()) {
                printIssues(result);
                System.err.println("No file was written.");
                return 1;
            }
            Path outputPath = Path.of(output).toAbsolutePath().normalize();
            if (outputPath.getParent() != null) {
                Files.createDirectories(outputPath.getParent());
            }
            Files.writeString(outputPath, result.getYaml());
            System.out.println("[AI DIFF] generated=" + outputPath);
            System.out.println("[AI DIFF] validation=passed");
            System.out.println("Next:");
            System.out.println("  consilens diff --dry-run -c " + outputPath);
            System.out.println("  consilens diff -c " + outputPath);
            return 0;
        } catch (Exception e) {
            log.error("AI diff config generation failed", e);
            System.err.println("[AI DIFF ERROR] " + e.getMessage());
            return 1;
        }
    }

    private void printIssues(AIConfigResult result) {
        System.err.println("[AI DIFF ERROR] validation failed");
        if (result.getIssues() == null) {
            return;
        }
        for (AIConfigIssue issue : result.getIssues()) {
            System.err.printf("%s %s %s:%n  %s%n",
                    issue.getSeverity(), issue.getCode(), issue.getPath(), issue.getMessage());
        }
    }
}
