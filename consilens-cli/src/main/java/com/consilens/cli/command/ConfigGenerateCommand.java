package com.consilens.cli.command;

import com.consilens.cli.config.ConfigurationManager;
import com.consilens.cli.model.CliConfiguration;

import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Picocli subcommand for generating configuration templates.
 */
@Slf4j
@Command(
    name = "generate",
    description = "Generate configuration template",
    mixinStandardHelpOptions = true
)
public class ConfigGenerateCommand implements Runnable {

    @Option(names = {"-o", "--output"}, required = true, description = "Output file path")
    private String outputFile;

    @Option(names = {"-f", "--format"}, defaultValue = "yaml", description = "Output format: yaml or json (default: yaml)")
    private String format;

    @Option(names = {"-t", "--type"}, defaultValue = "basic", description = "Template type: basic or advanced (default: basic)")
    private String templateType;

    @Override
    public void run() {
        try {
            ConfigurationManager configurationManager = new ConfigurationManager();

            CliConfiguration config = "advanced".equalsIgnoreCase(templateType)
                    ? CliConfiguration.createAdvancedTemplate()
                    : CliConfiguration.createBasicTemplate();

            configurationManager.saveConfiguration(config, outputFile, format);

            System.out.println("Configuration template generated: " + outputFile);
            System.out.println("Template type: " + templateType);
            System.out.println("Format: " + format);
        } catch (Exception e) {
            log.error("Failed to generate configuration", e);
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }
}
