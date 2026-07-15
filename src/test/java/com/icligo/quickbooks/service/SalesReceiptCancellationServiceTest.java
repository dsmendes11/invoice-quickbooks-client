package com.icligo.quickbooks.service;

import com.icligo.quickbooks.clients.quickbooks.model.CreditMemo;
import com.icligo.quickbooks.clients.quickbooks.model.Line;
import com.icligo.quickbooks.clients.quickbooks.model.ReferenceType;
import com.icligo.quickbooks.clients.quickbooks.model.SalesReceipt;
import com.icligo.quickbooks.enums.SalesDocumentTypes;
import com.icligo.quickbooks.model.EditSplitCrediteNoteResponseDto;
import com.icligo.quickbooks.model.QuickBooksDocument;
import com.icligo.quickbooks.repository.QuickBooksDocumentRepository;
import com.icligo.quickbooks.service.authentication.QuickBooksAlertService;
import com.icligo.quickbooks.util.QuickBooksException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Year;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SalesReceiptCancellationServiceTest {

    private final ActiveSalesReceiptFinder activeSalesReceiptFinder = mock(ActiveSalesReceiptFinder.class);
    private final CreditMemoService creditMemoService = mock(CreditMemoService.class);
    private final QuickBooksAlertService alertService = mock(QuickBooksAlertService.class);
    private final QuickBooksDocumentRepository documentRepository = mock(QuickBooksDocumentRepository.class);
    private final SalesReceiptCancellationService service =
            new SalesReceiptCancellationService(activeSalesReceiptFinder, creditMemoService, alertService, documentRepository);

    {
        ReflectionTestUtils.setField(service, "basePath", "/invoice-quickbooks-service/v1");
    }

    private static String controlKey(String productId) {
        return SalesDocumentTypes.CREDIT_MEMO.getValue() + productId + Year.now().getValue();
    }

    @Test
    void noActiveSalesReceiptsCreatesNoCreditMemo() {
        when(activeSalesReceiptFinder.findActive("svc-1")).thenReturn(List.of());

        List<QuickBooksDocument> result = service.cancelSalesReceiptsForServiceId("svc-1");

        assertThat(result).isEmpty();
        verifyNoInteractions(creditMemoService, alertService, documentRepository);
    }

    @Test
    void fullyUnrefundedSalesReceiptIsCreditedInFullWithLinesMirroringTheOriginal() throws Exception {
        SalesReceipt salesReceipt = salesReceipt("SRCprod-1", new BigDecimal("100.00"),
                line("Airport transfer", new BigDecimal("70.00")),
                line("Child seat", new BigDecimal("30.00")));
        ActiveSalesReceipt active = new ActiveSalesReceipt(salesReceiptDoc("prod-1", "srv-1"), salesReceipt, new BigDecimal("100.00"));

        when(activeSalesReceiptFinder.findActive("srv-1")).thenReturn(List.of(active));
        when(creditMemoService.createCreditMemo(any())).thenReturn(CreditMemo.builder().id("qb-cdm-1").build());
        when(documentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        List<QuickBooksDocument> result = service.cancelSalesReceiptsForServiceId("srv-1");

        var captor = forClass(CreditMemo.class);
        verify(creditMemoService).createCreditMemo(captor.capture());
        CreditMemo creditMemo = captor.getValue();

        assertThat(creditMemo.getDocNumber()).isEqualTo(controlKey("prod-1"));
        assertThat(creditMemo.getLine()).hasSize(2);
        assertThat(creditMemo.getLine().get(0).getAmount()).isEqualByComparingTo("70.00");
        assertThat(creditMemo.getLine().get(1).getAmount()).isEqualByComparingTo("30.00");
        verifyNoInteractions(alertService);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getType()).isEqualTo("CDM");
        assertThat(result.get(0).getControlKey()).isEqualTo(controlKey("prod-1"));
    }

    @Test
    void createdCreditMemoIsPersistedAsANormalDocumentWithControlKey() throws Exception {
        SalesReceipt salesReceipt = salesReceipt("SRCprod-3", new BigDecimal("50.00"),
                line("Day tour", new BigDecimal("50.00")));
        QuickBooksDocument salesReceiptDoc = salesReceiptDoc("prod-3", "srv-3");
        ActiveSalesReceipt active = new ActiveSalesReceipt(salesReceiptDoc, salesReceipt, new BigDecimal("50.00"));

        when(activeSalesReceiptFinder.findActive("srv-3")).thenReturn(List.of(active));
        when(creditMemoService.createCreditMemo(any())).thenReturn(CreditMemo.builder().id("qb-cdm-3").build());

        service.cancelSalesReceiptsForServiceId("srv-3");

        var captor = forClass(QuickBooksDocument.class);
        verify(documentRepository).save(captor.capture());
        QuickBooksDocument saved = captor.getValue();

        String expectedControlKey = controlKey("prod-3");
        assertThat(saved.getType()).isEqualTo("CDM");
        assertThat(saved.getControlKey()).isEqualTo(expectedControlKey);
        assertThat(saved.getServiceId()).isEqualTo("srv-3");
        assertThat(saved.getProductId()).isEqualTo("prod-3");
        assertThat(saved.getInvoice()).isInstanceOf(CreditMemo.class);
        assertThat(((CreditMemo) saved.getInvoice()).getId()).isEqualTo("qb-cdm-3");
        assertThat(saved.getDocumentPDF()).isEqualTo("/invoice-quickbooks-service/v1/documents/" + expectedControlKey + "/pdf");
        assertThat(saved.getItems()).hasSize(1);
        assertThat(saved.getItems().get(0).getValue()).isEqualByComparingTo("50.00");
    }

    @Test
    void alreadyCancelledProductIdIsSkippedWithoutCallingQuickBooksAgain() {
        SalesReceipt salesReceipt = salesReceipt("SRCprod-5", new BigDecimal("50.00"), line("Day tour", new BigDecimal("50.00")));
        ActiveSalesReceipt active = new ActiveSalesReceipt(salesReceiptDoc("prod-5", "srv-5"), salesReceipt, new BigDecimal("50.00"));

        when(activeSalesReceiptFinder.findActive("srv-5")).thenReturn(List.of(active));
        when(documentRepository.findByControlKey(controlKey("prod-5")))
                .thenReturn(Optional.of(new QuickBooksDocument()));

        service.cancelSalesReceiptsForServiceId("srv-5");

        verifyNoInteractions(creditMemoService, alertService);
        verify(documentRepository, never()).save(any());
    }

    @Test
    void partiallyRefundedSalesReceiptIsCreditedOnlyForTheRemainderProportionally() throws Exception {
        SalesReceipt salesReceipt = salesReceipt("SRCprod-2", new BigDecimal("100.00"),
                line("Airport transfer", new BigDecimal("70.00")),
                line("Child seat", new BigDecimal("30.00")));
        // 40 already refunded elsewhere → availableBalance = 60
        ActiveSalesReceipt active = new ActiveSalesReceipt(salesReceiptDoc("prod-2", "srv-2"), salesReceipt, new BigDecimal("60.00"));

        when(activeSalesReceiptFinder.findActive("srv-2")).thenReturn(List.of(active));
        when(creditMemoService.createCreditMemo(any())).thenReturn(CreditMemo.builder().id("qb-cdm-2").build());

        service.cancelSalesReceiptsForServiceId("srv-2");

        // ratio = 60/100 = 0.6 → 70*0.6=42.00, 30*0.6=18.00
        var captor = forClass(CreditMemo.class);
        verify(creditMemoService).createCreditMemo(captor.capture());
        CreditMemo creditMemo = captor.getValue();

        assertThat(creditMemo.getLine().get(0).getAmount()).isEqualByComparingTo("42.00");
        assertThat(creditMemo.getLine().get(1).getAmount()).isEqualByComparingTo("18.00");
    }

    @Test
    void quickBooksSummaryLineIsExcludedFromTheCreditMemo() throws Exception {
        // QuickBooks appends its own SubTotalLineDetail summary line to the SalesReceipt it
        // returns — real amount, but not a sold item — this must not be credited a second time.
        SalesReceipt salesReceipt = salesReceipt("SRCprod-6", new BigDecimal("49.90"),
                line("Trip Services", new BigDecimal("49.90")),
                subtotalLine(new BigDecimal("49.90")));
        ActiveSalesReceipt active = new ActiveSalesReceipt(salesReceiptDoc("prod-6", "srv-6"), salesReceipt, new BigDecimal("49.90"));

        when(activeSalesReceiptFinder.findActive("srv-6")).thenReturn(List.of(active));
        when(creditMemoService.createCreditMemo(any())).thenReturn(CreditMemo.builder().id("qb-cdm-6").build());

        service.cancelSalesReceiptsForServiceId("srv-6");

        var captor = forClass(CreditMemo.class);
        verify(creditMemoService).createCreditMemo(captor.capture());
        CreditMemo creditMemo = captor.getValue();

        assertThat(creditMemo.getLine()).hasSize(1);
        assertThat(creditMemo.getLine().get(0).getAmount()).isEqualByComparingTo("49.90");
    }

    @Test
    void creditMemoFailureIsAlertedAndDoesNotPropagate() throws Exception {
        SalesReceipt salesReceipt = salesReceipt("SRCprod-4", new BigDecimal("25.00"), line("Day tour", new BigDecimal("25.00")));
        ActiveSalesReceipt active = new ActiveSalesReceipt(salesReceiptDoc("prod-4", "srv-4"), salesReceipt, new BigDecimal("25.00"));

        when(activeSalesReceiptFinder.findActive("srv-4")).thenReturn(List.of(active));
        when(creditMemoService.createCreditMemo(any()))
                .thenThrow(new QuickBooksException("QuickBooks Item 'Day tour' does not exist in this company."));

        List<QuickBooksDocument> result = service.cancelSalesReceiptsForServiceId("srv-4");

        assertThat(result).isEmpty();
        verify(alertService).sendCreditMemoCancellationFailedAlert(eq("srv-4"), eq("prod-4"), anyString());
        verify(documentRepository, never()).save(any());
    }

    @Test
    void cancelByControlKeyCreditsWhatIsStillOpenAndReturnsTheCreditMemoDocument() throws Exception {
        SalesReceipt salesReceipt = salesReceipt("SRCprod-8", new BigDecimal("50.00"), line("Day tour", new BigDecimal("50.00")));
        QuickBooksDocument salesReceiptDoc = salesReceiptDoc("prod-8", "srv-8");
        salesReceiptDoc.setControlKey("SRTprod-82026");
        salesReceiptDoc.setInvoice(salesReceipt);

        when(documentRepository.findByControlKey("SRTprod-82026")).thenReturn(Optional.of(salesReceiptDoc));
        when(activeSalesReceiptFinder.findActiveForDocument(salesReceiptDoc))
                .thenReturn(Optional.of(new ActiveSalesReceipt(salesReceiptDoc, salesReceipt, new BigDecimal("50.00"))));
        when(creditMemoService.createCreditMemo(any())).thenReturn(CreditMemo.builder().id("qb-cdm-8").build());
        when(documentRepository.findByControlKey(controlKey("prod-8"))).thenReturn(Optional.empty());
        when(documentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Optional<EditSplitCrediteNoteResponseDto> result = service.cancelSalesReceiptByControlKey("SRTprod-82026");

        assertThat(result).isPresent();
        assertThat(result.get().getCrediteNoteValue()).isEqualByComparingTo("50.00");
        assertThat(result.get().getProductId()).isEqualTo("prod-8");
        assertThat(result.get().getDocuments()).hasSize(1);
        assertThat(result.get().getDocuments().get(0)).isInstanceOf(CreditMemo.class);
        assertThat(((CreditMemo) result.get().getDocuments().get(0)).getId()).isEqualTo("qb-cdm-8");
        verifyNoInteractions(alertService);
    }

    @Test
    void cancelByControlKeyReturnsEmptyWhenNothingIsLeftOpen() throws Exception {
        QuickBooksDocument salesReceiptDoc = salesReceiptDoc("prod-9", "srv-9");
        salesReceiptDoc.setControlKey("SRTprod-92026");

        when(documentRepository.findByControlKey("SRTprod-92026")).thenReturn(Optional.of(salesReceiptDoc));
        when(activeSalesReceiptFinder.findActiveForDocument(salesReceiptDoc)).thenReturn(Optional.empty());

        Optional<EditSplitCrediteNoteResponseDto> result = service.cancelSalesReceiptByControlKey("SRTprod-92026");

        assertThat(result).isEmpty();
        verifyNoInteractions(creditMemoService, alertService);
    }

    @Test
    void cancelByControlKeyThrowsNoSuchElementWhenControlKeyDoesNotExist() {
        when(documentRepository.findByControlKey("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelSalesReceiptByControlKey("missing"))
                .isInstanceOf(NoSuchElementException.class);
        verifyNoInteractions(activeSalesReceiptFinder, creditMemoService, alertService);
    }

    @Test
    void cancelByControlKeyThrowsNoSuchElementWhenDocumentIsNotASalesReceipt() {
        QuickBooksDocument invoiceDoc = new QuickBooksDocument();
        invoiceDoc.setType(SalesDocumentTypes.INVOICE.getValue());
        invoiceDoc.setControlKey("INV700212026");
        when(documentRepository.findByControlKey("INV700212026")).thenReturn(Optional.of(invoiceDoc));

        assertThatThrownBy(() -> service.cancelSalesReceiptByControlKey("INV700212026"))
                .isInstanceOf(NoSuchElementException.class);
        verifyNoInteractions(activeSalesReceiptFinder, creditMemoService, alertService);
    }

    @Test
    void cancelByControlKeyPropagatesQuickBooksFailureInsteadOfEmailingAndSwallowing() throws Exception {
        SalesReceipt salesReceipt = salesReceipt("SRCprod-10", new BigDecimal("25.00"), line("Day tour", new BigDecimal("25.00")));
        QuickBooksDocument salesReceiptDoc = salesReceiptDoc("prod-10", "srv-10");
        salesReceiptDoc.setControlKey("SRTprod-102026");
        salesReceiptDoc.setInvoice(salesReceipt);

        when(documentRepository.findByControlKey("SRTprod-102026")).thenReturn(Optional.of(salesReceiptDoc));
        when(activeSalesReceiptFinder.findActiveForDocument(salesReceiptDoc))
                .thenReturn(Optional.of(new ActiveSalesReceipt(salesReceiptDoc, salesReceipt, new BigDecimal("25.00"))));
        when(documentRepository.findByControlKey(controlKey("prod-10"))).thenReturn(Optional.empty());
        when(creditMemoService.createCreditMemo(any()))
                .thenThrow(new QuickBooksException("QuickBooks Item 'Day tour' does not exist in this company."));

        assertThatThrownBy(() -> service.cancelSalesReceiptByControlKey("SRTprod-102026"))
                .isInstanceOf(QuickBooksException.class);
        verifyNoInteractions(alertService);
        verify(documentRepository, never()).save(any());
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
        return Line.builder().description(description).amount(amount).detailType("SalesItemLineDetail").build();
    }

    /** Mirrors the summary line QuickBooks itself appends to every Line array it returns. */
    private Line subtotalLine(BigDecimal amount) {
        return Line.builder().amount(amount).detailType("SubTotalLineDetail").build();
    }
}
