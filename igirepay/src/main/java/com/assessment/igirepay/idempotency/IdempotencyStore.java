package com.assessment.igirepay.idempotency;

import com.assessment.igirepay.model.IdempotencyRecord;
import com.assessment.igirepay.model.PaymentRequest;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for idempotency records.
 *
 * WHY ConcurrentHashMap?
 * Regular HashMap is NOT thread-safe. If two requests arrive at the same
 * millisecond, they could both try to write to the map simultaneously,
 * causing corrupted data or missed writes.
 *
 * ConcurrentHashMap handles concurrent access safely without us having
 * to write complex locking code for every read/write.
 *
 * In production, this would be replaced with Redis (which also supports
 * distributed locking across multiple servers).
 */
@Component
public class IdempotencyStore {

    // Key = the Idempotency-Key string from the HTTP header
    // Value = the record we saved when we first processed that key
    private final ConcurrentHashMap<String, IdempotencyRecord> store = new ConcurrentHashMap<>();

    /**
     * Looks up an existing record by key.
     * Returns Optional.empty() if we've never seen this key before.
     */
    public Optional<IdempotencyRecord> find(String key) {
        return Optional.ofNullable(store.get(key));
    }

    /**
     * Atomically create a new record ONLY if the key doesn't already exist.
     *
     * "Atomically" means: no other thread can sneak in between the "check"
     * and the "write". This is the core of our race condition protection.
     *
     * putIfAbsent() returns:
     *   - null  → we successfully created a new record (we "won the race")
     *   - existing record → someone else already created it (we "lost the race")
     */
    public IdempotencyRecord createIfAbsent(String key, PaymentRequest request) {
        IdempotencyRecord newRecord = new IdempotencyRecord(key, request);
        IdempotencyRecord existing = store.putIfAbsent(key, newRecord);
        // If existing is null, our new record was inserted. Return it.
        // If existing is not null, our insert was ignored. Return the winner.
        return existing == null ? newRecord : existing;
    }

    /**
     * Returns the total number of keys in the store.
     * Useful for health checks and monitoring.
     */
    public int size() {
        return store.size();
    }

    /**
     * Used in tests to reset the store between test runs.
     */
    public void clear() {
        store.clear();
    }
}