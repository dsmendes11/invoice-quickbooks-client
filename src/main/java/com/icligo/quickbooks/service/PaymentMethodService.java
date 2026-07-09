package com.icligo.quickbooks.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.icligo.quickbooks.clients.quickbooks.QuickBooksClient;
import com.icligo.quickbooks.clients.quickbooks.model.PaymentMethod;
import com.icligo.quickbooks.util.QuickBooksConfig;
import com.icligo.quickbooks.util.QuickBooksException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Service
public class PaymentMethodService extends QuickBooksClient {

    public PaymentMethodService(QuickBooksConfig config) {
        super(config);
    }

    /**
     * Looks up a PaymentMethod by exact name (e.g. "Credit Card"). Returns {@code null} if
     * QuickBooks has no PaymentMethod with that name — callers must decide whether that's
     * an error rather than silently falling back to an unresolved reference.
     */
    public PaymentMethod findByName(String name) throws QuickBooksException {
        try {
            String query = "SELECT * FROM PaymentMethod WHERE Name = '" + name.replace("'", "\\'") + "'";
            String endpoint = "/query?query=" + java.net.URLEncoder.encode(query, StandardCharsets.UTF_8);
            String response = get(endpoint, String.class);

            JsonNode root = getObjectMapper().readTree(response);
            JsonNode queryResponse = root.get("QueryResponse");

            if (queryResponse != null && queryResponse.has("PaymentMethod")) {
                JsonNode array = queryResponse.get("PaymentMethod");
                if (array.isArray() && !array.isEmpty()) {
                    return getObjectMapper().treeToValue(array.get(0), PaymentMethod.class);
                }
            }

            return null;
        } catch (Exception e) {
            throw new QuickBooksException("Failed to query payment method '" + name + "': " + e.getMessage(), e);
        }
    }
}
