package com.consilens.cluster.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SplitAttemptTest {

    @Test
    void shouldOnlyAllowTerminalStateAfterRunning() {
        SplitAttempt attempt = new SplitAttempt("split-1", 1);

        assertThrows(IllegalStateException.class, attempt::succeed);
        attempt.start();
        attempt.succeed();

        assertEquals(SplitAttemptState.SUCCEEDED, attempt.getState());
        assertThrows(IllegalStateException.class, attempt::start);
    }
}
