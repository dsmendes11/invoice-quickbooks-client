# Integrating with Invoice QuickBooks Service

This service creates QuickBooks **invoices** and **sales receipts**, and issues **refunds**
against sales receipts, on behalf of other icligo services. It talks to QuickBooks itself —
callers never touch QuickBooks credentials or OAuth directly. Refunds are **allocation-driven**
(§4), not caller-specified: you state a `serviceId` and an amount, not a specific document.

Interactive schema browser: **Swagger UI** at `/swagger-ui/index.html` (raw spec at
`/v3/api-docs`). This document covers everything the Swagger UI doesn't: auth setup,
per-type payload rules, error semantics, and latency/retry expectations.

## 1. Base URL

| Environment | URL |
|---|---|
| Local dev | `http://localhost:9191` |
| Production | `https://invoices.icligo.com` |

All business endpoints are under `${api.base-path}` = `/invoice-quickbooks-service/v1`.

## 2. Authentication

**There is no token issuance, expiry, or refresh on the API side.** Every request must carry
one static header, whose name and value are both server config (`spring.application.tokenName`
/ `tokenValue`):

```
auth-token: <the shared secret — ask the platform team for the current value, do not commit it>
```

That's the entire contract described in the OAuth-verification questionnaire this work started
from: QuickBooks-side OAuth (token acquisition/refresh) is handled **entirely inside this
service** — client apps never see a QuickBooks token, only this one static header.

- Missing or wrong value → `401 Unauthorized`, empty body.
- The same header is required on every call, including the interactive "Try it out" in Swagger
  UI — click **Authorize** (top right) and paste the value once; it's attached to every
  subsequent try-it-out call.
- This is a single shared secret for all callers, not a per-client key — there's no way to
  identify *which* calling service made a request from the auth layer alone. If you need that
  for auditing, put a client identifier in your own request logs.

## 3. Create a document — `POST /invoice-quickbooks-service/v1/documents`

One endpoint creates Invoices and Sales Receipts; `type` selects which. **RefundReceipts
cannot be created here** — `type=RRT` is rejected outright (`400`); see §4.

### 3.1 Common request fields

| Field | Type | Required | Notes |
|---|---|---|---|
| `type` | string | **yes** | abbreviated code, not the long-form name: `INV` (Invoice) or `SRT` (SalesReceipt) (case-insensitive). `RRT` is rejected — refunds go through `POST /refunds` (§4) |
| `clientInvoiceInfo` | object | **yes** | see below — customer is found-or-created automatically |
| `serviceId` | string | **yes, for every type** | becomes QuickBooks `DocNumber` = `"INV" + serviceId` when `type=INV`; required for every type regardless, for cross-type context — does **not** feed into the internal `controlKey` (see §8). Also what `POST /refunds` matches Sales Receipts by |
| `productId` | string | **yes, for every type** | becomes `DocNumber` = `"SRC"+productId` when `type=SRT`; required for every type regardless, and **is** the identifier used in the internal `controlKey` (see §8) |
| `description` | string | no | mapped to the QuickBooks document's customer memo |
| `microsite` | string | no | defaults to `"icligous"` |
| `paymentMethod` | integer | no | used for `SRT`, and for `INV` when `productType` isn't `"Reserva"` (see below — carried onto the Payment that's created). Every non-null value currently resolves to the QuickBooks PaymentMethod **"Credit Card"** (per-code mapping isn't implemented yet) — the request fails (`502`) if that PaymentMethod doesn't exist in the company. Omitted/`null` → no payment method sent at all, QuickBooks applies the account default. Carried over automatically onto any RefundReceipt `POST /refunds` later creates against a Sales Receipt |
| `items` | array | **yes, at least one** | line items, see 3.3 — every `item` must match an existing QuickBooks Item name, values must be non-negative, and the total must be non-zero |
| `productType` | string | no | only meaningful when `type=INV`. If `"Reserva"`, any Sales Receipts already on file for this `serviceId` are cancelled via CreditMemo (docs/OPERATIONS.md §6) and the Invoice is left open/unpaid. **Any other value, or omitted, means the Invoice is paid immediately**: a QuickBooks Payment for the Invoice's full amount is created and applied to it (docs/OPERATIONS.md §8) |

Fields accepted but **not currently applied** to the QuickBooks document — safe to omit,
don't rely on them: `invoiceType`.

Every `SRT` document, and every `INV` whose Payment gets created (i.e. `productType` isn't
`"Reserva"`, §8), is deposited into a fixed, server-configured QuickBooks Account
(`DepositToAccountRef`, default **"1030 - Stripe/Paypal"**, configurable via
`quickbooks.deposit.sales-refund-account-name` / `QUICKBOOKS_DEPOSIT_SALES_REFUND_ACCOUNT_NAME`)
— this isn't a request field, it applies unconditionally (and to any RefundReceipt later
created against a Sales Receipt via §4), and the request fails (`502`) if that Account doesn't
exist in the company. A `"Reserva"` `INV` has no equivalent: QuickBooks' Invoice entity itself
has no deposit-account field at all (only Payment/SalesReceipt/RefundReceipt support
`DepositToAccountRef`; an invoice just posts to Accounts Receivable) — which is exactly why a
non-`"Reserva"` `INV` needs a separate Payment created to actually mark it paid.

