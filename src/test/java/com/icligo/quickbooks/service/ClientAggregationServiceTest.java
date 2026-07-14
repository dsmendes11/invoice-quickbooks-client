package com.icligo.quickbooks.service;

import com.icligo.quickbooks.clients.quickbooks.model.SalesReceipt;
import com.icligo.quickbooks.model.ClientServiceValueInfo;
import com.icligo.quickbooks.model.QuickBooksDocument;
import com.icligo.quickbooks.model.document.ClientInvoiceInfo;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClientAggregationServiceTest {

    private final ActiveSalesReceiptFinder activeSalesReceiptFinder = mock(ActiveSalesReceiptFinder.class);
    private final ClientAggregationService service = new ClientAggregationService(activeSalesReceiptFinder);

    @Test
    void noActiveSalesReceiptsReturnsEmpty() {
        when(activeSalesReceiptFinder.findActive("srv-1")).thenReturn(List.of());

        assertThat(service.getServiceIdClients("srv-1")).isEmpty();
    }

    @Test
    void singleActiveSalesReceiptReturnsOneClientWithItsBalance() {
        ClientInvoiceInfo info = clientInfo("hash-1", "Jane Doe", "jane@example.com");
        ActiveSalesReceipt active = new ActiveSalesReceipt(
                doc("prod-1", "srv-1", info), SalesReceipt.builder().build(), new BigDecimal("49.90"));
        when(activeSalesReceiptFinder.findActive("srv-1")).thenReturn(List.of(active));

        List<ClientServiceValueInfo> clients = service.getServiceIdClients("srv-1");

        assertThat(clients).hasSize(1);
        ClientServiceValueInfo client = clients.get(0);
        assertThat(client.getClientHash()).isEqualTo("hash-1");
        assertThat(client.getName()).isEqualTo("Jane Doe");
        assertThat(client.getEmail()).isEqualTo("jane@example.com");
        assertThat(client.getTotal()).isEqualByComparingTo("49.90");
        assertThat(client.getProductValues()).containsEntry("prod-1", new BigDecimal("49.90"));
    }

    @Test
    void multipleSalesReceiptsForTheSameClientHashAreAggregatedIntoOneEntry() {
        ClientInvoiceInfo info = clientInfo("hash-1", "Jane Doe", "jane@example.com");
        ActiveSalesReceipt first = new ActiveSalesReceipt(
                doc("prod-1", "srv-1", info), SalesReceipt.builder().build(), new BigDecimal("30.00"));
        ActiveSalesReceipt second = new ActiveSalesReceipt(
                doc("prod-2", "srv-1", info), SalesReceipt.builder().build(), new BigDecimal("20.00"));
        when(activeSalesReceiptFinder.findActive("srv-1")).thenReturn(List.of(first, second));

        List<ClientServiceValueInfo> clients = service.getServiceIdClients("srv-1");

        assertThat(clients).hasSize(1);
        ClientServiceValueInfo client = clients.get(0);
        assertThat(client.getTotal()).isEqualByComparingTo("50.00");
        assertThat(client.getProductValues())
                .containsEntry("prod-1", new BigDecimal("30.00"))
                .containsEntry("prod-2", new BigDecimal("20.00"));
    }

    @Test
    void differentClientHashesProduceSeparateEntries() {
        ActiveSalesReceipt first = new ActiveSalesReceipt(
                doc("prod-1", "srv-1", clientInfo("hash-1", "Jane Doe", "jane@example.com")),
                SalesReceipt.builder().build(), new BigDecimal("30.00"));
        ActiveSalesReceipt second = new ActiveSalesReceipt(
                doc("prod-2", "srv-1", clientInfo("hash-2", "John Roe", "john@example.com")),
                SalesReceipt.builder().build(), new BigDecimal("20.00"));
        when(activeSalesReceiptFinder.findActive("srv-1")).thenReturn(List.of(first, second));

        List<ClientServiceValueInfo> clients = service.getServiceIdClients("srv-1");

        assertThat(clients).hasSize(2);
        assertThat(clients).extracting(ClientServiceValueInfo::getClientHash)
                .containsExactlyInAnyOrder("hash-1", "hash-2");
    }

    @Test
    void missingClientInvoiceInfoIsGroupedUnderUnknownWithoutThrowing() {
        ActiveSalesReceipt active = new ActiveSalesReceipt(
                doc("prod-1", "srv-1", null), SalesReceipt.builder().build(), new BigDecimal("10.00"));
        when(activeSalesReceiptFinder.findActive("srv-1")).thenReturn(List.of(active));

        List<ClientServiceValueInfo> clients = service.getServiceIdClients("srv-1");

        assertThat(clients).hasSize(1);
        assertThat(clients.get(0).getClientHash()).isNull();
        assertThat(clients.get(0).getTotal()).isEqualByComparingTo("10.00");
    }

    private QuickBooksDocument doc(String productId, String serviceId, ClientInvoiceInfo info) {
        QuickBooksDocument document = new QuickBooksDocument();
        document.setProductId(productId);
        document.setServiceId(serviceId);
        document.setClientInvoiceInfo(info);
        return document;
    }

    private ClientInvoiceInfo clientInfo(String clientHash, String name, String email) {
        ClientInvoiceInfo info = new ClientInvoiceInfo();
        info.setClientHash(clientHash);
        info.setName(name);
        info.setEmail(email);
        return info;
    }
}
