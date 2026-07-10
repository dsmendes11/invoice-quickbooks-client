package com.icligo.quickbooks.clients.quickbooks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class SalesItemLineDetail {
    @JsonProperty("ItemRef")
    private ReferenceType itemRef;

    @JsonProperty("Qty")
    private Integer qty;

    @JsonProperty("UnitPrice")
    private BigDecimal unitPrice;

    @JsonProperty("TaxCodeRef")
    private ReferenceType taxCodeRef;
}