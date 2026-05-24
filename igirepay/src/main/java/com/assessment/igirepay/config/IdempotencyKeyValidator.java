package com.assessment.igirepay.config;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class IdempotencyKeyValidator {

    /**
     * Standard UUID v4 pattern:
     *
     * Example: 550e8400-e29b-41d4-a716-446655440000
     */
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"
    );

    private static final int MIN_LENGTH = 36;
    private static final int MAX_LENGTH = 36;

    /**
     * Validates an idempotency key.
     *
     * @param key the idempotency key from the request header
     * @return a ValidationResult explaining whether the key is valid
     */
    public ValidationResult validate(String key) {
        if (key == null || key.isBlank()) {
            return ValidationResult.invalid("Idempotency key must not be blank.");
        }

        if (key.length() < MIN_LENGTH || key.length() > MAX_LENGTH) {
            return ValidationResult.invalid(
                    String.format("Idempotency key must be exactly %d characters (UUID format). Got %d characters.",
                            MIN_LENGTH, key.length())
            );
        }

        if (!UUID_PATTERN.matcher(key).matches()) {
            return ValidationResult.invalid(
                    "Idempotency key must be a valid UUID v4 format. " +
                            "Example: 550e8400-e29b-41d4-a716-446655440000. " +
                            "Generate one with UUID.randomUUID().toString() in Java."
            );
        }

        return ValidationResult.valid();
    }

    /**
     * Simple result object — avoids throwing exceptions just for validation checks.
     */
    public record ValidationResult(boolean isValid, String errorMessage) {

        public static ValidationResult valid() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult invalid(String message) {
            return new ValidationResult(false, message);
        }

        public boolean isInvalid() {
            return !isValid;
        }
    }
}