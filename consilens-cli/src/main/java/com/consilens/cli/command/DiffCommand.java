package com.consilens.cli.command;

import com.consilens.cli.config.ConfigurationManager;
import com.consilens.cli.model.CliConfiguration;
import com.consilens.cli.model.CliDiffResult;
import com.consilens.cli.service.DiffService;
import com.consilens.cli.service.SensitiveValueMasker;

import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Picocli subcommand for performing data comparison (diff) operations.
 */
@Slf4j
@Command(
    name = "diff",
    description = "Perform data comparison between source and target databases",
    mixinStandardHelpOptions = true
)
public class DiffCommand implements Runnable {

    @Option(names = {"-c", "--config"}, required = true, description = "Configuration file path")
    private String configFile;

    @Option(names = {"--dry-run"}, description = "Perform a dry run without actual comparison")
    private boolean dryRun;

    @Option(names = {"--verbose"}, description = "Enable verbose logging")
    private boolean verbose;

    @Override
    public void run() {
        try {
            ConfigurationManager configurationManager = new ConfigurationManager();
            DiffService diffService = new DiffService();

            CliConfiguration config = configurationManager.loadConfiguration(configFile);

            if (verbose) {
                log.info("Starting diff operation with configuration:");
                log.info("  Strategy: {}", config.getStrategyMode());
                log.info("  Algorithm: {}", config.getAlgorithm());
                log.info("  Source: {}", SensitiveValueMasker.maskJdbcUrl(config.getSource().getUrl()));
                log.info("  Source Resource: {}", resourceDisplay(config.getSource()));
                log.info("  Target: {}", SensitiveValueMasker.maskJdbcUrl(config.getTarget().getUrl()));
                log.info("  Target Resource: {}", resourceDisplay(config.getTarget()));
                log.info("  Source Key Columns: {}", config.getComparison().getKeys().getSource());
                log.info("  Target Key Columns: {}", config.getComparison().getKeys().getTarget());
                if (config.getComparison().getFields() != null
                        && config.getComparison().getFields().getSource() != null
                        && !config.getComparison().getFields().getSource().isEmpty()) {
                    log.info("  Source Fields: {}", config.getComparison().getFields().getSource());
                }
                if (config.getComparison().getFields() != null
                        && config.getComparison().getFields().getTarget() != null
                        && !config.getComparison().getFields().getTarget().isEmpty()) {
                    log.info("  Target Fields: {}", config.getComparison().getFields().getTarget());
                }
                log.info("  Concurrency: {}", config.getConcurrency());
                log.info("  Batch Size: {}", config.getStrategy().getBatchSize());
            }

            CliDiffResult result;
            if (dryRun) {
                log.info("Performing dry run (validation only)...");
                result = diffService.performDryRun(config);
            } else {
                log.info("Starting diff operation...");
                log.info("This may take a while depending on table sizes and strategy chosen.");

                long startTime = System.currentTimeMillis();
                result = diffService.performDiff(config);
                long totalTime = System.currentTimeMillis() - startTime;

                log.info("Diff operation completed in {} ms", totalTime);
            }

            displayDiffResults(result, dryRun);

        } catch (Exception e) {
            log.error("Diff operation failed", e);
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private void displayDiffResults(CliDiffResult result, boolean isDryRun) {
        log.info("Diff Results" + (isDryRun ? " (Dry Run)" : "") + ":");
        log.info("=".repeat(50));
        log.info("Strategy: " + result.getStrategy());
        log.info("Source Row Count: " + result.getSourceRowCount());
        log.info("Target Row Count: " + result.getTargetRowCount());

        if (isDryRun) {
            log.info("Dry run completed successfully!");
            log.info("Database connections validated and table counts retrieved.");
            log.info("Run without --dry-run to perform actual diff operation.");
        } else {
            log.info("Differences Found:");
            log.info("  Source missing rows: " + result.getSourceMissingCount());
            log.info("  Target missing rows: " + result.getTargetMissingCount());
            log.info("  Mismatched rows: " + result.getMismatchCount());
            log.info("  Total differences: " + result.getTotalDifferences());

            if (result.getTotalDifferences() == 0) {
                log.info("No differences found! The tables are identical.");
            } else {
                log.info("Differences detected. Check the configured result sinks for details.");
            }

            if (result.getDurationMs() != null) {
                log.info("Operation duration: " + result.getDurationMs() + " ms");
            }
        }
    }

    private String resourceDisplay(com.consilens.cli.model.ConnectionConfig connectionConfig) {
        if (connectionConfig == null || connectionConfig.getResource() == null) {
            return "(not set)";
        }
        com.consilens.cli.model.ConnectionConfig.ResourceConfig resource = connectionConfig.getResource();
        String location = "sql".equalsIgnoreCase(resource.getType())
                ? resource.getPath()
                : resource.getName();
        return resource.getType() + ":" + location;
    }
}
