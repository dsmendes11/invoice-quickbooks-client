package com.icligo.quickbooks.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonView;
import com.icligo.quickbooks.model.document.ClientInvoiceInfo;
import com.icligo.quickbooks.model.document.ItemDto;
import com.icligo.quickbooks.validation.ValidQuickBooksDocument;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Document(collection = "quickbooks_document")
@JsonView({QuickBooksDocument.class})
@ValidQuickBooksDocument
@Schema(description = "Request to create, and response containing, a QuickBooks invoice/receipt.")
public class QuickBooksDocument {

    @Schema(description = "Server-generated Mongo id. Ignored on request, populated on response.",
            accessMode = Schema.AccessMode.READ_ONLY)
    @Id
    private String id;

    @Schema(description = "The QuickBooks Invoice/SalesReceipt/RefundReceipt object created by this call. "
            + "Ignored on request, populated on response.", accessMode = Schema.AccessMode.READ_ONLY)
    private Object invoice;

    @Schema(description = "Caller identifier, defaults to \"icligous\" when omitted.", example = "icligous")
    private String microsite;

    @Schema(description = "Current-year series, embedded in the internal controlKey (mirrors the "
            + "invoice-management-system's \"serie\"). Server-computed — ignored on request, populated on response.",
            accessMode = Schema.AccessMode.READ_ONLY)
    private String serie;

    @Schema(description = "Free-text memo, mapped to the QuickBooks document's customer memo.",
            example = "Booking #48213")
    private String description;

    @NotBlank(message = "serviceId is required")
    @Schema(description = "Used to build the QuickBooks doc number (\"INV\"+serviceId) and the Temporal workflow "
            + "id when type=INV. Required for every type regardless, for cross-type context — but only "
            + "productId feeds into the internal controlKey.",
            example = "48213", requiredMode = Schema.RequiredMode.REQUIRED)
    private String serviceId;

    @NotBlank(message = "productId is required")
    @Schema(description = "Used to build the QuickBooks doc number when type=SRT/RRT, the "
            + "Temporal workflow id, and the internal controlKey. Required for every type regardless, for "
            + "cross-type context.",
            example = "70021", requiredMode = Schema.RequiredMode.REQUIRED)
    private String productId;

    @Schema(description = "Only meaningful when type=INV: if this is \"Reserva\" (booking), any Sales Receipts "
            + "already on file for this serviceId are cancelled via CreditMemo (see docs/OPERATIONS.md). "
            + "Any other value (or omitted) has no effect.", example = "Reserva")
    private String productType;

    @Schema(description = "Document type to create — the abbreviated code, not the long-form name: "
            + "INV=Invoice, SRT=SalesReceipt. RRT (RefundReceipt) is rejected here — refunds are "
            + "allocation-driven, see POST {api.base-path}/refunds.", allowableValues = {"INV", "SRT"},
            example = "INV", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "type is required")
    private String type;

    @Schema(description = "Only used for SRT/RRT. Every non-null value currently "
            + "resolves to the QuickBooks PaymentMethod \"Credit Card\" (per-code mapping is not implemented "
            + "yet); the request fails if that PaymentMethod doesn't exist in the company. If omitted/null, "
            + "no payment method is sent at all and QuickBooks applies the company's account default.", example = "1")
    private Integer paymentMethod;

    @Schema(description = "Not currently used by document creation. Accepted for forward-compatibility only.")
    private String invoiceType;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "clientInvoiceInfo is required")
    @Valid
    private ClientInvoiceInfo clientInvoiceInfo;

    @Schema(description = "Line items — required, at least one. Only `item` (description) and `value` (amount, "
            + "qty is always 1) are currently sent to QuickBooks — `discount`/`tax`/`locator`/`itemDate` are "
            + "accepted but not applied. Every `item` must match an existing QuickBooks Item name exactly, every "
            + "`value` must be non-negative, and the total across all lines must be non-zero.",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private List<ItemDto> items;

    @Schema(description = "Not applicable to POST /documents (type=RRT is rejected there). Set internally "
            + "by RefundReceiptAllocationService when creating RefundReceipts via POST {api.base-path}/refunds.",
            accessMode = Schema.AccessMode.READ_ONLY, example = "rfd-9931")
    private String refundId;

    /**
     * Server-computed idempotency key: {@code type + productId + suffix + serie} (e.g.
     * "INV700202026" for productId=70020/serie=2026; suffix is {@code "_rfd" + refundId}
     * for RRT, empty otherwise) — matches the invoice-management-system's own
     * {@code checkAndCreateChaveControlo} exactly. A unique index on this field is what turns a
     * repeated create request into a no-op that returns the original document instead of
     * creating a duplicate in QuickBooks. Always built internally — never accepted from or
     * shown to API callers.
     */
    @JsonIgnore
    @Schema(hidden = true)
    @Indexed(unique = true)
    private String controlKey;

    /**
     * Returns the microsite value, defaulting to "icligo" when not set.
     */
    public String getMicrosite() {
        if (microsite == null) {
            return "icligous";
        }
        return microsite;
    }
}
