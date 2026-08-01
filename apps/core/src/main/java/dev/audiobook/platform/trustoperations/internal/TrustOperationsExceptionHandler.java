package dev.audiobook.platform.trustoperations.internal;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "dev.audiobook.platform.trustoperations")
public class TrustOperationsExceptionHandler {

    @ExceptionHandler(TrustOperationsAccessDeniedException.class)
    ResponseEntity<Void> accessDenied() {
        return ResponseEntity.notFound().cacheControl(CacheControl.noStore()).build();
    }

    @ExceptionHandler(TrustOperationsConflictException.class)
    ResponseEntity<ErrorView> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .cacheControl(CacheControl.noStore())
                .body(new ErrorView("TRUST_OPERATION_CONFLICT"));
    }

    @ExceptionHandler(TrustOperationsForbiddenException.class)
    ResponseEntity<ErrorView> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .cacheControl(CacheControl.noStore())
                .body(new ErrorView("TRUST_OPERATION_FORBIDDEN"));
    }

    @ExceptionHandler(TrustOperationsFreshMfaRequiredException.class)
    ResponseEntity<ErrorView> freshMfaRequired() {
        return ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED)
                .cacheControl(CacheControl.noStore())
                .body(new ErrorView("FRESH_MFA_REQUIRED"));
    }

    @ExceptionHandler(TrustOperationsPreconditionException.class)
    ResponseEntity<ErrorView> preconditionFailed() {
        return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
                .cacheControl(CacheControl.noStore())
                .body(new ErrorView("TRUST_OPERATION_PRECONDITION_FAILED"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorView> invalid() {
        return ResponseEntity.badRequest()
                .cacheControl(CacheControl.noStore())
                .body(new ErrorView("INVALID_TRUST_OPERATION"));
    }

    record ErrorView(String code) {
    }
}
