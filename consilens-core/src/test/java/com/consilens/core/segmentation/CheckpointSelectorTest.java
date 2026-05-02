package com.consilens.core.segmentation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CheckpointSelectorTest {

    @Test
    void shouldAllowSingleValueKeySpace() {
        CheckpointSelector selector = new CheckpointSelector(4, 100);
        KeyVector value = new KeyVector(10);

        List<KeyVector> checkpoints = selector.chooseCheckpoints(value, value, 4);

        assertEquals(List.of(value, value), checkpoints);
    }

    @Test
    void shouldRejectInvertedBounds() {
        CheckpointSelector selector = new CheckpointSelector(4, 100);

        assertThrows(IllegalArgumentException.class,
                () -> selector.chooseCheckpoints(new KeyVector(10), new KeyVector(1), 4));
    }
}
