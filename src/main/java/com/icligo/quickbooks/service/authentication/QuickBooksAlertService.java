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

/**
 * Sends a plain Mailjet email to the admin when the QuickBooks connection breaks, with a direct
 * link to the {@code /quickbooks/connect} re-authorization flow — see docs/OPERATIONS.md.
 * Same Mailjet SDK/call shape as notifications' MailJetClient, without the template plumbing
 * since this is a single fixed message.
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
        if (!sendEnabled) {
            log.warn("Mailjet send disabled — skipping QuickBooks disconnected alert (reason: {})", reason);
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
                                    .put(Emailv31.Message.SUBJECT, "QuickBooks connection lost — action needed")
                                    .put(Emailv31.Message.TEXTPART, textBody(reason))
                                    .put(Emailv31.Message.HTMLPART, htmlBody(reason))));

            MailjetResponse response = client.post(request);
            log.info("QuickBooks disconnected alert sent to {} – status={}", adminEmail, response.getStatus());
        } catch (Exception e) {
            log.error("Failed to send QuickBooks disconnected alert email: {}", e.getMessage(), e);
        }
    }

    private String htmlBody(String reason) {
        return "<p>The QuickBooks connection for the Invoice QuickBooks Service is broken and "
                + "invoices/receipts/refunds cannot be created until it's reconnected.</p>"
                + "<p><b>Reason:</b> " + reason + "</p>"
                + "<p>To fix it, open this link in a browser and authorize the app again:</p>"
                + "<p><a href=\"" + connectUri + "\">" + connectUri + "</a></p>";
    }

    private String textBody(String reason) {
        return "The QuickBooks connection for the Invoice QuickBooks Service is broken and "
                + "invoices/receipts/refunds cannot be created until it's reconnected.\n\n"
                + "Reason: " + reason + "\n\n"
                + "To fix it, open this link in a browser and authorize the app again:\n" + connectUri;
    }
}
