package com.icligo.quickbooks.service;

import com.icligo.quickbooks.clients.quickbooks.model.SalesReceipt;
import com.icligo.quickbooks.enums.SalesDocumentTypes;
import com.icligo.quickbooks.model.CreateRefundRequestDto;
import com.icligo.quickbooks.model.QuickBooksDocument;
import com.icligo.quickbooks.model.document.ClientInvoiceInfo;
import com.icligo.quickbooks.model.document.ItemDto;
import com.icligo.quickbooks.service.authentication.QuickBooksAlertService;
import com.icligo.quickbooks.temporal.TemporalDocumentService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RefundReceiptAllocationServiceTest {

    private final ActiveSalesReceiptFinder activeSalesReceiptFinder = mock(ActiveSalesReceiptFinder.class);
    private final TemporalDocumentService temporalDocumentService = mock(TemporalDocumentService.class);
    private final QuickBooksAlertService alertService = mock(QuickBooksAlertService.class);
    private final RefundReceiptAllocationService service =
            new RefundReceiptAllocationService(activeSalesReceiptFinder, temporalDocumentService, alertService);

    @Test
    void noActiveSalesReceiptsIsRejected() {
        when(activeSalesReceiptFinder.findActive("svc-1")).thenReturn(List.of());

        assertThatThrownBy(() -> service.createAllocatedRefundReceipts(request("svc-1", "rfd-1", "10.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("svc-1");
        verifyNoInteractions(temporalDocumentService);
    }

    @Test
    void singleActiveSalesReceiptWithPartialRefundCreatesOneRefundReceiptForTheRequestedValue() {
        ActiveSalesReceipt active = active("prod-1", "srv-1", "Airport transfer", new BigDecimal("100.00"));
        when(activeSalesReceiptFinder.findActive("srv-1")).thenReturn(List.of(active));
        when(temporalDocumentService.create(any())).thenAnswer(inv -> List.of(inv.getArgument(0, QuickBooksDocument.class)));

        List<Object> created = service.createAllocatedRefundReceipts(request("srv-1", "rfd-1", "30.00"));

        assertThat(created).hasSize(1);
        var captor = ArgumentCaptor.forClass(QuickBooksDocument.class);
        verify(temporalDocumentService).create(captor.capture());
        QuickBooksDocument refundDoc = captor.getValue();

        assertThat(refundDoc.getType()).isEqualTo(SalesDocumentTypes.REFUND_RECEIPT.getValue());
        assertThat(refundDoc.getServiceId()).isEqualTo("srv-1");
        assertThat(refundDoc.getProductId()).isEqualTo("prod-1");
        assertThat(refundDoc.getRefundId()).isEqualTo("rfd-1");
        assertThat(refundDoc.getItems()).hasSize(1);
        assertThat(refundDoc.getItems().get(0).getItem()).isEqualTo("Airport transfer");
        assertThat(refundDoc.getItems().get(0).getValue()).isEqualByComparingTo("30.00");
    }

    @Test
    void valueExceedingTotalAvailableFullyConsumesEveryActiveSalesReceiptAndIgnoresTheExcess() {
        ActiveSalesReceipt a = active("prod-a", "srv-2", "Day tour", new BigDecimal("40.00"));
        ActiveSalesReceipt b = active("prod-b", "srv-2", "Day tour", new BigDecimal("60.00"));
        when(activeSalesReceiptFinder.findActive("srv-2")).thenReturn(List.of(a, b));
        when(temporalDocumentService.create(any())).thenAnswer(inv -> List.of(inv.getArgument(0, QuickBooksDocument.class)));

        // requested 500 >> total available 100 → both fully consumed, excess silently ignored
        List<QuickBooksDocument> created = asDocuments(service.createAllocatedRefundReceipts(request("srv-2", "rfd-2", "500.00")));

        assertThat(created).hasSize(2);
        assertThat(created).extracting(QuickBooksDocument::getProductId).containsExactlyInAnyOrder("prod-a", "prod-b");
        assertThat(created.stream().flatMap(d -> d.getItems().stream()).map(ItemDto::getValue))
                .containsExactlyInAnyOrder(new BigDecimal("40.00"), new BigDecimal("60.00"));
    }

    @Test
    void multipleActiveSalesReceiptsSplitProportionallyWithLastAbsorbingTheRemainder() {
        ActiveSalesReceipt a = active("prod-c", "srv-3", "Day tour", new BigDecimal("70.00"));
        ActiveSalesReceipt b = active("prod-d", "srv-3", "Day tour", new BigDecimal("30.00"));
        when(activeSalesReceiptFinder.findActive("srv-3")).thenReturn(List.of(a, b));
        when(temporalDocumentService.create(any())).thenAnswer(inv -> List.of(inv.getArgument(0, QuickBooksDocument.class)));

        // total available = 100, requested = 50 → proportional: 70% share=35.00, 30% share=15.00
        List<QuickBooksDocument> created = asDocuments(service.createAllocatedRefundReceipts(request("srv-3", "rfd-3", "50.00")));

        assertThat(created).hasSize(2);
        BigDecimal amountFor = amountForProductId(created, "prod-c");
        BigDecimal amountForD = amountForProductId(created, "prod-d");
        assertThat(amountFor).isEqualByComparingTo("35.00");
        assertThat(amountForD).isEqualByComparingTo("15.00");
        assertThat(amountFor.add(amountForD)).isEqualByComparingTo("50.00");
    }

    @Test
    void oneAllocationFailingIsAlertedButOthersStillGetCreated() {
        ActiveSalesReceipt a = active("prod-e", "srv-4", "Day tour", new BigDecimal("50.00"));
        ActiveSalesReceipt b = active("prod-f", "srv-4", "Day tour", new BigDecimal("50.00"));
        when(activeSalesReceiptFinder.findActive("srv-4")).thenReturn(List.of(a, b));

        when(temporalDocumentService.create(argThat(d -> d != null && "prod-e".equals(d.getProductId()))))
                .thenThrow(new RuntimeException("QuickBooks Item 'Day tour' does not exist in this company."));
        when(temporalDocumentService.create(argThat(d -> d != null && "prod-f".equals(d.getProductId()))))
                .thenAnswer(inv -> List.of(inv.getArgument(0, QuickBooksDocument.class)));

        // total=100, requested=100 → both fully consumed (50 each)
        List<QuickBooksDocument> created = asDocuments(service.createAllocatedRefundReceipts(request("srv-4", "rfd-4", "100.00")));

        assertThat(created).hasSize(1);
        assertThat(created.get(0).getProductId()).isEqualTo("prod-f");
        verify(alertService).sendRefundAllocationFailedAlert(eq("srv-4"), eq("prod-e"), eq(new BigDecimal("50.00")), anyString());
    }

    /**
     * The mocked {@link TemporalDocumentService#create} above echoes back the built
     * QuickBooksDocument request itself (standing in for whatever entity it would really
     * return), so tests that need to inspect the built request's fields cast back here.
     */
    private List<QuickBooksDocument> asDocuments(List<Object> created) {
        return created.stream().map(QuickBooksDocument.class::cast).toList();
    }

    private BigDecimal amountForProductId(List<QuickBooksDocument> docs, String productId) {
        return docs.stream()
                .filter(d -> productId.equals(d.getProductId()))
                .flatMap(d -> d.getItems().stream())
                .map(ItemDto::getValue)
                .findFirst()
                .orElseThrow();
    }

    private ActiveSalesReceipt active(String productId, String serviceId, String itemName, BigDecimal availableBalance) {
        QuickBooksDocument document = new QuickBooksDocument();
        document.setServiceId(serviceId);
        document.setProductId(productId);
        document.setDescription("Booking #" + serviceId);

        ItemDto item = new ItemDto();
        item.setItem(itemName);
        item.setValue(availableBalance);
        document.setItems(List.of(item));

        ClientInvoiceInfo clientInfo = new ClientInvoiceInfo();
        clientInfo.setName("Jane Doe");
        clientInfo.setAddress("Rua Example 123");
        clientInfo.setCountry("PT");
        document.setClientInvoiceInfo(clientInfo);

        SalesReceipt salesReceipt = SalesReceipt.builder().totalAmt(availableBalance).build();
        return new ActiveSalesReceipt(document, salesReceipt, availableBalance);
    }

    private CreateRefundRequestDto request(String serviceId, String refundId, String value) {
        CreateRefundRequestDto dto = new CreateRefundRequestDto();
        dto.setServiceId(serviceId);
        dto.setRefundId(refundId);
        dto.setValue(new BigDecimal(value));
        return dto;
    }
}
