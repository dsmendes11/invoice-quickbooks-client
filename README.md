# QuickBooks API Client

A Java client library for interacting with the QuickBooks API.

## Features

- ✅ Customer management (Create, Read, Update, Delete)
- ✅ Invoice creation and tracking
- ✅ Payment processing
- ✅ Expense tracking
- ✅ Type-safe API with Java models
- ✅ OAuth 2.0 authentication
- ✅ Built with Maven
- ✅ Comprehensive error handling

## Prerequisites

- Java 17 or higher
- Maven 3.6 or higher
- QuickBooks API credentials (client ID, client secret, access token)

## Installation

### Clone and Build

```bash
git clone <your-repo-url>
cd quickbooks-client
mvn clean install
```

### Add as Maven Dependency

```xml
<dependency>
    <groupId>com.quickbooks</groupId>
    <artifactId>quickbooks-client</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Configuration

Update `src/main/resources/application.properties` with your credentials:

```properties
quickbooks.api.baseUrl=https://api.quickbooks.com/v1
quickbooks.api.clientId=your_client_id_here
quickbooks.api.clientSecret=your_client_secret_here
quickbooks.api.accessToken=your_access_token_here
```

## Quick Start

### Initialize the Client

```java
QuickBooksConfig config = QuickBooksConfig.builder()
    .baseUrl("https://api.quickbooks.com/v1")
    .clientId("your_client_id")
    .clientSecret("your_client_secret")
    .accessToken("your_access_token")
    .build();
```

### Create a Customer

```java
CustomerService customerService = new CustomerService(config);

Customer customer = Customer.builder()
    .name("Tech Startup Inc")
    .email("contact@techstartup.com")
    .phone("+1-555-0199")
    .address(Customer.Address.builder()
        .street("456 Innovation Way")
        .city("Austin")
        .state("TX")
        .zip("78701")
        .country("US")
        .build())
    .build();

Customer created = customerService.createCustomer(customer);
System.out.println("Customer ID: " + created.getId());
```

### Create an Invoice

```java
InvoiceService invoiceService = new InvoiceService(config);

Invoice invoice = Invoice.builder()
    .customerId("cust_123456")
    .invoiceNumber("INV-2024-001")
    .issueDate(LocalDate.now())
    .dueDate(LocalDate.now().plusDays(30))
    .items(Arrays.asList(
        Invoice.InvoiceItem.builder()
            .description("Web Design Services")
            .quantity(40)
            .unitPrice(150.00)
            .taxRate(0.08)
            .build()
    ))
    .build();

Invoice created = invoiceService.createInvoice(invoice);
```

### Record a Payment

```java
PaymentService paymentService = new PaymentService(config);

Payment payment = Payment.builder()
    .invoiceId("inv_789012")
    .amount(7020.00)
    .paymentMethod("credit_card")
    .paymentDate(LocalDate.now())
    .reference("CC-4242")
    .build();

Payment recorded = paymentService.createPayment(payment);
```

### Track an Expense

```java
ExpenseService expenseService = new ExpenseService(config);

Expense expense = Expense.builder()
    .date(LocalDate.now())
    .amount(350.00)
    .category("office_supplies")
    .vendor("Office Depot")
    .description("Printer ink and paper")
    .paymentMethod("company_card")
    .build();

Expense created = expenseService.createExpense(expense);
```

## Available Services

### CustomerService
- `listCustomers(page, limit, search)` - List all customers with pagination
- `getCustomer(customerId)` - Get a specific customer
- `createCustomer(customer)` - Create a new customer
- `updateCustomer(customerId, customer)` - Update a customer
- `deleteCustomer(customerId)` - Delete a customer

### InvoiceService
- `listInvoices(customerId, status, fromDate, toDate, page, limit)` - List invoices with filters
- `getInvoice(invoiceId)` - Get a specific invoice
- `createInvoice(invoice)` - Create a new invoice
- `updateInvoiceStatus(invoiceId, status)` - Update invoice status

### PaymentService
- `listPayments(page, limit)` - List all payments
- `getPayment(paymentId)` - Get a specific payment
- `createPayment(payment)` - Record a new payment

### ExpenseService
- `listExpenses(category, fromDate, toDate, page, limit)` - List expenses with filters
- `getExpense(expenseId)` - Get a specific expense
- `createExpense(expense)` - Create a new expense

## Running the Demo

```bash
# Update credentials in application.yml first
mvn clean compile
mvn exec:java -Dexec.mainClass="com.quickbooks.client.QuickBooksClientDemo"
```

## Building

```bash
# Compile
mvn clean compile

# Run tests
mvn test

# Create JAR
mvn clean package

# The executable JAR will be in target/quickbooks-client-1.0.0.jar
```

## Error Handling

The client throws `QuickBooksException` for all API errors:

```java
try {
    Customer customer = customerService.getCustomer("cust_123");
} catch (QuickBooksException e) {
    System.err.println("Error code: " + e.getErrorCode());
    System.err.println("Message: " + e.getErrorMessage());
    System.err.println("Status: " + e.getStatusCode());
}
```

## Project Structure

```
quickbooks-client/
├── src/
│   ├── main/
│   │   ├── java/com/quickbooks/client/
│   │   │   ├── model/          # Data models
│   │   │   ├── service/        # API service classes
│   │   │   ├── util/           # Utilities and config
│   │   │   └── QuickBooksClientDemo.java
│   │   └── resources/
│   │       └── application.properties
│   └── test/
│       └── java/com/quickbooks/client/
├── pom.xml
└── README.md
```

## Dependencies

- **OkHttp** - HTTP client
- **Jackson** - JSON serialization/deserialization
- **SLF4J** - Logging
- **Lombok** - Reduce boilerplate code
- **JUnit 5** - Testing framework

## IntelliJ IDEA Setup

1. Open IntelliJ IDEA
2. Select `File > Open` and choose the `quickbooks-client` directory
3. IntelliJ will automatically detect it as a Maven project
4. Wait for Maven to download dependencies
5. Enable Lombok plugin if not already installed
6. Run `QuickBooksClientDemo` to test

## License

MIT License

## Support

For issues and questions, please open an issue on GitHub.
