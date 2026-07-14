package com.icligo.quickbooks.service;

import com.icligo.quickbooks.clients.quickbooks.model.CreditMemo;
import com.icligo.quickbooks.clients.quickbooks.model.Invoice;
import com.icligo.quickbooks.clients.quickbooks.model.RefundReceipt;
import com.icligo.quickbooks.clients.quickbooks.model.SalesReceipt;
import com.icligo.quickbooks.enums.SalesDocumentTypes;
import com.icligo.quickbooks.model.InvoiceDetailDto;
import com.icligo.quickbooks.model.QuickBooksDocument;
import com.icligo.quickbooks.repository.QuickBooksDocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Mirrors the invoice-management-system's {@code getServiceInvoiceDetails}/{@code
 * getInvoiceEditable} — but there, "editable" meant an advance-invoice/quote type (FAA/ESFAA/
 * PRE*) with a still-open balance; here, the only "advance/prepaid" document type is a Sales
 * Receipt (see {@link SalesReceiptCancellationService}), so editability reuses the exact same
 * {@link ActiveSalesReceiptFinder} accounting already used by {@code GET /documents/clients/
 * {serviceId}} and {@code GET /documents/invoices/creditnote/{controlKey}}: a document is
 * editable only if it's a SalesReceipt with something still open. Every Invoice/RefundReceipt/
 * CreditMemo is always not editable.
 *
 * <p>Like the reference, both methods are "silent" on a missing lookup: an unknown {@code
 * serviceId} returns an empty list, and an unknown {@code controlKey} returns {@code false} —
 * neither throws, matching the reference's {@code .orElse(null)}/empty-list behavior exactly.
 */
@Service
@RequiredArgsConstructor
public class InvoiceDetailService {

    private final QuickBooksDocumentRepository documentRepository;
    private final ActiveSalesReceiptFinder activeSalesReceiptFinder;

    public List<InvoiceDetailDto> getServiceInvoiceDetails(String serviceId) {
        return documentRepository.findByServiceId(serviceId).stream()
                .map(this::toDetailDto)
                .toList();
    }

    public boolean getInvoiceEditable(String controlKey) {
        return documentRepository.findByControlKey(controlKey)
                .map(this::isEditable)
                .orElse(false);
    }

    private InvoiceDetailDto toDetailDto(QuickBooksDocument document) {
        return InvoiceDetailDto.builder()
                .id(document.getId())
                .controlKey(document.getControlKey())
                .type(document.getType())
                .docNumber(docNumber(document))
                .date(txnDate(document))
                .value(totalAmt(document))
                .editable(isEditable(document))
                .documentPDF(document.getDocumentPDF())
                .clientInvoiceInfo(document.getClientInvoiceInfo())
                .build();
    }

    private boolean isEditable(QuickBooksDocument document) {
        return SalesDocumentTypes.SALES_RECEIPT.getValue().equals(document.getType())
                && activeSalesReceiptFinder.findActiveForDocument(document).isPresent();
    }

    private String docNumber(QuickBooksDocument document) {
        Object entity = document.getInvoice();
        if (entity instanceof Invoice invoice) {
            return invoice.getDocNumber();
        }
        if (entity instanceof SalesReceipt salesReceipt) {
            return salesReceipt.getDocNumber();
        }
        if (entity instanceof RefundReceipt refundReceipt) {
            return refundReceipt.getDocNumber();
        }
        if (entity instanceof CreditMemo creditMemo) {
            return creditMemo.getDocNumber();
        }
        return null;
    }

    private String txnDate(QuickBooksDocument document) {
        Object entity = document.getInvoice();
        if (entity instanceof Invoice invoice) {
            return invoice.getTxnDate();
        }
        if (entity instanceof SalesReceipt salesReceipt) {
            return salesReceipt.getTxnDate();
        }
        if (entity instanceof RefundReceipt refundReceipt) {
            return refundReceipt.getTxnDate();
        }
        if (entity instanceof CreditMemo creditMemo) {
            return creditMemo.getTxnDate();
        }
        return null;
    }

    private BigDecimal totalAmt(QuickBooksDocument document) {
        Object entity = document.getInvoice();
        if (entity instanceof Invoice invoice) {
            return invoice.getTotalAmt();
        }
        if (entity instanceof SalesReceipt salesReceipt) {
            return salesReceipt.getTotalAmt();
        }
        if (entity instanceof RefundReceipt refundReceipt) {
            return refundReceipt.getTotalAmt();
        }
        if (entity instanceof CreditMemo creditMemo) {
            return creditMemo.getTotalAmt();
        }
        return null;
    }
}
