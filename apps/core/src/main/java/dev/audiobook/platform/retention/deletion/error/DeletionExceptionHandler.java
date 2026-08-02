package dev.audiobook.platform.retention.deletion.error;

import dev.audiobook.platform.retention.deletion.DeletionController;
import dev.audiobook.platform.retention.deletion.error.exception.DeletionConflictException;
import dev.audiobook.platform.retention.deletion.error.exception.DeletionPreconditionFailedException;
import dev.audiobook.platform.retention.deletion.error.exception.DeletionUnavailableException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice(assignableTypes = DeletionController.class)
public class DeletionExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalidRequest() {
        return problem(
                HttpStatus.BAD_REQUEST,
                "urn:folio:problem:invalid-deletion-request",
                "Invalid deletion request",
                "The deletion request is invalid.",
                "INVALID_DELETION_REQUEST");
    }

    @ExceptionHandler(DeletionUnavailableException.class)
    ProblemDetail unavailable() {
        return problem(
                HttpStatus.NOT_FOUND,
                "urn:folio:problem:deletion-resource-unavailable",
                "Private resource unavailable",
                "The private resource is unavailable.",
                "PRIVATE_RESOURCE_UNAVAILABLE");
    }

    @ExceptionHandler(DeletionPreconditionFailedException.class)
    ProblemDetail preconditionFailed() {
        return problem(
                HttpStatus.PRECONDITION_FAILED,
                "urn:folio:problem:stale-deletion-request",
                "Stale deletion request",
                "The private resource changed before deletion was requested.",
                "DELETION_PRECONDITION_FAILED");
    }

    @ExceptionHandler(DeletionConflictException.class)
    ProblemDetail conflict() {
        return problem(
                HttpStatus.CONFLICT,
                "urn:folio:problem:deletion-request-conflict",
                "Deletion request conflict",
                "The idempotency key was already used for a different deletion request.",
                "DELETION_REQUEST_CONFLICT");
    }

    private static ProblemDetail problem(
            HttpStatus status, String type, String title, String detail, String code) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(type));
        problem.setTitle(title);
        problem.setProperty("code", code);
        return problem;
    }
}
