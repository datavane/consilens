package com.consilens.server.application.topology;

import com.consilens.server.api.dto.NodeSummaryDto;
import com.consilens.server.domain.model.ServerNodeRecord;

import java.util.List;

public interface ServerNodeQueryService {

    List<NodeSummaryDto> listNodes();

    NodeSummaryDto getNode(String nodeKey);

    List<ServerNodeRecord> listAliveNodes();
}
