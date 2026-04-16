package com.consilens.connector.api.normalization;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NormalizationSpec {

    private List<NormalizationRule> global;

    private List<NormalizationRule> source;

    private List<NormalizationRule> target;
}
