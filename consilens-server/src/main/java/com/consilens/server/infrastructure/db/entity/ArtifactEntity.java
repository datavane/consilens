package com.consilens.server.infrastructure.db.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.consilens.server.domain.enums.ArtifactKind;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("cs_artifact")
public class ArtifactEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    @TableField("task_id")
    private Long taskId;

    @TableField("trace_id")
    private String traceId;

    @TableField("artifact_type")
    private ArtifactKind artifactType;

    @TableField("artifact_format")
    private String artifactFormat;

    @TableField("storage_type")
    private String storageType;

    @TableField("storage_uri")
    private String storageUri;

    @TableField("sha256")
    private String sha256;

    @TableField("metadata_json")
    private String metadataJson;

    @TableField("statistics_json")
    private String statisticsJson;

    @TableField("difference_count")
    private Long differenceCount;

    @TableField("difference_truncated")
    private Boolean differenceTruncated;

    @TableField("difference_rows")
    private Long differenceRows;

    @TableField("differences_uri")
    private String differencesUri;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
