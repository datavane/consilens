package com.consilens.cli.command;

import picocli.CommandLine.Command;

/**
 * AI-assisted Consilens commands.
 */
@Command(
    name = "ai",
    description = "AI-assisted configuration, explanation and diagnosis commands",
    mixinStandardHelpOptions = true,
    subcommands = {
        AiConfigCommand.class,
        AiExplainCommand.class,
        AiDiagnoseCommand.class,
        AiDiffCommand.class
    }
)
public class AiCommand implements Runnable {

    @Override
    public void run() {
        System.out.println("Use `consilens ai --help` to see available AI commands.");
    }
}
