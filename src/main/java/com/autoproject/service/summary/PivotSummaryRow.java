package com.autoproject.service.summary;

/**
 * One aggregated row for the pivot-style summary sheet.
 */
public record PivotSummaryRow(
        String addressIso3CountryCode,
        String closestPoi,
        String venueTaxonomyValue,
        Number floorCpm,
        String mediaOwnerCurrency,
        String vioohSelectOptin,
        long count,
        double sumImpressions
) {
}
