package com.icligo.quickbooks.repository;

import com.icligo.quickbooks.model.QuickBooksDocument;
import com.icligo.quickbooks.model.document.ClientInvoiceInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the {@code controlKey} uniqueness guarantee against a real (embedded) MongoDB,
 * not just mocked repository behaviour: two documents that resolve to the same controlKey
 * must never both persist, even when every other field — crucially {@code serviceId} — differs.
 */
@DataMongoTest
class QuickBooksDocumentRepositoryTest {

    @Autowired
    private QuickBooksDocumentRepository repository;

    @Test
    void savingASecondDocumentWithTheSameControlKeyIsRejectedRegardlessOfServiceId() {
        repository.save(document("service-a", "INV70021_dup_test2026"));

        QuickBooksDocument sameControlKeyDifferentServiceId = document("service-b", "INV70021_dup_test2026");

        assertThatThrownBy(() -> repository.save(sameControlKeyDifferentServiceId))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void findByControlKeyReturnsTheSavedDocument() {
        QuickBooksDocument saved = repository.save(document("service-a", "INV70021_lookup_test2026"));

        assertThat(repository.findByControlKey("INV70021_lookup_test2026"))
                .isPresent()
                .get()
                .extracting(QuickBooksDocument::getId)
                .isEqualTo(saved.getId());
    }

    private QuickBooksDocument document(String serviceId, String controlKey) {
        QuickBooksDocument document = new QuickBooksDocument();
        document.setType("INV");
        document.setServiceId(serviceId);
        document.setProductId("70021");
        document.setControlKey(controlKey);

        ClientInvoiceInfo customer = new ClientInvoiceInfo();
        customer.setName("Jane Doe");
        customer.setAddress("Rua Example 123");
        customer.setCountry("PT");
        document.setClientInvoiceInfo(customer);

        return document;
    }
}
