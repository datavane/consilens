package com.consilens.server.infrastructure.storage;

import com.consilens.server.domain.enums.ArtifactKind;

public interface ArtifactContentStore {

    StoredArtifactContent write(String artifactId, ArtifactKind artifactKind, String format, byte[] content);

    byte[] read(String storageUri);

    long size(String storageUri);
}
