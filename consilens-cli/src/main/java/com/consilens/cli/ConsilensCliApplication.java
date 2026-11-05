package com.consilens.cli;

import com.consilens.cli.command.ConfigCommand;
import com.consilens.cli.command.ConnectorCommand;
import com.consilens.cli.command.ContractCommand;
import com.consilens.cli.command.DiffCommand;
import com.consilens.cli.command.MetricsCommand;
import com.consilens.cli.command.SubmitCommand;
import com.consilens.cli.command.VerifyCommand;

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
 *   <li>{@code verify} - Run a comparison and evaluate a CI gate policy</li>
 *   <li>{@code contract} - Compare ODCS/dbt data contracts</li>
 *   <li>{@code metrics} - Compare aggregate metrics from a config</li>
 *   <li>{@code connector} - Validate connector implementations</li>
 *   <li>{@code submit} - Submit a comparison to an execution backend</li>
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
	        VerifyCommand.class,
	        ContractCommand.class,
	        MetricsCommand.class,
	        ConnectorCommand.class,
	        SubmitCommand.class
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
