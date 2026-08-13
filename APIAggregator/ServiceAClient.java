package com.example.aggregation;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;

public final class ServiceAClient implements UpstreamClient<AData> {

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;

    public ServiceAClient(String baseUrl, ObjectMapper mapper) {
        this.baseUrl = baseUrl;
        this.mapper = mapper;
        // Connect timeout at the client level; the read deadline is enforced by the template.
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(200)).build();
    }

    @Override public String name() { return "A"; }

    @Override public AData fetch(AggregationRequest request) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/users/" + request.userId()))
                .timeout(Duration.ofMillis(300))   // belt and braces with the template's deadline
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new UpstreamException("A returned " + resp.statusCode(), resp.statusCode());
        }
        return mapper.readValue(resp.body(), AData.class);
    }

    @Override public AData defaultValue() { return new AData(null, "UNKNOWN"); }
}