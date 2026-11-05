package com.consilens.server.infrastructure.db.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Reusable task definition (cs_task_definition). The full run configuration
 * (ServerCompareConfig JSON) is stored in the config column.
 */
@Data
@TableName("cs_task_definition")
public class TaskDefinitionEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("definition_key")
    private String definitionKey;

    @TableField("name")
    private String name;

    @TableField(value = "description", updateStrategy = FieldStrategy.IGNORED)
    private String description;

    @TableField("task_type")
    private String taskType;

    @TableField("config")
    private String config;

    @TableField("enabled")
    private Boolean enabled;

    @TableField("schedule_type")
    private String scheduleType;

    @TableField("cron_expr")
    private String cronExpr;

    @TableField("last_run_at")
    private LocalDateTime lastRunAt;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
