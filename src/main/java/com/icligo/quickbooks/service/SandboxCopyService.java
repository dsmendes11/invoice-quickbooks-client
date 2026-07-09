package com.icligo.quickbooks.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.icligo.quickbooks.clients.quickbooks.QuickBooksClient;
import com.icligo.quickbooks.clients.quickbooks.QuickBooksReadOnlyClient;
import com.icligo.quickbooks.model.SandboxCopyResult;
import com.icligo.quickbooks.util.QuickBooksConfig;
import com.icligo.quickbooks.util.QuickBooksException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Copies entities from the production QuickBooks company into the sandbox company in one
 * call, mirroring the "single command" copy tool at https://github.com/minimul/qbo-sandbox.
 *
 * <p>Safety model: production is only ever touched through {@link QuickBooksReadOnlyClient},
 * which has no write method at all, so this class has no code path capable of mutating
 * production data. Writes go through the inherited {@link QuickBooksClient} methods, which
 * are bound (via the constructor's {@code @Primary} {@code QuickBooksConfig}) to the sandbox
 * connection. {@link #assertSafeToCopy()} additionally refuses to run if the two connections
 * resolve to the same company/host, guarding against a misconfigured environment.
 */
@Slf4j
@Service
public class SandboxCopyService extends QuickBooksClient {

    private static final Set<String> SUPPORTED_ENTITIES = Set.of("Customer", "Vendor", "Item");
    private static final int MAX_PAGE_SIZE = 1000;
    private static final int MAX_BATCH_SIZE = 30;

    private final QuickBooksReadOnlyClient prodClient;
    private final QuickBooksConfig sandboxConfig;

    public SandboxCopyService(QuickBooksConfig quickBooksConfig, QuickBooksReadOnlyClient prodClient) {
        super(quickBooksConfig);
        this.sandboxConfig = quickBooksConfig;
        this.prodClient = prodClient;
    }

    public SandboxCopyResult copyToSandbox(String entity, int maxResults, int batchSize) throws QuickBooksException {
        String normalizedEntity = normalizeEntity(entity);
        assertSafeToCopy();

        int effectiveBatchSize = Math.min(batchSize <= 0 ? MAX_BATCH_SIZE : batchSize, MAX_BATCH_SIZE);
        int effectiveMax = maxResults <= 0 ? MAX_PAGE_SIZE : maxResults;

        List<ObjectNode> records = fetchAllFromProd(normalizedEntity, effectiveMax);
        log.info("Fetched {} {} record(s) from production realm {}", records.size(), normalizedEntity, prodClient.getRealmId());

        int succeeded = 0;
        List<String> failureMessages = new ArrayList<>();

        for (List<ObjectNode> chunk : partition(records, effectiveBatchSize)) {
            List<Map<String, Object>> batchItems = new ArrayList<>();
            for (ObjectNode record : chunk) {
                batchItems.add(buildBatchItem(record, normalizedEntity));
            }

            Map<String, Object> payload = Map.of("BatchItemRequest", batchItems);
            String response = post("/batch", payload, String.class);
            JsonNode root = parseJson(response);
            JsonNode batchItemResponses = root.get("BatchItemResponse");

            if (batchItemResponses != null && batchItemResponses.isArray()) {
                for (JsonNode item : batchItemResponses) {
                    if (item.has("Fault")) {
                        failureMessages.add(extractFaultMessage(item));
                    } else {
                        succeeded++;
                    }
                }
            }
        }

        return SandboxCopyResult.builder()
                .entity(normalizedEntity)
                .prodRealmId(prodClient.getRealmId())
                .sandboxRealmId(sandboxConfig.getRealmId())
                .fetchedFromProd(records.size())
                .submitted(records.size())
                .succeeded(succeeded)
                .failed(failureMessages.size())
                .failureMessages(failureMessages)
                .build();
    }

    /**
     * Refuses to proceed unless the production (read) and sandbox (write) connections are
     * unambiguously different companies. This is the last line of defense if configuration
     * is ever set up incorrectly.
     */
    private void assertSafeToCopy() throws QuickBooksException {
        String prodRealmId = prodClient.getRealmId();
        String sandboxRealmId = sandboxConfig.getRealmId();

        if (prodRealmId == null || prodRealmId.isBlank()) {
            throw new QuickBooksException("Production realm ID is not configured (quickbooks.prod.realm-id). Refusing to copy.");
        }
        if (sandboxRealmId == null || sandboxRealmId.isBlank()) {
            throw new QuickBooksException("Sandbox realm ID is not configured (quickbooks.api.realm-id). Refusing to copy.");
        }
        if (Objects.equals(prodRealmId, sandboxRealmId)) {
            throw new QuickBooksException("Refusing to copy: production and sandbox realm IDs are identical (" + prodRealmId + ").");
        }
        if (Objects.equals(prodClient.getBaseUrl(), sandboxConfig.getBaseUrl())) {
            throw new QuickBooksException("Refusing to copy: production and sandbox base URLs are identical.");
        }
        if (sandboxConfig.getBaseUrl() == null || !sandboxConfig.getBaseUrl().contains("sandbox")) {
            throw new QuickBooksException(
                    "Refusing to copy: write target's base URL (" + sandboxConfig.getBaseUrl() + ") does not look like a QuickBooks sandbox host."
            );
        }
    }

    private String normalizeEntity(String entity) throws QuickBooksException {
        if (entity == null) {
            throw new QuickBooksException("Entity is required.");
        }
        String normalized = Character.toUpperCase(entity.charAt(0)) + entity.substring(1).toLowerCase();
        if (!SUPPORTED_ENTITIES.contains(normalized)) {
            throw new QuickBooksException(
                    "Unsupported entity '" + entity + "'. Supported entities: " + SUPPORTED_ENTITIES
                            + " (entities with cross-references, e.g. Invoice or sub-customers, are not supported)."
            );
        }
        return normalized;
    }

    private List<ObjectNode> fetchAllFromProd(String entity, int maxResults) throws QuickBooksException {
        List<ObjectNode> results = new ArrayList<>();
        int startPosition = 1;
        int pageSize = Math.min(maxResults, MAX_PAGE_SIZE);

        while (results.size() < maxResults) {
            String soql = "SELECT * FROM " + entity + " STARTPOSITION " + startPosition + " MAXRESULTS " + pageSize;
            String response = prodClient.query(soql);
            JsonNode root = parseJson(response);
            JsonNode queryResponse = root.get("QueryResponse");

            if (queryResponse == null || !queryResponse.has(entity)) {
                break;
            }

            JsonNode array = queryResponse.get(entity);
            if (!array.isArray() || array.isEmpty()) {
                break;
            }

            for (JsonNode node : array) {
                results.add((ObjectNode) node);
            }

            if (array.size() < pageSize) {
                break;
            }
            startPosition += pageSize;
        }

        return results;
    }

    private Map<String, Object> buildBatchItem(ObjectNode record, String entity) {
        ObjectNode copy = record.deepCopy();
        copy.remove("Id");
        copy.remove("SyncToken");
        copy.remove("MetaData");

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("bId", "bid" + UUID.randomUUID());
        item.put("operation", "create");
        item.put(entity, copy);
        return item;
    }

    private String extractFaultMessage(JsonNode batchItem) {
        JsonNode fault = batchItem.get("Fault");
        JsonNode errors = fault.get("Error");
        if (errors != null && errors.isArray() && !errors.isEmpty()) {
            JsonNode error = errors.get(0);
            String message = error.has("Message") ? error.get("Message").asText() : "Unknown error";
            String detail = error.has("Detail") ? error.get("Detail").asText() : "";
            return detail.isEmpty() ? message : message + ": " + detail;
        }
        return "Unknown batch failure";
    }

    private JsonNode parseJson(String json) throws QuickBooksException {
        try {
            return getObjectMapper().readTree(json);
        } catch (IOException e) {
            throw new QuickBooksException("Failed to parse QuickBooks response", e);
        }
    }

    private static <T> List<List<T>> partition(List<T> items, int size) {
        List<List<T>> chunks = new ArrayList<>();
        for (int i = 0; i < items.size(); i += size) {
            chunks.add(items.subList(i, Math.min(i + size, items.size())));
        }
        return chunks;
    }
}
