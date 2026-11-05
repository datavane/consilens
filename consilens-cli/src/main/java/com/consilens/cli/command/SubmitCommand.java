package com.consilens.cli.command;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Cluster submission commands.
 */
@Command(
        name = "submit",
        description = "Submit a comparison to an execution backend",
        mixinStandardHelpOptions = true,
        subcommands = {
                SubmitLocalCommand.class,
                SubmitYarnCommand.class,
                SubmitKubernetesCommand.class
        }
)
public class SubmitCommand implements Runnable {

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}
