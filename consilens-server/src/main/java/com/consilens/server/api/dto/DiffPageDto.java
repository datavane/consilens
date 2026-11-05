package com.consilens.server.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 分页差异记录（读差异文件，按行偏移）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiffPageDto {

    private String artifactId;

    /** 差异总行数（DB 记录的行数或旧格式数组长度）。 */
    private long total;

    /** 本页实际行数。 */
    private int rows;

    /** 差异文件是否被截断（超过行数上限）。 */
    private boolean truncated;

    /** 是否还有下一页。 */
    private boolean hasMore;

    /** 本页差异记录：{operation, primaryKey, metadata, ...}。 */
    private List<Map<String, Object>> items;
}
