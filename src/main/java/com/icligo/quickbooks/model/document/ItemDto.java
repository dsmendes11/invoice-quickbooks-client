package com.icligo.quickbooks.model.document;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Represents a line item on a PrimaveraDocument invoice.
 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A single line item. Only `item` and `value` are currently sent to QuickBooks; "
        + "quantity is always 1.")
public class ItemDto {
    @Schema(description = "Line description, mapped to the QuickBooks line's Description.", example = "Airport transfer")
    String item;

    @Schema(description = "Not currently used by document creation. Accepted for forward-compatibility only.")
    String description;

    @Schema(description = "Not currently used by document creation. Accepted for forward-compatibility only.")
    String tax;

    @Schema(description = "Line amount, mapped to both Amount and UnitPrice (Qty is hardcoded to 1).", example = "49.90")
    BigDecimal value;

    @Schema(description = "Not currently used by document creation. Accepted for forward-compatibility only.")
    BigDecimal discount;

    @Schema(description = "Booking locator. Joined across all items with '|' and set as the "
            + "\"Reference no.\" on Sales Receipts, or the \"Memo\" on Invoices/CreditMemos/Refund Receipts.")
    String locator;

    @Schema(description = "Not currently used by document creation. Accepted for forward-compatibility only.")
    LocalDateTime itemDate;
}
