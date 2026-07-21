package com.autoproject.service.summary;

import com.autoproject.model.FrameData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ProposalBuilder {

    public List<ProposalSummaryRow> build(List<FrameData> dataList) {
        Map<SummaryKey, SummaryAggregate> grouped = new LinkedHashMap<>();
        for (FrameData d : dataList) {
            SummaryKey key = new SummaryKey(
                    normalizeDimensionValue(d.getAddressIso3CountryCode()),
                    normalizeDimensionValue(d.getClosestPoi()),
                    normalizeDimensionValue(d.getVenueTaxonomyValue()),
                    d.getFloorCpm(),
                    d.getEffectiveFloorCpm(),
                    normalizeDimensionValue(d.getMediaOwnerCurrency()),
                    normalizeDimensionValue(d.getVioohSelectOptin()),
                    normalizeDimensionValue(d.getMarket())
            );
            SummaryAggregate aggregate = grouped.computeIfAbsent(key, ignored -> new SummaryAggregate());
            aggregate.count++;
            if (d.getImpressions() != null) {
                aggregate.sumImpressions += d.getImpressions();
            }
        }

        List<ProposalSummaryRow> result = new ArrayList<>();
        for (Map.Entry<SummaryKey, SummaryAggregate> entry : grouped.entrySet()) {
            SummaryKey key = entry.getKey();
            SummaryAggregate aggregate = entry.getValue();
            result.add(new ProposalSummaryRow(
                    key.addressIso3CountryCode,
                    key.closestPoi,
                    key.venueTaxonomyValue,
                    key.floorCpm,
                    key.effectiveFloorCpm,
                    key.mediaOwnerCurrency,
                    key.vioohSelectOptin,
                    key.market,
                    aggregate.count,
                    aggregate.sumImpressions
            ));
        }
        result.sort(
                Comparator.comparing(ProposalSummaryRow::getAddressIso3CountryCode, this::compareNullableText)
                        .thenComparing(ProposalSummaryRow::getClosestPoi, this::compareNullableText)
                        .thenComparing(ProposalSummaryRow::getVenueTaxonomyValue, this::compareNullableText)
                        .thenComparing(ProposalSummaryRow::getFloorCpm, this::compareNullableNumber)
                        .thenComparing(ProposalSummaryRow::getEffectiveFloorCpm, this::compareNullableNumber)
                        .thenComparing(ProposalSummaryRow::getMediaOwnerCurrency, this::compareNullableText)
                        .thenComparing(ProposalSummaryRow::getVioohSelectOptin, this::compareNullableText)
                        .thenComparing(ProposalSummaryRow::getMarket, this::compareNullableText)
        );
        return result;
    }

    private int compareNullableText(String a, String b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return 1;
        }
        if (b == null) {
            return -1;
        }
        return a.compareToIgnoreCase(b);
    }

    private int compareNullableNumber(Double a, Double b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return 1;
        }
        if (b == null) {
            return -1;
        }
        return Double.compare(a, b);
    }

    private String normalizeDimensionValue(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.isEmpty() || v.equalsIgnoreCase("null") || v.equals("\\N") || v.equals("-")) {
            return null;
        }
        return v;
    }

    private static final class SummaryKey {
        private final String addressIso3CountryCode;
        private final String closestPoi;
        private final String venueTaxonomyValue;
        private final Double floorCpm;
        private final Double effectiveFloorCpm;
        private final String mediaOwnerCurrency;
        private final String vioohSelectOptin;
        private final String market;

        private SummaryKey(
                String addressIso3CountryCode,
                String closestPoi,
                String venueTaxonomyValue,
                Double floorCpm,
                Double effectiveFloorCpm,
                String mediaOwnerCurrency,
                String vioohSelectOptin,
                String market
        ) {
            this.addressIso3CountryCode = addressIso3CountryCode;
            this.closestPoi = closestPoi;
            this.venueTaxonomyValue = venueTaxonomyValue;
            this.floorCpm = floorCpm;
            this.effectiveFloorCpm = effectiveFloorCpm;
            this.mediaOwnerCurrency = mediaOwnerCurrency;
            this.vioohSelectOptin = vioohSelectOptin;
            this.market = market;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof SummaryKey)) {
                return false;
            }
            SummaryKey other = (SummaryKey) obj;
            return Objects.equals(addressIso3CountryCode, other.addressIso3CountryCode)
                    && Objects.equals(closestPoi, other.closestPoi)
                    && Objects.equals(venueTaxonomyValue, other.venueTaxonomyValue)
                    && Objects.equals(floorCpm, other.floorCpm)
                    && Objects.equals(effectiveFloorCpm, other.effectiveFloorCpm)
                    && Objects.equals(mediaOwnerCurrency, other.mediaOwnerCurrency)
                    && Objects.equals(vioohSelectOptin, other.vioohSelectOptin)
                    && Objects.equals(market, other.market);
        }

        @Override
        public int hashCode() {
            return Objects.hash(addressIso3CountryCode, closestPoi, venueTaxonomyValue, floorCpm, effectiveFloorCpm, mediaOwnerCurrency, vioohSelectOptin, market);
        }
    }

    private static final class SummaryAggregate {
        private int count;
        private double sumImpressions;
    }
}
