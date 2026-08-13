package com.example.aggregation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class AggregateController {

    private final AggregationService service;

    public AggregateController(AggregationService service) { this.service = service; }

    @GetMapping("/aggregate")
    public ResponseEntity<AggregateResponse> aggregate(
            @RequestParam String userId,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
            @RequestHeader(value = "X-Aggregation-Policy", required = false) Policy policyOverride) {

        AggregateResponse body = service.aggregate(
                new AggregationRequest(userId, correlationId, policyOverride));

        return ResponseEntity.status(httpStatus(body))
                .header("X-Correlation-Id", body.correlationId())
                .body(body);
    }

    /**
     * PARTIAL is 200, not 206/207: 206 is defined for byte ranges and confuses caches, 207 is
     * WebDAV and unsupported by most clients. The body carries status + errors + meta.degraded.
     */
    private static HttpStatus httpStatus(AggregateResponse r) {
        if (r.status() != ResultStatus.FAILED) return HttpStatus.OK;
        boolean allRejected = !r.errors().isEmpty()
                && r.errors().stream().allMatch(e -> Failure.REJECTED.name().equals(e.reason()));
        if (allRejected) return HttpStatus.SERVICE_UNAVAILABLE;            // 503 — capacity
        boolean anyDeadline = r.errors().stream()
                .anyMatch(e -> Failure.DEADLINE_EXCEEDED.name().equals(e.reason()));
        return anyDeadline ? HttpStatus.GATEWAY_TIMEOUT                    // 504 — too slow
                           : HttpStatus.BAD_GATEWAY;                       // 502 — upstream broke
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }
}