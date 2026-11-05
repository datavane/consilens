package com.consilens.benchmark.micro;

import com.consilens.core.algorithm.LocalDiffEngine;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BenchmarkFixturesTest {

    private static final String KEY = "id";
    private static final String EXTRA = "val";

    @Test
    void sameSeedProducesSameRows() {
        List<Object[]> a = BenchmarkFixtures.buildRows(100, 42L, 1, 2);
        List<Object[]> b = BenchmarkFixtures.buildRows(100, 42L, 1, 2);
        assertThat(a).hasSize(100);
        for (int i = 0; i < a.size(); i++) {
            assertThat(a.get(i)).containsExactly(b.get(i));
        }
    }

    @Test
    void widthMatchesColumnSpec() {
        List<Object[]> rows = BenchmarkFixtures.buildRows(10, 1L, 2, 3);
        assertThat(rows).hasSize(10);
        for (Object[] row : rows) {
            assertThat(row).hasSize(5);
        }
        for (int i = 0; i < rows.size(); i++) {
            assertThat((long) rows.get(i)[0]).isEqualTo(i);
        }
    }

    @Test
    void emptyAndZeroConfigs() {
        assertThat(BenchmarkFixtures.buildRows(0, 1L, 1, 1)).isEmpty();
        assertThat(BenchmarkFixtures.buildRows(5, 1L, 0, 0))
                .allSatisfy(row -> assertThat(row).isEmpty());
    }

    @Test
    void invalidArgsRejected() {
        assertThatThrownBy(() -> BenchmarkFixtures.buildRows(-1, 1L, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BenchmarkFixtures.withDifferences(null, 0.1, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BenchmarkFixtures.withDifferences(Collections.emptyList(), 1.5, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withDifferencesPreservesPkAndRatio() {
        List<Object[]> source = BenchmarkFixtures.buildRows(1000, 7L, 1, 1);
        List<Object[]> diff = BenchmarkFixtures.withDifferences(source, 0.05, 99L);

        assertThat(diff).hasSameSizeAs(source);
        int changed = 0;
        for (int i = 0; i < source.size(); i++) {
            assertThat(diff.get(i)[0]).isEqualTo(source.get(i)[0]);
            if (!Arrays.equals(source.get(i), diff.get(i))) {
                changed++;
            }
        }
        assertThat(changed).isBetween(20, 80);
    }

    @Test
    void fixturesMatchLocalDiffEngineContract() {
        List<Object[]> a = BenchmarkFixtures.buildRows(50, 1L, 1, 1);
        List<Object[]> b = BenchmarkFixtures.withDifferences(a, 0.5, 2L);

        long diffs = LocalDiffEngine.findDifferences(
                a, b,
                Collections.singletonList(KEY), Collections.singletonList(EXTRA),
                Collections.singletonList(KEY), Collections.singletonList(EXTRA))
                .size();

        assertThat(diffs).isGreaterThan(0);
    }
}
