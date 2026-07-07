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

    public Customer findOrCreateCustomerByEmailAndName(ClientInvoiceInfo clientInfo)
            throws QuickBooksException {
        String email = (clientInfo != null) ? clientInfo.getEmail() : null;
        String address = (clientInfo != null) ? clientInfo.getAddress() : null;
        String name = (clientInfo != null) ? clientInfo.getName() : null;
        String country = (clientInfo != null) ? clientInfo.getCountry() : null;

        if ((email == null || email.trim().isEmpty()) &&
                (address == null || address.trim().isEmpty()) &&
                (name == null || name.trim().isEmpty())&&
                (country == null || country.trim().isEmpty())) {
            throw new QuickBooksException("Failed to create customer: " + name);
        }

        try {
            String hashId = generateCustomerId(name, address, country);
            Customer customer = customerRepository.findByNotes(hashId);
            if (customer != null) {
                return customer;
            }

            String searchQuery = buildCombinedSearchQuery(email, name);
            if (searchQuery != null) {
                List<Customer> results = query(searchQuery);
                if (!results.isEmpty()) {
                    return results.getFirst();
                }
            }

            return createCustomerFromClientInfo(hashId, clientInfo);

        } catch (QuickBooksException e) {
            log.error("Error searching/creating customer: {}", e.getMessage());
            try {
                String hashId = generateCustomerId(name, address, country);
                return createCustomerFromClientInfo(hashId, clientInfo);
            } catch (QuickBooksException createEx) {
                log.error("Failed to create customer: {}", createEx.getMessage());
                throw new QuickBooksException("Failed to create customer: " + name, createEx);
            }
        }
    }

    private Customer createCustomerFromClientInfo(String customerHash, ClientInvoiceInfo clientInfo)
            throws QuickBooksException {

        String displayName = (clientInfo != null && clientInfo.getName() != null)
                ? clientInfo.getName()
                : "Customer";

        Customer.CustomerBuilder builder = Customer.builder()
                .displayName(displayName)
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

    private String buildCombinedSearchQuery(String email, String name) {
        List<String> conditions = new ArrayList<>();

        if (email != null && !email.trim().isEmpty()) {
            conditions.add(String.format("PrimaryEmailAddr = '%s'", email.replace("'", "\\'")));
        }

        if (name != null && !name.trim().isEmpty()) {
            conditions.add(String.format("DisplayName LIKE '%%%s%%'", name.replace("'", "\\'")));
        }

        if (conditions.isEmpty()) {
            return null;
        }

        return "SELECT * FROM Customer WHERE " + String.join(" AND ", conditions) + " MAXRESULTS 5";
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
