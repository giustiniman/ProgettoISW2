package it.uniroma2.isw2.openjpa.model;

import java.util.Set;

public record SzzClass(String classPath, Set<String> bugIntroducingCommits) {

    public SzzClass {
        bugIntroducingCommits = Set.copyOf(bugIntroducingCommits);
    }
}
