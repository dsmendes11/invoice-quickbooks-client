package com.icligo.quickbooks.service.authentication;

import com.mailjet.client.ClientOptions;
import com.mailjet.client.MailjetClient;
import com.mailjet.client.MailjetRequest;
import com.mailjet.client.MailjetResponse;
import com.mailjet.client.resource.Emailv31;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Sends plain Mailjet admin-alert emails for the failure conditions this service can't
 * self-recover from: the QuickBooks connection breaking (see docs/OPERATIONS.md §5), a
 * best-effort SalesReceipt-cancellation CreditMemo failing (see
 * {@link com.icligo.quickbooks.service.SalesReceiptCancellationService}), a partial-refund
 * allocation failing to create its RefundReceipt (see
 * {@link com.icligo.quickbooks.service.RefundReceiptAllocationService}), and a non-{@code
 * "Reserva"} Invoice's Payment failing to create (see {@code QuickBooksActivitiesImpl.createPayment}
 * — that failure still fails the workflow too, unlike the other two). Same Mailjet SDK/call
 * shape as notifications' MailJetClient, without the template plumbing since these are simple,
 * fixed-shape messages.
 */
@Slf4j
@Component
public class QuickBooksAlertService {

    @Value("${mailjet.send-enabled:true}")
    private boolean sendEnabled;

    @Value("${mailjet.public-key}")
    private String publicKey;

    @Value("${mailjet.private-key}")
    private String privateKey;

    @Value("${mailjet.from-email}")
    private String fromEmail;

    @Value("${mailjet.from-name}")
    private String fromName;

    @Value("${mailjet.admin-email}")
    private String adminEmail;

    @Value("${quickbooks.oauth.connect-uri}")
    private String connectUri;

    public void sendQuickBooksDisconnectedAlert(String reason) {
        String subject = "QuickBooks connection lost — action needed";
        String textBody = "The QuickBooks connection for the Invoice QuickBooks Service is broken and "
                + "invoices/receipts/refunds cannot be created until it's reconnected.\n\n"
                + "Reason: " + reason + "\n\n"
                + "To fix it, open this link in a browser and authorize the app again:\n" + connectUri;
        String htmlBody = "<p>The QuickBooks connection for the Invoice QuickBooks Service is broken and "
                + "invoices/receipts/refunds cannot be created until it's reconnected.</p>"
                + "<p><b>Reason:</b> " + reason + "</p>"
                + "<p>To fix it, open this link in a browser and authorize the app again:</p>"
                + "<p><a href=\"" + connectUri + "\">" + connectUri + "</a></p>";

        sendAlert("QuickBooks disconnected", subject, textBody, htmlBody);
    }

    /**
     * Sent when {@link com.icligo.quickbooks.service.SalesReceiptCancellationService} fails to
     * cancel a Sales Receipt via CreditMemo — this never blocks the triggering Invoice creation
     * (see that class), so this email is the only signal an admin gets that a Sales Receipt is
     * still open in QuickBooks and needs manual cancellation.
     */
    public void sendCreditMemoCancellationFailedAlert(String serviceId, String productId, String reason) {
        String subject = "QuickBooks: failed to cancel a Sales Receipt via CreditMemo — action needed";
        String textBody = "Creating a booking (Reserva) Invoice for serviceId=" + serviceId + " should have "
                + "cancelled a prepaid Sales Receipt (productId=" + productId + ") via a CreditMemo, but that "
                + "failed. The Invoice was still created — this Sales Receipt needs to be cancelled manually "
                + "in QuickBooks.\n\n"
                + "serviceId: " + serviceId + "\n"
                + "productId: " + productId + "\n"
                + "Reason: " + reason;
        String htmlBody = "<p>Creating a booking (Reserva) Invoice for serviceId=" + serviceId + " should have "
                + "cancelled a prepaid Sales Receipt (productId=" + productId + ") via a CreditMemo, but that "
                + "failed. The Invoice was still created — this Sales Receipt needs to be cancelled manually "
                + "in QuickBooks.</p>"
                + "<p><b>serviceId:</b> " + serviceId + "<br>"
                + "<b>productId:</b> " + productId + "<br>"
                + "<b>Reason:</b> " + reason + "</p>";

        sendAlert("CreditMemo cancellation failed", subject, textBody, htmlBody);
    }

