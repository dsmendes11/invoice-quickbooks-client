package com.icligo.quickbooks.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Mirrors the invoice-management-system's {@code CreateRefundRequestDto}/{@code createRefundInvoices}:
 * the caller states how much to refund for a {@code serviceId}, not which specific document or
 * line — {@link com.icligo.quickbooks.service.RefundReceiptAllocationService} determines which
 * Sales Receipts are still open and allocates {@code value} across them.
 */
@Data
@NoArgsConstructor
@Schema(description = "Refunds up to `value`, allocated proportionally across every Sales Receipt still "
        + "open for this serviceId. If `value` exceeds the total available balance, every open Sales "
        + "Receipt is fully consumed and the excess is silently ignored. Can result in zero, one, or "
        + "multiple RefundReceipts being created in QuickBooks (one per Sales Receipt that received an "
        + "allocation).")
public class CreateRefundRequestDto {

    @NotBlank(message = "serviceId is required")
    @Schema(example = "48213", requiredMode = Schema.RequiredMode.REQUIRED)
    private String serviceId;

    @NotBlank(message = "refundId is required")
    @Schema(description = "Identifies this refund transaction — embedded (as \"_rfd\" + refundId) in the "
            + "controlKey of every RefundReceipt this call creates.", example = "rfd-9931",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String refundId;

    @NotNull(message = "value is required")
    @Positive(message = "value must be greater than zero")
    @Schema(description = "Total amount to refund, before allocation across Sales Receipts.",
            example = "49.90", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal value;
}
