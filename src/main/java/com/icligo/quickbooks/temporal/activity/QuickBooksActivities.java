package com.icligo.quickbooks.temporal.activity;

import com.icligo.quickbooks.clients.quickbooks.model.Customer;
import com.icligo.quickbooks.clients.quickbooks.model.Invoice;
import com.icligo.quickbooks.clients.quickbooks.model.Payment;
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
     * Find an existing customer by an exact QuickBooks DisplayName match (name + dedup hash), or
     * create a new one. Idempotent: the same name/address/country always resolves to the same
     * DisplayName, so repeated calls return the existing customer instead of creating duplicates.
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

    /**
     * Cancels, via CreditMemo, any Sales Receipts already on file for this {@code serviceId} —
     * called when a booking (Reserva) Invoice is created for that service. Best-effort: never
     * throws, so it never fails the Invoice-creation workflow that calls it; failures are logged
     * and emailed to the admin instead (see {@code SalesReceiptCancellationService}).
     */
    @ActivityMethod
    void cancelSalesReceiptsForBooking(String serviceId);

    /**
     * Creates a Payment applying the Invoice's full amount, immediately marking it paid —
     * called when the Invoice's productType is anything other than "Reserva" (bookings instead
     * cancel prior Sales Receipts, see {@link #cancelSalesReceiptsForBooking}). Unlike that
     * cancellation, this is a required step: a failure here fails the Invoice-creation workflow,
     * same as {@link #createInvoice} itself.
     */
    @ActivityMethod
    Payment createPayment(QuickBooksDocument document, Invoice invoice, String customerId, String customerDisplayName);
}
