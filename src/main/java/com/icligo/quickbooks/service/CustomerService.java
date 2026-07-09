package com.icligo.quickbooks.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.icligo.quickbooks.clients.quickbooks.QuickBooksClient;
import com.icligo.quickbooks.clients.quickbooks.model.Customer;
import com.icligo.quickbooks.model.document.ClientInvoiceInfo;
import com.icligo.quickbooks.repository.CustomerRepository;
import com.icligo.quickbooks.util.QuickBooksConfig;
import com.icligo.quickbooks.util.QuickBooksException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class CustomerService extends QuickBooksClient {

    private final CustomerRepository customerRepository;

    public CustomerService(QuickBooksConfig config, CustomerRepository customerRepository) {
        super(config);
        this.customerRepository = customerRepository;
    }

    public List<Customer> query(String query) throws QuickBooksException {
        try {
            String endpoint = "/query?query=" + java.net.URLEncoder.encode(query, StandardCharsets.UTF_8);
            String response = get(endpoint, String.class);

            JsonNode root = getObjectMapper().readTree(response);
            JsonNode queryResponse = root.get("QueryResponse");

            if (queryResponse != null && queryResponse.has("Customer")) {
                JsonNode customerArray = queryResponse.get("Customer");
                List<Customer> customers = new ArrayList<>();

                if (customerArray.isArray()) {
                    for (JsonNode node : customerArray) {
                        Customer customer = getObjectMapper().treeToValue(node, Customer.class);
                        customers.add(customer);
                    }
                }
                return customers;
            }

            return new ArrayList<>();
        } catch (Exception e) {
            throw new QuickBooksException("Failed to query customers: " + e.getMessage(), e);
        }
    }

    public Customer getCustomer(String customerId) throws QuickBooksException {
        try {
            String response = get("/customer/" + customerId, String.class);
            JsonNode root = getObjectMapper().readTree(response);
            return getObjectMapper().treeToValue(root.get("Customer"), Customer.class);
        } catch (Exception e) {
            throw new QuickBooksException("Failed to get customer: " + e.getMessage(), e);
        }
    }

    public Customer createCustomer(Customer customer) throws QuickBooksException {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("DisplayName", customer.getDisplayName());

            if (customer.getPrimaryEmailAddr() != null) {
                requestBody.put("PrimaryEmailAddr", customer.getPrimaryEmailAddr());
            }
            if (customer.getPrimaryPhone() != null) {
                requestBody.put("PrimaryPhone", customer.getPrimaryPhone());
            }
            if (customer.getBillAddr() != null) {
                requestBody.put("BillAddr", customer.getBillAddr());
            }
            if (customer.getNotes() != null) {
                requestBody.put("Notes", customer.getNotes());
            }

            String response = post("/customer", requestBody, String.class);
            JsonNode root = getObjectMapper().readTree(response);

            Customer created = getObjectMapper().treeToValue(root.get("Customer"), Customer.class);
            created = customerRepository.save(created);
            return created;
        } catch (Exception e) {
            throw new QuickBooksException("Failed to create customer: " + e.getMessage(), e);
        }
    }

    public Customer updateCustomer(Customer customer) throws QuickBooksException {
        try {
            if (customer.getId() == null || customer.getSyncToken() == null) {
                throw new QuickBooksException("Customer Id and SyncToken are required for update");
            }

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("Id", customer.getId());
            requestBody.put("SyncToken", customer.getSyncToken());
            requestBody.put("sparse", true);

            if (customer.getDisplayName() != null) {
                requestBody.put("DisplayName", customer.getDisplayName());
            }
            if (customer.getPrimaryEmailAddr() != null) {
                requestBody.put("PrimaryEmailAddr", customer.getPrimaryEmailAddr());
            }
            if (customer.getPrimaryPhone() != null) {
                requestBody.put("PrimaryPhone", customer.getPrimaryPhone());
            }

            String response = post("/customer", requestBody, String.class);
            JsonNode root = getObjectMapper().readTree(response);

            Customer updated = getObjectMapper().treeToValue(root.get("Customer"), Customer.class);
            updated = customerRepository.save(updated);
            return updated;
        } catch (Exception e) {
            throw new QuickBooksException("Failed to update customer: " + e.getMessage(), e);
        }
    }

    /**
     * Finds the QuickBooks customer for this request, or creates it.
     *
     * <p>The match is a direct, exact QuickBooks query on {@code DisplayName = name + " " + hash},
     * where {@code hash} is the deterministic dedup hash of name/address/country (see
     * {@link #generateCustomerId}). Because the hash is appended to every DisplayName we create,
     * this lookup is authoritative on its own — the same real-world customer always produces the
     * same DisplayName, and QuickBooks enforces DisplayName uniqueness for us.
     */
    public Customer findOrCreateCustomerByEmailAndName(ClientInvoiceInfo clientInfo)
            throws QuickBooksException {
        String address = (clientInfo != null) ? clientInfo.getAddress() : null;
        String name = (clientInfo != null) ? clientInfo.getName() : null;
        String country = (clientInfo != null) ? clientInfo.getCountry() : null;

        if (isBlank(name) || isBlank(address) || isBlank(country)) {
            throw new QuickBooksException("Failed to create customer: name, address, and country are required");
        }

        String hashId = generateCustomerId(name, address, country);
        String displayName = buildDisplayName(name, hashId);

        Customer existing = findByDisplayName(displayName);
        if (existing != null) {
            return existing;
        }

        return createCustomerFromClientInfo(hashId, displayName, clientInfo);
    }

    private Customer findByDisplayName(String displayName) throws QuickBooksException {
        String escaped = displayName.replace("'", "\\'");
        List<Customer> results = query("SELECT * FROM Customer WHERE DisplayName = '" + escaped + "' MAXRESULTS 1");
        return results.isEmpty() ? null : results.getFirst();
    }

    private String buildDisplayName(String name, String hashId) {
        return baseName(name) + " " + hashId;
    }

    private String baseName(String name) {
        return (name != null && !name.trim().isEmpty()) ? name.trim() : "Customer";
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private Customer createCustomerFromClientInfo(String customerHash, String displayName, ClientInvoiceInfo clientInfo)
            throws QuickBooksException {

        String printOnCheckName = baseName(clientInfo != null ? clientInfo.getName() : null);

        Customer.CustomerBuilder builder = Customer.builder()
                .displayName(displayName)
                .printOnCheckName(printOnCheckName)
                .companyName(clientInfo != null ? clientInfo.getName() : null);

        if (clientInfo != null && clientInfo.getEmail() != null && !clientInfo.getEmail().trim().isEmpty()) {
            builder.primaryEmailAddr(Customer.EmailAddress.builder()
                    .address(clientInfo.getEmail())
                    .build());
        }

        if (clientInfo != null && clientInfo.getPhone() != null) {
            builder.primaryPhone(Customer.TelephoneNumber.builder()
                    .freeFormNumber(clientInfo.getPhone())
                    .build());
        }

        if (clientInfo != null && clientInfo.getAddress() != null) {
            Customer.PhysicalAddress billAddr = Customer.PhysicalAddress.builder()
                    .line1(clientInfo.getAddress())
                    .city(clientInfo.getCity())
                    .countrySubDivisionCode(clientInfo.getCity())
                    .postalCode(clientInfo.getZipCode())
                    .country(clientInfo.getCountry() != null ? clientInfo.getCountry() : "US")
                    .build();
            builder.billAddr(billAddr);
        }

        builder.notes(customerHash);

        Customer newCustomer = builder.build();

        try {
            return createCustomer(newCustomer);
        } catch (QuickBooksException e) {
            log.error("Failed to create customer: {}", e.getMessage());
            throw new QuickBooksException("Failed to create customer: " + displayName, e);
        }
    }

    public static String generateCustomerId(String name, String address, String country) throws QuickBooksException {
        try {
            String combined = (name != null ? name.toLowerCase().trim() : "") + "|" +
                    (address != null ? address.toLowerCase().trim() : "") + "|" +
                    (country != null ? country.toLowerCase().trim() : "");

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(combined.getBytes(StandardCharsets.UTF_8));

            StringBuilder hexString = new StringBuilder();
            for (int i = 0; i < Math.min(hash.length, 8); i++) {
                String hex = Integer.toHexString(0xff & hash[i]);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }

            return "CUST_" + hexString.toString().toUpperCase();

        } catch (Exception e) {
            log.error("Failed to generate customer ID hash: {}", e.getMessage());
            throw new QuickBooksException("Failed to generate customer ID hash: " + e.getMessage());
        }
    }
}
