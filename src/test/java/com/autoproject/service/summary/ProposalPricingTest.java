package com.autoproject.service.summary;

import com.autoproject.model.Brief;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProposalPricingTest {

    @Test
    void convertsMultipleSourceCurrenciesIntoOneTargetCurrency() {
        Brief brief = new Brief("Test", 100_000, 7, true, null, Map.of());
        brief.setTargetCurrency("USD");
        brief.setCurrencyExchangeRateBySource(Map.of(
                "EUR", 1.08d,
                "USD", 1d,
                "SGD", 0.74d));

        assertEquals(10.8d, ProposalPricing.convertFloorCpm(10d, "EUR", brief));
        assertEquals(10d, ProposalPricing.convertFloorCpm(10d, "usd", brief));
        assertEquals(7.4d, ProposalPricing.convertFloorCpm(10d, " SGD ", brief));
        assertNull(ProposalPricing.convertFloorCpm(10d, "GBP", brief));
    }
}
