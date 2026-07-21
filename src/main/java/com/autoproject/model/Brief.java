package com.autoproject.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class Brief {
    private String location;
    /** Total media budget for the campaign; allocation across rows is done in {@link com.autoproject.service.summary.SuggestionOptimizer}. */
    private int budget;
    private int campaignDays;
    /** User/global SOT for no-budget mode; unused while SOT UI is hidden. */
    private Double sot;
    private boolean convertBudgetToUsd;
    private Double usdExchangeRate;
    private String localPicsRootPath;
    /** When false, PICS skips downloading from {@code FRAMEIMAGEPATH} URLs and uses only local folder images. */
    private boolean picsFetchFromLinks = true;
    /**
     * Optional line item when frames are fully allocated but campaign budget remains; does not affect
     * {@link com.autoproject.service.summary.SuggestionOptimizer} (which always uses {@link #budget}).
     */
    private int photographyBudget;
    private Map<String, Double> usdExchangeRateByCurrency = new LinkedHashMap<>();

    public Brief() {
    }

    public Brief(String location, int budget, int campaignDays) {
        this(location, budget, campaignDays, false, null);
    }

    public Brief(String location, int budget, int campaignDays, boolean convertBudgetToUsd, Double usdExchangeRate) {
        this.location = location;
        this.budget = budget;
        this.campaignDays = campaignDays;
        this.sot = null;
        this.convertBudgetToUsd = convertBudgetToUsd;
        this.usdExchangeRate = usdExchangeRate;
    }

    public Brief(
            String location,
            int budget,
            int campaignDays,
            boolean convertBudgetToUsd,
            Double usdExchangeRate,
            Map<String, Double> usdExchangeRateByCurrency
    ) {
        this(location, budget, campaignDays, convertBudgetToUsd, usdExchangeRate);
        setUsdExchangeRateByCurrency(usdExchangeRateByCurrency);
    }

    public Brief(
            String location,
            int budget,
            int campaignDays,
            Double sot,
            boolean convertBudgetToUsd,
            Double usdExchangeRate,
            Map<String, Double> usdExchangeRateByCurrency
    ) {
        this(location, budget, campaignDays, convertBudgetToUsd, usdExchangeRate, usdExchangeRateByCurrency);
        this.sot = sot;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public int getBudget() {
        return budget;
    }

    public void setBudget(int budget) {
        this.budget = budget;
    }

    public int getCampaignDays() {
        return campaignDays;
    }

    public void setCampaignDays(int campaignDays) {
        this.campaignDays = campaignDays;
    }

    public Double getSot() {
        return sot;
    }

    public void setSot(Double sot) {
        this.sot = sot;
    }

    public boolean isConvertBudgetToUsd() {
        return convertBudgetToUsd;
    }

    public void setConvertBudgetToUsd(boolean convertBudgetToUsd) {
        this.convertBudgetToUsd = convertBudgetToUsd;
    }

    public Double getUsdExchangeRate() {
        return usdExchangeRate;
    }

    public void setUsdExchangeRate(Double usdExchangeRate) {
        this.usdExchangeRate = usdExchangeRate;
    }

    public Map<String, Double> getUsdExchangeRateByCurrency() {
        return Collections.unmodifiableMap(usdExchangeRateByCurrency);
    }

    public void setUsdExchangeRateByCurrency(Map<String, Double> usdExchangeRateByCurrency) {
        this.usdExchangeRateByCurrency.clear();
        if (usdExchangeRateByCurrency == null) {
            return;
        }
        this.usdExchangeRateByCurrency.putAll(usdExchangeRateByCurrency);
    }

    public String getLocalPicsRootPath() {
        return localPicsRootPath;
    }

    public void setLocalPicsRootPath(String localPicsRootPath) {
        this.localPicsRootPath = localPicsRootPath;
    }

    public boolean isPicsFetchFromLinks() {
        return picsFetchFromLinks;
    }

    public void setPicsFetchFromLinks(boolean picsFetchFromLinks) {
        this.picsFetchFromLinks = picsFetchFromLinks;
    }

    public int getPhotographyBudget() {
        return photographyBudget;
    }

    public void setPhotographyBudget(int photographyBudget) {
        this.photographyBudget = Math.max(0, photographyBudget);
    }
}
