package com.consilens.connector.api.normalization;

import java.util.Optional;

public interface NormalizationOperationRegistry {

    Optional<NormalizationOperationDefinition> find(String operation);
}
