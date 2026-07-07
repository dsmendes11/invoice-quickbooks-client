package com.icligo.quickbooks.service;

import com.icligo.quickbooks.clients.quickbooks.QuickBooksClient;
import com.icligo.quickbooks.clients.quickbooks.model.RefundReceipt;
import com.icligo.quickbooks.util.QuickBooksConfig;
import com.icligo.quickbooks.util.QuickBooksException;
import org.springframework.stereotype.Service;

@Service
public class RefundReceiptService extends QuickBooksClient {

    public RefundReceiptService(QuickBooksConfig config) {
        super(config);
    }

    public RefundReceipt createRefundReceipt(RefundReceipt refund) throws QuickBooksException {
        return post("/refundreceipt", refund, RefundReceipt.class);
    }
}
