package com.autoproject.web;

import com.autoproject.model.Brief;
import com.autoproject.model.FrameData;
import com.autoproject.service.DataMerger;
import com.autoproject.service.ExcelGenerator;
import com.autoproject.service.summary.PhotographyBudgetEvaluator;
import com.autoproject.service.summary.ProposalBuilder;
import com.autoproject.service.summary.ProposalPricing;
import com.autoproject.service.summary.ProposalSummaryRow;
import com.autoproject.service.summary.SuggestionOptimizer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class WebExportService {
    private static final Set<String> INPUT_EXTENSIONS = Set.of("csv", "tsv", "xlsx", "xls");
    private final boolean allowRemoteImages;

    WebExportService(boolean allowRemoteImages) {
        this.allowRemoteImages = allowRemoteImages;
    }

    ExportResult export(MultipartFormData.Form form, Path taskRoot) throws Exception {
        List<MultipartFormData.FilePart> inputParts = form.files("inputFiles");
        if (inputParts.isEmpty()) {
            throw new IllegalArgumentException("Upload at least one frame list CSV, TSV, or Excel file.");
        }
        List<String> inputPaths = new ArrayList<>();
        for (MultipartFormData.FilePart part : inputParts) {
            validateInputExtension(part.originalFileName());
            inputPaths.add(part.path().toString());
        }

        int budget = positiveInteger(form.first("budget"), "Campaign budget");
        int campaignDays = optionalPositiveInteger(form.first("campaignDays"), 7, "Campaign days");
        String location = trimToDefault(form.first("location"), "");
        String sourceCurrency = requiredCurrency(form.first("sourceCurrency"), "Original currency");
        String targetCurrency = requiredCurrency(form.first("targetCurrency"), "Target currency");
        double exchangeRate = exchangeRate(sourceCurrency, targetCurrency, form.first("exchangeRate"));

        if (!form.files("picsFiles").isEmpty()) {
            throw new IllegalArgumentException("PICS images can only be fetched from FRAMEIMAGEPATH. Local PICS uploads are not supported.");
        }

        if (!allowRemoteImages) {
            throw new IllegalArgumentException("This deployment is not allowed to fetch FRAMEIMAGEPATH images.");
        }

        Brief brief = new Brief(location, budget, campaignDays, true, null, Map.of());
        brief.setSourceCurrency(sourceCurrency);
        brief.setTargetCurrency(targetCurrency);
        brief.setCurrencyExchangeRate(exchangeRate);
        brief.setPicsFetchFromLinks(true);

        List<FrameData> merged = new DataMerger().merge(inputPaths.toArray(String[]::new));
        applyPhotographyBudget(brief, merged, form.first("photographyMode"), form.first("photographyBudget"));

        String downloadName = safeOutputName(form.first("outputName"));
        Path output = taskRoot.resolve("output.xlsx");
        new ExcelGenerator().generate(merged, output.toString(), brief);
        if (!Files.isRegularFile(output) || Files.size(output) == 0) {
            throw new IllegalStateException("Excel export did not produce a file");
        }
        return new ExportResult(output, downloadName, merged.size(), ExcelGenerator.countFilteredExportRows(merged));
    }

    private static void applyPhotographyBudget(Brief brief, List<FrameData> merged, String rawMode, String rawCustom) {
        String mode = trimToDefault(rawMode, "none").toLowerCase(Locale.ROOT);
        if ("none".equals(mode)) {
            brief.setPhotographyBudget(0);
            return;
        }
        if (!("auto".equals(mode) || "custom".equals(mode))) {
            throw new IllegalArgumentException("Unknown photography budget mode");
        }

        List<ProposalSummaryRow> rows = new ProposalBuilder().build(ExcelGenerator.filterFramesForExport(merged));
        List<Double> cpms = new ArrayList<>();
        for (ProposalSummaryRow row : rows) {
            cpms.add(ProposalPricing.effectiveCpmForBudget(row, brief));
        }
        SuggestionOptimizer.AllocationOutcome outcome =
                new SuggestionOptimizer().recommendGlobalOutcome(brief, rows, cpms);
        PhotographyBudgetEvaluator.Snapshot snapshot =
                PhotographyBudgetEvaluator.fromAllocationOutcome(brief, outcome);
        if (!snapshot.eligibleForPrompt()) {
            brief.setPhotographyBudget(0);
            return;
        }
        if ("auto".equals(mode)) {
            brief.setPhotographyBudget(snapshot.unspentBudgetRounded());
        } else {
            brief.setPhotographyBudget(nonNegativeInteger(rawCustom, "Photography budget"));
        }
    }

    private static Map<String, Double> parseRates(String raw) {
        Map<String, Double> rates = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return rates;
        }
        String normalized = raw.replace('\n', ',').replace('\r', ',');
        for (String token : normalized.split(",")) {
            String item = token.trim();
            if (item.isEmpty()) {
                continue;
            }
            int equals = item.indexOf('=');
            if (equals <= 0 || equals >= item.length() - 1) {
                throw new IllegalArgumentException("Invalid exchange rate format: " + item + ". Use EUR=1.08.");
            }
            String currency = item.substring(0, equals).trim().toUpperCase(Locale.ROOT);
            if (!currency.matches("[A-Z]{3}")) {
                throw new IllegalArgumentException("Currency code must be 3 letters: " + currency);
            }
            double rate;
            try {
                rate = Double.parseDouble(item.substring(equals + 1).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Exchange rate must be a number: " + item, e);
            }
            if (!Double.isFinite(rate) || rate <= 0) {
                throw new IllegalArgumentException("Exchange rate must be greater than 0: " + item);
            }
            rates.put(currency, rate);
        }
        return rates;
    }

    private static String requiredCurrency(String raw, String label) {
        String normalized = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException(label + " must be a 3-letter currency code");
        }
        return normalized;
    }

    private static double exchangeRate(String sourceCurrency, String targetCurrency, String raw) {
        if (sourceCurrency.equals(targetCurrency)) {
            return 1d;
        }
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Exchange rate is required when original and target currencies differ");
        }
        try {
            double rate = Double.parseDouble(raw.trim());
            if (!Double.isFinite(rate) || rate <= 0) {
                throw new IllegalArgumentException("Exchange rate must be greater than 0");
            }
            return rate;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Exchange rate must be a number", e);
        }
    }

    private static void validateInputExtension(String fileName) {
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        String extension = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!INPUT_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("Unsupported input file: " + fileName);
        }
    }

    private static int positiveInteger(String raw, String label) {
        int value = integer(raw, label);
        if (value <= 0) {
            throw new IllegalArgumentException(label + " must be greater than 0");
        }
        return value;
    }

    private static int optionalPositiveInteger(String raw, int fallback, String label) {
        return raw == null || raw.isBlank() ? fallback : positiveInteger(raw, label);
    }

    private static int nonNegativeInteger(String raw, String label) {
        int value = integer(raw, label);
        if (value < 0) {
            throw new IllegalArgumentException(label + " cannot be negative");
        }
        return value;
    }

    private static int integer(String raw, String label) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " must be an integer", e);
        }
    }

    private static boolean booleanValue(String raw) {
        return "true".equalsIgnoreCase(raw) || "1".equals(raw) || "on".equalsIgnoreCase(raw);
    }

    private static String trimToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String safeOutputName(String raw) {
        String name = trimToDefault(raw, "propel-export.xlsx")
                .replaceAll("[\\p{Cntrl}/\\\\:]", "_").trim();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            name += ".xlsx";
        }
        if (name.length() > 120) {
            name = name.substring(0, 115) + ".xlsx";
        }
        return name;
    }

    record ExportResult(Path path, String downloadName, long mergedRows, long filteredRows) {
    }
}
