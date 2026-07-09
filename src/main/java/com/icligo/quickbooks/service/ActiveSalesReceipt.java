package com.icligo.quickbooks.service;

import com.icligo.quickbooks.clients.quickbooks.model.SalesReceipt;
import com.icligo.quickbooks.model.QuickBooksDocument;

import java.math.BigDecimal;

/**
 * A Sales Receipt that still has value left to credit/refund: {@code availableBalance} is the
 * QuickBooks {@code TotalAmt} minus every Refund Receipt already on file for the same
 * {@code productId} (see {@link ActiveSalesReceiptFinder}).
 */
public record ActiveSalesReceipt(QuickBooksDocument document, SalesReceipt salesReceipt, BigDecimal availableBalance) {
}
