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
public class PhysicalAddress {
    @JsonProperty("Line1")
    private String line1;

    @JsonProperty("City")
    private String city;

    @JsonProperty("CountrySubDivisionCode")
    private String countrySubDivisionCode;

    @JsonProperty("PostalCode")
    private String postalCode;

    @JsonProperty("Country")
    private String country;
}