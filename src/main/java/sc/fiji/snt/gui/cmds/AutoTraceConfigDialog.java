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
import sc.fiji.snt.gui.*;
import sc.fiji.snt.tracing.auto.AutoTraceConfig;
import sc.fiji.snt.util.IOUtils;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Non-modal dialog displaying {@link AutoTraceConfig} results in a JTable, allowing the user to choose, for each
 * parameter, between keeping its current value and adopting the learned one. The dialog also accepts new example
 * paths once open (see {@link Learner}).
 * <p>
 * The dialog has three sections:
 * <ol>
 *   <li><b>Source Statistics</b> Read-only summary of what was measured from
 *       the example paths (mean radius, intensity, contraction, etc.)</li>
 *   <li><b>Parameters</b> Each row shows a parameter name, its current value (if known), its learned value, and a
 *       toggle button ("Current"/"Learned") with the choice to be applied when "Apply &amp; Close" is clicked. The
 *       value in effect is shown in bold. If current values are unknown, the choice is a checkbox. Rows the caller
 *       cannot apply are listed for reference only</li>
 *   <li><b>Recommendations &amp; Notes</b> Collapsible, advice-only suggestions and warnings</li>
 * </ol>
 *
 * @author Tiago Ferreira
 */
public class AutoTraceConfigDialog extends JDialog {

    /**
     * Source of learned configurations for dialogs that accept new example paths after being opened (dropped or
     * chosen reconstruction files, or the paths selected in a Path Manager). Implementations may work off the EDT
     * but must call the callbacks on it, exactly once per request
     */
    public interface Learner {
        /**
         * Derives a configuration from reconstruction files
         *
         * @param files  the reconstruction files (or directories of them)
         * @param onDone receives the derived configuration
         * @param onError receives a message describing why a configuration could not be derived
         */
        void learnFromFiles(List<File> files, Consumer<AutoTraceConfig> onDone, Consumer<String> onError);

        /**
         * Derives a configuration from the paths currently selected in the Path Manager
         *
         * @param onDone receives the derived configuration
         * @param onError receives a message describing why a configuration could not be derived
         */
        void learnFromSelection(Consumer<AutoTraceConfig> onDone, Consumer<String> onError);

        /**
         * Derives a configuration from all the paths listed in the Path Manager
         *
         * @param onDone receives the derived configuration
         * @param onError receives a message describing why a configuration could not be derived
         */
        void learnFromAll(Consumer<AutoTraceConfig> onDone, Consumer<String> onError);
    }

    private AutoTraceConfig config; // null until a configuration is available
    private final ParamTableModel tableModel;
    private final Map<String, String> currentValues;
    private final boolean hasCurrent;
    private final Learner learner;
    private Consumer<AutoTraceConfig> applyCallback;
    // Outer margin of the boxed sections is the holders' business, not Panels#section's
    private final JPanel statsHolder = marginHolder();
    private final JPanel notesHolder = marginHolder();
    private final JLabel statusLabel = new JLabel(" ");
    private JButton applyButton;
    private AbstractButton loadButton, chooseButton; // for the tour
    private JTable paramTable;
    private JScrollPane paramScroll;
    private boolean learning;
    private File lastDir;

    /**
     * Creates the dialog.
     *
     * @param owner  parent window (may be null)
     * @param config the derived configuration to display
     */
    public AutoTraceConfigDialog(final Window owner, final AutoTraceConfig config) {
        this(owner, config, null);
    }

    /**
     * Creates the dialog, listing the current value of the parameters next to the derived ones.
     *
     * @param owner         parent window (may be null)
     * @param config        the derived configuration to display
     * @param currentValues the current (formatted) value of each parameter the caller can apply, by parameter key (see
     *                      {@link #isSelected(String)}). Parameters not listed are shown for reference only and cannot
     *                      be selected. If null, all parameters are assumed to be applicable and no current values are
     *                      shown
     */
    public AutoTraceConfigDialog(final Window owner, final AutoTraceConfig config,
                                 final Map<String, String> currentValues) {
        this(owner, config, currentValues, null);
    }

