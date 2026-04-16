package com.consilens.connector.api.planner;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CursorSplit implements SegmentSplit {

    private String cursor;

    private Integer limit;
}
