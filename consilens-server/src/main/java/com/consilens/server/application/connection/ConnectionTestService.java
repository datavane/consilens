package com.consilens.server.application.connection;

import com.consilens.server.api.dto.ConnectionTestRequest;
import com.consilens.server.api.dto.ConnectionTestResponse;

public interface ConnectionTestService {

    ConnectionTestResponse test(ConnectionTestRequest request);
}
