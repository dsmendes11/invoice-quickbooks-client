package com.icligo.quickbooks.service;

import com.icligo.quickbooks.clients.quickbooks.model.*;
import com.icligo.quickbooks.model.QuickBooksDocument;
import com.icligo.quickbooks.model.document.ClientInvoiceInfo;
import com.icligo.quickbooks.model.document.ItemDto;
import com.icligo.quickbooks.repository.QuickBooksDocumentRepository;
import com.icligo.quickbooks.util.QuickBooksException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final QuickBooksDocumentRepository repository;
    private final CustomerService customerService;
    private final InvoiceService invoiceService;
    private final RefundReceiptService refundReceiptService;
    private final SalesReceiptService salesReceiptService;

    public QuickBooksDocument create(QuickBooksDocument document) {
        try {
            if(document.getClientInvoiceInfo() == null) {
                log.warn("ClientInvoiceInfo is required");
                throw new IllegalArgumentException("ClientInvoiceInfo is required");
            }

            Customer customer = findOrCreateCustomer(document.getClientInvoiceInfo());

            if (document.getType() == null) {
                log.warn("Document type is required");
                throw new IllegalArgumentException("Document type is required");
            }

            switch (document.getType().toUpperCase()) {
                case "INVOICE":
                    createInvoice(document, customer);
                    return savePrimaveraDocument(document);

                case "SALES_RECEIPT":
                    createSalesReceipt(document, customer);
                    return savePrimaveraDocument(document);

                case "REFUND_RECEIPT":
                    createRefundReceipt(document, customer);
                    return savePrimaveraDocument(document);

                default:
                    log.info("Document type '{}' does not map", document.getType());
                    break;
            }

        } catch (Exception e) {
            log.error("Error creating Document: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }

        return null;
    }

    private Customer findOrCreateCustomer(ClientInvoiceInfo clientInvoiceInfo) throws QuickBooksException {
        Customer customer = customerService.findOrCreateCustomerByEmailAndName(clientInvoiceInfo);
        clientInvoiceInfo.setClientId(customer.getId());
        clientInvoiceInfo.setClientHash(customer.getNotes());
        return customer;
    }

    private void createInvoice(QuickBooksDocument document, Customer customer) {
        try {
            Invoice invoice = Invoice.builder()
                    .customerRef(ReferenceType.builder()
                            .value(customer.getId())
                            .name(customer.getDisplayName())
                            .build())
                    .docNumber(generateInvoiceNumber(document))
                    .txnDate(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE))
                    .dueDate(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE))
                    .line(convertToInvoiceItems(document.getItems()))
                    .build();

            if (document.getDescription() != null) {
                invoice.setCustomerMemo(MemoRef.builder()
                        .value(document.getDescription())
                        .build());
            }

            Invoice created = invoiceService.createInvoice(invoice);
            document.setInvoice(created);

            log.info("Created Invoice: {}", created.getInvoiceNumber());

        } catch (QuickBooksException e) {
            log.error("Failed to create Invoice: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create Invoice", e);
        }
    }

    private void createSalesReceipt(QuickBooksDocument document, Customer customer) {
        try {
            SalesReceipt receipt = SalesReceipt.builder()
                    .customerRef(ReferenceType.builder()
                            .value(customer.getId())
                            .name(customer.getDisplayName())
                            .build())
                    .docNumber(generateInvoiceNumber(document))
                    .txnDate(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE))
                    .line(convertToInvoiceItems(document.getItems()))
                    .build();

            if (document.getDescription() != null) {
                receipt.setCustomerMemo(MemoRef.builder()
                        .value(document.getDescription())
                        .build());
            }

            if (document.getPaymentMethod() != null) {
                String paymentMethod = mapPaymentMethod(document.getPaymentMethod());
                receipt.setPaymentMethodRef(ReferenceType.builder()
                        .value(document.getPaymentMethod().toString())
                        .name(paymentMethod)
                        .build());
            }

            SalesReceipt created = salesReceiptService.createSalesReceipt(receipt);
            document.setInvoice(created);

            log.info("Created SalesReceipt: {})",
                    created.getReceiptNumber());

        } catch (QuickBooksException e) {
            log.error("Failed to create SalesReceipt: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create SalesReceipt", e);
        }
    }

    private void createRefundReceipt(QuickBooksDocument document, Customer customer) {
        try {
            RefundReceipt refund = RefundReceipt.builder()
                    .customerRef(ReferenceType.builder()
                            .value(customer.getId())
                            .name(customer.getDisplayName())
                            .build())
                    .docNumber(generateInvoiceNumber(document))
                    .txnDate(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE))
                    .line(convertToInvoiceItems(document.getItems()))
                    .build();

            if (document.getDescription() != null) {
                refund.setCustomerMemo(MemoRef.builder()
                        .value(document.getDescription())
                        .build());
            }

            if (document.getPaymentMethod() != null) {
                String paymentMethod = mapPaymentMethod(document.getPaymentMethod());
                refund.setPaymentMethodRef(ReferenceType.builder()
                        .value(document.getPaymentMethod().toString())
                        .name(paymentMethod)
                        .build());
            }

            RefundReceipt created = refundReceiptService.createRefundReceipt(refund);
            document.setInvoice(created);

            log.info("Created RefundReceipt: {})", created.getRefundNumber());

        } catch (QuickBooksException e) {
            log.error("Failed to create RefundReceipt: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create RefundReceipt", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper Methods - Item Conversion
    // ─────────────────────────────────────────────────────────────────────────

    private List<Line> convertToInvoiceItems(List<ItemDto> items) {
        if (items == null || items.isEmpty()) {
            return new ArrayList<>();
        }

        List<Line> lines = new ArrayList<>();
        int lineNum = 1;

        for (ItemDto item : items) {
            Line line = Line.builder()
                    .lineNum(lineNum++)
                    .description(item.getItem())
                    .amount(item.getValue())
                    .detailType("SalesItemLineDetail")
                    .salesItemLineDetail(SalesItemLineDetail.builder()
                            .qty(1)
                            .unitPrice(item.getValue())
                            .taxCodeRef(ReferenceType.builder()
                                    .value("NON")
                                    .build())
                            .build())
                    .build();

            lines.add(line);
        }

        return lines;
    }

    private String generateInvoiceNumber(QuickBooksDocument document) {
        String invoiceNumber = "";
        switch (document.getType().toUpperCase()) {
            case "INVOICE":
                invoiceNumber += "INV" + document.getServiceId();
                break;

            case "SALES_RECEIPT":
                invoiceNumber += "SRC" + document.getProductId();
                break;

            case "REFUND_RECEIPT":
                invoiceNumber += "RRC" + document.getProductId() + "_rfd" + document.getRefundId();
                break;

            default:
                throw new IllegalArgumentException("Document type '" + document.getType() + "' is not supported");
        }

        return invoiceNumber;
    }

    private String mapPaymentMethod(Integer paymentMethodCode) {
        if (paymentMethodCode != null) {
            return switch (paymentMethodCode) {
                case 1 -> "credit_card";
                case 2 -> "debit_card";
                default -> "other";
            };
        }
        return "credit_card";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MongoDB Save
    // ─────────────────────────────────────────────────────────────────────────

    private QuickBooksDocument savePrimaveraDocument(QuickBooksDocument document) {
        QuickBooksDocument saved = repository.save(document);
        log.info("Saved Document to MongoDB: type={}", saved.getType());
        return saved;
    }
}