`controlKey` and `serie` are server-computed (see §8) — both are ignored if sent on a request,
and both are populated back on the response. `controlKey` is the identifier used to fetch the
document's PDF (§5) and is stable/repeatable — see §8 for exactly how it's built.

### 3.2 `clientInvoiceInfo`

Used to find-or-create the QuickBooks customer (matched by email/name).

| Field | Notes |
|---|---|
| `name`, `address`, `country` | **required** |
| `email`, `phone`, `city`, `zipCode` | optional, mapped to the customer's email/phone/billing address |
| `nif`, `clientCountry`, `finalCustomer` | accepted, not currently applied |
| `clientId`, `clientHash` | **response-only** — ignore on request, this is how you get the resolved QuickBooks customer id back |

### 3.3 `items[]`

| Field | Notes |
|---|---|
| `item` | line description → QuickBooks line `Description` |
| `value` | line amount → both `Amount` and `UnitPrice` (`Qty` is always `1`) |
| `locator` | joined across every item with `\|` and set as: the **"Reference no."** (`PaymentRefNum`) on Sales Receipts; the **"Memo"** (`PrivateNote`) on Invoices, Refund Receipts (§4), and the CreditMemos created when cancelling Sales Receipts (docs/OPERATIONS.md §6) |
| `description`, `tax`, `discount`, `itemDate` | accepted, **not currently applied** — don't rely on these for tax/discount logic today |

### 3.4 Examples

**Invoice:**
```bash
curl -X POST https://invoices.icligo.com/invoice-quickbooks-service/v1/documents \
  -H "auth-token: $AUTH_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "type": "INV",
    "serviceId": "48213",
    "productId": "70021",
    "description": "Booking #48213",
    "clientInvoiceInfo": {
      "name": "Jane Doe",
      "email": "jane.doe@example.com",
      "address": "Rua Example 123",
      "country": "PT"
    },
    "items": [
      { "item": "Airport transfer", "value": 49.90 },
      { "item": "Child seat", "value": 10.00 }
    ]
  }'
```

**Sales receipt** (paid at time of sale — `paymentMethod` instead of relying on the QB default; `serviceId`/`productId` are both still required):
```bash
curl -X POST https://invoices.icligo.com/invoice-quickbooks-service/v1/documents \
  -H "auth-token: $AUTH_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "type": "SRT",
    "serviceId": "48213",
    "productId": "70021",
    "paymentMethod": 1,
    "clientInvoiceInfo": { "name": "Jane Doe", "address": "Rua Example 123", "country": "PT", "email": "jane.doe@example.com" },
    "items": [ { "item": "Day tour", "value": 89.00 } ]
  }'
```

### 3.5 Response — `201 Created`

Same shape as the request, plus:
- `id` — Mongo id of the saved record (internal; there's no GET endpoint for the JSON document itself yet, only direct DB access)
- `controlKey` — the server-computed idempotency key (§8) and the public identifier for this document — use it with §5 to fetch the PDF
- `documentPDF` — relative link to this document's PDF, equivalent to `GET {api.base-path}/documents/{controlKey}/pdf` (§5) — a convenience so you don't have to build the URL yourself
- `invoice` — the actual QuickBooks object (`Invoice`/`SalesReceipt`) with its QuickBooks `Id`, `TotalAmt`, `Balance`, etc. For a non-`"Reserva"` `INV`, this is still the **Invoice**, not the Payment created alongside it (docs/OPERATIONS.md §8) — the Payment's own QuickBooks `Id` isn't returned here; look it up in QuickBooks by the Invoice's `Id` (`LinkedTxn`) if you need it
- `clientInvoiceInfo.clientId` / `clientHash` — the resolved QuickBooks customer id

## 4. Refund a Sales Receipt — `POST /invoice-quickbooks-service/v1/refunds`

RefundReceipts are **allocation-driven**, not caller-specified: you state a `serviceId` and
an amount to refund, not a `productId`/`items`/which document. The service finds every Sales
Receipt still open for that `serviceId` and allocates the requested value across them —
mirroring the invoice-management-system's `createRefundInvoices`/NCA handling.

### 4.1 Request fields

| Field | Type | Required | Notes |
|---|---|---|---|
| `serviceId` | string | **yes** | matches the `serviceId` used when the original Sales Receipt(s) were created |
| `refundId` | string | **yes** | identifies this refund transaction — embedded (as `"_rfd" + refundId`) in the `controlKey` of every RefundReceipt this call creates |
| `value` | number | **yes, > 0** | total amount to refund, before allocation |

### 4.2 Allocation behavior

1. **"Open"** means: QuickBooks `TotalAmt` minus every RefundReceipt already created against
   the same `productId` (via this endpoint or otherwise) is greater than zero. Sales Receipts
   with nothing left to refund are excluded entirely.
2. If no open Sales Receipts exist for `serviceId` → `400`.
3. If `value` is **less than** the total open balance across all of them, it's split
   **proportionally** to each one's remaining balance (rounding remainder absorbed by the last
   allocation, so the sum always equals `value` exactly).
