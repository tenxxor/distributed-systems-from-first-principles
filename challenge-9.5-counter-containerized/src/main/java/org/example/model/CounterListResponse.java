package org.example.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Paginated list response. Shape:
 *   { "counters": [ ... ], "nextCursor": "...or null" }
 *
 * `nextCursor` is null on the last page; when null, Jackson omits it from the JSON.
 */
public class CounterListResponse {

    private final List<CounterResponse> counters;
    private final String nextCursor;

    public CounterListResponse(List<CounterResponse> counters, String nextCursor) {
        this.counters = counters;
        this.nextCursor = nextCursor;
    }

    @JsonProperty
    public List<CounterResponse> getCounters() { return counters; }

    @JsonProperty
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getNextCursor() { return nextCursor; }
}
