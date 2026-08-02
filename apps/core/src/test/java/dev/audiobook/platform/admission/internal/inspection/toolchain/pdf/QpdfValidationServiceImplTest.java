package dev.audiobook.platform.admission.internal.inspection.toolchain.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.audiobook.platform.admission.internal.inspection.toolchain.InspectionCommandRunner;
import dev.audiobook.platform.admission.internal.inspection.toolchain.InspectionProperties;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QpdfValidationServiceImplTest {

    @TempDir
    Path scratch;

    @Test
    void mapsEncryptionProbeAndValidationStatusesConservatively() {
        assertThat(validate(InspectionCommandRunner.Result.completed(0)))
                .isEqualTo(QpdfValidationService.Result.ENCRYPTED);
        assertThat(validate(InspectionCommandRunner.Result.completed(1)))
                .isEqualTo(QpdfValidationService.Result.FAILED);
        assertThat(validate(InspectionCommandRunner.Result.timeoutResult()))
                .isEqualTo(QpdfValidationService.Result.TIMED_OUT);
        assertThat(validate(
                        InspectionCommandRunner.Result.completed(2),
                        InspectionCommandRunner.Result.completed(0)))
                .isEqualTo(QpdfValidationService.Result.VALID);
        assertThat(validate(
                        InspectionCommandRunner.Result.completed(2),
                        InspectionCommandRunner.Result.completed(2)))
                .isEqualTo(QpdfValidationService.Result.INVALID);
        assertThat(validate(
                        InspectionCommandRunner.Result.completed(2),
                        InspectionCommandRunner.Result.completed(3)))
                .isEqualTo(QpdfValidationService.Result.VALID_WITH_WARNINGS);
        assertThat(validate(
                        InspectionCommandRunner.Result.completed(2),
                        InspectionCommandRunner.Result.completed(7)))
                .isEqualTo(QpdfValidationService.Result.FAILED);
    }

    private QpdfValidationService.Result validate(InspectionCommandRunner.Result... commandResults) {
        InspectionCommandRunner runner = mock(InspectionCommandRunner.class);
        if (commandResults.length == 1) {
            when(runner.run(any(), any())).thenReturn(commandResults[0]);
        } else {
            when(runner.run(any(), any())).thenReturn(commandResults[0], commandResults[1]);
        }
        return new QpdfValidationServiceImpl(properties(), runner).validate(scratch.resolve("opaque"));
    }

    private InspectionProperties properties() {
        return new InspectionProperties(
                262_144_000L, 2_000, 10_000, 1_073_741_824L, 100, 26_214_400L, 40_000_000L,
                Duration.ofSeconds(30), Duration.ofMinutes(9), 3, scratch, "clamscan", "qpdf");
    }
}
