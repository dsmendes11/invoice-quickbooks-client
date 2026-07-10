package com.icligo.quickbooks.service;

import com.icligo.quickbooks.clients.quickbooks.QuickBooksClient;
import com.icligo.quickbooks.clients.quickbooks.model.Payment;
import com.icligo.quickbooks.util.QuickBooksConfig;
import com.icligo.quickbooks.util.QuickBooksException;
import org.springframework.stereotype.Service;

@Service
public class PaymentService extends QuickBooksClient {

    public PaymentService(QuickBooksConfig config) {
        super(config);
    }

    public Payment createPayment(Payment payment) throws QuickBooksException {
        return post("/payment", payment, Payment.class);
    }
}
