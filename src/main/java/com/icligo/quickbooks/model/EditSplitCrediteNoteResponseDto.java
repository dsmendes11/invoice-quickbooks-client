package com.icligo.quickbooks.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Mirrors the invoice-management-system's {@code EditSplitCrediteNoteResponseDto} — returned by
 * {@code GET /documents/invoices/creditnote/{controlKey}}. There, {@code documents} held
 * Primavera-shaped {@code DocumentInfo} objects and the field was {@code faaProductId}; here,
 * each element of {@code documents} is the raw QuickBooks entity itself (a CreditMemo), same as
 * every other document-returning endpoint (see docs/CLIENT_INTEGRATION.md §3.5), and the field is
 * just {@code productId} since this service has no FAA-specific naming.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Result of crediting whatever's still open on a Sales Receipt via CreditMemo.")
public class EditSplitCrediteNoteResponseDto {

    @Schema(description = "The amount actually credited — the Sales Receipt's still-open balance at the time of the call.")
    private BigDecimal crediteNoteValue;

    @Schema(description = "The productId of the credited Sales Receipt.", example = "70021")
    private String productId;

    @Schema(description = "The raw QuickBooks entity/entities created — always a single-element array containing the CreditMemo.")
    private List<Object> documents;
}
