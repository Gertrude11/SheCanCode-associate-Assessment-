package com.assessment.igirepay.service;

import com.assessment.igirepay.exception.IdempotencyConflictException;
import com.assessment.igirepay.idempotency.IdempotencyStore;
import com.assessment.igirepay.model.IdempotencyRecord;
import com.assessment.igirepay.model.PaymentRequest;
import com.assessment.igirepay.model.PaymentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private static final long PROCESSING_DELAY_MS = 2000;

    private final IdempotencyStore store;

    public PaymentService(IdempotencyStore store) {
        this.store = store;
    }

    /**
     * Wrapper response returned to controller.
     */
    public record PaymentResult(PaymentResponse response, boolean cacheHit) {}

    /**
     * MAIN FLOW:
     * 1. Check if request already exists (idempotency key)
     * 2. If exists → validate request body
     * 3. If in-flight → wait
     * 4. If completed → return cached response
     * 5. If new → process payment
     */
    public PaymentResult processPayment(String idempotencyKey, PaymentRequest request) {

        log.info("Received payment request key={}", idempotencyKey);

        Optional<IdempotencyRecord> maybeRecord = store.find(idempotencyKey);

        // ─────────────────────────────────────────────
        // CASE 1: Key already exists
        // ─────────────────────────────────────────────
        if (maybeRecord.isPresent()) {

            IdempotencyRecord existing = maybeRecord.get();

            // 1. Conflict detection (same key, different request)
            if (!isSameRequest(existing.getOriginalRequest(), request)) {
                log.warn("Idempotency conflict for key={}", idempotencyKey);
                throw new IdempotencyConflictException(
                        "Idempotency key already used for a different request body."
                );
            }

            // 2. If still processing, wait
            if (existing.isInFlight()) {
                log.info("Key={} is IN_FLIGHT, waiting...", idempotencyKey);
                waitForCompletion(existing);
            }

            // 3. Return cached response
            log.info("Cache HIT for key={}", idempotencyKey);
            return new PaymentResult(existing.getResponse(), true);
        }

        // ─────────────────────────────────────────────
        // CASE 2: New request → create record atomically
        // ─────────────────────────────────────────────
        IdempotencyRecord record = store.createIfAbsent(idempotencyKey, request);

        // Another thread won race
        if (!isSameRequest(record.getOriginalRequest(), request)) {
            return handleExisting(record, request, idempotencyKey);
        }

        if (record.isCompleted()) {
            return new PaymentResult(record.getResponse(), true);
        }

        // ─────────────────────────────────────────────
        // CASE 3: Process payment
        // ─────────────────────────────────────────────
        log.info("Processing payment key={}, amount={} {}",
                idempotencyKey,
                request.getAmount(),
                request.getCurrency());

        PaymentResponse response = simulatePayment(request);

        // ─────────────────────────────────────────────
        // CASE 4: Mark completed + notify waiters
        // ─────────────────────────────────────────────
        synchronized (record) {
            record.complete(response);
            record.notifyAll();
        }

        log.info("Payment completed key={}, txn={}",
                idempotencyKey,
                response.getTransactionId());

        return new PaymentResult(response, false);
    }

    /**
     * Handles race condition winner/loser case safely.
     */
    private PaymentResult handleExisting(
            IdempotencyRecord record,
            PaymentRequest request,
            String key
    ) {

        if (!isSameRequest(record.getOriginalRequest(), request)) {
            throw new IdempotencyConflictException(
                    "Idempotency key already used for a different request body."
            );
        }

        if (record.isInFlight()) {
            waitForCompletion(record);
        }

        return new PaymentResult(record.getResponse(), true);
    }

    /**
     * Business rule: determines if two requests are logically identical.
     */
    private boolean isSameRequest(PaymentRequest a, PaymentRequest b) {
        return a.getCurrency().equalsIgnoreCase(b.getCurrency())
                && a.getAmount().compareTo(b.getAmount()) == 0;
    }

    /**
     * Simulates external payment provider call.
     */
    private PaymentResponse simulatePayment(PaymentRequest request) {
        try {
            Thread.sleep(PROCESSING_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Payment interrupted", e);
        }

        String message = String.format("Charged %s %s",
                request.getAmount().stripTrailingZeros().toPlainString(),
                request.getCurrency().toUpperCase());

        return new PaymentResponse(
                "success",
                message,
                "TXN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                Instant.now()
        );
    }

    /**
     * Waits until IN_FLIGHT → COMPLETED.
     */
    private void waitForCompletion(IdempotencyRecord record) {
        synchronized (record) {
            while (record.isInFlight()) {
                try {
                    record.wait(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while waiting", e);
                }
            }
        }
    }
}