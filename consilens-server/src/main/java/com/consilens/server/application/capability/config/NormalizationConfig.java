package com.consilens.server.application.capability.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 跨方言归一化配置（与 CLI 的 {@code normalization} 段同形），
 * 使服务端/UI 定义与 CLI 配置产生完全一致的归一化规则。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NormalizationConfig {

    /** key 为类型名（decimal/date/varchar...），value 为该类型的归一化字段。 */
    private Map<String, TypeNormalizationRule> global;

    private Map<String, TypeNormalizationRule> source;

    private Map<String, TypeNormalizationRule> target;

    /** 列级规则（key 为列名，两侧同名生效），优先级高于类型级规则。 */
    private Map<String, TypeNormalizationRule> columns;
}
