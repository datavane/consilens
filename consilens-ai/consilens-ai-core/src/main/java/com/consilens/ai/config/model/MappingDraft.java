package com.consilens.ai.config.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Key and field mapping draft.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MappingDraft {

    @Builder.Default
    private List<String> sourceKeys = new ArrayList<>();

    @Builder.Default
    private List<String> targetKeys = new ArrayList<>();

    @Builder.Default
    private List<String> sourceFields = new ArrayList<>();

    @Builder.Default
    private List<String> targetFields = new ArrayList<>();

    @Builder.Default
    private Map<String, String> fieldMapping = new LinkedHashMap<>();
}
