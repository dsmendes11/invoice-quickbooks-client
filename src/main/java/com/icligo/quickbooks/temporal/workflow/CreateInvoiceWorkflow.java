package com.icligo.quickbooks.temporal.workflow;

import com.icligo.quickbooks.model.QuickBooksDocument;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.util.List;

/**
 * Temporal Workflow: Create Invoice (with find-or-create customer).
 *
 * <p>Orchestrates:
 * <ol>
 *   <li>findOrCreateCustomer – idempotent, retried up to 5×</li>
 *   <li>createInvoice        – retried up to 3×</li>
 *   <li>saveDocument         – retried up to 3×</li>
 * </ol>
 */
@WorkflowInterface
public interface CreateInvoiceWorkflow {

    /**
     * @return the documents emitted by this call — the Invoice itself, plus, when it's a booking
     *         (Reserva) invoice, any CreditMemos created cancelling prior Sales Receipts for the
     *         same serviceId (e.g. [INV, CDM, CDM]). Just [INV] for a non-booking invoice.
     */
    @WorkflowMethod
    List<QuickBooksDocument> execute(QuickBooksDocument document);
}
