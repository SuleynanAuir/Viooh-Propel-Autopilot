package com.autoproject.service.summary;

import com.autoproject.model.Brief;

import java.util.List;

/**
 * Detects when frame allocation has used all inventory at max SOT but campaign budget remains,
 * so optional photography budget can be offered.
 */
public final class PhotographyBudgetEvaluator {
    private static final double EPS = 1e-9d;

    private PhotographyBudgetEvaluator() {
    }

    public record Snapshot(
            double totalMediaSpend,
            int campaignBudget,
            double unspentBudget,
            boolean allInventoryMaxed,
            String skipReason
    ) {
        public boolean eligibleForPrompt() {
            return skipReason == null || skipReason.isEmpty();
        }

        public int unspentBudgetRounded() {
            return (int) Math.round(unspentBudget);
        }
    }

    public static Snapshot fromAllocationOutcome(Brief brief, SuggestionOptimizer.AllocationOutcome outcome) {
        return fromOutcome(brief, outcome);
    }

    public static Snapshot fromOutcome(Brief brief, SuggestionOptimizer.AllocationOutcome outcome) {
        if (brief == null || outcome == null) {
            return new Snapshot(0d, 0, 0d, false, "missing brief or allocation outcome");
        }
        double spent = totalMediaSpend(outcome.recommendations());
        int campaignBudget = brief.getBudget();
        double unspent = outcome.remainingBudget();
        boolean maxed = outcome.allInventoryMaxed();
        String skip = explainSkip(brief, outcome, spent, unspent, maxed);
        return new Snapshot(spent, campaignBudget, unspent, maxed, skip);
    }

    /** @deprecated Prefer {@link #fromOutcome(Brief, SuggestionOptimizer.AllocationOutcome)} */
    public static Snapshot analyze(
            Brief brief,
            List<ProposalSummaryRow> rows,
            List<Double> effectiveCpms,
            List<SuggestionOptimizer.Recommendation> recommendations
    ) {
        SuggestionOptimizer.AllocationOutcome outcome =
                new SuggestionOptimizer().recommendGlobalOutcome(brief, rows, effectiveCpms);
        return fromOutcome(brief, outcome);
    }

    public static double totalMediaSpend(List<SuggestionOptimizer.Recommendation> recommendations) {
        if (recommendations == null) {
            return 0d;
        }
        double sum = 0d;
        for (SuggestionOptimizer.Recommendation r : recommendations) {
            if (r != null && !r.isEmpty()) {
                sum += r.estimatedMediaBudget();
            }
        }
        return sum;
    }

    private static String explainSkip(
            Brief brief,
            SuggestionOptimizer.AllocationOutcome outcome,
            double spent,
            double unspent,
            boolean maxed
    ) {
        if (brief.getBudget() <= 0) {
            return "campaign budget is zero";
        }
        if (outcome.candidateCount() <= 0) {
            return "no allocatable proposal rows (check CPM / impressions / screen count)";
        }
        if (!maxed) {
            return "not all rows at full screen count with 30% SOT (candidateCount="
                    + outcome.candidateCount() + ")";
        }
        if (unspent <= EPS) {
            return "no unspent budget after allocation (spent=" + Math.round(spent) + ", budget="
                    + brief.getBudget() + ")";
        }
        return "";
    }
}
