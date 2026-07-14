package com.icligo.quickbooks.service;

import com.icligo.quickbooks.model.ClientServiceValueInfo;
import com.icligo.quickbooks.model.document.ClientInvoiceInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mirrors the invoice-management-system's {@code getServiceIdClients} — the distinct clients
 * with still-open advance-payment value for a {@code serviceId}. There, "advance invoice" (FAA)
 * netted against "advance credit note" (NCA) and grouped by NIF; here, the equivalent is Sales
 * Receipts netted against RefundReceipts/CreditMemos (see {@link ActiveSalesReceiptFinder}, the
 * same accounting {@link SalesReceiptCancellationService}/{@link RefundReceiptAllocationService}
 * use), grouped by {@code clientHash} (this project has no NIF-based customer dedup).
 */
@Service
@RequiredArgsConstructor
public class ClientAggregationService {

    private final ActiveSalesReceiptFinder activeSalesReceiptFinder;

    public List<ClientServiceValueInfo> getServiceIdClients(String serviceId) {
        Map<String, ClientServiceValueInfo> byClientHash = new LinkedHashMap<>();

        for (ActiveSalesReceipt active : activeSalesReceiptFinder.findActive(serviceId)) {
            ClientInvoiceInfo info = active.document().getClientInvoiceInfo();
            String key = (info != null && info.getClientHash() != null) ? info.getClientHash() : "unknown";

            ClientServiceValueInfo client = byClientHash.computeIfAbsent(key, k -> newClient(info));
            client.addValue(active.document().getProductId(), active.availableBalance());
        }

        return new ArrayList<>(byClientHash.values());
    }

    private ClientServiceValueInfo newClient(ClientInvoiceInfo info) {
        ClientServiceValueInfo client = new ClientServiceValueInfo();
        if (info != null) {
            client.setClientHash(info.getClientHash());
            client.setName(info.getName());
            client.setEmail(info.getEmail());
            client.setPhone(info.getPhone());
            client.setAddress(info.getAddress());
            client.setCity(info.getCity());
            client.setZipCode(info.getZipCode());
            client.setCountry(info.getCountry());
            client.setNif(info.getNif());
        }
        return client;
    }
}
