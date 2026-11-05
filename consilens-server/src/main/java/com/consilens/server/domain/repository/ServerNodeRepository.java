package com.consilens.server.domain.repository;

import com.consilens.server.domain.model.ServerNodeRecord;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ServerNodeRepository {

    List<ServerNodeRecord> findAll();

    Optional<ServerNodeRecord> findByNodeKey(String nodeKey);

    List<ServerNodeRecord> findAliveSince(Instant cutoff);

    int deleteStaleNodesBefore(Instant cutoff);

    ServerNodeRecord save(ServerNodeRecord serverNodeRecord);
}
