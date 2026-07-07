package com.icligo.quickbooks.temporal;

import com.icligo.quickbooks.model.QuickBooksDocument;
import com.icligo.quickbooks.model.document.ClientInvoiceInfo;
import com.icligo.quickbooks.repository.QuickBooksDocumentRepository;
import com.icligo.quickbooks.temporal.workflow.CreateInvoiceWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.junit.jupiter.api.Test;

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
        when(documentRepository.findByNaturalKey("INVOICE:48213")).thenReturn(Optional.of(existing));

        QuickBooksDocument result = service.create(invoiceRequest("48213"));

        assertThat(result.getId()).isEqualTo("existing-id");
        verifyNoInteractions(workflowClient);
    }

    @Test
    void newRequestStartsWorkflowAndTagsTheDocumentWithItsNaturalKey() {
        when(documentRepository.findByNaturalKey("INVOICE:48213")).thenReturn(Optional.empty());

        CreateInvoiceWorkflow workflowStub = mock(CreateInvoiceWorkflow.class);
        QuickBooksDocument created = new QuickBooksDocument();
        created.setId("new-id");
        when(workflowStub.execute(any())).thenReturn(created);
        when(workflowClient.newWorkflowStub(eq(CreateInvoiceWorkflow.class), any(WorkflowOptions.class)))
                .thenReturn(workflowStub);

        QuickBooksDocument request = invoiceRequest("48213");
        QuickBooksDocument result = service.create(request);

        assertThat(result.getId()).isEqualTo("new-id");
        assertThat(request.getNaturalKey()).isEqualTo("INVOICE:48213");
        verify(workflowStub).execute(request);
    }

    @Test
    void missingClientInvoiceInfoIsRejectedBeforeTouchingTheRepository() {
        QuickBooksDocument request = new QuickBooksDocument();
        request.setType("INVOICE");
        request.setServiceId("48213");

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clientInvoiceInfo is required");
        verifyNoInteractions(documentRepository, workflowClient);
    }

    @Test
    void unsupportedTypeIsRejectedBeforeTouchingTheRepository() {
        QuickBooksDocument request = invoiceRequest("48213");
        request.setType("BOGUS");

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported document type");
        verifyNoInteractions(documentRepository, workflowClient);
    }

    private QuickBooksDocument invoiceRequest(String serviceId) {
        QuickBooksDocument document = new QuickBooksDocument();
        document.setType("INVOICE");
        document.setServiceId(serviceId);
        ClientInvoiceInfo customer = new ClientInvoiceInfo();
        customer.setName("Jane Doe");
        document.setClientInvoiceInfo(customer);
        return document;
    }
}
