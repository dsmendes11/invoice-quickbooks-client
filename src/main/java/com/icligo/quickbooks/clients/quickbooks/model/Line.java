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
public class Line {
    @JsonProperty("Id")
    private String id;

    @JsonProperty("LineNum")
    private Integer lineNum;

    @JsonProperty("Description")
    private String description;

    @JsonProperty("Amount")
    private BigDecimal amount;

    @JsonProperty("DetailType")
    private String detailType;

    @JsonProperty("SalesItemLineDetail")
    private SalesItemLineDetail salesItemLineDetail;
}