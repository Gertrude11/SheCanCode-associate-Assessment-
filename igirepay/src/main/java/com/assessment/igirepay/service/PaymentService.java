package com.assessment.igirepay.service;


import com.assessment.igirepay.enums.EventType;
import com.assessment.igirepay.exception.IdempotencyConflictException;
import com.assessment.igirepay.model.AuditEvent;
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
    private final AuditLogService auditLogService;

    public PaymentService(IdempotencyStore store, AuditLogService auditLogService) {
        this.store = store;
        this.auditLogService = auditLogService;
    }

    public record PaymentResult(PaymentResponse response, boolean cacheHit) {}

    public PaymentResult processPayment(String idempotencyKey, PaymentRequest request, String clientIp) {

        Optional<IdempotencyRecord> maybeExisting = store.find(idempotencyKey);

        if (maybeExisting.isPresent()) {
            IdempotencyRecord existing = maybeExisting.get();

            // Body conflict — same key, different body (fraud / error)
            if (!isSameRequest(existing.getOriginalRequest(), request)) {
                log.warn("Conflict on key={}", idempotencyKey);

                auditLogService.record(new AuditEvent(
                        idempotencyKey,
                      EventType.CONFLICT_REJECTED,
                        String.format("Original amount=%s %s, new amount=%s %s",
                                existing.getOriginalRequest().getAmount(),
                                existing.getOriginalRequest().getCurrency(),
                                request.getAmount(),
                                request.getCurrency()),
                        clientIp
                ));

                throw new IdempotencyConflictException(
                        "Idempotency key already used for a different request body."
                );
            }

            // In-flight — wait for original to complete
            if (existing.isInFlight()) {
                log.info("Key={} is IN_FLIGHT, waiting...", idempotencyKey);
                waitForCompletion(existing);
            }

            // Cache hit — return saved response
            log.info("Cache HIT for key={}", idempotencyKey);

            auditLogService.record(new AuditEvent(
                    idempotencyKey,
                    EventType.DUPLICATE_DETECTED,
                    String.format("Replayed response for amount=%s %s, transactionId=%s",
                            request.getAmount(),
                            request.getCurrency(),
                            existing.getResponse().getTransactionId()),
                    clientIp
            ));

            return new PaymentResult(existing.getResponse(), true);
        }

        // New key — atomically claim the slot
        IdempotencyRecord record = store.createIfAbsent(idempotencyKey, request);

        if (!isSameRequest(record.getOriginalRequest(), request)) {
            return processPayment(idempotencyKey, request, clientIp);
        }

        if (record.isCompleted()) {
            return new PaymentResult(record.getResponse(), true);
        }

        // Process the payment
        log.info("Processing NEW payment for key={}", idempotencyKey);
        PaymentResponse response = simulatePayment(request);

        synchronized (record) {
            record.complete(response);
            record.notifyAll();
        }

        // Record successful payment in audit log
        auditLogService.record(new AuditEvent(
                idempotencyKey,
                EventType.PAYMENT_PROCESSED,
                String.format("Successfully charged %s %s, transactionId=%s",
                        request.getAmount(),
                        request.getCurrency(),
                        response.getTransactionId()),
                clientIp
        ));

        log.info("Payment COMPLETED for key={}", idempotencyKey);
        return new PaymentResult(response, false);
    }

    private PaymentResponse simulatePayment(PaymentRequest request) {
        try {
            Thread.sleep(PROCESSING_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Payment processing interrupted", e);
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

    private void waitForCompletion(IdempotencyRecord record) {
        synchronized (record) {
            while (record.isInFlight()) {
                try {
                    record.wait(5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while waiting for in-flight payment", e);
                }
            }
        }
    }
    /**
     * Determines whether two payment requests are logically identical.
     *
     * This comparison supports idempotency validation:
     * same key + different request body = conflict
     */
    private boolean isSameRequest(PaymentRequest original, PaymentRequest incoming) {

        if (original == null || incoming == null) {
            return false;
        }

        return original.getCurrency()
                .equalsIgnoreCase(incoming.getCurrency())
                && original.getAmount()
                .compareTo(incoming.getAmount()) == 0;
    }
}