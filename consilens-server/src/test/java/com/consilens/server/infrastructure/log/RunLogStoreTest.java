package com.consilens.server.infrastructure.log;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunLogStoreTest {

    @TempDir
    Path tempDir;

    private RunLogStore store;

    @BeforeEach
    void setUp() {
        store = new RunLogStore(tempDir, true);
    }

    @AfterEach
    void tearDown() {
        store.closeAll();
    }

    @Test
    void shouldAppendAndReadTail() {
        store.beginRun("inst_1");
        store.append(event(Level.INFO, "hello"));
        store.append(event(Level.WARN, "warn"));
        store.endRun("inst_1");

        RunLogStore.LogPage page = store.readTail("inst_1", 10);

        assertTrue(page.isAvailable());
        // nextOffset 现在是文件长度（字节），不再是行号：从这个偏移继续读应当是空的
        RunLogStore.LogPage after = store.read("inst_1", page.getNextOffset(), 10);
        assertEquals("", after.getContent());
        assertFalse(after.isHasMore());
        assertTrue(page.getContent().contains("hello"));
        assertTrue(page.getContent().contains("WARN"));
    }

    @Test
    void shouldPageFromOffset() {
        store.beginRun("inst_2");
        IntStream.range(0, 5).forEach(i -> store.append(event(Level.INFO, "line-" + i)));
        store.endRun("inst_2");

        RunLogStore.LogPage first = store.read("inst_2", 0, 2);
        assertTrue(first.getNextOffset() > 2L, "偏移是字节，不是行号");
        assertTrue(first.isHasMore());
        assertTrue(first.getContent().contains("line-0"));
        assertTrue(first.getContent().contains("line-1"));
        assertFalse(first.getContent().contains("line-2"));

        RunLogStore.LogPage rest = store.read("inst_2", first.getNextOffset(), 10);
        assertFalse(rest.isHasMore());
        assertTrue(rest.getContent().contains("line-2"));
        assertTrue(rest.getContent().contains("line-4"));
        assertFalse(rest.getContent().contains("line-0"));
    }

    @Test
    void shouldReturnUnavailableWhenNoFile() {
        RunLogStore.LogPage page = store.read("missing", 0, 10);

        assertFalse(page.isAvailable());
        assertEquals("", page.getContent());
    }

    @Test
    void shouldSanitizeInstanceKey() {
        assertEquals(tempDir.resolve("a_b_c.log"), store.fileFor("a/b c"));
        assertEquals(tempDir.resolve("unknown.log"), store.fileFor(""));
    }

    @Test
    void shouldIgnoreEventsWithoutMdcContext() {
        store.append(event(Level.INFO, "no context"));

        assertFalse(store.read("unknown", 0, 10).isAvailable());
    }

    @Test
    void shouldNotWriteWhenDisabled() {
        RunLogStore disabled = new RunLogStore(tempDir.resolve("off"), false);
        disabled.beginRun("inst_3");
        disabled.append(event(Level.INFO, "ignored"));
        disabled.endRun("inst_3");

        assertFalse(disabled.read("inst_3", 0, 10).isAvailable());
    }

    private LogEvent event(Level level, String message) {
        return Log4jLogEvent.newBuilder()
                .setLoggerName("com.consilens.test")
                .setLevel(level)
                .setMessage(new SimpleMessage(message))
                .setTimeMillis(System.currentTimeMillis())
                .setThreadName(Thread.currentThread().getName())
                .build();
    }

    @Test
    void incrementalReadsStayCheapOnLongLogs() {
        store.beginRun("inst_long");
        IntStream.range(0, 3000).forEach(i -> store.append(event(Level.INFO, "line-" + i)));
        store.endRun("inst_long");

        // 逐页推进：每一页都只读新增部分（字节偏移），不需要把前面几千行重新解码
        long offset = 0L;
        int pages = 0;
        int lines = 0;
        while (pages < 10) {
            RunLogStore.LogPage page = store.read("inst_long", offset, 500);
            lines += page.getContent().isEmpty() ? 0 : page.getContent().split("\n").length;
            offset = page.getNextOffset();
            pages++;
            if (!page.isHasMore()) {
                break;
            }
        }
        assertEquals(3000, lines);
        assertFalse(store.read("inst_long", offset, 10).isHasMore());

        // 取末尾几行不应该受文件总长度影响（内部只回看固定窗口）
        RunLogStore.LogPage tail = store.readTail("inst_long", 3);
        assertTrue(tail.getContent().contains("line-2999"));
        assertFalse(tail.getContent().contains("line-0\n"));
    }
}
