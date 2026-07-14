package com.icligo.quickbooks.service;

import com.icligo.quickbooks.clients.quickbooks.model.CreditMemo;
import com.icligo.quickbooks.clients.quickbooks.model.Line;
import com.icligo.quickbooks.clients.quickbooks.model.MemoRef;
import com.icligo.quickbooks.clients.quickbooks.model.ReferenceType;
import com.icligo.quickbooks.clients.quickbooks.model.SalesItemLineDetail;
import com.icligo.quickbooks.clients.quickbooks.model.SalesReceipt;
import com.icligo.quickbooks.enums.SalesDocumentTypes;
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
    private final QuickBooksDocumentRepository documentRepository;

    @Value("${api.base-path}")
    private String basePath;

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
        String productId = salesReceiptDoc.getProductId();

        String controlKey = buildControlKey(productId);
        if (documentRepository.findByControlKey(controlKey).isPresent()) {
            log.info("Idempotent replay for controlKey={} – SalesReceipt productId={} already cancelled",
                    controlKey, productId);
            return;
        }

        if (salesReceipt.getLine() == null || salesReceipt.getLine().isEmpty()) {
            throw new QuickBooksException("SalesReceipt for productId=" + productId
                    + " has no line detail to cancel");
        }

        List<Line> creditLines = buildCreditLines(salesReceiptDoc, salesReceipt, activeSalesReceipt.availableBalance());
        CreditMemo creditMemo = buildCreditMemo(salesReceiptDoc, salesReceipt, creditLines);
        CreditMemo created = creditMemoService.createCreditMemo(creditMemo);
        log.info("Created CreditMemo for SalesReceipt productId={} amount={}",
                productId, activeSalesReceipt.availableBalance());

        saveDocument(salesReceiptDoc, controlKey, created, creditLines);
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

    private CreditMemo buildCreditMemo(QuickBooksDocument salesReceiptDoc, SalesReceipt salesReceipt, List<Line> creditLines) {
        return CreditMemo.builder()
                .customerRef(salesReceipt.getCustomerRef())
                .docNumber("NCC" + salesReceiptDoc.getProductId())
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
    private void saveDocument(QuickBooksDocument salesReceiptDoc, String controlKey, CreditMemo creditMemo, List<Line> creditLines) {
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
        documentRepository.save(document);
    }

    private ItemDto toItemDto(Line line) {
        ItemDto item = new ItemDto();
        ReferenceType itemRef = line.getSalesItemLineDetail() != null ? line.getSalesItemLineDetail().getItemRef() : null;
        item.setItem(itemRef != null && itemRef.getName() != null ? itemRef.getName() : line.getDescription());
        item.setValue(line.getAmount());
        return item;
    }
}
