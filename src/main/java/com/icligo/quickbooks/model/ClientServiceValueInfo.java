package com.icligo.quickbooks.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One client's aggregated, still-open Sales Receipt value for a {@code serviceId} — mirrors the
 * invoice-management-system's {@code getServiceIdClients}/{@code ClientValueInvoiceInfo}, adapted
 * to this project's document types: FAA (advance invoice) → SalesReceipt, NCA (advance credit
 * note) → RefundReceipt/CreditMemo (see {@link com.icligo.quickbooks.service.ActiveSalesReceiptFinder}).
 * There's no Proforma/"fee" equivalent here, so that field is dropped entirely rather than
 * always sent as zero.
 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "One client's aggregated, still-open (not yet refunded/cancelled) Sales "
        + "Receipt value for a serviceId.")
public class ClientServiceValueInfo {

    @Schema(description = "Server-computed dedup hash of name+address+country — the same value "
            + "as clientInvoiceInfo.clientHash. Clients are grouped by this, not by name/email.")
    private String clientHash;

    @Schema(example = "Jane Doe")
    private String name;

    @Schema(example = "jane.doe@example.com")
    private String email;

    @Schema(example = "+351912345678")
    private String phone;

    @Schema(example = "Rua Example 123")
    private String address;

    @Schema(example = "Lisboa")
    private String city;

    @Schema(example = "1000-001")
    private String zipCode;

    @Schema(example = "PT")
    private String country;

    @Schema(description = "Tax id, if the original request included one.", example = "123456789")
    private String nif;

    @Schema(description = "Sum of this client's still-open Sales Receipt balances for this "
            + "serviceId — each Sales Receipt's TotalAmt minus any RefundReceipt/CreditMemo "
            + "already on file against its productId.")
    private BigDecimal total = BigDecimal.ZERO;

    @Schema(description = "Same total, broken down per productId.")
    private Map<String, BigDecimal> productValues = new LinkedHashMap<>();

    public void addValue(String productId, BigDecimal value) {
        this.total = this.total.add(value);
        this.productValues.put(productId, value);
    }
}
