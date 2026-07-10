package com.icligo.quickbooks.controller;

import com.icligo.quickbooks.exception.ApiError;
import com.icligo.quickbooks.model.QuickBooksDocument;
import com.icligo.quickbooks.service.DocumentPdfService;
import com.icligo.quickbooks.temporal.TemporalDocumentService;
import com.icligo.quickbooks.util.QuickBooksException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("${api.base-path}/documents")
@RequiredArgsConstructor
@Tag(name = "Documents", description = "Create QuickBooks invoices, sales receipts and refund receipts")
public class DocumentController {

    private final TemporalDocumentService temporalDocumentService;
    private final DocumentPdfService documentPdfService;

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

    @Operation(
            summary = "Download a document's PDF",
            description = """
                    Fetches the PDF for a previously created INV/SRT/RRT/CDM document directly \
                    from QuickBooks (generated live on every call, not cached) and streams it \
                    back as an application/pdf attachment. Looked up by `controlKey` (the same \
                    value returned in the POST /documents response and usable directly as \
                    `documentPDF`'s link), not QuickBooks' own id.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "PDF bytes",
                    content = @Content(mediaType = "application/pdf")),
            @ApiResponse(responseCode = "401", description = "Missing or invalid auth-token header",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "No document with this controlKey",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "502", description = "QuickBooks rejected or failed the PDF request",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
    })
    @GetMapping("/{controlKey}/pdf")
    public ResponseEntity<byte[]> getPdf(@PathVariable String controlKey) throws QuickBooksException {
        log.info("GET /documents/{}/pdf", controlKey);
        byte[] pdf = documentPdfService.getPdf(controlKey);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(controlKey + ".pdf").build().toString())
                .body(pdf);
    }

    @Operation(
            summary = "Get a document's PDF as base64",
            description = "Same lookup and QuickBooks fetch as GET /{controlKey}/pdf, but returns "
                    + "the PDF content base64-encoded in the response body instead of a binary attachment.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Base64-encoded PDF content",
                    content = @Content(mediaType = "text/plain")),
            @ApiResponse(responseCode = "401", description = "Missing or invalid auth-token header",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "No document with this controlKey",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "502", description = "QuickBooks rejected or failed the PDF request",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
    })
    @GetMapping(value = "/{controlKey}/pdf/base64", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getPdfBase64(@PathVariable String controlKey) throws QuickBooksException {
        log.info("GET /documents/{}/pdf/base64", controlKey);
        return ResponseEntity.ok(documentPdfService.getPdfBase64(controlKey));
    }
}
