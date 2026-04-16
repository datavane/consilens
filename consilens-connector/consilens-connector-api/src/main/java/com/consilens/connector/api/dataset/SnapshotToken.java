package com.consilens.connector.api.dataset;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SnapshotToken {

    private String token;

    private Map<String, Object> attributes;
}
