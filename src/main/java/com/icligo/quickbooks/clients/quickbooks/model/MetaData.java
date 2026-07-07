package com.icligo.quickbooks.clients.quickbooks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class MetaData {
    @JsonProperty("CreateTime")
    private String createTime;

    @JsonProperty("LastUpdatedTime")
    private String lastUpdatedTime;
}