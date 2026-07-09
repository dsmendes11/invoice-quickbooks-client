package com.icligo.quickbooks.enums;

import lombok.Getter;

/**
 * Mirrors the invoice-management-system's {@code ProductTypes}. Only {@link #BOOKING}
 * ("Reserva") currently drives any business logic (see {@code CreateInvoiceWorkflowImpl}),
 * the rest are replicated for parity/forward-compatibility.
 */
@Getter
public enum ProductTypes {

    SERVICE("Serviço"),
    BOOKING("Reserva"),
    ENROLLMENT("Inscrição"),
    GIFT("Gift"),
    UPGRADE("Licença"),
    YMG("Inscrição YMG");

    private final String value;

    ProductTypes(String value) {
        this.value = value;
    }

    public static boolean exists(String value) {
        for (ProductTypes v : values()) {
            if (v.value.equals(value)) {
                return true;
            }
        }
        return false;
    }

    public static ProductTypes getByValue(String value) {
        for (ProductTypes v : values()) {
            if (v.value.equals(value)) {
                return v;
            }
        }
        return null;
    }
}
