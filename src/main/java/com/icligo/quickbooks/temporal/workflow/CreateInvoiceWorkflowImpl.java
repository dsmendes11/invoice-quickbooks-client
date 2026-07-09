package com.icligo.quickbooks.temporal.workflow;

import com.icligo.quickbooks.clients.quickbooks.model.Customer;
import com.icligo.quickbooks.clients.quickbooks.model.Invoice;
import com.icligo.quickbooks.enums.ProductTypes;
import com.icligo.quickbooks.model.QuickBooksDocument;
import com.icligo.quickbooks.temporal.activity.QuickBooksActivities;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import lombok.extern.slf4j.Slf4j;

import static com.icligo.quickbooks.temporal.activity.QuickBooksActivitiesImpl.TASK_QUEUE;

/**
 * Workflow implementation: Create Invoice.
 *
 * <p>Steps (each wrapped with independent retry policy):
 * <ol>
 *   <li>findOrCreateCustomer</li>
 *   <li>createInvoice</li>
 *   <li>cancelSalesReceiptsForBooking — only when productType=Reserva; best-effort, never fails this workflow</li>
 *   <li>saveDocument</li>
 * </ol>
 */
@WorkflowImpl(taskQueues = TASK_QUEUE)
public class CreateInvoiceWorkflowImpl implements CreateInvoiceWorkflow {

    private final QuickBooksActivities customerActivity =
            Workflow.newActivityStub(QuickBooksActivities.class, TemporalActivityOptions.CUSTOMER);

    private final QuickBooksActivities documentActivity =
            Workflow.newActivityStub(QuickBooksActivities.class, TemporalActivityOptions.DOCUMENT);

    private final QuickBooksActivities persistActivity =
            Workflow.newActivityStub(QuickBooksActivities.class, TemporalActivityOptions.PERSIST);

    @Override
    public QuickBooksDocument execute(QuickBooksDocument document) {
        Workflow.getLogger(CreateInvoiceWorkflowImpl.class)
                .info("CreateInvoiceWorkflow started – serviceId={}", document.getServiceId());

        // Step 1 – find or create the QB customer
        Customer customer = customerActivity.findOrCreateCustomer(document.getClientInvoiceInfo());
        document.getClientInvoiceInfo().setClientId(customer.getId());
        document.getClientInvoiceInfo().setClientHash(customer.getNotes());

        // Step 2 – create the invoice in QuickBooks
        Invoice invoice = documentActivity.createInvoice(
                document,
                customer.getId(),
                customer.getDisplayName()
        );
        document.setInvoice(invoice);

        // Step 2b – a booking (Reserva) invoice supersedes any prepaid Sales Receipts for this
        // serviceId; cancel them via CreditMemo. Best-effort — failures are emailed to the admin
        // (see SalesReceiptCancellationService) but never fail this workflow.
        if (ProductTypes.BOOKING.getValue().equalsIgnoreCase(document.getProductType())) {
            documentActivity.cancelSalesReceiptsForBooking(document.getServiceId());
        }

        // Step 3 – persist to MongoDB
        QuickBooksDocument saved = persistActivity.saveDocument(document);

        Workflow.getLogger(CreateInvoiceWorkflowImpl.class)
                .info("CreateInvoiceWorkflow completed – invoiceId={}", invoice.getId());

        return saved;
    }
}
