package com.icligo.quickbooks.validation;

import com.icligo.quickbooks.model.QuickBooksDocument;
import com.icligo.quickbooks.model.document.ClientInvoiceInfo;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class QuickBooksDocumentValidatorTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void validInvoiceHasNoViolations() {
        QuickBooksDocument document = invoiceWithServiceId("48213");

        assertThat(validator.validate(document)).isEmpty();
    }

    @Test
    void missingTypeIsRejected() {
        QuickBooksDocument document = invoiceWithServiceId("48213");
        document.setType(null);

        assertThat(messages(document)).anyMatch(m -> m.contains("type is required"));
    }

    @Test
    void missingClientInvoiceInfoIsRejected() {
        QuickBooksDocument document = invoiceWithServiceId("48213");
        document.setClientInvoiceInfo(null);

        assertThat(messages(document)).anyMatch(m -> m.contains("clientInvoiceInfo is required"));
    }

    @Test
    void invoiceWithoutServiceIdIsRejected() {
        QuickBooksDocument document = invoiceWithServiceId(null);

        assertThat(validator.validate(document)).anySatisfy(v -> {
            assertThat(v.getPropertyPath().toString()).isEqualTo("serviceId");
            assertThat(v.getMessage()).isEqualTo("is required when type is INVOICE");
        });
    }

    @Test
    void salesReceiptWithoutProductIdIsRejected() {
        QuickBooksDocument document = invoiceWithServiceId("48213");
        document.setType("SALES_RECEIPT");
        document.setServiceId(null);

        assertThat(validator.validate(document)).anySatisfy(v -> {
            assertThat(v.getPropertyPath().toString()).isEqualTo("productId");
            assertThat(v.getMessage()).isEqualTo("is required when type is SALES_RECEIPT");
        });
    }

    @Test
    void refundReceiptWithoutRefundIdIsRejected() {
        QuickBooksDocument document = invoiceWithServiceId("48213");
        document.setType("REFUND_RECEIPT");
        document.setServiceId(null);
        document.setProductId("70021");

        assertThat(validator.validate(document)).anySatisfy(v -> {
            assertThat(v.getPropertyPath().toString()).isEqualTo("refundId");
            assertThat(v.getMessage()).isEqualTo("is required when type is REFUND_RECEIPT");
        });
    }

    @Test
    void unsupportedTypeIsRejected() {
        QuickBooksDocument document = invoiceWithServiceId("48213");
        document.setType("BOGUS");

        assertThat(messages(document)).anyMatch(m -> m.contains("must be one of INVOICE, SALES_RECEIPT, REFUND_RECEIPT"));
    }

    @Test
    void customerWithNoIdentifiersIsRejected() {
        QuickBooksDocument document = invoiceWithServiceId("48213");
        document.setClientInvoiceInfo(new ClientInvoiceInfo());

        assertThat(messages(document)).anyMatch(m -> m.contains("must have at least one of: name, email, address, country"));
    }

    @Test
    void customerIdentifiedByEmailOnlyIsAccepted() {
        QuickBooksDocument document = invoiceWithServiceId("48213");
        ClientInvoiceInfo customer = new ClientInvoiceInfo();
        customer.setEmail("jane.doe@example.com");
        document.setClientInvoiceInfo(customer);

        assertThat(validator.validate(document)).isEmpty();
    }

    private Set<String> messages(QuickBooksDocument document) {
        Set<ConstraintViolation<QuickBooksDocument>> violations = validator.validate(document);
        return violations.stream().map(ConstraintViolation::getMessage).collect(java.util.stream.Collectors.toSet());
    }

    private QuickBooksDocument invoiceWithServiceId(String serviceId) {
        QuickBooksDocument document = new QuickBooksDocument();
        document.setType("INVOICE");
        document.setServiceId(serviceId);
        ClientInvoiceInfo customer = new ClientInvoiceInfo();
        customer.setName("Jane Doe");
        document.setClientInvoiceInfo(customer);
        return document;
    }
}
