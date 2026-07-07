# Integrating with Invoice QuickBooks Service

This service creates QuickBooks **invoices**, **sales receipts** and **refund receipts**
on behalf of other icligo services. It talks to QuickBooks itself — callers never touch
QuickBooks credentials or OAuth directly.

Interactive schema browser: **Swagger UI** at `/swagger-ui/index.html` (raw spec at
`/v3/api-docs`). This document covers everything the Swagger UI doesn't: auth setup,
per-type payload rules, error semantics, and latency/retry expectations.

## 1. Base URL

| Environment | URL |
|---|---|
| Local dev | `http://localhost:9191` |
| Production | `https://api.icligo.com` *(confirm with the team that owns the ingress — this is inferred from the QuickBooks OAuth redirect URI, not a dedicated deploy config)* |

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

One endpoint creates all three document types; `type` selects which.

### 3.1 Common request fields

| Field | Type | Required | Notes |
|---|---|---|---|
| `type` | string | **yes** | `INVOICE`, `SALES_RECEIPT`, or `REFUND_RECEIPT` (case-insensitive) |
| `clientInvoiceInfo` | object | **yes** | see below — customer is found-or-created automatically |
| `serviceId` | string | required if `type=INVOICE` | becomes QuickBooks `DocNumber` = `"INV" + serviceId` |
| `productId` | string | required if `type=SALES_RECEIPT` or `REFUND_RECEIPT` | becomes `DocNumber` = `"SRC"+productId` or `"RRC"+productId+"_rfd"+refundId` |
| `refundId` | string | required if `type=REFUND_RECEIPT` | see above |
| `description` | string | no | mapped to the QuickBooks document's customer memo |
| `microsite` | string | no | defaults to `"icligousa"` |
| `paymentMethod` | integer | no | only used for `SALES_RECEIPT`/`REFUND_RECEIPT`. `1`→credit_card, `2`→debit_card, any other non-null value→`other`. Omitted/`null` → no payment method sent at all, QuickBooks applies the account default |
| `items` | array | no | line items, see 3.3. Empty/omitted → document created with zero lines |

Fields accepted but **not currently applied** to the QuickBooks document — safe to omit,
don't rely on them: `controlKey`, `serie`, `productType`, `invoiceType`.

### 3.2 `clientInvoiceInfo`

Used to find-or-create the QuickBooks customer (matched by email/name).

| Field | Notes |
|---|---|
| `name`, `email`, `address`, `country` | **at least one of these four is required** — a request with all four blank fails validation |
| `phone`, `city`, `zipCode` | optional, mapped to the customer's phone/billing address |
| `country` | defaults to `"US"` in QuickBooks if blank |
| `nif`, `clientCountry`, `finalCustomer` | accepted, not currently applied |
| `clientId`, `clientHash` | **response-only** — ignore on request, this is how you get the resolved QuickBooks customer id back |

### 3.3 `items[]`

| Field | Notes |
|---|---|
| `item` | line description → QuickBooks line `Description` |
| `value` | line amount → both `Amount` and `UnitPrice` (`Qty` is always `1`) |
| `description`, `tax`, `discount`, `locator`, `itemDate` | accepted, **not currently applied** — don't rely on these for tax/discount logic today |

### 3.4 Examples

**Invoice:**
```bash
curl -X POST https://api.icligo.com/invoice-quickbooks-service/v1/documents \
  -H "auth-token: $AUTH_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "type": "INVOICE",
    "serviceId": "48213",
    "description": "Booking #48213",
    "clientInvoiceInfo": {
      "name": "Jane Doe",
      "email": "jane.doe@example.com",
      "country": "PT"
    },
    "items": [
      { "item": "Airport transfer", "value": 49.90 },
      { "item": "Child seat", "value": 10.00 }
    ]
  }'
```

**Sales receipt** (paid at time of sale — needs `productId` + `paymentMethod` instead of `serviceId`):
```bash
curl -X POST https://api.icligo.com/invoice-quickbooks-service/v1/documents \
  -H "auth-token: $AUTH_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "type": "SALES_RECEIPT",
    "productId": "70021",
    "paymentMethod": 1,
    "clientInvoiceInfo": { "name": "Jane Doe", "email": "jane.doe@example.com" },
    "items": [ { "item": "Day tour", "value": 89.00 } ]
  }'
```

