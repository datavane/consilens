package com.consilens.core.algorithm;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JoinDifferNullableBoundsTest {

    @Test
    void unionMinKeepsNullWhenEitherSideHasNullDimension() {
        // NULL 排在最前：并集下界必须保留 NULL，否则该侧 NULL 键行会落在所有分段之外
        assertEquals(Arrays.asList(1, null),
                JoinDiffer.componentExtreme(Arrays.asList(1, null), Arrays.asList(1, 2), true));
        assertEquals(Arrays.asList(null, 2),
                JoinDiffer.componentExtreme(Arrays.asList(null, 2), Arrays.asList(1, 2), true));
    }

    @Test
    void unionMaxUsesNonNullDimension() {
        assertEquals(Arrays.asList(1, 2),
                JoinDiffer.componentExtreme(Arrays.asList(1, null), Arrays.asList(1, 2), false));
        assertEquals(Collections.singletonList(null),
                JoinDiffer.componentExtreme(Collections.singletonList(null),
                        Collections.singletonList(null), false));
    }
}
