package com.icligo.quickbooks.clients.quickbooks.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@Document(collection = "customer")
public class Customer {
    @Id
    @JsonProperty("Id")
    private String id;

    @JsonProperty("SyncToken")
    private String syncToken;

    @JsonProperty("Title")
    private String title;

    @JsonProperty("GivenName")
    private String givenName;

    @JsonProperty("MiddleName")
    private String middleName;

    @JsonProperty("FamilyName")
    private String familyName;

    @JsonProperty("Suffix")
    private String suffix;

    @JsonProperty("FullyQualifiedName")
    private String fullyQualifiedName;

    @JsonProperty("CompanyName")
    private String companyName;

    @JsonProperty("DisplayName")
    private String displayName;

    @JsonProperty("PrintOnCheckName")
    private String printOnCheckName;

    @JsonProperty("Active")
    private Boolean active;

    @JsonProperty("PrimaryPhone")
    private TelephoneNumber primaryPhone;

    @JsonProperty("AlternatePhone")
    private TelephoneNumber alternatePhone;

    @JsonProperty("Mobile")
    private TelephoneNumber mobile;

    @JsonProperty("Fax")
    private TelephoneNumber fax;

    @JsonProperty("PrimaryEmailAddr")
    private EmailAddress primaryEmailAddr;

    @JsonProperty("WebAddr")
    private WebSiteAddress webAddr;

    @JsonProperty("BillAddr")
    private PhysicalAddress billAddr;

    @JsonProperty("ShipAddr")
    private PhysicalAddress shipAddr;

    @JsonProperty("Notes")
    private String notes;

    @JsonProperty("Taxable")
    private Boolean taxable;

    @JsonProperty("Balance")
    private Double balance;

    @JsonProperty("BalanceWithJobs")
    private Double balanceWithJobs;

    @JsonProperty("CurrencyRef")
    private ReferenceType currencyRef;

    @JsonProperty("PreferredDeliveryMethod")
    private String preferredDeliveryMethod;

    @JsonProperty("ResaleNum")
    private String resaleNum;

    @JsonProperty("MetaData")
    private ModificationMetaData metaData;

    // Nested classes based on QuickBooks API

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TelephoneNumber {
        @JsonProperty("FreeFormNumber")
        private String freeFormNumber;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EmailAddress {
        @JsonProperty("Address")
        private String address;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WebSiteAddress {
        @JsonProperty("URI")
        private String uri;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PhysicalAddress {
        @JsonProperty("Id")
        private String id;

        @JsonProperty("Line1")
        private String line1;

        @JsonProperty("Line2")
        private String line2;

        @JsonProperty("Line3")
        private String line3;

        @JsonProperty("Line4")
        private String line4;

        @JsonProperty("Line5")
        private String line5;

        @JsonProperty("City")
        private String city;

        @JsonProperty("Country")
        private String country;

        @JsonProperty("CountrySubDivisionCode")
        private String countrySubDivisionCode;

        @JsonProperty("PostalCode")
        private String postalCode;

        @JsonProperty("Lat")
        private String lat;

        @JsonProperty("Long")
        private String lon;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReferenceType {
        @JsonProperty("value")
        private String value;

        @JsonProperty("name")
        private String name;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ModificationMetaData {
        @JsonProperty("CreateTime")
        private String createTime;

        @JsonProperty("LastUpdatedTime")
        private String lastUpdatedTime;
    }

    // Compatibility methods for backward compatibility with existing code — @JsonIgnore so
    // these don't leak as extra unrecognized properties (QuickBooks' API rejects a POST/PUT
    // body containing them with a 400 ValidationFault).
    @JsonIgnore
    public String getName() {
        if (displayName != null) return displayName;
        if (companyName != null) return companyName;
        if (givenName != null && familyName != null) return givenName + " " + familyName;
        if (givenName != null) return givenName;
        return null;
    }

    @JsonIgnore
    public String getEmail() {
        return (primaryEmailAddr != null) ? primaryEmailAddr.getAddress() : null;
    }

    @JsonIgnore
    public String getPhone() {
        return (primaryPhone != null) ? primaryPhone.getFreeFormNumber() : null;
    }

    @JsonIgnore
    public Address getAddress() {
        if (billAddr == null) return null;
        return Address.builder()
                .street(billAddr.getLine1())
                .city(billAddr.getCity())
                .state(billAddr.getCountrySubDivisionCode())
                .zip(billAddr.getPostalCode())
                .country(billAddr.getCountry())
                .build();
    }

    // Legacy Address class for compatibility
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Address {
        private String street;
        private String city;
        private String state;
        private String zip;
        private String country;
    }
}