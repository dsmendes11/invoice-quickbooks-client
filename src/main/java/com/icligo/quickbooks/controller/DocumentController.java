package com.icligo.quickbooks.controller;

import com.icligo.quickbooks.exception.ApiError;
import com.icligo.quickbooks.model.QuickBooksDocument;
import com.icligo.quickbooks.temporal.TemporalDocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("${api.base-path}/documents")
@RequiredArgsConstructor
@Tag(name = "Documents", description = "Create QuickBooks invoices, sales receipts and refund receipts")
public class DocumentController {

    private final TemporalDocumentService temporalDocumentService;

    @Operation(
            summary = "Create a QuickBooks document",
            description = """
                    Creates an INVOICE, SALES_RECEIPT or REFUND_RECEIPT in QuickBooks via a Temporal \
                    workflow. The QuickBooks customer is looked up by email/name and created \
                    automatically if it doesn't exist yet. Transient QuickBooks/network failures are \
                    retried by the workflow before this call returns.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Document created",
                    content = @Content(schema = @Schema(implementation = QuickBooksDocument.class))),
            @ApiResponse(responseCode = "400", description = "Request failed validation (missing/invalid fields)",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid auth-token header",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "502", description = "QuickBooks (or the Temporal workflow) rejected or failed the request",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
    })
    @PostMapping
    public ResponseEntity<QuickBooksDocument> create(@Valid @RequestBody QuickBooksDocument document) {
        log.info("POST /documents – type={}", document.getType());
        QuickBooksDocument created = temporalDocumentService.create(document);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}
