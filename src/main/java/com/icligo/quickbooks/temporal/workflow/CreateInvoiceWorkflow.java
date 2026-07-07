package com.icligo.quickbooks.temporal.workflow;

import com.icligo.quickbooks.model.QuickBooksDocument;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

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

    @WorkflowMethod
    QuickBooksDocument execute(QuickBooksDocument document);
}
