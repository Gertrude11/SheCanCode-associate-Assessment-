package com.assessment.igirepay.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.Instant;

/**
 * Response returned after processing or replaying a payment.
 * */
@Getter
@RequiredArgsConstructor
public class PaymentResponse {

    private final String status;
    private final String message;
    private final String transactionId;
    private final Instant processedAt;
}