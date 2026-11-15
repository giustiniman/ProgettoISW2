package it.uniroma2.isw2.openjpa.model;

import java.time.Instant;
import java.util.List;

public record Ticket(String key, Instant createdAt, List<String> affectedVersions) {

    public Ticket {
        affectedVersions = List.copyOf(affectedVersions);
    }
}
