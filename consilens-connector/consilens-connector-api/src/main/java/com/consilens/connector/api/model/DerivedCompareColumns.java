package com.consilens.connector.api.model;

import java.util.Locale;

public final class DerivedCompareColumns {

    private DerivedCompareColumns() {
    }

    public static boolean isDerived(String columnName) {
        String normalized = columnName == null ? "" : columnName.trim().toLowerCase(Locale.ROOT);
        return "checksum".equals(normalized)
                || "row_checksum".equals(normalized)
                || "record_checksum".equals(normalized)
                || "row_hash".equals(normalized)
                || "record_hash".equals(normalized)
                || "row_md5".equals(normalized)
                || "consilens_checksum".equals(normalized)
                || "consilens_row_hash".equals(normalized);
    }
}
