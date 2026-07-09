package com.icligo.quickbooks.enums;

import lombok.Getter;

/**
 * Mirrors the invoice-management-system's {@code TiposDocumentoVendas}, scoped down to the
 * three document types this service creates.
 */
@Getter
public enum SalesDocumentTypes {

    INVOICE("INV"), SALES_RECEIPT("SRT"), REFUND_RECEIPT("RRT");

    private final String value;

    SalesDocumentTypes(String value) {
        this.value = value;
    }

    public static boolean exists(String value) {
        for (SalesDocumentTypes v : values()) {
            if (v.value.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    public static SalesDocumentTypes getByValue(String value) {
        for (SalesDocumentTypes v : values()) {
            if (v.value.equalsIgnoreCase(value)) {
                return v;
            }
        }
        return null;
    }
}
