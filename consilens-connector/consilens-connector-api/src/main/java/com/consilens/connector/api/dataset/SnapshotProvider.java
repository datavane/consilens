package com.consilens.connector.api.dataset;

import com.consilens.connector.api.config.ReadOptions;

public interface SnapshotProvider {

    SnapshotToken createSnapshot(ReadOptions options);
}
