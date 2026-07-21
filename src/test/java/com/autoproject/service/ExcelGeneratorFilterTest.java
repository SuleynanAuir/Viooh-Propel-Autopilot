package com.autoproject.service;

import com.autoproject.model.FrameData;
import com.autoproject.service.summary.ProposalBuilder;
import com.autoproject.service.summary.ProposalSummaryRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExcelGeneratorFilterTest {

    @Test
    void filterFramesForExportExcludesNullAndZeroImpressionsAndDedupesByMarketAssetUuid() {
        FrameData withImp = frame("MKT", "uuid-1", 1000d);
        FrameData duplicate = frame("MKT", "uuid-1", 2000d);
        FrameData zeroImp = frame("MKT", "uuid-2", 0d);
        FrameData nullImp = frame("MKT", "uuid-3", null);

        List<FrameData> filtered = ExcelGenerator.filterFramesForExport(
                List.of(withImp, duplicate, zeroImp, nullImp));

        assertEquals(1, filtered.size());
        assertEquals(1000d, filtered.get(0).getImpressions());
    }

    @Test
    void proposalBuiltFromFilteredFramesHasNoZeroMonthlyImpressionsWhenOnlyNullInGroup() {
        FrameData good = frame("MKT", "uuid-a", 500d);
        good.setAddressIso3CountryCode("GBR");
        good.setClosestPoi("POI-1");
        good.setVenueTaxonomyValue("outdoor.billboards");
        good.setFloorCpm(10d);
        good.setMediaOwnerCurrency("GBP");
        good.setVioohSelectOptin("Yes");

        FrameData nullOnly = frame("MKT", "uuid-b", null);
        nullOnly.setAddressIso3CountryCode("GBR");
        nullOnly.setClosestPoi("POI-2");
        nullOnly.setVenueTaxonomyValue("outdoor.urban_panels");
        nullOnly.setFloorCpm(10d);
        nullOnly.setMediaOwnerCurrency("GBP");
        nullOnly.setVioohSelectOptin("Yes");

        List<FrameData> filtered = ExcelGenerator.filterFramesForExport(List.of(good, nullOnly));
        List<ProposalSummaryRow> proposal = new ProposalBuilder().build(filtered);

        assertEquals(1, proposal.size());
        assertTrue(proposal.get(0).getSumImpressions() > 0);
    }

    private static FrameData frame(String market, String assetUuid, Double impressions) {
        FrameData d = new FrameData();
        d.setMarket(market);
        d.setAssetUuid(assetUuid);
        d.setImpressions(impressions);
        return d;
    }
}
