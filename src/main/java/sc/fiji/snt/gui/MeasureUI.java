/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2021 Fiji developers.
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

package sc.fiji.snt.gui;

import net.imagej.ImageJ;
import org.apache.commons.math3.stat.descriptive.SummaryStatistics;
import org.scijava.display.DisplayService;
import org.scijava.plugin.Parameter;

import com.jidesoft.swing.CheckBoxList;

import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.SNTTable;
import sc.fiji.snt.analysis.TreeStatistics;

import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;

/**
 * @author Cameron Arshadi
 * @author Tiago Ferreira
 */
public class MeasureUI extends JFrame {

    @Parameter
    DisplayService displayService;

    static final String MIN = "Min";
    static final String MAX = "Max";
    static final String MEAN = "Mean";
    static final String STDDEV = "SD";
    static final String SUM = "Sum";

    static final String[] allFlags = new String[]{MIN, MAX, MEAN, STDDEV, SUM};
    static final Class<?>[] columnClasses = new Class<?>[]{String.class, Boolean.class, Boolean.class, Boolean.class,
            Boolean.class, Boolean.class};

    final Collection<Tree> trees;

    public MeasureUI(SNT plugin, Collection<Tree> trees) {
        super("SNT Measurements");
        plugin.getContext().inject(this);
        this.trees = trees;
        MeasurePanel panel = new MeasurePanel(trees, displayService);
        add(panel);
        pack();
        setLocationRelativeTo(null);
    }

    static class MeasurePanel extends JPanel {

        private final CheckBoxList metricList;

