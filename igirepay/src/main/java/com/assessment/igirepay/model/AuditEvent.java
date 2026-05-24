package com.assessment.igirepay.model;

import com.assessment.igirepay.enums.EventType;
import lombok.Getter;

import java.time.Instant;

@Getter
public class AuditEvent {

    private final String idempotencyKey;
    private final EventType eventType;
    private final String details;
    private final String clientIp;
    private final Instant timestamp;

    public AuditEvent(String idempotencyKey, EventType eventType, String details, String clientIp) {
        this.idempotencyKey = idempotencyKey;
        this.eventType = eventType;
        this.details = details;
        this.clientIp = clientIp;
        this.timestamp = Instant.now();
    }

    @Override
    public String toString() {
        return String.format("[%s] %-22s | key=%-36s | ip=%-15s | %s",
                timestamp, eventType, idempotencyKey, clientIp, details);
    }
}