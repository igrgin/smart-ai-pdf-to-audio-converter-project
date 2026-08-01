package dev.audiobook.platform.admission;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class InspectionCommandRunnerTest {

    private final InspectionCommandRunner runner = new InspectionCommandRunner();

    @Test
    void reportsOnlyTheExitStatusAndDiscardsProcessDiagnostics() {
        InspectionCommandRunner.Result result = runner.run(
                List.of("/bin/sh", "-c", "printf private-output; printf private-error >&2; exit 3"),
                Duration.ofSeconds(1));

        assertThat(result).isEqualTo(InspectionCommandRunner.Result.completed(3));
    }

    @Test
    void failsClosedWhenTheExecutableCannotStart() {
        assertThat(runner.run(List.of("/definitely/missing-inspection-command"), Duration.ofSeconds(1)))
                .isEqualTo(InspectionCommandRunner.Result.failureResult());
    }

    @Test
    void forciblyStopsCommandsAtTheConfiguredDeadline() {
        assertThat(runner.run(List.of("/bin/sh", "-c", "/bin/sleep 10"), Duration.ofMillis(50)))
                .isEqualTo(InspectionCommandRunner.Result.timeoutResult());
    }
}
