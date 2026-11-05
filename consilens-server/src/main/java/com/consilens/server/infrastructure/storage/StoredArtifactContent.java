package com.consilens.server.infrastructure.storage;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoredArtifactContent {

    private String storageType;
    private String storageUri;
    private String sha256;
}
