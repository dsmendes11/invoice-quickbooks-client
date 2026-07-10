# Operations runbook — QuickBooks connection

This service holds one QuickBooks OAuth connection (one company/`realmId`) per environment.
This doc covers keeping that connection alive and what can/can't be automated about it.

## 1. Reconnecting to QuickBooks (manual, by design)

Needed when: the refresh token has been revoked or expired (see §3), or you're connecting a
new environment/company for the first time.

1. **Check the redirect URI is registered with Intuit.** `quickbooks.oauth.redirect-uri` in
   [`application.yml`](../src/main/resources/application.yml) (currently
   `https://invoices.icligo.com/quickbooks/callback` for prod) must match, character for character
   (scheme, host, path, no trailing slash difference), an entry under **Keys & OAuth → Redirect
   URIs** for this app at [developer.intuit.com](https://developer.intuit.com). If it doesn't
   match, Intuit rejects the authorization before it ever reaches our `/callback`.
2. **Check the service is actually reachable at that host** (DNS/ingress/TLS) —
   `SecurityConfig` already leaves `/quickbooks/connect` and `/quickbooks/callback` public
   (`permitAll`), so once traffic reaches the service there's nothing else to configure there.
3. **Authorize.** Someone with access to the target QuickBooks company opens, in a normal
   browser:
   ```
   https://invoices.icligo.com/quickbooks/connect
   ```
   They're redirected to Intuit's consent screen, log in, and approve. Intuit calls back
   `/quickbooks/callback`, which exchanges the `code` for tokens and stores them in the
   `stored_tokens` Mongo collection — no manual token copy-pasting anywhere in this flow.
4. **Verify:**
   ```bash
   curl -i -H "auth-token: $AUTH_TOKEN" https://invoices.icligo.com/quickbooks/launch
   ```
   Expect `200` and `"...running (connected)."`. This forces a real round-trip to Intuit (see
   §4), so a `503` here means the connection is genuinely broken, not just "no cached token".

Repeat this once per environment (dev/staging/prod each authorize their own company/`realmId`)
and whenever a connection breaks. It is **not** part of the normal deploy — code deploys don't
touch the stored tokens.

## 2. Disconnecting

```bash
curl -H "auth-token: $AUTH_TOKEN" https://invoices.icligo.com/quickbooks/disconnect
```
Revokes the token at Intuit and clears local state. Requires the `auth-token` header (unlike
`connect`/`callback`, which Intuit's browser redirect hits directly and can't attach a header
to).

## 3. Why this can't be fully automated

The authorization step in §1.3 is a deliberate human action — it's QuickBooks/Intuit granting
*this app* permission to act on *a specific company's* books, on behalf of a logged-in Intuit
user. There's no service-account or machine-to-machine credential for this; scripting the
consent screen (e.g. headless browser + stored password) would mean storing a real human's
QuickBooks login somewhere, which is a worse security posture than the manual click, not a
better one. So: **the reconnect click itself stays manual.**

What *is* automatable, and what we haven't automated yet:

- **Token refresh** — already automatic. `OAuthService.refreshAccessToken()` runs
  transparently whenever the access token is close to expiry; no one needs to intervene for
  normal day-to-day expiry.
- **Detecting that the connection is broken** — automatable, see §4. `/quickbooks/launch`
  returns `503` on a real failure, so any uptime/health-check tool (Pingdom, an internal cron,
  a Kubernetes liveness-style probe hitting it periodically, etc.) can alert on non-2xx too.
- **Turning "broken" into a one-click fix for a human** — **done**, see §5. Any failed token
  refresh — whether triggered by `/quickbooks/launch`, by a real `/documents` request, or by
  the automatic background refresh — emails the admin with the `/quickbooks/connect` link
  directly, so recovery is "click the link in the email" rather than "go read this runbook
  first."

## 4. `/quickbooks/launch` is a real health check

`OAuthService.verifyConnection()` (called by `/quickbooks/launch`) forces a token refresh
round-trip to Intuit on every call and returns `503` if that fails — not just "is *a* refresh
token string cached locally" (`isConnected()`/`hasRefreshToken()`, still used internally, but no
longer what `/launch` reports). This is what caught the sandbox connection actually being dead
(the `invalid_grant` failures found while testing the idempotency work) instead of reporting a
false "connected". Because every call rotates the refresh token, don't poll this at high
frequency — every few minutes is plenty for a monitor.

## 5. Admin email alert on disconnect

[`QuickBooksAlertService`](../src/main/java/com/icligo/quickbooks/service/authentication/QuickBooksAlertService.java)
sends a plain email via Mailjet — without the template plumbing since this is one fixed message — to
`mailjet.admin-email` (`diogomendes@icligo.com` by default) whenever a token refresh fails,
with the `/quickbooks/connect` link in the body.

It's wired into [`OAuthService.refreshAccessToken()`](../src/main/java/com/icligo/quickbooks/service/authentication/OAuthService.java)
and is **edge-triggered**: it fires once when a refresh fails and goes quiet until a refresh
succeeds again (`disconnectedAlertSent` flag), so a broken connection sends exactly one email,
not one per retry/request. It fires regardless of *what* triggered the failing refresh —
`/quickbooks/launch`, a real `/documents` request, or the lazy refresh in `getAccessToken()` —
since they all go through the same method.

**Config** (`application.yml` / env vars):

| Property | Source | Notes |
|---|---|---|
| `mailjet.public-key` / `mailjet.private-key` | `MAILJET_PUBLIC_KEY` / `MAILJET_PRIVATE_KEY` env vars | same env var names as ops-reaktor's `notifications` module |
| `mailjet.from-email` / `mailjet.from-name` | `application.yml` | sender shown to the admin |
| `mailjet.admin-email` | `application.yml`, defaults to `diogomendes@icligo.com` | alert recipient |
| `mailjet.send-enabled` | `application.yml`, defaults to `true` | kill switch — set `false` to disable sending without removing credentials |

