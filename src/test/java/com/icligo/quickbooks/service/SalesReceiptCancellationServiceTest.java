package com.icligo.quickbooks.service;

import com.icligo.quickbooks.clients.quickbooks.model.CreditMemo;
import com.icligo.quickbooks.clients.quickbooks.model.Line;
import com.icligo.quickbooks.clients.quickbooks.model.ReferenceType;
import com.icligo.quickbooks.clients.quickbooks.model.SalesReceipt;
import com.icligo.quickbooks.enums.SalesDocumentTypes;
import com.icligo.quickbooks.model.QuickBooksDocument;
import com.icligo.quickbooks.service.authentication.QuickBooksAlertService;
import com.icligo.quickbooks.util.QuickBooksException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SalesReceiptCancellationServiceTest {

    private final ActiveSalesReceiptFinder activeSalesReceiptFinder = mock(ActiveSalesReceiptFinder.class);
    private final CreditMemoService creditMemoService = mock(CreditMemoService.class);
    private final QuickBooksAlertService alertService = mock(QuickBooksAlertService.class);
    private final SalesReceiptCancellationService service =
            new SalesReceiptCancellationService(activeSalesReceiptFinder, creditMemoService, alertService);

    @Test
    void noActiveSalesReceiptsCreatesNoCreditMemo() {
        when(activeSalesReceiptFinder.findActive("svc-1")).thenReturn(List.of());

        service.cancelSalesReceiptsForServiceId("svc-1");

        verifyNoInteractions(creditMemoService, alertService);
    }

    @Test
    void fullyUnrefundedSalesReceiptIsCreditedInFullWithLinesMirroringTheOriginal() throws Exception {
        SalesReceipt salesReceipt = salesReceipt("SRCprod-1", new BigDecimal("100.00"),
                line("Airport transfer", new BigDecimal("70.00")),
                line("Child seat", new BigDecimal("30.00")));
        ActiveSalesReceipt active = new ActiveSalesReceipt(salesReceiptDoc("prod-1", "srv-1"), salesReceipt, new BigDecimal("100.00"));

        when(activeSalesReceiptFinder.findActive("srv-1")).thenReturn(List.of(active));
        when(creditMemoService.createCreditMemo(any())).thenReturn(new CreditMemo());

        service.cancelSalesReceiptsForServiceId("srv-1");

        var captor = forClass(CreditMemo.class);
        verify(creditMemoService).createCreditMemo(captor.capture());
        CreditMemo creditMemo = captor.getValue();

        assertThat(creditMemo.getDocNumber()).isEqualTo("NCCprod-1");
        assertThat(creditMemo.getLine()).hasSize(2);
        assertThat(creditMemo.getLine().get(0).getAmount()).isEqualByComparingTo("70.00");
        assertThat(creditMemo.getLine().get(1).getAmount()).isEqualByComparingTo("30.00");
        verifyNoInteractions(alertService);
    }

    @Test
    void partiallyRefundedSalesReceiptIsCreditedOnlyForTheRemainderProportionally() throws Exception {
        SalesReceipt salesReceipt = salesReceipt("SRCprod-2", new BigDecimal("100.00"),
                line("Airport transfer", new BigDecimal("70.00")),
                line("Child seat", new BigDecimal("30.00")));
        // 40 already refunded elsewhere → availableBalance = 60
        ActiveSalesReceipt active = new ActiveSalesReceipt(salesReceiptDoc("prod-2", "srv-2"), salesReceipt, new BigDecimal("60.00"));

        when(activeSalesReceiptFinder.findActive("srv-2")).thenReturn(List.of(active));
        when(creditMemoService.createCreditMemo(any())).thenReturn(new CreditMemo());

        service.cancelSalesReceiptsForServiceId("srv-2");

        // ratio = 60/100 = 0.6 → 70*0.6=42.00, 30*0.6=18.00
        var captor = forClass(CreditMemo.class);
        verify(creditMemoService).createCreditMemo(captor.capture());
        CreditMemo creditMemo = captor.getValue();

        assertThat(creditMemo.getLine().get(0).getAmount()).isEqualByComparingTo("42.00");
        assertThat(creditMemo.getLine().get(1).getAmount()).isEqualByComparingTo("18.00");
    }

    @Test
    void creditMemoFailureIsAlertedAndDoesNotPropagate() throws Exception {
        SalesReceipt salesReceipt = salesReceipt("SRCprod-4", new BigDecimal("25.00"), line("Day tour", new BigDecimal("25.00")));
        ActiveSalesReceipt active = new ActiveSalesReceipt(salesReceiptDoc("prod-4", "srv-4"), salesReceipt, new BigDecimal("25.00"));

        when(activeSalesReceiptFinder.findActive("srv-4")).thenReturn(List.of(active));
        when(creditMemoService.createCreditMemo(any()))
                .thenThrow(new QuickBooksException("QuickBooks Item 'Day tour' does not exist in this company."));

        service.cancelSalesReceiptsForServiceId("srv-4");

        verify(alertService).sendCreditMemoCancellationFailedAlert(eq("srv-4"), eq("prod-4"), anyString());
    }

    private QuickBooksDocument salesReceiptDoc(String productId, String serviceId) {
        QuickBooksDocument document = new QuickBooksDocument();
        document.setType(SalesDocumentTypes.SALES_RECEIPT.getValue());
        document.setProductId(productId);
        document.setServiceId(serviceId);
        return document;
    }

    private SalesReceipt salesReceipt(String docNumber, BigDecimal totalAmt, Line... lines) {
        return SalesReceipt.builder()
                .docNumber(docNumber)
                .totalAmt(totalAmt)
                .customerRef(ReferenceType.builder().value("1").name("Jane Doe").build())
                .line(List.of(lines))
                .build();
    }

    private Line line(String description, BigDecimal amount) {
        return Line.builder().description(description).amount(amount).build();
    }
}
