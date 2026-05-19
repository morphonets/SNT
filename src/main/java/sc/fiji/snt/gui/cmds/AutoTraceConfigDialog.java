/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2026 Fiji developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package sc.fiji.snt.gui.cmds;

import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.tracing.auto.AutoTraceConfig;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Non-modal dialog displaying {@link AutoTraceConfig} results in a JTable with
 * checkboxes, allowing the user to cherry-pick which derived parameters to apply.
 * <p>
 * The dialog has two sections:
 * <ol>
 *   <li><b>Source Statistics</b> Read-only summary of what was measured from
 *       the example paths (mean radius, intensity, contraction, etc.)</li>
 *   <li><b>Derived Parameters</b> Each row shows a parameter name, its derived
 *       value, and a checkbox for inclusion when "Apply Selected" is clicked</li>
 * </ol>
 *
 * @author Tiago Ferreira
 */
public class AutoTraceConfigDialog extends JDialog {

    private final AutoTraceConfig config;
    private final ParamTableModel tableModel;
    private Consumer<AutoTraceConfig> applyCallback;

    /**
     * Creates the dialog.
     *
     * @param owner  parent window (may be null)
     * @param config the derived configuration to display
     */
    public AutoTraceConfigDialog(final Window owner, final AutoTraceConfig config) {
        super(owner, "Auto-Trace Configuration (Learned)", ModalityType.MODELESS);
        this.config = config;
        this.tableModel = new ParamTableModel(buildRows(config));
        initGUI();
    }

    /**
     * Sets the callback invoked when "Apply Selected" is clicked. The callback
     * receives the same {@link AutoTraceConfig}: the caller should inspect
     * {@link #isSelected(String)} to decide which fields to apply.
     *
     * @param callback the apply action
     */
    public void setApplyCallback(final Consumer<AutoTraceConfig> callback) {
        this.applyCallback = callback;
    }

    /**
     * Whether the row with the given parameter key is checked for application.
     *
     * @param key the parameter key (e.g. "backgroundThreshold")
     * @return true if the user has the checkbox ticked
     */
    public boolean isSelected(final String key) {
        for (final ParamRow row : tableModel.rows) {
            if (key.equals(row.key)) return row.selected;
        }
        return false;
    }


    private void initGUI() {
        final JPanel content = new JPanel(new BorderLayout());

        // Source statistics (read-only)
        content.add(createStatsPanel(), BorderLayout.NORTH);

        // Parameters table
        final JTable table = new JTable(tableModel);
        table.setRowHeight(table.getRowHeight() + 4);
        table.getTableHeader().setReorderingAllowed(false);

        // Checkbox column
        GuiUtils.JTables.configureCheckboxColumn(table, 0, "Include when applying");

        // Right-align the value column
        final DefaultTableCellRenderer rightRenderer = new DefaultTableCellRenderer();
        rightRenderer.setHorizontalAlignment(SwingConstants.RIGHT);
        table.getColumnModel().getColumn(2).setCellRenderer(rightRenderer);

        final JScrollPane scrollPane = GuiUtils.JTables.scrollPane(table);
        scrollPane.setPreferredSize(new Dimension(400, 180));
        content.add(scrollPane, BorderLayout.CENTER);

        // Buttons
        final JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        final JButton selectAll = new JButton("Select All");
        selectAll.addActionListener(e -> {
            tableModel.setAllSelected(true);
            tableModel.fireTableDataChanged();
        });
        final JButton selectNone = new JButton("Select None");
        selectNone.addActionListener(e -> {
            tableModel.setAllSelected(false);
            tableModel.fireTableDataChanged();
        });
        final JButton apply = new JButton("Apply & Close");
        apply.addActionListener(e -> {
            if (applyCallback != null) applyCallback.accept(config);
            dispose();
        });

        toolbar.add(selectAll);
        toolbar.add(selectNone);
        toolbar.addSeparator();
        toolbar.add(Box.createHorizontalGlue());
        toolbar.add(apply);
        content.add(toolbar, BorderLayout.SOUTH);

        setContentPane(content);
        pack();
        setLocationRelativeTo(getOwner());
    }

