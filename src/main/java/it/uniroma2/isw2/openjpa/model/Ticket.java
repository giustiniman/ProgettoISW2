package it.uniroma2.isw2.openjpa.model;

import java.time.Instant;
import java.util.List;

public record Ticket(String key, Instant createdAt, Instant resolvedAt,
                     List<String> affectedVersions, List<String> fixVersions) {

    public Ticket {
        affectedVersions = List.copyOf(affectedVersions);
        fixVersions = List.copyOf(fixVersions);
    }
}
