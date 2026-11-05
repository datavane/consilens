package com.consilens.server.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskSummaryDto {

    private String taskId;
    private String id;
    private String serialNo;
    private String taskType;
    private String status;
    private String traceId;
    private String executeNodeKey;
    private Integer retryCount;
    /** 失败时的结构化错误码；成功任务为空。 */
    private String errorCode;
    /** 失败原因（已脱敏）；成功任务为空。 */
    private String errorMessage;
    private Instant submitTime;
    private Instant startTime;
    private Instant endTime;
    private List<String> availableNextActions;

    /** Diff summary for succeeded tasks; null unless includeDiffSummary was requested. */
    private TaskDiffSummaryDto diffSummary;

    /** Owning task definition (null for externally submitted runs). */
    private String definitionId;
    private String definitionName;
}
