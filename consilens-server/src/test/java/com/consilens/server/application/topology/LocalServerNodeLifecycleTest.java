package com.consilens.server.application.topology;

import com.consilens.server.boot.ConsilensServerProperties;
import com.consilens.server.domain.repository.ServerNodeRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LocalServerNodeLifecycleTest {

    @Test
    void shouldScheduleHeartbeatFromSecondsProperty() throws NoSuchMethodException {
        Scheduled scheduled = LocalServerNodeLifecycle.class
                .getMethod("heartbeat")
                .getAnnotation(Scheduled.class);

        assertThat(scheduled.fixedDelayString())
                .contains("consilens.server.node.heartbeat-interval-seconds")
                .doesNotContain("consilens.server.node.heartbeat-interval-ms");
    }

    @Test
    void shouldUseConfiguredNodeKeyWhenProvided() {
        ServerNodeRepository serverNodeRepository = mock(ServerNodeRepository.class);
        ConsilensServerProperties properties = new ConsilensServerProperties();
        properties.getNode().setNodeKey("node-a");
        LocalServerNodeLifecycle lifecycle = new LocalServerNodeLifecycle(serverNodeRepository,
                properties,
                18080);

        assertThat(lifecycle.currentNodeKey()).isEqualTo("node-a");
    }

    @Test
    void shouldDeleteStaleNodesBeforeHeartbeatOnStartup() {
        ServerNodeRepository serverNodeRepository = mock(ServerNodeRepository.class);
        ConsilensServerProperties properties = new ConsilensServerProperties();
        properties.getNode().setExpireSeconds(30);
        LocalServerNodeLifecycle lifecycle = new LocalServerNodeLifecycle(serverNodeRepository,
                properties,
                18080);
        Instant before = Instant.now();

        lifecycle.register();

        Instant after = Instant.now();
        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(serverNodeRepository).deleteStaleNodesBefore(cutoffCaptor.capture());
        assertThat(cutoffCaptor.getValue())
                .isAfterOrEqualTo(before.minusSeconds(30))
                .isBeforeOrEqualTo(after.minusSeconds(30));
        InOrder inOrder = inOrder(serverNodeRepository);
        inOrder.verify(serverNodeRepository).deleteStaleNodesBefore(any());
        inOrder.verify(serverNodeRepository).save(any());
    }

    @Test
    void shouldScheduleStaleNodeDeletionFromSecondsProperty() throws NoSuchMethodException {
        Scheduled scheduled = LocalServerNodeLifecycle.class
                .getMethod("deleteStaleNodes")
                .getAnnotation(Scheduled.class);

        assertThat(scheduled.fixedDelayString())
                .contains("consilens.server.node.heartbeat-interval-seconds");
    }
}
