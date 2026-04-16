package com.consilens.connector.api.capability;

import java.util.EnumSet;

public final class CapabilitySet {

    private final EnumSet<ConnectorCapability> capabilities;

    public CapabilitySet(EnumSet<ConnectorCapability> capabilities) {
        this.capabilities = capabilities == null
                ? EnumSet.noneOf(ConnectorCapability.class)
                : capabilities.clone();
    }

    public static CapabilitySet empty() {
        return new CapabilitySet(EnumSet.noneOf(ConnectorCapability.class));
    }

    public boolean supports(ConnectorCapability capability) {
        return capabilities.contains(capability);
    }

    public boolean supportsAll(ConnectorCapability... required) {
        if (required == null) {
            return true;
        }
        for (ConnectorCapability capability : required) {
            if (!capabilities.contains(capability)) {
                return false;
            }
        }
        return true;
    }

    public EnumSet<ConnectorCapability> asEnumSet() {
        return capabilities.clone();
    }
}
