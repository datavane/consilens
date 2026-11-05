package com.consilens.server.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {

    private boolean success;
    private T data;
    private String error;
    private String errorCode;
    private String traceId;

    public static <T> ApiResponse<T> success(T data, String traceId) {
        return ApiResponse.<T>builder()
                .success(true)
                .data(data)
                .traceId(traceId)
                .build();
    }

    public static <T> ApiResponse<T> error(String error, String errorCode, String traceId) {
        return ApiResponse.<T>builder()
                .success(false)
                .error(error)
                .errorCode(errorCode)
                .traceId(traceId)
                .build();
    }
}
