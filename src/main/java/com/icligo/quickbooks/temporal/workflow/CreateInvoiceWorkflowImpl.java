package com.icligo.quickbooks.temporal.workflow;

import com.icligo.quickbooks.clients.quickbooks.model.Customer;
import com.icligo.quickbooks.clients.quickbooks.model.Invoice;
import com.icligo.quickbooks.enums.ProductTypes;
import com.icligo.quickbooks.model.QuickBooksDocument;
import com.icligo.quickbooks.model.document.ClientInvoiceInfo;
import com.icligo.quickbooks.service.CustomerService;
import com.icligo.quickbooks.temporal.activity.QuickBooksActivities;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

import static com.icligo.quickbooks.temporal.activity.QuickBooksActivitiesImpl.TASK_QUEUE;

/**
 * Workflow implementation: Create Invoice.
 *
 * <p>Steps (each wrapped with independent retry policy):
 * <ol>
 *   <li>findOrCreateCustomer</li>
 *   <li>createInvoice</li>
 *   <li>productType=Reserva → cancelSalesReceiptsForBooking (best-effort, never fails this workflow);
 *       any other productType → createPayment (required — a failure here fails this workflow, same as createInvoice)</li>
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
    public List<QuickBooksDocument> execute(QuickBooksDocument document) {
        Workflow.getLogger(CreateInvoiceWorkflowImpl.class)
                .info("CreateInvoiceWorkflow started – serviceId={}", document.getServiceId());

        // Step 1 – find or create the QB customer
        ClientInvoiceInfo info = document.getClientInvoiceInfo();
        Customer customer = customerActivity.findOrCreateCustomer(info);
        info.setClientId(customer.getId());
        // Recomputed directly rather than read back from QuickBooks (e.g. Customer.Notes) —
        // it's a pure function of name/address/country, so there's nothing to round-trip.
        info.setClientHash(CustomerService.generateCustomerId(info.getName(), info.getAddress(), info.getCountry()));

        // Step 2 – create the invoice in QuickBooks
        Invoice invoice = documentActivity.createInvoice(
                document,
                customer.getId(),
                customer.getDisplayName()
        );
        document.setInvoice(invoice);

        // Step 2b – a booking (Reserva) invoice supersedes any prepaid Sales Receipts for this
        // serviceId; cancel them via CreditMemo (best-effort — failures are emailed to the admin,
        // see SalesReceiptCancellationService, but never fail this workflow). Any other
        // productType means the invoice is paid immediately: record a Payment for its full
        // amount instead — this step is required, a failure here fails the workflow.
        List<QuickBooksDocument> cancelledSalesReceipts = List.of();
        if (ProductTypes.BOOKING.getValue().equalsIgnoreCase(document.getProductType())) {
            cancelledSalesReceipts = documentActivity.cancelSalesReceiptsForBooking(document.getServiceId());
        } else {
            documentActivity.createPayment(document, invoice, customer.getId(), customer.getDisplayName());
        }

        // Step 3 – persist to MongoDB
        QuickBooksDocument saved = persistActivity.saveDocument(document);

        Workflow.getLogger(CreateInvoiceWorkflowImpl.class)
                .info("CreateInvoiceWorkflow completed – invoiceId={}", invoice.getId());

        // The Invoice itself first, followed by any CreditMemos emitted cancelling prior Sales
        // Receipts (e.g. [INV, CDM, CDM]) — empty for a non-booking invoice.
        List<QuickBooksDocument> result = new ArrayList<>();
        result.add(saved);
        result.addAll(cancelledSalesReceipts);
        return result;
    }
}
