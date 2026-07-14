package com.icligo.quickbooks.clients.quickbooks.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
public class Payment {

    @JsonProperty("Id")
    private String id;

    @JsonProperty("SyncToken")
    private String syncToken;

    @JsonProperty("TxnDate")
    private String txnDate;

    @JsonProperty("CustomerRef")
    private ReferenceType customerRef;

    @JsonProperty("TotalAmt")
    private BigDecimal totalAmt;

    @JsonProperty("DepositToAccountRef")
    private ReferenceType depositToAccountRef;

    @JsonProperty("PaymentMethodRef")
    private ReferenceType paymentMethodRef;

    @JsonProperty("PaymentRefNum")
    private String paymentRefNum;

    @JsonProperty("Line")
    private List<PaymentLine> line;

    @JsonProperty("MetaData")
    private MetaData metaData;

    // @JsonIgnore so this doesn't leak as an extra unrecognized property (QuickBooks' API
    // rejects a POST/PUT body containing it with a 400 ValidationFault).
    @JsonIgnore
    public String getCustomerId() {
        return customerRef != null ? customerRef.getValue() : null;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PaymentLine {
        @JsonProperty("Amount")
        private BigDecimal amount;

        @JsonProperty("LinkedTxn")
        private List<LinkedTxn> linkedTxn;
    }

    /**
     * Applies this Payment against an existing transaction (here, always an Invoice) —
     * {@code txnType} must match QuickBooks' own transaction type name exactly ("Invoice").
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LinkedTxn {
        @JsonProperty("TxnId")
        private String txnId;

        @JsonProperty("TxnType")
        private String txnType;
    }
}
