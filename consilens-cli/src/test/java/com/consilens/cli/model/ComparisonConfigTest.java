package com.consilens.cli.model;

import com.consilens.core.validation.ValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void shouldRejectMismatchedKeysFieldsFiltersAndDuplicateMappings() {
        ComparisonConfig mismatchedKeys = ComparisonConfig.builder()
                .keys(ListPairConfig.builder().source(List.of("id")).target(List.of("id", "tenant_id")).build())
                .build();
        assertThrows(ValidationException.class, mismatchedKeys::validate);

        ComparisonConfig mismatchedFields = ComparisonConfig.builder()
                .keys(ListPairConfig.builder().source(List.of("id")).target(List.of("id")).build())
                .fields(ListPairConfig.builder().source(List.of("amount")).target(List.of()).build())
                .build();
        assertThrows(ValidationException.class, mismatchedFields::validate);

        ComparisonConfig mismatchedFilters = ComparisonConfig.builder()
                .keys(ListPairConfig.builder().source(List.of("id")).target(List.of("id")).build())
                .filters(StringPairConfig.builder().source("id > 1").target(null).build())
                .build();
        assertThrows(ValidationException.class, mismatchedFilters::validate);

        ComparisonConfig duplicateMappings = ComparisonConfig.builder()
                .keys(ListPairConfig.builder().source(List.of("id")).target(List.of("id")).build())
                .mappings(List.of(
                        CompareMappingConfig.builder()
                                .name("amount")
                                .source(FieldExpressionConfig.builder().column("amount").build())
                                .target(FieldExpressionConfig.builder().column("amount").build())
                                .build(),
                        CompareMappingConfig.builder()
                                .name("amount")
                                .source(FieldExpressionConfig.builder().column("discount").build())
                                .target(FieldExpressionConfig.builder().column("discount").build())
                                .build()))
                .build();
        assertThrows(ValidationException.class, duplicateMappings::validate);
    }

    @Test
    void shouldAcceptWellFormedComparisonConfigWithExcludeAndExtraColumns() {
        ComparisonConfig config = ComparisonConfig.builder()
                .keys(ListPairConfig.builder().source(List.of("id")).target(List.of("id")).build())
                .fields(ListPairConfig.builder().source(List.of("amount")).target(List.of("amount")).build())
                .exclude(ListPairConfig.builder().source(List.of("debug")).target(List.of("debug")).build())
                .extraColumns(List.of("source_only_note"))
                .filters(StringPairConfig.builder().source("id > 1").target("id > 1").build())
                .build();

        assertDoesNotThrow(config::validate);
        assertTrue(config.getExtraColumns().contains("source_only_note"));
    }
}
