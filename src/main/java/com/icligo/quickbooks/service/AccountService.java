package com.icligo.quickbooks.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.icligo.quickbooks.clients.quickbooks.QuickBooksClient;
import com.icligo.quickbooks.clients.quickbooks.model.Account;
import com.icligo.quickbooks.util.QuickBooksConfig;
import com.icligo.quickbooks.util.QuickBooksException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Service
public class AccountService extends QuickBooksClient {

    public AccountService(QuickBooksConfig config) {
        super(config);
    }

    /**
     * Looks up an Account by exact name (e.g. "1030 - Stripe/Paypal"). Returns {@code null} if
     * QuickBooks has no Account with that name — callers must decide whether that's an error
     * rather than silently falling back to an unresolved reference.
     */
    public Account findByName(String name) throws QuickBooksException {
        try {
            String query = "SELECT * FROM Account WHERE Name = '" + name.replace("'", "\\'") + "'";
            String endpoint = "/query?query=" + java.net.URLEncoder.encode(query, StandardCharsets.UTF_8);
            String response = get(endpoint, String.class);

            JsonNode root = getObjectMapper().readTree(response);
            JsonNode queryResponse = root.get("QueryResponse");

            if (queryResponse != null && queryResponse.has("Account")) {
                JsonNode array = queryResponse.get("Account");
                if (array.isArray() && !array.isEmpty()) {
                    return getObjectMapper().treeToValue(array.get(0), Account.class);
                }
            }

            return null;
        } catch (Exception e) {
            throw new QuickBooksException("Failed to query account '" + name + "': " + e.getMessage(), e);
        }
    }
}
