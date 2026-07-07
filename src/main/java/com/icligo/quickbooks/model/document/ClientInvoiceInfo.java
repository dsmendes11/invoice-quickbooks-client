package com.icligo.quickbooks.model.document;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Client billing details embedded inside a PrimaveraDocument.
 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Customer used to find-or-create the QuickBooks customer. At least one of "
        + "name/email/address/country must be set.")
public class ClientInvoiceInfo {
    @Schema(description = "QuickBooks customer id. Ignored on request, populated on response.",
            accessMode = Schema.AccessMode.READ_ONLY)
    String clientId;

    @Schema(description = "Server-computed dedup hash of name+address+country. Ignored on request, "
            + "populated on response.", accessMode = Schema.AccessMode.READ_ONLY)
    String clientHash;

    @Schema(example = "Jane Doe")
    String name;

    @Schema(example = "jane.doe@example.com")
    String email;

    @Schema(example = "+351912345678")
    String phone;

    @Schema(example = "Rua Example 123")
    String address;

    @Schema(example = "Lisboa")
    String city;

    @Schema(example = "1000-001")
    String zipCode;

    @Schema(description = "Used as the QuickBooks BillAddr/ShipAddr country. Defaults to \"US\" when blank.",
            example = "PT")
    String country;

    @Schema(description = "Not currently used by document creation. Accepted for forward-compatibility only.")
    String clientCountry;

    @Schema(description = "Tax id. Not currently mapped onto the QuickBooks customer/document.", example = "123456789")
    String nif;

    @Schema(description = "Not currently used by document creation. Accepted for forward-compatibility only.")
    Boolean finalCustomer;
}
