package com.consilens.server.domain.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class ArtifactPage {

    private final long total;
    private final List<ArtifactRecord> items;
}
