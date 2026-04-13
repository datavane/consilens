package com.consilens.cli.command;

import com.consilens.cli.config.ConfigurationManager;
import com.consilens.cli.model.CliConfiguration;
import com.consilens.cli.service.DatabaseAdapterFactory;
import com.consilens.core.database.adpter.DatabaseAdapter;

import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Picocli subcommand for validating configuration files.
 */
@Slf4j
@Command(
    name = "validate",
    description = "Validate configuration file",
    mixinStandardHelpOptions = true
)
public class ConfigValidateCommand implements Runnable {

    @Option(names = {"-c", "--config"}, required = true, description = "Configuration file path")
    private String configFile;

    @Option(names = {"--test-connection"}, description = "Test database connections after validation")
    private boolean testConnection;

    @Option(names = {"--verbose"}, description = "Show detailed validation results")
    private boolean verbose;

    @Override
    public void run() {
        try {
            ConfigurationManager configurationManager = new ConfigurationManager();

            String absolutePath = new java.io.File(configFile).getAbsolutePath();
            ConfigurationManager.ValidationResult result =
                    configurationManager.validateConfigurationFile(absolutePath);

            if (result.isValid()) {
                System.out.println("✓ Configuration file is valid");
                System.out.println("✓ File format: " + configurationManager.detectFormat(configFile));

                if (verbose) {
                    printVerboseDetails(configurationManager, absolutePath);
                }

                if (testConnection) {
                    testDatabaseConnections(configurationManager, absolutePath);
                }
            } else {
                System.err.println("✗ Configuration validation failed: " + result.getMessage());
                System.exit(1);
            }
        } catch (Exception e) {
            log.error("Failed to validate configuration", e);
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Print detailed configuration information for verbose mode.
     */
    private void printVerboseDetails(ConfigurationManager configurationManager, String absolutePath) {
        try {
            CliConfiguration config = configurationManager.loadConfiguration(absolutePath);
            System.out.println();
            System.out.println("── Configuration Details ──────────────────────────────");

            // Source connection
            if (config.getSource() != null) {
                System.out.println("  Source:");
                System.out.println("    Type   : " + nvl(config.getSource().getType()));
                System.out.println("    URL    : " + nvl(config.getSource().getUrl()));
                System.out.println("    User   : " + nvl(config.getSource().getUsername()));
            }

            // Target connection
            if (config.getTarget() != null) {
                System.out.println("  Target:");
                System.out.println("    Type   : " + nvl(config.getTarget().getType()));
                System.out.println("    URL    : " + nvl(config.getTarget().getUrl()));
                System.out.println("    User   : " + nvl(config.getTarget().getUsername()));
            }

            // Comparison
            if (config.getComparison() != null) {
                System.out.println("  Comparison:");
                if (config.getComparison().getTables() != null) {
                    System.out.println("    Source table : " + nvl(config.getComparison().getTables().getSource()));
                    System.out.println("    Target table : " + nvl(config.getComparison().getTables().getTarget()));
                }
                if (config.getComparison().getKeys() != null) {
                    System.out.println("    Source keys  : " + nvl(config.getComparison().getKeys().getSource()));
                    System.out.println("    Target keys  : " + nvl(config.getComparison().getKeys().getTarget()));
                }
                if (config.getComparison().getCompareColumns() != null) {
                    System.out.println("    Source cols  : " + nvl(config.getComparison().getCompareColumns().getSource()));
                    System.out.println("    Target cols  : " + nvl(config.getComparison().getCompareColumns().getTarget()));
                }
                if (config.getComparison().getWhere() != null) {
                    System.out.println("    Source where : " + nvl(config.getComparison().getWhere().getSource()));
                    System.out.println("    Target where : " + nvl(config.getComparison().getWhere().getTarget()));
                }
            }

            // Strategy
            if (config.getStrategy() != null) {
                System.out.println("  Strategy:");
                System.out.println("    Mode              : " + nvl(config.getStrategy().getMode()));
                System.out.println("    Algorithm         : " + nvl(config.getStrategy().getAlgorithm()));
                System.out.println("    Batch size        : " + nvl(config.getStrategy().getBatchSize()));
                System.out.println("    Bisection factor  : " + nvl(config.getStrategy().getBisectionFactor()));
                System.out.println("    Bisection threshold: " + nvl(config.getStrategy().getBisectionThreshold()));
            }

            // Concurrency
            if (config.getConcurrency() != null) {
                System.out.println("  Concurrency:");
                System.out.println("    IO  pool core/max : "
                        + (config.getConcurrency().getIo() != null
                           ? config.getConcurrency().getIo().getCore() + "/" + config.getConcurrency().getIo().getMax()
                           : "(default)"));
                System.out.println("    CPU pool core/max : "
                        + (config.getConcurrency().getCpu() != null
                           ? config.getConcurrency().getCpu().getCore() + "/" + config.getConcurrency().getCpu().getMax()
                           : "(default)"));
            }

            // Result sinks
            if (config.getResult() != null && config.getResult().getSinks() != null) {
                System.out.println("  Result sinks:");
                for (com.consilens.sink.api.model.SinkConfig sink : config.getResult().getSinks()) {
                    System.out.println("    - type=" + nvl(sink.getType()) + ", format=" + nvl(sink.getFormat()));
                }
            }

            System.out.println("───────────────────────────────────────────────────────");
        } catch (Exception e) {
            log.warn("Failed to load config for verbose output: {}", e.getMessage());
        }
    }

    /**
     * Test source and target database connections.
     */
    private void testDatabaseConnections(ConfigurationManager configurationManager, String absolutePath) {
        System.out.println();
        System.out.println("── Connection Tests ────────────────────────────────────");

        CliConfiguration config;
        try {
            config = configurationManager.loadConfiguration(absolutePath);
        } catch (Exception e) {
            System.err.println("✗ Failed to load configuration for connection test: " + e.getMessage());
            System.exit(1);
            return;
        }

        boolean allPassed = true;

        // Test source connection
        DatabaseAdapter sourceAdapter = null;
        try {
            System.out.print("  Testing source connection (" + config.getSource().getUrl() + ") ... ");
            sourceAdapter = DatabaseAdapterFactory.createSourceAdapter(config);
            if (sourceAdapter.isHealthy()) {
                System.out.println("✓ OK");
            } else {
                System.out.println("✗ FAILED (unhealthy pool)");
                allPassed = false;
            }
        } catch (Exception e) {
            System.out.println("✗ FAILED");
            System.err.println("    Error: " + e.getMessage());
            if (verbose) {
                log.debug("Source connection test failed", e);
            }
            allPassed = false;
        } finally {
            closeAdapter(sourceAdapter, "source");
        }

        // Test target connection
        DatabaseAdapter targetAdapter = null;
        try {
            System.out.print("  Testing target connection (" + config.getTarget().getUrl() + ") ... ");
            targetAdapter = DatabaseAdapterFactory.createTargetAdapter(config);
            if (targetAdapter.isHealthy()) {
                System.out.println("✓ OK");
            } else {
                System.out.println("✗ FAILED (unhealthy pool)");
                allPassed = false;
            }
        } catch (Exception e) {
            System.out.println("✗ FAILED");
            System.err.println("    Error: " + e.getMessage());
            if (verbose) {
                log.debug("Target connection test failed", e);
            }
            allPassed = false;
        } finally {
            closeAdapter(targetAdapter, "target");
        }

        System.out.println("───────────────────────────────────────────────────────");

        if (!allPassed) {
            System.err.println("✗ One or more connection tests failed.");
            System.exit(1);
        } else {
            System.out.println("✓ All connections OK");
        }
    }

    private void closeAdapter(DatabaseAdapter adapter, String name) {
        if (adapter != null) {
            try {
                if (adapter.getConnectionPool() != null) {
                    adapter.getConnectionPool().close();
                }
            } catch (Exception e) {
                log.warn("Failed to close {} adapter: {}", name, e.getMessage());
            }
        }
    }

    private String nvl(Object value) {
        return value == null ? "(not set)" : value.toString();
    }
}
