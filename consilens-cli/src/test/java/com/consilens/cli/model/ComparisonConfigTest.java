package com.consilens.cli.model;

import com.consilens.core.validation.ValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ComparisonConfigTest {

    @Test
    void shouldRejectFieldsAndMappingsTogether() {
        ComparisonConfig config = ComparisonConfig.builder()
                .keys(ListPairConfig.builder().source(List.of("id")).target(List.of("id")).build())
                .fields(ListPairConfig.builder().source(List.of("amount")).target(List.of("actual_amount")).build())
                .mappings(List.of(CompareMappingConfig.builder()
                        .name("amount")
                        .source(FieldExpressionConfig.builder().column("amount").build())
                        .target(FieldExpressionConfig.builder().column("actual_amount").build())
                        .build()))
                .build();

        assertThrows(ValidationException.class, config::validate);
    }

    @Test
    void shouldRejectInvalidMappingExpressionChoices() {
        ComparisonConfig config = ComparisonConfig.builder()
                .keys(ListPairConfig.builder().source(List.of("id")).target(List.of("id")).build())
                .mappings(List.of(CompareMappingConfig.builder()
                        .name("amount")
                        .source(FieldExpressionConfig.builder().column("amount").expression("amount + 1").build())
                        .target(FieldExpressionConfig.builder().column("actual_amount").build())
                        .build()))
                .build();

        assertThrows(ValidationException.class, config::validate);
    }
}
