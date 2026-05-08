package com.consilens.cli;

import com.consilens.cli.command.AiCommand;
import com.consilens.cli.command.ConfigCommand;
import com.consilens.cli.command.DiffCommand;

import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Main entry point for the consilens CLI application.
 *
 * <p>Uses Picocli for command-line parsing and subcommand dispatch.
 * Available subcommands:
 * <ul>
 *   <li>{@code diff} - Perform data comparison between databases</li>
 *   <li>{@code config} - Configuration management (generate, validate)</li>
 *   <li>{@code ai} - AI-assisted configuration and explanation</li>
 * </ul>
 */
@Slf4j
@Command(
    name = "consilens",
    version = "consilens CLI version 0.1-SNAPSHOT",
    description = "Cross-Database Data Comparison Tool",
    mixinStandardHelpOptions = true,
	    subcommands = {
	        DiffCommand.class,
	        ConfigCommand.class,
	        AiCommand.class
	    }
	)
public class ConsilensCliApplication implements Runnable {

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");
        configureLogging(args);
        log.info("Starting consilens CLI application");

        int exitCode = new CommandLine(new ConsilensCliApplication()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        // When no subcommand is specified, print usage help
        new CommandLine(this).usage(System.out);
    }

    /**
     * Configure logging based on command line arguments.
     */
    private static void configureLogging(String[] args) {
        boolean verbose = false;
        for (String arg : args) {
            if ("--verbose".equals(arg)) {
                verbose = true;
                break;
            }
        }

        if (verbose) {
            System.setProperty("logging.level.com.consilens", "DEBUG");
            System.setProperty("logging.level.root", "INFO");
        } else {
            System.setProperty("logging.level.com.consilens", "INFO");
            System.setProperty("logging.level.root", "WARN");
        }
    }
}