4. If `value` is **greater than or equal to** the total open balance, every open Sales Receipt
   is fully refunded and **the excess is silently ignored** — you will not get an error, and
   you will not get back more RefundReceipts' worth than what was actually open.
5. One RefundReceipt is created per Sales Receipt that received a non-zero allocation — so a
   single call can create zero (nothing open), one, or several RefundReceipts.
6. Each created RefundReceipt's line mirrors the original Sales Receipt's *first* item
   (description) with the allocated amount as its value — not a proportional split across every
   original line (contrast with the CreditMemo cancellation in docs/OPERATIONS.md §6, which
   *does* split across all original lines; this endpoint intentionally follows the reference
   implementation's simpler "first item" convention instead).

### 4.3 Example

```bash
curl -X POST https://invoices.icligo.com/invoice-quickbooks-service/v1/refunds \
  -H "auth-token: $AUTH_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "serviceId": "48213",
    "refundId": "rfd-9931",
    "value": 40.00
  }'
```

### 4.4 Response — `200 OK`

A JSON array of the created documents (same shape as §3.5) — **can be empty** if every
allocation failed (see §4.5), or contain fewer entries than the number of open Sales Receipts
if only some allocations succeeded.

### 4.5 Partial failure is best-effort, not transactional

If creating the RefundReceipt for one allocation fails (e.g. that Sales Receipt's Item no
longer exists in QuickBooks), it does **not** stop the others in the same request — the
response simply omits that one, and an admin is emailed the details (see
docs/OPERATIONS.md §7) since that portion of the refund needs to be handled manually. Check
whether the response array's length matches what you expected rather than assuming a `200`
means every allocation succeeded.

## 5. Get a document's PDF

Two `GET` endpoints, both looked up by `controlKey` (the value returned in §3.5/§4.4 —
equivalently, just follow the `documentPDF` link on the document). QuickBooks generates the
PDF live on every call; nothing is cached on this service's side, so every request re-fetches
from QuickBooks.

| Endpoint | Returns |
|---|---|
| `GET /invoice-quickbooks-service/v1/documents/{controlKey}/pdf` | Raw PDF bytes, `Content-Type: application/pdf`, `Content-Disposition: attachment; filename="{controlKey}.pdf"` |
| `GET /invoice-quickbooks-service/v1/documents/{controlKey}/pdf/base64` | The same PDF content, base64-encoded, as a plain-text body |

```bash
curl -H "auth-token: $AUTH_TOKEN" \
  https://invoices.icligo.com/invoice-quickbooks-service/v1/documents/<controlKey>/pdf \
  -o document.pdf
```

Errors: `404` if no document exists with that `controlKey`; `502` if QuickBooks rejects or
fails the PDF request (same semantics as §6). CreditMemos are covered too (`type=CDM` in the
response, see docs/OPERATIONS.md §6) — they're persisted like every other document type,
including their own `controlKey`/`documentPDF`, even though `POST /documents` itself never
accepts `type=CDM` as input (system-generated only).

## 6. Error responses

Every error returns a JSON body shaped like:

```json
{
  "timestamp": "2026-07-07T15:29:53.942206Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Request failed validation",
  "details": ["clientInvoiceInfo: clientInvoiceInfo is required", "serviceId: serviceId is required"]
}
```

| Status | Meaning | Retry? |
|---|---|---|
| `201` | Created | — |
| `400` | Request failed validation — missing/invalid `type`, `type=RRT`/`type=CDM` on `/documents` (§3), missing `clientInvoiceInfo`, missing `serviceId`/`productId` (required for every type), no open Sales Receipts / non-positive `value` on `/refunds` (§4), or missing `clientInvoiceInfo.name`/`address`/`country`. `details` lists every violation where applicable. | No — fix the payload |
| `401` | Missing/wrong `auth-token` header | No — fix the header |
| `502` | QuickBooks itself (or the Temporal workflow orchestrating the call) rejected or failed the request — e.g. invalid customer data QuickBooks itself rejects, QuickBooks API outage, an expired/revoked QuickBooks OAuth connection on this service's side, the configured PaymentMethod/deposit-Account not existing in the company (`SRT`, or `INV` when `productType` isn't `"Reserva"`, §8), or the Payment itself failing to create for a non-`"Reserva"` `INV` — that failure fails the whole request, the Invoice is not left half-created | Transient causes: yes, with backoff. A missing PaymentMethod/Account is a standing config problem in QuickBooks, not transient — retrying won't help until it's created there |
| `500` | Unexpected internal error | Yes, with backoff |

