package com.icligo.quickbooks.util;

/**
 * Maps the integer payment-method code accepted on {@code QuickBooksDocument} to the
 * QuickBooks PaymentMethod name it should resolve to. Every code currently maps to
 * "Credit Card" — per-code mapping (credit card vs. debit card, etc.) is not implemented yet.
 */
public final class PaymentMethodUtils {

    private PaymentMethodUtils() {
    }

    public static String mapPaymentMethod(Integer code) {
        return "Credit Card";
    }
}
