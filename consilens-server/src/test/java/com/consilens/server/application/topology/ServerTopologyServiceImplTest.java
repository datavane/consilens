package com.consilens.server.application.topology;

import com.consilens.server.domain.model.ServerNodeRecord;
import com.consilens.server.domain.model.ServerTopologySnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultServerTopologyServiceTest {

    @Test
    void shouldNotDispatchWhenCurrentNodeIsNotAlive() {
        ServerNodeQueryService serverNodeQueryService = mock(ServerNodeQueryService.class);
        LocalServerNodeLifecycle localServerNodeLifecycle = mock(LocalServerNodeLifecycle.class);
        when(localServerNodeLifecycle.currentNodeKey()).thenReturn("127.0.0.1:18080");
        when(serverNodeQueryService.listAliveNodes()).thenReturn(List.of(node("127.0.0.2:18080")));

        ServerTopologyServiceImpl service = new ServerTopologyServiceImpl(serverNodeQueryService,
                localServerNodeLifecycle);

        ServerTopologySnapshot snapshot = service.snapshot();

        assertThat(snapshot.isDispatchable()).isFalse();
    }

    @Test
    void shouldUseSortedCurrentNodeSlotWhenCurrentNodeIsAlive() {
        ServerNodeQueryService serverNodeQueryService = mock(ServerNodeQueryService.class);
        LocalServerNodeLifecycle localServerNodeLifecycle = mock(LocalServerNodeLifecycle.class);
        when(localServerNodeLifecycle.currentNodeKey()).thenReturn("127.0.0.2:18080");
        when(serverNodeQueryService.listAliveNodes()).thenReturn(List.of(
                node("127.0.0.3:18080"),
                node("127.0.0.1:18080"),
                node("127.0.0.2:18080")));

        ServerTopologyServiceImpl service = new ServerTopologyServiceImpl(serverNodeQueryService,
                localServerNodeLifecycle);

        ServerTopologySnapshot snapshot = service.snapshot();

        assertThat(snapshot.isDispatchable()).isTrue();
        assertThat(snapshot.getTotalSlot()).isEqualTo(3);
        assertThat(snapshot.getCurrentSlot()).isEqualTo(1);
    }

    private ServerNodeRecord node(String nodeKey) {
        return ServerNodeRecord.builder()
                .nodeKey(nodeKey)
                .build();
    }
}
