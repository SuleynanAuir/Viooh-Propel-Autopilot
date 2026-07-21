package com.autoproject.service.summary;

import com.autoproject.model.Brief;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhotographyBudgetEvaluatorTest {

    @Test
    void eligibleWhenInventoryMaxedAndOptimizerHasRemainingBudget() {
        List<ProposalSummaryRow> rows = new ArrayList<>();
        List<Double> cpms = new ArrayList<>();
        rows.add(new ProposalSummaryRow("GBR", "POI", "indoor.mall", 2.0, "USD", "Yes", null, 20, 5_000_000d));
        cpms.add(2.0);
        Brief brief = new Brief("\\N", 500_000, 30);
        SuggestionOptimizer opt = new SuggestionOptimizer();
        SuggestionOptimizer.AllocationOutcome outcome = opt.recommendGlobalOutcome(brief, rows, cpms);
        PhotographyBudgetEvaluator.Snapshot snapshot = PhotographyBudgetEvaluator.fromOutcome(brief, outcome);

        assertTrue(outcome.allInventoryMaxed(), "inventory should max: " + snapshot.skipReason());
        assertTrue(outcome.remainingBudget() > 1000d, "remaining=" + outcome.remainingBudget());
        assertTrue(snapshot.eligibleForPrompt(), "skip=" + snapshot.skipReason());
        assertEquals(outcome.remainingBudget(), snapshot.unspentBudget(), 1d);
    }

    @Test
    void sotJustBelowOneUlpStillCountsAsMaxForInventoryCheck() {
        assertTrue(SuggestionOptimizer.sotAtMaxForDisplay(0.299999999995d));
        assertFalse(SuggestionOptimizer.sotAtMaxForDisplay(0.29d));
    }

    @Test
    void notEligibleWhenBudgetFullySpent() {
        List<ProposalSummaryRow> rows = new ArrayList<>();
        List<Double> cpms = new ArrayList<>();
        rows.add(new ProposalSummaryRow("GBR", "POI-1", "outdoor.billboards.rail", 3.0, "GBP", "Yes", null, 50, 2_000_000d));
        cpms.add(3.0);
        rows.add(new ProposalSummaryRow("GBR", "POI-2", "indoor.mall.screens", 2.0, "GBP", "Yes", null, 200, 50_000_000d));
        cpms.add(2.0);
        Brief brief = new Brief("\\N", 8000, 30);
        SuggestionOptimizer.AllocationOutcome outcome =
                new SuggestionOptimizer().recommendGlobalOutcome(brief, rows, cpms);
        PhotographyBudgetEvaluator.Snapshot snapshot = PhotographyBudgetEvaluator.fromOutcome(brief, outcome);
        assertFalse(snapshot.eligibleForPrompt(), "skip=" + snapshot.skipReason());
    }
}
