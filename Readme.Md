# Idempotency Gateway — IgirePay Technologies

A production-grade **Pay-Once Protocol** built in Java (Spring Boot). Ensures that no matter how many times a client sends the same payment request due to network retries, the customer is charged **exactly once**.
 
---

## Project Structure

```
src/
├── main/java/com/igirepay/gateway/
│   ├── IdempotencyGatewayApplication.java   ← Entry point
│   ├── config/
│   │   └── SchedulingConfig.java            ← Enables background jobs
│   ├── controller/
│   │   └── PaymentController.java           ← HTTP endpoints
│   ├── exception/
│   │   ├── GlobalExceptionHandler.java      ← Maps exceptions to JSON errors
│   │   └── IdempotencyConflictException.java
│   ├── model/
│   │   ├── AuditEvent.java                  ← One audit log entry
│   │   ├── IdempotencyRecord.java           ← Stored record per key
│   │   ├── PaymentRequest.java              ← Request body shape
│   │   └── PaymentResponse.java             ← Response body shape
│   └── service/
│       ├── AuditLogService.java             ← Developer's Choice 1: Audit Log
│       ├── IdempotencyKeyValidator.java     ← Developer's Choice 2: Key Validation
│       ├── IdempotencyStore.java            ← Thread-safe in-memory store
│       ├── KeyExpiryService.java            ← TTL expiry (24 hours)
│       └── PaymentService.java              ← Core business logic
└── test/java/com/igirepay/gateway/
    └── PaymentControllerIntegrationTest.java
```

---

## Setup Instructions

### Prerequisites

- Java 21+
- Maven 3.8+ (or use the included `./mvnw` wrapper)

```bash
# Verify your Java version
java -version
```

### Run the server

```bash
git clone https://github.com/Gertrude11/SheCanCode-associate-Assessment-.git
cd SheCanCode-associate-Assessment-
./mvnw spring-boot:run
```

The server starts on `http://localhost:8080`.

You should see this in the console:
```
Tomcat started on port(s): 8080
Started IdempotencyGatewayApplication
```

### Run the tests

```bash
./mvnw test
```

---

## API Documentation

### Base URL

```
http://localhost:8080/api/v1
```

### Important: Idempotency Key Format

All requests require a **UUID v4** formatted `Idempotency-Key` header.

✅ Valid: `550e8400-e29b-41d4-a716-446655440000`
❌ Invalid: `order-123`, `abc`, `""`

Generate one at https://www.uuidgenerator.net

---

### POST /process-payment

Processes a payment exactly once per unique `Idempotency-Key`.

**Required Headers**

| Header              | Value                        |
|---------------------|------------------------------|
| `Content-Type`      | `application/json`           |
| `Idempotency-Key`   | Valid UUID v4 string         |

**Request Body**

```json
{
  "amount": 100,
  "currency": "RWF"
}
```

| Field      | Type   | Required | Notes                    |
|------------|--------|----------|--------------------------|
| `amount`   | number | Yes      | Must be greater than 0   |
| `currency` | string | Yes      | e.g. RWF, USD, EUR       |

---

#### Example 1 — First Request (Happy Path)

```bash
curl -X POST http://localhost:8080/api/v1/process-payment \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000" \
  -d '{"amount": 100, "currency": "RWF"}'
```

**Response** `201 Created` — after 2 second processing delay

```json
{
  "status": "success",
  "message": "Charged 100 RWF",
  "transactionId": "TXN-A1B2C3D4",
  "processedAt": "2025-06-15T10:30:00Z"
}
```

---

#### Example 2 — Duplicate Request (Idempotency)

Send the exact same request again with the same key:

```bash
curl -X POST http://localhost:8080/api/v1/process-payment \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000" \
  -d '{"amount": 100, "currency": "RWF"}'
```

**Response** `200 OK` — returns instantly, no reprocessing

```
X-Cache-Hit: true
```

```json
{
  "status": "success",
  "message": "Charged 100 RWF",
  "transactionId": "TXN-A1B2C3D4",
  "processedAt": "2025-06-15T10:30:00Z"
}
```

Same `transactionId` confirms the customer was **not charged again**.

---

#### Example 3 — Key Conflict (Security Check)

Same key, different amount:

```bash
curl -X POST http://localhost:8080/api/v1/process-payment \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000" \
  -d '{"amount": 500, "currency": "RWF"}'
```

**Response** `422 Unprocessable Entity`

```json
{
  "error": "Idempotency key already used for a different request body.",
  "status": 422,
  "timestamp": "2025-06-15T10:31:00Z"
}
```

