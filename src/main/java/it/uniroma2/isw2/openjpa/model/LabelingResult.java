package it.uniroma2.isw2.openjpa.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record LabelingResult(Map<String, Boolean> labels,
                             Map<String, Instant> fixResolutionByCommit,
                             List<TicketWindow> windows,
                             int linkedFixCommits,
                             int szzClasses) {

    public LabelingResult {
        labels = Map.copyOf(labels);
        fixResolutionByCommit = Map.copyOf(fixResolutionByCommit);
        windows = List.copyOf(windows);
    }
}
