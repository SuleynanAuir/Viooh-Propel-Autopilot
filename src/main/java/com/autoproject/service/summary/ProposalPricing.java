package com.autoproject.service.summary;

import com.autoproject.model.Brief;
import com.autoproject.model.FrameData;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;

/**
 * Shared floor CPM adjustments used by Proposal sheet budget math (VS uplift, USD conversion).
 */
public final class ProposalPricing {
    private ProposalPricing() {
    }

    public static Double effectiveCpmForBudget(ProposalSummaryRow row, Brief brief) {
        if (row == null) {
            return null;
        }
        Double evenAdjusted = calculateEvenAdjustedFloorCpm(row.getEffectiveFloorCpm(), row.getVioohSelectOptin());
        if (evenAdjusted == null || evenAdjusted <= 0) {
            return null;
        }
        if (brief == null || !brief.isConvertBudgetToUsd()) {
            return evenAdjusted;
        }
        String currency = normalizeCurrency(row.getMediaOwnerCurrency());
        return convertFloorCpmToUsd(evenAdjusted, currency, brief.getUsdExchangeRateByCurrency());
    }

    public static Double effectiveCpmForBudget(FrameData frame, Brief brief) {
        if (frame == null) {
            return null;
        }
        Double evenAdjusted = calculateEvenAdjustedFloorCpm(frame.getEffectiveFloorCpm(), frame.getVioohSelectOptin());
        if (evenAdjusted == null || evenAdjusted <= 0) {
            return null;
        }
        if (brief == null || !brief.isConvertBudgetToUsd()) {
            return evenAdjusted;
        }
        String currency = normalizeCurrency(frame.getMediaOwnerCurrency());
        return convertFloorCpmToUsd(evenAdjusted, currency, brief.getUsdExchangeRateByCurrency());
    }

    public static Double calculateEvenAdjustedFloorCpm(Double floorCpm, String vioohSelectOptin) {
        if (floorCpm == null) {
            return null;
        }
        if (!isYes(vioohSelectOptin)) {
            return floorCpm;
        }
        return BigDecimal.valueOf(floorCpm * 1.2d)
                .setScale(4, RoundingMode.HALF_UP)
                .doubleValue();
    }

    public static String normalizeCurrency(String currency) {
        if (currency == null) {
            return null;
        }
        String normalized = currency.trim().toUpperCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }

    public static Double convertFloorCpmToUsd(
            Double floorCpm,
            String currency,
            Map<String, Double> usdExchangeRateByCurrency
    ) {
        if (floorCpm == null || currency == null) {
            return null;
        }
        if ("USD".equals(currency)) {
            return floorCpm;
        }
        Double effectiveRate = resolveUsdRate(currency, usdExchangeRateByCurrency);
        if (effectiveRate == null || effectiveRate <= 0) {
            return null;
        }
        return BigDecimal.valueOf(floorCpm * effectiveRate)
                .setScale(4, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private static Double resolveUsdRate(String currency, Map<String, Double> usdExchangeRateByCurrency) {
        if (usdExchangeRateByCurrency != null) {
            Double rate = usdExchangeRateByCurrency.get(currency);
            if (rate != null && rate > 0) {
                return rate;
            }
        }
        return null;
    }

    private static boolean isYes(String value) {
        if (value == null) {
            return false;
        }
        return "YES".equals(value.trim().toUpperCase(Locale.ROOT));
    }
}
