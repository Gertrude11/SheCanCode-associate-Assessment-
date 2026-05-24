package com.assessment.igirepay.controller;

import com.assessment.igirepay.config.IdempotencyKeyValidator;
import com.assessment.igirepay.enums.EventType;
import com.assessment.igirepay.model.AuditEvent;
import com.assessment.igirepay.model.PaymentRequest;
import com.assessment.igirepay.service.AuditLogService;
import com.assessment.igirepay.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class PaymentController {

    private final PaymentService paymentService;
    private final AuditLogService auditLogService;
    private final IdempotencyKeyValidator keyValidator;

    public PaymentController(PaymentService paymentService,
                             AuditLogService auditLogService,
                             IdempotencyKeyValidator keyValidator) {
        this.paymentService = paymentService;
        this.auditLogService = auditLogService;
        this.keyValidator = keyValidator;
    }

    @PostMapping("/process-payment")
    public ResponseEntity<?> processPayment(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PaymentRequest request,
            HttpServletRequest httpRequest) {

        String clientIp = httpRequest.getRemoteAddr();

        //Validate key format before doing anything else
        IdempotencyKeyValidator.ValidationResult validation = keyValidator.validate(idempotencyKey);
        if (validation.isInvalid()) {
            auditLogService.record(new AuditEvent(
                    idempotencyKey,
                    EventType.INVALID_KEY_FORMAT,
                    validation.errorMessage(),
                    clientIp
            ));
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Map.of(
                            "error", validation.errorMessage(),
                            "status", 422,
                            "hint", "Generate a valid key with: UUID.randomUUID().toString()"
                    ));
        }

        //Process the payment (service handles idempotency logic)
        PaymentService.PaymentResult result = paymentService.processPayment(
                idempotencyKey, request, clientIp);

        if (result.cacheHit()) {
            return ResponseEntity.ok()
                    .header("X-Cache-Hit", "true")
                    .header("Idempotency-Key", idempotencyKey)
                    .body(result.response());
        }

        return ResponseEntity.status(HttpStatus.CREATED)
                .header("Idempotency-Key", idempotencyKey)
                .body(result.response());
    }
    /**
     * GET /api/v1/audit-log
     * Returns all audit events
     */
    @GetMapping("/audit-log")
    public ResponseEntity<List<AuditEvent>> getAuditLog() {
        return ResponseEntity.ok(auditLogService.getAll());
    }

    /**
     * GET /api/v1/audit-log/{key}
     * Returns all events for a specific idempotency key.
     */
    @GetMapping("/audit-log/{key}")
    public ResponseEntity<List<AuditEvent>> getAuditLogByKey(@PathVariable String key) {
        return ResponseEntity.ok(auditLogService.getByKey(key));
    }
}