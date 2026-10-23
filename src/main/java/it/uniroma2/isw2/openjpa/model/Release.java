package it.uniroma2.isw2.openjpa.model;

import java.time.LocalDate;

public record Release(int index, String jiraId, String version, LocalDate releaseDate,
                      String snapshotRef, String commitHash) {
}