        MeasurePanel(Collection<Tree> trees, DisplayService displayService) {
            setLayout(new GridBagLayout());
            GridBagConstraints c = new GridBagConstraints();

            c.gridx = 0;
            c.gridy = 0;
            c.weighty = 1.0; // fill height when when resizing pane
            c.fill = GridBagConstraints.BOTH;
            DefaultListModel<String> listModel = new DefaultListModel<>();
            TreeStatistics.getAllMetrics().forEach(listModel::addElement);
            metricList = new CheckBoxList(listModel);
            metricList.setClickInCheckBoxOnly(false);
            metricList.setComponentPopupMenu(listPopupMenu());
            c.weightx = 0.0; // do not fill width when when resizing panel
            JScrollPane metricListScrollPane = new JScrollPane(metricList);
            add(metricListScrollPane, c);

            c.gridx = 1;
            c.gridy = 0;
            c.fill = GridBagConstraints.BOTH;
            JTable statsTable = new JTable(new DefaultTableModel() {

                private static final long serialVersionUID = 1L;

                @Override
                public Class<?> getColumnClass(int column) {
                    return columnClasses[column];
                }
            });
            statsTable.setComponentPopupMenu(new TablePopupMenu(statsTable));
            DefaultTableModel tableModel = (DefaultTableModel) statsTable.getModel();
            tableModel.addColumn("Metric");
            for (String metric : allFlags) {
                tableModel.addColumn(metric);
            }
            for (int i = 1; i < statsTable.getColumnCount(); ++i) {
                statsTable.getColumnModel().getColumn(i).setHeaderRenderer(new SelectAllHeader(statsTable, i, statsTable.getColumnName(i)));
            }
            // FIXME: this is inherently slow, but should be fast enough with a reasonable number of metrics
            metricList.getCheckBoxListSelectionModel().addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting()) {
                    final List<Object> selectedMetrics = new ArrayList<>(Arrays.asList(metricList.getCheckBoxListSelectedValues()));
                    List<Integer> metricIndicesToRemove = new ArrayList<>();
                    List<Object> existingMetrics = new ArrayList<>();
                    for (int i = 0; i < tableModel.getRowCount(); ++i) {
                        Object existingMetric = tableModel.getValueAt(i, 0);
                        if (!selectedMetrics.contains(existingMetric)) {
                            metricIndicesToRemove.add(i);
                        } else {
                            existingMetrics.add(existingMetric);
                        }
                    }
                    removeRows(tableModel, metricIndicesToRemove);
                    selectedMetrics.removeAll(existingMetrics);
                    for (final Object metric : selectedMetrics)
                        tableModel.addRow(new Object[]{metric, false, false, false, false, false});
                }
            });

            // Enlarge default width of first column. Another option would be to have all columns to auto-fit
            // at all times, e.g., https://stackoverflow.com/a/25570812. Maybe that would be better?
            statsTable.getColumnModel().getColumn(0).setMinWidth(SwingUtilities.
                    computeStringWidth(statsTable.getFontMetrics(statsTable.getFont()), "Average spine/varicosity"));
            statsTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);

            JScrollPane statsTableScrollPane = new JScrollPane(statsTable);
            c.weightx = 1.0; // fill width when when resizing panel
            add(statsTableScrollPane, c);

            c.gridx = 0;
            c.gridy = 1;
            c.gridwidth = 2;
            c.anchor = GridBagConstraints.CENTER;
            JPanel buttonPanel = new JPanel();
            JButton runButton = new JButton("Run");
            runButton.addActionListener(new GenerateTableAction(trees, tableModel, displayService));
            buttonPanel.add(runButton);
            c.weighty = 0.0; // do not allow panel to fill height when when resizing pane
            add(buttonPanel, c);
        }

        private void removeRows(DefaultTableModel model, List<Integer> indices) {
            Collections.sort(indices);
            for (int i = indices.size() - 1; i >= 0; i--) {
                model.removeRow(indices.get(i));
            }
        }

        private JPopupMenu listPopupMenu() {
            final JPopupMenu pMenu = new JPopupMenu();
            JMenuItem mi = new JMenuItem("Clear Selection");
            mi.addActionListener(e -> metricList.clearCheckBoxListSelection());
            pMenu.add(mi);
            mi = new JMenuItem("Select All");
            mi.addActionListener(e -> metricList.selectAll());
            pMenu.add(mi);
            return pMenu;
        }

    }

    static class GenerateTableAction extends AbstractAction {

        private Collection<Tree> trees;
        private DefaultTableModel tableModel;
        private DisplayService displayService;

        GenerateTableAction(Collection<Tree> trees, DefaultTableModel tableModel, DisplayService displayService) {
            super("Run", null);
            this.trees = trees;
            this.tableModel = tableModel;
            this.displayService = displayService;
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            SNTTable table = new SNTTable();
            for (Tree tree : trees) {
                table.insertRow(tree.getLabel());
                TreeStatistics tStats = new TreeStatistics(tree);
                for (int i = 0; i < tableModel.getRowCount(); ++i) {
                    String metric = (String) tableModel.getValueAt(i, 0);
                    SummaryStatistics summaryStatistics = tStats.getSummaryStats(metric);
                    for (int j = 1; j < tableModel.getColumnCount(); ++j) {
                        if (!(boolean) tableModel.getValueAt(i, j)) continue;
                        String measurement = tableModel.getColumnName(j);
                        final double value;
                        switch (measurement) {
                            case MIN:
                                value = summaryStatistics.getMin();
                                break;
                            case MAX:
                                value = summaryStatistics.getMax();
                                break;
                            case MEAN:
                                value = summaryStatistics.getMean();
                                break;
                            case STDDEV:
                                value = summaryStatistics.getStandardDeviation();
                                break;
                            case SUM:
                                value = summaryStatistics.getSum();
                                break;
                            default:
                                throw new IllegalArgumentException("Unknown statistic: " + measurement);
                        }
                        table.appendToLastRow(metric + " (" + measurement + ")", value);
                    }
                }
            }

            if (!table.isEmpty()) {
                displayService.createDisplay("SNT Measurements", table);
            }
        }
    }

    /**
     * A TableCellRenderer that selects all or none of a Boolean column.
     * <p>
     * Adapted from https://stackoverflow.com/a/7137801
     */
    static class SelectAllHeader extends JToggleButton implements TableCellRenderer {

        private static final String ALL_SELECTED = "✓ ";
        private final String label;
        private JTable table;
        private TableModel tableModel;
        private JTableHeader header;
        private TableColumnModel tcm;
        private int targetColumn;
        private int viewColumn;

        public SelectAllHeader(JTable table, int targetColumn, String label) {
            super(label);
            this.label = label;
            this.table = table;
            this.tableModel = table.getModel();
            if (tableModel.getColumnClass(targetColumn) != Boolean.class) {
                throw new IllegalArgumentException("Boolean column required.");
            }
            this.targetColumn = targetColumn;
            this.header = table.getTableHeader();
            this.tcm = table.getColumnModel();
            this.applyUI();
            this.addItemListener(new ItemHandler());
            header.addMouseListener(new MouseHandler());

            // FIXME: This does not appear to work when the table
            //  has multiple of these listeners. Multiple columns get
            //  selected.

            // tableModel.addTableModelListener(new ModelHandler());
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
                                                       int row, int column)
        {
            return this;
        }

        private class ItemHandler implements ItemListener {

            @Override
            public void itemStateChanged(ItemEvent e) {
                boolean state = e.getStateChange() == ItemEvent.SELECTED;
                setText((state) ? ALL_SELECTED + label : label);
                for (int r = 0; r < table.getRowCount(); r++) {
                    table.setValueAt(state, r, viewColumn);
                }
            }
        }

        @Override
        public void updateUI() {
            super.updateUI();
            applyUI();
        }

        private void applyUI() {
            this.setFont(UIManager.getFont("TableHeader.font"));
            this.setBorder(UIManager.getBorder("TableHeader.cellBorder"));
            this.setBackground(UIManager.getColor("TableHeader.background"));
            this.setForeground(UIManager.getColor("TableHeader.foreground"));
        }

        private class MouseHandler extends MouseAdapter {

            @Override
            public void mouseClicked(MouseEvent e) {
                viewColumn = header.columnAtPoint(e.getPoint());
                int modelColumn = tcm.getColumn(viewColumn).getModelIndex();
                if (modelColumn == targetColumn) {
                    doClick();
                }
            }
        }

//        private class ModelHandler implements TableModelListener {
//
//            @Override
//            public void tableChanged(TableModelEvent e) {
//                if (needsToggle()) {
//                    doClick();
//                    header.repaint();
//                }
//            }
//        }
//
//        // Return true if this toggle needs to match the model.
//        private boolean needsToggle() {
//            boolean allTrue = true;
//            boolean allFalse = true;
//            for (int r = 0; r < tableModel.getRowCount(); r++) {
//                boolean b = (Boolean) tableModel.getValueAt(r, targetColumn);
//                allTrue &= b;
//                allFalse &= !b;
//            }
//            return allTrue && !isSelected() || allFalse && isSelected();
//        }
    }

    static class TablePopupMenu extends JPopupMenu {
        // see https://stackoverflow.com/questions/16743427/

        private static final long serialVersionUID = -6423775304360422577L;
        private int rowAtClickPoint;
        private int columnAtClickPoint;
        private final JTable table;


        public TablePopupMenu(final JTable table) {
            super();
            this.table = table;
            addPopupMenuListener(new PopupMenuListener() {

                @Override
                public void popupMenuWillBecomeVisible(final PopupMenuEvent e) {
                    SwingUtilities.invokeLater(() -> {
                        final Point clickPoint = SwingUtilities.convertPoint(TablePopupMenu.this, new Point(0, 0), table);
                        rowAtClickPoint = table.rowAtPoint(clickPoint);
                        columnAtClickPoint = table.columnAtPoint(clickPoint);
                        for (final MenuElement element : getSubElements()) {
                            if (!(element instanceof JMenuItem)) continue;
                            if (((JMenuItem) element).getText().endsWith("Column")) {
                                ((JMenuItem)element).setEnabled(columnAtClickPoint > 0);
                            }
                            if (((JMenuItem) element).getText().endsWith("Row(s)")) {
                                ((JMenuItem)element).setEnabled(table.getSelectedRowCount() >1);
                            }
                        }
                    });
                }

                @Override
                public void popupMenuWillBecomeInvisible(final PopupMenuEvent e) {}

                @Override
                public void popupMenuCanceled(final PopupMenuEvent e) {}

            });
            JMenuItem mi = new JMenuItem("Enable All");
            mi.addActionListener(e -> setAllState(true));
            add(mi);
            mi = new JMenuItem("Enable This Column");
            mi.addActionListener(e -> setColumnState(true));
            add(mi);
//            mi = new JMenuItem("Enable This Row");
//            mi.addActionListener(e -> setRowState(true));
//            add(mi);
            mi = new JMenuItem("Enable Selected Row(s)");
            mi.addActionListener(e -> setSelectedRowsState(true));
            add(mi);
            addSeparator();
            mi = new JMenuItem("Disable All");
            mi.addActionListener(e -> setAllState(false));
            add(mi);
            mi = new JMenuItem("Disable This Column");
            mi.addActionListener(e -> setColumnState(false));
            add(mi);
//            mi = new JMenuItem("Disable This Row");
//            mi.addActionListener(e -> setRowState(false));
//            add(mi);
            mi = new JMenuItem("Disable Selected Row(s)");
            mi.addActionListener(e -> setSelectedRowsState(false));
            add(mi);
        }

        private void setColumnState(final boolean state) {
            if (columnAtClickPoint == 0)
                // This is the metric String column, we don't want to change this
                return;
            for (int i = 0; i < table.getRowCount(); i++) {
                table.setValueAt(state, i, columnAtClickPoint);
            }
        }

        @SuppressWarnings("unused")
        private void setRowState(final boolean state) {
            // Boolean columns start at idx == 1
            for (int i = 1; i < table.getColumnCount(); i++) {
                table.setValueAt(state, rowAtClickPoint, i);
            }
        }

        private void setAllState(final boolean state) {
            for (int row = 0; row < table.getRowCount(); row++) {
                for (int col = 1; col < table.getColumnCount(); col++) { // Skip metric String column
                    table.setValueAt(state, row, col);
                }
            }
        }

        private void setSelectedRowsState(final boolean state) {
            final int[] selectedIndices = table.getSelectedRows();
            for (int idx = 0; idx < selectedIndices.length; idx++) {
                for (int col = 1; col < table.getColumnCount(); col++) { // Skip metric String column
                    table.setValueAt(state, selectedIndices[idx], col);
                }
            }
        }

    }

    public static void main(String[] args) {
        ImageJ ij = new ImageJ();
        ij.ui().showUI();
        SNTService sntService = ij.get(SNTService.class);
        SNT plugin = sntService.initialize(true);
        MeasureUI frame = new MeasureUI(plugin, sntService.demoTrees());
        //Schedule a job for the event-dispatching thread:
        //creating and showing this application's GUI.
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                frame.setVisible(true);
            }
        });
    }

}