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
            throw new IllegalArgumentException("请至少上传一个 frame list CSV、TSV 或 Excel 文件。");
        }
        List<String> inputPaths = new ArrayList<>();
        for (MultipartFormData.FilePart part : inputParts) {
            validateInputExtension(part.originalFileName());
            inputPaths.add(part.path().toString());
        }

        int budget = positiveInteger(form.first("budget"), "Campaign budget");
        int campaignDays = optionalPositiveInteger(form.first("campaignDays"), 7, "Campaign days");
        String location = trimToDefault(form.first("location"), "\\N");
        boolean convertToUsd = booleanValue(form.first("convertBudgetToUsd"));
        Map<String, Double> usdRates = parseRates(form.first("usdRates"));
        if (convertToUsd && usdRates.isEmpty()) {
            throw new IllegalArgumentException("启用 USD 换算后，请至少填写一个汇率，例如 EUR=1.08。");
        }

        boolean fetchRemote = booleanValue(form.first("fetchPicsFromLinks"));
        if (fetchRemote && !allowRemoteImages) {
            throw new IllegalArgumentException("该部署未开启远程图片抓取；请上传本地 PICS 文件夹或联系管理员。");
        }

        Brief brief = new Brief(location, budget, campaignDays, convertToUsd, null, usdRates);
        brief.setPicsFetchFromLinks(fetchRemote);
        if (!form.files("picsFiles").isEmpty()) {
            brief.setLocalPicsRootPath(taskRoot.resolve("pics").toString());
        }

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
                throw new IllegalArgumentException("汇率格式无效：" + item + "。请使用 EUR=1.08。");
            }
            String currency = item.substring(0, equals).trim().toUpperCase(Locale.ROOT);
            if (!currency.matches("[A-Z]{3}")) {
                throw new IllegalArgumentException("币种代码必须是 3 个字母：" + currency);
            }
            double rate;
            try {
                rate = Double.parseDouble(item.substring(equals + 1).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("汇率必须是数字：" + item, e);
            }
            if (!Double.isFinite(rate) || rate <= 0) {
                throw new IllegalArgumentException("汇率必须大于 0：" + item);
            }
            rates.put(currency, rate);
        }
        return rates;
    }

    private static void validateInputExtension(String fileName) {
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        String extension = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!INPUT_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("不支持的输入文件：" + fileName);
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