    /**
     * Sent when {@link com.icligo.quickbooks.service.RefundReceiptAllocationService} fails to
     * create the RefundReceipt for one Sales Receipt's allocated share of a refund — this never
     * blocks the other allocations from that same {@code POST /refunds} call (see that class), so
     * this email is the only signal an admin gets that part of the requested refund didn't land.
     */
    public void sendRefundAllocationFailedAlert(String serviceId, String productId, BigDecimal amount, String reason) {
        String subject = "QuickBooks: failed to create a RefundReceipt allocation — action needed";
        String textBody = "A refund request for serviceId=" + serviceId + " allocated " + amount + " to Sales "
                + "Receipt productId=" + productId + ", but creating that RefundReceipt in QuickBooks failed. "
                + "Any other Sales Receipts in the same refund request were still processed independently — "
                + "this one amount needs to be refunded manually.\n\n"
                + "serviceId: " + serviceId + "\n"
                + "productId: " + productId + "\n"
                + "amount: " + amount + "\n"
                + "Reason: " + reason;
        String htmlBody = "<p>A refund request for serviceId=" + serviceId + " allocated " + amount + " to Sales "
                + "Receipt productId=" + productId + ", but creating that RefundReceipt in QuickBooks failed. "
                + "Any other Sales Receipts in the same refund request were still processed independently — "
                + "this one amount needs to be refunded manually.</p>"
                + "<p><b>serviceId:</b> " + serviceId + "<br>"
                + "<b>productId:</b> " + productId + "<br>"
                + "<b>amount:</b> " + amount + "<br>"
                + "<b>Reason:</b> " + reason + "</p>";

        sendAlert("RefundReceipt allocation failed", subject, textBody, htmlBody);
    }

    /**
     * Sent when {@code createPayment} fails for a non-{@code "Reserva"} Invoice — unlike the
     * CreditMemo/RefundReceipt alerts above, this failure is <b>not</b> best-effort: it still
     * fails the whole {@code CreateInvoiceWorkflow} (see docs/OPERATIONS.md §8), so the caller
     * already gets a 502. This email is an additional heads-up that the Invoice itself was
     * created and is sitting unpaid in QuickBooks until the Payment is created manually.
     */
    public void sendPaymentCreationFailedAlert(String serviceId, String invoiceDocNumber, String reason) {
        String subject = "QuickBooks: failed to create a Payment for an Invoice — action needed";
        String textBody = "Creating an Invoice for serviceId=" + serviceId + " (DocNumber=" + invoiceDocNumber
                + ") succeeded, but recording the Payment for its full amount failed. The Invoice was still "
                + "created and is left unpaid in QuickBooks — it needs a Payment created manually, and the "
                + "original request already received a 502 error.\n\n"
                + "serviceId: " + serviceId + "\n"
                + "invoice DocNumber: " + invoiceDocNumber + "\n"
                + "Reason: " + reason;
        String htmlBody = "<p>Creating an Invoice for serviceId=" + serviceId + " (DocNumber=" + invoiceDocNumber
                + ") succeeded, but recording the Payment for its full amount failed. The Invoice was still "
                + "created and is left unpaid in QuickBooks — it needs a Payment created manually, and the "
                + "original request already received a 502 error.</p>"
                + "<p><b>serviceId:</b> " + serviceId + "<br>"
                + "<b>invoice DocNumber:</b> " + invoiceDocNumber + "<br>"
                + "<b>Reason:</b> " + reason + "</p>";

        sendAlert("Payment creation failed", subject, textBody, htmlBody);
    }

    private void sendAlert(String logLabel, String subject, String textBody, String htmlBody) {
        if (!sendEnabled) {
            log.warn("Mailjet send disabled — skipping {} alert", logLabel);
            return;
        }

        try {
            MailjetClient client = new MailjetClient(publicKey, privateKey, new ClientOptions("v3.1"));
            MailjetRequest request = new MailjetRequest(Emailv31.resource)
                    .property(Emailv31.MESSAGES, new JSONArray()
                            .put(new JSONObject()
                                    .put(Emailv31.Message.FROM, new JSONObject()
                                            .put("Email", fromEmail)
                                            .put("Name", fromName))
                                    .put(Emailv31.Message.TO, new JSONArray()
                                            .put(new JSONObject().put("Email", adminEmail)))
                                    .put(Emailv31.Message.SUBJECT, subject)
                                    .put(Emailv31.Message.TEXTPART, textBody)
                                    .put(Emailv31.Message.HTMLPART, htmlBody)));

            MailjetResponse response = client.post(request);
            log.info("{} alert sent to {} – status={}", logLabel, adminEmail, response.getStatus());
        } catch (Exception e) {
            log.error("Failed to send {} alert email: {}", logLabel, e.getMessage(), e);
        }
    }
}
