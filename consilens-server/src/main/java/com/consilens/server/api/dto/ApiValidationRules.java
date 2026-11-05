package com.consilens.server.api.dto;

public final class ApiValidationRules {

    public static final int ID_MAX_LENGTH = 128;
    public static final String SAFE_ID_PATTERN = "[A-Za-z0-9_-]{1,128}";
    /** 名称允许中文/字母/数字/空格及 . _ - 等常见字符（用于任务定义、数据源等展示型名称）。 */
    public static final String NAME_PATTERN = "[\\p{L}\\p{N}\\p{M}._\\s-]{1,128}";
    public static final String NODE_KEY_PATTERN = "[A-Za-z0-9._:-]{1,128}";

    private ApiValidationRules() {
    }
}
