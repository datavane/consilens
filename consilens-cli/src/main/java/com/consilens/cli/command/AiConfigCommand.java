package com.consilens.cli.command;

import com.consilens.ai.config.model.AIConfigIssue;
import com.consilens.cli.ai.AIConfigResult;
import com.consilens.cli.ai.AIConfigService;
import com.consilens.cli.config.ConfigurationManager;
import com.consilens.cli.service.DiffService;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;

/**
 * Generates a production-shaped Consilens YAML configuration with AI assistance.
 */
@Slf4j
@Command(
    name = "config",
    description = "Generate a validated Consilens YAML configuration from a goal and explicit hints",
    mixinStandardHelpOptions = true
)
public class AiConfigCommand implements Callable<Integer> {

    private final AIConfigService configService;
    private final DiffService diffService;
    private final ConfigurationManager configurationManager;

    @Mixin
    private AIConfigCliOptions options = new AIConfigCliOptions();

    @Option(names = {"-o", "--output"}, description = "Output YAML file")
    private String output;

    @Option(names = "--dry-run", description = "Run DiffService dry-run after generating the config")
    private boolean dryRun;

    public AiConfigCommand() {
        this(new AIConfigService(), new DiffService(), new ConfigurationManager());
    }

    AiConfigCommand(AIConfigService configService,
                    DiffService diffService,
                    ConfigurationManager configurationManager) {
        this.configService = configService;
        this.diffService = diffService;
        this.configurationManager = configurationManager;
    }

    @Override
    public Integer call() {
        try {
            AIConfigResult result = configService.generate(options.toRequest());
            if (!result.isValid()) {
                printIssues(result);
                System.err.println("No file was written.");
                return 1;
            }

            if (dryRun) {
                diffService.performDryRun(loadGeneratedConfiguration(result.getYaml()));
            }

            if (output == null || output.isBlank()) {
                System.out.println(result.getYaml());
            } else {
                Path outputPath = Path.of(output).toAbsolutePath().normalize();
                if (outputPath.getParent() != null) {
                    Files.createDirectories(outputPath.getParent());
                }
                Files.writeString(outputPath, result.getYaml());
                System.out.println("[AI CONFIG] generated=" + outputPath);
            }
            System.out.println("[AI CONFIG] validation=passed");
            if (dryRun) {
                System.out.println("[AI CONFIG] dryRun=passed");
            }
            return 0;
        } catch (Exception e) {
            log.error("AI config generation failed", e);
            System.err.println("[AI CONFIG ERROR] " + e.getMessage());
            return 1;
        }
    }

    private void printIssues(AIConfigResult result) {
        System.err.println("[AI CONFIG ERROR] validation failed");
        if (result.getIssues() == null) {
            return;
        }
        for (AIConfigIssue issue : result.getIssues()) {
            System.err.printf("%s %s %s:%n  %s%n",
                    issue.getSeverity(), issue.getCode(), issue.getPath(), issue.getMessage());
        }
    }

    private com.consilens.cli.model.CliConfiguration loadGeneratedConfiguration(String yaml) throws Exception {
        return configurationManager.loadConfiguration(
                new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "yaml");
    }
}
