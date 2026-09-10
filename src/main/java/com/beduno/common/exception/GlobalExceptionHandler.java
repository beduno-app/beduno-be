package com.beduno.common.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;

/**
 * Translates exceptions into the {@link ErrorResponse} envelope every client expects.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} is what keeps ordinary client mistakes out of
 * the 500 bucket. Spring raises a distinct exception for each of them -- wrong method, unreadable
 * body, unsupported content type, a path variable that will not parse -- and without the base class
 * they all fell through to {@code handleGeneral} and were reported as INTERNAL_ERROR. That lied to
 * callers, who cannot tell "fix your request" from "the server is broken" and may retry a 5xx, and
 * it buried genuine faults among routine noise in the logs.
 *
 * <p>The base class only chooses the status; {@link #handleExceptionInternal} replaces its
 * {@code ProblemDetail} body with our envelope, so the response shape stays uniform. Unqualified
 * {@code ErrorResponse} here is this package's record, not Spring's same-named interface.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(org.springframework.security.access.AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of("FORBIDDEN", "error.access_denied"));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorized(UnauthorizedException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of("UNAUTHORIZED", ex.getMessageCode()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("NOT_FOUND", ex.getMessageCode()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(ConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("CONFLICT", ex.getMessageCode()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ErrorResponse.of("CONSTRAINT_VIOLATION", ex.getMessageCode(), ex.getDetails()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(ForbiddenException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of("FORBIDDEN", ex.getMessageCode()));
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidation(ValidationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("VALIDATION_ERROR", ex.getMessageCode()));
    }

    /**
     * Overrides the base class rather than declaring a second {@code @ExceptionHandler} for this
     * type: two handlers claiming one exception in the same advice is an ambiguous mapping and
     * fails the context at startup.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        List<ErrorResponse> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> ErrorResponse.of(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return handleExceptionInternal(ex,
                ErrorResponse.of("VALIDATION_ERROR", "error.validation.failed", fieldErrors),
                headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex,
                                                             Object body,
                                                             HttpHeaders headers,
                                                             HttpStatusCode status,
                                                             WebRequest request) {
        if (status.is5xxServerError()) {
            log.error("Unhandled exception", ex);
        }
        var envelope = body instanceof ErrorResponse ? body : envelopeFor(status);
        return super.handleExceptionInternal(ex, envelope, headers, status, request);
    }

    /**
     * Message codes, not sentences: the client resolves them against its own locale bundle, the
     * same as every other envelope this class emits.
     */
    private ErrorResponse envelopeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> ErrorResponse.of("BAD_REQUEST", "error.bad_request");
            case 404 -> ErrorResponse.of("NOT_FOUND", "error.not_found");
            case 405 -> ErrorResponse.of("METHOD_NOT_ALLOWED", "error.method_not_allowed");
            case 415 -> ErrorResponse.of("UNSUPPORTED_MEDIA_TYPE", "error.unsupported_media_type");
            default -> status.is4xxClientError()
                    ? ErrorResponse.of("BAD_REQUEST", "error.bad_request")
                    : ErrorResponse.of("INTERNAL_ERROR", "error.internal");
        };
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("INTERNAL_ERROR", "error.internal"));
    }
}
