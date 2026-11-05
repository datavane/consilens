package com.consilens.core.algorithm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

class MultisetRowPairingTest {

    @Test
    void signatureShouldNotCollideWhenValuesContainSeparatorCharacters() {
        String left = MultisetRowPairing.signature(List.of("a\u0001b", "c"));
        String right = MultisetRowPairing.signature(List.of("a", "b\u0001c"));

        assertNotEquals(left, right);
    }
}
