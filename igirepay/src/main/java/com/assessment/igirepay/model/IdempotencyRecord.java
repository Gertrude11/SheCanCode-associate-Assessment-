package com.assessment.igirepay.model;

import com.assessment.igirepay.enums.Status;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.Instant;

/**
 * Represents a stored idempotency entry for a processed request.
 *
 * Each record tracks the lifecycle of a request associated with an
 * idempotency key and helps prevent duplicate processing.
 */
@RequiredArgsConstructor
@Getter
public class IdempotencyRecord {


    private final String idempotencyKey;
    private final PaymentRequest originalRequest;
    private PaymentResponse response;
    private Status status;
    private final Instant createdAt;
    private Instant completedAt;

    public IdempotencyRecord(String idempotencyKey, PaymentRequest originalRequest) {
        this.idempotencyKey = idempotencyKey;
        this.originalRequest = originalRequest;
        this.status = Status.IN_FLIGHT;
        this.createdAt = Instant.now();
    }

    // Called once processing finishes
    public void complete(PaymentResponse response) {
        this.response = response;
        this.status = Status.COMPLETED;
        this.completedAt = Instant.now();
    }

    public boolean isInFlight()  { return status == Status.IN_FLIGHT; }
    public boolean isCompleted() { return status == Status.COMPLETED; }
}