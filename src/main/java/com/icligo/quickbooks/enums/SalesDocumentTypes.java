package com.icligo.quickbooks.enums;

import lombok.Getter;

/**
 * Mirrors the invoice-management-system's {@code TiposDocumentoVendas}, scoped down to the
 * document types this service creates. {@code CREDIT_MEMO} is system-generated only (created by
 * {@code SalesReceiptCancellationService} when a booking Invoice cancels prior Sales Receipts,
 * see docs/OPERATIONS.md §6) — like {@code REFUND_RECEIPT}, it's rejected on {@code POST
 * /documents} ({@link com.icligo.quickbooks.validation.QuickBooksDocumentValidator}).
 */
@Getter
public enum SalesDocumentTypes {

    INVOICE("INV"), SALES_RECEIPT("SRT"), REFUND_RECEIPT("RRT"), CREDIT_MEMO("CDM");

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
