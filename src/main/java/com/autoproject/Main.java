package com.autoproject;

import com.autoproject.model.Brief;
import com.autoproject.model.FrameData;
import com.autoproject.service.AuditLogger;
import com.autoproject.service.DataMerger;
import com.autoproject.service.ExcelGenerator;
import com.autoproject.service.summary.PhotographyBudgetEvaluator;
import com.autoproject.service.summary.ProposalBuilder;
import com.autoproject.service.summary.ProposalPricing;
import com.autoproject.service.summary.ProposalSummaryRow;
import com.autoproject.service.summary.SuggestionOptimizer;
import com.autoproject.ui.MergeToolFrame;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        if (shouldUseGui(args)) {
            launchGui();
            return;
        }

        Scanner scanner = new Scanner(System.in);
        List<String> filePaths = new ArrayList<>();
        boolean runSuccess = false;

        try {
            System.out.print("Enter the number of frame-list files to merge (CSV/TSV/Excel): ");
            int count = scanner.nextInt();
            scanner.nextLine();

            for (int i = 1; i <= count; i++) {
                System.out.print("Enter path for file " + i + ": ");
                String path = scanner.nextLine();
                filePaths.add(path);
            }

            System.out.print("Enter location (optional, press Enter for default \\N): ");
            String locationInput = scanner.nextLine();
            String location = locationInput == null || locationInput.trim().isEmpty() ? "\\N" : locationInput.trim();
            System.out.print("Enter local Google Drive image root path (optional, for PICS sheet): ");
            String localPicsRootPathInput = scanner.nextLine();
            String localPicsRootPath = localPicsRootPathInput == null ? null : localPicsRootPathInput.trim();
            if (localPicsRootPath != null && localPicsRootPath.isEmpty()) {
                localPicsRootPath = null;
            }

            System.out.print("Enter budget (required, integer > 0): ");
            String budgetRaw = scanner.nextLine();
            if (budgetRaw == null || budgetRaw.trim().isEmpty()) {
                throw new IllegalArgumentException("budget is required");
            }
            int budget;
            try {
                budget = Integer.parseInt(budgetRaw.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("budget must be an integer: " + budgetRaw, e);
            }
            if (budget <= 0) {
                throw new IllegalArgumentException("budget must be greater than 0");
            }

            // 隐藏：无 budget + 手填 SOT 的 CLI；若要恢复，取消下面注释并放宽上方 budget 必填校验，且构造 Brief 时传入 sot
            // Double sot = null;
            // if (budget <= 0) {
            //     System.out.print("No budget mode: enter SOT (<1, supports 0.2 or 20%): ");
            //     String sotRaw = scanner.nextLine();
            //     sot = parseSotInput(sotRaw);
            //     if (sot == null) {
            //         throw new IllegalArgumentException("SOT is required when budget is empty");
            //     }
            // }

            System.out.print("Enter campaignDays (optional, default 7): ");
            String campaignRaw = scanner.nextLine();
            int campaignDays = 7;
            if (campaignRaw != null && !campaignRaw.trim().isEmpty()) {
                try {
                    campaignDays = Integer.parseInt(campaignRaw.trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("campaignDays must be an integer: " + campaignRaw, e);
                }
            }
            if (campaignDays <= 0) {
                throw new IllegalArgumentException("campaignDays must be greater than 0");
            }
            System.out.print("Convert budget currency to USD? (Y/N, default N): ");
            String convertRaw = scanner.nextLine();
            boolean convertBudgetToUsd = convertRaw != null && "Y".equalsIgnoreCase(convertRaw.trim());
            Map<String, Double> usdExchangeRateByCurrency = new LinkedHashMap<>();
            if (convertBudgetToUsd) {
                System.out.print("Enter per-currency USD rates (e.g. EUR=1.08,GBP=1.27,USD=1): ");
                String perCurrencyRaw = scanner.nextLine();
                usdExchangeRateByCurrency = parseUsdRatesByCurrency(perCurrencyRaw);
            }
            Brief brief = new Brief(location, budget, campaignDays, null, convertBudgetToUsd, null, usdExchangeRateByCurrency);
            brief.setLocalPicsRootPath(localPicsRootPath);

            // List -> String
            DataMerger merger = new DataMerger();
            List<FrameData> mergedData = merger.merge(
                    filePaths.toArray(new String[0])
            );

            System.out.println("Merge complete. Total rows: " + mergedData.size());

            offerPhotographyBudgetCli(scanner, brief, mergedData);

            ExcelGenerator generator = new ExcelGenerator();
            generator.generate(mergedData, "Proposal_Client_VIOOH_Date.xlsx", brief);

            System.out.println("✅ Success! File generated: Proposal_Client_VIOOH_Date.xlsx");
            runSuccess = true;

        } catch (Exception e) {
            System.out.println("❌ Execution failed:");
            e.printStackTrace();
        } finally {
            AuditLogger.logRun("GENERATE", filePaths, runSuccess);
            scanner.close();
        }
    }

    /** Blocks until the Swing UI is on screen so the JVM does not exit before the EDT starts (common with jpackage exe). */
    private static void launchGui() {
        try {
            Toolkit.getDefaultToolkit();
            SwingUtilities.invokeAndWait(() -> {
                MergeToolFrame frame = new MergeToolFrame();
                frame.setVisible(true);
            });
        } catch (Exception e) {
            Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null
                    ? ite.getCause()
                    : e;
            System.err.println("GUI startup failed: " + cause.getMessage());
            cause.printStackTrace();
            if (!GraphicsEnvironment.isHeadless()) {
                JOptionPane.showMessageDialog(
                        null,
                        "Failed to start propel.\n" + cause.getMessage(),
                        "propel",
                        JOptionPane.ERROR_MESSAGE
                );
            }
            System.exit(1);
        }
    }

    /** Desktop / packaged exe: GUI by default. Pass {@code --cli} for the interactive console flow. */
    private static boolean shouldUseGui(String[] args) {
        if (GraphicsEnvironment.isHeadless()) {
            return false;
        }
        if (args != null) {
            for (String arg : args) {
                if ("--cli".equalsIgnoreCase(arg)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Map<String, Double> parseUsdRatesByCurrency(String raw) {
        Map<String, Double> rates = new LinkedHashMap<>();
        if (raw == null || raw.trim().isEmpty()) {
            return rates;
        }
        String[] tokens = raw.split(",");
        for (String token : tokens) {
            String item = token == null ? "" : token.trim();
            if (item.isEmpty()) {
                continue;
            }
            int eq = item.indexOf('=');
            if (eq <= 0 || eq >= item.length() - 1) {
                throw new IllegalArgumentException("Invalid rate format: " + item + ". Use EUR=1.08");
            }
            String currency = item.substring(0, eq).trim().toUpperCase(Locale.ROOT);
            String rateRaw = item.substring(eq + 1).trim();
            if (currency.isEmpty()) {
                throw new IllegalArgumentException("Currency code cannot be empty in: " + item);
            }
            double rate;
            try {
                rate = Double.parseDouble(rateRaw);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid numeric rate in: " + item, e);
            }
            if (rate <= 0) {
                throw new IllegalArgumentException("Rate must be greater than 0 in: " + item);
            }
            rates.put(currency, rate);
        }
        return rates;
    }

    private static void offerPhotographyBudgetCli(Scanner scanner, Brief brief, List<FrameData> mergedData) {
        List<FrameData> filtered = ExcelGenerator.filterFramesForExport(mergedData);
        List<ProposalSummaryRow> rows = new ProposalBuilder().build(filtered);
        if (rows.isEmpty()) {
            return;
        }
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
            if (snapshot.skipReason() != null && !snapshot.skipReason().isBlank()) {
                System.out.println("Photography budget skipped: " + snapshot.skipReason());
            }
            return;
        }
        System.out.printf(
                "All frames are fully allocated at 30%% SOT. Media spend: %.0f. Campaign budget: %d. Unspent: %.0f%n",
                snapshot.totalMediaSpend(),
                snapshot.campaignBudget(),
                snapshot.unspentBudget());
        System.out.print("Add photography budget? (Y=unspent amount / N=skip / or enter custom integer): ");
        String line = scanner.nextLine();
        if (line == null || line.trim().isEmpty() || "N".equalsIgnoreCase(line.trim())) {
            brief.setPhotographyBudget(0);
            return;
        }
        if ("Y".equalsIgnoreCase(line.trim())) {
            brief.setPhotographyBudget(snapshot.unspentBudgetRounded());
            return;
        }
        try {
            int custom = Integer.parseInt(line.trim());
            brief.setPhotographyBudget(Math.max(0, custom));
        } catch (NumberFormatException e) {
            brief.setPhotographyBudget(0);
        }
    }

    /** 隐藏路径启用后使用：{@link #main} 里无 budget + SOT 的 CLI 取消注释时。 */
    @SuppressWarnings("unused")
    private static Double parseSotInput(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String normalized = raw.trim();
        boolean isPercent = normalized.endsWith("%");
        if (isPercent) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        double value;
        try {
            value = Double.parseDouble(normalized);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("SOT must be numeric. Examples: 0.2 or 20%", e);
        }
        if (isPercent) {
            value = value / 100d;
        }
        if (value <= 0 || value >= 1) {
            throw new IllegalArgumentException("SOT must be greater than 0 and less than 1");
        }
        return value;
    }
}
