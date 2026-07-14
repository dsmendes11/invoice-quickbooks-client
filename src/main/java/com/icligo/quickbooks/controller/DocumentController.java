package com.icligo.quickbooks.controller;

import com.icligo.quickbooks.exception.ApiError;
import com.icligo.quickbooks.model.ClientServiceValueInfo;
import com.icligo.quickbooks.model.InvoiceDetailDto;
import com.icligo.quickbooks.model.QuickBooksDocument;
import com.icligo.quickbooks.service.ClientAggregationService;
import com.icligo.quickbooks.service.DocumentPdfService;
import com.icligo.quickbooks.service.InvoiceDetailService;
import com.icligo.quickbooks.service.SalesReceiptCancellationService;
import com.icligo.quickbooks.temporal.TemporalDocumentService;
import com.icligo.quickbooks.util.QuickBooksException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
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

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("${api.base-path}/documents")
@RequiredArgsConstructor
@Tag(name = "Documents", description = "Create QuickBooks invoices, sales receipts and refund receipts")
public class DocumentController {

    private final TemporalDocumentService temporalDocumentService;
    private final DocumentPdfService documentPdfService;
    private final ClientAggregationService clientAggregationService;
    private final SalesReceiptCancellationService salesReceiptCancellationService;
    private final InvoiceDetailService invoiceDetailService;

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

    @Operation(
            summary = "Get the distinct clients with still-open Sales Receipt value for a serviceId",
            description = """
                    Mirrors the invoice-management-system's GET /documents/clients/{serviceId}: \
                    every Sales Receipt on file for this serviceId, netted against any \
                    RefundReceipt/CreditMemo already on file against its productId (same \
                    accounting as POST /refunds and booking-Invoice cancellation, see \
                    docs/OPERATIONS.md §6-§7), grouped by client (clientHash). Sales Receipts \
                    with nothing left open (fully refunded or cancelled) are omitted entirely — \
                    an empty array means every Sales Receipt for this serviceId is fully settled, \
                    not that none ever existed.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Clients with still-open value (possibly empty)",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = ClientServiceValueInfo.class)))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid auth-token header",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
    })
    @GetMapping("/clients/{serviceId}")
    public ResponseEntity<List<ClientServiceValueInfo>> getServiceIdClients(@PathVariable String serviceId) {
        log.info("GET /documents/clients/{}", serviceId);
        return ResponseEntity.ok(clientAggregationService.getServiceIdClients(serviceId));
    }

    @Operation(
            summary = "Credit whatever's still open on a Sales Receipt via CreditMemo",
            description = """
                    Mirrors the invoice-management-system's GET /documents/invoices/creditnote/{controlKey} \
                    — but here the "advance invoice" equivalent is a Sales Receipt, not an \
                    Invoice, so this only ever targets Sales Receipts (identified by \
                    `controlKey`, e.g. "SRT70021..."). Credits whatever's still open (Sales \
                    Receipt TotalAmt minus any RefundReceipt/CreditMemo already on file) via a \
                    new CreditMemo — same accounting as §6 and the automatic booking-Invoice \
                    cancellation (docs/OPERATIONS.md §6), just triggered directly instead of as \
                    a side effect. Unlike that automatic path, a failure here is NOT best-effort \
                    — it fails this request (`502`), since you asked for this specific credit \
                    right now.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "CreditMemo created (or already existed from a prior identical call)",
                    content = @Content(schema = @Schema(implementation = QuickBooksDocument.class))),
            @ApiResponse(responseCode = "204", description = "This Sales Receipt has nothing left open to credit — nothing was done"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid auth-token header",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "404", description = "No SalesReceipt document with this controlKey",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "502", description = "QuickBooks rejected or failed the CreditMemo request",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
    })
    @GetMapping("/invoices/creditnote/{controlKey}")
    public ResponseEntity<QuickBooksDocument> createCreditNote(@PathVariable String controlKey) throws QuickBooksException {
        log.info("GET /documents/invoices/creditnote/{}", controlKey);
        return salesReceiptCancellationService.cancelSalesReceiptByControlKey(controlKey)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @Operation(
            summary = "List every document on file for a serviceId, with its editable status",
            description = """
                    Mirrors the invoice-management-system's GET /documents/invoices/{serviceId}/details \
                    — every document (INV/SRT/RRT/CDM) on file for this serviceId, each with its \
                    DocNumber/TxnDate/TotalAmt and an `editable` flag. There, only advance-invoice/ \
                    quote documents were ever editable; here the equivalent "advance/prepaid" type \
                    is a Sales Receipt, so `editable` is true only for a Sales Receipt with a still-open \
                    balance (same accounting as §6/§7, docs/OPERATIONS.md §6-§7) — every Invoice/ \
                    RefundReceipt/CreditMemo is always false. An unknown serviceId returns an empty \
                    array, not a 404.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Documents on file for this serviceId (possibly empty)",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = InvoiceDetailDto.class)))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid auth-token header",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
    })
    @GetMapping("/invoices/{serviceId}/details")
    public ResponseEntity<List<InvoiceDetailDto>> getServiceInvoiceDetails(@PathVariable String serviceId) {
        log.info("GET /documents/invoices/{}/details", serviceId);
        return ResponseEntity.ok(invoiceDetailService.getServiceInvoiceDetails(serviceId));
    }

    @Operation(
            summary = "Whether a document can still be edited",
            description = """
                    Mirrors the invoice-management-system's GET /documents/invoices/document/{controlKey}/editable \
                    — true only if controlKey identifies a Sales Receipt with a still-open balance \
                    (same accounting as §6/§7). An unknown controlKey, or one identifying an Invoice/ \
                    RefundReceipt/CreditMemo, returns `{"editable": false}` rather than 404 — matching \
                    the reference's silent-false behavior exactly.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "{\"editable\": true|false}"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid auth-token header",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
    })
    @GetMapping("/invoices/document/{controlKey}/editable")
    public ResponseEntity<Map<String, Boolean>> getInvoiceEditable(@PathVariable String controlKey) {
        log.info("GET /documents/invoices/document/{}/editable", controlKey);
        return ResponseEntity.ok(Map.of("editable", invoiceDetailService.getInvoiceEditable(controlKey)));
    }
}
