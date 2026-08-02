package dev.audiobook.platform.retention.restore;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.audiobook.platform.retention.restore.persistence.RestoreReplayIncidentPersistence;
import dev.audiobook.platform.retention.restore.service.TombstoneReplayService;
import dev.audiobook.platform.retention.RetentionProperties;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.boot.ApplicationArguments;

import java.nio.file.Path;
import java.time.Duration;

class TombstoneReplayRunnerTest {

    private final TombstoneReplayService replayService = mock(TombstoneReplayService.class);
    private final RestoreSafetyGate gate =
            new RestoreSafetyGate(
                    new RetentionProperties(
                            "retention-test-key-with-32-characters",
                            Path.of("retention-test"),
                            "retention-test-bucket",
                            Duration.ofHours(24),
                            Duration.ofDays(23),
                            Duration.ofDays(30),
                            Duration.ofDays(90),
                            Duration.ofDays(365),
                            100,
                            5,
                            true));
    private final RestoreReplayIncidentPersistence incidents =
            mock(RestoreReplayIncidentPersistence.class);
    private final TombstoneReplayRunner runner =
            new TombstoneReplayRunner(replayService, gate, incidents);

    @Test
    void opensThePrivateBoundaryOnlyAfterReplayAndIncidentResolution() {
        ApplicationArguments arguments = mock(ApplicationArguments.class);

        runner.run(arguments);

        InOrder order = inOrder(replayService, incidents);
        order.verify(replayService).replay();
        order.verify(incidents).resolveFailure();
        org.assertj.core.api.Assertions.assertThat(gate.isSafe()).isTrue();
        verify(incidents, never()).recordFailure();
    }

    @Test
    void recordsReplayFailureAndKeepsThePrivateBoundaryClosed() {
        RuntimeException failure = new IllegalStateException("registry unavailable");
        when(replayService.replay()).thenThrow(failure);

        assertThatThrownBy(() -> runner.run(mock(ApplicationArguments.class)))
                .isSameAs(failure);

        verify(incidents).recordFailure();
        verify(incidents, never()).resolveFailure();
        org.assertj.core.api.Assertions.assertThat(gate.isSafe()).isFalse();
    }
}
