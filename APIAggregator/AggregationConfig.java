package com.example.aggregation;

import java.time.Duration;

public record AggregationConfig(Policy policy, Duration overallBudget) {
    public static AggregationConfig defaults() {
        return new AggregationConfig(Policy.WAIT_ALL, Duration.ofMillis(900));
    }
}