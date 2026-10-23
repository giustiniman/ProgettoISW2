package it.uniroma2.isw2.openjpa.model;

public record DatasetRow(String project, String release, String className,
                         StaticMetrics staticMetrics, ProcessMetrics processMetrics,
                         int nSmells, boolean buggy) {
}
