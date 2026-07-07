package com.icligo.quickbooks.clients.quickbooks.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.Instant;
import java.time.LocalDate;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {
    
    private String id;
    
    @JsonProperty("invoice_id")
    private String invoiceId;
    
    private Double amount;
    
    @JsonProperty("payment_method")
    private String paymentMethod;
    
    @JsonProperty("payment_date")
    private LocalDate paymentDate;
    
    private String reference;
    private String notes;
    private String status;
    
    @JsonProperty("created_at")
    private Instant createdAt;
}
