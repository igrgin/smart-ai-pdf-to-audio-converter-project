package dev.audiobook.platform.narration.internal.selection;

import dev.audiobook.platform.narration.NarrationSelectionRejectedException;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = NarrationController.class)
public class NarrationExceptionHandler {

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    ProblemDetail invalidChoice(Exception exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Choose an issued Narrator Voice and one supported Narration Pace.");
        problem.setType(URI.create("urn:folio:problem:invalid-narration-choice"));
        problem.setTitle("Invalid narration choice");
        problem.setProperty("code", "INVALID_NARRATION_CHOICE");
        return problem;
    }

    @ExceptionHandler(NarrationSelectionRejectedException.class)
    ProblemDetail rejectedChoice(NarrationSelectionRejectedException exception) {
        HttpStatus status = switch (exception.reason()) {
            case AUDIOBOOK_CONVERSION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONVERSION_VERSION_MISMATCH -> HttpStatus.PRECONDITION_FAILED;
            default -> HttpStatus.CONFLICT;
        };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                status, "This Narrator Voice and Narration Pace combination is not currently eligible.");
        problem.setType(URI.create("urn:folio:problem:narration-choice-rejected"));
        problem.setTitle("Narration choice rejected");
        problem.setProperty("code", exception.reason().name());
        return problem;
    }
}
