package com.consilens.ai.config.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result output draft.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResultDraft {

    private String sinkFormat;
    private String sinkType;
}
