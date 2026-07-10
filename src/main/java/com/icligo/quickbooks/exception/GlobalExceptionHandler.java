package com.icligo.quickbooks.exception;

import com.icligo.quickbooks.util.QuickBooksException;
import io.temporal.client.WorkflowException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Maps every exception that can surface from {@code DocumentController} to a status
 * code + {@link ApiError} body other services can branch on, instead of the previous
 * behaviour where any business-rule or QuickBooks-side failure surfaced as a bare 500.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        List<String> details = ex.getBindingResult().getAllErrors().stream()
                .map(err -> {
                    String field = err instanceof org.springframework.validation.FieldError fe
                            ? fe.getField() : err.getObjectName();
                    return field + ": " + err.getDefaultMessage();
                })
                .toList();

        return build(HttpStatus.BAD_REQUEST, "Request failed validation", details);
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            HttpMediaTypeNotSupportedException.class})
    public ResponseEntity<ApiError> handleBadRequest(Exception ex) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), null);
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiError> handleDuplicateKey(DuplicateKeyException ex) {
        return build(HttpStatus.CONFLICT,
                "A document for this type/serviceId/productId/refundId was already created concurrently", null);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiError> handleNotFound(NoSuchElementException ex) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), null);
    }

    /**
     * Surfaces from {@code DocumentPdfService} when a document's QuickBooks entity wasn't
     * recorded as expected (should not happen in practice — every document is only ever
     * returned to callers after its QuickBooks entity is set).
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleConflict(IllegalStateException ex) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), null);
    }

    @ExceptionHandler(QuickBooksException.class)
    public ResponseEntity<ApiError> handleQuickBooksException(QuickBooksException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode());
        if (status == null) {
            status = HttpStatus.BAD_GATEWAY;
        }
        log.warn("QuickBooks API error: {}", ex.getMessage());
        return build(status, ex.getMessage(), null);
    }

    /**
     * Covers {@code WorkflowFailedException} and any other failure raised while a Temporal
     * workflow/activity was talking to QuickBooks. The original exception type is lost across
     * the workflow boundary (Temporal re-serializes it as {@code ApplicationFailure}), so we
     * report it uniformly as an upstream failure rather than guessing at a specific status.
     */
    @ExceptionHandler(WorkflowException.class)
    public ResponseEntity<ApiError> handleWorkflowException(WorkflowException ex) {
        String message = rootMessage(ex);
        log.error("QuickBooks workflow failed: {}", message, ex);
        return build(HttpStatus.BAD_GATEWAY, "QuickBooks integration failed: " + message, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("Unexpected error handling request", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", null);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String message, List<String> details) {
        return ResponseEntity.status(status).body(ApiError.builder()
                .timestamp(Instant.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .details(details)
                .build());
    }

    private String rootMessage(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }
}
