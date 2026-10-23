package it.uniroma2.isw2.openjpa.model;

import java.time.LocalDate;

public record RawRelease(String jiraId, String version, LocalDate releaseDate) {
}
