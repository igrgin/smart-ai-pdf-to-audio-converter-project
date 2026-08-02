package dev.audiobook.platform.admission.inspection.toolchain.pdf.service;

import dev.audiobook.platform.admission.inspection.toolchain.InspectionCommandRunner;
import dev.audiobook.platform.admission.inspection.toolchain.InspectionProperties;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

@Service
@RequiredArgsConstructor
public class QpdfValidationServiceImpl implements QpdfValidationService {

    private final InspectionProperties properties;
    private final InspectionCommandRunner commandRunner;

    @Override
    public Result validate(Path publication) {
        InspectionCommandRunner.Result encryption =
                commandRunner.run(
                        List.of(properties.qpdfCommand(), "--is-encrypted", publication.toString()),
                        properties.commandTimeout());
        if (encryption.timedOut()) {
            return Result.TIMED_OUT;
        }
        if (encryption.failed() || encryption.exitCode() == 1) {
            return Result.FAILED;
        }
        if (encryption.exitCode() == 0) {
            return Result.ENCRYPTED;
        }
        if (encryption.exitCode() != 2) {
            return Result.FAILED;
        }

        InspectionCommandRunner.Result validation =
                commandRunner.run(
                        List.of(properties.qpdfCommand(), "--check", publication.toString()),
                        properties.commandTimeout());
        if (validation.timedOut()) {
            return Result.TIMED_OUT;
        }
        if (validation.failed()) {
            return Result.FAILED;
        }
        return switch (validation.exitCode()) {
            case 0 -> Result.VALID;
            case 2 -> Result.INVALID;
            case 3 -> Result.VALID_WITH_WARNINGS;
            default -> Result.FAILED;
        };
    }
}
