package dev.audiobook.platform.workflow.internal.conversion;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AudiobookConversionController.class)
public class AudiobookConversionExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalidCommand() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "The Audiobook Conversion command is invalid.");
        problem.setType(URI.create("urn:folio:problem:invalid-audiobook-conversion-command"));
        problem.setTitle("Invalid Audiobook Conversion command");
        problem.setProperty("code", "INVALID_CONVERSION_COMMAND");
        return problem;
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail conflictingCommand() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "The Audiobook Conversion changed or cannot accept this command in its current state.");
        problem.setType(URI.create("urn:folio:problem:audiobook-conversion-command-conflict"));
        problem.setTitle("Audiobook Conversion command conflict");
        problem.setProperty("code", "CONVERSION_COMMAND_CONFLICT");
        return problem;
    }

    @ExceptionHandler(AudiobookConversionUnavailableException.class)
    ProblemDetail unavailable() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                "The Audiobook Conversion is unavailable.");
        problem.setType(URI.create("urn:folio:problem:audiobook-conversion-unavailable"));
        problem.setTitle("Audiobook Conversion unavailable");
        problem.setProperty("code", "CONVERSION_UNAVAILABLE");
        return problem;
    }
}
