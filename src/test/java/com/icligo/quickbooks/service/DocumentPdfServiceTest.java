package com.icligo.quickbooks.service;

import com.icligo.quickbooks.clients.quickbooks.model.CreditMemo;
import com.icligo.quickbooks.clients.quickbooks.model.Invoice;
import com.icligo.quickbooks.clients.quickbooks.model.RefundReceipt;
import com.icligo.quickbooks.clients.quickbooks.model.SalesReceipt;
import com.icligo.quickbooks.model.QuickBooksDocument;
import com.icligo.quickbooks.repository.QuickBooksDocumentRepository;
import com.icligo.quickbooks.util.QuickBooksException;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DocumentPdfServiceTest {

    private final QuickBooksDocumentRepository documentRepository = mock(QuickBooksDocumentRepository.class);
    private final InvoiceService invoiceService = mock(InvoiceService.class);
    private final SalesReceiptService salesReceiptService = mock(SalesReceiptService.class);
    private final RefundReceiptService refundReceiptService = mock(RefundReceiptService.class);
    private final CreditMemoService creditMemoService = mock(CreditMemoService.class);
    private final DocumentPdfService service = new DocumentPdfService(
            documentRepository, invoiceService, salesReceiptService, refundReceiptService, creditMemoService);

    @Test
    void invoiceDocumentFetchesPdfFromQuickBooksByItsInvoiceId() throws QuickBooksException {
        QuickBooksDocument document = document("INV", Invoice.builder().id("qb-inv-1").build());
        when(documentRepository.findByControlKey("INV70021")).thenReturn(Optional.of(document));
        when(invoiceService.getInvoicePdf("qb-inv-1")).thenReturn(new byte[]{1, 2, 3});

        byte[] pdf = service.getPdf("INV70021");

        assertThat(pdf).containsExactly(1, 2, 3);
        verifyNoInteractions(salesReceiptService, refundReceiptService, creditMemoService);
    }

    @Test
    void salesReceiptDocumentFetchesPdfFromQuickBooksByItsSalesReceiptId() throws QuickBooksException {
        QuickBooksDocument document = document("SRT", SalesReceipt.builder().id("qb-srt-1").build());
        when(documentRepository.findByControlKey("SRT70021")).thenReturn(Optional.of(document));
        when(salesReceiptService.getSalesReceiptPdf("qb-srt-1")).thenReturn(new byte[]{4, 5});

        assertThat(service.getPdf("SRT70021")).containsExactly(4, 5);
        verifyNoInteractions(invoiceService, refundReceiptService, creditMemoService);
    }

    @Test
    void refundReceiptDocumentFetchesPdfFromQuickBooksByItsRefundReceiptId() throws QuickBooksException {
        QuickBooksDocument document = document("RRT", RefundReceipt.builder().id("qb-rrt-1").build());
        when(documentRepository.findByControlKey("RRT70021")).thenReturn(Optional.of(document));
        when(refundReceiptService.getRefundReceiptPdf("qb-rrt-1")).thenReturn(new byte[]{6});

        assertThat(service.getPdf("RRT70021")).containsExactly(6);
        verifyNoInteractions(invoiceService, salesReceiptService, creditMemoService);
    }

    @Test
    void creditMemoDocumentFetchesPdfFromQuickBooksByItsCreditMemoId() throws QuickBooksException {
        QuickBooksDocument document = document("CDM", CreditMemo.builder().id("qb-cdm-1").build());
        when(documentRepository.findByControlKey("CDM70021")).thenReturn(Optional.of(document));
        when(creditMemoService.getCreditMemoPdf("qb-cdm-1")).thenReturn(new byte[]{7, 8});

        assertThat(service.getPdf("CDM70021")).containsExactly(7, 8);
        verifyNoInteractions(invoiceService, salesReceiptService, refundReceiptService);
    }

    @Test
    void base64VariantEncodesTheFetchedPdfBytes() throws QuickBooksException {
        QuickBooksDocument document = document("INV", Invoice.builder().id("qb-inv-2").build());
        when(documentRepository.findByControlKey("INV70022")).thenReturn(Optional.of(document));
        when(invoiceService.getInvoicePdf("qb-inv-2")).thenReturn(new byte[]{10, 20, 30});

        String base64 = service.getPdfBase64("INV70022");

        assertThat(base64).isEqualTo(Base64.getEncoder().encodeToString(new byte[]{10, 20, 30}));
    }

    @Test
    void unknownControlKeyThrowsNoSuchElementException() {
        when(documentRepository.findByControlKey("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPdf("missing"))
                .isInstanceOf(NoSuchElementException.class);
        verifyNoInteractions(invoiceService, salesReceiptService, refundReceiptService, creditMemoService);
    }

    @Test
    void documentWithoutItsQuickBooksEntityRecordedThrowsIllegalState() {
        QuickBooksDocument document = document("INV", null);
        when(documentRepository.findByControlKey("INV70023")).thenReturn(Optional.of(document));

        assertThatThrownBy(() -> service.getPdf("INV70023"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void documentWithNoQuickBooksIdRecordedThrowsNoSuchElementInsteadOfCallingQuickBooks() {
        // Simulates a document saved before the response-envelope unwrapping fix, where every
        // field of the stored QuickBooks entity — including its id — was left null.
        QuickBooksDocument document = document("INV", Invoice.builder().build());
        when(documentRepository.findByControlKey("INV70024")).thenReturn(Optional.of(document));

        assertThatThrownBy(() -> service.getPdf("INV70024"))
                .isInstanceOf(NoSuchElementException.class);
        verifyNoInteractions(invoiceService, salesReceiptService, refundReceiptService, creditMemoService);
    }

    @Test
    void emptyPdfFromQuickBooksThrowsNoSuchElementInsteadOfReturning200WithNoBody() throws QuickBooksException {
        QuickBooksDocument document = document("INV", Invoice.builder().id("qb-inv-3").build());
        when(documentRepository.findByControlKey("INV70025")).thenReturn(Optional.of(document));
        when(invoiceService.getInvoicePdf("qb-inv-3")).thenReturn(new byte[0]);

        assertThatThrownBy(() -> service.getPdf("INV70025"))
                .isInstanceOf(NoSuchElementException.class);
    }

    private QuickBooksDocument document(String type, Object invoice) {
        QuickBooksDocument document = new QuickBooksDocument();
        document.setControlKey(type + "70021");
        document.setType(type);
        document.setInvoice(invoice);
        return document;
    }
}
