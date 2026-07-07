package com.icligo.quickbooks.service;

import com.icligo.quickbooks.clients.quickbooks.QuickBooksClient;
import com.icligo.quickbooks.clients.quickbooks.model.SalesReceipt;
import com.icligo.quickbooks.util.QuickBooksConfig;
import com.icligo.quickbooks.util.QuickBooksException;
import org.springframework.stereotype.Service;

@Service
public class SalesReceiptService extends QuickBooksClient {

    public SalesReceiptService(QuickBooksConfig config) {
        super(config);
    }

    public SalesReceipt createSalesReceipt(SalesReceipt receipt) throws QuickBooksException {
        return post("/salesreceipt", receipt, SalesReceipt.class);
    }
}
