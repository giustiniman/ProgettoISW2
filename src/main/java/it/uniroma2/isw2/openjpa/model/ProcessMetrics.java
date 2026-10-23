package it.uniroma2.isw2.openjpa.model;

public record ProcessMetrics(int locTouched, int nr, int nFix, int nAuth,
                             int locAdded, int maxLocAdded, double avgLocAdded,
                             int churn, int maxChurn, double avgChurn,
                             int changeSetSize, int maxChangeSet, double avgChangeSet,
                             double ageWeeks, double weightedAgeWeeks,
                             double changeEntropy, double ownership) {
}
