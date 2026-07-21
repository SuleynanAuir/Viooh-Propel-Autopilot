package com.autoproject.service.summary;

import com.autoproject.model.Brief;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Three-phase budget allocation:
 * <ol>
 *   <li>Billboard + urban: impression priority, each POI covered; per-POI screen totals
 *       {@value #FAIR_BILLBOARD_SCREENS_PER_POI} / {@value #FAIR_URBAN_SCREENS_PER_POI} (or inventory if lower);
 *       per-row SOT &le; {@value #MAX_SOT}.</li>
 *   <li>Phase 2 starts only when every tier-1 POI has billboard/urban screen totals at cap (5 bill / 10 urban or
 *       inventory if lower) and every funded tier-1 row is at {@value #MAX_SOT}. Then <em>all</em> rows (including
 *       billboard and urban) share the same phase-2 rules: SOT rounds 5%&ndash;30% in 5% steps (POI&times;venue screen
 *       caps must satisfy each round before the next; min(10, inventory) through 25%, dropped at 30%). Tier-1 5/10
 *       POI totals do not apply in phase 2. POIs ordered by total impression; round-robin; highest-impression row
 *       within each POI grows first.</li>
 * </ol>
 * Deliverable impressions and budget follow {@code O = K*(L/30)*(M/J)*N}.
 */
public class SuggestionOptimizer {
    private static final double MAX_SOT = 0.30d;
    private static final double TIER2_INITIAL_SOT = 0.05d;
    /** Phase-2 SOT ceilings per round: 5%, 10%, …, 30% ({@link #SOT_STEP} increments). */
    private static final double[] PHASE2_ROUND_MAX_SOT = {
            0.05d, 0.10d, 0.15d, 0.20d, 0.25d, MAX_SOT
    };
    private static final double SOT_STEP = 0.05d;
    private static final int FAIR_BILLBOARD_SCREENS_PER_POI = 5;
    private static final int FAIR_URBAN_SCREENS_PER_POI = 10;
    private static final int OTHER_FAIR_SCREENS_PER_POI_VENUE = 10;
    private static final double EPS = 1e-9d;

    /**
     * Result of {@link #recommendGlobalOutcome}: recommendations plus optimizer-internal
     * remaining budget and whether every candidate hit full screens at {@link #MAX_SOT}.
     */
    public record AllocationOutcome(
            List<Recommendation> recommendations,
            double remainingBudget,
            boolean allInventoryMaxed,
            int candidateCount
    ) {
    }

    public List<Recommendation> recommendGlobal(
            Brief brief,
            List<ProposalSummaryRow> rows,
            List<Double> effectiveCpms
    ) {
        return recommendGlobalOutcome(brief, rows, effectiveCpms).recommendations();
    }

    /**
     * Same allocation as {@link #recommendGlobal} plus {@link #remainingBudget()} and whether every
     * optimizer candidate row is at full screen count and {@link #MAX_SOT} (for photography-budget prompt).
     */
    public AllocationOutcome recommendGlobalOutcome(
            Brief brief,
            List<ProposalSummaryRow> rows,
            List<Double> effectiveCpms
    ) {
        List<Recommendation> result = new ArrayList<>();
        if (rows == null || rows.isEmpty()) {
            return new AllocationOutcome(result, 0d, false, 0);
        }
        for (int i = 0; i < rows.size(); i++) {
            result.add(Recommendation.empty());
        }
        if (brief == null || brief.getCampaignDays() <= 0 || effectiveCpms == null || effectiveCpms.size() != rows.size()) {
            return new AllocationOutcome(result, brief == null ? 0d : Math.max(0, brief.getBudget()), false, 0);
        }
        if (brief.getBudget() <= 0) {
            return new AllocationOutcome(
                    recommendWithGlobalSot(brief, rows, effectiveCpms, result),
                    0d,
                    false,
                    0);
        }

        List<CandidateAlloc> billboard = new ArrayList<>();
        List<CandidateAlloc> urban = new ArrayList<>();
        List<CandidateAlloc> other = new ArrayList<>();
        int campaignDays = brief.getCampaignDays();
        for (int i = 0; i < rows.size(); i++) {
            ProposalSummaryRow row = rows.get(i);
            Double cpm = effectiveCpms.get(i);
            if (row == null || cpm == null || cpm <= 0 || row.getCount() <= 0 || row.getSumImpressions() <= 0) {
                continue;
            }
            double impressionsPerUnit = row.getSumImpressions() * (campaignDays / 30d);
            CandidateAlloc alloc = new CandidateAlloc(i, row, cpm, impressionsPerUnit);
            String venue = row.getVenueTaxonomyValue();
            if (isBillboardVenue(venue)) {
                billboard.add(alloc);
            } else if (isUrbanPanelsVenue(venue)) {
                urban.add(alloc);
            } else {
                other.add(alloc);
            }
        }

        List<CandidateAlloc> tier1 = new ArrayList<>();
        tier1.addAll(billboard);
        tier1.addAll(urban);
        Map<String, PoiInventory> poiInventory = buildPoiInventory(billboard, urban);
        List<String> poiOrder = sortedPoiKeys(poiInventory);

        double remaining = brief.getBudget();
        remaining -= allocateTier1Coverage(tier1, poiInventory, poiOrder, remaining);
        while (remaining > EPS && !tier1Saturated(tier1, poiInventory, poiOrder)) {
            double before = remaining;
            remaining -= growTier1UntilSaturatedOrBudget(tier1, poiInventory, poiOrder, remaining, true);
            if (before - remaining < EPS) {
                break;
            }
        }

        boolean phase2Started = false;
        int lastCompletedPhase2Round = -1;
        List<CandidateAlloc> phase2 = null;
        List<String> phase2PoiOrder = null;
        Map<String, OtherVenuePoiCap> phase2VenueCaps = null;
        if (remaining > EPS && tier1Saturated(tier1, poiInventory, poiOrder)) {
            phase2Started = true;
            phase2 = new ArrayList<>(tier1.size() + other.size());
            phase2.addAll(tier1);
            phase2.addAll(other);
            phase2VenueCaps = buildOtherVenueCaps(phase2);
            phase2PoiOrder = sortedOtherPoiKeysByImpressions(phase2);
            remaining -= allocateOtherInitial(phase2, phase2PoiOrder, phase2VenueCaps, remaining);
            for (int r = 0; r < PHASE2_ROUND_MAX_SOT.length && remaining > EPS; r++) {
                boolean enforceScreenCap = r < PHASE2_ROUND_MAX_SOT.length - 1;
                double roundMaxSot = PHASE2_ROUND_MAX_SOT[r];
                while (remaining > EPS
                        && !tier2RoundComplete(
                                phase2, roundMaxSot, enforceScreenCap, phase2VenueCaps, remaining)) {
                    double before = remaining;
                    remaining -= growOtherFair(
                            phase2,
                            phase2PoiOrder,
                            phase2VenueCaps,
                            remaining,
                            roundMaxSot,
                            enforceScreenCap);
                    if (before - remaining < EPS) {
                        break;
                    }
                }
                if (!tier2RoundComplete(
                        phase2, roundMaxSot, enforceScreenCap, phase2VenueCaps, remaining)) {
                    break;
                }
                lastCompletedPhase2Round = r;
            }
        }

        if (remaining > EPS && phase2Started && phase2 != null) {
            remaining -= spendRemainingInPhase2Rounds(
                    phase2, phase2PoiOrder, phase2VenueCaps, lastCompletedPhase2Round, remaining);
        }
        if (remaining > EPS && !tier1Saturated(tier1, poiInventory, poiOrder)) {
            while (remaining > EPS && !tier1Saturated(tier1, poiInventory, poiOrder)) {
                double before = remaining;
                remaining -= growTier1UntilSaturatedOrBudget(tier1, poiInventory, poiOrder, remaining, true);
                if (before - remaining < EPS) {
                    break;
                }
            }
        }

        List<CandidateAlloc> allCandidates = new ArrayList<>(billboard.size() + urban.size() + other.size());
        allCandidates.addAll(billboard);
        allCandidates.addAll(urban);
        allCandidates.addAll(other);

        for (CandidateAlloc c : tier1) {
            result.set(c.index, c.toRecommendation());
        }
        for (CandidateAlloc c : other) {
            result.set(c.index, c.toRecommendation());
        }
        return new AllocationOutcome(
                result,
                Math.max(0d, remaining),
                allCandidatesInventoryMaxed(allCandidates),
                allCandidates.size());
    }

    /**
     * Matches Proposal sheet {@code 0.00%} display: values that render as 30% (e.g. {@code 0.299999999995})
     * still count as at the {@value #MAX_SOT} cap. Allocation math is unchanged.
     */
    static boolean sotAtMaxForDisplay(double sot) {
        return Math.round(sot * 10_000d) >= Math.round(MAX_SOT * 10_000d);
    }

    /** Every optimizer candidate uses full {@link ProposalSummaryRow#getCount()} at {@link #MAX_SOT}. */
    static boolean allCandidatesInventoryMaxed(List<CandidateAlloc> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return false;
        }
        for (CandidateAlloc c : candidates) {
            if (!c.hasAllocation()) {
                return false;
            }
            if (c.suggestedScreens < c.row.getCount()) {
                return false;
            }
            if (!sotAtMaxForDisplay(c.suggestedSot)) {
                return false;
            }
        }
        return true;
    }

    /** Each POI gets one tier-1 row (highest impressions bill or urban) at {@link #TIER2_INITIAL_SOT}. */
    private static double allocateTier1Coverage(
            List<CandidateAlloc> tier1,
            Map<String, PoiInventory> inventory,
            List<String> poiOrder,
            double budget
    ) {
        if (budget <= EPS || tier1.isEmpty()) {
            return 0d;
        }
        Map<String, List<CandidateAlloc>> byPoi = groupByPoi(tier1);
        double spent = 0d;
        for (String poi : poiOrder) {
            List<CandidateAlloc> rows = byPoi.get(poi);
            if (rows == null || rows.isEmpty()) {
                continue;
            }
            if (inventory.get(poi).hasTier1Coverage()) {
                continue;
            }
            CandidateAlloc pick = pickHighestImpressions(rows);
            if (pick == null) {
                continue;
            }
            double cost = pick.costToSet(1, TIER2_INITIAL_SOT);
            if (cost <= EPS || spent + cost > budget + EPS) {
                continue;
            }
            inventory.get(poi).applyAllocation(pick, 1, TIER2_INITIAL_SOT);
            spent += pick.currentBudget();
        }
        return spent;
    }

    /**
     * Grow tier-1 by impression priority with round-robin across POIs.
     *
     * @param enforcePoiCaps when {@code true}, respect per-POI 5/10 screen totals and {@link #MAX_SOT}
     */
    private static double growTier1UntilSaturatedOrBudget(
            List<CandidateAlloc> tier1,
            Map<String, PoiInventory> inventory,
            List<String> poiOrder,
            double budget,
            boolean enforcePoiCaps
    ) {
        if (budget <= EPS || tier1.isEmpty()) {
            return 0d;
        }
        Map<String, List<CandidateAlloc>> byPoi = groupByPoi(tier1);
        double spent = 0d;
        int poiIdx = 0;
        int guard = 0;
        final int maxIterations = tier1.size() * Math.max(1, poiOrder.size()) * 500;
        while (spent < budget - EPS && guard++ < maxIterations) {
            if (enforcePoiCaps && tier1Saturated(tier1, inventory, poiOrder)) {
                break;
            }
            if (poiOrder.isEmpty()) {
                break;
            }
            boolean progressed = false;
            for (int i = 0; i < poiOrder.size(); i++) {
                String poi = poiOrder.get((poiIdx + i) % poiOrder.size());
                List<CandidateAlloc> rows = byPoi.get(poi);
                if (rows == null || rows.isEmpty()) {
                    continue;
                }
                PoiInventory inv = inventory.get(poi);
                GrowStep step = findBestGrowStep(rows, inv, enforcePoiCaps, budget - spent, MAX_SOT);
                if (step == null) {
                    continue;
                }
                double before = step.alloc.currentBudget();
                if (enforcePoiCaps) {
                    inv.applyAllocation(step.alloc, step.screens, step.sot);
                } else {
                    step.alloc.setAllocation(step.screens, step.sot);
                }
                double delta = step.alloc.currentBudget() - before;
                if (delta > EPS) {
                    spent += delta;
                    progressed = true;
                    poiIdx = (poiIdx + i + 1) % poiOrder.size();
                    break;
                }
            }
            if (!progressed) {
                break;
            }
        }
        return spent;
    }

    /**
     * Spend leftover budget using the same grow/seed steps as the current (last completed) phase-2 round —
     * never jumping to {@link #MAX_SOT} in one fill pass.
     */
    /**
     * Spend leftover budget advancing through phase-2 rounds (from the round after the last completed one).
     */
    private static double spendRemainingInPhase2Rounds(
            List<CandidateAlloc> phase2,
            List<String> phase2PoiOrder,
            Map<String, OtherVenuePoiCap> venueCaps,
            int lastCompletedPhase2Round,
            double budget
    ) {
        if (phase2 == null || phase2.isEmpty() || budget <= EPS || phase2PoiOrder == null) {
            return 0d;
        }
        double spent = 0d;
        int startRound = lastCompletedPhase2Round + 1;
        if (lastCompletedPhase2Round < 0) {
            startRound = 0;
        }
        for (int r = startRound; r < PHASE2_ROUND_MAX_SOT.length && budget - spent > EPS; r++) {
            boolean enforceScreenCap = r < PHASE2_ROUND_MAX_SOT.length - 1;
            double roundMaxSot = PHASE2_ROUND_MAX_SOT[r];
            while (budget - spent > EPS
                    && !tier2RoundComplete(
                            phase2, roundMaxSot, enforceScreenCap, venueCaps, budget - spent)) {
                double before = budget - spent;
                double delta = growOtherFair(
                        phase2, phase2PoiOrder, venueCaps, before, roundMaxSot, enforceScreenCap);
                if (delta <= EPS) {
                    break;
                }
                spent += delta;
            }
        }
        return spent;
    }

    /**
     * Seed one screen per POI per cycle (impression-ranked POI order); within each POI pick the highest-impression
     * row that is still unallocated.
     */
    private static double allocateOtherInitial(
            List<CandidateAlloc> other,
            List<String> poiOrder,
            Map<String, OtherVenuePoiCap> venueCaps,
            double budget
    ) {
        if (other.isEmpty() || budget <= EPS || poiOrder.isEmpty()) {
            return 0d;
        }
        Map<String, List<CandidateAlloc>> byPoi = groupByPoi(other);
        double spent = 0d;
        boolean progress;
        do {
            progress = false;
            for (String poi : poiOrder) {
                List<CandidateAlloc> rows = byPoi.get(poi);
                if (rows == null) {
                    continue;
                }
                CandidateAlloc pick = pickHighestUnallocatedOtherVenue(rows, venueCaps);
                if (pick == null) {
                    continue;
                }
                OtherVenuePoiCap cap = venueCaps.get(otherVenueKey(pick));
                if (cap == null || !cap.canAddScreens(pick, 1)) {
                    continue;
                }
                double sot = seedSotForOtherVenue(pick.row.getCount(), TIER2_INITIAL_SOT);
                double cost = pick.costToSet(1, sot);
                if (cost <= EPS || spent + cost > budget + EPS) {
                    return spent;
                }
                cap.applyAllocation(pick, 1, sot);
                spent += cost;
                progress = true;
            }
        } while (progress && spent < budget - EPS);
        return spent;
    }

    /**
     * Fair round-robin across POIs (impression descending): seed unallocated rows or grow the highest-impression
     * row that can advance within this round's SOT ceiling and optional POI/venue screen cap.
     */
    private static double growOtherFair(
            List<CandidateAlloc> other,
            List<String> poiOrder,
            Map<String, OtherVenuePoiCap> venueCaps,
            double budget,
            double maxSot,
            boolean enforcePoiVenueScreenCap
    ) {
        if (other.isEmpty() || budget <= EPS) {
            return 0d;
        }
        Map<String, List<CandidateAlloc>> byPoi = groupByPoi(other);
        double spent = 0d;
        int poiIdx = 0;
        int guard = 0;
        final int maxIterations = other.size() * Math.max(1, poiOrder.size()) * 500;
        while (spent < budget - EPS && guard++ < maxIterations) {
            if (poiOrder.isEmpty()) {
                break;
            }
            boolean progressed = false;
            for (int i = 0; i < poiOrder.size(); i++) {
                String poi = poiOrder.get((poiIdx + i) % poiOrder.size());
                List<CandidateAlloc> rows = byPoi.get(poi);
                if (rows == null || rows.isEmpty()) {
                    continue;
                }
                double stepSpent = spendOtherFillStepOnPoi(
                        rows, venueCaps, budget - spent, maxSot, enforcePoiVenueScreenCap);
                if (stepSpent > EPS) {
                    spent += stepSpent;
                    progressed = true;
                    poiIdx = (poiIdx + i + 1) % poiOrder.size();
                    break;
                }
            }
            if (!progressed) {
                break;
            }
        }
        return spent;
    }

    /** One budget step for a POI: seed or grow/fill the highest-impression row that can still absorb spend. */
    private static double spendOtherFillStepOnPoi(
            List<CandidateAlloc> rows,
            Map<String, OtherVenuePoiCap> venueCaps,
            double budget,
            double maxSot,
            boolean enforcePoiVenueScreenCap
    ) {
        if (budget <= EPS) {
            return 0d;
        }
        CandidateAlloc unallocated = pickHighestUnallocatedOtherVenue(rows, venueCaps);
        if (unallocated != null) {
            OtherVenuePoiCap cap = venueCaps.get(otherVenueKey(unallocated));
            if (enforcePoiVenueScreenCap && (cap == null || !cap.canAddScreens(unallocated, 1))) {
                return 0d;
            }
            double sot = seedSotForOtherVenue(unallocated.row.getCount(), maxSot);
            if (sot > maxSot + EPS) {
                return 0d;
            }
            double cost = unallocated.costToSet(1, sot);
            if (cost > budget + EPS) {
                return 0d;
            }
            if (cap != null && enforcePoiVenueScreenCap) {
                cap.applyAllocation(unallocated, 1, sot);
            } else {
                unallocated.setAllocation(1, sot);
                if (cap != null) {
                    cap.syncUsedFromAllocations();
                }
            }
            return cost;
        }
        GrowStep best = null;
        double bestImp = -1d;
        for (CandidateAlloc c : rows) {
            if (!c.hasAllocation()) {
                continue;
            }
            OtherVenuePoiCap cap = venueCaps.get(otherVenueKey(c));
            GrowStep step = bestGrowStepForRow(c, null, enforcePoiVenueScreenCap, budget, maxSot, cap);
            if (step == null) {
                continue;
            }
            double imp = c.row.getSumImpressions();
            if (best == null || imp > bestImp + EPS) {
                best = step;
                bestImp = imp;
            }
        }
        if (best != null) {
            double before = best.alloc.currentBudget();
            OtherVenuePoiCap cap = venueCaps.get(otherVenueKey(best.alloc));
            if (enforcePoiVenueScreenCap && cap != null) {
                cap.applyAllocation(best.alloc, best.screens, best.sot);
            } else {
                best.alloc.setAllocation(best.screens, best.sot);
                if (cap != null) {
                    cap.syncUsedFromAllocations();
                }
            }
            return best.alloc.currentBudget() - before;
        }
        CandidateAlloc fillPick = null;
        double fillPickImp = -1d;
        int fillTargetScreens = 0;
        OtherVenuePoiCap fillCap = null;
        for (CandidateAlloc c : rows) {
            if (!c.hasAllocation()) {
                continue;
            }
            int targetScreens = c.row.getCount();
            OtherVenuePoiCap cap = venueCaps.get(otherVenueKey(c));
            if (enforcePoiVenueScreenCap && cap != null) {
                targetScreens = cap.maxScreensForRow(c, targetScreens);
            }
            if (c.suggestedScreens >= targetScreens && c.suggestedSot >= maxSot - EPS) {
                continue;
            }
            double imp = c.row.getSumImpressions();
            if (fillPick == null || imp > fillPickImp + EPS) {
                fillPick = c;
                fillPickImp = imp;
                fillTargetScreens = targetScreens;
                fillCap = cap;
            }
        }
        if (fillPick != null) {
            double filled = fillRowWithinBudget(fillPick, fillTargetScreens, maxSot, budget);
            if (filled > EPS && fillCap != null) {
                fillCap.syncUsedFromAllocations();
            }
            return filled;
        }
        return 0d;
    }

    private static double fillRowWithinBudget(CandidateAlloc c, int targetScreens, double targetSot, double budget) {
        if (budget <= EPS) {
            return 0d;
        }
        targetSot = Math.min(MAX_SOT, targetSot);
        targetScreens = Math.min(c.row.getCount(), Math.max(c.suggestedScreens, targetScreens));
        double bestCost = 0d;
        int bestScreens = c.suggestedScreens;
        double bestSot = c.hasAllocation() ? c.suggestedSot : 0d;
        for (int screens = c.suggestedScreens > 0 ? c.suggestedScreens : 1; screens <= targetScreens; screens++) {
            double lo = c.hasAllocation() ? Math.min(MAX_SOT, c.suggestedSot) : Math.min(MAX_SOT, TIER2_INITIAL_SOT);
            double hi = targetSot;
            if (screens > c.suggestedScreens) {
                double needed = minimalSotForScreens(c.row.getCount(), screens);
                if (needed <= hi + EPS) {
                    lo = Math.min(hi, Math.max(lo, needed));
                } else {
                    continue;
                }
            }
            for (int i = 0; i < 40; i++) {
                double mid = Math.min(MAX_SOT, (lo + hi) / 2d);
                double cost = c.costToSet(screens, mid) - c.currentBudget();
                if (cost <= budget + EPS) {
                    lo = mid;
                } else {
                    hi = mid;
                }
            }
            double sot = Math.min(MAX_SOT, lo);
            double cost = c.costToSet(screens, sot) - c.currentBudget();
            if (cost > EPS && cost <= budget + EPS && cost >= bestCost - EPS) {
                if (cost > bestCost + EPS
                        || screens > bestScreens
                        || (screens == bestScreens && sot > bestSot + EPS)) {
                    bestCost = cost;
                    bestScreens = screens;
                    bestSot = sot;
                }
            }
        }
        if (bestCost > EPS) {
            c.setAllocation(bestScreens, bestSot);
            return bestCost;
        }
        return 0d;
    }

    private static GrowStep findBestGrowStep(
            List<CandidateAlloc> rows,
            PoiInventory inv,
            boolean enforcePoiCaps,
            double maxCost,
            double maxSot
    ) {
        GrowStep best = null;
        double bestImp = -1d;
        for (CandidateAlloc c : rows) {
            GrowStep step = bestGrowStepForRow(c, inv, enforcePoiCaps, maxCost, maxSot, null);
            if (step == null) {
                continue;
            }
            double imp = c.row.getSumImpressions();
            if (best == null || imp > bestImp + EPS) {
                best = step;
                bestImp = imp;
            }
        }
        return best;
    }

    private static GrowStep bestGrowStepForRow(
            CandidateAlloc c,
            PoiInventory inv,
            boolean enforcePoiCaps,
            double maxCost,
            double maxSot,
            OtherVenuePoiCap otherCap
    ) {
        double cappedMaxSot = Math.min(MAX_SOT, maxSot);
        int count = c.row.getCount();
        int screens = c.hasAllocation() ? c.suggestedScreens : 0;
        double sot = c.hasAllocation() ? c.suggestedSot : 0d;

        GrowStep best = null;
        boolean isOtherVenue = otherCap != null;
        double initialSot = isOtherVenue ? minimalSotForScreens(count, 1) : TIER2_INITIAL_SOT;

        if (screens < count) {
            int nextScreens = screens == 0 ? 1 : screens + 1;
            boolean screenOk = !enforcePoiCaps
                    || (otherCap != null ? otherCap.canAddScreens(c, nextScreens - screens)
                    : inv == null || inv.canAddScreens(c, nextScreens - screens));
            if (screenOk) {
                double sotKeep = screens == 0 ? initialSot : sot;
                if (otherCap != null && enforcePoiCaps) {
                    nextScreens = otherCap.maxScreensForRow(c, nextScreens);
                } else if (enforcePoiCaps && inv != null) {
                    nextScreens = inv.maxScreensForRow(c, nextScreens);
                }
                if (nextScreens > screens) {
                    double cost = c.costToSet(nextScreens, sotKeep) - c.currentBudget();
                    if (cost > EPS && cost <= maxCost + EPS && sotKeep <= cappedMaxSot + EPS) {
                        best = new GrowStep(c, nextScreens, sotKeep);
                    }
                }
            }
        }

        if (sot < cappedMaxSot - EPS) {
            double nextSot = screens == 0
                    ? Math.min(cappedMaxSot, initialSot)
                    : Math.min(cappedMaxSot, sot + SOT_STEP);
            int nextScreens = screensForSot(count, nextSot);
            if (enforcePoiCaps && otherCap != null) {
                nextScreens = otherCap.maxScreensForRow(c, nextScreens);
            } else if (enforcePoiCaps && inv != null) {
                nextScreens = Math.min(nextScreens, inv.maxScreensForRow(c, nextScreens));
            }
            boolean sotOnlyAdvance = nextScreens == screens && nextSot > sot + EPS;
            if (nextScreens >= 1 && nextSot <= cappedMaxSot + EPS
                    && (nextScreens > screens || sotOnlyAdvance)) {
                boolean canSet = !enforcePoiCaps
                        || (otherCap != null ? otherCap.canSetScreens(c, nextScreens)
                        : inv == null || inv.canSetScreens(c, nextScreens));
                if (canSet) {
                    double cost = c.costToSet(nextScreens, nextSot) - c.currentBudget();
                    if (cost > EPS && cost <= maxCost + EPS) {
                        best = pickCheaperStep(best, c, nextScreens, nextSot, cost);
                    }
                }
            }
        }

        if (screens > 0 && screens < count && sot < cappedMaxSot - EPS) {
            double nextSot = Math.min(cappedMaxSot, sot + SOT_STEP);
            int nextScreens = Math.min(count, Math.max(screens, screensForSot(count, nextSot)));
            if (enforcePoiCaps && otherCap != null) {
                nextScreens = otherCap.maxScreensForRow(c, nextScreens);
            } else if (enforcePoiCaps && inv != null) {
                nextScreens = Math.min(nextScreens, inv.maxScreensForRow(c, nextScreens));
            }
            if (nextScreens > screens && nextSot <= cappedMaxSot + EPS) {
                boolean canSet = !enforcePoiCaps
                        || (otherCap != null ? otherCap.canSetScreens(c, nextScreens)
                        : inv == null || inv.canSetScreens(c, nextScreens));
                if (canSet) {
                    double cost = c.costToSet(nextScreens, nextSot) - c.currentBudget();
                    if (cost > EPS && cost <= maxCost + EPS) {
                        best = pickCheaperStep(best, c, nextScreens, nextSot, cost);
                    }
                }
            }
        }
        return best;
    }

    private static GrowStep pickCheaperStep(GrowStep current, CandidateAlloc c, int screens, double sot, double cost) {
        if (current == null) {
            return new GrowStep(c, screens, sot);
        }
        double currentCost = current.alloc.costToSet(current.screens, current.sot)
                - (current.alloc.hasAllocation() ? 0 : 0);
        if (cost < currentCost - EPS) {
            return new GrowStep(c, screens, sot);
        }
        return current;
    }

    /**
     * Tier-1 complete before phase 2: per country+POI billboard/urban screen totals at cap (5 / 10 or inventory)
     * and every funded tier-1 row at {@link #MAX_SOT}.
     */
    private static boolean tier1Saturated(
            List<CandidateAlloc> tier1,
            Map<String, PoiInventory> inventory,
            List<String> poiOrder
    ) {
        if (tier1.isEmpty()) {
            return true;
        }
        boolean anyTier1Poi = false;
        for (String poi : poiOrder) {
            PoiInventory inv = inventory.get(poi);
            if (inv == null || !inv.hasTier1Inventory()) {
                continue;
            }
            anyTier1Poi = true;
            if (!inv.screenCapsSaturated() || !inv.allFundedTier1AtMaxSot()) {
                return false;
            }
        }
        return anyTier1Poi;
    }

    /**
     * Phase-2 round complete: no further grow/seed step is possible within this round's SOT ceiling and screen caps
     * (given remaining budget), and every allocated row is at the round SOT.
     */
    private static boolean tier2RoundComplete(
            List<CandidateAlloc> candidates,
            double roundMaxSot,
            boolean enforcePoiVenueScreenCap,
            Map<String, OtherVenuePoiCap> venueCaps,
            double remainingBudget
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return false;
        }
        for (CandidateAlloc c : candidates) {
            if (c.hasAllocation() && c.suggestedSot < roundMaxSot - EPS) {
                return false;
            }
        }
        if (enforcePoiVenueScreenCap && venueCaps != null) {
            for (OtherVenuePoiCap cap : venueCaps.values()) {
                if (!cap.screenCapSaturated()) {
                    return false;
                }
            }
        }
        return !canGrowAnyInPhase2Round(
                candidates, venueCaps, roundMaxSot, enforcePoiVenueScreenCap, remainingBudget);
    }

    private static boolean canGrowAnyInPhase2Round(
            List<CandidateAlloc> candidates,
            Map<String, OtherVenuePoiCap> venueCaps,
            double roundMaxSot,
            boolean enforcePoiVenueScreenCap,
            double budget
    ) {
        if (budget <= EPS) {
            return false;
        }
        for (CandidateAlloc c : candidates) {
            OtherVenuePoiCap cap = venueCaps != null ? venueCaps.get(otherVenueKey(c)) : null;
            if (!c.hasAllocation()) {
                if (enforcePoiVenueScreenCap && cap != null) {
                    if (cap.screenCapSaturated() || !cap.canAddScreens(c, 1)) {
                        continue;
                    }
                }
                double sot = minimalSotForScreens(c.row.getCount(), 1);
                if (sot > roundMaxSot + EPS) {
                    continue;
                }
                double cost = c.costToSet(1, sot);
                if (cost > EPS && cost <= budget + EPS) {
                    return true;
                }
                continue;
            }
            GrowStep step = bestGrowStepForRow(c, null, enforcePoiVenueScreenCap, budget, roundMaxSot, cap);
            if (step != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * SOT for seeding a non-tier-1 row with one screen, capped at {@code roundMaxSot} so phase-2 rounds can
     * still grow from the first round upward.
     */
    private static double seedSotForOtherVenue(int count, double roundMaxSot) {
        double minimal = minimalSotForScreens(count, 1);
        double capped = Math.min(roundMaxSot, minimal);
        if (screensForSot(count, capped) >= 1) {
            return capped;
        }
        return minimal <= roundMaxSot + EPS ? minimal : roundMaxSot;
    }

    /** Minimum SOT so {@link #screensForSot} yields at least {@code targetScreens} screens. */
    private static double minimalSotForScreens(int count, int targetScreens) {
        if (count <= 0 || targetScreens <= 0) {
            return EPS;
        }
        int cappedTarget = Math.min(count, targetScreens);
        double sot = cappedTarget / (double) count;
        while (screensForSot(count, sot) < cappedTarget && sot < 1d) {
            sot += 1d / count;
        }
        return Math.min(MAX_SOT, Math.min(1d, sot));
    }

    private static String otherVenueKey(CandidateAlloc c) {
        return poiGroupKey(c.row) + "\0" + normalizeVenueType(c.row.getVenueTaxonomyValue());
    }

    /** Country + POI — same POI name in different markets must not share tier-1 / phase-2 caps. */
    private static String poiGroupKey(ProposalSummaryRow row) {
        return normalizeCountry(row.getAddressIso3CountryCode()) + "\0" + normalizePoi(row.getClosestPoi());
    }

    private static String normalizeCountry(String iso3) {
        if (iso3 == null) {
            return "";
        }
        String t = iso3.trim();
        if (t.isEmpty() || t.equalsIgnoreCase("null") || t.equals("\\N") || t.equals("-")) {
            return "";
        }
        return t.toUpperCase(Locale.ROOT);
    }

    private static String normalizeVenueType(String venueTaxonomyValue) {
        if (venueTaxonomyValue == null) {
            return "";
        }
        return venueTaxonomyValue.trim().toLowerCase(Locale.ROOT);
    }

    private static Map<String, OtherVenuePoiCap> buildOtherVenueCaps(List<CandidateAlloc> candidates) {
        Map<String, OtherVenuePoiCap> map = new LinkedHashMap<>();
        for (CandidateAlloc c : candidates) {
            String key = otherVenueKey(c);
            map.computeIfAbsent(key, ignored -> new OtherVenuePoiCap())
                    .addInventory(c.row.getCount());
        }
        return map;
    }

    private static int screensForSot(int count, double sot) {
        if (count <= 0 || sot <= EPS) {
            return 0;
        }
        int m = (int) Math.round(count * Math.min(1d, sot));
        return Math.max(1, Math.min(count, m));
    }

    private static Map<String, PoiInventory> buildPoiInventory(List<CandidateAlloc> billboard, List<CandidateAlloc> urban) {
        Map<String, PoiInventory> map = new LinkedHashMap<>();
        for (CandidateAlloc c : billboard) {
            String poi = poiGroupKey(c.row);
            map.computeIfAbsent(poi, ignored -> new PoiInventory()).addBillboard(c.row.getCount());
        }
        for (CandidateAlloc c : urban) {
            String poi = poiGroupKey(c.row);
            map.computeIfAbsent(poi, ignored -> new PoiInventory()).addUrban(c.row.getCount());
        }
        return map;
    }

    private static List<String> sortedPoiKeys(Map<String, PoiInventory> inventory) {
        return new ArrayList<>(new TreeSet<>(inventory.keySet()));
    }

    /** POIs that appear in {@code other}, ordered by descending total row impressions (fair round-robin order). */
    private static List<String> sortedOtherPoiKeysByImpressions(List<CandidateAlloc> other) {
        Map<String, Double> impressionsByPoi = new LinkedHashMap<>();
        for (CandidateAlloc c : other) {
            String poi = poiGroupKey(c.row);
            impressionsByPoi.merge(poi, c.row.getSumImpressions(), Double::sum);
        }
        List<Map.Entry<String, Double>> entries = new ArrayList<>(impressionsByPoi.entrySet());
        entries.sort(Comparator
                .<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue).reversed()
                .thenComparing(Map.Entry::getKey));
        List<String> order = new ArrayList<>(entries.size());
        for (Map.Entry<String, Double> e : entries) {
            order.add(e.getKey());
        }
        return order;
    }

    private static CandidateAlloc pickFirstUnallocated(List<CandidateAlloc> rows) {
        for (CandidateAlloc c : rows) {
            if (!c.hasAllocation()) {
                return c;
            }
        }
        return null;
    }

    /** Highest-impression row that is still unallocated and has a phase-2 POI&times;venue cap entry. */
    private static CandidateAlloc pickHighestUnallocatedOtherVenue(
            List<CandidateAlloc> rows,
            Map<String, OtherVenuePoiCap> venueCaps
    ) {
        CandidateAlloc best = null;
        double bestImp = -1d;
        for (CandidateAlloc c : rows) {
            if (c.hasAllocation()) {
                continue;
            }
            if (!venueCaps.containsKey(otherVenueKey(c))) {
                continue;
            }
            double imp = c.row.getSumImpressions();
            if (best == null || imp > bestImp + EPS) {
                best = c;
                bestImp = imp;
            }
        }
        return best;
    }

    private static Map<String, List<CandidateAlloc>> groupByPoi(List<CandidateAlloc> candidates) {
        Map<String, List<CandidateAlloc>> byPoi = new LinkedHashMap<>();
        for (CandidateAlloc c : candidates) {
            String poi = poiGroupKey(c.row);
            byPoi.computeIfAbsent(poi, ignored -> new ArrayList<>()).add(c);
        }
        for (List<CandidateAlloc> list : byPoi.values()) {
            list.sort(Comparator
                    .comparingDouble((CandidateAlloc a) -> a.row.getSumImpressions()).reversed()
                    .thenComparingDouble(a -> a.cpm).reversed());
        }
        return byPoi;
    }

    private static CandidateAlloc pickHighestImpressions(List<CandidateAlloc> rows) {
        CandidateAlloc best = null;
        double bestImp = -1d;
        for (CandidateAlloc c : rows) {
            double imp = c.row.getSumImpressions();
            if (best == null || imp > bestImp + EPS) {
                best = c;
                bestImp = imp;
            }
        }
        return best;
    }

    private static final class PoiInventory {
        private int totalBillScreens;
        private int totalUrbanScreens;
        private int usedBillScreens;
        private int usedUrbanScreens;
        private boolean tier1Coverage;
        private final List<CandidateAlloc> fundedTier1 = new ArrayList<>();

        void addBillboard(int count) {
            totalBillScreens += count;
        }

        void addUrban(int count) {
            totalUrbanScreens += count;
        }

        void applyAllocation(CandidateAlloc c, int screens, double sot) {
            int oldScreens = screensOnRow(c);
            c.setAllocation(screens, sot);
            int delta = c.suggestedScreens - oldScreens;
            if (delta > 0) {
                if (isBillboardVenue(c.row.getVenueTaxonomyValue())) {
                    usedBillScreens += delta;
                } else if (isUrbanPanelsVenue(c.row.getVenueTaxonomyValue())) {
                    usedUrbanScreens += delta;
                }
            }
            if (c.hasAllocation()) {
                tier1Coverage = true;
                if (!fundedTier1.contains(c)) {
                    fundedTier1.add(c);
                }
            }
        }

        private static int screensOnRow(CandidateAlloc c) {
            return c.hasAllocation() ? c.suggestedScreens : 0;
        }

        int billboardCap() {
            if (totalBillScreens <= 0) {
                return 0;
            }
            return Math.min(FAIR_BILLBOARD_SCREENS_PER_POI, totalBillScreens);
        }

        int urbanCap() {
            if (totalUrbanScreens <= 0) {
                return 0;
            }
            return Math.min(FAIR_URBAN_SCREENS_PER_POI, totalUrbanScreens);
        }

        boolean hasTier1Inventory() {
            return totalBillScreens > 0 || totalUrbanScreens > 0;
        }

        boolean hasTier1Coverage() {
            return tier1Coverage;
        }

        boolean screenCapsSaturated() {
            if (totalBillScreens > 0 && usedBillScreens < billboardCap() - EPS) {
                return false;
            }
            if (totalUrbanScreens > 0 && usedUrbanScreens < urbanCap() - EPS) {
                return false;
            }
            return true;
        }

        boolean allFundedTier1AtMaxSot() {
            if (!tier1Coverage) {
                return false;
            }
            for (CandidateAlloc c : fundedTier1) {
                if (c.suggestedSot < MAX_SOT - EPS) {
                    return false;
                }
            }
            return !fundedTier1.isEmpty();
        }

        boolean canAddScreens(CandidateAlloc c, int delta) {
            return canSetScreens(c, screensOnRow(c) + delta);
        }

        boolean canSetScreens(CandidateAlloc c, int targetScreens) {
            if (targetScreens > c.row.getCount()) {
                return false;
            }
            int delta = targetScreens - screensOnRow(c);
            if (delta <= 0) {
                return true;
            }
            if (isBillboardVenue(c.row.getVenueTaxonomyValue())) {
                return usedBillScreens + delta <= billboardCap() + EPS;
            }
            if (isUrbanPanelsVenue(c.row.getVenueTaxonomyValue())) {
                return usedUrbanScreens + delta <= urbanCap() + EPS;
            }
            return true;
        }

        int maxScreensForRow(CandidateAlloc c, int desired) {
            int current = screensOnRow(c);
            int capLeft;
            if (isBillboardVenue(c.row.getVenueTaxonomyValue())) {
                capLeft = billboardCap() - usedBillScreens + current;
            } else if (isUrbanPanelsVenue(c.row.getVenueTaxonomyValue())) {
                capLeft = urbanCap() - usedUrbanScreens + current;
            } else {
                return desired;
            }
            return Math.min(c.row.getCount(), Math.min(desired, Math.max(current, capLeft)));
        }

    }

    private static final class OtherVenuePoiCap {
        private int totalScreens;
        private int usedScreens;
        private final List<CandidateAlloc> funded = new ArrayList<>();

        void addInventory(int count) {
            totalScreens += count;
        }

        int screenCap() {
            if (totalScreens <= 0) {
                return 0;
            }
            return Math.min(OTHER_FAIR_SCREENS_PER_POI_VENUE, totalScreens);
        }

        boolean screenCapSaturated() {
            return totalScreens <= 0 || usedScreens >= screenCap() - EPS;
        }

        void applyAllocation(CandidateAlloc c, int screens, double sot) {
            int old = screensOnRow(c);
            c.setAllocation(screens, sot);
            int delta = c.suggestedScreens - old;
            if (delta > 0) {
                usedScreens += delta;
            }
            if (c.hasAllocation() && !funded.contains(c)) {
                funded.add(c);
            }
        }

        void syncUsedFromAllocations() {
            usedScreens = 0;
            for (CandidateAlloc c : funded) {
                usedScreens += screensOnRow(c);
            }
        }

        private static int screensOnRow(CandidateAlloc c) {
            return c.hasAllocation() ? c.suggestedScreens : 0;
        }

        boolean canAddScreens(CandidateAlloc c, int delta) {
            return canSetScreens(c, screensOnRow(c) + delta);
        }

        boolean canSetScreens(CandidateAlloc c, int targetScreens) {
            if (targetScreens > c.row.getCount()) {
                return false;
            }
            int delta = targetScreens - screensOnRow(c);
            if (delta <= 0) {
                return true;
            }
            return usedScreens + delta <= screenCap() + EPS;
        }

        int maxScreensForRow(CandidateAlloc c, int desired) {
            int current = screensOnRow(c);
            int capLeft = screenCap() - usedScreens + current;
            return Math.min(c.row.getCount(), Math.min(desired, Math.max(current, capLeft)));
        }
    }

    private record GrowStep(CandidateAlloc alloc, int screens, double sot) {
    }

    private static String normalizePoi(String closestPoi) {
        if (closestPoi == null) {
            return "";
        }
        String t = closestPoi.trim();
        if (t.isEmpty() || t.equalsIgnoreCase("null") || t.equals("\\N") || t.equals("-")) {
            return "";
        }
        return t.toLowerCase(Locale.ROOT);
    }

    private static boolean isBillboardVenue(String venueTaxonomyValue) {
        if (venueTaxonomyValue == null) {
            return false;
        }
        return venueTaxonomyValue.toLowerCase(Locale.ROOT).contains("billboards");
    }

    private static boolean isUrbanPanelsVenue(String venueTaxonomyValue) {
        if (venueTaxonomyValue == null) {
            return false;
        }
        String v = venueTaxonomyValue.toLowerCase(Locale.ROOT);
        return v.contains("urban_panels") || v.contains("urban panels");
    }

    static boolean isBillboardOrUrbanPanelsPriority(String venueTaxonomyValue) {
        return isBillboardVenue(venueTaxonomyValue) || isUrbanPanelsVenue(venueTaxonomyValue);
    }

    static boolean isOutdoorVenue(String venueTaxonomyValue) {
        if (venueTaxonomyValue == null) {
            return false;
        }
        return venueTaxonomyValue.toLowerCase(Locale.ROOT).startsWith("outdoor.");
    }

    private List<Recommendation> recommendWithGlobalSot(
            Brief brief,
            List<ProposalSummaryRow> rows,
            List<Double> effectiveCpms,
            List<Recommendation> result
    ) {
        if (brief == null || brief.getCampaignDays() <= 0 || effectiveCpms == null || effectiveCpms.size() != rows.size()) {
            return result;
        }
        Double globalSot = brief.getSot();
        if (globalSot == null || globalSot <= 0 || globalSot >= 1) {
            return result;
        }
        int campaignDays = brief.getCampaignDays();
        for (int i = 0; i < rows.size(); i++) {
            ProposalSummaryRow row = rows.get(i);
            Double cpm = effectiveCpms.get(i);
            if (row == null || cpm == null || cpm <= 0 || row.getCount() <= 0 || row.getSumImpressions() <= 0) {
                continue;
            }
            double impressionsPerUnit = row.getSumImpressions() * (campaignDays / 30d);
            int screens = screensForSot(row.getCount(), globalSot);
            double sot = Math.min(MAX_SOT, globalSot);
            double impressions = impressionsPerUnit * effectiveShare(screens, row.getCount(), sot);
            double budget = impressions / 1000d * cpm;
            result.set(i, new Recommendation(screens, sot, impressions, budget));
        }
        return result;
    }

    private static double effectiveShare(int screens, int count, double sot) {
        if (count <= 0 || screens <= 0 || sot <= EPS) {
            return 0d;
        }
        return (screens / (double) count) * sot;
    }

    public record Recommendation(
            Integer suggestedScreenNo,
            Double suggestedSot,
            Double estimatedImpressions,
            Double estimatedMediaBudget
    ) {
        static Recommendation empty() {
            return new Recommendation(0, 0d, 0d, 0d);
        }

        boolean isEmpty() {
            return suggestedScreenNo == null || suggestedSot == null || (suggestedScreenNo == 0 && suggestedSot == 0d);
        }
    }

    private static final class CandidateAlloc {
        final int index;
        final ProposalSummaryRow row;
        final double cpm;
        final double impressionsPerUnit;
        int suggestedScreens;
        double suggestedSot;

        CandidateAlloc(int index, ProposalSummaryRow row, double cpm, double impressionsPerUnit) {
            this.index = index;
            this.row = row;
            this.cpm = cpm;
            this.impressionsPerUnit = impressionsPerUnit;
        }

        boolean hasAllocation() {
            return suggestedScreens > 0 && suggestedSot > EPS;
        }

        double effectiveShare(int screens, double sot) {
            return SuggestionOptimizer.effectiveShare(screens, row.getCount(), sot);
        }

        double currentBudget() {
            if (!hasAllocation()) {
                return 0d;
            }
            return budgetFor(suggestedScreens, suggestedSot);
        }

        double costToSet(int screens, double sot) {
            return budgetFor(screens, sot);
        }

        double budgetFor(int screens, double sot) {
            double share = effectiveShare(screens, sot);
            if (share <= EPS) {
                return 0d;
            }
            return impressionsPerUnit * share / 1000d * cpm;
        }

        void setAllocation(int screens, double sot) {
            this.suggestedScreens = Math.max(0, Math.min(row.getCount(), screens));
            this.suggestedSot = Math.min(MAX_SOT, Math.max(0d, sot));
        }

        Recommendation toRecommendation() {
            if (!hasAllocation()) {
                return Recommendation.empty();
            }
            double share = effectiveShare(suggestedScreens, suggestedSot);
            double impressions = impressionsPerUnit * share;
            double budget = impressions / 1000d * cpm;
            return new Recommendation(suggestedScreens, suggestedSot, impressions, budget);
        }
    }
}
