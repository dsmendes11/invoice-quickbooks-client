package com.icligo.quickbooks.temporal.activity;

import com.icligo.quickbooks.clients.quickbooks.model.Customer;
import com.icligo.quickbooks.clients.quickbooks.model.Invoice;
import com.icligo.quickbooks.clients.quickbooks.model.RefundReceipt;
import com.icligo.quickbooks.clients.quickbooks.model.SalesReceipt;
import com.icligo.quickbooks.model.QuickBooksDocument;
import com.icligo.quickbooks.model.document.ClientInvoiceInfo;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

/**
 * Temporal Activities for QuickBooks operations.
 *
 * <p>Each method maps to one atomic, side-effectful step.
 * Temporal retries each activity independently on failure.
 */
@ActivityInterface
public interface QuickBooksActivities {

    /**
     * Find an existing customer by email + display name, or create a new one.
     * Idempotent: if the customer already exists in MongoDB or QuickBooks it is returned as-is.
     */
    @ActivityMethod
    Customer findOrCreateCustomer(ClientInvoiceInfo clientInfo);

    /**
     * Create an Invoice in QuickBooks for the given customer.
     */
    @ActivityMethod
    Invoice createInvoice(QuickBooksDocument document, String customerId, String customerDisplayName);

    /**
     * Create a SalesReceipt in QuickBooks for the given customer.
     */
    @ActivityMethod
    SalesReceipt createSalesReceipt(QuickBooksDocument document, String customerId, String customerDisplayName);

    /**
     * Create a RefundReceipt in QuickBooks for the given customer.
     */
    @ActivityMethod
    RefundReceipt createRefundReceipt(QuickBooksDocument document, String customerId, String customerDisplayName);

    /**
     * Persist the final QuickBooksDocument (with the QB response embedded) to MongoDB.
     */
    @ActivityMethod
    QuickBooksDocument saveDocument(QuickBooksDocument document);
}
