package com.consilens.cli.command;

import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

/**
 * Placeholder for safe AI diff command.
 */
@Command(
    name = "diff",
    description = "Generate and validate an AI diff plan (execution not implemented yet)",
    mixinStandardHelpOptions = true
)
public class AiDiffCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        System.err.println("`consilens ai diff` is not implemented yet. Use `consilens ai config` to generate a YAML first.");
        return 2;
    }
}
