package com.icligo.quickbooks.service;

import com.icligo.quickbooks.clients.quickbooks.model.CreditMemo;
import com.icligo.quickbooks.clients.quickbooks.model.Invoice;
import com.icligo.quickbooks.clients.quickbooks.model.RefundReceipt;
import com.icligo.quickbooks.clients.quickbooks.model.SalesReceipt;
import com.icligo.quickbooks.enums.SalesDocumentTypes;
import com.icligo.quickbooks.model.QuickBooksDocument;
import com.icligo.quickbooks.repository.QuickBooksDocumentRepository;
import com.icligo.quickbooks.util.QuickBooksException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.NoSuchElementException;

/**
 * Fetches the PDF for a document we've created, mirroring the invoice-management-system's
 * {@code PDFService} — but simpler: QuickBooks generates the PDF live on every request
 * ({@code GET /invoice|salesreceipt|refundreceipt|creditmemo/{id}/pdf}), unlike Primavera's SOAP
 * call, so there's no need to cache the bytes to an external file-management service the way the
 * reference project does. Every request re-fetches fresh from QuickBooks.
 *
 * <p>Looked up by {@code controlKey} — same public identifier the reference project uses
 * ("chave de controlo"), returned in every document response.
 */
@Service
@RequiredArgsConstructor
public class DocumentPdfService {

    private final QuickBooksDocumentRepository documentRepository;
    private final InvoiceService invoiceService;
    private final SalesReceiptService salesReceiptService;
    private final RefundReceiptService refundReceiptService;
    private final CreditMemoService creditMemoService;

    public byte[] getPdf(String controlKey) throws QuickBooksException {
        QuickBooksDocument document = documentRepository.findByControlKey(controlKey)
                .orElseThrow(() -> new NoSuchElementException("No document found with controlKey=" + controlKey));
        return fetchPdf(document);
    }

    public String getPdfBase64(String controlKey) throws QuickBooksException {
        return Base64.getEncoder().encodeToString(getPdf(controlKey));
    }

    private byte[] fetchPdf(QuickBooksDocument document) throws QuickBooksException {
        SalesDocumentTypes type = SalesDocumentTypes.getByValue(document.getType());
        if (type == null) {
            throw new IllegalStateException("Document " + document.getControlKey() + " has an unknown type: " + document.getType());
        }

        Object entity = document.getInvoice();
        return switch (type) {
            case INVOICE -> {
                if (!(entity instanceof Invoice invoice)) {
                    throw new IllegalStateException("Document " + document.getControlKey() + " has no Invoice recorded yet");
                }
                yield invoiceService.getInvoicePdf(invoice.getId());
            }
            case SALES_RECEIPT -> {
                if (!(entity instanceof SalesReceipt salesReceipt)) {
                    throw new IllegalStateException("Document " + document.getControlKey() + " has no SalesReceipt recorded yet");
                }
                yield salesReceiptService.getSalesReceiptPdf(salesReceipt.getId());
            }
            case REFUND_RECEIPT -> {
                if (!(entity instanceof RefundReceipt refundReceipt)) {
                    throw new IllegalStateException("Document " + document.getControlKey() + " has no RefundReceipt recorded yet");
                }
                yield refundReceiptService.getRefundReceiptPdf(refundReceipt.getId());
            }
            case CREDIT_MEMO -> {
                if (!(entity instanceof CreditMemo creditMemo)) {
                    throw new IllegalStateException("Document " + document.getControlKey() + " has no CreditMemo recorded yet");
                }
                yield creditMemoService.getCreditMemoPdf(creditMemo.getId());
            }
        };
    }
}
