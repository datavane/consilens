package com.consilens.server.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 运行实例日志分页结果。
 *
 * <p>{@code nextOffset} 是下一次增量拉取的起始行号；{@code available=false}
 * 表示该实例尚未产生日志文件（未开始执行，或日志功能被关闭）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskLogPageDto {

    private boolean available;
    private String content;
    private long nextOffset;
    private boolean hasMore;
    private long fileSize;
    private Instant lastModified;
}
