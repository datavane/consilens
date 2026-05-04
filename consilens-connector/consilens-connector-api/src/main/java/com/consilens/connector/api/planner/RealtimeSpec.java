package com.consilens.connector.api.planner;

import com.consilens.connector.api.model.UpdateWindow;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RealtimeSpec {

    private String taskId;

    private boolean enabled;

    private String watermarkDelay;

    private String windowSize;

    private String overlap;

    private UpdateWindow sourceWindow;

    private UpdateWindow targetWindow;

    private String checkpointStoreType;

    private String checkpointStoreName;
}
