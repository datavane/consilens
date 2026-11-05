package com.consilens.server.application.log;

import com.consilens.server.api.dto.TaskLogPageDto;
import com.consilens.server.domain.exception.ResourceNotFoundException;
import com.consilens.server.domain.model.TaskInstanceRecord;
import com.consilens.server.domain.repository.TaskRepository;
import com.consilens.server.infrastructure.log.RunLogStore;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 运行实例日志读取：先按外部引用（instanceKey 或数值 id）解析实例，再读对应日志文件。
 * 日志文件由 {@link RunLogStore} 在任务执行期间写入，这里只做只读访问。
 */
@Service
@RequiredArgsConstructor
public class RunLogService {

    private final TaskRepository taskRepository;
    private final RunLogStore runLogStore;

    /**
     * @param offset    起始行号；与 tailLines 同时为空时从 0 开始
     * @param tailLines 非空时读取文件末尾若干行，并返回总行数作为 nextOffset
     */
    public TaskLogPageDto read(String taskRef, Long offset, Integer tailLines, int limit) {
        TaskInstanceRecord task = resolve(taskRef);
        RunLogStore.LogPage page = tailLines != null
                ? runLogStore.readTail(task.getInstanceKey(), tailLines)
                : runLogStore.read(task.getInstanceKey(), offset == null ? 0L : offset, limit);
        return TaskLogPageDto.builder()
                .available(page.isAvailable())
                .content(page.getContent())
                .nextOffset(page.getNextOffset())
                .hasMore(page.isHasMore())
                .fileSize(page.getFileSize())
                .lastModified(page.getLastModified())
                .build();
    }

    public LogFile download(String taskRef) {
        TaskInstanceRecord task = resolve(taskRef);
        java.nio.file.Path path = runLogStore.path(task.getInstanceKey()).orElse(null);
        return new LogFile(task.getInstanceKey() + ".log", path);
    }

    private TaskInstanceRecord resolve(String taskRef) {
        return taskRepository.resolveByRef(taskRef)
                .orElseThrow(() -> new ResourceNotFoundException("Task not found: " + taskRef));
    }

    @Getter
    public static class LogFile {
        private final String fileName;
        /** 日志文件路径；为空表示文件还没生成（例如任务尚未开始跑）。 */
        private final java.nio.file.Path path;

        LogFile(String fileName, java.nio.file.Path path) {
            this.fileName = fileName;
            this.path = path;
        }
    }
}
