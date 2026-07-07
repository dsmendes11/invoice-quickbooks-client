package com.icligo.quickbooks.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonView;
import com.icligo.quickbooks.model.document.ClientInvoiceInfo;
import com.icligo.quickbooks.model.document.ItemDto;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Document(collection = "quickbooks_document")
@JsonView({QuickBooksDocument.class})
public class QuickBooksDocument {

    @Id
    private String id;

    private Object invoice;

    private String microsite;

    private String controlKey;

    private String serie;

    private String description;

    private String serviceId;

    private String productId;

    private String productType;

    private String type;

    private Integer paymentMethod;

    private String invoiceType;

    private ClientInvoiceInfo clientInvoiceInfo;

    private List<ItemDto> items;

    private String refundId;

    /**
     * Returns the microsite value, defaulting to "icligo" when not set.
     */
    public String getMicrosite() {
        if (microsite == null) {
            return "icligousa";
        }
        return microsite;
    }
}
