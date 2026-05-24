package com.assessment.igirepay;

import com.assessment.igirepay.enums.EventType;
import com.assessment.igirepay.service.IdempotencyStore;
import com.assessment.igirepay.model.AuditEvent;
import com.assessment.igirepay.model.PaymentRequest;
import com.assessment.igirepay.service.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
public class PaymentControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IdempotencyStore store;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private ObjectMapper objectMapper;

    // ─── Setup ───────────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() {
        // Wipe the store and audit log before every test
        // so tests never interfere with each other
        store.clear();
        auditLogService.clear();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String toJson(PaymentRequest req) throws Exception {
        return objectMapper.writeValueAsString(req);
    }

    /** Generates a valid UUID v4 key — matches our validator requirements */
    private String validKey() {
        return UUID.randomUUID().toString();
    }

    // =========================================================================
    // USER STORY 1: First Transaction (Happy Path)
    // =========================================================================

    @Test
    @DisplayName("US1: First payment returns 201 Created with correct message")
    void firstPaymentReturns201() throws Exception {
        mockMvc.perform(post("/api/v1/process-payment")
                        .header("Idempotency-Key", validKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new PaymentRequest(new BigDecimal("100"), "RWF"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.message").value("Charged 100 RWF"))
                .andExpect(jsonPath("$.transactionId").exists())
                .andExpect(jsonPath("$.processedAt").exists());
    }

    @Test
    @DisplayName("US1: Response includes Idempotency-Key header echoed back")
    void firstPaymentEchosKeyInResponseHeader() throws Exception {
        String key = validKey();

        mockMvc.perform(post("/api/v1/process-payment")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new PaymentRequest(new BigDecimal("100"), "RWF"))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Key", key));
    }

    @Test
    @DisplayName("US1: Missing Idempotency-Key header returns 400")
    void missingHeaderReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/process-payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new PaymentRequest(new BigDecimal("100"), "RWF"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("US1: Missing amount in body returns 400")
    void missingAmountReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/process-payment")
                        .header("Idempotency-Key", validKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\": \"RWF\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("US1: Missing currency in body returns 400")
    void missingCurrencyReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/process-payment")
                        .header("Idempotency-Key", validKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 100}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("US1: Zero amount returns 400")
    void zeroAmountReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/process-payment")
                        .header("Idempotency-Key", validKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new PaymentRequest(new BigDecimal("0"), "RWF"))))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // USER STORY 2: Duplicate Request (Idempotency)
    // =========================================================================

    @Test
    @DisplayName("US2: Duplicate request returns 200 OK with X-Cache-Hit: true")
    void duplicateRequestReturnsCacheHit() throws Exception {
        String key = validKey();
        String body = toJson(new PaymentRequest(new BigDecimal("250"), "RWF"));

        // First request
        mockMvc.perform(post("/api/v1/process-payment")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        // Duplicate request
        mockMvc.perform(post("/api/v1/process-payment")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Cache-Hit", "true"));
    }

    @Test
    @DisplayName("US2: Duplicate response body is identical to original")
    void duplicateResponseBodyMatchesOriginal() throws Exception {
        String key = validKey();
        String body = toJson(new PaymentRequest(new BigDecimal("250"), "USD"));

        MvcResult first = mockMvc.perform(post("/api/v1/process-payment")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        MvcResult second = mockMvc.perform(post("/api/v1/process-payment")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        // Same transactionId proves no double charge
        assertEquals(
                first.getResponse().getContentAsString(),
                second.getResponse().getContentAsString(),
                "Duplicate response must be identical to original — same transactionId"
        );
    }

    @Test
    @DisplayName("US2: Duplicate request responds instantly — no 2 second delay")
    void duplicateRequestIsFast() throws Exception {
        String key = validKey();
        String body = toJson(new PaymentRequest(new BigDecimal("500"), "RWF"));

        // First request — takes 2 seconds
        long start1 = System.currentTimeMillis();
        mockMvc.perform(post("/api/v1/process-payment")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());
        long duration1 = System.currentTimeMillis() - start1;

        // Duplicate request — should be instant
        long start2 = System.currentTimeMillis();
        mockMvc.perform(post("/api/v1/process-payment")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        long duration2 = System.currentTimeMillis() - start2;

        assertTrue(duration1 > 1500, "Original must take over 1.5s, got: " + duration1 + "ms");
        assertTrue(duration2 < 500,  "Duplicate must respond under 500ms, got: " + duration2 + "ms");
    }

    @Test
    @DisplayName("US2: Currency case difference is treated as same request")
    void currencyCaseInsensitiveMatch() throws Exception {
        String key = validKey();

        // First request with uppercase RWF
        MvcResult first = mockMvc.perform(post("/api/v1/process-payment")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new PaymentRequest(new BigDecimal("100"), "RWF"))))
                .andExpect(status().isCreated())
                .andReturn();

        // Second request with lowercase rwf — should still be a cache hit
        MvcResult second = mockMvc.perform(post("/api/v1/process-payment")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new PaymentRequest(new BigDecimal("100"), "rwf"))))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Cache-Hit", "true"))
                .andReturn();

        assertEquals(
                first.getResponse().getContentAsString(),
                second.getResponse().getContentAsString()
        );
    }

    // =========================================================================
    // USER STORY 3: Body Conflict (Security / Fraud Check)
    // =========================================================================

    @Test
    @DisplayName("US3: Same key different amount returns 422")
    void differentAmountReturns422() throws Exception {
        String key = validKey();

        mockMvc.perform(post("/api/v1/process-payment")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new PaymentRequest(new BigDecimal("100"), "RWF"))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/process-payment")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new PaymentRequest(new BigDecimal("500"), "RWF"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error")
                        .value("Idempotency key already used for a different request body."));
    }

    @Test
    @DisplayName("US3: Same key different currency returns 422")
    void differentCurrencyReturns422() throws Exception {
        String key = validKey();

        mockMvc.perform(post("/api/v1/process-payment")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new PaymentRequest(new BigDecimal("100"), "RWF"))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/process-payment")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new PaymentRequest(new BigDecimal("100"), "USD"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error")
                        .value("Idempotency key already used for a different request body."));
    }

    // =========================================================================
    //  Audit Log
    // =========================================================================

    @Test
    @DisplayName("AUDIT: Successful payment is recorded in audit log")
    void successfulPaymentIsAudited() throws Exception {
        String key = validKey();

        mockMvc.perform(post("/api/v1/process-payment")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new PaymentRequest(new BigDecimal("100"), "RWF"))))
                .andExpect(status().isCreated());

        List<AuditEvent> events = auditLogService.getByKey(key);
        assertEquals(1, events.size());
        assertEquals(EventType.PAYMENT_PROCESSED, events.get(0).getEventType());
    }

    @Test
    @DisplayName("AUDIT: Duplicate request is recorded as DUPLICATE_DETECTED")
    void duplicateRequestIsAudited() throws Exception {
        String key = validKey();
        String body = toJson(new PaymentRequest(new BigDecimal("100"), "RWF"));

        mockMvc.perform(post("/api/v1/process-payment")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/process-payment")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        List<AuditEvent> events = auditLogService.getByKey(key);
        assertEquals(2, events.size());

        boolean hasDuplicate = events.stream()
                .anyMatch(e -> e.getEventType() == EventType.DUPLICATE_DETECTED);
        assertTrue(hasDuplicate, "DUPLICATE_DETECTED event must be recorded");
    }

    @Test
    @DisplayName("AUDIT: Conflict attempt is recorded as CONFLICT_REJECTED")
    void conflictIsAudited() throws Exception {
        String key = validKey();

        mockMvc.perform(post("/api/v1/process-payment")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new PaymentRequest(new BigDecimal("100"), "RWF"))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/process-payment")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new PaymentRequest(new BigDecimal("999"), "RWF"))))
                .andExpect(status().isUnprocessableEntity());

        List<AuditEvent> events = auditLogService.getByKey(key);
        boolean hasConflict = events.stream()
                .anyMatch(e -> e.getEventType() == EventType.CONFLICT_REJECTED);
        assertTrue(hasConflict, "CONFLICT_REJECTED event must be recorded");
    }

    @Test
    @DisplayName("AUDIT: GET /audit-log endpoint returns all events")
    void auditLogEndpointReturnsEvents() throws Exception {
        mockMvc.perform(post("/api/v1/process-payment")
                        .header("Idempotency-Key", validKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new PaymentRequest(new BigDecimal("100"), "RWF"))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/audit-log"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].eventType").value("PAYMENT_PROCESSED"));
    }

    @Test
    @DisplayName("AUDIT: GET /audit-log/{key} returns events for that key only")
    void auditLogByKeyEndpointWorks() throws Exception {
        String key = validKey();

        mockMvc.perform(post("/api/v1/process-payment")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new PaymentRequest(new BigDecimal("100"), "RWF"))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/audit-log/" + key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].idempotencyKey").value(key));
    }

    // =========================================================================
    // DEVELOPER'S CHOICE 2: Idempotency Key Format Validation
    // =========================================================================

    @Test
    @DisplayName("KEY VALIDATION: Non-UUID key is rejected")
    void nonUuidKeyIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/process-payment")
                        .header("Idempotency-Key", "order-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new PaymentRequest(new BigDecimal("100"), "RWF"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.hint").exists());
    }

    @Test
    @DisplayName("KEY VALIDATION: Short key is rejected")
    void shortKeyIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/process-payment")
                        .header("Idempotency-Key", "abc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new PaymentRequest(new BigDecimal("100"), "RWF"))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("KEY VALIDATION: Blank key is rejected")
    void blankKeyIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/process-payment")
                        .header("Idempotency-Key", "   ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new PaymentRequest(new BigDecimal("100"), "RWF"))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("KEY VALIDATION: Valid UUID v4 key is accepted")
    void validUuidKeyIsAccepted() throws Exception {
        mockMvc.perform(post("/api/v1/process-payment")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new PaymentRequest(new BigDecimal("100"), "RWF"))))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("KEY VALIDATION: Invalid key format is recorded in audit log")
    void invalidKeyFormatIsAudited() throws Exception {
        mockMvc.perform(post("/api/v1/process-payment")
                        .header("Idempotency-Key", "bad-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(new PaymentRequest(new BigDecimal("100"), "RWF"))))
                .andExpect(status().isUnprocessableEntity());

        List<AuditEvent> events = auditLogService.getByKey("bad-key");
        assertEquals(1, events.size());
        assertEquals(EventType.INVALID_KEY_FORMAT, events.get(0).getEventType());
    }
}