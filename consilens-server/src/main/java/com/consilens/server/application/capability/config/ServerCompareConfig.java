package com.consilens.server.application.capability.config;

import com.consilens.sink.api.model.ResultConfig;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServerCompareConfig {

    @Builder.Default
    private String version = "1.0";

    private String goal;

    private EndpointConfig source;

    private EndpointConfig target;

    @Builder.Default
    private List<String> keys = new ArrayList<>();

    @Builder.Default
    private ComparisonConfig comparison = new ComparisonConfig();

    /**
     * 顶层归一化配置（与 CLI 配置同形）。UI/CLI 形内容把它放在顶层，
     * 解析后合并进 {@link ComparisonConfig#getNormalization()}。
     */
    private NormalizationConfig normalization;

    @Builder.Default
    private Map<String, Object> hints = new LinkedHashMap<>();

    @Builder.Default
    private Map<String, Object> executionOptions = new LinkedHashMap<>();

    /** 执行平台（local / yarn / kubernetes），任务定义运行时转换为 RunRequest.Options。 */
    private String platform;

    /** 与 platform 对应的运行参数，保存在任务定义配置中。 */
    @Builder.Default
    private Map<String, Object> properties = new LinkedHashMap<>();

    @Builder.Default
    private ResultConfig result = new ResultConfig();
}
