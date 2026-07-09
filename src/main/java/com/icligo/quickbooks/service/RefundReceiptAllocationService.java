package com.icligo.quickbooks.service;

import com.icligo.quickbooks.enums.SalesDocumentTypes;
import com.icligo.quickbooks.model.CreateRefundRequestDto;
import com.icligo.quickbooks.model.QuickBooksDocument;
import com.icligo.quickbooks.model.document.ItemDto;
import com.icligo.quickbooks.service.authentication.QuickBooksAlertService;
import com.icligo.quickbooks.temporal.TemporalDocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Mirrors the invoice-management-system's {@code createRefundInvoices}/{@code
 * calculateProportionalAllocations} for the NCA (advance-payment credit note) case — our
 * equivalent being Sales Receipts. The caller states a {@code serviceId} and a total
 * {@code value} to refund, not a specific document; this finds every Sales Receipt still open
 * for that {@code serviceId} (via {@link ActiveSalesReceiptFinder} — the same "available
 * balance" accounting used by {@link SalesReceiptCancellationService}, so both flows treat
 * "already refunded" identically) and splits {@code value} across them proportionally to each
 * one's remaining balance.
 *
 * <p>Each allocation becomes its own RefundReceipt, created via the normal
 * {@link TemporalDocumentService#create} path — so controlKey generation (including the
 * {@code "_rfd" + refundId} suffix), idempotency, and QuickBooks creation are all identical to
 * any other RefundReceipt, just driven by this allocation instead of a caller-supplied
 * productId/items.
 *
 * <p>Best-effort per allocation, matching the reference implementation: a failure creating one
 * Sales Receipt's RefundReceipt is logged and alerted by email, but doesn't stop the other
 * allocations in the same request from being processed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundReceiptAllocationService {

    private final ActiveSalesReceiptFinder activeSalesReceiptFinder;
    private final TemporalDocumentService temporalDocumentService;
    private final QuickBooksAlertService alertService;

    public List<QuickBooksDocument> createAllocatedRefundReceipts(CreateRefundRequestDto request) {
        List<ActiveSalesReceipt> active = activeSalesReceiptFinder.findActive(request.getServiceId());
        if (active.isEmpty()) {
            throw new IllegalArgumentException("No open Sales Receipts found for serviceId=" + request.getServiceId());
        }

        List<RefundAllocation> allocations = allocateProportionally(active, request.getValue());

        List<QuickBooksDocument> created = new ArrayList<>();
        for (RefundAllocation allocation : allocations) {
            if (allocation.amount().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            String productId = allocation.activeSalesReceipt().document().getProductId();
            try {
                QuickBooksDocument refundDoc = buildRefundDocument(allocation, request.getRefundId());
                created.add(temporalDocumentService.create(refundDoc));
                log.info("Created RefundReceipt for productId={} serviceId={} amount={}",
                        productId, request.getServiceId(), allocation.amount());
            } catch (Exception e) {
                log.error("Failed to create RefundReceipt for productId={} serviceId={}: {}",
                        productId, request.getServiceId(), e.getMessage(), e);
                alertService.sendRefundAllocationFailedAlert(request.getServiceId(), productId, allocation.amount(), e.getMessage());
            }
        }
        return created;
    }

    /**
     * Mirrors {@code calculateProportionalAllocations} exactly: if the requested value is at
     * least the total available balance, every active Sales Receipt is fully consumed and the
     * excess is silently ignored; otherwise the value is split proportionally to each Sales
     * Receipt's share of the total, with the last allocation absorbing the rounding remainder so
     * the sum always equals the requested value.
     */
    private List<RefundAllocation> allocateProportionally(List<ActiveSalesReceipt> active, BigDecimal requestedValue) {
        BigDecimal totalAvailable = active.stream()
                .map(ActiveSalesReceipt::availableBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (requestedValue.compareTo(totalAvailable) >= 0) {
            log.warn("Refund value {} >= total available {} — fully consuming every open Sales Receipt, ignoring the excess",
                    requestedValue, totalAvailable);
            return active.stream()
                    .map(a -> new RefundAllocation(a, a.availableBalance()))
                    .toList();
        }

        List<RefundAllocation> allocations = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO;

        for (int i = 0; i < active.size(); i++) {
            ActiveSalesReceipt activeSalesReceipt = active.get(i);
            BigDecimal amount;

            if (i == active.size() - 1) {
                amount = requestedValue.subtract(allocated)
                        .min(activeSalesReceipt.availableBalance())
                        .max(BigDecimal.ZERO);
            } else {
                BigDecimal proportional = requestedValue
                        .multiply(activeSalesReceipt.availableBalance())
                        .divide(totalAvailable, new MathContext(10, RoundingMode.HALF_UP))
                        .setScale(2, RoundingMode.HALF_UP);
                amount = proportional.min(activeSalesReceipt.availableBalance());
            }

            allocations.add(new RefundAllocation(activeSalesReceipt, amount));
            allocated = allocated.add(amount);
        }

        return allocations;
    }

    /**
     * Builds the RefundReceipt request document for one allocation: same serviceId/productId/
     * clientInvoiceInfo as the original Sales Receipt, a single line (mirroring the reference's
     * "first item" convention, not a proportional split across every original line — the credited
     * total is what matters here, not per-line detail), and the given refundId, which
     * {@link TemporalDocumentService} embeds as the controlKey's {@code "_rfd" + refundId} suffix.
     */
    private QuickBooksDocument buildRefundDocument(RefundAllocation allocation, String refundId) {
        QuickBooksDocument original = allocation.activeSalesReceipt().document();
        ItemDto firstItem = original.getItems().get(0);

        ItemDto refundItem = new ItemDto();
        refundItem.setItem(firstItem.getItem());
        refundItem.setValue(allocation.amount());

        QuickBooksDocument refundDoc = new QuickBooksDocument();
        refundDoc.setType(SalesDocumentTypes.REFUND_RECEIPT.getValue());
        refundDoc.setServiceId(original.getServiceId());
        refundDoc.setProductId(original.getProductId());
        refundDoc.setRefundId(refundId);
        refundDoc.setDescription(original.getDescription());
        refundDoc.setPaymentMethod(original.getPaymentMethod());
        refundDoc.setClientInvoiceInfo(original.getClientInvoiceInfo());
        refundDoc.setItems(List.of(refundItem));
        return refundDoc;
    }

    private record RefundAllocation(ActiveSalesReceipt activeSalesReceipt, BigDecimal amount) {
    }
}
