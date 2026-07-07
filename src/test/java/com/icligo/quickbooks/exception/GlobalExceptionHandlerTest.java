package com.icligo.quickbooks.exception;

import com.icligo.quickbooks.util.QuickBooksException;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowException;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void illegalArgumentMapsTo400() {
        ResponseEntity<ApiError> response = handler.handleBadRequest(new IllegalArgumentException("type is required"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getMessage()).isEqualTo("type is required");
    }

    @Test
    void validationFailureMapsTo400WithFieldDetails() {
        BindingResult bindingResult = mock(BindingResult.class);
        FieldError fieldError = new FieldError("quickBooksDocument", "type", "type is required");
        when(bindingResult.getAllErrors()).thenReturn(List.of(fieldError));
        MethodArgumentNotValidException ex =
                new MethodArgumentNotValidException(mock(MethodParameter.class), bindingResult);

        ResponseEntity<ApiError> response = handler.handleValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getDetails()).containsExactly("type: type is required");
    }

    @Test
    void quickBooksExceptionWithKnownStatusCodeIsPassedThrough() {
        QuickBooksException ex = new QuickBooksException(401, "3200", "AuthenticationFailed");

        ResponseEntity<ApiError> response = handler.handleQuickBooksException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void quickBooksExceptionWithoutAStatusCodeDefaultsTo502() {
        QuickBooksException ex = new QuickBooksException("Network error during token refresh");

        ResponseEntity<ApiError> response = handler.handleQuickBooksException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void workflowFailureMapsTo502WithRootCauseMessage() {
        QuickBooksException rootCause = new QuickBooksException("Failed to create customer: Jane Doe");
        WorkflowException ex = new TestWorkflowException(rootCause);

        ResponseEntity<ApiError> response = handler.handleWorkflowException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody().getMessage()).contains("Failed to create customer: Jane Doe");
    }

    @Test
    void duplicateKeyMapsTo409() {
        ResponseEntity<ApiError> response = handler.handleDuplicateKey(new DuplicateKeyException("E11000 duplicate key"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void unexpectedExceptionMapsTo500WithoutLeakingItsMessage() {
        ResponseEntity<ApiError> response = handler.handleUnexpected(new NullPointerException("sensitive internal detail"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getMessage()).doesNotContain("sensitive internal detail");
    }

    /** {@code WorkflowException}'s constructor is protected — a throwaway subclass exposes it for the test. */
    private static class TestWorkflowException extends WorkflowException {
        TestWorkflowException(Throwable cause) {
            super(WorkflowExecution.newBuilder().setWorkflowId("wf-1").build(), "CreateInvoiceWorkflow", cause);
        }
    }
}