**Refund receipt** (also needs `refundId`):
```bash
curl -X POST https://api.icligo.com/invoice-quickbooks-service/v1/documents \
  -H "auth-token: $AUTH_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "type": "REFUND_RECEIPT",
    "productId": "70021",
    "refundId": "rfd-9931",
    "clientInvoiceInfo": { "name": "Jane Doe", "email": "jane.doe@example.com" },
    "items": [ { "item": "Day tour refund", "value": 89.00 } ]
  }'
```

### 3.5 Response — `201 Created`

Same shape as the request, plus:
- `id` — Mongo id of the saved record (use this if you need to look the document up later; there's no GET endpoint yet, only direct DB access)
- `invoice` — the actual QuickBooks object (`Invoice`/`SalesReceipt`/`RefundReceipt`) with its QuickBooks `Id`, `TotalAmt`, `Balance`, etc.
- `clientInvoiceInfo.clientId` / `clientHash` — the resolved QuickBooks customer id

## 4. Error responses

Every error returns a JSON body shaped like:

```json
{
  "timestamp": "2026-07-07T15:29:53.942206Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Request failed validation",
  "details": ["clientInvoiceInfo: clientInvoiceInfo is required", "serviceId: is required when type is INVOICE"]
}
```

| Status | Meaning | Retry? |
|---|---|---|
| `201` | Created | — |
| `400` | Request failed validation — missing/invalid `type`, missing `clientInvoiceInfo`, missing `serviceId`/`productId`/`refundId` for the given `type`, or all four customer identifiers blank. `details` lists every violation. | No — fix the payload |
| `401` | Missing/wrong `auth-token` header | No — fix the header |
| `502` | QuickBooks itself (or the Temporal workflow orchestrating the call) rejected or failed the request — e.g. invalid customer data QuickBooks itself rejects, QuickBooks API outage, or (currently) an expired/revoked QuickBooks OAuth connection on this service's side | Yes, with backoff — this is this service's/QuickBooks' problem, not the payload |
| `500` | Unexpected internal error | Yes, with backoff |

`message` is safe to log; don't parse it programmatically — branch on `status` only. The
`502` case does not currently forward QuickBooks' own error code/message verbatim (it's
folded into `message`), so if you need the raw QuickBooks fault for support tickets, capture
the full response body and this service's own logs (correlate by timestamp).

## 5. Timeouts and retries — what to expect on your side

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
  timeouts racing the server-side retry loop.
- A client-side timeout does **not** mean the request failed server-side — see §6 before
  deciding whether it's safe to retry.

## 6. Idempotency

**Repeating an already-completed request is safe.** Document creation is keyed internally
by `(type, serviceId)` for `INVOICE`, `(type, productId)` for `SALES_RECEIPT`, or
`(type, productId, refundId)` for `REFUND_RECEIPT`. If a document already exists for that
key, a repeat `POST /documents` with the same key returns the **existing** document
(same `201`, no new QuickBooks call, no duplicate) instead of creating a second one. This
is what makes "retry after timeout/502" generally safe — reuse the same `serviceId`/
`productId`/`refundId` on retry and, if the original attempt actually succeeded, you get
the original document back.

**The one gap:** this only covers *retries of a request that already finished*. If two
requests for the same brand-new key race each other within the same few hundred milliseconds
(before either has been persisted), both can reach QuickBooks and one of them will fail —
surfaced as `502` for the loser, not `409`, since the failure happens inside the Temporal
workflow (see §4). This is a narrow window and not a concern for sequential retries; it
mainly matters if your own service could plausibly fire two near-simultaneous requests for
the same `serviceId`/`productId`/`refundId` (e.g. a double-click or a duplicate queue
message) — in that case, dedupe on your side before calling, if you can.

## 7. Getting your `auth-token` value

The value lives in this service's `application.yml` (`spring.application.tokenValue`) —
ask whoever manages that deployment for the current value rather than reading it out of a
shared doc. It's the same value for every caller today, so treat it as a shared secret and
don't log it.
