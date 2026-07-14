package com.icligo.quickbooks.temporal.workflow;

import com.icligo.quickbooks.model.QuickBooksDocument;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.util.List;

/**
 * Temporal Workflow: Create RefundReceipt (with find-or-create customer).
 */
@WorkflowInterface
public interface CreateRefundReceiptWorkflow {

    /**
     * @return the documents emitted by this call — always a single-element list ([RRT]).
     */
    @WorkflowMethod
    List<QuickBooksDocument> execute(QuickBooksDocument document);
}
