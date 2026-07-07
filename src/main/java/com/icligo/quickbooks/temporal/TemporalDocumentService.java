package com.icligo.quickbooks.temporal;

import com.icligo.quickbooks.model.QuickBooksDocument;
import com.icligo.quickbooks.repository.QuickBooksDocumentRepository;
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
    private final QuickBooksDocumentRepository documentRepository;

    /**
     * Dispatch a document creation request to the appropriate Temporal workflow.
     *
     * <p>Idempotent on {@code (type, serviceId/productId/refundId)}: a repeat request for a
     * key that already has a saved document returns that document as-is, without starting a
     * new workflow or calling QuickBooks again. This only covers repeats of a request that
     * already completed — two truly concurrent first-time requests for the same key can still
     * both reach QuickBooks; the unique Mongo index on {@code naturalKey} is the backstop for
     * that race, surfacing as a 502 from {@code saveDocument} for the loser.
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

        String naturalKey = buildNaturalKey(document);
        var existing = documentRepository.findByNaturalKey(naturalKey);
        if (existing.isPresent()) {
            log.info("Idempotent replay for naturalKey={} – returning existing document id={}",
                    naturalKey, existing.get().getId());
            return existing.get();
        }
        document.setNaturalKey(naturalKey);

        return switch (document.getType().toUpperCase()) {
            case "INVOICE"        -> runInvoiceWorkflow(document);
            case "SALES_RECEIPT"  -> runSalesReceiptWorkflow(document);
            case "REFUND_RECEIPT" -> runRefundReceiptWorkflow(document);
            default -> throw new IllegalArgumentException(
                    "Unsupported document type: " + document.getType());
        };
    }

    private String buildNaturalKey(QuickBooksDocument document) {
        return switch (document.getType().toUpperCase()) {
            case "INVOICE"        -> "INVOICE:" + document.getServiceId();
            case "SALES_RECEIPT"  -> "SALES_RECEIPT:" + document.getProductId();
            case "REFUND_RECEIPT" -> "REFUND_RECEIPT:" + document.getProductId() + ":" + document.getRefundId();
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