If `MAILJET_PUBLIC_KEY`/`MAILJET_PRIVATE_KEY` aren't set in an environment, Mailjet rejects the
send with 401 and the failure is logged and swallowed — it never blocks or fails the request
that triggered it (a broken alert channel must not also break `/documents`).

## 6. Booking (Reserva) invoices cancel prior Sales Receipts

When an Invoice is created with `productType=Reserva`,
[`CreateInvoiceWorkflowImpl`](../src/main/java/com/icligo/quickbooks/temporal/workflow/CreateInvoiceWorkflowImpl.java)
calls [`SalesReceiptCancellationService`](../src/main/java/com/icligo/quickbooks/service/SalesReceiptCancellationService.java),
which uses [`ActiveSalesReceiptFinder`](../src/main/java/com/icligo/quickbooks/service/ActiveSalesReceiptFinder.java)
to find every Sales Receipt already on file for that `serviceId` (in our own Mongo, by
`type`+`serviceId` — QuickBooks has no `serviceId` field to query by) that still has an open
balance, and cancels each one in QuickBooks via a CreditMemo, since the booking invoice now
supersedes what was originally billed as a prepaid sale.

- **"Open balance"** = QuickBooks `TotalAmt` minus every Refund Receipt already on file for the
  same `productId` — the same accounting `RefundReceiptAllocationService` (§7) uses, via the
  same `ActiveSalesReceiptFinder`, so both flows agree on what "already refunded" means. A Sales
  Receipt with nothing left open is skipped entirely (no CreditMemo).
- The CreditMemo's lines mirror the original Sales Receipt's lines, scaled down proportionally
  to the open balance (not one lump-sum line), so the credit's detail matches what was actually
  sold.
- **Best-effort, not transactional with the Invoice**: a failure cancelling one Sales Receipt
  (e.g. one of its line Items no longer exists in QuickBooks) is caught per-receipt — it never
  fails the Invoice creation, and doesn't stop other matching Sales Receipts from being
  cancelled. Instead, it emails the admin (`sendCreditMemoCancellationFailedAlert`, same Mailjet
  mechanism as §5) with the `serviceId`/`productId`/reason, since that Sales Receipt is left
  open in QuickBooks and needs manual cancellation.

## 7. Refunds are allocated across open Sales Receipts, not caller-specified

`POST {api.base-path}/refunds` (see docs/CLIENT_INTEGRATION.md §4) is the **only** way to
create a RefundReceipt — `POST /documents` rejects `type=RRT` outright
(`QuickBooksDocumentValidator`). This mirrors the invoice-management-system's
`createRefundInvoices`/NCA handling: the caller states a `serviceId` and an amount, and
[`RefundReceiptAllocationService`](../src/main/java/com/icligo/quickbooks/service/RefundReceiptAllocationService.java)
determines which Sales Receipts absorb it.

- Uses the same [`ActiveSalesReceiptFinder`](../src/main/java/com/icligo/quickbooks/service/ActiveSalesReceiptFinder.java)
  as §6 to find every Sales Receipt still open for the `serviceId`, then splits the requested
  value across them proportionally to each one's open balance (last allocation absorbs the
  rounding remainder). If the requested value is at least the total open balance, every open
  Sales Receipt is fully consumed and the excess is silently ignored — never an error.
- Each allocation becomes its own RefundReceipt via the normal `TemporalDocumentService.create`
  path, so controlKey generation (`type + productId + "_rfd" + refundId + serie`), idempotency,
  and QuickBooks creation are identical to any other RefundReceipt — just driven by the
  allocation instead of a caller-supplied `productId`/`items`.
- **Best-effort per allocation**, matching the reference implementation: a failure creating one
  allocation's RefundReceipt (e.g. that Sales Receipt's Item no longer exists) doesn't stop the
  other allocations in the same request — it emails the admin
  (`sendRefundAllocationFailedAlert`, same Mailjet mechanism as §5) with the
  `serviceId`/`productId`/`amount`/reason, since that portion of the refund needs to be handled
  manually.

## 8. Non-booking invoices get a Payment recorded immediately

`productType=Reserva` is the only case where an Invoice is left as an open, unpaid receivable
(§6 handles what happens to it instead). For every other `productType` (including none) —
[`CreateInvoiceWorkflowImpl`](../src/main/java/com/icligo/quickbooks/temporal/workflow/CreateInvoiceWorkflowImpl.java)
calls the `createPayment` activity right after `createInvoice`, applying a QuickBooks Payment
for the Invoice's full `TotalAmt`, linked to that Invoice (`LinkedTxn`: `TxnType=Invoice`), so
it shows as paid immediately rather than sitting on Accounts Receivable.

- Same customer, same `paymentMethod` (if the request sent one — resolved the same way as
  Sales Receipts/Refund Receipts, see docs/CLIENT_INTEGRATION.md §3), and the same deposit
  Account (`quickbooks.deposit.sales-refund-account-name`) as every other money-receiving
  transaction this service creates — Payment is one of the few QuickBooks entities (along with
  SalesReceipt/RefundReceipt) that supports `DepositToAccountRef` at all.
- **Not best-effort**: unlike §6/§7, a failure creating the Payment fails the whole
  `CreateInvoiceWorkflow` (same retry/failure semantics as `createInvoice` itself) — the Invoice
  and its Payment are meant to exist together, not one without the other.
