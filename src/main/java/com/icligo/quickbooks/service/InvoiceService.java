package com.icligo.quickbooks.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.icligo.quickbooks.clients.quickbooks.QuickBooksClient;
import com.icligo.quickbooks.clients.quickbooks.model.ApiResponse;
import com.icligo.quickbooks.clients.quickbooks.model.Invoice;
import com.icligo.quickbooks.util.QuickBooksConfig;
import com.icligo.quickbooks.util.QuickBooksException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@Service
public class InvoiceService extends QuickBooksClient {
    
    public InvoiceService(QuickBooksConfig config) {
        super(config);
    }
    
    /**
     * List all invoices with optional filters
     */
    public ApiResponse<Invoice> listInvoices(String customerId, String status, 
                                               LocalDate fromDate, LocalDate toDate,
                                               Integer page, Integer limit) 
            throws QuickBooksException {
        StringBuilder endpoint = new StringBuilder("/invoices?");
        
        if (customerId != null) {
            endpoint.append("customer_id=").append(customerId).append("&");
        }
        if (status != null) {
            endpoint.append("status=").append(status).append("&");
        }
        if (fromDate != null) {
            endpoint.append("from_date=").append(fromDate).append("&");
        }
        if (toDate != null) {
            endpoint.append("to_date=").append(toDate).append("&");
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
                    new TypeReference<ApiResponse<Invoice>>() {}
            );
        } catch (Exception e) {
            throw new QuickBooksException("Failed to list invoices", e);
        }
    }
    
    /**
     * Get a specific invoice by ID
     */
    public Invoice getInvoice(String invoiceId) throws QuickBooksException {
        return get("/invoices/" + invoiceId, Invoice.class);
    }
    
    /**
     * Create a new invoice
     */
    public Invoice createInvoice(Invoice invoice) throws QuickBooksException {
        return post("/invoices", invoice, Invoice.class);
    }
    
    /**
     * Update invoice status
     */
    public Invoice updateInvoiceStatus(String invoiceId, String status) throws QuickBooksException {
        Map<String, String> body = new HashMap<>();
        body.put("status", status);
        return put("/invoices/" + invoiceId + "/status", body, Invoice.class);
    }
}
