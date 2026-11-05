package com.consilens.cli.command;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Connector certification utilities.
 */
@Command(
        name = "connector",
        description = "Validate connector implementations",
        mixinStandardHelpOptions = true,
        subcommands = {ConnectorCertifyCommand.class}
)
public class ConnectorCommand implements Runnable {

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}
