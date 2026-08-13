package com.example.aggregation;

/** One upstream. Implementations do a single blocking call — no retry, no timeout, no metrics. */
public interface UpstreamClient<T> {

    /** Stable id used for metric tags, log fields and error-payload keys. */
    String name();

    /** One attempt. Throw UpstreamException (with status) or any Exception; the template classifies. */
    T fetch(AggregationRequest request) throws Exception;

    /** Value substituted when this upstream fails under WAIT_ALL. Owned by the client, not the aggregator. */
    T defaultValue();
}