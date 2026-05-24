package com.assessment.igirepay.service;

import com.assessment.igirepay.enums.EventType;
import com.assessment.igirepay.model.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final CopyOnWriteArrayList<AuditEvent> events = new CopyOnWriteArrayList<>();

    /**
     * Record a new audit event.
     * Called from PaymentService and PaymentController whenever something notable happens.
     */
    public void record(AuditEvent event) {
        events.add(event);
        log.info("AUDIT | {}", event);
    }

    /**
     * Returns all audit events (newest first).
     * Used by the audit log endpoint so operators can inspect history.
     */
    public List<AuditEvent> getAll() {
        List<AuditEvent> copy = List.copyOf(events);
        return copy.reversed();
    }

    /**
     * Returns only events for a specific idempotency key.
     * Useful for investigating a specific transaction.
     */
    public List<AuditEvent> getByKey(String idempotencyKey) {
        return events.stream()
                .filter(e -> idempotencyKey.equals(e.getIdempotencyKey()))
                .collect(Collectors.toList());
    }

    /**
     * Returns only events of a specific type.
     */
    public List<AuditEvent> getByType(EventType type) {
        return events.stream()
                .filter(e -> type == e.getEventType())
                .collect(Collectors.toList());
    }

    /**
     * Total number of events recorded since server start.
     */
    public int count() {
        return events.size();
    }

    /**
     * Clears all events. Used in tests only.
     */
    public void clear() {
        events.clear();
    }
}