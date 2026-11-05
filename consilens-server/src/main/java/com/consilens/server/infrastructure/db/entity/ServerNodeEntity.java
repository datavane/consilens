package com.consilens.server.infrastructure.db.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.consilens.server.domain.enums.ServerNodeStatus;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("cs_server_node")
public class ServerNodeEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("node_key")
    private String nodeKey;

    @TableField("host")
    private String host;

    @TableField("port")
    private Integer port;

    @TableField("machine_code")
    private String machineCode;

    @TableField("status")
    private ServerNodeStatus status;

    @TableField("load_average")
    private Double loadAverage;

    @TableField("available_memory_mb")
    private Double availableMemoryMb;

    @TableField("heartbeat_time")
    private LocalDateTime heartbeatTime;

    @TableField("status_update_time")
    private LocalDateTime statusUpdateTime;

    @TableField("status_update_by")
    private String statusUpdateBy;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
