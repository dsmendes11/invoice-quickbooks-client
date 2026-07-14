package com.icligo.quickbooks.temporal.activity;

import com.icligo.quickbooks.clients.quickbooks.model.*;
import com.icligo.quickbooks.enums.SalesDocumentTypes;
import com.icligo.quickbooks.model.QuickBooksDocument;
import com.icligo.quickbooks.model.document.ClientInvoiceInfo;
import com.icligo.quickbooks.model.document.ItemDto;
import com.icligo.quickbooks.repository.QuickBooksDocumentRepository;
import com.icligo.quickbooks.service.AccountService;
import com.icligo.quickbooks.service.CustomerService;
import com.icligo.quickbooks.service.InvoiceService;
import com.icligo.quickbooks.service.ItemService;
import com.icligo.quickbooks.service.PaymentMethodService;
import com.icligo.quickbooks.service.PaymentService;
import com.icligo.quickbooks.service.RefundReceiptService;
import com.icligo.quickbooks.service.SalesReceiptCancellationService;
import com.icligo.quickbooks.service.SalesReceiptService;
import com.icligo.quickbooks.service.authentication.QuickBooksAlertService;
import com.icligo.quickbooks.util.ItemLocatorUtils;
import com.icligo.quickbooks.util.PaymentMethodUtils;
import com.icligo.quickbooks.util.QuickBooksException;
import io.temporal.spring.boot.ActivityImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Temporal Activity implementation.
 *
 * <p>All heavy lifting is delegated to the existing Spring services — no logic is duplicated.
 * Temporal wraps each method call with its own retry / timeout policy defined in the workflows.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ActivityImpl(taskQueues = QuickBooksActivitiesImpl.TASK_QUEUE)
public class QuickBooksActivitiesImpl implements QuickBooksActivities {

    public static final String TASK_QUEUE = "quickbooks-task-queue";

    private final CustomerService customerService;
    private final InvoiceService invoiceService;
    private final SalesReceiptService salesReceiptService;
    private final RefundReceiptService refundReceiptService;
    private final PaymentMethodService paymentMethodService;
    private final AccountService accountService;
    private final ItemService itemService;
    private final SalesReceiptCancellationService salesReceiptCancellationService;
    private final PaymentService paymentService;
    private final QuickBooksDocumentRepository documentRepository;
    private final QuickBooksAlertService alertService;

    /**
     * Deposit account for SalesReceipt/RefundReceipt (QuickBooks' {@code DepositToAccountRef} —
     * not supported on Invoice, which only posts to Accounts Receivable). Configurable per
     * environment; validated against QuickBooks on every SalesReceipt/RefundReceipt creation
     * (see {@link #resolveDepositAccountRef}) rather than assumed to exist.
     */
    @Value("${quickbooks.deposit.sales-refund-account-name:1030 - Stripe/Paypal}")
    private String salesRefundDepositAccountName;

    /**
     * Deposit account for the Payment recorded against a non-{@code "Reserva"} Invoice (see
     * {@link #createPayment}) — deliberately a separate, independently-configurable account from
     * {@link #salesRefundDepositAccountName}, since invoice payments and Sales Receipts/Refund
     * Receipts settle into different real-world accounts.
     */
    @Value("${quickbooks.deposit.payment-account-name:1010 - BPI}")
    private String paymentDepositAccountName;

