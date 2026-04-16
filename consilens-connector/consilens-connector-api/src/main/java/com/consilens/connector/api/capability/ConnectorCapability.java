package com.consilens.connector.api.capability;

public enum ConnectorCapability {
    SCHEMA_DISCOVERY,
    FILTER_PUSHDOWN,
    PROJECTION_PUSHDOWN,
    SERVER_SIDE_COUNT,
    SERVER_SIDE_HASH,
    KEY_LOOKUP,
    RANGE_SPLIT,
    PARTITION_SPLIT,
    CURSOR_SPLIT,
    SNAPSHOT_READ,
    ORDERED_SCAN,
    STREAM_SCAN,
    SERVER_SIDE_JOIN
}
