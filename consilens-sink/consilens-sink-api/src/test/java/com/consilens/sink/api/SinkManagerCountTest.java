package com.consilens.sink.api;

import com.consilens.core.lifecycle.DiffContext;
import com.consilens.sink.api.model.SinkConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P2-14：多个 diff-record sink 收到的是同一批行，统计必须取最大值而不是求和，
 * 否则配两个 sink 行数就翻倍。
 */
@DisplayName("SinkManager 差异行计数")
class SinkManagerCountTest {

    @Test
    @DisplayName("多个 diff-record sink 取最大值，不求和")
    void countsMaxAcrossDiffRecordSinks() throws Exception {
        SinkManager manager = new SinkManager(null);
        List<Sink> sinks = diffRecordSinks(manager);
        sinks.add(counting(5L));
        sinks.add(counting(5L));

        assertEquals(5L, manager.diffRecordCount());
    }

    @Test
    @DisplayName("未实现计数的 sink 不参与统计")
    void ignoresSinksWithoutCount() throws Exception {
        SinkManager manager = new SinkManager(null);
        List<Sink> sinks = diffRecordSinks(manager);
        sinks.add(counting(7L));
        sinks.add(counting(-1L));

        assertEquals(7L, manager.diffRecordCount());
    }

    @Test
    @DisplayName("没有 diff-record sink 时计数为 0")
    void returnsZeroWithoutSinks() {
        assertEquals(0L, new SinkManager(null).diffRecordCount());
    }

    @SuppressWarnings("unchecked")
    private List<Sink> diffRecordSinks(SinkManager manager) throws Exception {
        Field field = SinkManager.class.getDeclaredField("diffRecordSinks");
        field.setAccessible(true);
        return (List<Sink>) field.get(manager);
    }

    private Sink counting(long written) {
        return new Sink() {
            @Override
            public void open(SinkConfig config, DiffContext context) {
            }

            @Override
            public long writtenRecordCount() {
                return written;
            }

            @Override
            public void close() {
            }
        };
    }
}
