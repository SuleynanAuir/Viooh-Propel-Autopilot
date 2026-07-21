package com.autoproject.ui;

import com.autoproject.service.summary.PhotographyBudgetEvaluator;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Window;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * Asks whether to add unspent campaign budget as photography budget (does not change frame allocation).
 */
public final class PhotographyBudgetDialog {
    private PhotographyBudgetDialog() {
    }

    /**
     * @return photography budget to add (0 if skipped or invalid)
     */
    public static int show(Window owner, PhotographyBudgetEvaluator.Snapshot snapshot) {
        if (snapshot == null || !snapshot.eligibleForPrompt()) {
            return 0;
        }
        NumberFormat usd = NumberFormat.getCurrencyInstance(Locale.US);
        JDialog dialog = new JDialog(owner, "Photography budget", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setAlwaysOnTop(true);
        dialog.setLayout(new BorderLayout(8, 8));
        JPanel content = new JPanel(new GridLayout(0, 1, 4, 4));
        content.setBorder(new EmptyBorder(12, 12, 8, 12));
        content.add(new JLabel("All frames are at full screen count and 30% SOT, but campaign budget is not fully spent."));
        content.add(new JLabel("Total media spend (all frames): " + usd.format(snapshot.totalMediaSpend())));
        content.add(new JLabel("Campaign budget (allocation input): " + usd.format(snapshot.campaignBudget())));
        content.add(new JLabel("Unspent from campaign budget: " + usd.format(snapshot.unspentBudget())));

        int defaultUnspent = snapshot.unspentBudgetRounded();
        JCheckBox addShortfall = new JCheckBox(
                "Add " + usd.format(defaultUnspent) + " photography budget",
                true);
        JCheckBox addCustom = new JCheckBox("Add custom amount:");
        JTextField customField = new JTextField(12);
        customField.setEnabled(false);

        addShortfall.addActionListener(e -> {
            if (addShortfall.isSelected()) {
                addCustom.setSelected(false);
                customField.setEnabled(false);
            }
        });
        addCustom.addActionListener(e -> {
            if (addCustom.isSelected()) {
                addShortfall.setSelected(false);
                customField.setEnabled(true);
                customField.requestFocusInWindow();
            } else {
                customField.setEnabled(false);
            }
        });

        JPanel optionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        optionPanel.add(addCustom);
        optionPanel.add(customField);
        content.add(addShortfall);
        content.add(optionPanel);

        final int[] result = {0};
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton confirm = new JButton("Confirm");
        JButton skip = new JButton("Skip");
        confirm.addActionListener(e -> {
            if (addCustom.isSelected()) {
                result[0] = parseCustomAmount(customField.getText());
            } else if (addShortfall.isSelected()) {
                result[0] = defaultUnspent;
            }
            dialog.dispose();
        });
        skip.addActionListener(e -> {
            result[0] = 0;
            dialog.dispose();
        });
        buttons.add(confirm);
        buttons.add(skip);

        dialog.add(content, BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.toFront();
        dialog.requestFocus();
        dialog.setVisible(true);
        return Math.max(0, result[0]);
    }

    private static int parseCustomAmount(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return 0;
        }
        String t = raw.trim().replace(",", "").replace("$", "");
        try {
            double v = Double.parseDouble(t);
            if (v <= 0) {
                return 0;
            }
            return (int) Math.round(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
