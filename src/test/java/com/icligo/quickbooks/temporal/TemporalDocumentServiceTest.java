package com.icligo.quickbooks.temporal;

import com.icligo.quickbooks.model.QuickBooksDocument;
import com.icligo.quickbooks.model.document.ClientInvoiceInfo;
import com.icligo.quickbooks.repository.QuickBooksDocumentRepository;
import com.icligo.quickbooks.temporal.workflow.CreateInvoiceWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.junit.jupiter.api.Test;

import java.time.Year;
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

    @Test
    void idempotentReplayReturnsExistingDocumentWithoutStartingAWorkflow() {
        QuickBooksDocument existing = new QuickBooksDocument();
        existing.setId("existing-id");
        when(documentRepository.findByControlKey(expectedControlKey("INV", "70021", "")))
                .thenReturn(Optional.of(existing));

        QuickBooksDocument result = service.create(invoiceRequest("48213", "70021"));

        assertThat(result.getId()).isEqualTo("existing-id");
        verifyNoInteractions(workflowClient);
    }

    @Test
    void newRequestStartsWorkflowAndTagsTheDocumentWithItsControlKey() {
        when(documentRepository.findByControlKey(expectedControlKey("INV", "70021", "")))
                .thenReturn(Optional.empty());

        CreateInvoiceWorkflow workflowStub = mock(CreateInvoiceWorkflow.class);
        QuickBooksDocument created = new QuickBooksDocument();
        created.setId("new-id");
        when(workflowStub.execute(any())).thenReturn(created);
        when(workflowClient.newWorkflowStub(eq(CreateInvoiceWorkflow.class), any(WorkflowOptions.class)))
                .thenReturn(workflowStub);

        QuickBooksDocument request = invoiceRequest("48213", "70021");
        QuickBooksDocument result = service.create(request);

        assertThat(result.getId()).isEqualTo("new-id");
        assertThat(request.getControlKey()).isEqualTo(expectedControlKey("INV", "70021", ""));
        assertThat(request.getSerie()).isEqualTo(String.valueOf(Year.now().getValue()));
        verify(workflowStub).execute(request);
    }

    @Test
    void controlKeyIgnoresServiceIdAndOnlyDependsOnProductId() {
        QuickBooksDocument existing = new QuickBooksDocument();
        existing.setId("existing-id");
        when(documentRepository.findByControlKey(expectedControlKey("INV", "70021", "")))
                .thenReturn(Optional.of(existing));

        // Same productId, different serviceId — still matches the same controlKey (serviceId
        // is required for context, per the corrected formula it does not feed into the key).
        QuickBooksDocument result = service.create(invoiceRequest("different-service-id", "70021"));

        assertThat(result.getId()).isEqualTo("existing-id");
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
