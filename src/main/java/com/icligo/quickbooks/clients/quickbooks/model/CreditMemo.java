package com.icligo.quickbooks.clients.quickbooks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CreditMemo {

    @JsonProperty("Id")
    private String id;

    @JsonProperty("SyncToken")
    private String syncToken;

    @JsonProperty("DocNumber")
    private String docNumber;

    @JsonProperty("TxnDate")
    private String txnDate;

    @JsonProperty("CustomerRef")
    private ReferenceType customerRef;

    @JsonProperty("Line")
    private List<Line> line;

    @JsonProperty("TxnTaxDetail")
    private TxnTaxDetail txnTaxDetail;

    @JsonProperty("CustomerMemo")
    private MemoRef customerMemo;

    @JsonProperty("TotalAmt")
    private BigDecimal totalAmt;

    @JsonProperty("Balance")
    private BigDecimal balance;

    @JsonProperty("MetaData")
    private MetaData metaData;

    public String getCustomerId() {
        return customerRef != null ? customerRef.getValue() : null;
    }
}
