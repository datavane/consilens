package com.consilens.cli.command;

import com.consilens.cli.ai.AIExplainService;
import com.consilens.cli.config.ConfigurationManager;
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

    private final ConfigurationManager configurationManager;
    private final DiffService diffService;
    private final AIExplainService explainService;
    private final ObjectMapper rawConfigMapper;

    @Option(names = {"-c", "--config"}, required = true, description = "Configuration file path")
    private String configFile;

    @Option(names = "--dry-run", description = "Run DiffService dry-run before printing the explanation")
    private boolean dryRun;

    public AiExplainCommand() {
        this(new ConfigurationManager(), new DiffService(), new AIExplainService(), new ObjectMapper(new YAMLFactory()));
    }

    AiExplainCommand(ConfigurationManager configurationManager,
                     DiffService diffService,
                     AIExplainService explainService,
                     ObjectMapper rawConfigMapper) {
        this.configurationManager = configurationManager;
        this.diffService = diffService;
        this.explainService = explainService;
        this.rawConfigMapper = rawConfigMapper;
    }

    @Override
    public Integer call() {
        try {
            CliConfiguration config = dryRun ? configurationManager.loadConfiguration(configFile, false) : loadRawConfig();
            if (dryRun) {
                diffService.performDryRun(config);
            }
            System.out.print(explainService.explain(config));
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

    private CliConfiguration loadRawConfig() throws Exception {
        CliConfiguration config = rawConfigMapper.readValue(new File(configFile), CliConfiguration.class);
        config.validate();
        return config;
    }
}
