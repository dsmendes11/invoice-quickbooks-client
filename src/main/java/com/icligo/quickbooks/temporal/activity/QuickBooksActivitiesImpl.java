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
import com.icligo.quickbooks.service.PaymentMethodService;
import com.icligo.quickbooks.service.RefundReceiptService;
import com.icligo.quickbooks.service.SalesReceiptCancellationService;
import com.icligo.quickbooks.service.SalesReceiptService;
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
    private final SalesReceiptCancellationService salesReceiptCancellationService;
    private final QuickBooksDocumentRepository documentRepository;

    /**
     * Deposit account for SalesReceipt/RefundReceipt (QuickBooks' {@code DepositToAccountRef} —
     * not supported on Invoice, which only posts to Accounts Receivable). Configurable per
     * environment; validated against QuickBooks on every SalesReceipt/RefundReceipt creation
     * (see {@link #resolveDepositAccountRef()}) rather than assumed to exist.
     */
    @Value("${quickbooks.deposit.sales-refund-account-name:1030 - Stripe/Paypal}")
    private String depositAccountName;

    // ─────────────────────────────────────────────────────────────────────────
    // Customer
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public Customer findOrCreateCustomer(ClientInvoiceInfo clientInfo) {
        log.info("[Activity] findOrCreateCustomer – name={}, email={}",
                clientInfo.getName(), clientInfo.getEmail());
        try {
            Customer customer = customerService.findOrCreateCustomerByEmailAndName(clientInfo);
            // propagate resolved IDs back into the info so downstream activities can use them
            clientInfo.setClientId(customer.getId());
            clientInfo.setClientHash(customer.getNotes());
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
                    .line(buildLines(document.getItems()))
                    .build();

            if (document.getDescription() != null) {
                invoice.setCustomerMemo(MemoRef.builder()
                        .value(document.getDescription())
                        .build());
            }

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
                    .line(buildLines(document.getItems()))
                    .build();

            if (document.getDescription() != null) {
                receipt.setCustomerMemo(MemoRef.builder()
                        .value(document.getDescription())
                        .build());
            }

            if (document.getPaymentMethod() != null) {
                receipt.setPaymentMethodRef(resolvePaymentMethodRef(document.getPaymentMethod()));
            }

            receipt.setDepositToAccountRef(resolveDepositAccountRef());

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
                    .line(buildLines(document.getItems()))
                    .build();

            if (document.getDescription() != null) {
                refund.setCustomerMemo(MemoRef.builder()
                        .value(document.getDescription())
                        .build());
            }

            if (document.getPaymentMethod() != null) {
                refund.setPaymentMethodRef(resolvePaymentMethodRef(document.getPaymentMethod()));
            }

            refund.setDepositToAccountRef(resolveDepositAccountRef());

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

    private List<Line> buildLines(List<ItemDto> items) {
        if (items == null || items.isEmpty()) {
            return new ArrayList<>();
        }
        List<Line> lines = new ArrayList<>();
        int lineNum = 1;
        for (ItemDto item : items) {
            lines.add(Line.builder()
                    .lineNum(lineNum++)
                    .description(item.getItem())
                    .amount(item.getValue())
                    .detailType("SalesItemLineDetail")
                    .salesItemLineDetail(SalesItemLineDetail.builder()
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
        };
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
     * Resolves the configured deposit account (see {@link #depositAccountName}) to an existing
     * QuickBooks Account. Called unconditionally for every SalesReceipt/RefundReceipt — unlike
     * payment method, this isn't tied to a request field, it's a fixed business rule.
     *
     * @throws QuickBooksException if the configured Account name doesn't exist in QuickBooks.
     */
    private ReferenceType resolveDepositAccountRef() throws QuickBooksException {
        Account account = accountService.findByName(depositAccountName);
        if (account == null) {
            throw new QuickBooksException("QuickBooks Account '" + depositAccountName + "' does not exist in this company.");
        }
        return ReferenceType.builder()
                .value(account.getId())
                .name(account.getName())
                .build();
    }
}
