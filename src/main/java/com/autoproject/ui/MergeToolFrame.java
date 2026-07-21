package com.autoproject.ui;

import com.autoproject.model.Brief;
import com.autoproject.model.FrameData;
import com.autoproject.service.AuditLogger;
import com.autoproject.service.CsvReader;
import com.autoproject.service.DataMerger;
import com.autoproject.service.ExcelGenerator;
import com.autoproject.service.FrameDetailsFileDetector;
import com.autoproject.service.ExportProgress;
import com.autoproject.service.pics.PicsSheetWriter;
import com.autoproject.service.summary.PhotographyBudgetEvaluator;
import com.autoproject.service.summary.ProposalBuilder;
import com.autoproject.service.summary.ProposalPricing;
import com.autoproject.service.summary.ProposalSummaryRow;
import com.autoproject.service.summary.SuggestionOptimizer;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JCheckBox;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.ProgressMonitor;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Color;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MergeToolFrame extends JFrame {

    /** When PICS venue groups exceed this count, ask before downloading from {@code FRAMEIMAGEPATH} links and show a progress monitor. */
    private static final int PICS_GROUP_COUNT_LINK_PROMPT_THRESHOLD = 20;
    private static final DateTimeFormatter ERROR_TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DefaultListModel<String> fileListModel = new DefaultListModel<>();
    private final JList<String> fileList = new JList<>(fileListModel);
    private final JTextField outputField = new JTextField("Proposal_Client_VIOOH_Date.xlsx", 20);
    private final JTextField locationField = new JTextField("\\N", 12);
    private final JTextField localPicsRootPathField = new JTextField(18);
    private final JTextField budgetField = new JTextField(10);
    // 隐藏：无 budget 模式下的 SOT 输入框；恢复时取消本行与 buildCenterPanel / buildBriefFromInputs 中对应注释
    // private final JTextField sotField = new JTextField(10);
    private final JTextField campaignDaysField = new JTextField("7", 10);
    private final JCheckBox convertBudgetToUsdCheckbox = new JCheckBox("Convert budget currency to USD");
    private final JTextArea logArea = new JTextArea(6, 42);
    private final JLabel dropHintLabel = new JLabel(
            "Drag and drop frame-list CSV/TSV/Excel files and optional frame-details xlsx/csv files (name contains \"details\")"
    );

    public MergeToolFrame() {
        String buildStamp = readBuildStamp();
        setTitle(buildStamp.isEmpty() ? "propel" : "propel - build " + buildStamp);
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));
        setMinimumSize(new Dimension(520, 420));

        JPanel formPanel = buildCenterPanel();
        JScrollPane formScroll = new JScrollPane(formPanel);
        formScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        formScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        formScroll.getVerticalScrollBar().setUnitIncrement(16);
        formScroll.getHorizontalScrollBar().setUnitIncrement(24);
        formScroll.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 4));

        JPanel bottomPanel = buildBottomPanel();
        bottomPanel.setMinimumSize(new Dimension(200, 120));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, formScroll, bottomPanel);
        split.setResizeWeight(0.62);
        split.setDividerSize(8);
        split.setOneTouchExpandable(true);
        split.setBorder(null);
        split.setContinuousLayout(true);

        add(split, BorderLayout.CENTER);
        pack();
        setLocationRelativeTo(null);
    }

    private static String readBuildStamp() {
        Properties p = new Properties();
        try (InputStream in = MergeToolFrame.class.getResourceAsStream("/autoproject-build.properties")) {
            if (in == null) {
                return "";
            }
            p.load(in);
            String v = p.getProperty("buildTime", "");
            return v == null ? "" : v.trim();
        } catch (IOException e) {
            return "";
        }
    }

    private JPanel buildCenterPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 8, 8, 8);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        gbc.gridx = 0;
        gbc.gridy = 0;
        panel.add(new JLabel("Selected input files (frame lists + optional frame-details):"), gbc);

        dropHintLabel.setForeground(new Color(40, 98, 154));
        gbc.gridy = 1;
        panel.add(dropHintLabel, gbc);

        fileList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        JScrollPane fileScroll = new JScrollPane(fileList);
        fileScroll.setMinimumSize(new Dimension(160, 100));
        fileScroll.setPreferredSize(new Dimension(400, 200));
        enableFileDrop(fileScroll);
        gbc.gridy = 2;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weighty = 1.0;
        panel.add(fileScroll, gbc);

        JPanel fileButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton addFilesBtn = new JButton("Add Files");
        JButton removeSelectedBtn = new JButton("Remove Selected");
        JButton clearBtn = new JButton("Clear");
        fileButtons.add(addFilesBtn);
        fileButtons.add(removeSelectedBtn);
        fileButtons.add(clearBtn);

        gbc.gridy = 3;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weighty = 0.0;
        panel.add(fileButtons, gbc);

        JButton chooseOutputBtn = new JButton("Choose Output Path");
        JPanel outputPanel = new JPanel(new GridBagLayout());
        GridBagConstraints og = new GridBagConstraints();
        og.insets = new Insets(2, 0, 2, 8);
        og.anchor = GridBagConstraints.LINE_START;
        og.gridy = 0;
        og.gridx = 0;
        og.weightx = 0;
        og.fill = GridBagConstraints.NONE;
        outputPanel.add(new JLabel("Output Excel:"), og);
        og.gridx = 1;
        og.weightx = 1.0;
        og.fill = GridBagConstraints.HORIZONTAL;
        outputPanel.add(outputField, og);
        og.gridx = 2;
        og.weightx = 0;
        og.fill = GridBagConstraints.NONE;
        outputPanel.add(chooseOutputBtn, og);

        gbc.gridy = 4;
        panel.add(outputPanel, gbc);

        JButton choosePicsRootBtn = new JButton("Choose PICS Root");

        JPanel briefLine1 = new JPanel(new GridBagLayout());
        GridBagConstraints b1 = new GridBagConstraints();
        b1.insets = new Insets(2, 0, 2, 8);
        b1.anchor = GridBagConstraints.LINE_START;
        b1.gridy = 0;
        b1.gridx = 0;
        b1.weightx = 0;
        briefLine1.add(new JLabel("Location (optional):"), b1);
        b1.gridx = 1;
        b1.weightx = 1.0;
        b1.fill = GridBagConstraints.HORIZONTAL;
        briefLine1.add(locationField, b1);
        b1.gridx = 2;
        b1.weightx = 0;
        b1.fill = GridBagConstraints.NONE;
        briefLine1.add(new JLabel("Budget (required):"), b1);
        b1.gridx = 3;
        briefLine1.add(budgetField, b1);
        b1.gridx = 4;
        briefLine1.add(new JLabel("CampaignDays:"), b1);
        b1.gridx = 5;
        briefLine1.add(campaignDaysField, b1);

        JPanel briefLine2 = new JPanel(new GridBagLayout());
        GridBagConstraints b2 = new GridBagConstraints();
        b2.insets = new Insets(2, 0, 2, 8);
        b2.anchor = GridBagConstraints.LINE_START;
        b2.gridy = 0;
        b2.gridx = 0;
        b2.weightx = 0;
        briefLine2.add(new JLabel("Google Drive image root (optional):"), b2);
        b2.gridx = 1;
        b2.weightx = 1.0;
        b2.fill = GridBagConstraints.HORIZONTAL;
        briefLine2.add(localPicsRootPathField, b2);
        b2.gridx = 2;
        b2.weightx = 0;
        b2.fill = GridBagConstraints.NONE;
        briefLine2.add(choosePicsRootBtn, b2);

        JPanel briefLine3 = new JPanel(new GridBagLayout());
        GridBagConstraints b3 = new GridBagConstraints();
        b3.insets = new Insets(2, 0, 2, 8);
        b3.anchor = GridBagConstraints.LINE_START;
        b3.gridx = 0;
        b3.gridy = 0;
        b3.weightx = 1.0;
        b3.fill = GridBagConstraints.HORIZONTAL;
        briefLine3.add(convertBudgetToUsdCheckbox, b3);

        JPanel briefPanel = new JPanel(new GridBagLayout());
        GridBagConstraints bg = new GridBagConstraints();
        bg.gridx = 0;
        bg.gridy = 0;
        bg.anchor = GridBagConstraints.WEST;
        bg.fill = GridBagConstraints.HORIZONTAL;
        bg.weightx = 1.0;
        briefPanel.add(briefLine1, bg);
        bg.gridy = 1;
        briefPanel.add(briefLine2, bg);
        bg.gridy = 2;
        briefPanel.add(briefLine3, bg);

        gbc.gridy = 5;
        panel.add(briefPanel, gbc);

        addFilesBtn.addActionListener(e -> onAddFiles());
        removeSelectedBtn.addActionListener(e -> onRemoveSelected());
        clearBtn.addActionListener(e -> fileListModel.clear());
        chooseOutputBtn.addActionListener(e -> onChooseOutput());
        choosePicsRootBtn.addActionListener(e -> onChoosePicsRoot());

        return panel;
    }

    private JPanel buildBottomPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton runBtn = new JButton("Merge and Export");
        actions.add(runBtn);
        panel.add(actions, BorderLayout.NORTH);

        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setMinimumSize(new Dimension(120, 80));
        logScroll.setPreferredSize(new Dimension(400, 140));
        panel.add(logScroll, BorderLayout.CENTER);

        runBtn.addActionListener(e -> onRun(runBtn));
        return panel;
    }

    private void onAddFiles() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose input files");
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter("Frame list / frame-details files", "csv", "tsv", "xlsx", "xls"));
        int result = chooser.showOpenDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }

        addInputFiles(List.of(chooser.getSelectedFiles()));
    }

    private void onRemoveSelected() {
        List<String> selected = fileList.getSelectedValuesList();
        for (String s : selected) {
            fileListModel.removeElement(s);
        }
    }

    private void onChooseOutput() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose output file");
        chooser.setSelectedFile(Path.of(outputField.getText()).toFile());
        chooser.setFileFilter(new FileNameExtensionFilter("Excel workbook (*.xlsx)", "xlsx"));
        int result = chooser.showSaveDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }
        String chosen = chooser.getSelectedFile().toPath().toString();
        if (!chosen.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            if (chosen.toLowerCase(Locale.ROOT).endsWith(".xsl")) {
                chosen = chosen.substring(0, chosen.length() - 4);
            }
            chosen = chosen + ".xlsx";
        }
        outputField.setText(chosen);
    }

    /** Normalizes GUI output path to {@code .xlsx} (legacy default was {@code .xsl}). */
    private static String ensureXlsxOutputPath(String raw) {
        if (raw == null) {
            return "";
        }
        String path = raw.trim();
        if (path.isEmpty()) {
            return "";
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".xlsx")) {
            return path;
        }
        if (lower.endsWith(".xsl")) {
            return path.substring(0, path.length() - 4) + ".xlsx";
        }
        return path + ".xlsx";
    }

    private void onRun(JButton runBtn) {
        if (fileListModel.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please add at least one input file first.", "Notice", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String[] inputs = new String[fileListModel.size()];
        for (int i = 0; i < fileListModel.size(); i++) {
            inputs[i] = fileListModel.getElementAt(i);
        }
        String outPath = resolveOutputPathForRun(ensureXlsxOutputPath(outputField.getText()), inputs);
        if (outPath.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please specify an output file path first.", "Notice", JOptionPane.WARNING_MESSAGE);
            return;
        }
        outputField.setText(outPath);

        Brief brief;
        try {
            brief = buildBriefFromInputs(inputs);
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Invalid Parameters", JOptionPane.ERROR_MESSAGE);
            return;
        }

        runBtn.setEnabled(false);
        appendLog("Processing started. Total files: " + inputs.length + "...");

        new SwingWorker<Void, String>() {
            @Override
            protected Void doInBackground() throws Exception {
                final ProgressMonitor[] pmRef = new ProgressMonitor[1];
                final ExportProgressMonitor[] exportProgressRef = new ExportProgressMonitor[1];
                try {
                    invokeEdtAndWait(() -> {
                        ProgressMonitor pm = new ProgressMonitor(
                                MergeToolFrame.this,
                                "正在导出",
                                "准备读取文件…",
                                0,
                                10_000);
                        pm.setMillisToDecideToPopup(0);
                        pm.setMillisToPopup(0);
                        pmRef[0] = pm;
                    });

                    exportProgressRef[0] = new ExportProgressMonitor(pmRef[0], inputs.length);

                    DataMerger merger = new DataMerger();
                    List<FrameData> mergedData = merger.merge(inputs, exportProgressRef[0]);
                    publish("Merge completed. Total rows: " + mergedData.size());

                    PicsSheetWriter picsCounter = new PicsSheetWriter();
                    final int picsGroups = picsCounter.countPicsGroups(mergedData);
                    exportProgressRef[0].afterMergeConfigurePicsGroups(picsGroups);

                    // Close progress UI before modal prompts so dialogs are not hidden behind ProgressMonitor.
                    invokeEdtAndWait(() -> closeProgressMonitor(pmRef[0]));

                    final boolean[] fetchPicsFromLinks = {true};
                    invokeEdtAndWait(() -> {
                        if (picsGroups > PICS_GROUP_COUNT_LINK_PROMPT_THRESHOLD) {
                            int choice = JOptionPane.showConfirmDialog(
                                    MergeToolFrame.this,
                                    "当前 PICS 按场地类型共有 " + picsGroups + " 组（大于 "
                                            + PICS_GROUP_COUNT_LINK_PROMPT_THRESHOLD + "）。\n"
                                            + "从 FRAMEIMAGEPATH 链接下载图片需要联网，并可能等待较长时间。\n\n"
                                    + "是否从链接获取？\n"
                                            + "选「否」将仅使用本地「Google Drive image root」文件夹下的图片。",
                                    "PICS 图片来源",
                                    JOptionPane.YES_NO_OPTION,
                                    JOptionPane.WARNING_MESSAGE);
                            fetchPicsFromLinks[0] = (choice == JOptionPane.YES_OPTION);
                        }
                    });

                    brief.setPicsFetchFromLinks(fetchPicsFromLinks[0]);

                    invokeEdtAndWait(() -> offerPhotographyBudgetIfApplicable(brief, mergedData));

                    invokeEdtAndWait(() -> {
                        int maxIndex = inputs.length + picsGroups + 2;
                        ProgressMonitor pm = new ProgressMonitor(
                                MergeToolFrame.this,
                                "正在导出",
                                "写入 Excel / PICS…",
                                inputs.length,
                                Math.max(inputs.length, maxIndex));
                        pm.setMillisToDecideToPopup(0);
                        pm.setMillisToPopup(0);
                        pm.setProgress(inputs.length);
                        pmRef[0] = pm;
                    });
                    exportProgressRef[0] = new ExportProgressMonitor(pmRef[0], inputs.length);
                    exportProgressRef[0].afterMergeConfigurePicsGroups(picsGroups);

                    ExcelGenerator generator = new ExcelGenerator();
                    generator.generate(mergedData, outPath, brief, exportProgressRef[0]);
                    publish("Export successful: " + outPath);
                    return null;
                } finally {
                    if (pmRef[0] != null) {
                        SwingUtilities.invokeLater(pmRef[0]::close);
                    }
                }
            }

            @Override
            protected void process(List<String> chunks) {
                for (String line : chunks) {
                    appendLog(line);
                }
            }

            @Override
            protected void done() {
                runBtn.setEnabled(true);
                try {
                    get();
                    AuditLogger.logRun("EXPORT", List.of(inputs), true);
                    JOptionPane.showMessageDialog(MergeToolFrame.this, "Processing completed.", "Success", JOptionPane.INFORMATION_MESSAGE);
                } catch (Throwable t) {
                    Throwable cause = t;
                    if (t instanceof java.util.concurrent.ExecutionException && t.getCause() != null) {
                        cause = t.getCause();
                    }
                    Path errorLogPath = writeErrorLog(cause, inputs, outPath);
                    String message = formatExportFailureMessage(cause, errorLogPath);
                    appendLog("Execution failed: " + message);
                    AuditLogger.logRun("EXPORT", List.of(inputs), false);
                    JOptionPane.showMessageDialog(
                            MergeToolFrame.this,
                            message,
                            "Error",
                            JOptionPane.ERROR_MESSAGE
                    );
                }
            }
        }.execute();
    }

    private static String resolveOutputPathForRun(String rawPath, String[] inputs) {
        if (rawPath == null || rawPath.trim().isEmpty()) {
            return "";
        }
        Path path = Path.of(rawPath.trim());
        if (path.isAbsolute()) {
            return path.toString();
        }
        Path baseDir = null;
        if (inputs != null) {
            for (String input : inputs) {
                if (input == null || input.trim().isEmpty()) {
                    continue;
                }
                Path parent = Path.of(input.trim()).toAbsolutePath().getParent();
                if (parent != null) {
                    baseDir = parent;
                    break;
                }
            }
        }
        if (baseDir == null) {
            String home = System.getProperty("user.home");
            baseDir = (home == null || home.isBlank()) ? Path.of("").toAbsolutePath() : Path.of(home);
        }
        return baseDir.resolve(path).normalize().toString();
    }

    private void onChoosePicsRoot() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose local Google Drive image root");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        int result = chooser.showOpenDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }
        localPicsRootPathField.setText(chooser.getSelectedFile().toPath().toString());
    }

    private void appendLog(String line) {
        logArea.append(line + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private static String formatExportFailureMessage(Throwable cause, Path errorLogPath) {
        Throwable root = rootCause(cause);
        Throwable displayCause = cause;
        if (displayCause == null || displayCause.getMessage() == null || displayCause.getMessage().isBlank()) {
            displayCause = root;
        }
        String logLine = errorLogPath == null ? "" : "\nDetailed log: " + errorLogPath;
        if (cause instanceof OutOfMemoryError || root instanceof OutOfMemoryError) {
            return "Java ran out of memory while exporting (often Excel / large VS CPM details).\n"
                    + "Try fewer/smaller input files, or run with more heap (propel is packaged with -Xmx4g).\n"
                    + "Technical: " + cause.getClass().getSimpleName()
                    + logLine;
        }
        String detail = displayCause.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = displayCause.getClass().getSimpleName();
        }
        return "Execution failed. Please check logs.\n"
                + displayCause.getClass().getSimpleName() + ": " + detail
                + logLine;
    }

    private static Throwable rootCause(Throwable cause) {
        Throwable current = cause == null ? new RuntimeException("Unknown error") : cause;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static Path writeErrorLog(Throwable cause, String[] inputs, String outPath) {
        Path logPath = resolveErrorLogPath();
        if (logPath == null) {
            return null;
        }
        StringWriter stack = new StringWriter();
        rootCause(cause).printStackTrace(new PrintWriter(stack));
        if (cause != null && cause != rootCause(cause)) {
            stack.append(System.lineSeparator()).append("Wrapped cause:").append(System.lineSeparator());
            cause.printStackTrace(new PrintWriter(stack));
        }

        StringBuilder entry = new StringBuilder();
        entry.append("==== ")
                .append(LocalDateTime.now().format(ERROR_TS_FORMAT))
                .append(" EXPORT FAIL ====")
                .append(System.lineSeparator());
        entry.append("cwd=").append(Path.of("").toAbsolutePath()).append(System.lineSeparator());
        entry.append("output=").append(outPath == null ? "-" : outPath).append(System.lineSeparator());
        entry.append("inputs=").append(inputs == null ? "-" : String.join(", ", inputs)).append(System.lineSeparator());
        entry.append(stack).append(System.lineSeparator());

        try {
            Files.writeString(
                    logPath,
                    entry.toString(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
            return logPath;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static Path resolveErrorLogPath() {
        List<Path> candidates = new ArrayList<>();
        String configuredDir = System.getProperty("propel.log.dir");
        if (configuredDir != null && !configuredDir.trim().isEmpty()) {
            candidates.add(Path.of(configuredDir.trim()));
        }
        candidates.add(Path.of("").toAbsolutePath());
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            candidates.add(Path.of(home, "Desktop", "VIOOH", "Propel_mac"));
            candidates.add(Path.of(home));
        }
        for (Path dir : candidates) {
            try {
                if (Files.isDirectory(dir) && Files.isWritable(dir)) {
                    return dir.resolve("propel_error.log");
                }
            } catch (Exception ignored) {
                // Try the next candidate.
            }
        }
        return null;
    }

    private void enableFileDrop(JScrollPane fileScroll) {
        fileScroll.setTransferHandler(new javax.swing.TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) {
                    return false;
                }
                try {
                    List<File> dropped = (List<File>) support.getTransferable()
                            .getTransferData(DataFlavor.javaFileListFlavor);
                    addInputFiles(dropped);
                    return true;
                } catch (Exception e) {
                    appendLog("Failed to read dropped files: " + e.getMessage());
                    return false;
                }
            }
        });
    }

    private void addInputFiles(List<File> files) {
        List<String> skipped = new ArrayList<>();
        for (File file : files) {
            if (file == null || !file.isFile()) {
                continue;
            }
            String lower = file.getName().toLowerCase();
            if (!(lower.endsWith(".csv") || lower.endsWith(".tsv") || lower.endsWith(".xlsx") || lower.endsWith(".xls"))) {
                skipped.add(file.getName());
                continue;
            }
            String path = file.toPath().toString();
            if (!fileListModel.contains(path)) {
                fileListModel.addElement(path);
            }
        }
        if (!skipped.isEmpty()) {
            appendLog("The following files are not CSV/TSV/Excel and were skipped: " + String.join(", ", skipped));
        }
    }

    /**
     * Frame allocation uses {@link Brief#getBudget()} only. Optional photography budget is offered when every
     * allocatable row is at full screens and 30% SOT but media spend is below campaign budget.
     */
    private void offerPhotographyBudgetIfApplicable(Brief brief, List<FrameData> mergedData) {
        if (brief == null || brief.getBudget() <= 0 || mergedData == null) {
            return;
        }
        List<FrameData> filtered = ExcelGenerator.filterFramesForExport(mergedData);
        List<ProposalSummaryRow> rows = new ProposalBuilder().build(filtered);
        if (rows.isEmpty()) {
            return;
        }
        List<Double> cpms = new ArrayList<>();
        for (ProposalSummaryRow row : rows) {
            cpms.add(ProposalPricing.effectiveCpmForBudget(row, brief));
        }
        SuggestionOptimizer optimizer = new SuggestionOptimizer();
        SuggestionOptimizer.AllocationOutcome outcome = optimizer.recommendGlobalOutcome(brief, rows, cpms);
        PhotographyBudgetEvaluator.Snapshot snapshot =
                PhotographyBudgetEvaluator.fromAllocationOutcome(brief, outcome);
        appendLog("Photography budget check: proposalRows=" + rows.size()
                + ", optimizerCandidates=" + outcome.candidateCount()
                + ", allAtFullScreensAnd30PctSot=" + outcome.allInventoryMaxed()
                + ", optimizerRemaining=" + (int) Math.round(outcome.remainingBudget()));
        if (!snapshot.eligibleForPrompt()) {
            brief.setPhotographyBudget(0);
            appendLog("Photography budget dialog skipped: " + snapshot.skipReason());
            return;
        }
        appendLog("Photography budget dialog: unspent " + snapshot.unspentBudgetRounded()
                + " — opening dialog.");
        int chosen = PhotographyBudgetDialog.show(MergeToolFrame.this, snapshot);
        brief.setPhotographyBudget(chosen);
        if (chosen > 0) {
            appendLog("Photography budget added: " + chosen
                    + " (proposal total media + photography = "
                    + ((int) Math.round(snapshot.totalMediaSpend())) + chosen + ")");
        }
    }

    private Brief buildBriefFromInputs(String[] inputs) {
        String locationRaw = locationField.getText();
        String location = (locationRaw == null || locationRaw.trim().isEmpty()) ? "\\N" : locationRaw.trim();

        String budgetRaw = budgetField.getText();
        if (budgetRaw == null || budgetRaw.trim().isEmpty()) {
            throw new IllegalArgumentException("Budget is required.");
        }
        int budget;
        try {
            budget = Integer.parseInt(budgetRaw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Budget must be an integer.");
        }
        if (budget <= 0) {
            throw new IllegalArgumentException("Budget must be greater than 0.");
        }

        String campaignRaw = campaignDaysField.getText();
        int campaignDays = 7;
        if (campaignRaw != null && !campaignRaw.trim().isEmpty()) {
            try {
                campaignDays = Integer.parseInt(campaignRaw.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("CampaignDays must be an integer.");
            }
        }
        if (campaignDays <= 0) {
            throw new IllegalArgumentException("CampaignDays must be greater than 0.");
        }

        Double sot = null;
        // 隐藏：无 budget + SOT 校验与读取；与上方 sotField 一并取消注释
        // String sotRaw = sotField.getText();
        // sot = parseSot(sotRaw);
        // if (budget <= 0 && sot == null) {
        //     throw new IllegalArgumentException("Please provide either Budget, or SOT (for no-budget mode).");
        // }
        // if (budget > 0 && sot != null) {
        //     appendLog("Budget is provided, SOT is ignored. Proposal uses budget mode.");
        //     sot = null;
        // }

        boolean convertBudgetToUsd = convertBudgetToUsdCheckbox.isSelected();
        Map<String, Double> usdExchangeRateByCurrency = new LinkedHashMap<>();
        if (convertBudgetToUsd) {
            List<String> detectedCurrencies = detectCurrenciesFromInputs(inputs);
            usdExchangeRateByCurrency = askUsdExchangeRateByCurrency(detectedCurrencies);
        }
        Brief brief = new Brief(location, budget, campaignDays, sot, convertBudgetToUsd, null, usdExchangeRateByCurrency);
        String localPicsRootPath = localPicsRootPathField.getText();
        if (localPicsRootPath != null) {
            String trimmed = localPicsRootPath.trim();
            brief.setLocalPicsRootPath(trimmed.isEmpty() ? null : trimmed);
        }
        return brief;
    }

    /** 隐藏路径启用后使用：{@link #buildBriefFromInputs} 里 SOT 相关取消注释时。 */
    @SuppressWarnings("unused")
    private Double parseSot(String raw) {
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
            throw new IllegalArgumentException("SOT must be numeric. Examples: 0.2 or 20%.");
        }
        if (isPercent) {
            value = value / 100d;
        }
        if (value <= 0 || value >= 1) {
            throw new IllegalArgumentException("SOT must be greater than 0 and less than 1.");
        }
        return value;
    }

    private List<String> detectCurrenciesFromInputs(String[] inputs) {
        Set<String> currencies = new LinkedHashSet<>();
        CsvReader reader = new CsvReader();
        for (String input : inputs) {
            if (FrameDetailsFileDetector.isDetailsFile(input)) {
                continue;
            }
            try {
                List<FrameData> rows = reader.read(input);
                for (FrameData row : rows) {
                    String normalized = normalizeCurrencyCode(row.getMediaOwnerCurrency());
                    if (normalized != null) {
                        currencies.add(normalized);
                    }
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to scan currencies in file: " + input + ". " + e.getMessage(), e);
            }
        }
        return new ArrayList<>(currencies);
    }

    private String normalizeCurrencyCode(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty() || "NULL".equals(normalized) || "\\N".equals(normalized) || "-".equals(normalized)) {
            return null;
        }
        return normalized;
    }

    private Map<String, Double> askUsdExchangeRateByCurrency(List<String> currencies) {
        Map<String, Double> rates = new LinkedHashMap<>();
        if (currencies == null || currencies.isEmpty()) {
            return rates;
        }

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 6, 4, 6);
        gbc.gridy = 0;
        gbc.gridx = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(new JLabel("Detected currencies from selected files:"), gbc);

        Map<String, JTextField> fieldByCurrency = new LinkedHashMap<>();
        for (String currency : currencies) {
            gbc.gridy++;
            gbc.gridx = 0;
            gbc.weightx = 0.0;
            panel.add(new JLabel(currency + " -> USD"), gbc);
            gbc.gridx = 1;
            gbc.weightx = 1.0;
            JTextField field = new JTextField(12);
            if ("USD".equals(currency)) {
                field.setText("1");
            }
            panel.add(field, gbc);
            fieldByCurrency.put(currency, field);
        }

        int result = JOptionPane.showConfirmDialog(
                this,
                panel,
                "Enter USD Rate For Each Currency",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE
        );
        if (result != JOptionPane.OK_OPTION) {
            throw new IllegalArgumentException("Cancelled by user while entering currency rates.");
        }

        for (Map.Entry<String, JTextField> entry : fieldByCurrency.entrySet()) {
            String currency = entry.getKey();
            String raw = entry.getValue().getText();
            if (raw == null || raw.trim().isEmpty()) {
                continue;
            }
            double rate;
            try {
                rate = Double.parseDouble(raw.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid numeric rate for " + currency + ": " + raw);
            }
            if (rate <= 0) {
                throw new IllegalArgumentException("Rate must be greater than 0 for " + currency + ".");
            }
            rates.put(currency, rate);
        }

        return rates;
    }

    private static void closeProgressMonitor(ProgressMonitor pm) {
        if (pm != null) {
            pm.close();
        }
    }

    private void invokeEdtAndWait(Runnable runnable) throws Exception {
        try {
            SwingUtilities.invokeAndWait(runnable);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw new RuntimeException(cause);
        }
    }

    /**
     * Maps export phases to a single {@link ProgressMonitor}. Progress indices:
     * {@code 0..fileCount-1} reading each input file, {@code fileCount} merge done,
     * {@code fileCount+1 .. fileCount+picsGroups} PICS groups, then write rows / save.
     */
    private static final class ExportProgressMonitor implements ExportProgress {
        private final ProgressMonitor pm;
        private final int fileCount;
        private int picsGroups;

        ExportProgressMonitor(ProgressMonitor pm, int fileCount) {
            this.pm = pm;
            this.fileCount = fileCount;
        }

        void afterMergeConfigurePicsGroups(int picsGroups) {
            this.picsGroups = picsGroups;
            int maxIndex = fileCount + picsGroups + 2;
            try {
                SwingUtilities.invokeAndWait(() -> {
                    pm.setMaximum(Math.max(pm.getMinimum(), maxIndex));
                    pm.setNote("已计算 PICS 分组，继续生成…");
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (InvocationTargetException e) {
                Throwable c = e.getCause();
                if (c instanceof RuntimeException re) {
                    throw re;
                }
                if (c instanceof Error err) {
                    throw err;
                }
                throw new RuntimeException(c);
            }
        }

        private void edt(Runnable r) {
            SwingUtilities.invokeLater(r);
        }

        @Override
        public void onMergeReadingFile(int indexZeroBased, int totalFiles, String path) {
            edt(() -> {
                pm.setProgress(indexZeroBased);
                String label = path == null ? "" : path;
                if (label.length() > 52) {
                    label = "…" + label.substring(label.length() - 48);
                }
                pm.setNote("读取 " + (indexZeroBased + 1) + "/" + totalFiles + ": " + label);
            });
        }

        @Override
        public void onMergeComplete(int mergedRowCount) {
            edt(() -> {
                pm.setProgress(fileCount);
                pm.setNote("已合并 " + mergedRowCount + " 行");
            });
        }

        @Override
        public void onStart(int totalGroups) {
            edt(() -> pm.setNote("PICS 图片 (" + totalGroups + " 组)…"));
        }

        @Override
        public void onGroupDone(int completedOneBased, int totalGroups, String note) {
            edt(() -> {
                pm.setProgress(fileCount + completedOneBased);
                pm.setNote("PICS: " + note + "  (" + completedOneBased + "/" + totalGroups + ")");
            });
        }

        @Override
        public boolean isCancelled() {
            return pm.isCanceled();
        }

        @Override
        public void onWritingFrameSheets() {
            edt(() -> {
                pm.setProgress(fileCount + picsGroups + 1);
                pm.setNote("写入数据表…");
            });
        }

        @Override
        public void onSavingWorkbook() {
            edt(() -> {
                pm.setProgress(fileCount + picsGroups + 2);
                pm.setNote("保存 Excel 文件…");
            });
        }

        @Override
        public void onExportComplete() {
            edt(() -> pm.setNote("完成"));
        }
    }
}