`message` is safe to log; don't parse it programmatically — branch on `status` only. The
`502` case does not currently forward QuickBooks' own error code/message verbatim (it's
folded into `message`), so if you need the raw QuickBooks fault for support tickets, capture
the full response body and this service's own logs (correlate by timestamp).

## 7. Timeouts and retries — what to expect on your side

Each document creation runs as a Temporal workflow with its own internal retries against
QuickBooks: the customer find-or-create step retries up to 5 times (2s/4s/8s/16s backoff,
30s timeout per attempt) and, only if that succeeds, the document-creation step retries up
to 3 times (3s/6s/12s backoff, 45s timeout per attempt). That means:

- A **successful** call typically completes in well under a second.
- A call that hits **transient** QuickBooks/network errors can take **up to ~2 minutes**
  in the worst case (both steps timing out on every attempt) before this service gives up
  and returns `502` — the retries happen server-side, you don't need your own retry-on-5xx
  logic for transient failures, though it doesn't hurt to have one with a sane cap.
- Set your HTTP client timeout to **at least 120s** for this endpoint to avoid client-side
  timeouts racing the server-side retry loop. `POST /refunds` can create multiple documents
  sequentially, so budget accordingly if several Sales Receipts are open for a `serviceId`.
- A client-side timeout does **not** mean the request failed server-side — see §8 before
  deciding whether it's safe to retry.

## 8. Idempotency

**Repeating an already-completed request is safe.** Document creation is keyed by a
server-computed `controlKey` = `type + productId + suffix + serie`, where `suffix` is
`"_rfd" + refundId` for `RRT` (empty otherwise) and `serie` is the **current
year** — this matches the invoice-management-system's own `checkAndCreateChaveControlo`
exactly, and applies identically whether a RefundReceipt was created via `POST /refunds`'
allocation logic or (internally) any other path. Note that **`serviceId` is not part of the
key** — even though it's required on every request (for cross-type context), only
`productId` drives idempotency matching. `controlKey` and `serie` are always computed
server-side; you cannot set or influence them from the request. If a document already exists
for that key, a repeat request with the same `type`/`productId`[/`refundId`] returns the
**existing** document (same status code, no new QuickBooks call, no duplicate) instead of
creating a second one — regardless of what `serviceId` is sent. This is what makes "retry
after timeout/502" generally safe — reuse the same `productId`/`refundId` on retry and, if
the original attempt actually succeeded, you get the original document back. For `POST
/refunds`, this means retrying with the same `serviceId`/`refundId`/`value` re-runs the same
allocation and hits the same per-Sales-Receipt controlKeys, so already-created RefundReceipts
come back as-is rather than duplicating.

CreditMemos (`type=CDM`, docs/OPERATIONS.md §6) follow the same `controlKey` formula with no
suffix (`"CDM" + productId + serie`) — if a booking Invoice's cancellation step is retried
(e.g. a Temporal activity retry), it's skipped rather than cancelling the same Sales Receipt
twice, exactly like every other type here.

**Year boundary:** because `serie` is embedded in the key, a repeat of the same `productId`
in a **following calendar year** is treated as a brand-new document, not a replay — it will
create a second QuickBooks document. This only matters if you retry a request across a year
boundary (e.g. a request from Dec 31 retried on Jan 1); same-year retries are unaffected.

**The one gap:** this only covers *retries of a request that already finished*. If two
requests for the same brand-new key race each other within the same few hundred milliseconds
(before either has been persisted), both can reach QuickBooks and one of them will fail —
surfaced as `502` for the loser, not `409`, since the failure happens inside the Temporal
workflow (see §6). This is a narrow window and not a concern for sequential retries; it
mainly matters if your own service could plausibly fire two near-simultaneous requests for
the same `serviceId`/`productId`/`refundId` (e.g. a double-click or a duplicate queue
message) — in that case, dedupe on your side before calling, if you can.

## 9. Getting your `auth-token` value

The value lives in this service's `application.yml` (`spring.application.tokenValue`) —
ask whoever manages that deployment for the current value rather than reading it out of a
shared doc. It's the same value for every caller today, so treat it as a shared secret and
don't log it.
