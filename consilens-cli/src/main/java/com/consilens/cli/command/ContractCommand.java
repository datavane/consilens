package com.consilens.cli.command;

import picocli.CommandLine.Command;
import picocli.CommandLine;

/**
 * Data contract utilities (ODCS).
 */
@Command(
        name = "contract",
        description = "Inspect and compare data contracts",
        mixinStandardHelpOptions = true,
        subcommands = {ContractDiffCommand.class, ContractInitCommand.class}
)
public class ContractCommand implements Runnable {

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}
