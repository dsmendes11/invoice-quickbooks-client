package com.icligo.quickbooks.temporal;

import com.icligo.quickbooks.clients.quickbooks.model.Invoice;
import com.icligo.quickbooks.model.QuickBooksDocument;
import com.icligo.quickbooks.model.document.ClientInvoiceInfo;
import com.icligo.quickbooks.repository.QuickBooksDocumentRepository;
import com.icligo.quickbooks.temporal.workflow.CreateInvoiceWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Year;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TemporalDocumentServiceTest {

    private final WorkflowClient workflowClient = mock(WorkflowClient.class);
    private final QuickBooksDocumentRepository documentRepository = mock(QuickBooksDocumentRepository.class);
    private final TemporalDocumentService service =
            new TemporalDocumentService(workflowClient, documentRepository);

    {
        ReflectionTestUtils.setField(service, "basePath", "/invoice-quickbooks-service/v1");
    }

    @Test
    void newRequestReturnsTheRawQuickBooksEntityFromTheWorkflow() {
        when(documentRepository.findByControlKey(expectedControlKey("INV", "70021", "")))
                .thenReturn(Optional.empty());

        Invoice invoice = Invoice.builder().id("qb-inv-1").docNumber(expectedControlKey("INV", "70021", "")).build();
        CreateInvoiceWorkflow workflowStub = mock(CreateInvoiceWorkflow.class);
        when(workflowStub.execute(any())).thenAnswer(invocation -> {
            QuickBooksDocument document = invocation.getArgument(0);
            document.setInvoice(invoice);
            return List.of(document);
        });
        when(workflowClient.newWorkflowStub(eq(CreateInvoiceWorkflow.class), any(WorkflowOptions.class)))
                .thenReturn(workflowStub);

        List<Object> result = service.create(invoiceRequest("48213", "70021"));

        assertThat(result).containsExactly(invoice);
    }

    @Test
    void idempotentReplayReturnsTheExistingDocumentsInvoiceEntity() {
        Invoice invoice = Invoice.builder().id("qb-inv-existing").build();
        QuickBooksDocument existing = new QuickBooksDocument();
        existing.setId("existing-id");
        existing.setControlKey(expectedControlKey("INV", "70021", ""));
        existing.setInvoice(invoice);
        when(documentRepository.findByControlKey(expectedControlKey("INV", "70021", "")))
                .thenReturn(Optional.of(existing));

        List<Object> result = service.create(invoiceRequest("48213", "70021"));

        assertThat(result).containsExactly(invoice);
    }

    @Test
    void creditMemoTypeIsRejected() {
        QuickBooksDocument request = invoiceRequest("48213", "70021");
        request.setType("CDM");

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CreditMemo creation is not accepted here");
        verifyNoInteractions(documentRepository, workflowClient);
    }

    @Test
    void idempotentReplayReturnsExistingDocumentWithoutStartingAWorkflow() {
        QuickBooksDocument existing = new QuickBooksDocument();
        existing.setId("existing-id");
        existing.setInvoice(Invoice.builder().id("qb-inv-existing").build());
        when(documentRepository.findByControlKey(expectedControlKey("INV", "70021", "")))
                .thenReturn(Optional.of(existing));

        List<Object> result = service.create(invoiceRequest("48213", "70021"));

        assertThat(result).hasSize(1);
        verifyNoInteractions(workflowClient);
    }

    @Test
    void newRequestStartsWorkflowAndTagsTheDocumentWithItsControlKey() {
        when(documentRepository.findByControlKey(expectedControlKey("INV", "70021", "")))
                .thenReturn(Optional.empty());

        CreateInvoiceWorkflow workflowStub = mock(CreateInvoiceWorkflow.class);
        QuickBooksDocument created = new QuickBooksDocument();
        created.setId("new-id");
        created.setInvoice(Invoice.builder().id("qb-inv-new").build());
        when(workflowStub.execute(any())).thenReturn(List.of(created));
        when(workflowClient.newWorkflowStub(eq(CreateInvoiceWorkflow.class), any(WorkflowOptions.class)))
                .thenReturn(workflowStub);

        QuickBooksDocument request = invoiceRequest("48213", "70021");
        List<Object> result = service.create(request);

        assertThat(result).hasSize(1);
        assertThat(request.getControlKey()).isEqualTo(expectedControlKey("INV", "70021", ""));
        assertThat(request.getSerie()).isEqualTo(String.valueOf(Year.now().getValue()));
        // Set on the request before the workflow runs, so saveDocument persists it to Mongo —
        // even though it's no longer part of the API response itself (see #toInvoices).
        assertThat(request.getDocumentPDF())
                .isEqualTo("/invoice-quickbooks-service/v1/documents/" + expectedControlKey("INV", "70021", "") + "/pdf");
        verify(workflowStub).execute(request);
    }

    @Test
    void controlKeyIgnoresServiceIdAndOnlyDependsOnProductId() {
        QuickBooksDocument existing = new QuickBooksDocument();
        existing.setId("existing-id");
        existing.setInvoice(Invoice.builder().id("qb-inv-existing").build());
        when(documentRepository.findByControlKey(expectedControlKey("INV", "70021", "")))
                .thenReturn(Optional.of(existing));

        // Same productId, different serviceId — still matches the same controlKey (serviceId
        // is required for context, per the corrected formula it does not feed into the key).
        List<Object> result = service.create(invoiceRequest("different-service-id", "70021"));

        assertThat(result).hasSize(1);
        verifyNoInteractions(workflowClient);
    }

    @Test
    void missingClientInvoiceInfoIsRejectedBeforeTouchingTheRepository() {
        QuickBooksDocument request = new QuickBooksDocument();
        request.setType("INV");
        request.setServiceId("48213");
        request.setProductId("70021");

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clientInvoiceInfo is required");
        verifyNoInteractions(documentRepository, workflowClient);
    }

    @Test
    void missingServiceIdIsRejectedBeforeTouchingTheRepository() {
        QuickBooksDocument request = invoiceRequest("48213", "70021");
        request.setServiceId(null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("serviceId is required");
        verifyNoInteractions(documentRepository, workflowClient);
    }

    @Test
    void missingProductIdIsRejectedBeforeTouchingTheRepository() {
        QuickBooksDocument request = invoiceRequest("48213", "70021");
        request.setProductId(null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("productId is required");
        verifyNoInteractions(documentRepository, workflowClient);
    }

    @Test
    void unsupportedTypeIsRejectedBeforeTouchingTheRepository() {
        QuickBooksDocument request = invoiceRequest("48213", "70021");
        request.setType("BOGUS");

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported document type");
        verifyNoInteractions(documentRepository, workflowClient);
    }

    private String expectedControlKey(String type, String productId, String suffix) {
        return type + productId + suffix + Year.now().getValue();
    }

    private QuickBooksDocument invoiceRequest(String serviceId, String productId) {
        QuickBooksDocument document = new QuickBooksDocument();
        document.setType("INV");
        document.setServiceId(serviceId);
        document.setProductId(productId);
        ClientInvoiceInfo customer = new ClientInvoiceInfo();
        customer.setName("Jane Doe");
        document.setClientInvoiceInfo(customer);
        return document;
    }
}
