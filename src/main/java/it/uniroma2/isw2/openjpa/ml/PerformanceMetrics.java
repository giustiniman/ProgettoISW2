package it.uniroma2.isw2.openjpa.ml;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

record PerformanceMetrics(long truePositive, long falsePositive, long trueNegative,
                          long falseNegative, double precision, double recall,
                          double f1, double auc, double kappa, double npofb20) {

    static PerformanceMetrics from(List<ScoredPrediction> predictions) {
        if (predictions.isEmpty()) {
            throw new IllegalArgumentException("Non esistono predizioni da valutare");
        }
        long tp = predictions.stream().filter(p -> p.actualBuggy() && p.predictedBuggy()).count();
        long fp = predictions.stream().filter(p -> !p.actualBuggy() && p.predictedBuggy()).count();
        long tn = predictions.stream().filter(p -> !p.actualBuggy() && !p.predictedBuggy()).count();
        long fn = predictions.stream().filter(p -> p.actualBuggy() && !p.predictedBuggy()).count();
        double precision = divide(tp, tp + fp);
        double recall = divide(tp, tp + fn);
        double f1 = precision + recall == 0.0
                ? 0.0 : 2.0 * precision * recall / (precision + recall);
        return new PerformanceMetrics(tp, fp, tn, fn, precision, recall, f1,
                auc(predictions), kappa(tp, fp, tn, fn), npofb20(predictions));
    }

    static PerformanceMetrics average(List<PerformanceMetrics> values) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Non esistono metriche da aggregare");
        }
        return new PerformanceMetrics(
                Math.round(values.stream().mapToLong(PerformanceMetrics::truePositive).average().orElse(0)),
                Math.round(values.stream().mapToLong(PerformanceMetrics::falsePositive).average().orElse(0)),
                Math.round(values.stream().mapToLong(PerformanceMetrics::trueNegative).average().orElse(0)),
                Math.round(values.stream().mapToLong(PerformanceMetrics::falseNegative).average().orElse(0)),
                mean(values, Metric.PRECISION), mean(values, Metric.RECALL),
                mean(values, Metric.F1), mean(values, Metric.AUC),
                mean(values, Metric.KAPPA), mean(values, Metric.NPOFB20));
    }

    private static double mean(List<PerformanceMetrics> values, Metric metric) {
        return values.stream().mapToDouble(metric::value).average().orElse(0.0);
    }

    private static double auc(List<ScoredPrediction> predictions) {
        List<ScoredPrediction> ordered = new ArrayList<>(predictions);
        ordered.sort(Comparator.comparingDouble(ScoredPrediction::buggyProbability));
        long positives = ordered.stream().filter(ScoredPrediction::actualBuggy).count();
        long negatives = ordered.size() - positives;
        if (positives == 0 || negatives == 0) {
            return 0.0;
        }

        double positiveRankSum = 0.0;
        int start = 0;
        while (start < ordered.size()) {
            int end = start + 1;
            while (end < ordered.size()
                    && Double.compare(ordered.get(start).buggyProbability(),
                    ordered.get(end).buggyProbability()) == 0) {
                end++;
            }
            double averageRank = ((start + 1) + end) / 2.0;
            for (int index = start; index < end; index++) {
                if (ordered.get(index).actualBuggy()) {
                    positiveRankSum += averageRank;
                }
            }
            start = end;
        }
        return (positiveRankSum - positives * (positives + 1) / 2.0)
                / (positives * negatives);
    }

    private static double kappa(long tp, long fp, long tn, long fn) {
        double total = tp + fp + tn + fn;
        double observed = (tp + tn) / total;
        double actualPositive = tp + fn;
        double actualNegative = tn + fp;
        double predictedPositive = tp + fp;
        double predictedNegative = tn + fn;
        double expected = (actualPositive * predictedPositive
                + actualNegative * predictedNegative) / (total * total);
        return expected == 1.0 ? 0.0 : (observed - expected) / (1.0 - expected);
    }

    private static double npofb20(List<ScoredPrediction> predictions) {
        long totalBugs = predictions.stream().filter(ScoredPrediction::actualBuggy).count();
        long totalLoc = predictions.stream().mapToLong(ScoredPrediction::loc).sum();
        if (totalBugs == 0 || totalLoc == 0) {
            return 0.0;
        }
        List<ScoredPrediction> ordered = new ArrayList<>(predictions);
        ordered.sort(Comparator.comparingDouble(
                (ScoredPrediction p) -> p.buggyProbability() / p.loc()).reversed());
        double budget = totalLoc * 0.20;
        long inspectedLoc = 0;
        long found = 0;
        for (ScoredPrediction prediction : ordered) {
            if (inspectedLoc + prediction.loc() > budget) {
                break;
            }
            inspectedLoc += prediction.loc();
            if (prediction.actualBuggy()) {
                found++;
            }
        }
        return (double) found / totalBugs;
    }

    private static double divide(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    private enum Metric {
        PRECISION { double value(PerformanceMetrics m) { return m.precision; } },
        RECALL { double value(PerformanceMetrics m) { return m.recall; } },
        F1 { double value(PerformanceMetrics m) { return m.f1; } },
        AUC { double value(PerformanceMetrics m) { return m.auc; } },
        KAPPA { double value(PerformanceMetrics m) { return m.kappa; } },
        NPOFB20 { double value(PerformanceMetrics m) { return m.npofb20; } };

        abstract double value(PerformanceMetrics metrics);
    }
}

record ScoredPrediction(double buggyProbability, boolean actualBuggy,
                        boolean predictedBuggy, int loc) {
    ScoredPrediction {
        if (loc <= 0) {
            throw new IllegalArgumentException("LOC deve essere positivo");
        }
    }
}
