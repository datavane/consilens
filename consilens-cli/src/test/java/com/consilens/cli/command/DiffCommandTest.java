package com.consilens.cli.command;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffCommandTest {

    @Test
    void shouldExposeFailOnDiffOption() {
        CommandLine commandLine = new CommandLine(new DiffCommand());
        assertNotNull(commandLine.getCommandSpec().findOption("--fail-on-diff"),
                "CI 需要 --fail-on-diff 才能在发现差异时返回非零退出码");
    }

    @Test
    void shouldReturnExitCodeTwoWhenDifferencesExistAndFailOnDiffSet() {
        assertEquals(2, DiffCommand.resolveExitCode(true, false, 1L));
        assertEquals(0, DiffCommand.resolveExitCode(true, false, 0L));
        assertEquals(0, DiffCommand.resolveExitCode(false, false, 5L));
    }

    @Test
    void shouldNeverFailInDryRun() {
        assertEquals(0, DiffCommand.resolveExitCode(true, true, 5L));
    }

    @Test
    void shouldAcceptFailOnDiffFlag() {
        CommandLine commandLine = new CommandLine(new DiffCommand());
        CommandLine.ParseResult result = commandLine.parseArgs(
                "--config", "config.yaml", "--fail-on-diff");
        assertTrue(result.hasMatchedOption("--fail-on-diff"));
    }
}
