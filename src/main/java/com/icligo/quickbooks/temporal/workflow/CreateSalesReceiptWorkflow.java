package com.icligo.quickbooks.temporal.workflow;

import com.icligo.quickbooks.model.QuickBooksDocument;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.util.List;

/**
 * Temporal Workflow: Create SalesReceipt (with find-or-create customer).
 */
@WorkflowInterface
public interface CreateSalesReceiptWorkflow {

    /**
     * @return the documents emitted by this call — always a single-element list ([SRT]), since
     *         creating a Sales Receipt has no side-effect documents (contrast with
     *         {@link CreateInvoiceWorkflow}, which can also emit CreditMemos).
     */
    @WorkflowMethod
    List<QuickBooksDocument> execute(QuickBooksDocument document);
}
