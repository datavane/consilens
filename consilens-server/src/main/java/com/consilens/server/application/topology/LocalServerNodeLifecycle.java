package com.consilens.server.application.topology;

import com.consilens.server.boot.ConsilensServerProperties;
import com.consilens.server.domain.enums.ServerNodeStatus;
import com.consilens.server.domain.model.ServerNodeRecord;
import com.consilens.server.domain.repository.ServerNodeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import lombok.extern.slf4j.Slf4j;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.time.Instant;
import java.util.Optional;

@Slf4j
@Component
public class LocalServerNodeLifecycle {

    private final ServerNodeRepository serverNodeRepository;
    private final ConsilensServerProperties properties;
    private final int port;

    public LocalServerNodeLifecycle(ServerNodeRepository serverNodeRepository,
                                    ConsilensServerProperties properties,
                                    @Value("${server.port:16789}") int port) {
        this.serverNodeRepository = serverNodeRepository;
        this.properties = properties;
        this.port = port;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void register() {
        deleteStaleNodes();
        heartbeat();
    }

    @Scheduled(fixedDelayString = "#{${consilens.server.node.heartbeat-interval-seconds:2} * 1000 * 5}")
    public void deleteStaleNodes() {
        Instant cutoff = Instant.now().minusSeconds(properties.getNode().getExpireSeconds());
        int deleted = serverNodeRepository.deleteStaleNodesBefore(cutoff);
        if (deleted > 0) {
            log.info("Deleted {} stale server node(s) with no heartbeat since {}", deleted, cutoff);
        }
    }

    @Scheduled(fixedDelayString = "#{${consilens.server.node.heartbeat-interval-seconds:2} * 1000}")
    public void heartbeat() {
        Instant now = Instant.now();
        String host = resolveHost();
        String nodeKey = resolveNodeKey(host);
        Optional<ServerNodeRecord> existing = serverNodeRepository.findByNodeKey(nodeKey);
        ServerNodeRecord record = existing.orElseGet(ServerNodeRecord::new);
        record.setNodeKey(nodeKey);
        record.setHost(host);
        record.setPort(port);
        record.setMachineCode(resolveMachineCode());
        record.setStatus(ServerNodeStatus.ONLINE);
        record.setLoadAverage(currentLoadAverage());
        record.setAvailableMemoryMb(currentAvailableMemoryMb());
        record.setHeartbeatTime(now);
        record.setStatusUpdateTime(now);
        record.setStatusUpdateBy(nodeKey);
        if (record.getCreatedAt() == null) {
            record.setCreatedAt(now);
        }
        record.setUpdatedAt(now);
        serverNodeRepository.save(record);
    }

    public String currentNodeKey() {
        return resolveNodeKey(resolveHost());
    }

    private String resolveHost() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception exception) {
            return "127.0.0.1";
        }
    }

    private String resolveNodeKey(String host) {
        String nodeKey = properties.getNode().getNodeKey();
        if (nodeKey != null && !nodeKey.isBlank()) {
            return nodeKey.trim();
        }
        return host + ":" + port;
    }

    private String resolveMachineCode() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception exception) {
            return "unknown";
        }
    }

    private double currentLoadAverage() {
        return ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
    }

    private double currentAvailableMemoryMb() {
        long bytes = Runtime.getRuntime().freeMemory();
        return bytes / 1024d / 1024d;
    }
}
