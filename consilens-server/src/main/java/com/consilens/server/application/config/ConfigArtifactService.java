package com.consilens.server.application.config;

import com.consilens.server.api.dto.ConfigResponse;

public interface ConfigArtifactService {

    ConfigResponse getConfig(String configId);
}