    private JPanel createStatsPanel() {
        final JPanel panel = new JPanel(new GridLayout(0, 2, 8, 2));
        panel.setBorder(BorderFactory.createTitledBorder("Source Statistics"));
        addStatRow(panel, "Paths analyzed", String.valueOf(config.getPathCount()));
        addStatRow(panel, "Total nodes", String.valueOf(config.getNodeCount()));
        if (!Double.isNaN(config.getMeanRadius()))
            addStatRow(panel, "Mean radius", fmt(config.getMeanRadius()));
        if (!Double.isNaN(config.getMeanIntensity()))
            addStatRow(panel, "Mean intensity", fmt(config.getMeanIntensity()));
        if (!Double.isNaN(config.getMeanContraction()))
            addStatRow(panel, "Mean contraction", fmt(config.getMeanContraction()));

        return panel;
    }

    private void addStatRow(final JPanel panel, final String label, final String value) {
        final JLabel lbl = new JLabel(label + ":");
        lbl.setFont(lbl.getFont().deriveFont(Font.PLAIN));
        panel.add(lbl);
        final JLabel val = new JLabel(value);
        val.setFont(val.getFont().deriveFont(Font.BOLD));
        panel.add(val);
    }

    private static List<ParamRow> buildRows(final AutoTraceConfig config) {
        // Order matches the GWDTTracerCmd prompt sections (III → IV → V)
        final List<ParamRow> rows = new ArrayList<>();
        // III. Thresholding
        if (!Double.isNaN(config.getBackgroundThreshold()))
            rows.add(new ParamRow("backgroundThreshold", "Background threshold",
                    fmt(config.getBackgroundThreshold()), true));
        // IV. Branch Filtering and Scoring
        if (config.getScoreMapScales() != null && config.getScoreMapScales().length > 0)
            rows.add(new ParamRow("scoreMapEnabled", "Score map filter",
                    "Enabled (scales: " + formatArray(config.getScoreMapScales()) + ")", true));
        if (!Double.isNaN(config.getMinBranchIntensityLength()))
            rows.add(new ParamRow("lengthThreshold", "Min. branch score",
                    fmt(config.getMinBranchIntensityLength()), true));
        // V. Post-processing
        if (!Double.isNaN(config.getBranchTuneMaxAngle()))
            rows.add(new ParamRow("branchTuneMaxAngle", "Max. branching angle",
                    fmt(config.getBranchTuneMaxAngle()) + "°", true));
        if (!Double.isNaN(config.getReconnectMinContraction()))
            rows.add(new ParamRow("reconnectMinContraction", "Reconnect min. contraction",
                    fmt(config.getReconnectMinContraction()), false));
        if (!Double.isNaN(config.getReconnectMaxAngleDeg()))
            rows.add(new ParamRow("reconnectMaxAngleDeg", "Reconnect max. angle",
                    fmt(config.getReconnectMaxAngleDeg()) + "°", false));
        if (!Double.isNaN(config.getReconnectMaxBridgeDist()))
            rows.add(new ParamRow("reconnectMaxBridgeDist", "Reconnect max. bridge distance",
                    fmt(config.getReconnectMaxBridgeDist()) + " voxels", false));
        return rows;
    }

    private static String fmt(final double v) {
        return SNTUtils.formatDouble(v, 2);
    }

    private static String formatArray(final double[] arr) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(fmt(arr[i]));
        }
        return sb.toString();
    }

    static class ParamRow {
        final String key;
        final String label;
        final String value;
        boolean selected;

        ParamRow(final String key, final String label, final String value, final boolean selected) {
            this.key = key;
            this.label = label;
            this.value = value;
            this.selected = selected;
        }
    }

    static class ParamTableModel extends AbstractTableModel {

        private static final String[] COLUMNS = {"", "Parameter", "Derived Value"};
        final List<ParamRow> rows;

        ParamTableModel(final List<ParamRow> rows) {
            this.rows = rows;
        }

        void setAllSelected(final boolean selected) {
            for (final ParamRow row : rows) row.selected = selected;
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(final int col) {
            return COLUMNS[col];
        }

        @Override
        public Class<?> getColumnClass(final int col) {
            return col == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(final int row, final int col) {
            return col == 0; // only checkbox is editable
        }

        @Override
        public Object getValueAt(final int row, final int col) {
            final ParamRow r = rows.get(row);
            return switch (col) {
                case 0 -> r.selected;
                case 1 -> r.label;
                case 2 -> r.value;
                default -> null;
            };
        }

        @Override
        public void setValueAt(final Object value, final int row, final int col) {
            if (col == 0 && value instanceof Boolean b) {
                rows.get(row).selected = b;
                fireTableCellUpdated(row, col);
            }
        }
    }
}
