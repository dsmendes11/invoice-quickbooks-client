package com.icligo.quickbooks.validation;

import com.icligo.quickbooks.clients.quickbooks.model.Item;
import com.icligo.quickbooks.model.QuickBooksDocument;
import com.icligo.quickbooks.model.document.ClientInvoiceInfo;
import com.icligo.quickbooks.model.document.ItemDto;
import com.icligo.quickbooks.service.ItemService;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuickBooksDocumentValidatorTest {

    private static Validator validator;
    private static ItemService itemService;

    /**
     * {@code QuickBooksDocumentValidator} is normally constructed by Spring's
     * {@code SpringConstraintValidatorFactory} (which injects {@link ItemService} like any other
     * bean), but this test bootstraps a plain JSR-380 validator with no Spring context — so it
     * needs its own {@link ConstraintValidatorFactory} that knows how to build a
     * {@code QuickBooksDocumentValidator} with a mocked {@link ItemService}.
     */
    @BeforeAll
    static void setUp() throws Exception {
        itemService = mock(ItemService.class);
        when(itemService.findByName(anyString())).thenReturn(new Item());

        ConstraintValidatorFactory factory = new ConstraintValidatorFactory() {
            @Override
            @SuppressWarnings("unchecked")
            public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> key) {
                if (key == QuickBooksDocumentValidator.class) {
                    return (T) new QuickBooksDocumentValidator(itemService);
                }
                try {
                    return key.getDeclaredConstructor().newInstance();
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public void releaseInstance(ConstraintValidator<?, ?> instance) {
            }
        };

        validator = Validation.byDefaultProvider()
                .configure()
                .constraintValidatorFactory(factory)
                .buildValidatorFactory()
                .getValidator();
    }

    @Test
    void validInvoiceHasNoViolations() {
        QuickBooksDocument document = validDocument();

        assertThat(validator.validate(document)).isEmpty();
    }

    @Test
    void missingTypeIsRejected() {
        QuickBooksDocument document = validDocument();
        document.setType(null);

        assertThat(messages(document)).anyMatch(m -> m.contains("type is required"));
    }

    @Test
    void missingClientInvoiceInfoIsRejected() {
        QuickBooksDocument document = validDocument();
        document.setClientInvoiceInfo(null);

        assertThat(messages(document)).anyMatch(m -> m.contains("clientInvoiceInfo is required"));
    }

    @Test
    void missingServiceIdIsRejectedRegardlessOfType() {
        QuickBooksDocument document = validDocument();
        document.setServiceId(null);
        document.setType("SRT");

        assertThat(messages(document)).contains("serviceId is required");
    }

    @Test
    void missingProductIdIsRejectedRegardlessOfType() {
        QuickBooksDocument document = validDocument();
        document.setProductId(null);
        // type stays INV — productId is required for every type, not just SRT/RRT

        assertThat(messages(document)).contains("productId is required");
    }

    @Test
    void refundReceiptTypeIsAlwaysRejectedRegardlessOfOtherFields() {
        QuickBooksDocument document = validDocument();
        document.setType("RRT");
        document.setRefundId("rfd-9931");

        assertThat(validator.validate(document)).anySatisfy(v -> {
            assertThat(v.getPropertyPath().toString()).isEqualTo("type");
            assertThat(v.getMessage()).contains("POST /refunds");
        });
    }

    @Test
    void unsupportedTypeIsRejected() {
        QuickBooksDocument document = validDocument();
        document.setType("BOGUS");

        assertThat(messages(document)).anyMatch(m -> m.contains("must be one of INV, SRT, RRT"));
    }

    @Test
    void customerWithNoFieldsIsRejectedForNameAddressAndCountry() {
        QuickBooksDocument document = validDocument();
        document.setClientInvoiceInfo(new ClientInvoiceInfo());

        Set<String> messages = messages(document);
        assertThat(messages).contains("name is required", "address is required", "country is required");
    }

    @Test
    void customerMissingCountryOnlyIsRejected() {
        QuickBooksDocument document = validDocument();
        document.getClientInvoiceInfo().setCountry(null);

        assertThat(messages(document)).contains("country is required");
    }

    @Test
    void customerIdentifiedByEmailOnlyIsRejected() {
        QuickBooksDocument document = validDocument();
        ClientInvoiceInfo customer = new ClientInvoiceInfo();
        customer.setEmail("jane.doe@example.com");
        document.setClientInvoiceInfo(customer);

        assertThat(messages(document)).contains("name is required", "address is required", "country is required");
    }

    @Test
    void missingItemsIsRejected() {
        QuickBooksDocument document = validDocument();
        document.setItems(null);

        assertThat(messages(document)).anyMatch(m -> m.contains("must be present and reference existing QuickBooks Items"));
    }

    @Test
    void emptyItemsIsRejectedSinceTotalIsZero() {
        QuickBooksDocument document = validDocument();
        document.setItems(List.of());

        assertThat(messages(document)).anyMatch(m -> m.contains("must be present and reference existing QuickBooks Items"));
    }

    @Test
    void itemWithNegativeValueIsRejected() {
        QuickBooksDocument document = validDocument();
        document.setItems(List.of(item("Airport transfer", new BigDecimal("-10.00"))));

        assertThat(messages(document)).anyMatch(m -> m.contains("must be present and reference existing QuickBooks Items"));
    }

    @Test
    void itemNotFoundInQuickBooksIsRejected() throws Exception {
        when(itemService.findByName("Unknown Item")).thenReturn(null);

        QuickBooksDocument document = validDocument();
        document.setItems(List.of(item("Unknown Item", BigDecimal.TEN)));

        assertThat(messages(document)).anyMatch(m -> m.contains("must be present and reference existing QuickBooks Items"));
    }

    private Set<String> messages(QuickBooksDocument document) {
        Set<ConstraintViolation<QuickBooksDocument>> violations = validator.validate(document);
        return violations.stream().map(ConstraintViolation::getMessage).collect(java.util.stream.Collectors.toSet());
    }

    private ItemDto item(String name, BigDecimal value) {
        ItemDto item = new ItemDto();
        item.setItem(name);
        item.setValue(value);
        return item;
    }

    private QuickBooksDocument validDocument() {
        QuickBooksDocument document = new QuickBooksDocument();
        document.setType("INV");
        document.setServiceId("48213");
        document.setProductId("70021");
        document.setItems(List.of(item("Airport transfer", new BigDecimal("49.90"))));
        ClientInvoiceInfo customer = new ClientInvoiceInfo();
        customer.setName("Jane Doe");
        customer.setAddress("Rua Example 123");
        customer.setCountry("PT");
        document.setClientInvoiceInfo(customer);
        return document;
    }
}
