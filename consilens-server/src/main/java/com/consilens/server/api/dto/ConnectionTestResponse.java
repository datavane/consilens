package com.consilens.server.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConnectionTestResponse {

    private boolean success;
    private long latencyMs;
    private String error;

    /**
     * 原始异常链。仅供服务端判定失败类型（如通过 SQLState 区分认证/网络问题），
     * 不参与 JSON 序列化，避免把驱动内部信息暴露给 API 调用方。
     */
    @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private transient Throwable cause;
}
