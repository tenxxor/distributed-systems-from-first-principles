package org.example.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class CreateCounterRequest {

    private final String counterId;

    @JsonCreator
    public CreateCounterRequest(@JsonProperty("counterId") String counterId) {
        this.counterId = counterId;
    }

    @JsonProperty
    public String getCounterId() { return counterId; }
}
