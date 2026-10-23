package it.uniroma2.isw2.openjpa.model;

public record ReleaseRange(Release current, Release predecessor,
                           String boundaryCommit, BoundaryType boundaryType) {

    public enum BoundaryType {
        PREDECESSOR,
        MERGE_BASE,
        REPOSITORY_ORIGIN
    }
}
