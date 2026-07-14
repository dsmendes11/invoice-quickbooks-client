package com.icligo.quickbooks.temporal.workflow;

import com.icligo.quickbooks.clients.quickbooks.model.Customer;
import com.icligo.quickbooks.clients.quickbooks.model.SalesReceipt;
import com.icligo.quickbooks.model.QuickBooksDocument;
import com.icligo.quickbooks.model.document.ClientInvoiceInfo;
import com.icligo.quickbooks.service.CustomerService;
import com.icligo.quickbooks.temporal.activity.QuickBooksActivities;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;

import java.util.List;

import static com.icligo.quickbooks.temporal.activity.QuickBooksActivitiesImpl.TASK_QUEUE;

/**
 * Workflow implementation: Create SalesReceipt.
 *
 * <p>Steps:
 * <ol>
 *   <li>findOrCreateCustomer</li>
 *   <li>createSalesReceipt</li>
 *   <li>saveDocument</li>
 * </ol>
 */
@WorkflowImpl(taskQueues = TASK_QUEUE)
public class CreateSalesReceiptWorkflowImpl implements CreateSalesReceiptWorkflow {

    private final QuickBooksActivities customerActivity =
            Workflow.newActivityStub(QuickBooksActivities.class, TemporalActivityOptions.CUSTOMER);

    private final QuickBooksActivities documentActivity =
            Workflow.newActivityStub(QuickBooksActivities.class, TemporalActivityOptions.DOCUMENT);

    private final QuickBooksActivities persistActivity =
            Workflow.newActivityStub(QuickBooksActivities.class, TemporalActivityOptions.PERSIST);

    @Override
    public List<QuickBooksDocument> execute(QuickBooksDocument document) {
        Workflow.getLogger(CreateSalesReceiptWorkflowImpl.class)
                .info("CreateSalesReceiptWorkflow started – productId={}", document.getProductId());

        // Step 1 – find or create the QB customer
        ClientInvoiceInfo info = document.getClientInvoiceInfo();
        Customer customer = customerActivity.findOrCreateCustomer(info);
        info.setClientId(customer.getId());
        // Recomputed directly rather than read back from QuickBooks (e.g. Customer.Notes) —
        // it's a pure function of name/address/country, so there's nothing to round-trip.
        info.setClientHash(CustomerService.generateCustomerId(info.getName(), info.getAddress(), info.getCountry()));

        // Step 2 – create the sales receipt in QuickBooks
        SalesReceipt receipt = documentActivity.createSalesReceipt(
                document,
                customer.getId(),
                customer.getDisplayName()
        );
        document.setInvoice(receipt);

        // Step 3 – persist to MongoDB
        QuickBooksDocument saved = persistActivity.saveDocument(document);

        Workflow.getLogger(CreateSalesReceiptWorkflowImpl.class)
                .info("CreateSalesReceiptWorkflow completed – receiptId={}", receipt.getId());

        return List.of(saved);
    }
}
