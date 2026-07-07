package com.icligo.quickbooks.model.document;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Client billing details embedded inside a PrimaveraDocument.
 */
@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ClientInvoiceInfo {
    String clientId;
    String clientHash;
    String name;
    String email;
    String phone;
    String address;
    String city;
    String zipCode;
    String country;
    String clientCountry;
    String nif;
    Boolean finalCustomer;
}