---

#### Example 4 — Invalid Key Format

```bash
curl -X POST http://localhost:8080/api/v1/process-payment \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: order-001" \
  -d '{"amount": 100, "currency": "RWF"}'
```

**Response** `422 Unprocessable Entity`

```json
{
  "error": "Idempotency key must be a valid UUID v4 format. Example: 550e8400-e29b-41d4-a716-446655440000.",
  "status": 422,
  "hint": "Generate a valid key with: UUID.randomUUID().toString()"
}
```

---

### GET /audit-log

Returns all audit events recorded since server start, newest first.

```bash
curl http://localhost:8080/api/v1/audit-log
```

**Response** `200 OK`

```json
[
  {
    "idempotencyKey": "550e8400-e29b-41d4-a716-446655440000",
    "eventType": "DUPLICATE_DETECTED",
    "details": "Replayed response for amount=100 RWF, transactionId=TXN-A1B2C3D4",
    "clientIp": "127.0.0.1",
    "timestamp": "2025-06-15T10:31:00Z"
  },
  {
    "idempotencyKey": "550e8400-e29b-41d4-a716-446655440000",
    "eventType": "PAYMENT_PROCESSED",
    "details": "Successfully charged 100 RWF, transactionId=TXN-A1B2C3D4",
    "clientIp": "127.0.0.1",
    "timestamp": "2025-06-15T10:30:00Z"
  }
]
```
---

### GET /audit-log/{key}

Returns all events for one specific idempotency key.

```bash
curl http://localhost:8080/api/v1/audit-log/550e8400-e29b-41d4-a716-446655440000
```
---



## Design Decisions

### Why `ConcurrentHashMap` instead of a database?

The brief permits an in-memory store. `ConcurrentHashMap` is thread-safe — if two requests arrive at the exact same millisecond, only one can write to a key slot. `putIfAbsent()` is an atomic operation that guarantees this. In production, this would be replaced with Redis which supports the same atomic operations and works across multiple servers.

---

### Why `synchronized + wait/notify` for in-flight requests?

When a duplicate arrives while the original is still processing, we don't start a new process or return an error. Instead we park the duplicate thread using Java's `record.wait()`. When the original completes it calls `record.notifyAll()`, waking all parked duplicates. They read the completed response and return it. This is zero-dependency and correct.

---

### Why `BigDecimal` instead of `double` for amounts?

`double` has floating-point precision issues in Java — `0.1 + 0.2` equals `0.30000000000000004`. In fintech that is a serious bug. `BigDecimal` is exact. We also use `compareTo()` instead of `equals()` so that `100` and `100.00` are treated as the same amount.

---

### Why keep the controller thin?

All logic lives in the service layer so tests don't need to spin up a full web server to test business rules. The controller only translates between HTTP and Java objects.

---

## Developer's Choice Features

### Feature 1 — Audit Log

**What it does**

Every meaningful event is recorded with a timestamp, event type, idempotency key, details, and the client IP address. Events are viewable via the `/audit-log` endpoint in real time.

**Event types tracked**

| Event | Meaning |
|-------|---------|
| `PAYMENT_PROCESSED` | New payment successfully charged |
| `DUPLICATE_DETECTED` | Same key replayed, customer not charged again |
| `CONFLICT_REJECTED` | Same key reused with a different request body |
| `INVALID_KEY_FORMAT` | Key failed UUID v4 format validation |

**Why this matters for fintech**

Financial regulators such as the Rwanda National Bank and PCI-DSS require that every payment attempt is fully traceable. If a customer disputes a charge, the audit log proves exactly what happened, at what time, and from which IP address. Without this, disputes cannot be investigated and compliance requirements cannot be satisfied. Every serious payment processor maintains an audit trail for exactly this reason.

---

### Feature 2 — Idempotency Key Format Validation

**What it does**

Enforces that all idempotency keys must follow UUID v4 format before any processing occurs. Keys like `order-123`, single characters, or blank strings are immediately rejected with a `422` response and a helpful hint pointing the developer to the correct format.

**Valid format**
```
550e8400-e29b-41d4-a716-446655440000
```

**Why this matters for fintech**

UUID v4 has 2^122 possible values making accidental key collisions virtually impossible. Short or arbitrary keys risk collisions which would cause legitimate payments to be incorrectly treated as duplicates — resulting in customers being unable to pay. Enforcing UUID format also matches the industry standard set by Stripe, PayPal, and other major payment processors, making integration straightforward for any developer already familiar with those APIs.