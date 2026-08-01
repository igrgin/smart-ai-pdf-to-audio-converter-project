package dev.audiobook.platform.workflow;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AudiobookConversionController.class)
public class AudiobookConversionExceptionHandler {

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
