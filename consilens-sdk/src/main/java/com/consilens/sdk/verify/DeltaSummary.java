package com.consilens.sdk.verify;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeltaSummary {

    private long newDifferences;
    private long fixedDifferences;
    private long persistingDifferences;
}
