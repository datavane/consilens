package com.consilens.connector.api.normalization;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NormalizationOperationDefinition {

    private String name;

    private Set<String> supportedTypes;

    private Set<String> supportedParams;
}
