MeterRegistry registry = new SimpleMeterRegistry();
ObjectMapper mapper = new ObjectMapper();
AggregationConfig cfg = AggregationConfig.defaults();   // WAIT_ALL, 900ms

try (AggregationModule module = new AggregationModule(
        new ServiceAClient("http://a.internal", mapper),
        new ServiceBClient("http://b.internal", mapper),
        new ServiceCClient("http://c.internal", mapper),
        registry, () -> cfg, RetryPolicy.defaults())) {

    AggregateResponse r = module.service()
            .aggregate(new AggregationRequest("u-1", null, null));
}