    /**
     * Creates the dialog, which also accepts new example paths once open: reconstruction files dropped onto it or
     * chosen with its file chooser button, and the paths selected in the Path Manager.
     *
     * @param owner         parent window (may be null)
     * @param config        the derived configuration to display (may be null: the dialog is then empty until one
     *                      is derived by the learner)
     * @param currentValues as in {@link #AutoTraceConfigDialog(Window, AutoTraceConfig, Map)}
     * @param learner       derives configurations from new example paths (may be null: no new paths are accepted)
     */
    public AutoTraceConfigDialog(final Window owner, final AutoTraceConfig config,
                                 final Map<String, String> currentValues, final Learner learner) {
        super(owner, "Auto-Trace Configuration Wizard", ModalityType.MODELESS);
        this.config = config;
        this.currentValues = currentValues;
        this.hasCurrent = currentValues != null;
        this.learner = learner;
        this.tableModel = new ParamTableModel((config == null) ? new ArrayList<>() : buildRows(config, currentValues),
                hasCurrent);
        initGUI();
    }

    /**
     * Replaces the displayed configuration. To be called on the EDT
     *
     * @param newConfig the configuration to display
     */
    public void setConfig(final AutoTraceConfig newConfig) {
        this.config = newConfig;
        tableModel.setRows((newConfig == null) ? new ArrayList<>() : buildRows(newConfig, currentValues));
        refreshHolders();
        applyButton.setEnabled(newConfig != null);
        showMessage(null);
    }

    /**
     * Derives a configuration from the paths selected in the Path Manager, if a learner was specified. To be
     * called on the EDT
     */
    public void learnFromSelection() {
        if (learner == null || learning) return;
        setLearning(true);
        learner.learnFromSelection(this::learned, this::learnFailed);
    }

    private void learnFromAll() {
        if (learner == null || learning) return;
        setLearning(true);
        learner.learnFromAll(this::learned, this::learnFailed);
    }

    private void learnFromFiles(final List<File> files) {
        if (learner == null || learning) return;
        setLearning(true); // also blocks re-entry while the items are being checked
        new SwingWorker<List<File>, Object>() {

            @Override
            protected List<File> doInBackground() {
                final List<File> valid = new ArrayList<>();
                for (final File f : files) {
                    if (f != null && f.canRead() && (f.isDirectory() || IOUtils.isReconstructionFile(f, true) || IOUtils.isReconstructionZip(f)))
                        valid.add(f);
                }
                return valid;
            }

            @Override
            protected void done() {
                List<File> valid;
                try {
                    valid = get();
                } catch (final Exception ex) {
                    valid = List.of();
                }
                if (valid.isEmpty()) {
                    setLearning(false);
                    showMessage("None of the items are supported reconstruction files.");
                    return;
                }
                learner.learnFromFiles(valid, AutoTraceConfigDialog.this::learned, AutoTraceConfigDialog.this::learnFailed);
            }
        }.execute();
    }

    private void learned(final AutoTraceConfig newConfig) {
        if (!isDisplayable()) return; // closed while learning
        setLearning(false);
        setConfig(newConfig);
        toFront(); // the prompt may have been clicked while learning
    }

    private void learnFailed(final String message) {
        if (!isDisplayable()) return;
        setLearning(false);
        showMessage(message);
    }

    private void setLearning(final boolean learning) {
        this.learning = learning;
        setCursor(Cursor.getPredefinedCursor(learning ? Cursor.WAIT_CURSOR : Cursor.DEFAULT_CURSOR));
        if (learning) showMessage("Learning from example paths...");
    }

    // Message line (null clears it)
    private void showMessage(final String message) {
        statusLabel.setText((message == null || message.isEmpty()) ? " " : message);
        statusLabel.setForeground((message != null && message.startsWith("Learning")) ? UIManager.getColor("Label.foreground")
                : GuiUtils.Colors.errorColor());
    }

    private void refreshHolders() {
        statsHolder.removeAll();
        statsHolder.add(createStatsPanel(), BorderLayout.CENTER);
        notesHolder.removeAll();
        notesHolder.add(createNotesPanel(), BorderLayout.CENTER);
        autoSizeColumns();
        pack();
    }

    /**
     * Sets the callback invoked when "Apply & Close" is clicked. The callback
     * receives the same {@link AutoTraceConfig}: the caller should inspect
     * {@link #isSelected(String)} to decide which fields to apply.
     *
     * @param callback the apply action
     */
    public void setApplyCallback(final Consumer<AutoTraceConfig> callback) {
        this.applyCallback = callback;
    }

    /**
     * Whether the user has chosen to adopt the learned value of the given parameter (as opposed to keeping the
     * current one).
     *
     * @param key the parameter key (e.g. "backgroundThreshold")
     * @return true if the learned value is to be adopted
     */
    public boolean isSelected(final String key) {
        for (final ParamRow row : tableModel.rows) {
            if (key.equals(row.key)) return row.selected;
        }
        return false;
    }


