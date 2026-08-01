package dev.audiobook.platform.admission;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = PublicationSubmissionController.class)
public class AdmissionExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalidRequest(IllegalArgumentException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "The submission command is invalid.");
        problem.setType(URI.create("urn:folio:problem:invalid-publication-submission"));
        problem.setTitle("Invalid publication submission");
        problem.setProperty("code", "INVALID_SUBMISSION_COMMAND");
        return problem;
    }

    @ExceptionHandler(PublicationSubmissionServiceImpl.SubmissionRejectedException.class)
    ProblemDetail rejected(PublicationSubmissionServiceImpl.SubmissionRejectedException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "The publication submission cannot continue in its current state.");
        problem.setType(URI.create("urn:folio:problem:publication-submission-rejected"));
        problem.setTitle("Publication submission rejected");
        problem.setProperty("code", exception.reasonCode());
        return problem;
    }
}
