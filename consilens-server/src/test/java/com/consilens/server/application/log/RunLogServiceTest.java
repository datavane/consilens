package com.consilens.server.application.log;

import com.consilens.server.api.dto.TaskLogPageDto;
import com.consilens.server.domain.exception.ResourceNotFoundException;
import com.consilens.server.domain.model.TaskInstanceRecord;
import com.consilens.server.domain.repository.TaskRepository;
import com.consilens.server.infrastructure.log.RunLogStore;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RunLogServiceTest {

    @TempDir
    Path tempDir;

    private final TaskRepository taskRepository = mock(TaskRepository.class);
    private RunLogStore store;

    @BeforeEach
    void setUp() {
        store = new RunLogStore(tempDir, true);
        when(taskRepository.resolveByRef("inst_1")).thenReturn(Optional.of(TaskInstanceRecord.builder()
                .id(1L)
                .instanceKey("inst_1")
                .build()));
    }

    @Test
    void shouldReadTailByTaskRef() {
        store.beginRun("inst_1");
        store.append(Log4jLogEvent.newBuilder()
                .setLoggerName("com.consilens.test")
                .setLevel(Level.INFO)
                .setMessage(new SimpleMessage("run line"))
                .setTimeMillis(System.currentTimeMillis())
                .setThreadName(Thread.currentThread().getName())
                .build());
        store.endRun("inst_1");

        TaskLogPageDto page = new RunLogService(taskRepository, store).read("inst_1", null, 100, 100);

        assertTrue(page.isAvailable());
        assertTrue(page.getContent().contains("run line"));
        // nextOffset 是字节偏移（文件长度），不再是行号
        assertTrue(page.getNextOffset() > 1L);
    }

    @Test
    void shouldDownloadWholeFile() throws java.io.IOException {
        store.beginRun("inst_1");
        store.append(Log4jLogEvent.newBuilder()
                .setLoggerName("com.consilens.test")
                .setLevel(Level.ERROR)
                .setMessage(new SimpleMessage("boom"))
                .setTimeMillis(System.currentTimeMillis())
                .setThreadName(Thread.currentThread().getName())
                .build());
        store.endRun("inst_1");

        RunLogService.LogFile file = new RunLogService(taskRepository, store).download("inst_1");

        assertEquals("inst_1.log", file.getFileName());
        // 不再把整份日志读成 byte[]：交给 Spring 流式返回，这里只验证路径可用
        assertNotNull(file.getPath());
        assertTrue(new String(java.nio.file.Files.readAllBytes(file.getPath()),
                java.nio.charset.StandardCharsets.UTF_8).contains("boom"));
    }

    @Test
    void shouldFailWhenTaskMissing() {
        when(taskRepository.resolveByRef("missing")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> new RunLogService(taskRepository, store).read("missing", 0L, null, 100));
    }

    @Test
    void shouldReturnUnavailableBeforeRunStarts() {
        TaskLogPageDto page = new RunLogService(taskRepository, store).read("inst_1", 0L, null, 100);

        assertFalse(page.isAvailable());
    }
}
