package com.consilens.core.compare;

import com.consilens.connector.api.capability.CapabilitySet;
import com.consilens.connector.api.capability.ConnectorCapability;
import com.consilens.connector.api.dataset.DatasetHandle;
import com.consilens.connector.api.dataset.DatasetMetadata;
import com.consilens.connector.api.planner.ComparePlanTypes;
import com.consilens.connector.api.planner.CompareRequest;
import com.consilens.connector.api.planner.CompareStrategyPreference;
import com.consilens.core.compare.plan.KeyHashPlan;
import com.consilens.core.compare.plan.PushdownChecksumPlan;
import com.consilens.core.compare.plan.ServerJoinPlan;
import com.consilens.core.compare.plan.StreamingMergePlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class DefaultComparePlanner implements ComparePlanner {

    @Override
    public ComparePlan plan(CompareRequest request, DatasetHandle source, DatasetHandle target) {
        CompareExecutionSettings executionSettings = CompareExecutionSettings.fromRequest(request);
        CapabilitySet sourceCapabilities = getCapabilities(source);
        CapabilitySet targetCapabilities = getCapabilities(target);
        List<ComparePlan> availablePlans = new ArrayList<>();

        if (sameExecutionDomain(source, target)
                && sourceCapabilities.supports(ConnectorCapability.SERVER_SIDE_JOIN)
                && targetCapabilities.supports(ConnectorCapability.SERVER_SIDE_JOIN)) {
            availablePlans.add(new ServerJoinPlan(executionSettings));
        }

        if (sourceCapabilities.supports(ConnectorCapability.SERVER_SIDE_HASH)
                && targetCapabilities.supports(ConnectorCapability.SERVER_SIDE_HASH)) {
            availablePlans.add(new PushdownChecksumPlan(executionSettings));
        }

        if (supportsKeyHash(source, sourceCapabilities, target, targetCapabilities)) {
            availablePlans.add(new KeyHashPlan(executionSettings, "source"));
        }

        if (supportsKeyHash(target, targetCapabilities, source, sourceCapabilities)) {
            availablePlans.add(new KeyHashPlan(executionSettings, "target"));
        }

        if (supportsStreamingMerge(source, sourceCapabilities, target, targetCapabilities)) {
            availablePlans.add(new StreamingMergePlan(executionSettings));
        }

        if (availablePlans.isEmpty()) {
            throw new IllegalStateException("No compatible compare plan found");
        }

        ComparePlan preferredPlan = resolvePreferredPlan(request != null ? request.getStrategyPreference() : null, availablePlans);
        if (preferredPlan != null) {
            return preferredPlan;
        }

        return availablePlans.get(0);
    }

    private CapabilitySet getCapabilities(DatasetHandle datasetHandle) {
        DatasetMetadata metadata = datasetHandle != null ? datasetHandle.getMetadata() : null;
        return metadata != null && metadata.getCapabilities() != null
                ? metadata.getCapabilities()
                : CapabilitySet.empty();
    }

    private boolean sameExecutionDomain(DatasetHandle source, DatasetHandle target) {
        DatasetMetadata sourceMetadata = source != null ? source.getMetadata() : null;
        DatasetMetadata targetMetadata = target != null ? target.getMetadata() : null;
        if (sourceMetadata == null || targetMetadata == null) {
            return false;
        }
        String sourceExecutionDomain = sourceMetadata.getExecutionDomainId();
        String targetExecutionDomain = targetMetadata.getExecutionDomainId();
        return sourceExecutionDomain != null && sourceExecutionDomain.equals(targetExecutionDomain);
    }

    private boolean supportsKeyHash(DatasetHandle lookupDataset,
                                    CapabilitySet lookupCapabilities,
                                    DatasetHandle scanDataset,
                                    CapabilitySet scanCapabilities) {
        return lookupCapabilities.supports(ConnectorCapability.KEY_LOOKUP)
                && scanCapabilities.supports(ConnectorCapability.STREAM_SCAN)
                && lookupDataset.getKeyLookupProvider().isPresent()
                && scanDataset.getRecordScanner().isPresent();
    }

    private boolean supportsStreamingMerge(DatasetHandle source,
                                           CapabilitySet sourceCapabilities,
                                           DatasetHandle target,
                                           CapabilitySet targetCapabilities) {
        return sourceCapabilities.supports(ConnectorCapability.ORDERED_SCAN)
                && targetCapabilities.supports(ConnectorCapability.ORDERED_SCAN)
                && source.getRecordScanner().isPresent()
                && target.getRecordScanner().isPresent();
    }

    private ComparePlan resolvePreferredPlan(CompareStrategyPreference preference, List<ComparePlan> availablePlans) {
        if (preference == null || preference.getPreferredPlans() == null || preference.getPreferredPlans().isEmpty()) {
            return null;
        }

        for (String preferredPlan : preference.getPreferredPlans()) {
            String normalized = normalizePlanType(preferredPlan);
            for (ComparePlan availablePlan : availablePlans) {
                if (normalizePlanType(availablePlan.getPlanType()).equals(normalized)) {
                    return availablePlan;
                }
            }
        }

        if (Boolean.FALSE.equals(preference.getAllowFallback())) {
            throw new IllegalStateException("Preferred compare plan(s) unavailable: " + preference.getPreferredPlans());
        }
        return null;
    }

    private String normalizePlanType(String planType) {
        if (planType == null) {
            return "";
        }
        switch (planType.trim().toLowerCase(Locale.ROOT)) {
            case "join":
                return ComparePlanTypes.SERVER_JOIN;
            case "checksum":
                return ComparePlanTypes.PUSHDOWN_CHECKSUM;
            default:
                return planType.trim().toLowerCase(Locale.ROOT);
        }
    }
}
