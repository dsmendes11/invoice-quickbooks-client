package com.icligo.quickbooks.temporal;

import com.icligo.quickbooks.enums.SalesDocumentTypes;
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

import java.time.Year;
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
     * <p>Idempotent on {@code (type, productId, serie[, refundId])}: a repeat request for a key
     * that already has a saved document returns that document as-is, without starting a new
     * workflow or calling QuickBooks again. This only covers repeats of a request that already
     * completed — two truly concurrent first-time requests for the same key can still both
     * reach QuickBooks; the unique Mongo index on {@code controlKey} is the backstop for that
     * race, surfacing as a 502 from {@code saveDocument} for the loser.
     *
     * <p>Because {@code serie} is the current year, this key naturally resets every year — a
     * repeat of the same {@code productId} in a following year is treated as a brand-new
     * document, not a replay, matching how the invoice-management-system's own year-scoped
     * {@code serie} works.
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
        if (isBlank(document.getServiceId())) {
            throw new IllegalArgumentException("serviceId is required");
        }
        if (isBlank(document.getProductId())) {
            throw new IllegalArgumentException("productId is required");
        }

        SalesDocumentTypes type = SalesDocumentTypes.getByValue(document.getType().toUpperCase());
        if (type == null) {
            throw new IllegalArgumentException("Unsupported document type: " + document.getType());
        }
        // Normalize to the canonical code so later lookups (e.g. cancelling matching Sales
        // Receipts by type+serviceId) can rely on an exact, consistently-cased stored value.
        document.setType(type.getValue());

        String controlKey = buildControlKey(document, type);
        var existing = documentRepository.findByControlKey(controlKey);
        if (existing.isPresent()) {
            log.info("Idempotent replay for controlKey={} – returning existing document id={}",
                    controlKey, existing.get().getId());
            return existing.get();
        }
        document.setControlKey(controlKey);

        return switch (type) {
            case INVOICE -> runInvoiceWorkflow(document);
            case SALES_RECEIPT -> runSalesReceiptWorkflow(document);
            case REFUND_RECEIPT -> runRefundReceiptWorkflow(document);
        };
    }

    /**
     * Mirrors the invoice-management-system's {@code checkAndCreateChaveControlo} exactly:
     * {@code type + productId + suffix + serie} — plain concatenation of type, productId, a
     * REFUND_RECEIPT-only {@code "_rfd" + refundId} suffix, and the current-year serie.
     * {@code serviceId} is required on every request (see {@link #create}) but, matching the
     * reference implementation, does not itself feed into the key.
     */
    private String buildControlKey(QuickBooksDocument document, SalesDocumentTypes type) {
        String serie = String.valueOf(Year.now().getValue());
        document.setSerie(serie);

        String suffix = type == SalesDocumentTypes.REFUND_RECEIPT ? "_rfd" + document.getRefundId() : "";

        return type.getValue() + document.getProductId() + suffix + serie;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
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
