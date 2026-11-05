package com.consilens.server.infrastructure.db.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.consilens.server.domain.enums.TaskStatus;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("cs_task_instance")
public class TaskInstanceEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("instance_key")
    private String instanceKey;

    @TableField("definition_id")
    private Long definitionId;

    @TableField("serial_no")
    private String serialNo;

    @TableField("tenant_id")
    private String tenantId;

    @TableField("trace_id")
    private String traceId;

    @TableField("correlation_id")
    private String correlationId;

    @TableField("request_payload")
    private String requestPayload;

    @TableField("request_hash")
    private String requestHash;

    @TableField("status")
    private TaskStatus status;

    @TableField("priority")
    private Integer priority;

    @TableField("schedule_time")
    private LocalDateTime scheduleTime;

    @TableField("submit_time")
    private LocalDateTime submitTime;

    @TableField("start_time")
    private LocalDateTime startTime;

    @TableField("end_time")
    private LocalDateTime endTime;

    @TableField("execute_node_key")
    private String executeNodeKey;

    @TableField("result_artifact_id")
    private String resultArtifactId;

    @TableField("error_code")
    private String errorCode;

    @TableField("error_message")
    private String errorMessage;

    @TableField("retry_count")
    private Integer retryCount;

    @TableField("max_retry_count")
    private Integer maxRetryCount;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
