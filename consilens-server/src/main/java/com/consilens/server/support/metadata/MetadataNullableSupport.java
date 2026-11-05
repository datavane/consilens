package com.consilens.server.support.metadata;

import java.util.Locale;

/**
 * information_schema / ALL_TAB_COLUMNS 的可空性列在不同数据库里可能是
 * {@code YES/NO}、{@code Y/N} 或 {@code 1/0}；统一解析，避免各查询链路
 * 直接把字符串当 boolean 或只识别其中一种形态。
 */
public final class MetadataNullableSupport {

    private MetadataNullableSupport() {
    }

    /** @return null when the metadata did not provide a value, otherwise nullable flag. */
    public static Boolean parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if ("NO".equals(normalized) || "N".equals(normalized)
                || "0".equals(normalized) || "FALSE".equals(normalized)) {
            return false;
        }
        return true;
    }
}
