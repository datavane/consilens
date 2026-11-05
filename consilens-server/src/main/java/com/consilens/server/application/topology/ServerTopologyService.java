package com.consilens.server.application.topology;

import com.consilens.server.domain.model.ServerTopologySnapshot;

public interface ServerTopologyService {

    ServerTopologySnapshot snapshot();

    String currentNodeKey();
}
