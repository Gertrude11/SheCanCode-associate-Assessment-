package com.assessment.igirepay.enums;


/**
 * All possible outcomes we track.
 */
public enum EventType {
    PAYMENT_PROCESSED,
    DUPLICATE_DETECTED,
    CONFLICT_REJECTED,
    INVALID_KEY_FORMAT
}