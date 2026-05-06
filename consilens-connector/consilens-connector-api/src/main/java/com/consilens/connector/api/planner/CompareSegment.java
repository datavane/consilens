package com.consilens.connector.api.planner;

import com.consilens.connector.api.dataset.DatasetHandle;
import com.consilens.connector.api.dataset.SnapshotToken;
import com.consilens.connector.api.model.ComparisonSpec;
import com.consilens.connector.api.model.KeySpec;
import com.consilens.connector.api.model.PredicateSpec;
import com.consilens.connector.api.model.ResourceLocator;
import com.consilens.connector.api.model.SchemaDescriptor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class CompareSegment {

    private DatasetHandle dataset;

    private ResourceLocator resource;

    private KeySpec keySpec;

    private ComparisonSpec comparisons;

    private PredicateSpec filter;

    private SegmentSplit split;

    private SnapshotToken snapshot;

    private SchemaDescriptor schema;

    private String side;
}
