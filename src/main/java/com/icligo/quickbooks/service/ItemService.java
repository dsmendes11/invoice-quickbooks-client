package com.icligo.quickbooks.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.icligo.quickbooks.clients.quickbooks.QuickBooksClient;
import com.icligo.quickbooks.clients.quickbooks.model.Item;
import com.icligo.quickbooks.util.QuickBooksConfig;
import com.icligo.quickbooks.util.QuickBooksException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Service
public class ItemService extends QuickBooksClient {

    public ItemService(QuickBooksConfig config) {
        super(config);
    }

    /**
     * Looks up an Item by exact name. Returns {@code null} if QuickBooks has no Item with that
     * name — callers must decide whether that's an error rather than silently sending a line
     * with a description that doesn't correspond to a real QuickBooks Item.
     */
    public Item findByName(String name) throws QuickBooksException {
        try {
            String query = "SELECT * FROM Item WHERE Name = '" + name.replace("'", "\\'") + "'";
            String endpoint = "/query?query=" + java.net.URLEncoder.encode(query, StandardCharsets.UTF_8);
            String response = get(endpoint, String.class);

            JsonNode root = getObjectMapper().readTree(response);
            JsonNode queryResponse = root.get("QueryResponse");

            if (queryResponse != null && queryResponse.has("Item")) {
                JsonNode array = queryResponse.get("Item");
                if (array.isArray() && !array.isEmpty()) {
                    return getObjectMapper().treeToValue(array.get(0), Item.class);
                }
            }

            return null;
        } catch (Exception e) {
            throw new QuickBooksException("Failed to query item '" + name + "': " + e.getMessage(), e);
        }
    }
}
