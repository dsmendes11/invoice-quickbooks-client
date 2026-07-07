package com.icligo.quickbooks.temporal;

import com.icligo.quickbooks.model.QuickBooksDocument;
import com.icligo.quickbooks.temporal.activity.QuickBooksActivitiesImpl;
import com.icligo.quickbooks.temporal.workflow.CreateInvoiceWorkflow;
import com.icligo.quickbooks.temporal.workflow.CreateRefundReceiptWorkflow;
import com.icligo.quickbooks.temporal.workflow.CreateSalesReceiptWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Spring service that is the single entrypoint for document creation.
 *
 * <p>Instead of calling QB APIs directly, it starts the appropriate Temporal
 * workflow, which handles orchestration, retries, and persistence.
 *
 * <p>The REST controller ({@link com.icligo.quickbooks.controller.DocumentController})
 * now injects this service instead of the old {@code DocumentService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TemporalDocumentService {

    private final WorkflowClient workflowClient;

    /**
     * Dispatch a document creation request to the appropriate Temporal workflow.
     *
     * @param document the incoming request document
     * @return the persisted document containing the QB response
     */
    public QuickBooksDocument create(QuickBooksDocument document) {
        if (document.getClientInvoiceInfo() == null) {
            throw new IllegalArgumentException("clientInvoiceInfo is required");
        }
        if (document.getType() == null) {
            throw new IllegalArgumentException("type is required");
        }

        return switch (document.getType().toUpperCase()) {
            case "INVOICE"        -> runInvoiceWorkflow(document);
            case "SALES_RECEIPT"  -> runSalesReceiptWorkflow(document);
            case "REFUND_RECEIPT" -> runRefundReceiptWorkflow(document);
            default -> throw new IllegalArgumentException(
                    "Unsupported document type: " + document.getType());
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private QuickBooksDocument runInvoiceWorkflow(QuickBooksDocument document) {
        String workflowId = "create-invoice-" + document.getServiceId() + "-" + uuid();
        log.info("Starting CreateInvoiceWorkflow workflowId={}", workflowId);

        CreateInvoiceWorkflow workflow = workflowClient.newWorkflowStub(
                CreateInvoiceWorkflow.class,
                workflowOptions(workflowId)
        );

        return workflow.execute(document);
    }

    private QuickBooksDocument runSalesReceiptWorkflow(QuickBooksDocument document) {
        String workflowId = "create-sales-receipt-" + document.getProductId() + "-" + uuid();
        log.info("Starting CreateSalesReceiptWorkflow workflowId={}", workflowId);

        CreateSalesReceiptWorkflow workflow = workflowClient.newWorkflowStub(
                CreateSalesReceiptWorkflow.class,
                workflowOptions(workflowId)
        );

        return workflow.execute(document);
    }

    private QuickBooksDocument runRefundReceiptWorkflow(QuickBooksDocument document) {
        String workflowId = "create-refund-receipt-" + document.getRefundId() + "-" + uuid();
        log.info("Starting CreateRefundReceiptWorkflow workflowId={}", workflowId);

        CreateRefundReceiptWorkflow workflow = workflowClient.newWorkflowStub(
                CreateRefundReceiptWorkflow.class,
                workflowOptions(workflowId)
        );

        return workflow.execute(document);
    }

    private WorkflowOptions workflowOptions(String workflowId) {
        return WorkflowOptions.newBuilder()
                .setTaskQueue(QuickBooksActivitiesImpl.TASK_QUEUE)
                .setWorkflowId(workflowId)
                // Workflow-level execution timeout – hard ceiling for the entire run
                .setWorkflowExecutionTimeout(java.time.Duration.ofMinutes(10))
                .build();
    }

    private String uuid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
