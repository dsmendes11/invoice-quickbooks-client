package com.icligo.quickbooks.service;

import com.icligo.quickbooks.clients.quickbooks.QuickBooksClient;
import com.icligo.quickbooks.clients.quickbooks.model.Invoice;
import com.icligo.quickbooks.util.QuickBooksConfig;
import com.icligo.quickbooks.util.QuickBooksException;
import org.springframework.stereotype.Service;

@Service
public class InvoiceService extends QuickBooksClient {

    public InvoiceService(QuickBooksConfig config) {
        super(config);
    }

    public Invoice createInvoice(Invoice invoice) throws QuickBooksException {
        return post("/invoice", invoice, Invoice.class);
    }

    public byte[] getInvoicePdf(String id) throws QuickBooksException {
        return getPdf("/invoice/" + id + "/pdf");
    }
}
