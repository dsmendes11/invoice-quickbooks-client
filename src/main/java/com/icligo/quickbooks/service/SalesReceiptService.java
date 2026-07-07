package com.icligo.quickbooks.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.icligo.quickbooks.clients.quickbooks.QuickBooksClient;
import com.icligo.quickbooks.clients.quickbooks.model.ApiResponse;
import com.icligo.quickbooks.clients.quickbooks.model.SalesReceipt;
import com.icligo.quickbooks.util.QuickBooksConfig;
import com.icligo.quickbooks.util.QuickBooksException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
public class SalesReceiptService extends QuickBooksClient {

    public SalesReceiptService(QuickBooksConfig config) {
        super(config);
    }

    public ApiResponse<SalesReceipt> listSalesReceipts(
            String customerId,
            LocalDate fromDate,
            LocalDate toDate,
            String status,
            Integer page,
            Integer limit) throws QuickBooksException {

        StringBuilder endpoint = new StringBuilder("/sales-receipts?");

        if (customerId != null) {
            endpoint.append("customer_id=").append(customerId).append("&");
        }
        if (fromDate != null) {
            endpoint.append("from_date=").append(fromDate).append("&");
        }
        if (toDate != null) {
            endpoint.append("to_date=").append(toDate).append("&");
        }
        if (status != null) {
            endpoint.append("status=").append(status).append("&");
        }
        if (page != null) {
            endpoint.append("page=").append(page).append("&");
        }
        if (limit != null) {
            endpoint.append("limit=").append(limit).append("&");
        }

        try {
            String response = get(endpoint.toString(), String.class);
            return getObjectMapper().readValue(
                    response,
                    new TypeReference<ApiResponse<SalesReceipt>>() {}
            );
        } catch (Exception e) {
            throw new QuickBooksException("Failed to list sales receipts", e);
        }
    }

    public SalesReceipt getSalesReceipt(String receiptId) throws QuickBooksException {
        return get("/sales-receipts/" + receiptId, SalesReceipt.class);
    }

    public SalesReceipt createSalesReceipt(SalesReceipt receipt) throws QuickBooksException {
        return post("/sales-receipts", receipt, SalesReceipt.class);
    }

    public SalesReceipt updateSalesReceipt(String receiptId, SalesReceipt receipt) 
            throws QuickBooksException {
        return put("/sales-receipts/" + receiptId, receipt, SalesReceipt.class);
    }

    public void voidSalesReceipt(String receiptId) throws QuickBooksException {
        delete("/sales-receipts/" + receiptId);
    }
}
