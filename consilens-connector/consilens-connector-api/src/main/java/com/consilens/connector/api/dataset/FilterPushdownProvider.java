package com.consilens.connector.api.dataset;

import com.consilens.connector.api.model.PredicateSpec;

public interface FilterPushdownProvider {

    PushdownResult analyze(PredicateSpec predicate);
}
