package com.icligo.quickbooks.temporal.workflow;

import com.icligo.quickbooks.model.QuickBooksDocument;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Temporal Workflow: Create RefundReceipt (with find-or-create customer).
 */
@WorkflowInterface
public interface CreateRefundReceiptWorkflow {

    @WorkflowMethod
    QuickBooksDocument execute(QuickBooksDocument document);
}
