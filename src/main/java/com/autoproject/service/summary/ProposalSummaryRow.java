package com.autoproject.service.summary;

public class ProposalSummaryRow {
    private final String addressIso3CountryCode;
    private final String closestPoi;
    private final String venueTaxonomyValue;
    private final Double floorCpm;
    private final Double effectiveFloorCpm;
    private final String mediaOwnerCurrency;
    private final String vioohSelectOptin;
    private final String market;
    private final int count;
    private final double sumImpressions;

    public ProposalSummaryRow(
            String addressIso3CountryCode,
            String closestPoi,
            String venueTaxonomyValue,
            Double floorCpm,
            String mediaOwnerCurrency,
            String vioohSelectOptin,
            String market,
            int count,
            double sumImpressions
    ) {
        this(
                addressIso3CountryCode,
                closestPoi,
                venueTaxonomyValue,
                floorCpm,
                floorCpm,
                mediaOwnerCurrency,
                vioohSelectOptin,
                market,
                count,
                sumImpressions
        );
    }

    public ProposalSummaryRow(
            String addressIso3CountryCode,
            String closestPoi,
            String venueTaxonomyValue,
            Double floorCpm,
            Double effectiveFloorCpm,
            String mediaOwnerCurrency,
            String vioohSelectOptin,
            String market,
            int count,
            double sumImpressions
    ) {
        this.addressIso3CountryCode = addressIso3CountryCode;
        this.closestPoi = closestPoi;
        this.venueTaxonomyValue = venueTaxonomyValue;
        this.floorCpm = floorCpm;
        this.effectiveFloorCpm = effectiveFloorCpm;
        this.mediaOwnerCurrency = mediaOwnerCurrency;
        this.vioohSelectOptin = vioohSelectOptin;
        this.market = market;
        this.count = count;
        this.sumImpressions = sumImpressions;
    }

    public String getAddressIso3CountryCode() {
        return addressIso3CountryCode;
    }

    public String getClosestPoi() {
        return closestPoi;
    }

    public String getVenueTaxonomyValue() {
        return venueTaxonomyValue;
    }

    public Double getFloorCpm() {
        return floorCpm;
    }

    public Double getEffectiveFloorCpm() {
        return effectiveFloorCpm;
    }

    public String getMediaOwnerCurrency() {
        return mediaOwnerCurrency;
    }

    public String getVioohSelectOptin() {
        return vioohSelectOptin;
    }

    public String getMarket() {
        return market;
    }

    public int getCount() {
        return count;
    }

    public double getSumImpressions() {
        return sumImpressions;
    }
}
