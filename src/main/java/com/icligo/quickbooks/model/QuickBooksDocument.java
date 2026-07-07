package com.icligo.quickbooks.model;

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

    @Schema(description = "Caller identifier, defaults to \"icligousa\" when omitted.", example = "icligousa")
    private String microsite;

    @Schema(description = "Not currently used by document creation. Accepted for forward-compatibility only.")
    private String controlKey;

    @Schema(description = "Not currently used by document creation. Accepted for forward-compatibility only.")
    private String serie;

    @Schema(description = "Free-text memo, mapped to the QuickBooks document's customer memo.",
            example = "Booking #48213")
    private String description;

    @Schema(description = "Required when type=INVOICE. Used to build the QuickBooks doc number (\"INV\"+serviceId) "
            + "and the Temporal workflow id.", example = "48213")
    private String serviceId;

    @Schema(description = "Required when type=SALES_RECEIPT or REFUND_RECEIPT. Used to build the QuickBooks doc "
            + "number and the Temporal workflow id.", example = "70021")
    private String productId;

    @Schema(description = "Not currently used by document creation. Accepted for forward-compatibility only.")
    private String productType;

    @Schema(description = "Document type to create.", allowableValues = {"INVOICE", "SALES_RECEIPT", "REFUND_RECEIPT"},
            example = "INVOICE", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "type is required")
    private String type;

    @Schema(description = "Only used for SALES_RECEIPT/REFUND_RECEIPT. 1=credit_card, 2=debit_card, "
            + "anything else defaults to credit_card.", example = "1")
    private Integer paymentMethod;

    @Schema(description = "Not currently used by document creation. Accepted for forward-compatibility only.")
    private String invoiceType;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "clientInvoiceInfo is required")
    @Valid
    private ClientInvoiceInfo clientInvoiceInfo;

    @Schema(description = "Line items. Only `item` (description) and `value` (amount, qty is always 1) are "
            + "currently sent to QuickBooks — `discount`/`tax`/`locator`/`itemDate` are accepted but not applied.")
    private List<ItemDto> items;

    @Schema(description = "Required when type=REFUND_RECEIPT. Used to build the QuickBooks doc number and the "
            + "Temporal workflow id.", example = "rfd-9931")
    private String refundId;

    /**
     * Returns the microsite value, defaulting to "icligo" when not set.
     */
    public String getMicrosite() {
        if (microsite == null) {
            return "icligousa";
        }
        return microsite;
    }
}
