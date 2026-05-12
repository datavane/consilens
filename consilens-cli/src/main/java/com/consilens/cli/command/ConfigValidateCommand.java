package com.consilens.cli.command;

import com.consilens.cli.config.ConfigurationManager;
import com.consilens.cli.model.CliConfiguration;
import com.consilens.cli.service.ConnectorConfigMapper;
import com.consilens.cli.service.ConnectorProbeService;
import com.consilens.cli.service.SensitiveValueMasker;

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
                System.out.println("    URL    : " + SensitiveValueMasker.maskJdbcUrl(config.getSource().getUrl()));
                System.out.println("    User   : " + SensitiveValueMasker.maskUsername(config.getSource().getUsername()));
            }

            // Target connection
            if (config.getTarget() != null) {
                System.out.println("  Target:");
                System.out.println("    Type   : " + nvl(config.getTarget().getType()));
                System.out.println("    URL    : " + SensitiveValueMasker.maskJdbcUrl(config.getTarget().getUrl()));
                System.out.println("    User   : " + SensitiveValueMasker.maskUsername(config.getTarget().getUsername()));
            }

            // Comparison
            if (config.getComparison() != null) {
                System.out.println("  Comparison:");
                System.out.println("    Source resource : " + describeResource(config.getSource()));
                System.out.println("    Target resource : " + describeResource(config.getTarget()));
                if (config.getComparison().getKeys() != null) {
                    System.out.println("    Source keys  : " + nvl(config.getComparison().getKeys().getSource()));
                    System.out.println("    Target keys  : " + nvl(config.getComparison().getKeys().getTarget()));
                }
                if (config.getComparison().getFields() != null) {
                    System.out.println("    Source fields: " + nvl(config.getComparison().getFields().getSource()));
                    System.out.println("    Target fields: " + nvl(config.getComparison().getFields().getTarget()));
                }
                if (config.getComparison().getFilters() != null) {
                    System.out.println("    Source filters: " + nvl(config.getComparison().getFilters().getSource()));
                    System.out.println("    Target filters: " + nvl(config.getComparison().getFilters().getTarget()));
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
        try {
            System.out.print("  Testing source connection (" + SensitiveValueMasker.maskJdbcUrl(config.getSource().getUrl()) + ") ... ");
            new ConnectorProbeService().verifyAccessible(
                    ConnectorConfigMapper.toConnectorConfig(config.getSource()));
            System.out.println("✓ OK");
        } catch (Exception e) {
            System.out.println("✗ FAILED");
            System.err.println("    Error: " + e.getMessage());
            if (verbose) {
                log.debug("Source connection test failed", e);
            }
            allPassed = false;
        }

        // Test target connection
        try {
            System.out.print("  Testing target connection (" + SensitiveValueMasker.maskJdbcUrl(config.getTarget().getUrl()) + ") ... ");
            new ConnectorProbeService().verifyAccessible(
                    ConnectorConfigMapper.toConnectorConfig(config.getTarget()));
            System.out.println("✓ OK");
        } catch (Exception e) {
            System.out.println("✗ FAILED");
            System.err.println("    Error: " + e.getMessage());
            if (verbose) {
                log.debug("Target connection test failed", e);
            }
            allPassed = false;
        }

        System.out.println("───────────────────────────────────────────────────────");

        if (!allPassed) {
            System.err.println("✗ One or more connection tests failed.");
            System.exit(1);
        } else {
            System.out.println("✓ All connections OK");
        }
    }

    private String nvl(Object value) {
        return value == null ? "(not set)" : value.toString();
    }

    private String describeResource(com.consilens.cli.model.ConnectionConfig connectionConfig) {
        if (connectionConfig == null || connectionConfig.getResource() == null) {
            return "(not set)";
        }
        com.consilens.cli.model.ConnectionConfig.ResourceConfig resource = connectionConfig.getResource();
        String location = "sql".equalsIgnoreCase(resource.getType())
                ? resource.getPath()
                : resource.getName();
        return nvl(resource.getType()) + ":" + nvl(location);
    }
}
