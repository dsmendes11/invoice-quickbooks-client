package com.icligo.quickbooks.service;

import com.icligo.quickbooks.clients.quickbooks.model.RefundReceipt;
import com.icligo.quickbooks.clients.quickbooks.model.SalesReceipt;
import com.icligo.quickbooks.enums.SalesDocumentTypes;
import com.icligo.quickbooks.model.QuickBooksDocument;
import com.icligo.quickbooks.repository.QuickBooksDocumentRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActiveSalesReceiptFinderTest {

    private final QuickBooksDocumentRepository documentRepository = mock(QuickBooksDocumentRepository.class);
    private final ActiveSalesReceiptFinder finder = new ActiveSalesReceiptFinder(documentRepository);

    @Test
    void noSalesReceiptsForServiceIdReturnsEmpty() {
        when(documentRepository.findByTypeAndServiceId(SalesDocumentTypes.SALES_RECEIPT.getValue(), "svc-1"))
                .thenReturn(List.of());

        assertThat(finder.findActive("svc-1")).isEmpty();
    }

    @Test
    void salesReceiptWithNoRefundsHasFullBalanceAvailable() {
        QuickBooksDocument doc = salesReceiptDoc("prod-1", "srv-1", salesReceipt(new BigDecimal("100.00")));
        when(documentRepository.findByTypeAndServiceId(SalesDocumentTypes.SALES_RECEIPT.getValue(), "srv-1"))
                .thenReturn(List.of(doc));
        when(documentRepository.findByTypeAndProductId(SalesDocumentTypes.REFUND_RECEIPT.getValue(), "prod-1"))
                .thenReturn(List.of());

        List<ActiveSalesReceipt> active = finder.findActive("srv-1");

        assertThat(active).hasSize(1);
        assertThat(active.get(0).availableBalance()).isEqualByComparingTo("100.00");
    }

    @Test
    void salesReceiptWithPartialRefundsHasReducedBalanceAvailable() {
        QuickBooksDocument doc = salesReceiptDoc("prod-2", "srv-2", salesReceipt(new BigDecimal("100.00")));
        QuickBooksDocument refund = refundDoc("prod-2", new BigDecimal("40.00"));
        when(documentRepository.findByTypeAndServiceId(SalesDocumentTypes.SALES_RECEIPT.getValue(), "srv-2"))
                .thenReturn(List.of(doc));
        when(documentRepository.findByTypeAndProductId(SalesDocumentTypes.REFUND_RECEIPT.getValue(), "prod-2"))
                .thenReturn(List.of(refund));

        List<ActiveSalesReceipt> active = finder.findActive("srv-2");

        assertThat(active).hasSize(1);
        assertThat(active.get(0).availableBalance()).isEqualByComparingTo("60.00");
    }

    @Test
    void fullyRefundedSalesReceiptIsExcluded() {
        QuickBooksDocument doc = salesReceiptDoc("prod-3", "srv-3", salesReceipt(new BigDecimal("50.00")));
        QuickBooksDocument refund = refundDoc("prod-3", new BigDecimal("50.00"));
        when(documentRepository.findByTypeAndServiceId(SalesDocumentTypes.SALES_RECEIPT.getValue(), "srv-3"))
                .thenReturn(List.of(doc));
        when(documentRepository.findByTypeAndProductId(SalesDocumentTypes.REFUND_RECEIPT.getValue(), "prod-3"))
                .thenReturn(List.of(refund));

        assertThat(finder.findActive("srv-3")).isEmpty();
    }

    @Test
    void overRefundedSalesReceiptIsExcluded() {
        QuickBooksDocument doc = salesReceiptDoc("prod-4", "srv-4", salesReceipt(new BigDecimal("50.00")));
        QuickBooksDocument refund = refundDoc("prod-4", new BigDecimal("70.00"));
        when(documentRepository.findByTypeAndServiceId(SalesDocumentTypes.SALES_RECEIPT.getValue(), "srv-4"))
                .thenReturn(List.of(doc));
        when(documentRepository.findByTypeAndProductId(SalesDocumentTypes.REFUND_RECEIPT.getValue(), "prod-4"))
                .thenReturn(List.of(refund));

        assertThat(finder.findActive("srv-4")).isEmpty();
    }

    @Test
    void documentWithoutAStoredSalesReceiptPayloadIsSkipped() {
        QuickBooksDocument doc = salesReceiptDoc("prod-5", "srv-5", null);
        when(documentRepository.findByTypeAndServiceId(SalesDocumentTypes.SALES_RECEIPT.getValue(), "srv-5"))
                .thenReturn(List.of(doc));

        assertThat(finder.findActive("srv-5")).isEmpty();
    }

    private QuickBooksDocument salesReceiptDoc(String productId, String serviceId, SalesReceipt salesReceipt) {
        QuickBooksDocument document = new QuickBooksDocument();
        document.setType(SalesDocumentTypes.SALES_RECEIPT.getValue());
        document.setProductId(productId);
        document.setServiceId(serviceId);
        document.setInvoice(salesReceipt);
        return document;
    }

    private QuickBooksDocument refundDoc(String productId, BigDecimal totalAmt) {
        QuickBooksDocument document = new QuickBooksDocument();
        document.setType(SalesDocumentTypes.REFUND_RECEIPT.getValue());
        document.setProductId(productId);
        document.setInvoice(RefundReceipt.builder().totalAmt(totalAmt).build());
        return document;
    }

    private SalesReceipt salesReceipt(BigDecimal totalAmt) {
        return SalesReceipt.builder().totalAmt(totalAmt).build();
    }
}
