package com.assessment.igirepay.exception;

/**
 * Thrown when a client reuses an idempotency key with a different request body.
 * Maps to HTTP 422 Unprocessable Entity.
 */
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String message) {
        super(message);
    }
}