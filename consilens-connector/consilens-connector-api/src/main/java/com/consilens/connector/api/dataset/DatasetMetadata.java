package com.consilens.connector.api.dataset;

import com.consilens.connector.api.capability.CapabilitySet;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatasetMetadata {

    private String logicalName;

    private CapabilitySet capabilities;

    private String executionDomainId;

    private Map<String, Object> attributes;
}
