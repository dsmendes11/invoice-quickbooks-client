package com.icligo.quickbooks.service;

import com.icligo.quickbooks.clients.quickbooks.QuickBooksClient;
import com.icligo.quickbooks.clients.quickbooks.model.CreditMemo;
import com.icligo.quickbooks.util.QuickBooksConfig;
import com.icligo.quickbooks.util.QuickBooksException;
import org.springframework.stereotype.Service;

@Service
public class CreditMemoService extends QuickBooksClient {

    public CreditMemoService(QuickBooksConfig config) {
        super(config);
    }

    public CreditMemo createCreditMemo(CreditMemo creditMemo) throws QuickBooksException {
        return post("/creditmemo", creditMemo, CreditMemo.class);
    }
}
