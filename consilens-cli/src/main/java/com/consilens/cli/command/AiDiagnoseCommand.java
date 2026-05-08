package com.consilens.cli.command;

import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

/**
 * Placeholder for AI diagnosis command.
 */
@Command(
    name = "diagnose",
    description = "Diagnose diff results (not implemented in this production landing slice)",
    mixinStandardHelpOptions = true
)
public class AiDiagnoseCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        System.err.println("`consilens ai diagnose` is not implemented yet. Use `consilens ai explain` first.");
        return 2;
    }
}
