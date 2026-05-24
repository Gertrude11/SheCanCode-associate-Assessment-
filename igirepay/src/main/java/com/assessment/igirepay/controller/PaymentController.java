package com.assessment.igirepay.controller;

import com.assessment.igirepay.model.PaymentRequest;
import com.assessment.igirepay.model.PaymentResponse;
import com.assessment.igirepay.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * The HTTP entry point for the payment API.
 *
 * This controller is intentionally thin. Its only job is to:
 *   1. Accept HTTP requests
 *   2. Extract and validate input
 *   3. Delegate to PaymentService
 *   4. Wrap the result in the right HTTP response
 *
 * Business logic lives in PaymentService, not here.
 */
@RestController
@RequestMapping("/api/v1")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * POST /api/v1/process-payment
     *
     * Required header: Idempotency-Key: <unique-string>
     * Body: { "amount": 100, "currency": "RWF" }
     *
     * Returns:
     *   201 Created        → new payment processed
     *   200 OK             → duplicate request, replayed from cache (+ X-Cache-Hit: true header)
     *   400 Bad Request    → missing header or invalid body
     *   422 Unprocessable  → key reused with a different body
     */
    @PostMapping("/process-payment")
    public ResponseEntity<PaymentResponse> processPayment(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PaymentRequest request) {

        // Validate the key isn't just whitespace
        if (idempotencyKey.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        PaymentService.PaymentResult result = paymentService.processPayment(idempotencyKey, request);

        if (result.cacheHit()) {
            // Duplicate request — return EXACT same body, status 200, with cache header
            return ResponseEntity.ok()
                    .header("X-Cache-Hit", "true")
                    .header("Idempotency-Key", idempotencyKey)
                    .body(result.response());
        }

        // Fresh payment — return 201 Created
        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Idempotency-Key", idempotencyKey)
                .body(result.response());
    }

    /**
     * GET /api/v1/health
     * Simple health check for deployment verification.
     */
    @GetMapping("/health")
    public ResponseEntity<Object> health() {
        return ResponseEntity.ok(java.util.Map.of(
                "status", "UP",
                "service", "Idempotency Gateway",
                "timestamp", java.time.Instant.now()
        ));
    }
}