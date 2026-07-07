package com.icligo.quickbooks.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.icligo.quickbooks.clients.quickbooks.QuickBooksClient;
import com.icligo.quickbooks.clients.quickbooks.model.ApiResponse;
import com.icligo.quickbooks.clients.quickbooks.model.RefundReceipt;
import com.icligo.quickbooks.util.QuickBooksConfig;
import com.icligo.quickbooks.util.QuickBooksException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
public class RefundReceiptService extends QuickBooksClient {

    public RefundReceiptService(QuickBooksConfig config) {
        super(config);
    }

    public ApiResponse<RefundReceipt> listRefundReceipts(
            String customerId,
            String originalReceiptId,
            LocalDate fromDate,
            LocalDate toDate,
            String status,
            Integer page,
            Integer limit) throws QuickBooksException {

        StringBuilder endpoint = new StringBuilder("/refund-receipts?");

        if (customerId != null) {
            endpoint.append("customer_id=").append(customerId).append("&");
        }
        if (originalReceiptId != null) {
            endpoint.append("original_receipt_id=").append(originalReceiptId).append("&");
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
                    new TypeReference<ApiResponse<RefundReceipt>>() {}
            );
        } catch (Exception e) {
            throw new QuickBooksException("Failed to list refund receipts", e);
        }
    }

    public RefundReceipt getRefundReceipt(String refundId) throws QuickBooksException {
        return get("/refund-receipts/" + refundId, RefundReceipt.class);
    }

    public RefundReceipt createRefundReceipt(RefundReceipt refund) throws QuickBooksException {
        return post("/refund-receipts", refund, RefundReceipt.class);
    }

    public RefundReceipt updateRefundReceipt(String refundId, RefundReceipt refund) 
            throws QuickBooksException {
        return put("/refund-receipts/" + refundId, refund, RefundReceipt.class);
    }

    public void voidRefundReceipt(String refundId) throws QuickBooksException {
        delete("/refund-receipts/" + refundId);
    }
}
