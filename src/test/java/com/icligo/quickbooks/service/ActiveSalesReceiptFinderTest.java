package com.icligo.quickbooks.service;

import com.icligo.quickbooks.clients.quickbooks.model.CreditMemo;
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
    void salesReceiptFullyCancelledByCreditMemoIsExcluded() {
        // A booking (Reserva) Invoice superseded this Sales Receipt via CreditMemo — it must be
        // treated as fully settled, the same as if it had been fully refunded, so a later
        // /refunds call doesn't try to refund it a second time.
        QuickBooksDocument doc = salesReceiptDoc("prod-6", "srv-6", salesReceipt(new BigDecimal("50.00")));
        QuickBooksDocument creditMemo = creditMemoDoc("prod-6", new BigDecimal("50.00"));
        when(documentRepository.findByTypeAndServiceId(SalesDocumentTypes.SALES_RECEIPT.getValue(), "srv-6"))
                .thenReturn(List.of(doc));
        when(documentRepository.findByTypeAndProductId(SalesDocumentTypes.REFUND_RECEIPT.getValue(), "prod-6"))
                .thenReturn(List.of());
        when(documentRepository.findByTypeAndProductId(SalesDocumentTypes.CREDIT_MEMO.getValue(), "prod-6"))
                .thenReturn(List.of(creditMemo));

        assertThat(finder.findActive("srv-6")).isEmpty();
    }

    @Test
    void salesReceiptPartiallyRefundedAndPartiallyCreditedNetsBoth() {
        QuickBooksDocument doc = salesReceiptDoc("prod-7", "srv-7", salesReceipt(new BigDecimal("100.00")));
        QuickBooksDocument refund = refundDoc("prod-7", new BigDecimal("30.00"));
        QuickBooksDocument creditMemo = creditMemoDoc("prod-7", new BigDecimal("20.00"));
        when(documentRepository.findByTypeAndServiceId(SalesDocumentTypes.SALES_RECEIPT.getValue(), "srv-7"))
                .thenReturn(List.of(doc));
        when(documentRepository.findByTypeAndProductId(SalesDocumentTypes.REFUND_RECEIPT.getValue(), "prod-7"))
                .thenReturn(List.of(refund));
        when(documentRepository.findByTypeAndProductId(SalesDocumentTypes.CREDIT_MEMO.getValue(), "prod-7"))
                .thenReturn(List.of(creditMemo));

        List<ActiveSalesReceipt> active = finder.findActive("srv-7");

        assertThat(active).hasSize(1);
        assertThat(active.get(0).availableBalance()).isEqualByComparingTo("50.00");
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

    private QuickBooksDocument creditMemoDoc(String productId, BigDecimal totalAmt) {
        QuickBooksDocument document = new QuickBooksDocument();
        document.setType(SalesDocumentTypes.CREDIT_MEMO.getValue());
        document.setProductId(productId);
        document.setInvoice(CreditMemo.builder().totalAmt(totalAmt).build());
        return document;
    }

    private SalesReceipt salesReceipt(BigDecimal totalAmt) {
        return SalesReceipt.builder().totalAmt(totalAmt).build();
    }
}