    // ─────────────────────────────────────────────────────────────────────────
    // Customer
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public Customer findOrCreateCustomer(ClientInvoiceInfo clientInfo) {
        log.info("[Activity] findOrCreateCustomer – name={}, email={}",
                clientInfo.getName(), clientInfo.getEmail());
        try {
            // Note: mutating `clientInfo` here has no effect on the workflow's own copy of the
            // document — Temporal (de)serializes activity parameters independently, so the
            // workflow sets clientId/clientHash itself from this method's return value instead.
            Customer customer = customerService.findOrCreateCustomerByEmailAndName(clientInfo);
            log.info("[Activity] findOrCreateCustomer – resolved customerId={}", customer.getId());
            return customer;
        } catch (QuickBooksException e) {
            log.error("[Activity] findOrCreateCustomer failed: {}", e.getMessage());
            // Throwing a runtime exception causes Temporal to retry the activity
            throw new RuntimeException("findOrCreateCustomer failed: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Invoice
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public Invoice createInvoice(QuickBooksDocument document,
                                 String customerId,
                                 String customerDisplayName) {
        log.info("[Activity] createInvoice – docNumber={}, customerId={}",
                buildDocNumber(document), customerId);
        try {
            Invoice invoice = Invoice.builder()
                    .customerRef(ReferenceType.builder()
                            .value(customerId)
                            .name(customerDisplayName)
                            .build())
                    .docNumber(buildDocNumber(document))
                    .txnDate(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE))
                    .dueDate(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE))
                    .line(buildLines(document.getDescription(), document.getItems()))
                    .build();

            invoice.setPrivateNote(ItemLocatorUtils.joinLocators(document.getItems()));

            Invoice created = invoiceService.createInvoice(invoice);
            log.info("[Activity] createInvoice – created id={}", created.getId());
            return created;
        } catch (QuickBooksException e) {
            log.error("[Activity] createInvoice failed: {}", e.getMessage());
            throw new RuntimeException("createInvoice failed: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SalesReceipt
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public SalesReceipt createSalesReceipt(QuickBooksDocument document,
                                           String customerId,
                                           String customerDisplayName) {
        log.info("[Activity] createSalesReceipt – docNumber={}, customerId={}",
                buildDocNumber(document), customerId);
        try {
            SalesReceipt receipt = SalesReceipt.builder()
                    .customerRef(ReferenceType.builder()
                            .value(customerId)
                            .name(customerDisplayName)
                            .build())
                    .docNumber(buildDocNumber(document))
                    .txnDate(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE))
                    .line(buildLines(document.getDescription(), document.getItems()))
                    .build();

            if (document.getDescription() != null) {
                receipt.setCustomerMemo(MemoRef.builder()
                        .value(document.getDescription())
                        .build());
            }

            if (document.getPaymentMethod() != null) {
                receipt.setPaymentMethodRef(resolvePaymentMethodRef(document.getPaymentMethod()));
            }

            receipt.setDepositToAccountRef(resolveDepositAccountRef(salesRefundDepositAccountName));
            receipt.setPaymentRefNum(ItemLocatorUtils.joinLocators(document.getItems()));

            SalesReceipt created = salesReceiptService.createSalesReceipt(receipt);
            log.info("[Activity] createSalesReceipt – created id={}", created.getId());
            return created;
        } catch (QuickBooksException e) {
            log.error("[Activity] createSalesReceipt failed: {}", e.getMessage());
            throw new RuntimeException("createSalesReceipt failed: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RefundReceipt
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public RefundReceipt createRefundReceipt(QuickBooksDocument document,
                                             String customerId,
                                             String customerDisplayName) {
        log.info("[Activity] createRefundReceipt – docNumber={}, customerId={}",
                buildDocNumber(document), customerId);
        try {
            RefundReceipt refund = RefundReceipt.builder()
                    .customerRef(ReferenceType.builder()
                            .value(customerId)
                            .name(customerDisplayName)
                            .build())
                    .docNumber(buildDocNumber(document))
                    .txnDate(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE))
                    .line(buildLines(document.getDescription(), document.getItems()))
                    .build();

            if (document.getDescription() != null) {
                refund.setCustomerMemo(MemoRef.builder()
                        .value(document.getDescription())
                        .build());
            }

            if (document.getPaymentMethod() != null) {
                refund.setPaymentMethodRef(resolvePaymentMethodRef(document.getPaymentMethod()));
            }

            refund.setDepositToAccountRef(resolveDepositAccountRef(salesRefundDepositAccountName));
            refund.setPrivateNote(ItemLocatorUtils.joinLocators(document.getItems()));

            RefundReceipt created = refundReceiptService.createRefundReceipt(refund);
            log.info("[Activity] createRefundReceipt – created id={}", created.getId());
            return created;
        } catch (QuickBooksException e) {
            log.error("[Activity] createRefundReceipt failed: {}", e.getMessage());
            throw new RuntimeException("createRefundReceipt failed: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SalesReceipt cancellation (booking/Reserva invoices)
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void cancelSalesReceiptsForBooking(String serviceId) {
        log.info("[Activity] cancelSalesReceiptsForBooking – serviceId={}", serviceId);
        // Best-effort by design (see SalesReceiptCancellationService) — never throws, so this
        // never retries and never fails the Invoice-creation workflow that calls it.
        salesReceiptCancellationService.cancelSalesReceiptsForServiceId(serviceId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Payment (non-booking invoices, paid immediately)
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public Payment createPayment(QuickBooksDocument document, Invoice invoice, String customerId, String customerDisplayName) {
        log.info("[Activity] createPayment – invoiceId={}, customerId={}", invoice.getId(), customerId);
        try {
            Payment payment = Payment.builder()
                    .customerRef(ReferenceType.builder()
                            .value(customerId)
                            .name(customerDisplayName)
                            .build())
                    .txnDate(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE))
                    .totalAmt(invoice.getTotalAmt())
                    .paymentRefNum(invoice.getDocNumber())
                    .line(List.of(Payment.PaymentLine.builder()
                            .amount(invoice.getTotalAmt())
                            .linkedTxn(List.of(Payment.LinkedTxn.builder()
                                    .txnId(invoice.getId())
                                    .txnType("Invoice")
                                    .build()))
                            .build()))
                    .build();

            if (document.getPaymentMethod() != null) {
                payment.setPaymentMethodRef(resolvePaymentMethodRef(document.getPaymentMethod()));
            }

            payment.setDepositToAccountRef(resolveDepositAccountRef(paymentDepositAccountName));

            Payment created = paymentService.createPayment(payment);
            log.info("[Activity] createPayment – created id={}", created.getId());
            return created;
        } catch (QuickBooksException e) {
            log.error("[Activity] createPayment failed: {}", e.getMessage());
            // Not best-effort — this still fails the workflow (see class javadoc on
            // CreateInvoiceWorkflowImpl) — but the admin gets an email too, since the Invoice
            // itself was already created and is left unpaid until the Payment is added manually.
            alertService.sendPaymentCreationFailedAlert(document.getServiceId(), invoice.getDocNumber(), e.getMessage());
            throw new RuntimeException("createPayment failed: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Persist to MongoDB
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public QuickBooksDocument saveDocument(QuickBooksDocument document) {
        log.info("[Activity] saveDocument – type={}", document.getType());
        try {
            QuickBooksDocument saved = documentRepository.save(document);
            log.info("[Activity] saveDocument – saved id={}", saved.getId());
            return saved;
        } catch (Exception e) {
            log.error("[Activity] saveDocument failed: {}", e.getMessage());
            throw new RuntimeException("saveDocument failed: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers (mirror DocumentService logic — kept in sync)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * When {@code documentDescription} is present, it replaces the item's own description on
     * every line (not appended) — falls back to {@code item.getItem()} only when the document
     * has no description at all. {@code item.getItem()} itself always maps to the line's
     * QuickBooks {@code ItemRef} (see {@link #resolveItemRef(String)}), regardless of what the
     * line's Description text ends up being.
     */
    private List<Line> buildLines(String documentDescription, List<ItemDto> items) throws QuickBooksException {
        if (items == null || items.isEmpty()) {
            return new ArrayList<>();
        }
        boolean hasDocumentDescription = documentDescription != null && !documentDescription.isBlank();
        List<Line> lines = new ArrayList<>();
        int lineNum = 1;
        for (ItemDto item : items) {
            lines.add(Line.builder()
                    .lineNum(lineNum++)
                    .description(hasDocumentDescription ? documentDescription : item.getItem())
                    .amount(item.getValue())
                    .detailType("SalesItemLineDetail")
                    .salesItemLineDetail(SalesItemLineDetail.builder()
                            .itemRef(resolveItemRef(item.getItem()))
                            .qty(1)
                            .unitPrice(item.getValue())
                            .taxCodeRef(ReferenceType.builder().value("NON").build())
                            .build())
                    .build());
        }
        return lines;
    }

    /**
     * Note: these DocNumber prefixes (INV/SRC/RRC) are independent of {@link SalesDocumentTypes}'
     * request-facing type codes (INV/SRT/RRT) — SALES_RECEIPT and REFUND_RECEIPT happen to use
     * different abbreviations here than in the request {@code type} field. Don't conflate them.
     */
    private String buildDocNumber(QuickBooksDocument document) {
        SalesDocumentTypes type = SalesDocumentTypes.getByValue(document.getType().toUpperCase());
        if (type == null) {
            throw new IllegalArgumentException("Unsupported document type: " + document.getType());
        }
        return switch (type) {
            case INVOICE -> "INV" + document.getServiceId();
            case SALES_RECEIPT -> "SRC" + document.getProductId();
            case REFUND_RECEIPT -> "RRC" + document.getProductId() + "_rfd" + document.getRefundId();
            // Not actually reached today — CreditMemos are built directly in
            // SalesReceiptCancellationService, not through createInvoice/createSalesReceipt/
            // createRefundReceipt — kept here only so this switch stays exhaustive, using the
            // same "NCC" prefix convention as that class.
            case CREDIT_MEMO -> "NCC" + document.getProductId();
        };
    }

    /**
     * Resolves {@code item.item} to an existing QuickBooks Item, for the line's {@code ItemRef}.
     *
     * @throws QuickBooksException if no QuickBooks Item exists with that name — callers must not
     *         fall back to an unresolved reference, since QuickBooks would reject the line (or
     *         apply the wrong item) instead.
     */
    private ReferenceType resolveItemRef(String itemName) throws QuickBooksException {
        Item item = itemService.findByName(itemName);
        if (item == null) {
            throw new QuickBooksException("QuickBooks Item '" + itemName + "' does not exist in this company.");
        }
        return ReferenceType.builder()
                .value(item.getId())
                .name(item.getName())
                .build();
    }

    /**
     * Resolves the requested payment method code to an existing QuickBooks PaymentMethod.
     * Only called from a {@code paymentMethod != null} guard — {@code code} is never null here.
     *
     * @throws QuickBooksException if the mapped PaymentMethod name doesn't exist in QuickBooks —
     *         callers must not fall back to an unresolved reference, since QuickBooks would
     *         reject the transaction (or worse, silently apply the account default) instead.
     */
    private ReferenceType resolvePaymentMethodRef(Integer code) throws QuickBooksException {
        String name = PaymentMethodUtils.mapPaymentMethod(code);
        PaymentMethod paymentMethod = paymentMethodService.findByName(name);
        if (paymentMethod == null) {
            throw new QuickBooksException("QuickBooks PaymentMethod '" + name + "' does not exist in this company.");
        }
        return ReferenceType.builder()
                .value(paymentMethod.getId())
                .name(paymentMethod.getName())
                .build();
    }

    /**
     * Resolves the given deposit account name to an existing QuickBooks Account. Called
     * unconditionally for every SalesReceipt/RefundReceipt/Payment — unlike payment method,
     * this isn't tied to a request field, it's a fixed business rule (with a different account
     * per document type, see {@link #salesRefundDepositAccountName}/{@link #paymentDepositAccountName}).
     *
     * @throws QuickBooksException if the configured Account name doesn't exist in QuickBooks.
     */
    private ReferenceType resolveDepositAccountRef(String accountName) throws QuickBooksException {
        Account account = accountService.findByName(accountName);
        if (account == null) {
            throw new QuickBooksException("QuickBooks Account '" + accountName + "' does not exist in this company.");
        }
        return ReferenceType.builder()
                .value(account.getId())
                .name(account.getName())
                .build();
    }
}
