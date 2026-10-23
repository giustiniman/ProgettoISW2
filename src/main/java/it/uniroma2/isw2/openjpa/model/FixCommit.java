package it.uniroma2.isw2.openjpa.model;

import java.time.Instant;

public record FixCommit(String ticketKey, String commitHash, Instant commitTime) {
}
