package com.consilens.cli.command;

import com.consilens.cli.ai.AIExplainService;
import com.consilens.cli.model.CliConfiguration;
import com.consilens.cli.service.DiffService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.util.concurrent.Callable;

/**
 * Explains a Consilens configuration using deterministic engine facts.
 */
@Slf4j
@Command(
    name = "explain",
    description = "Explain a Consilens YAML configuration and its execution risks",
    mixinStandardHelpOptions = true
)
public class AiExplainCommand implements Callable<Integer> {

    @Option(names = {"-c", "--config"}, required = true, description = "Configuration file path")
    private String configFile;

    @Option(names = "--dry-run", description = "Run DiffService dry-run before printing the explanation")
    private boolean dryRun;

    @Override
    public Integer call() {
        try {
            ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
            CliConfiguration config = mapper.readValue(new File(configFile), CliConfiguration.class);
            config.validate();
            if (dryRun) {
                new DiffService().performDryRun(config);
            }
            System.out.print(new AIExplainService().explain(config));
            if (dryRun) {
                System.out.println();
                System.out.println("Dry run:");
                System.out.println("  passed");
            }
            return 0;
        } catch (Exception e) {
            log.error("AI explain failed", e);
            System.err.println("[AI EXPLAIN ERROR] " + e.getMessage());
            return 1;
        }
    }
}
