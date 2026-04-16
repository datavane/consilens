package com.consilens.connector.api.planner;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KeyRangeSplit implements SegmentSplit {

    private List<Object> startKey;

    private List<Object> endKey;
}
