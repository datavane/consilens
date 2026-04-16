package com.consilens.connector.api.dataset;

import com.consilens.connector.api.model.PredicateSpec;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PushdownResult {

    private PredicateSpec pushedPredicate;

    private PredicateSpec residualPredicate;
}
