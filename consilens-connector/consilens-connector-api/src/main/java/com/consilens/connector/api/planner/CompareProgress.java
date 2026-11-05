package com.consilens.connector.api.planner;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Coarse progress snapshot emitted by the streaming comparison path.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompareProgress {

    private long sourceRows;
    private long targetRows;
    private long differences;
    private long elapsedMs;
}
