package com.icligo.quickbooks.util;

import com.icligo.quickbooks.model.document.ItemDto;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Joins every item's {@code locator} into a single string (mirroring the invoice-management-system's
 * {@code DocumentService} locator-joining for its own alert emails), used to fill the
 * "Reference no." field on Sales Receipts and the "Memo" field on Invoices/CreditMemos/Refund
 * Receipts. Same {@code "|"} separator as the reference project.
 */
public final class ItemLocatorUtils {

    private ItemLocatorUtils() {
    }

    public static String joinLocators(List<ItemDto> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        String joined = items.stream()
                .map(ItemDto::getLocator)
                .filter(locator -> locator != null && !locator.isBlank())
                .collect(Collectors.joining("|"));
        return joined.isBlank() ? null : joined;
    }
}
