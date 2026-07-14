package com.icligo.quickbooks.service;

import com.icligo.quickbooks.clients.quickbooks.model.CreditMemo;
import com.icligo.quickbooks.clients.quickbooks.model.Invoice;
import com.icligo.quickbooks.clients.quickbooks.model.RefundReceipt;
import com.icligo.quickbooks.clients.quickbooks.model.SalesReceipt;
import com.icligo.quickbooks.model.InvoiceDetailDto;
import com.icligo.quickbooks.model.QuickBooksDocument;
import com.icligo.quickbooks.model.document.ClientInvoiceInfo;
import com.icligo.quickbooks.repository.QuickBooksDocumentRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InvoiceDetailServiceTest {

    private final QuickBooksDocumentRepository documentRepository = mock(QuickBooksDocumentRepository.class);
    private final ActiveSalesReceiptFinder activeSalesReceiptFinder = mock(ActiveSalesReceiptFinder.class);
    private final InvoiceDetailService service = new InvoiceDetailService(documentRepository, activeSalesReceiptFinder);

    @Test
    void getServiceInvoiceDetailsReturnsEmptyWhenNoDocumentsExist() {
        when(documentRepository.findByServiceId("srv-1")).thenReturn(List.of());

        assertThat(service.getServiceInvoiceDetails("srv-1")).isEmpty();
    }

    @Test
    void getServiceInvoiceDetailsMapsSalesReceiptFieldsAndMarksItEditableWhenStillOpen() {
        ClientInvoiceInfo info = clientInfo();
        SalesReceipt salesReceipt = SalesReceipt.builder()
                .docNumber("SRC70021").txnDate("2026-07-14").totalAmt(new BigDecimal("49.90")).build();
        QuickBooksDocument document = doc("doc-1", "SRT", "SRT700212026", salesReceipt, info);
        when(documentRepository.findByServiceId("srv-1")).thenReturn(List.of(document));
        when(activeSalesReceiptFinder.findActiveForDocument(document))
                .thenReturn(Optional.of(new ActiveSalesReceipt(document, salesReceipt, new BigDecimal("49.90"))));

        List<InvoiceDetailDto> details = service.getServiceInvoiceDetails("srv-1");

        assertThat(details).hasSize(1);
        InvoiceDetailDto dto = details.get(0);
        assertThat(dto.getId()).isEqualTo("doc-1");
        assertThat(dto.getControlKey()).isEqualTo("SRT700212026");
        assertThat(dto.getType()).isEqualTo("SRT");
        assertThat(dto.getDocNumber()).isEqualTo("SRC70021");
        assertThat(dto.getDate()).isEqualTo("2026-07-14");
        assertThat(dto.getValue()).isEqualByComparingTo("49.90");
        assertThat(dto.isEditable()).isTrue();
        assertThat(dto.getDocumentPDF()).isEqualTo(document.getDocumentPDF());
        assertThat(dto.getClientInvoiceInfo()).isEqualTo(info);
    }

    @Test
    void getServiceInvoiceDetailsMarksSalesReceiptNotEditableWhenNothingIsOpen() {
        SalesReceipt salesReceipt = SalesReceipt.builder().totalAmt(new BigDecimal("49.90")).build();
        QuickBooksDocument document = doc("doc-1", "SRT", "SRT700212026", salesReceipt, clientInfo());
        when(documentRepository.findByServiceId("srv-1")).thenReturn(List.of(document));
        when(activeSalesReceiptFinder.findActiveForDocument(document)).thenReturn(Optional.empty());

        List<InvoiceDetailDto> details = service.getServiceInvoiceDetails("srv-1");

        assertThat(details.get(0).isEditable()).isFalse();
    }

    @Test
    void getServiceInvoiceDetailsNeverMarksInvoiceRefundReceiptOrCreditMemoAsEditable() {
        Invoice invoice = Invoice.builder().docNumber("INV48213").txnDate("2026-07-01").totalAmt(BigDecimal.TEN).build();
        RefundReceipt refundReceipt = RefundReceipt.builder().docNumber("RFD70021").totalAmt(BigDecimal.ONE).build();
        CreditMemo creditMemo = CreditMemo.builder().docNumber("NCC70021").totalAmt(BigDecimal.ONE).build();

        QuickBooksDocument invoiceDoc = doc("doc-inv", "INV", "INV482132026", invoice, clientInfo());
        QuickBooksDocument refundDoc = doc("doc-rrt", "RRT", "RRT700212026_rfd1", refundReceipt, clientInfo());
        QuickBooksDocument creditDoc = doc("doc-cdm", "CDM", "CDM700212026", creditMemo, clientInfo());
        when(documentRepository.findByServiceId("srv-1")).thenReturn(List.of(invoiceDoc, refundDoc, creditDoc));
        // Even if the finder were (incorrectly) called for these, it should never be consulted for non-SRT types.
        when(activeSalesReceiptFinder.findActiveForDocument(any())).thenReturn(Optional.of(
                new ActiveSalesReceipt(invoiceDoc, SalesReceipt.builder().build(), BigDecimal.TEN)));

        List<InvoiceDetailDto> details = service.getServiceInvoiceDetails("srv-1");

        assertThat(details).extracting(InvoiceDetailDto::isEditable).containsOnly(false, false, false);
        assertThat(details).extracting(InvoiceDetailDto::getDocNumber)
                .containsExactly("INV48213", "RFD70021", "NCC70021");
    }

    @Test
    void getInvoiceEditableReturnsTrueForStillOpenSalesReceipt() {
        SalesReceipt salesReceipt = SalesReceipt.builder().totalAmt(new BigDecimal("49.90")).build();
        QuickBooksDocument document = doc("doc-1", "SRT", "SRT700212026", salesReceipt, clientInfo());
        when(documentRepository.findByControlKey("SRT700212026")).thenReturn(Optional.of(document));
        when(activeSalesReceiptFinder.findActiveForDocument(document))
                .thenReturn(Optional.of(new ActiveSalesReceipt(document, salesReceipt, new BigDecimal("49.90"))));

        assertThat(service.getInvoiceEditable("SRT700212026")).isTrue();
    }

    @Test
    void getInvoiceEditableReturnsFalseForNonSalesReceiptType() {
        Invoice invoice = Invoice.builder().totalAmt(BigDecimal.TEN).build();
        QuickBooksDocument document = doc("doc-inv", "INV", "INV482132026", invoice, clientInfo());
        when(documentRepository.findByControlKey("INV482132026")).thenReturn(Optional.of(document));

        assertThat(service.getInvoiceEditable("INV482132026")).isFalse();
    }

    @Test
    void getInvoiceEditableReturnsFalseWhenSalesReceiptHasNothingOpen() {
        SalesReceipt salesReceipt = SalesReceipt.builder().totalAmt(new BigDecimal("49.90")).build();
        QuickBooksDocument document = doc("doc-1", "SRT", "SRT700212026", salesReceipt, clientInfo());
        when(documentRepository.findByControlKey("SRT700212026")).thenReturn(Optional.of(document));
        when(activeSalesReceiptFinder.findActiveForDocument(document)).thenReturn(Optional.empty());

        assertThat(service.getInvoiceEditable("SRT700212026")).isFalse();
    }

    @Test
    void getInvoiceEditableReturnsFalseWhenControlKeyDoesNotExist() {
        when(documentRepository.findByControlKey("unknown")).thenReturn(Optional.empty());

        assertThat(service.getInvoiceEditable("unknown")).isFalse();
    }

    private QuickBooksDocument doc(String id, String type, String controlKey, Object invoice, ClientInvoiceInfo info) {
        QuickBooksDocument document = new QuickBooksDocument();
        document.setId(id);
        document.setType(type);
        document.setControlKey(controlKey);
        document.setInvoice(invoice);
        document.setClientInvoiceInfo(info);
        document.setDocumentPDF("/invoice-quickbooks-service/v1/documents/" + controlKey + "/pdf");
        return document;
    }

    private ClientInvoiceInfo clientInfo() {
        ClientInvoiceInfo info = new ClientInvoiceInfo();
        info.setName("Jane Doe");
        info.setEmail("jane@example.com");
        return info;
    }
}
