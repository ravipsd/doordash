package com.example.aggregation;

import java.util.List;

public record AggregationRequest(String userId, String correlationId, Policy policyOverride) {}

public record AData(String userId, String tier) {}
public record BData(List<Card> cards) { public record Card(String last4) {} }
public record CData(String address) {}

public record AggregateData(AData a, BData b, CData c) {}

public record UpstreamError(String upstream, String reason, int attempts, long elapsedMs, boolean retryable) {}

public record Meta(long totalMs, String policy, boolean degraded) {}

public enum ResultStatus { OK, PARTIAL, FAILED }

public record AggregateResponse(String correlationId,
                                ResultStatus status,
                                AggregateData data,      // null when FAILED
                                List<UpstreamError> errors,
                                Meta meta) {}