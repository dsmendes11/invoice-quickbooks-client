package com.icligo.quickbooks.service;

import com.icligo.quickbooks.clients.quickbooks.model.CreditMemo;
import com.icligo.quickbooks.clients.quickbooks.model.Line;
import com.icligo.quickbooks.clients.quickbooks.model.MemoRef;
import com.icligo.quickbooks.clients.quickbooks.model.ReferenceType;
import com.icligo.quickbooks.clients.quickbooks.model.SalesItemLineDetail;
import com.icligo.quickbooks.clients.quickbooks.model.SalesReceipt;
import com.icligo.quickbooks.enums.SalesDocumentTypes;
import com.icligo.quickbooks.model.EditSplitCrediteNoteResponseDto;
import com.icligo.quickbooks.model.QuickBooksDocument;
import com.icligo.quickbooks.model.document.ItemDto;
import com.icligo.quickbooks.repository.QuickBooksDocumentRepository;
import com.icligo.quickbooks.service.authentication.QuickBooksAlertService;
import com.icligo.quickbooks.util.ItemLocatorUtils;
import com.icligo.quickbooks.util.QuickBooksException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * When a "Reserva" (booking) Invoice is created for a {@code serviceId}, any prepaid Sales
 * Receipts for that same {@code serviceId} are now superseded and must be cancelled in
 * QuickBooks via a CreditMemo. Uses {@link ActiveSalesReceiptFinder} to get each Sales Receipt's
 * remaining (not-yet-refunded) balance — that full remaining amount is credited, split across
 * the original Sales Receipt's line items in proportion to their share of the total, so the
 * CreditMemo's line detail mirrors the original sale rather than collapsing it into one lump sum.
 *
 * <p>{@link #cancelSalesReceiptsForServiceId} (triggered automatically by a booking Invoice) is
 * deliberately best-effort: a failure cancelling one Sales Receipt (e.g. a line's Item no longer
 * exists in QuickBooks) is logged and alerted by email, but never propagated — it must not block
 * the Invoice creation that triggered this, nor prevent cancelling any other matching Sales
 * Receipt. {@link #cancelSalesReceiptByControlKey} (a direct, caller-requested action via
 * {@code GET /documents/invoices/creditnote/{controlKey}}) is <b>not</b> best-effort — a failure
 * propagates to the caller, since they explicitly asked for this specific credit right now.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SalesReceiptCancellationService {

    private final ActiveSalesReceiptFinder activeSalesReceiptFinder;
    private final CreditMemoService creditMemoService;
    private final QuickBooksAlertService alertService;
    private final QuickBooksDocumentRepository documentRepository;

    @Value("${api.base-path}")
    private String basePath;

    /**
     * @return the CreditMemo documents actually created (or, on idempotent replay, already
     *         existing) — one per Sales Receipt successfully cancelled. A Sales Receipt whose
     *         cancellation failed is alerted (see above) but omitted here, matching {@link
     *         com.icligo.quickbooks.service.RefundReceiptAllocationService}'s same best-effort
     *         partial-results convention.
     */
    public List<QuickBooksDocument> cancelSalesReceiptsForServiceId(String serviceId) {
        List<ActiveSalesReceipt> active = activeSalesReceiptFinder.findActive(serviceId);

        if (active.isEmpty()) {
            log.info("No open Sales Receipts found for serviceId={} — nothing to cancel", serviceId);
            return List.of();
        }

        List<QuickBooksDocument> cancelled = new ArrayList<>();
        for (ActiveSalesReceipt activeSalesReceipt : active) {
            try {
                cancelled.add(cancelOne(activeSalesReceipt));
            } catch (Exception e) {
                String productId = activeSalesReceipt.document().getProductId();
                log.error("Failed to cancel SalesReceipt productId={} for serviceId={}: {}",
                        productId, serviceId, e.getMessage(), e);
                alertService.sendCreditMemoCancellationFailedAlert(serviceId, productId, e.getMessage());
            }
        }
        return cancelled;
    }

    /**
     * Mirrors the invoice-management-system's {@code GET /documents/invoices/creditnote/{controlKey}}
     * — credits, via CreditMemo, whatever's still open on the Sales Receipt identified by
     * {@code controlKey} (this project's advance-invoice equivalent is a Sales Receipt, not an
     * Invoice, so unlike the reference this only ever targets Sales Receipts). Recomputes the
     * remaining balance fresh every call (same {@link ActiveSalesReceiptFinder} accounting as
     * everything else), so a Sales Receipt already fully credited/refunded correctly reports
     * "nothing to do" rather than crediting it again.
     *
     * @return the credited value/productId plus the created (or, on idempotent replay,
     *         already-existing) CreditMemo's raw QuickBooks entity, or empty if this Sales
     *         Receipt has nothing left open to credit.
     * @throws NoSuchElementException if {@code controlKey} doesn't identify a Sales Receipt
     *         document at all.
     * @throws QuickBooksException if QuickBooks rejects or fails the CreditMemo creation.
     */
    public Optional<EditSplitCrediteNoteResponseDto> cancelSalesReceiptByControlKey(String controlKey) throws QuickBooksException {
        QuickBooksDocument salesReceiptDoc = documentRepository.findByControlKey(controlKey)
                .filter(doc -> SalesDocumentTypes.SALES_RECEIPT.getValue().equals(doc.getType()))
                .orElseThrow(() -> new NoSuchElementException("No SalesReceipt document found with controlKey=" + controlKey));

        Optional<ActiveSalesReceipt> active = activeSalesReceiptFinder.findActiveForDocument(salesReceiptDoc);
        if (active.isEmpty()) {
            log.info("SalesReceipt controlKey={} has nothing left open to credit", controlKey);
            return Optional.empty();
        }

        QuickBooksDocument creditMemoDoc = cancelOne(active.get());
        return Optional.of(EditSplitCrediteNoteResponseDto.builder()
                .crediteNoteValue(active.get().availableBalance())
                .productId(salesReceiptDoc.getProductId())
                .documents(List.of(creditMemoDoc.getInvoice()))
                .build());
    }

    private QuickBooksDocument cancelOne(ActiveSalesReceipt activeSalesReceipt) throws QuickBooksException {
        QuickBooksDocument salesReceiptDoc = activeSalesReceipt.document();
        SalesReceipt salesReceipt = activeSalesReceipt.salesReceipt();
        String productId = salesReceiptDoc.getProductId();

        String controlKey = buildControlKey(productId);
        Optional<QuickBooksDocument> existing = documentRepository.findByControlKey(controlKey);
        if (existing.isPresent()) {
            log.info("Idempotent replay for controlKey={} – SalesReceipt productId={} already cancelled",
                    controlKey, productId);
            return existing.get();
        }

        if (salesReceipt.getLine() == null || salesReceipt.getLine().isEmpty()) {
            throw new QuickBooksException("SalesReceipt for productId=" + productId
                    + " has no line detail to cancel");
        }

        List<Line> creditLines = buildCreditLines(salesReceiptDoc, salesReceipt, activeSalesReceipt.availableBalance());
        CreditMemo creditMemo = buildCreditMemo(salesReceiptDoc, salesReceipt, creditLines, controlKey);
        CreditMemo created = creditMemoService.createCreditMemo(creditMemo);
        log.info("Created CreditMemo for SalesReceipt productId={} amount={}",
                productId, activeSalesReceipt.availableBalance());

        return saveDocument(salesReceiptDoc, controlKey, created, creditLines);
    }

    private String buildControlKey(String productId) {
        String serie = String.valueOf(Year.now().getValue());
        return SalesDocumentTypes.CREDIT_MEMO.getValue() + productId + serie;
    }

    /**
     * Splits {@code remaining} across the original Sales Receipt's lines proportionally to each
     * line's share of the Sales Receipt's total, so a partially-refunded receipt produces a
     * CreditMemo whose lines mirror the original sale rather than one lump amount.
     */
    private List<Line> buildCreditLines(QuickBooksDocument salesReceiptDoc, SalesReceipt salesReceipt, BigDecimal remaining) {
        BigDecimal ratio = remaining.divide(salesReceipt.getTotalAmt(), 10, RoundingMode.HALF_UP);
        String documentDescription = salesReceiptDoc.getDescription();
        boolean hasDocumentDescription = documentDescription != null && !documentDescription.isBlank();

        List<Line> creditLines = new ArrayList<>();
        int lineNum = 1;
        for (Line originalLine : salesReceipt.getLine()) {
            // QuickBooks appends its own summary lines (e.g. DetailType=SubTotalLineDetail,
            // Amount = the receipt's total) to the Line array it returns — those aren't sold
            // items and must be skipped, or they'd be double-counted as an extra credited line.
            if (originalLine.getAmount() == null || !"SalesItemLineDetail".equals(originalLine.getDetailType())) {
                continue;
            }
            BigDecimal lineAmount = originalLine.getAmount().multiply(ratio).setScale(2, RoundingMode.HALF_UP);
            if (lineAmount.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            ReferenceType itemRef = originalLine.getSalesItemLineDetail() != null
                    ? originalLine.getSalesItemLineDetail().getItemRef() : null;
            creditLines.add(Line.builder()
                    .lineNum(lineNum++)
                    .description(hasDocumentDescription ? documentDescription : originalLine.getDescription())
                    .amount(lineAmount)
                    .detailType("SalesItemLineDetail")
                    .salesItemLineDetail(SalesItemLineDetail.builder()
                            .itemRef(itemRef)
                            .qty(1)
                            .unitPrice(lineAmount)
                            .build())
                    .build());
        }
        return creditLines;
    }

    private CreditMemo buildCreditMemo(QuickBooksDocument salesReceiptDoc, SalesReceipt salesReceipt, List<Line> creditLines, String controlKey) {
        return CreditMemo.builder()
                .customerRef(salesReceipt.getCustomerRef())
                .docNumber(controlKey)
                .txnDate(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE))
                .customerMemo(MemoRef.builder()
                        .value("Cancellation of SalesReceipt " + salesReceipt.getDocNumber())
                        .build())
                .privateNote(ItemLocatorUtils.joinLocators(salesReceiptDoc.getItems()))
                .line(creditLines)
                .build();
    }

    /**
     * Persists the CreditMemo as a normal {@code QuickBooksDocument} (type=CDM), the same as
     * every other document type this service creates — same {@code controlKey} uniqueness
     * guarantee, same {@code documentPDF} link. Uses each credit line's {@code ItemRef} name
     * (falling back to its description) to rebuild an {@code items} list, since {@code Line}
     * doesn't carry the original request-level {@code ItemDto} shape.
     */
    private QuickBooksDocument saveDocument(QuickBooksDocument salesReceiptDoc, String controlKey, CreditMemo creditMemo, List<Line> creditLines) {
        QuickBooksDocument document = new QuickBooksDocument();
        document.setType(SalesDocumentTypes.CREDIT_MEMO.getValue());
        document.setServiceId(salesReceiptDoc.getServiceId());
        document.setProductId(salesReceiptDoc.getProductId());
        document.setDescription(salesReceiptDoc.getDescription());
        document.setClientInvoiceInfo(salesReceiptDoc.getClientInvoiceInfo());
        document.setSerie(String.valueOf(Year.now().getValue()));
        document.setControlKey(controlKey);
        document.setInvoice(creditMemo);
        document.setItems(creditLines.stream().map(this::toItemDto).toList());
        document.setDocumentPDF(basePath + "/documents/" + controlKey + "/pdf");
        return documentRepository.save(document);
    }

    private ItemDto toItemDto(Line line) {
        ItemDto item = new ItemDto();
        ReferenceType itemRef = line.getSalesItemLineDetail() != null ? line.getSalesItemLineDetail().getItemRef() : null;
        item.setItem(itemRef != null && itemRef.getName() != null ? itemRef.getName() : line.getDescription());
        item.setValue(line.getAmount());
        return item;
    }
}
