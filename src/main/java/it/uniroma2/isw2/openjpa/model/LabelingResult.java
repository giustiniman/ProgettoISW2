package it.uniroma2.isw2.openjpa.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record LabelingResult(Map<String, Boolean> labels,
                             Set<String> fixCommits,
                             List<TicketWindow> windows,
                             int linkedFixCommits,
                             int szzClasses) {

    public LabelingResult {
        labels = Map.copyOf(labels);
        fixCommits = Set.copyOf(fixCommits);
        windows = List.copyOf(windows);
    }
}