    private void initGUI() {
        final JPanel content = new JPanel(new BorderLayout());

        statusLabel.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));

        // Parameters table. Tooltips explain how each value was derived
        paramTable = GuiUtils.Tables.tableWithPlaceholder(tableModel,
                () -> (learner == null) ? "No parameters" : "Drop reconstruction files here",
                () -> (learner == null) ? null : "or use the Learn From Existing Data button below");
        paramTable.setFillsViewportHeight(true); // so that the placeholder is painted when empty
        paramTable.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override
            public void mouseMoved(final java.awt.event.MouseEvent e) {
                final int row = paramTable.rowAtPoint(e.getPoint());
                if (row < 0) {
                    paramTable.setToolTipText(null);
                    paramTable.setCursor(Cursor.getDefaultCursor());
                    return;
                }
                final ParamRow r = tableModel.rows.get(paramTable.convertRowIndexToModel(row));
                if (isChoiceCell(paramTable.columnAtPoint(e.getPoint()))) {
                    paramTable.setToolTipText(r.applicable
                            ? "<html>Click to switch between keeping the current value and adopting the learned one"
                            : r.tip);
                    paramTable.setCursor(Cursor.getPredefinedCursor(r.applicable ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                } else {
                    paramTable.setToolTipText(r.tip);
                    paramTable.setCursor(Cursor.getDefaultCursor());
                }
            }
        });
        // A click on the "Use" button flips the choice of the row. So does a double-click anywhere else on the row (on
        // the button itself only the first click counts, as two flips would cancel each other; the checkbox shown
        // if current values are unknown has its own editor)
        paramTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(final java.awt.event.MouseEvent e) {
                final int row = paramTable.rowAtPoint(e.getPoint());
                if (row < 0 || !SwingUtilities.isLeftMouseButton(e)) return;
                final int viewCol = paramTable.columnAtPoint(e.getPoint());
                final boolean useColumn = viewCol >= 0
                        && paramTable.convertColumnIndexToModel(viewCol) == ParamTableModel.COL_USE;
                if (useColumn && !hasCurrent) return;
                if (useColumn ? e.getClickCount() != 1 : e.getClickCount() != 2) return;
                final int modelRow = paramTable.convertRowIndexToModel(row);
                final ParamRow r = tableModel.rows.get(modelRow);
                if (!r.applicable) return;
                r.selected = !r.selected;
                tableModel.fireTableRowsUpdated(modelRow, modelRow);
            }
        });
        paramTable.setRowHeight(paramTable.getRowHeight() + 4);
        paramTable.setRowSelectionAllowed(false);
        paramTable.getTableHeader().setReorderingAllowed(false);

        // Values: Parameter left-aligned, values right-aligned. If current values are known, the value that will be in
        // effect (current or learned) is in bold and the other one grayed out. Rows that cannot be applied are grayed
        // out entirely
        final DefaultTableCellRenderer renderer = new DefaultTableCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getTableCellRendererComponent(final JTable t, final Object value, final boolean isSel,
                                                           final boolean hasFocus, final int row, final int col) {
                super.getTableCellRendererComponent(t, value, false, false, row, col);
                final ParamRow r = tableModel.rows.get(t.convertRowIndexToModel(row));
                final int modelCol = t.convertColumnIndexToModel(col);
                setHorizontalAlignment((modelCol == 0) ? SwingConstants.LEFT
                        : (modelCol == ParamTableModel.COL_USE) ? SwingConstants.CENTER : SwingConstants.RIGHT);
                boolean inEffect = false;
                if (hasCurrent && r.applicable) {
                    inEffect = (modelCol == ParamTableModel.COL_CURRENT && !r.selected)
                            || (modelCol == ParamTableModel.COL_DERIVED && r.selected)
                            || modelCol == ParamTableModel.COL_USE;
                }
                setFont(t.getFont().deriveFont(inEffect && modelCol != ParamTableModel.COL_USE ? Font.BOLD : Font.PLAIN));
                // NB: setForeground() is sticky in DefaultTableCellRenderer: always set it explicitly
                final boolean gray = !r.applicable || (hasCurrent && modelCol != 0 && !inEffect);
                setForeground(gray ? GuiUtils.Colors.disabledComponentColor() : t.getForeground());
                return this;
            }
        };
        final TableCellRenderer choiceRenderer = new ChoiceRenderer();
        for (int col = 0; col < tableModel.getColumnCount(); col++) {
            if (col == ParamTableModel.COL_USE && !hasCurrent) continue; // default checkbox renderer
            paramTable.getColumnModel().getColumn(col)
                    .setCellRenderer((col == ParamTableModel.COL_USE) ? choiceRenderer : renderer);
        }
        if (!hasCurrent) paramTable.removeColumn(paramTable.getColumnModel().getColumn(ParamTableModel.COL_CURRENT));
        if (!hasCurrent) GuiUtils.Tables.configureCheckboxColumn(paramTable, paramTable.getColumnCount() - 1, "Adopt the learned value");
        paramTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);

        final JScrollPane scrollPane = GuiUtils.Tables.scrollPane(paramTable);
        paramScroll = scrollPane;
        autoSizeColumns();
        final JPanel tableHolder = marginHolder(); // same margin as the boxed sections above and below
        tableHolder.add(scrollPane, BorderLayout.CENTER);
        content.add(tableHolder, BorderLayout.CENTER);

        // Buttons
        final JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.setLayout(new GridLayout(1, (learner == null) ? 2 : 3));

        final JButton apply = new JButton("Apply & Close", IconFactory.buttonIcon('\uf2f6', true, null));
        apply.setDisabledIcon(IconFactory.buttonIcon('\uf2f6', true, IconFactory.disabledColor()));
        apply.setEnabled(config != null);
        applyButton = apply;
        apply.addActionListener(e -> {
            if (config != null && applyCallback != null) applyCallback.accept(config);
            dispose();
        });

        if (learner != null) {
            loadButton = loadOptionsButton();
            toolbar.add(loadButton);
        }
        chooseButton = selectOptionsButton();
        toolbar.add(chooseButton);
        toolbar.add(apply);
        final JButton help = GuiUtils.Buttons.help(null);
        help.setToolTipText("Tour of controls");
        help.addActionListener(e -> startTour());
        final JPanel helpHolder = new JPanel(new GridBagLayout()); // keeps the button vertically centered
        helpHolder.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 6));
        helpHolder.add(help);
        final JPanel buttonRow = new JPanel(new BorderLayout());
        buttonRow.add(toolbar, BorderLayout.CENTER);
        buttonRow.add(helpHolder, BorderLayout.EAST);
        final JPanel south = new JPanel(new BorderLayout());
        south.add(buttonRow, BorderLayout.NORTH);
        south.add(statusLabel, BorderLayout.CENTER); // message line, right below the toolbar
        // Collapsible sections (source statistics, then notes), right below the message line
        final JPanel sections = new JPanel();
        sections.setLayout(new BoxLayout(sections, BoxLayout.Y_AXIS));
        sections.add(statsHolder);
        sections.add(notesHolder);
        south.add(sections, BorderLayout.SOUTH);
        content.add(south, BorderLayout.SOUTH);
        setContentPane(content);
        statsHolder.add(createStatsPanel(), BorderLayout.CENTER);
        notesHolder.add(createNotesPanel(), BorderLayout.CENTER);
        pack();
        setLocationRelativeTo(getOwner());
        // Installed last, so that all components are covered. NB: components added later do not define drop targets
        if (learner != null)
            new FileDrop(content, files -> SwingUtilities.invokeLater(() -> learnFromFiles(Arrays.asList(files))));
    }

    // Starts the tour of controls, or moves to its next callout if it is already running
    private void startTour() {
        final String group = CalloutManager.groupFor(this);
        if (!CalloutManager.isActive(group)) {
            // (re)registered every time: the window may have been resized or rebuilt since last time
            CalloutManager.clearRegistrations(group);
            registerCallouts(group);
        }
        CalloutManager.showAllOrAdvance(group);
    }

    private void registerCallouts(final String group) {
        int order = 0;
        CalloutManager.add(paramScroll, CalloutManager.AUTO, "<b>Parameters</b><br>"
                + "Tracing settings, with the learned values. Hover a row to see how its parameter<br>was derived. "
                + ((learner == null) ? "" : "Reconstruction files can be dropped anywhere onto this window."),
                group, order++);
        if (hasCurrent) {
            final int col = paramTable.convertColumnIndexToView(ParamTableModel.COL_USE);
            final javax.swing.table.JTableHeader header = paramTable.getTableHeader();
            if (col >= 0 && header.isShowing()) {
                CalloutManager.add(header, header.getHeaderRect(col), CalloutManager.AUTO, "<b>Use</b><br>"
                        + "Chooses between the <i>Current</i> and <i>Learned</i> value of a parameter. Pressed "
                        + "buttons<br>mark the values that will be adopted.", group, order++);
            }
        }
        if (loadButton != null) {
            CalloutManager.add(loadButton, CalloutManager.AUTO, "<b>Learn From Existing Data</b><br>"
                    + "Learns from the paths selected in Path Manager, all of its paths, the image itself,<br>"
                    + "or imported reconstruction files.<br>Such actions replace the current examples.", group, order++);
        }
        CalloutManager.add(chooseButton, CalloutManager.AUTO, "<b>Choose</b><br>"
                + "Selects which parameters will be adopted: all, only the defaults, or none.", group, order++);
        CalloutManager.add(statsHolder, CalloutManager.AUTO, "<b>Source Statistics</b><br>"
                + "Summary of the examples the values were learned from.", group, order++);
        CalloutManager.add(notesHolder, CalloutManager.AUTO, "<b>Recommendations &amp; Notes</b><br>"
                + "Warnings and advice that are not part of the learned values.", group, order++);
        CalloutManager.add(applyButton, CalloutManager.AUTO, "<b>Apply &amp; Close</b><br>"
                + "Transfers the chosen values to the command prompt and closes this window.", group, order); // last in the sequence
    }

    // Renders the choice of a row as a toggle button (pressed: the learned value is adopted). Clicks are handled by
    // the table (see mouse listener), the button is only a visual cue that the cell is clickable
    private class ChoiceRenderer implements TableCellRenderer {
        private final JPanel panel = new JPanel(new BorderLayout());
        private final JToggleButton button = new JToggleButton();

        ChoiceRenderer() {
            button.setFocusable(false);
            GuiUtils.Buttons.makeRoundRect(button);
            panel.add(button, BorderLayout.CENTER);
            panel.setBorder(BorderFactory.createEmptyBorder(1, 4, 1, 4));
        }

        @Override
        public Component getTableCellRendererComponent(final JTable t, final Object value, final boolean isSel,
                                                       final boolean hasFocus, final int row, final int col) {
            final ParamRow r = tableModel.rows.get(t.convertRowIndexToModel(row));
            button.setText(String.valueOf(value));
            button.setSelected(r.applicable && r.selected);
            button.setEnabled(r.applicable);
            panel.setBackground(t.getBackground());
            return panel;
        }
    }

    // Whether a column of the view is the one in which the user chooses between current and learned values
    private boolean isChoiceCell(final int viewCol) {
        return hasCurrent && viewCol >= 0
                && paramTable.convertColumnIndexToModel(viewCol) == ParamTableModel.COL_USE;
    }

    private void autoSizeColumns() {
        final int n = paramTable.getColumnCount();
        for (int col = 0; col < n; col++) {
            if (!hasCurrent && col == n - 1) continue; // checkbox column has a fixed width
            GuiUtils.Tables.autoSizeColumn(paramTable, col, 6);
        }
        // Size the viewport to the columns (and rows), otherwise they would be squeezed into the size it had when
        // the table was empty
        int width = 0;
        for (int col = 0; col < n; col++) width += paramTable.getColumnModel().getColumn(col).getPreferredWidth();
        final int rows = Math.min(12, Math.max(3, paramTable.getRowCount()));
        final int headerHeight = paramTable.getTableHeader().getPreferredSize().height;
        paramScroll.setPreferredSize(new Dimension(Math.max(400, width + 24),
                rows * paramTable.getRowHeight() + headerHeight + 4));
    }

    private AbstractButton loadOptionsButton() {
        final JMenuItem load = new JMenuItem("Learn From Reconstruction File(s)...");
        load.setToolTipText("<html>Learn from a reconstruction file (SWC, TRACES, JSON, etc.).<br>"
                + "Files can also be dropped onto this window. Replaces the current examples");
        load.addActionListener(e -> {
            final File f = new GuiUtils(this).getOpenFile("Choose Reconstruction File", lastDir,
                    IOUtils.getReconstructionExtensions(true));
            if (f != null) {
                lastDir = f.getParentFile();
                learnFromFiles(List.of(f));
            }
        });
        final JMenuItem fromSelection = new JMenuItem("Learn From Selected Paths");
        fromSelection.setToolTipText("<html>Learn from the paths currently selected in Path Manager.<br>"
                + "Replaces the current examples");
        fromSelection.addActionListener(e -> learnFromSelection());
        final JMenuItem fromAll = new JMenuItem("Learn From All Paths");
        fromAll.setToolTipText("<html>Learn from all the paths listed in Path Manager.<br>"
                + "Replaces the current examples. Only recommended if all of them are representative");
        fromAll.addActionListener(e -> learnFromAll());
        final JPopupMenu menu = new JPopupMenu();
        GuiUtils.MenuItems.addSeparator(menu, "External Traces:");
        menu.add(load);
        GuiUtils.MenuItems.addSeparator(menu, "Paths Loaded in Path Manager:");
        menu.add(fromAll);
        menu.add(fromSelection);
        return GuiUtils.Buttons.OptionsButton("Learn From Existing Data", IconFactory.GLYPH.PERSON_CHALKBOARD, null, 1f, menu);
    }

    private AbstractButton selectOptionsButton() {
        final JMenuItem selectAll = new JMenuItem("Adopt All");
        selectAll.addActionListener(e -> {
            if (isValidTable()) {
                tableModel.setAllSelected(true);
                tableModel.fireTableDataChanged();
            }
        });
        final JMenuItem selectDefaults = new JMenuItem("Adopt Recommended");
        selectDefaults.setToolTipText("<html>Restore the initial choice: adopt confident parameters only,<br>"
                + "keep the current value of heuristic ones");
        selectDefaults.addActionListener(e -> {
            if (isValidTable()) {
                tableModel.selectDefaults();
                tableModel.fireTableDataChanged();
            }
        });
        final JMenuItem selectNone = new JMenuItem("Reject All");
        selectNone.addActionListener(e -> {
            if (isValidTable()) {
                tableModel.setAllSelected(false);
                tableModel.fireTableDataChanged();
            }
        });
        final JPopupMenu menu = new JPopupMenu();
        GuiUtils.MenuItems.addSeparator(menu, "Learned Parameters:");
        menu.add(selectAll);
        menu.add(selectDefaults);
        menu.addSeparator();
        menu.add(selectNone);
        return GuiUtils.Buttons.OptionsButton("Choose", IconFactory.GLYPH.CHECK_DOUBLE, null, 1f, menu);
    }

    private boolean isValidTable() {
        final boolean empty = tableModel.getRowCount() == 0;
        if (empty) showMessage("Nothing has been learned yet.");
        return !empty;
    }

    // Collapsible "Recommendations & Notes" section: advice that is not part of the learned values. Expanded only if
    // there is something to warn about
    private JComponent createNotesPanel() {
        if (config == null) return placeholderSection("Recommendations & Notes",
                "Recommendations will be listed once parameters are learned.");
        final List<String> recs = new ArrayList<>(config.getRecommendations());
        final String tipExt = currentValue("reconnectMaxBridgeDist");
        if (tipExt != null && tipExt.startsWith("0 ") && !Double.isNaN(config.getReconnectMaxBridgeDist())) {
            recs.add("Tip extension is off (experimental). If traces break across small gaps, try the learned "
                    + "tip extension distance.");
        }
        final List<String> warns = config.getWarnings();
        if (recs.isEmpty() && warns.isEmpty())
            return placeholderSection("Recommendations & Notes", "Nothing to report.");
        final boolean headingsNeeded = !warns.isEmpty() && !recs.isEmpty();
        final StringBuilder sb = new StringBuilder("<html><body>");
        sb.append("<i>These are suggestions based on the image and the example paths, not learned values. ")
                .append("These suggestions are not applied automatically.</i>");
        appendList(sb, "Warnings", warns, headingsNeeded);
        appendList(sb, "Recommendations", recs, headingsNeeded);
        sb.append("</body></html>");
        // NB: a JLabel would not wrap its HTML to the width of the dialog
        final JEditorPane pane = GuiUtils.Panels.WrappingHtmlPane(sb.toString(),
                () -> (paramScroll == null) ? 400 : paramScroll.getPreferredSize().width);
        pane.setBorder(BorderFactory.createEmptyBorder(2, 8, 8, 8));
        // Text components have their own drop target, which would reject files dropped onto the notes
        pane.setTransferHandler(null);
        pane.setDropTarget(null);
        return  GuiUtils.Panels.section(null,
                GuiUtils.Panels.collapsibleSection("Recommendations & Notes", pane, !warns.isEmpty()));
    }

    // Collapsed section with a placeholder message, so that sections are always present (and part of the tour)
    private static JComponent placeholderSection(final String title, final String text) {
        final JLabel label = new JLabel(text);
        label.setEnabled(false);
        label.setBorder(BorderFactory.createEmptyBorder(2, 8, 8, 8));
        return GuiUtils.Panels.section(null, GuiUtils.Panels.collapsibleSection(title, label, false));
    }

    private static JPanel marginHolder() {
        final JPanel holder = new JPanel(new BorderLayout());
        holder.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        return holder;
    }

    private static void appendList(final StringBuilder sb, final String heading, final List<String> items,
                                   final boolean showHeading) {
        if (items.isEmpty()) return;
        if (showHeading) sb.append("<p><b>").append(heading).append("</b>");
        sb.append("<ul style='margin-top:0;margin-bottom:0'>");
        for (final String item : items) sb.append("<li>").append(item).append("</li>");
        sb.append("</ul>");
    }

    private String currentValue(final String key) {
        for (final ParamRow row : tableModel.rows) {
            if (key.equals(row.key)) return row.currentValue;
        }
        return null;
    }

    // Collapsible "Source Statistics" section (read-only). Collapsed by default: it is reference information
    private JComponent createStatsPanel() {
        if (config == null) return placeholderSection("Source Statistics",
                "Statistics of the examples will be listed once parameters are learned.");
        final JPanel panel = new JPanel(new GridLayout(0, 2, 8, 2));
        addStatRow(panel, "Paths analyzed", String.valueOf(config.getPathCount()));
        addStatRow(panel, "Total nodes", String.valueOf(config.getNodeCount()));
        if (!Double.isNaN(config.getMeanRadius()))
            addStatRow(panel, "Mean radius", fmt(config.getMeanRadius()));
        if (!Double.isNaN(config.getMeanIntensity()))
            addStatRow(panel, "Mean intensity", fmt(config.getMeanIntensity()));
        if (!Double.isNaN(config.getMeanContraction()))
            addStatRow(panel, "Mean contraction", fmt(config.getMeanContraction()));
        return GuiUtils.Panels.section(null, GuiUtils.Panels.collapsibleSection("Source Statistics", panel, false));
    }

    private void addStatRow(final JPanel panel, final String label, final String value) {
        final JLabel lbl = new JLabel(label + ":");
        lbl.setFont(lbl.getFont().deriveFont(Font.PLAIN));
        panel.add(lbl);
        final JLabel val = new JLabel(value);
        val.setFont(val.getFont().deriveFont(Font.BOLD));
        panel.add(val);
    }

    private static final String NOT_APPLIED = " This value is not used by this command (shown for reference only).";

    private static List<ParamRow> buildRows(final AutoTraceConfig config, final Map<String, String> current) {
        // Order matches the GWDTTracerCmd prompt sections (III -> IV -> V)
        final List<ParamRow> rows = new ArrayList<>();
        // III. Thresholding
        if (!Double.isNaN(config.getBackgroundThreshold()))
            rows.add(new ParamRow("backgroundThreshold", "Background threshold",
                    fmt(config.getBackgroundThreshold()), true, current,
                    "25% of the mean intensity along the example paths: Paths run through signal, so the "
                            + "background should be well below it."));
        // IV. Branch Filtering and Scoring
        if (config.getScoreMapScales() != null && config.getScoreMapScales().length > 0)
            rows.add(new ParamRow("scoreMapEnabled", "Score map filter",
                    "Enabled (scales: " + formatArray(config.getScoreMapScales()) + ")", true, current,
                    "Scales spanning the radii of the example paths. Only the filter type is applied: scales are "
                            + "handled by the tracer."));
        if (!Double.isNaN(config.getMinBranchIntensityLength()))
            rows.add(new ParamRow("lengthThreshold", "Min. branch score",
                    fmt(config.getMinBranchIntensityLength()), true, current,
                    "Radius of the thinnest example structures (10th percentile, in voxels and at least 3): "
                            + "Shorter branches are likely noise."));
        if (!Double.isNaN(config.getSphereOverlapThreshold()))
            rows.add(new ParamRow("sphereOverlapThreshold", "Overlap tolerance",
                    fmt(config.getSphereOverlapThreshold()), false, current,
                    "90th percentile of the sphere overlap observed between distinct example paths (between "
                            + "0.05 and 0.5): Overlap this large occurs in real structures and should not be pruned."));
        // V. Post-processing
        if (!Double.isNaN(config.getBranchTuneMaxAngle()))
            rows.add(new ParamRow("branchTuneMaxAngle", "Max. branching angle",
                    fmt(config.getBranchTuneMaxAngle()) + "°", true, current,
                    "90th percentile of the fork angles observed in the example paths."));
        if (!Double.isNaN(config.getReconnectMinContraction()))
            rows.add(new ParamRow("reconnectMinContraction", "Reconnect min. contraction",
                    fmt(config.getReconnectMinContraction()), false, current,
                    "80% of the lowest contraction of the example paths (at least 0.3): Bridges between segments "
                            + "should be at least this straight."));
        if (!Double.isNaN(config.getReconnectMaxBridgeDist()))
            rows.add(new ParamRow("reconnectMaxBridgeDist", "Tip extension distance",
                    fmt(config.getReconnectMaxBridgeDist()) + " voxels", false, current,
                    "Four times the largest radius of the example paths, in voxels (at least 10): "
                            + "The largest gap expected between segments. Sets the tip "
                            + "extension distance (enables tip extension)."));
        // VI. Smoothing & Resampling
        if (!Double.isNaN(config.getResampleStep()))
            rows.add(new ParamRow("resampleStep", "Resample interval",
                    fmt(config.getResampleStep()) + " voxels", true, current,
                    "Median distance between consecutive nodes of the example paths, in voxels (1 to 10): "
                            + "Output matches the node density of the examples."));
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
        final String currentValue; // null if unknown
        final boolean applicable;
        final String tip;
        final boolean selectedByDefault;
        boolean selected;

        ParamRow(final String key, final String label, final String value, final boolean selectedByDefault,
                 final Map<String, String> current, final String tip) {
            this.key = key;
            this.label = label;
            this.value = value;
            this.applicable = current == null || current.containsKey(key);
            this.currentValue = (current == null) ? null : current.get(key);
            this.selectedByDefault = selectedByDefault;
            this.selected = applicable && selectedByDefault;
            this.tip = "<html><div width=300>" + tip + ((applicable) ? "" : NOT_APPLIED);
        }

        // Whether applying this row would change the current value
        boolean changed() {
            return applicable && currentValue != null && !currentValue.equals(value);
        }
    }

    static class ParamTableModel extends AbstractTableModel {

        static final int COL_PARAM = 0, COL_CURRENT = 1, COL_DERIVED = 2, COL_USE = 3;
        private static final String[] COLUMNS = {"Parameter", "Current", "Learned", "Use"};
        List<ParamRow> rows;
        private final boolean hasCurrent;

        // hasCurrent: whether current values are known. If so, the last column holds the choice between keeping them
        // and adopting the learned ones (text, switched by clicking on it). Otherwise it is a checkbox
        ParamTableModel(final List<ParamRow> rows, final boolean hasCurrent) {
            this.rows = rows;
            this.hasCurrent = hasCurrent;
        }

        void setRows(final List<ParamRow> rows) {
            this.rows = rows;
            fireTableDataChanged();
        }

        void selectDefaults() {
            for (final ParamRow row : rows) row.selected = row.applicable && row.selectedByDefault;
        }

        void setAllSelected(final boolean selected) {
            for (final ParamRow row : rows) row.selected = selected && row.applicable;
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
            return (col == COL_USE && !hasCurrent) ? "Adopt" : COLUMNS[col];
        }

        @Override
        public Class<?> getColumnClass(final int col) {
            return (col == COL_USE && !hasCurrent) ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(final int row, final int col) {
            // only the checkbox of applicable rows is editable. Choices are switched by clicking on them
            return col == COL_USE && !hasCurrent && rows.get(row).applicable;
        }

        @Override
        public Object getValueAt(final int row, final int col) {
            final ParamRow r = rows.get(row);
            return switch (col) {
                case COL_PARAM -> r.label;
                case COL_CURRENT -> (r.currentValue == null) ? "" : r.currentValue;
                case COL_DERIVED -> r.value;
                case COL_USE -> hasCurrent ? (!r.applicable ? "n/a" : r.selected ? "Learned" : "Current") : r.selected;
                default -> null;
            };
        }

        @Override
        public void setValueAt(final Object value, final int row, final int col) {
            if (col == COL_USE && !hasCurrent && value instanceof Boolean b) {
                rows.get(row).selected = b;
                fireTableCellUpdated(row, col);
            }
        }
    }
}
