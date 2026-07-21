package com.autoproject.service.summary;

import com.autoproject.model.FrameData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProposalBuilderTest {

    @Test
    void shouldGroupProposalRowsByCountryThenPoiInsteadOfInputOrder() {
        ProposalBuilder builder = new ProposalBuilder();

        FrameData row1 = frame("FRA", "Paris", "Mall", 10.0, "EUR", "YES", 100.0);
        FrameData row2 = frame("DEU", "Berlin", "Airport", 12.0, "EUR", "YES", 200.0);
        FrameData row3 = frame("FRA", "Lyon", "Street", 11.0, "EUR", "YES", 150.0);
        FrameData row4 = frame("DEU", "Munich", "Station", 9.0, "EUR", "NO", 120.0);

        List<ProposalSummaryRow> rows = builder.build(List.of(row1, row2, row3, row4));

        assertEquals(4, rows.size());
        assertEquals("DEU", rows.get(0).getAddressIso3CountryCode());
        assertEquals("Berlin", rows.get(0).getClosestPoi());
        assertEquals("DEU", rows.get(1).getAddressIso3CountryCode());
        assertEquals("Munich", rows.get(1).getClosestPoi());
        assertEquals("FRA", rows.get(2).getAddressIso3CountryCode());
        assertEquals("Lyon", rows.get(2).getClosestPoi());
        assertEquals("FRA", rows.get(3).getAddressIso3CountryCode());
        assertEquals("Paris", rows.get(3).getClosestPoi());
    }

    private FrameData frame(
            String iso3,
            String poi,
            String venue,
            Double floorCpm,
            String currency,
            String optin,
            Double impressions
    ) {
        FrameData data = new FrameData();
        data.setAddressIso3CountryCode(iso3);
        data.setClosestPoi(poi);
        data.setVenueTaxonomyValue(venue);
        data.setFloorCpm(floorCpm);
        data.setMediaOwnerCurrency(currency);
        data.setVioohSelectOptin(optin);
        data.setImpressions(impressions);
        return data;
    }
}
