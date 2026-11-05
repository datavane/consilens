package com.consilens.benchmark.baseline;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基线文件根结构：{@code {version, createdAt, jdk, entries: {key: BaselineEntry}}}。
 */
@Data
public class Baseline {
    private int version;
    private String createdAt;
    private String jdk;
    private Map<String, BaselineEntry> entries = new LinkedHashMap<>();
}
