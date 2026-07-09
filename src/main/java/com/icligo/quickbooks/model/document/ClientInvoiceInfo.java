package com.icligo.quickbooks.model.document;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Client billing details embedded inside a PrimaveraDocument.
 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Customer used to find-or-create the QuickBooks customer. name, address, and "
        + "country are required.")
public class ClientInvoiceInfo {
    @Schema(description = "QuickBooks customer id. Ignored on request, populated on response.",
            accessMode = Schema.AccessMode.READ_ONLY)
    String clientId;

    @Schema(description = "Server-computed dedup hash of name+address+country. Ignored on request, "
            + "populated on response.", accessMode = Schema.AccessMode.READ_ONLY)
    String clientHash;

    @NotBlank(message = "name is required")
    @Schema(example = "Jane Doe", requiredMode = Schema.RequiredMode.REQUIRED)
    String name;

    @Schema(example = "jane.doe@example.com")
    String email;

    @Schema(example = "+351912345678")
    String phone;

    @NotBlank(message = "address is required")
    @Schema(example = "Rua Example 123", requiredMode = Schema.RequiredMode.REQUIRED)
    String address;

    @Schema(example = "Lisboa")
    String city;

    @Schema(example = "1000-001")
    String zipCode;

    @NotBlank(message = "country is required")
    @Schema(description = "Used as the QuickBooks BillAddr/ShipAddr country.",
            example = "PT", requiredMode = Schema.RequiredMode.REQUIRED)
    String country;

    @Schema(description = "Not currently used by document creation. Accepted for forward-compatibility only.")
    String clientCountry;

    @Schema(description = "Tax id. Not currently mapped onto the QuickBooks customer/document.", example = "123456789")
    String nif;

    @Schema(description = "Not currently used by document creation. Accepted for forward-compatibility only.")
    Boolean finalCustomer;
}
