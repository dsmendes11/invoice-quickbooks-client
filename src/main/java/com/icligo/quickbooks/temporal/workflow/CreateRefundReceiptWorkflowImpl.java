package com.icligo.quickbooks.temporal.workflow;

import com.icligo.quickbooks.clients.quickbooks.model.Customer;
import com.icligo.quickbooks.clients.quickbooks.model.RefundReceipt;
import com.icligo.quickbooks.model.QuickBooksDocument;
import com.icligo.quickbooks.model.document.ClientInvoiceInfo;
import com.icligo.quickbooks.service.CustomerService;
import com.icligo.quickbooks.temporal.activity.QuickBooksActivities;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

import static com.icligo.quickbooks.temporal.activity.QuickBooksActivitiesImpl.TASK_QUEUE;

/**
 * Workflow implementation: Create RefundReceipt.
 *
 * <p>Steps:
 * <ol>
 *   <li>findOrCreateCustomer</li>
 *   <li>createRefundReceipt</li>
 *   <li>saveDocument</li>
 * </ol>
 */
@WorkflowImpl(taskQueues = TASK_QUEUE)
public class CreateRefundReceiptWorkflowImpl implements CreateRefundReceiptWorkflow {

    private final QuickBooksActivities customerActivity =
            Workflow.newActivityStub(QuickBooksActivities.class, TemporalActivityOptions.CUSTOMER);

    private final QuickBooksActivities documentActivity =
            Workflow.newActivityStub(QuickBooksActivities.class, TemporalActivityOptions.DOCUMENT);

    private final QuickBooksActivities persistActivity =
            Workflow.newActivityStub(QuickBooksActivities.class, TemporalActivityOptions.PERSIST);

    @Override
    public QuickBooksDocument execute(QuickBooksDocument document) {
        Workflow.getLogger(CreateRefundReceiptWorkflowImpl.class)
                .info("CreateRefundReceiptWorkflow started – refundId={}", document.getRefundId());

        // Step 1 – find or create the QB customer
        ClientInvoiceInfo info = document.getClientInvoiceInfo();
        Customer customer = customerActivity.findOrCreateCustomer(info);
        info.setClientId(customer.getId());
        // Recomputed directly rather than read back from QuickBooks (e.g. Customer.Notes) —
        // it's a pure function of name/address/country, so there's nothing to round-trip.
        info.setClientHash(CustomerService.generateCustomerId(info.getName(), info.getAddress(), info.getCountry()));

        // Step 2 – create the refund receipt in QuickBooks
        RefundReceipt refund = documentActivity.createRefundReceipt(
                document,
                customer.getId(),
                customer.getDisplayName()
        );
        document.setInvoice(refund);

        // Step 3 – persist to MongoDB
        QuickBooksDocument saved = persistActivity.saveDocument(document);

        Workflow.getLogger(CreateRefundReceiptWorkflowImpl.class)
                .info("CreateRefundReceiptWorkflow completed – refundId={}", refund.getId());

        return saved;
    }
}
