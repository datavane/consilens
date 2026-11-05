package com.consilens.server.infrastructure.db.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.consilens.server.domain.enums.TaskCommandStatus;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("cs_task_command")
public class TaskCommandEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("command_key")
    private String commandKey;

    @TableField("task_id")
    private Long taskId;

    @TableField("shard_key")
    private String shardKey;

    @TableField("shard_slot")
    private Integer shardSlot;

    @TableField("status")
    private TaskCommandStatus status;

    @TableField("execute_node_key")
    private String executeNodeKey;

    @TableField("lock_time")
    private LocalDateTime lockTime;

    @TableField("lock_until")
    private LocalDateTime lockUntil;

    @TableField("schedule_time")
    private LocalDateTime scheduleTime;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
