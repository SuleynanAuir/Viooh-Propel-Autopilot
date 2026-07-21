package com.autoproject.service.summary;

import com.autoproject.model.Brief;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SuggestionOptimizerTest {

    @Test
    void spendsEssentiallyFullBudgetWhenPhase2Runs() {
        String bill = "outdoor.billboards.rail";
        String indoor = "indoor.mall.screens";
        List<ProposalSummaryRow> rows = new ArrayList<>();
        List<Double> cpms = new ArrayList<>();
        rows.add(new ProposalSummaryRow("GBR", "POI-1", bill, 1.0, "GBP", "Yes", null, 50, 2_000_000d));
        cpms.add(3.0);
        rows.add(new ProposalSummaryRow("GBR", "POI-1", indoor, 1.0, "GBP", "Yes", null, 200, 50_000_000d));
        cpms.add(2.0);
        Brief brief = new Brief("\\N", 8000, 30);
        SuggestionOptimizer opt = new SuggestionOptimizer();
        List<SuggestionOptimizer.Recommendation> rec = opt.recommendGlobal(brief, rows, cpms);
        double sum = 0d;
        for (SuggestionOptimizer.Recommendation r : rec) {
            if (r != null && !r.isEmpty()) {
                sum += r.estimatedMediaBudget();
            }
        }
        assertTrue(sum >= 5000d, "expected most budget placed via tier-1 and phase-2 rounds, sum=" + sum);
    }

    @Test
    void hkgAndMacSharePoiNameButSeparateTier1Caps() {
        String urban = "outdoor.urban_panels";
        List<ProposalSummaryRow> rows = new ArrayList<>();
        List<Double> cpms = new ArrayList<>();
        rows.add(new ProposalSummaryRow("HKG", "china", urban, 45.0, "HKD", "No", null, 50, 100_000_000d));
        cpms.add(45.0);
        rows.add(new ProposalSummaryRow("MAC", "china", urban, 45.0, "HKD", "No", null, 234, 495_000_000d));
        cpms.add(45.0);
        Brief brief = new Brief("\\N", 30_000, 7);
        SuggestionOptimizer opt = new SuggestionOptimizer();
        List<SuggestionOptimizer.Recommendation> rec = opt.recommendGlobal(brief, rows, cpms);
        int hkgScreens = rec.get(0) != null && !rec.get(0).isEmpty() ? rec.get(0).suggestedScreenNo() : 0;
        int macScreens = rec.get(1) != null && !rec.get(1).isEmpty() ? rec.get(1).suggestedScreenNo() : 0;
        assertTrue(hkgScreens > 0, "HKG urban should receive screens independently of MAC");
        assertTrue(macScreens > 0, "MAC urban should receive screens independently of HKG");
        assertTrue(hkgScreens <= 10 && macScreens <= 10,
                "each market keeps its own urban cap, hkg=" + hkgScreens + " mac=" + macScreens);
    }

    @Test
    void perPoiBillboardScreensStayWithinFiveDuringTier1() {
        String venue = "outdoor.billboards.train";
        String poi = "POI-A";
        List<ProposalSummaryRow> rows = new ArrayList<>();
        List<Double> cpms = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            rows.add(new ProposalSummaryRow("GBR", poi, venue, 1.0, "GBP", "Yes", null, 100, 2_000_000d - i * 10_000d));
            cpms.add(2.0);
        }
        Brief brief = new Brief("\\N", 12, 30);
        SuggestionOptimizer opt = new SuggestionOptimizer();
        List<SuggestionOptimizer.Recommendation> rec = opt.recommendGlobal(brief, rows, cpms);
        int screenSum = 0;
        for (int i = 0; i < rows.size(); i++) {
            SuggestionOptimizer.Recommendation r = rec.get(i);
            if (r == null || r.isEmpty()) {
                continue;
            }
            screenSum += r.suggestedScreenNo();
            assertTrue(r.suggestedSot() >= 0.05d - 1e-6);
            assertTrue(r.suggestedScreenNo() <= rows.get(i).getCount());
        }
        assertTrue(screenSum <= 5, "billboard screens per POI should stay <= 5 during tier-1, got " + screenSum);
    }

    @Test
    void tier1UsesSensibleSotNotTinyWithFullScreens() {
        String bill = "outdoor.billboards.rail";
        List<ProposalSummaryRow> rows = new ArrayList<>();
        List<Double> cpms = new ArrayList<>();
        rows.add(new ProposalSummaryRow("GBR", "POI-X", bill, 1.0, "GBP", "Yes", null, 20, 5_000_000d));
        cpms.add(2.0);
        Brief brief = new Brief("\\N", 5000, 30);
        SuggestionOptimizer opt = new SuggestionOptimizer();
        SuggestionOptimizer.Recommendation r = opt.recommendGlobal(brief, rows, cpms).get(0);
        assertTrue(r != null && !r.isEmpty());
        assertTrue(r.suggestedSot() >= 0.05d, "SOT should start at least at 5%, got " + r.suggestedSot());
        assertTrue(r.suggestedScreenNo() <= 20);
        assertTrue(r.suggestedScreenNo() < 20 || r.suggestedSot() >= 0.29d,
                "full screens should pair with high SOT, screens=" + r.suggestedScreenNo() + " sot=" + r.suggestedSot());
    }

    @Test
    void phase2BillboardCanGrowBeyondTier1CapWithLargeBudget() {
        String bill = "outdoor.billboards.rail";
        String indoor = "indoor.mall.screens";
        String poi = "POI-B";
        List<ProposalSummaryRow> rows = new ArrayList<>();
        List<Double> cpms = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            rows.add(new ProposalSummaryRow("GBR", poi, bill, 1.0, "GBP", "Yes", null, 50, 3_000_000d));
            cpms.add(2.0);
        }
        rows.add(new ProposalSummaryRow("GBR", poi, indoor, 1.0, "GBP", "Yes", null, 100, 80_000_000d));
        cpms.add(1.0);
        Brief brief = new Brief("\\N", 500_000, 30);
        SuggestionOptimizer opt = new SuggestionOptimizer();
        List<SuggestionOptimizer.Recommendation> rec = opt.recommendGlobal(brief, rows, cpms);
        int billScreens = 0;
        for (int i = 0; i < 3; i++) {
            SuggestionOptimizer.Recommendation r = rec.get(i);
            if (r != null && !r.isEmpty()) {
                billScreens += r.suggestedScreenNo();
            }
        }
        SuggestionOptimizer.Recommendation indoorRec = rec.get(3);
        assertTrue(indoorRec != null && !indoorRec.isEmpty());
        assertTrue(indoorRec.suggestedScreenNo() >= 10);
        assertTrue(indoorRec.suggestedSot() >= 0.29d);
        assertTrue(billScreens > 5,
                "phase 2 should grow billboard screens beyond tier-1 POI cap of 5 when budget allows, got "
                        + billScreens);
    }

    @Test
    void phase2MaxesAllRowsWhenBudgetIsAbundant() {
        String bill = "outdoor.billboards.rail";
        String urban = "outdoor.urban_panels";
        String indoor = "indoor.mall.screens";
        List<ProposalSummaryRow> rows = new ArrayList<>();
        List<Double> cpms = new ArrayList<>();
        rows.add(new ProposalSummaryRow("GBR", "POI-1", bill, 1.0, "GBP", "Yes", null, 20, 2_000_000d));
        cpms.add(2.0);
        rows.add(new ProposalSummaryRow("GBR", "POI-1", urban, 1.0, "GBP", "Yes", null, 30, 3_000_000d));
        cpms.add(2.0);
        rows.add(new ProposalSummaryRow("GBR", "POI-1", indoor, 1.0, "GBP", "Yes", null, 40, 50_000_000d));
        cpms.add(1.0);
        Brief brief = new Brief("\\N", 10_000_000, 30);
        SuggestionOptimizer opt = new SuggestionOptimizer();
        SuggestionOptimizer.AllocationOutcome outcome = opt.recommendGlobalOutcome(brief, rows, cpms);
        for (int i = 0; i < rows.size(); i++) {
            SuggestionOptimizer.Recommendation r = outcome.recommendations().get(i);
            assertTrue(r != null && !r.isEmpty(), "row " + i + " should be funded");
            assertTrue(r.suggestedScreenNo() >= rows.get(i).getCount(),
                    "row " + i + " screens=" + r.suggestedScreenNo() + " count=" + rows.get(i).getCount());
            assertTrue(SuggestionOptimizer.sotAtMaxForDisplay(r.suggestedSot()),
                    "row " + i + " SOT should be at 30% cap, got " + r.suggestedSot());
        }
        assertTrue(outcome.allInventoryMaxed(), "all candidates should be full screens at 30% SOT");
    }

    @Test
    void neverExceedsMaxSotAndSpendsNearlyFullBudget() {
        String bill = "outdoor.billboards.rail";
        String indoor = "indoor.mall.screens";
        List<ProposalSummaryRow> rows = new ArrayList<>();
        List<Double> cpms = new ArrayList<>();
        rows.add(new ProposalSummaryRow("GBR", "POI-1", bill, 1.0, "GBP", "Yes", null, 50, 2_000_000d));
        cpms.add(3.0);
        rows.add(new ProposalSummaryRow("GBR", "POI-2", indoor, 1.0, "GBP", "Yes", null, 200, 50_000_000d));
        cpms.add(2.0);
        int campaignBudget = 8000;
        Brief brief = new Brief("\\N", campaignBudget, 30);
        SuggestionOptimizer opt = new SuggestionOptimizer();
        List<SuggestionOptimizer.Recommendation> rec = opt.recommendGlobal(brief, rows, cpms);
        double sum = 0d;
        for (SuggestionOptimizer.Recommendation r : rec) {
            if (r == null || r.isEmpty()) {
                continue;
            }
            assertTrue(r.suggestedSot() <= 0.30d + 1e-6, "SOT must not exceed 30%, got " + r.suggestedSot());
            sum += r.estimatedMediaBudget();
        }
        assertTrue(sum >= campaignBudget - 1d, "budget should be fully allocated, sum=" + sum);
    }
}
