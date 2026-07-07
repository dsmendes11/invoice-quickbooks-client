package com.icligo.quickbooks.clients.quickbooks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Invoice {

    @JsonProperty("Id")
    private String id;

    @JsonProperty("SyncToken")
    private String syncToken;

    @JsonProperty("DocNumber")
    private String docNumber;

    @JsonProperty("TxnDate")
    private String txnDate;  // Format: YYYY-MM-DD

    @JsonProperty("DueDate")
    private String dueDate;  // Format: YYYY-MM-DD

    @JsonProperty("CustomerRef")
    private ReferenceType customerRef;

    @JsonProperty("Line")
    private List<Line> line;

    @JsonProperty("TxnTaxDetail")
    private TxnTaxDetail txnTaxDetail;

    @JsonProperty("CustomerMemo")
    private MemoRef customerMemo;

    @JsonProperty("BillAddr")
    private PhysicalAddress billAddr;

    @JsonProperty("ShipAddr")
    private PhysicalAddress shipAddr;

    @JsonProperty("TotalAmt")
    private BigDecimal totalAmt;

    @JsonProperty("Balance")
    private BigDecimal balance;

    @JsonProperty("MetaData")
    private MetaData metaData;

    // Compatibility methods
    public String getInvoiceNumber() {
        return docNumber;
    }

    public String getCustomerId() {
        return customerRef != null ? customerRef.getValue() : null;
    }

    public String getStatus() {
        if (balance != null && totalAmt != null) {
            return balance.compareTo(BigDecimal.ZERO) == 0 ? "paid" : "unpaid";
        }
        return "unpaid";
    }
}
