package com.consilens.server.infrastructure.log;

import com.consilens.common.io.PathSupport;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.core.LogEvent;
import org.slf4j.MDC;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 运行实例日志存储：每个 {@code instanceKey} 对应 {@code <dir>/<instanceKey>.log}。
 *
 * <p>写入由 {@link RunLogAppender} 在 {@code com.consilens} logger 上触发，只接受 MDC 中带
 * {@link #MDC_INSTANCE_KEY} 的事件——即 {@code RunTaskHandler} 正在执行的运行实例。
 * 因此控制台/公共日志不会进入实例文件，多个并发的运行实例也不会串日志。
 *
 * <p>文件按行追加、每条事件后 flush，保证实例详情页轮询能及时看到新内容。
 */
@Slf4j
public class RunLogStore {

    /** MDC key：当前线程正在执行的运行实例。 */
    public static final String MDC_INSTANCE_KEY = "runInstanceId";

    /** 增量读一次最多读这么多字节：够一屏几百行，又不至于把整份日志读进内存。 */
    private static final int READ_CHUNK_BYTES = 128 * 1024;
    /** 单行上限（防止没有换行的超大行把轮询卡死）。 */
    private static final int MAX_LINE_BYTES = 4 * 1024 * 1024;
    /** 取末尾 N 行时只回看这么多字节。 */
    private static final int TAIL_WINDOW_BYTES = 256 * 1024;

    private static final int MAX_OPEN_WRITERS = 128;
    private static final int MAX_INSTANCE_KEY_LENGTH = 128;
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private final Path directory;
    private final boolean enabled;
    private final Map<String, BufferedWriter> writers = new LinkedHashMap<>(16, 0.75f, true);

    public RunLogStore(Path directory, boolean enabled) {
        this.directory = directory;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Path fileFor(String instanceKey) {
        return directory.resolve(sanitize(instanceKey) + ".log");
    }

    /**
     * 标记当前线程进入某个运行实例：写入 MDC，后续日志由 appender 路由到该实例文件。
     */
    public void beginRun(String instanceKey) {
        if (!enabled || instanceKey == null || instanceKey.isBlank()) {
            return;
        }
        try {
            PathSupport.ensureDirectory(directory);
        } catch (IOException e) {
            log.warn("Cannot create run log directory {}: {}", directory, e.getMessage());
            return;
        }
        MDC.put(MDC_INSTANCE_KEY, sanitize(instanceKey));
    }

    /**
     * 结束运行实例：移除 MDC 并关闭对应文件句柄（重试会重新打开并追加）。
     */
    public void endRun(String instanceKey) {
        if (!enabled || instanceKey == null || instanceKey.isBlank()) {
            return;
        }
        MDC.remove(MDC_INSTANCE_KEY);
        close(sanitize(instanceKey));
    }

    /**
     * 供 appender 调用；无运行实例上下文时直接丢弃。
     */
    public void append(LogEvent event) {
        if (!enabled) {
            return;
        }
        String instanceKey = MDC.get(MDC_INSTANCE_KEY);
        if (instanceKey == null || instanceKey.isBlank()) {
            return;
        }
        write(instanceKey, format(event));
    }

    /**
     * 从指定**字节偏移**读取日志。{@code hasMore} 为 true 表示文件还有后续内容。
     *
     * <p>偏移是字节而不是行号：轮询接口只把上次的 {@code nextOffset} 原样带回来，
     * 用行号就得把前 N 行全部解码丢弃（长跑任务的日志轮询会退化成 O(n²) 磁盘与解码开销）。
     * 只返回到最后一个换行为止的完整行，末尾半行留到下次再读。
     */
    public LogPage read(String instanceKey, long offset, int limit) {
        Path file = fileFor(instanceKey);
        if (!available(file)) {
            return LogPage.unavailable(offset);
        }
        long fileSize = sizeOf(file);
        long start = Math.min(Math.max(0L, offset), fileSize);
        int max = Math.max(1, limit);
        byte[] chunk = readChunk(file, start, READ_CHUNK_BYTES);
        int complete = lastNewline(chunk) + 1;
        if (complete == 0 && start + chunk.length < fileSize) {
            // 单行超过一个读块：这一行只能整段读出来，否则永远推进不了
            chunk = readChunk(file, start, MAX_LINE_BYTES);
            complete = lastNewline(chunk) + 1;
        }
        List<String> lines = splitLines(new String(chunk, 0, complete, StandardCharsets.UTF_8));
        long consumed = complete;
        if (lines.size() > max) {
            List<String> kept = new ArrayList<>(lines.subList(0, max));
            consumed = 0L;
            for (String line : kept) {
                consumed += line.getBytes(StandardCharsets.UTF_8).length + 1L;
            }
            lines = kept;
        }
        long nextOffset = start + consumed;
        return LogPage.builder()
                .available(true)
                .content(join(lines))
                .nextOffset(nextOffset)
                .hasMore(nextOffset < fileSize)
                .fileSize(fileSize)
                .lastModified(lastModifiedOf(file))
                .build();
    }

    /**
     * 读取文件末尾 {@code tailLines} 行。{@code nextOffset} 是文件长度（字节），
     * 便于调用方随后按 offset 增量拉取。
     *
     * <p>只从文件尾部固定窗口反向切行，不再为了取末尾几行把整份日志读一遍。
     */
    public LogPage readTail(String instanceKey, int tailLines) {
        Path file = fileFor(instanceKey);
        if (!available(file)) {
            return LogPage.unavailable(0L);
        }
        int tail = Math.max(1, tailLines);
        long fileSize = sizeOf(file);
        long start = Math.max(0L, fileSize - TAIL_WINDOW_BYTES);
        byte[] chunk = readChunk(file, start, TAIL_WINDOW_BYTES);
        List<String> lines = splitLines(new String(chunk, StandardCharsets.UTF_8));
        // 文件中间截断读取时，第一行可能是半行
        if (start > 0 && !lines.isEmpty()) {
            lines.remove(0);
        }
        if (lines.size() > tail) {
            lines = new ArrayList<>(lines.subList(lines.size() - tail, lines.size()));
        }
        return LogPage.builder()
                .available(true)
                .content(join(lines))
                .nextOffset(fileSize)
                .hasMore(false)
                .fileSize(fileSize)
                .lastModified(lastModifiedOf(file))
                .build();
    }

    private static byte[] readChunk(Path file, long start, int maxBytes) {
        try (java.nio.channels.SeekableByteChannel channel =
                     java.nio.file.Files.newByteChannel(file, StandardOpenOption.READ)) {
            channel.position(start);
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(maxBytes);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    break;
                }
            }
            int read = buffer.position();
            byte[] bytes = new byte[read];
            buffer.flip();
            buffer.get(bytes);
            return bytes;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read run log: " + file, e);
        }
    }

    private static int lastNewline(byte[] bytes) {
        for (int index = bytes.length - 1; index >= 0; index--) {
            if (bytes[index] == '\n') {
                return index;
            }
        }
        return -1;
    }

    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        if (text.isEmpty()) {
            return lines;
        }
        int from = 0;
        while (from < text.length()) {
            int newline = text.indexOf('\n', from);
            if (newline < 0) {
                lines.add(text.substring(from));
                break;
            }
            lines.add(text.substring(from, newline));
            from = newline + 1;
        }
        return lines;
    }

    /**
     * 日志文件路径；不存在时返回 {@link java.util.Optional#empty()}。
     *
     * <p>下载接口直接把它交给 Spring 的 {@code FileSystemResource} 做流式响应——
     * 之前是 {@code Files.readAllBytes} 整份读进堆，几十分钟的运行日志会把内存顶满。
     */
    public java.util.Optional<Path> path(String instanceKey) {
        Path file = fileFor(instanceKey);
        return available(file) ? java.util.Optional.of(file) : java.util.Optional.empty();
    }

    public void closeAll() {
        synchronized (writers) {
            writers.values().forEach(RunLogStore::closeQuietly);
            writers.clear();
        }
    }

    private void write(String instanceKey, String line) {
        BufferedWriter writer;
        try {
            writer = writer(instanceKey);
        } catch (IOException e) {
            log.warn("Cannot open run log for {}: {}", instanceKey, e.getMessage());
            return;
        }
        try {
            synchronized (writer) {
                writer.write(line);
                writer.newLine();
                writer.flush();
            }
        } catch (IOException e) {
            log.warn("Cannot write run log for {}: {}", instanceKey, e.getMessage());
            close(instanceKey);
        }
    }

    private BufferedWriter writer(String instanceKey) throws IOException {
        synchronized (writers) {
            BufferedWriter existing = writers.get(instanceKey);
            if (existing != null) {
                return existing;
            }
            if (writers.size() >= MAX_OPEN_WRITERS) {
                Iterator<Map.Entry<String, BufferedWriter>> eldest = writers.entrySet().iterator();
                if (eldest.hasNext()) {
                    closeQuietly(eldest.next().getValue());
                    eldest.remove();
                }
            }
            PathSupport.ensureDirectory(directory);
            BufferedWriter created = Files.newBufferedWriter(fileFor(instanceKey), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            writers.put(instanceKey, created);
            return created;
        }
    }

    private void close(String instanceKey) {
        synchronized (writers) {
            closeQuietly(writers.remove(instanceKey));
        }
    }

    private static void closeQuietly(BufferedWriter writer) {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (IOException ignored) {
            // 关闭失败不影响运行结果
        }
    }

    private static String format(LogEvent event) {
        StringBuilder builder = new StringBuilder(160);
        builder.append(TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(event.getTimeMillis())))
                .append(' ')
                .append(String.format("%-5s", event.getLevel()))
                .append(" [").append(event.getThreadName()).append("] ")
                .append(event.getLoggerName()).append(" - ")
                .append(event.getMessage() != null ? event.getMessage().getFormattedMessage() : "");
        Throwable thrown = event.getThrown();
        if (thrown == null && event.getThrownProxy() != null) {
            thrown = event.getThrownProxy().getThrowable();
        }
        if (thrown != null) {
            StringWriter stackTrace = new StringWriter();
            thrown.printStackTrace(new PrintWriter(stackTrace));
            builder.append(System.lineSeparator()).append(stackTrace);
        }
        return builder.toString();
    }

    private static String join(List<String> lines) {
        if (lines.isEmpty()) {
            return "";
        }
        return String.join(System.lineSeparator(), lines) + System.lineSeparator();
    }

    private static boolean available(Path file) {
        return Files.isRegularFile(file);
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0L;
        }
    }

    private static Instant lastModifiedOf(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 实例 key 会拼进文件名：只保留安全字符，避免路径穿越。
     */
    static String sanitize(String instanceKey) {
        String normalized = instanceKey == null ? "" : instanceKey.trim();
        normalized = normalized.replaceAll("[^A-Za-z0-9._-]", "_");
        if (normalized.isEmpty()) {
            normalized = "unknown";
        }
        return normalized.length() > MAX_INSTANCE_KEY_LENGTH
                ? normalized.substring(0, MAX_INSTANCE_KEY_LENGTH)
                : normalized;
    }

    @Getter
    @lombok.Builder
    public static class LogPage {
        private final boolean available;
        private final String content;
        private final long nextOffset;
        private final boolean hasMore;
        private final long fileSize;
        private final Instant lastModified;

        static LogPage unavailable(long offset) {
            return LogPage.builder()
                    .available(false)
                    .content("")
                    .nextOffset(offset)
                    .hasMore(false)
                    .fileSize(0L)
                    .lastModified(null)
                    .build();
        }
    }
}
