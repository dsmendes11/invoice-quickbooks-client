package com.icligo.quickbooks.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.icligo.quickbooks.model.document.ClientInvoiceInfo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Mirrors the invoice-management-system's {@code PrimaveraInvoiceDetailDto} — one entry per
 * {@link QuickBooksDocument} on file for a {@code serviceId}, regardless of type (INV/SRT/RRT/CDM).
 * There, only advance-invoice/quote documents (FAA/ESFAA/PRE*) were ever {@code editable}; here,
 * the equivalent "advance/prepaid" type is a Sales Receipt (see
 * {@link com.icligo.quickbooks.service.SalesReceiptCancellationService}), so {@code editable} is
 * only ever {@code true} for a still-open SalesReceipt (same accounting as
 * {@link com.icligo.quickbooks.service.ActiveSalesReceiptFinder}) — every Invoice/RefundReceipt/
 * CreditMemo is always {@code false}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "One document on file for a serviceId, with its editable status.")
public class InvoiceDetailDto {

    @Schema(description = "Mongo id of the saved QuickBooksDocument.")
    private String id;

    @Schema(description = "The document's controlKey — use with GET {api.base-path}/documents/{controlKey}/pdf.",
            example = "SRT700212026")
    private String controlKey;

    @Schema(description = "INV, SRT, RRT or CDM.", example = "SRT")
    private String type;

    @Schema(description = "QuickBooks DocNumber for the underlying Invoice/SalesReceipt/RefundReceipt/CreditMemo.")
    private String docNumber;

    @Schema(description = "QuickBooks TxnDate, format YYYY-MM-DD.", example = "2026-07-14")
    private String date;

    @Schema(description = "QuickBooks TotalAmt.")
    private BigDecimal value;

    @Schema(description = "True only for a Sales Receipt with a still-open balance (TotalAmt minus any "
            + "RefundReceipt/CreditMemo already on file) — see GET /documents/invoices/creditnote/{controlKey}. "
            + "Always false for Invoice/RefundReceipt/CreditMemo.")
    private boolean editable;

    @Schema(description = "Relative link to this document's PDF.",
            example = "/invoice-quickbooks-service/v1/documents/SRT700212026/pdf")
    private String documentPDF;

    @Schema(description = "Client billing details recorded on the document.")
    private ClientInvoiceInfo clientInvoiceInfo;
}
