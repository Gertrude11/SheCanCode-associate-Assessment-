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
 *
 * The record stores:
 * - The original request for conflict detection
 * - Processing status
 * - The generated response
 * - Timestamps for request tracking
 *
 * Status values:
 * IN_FLIGHT  - Request is currently being processed
 * COMPLETED  - Request processing finished and response was saved
 */
@RequiredArgsConstructor
@Getter
public class IdempotencyRecord {


    private final String idempotencyKey;
    private final PaymentRequest originalRequest;  // Saved so we can detect body conflicts
    private PaymentResponse response;              // Null until COMPLETED
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