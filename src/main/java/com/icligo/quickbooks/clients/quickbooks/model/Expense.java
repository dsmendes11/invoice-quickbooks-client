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
public class Expense {
    
    private String id;
    private LocalDate date;
    private Double amount;
    private String category;
    private String vendor;
    private String description;
    
    @JsonProperty("payment_method")
    private String paymentMethod;
    
    @JsonProperty("receipt_url")
    private String receiptUrl;
    
    @JsonProperty("created_at")
    private Instant createdAt;
}
