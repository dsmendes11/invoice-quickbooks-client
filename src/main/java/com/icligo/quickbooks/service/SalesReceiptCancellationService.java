package com.icligo.quickbooks.service;

import com.icligo.quickbooks.clients.quickbooks.model.CreditMemo;
import com.icligo.quickbooks.clients.quickbooks.model.Line;
import com.icligo.quickbooks.clients.quickbooks.model.MemoRef;
import com.icligo.quickbooks.clients.quickbooks.model.SalesItemLineDetail;
import com.icligo.quickbooks.clients.quickbooks.model.SalesReceipt;
import com.icligo.quickbooks.model.QuickBooksDocument;
import com.icligo.quickbooks.service.authentication.QuickBooksAlertService;
import com.icligo.quickbooks.util.QuickBooksException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * When a "Reserva" (booking) Invoice is created for a {@code serviceId}, any prepaid Sales
 * Receipts for that same {@code serviceId} are now superseded and must be cancelled in
 * QuickBooks via a CreditMemo. Uses {@link ActiveSalesReceiptFinder} to get each Sales Receipt's
 * remaining (not-yet-refunded) balance — that full remaining amount is credited, split across
 * the original Sales Receipt's line items in proportion to their share of the total, so the
 * CreditMemo's line detail mirrors the original sale rather than collapsing it into one lump sum.
 *
 * <p>Deliberately best-effort: a failure cancelling one Sales Receipt (e.g. a line's Item no
 * longer exists in QuickBooks) is logged and alerted by email, but never propagated — it must
 * not block the Invoice creation that triggered this, nor prevent cancelling any other matching
 * Sales Receipt.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SalesReceiptCancellationService {

    private final ActiveSalesReceiptFinder activeSalesReceiptFinder;
    private final CreditMemoService creditMemoService;
    private final QuickBooksAlertService alertService;

    public void cancelSalesReceiptsForServiceId(String serviceId) {
        List<ActiveSalesReceipt> active = activeSalesReceiptFinder.findActive(serviceId);

        if (active.isEmpty()) {
            log.info("No open Sales Receipts found for serviceId={} — nothing to cancel", serviceId);
            return;
        }

        for (ActiveSalesReceipt activeSalesReceipt : active) {
            try {
                cancelOne(activeSalesReceipt);
            } catch (Exception e) {
                String productId = activeSalesReceipt.document().getProductId();
                log.error("Failed to cancel SalesReceipt productId={} for serviceId={}: {}",
                        productId, serviceId, e.getMessage(), e);
                alertService.sendCreditMemoCancellationFailedAlert(serviceId, productId, e.getMessage());
            }
        }
    }

    private void cancelOne(ActiveSalesReceipt activeSalesReceipt) throws QuickBooksException {
        QuickBooksDocument salesReceiptDoc = activeSalesReceipt.document();
        SalesReceipt salesReceipt = activeSalesReceipt.salesReceipt();

        if (salesReceipt.getLine() == null || salesReceipt.getLine().isEmpty()) {
            throw new QuickBooksException("SalesReceipt for productId=" + salesReceiptDoc.getProductId()
                    + " has no line detail to cancel");
        }

        CreditMemo creditMemo = buildCreditMemo(salesReceiptDoc, salesReceipt,
                activeSalesReceipt.availableBalance(), salesReceipt.getTotalAmt());
        creditMemoService.createCreditMemo(creditMemo);
        log.info("Created CreditMemo for SalesReceipt productId={} amount={}",
                salesReceiptDoc.getProductId(), activeSalesReceipt.availableBalance());
    }

    /**
     * Splits {@code remaining} across the original Sales Receipt's lines proportionally to each
     * line's share of {@code salesReceiptTotal}, so a partially-refunded receipt produces a
     * CreditMemo whose lines mirror the original sale rather than one lump amount.
     */
    private CreditMemo buildCreditMemo(QuickBooksDocument salesReceiptDoc, SalesReceipt salesReceipt,
                                        BigDecimal remaining, BigDecimal salesReceiptTotal) {
        BigDecimal ratio = remaining.divide(salesReceiptTotal, 10, RoundingMode.HALF_UP);

        List<Line> creditLines = new ArrayList<>();
        int lineNum = 1;
        for (Line originalLine : salesReceipt.getLine()) {
            if (originalLine.getAmount() == null) {
                continue;
            }
            BigDecimal lineAmount = originalLine.getAmount().multiply(ratio).setScale(2, RoundingMode.HALF_UP);
            if (lineAmount.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            creditLines.add(Line.builder()
                    .lineNum(lineNum++)
                    .description(originalLine.getDescription())
                    .amount(lineAmount)
                    .detailType("SalesItemLineDetail")
                    .salesItemLineDetail(SalesItemLineDetail.builder()
                            .qty(1)
                            .unitPrice(lineAmount)
                            .build())
                    .build());
        }

        return CreditMemo.builder()
                .customerRef(salesReceipt.getCustomerRef())
                .docNumber("NCC" + salesReceiptDoc.getProductId())
                .txnDate(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE))
                .customerMemo(MemoRef.builder()
                        .value("Cancellation of SalesReceipt " + salesReceipt.getDocNumber())
                        .build())
                .line(creditLines)
                .build();
    }
}
