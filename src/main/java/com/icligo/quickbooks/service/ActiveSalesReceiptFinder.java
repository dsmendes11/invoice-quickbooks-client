package com.icligo.quickbooks.service;

import com.icligo.quickbooks.clients.quickbooks.model.CreditMemo;
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
import java.util.Optional;

/**
 * Single source of truth for "how much of this serviceId's Sales Receipts is still open" —
 * shared by {@link SalesReceiptCancellationService} (booking/Reserva invoices cancel the full
 * remaining balance), {@link RefundReceiptAllocationService} (partial refunds allocate a
 * requested amount across it), and {@link ClientAggregationService} (per-client reporting).
 * All three must agree on what "already credited back" means, so this exists once rather than
 * being recomputed independently in each.
 *
 * <p>Both a Refund Receipt and a CreditMemo are matched to a Sales Receipt by exact
 * {@code productId} equality — unlike the invoice-management-system's {@code
 * cnProductId.contains(invoiceProductId + "_rfd")} check, our schema keeps {@code productId} and
 * {@code refundId} as distinct fields (the {@code _rfd} suffix only appears inside the
 * generated {@code controlKey}, never in the stored {@code productId} itself), so a plain
 * equality match is both correct and sufficient here.
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
            findActiveForDocument(doc).ifPresent(active::add);
        }
        return active;
    }

    /**
     * Same "how much is still open" calculation as {@link #findActive}, for a single, already
     * looked-up Sales Receipt document — shared so a caller that already has the document (e.g.
     * looked up by {@code controlKey}, see {@link SalesReceiptCancellationService#cancelSalesReceiptByControlKey})
     * doesn't have to re-derive this logic.
     */
    public Optional<ActiveSalesReceipt> findActiveForDocument(QuickBooksDocument doc) {
        if (!(doc.getInvoice() instanceof SalesReceipt salesReceipt) || salesReceipt.getTotalAmt() == null) {
            return Optional.empty();
        }
        BigDecimal alreadyCredited = sumCredits(doc.getProductId());
        BigDecimal availableBalance = salesReceipt.getTotalAmt().subtract(alreadyCredited);
        if (availableBalance.compareTo(BigDecimal.ZERO) <= 0) {
            return Optional.empty();
        }
        return Optional.of(new ActiveSalesReceipt(doc, salesReceipt, availableBalance));
    }

    /**
     * Sums both RefundReceipts (partial refunds via {@code POST /refunds}) and CreditMemos (a
     * booking/Reserva Invoice superseding this Sales Receipt, see
     * {@link SalesReceiptCancellationService}) already on file for this {@code productId} — a
     * Sales Receipt fully cancelled by a CreditMemo has nothing left "available" either, exactly
     * like one that's been fully refunded.
     */
    private BigDecimal sumCredits(String productId) {
        BigDecimal sum = BigDecimal.ZERO;

        for (QuickBooksDocument refundDoc : documentRepository.findByTypeAndProductId(
                SalesDocumentTypes.REFUND_RECEIPT.getValue(), productId)) {
            if (refundDoc.getInvoice() instanceof RefundReceipt refundReceipt && refundReceipt.getTotalAmt() != null) {
                sum = sum.add(refundReceipt.getTotalAmt());
            }
        }

        for (QuickBooksDocument creditMemoDoc : documentRepository.findByTypeAndProductId(
                SalesDocumentTypes.CREDIT_MEMO.getValue(), productId)) {
            if (creditMemoDoc.getInvoice() instanceof CreditMemo creditMemo && creditMemo.getTotalAmt() != null) {
                sum = sum.add(creditMemo.getTotalAmt());
            }
        }

        return sum;
    }
}
