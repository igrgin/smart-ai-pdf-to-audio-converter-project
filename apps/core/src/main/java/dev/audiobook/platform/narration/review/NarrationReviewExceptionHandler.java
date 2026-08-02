package dev.audiobook.platform.narration.review;

import dev.audiobook.platform.narration.review.service.*;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice(assignableTypes = NarrationReviewController.class)
public class NarrationReviewExceptionHandler {

    @ExceptionHandler({
        IllegalArgumentException.class,
        HttpMessageNotReadableException.class,
        MissingRequestHeaderException.class
    })
    ProblemDetail invalidRequest(Exception exception) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "INVALID_NARRATION_REVIEW",
                "Invalid Narration Review",
                "Submit only bounded structural decisions and Narration Review Item treatment or"
                        + " snippet changes.");
    }

    @ExceptionHandler(NarrationReviewRejectedException.class)
    ProblemDetail rejected(NarrationReviewRejectedException exception) {
        HttpStatus status =
                switch (exception.reason()) {
                    case CONVERSION_UNAVAILABLE -> HttpStatus.NOT_FOUND;
                    case CONVERSION_VERSION_MISMATCH,
                            IDEMPOTENCY_KEY_REUSED,
                            REVIEW_NOT_AVAILABLE ->
                            HttpStatus.CONFLICT;
                    case INVALID_REVIEW -> HttpStatus.BAD_REQUEST;
                    case WORKING_ASSET_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
                };
        String detail =
                switch (exception.reason()) {
                    case CONVERSION_VERSION_MISMATCH ->
                            "The Narration Review changed after it was loaded. Reload the latest"
                                    + " review and try again.";
                    case IDEMPOTENCY_KEY_REUSED ->
                            "This review operation key was already used for different review"
                                    + " decisions.";
                    case CONVERSION_UNAVAILABLE -> "The Audiobook Conversion is unavailable.";
                    case REVIEW_NOT_AVAILABLE ->
                            "This Narration Review is no longer available for changes.";
                    case INVALID_REVIEW ->
                            "Submit every source section and review item exactly once using only"
                                    + " supported decisions.";
                    case WORKING_ASSET_UNAVAILABLE ->
                            "The Narration Review could not be frozen safely. Try again later.";
                };
        ProblemDetail problem =
                problem(status, exception.reason().name(), "Narration Review rejected", detail);
        if (exception.currentVersion() != null) {
            problem.setProperty("currentVersion", exception.currentVersion());
            problem.setProperty("recoverable", true);
        }
        return problem;
    }

    private static ProblemDetail problem(
            HttpStatus status, String code, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:folio:problem:" + code.toLowerCase().replace('_', '-')));
        problem.setTitle(title);
        problem.setProperty("code", code);
        return problem;
    }
}
