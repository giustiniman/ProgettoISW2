package it.uniroma2.isw2.openjpa.model;

import java.time.Instant;

public record Touch(String commitHash, Instant time, String author, int added, int deleted) {

    public int locTouched() {
        return added + deleted;
    }

    public int churn() {
        return added - deleted;
    }
}
