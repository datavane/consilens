package com.consilens.cli.command;

import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;

/**
 * Picocli subcommand for configuration management.
 */
@Slf4j
@Command(
    name = "config",
    description = "Configuration management",
    subcommands = {
        ConfigGenerateCommand.class,
        ConfigValidateCommand.class
    },
    mixinStandardHelpOptions = true
)
public class ConfigCommand implements Runnable {

    @Override
    public void run() {
        System.out.println("Usage: consilens config <subcommand>");
        System.out.println("Available subcommands: generate, validate");
        System.out.println("Use 'consilens config <subcommand> --help' for more information.");
    }
}
