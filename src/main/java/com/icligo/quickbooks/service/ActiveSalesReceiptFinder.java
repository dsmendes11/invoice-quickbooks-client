package com.icligo.quickbooks.service;

import com.icligo.quickbooks.clients.quickbooks.model.RefundReceipt;
import com.icligo.quickbooks.clients.quickbooks.model.SalesReceipt;
import com.icligo.quickbooks.enums.SalesDocumentTypes;
import com.icligo.quickbooks.model.QuickBooksDocument;
import com.icligo.quickbooks.repository.QuickBooksDocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Single source of truth for "how much of this serviceId's Sales Receipts is still open" —
 * shared by {@link SalesReceiptCancellationService} (booking/Reserva invoices cancel the full
 * remaining balance) and {@link RefundReceiptAllocationService} (partial refunds allocate a
 * requested amount across it). Both must agree on what "already refunded" means, so this exists
 * once rather than being recomputed independently in each.
 *
 * <p>A Refund Receipt is matched to a Sales Receipt by exact {@code productId} equality — unlike
 * the invoice-management-system's {@code cnProductId.contains(invoiceProductId + "_rfd")} check,
 * our schema keeps {@code productId} and {@code refundId} as distinct fields (the {@code _rfd}
 * suffix only appears inside the generated {@code controlKey}, never in the stored
 * {@code productId} itself), so a plain equality match is both correct and sufficient here.
 */
@Service
@RequiredArgsConstructor
public class ActiveSalesReceiptFinder {

    private final QuickBooksDocumentRepository documentRepository;

    public List<ActiveSalesReceipt> findActive(String serviceId) {
        List<QuickBooksDocument> salesReceiptDocs = documentRepository.findByTypeAndServiceId(
                SalesDocumentTypes.SALES_RECEIPT.getValue(), serviceId);

        List<ActiveSalesReceipt> active = new ArrayList<>();
        for (QuickBooksDocument doc : salesReceiptDocs) {
            if (!(doc.getInvoice() instanceof SalesReceipt salesReceipt) || salesReceipt.getTotalAmt() == null) {
                continue;
            }
            BigDecimal alreadyRefunded = sumRefunds(doc.getProductId());
            BigDecimal availableBalance = salesReceipt.getTotalAmt().subtract(alreadyRefunded);
            if (availableBalance.compareTo(BigDecimal.ZERO) > 0) {
                active.add(new ActiveSalesReceipt(doc, salesReceipt, availableBalance));
            }
        }
        return active;
    }

    private BigDecimal sumRefunds(String productId) {
        List<QuickBooksDocument> refunds = documentRepository.findByTypeAndProductId(
                SalesDocumentTypes.REFUND_RECEIPT.getValue(), productId);

        BigDecimal sum = BigDecimal.ZERO;
        for (QuickBooksDocument refundDoc : refunds) {
            if (refundDoc.getInvoice() instanceof RefundReceipt refundReceipt && refundReceipt.getTotalAmt() != null) {
                sum = sum.add(refundReceipt.getTotalAmt());
            }
        }
        return sum;
    }
}
