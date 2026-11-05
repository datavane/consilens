package com.consilens.conncetor.base;

import com.consilens.connector.api.CapabilityProvider;
import com.consilens.connector.api.enums.DatabaseFeature;

import java.util.EnumSet;
import java.util.Set;

public class BaseCapabilityProvider implements CapabilityProvider {

    private static Set<DatabaseFeature> createDefaultFeatures() {
        return EnumSet.of(
                DatabaseFeature.TRANSACTIONS,
                DatabaseFeature.SAVEPOINTS);
    }

    @Override
    public String getOpenQuote() {
        return "\"";
    }

    @Override
    public String getCloseQuote() {
        return "\"";
    }


    @Override
    public boolean supportsFeature(DatabaseFeature feature) {
        return createDefaultFeatures().contains(feature);
    }

    @Override
    public Set<DatabaseFeature> getSupportedFeatures() {
        return createDefaultFeatures();
    }

}