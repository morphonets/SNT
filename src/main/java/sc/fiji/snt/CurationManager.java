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

package sc.fiji.snt;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.extras.components.FlatTriStateCheckBox;
import ij.ImagePlus;
import sc.fiji.snt.analysis.curation.CurationHistograms;
import sc.fiji.snt.analysis.curation.CurationTags;
import sc.fiji.snt.analysis.curation.PlausibilityCalibrator;
import sc.fiji.snt.analysis.curation.PlausibilityCheck;
import sc.fiji.snt.analysis.curation.PlausibilityMonitor;
import sc.fiji.snt.gui.FileChooser;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.IconFactory;
import sc.fiji.snt.gui.SNTCommandFinder;
import sc.fiji.snt.util.ImpUtils;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.util.*;
import java.util.List;


/**
 * Implements the <i>Curation Assistant</i> pane in SNT's UI. Provides
 * morphological plausibility feedback during tracing and editing operations.
 * <p>
 * The panel is organized into:
 * <ul>
 *   <li><b>Live Monitoring Parameters</b> configures checks that run inline
 *       during interactive tracing (fork, segment, finalization hooks).</li>
 *   <li><b>On-demand Monitoring Parameters</b> configures heavier checks
 *       that scan the full reconstruction.</li>
 *   <li><b>Action buttons</b> toggle live monitoring on/off, or trigger a
 *       full on-demand scan (which runs <i>both</i> parameter sets across
 *       all paths).</li>
 *   <li><b>Warnings table</b> displays current issues; double-click to
 *       navigate to the issue location.</li>
 * </ul>
 *
 * @author Tiago Ferreira
 * @see PlausibilityMonitor
 * @see PlausibilityCheck
 */
public class CurationManager implements PlausibilityMonitor.WarningListener {

    // Severity column: colored circle icons (Red/Yellow/Blue) matching BookmarkManager style
    private static final Color SEVERITY_ERROR = new Color(255, 101, 101); // Red
    private static final Color SEVERITY_WARNING = new Color(255, 214, 84); // Yellow
    private static final Color SEVERITY_INFO = new Color(77, 160, 255); // Blue

    static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

    private final SNTUI sntui;
    private final PlausibilityMonitor monitor;
    private final WarningTableModel tableModel;
    private final JTable warningsTable;
    private JPanel panel;
    // Section header tri-state checkboxes (select all / none / mixed)
    private FlatTriStateCheckBox liveHeaderCheckbox;
    private FlatTriStateCheckBox onDemandHeaderCheckbox;
    // Parameter checkboxes (instance fields for sync from .curation presets)
    private JCheckBox branchAngleMinCheckbox;
    private JCheckBox branchAngleMaxCheckbox;
    private JCheckBox directionCheckbox;
    private JCheckBox radiusCheckbox;
    private JCheckBox termBranchCheckbox;
    private JCheckBox constantRadiiCheckbox;
    private JCheckBox tortuosityCheckbox;
    private JCheckBox overlapCheckbox;
    private JCheckBox radiusJumpsCheckbox;
    private JCheckBox radiusMonoCheckbox;
    private JCheckBox invalidRadiusCheckbox;
    private JCheckBox uncertainTerminalCheckbox;
    private JCheckBox intensityValleyCheckbox;
    private JCheckBox signalQualityCheckbox;
    // Checkbox groups for section-level toggling
    private List<JCheckBox> liveCheckboxes;
    private List<JCheckBox> onDemandCheckboxes;
    // Parameter spinners
    private JSpinner radiusSpinner;
    private JSpinner directionSpinner;
    private JSpinner branchAngleMinSpinner;
    private JSpinner branchAngleMaxSpinner;
    private JSpinner termBranchSpinner;
    private JSpinner interForkSpinner;
    private JSpinner overlapSpinner;
    private JSpinner bundledPathsSpinner;
    private JSpinner terminalNearAncestorSpinner;
    private JSpinner radiusJumpsSpinner;
    private JSpinner radiusMonoSpinner;
    private JSpinner zExtentSpinner;
    private JSpinner uncertainTerminalSpinner;
    private JSpinner intensityValleySpinner;
    private JSpinner tortuositySpinner;
    private JSpinner signalQualitySpinner;
    // Width of the undo button, used as a spacer for other spinner rows
    private static final int undoButtonWidth = GuiUtils.Buttons.undo().getPreferredSize().width;
    // Action controls
    private JToggleButton liveToggle;
    private JButton onDemandButton;
    // Navigation
    /** Preferred zoom level applied when navigating to a flagged issue. */
    private final GuiUtils.VisitingZoom visitingZoom = new GuiUtils.VisitingZoom();
    // Menus
    private JPopupMenu calibrationMenu;
    // Detachable table (state is owned by the helper)
    private JScrollPane tableScroll;
    private GuiUtils.JTables.DetachableTable tableDetacher;


    public CurationManager(final SNTUI sntui, final PlausibilityMonitor monitor) {
        this.sntui = sntui;
        this.monitor = monitor;
        this.tableModel = new WarningTableModel();
        this.warningsTable = GuiUtils.JTables.tableWithPlaceholder(tableModel,
                tableModel::placeholderLine1, tableModel::placeholderLine2);
        monitor.addWarningListener(this);
        configureTable();
        visitingZoom.resetFor(sntui != null ? sntui.plugin.getImagePlus() : null);
    }

    /**
     * Unregisters this panel from the monitor. Should be called when the
     * panel is removed from the UI to avoid listener leaks.
     */
    public void dispose() {
        monitor.removeWarningListener(this);
        if (tableDetacher != null && tableDetacher.isDetached()) {
            tableDetacher.dock(); // ensure the floating dialog is closed
        }
    }

    private void configureTable() {
        warningsTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        warningsTable.setShowGrid(false);
        warningsTable.setAutoCreateRowSorter(true);
        // Null-safe comparator for padding rows
        final javax.swing.table.TableRowSorter<?> sorter =
                (javax.swing.table.TableRowSorter<?>) warningsTable.getRowSorter();
        sorter.setComparator(0, (a, b) -> {
            if (a == null && b == null) return 0;
            if (a == null) return 1; // nulls sort last
            if (b == null) return -1;
            return ((Comparable) a).compareTo(b);
        });
        // Impact column: NaN-aware comparator. Default Double.compareTo
        // treats NaN as greater than any finite value, so descending sort
        // would put em-dashes (NaN impact, e.g., live warnings or
        // ImpactKind.NONE) at the top. Treat NaN as the lowest value
        // instead: descending puts high-impact rows first, em-dashes last.
        sorter.setComparator(2, (a, b) -> {
            final double da = (a instanceof Double) ? (Double) a : Double.NEGATIVE_INFINITY;
            final double db = (b instanceof Double) ? (Double) b : Double.NEGATIVE_INFINITY;
            final double na = Double.isNaN(da) ? Double.NEGATIVE_INFINITY : da;
            final double nb = Double.isNaN(db) ? Double.NEGATIVE_INFINITY : db;
            return Double.compare(na, nb);
        });

        // Severity column: icon-width + icon header + color renderer
        final javax.swing.table.TableColumn sevCol = warningsTable.getColumnModel().getColumn(0);
        sevCol.setPreferredWidth((int) GuiUtils.uiFontSize());
        sevCol.setMaxWidth((int) GuiUtils.uiFontSize());
        sevCol.setCellRenderer(new SeverityRenderer());
        sevCol.setHeaderRenderer(GuiUtils.JTables.iconHeaderRenderer(
                IconFactory.buttonIcon(IconFactory.GLYPH.DANGER, .9f), "Severity (click to sort)"));
        // Message column: fill, with tooltip for truncated text
        final javax.swing.table.TableColumn msgCol = warningsTable.getColumnModel().getColumn(1);
        msgCol.setPreferredWidth(300);
        msgCol.setCellRenderer(new DefaultTableCellRenderer() {
            final static String TIP = "<html>Tip: Press <tt><b>1</b></tt> to activate the Path Display Filter " +
                    "<br><i>Only selected paths (hide deselected)</i> to isolate affected paths";

            @Override
            public Component getTableCellRendererComponent(final JTable table, final Object value,
                                                           final boolean isSelected, final boolean hasFocus,
                                                           final int row, final int column) {
                super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (value instanceof String text && !text.isEmpty()) {
                    final FontMetrics fm = getFontMetrics(getFont());
                    final int colWidth = table.getColumnModel().getColumn(column).getWidth();
                    setToolTipText(fm.stringWidth(text) > colWidth - 4 ? text : TIP);
                } else {
                    setToolTipText(TIP);
                }
                return this;
            }
        });

        // Impact column: percentage + bar, sortable. Width is tight because the values are short, and we want
        // the message column to dominate
        final javax.swing.table.TableColumn impactCol = warningsTable.getColumnModel().getColumn(2);
        final int impactColWidth = (int) (GuiUtils.uiFontSize() * 4); // character length of 100%
        impactCol.setPreferredWidth(impactColWidth);
        impactCol.setMaxWidth(impactColWidth * 2);
        impactCol.setCellRenderer(new ImpactRenderer());
        impactCol.setHeaderRenderer(GuiUtils.JTables.iconHeaderRenderer(
                IconFactory.buttonIcon(IconFactory.GLYPH.SCALE_BALANCED, .9f),
                "<html>Impact: fraction of the reconstruction affected if this " +
                        "is a true error.<br>Higher = more downstream content at " +
                        "stake. Click to sort."));

        // Double-click navigates to warning location
        warningsTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(final java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    final int viewRow = warningsTable.getSelectedRow();
                    if (viewRow < 0) return;
                    final int modelRow = warningsTable.convertRowIndexToModel(viewRow);
                    if (modelRow >= 0 && modelRow < tableModel.warnings.size()) {
                        navigateToWarning(tableModel.warnings.get(modelRow));
                    }
                }
            }
        });

        // Right-click context menu
        warningsTable.setComponentPopupMenu(buildTablePopupMenu());
    }

    private JPopupMenu buildTablePopupMenu() {
        final JPopupMenu popup = new JPopupMenu();
        final JMenuItem copyItem = new JMenuItem("Copy Issue Description", IconFactory.menuIcon(IconFactory.GLYPH.CLIPBOARD));
        copyItem.addActionListener(e -> {
            final List<PlausibilityCheck.Warning> toCopy = tableModel.getSelectedWarnings(warningsTable, true);
            if (toCopy.isEmpty()) {
                sntui.error("No issues exist.");
                return;
            }
            final StringBuilder sb = new StringBuilder();
            for (final PlausibilityCheck.Warning w : toCopy) {
                sb.append(w.severity()).append('\t').append(w.message());
                if (!w.affectedPaths().isEmpty()) {
                    sb.append("\t[");
                    for (int i = 0; i < w.affectedPaths().size(); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(w.affectedPaths().get(i).getName());
                    }
                    sb.append(']');
                }
                sb.append('\n');
            }
            final java.awt.datatransfer.StringSelection sel = new java.awt.datatransfer.StringSelection(sb.toString().strip());
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
        });
        popup.add(copyItem);

        // Explain issue: open documentation page anchored to the relevant check
        final JMenuItem explainItem = new JMenuItem("Help on Issue...", IconFactory.menuIcon(IconFactory.GLYPH.QUESTION));
        explainItem.addActionListener(e -> {
            final List<PlausibilityCheck.Warning> warnings = tableModel.getSelectedWarnings(warningsTable, true);
            if (warnings.size() != 1) {
                sntui.error("No issue selected. Please re-run after selecting a single issue.");
                return;
            }
            final String anchor = getDocAnchor(warnings.getFirst().checkName());
            GuiUtils.openURL("https://imagej.net/plugins/snt/curation" + anchor);
        });
        popup.add(explainItem);
        popup.addSeparator();
        final JMenuItem clearItem = new JMenuItem("Clear All Issues", IconFactory.menuIcon(IconFactory.GLYPH.TRASH));
        clearItem.addActionListener(e -> {
            tableModel.setWarnings(List.of());
            refreshTableHeader();
        });
        popup.add(clearItem);
        popup.addSeparator();
        return popup;
    }


    /**
     * Re-attaches the table scroll pane after a dock. Called by the
     * {@link GuiUtils.JTables.DetachableTable} helper since GridBag constraints
     * aren't preserved by remove/add.
     */
    private void redockTableScroll() {
        if (panel == null || tableScroll == null) return;
        final GridBagConstraints gbc = GuiUtils.defaultGbc();
        gbc.gridy = GridBagConstraints.RELATIVE;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        panel.add(tableScroll, gbc);
        panel.revalidate();
        panel.repaint();
    }

    private void navigateToWarning(final PlausibilityCheck.Warning warning) {
        // Select affected paths so they render in selectedColor in all viewers
        final List<Path> affected = warning.affectedPaths();
        if (!affected.isEmpty()) {
            sntui.getPathManager().setSelectedPaths(new java.util.HashSet<>(affected), this);
        }
        final ImagePlus imp = sntui.plugin.getImagePlus();
        if (imp != null) {
            final sc.fiji.snt.util.PointInImage location = warning.location();
            if (location != null) {
                // Zoom to the warning's focal point (e.g. fork node), using the
                // first affected path to resolve any canvas offset
                final Path offsetPath = affected.isEmpty() ? null : affected.getFirst();
                ImpUtils.zoomTo(imp, visitingZoom.fraction(), location, offsetPath);
            } else if (!affected.isEmpty()) {
                // No specific location: fall back to fitting affected paths
                ImpUtils.zoomTo(imp, visitingZoom.fraction(), affected, sc.fiji.snt.hyperpanes.MultiDThreePanes.XY_PLANE);
            }
        } else {
            // No image data: refresh all active viewers (Rec. Viewer, sciview, etc.)
            sntui.plugin.updateAllViewers();
        }
    }


    /**
     * Builds and returns the panel to be added as a tab in SNTUI.
     */
    public JPanel getPanel() {
        if (panel != null) return panel;

        panel = SNTUI.InternalUtils.getTab();
        final GridBagConstraints gbc = GuiUtils.defaultGbc();

        // Header & description
        SNTUI.InternalUtils.addSeparatorWithURL(panel, "Curation Assistant:",
                "https://imagej.net/plugins/snt/curation", false, gbc);
        gbc.gridy++;
        gbc.weighty = 0.0;
        panel.add(GuiUtils.longSmallMsg("Flags implausible morphology in real time and on demand. "
                + "Adjust thresholds below to calibrate for specific cell types. "
                + "Double-click an issue to navigate to its location; "
                + "right-click the issues table for actions.", panel), gbc);
        gbc.gridy++;
        // Parameters region (live + on-demand). Wrapped in a scroll pane so
        // that when the user shrinks the top half of the split below the
        // params' natural height, the content scrolls instead of clipping.
        // The two sections are also independently collapsible via the
        // chevron next to each header -- collapse one to focus on the other.
        // WidthTrackingPanel (see below) instead of plain JPanel: it
        // implements Scrollable with getScrollableTracksViewportWidth() ==
        // true, so the panel reflows to the viewport's width instead of
        // sizing to its preferred width and overflowing the right edge
        // (which, with HORIZONTAL_SCROLLBAR_NEVER, would clip the histogram
        // buttons at the right of each row).
        final JPanel paramsPane = new WidthTrackingPanel(new GridBagLayout());
        final GridBagConstraints paramsGbc = GuiUtils.defaultGbc();
        paramsGbc.fill = GridBagConstraints.HORIZONTAL;
        paramsGbc.weightx = 1.0;
        paramsPane.add(buildLiveParamsPanel(), paramsGbc);
        paramsGbc.gridy++;
        paramsPane.add(buildOnDemandParamsPanel(), paramsGbc);
        paramsGbc.gridy++;
        // Push children to the top of the scroll viewport
        paramsGbc.weighty = 1.0;
        paramsPane.add(Box.createVerticalGlue(), paramsGbc);

        // VERTICAL_SCROLLBAR_ALWAYS rather than AS_NEEDED so the scrollbar's
        // width is always reserved by the viewport, never overlapping the
        // chevron and per-row histogram buttons that sit at the right edge.
        // The "non-functional scrollbar when content fits" cost is small;
        // for this dense panel the bar is usually active anyway.
        final JScrollPane paramsScroll = new JScrollPane(paramsPane,
                JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        paramsScroll.setBorder(BorderFactory.createEmptyBorder());
        paramsScroll.setViewportBorder(BorderFactory.createEmptyBorder());
        paramsScroll.setOpaque(false);
        paramsScroll.getViewport().setOpaque(false);
        // Faster scroll than the default 1px-per-tick
        paramsScroll.getVerticalScrollBar().setUnitIncrement(16);

        // Bottom half of the split: toolbar + warnings table. Bundling them
        // here (rather than as separate top-level rows) lets the JSplitPane
        // treat "params" and "warnings + their controls" as the two natural
        // sides of the divider; the toolbar always sits with what it
        // operates on.
        tableScroll = new JScrollPane(warningsTable);
        tableScroll.setMinimumSize(new Dimension(0, 0)); // allow shrinking
        warningsTable.setPreferredScrollableViewportSize(null); // defer to layout
        final JPanel bottomHalf = new JPanel(new BorderLayout());
        bottomHalf.setOpaque(false);
        bottomHalf.add(buildToolbar(), BorderLayout.NORTH);
        bottomHalf.add(tableScroll, BorderLayout.CENTER);

        // Vertical split between params (top) and toolbar+table (bottom).
        // resizeWeight = 0.0 means: when the whole tab is resized, the
        // bottom (table) absorbs the change while params stays at its
        // current size -- which matches users' usual mental model of
        // "give me more room for the issues list".
        final javax.swing.JSplitPane paramsTableSplit = new javax.swing.JSplitPane(
                javax.swing.JSplitPane.VERTICAL_SPLIT, paramsScroll, bottomHalf);
        paramsTableSplit.setBorder(BorderFactory.createEmptyBorder());
        paramsTableSplit.setOpaque(false);
        paramsTableSplit.setContinuousLayout(true);
        paramsTableSplit.setOneTouchExpandable(false);
        // Initial split: ~60% to params (typical content fits there), rest
        // for the issues list. setDividerLocation(double) only works after
        // the component is realized, so we also set a sensible absolute
        // fallback that takes effect immediately.
        paramsTableSplit.setDividerLocation(0.7);
        SwingUtilities.invokeLater(() -> paramsTableSplit.setDividerLocation(0.7));

        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.insets.top = SNTUI.InternalUtils.MARGIN;
        panel.add(paramsTableSplit, gbc);
        gbc.gridy++;
        gbc.insets.top = 0;

        // Detach / dock table: the helper needs the scroll pane to exist
        // (it captures a reference to it). We can't wire it inside
        // buildTablePopupMenu() because that runs from the constructor,
        // long before tableScroll is created here. Append the toggle item
        // to the existing popup now that everything it needs is in place.
        if (tableDetacher == null) {
            tableDetacher = new GuiUtils.JTables.DetachableTable(
                    tableScroll, "Issues (Curation Assistant)", this::redockTableScroll,
                    new Dimension(500, 200));
            final javax.swing.JPopupMenu existing = warningsTable.getComponentPopupMenu();
            if (existing != null) tableDetacher.installMenuItem(existing);
        }

        // Bind display filter shortcuts (same keys as QueueJumpingKeyListener)
        final int condition = JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT;
        panel.getInputMap(condition).put(KeyStroke.getKeyStroke('1'), "togglePathsChoice");
        panel.getInputMap(condition).put(KeyStroke.getKeyStroke('2'), "togglePartsChoice");
        panel.getInputMap(condition).put(KeyStroke.getKeyStroke('3'), "toggleChannelAndFrameChoice");
        panel.getActionMap().put("togglePathsChoice", new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent e) {
                sntui.togglePathsChoice();
            }
        });
        panel.getActionMap().put("togglePartsChoice", new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent e) {
                sntui.togglePartsChoice();
            }
        });
        panel.getActionMap().put("toggleChannelAndFrameChoice", new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent e) {
                sntui.toggleChannelAndFrameChoice();
            }
        });

        SNTUI.InternalUtils.addHoldToToggleKeyListener(warningsTable, sntui.plugin);

        return panel;
    }

    /**
     * Registers curation commands in the SNT Command Finder.
     *
     * @param cmdFinder the command finder instance
     */
    protected void registerCommands(final SNTCommandFinder cmdFinder) {
        if (liveToggle != null)
            cmdFinder.register(liveToggle, "Toggle Live Monitoring", "Assistant tab");
        if (onDemandButton != null)
            cmdFinder.register(onDemandButton, "Run Full Scan", "Assistant tab");
    }

    private JPanel buildLiveParamsPanel() {
        final JPanel p = new JPanel(new GridBagLayout());
        final GridBagConstraints c = GuiUtils.defaultGbc();

        liveHeaderCheckbox = createSectionHeader(p, "Live Monitoring Parameters:", c);
        c.gridy++;
        final int savedLeft = c.insets.left;
        c.insets.left += sectionChildIndent(liveHeaderCheckbox);

        // Branch angle min
        final PlausibilityCheck.BranchAngle angleCheck = monitor.getLiveCheck(PlausibilityCheck.BranchAngle.class);
        branchAngleMinCheckbox = new JCheckBox("Fork angle: min (°)",
                angleCheck != null && angleCheck.isEnabled());
        branchAngleMinCheckbox.setToolTipText("Warns when a child path emerges nearly parallel to its parent");
        branchAngleMinSpinner = new JSpinner(new SpinnerNumberModel(
                angleCheck != null ? angleCheck.getMinAngleDeg() : 10.0, 0.0, 90.0, 5.0));
        branchAngleMinCheckbox.addActionListener(e -> {
            if (angleCheck != null) angleCheck.setEnabled(
                    branchAngleMinCheckbox.isSelected() || branchAngleMaxCheckbox.isSelected());
            branchAngleMinSpinner.setEnabled(branchAngleMinCheckbox.isSelected());
        });
        branchAngleMinSpinner.addChangeListener(e -> {
            if (angleCheck != null) angleCheck.setMinAngleDeg((Double) branchAngleMinSpinner.getValue());
        });
        // Histogram button: both BranchAngle spinners share the same OUTSIDE-flagged
        // distribution. Clicking either button opens the same chart with both markers.
        final JButton branchAngleMinHist = (angleCheck == null) ? null
                : CurationHistograms.button("Fork angle",
                paths -> monitor.measure(angleCheck, paths),
                this::currentPaths,
                () -> ((Number) branchAngleMinSpinner.getValue()).doubleValue(),
                () -> ((Number) branchAngleMaxSpinner.getValue()).doubleValue(),
                CurationHistograms.Side.OUTSIDE_FLAGGED, p);
        addCheckRow(p, c, branchAngleMinCheckbox, spinnerWithHistogram(branchAngleMinSpinner, branchAngleMinHist));

        // Branch angle max
        branchAngleMaxCheckbox = new JCheckBox("Fork angle: max (°)",
                angleCheck != null && angleCheck.isEnabled());
        branchAngleMaxCheckbox.setToolTipText("Warns when a child path doubles back, nearly anti-parallel to its parent");
        branchAngleMaxSpinner = new JSpinner(new SpinnerNumberModel(
                angleCheck != null ? angleCheck.getMaxAngleDeg() : 170.0, 90.0, 180.0, 5.0));
        branchAngleMaxCheckbox.addActionListener(e -> {
            if (angleCheck != null) angleCheck.setEnabled(
                    branchAngleMinCheckbox.isSelected() || branchAngleMaxCheckbox.isSelected());
            branchAngleMaxSpinner.setEnabled(branchAngleMaxCheckbox.isSelected());
        });
        branchAngleMaxSpinner.addChangeListener(e -> {
            if (angleCheck != null) angleCheck.setMaxAngleDeg((Double) branchAngleMaxSpinner.getValue());
        });
        final JButton branchAngleMaxHist = (angleCheck == null) ? null
                : CurationHistograms.button("Fork angle",
                paths -> monitor.measure(angleCheck, paths),
                this::currentPaths,
                () -> ((Number) branchAngleMaxSpinner.getValue()).doubleValue(),
                () -> ((Number) branchAngleMinSpinner.getValue()).doubleValue(),
                CurationHistograms.Side.OUTSIDE_FLAGGED, p);
        addCheckRow(p, c, branchAngleMaxCheckbox, spinnerWithHistogram(branchAngleMaxSpinner, branchAngleMaxHist));

        // Direction continuity
        final PlausibilityCheck.DirectionContinuity dirCheck =
                monitor.getLiveCheck(PlausibilityCheck.DirectionContinuity.class);
        directionCheckbox = new JCheckBox("Fork direction flow: max change (°)",
                dirCheck != null && dirCheck.isEnabled());
        directionCheckbox.setToolTipText("Detects possible U-turns where the child reverses the parent's trajectory");
        directionSpinner = new JSpinner(new SpinnerNumberModel(
                dirCheck != null ? dirCheck.getMinAlignmentDeg() : 30.0, 0.0, 90.0, 5.0));
        wireCheckbox(directionCheckbox, directionSpinner, dirCheck);
        directionSpinner.addChangeListener(e -> {
            if (dirCheck != null) dirCheck.setMinAlignmentDeg((Double) directionSpinner.getValue());
        });
        final JButton directionHist = (dirCheck == null) ? null
                : CurationHistograms.button("Direction continuity",
                paths -> monitor.measure(dirCheck, paths),
                this::currentPaths,
                () -> ((Number) directionSpinner.getValue()).doubleValue(),
                CurationHistograms.Side.LEFT_FLAGGED, p);
        addCheckRow(p, c, directionCheckbox, spinnerWithHistogram(directionSpinner, directionHist));

        // Tortuosity consistency
        final PlausibilityCheck.TortuosityConsistency tortCheck =
                monitor.getLiveCheck(PlausibilityCheck.TortuosityConsistency.class);
        tortuosityCheckbox = new JCheckBox("Tortuosity: max mismatch at fork",
                tortCheck != null && tortCheck.isEnabled());
        tortuosityCheckbox.setToolTipText("Warns when a child's path sinuosity differs markedly from its parent's");
        tortuositySpinner = new JSpinner(new SpinnerNumberModel(
                tortCheck != null ? tortCheck.getMaxContractionDiff() : 0.3, 0.05, 1.0, 0.05));
        wireCheckbox(tortuosityCheckbox, tortuositySpinner, tortCheck);
        tortuositySpinner.addChangeListener(e -> {
            if (tortCheck != null) tortCheck.setMaxContractionDiff((Double) tortuositySpinner.getValue());
        });
        final JButton tortuosityHist = (tortCheck == null) ? null
                : CurationHistograms.button("Tortuosity consistency",
                paths -> monitor.measure(tortCheck, paths),
                this::currentPaths,
                () -> ((Number) tortuositySpinner.getValue()).doubleValue(),
                CurationHistograms.Side.RIGHT_FLAGGED, p);
        addCheckRow(p, c, tortuosityCheckbox, spinnerWithHistogram(tortuositySpinner, tortuosityHist));

        // Radius continuity
        final PlausibilityCheck.RadiusContinuity radiusCheck =
                monitor.getLiveCheck(PlausibilityCheck.RadiusContinuity.class);
        radiusCheckbox = new JCheckBox("Radius continuity: max ratio at fork",
                radiusCheck != null && radiusCheck.isEnabled());
        radiusCheckbox.setToolTipText("Flags forks where the child's caliber differs sharply from the parent's");
        radiusSpinner = new JSpinner(new SpinnerNumberModel(
                radiusCheck != null ? radiusCheck.getMaxRatio() : 1.5, 1.0, 10.0, 0.1));
        wireCheckbox(radiusCheckbox, radiusSpinner, radiusCheck);
        radiusSpinner.addChangeListener(e -> {
            if (radiusCheck != null) radiusCheck.setMaxRatio((Double) radiusSpinner.getValue());
        });
        final JButton radiusHist = (radiusCheck == null) ? null
                : CurationHistograms.button("Radius continuity",
                paths -> monitor.measure(radiusCheck, paths),
                this::currentPaths,
                () -> ((Number) radiusSpinner.getValue()).doubleValue(),
                CurationHistograms.Side.RIGHT_FLAGGED, p);
        addCheckRow(p, c, radiusCheckbox, spinnerWithHistogram(radiusSpinner, radiusHist));

        // Inter-fork distance
        final PlausibilityCheck.InterForkDistance interForkCheck = monitor.getLiveCheck(PlausibilityCheck.InterForkDistance.class);
        final JCheckBox interForkCheckbox = new JCheckBox("Inter-fork distance: min",
                interForkCheck != null && interForkCheck.isEnabled());
        interForkCheckbox.setToolTipText("Flags consecutive forks on the same parent path that sit suspiciously close together");
        interForkSpinner = new JSpinner(new SpinnerNumberModel(
                interForkCheck != null ? interForkCheck.getMinDistanceUm() : 5.0, 0.1, 500.0, 0.5));
        wireCheckbox(interForkCheckbox, interForkSpinner, interForkCheck);
        interForkSpinner.addChangeListener(e -> {
            if (interForkCheck != null) interForkCheck.setMinDistanceUm((Double) interForkSpinner.getValue());
        });
        final JButton interForkHist = (interForkCheck == null) ? null
                : CurationHistograms.button("Inter-fork distance",
                paths -> monitor.measure(interForkCheck, paths),
                this::currentPaths,
                () -> ((Number) interForkSpinner.getValue()).doubleValue(),
                CurationHistograms.Side.LEFT_FLAGGED, p);
        addCheckRow(p, c, interForkCheckbox, spinnerWithHistogram(interForkSpinner, interForkHist));

        // Terminal path length
        final PlausibilityCheck.TerminalBranchLength termCheck = monitor.getLiveCheck(PlausibilityCheck.TerminalBranchLength.class);
        termBranchCheckbox = new JCheckBox("Terminal path: min length", termCheck != null && termCheck.isEnabled());
        termBranchCheckbox.setToolTipText("Catches stub branches that may be accidental clicks or tracing artifacts");
        termBranchSpinner = new JSpinner(new SpinnerNumberModel(
                termCheck != null ? termCheck.getMinLengthUm() : 2.0, 0.1, 100.0, 0.5));
        wireCheckbox(termBranchCheckbox, termBranchSpinner, termCheck);
        termBranchSpinner.addChangeListener(e -> {
            if (termCheck != null) termCheck.setMinLengthUm((Double) termBranchSpinner.getValue());
        });
        final JButton termBranchHist = (termCheck == null) ? null
                : CurationHistograms.button("Terminal path length",
                paths -> monitor.measure(termCheck, paths),
                this::currentPaths,
                () -> ((Number) termBranchSpinner.getValue()).doubleValue(),
                CurationHistograms.Side.LEFT_FLAGGED, p);
        addCheckRow(p, c, termBranchCheckbox, spinnerWithHistogram(termBranchSpinner, termBranchHist));

        // Constant radii
        final PlausibilityCheck.ConstantRadii constCheck = monitor.getLiveCheck(PlausibilityCheck.ConstantRadii.class);
        constantRadiiCheckbox = new JCheckBox("Uniform thickness: flag",
                constCheck != null && constCheck.isEnabled());
        constantRadiiCheckbox.setToolTipText("Identifies paths where all nodes share the same radius, suggesting radii were not fitted");
        wireCheckbox(constantRadiiCheckbox, null, constCheck);
        addCheckRow(p, c, constantRadiiCheckbox, null);

        c.insets.left = savedLeft;

        // Collect and wire section-level toggling
        liveCheckboxes = List.of(branchAngleMinCheckbox, branchAngleMaxCheckbox,
                directionCheckbox, tortuosityCheckbox, radiusCheckbox,
                interForkCheckbox, termBranchCheckbox,
                constantRadiiCheckbox);
        wireSectionHeader(liveHeaderCheckbox, liveCheckboxes);

        return p;
    }

    private JPanel buildOnDemandParamsPanel() {
        final JPanel p = new JPanel(new GridBagLayout());
        final GridBagConstraints c = GuiUtils.defaultGbc();

        onDemandHeaderCheckbox = createSectionHeader(p, "On-Demand Monitoring Parameters", c);
        c.gridy++;
        final int savedLeft = c.insets.left;
        c.insets.left += sectionChildIndent(onDemandHeaderCheckbox);

        // Crossovers
        final PlausibilityCheck.Crossovers overlapCheck = monitor.getDeepCheck(PlausibilityCheck.Crossovers.class);
        // On-demand check UI controls
        overlapCheckbox = new JCheckBox("Cross-over detection: max proximity", overlapCheck != null && overlapCheck.isEnabled());
        overlapCheckbox.setToolTipText("Detects regions where distinct paths run suspiciously close, suggesting duplicate tracing");
        overlapSpinner = new JSpinner(new SpinnerNumberModel(
                overlapCheck != null ? overlapCheck.getProximityUm() : 2.0, 0.1, 100.0, 0.5));
        wireCheckbox(overlapCheckbox, overlapSpinner, overlapCheck);
        overlapSpinner.addChangeListener(e -> {
            if (overlapCheck != null) overlapCheck.setProximityUm((Double) overlapSpinner.getValue());
        });
        final JButton overlapHist = (overlapCheck == null) ? null
                : CurationHistograms.button("Crossovers",
                paths -> monitor.measure(overlapCheck, paths),
                this::currentPaths,
                () -> ((Number) overlapSpinner.getValue()).doubleValue(),
                CurationHistograms.Side.LEFT_FLAGGED, p);
        addCheckRow(p, c, overlapCheckbox, spinnerWithHistogram(overlapSpinner, overlapHist));

        // Bundled paths (sustained parallel proximity)
        final PlausibilityCheck.BundledPaths bundledCheck = monitor.getDeepCheck(PlausibilityCheck.BundledPaths.class);
        final JCheckBox bundledPathsCheckbox = new JCheckBox("Bundle detection: max angle (°)",
                bundledCheck != null && bundledCheck.isEnabled());
        bundledPathsCheckbox.setToolTipText("<html>Flags regions where two paths run nearly parallel for a sustained " +
                "distance<br>(complement of path cross-overs). Useful for catching duplicate traces<br>" +
                "of an axon that runs alongside its neighbor. Off by default -- enable when relevant.");
        bundledPathsSpinner = new JSpinner(new SpinnerNumberModel(
                bundledCheck != null ? bundledCheck.getMaxParallelAngleDeg() : 20.0, 0.0, 90.0, 5.0));
        wireCheckbox(bundledPathsCheckbox, bundledPathsSpinner, bundledCheck);
        bundledPathsSpinner.addChangeListener(e -> {
            if (bundledCheck != null) bundledCheck.setMaxParallelAngleDeg((Double) bundledPathsSpinner.getValue());
        });
        final JButton bundledPathsHist = (bundledCheck == null) ? null
                : CurationHistograms.button("Bundled paths",
                paths -> monitor.measure(bundledCheck, paths),
                this::currentPaths,
                () -> ((Number) bundledPathsSpinner.getValue()).doubleValue(),
                CurationHistograms.Side.LEFT_FLAGGED, p);
        addCheckRow(p, c, bundledPathsCheckbox, spinnerWithHistogram(bundledPathsSpinner, bundledPathsHist));

        // Terminal near ancestor
        final PlausibilityCheck.TerminalNearAncestor tnaCheck = monitor.getDeepCheck(PlausibilityCheck.TerminalNearAncestor.class);
        final JCheckBox terminalNearAncestorCheckbox = new JCheckBox("Missed-fork candidate: max proximity",
                tnaCheck != null && tnaCheck.isEnabled());
        terminalNearAncestorCheckbox.setToolTipText("Flags terminal branches whose endpoint sits near a non-direct-ancestor path (possible missed fork)");
        terminalNearAncestorSpinner = new JSpinner(new SpinnerNumberModel(
                tnaCheck != null ? tnaCheck.getMaxProximityUm() : 3.0, 0.1, 500.0, 0.5));
        wireCheckbox(terminalNearAncestorCheckbox, terminalNearAncestorSpinner, tnaCheck);
        terminalNearAncestorSpinner.addChangeListener(e -> {
            if (tnaCheck != null) tnaCheck.setMaxProximityUm((Double) terminalNearAncestorSpinner.getValue());
        });
        final JButton tnaHist = (tnaCheck == null) ? null
                : CurationHistograms.button("Missed-fork candidate",
                paths -> monitor.measure(tnaCheck, paths),
                this::currentPaths,
                () -> ((Number) terminalNearAncestorSpinner.getValue()).doubleValue(),
                CurationHistograms.Side.LEFT_FLAGGED, p);
        addCheckRow(p, c, terminalNearAncestorCheckbox, spinnerWithHistogram(terminalNearAncestorSpinner, tnaHist));

        // Z-extent ratio (flat-in-z detection; off by default)
        final PlausibilityCheck.ZExtentRatio zCheck = monitor.getDeepCheck(PlausibilityCheck.ZExtentRatio.class);
        final JCheckBox zExtentCheckbox = new JCheckBox("Z-extent: min ratio", zCheck != null && zCheck.isEnabled());
        zExtentCheckbox.setToolTipText("<html>Flags paths whose nodes barely vary in Z relative to path length.<br>" +
                "Off by default: enable for cell types that span multiple Z slices.<br>" +
                "Silently skipped when the dataset is 2D.");
        zExtentSpinner = new JSpinner(new SpinnerNumberModel(
                zCheck != null ? zCheck.getMinRatio() : 0.01, 0.0, 1.0, 0.005));
        wireCheckbox(zExtentCheckbox, zExtentSpinner, zCheck);
        zExtentSpinner.addChangeListener(e -> {
            if (zCheck != null) zCheck.setMinRatio((Double) zExtentSpinner.getValue());
        });
        final JButton zExtentHist = (zCheck == null) ? null
                : CurationHistograms.button("Z-extent ratio",
                paths -> monitor.measure(zCheck, paths),
                this::currentPaths,
                () -> ((Number) zExtentSpinner.getValue()).doubleValue(),
                CurationHistograms.Side.LEFT_FLAGGED, p);
        addCheckRow(p, c, zExtentCheckbox, spinnerWithHistogram(zExtentSpinner, zExtentHist));

        // Radius jumps
        final PlausibilityCheck.RadiusJumps jumpsCheck = monitor.getDeepCheck(PlausibilityCheck.RadiusJumps.class);
        radiusJumpsCheckbox = new JCheckBox("Thickness jumps: max ratio",
                jumpsCheck != null && jumpsCheck.isEnabled());
        radiusJumpsCheckbox.setToolTipText("Finds adjacent nodes with a sudden radius jump, often from fitting errors");
        radiusJumpsSpinner = new JSpinner(new SpinnerNumberModel(
                jumpsCheck != null ? jumpsCheck.getMaxJumpRatio() : 3.0, 1.5, 20.0, 0.5));
        wireCheckbox(radiusJumpsCheckbox, radiusJumpsSpinner, jumpsCheck);
        radiusJumpsSpinner.addChangeListener(e -> {
            if (jumpsCheck != null) jumpsCheck.setMaxJumpRatio((Double) radiusJumpsSpinner.getValue());
        });
        final JButton radiusJumpsHist = (jumpsCheck == null) ? null
                : CurationHistograms.button("Thickness jumps",
                paths -> monitor.measure(jumpsCheck, paths),
                this::currentPaths,
                () -> ((Number) radiusJumpsSpinner.getValue()).doubleValue(),
                CurationHistograms.Side.RIGHT_FLAGGED, p);
        addCheckRow(p, c, radiusJumpsCheckbox, spinnerWithHistogram(radiusJumpsSpinner, radiusJumpsHist));

        // Radius monotonicity
        final PlausibilityCheck.RadiusMonotonicity monoCheck =
                monitor.getDeepCheck(PlausibilityCheck.RadiusMonotonicity.class);
        radiusMonoCheckbox = new JCheckBox("Thickness inversions: min run length",
                monoCheck != null && monoCheck.isEnabled());
        radiusMonoCheckbox.setToolTipText("Flags sustained centripetal radius increases, which violate the expected centrifugal taper");
        radiusMonoSpinner = new JSpinner(new SpinnerNumberModel(
                monoCheck != null ? monoCheck.getMinIncreasingRun() : 10, 3, 100, 1));
        wireCheckbox(radiusMonoCheckbox, radiusMonoSpinner, monoCheck);
        radiusMonoSpinner.addChangeListener(e -> {
            if (monoCheck != null) monoCheck.setMinIncreasingRun((Integer) radiusMonoSpinner.getValue());
        });
        final JButton radiusMonoHist = (monoCheck == null) ? null
                : CurationHistograms.button("Thickness inversions",
                paths -> monitor.measure(monoCheck, paths),
                this::currentPaths,
                () -> ((Number) radiusMonoSpinner.getValue()).doubleValue(),
                CurationHistograms.Side.RIGHT_FLAGGED, p);
        addCheckRow(p, c, radiusMonoCheckbox, spinnerWithHistogram(radiusMonoSpinner, radiusMonoHist));

        // Invalid radii (zero/NaN) -- flag-only, no threshold
        final PlausibilityCheck.InvalidRadius invalidRadiusCheck =
                monitor.getDeepCheck(PlausibilityCheck.InvalidRadius.class);
        invalidRadiusCheckbox = new JCheckBox("Invalid thickness: flag",
                invalidRadiusCheck != null && invalidRadiusCheck.isEnabled());
        invalidRadiusCheckbox.setToolTipText("Flags paths containing nodes with zero, negative, or NaN radius (typically un-fitted nodes)");
        wireCheckbox(invalidRadiusCheckbox, null, invalidRadiusCheck);
        addCheckRow(p, c, invalidRadiusCheckbox, null);

        // Image signal quality
        final PlausibilityCheck.SignalQuality signalCheck = monitor.getDeepCheck(PlausibilityCheck.SignalQuality.class);
        signalQualityCheckbox = new JCheckBox("Path signal quality: min contrast",
                signalCheck != null && signalCheck.isEnabled());
        signalQualityCheckbox.setToolTipText(
                "Flags paths with poor signal-to-background contrast (requires image).\n" +
                        "Set to -1 for auto-threshold derived from image statistics.");
        signalQualitySpinner = new JSpinner(new SpinnerNumberModel(
                signalCheck != null ? signalCheck.getMinContrast() : PlausibilityCheck.SignalQuality.AUTO_THRESHOLD,
                PlausibilityCheck.SignalQuality.AUTO_THRESHOLD, 10000.0, 0.5));
        wireCheckbox(signalQualityCheckbox, signalQualitySpinner, signalCheck);
        signalQualitySpinner.addChangeListener(e -> {
            if (signalCheck != null) signalCheck.setMinContrast((Double) signalQualitySpinner.getValue());
        });
        // Undo button: always visible, resets spinner to AUTO_THRESHOLD
        final JButton sqUndoBtn = GuiUtils.Buttons.undo();
        sqUndoBtn.setToolTipText("Reset to auto-threshold (-1)");
        sqUndoBtn.addActionListener(e -> {
            signalQualitySpinner.setValue(PlausibilityCheck.SignalQuality.AUTO_THRESHOLD);
            if (signalCheck != null) signalCheck.setMinContrast(PlausibilityCheck.SignalQuality.AUTO_THRESHOLD);
        });
        // Histogram button. The image-context setup happens inside the worker  which runs a full-volume min/max
        // scan independent of SNT.stats. This bypasses the scenario where the cached SNT.stats object is lazily
        // populated by e.g. a local search, causing the auto-resolved threshold to be drawn at a meaningless value.
        // After prepareImage runs on the worker, done() sees fresh image stats and resolves the AUTO value
        final JButton signalHist = (signalCheck == null) ? null
                : CurationHistograms.button("Path signal quality",
                paths -> {
                    // prepareImage is null-safe (clears stats when no image is loaded). We always defer to
                    // signalCheck.measure(), which returns its own withHint("Load an image first.") when the
                    // image is missing: returning EMPTY here would strip that hint and the user would see the
                    // generic "trace some paths" fallback instead.
                    signalCheck.prepareImage(sntui.plugin.getLoadedData());
                    return signalCheck.measure(paths);
                },
                this::currentPaths,
                () -> {
                    final double v = ((Number) signalQualitySpinner.getValue()).doubleValue();
                    return (v == PlausibilityCheck.SignalQuality.AUTO_THRESHOLD)
                           ? signalCheck.getResolvedThreshold() : v;
                },
                CurationHistograms.Side.LEFT_FLAGGED, p);
        addCheckRow(p, c, signalQualityCheckbox, sqUndoBtn, spinnerWithHistogram(signalQualitySpinner, signalHist));

        // Uncertain terminal: tip-window SNR (image-dependent, parallel to signal quality)
        final PlausibilityCheck.UncertainTerminal utCheck = monitor.getDeepCheck(PlausibilityCheck.UncertainTerminal.class);
        uncertainTerminalCheckbox = new JCheckBox("Tip signal quality: min contrast",
                utCheck != null && utCheck.isEnabled());
        uncertainTerminalCheckbox.setToolTipText("<html>Flags terminal branches whose last few nodes have low " +
                "signal-to-background contrast<br>(the tip's location is uncertain because the neurite faded into " +
                "the background).<br>Requires a loaded image.<br>" +
                "Set to -1 for auto-threshold (adopted from Path signal quality when available, otherwise " +
                "derived from image statistics).");
        uncertainTerminalSpinner = new JSpinner(new SpinnerNumberModel(
                utCheck != null ? utCheck.getMinTipContrast() : PlausibilityCheck.UncertainTerminal.AUTO_THRESHOLD,
                PlausibilityCheck.UncertainTerminal.AUTO_THRESHOLD, 100.0, 0.1));
        wireCheckbox(uncertainTerminalCheckbox, uncertainTerminalSpinner, utCheck);
        uncertainTerminalSpinner.addChangeListener(e -> {
            if (utCheck != null) utCheck.setMinTipContrast((Double) uncertainTerminalSpinner.getValue());
        });
        final JButton utUndoBtn = GuiUtils.Buttons.undo();
        utUndoBtn.setToolTipText("Reset to auto-threshold (-1)");
        utUndoBtn.addActionListener(e -> {
            uncertainTerminalSpinner.setValue(PlausibilityCheck.UncertainTerminal.AUTO_THRESHOLD);
            if (utCheck != null) utCheck.setMinTipContrast(PlausibilityCheck.UncertainTerminal.AUTO_THRESHOLD);
        });
        final JButton uncertainTerminalHist = (utCheck == null) ? null
                : CurationHistograms.button("Tip signal quality",
                paths -> {
                    // prepareImage borrows SignalQuality's stats when its peer has them,
                    // otherwise runs an own full-volume scan. Null-safe on no image.
                    utCheck.prepareImage(sntui.plugin.getLoadedData());
                    return utCheck.measure(paths);
                },
                this::currentPaths,
                () -> {
                    final double v = ((Number) uncertainTerminalSpinner.getValue()).doubleValue();
                    return (v == PlausibilityCheck.UncertainTerminal.AUTO_THRESHOLD)
                           ? utCheck.getResolvedThreshold() : v;
                },
                CurationHistograms.Side.LEFT_FLAGGED, p);
        addCheckRow(p, c, uncertainTerminalCheckbox, utUndoBtn, spinnerWithHistogram(uncertainTerminalSpinner, uncertainTerminalHist));

        // Intensity valley: per-node localized dip detector (off by default)
        final PlausibilityCheck.IntensityValley ivCheck =
                monitor.getDeepCheck(PlausibilityCheck.IntensityValley.class);
        intensityValleyCheckbox = new JCheckBox("Path signal quality dips: min drop",
                ivCheck != null && ivCheck.isEnabled());
        intensityValleyCheckbox.setToolTipText("<html>Flags localized intensity dips along a path: a few-node " +
                "drop in brightness flanked by bright signal on both sides.<br>" +
                "Off by default -- enable per-image when relevant (e.g., en-passant axons crossing dim regions).<br>" +
                "Threshold is the minimum relative prominence (fraction below the surrounding peaks). " +
                "Requires a loaded image.");
        intensityValleySpinner = new JSpinner(new SpinnerNumberModel(
                ivCheck != null ? ivCheck.getMinProminence() : 0.30, 0.05, 1.0, 0.05));
        wireCheckbox(intensityValleyCheckbox, intensityValleySpinner, ivCheck);
        intensityValleySpinner.addChangeListener(e -> {
            if (ivCheck != null) ivCheck.setMinProminence((Double) intensityValleySpinner.getValue());
        });
        final JButton intensityValleyHist = (ivCheck == null) ? null
                : CurationHistograms.button("Path signal quality dips",
                paths -> {
                    ivCheck.setImage(sntui.plugin.getLoadedData());
                    return ivCheck.measure(paths);
                },
                this::currentPaths,
                () -> ((Number) intensityValleySpinner.getValue()).doubleValue(),
                CurationHistograms.Side.RIGHT_FLAGGED, p);
        addCheckRow(p, c, intensityValleyCheckbox, spinnerWithHistogram(intensityValleySpinner, intensityValleyHist));

        c.insets.left = savedLeft;

        // Collect and wire section-level toggling
        onDemandCheckboxes = List.of(overlapCheckbox, bundledPathsCheckbox,
                terminalNearAncestorCheckbox, zExtentCheckbox,
                radiusJumpsCheckbox, radiusMonoCheckbox, invalidRadiusCheckbox,
                signalQualityCheckbox, uncertainTerminalCheckbox,
                intensityValleyCheckbox);
        wireSectionHeader(onDemandHeaderCheckbox, onDemandCheckboxes);

        return p;
    }

    private JToolBar buildToolbar() {
        final JToolBar tb = new JToolBar();
        tb.setFloatable(false);
        tb.add(GuiUtils.Buttons.help("https://imagej.net/plugins/snt/curation"));
        tb.add(Box.createHorizontalGlue());
        tb.addSeparator();

        // Live monitoring toggle
        liveToggle = new JToggleButton();
        liveToggle.setSelected(monitor.isEnabled());
        IconFactory.assignIcon(liveToggle, IconFactory.GLYPH.HEART_CIRCLE_BOLT, IconFactory.GLYPH.HEART_PULSE, 1.1f);
        liveToggle.setToolTipText("Enable live monitoring");
        liveToggle.addActionListener(e -> {
            if (noParametersSelected(liveCheckboxes, "live parameter")) {
                liveToggle.setSelected(false);
                return;
            }
            final boolean on = liveToggle.isSelected();
            if (on && !monitor.getCurrentWarnings().isEmpty()) {
                final boolean clear = sntui.guiUtils.getConfirmation(
                        "Enabling live monitoring will clear the current list of issues. Continue?",
                        "Enable Live Monitoring?");
                if (!clear) {
                    liveToggle.setSelected(false); // revert toggle
                    return;
                }
            }
            monitor.setEnabled(on);
        });
        tb.add(liveToggle);
        tb.addSeparator();
        // Run Full Scan button
        onDemandButton = new JButton(IconFactory.buttonIcon(IconFactory.GLYPH.STETHOSCOPE, 1.1f));
        onDemandButton.setToolTipText("Run full scan.\nScans all paths using both live and on-demand parameters");
        onDemandButton.addActionListener(e -> runOnDemandAsync());
        tb.add(onDemandButton);
        tb.addSeparator();
        tb.add(Box.createHorizontalGlue());

        // Filter button: restrict table by severity
        final JButton filterButton = GuiUtils.Buttons.OptionsButton(
                IconFactory.GLYPH.EYE, 1.1f, getFilterVisibilityMenu());
        filterButton.setToolTipText("Filter warnings by severity");
        tb.add(filterButton);
        tb.addSeparator();
        // Tools button
        final JButton toolsButton = GuiUtils.Buttons.OptionsButton(IconFactory.GLYPH.TOOLBOX, 1f, getToolsMenu());
        toolsButton.setToolTipText("Actions & utilities");
        tb.add(toolsButton);
        tb.addSeparator();
        // Calibration button
        calibrationMenu = new JPopupMenu();
        GuiUtils.addSeparator(calibrationMenu, "Auto-tuning:");
        final JMenuItem calibrateItem = new JMenuItem("Calibrate Thresholds from Traced Cells...");
        calibrateItem.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.CALCULATOR));
        calibrateItem.setToolTipText("Infer parameter thresholds from the statistics of existing reconstructions");
        calibrateItem.addActionListener(e -> runCalibration());
        calibrationMenu.add(calibrateItem);
        GuiUtils.addSeparator(calibrationMenu, "Built-in Presets:");
        populateBuiltInPresetEntries();
        GuiUtils.addSeparator(calibrationMenu, "User Presets:");
        populateUserPresetEntries();
        final JMenuItem saveItem = new JMenuItem("Create From Current Parameters...");
        saveItem.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.PLUS));
        saveItem.setToolTipText("Save current thresholds and enabled states to a .curation file");
        saveItem.addActionListener(e -> saveCurationFile());
        calibrationMenu.add(saveItem);
        calibrationMenu.addSeparator();
        final JMenuItem refreshItem = new JMenuItem("Reload User Presets");
        refreshItem.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.REDO));
        refreshItem.addActionListener(e -> {
            final int changedItems = populateUserPresetEntries();
            sntui.showStatus(
                    (changedItems > 0) ? changedItems + " new item(s) loaded." : "No new presets detected.",
                    true);
        });
        calibrationMenu.add(refreshItem);
        final JMenuItem openDirItem = new JMenuItem("Reveal User Presets Directory");
        openDirItem.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.OPEN_FOLDER));
        openDirItem.addActionListener(e -> {
            try {
                FileChooser.reveal(PlausibilityCalibrator.getCurationsDirectory(sntui.getPrefs().getWorkspaceDir()));
            } catch (final Exception ex) {
                SNTUtils.log("Could not open directory: " + ex.getMessage());
            }
        });
        calibrationMenu.add(openDirItem);
        final JButton optionsButton = GuiUtils.Buttons.OptionsButton(IconFactory.GLYPH.OPTIONS, 1.1f, calibrationMenu);
        optionsButton.setToolTipText("Auto-tuning and calibration options");
        tb.add(optionsButton);

        return tb;
    }

    private JPopupMenu getFilterVisibilityMenu() {
        final JPopupMenu filterMenu = new JPopupMenu();
        GuiUtils.addSeparator(filterMenu, "Show:");
        for (final PlausibilityCheck.Severity sev : PlausibilityCheck.Severity.values()) {
            final Color sevColor = severityColor(sev);
            final String sevLabel = switch (sev) {
                case ERROR -> "Errors";
                case WARNING -> "Warnings";
                case INFO -> "Advisory Notes";
            };
            final JCheckBoxMenuItem item = new JCheckBoxMenuItem(sevLabel,
                    IconFactory.accentIcon(sevColor, true), tableModel.isSeverityVisible(sev));
            item.addActionListener(e -> {
                tableModel.setSeverityVisible(sev, item.isSelected());
                refreshTableHeader();
                if (tableModel.visibleSeverities.isEmpty()) {
                    sntui.showMessage("All severity levels disabled. No issues will be listed.",
                            "All Issues Filtered Out");
                }
            });
            filterMenu.add(item);
        }

        // Sort options. The TableRowSorter preserves sort keys across fireTableDataChanged, so toggling
        // this once keeps the table sorted by impact through subsequent scans and filter changes
        GuiUtils.addSeparator(filterMenu, "Sort:");
        final JCheckBoxMenuItem sortByImpact = new JCheckBoxMenuItem("Sort by Descending Impact",
                IconFactory.menuIcon(IconFactory.GLYPH.SCALE_BALANCED));
        sortByImpact.setToolTipText("<html>When enabled, the warnings table is sorted by " +
                "impact (highest first).<br>Useful for triage: rows with more of the " +
                "reconstruction at stake float to the top.<br>Clicking a column header " +
                "manually overrides this preference until re-enabled.");
        // Reflect the current sort state when the menu is shown
        sortByImpact.setSelected(isSortedByImpactDesc(warningsTable.getRowSorter()));
        sortByImpact.addActionListener(e -> {
            final javax.swing.RowSorter<?> sorter = warningsTable.getRowSorter();
            if (sorter == null) return;
            if (sortByImpact.isSelected()) {
                sorter.setSortKeys(List.of(new javax.swing.RowSorter.SortKey(2, javax.swing.SortOrder.DESCENDING)));
            } else {
                sorter.setSortKeys(null); // back to model order
            }
        });
        filterMenu.add(sortByImpact);
        return filterMenu;
    }

    private JPopupMenu getToolsMenu() {
        final JPopupMenu popup = new JPopupMenu();
        GuiUtils.addSeparator(popup, "Navigation:");
        popup.add(visitingZoom.zoomControls("Visiting Zoom Level", "issues"));
        GuiUtils.addSeparator(popup, "Path Tagging:");
        final JMenuItem colorMenuItem = new JMenuItem("Color Affected Paths by Issue Severity");
        colorMenuItem.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.DANGER));
        popup.add(colorMenuItem);
        colorMenuItem.addActionListener(e -> {
            final List<PlausibilityCheck.Warning> warnings = tableModel.getSelectedWarnings(warningsTable, true);
            if (warnings.isEmpty()) {
                sntui.error("No issue selected.");
                return;
            }
            for (final PlausibilityCheck.Warning warning : warnings) {
                warning.affectedPaths().forEach(p -> {
                    p.setColor(severityColor(warning.severity()));
                });
            }
            sntui.plugin.updateAllViewers();
        });
        GuiUtils.addSeparator(popup, "Seed Reviews:");

        // Review-tag actions: mark the affected paths of the selected warning(s) as + / - training examples
        final JMenu reviewMenu = new JMenu("Mark Affected Path(s) As");
        reviewMenu.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.SEEDLING));
        reviewMenu.setToolTipText("<HTML>Tag the path(s) referenced by the selected warning(s)<br>" +
                "as either positive or negative training examples.<br>" +
                "Tags use the reserved <code>cur:</code> prefix and survive<br>" +
                ".traces round-trips, so they can be filtered/exported later<br>" +
                "from the Path Manager.");
        final LinkedHashMap<String, Color> colors = GuiUtils.MenuItems.colorTagPresets();
        final JMenuItem positiveItem = new JMenuItem("Positive Example",
                IconFactory.accentIcon(colors.get("Green"), true));
        positiveItem.setToolTipText("Tag affected paths as positive (accepted) training examples.");
        positiveItem.addActionListener(e -> applyReviewTag(CurationTags::markPositive, "positive examples", true));
        reviewMenu.add(positiveItem);

        final JMenuItem negativeItem = new JMenuItem("Negative Example",
                IconFactory.accentIcon(colors.get("Red"), true));
        negativeItem.setToolTipText("Tag affected paths as negative (rejected/suspect) training examples.");
        negativeItem.addActionListener(e -> applyReviewTag(CurationTags::markNegative, "negative examples", true));
        reviewMenu.add(negativeItem);

        final JMenuItem unsureItem = new JMenuItem("Needs Follow-up Review",
                IconFactory.accentIcon(colors.get("Yellow"), true));
        unsureItem.setToolTipText("Tag affected paths as needing a second pass (\"not sure\").");
        unsureItem.addActionListener(e -> applyReviewTag(CurationTags::markUnsure, "needs-follow-up", true));
        reviewMenu.add(unsureItem);

        final JMenuItem clearReviewItem = new JMenuItem("Clear Review Status",
                IconFactory.menuIcon(IconFactory.GLYPH.TIMES));
        clearReviewItem.setToolTipText("Remove any cur:* review tag from affected paths.");
        clearReviewItem.addActionListener(e -> applyReviewTag(CurationTags::clearReview, "(review tags cleared)", false));
        reviewMenu.add(clearReviewItem);
        reviewMenu.addSeparator();
        reviewMenu.add(GuiUtils.MenuItems.openHelpURL("Help on Seed Reviews",
                "https://imagej.net/plugins/snt/curation#seed-review"));
        popup.add(reviewMenu);

        final JMenuItem showCuratedItem = new JMenuItem("Show Reviewed Paths in Path Manager",
                IconFactory.menuIcon(IconFactory.GLYPH.FILTER));
        showCuratedItem.setToolTipText("<HTML>Switches to the Path Manager and filters its list to<br>" +
                "show only paths carrying a <code>cur:*</code> review tag.");
        showCuratedItem.addActionListener(e -> showCuratedPathsInPathManager());
        popup.add(showCuratedItem);
        return popup;
    }

    /**
     * @return {@code true} if the row sorter's primary sort key targets the impact column (index 2) in descending order
     */
    private static boolean isSortedByImpactDesc(final javax.swing.RowSorter<?> sorter) {
        if (sorter == null) return false;
        final List<? extends javax.swing.RowSorter.SortKey> keys = sorter.getSortKeys();
        if (keys == null || keys.isEmpty()) return false;
        final javax.swing.RowSorter.SortKey first = keys.getFirst();
        return first.getColumn() == 2 && first.getSortOrder() == javax.swing.SortOrder.DESCENDING;
    }

    private void runOnDemandAsync() {
        if (noParametersSelected()) return;
        onDemandButton.setEnabled(false);
        sntui.showStatus("Running Full scan...", true);
        final List<Path> paths = sntui.plugin.getPathAndFillManager().getPathsFiltered();
        if (paths.isEmpty()) {
            sntui.error("There are no traced paths.");
            onDemandButton.setEnabled(true);
            return;
        }
        // Image-requiring checks: when no image is loaded, soft-disable each
        // one (rather than aborting the scan) and proceed with the rest.
        if (disableImageDependentChecksIfNoImage()) {
            onDemandButton.setEnabled(true);
            return;
        }
        final PlausibilityCheck.SignalQuality sq = monitor.getDeepCheck(PlausibilityCheck.SignalQuality.class);
        // Provide image context for checks that need it (e.g., SignalQuality)
        monitor.setImageData(sntui.plugin.getLoadedData());
        // Ensure image statistics are available for auto-threshold. If not yet
        // computed, prompt the user (same pattern as runSecondaryLayerWizard).
        if (sq != null && sq.isEnabled() && sq.getMinContrast() == PlausibilityCheck.SignalQuality.AUTO_THRESHOLD) {
            final ij.process.ImageStatistics imgStats = sntui.plugin.getStats();
            if (imgStats.min == 0 && imgStats.max == 0) {
                // min/max not yet computed. Prompt the user to compute them.
                if (sntui.plugin.invalidStatsError(false)) {
                    onDemandButton.setEnabled(true);
                    return; // user dismissed: abort scan
                }
            }
            monitor.setImageStats(imgStats.min, imgStats.max);
        }
        // Compute the resolved auto-threshold now (before scan) so we can
        // display it in the spinner after the scan completes
        final double resolvedThreshold;
        if (sq != null && sq.getMinContrast() == PlausibilityCheck.SignalQuality.AUTO_THRESHOLD) {
            final ij.process.ImageStatistics ist = sntui.plugin.getStats();
            resolvedThreshold = (ist.max > ist.min) ? (ist.max + 1.0) / (ist.min + 1.0) / 2.0 : 1.5;
        } else {
            resolvedThreshold = Double.NaN;
        }
        new SwingWorker<List<PlausibilityCheck.Warning>, Void>() {
            @Override
            protected List<PlausibilityCheck.Warning> doInBackground() {
                return monitor.runFullScan(paths);
            }

            @Override
            protected void done() {
                onDemandButton.setEnabled(true);
                // If auto-threshold was used, display the resolved value
                if (!Double.isNaN(resolvedThreshold)) {
                    signalQualitySpinner.setValue(resolvedThreshold);
                    if (sq != null) sq.setMinContrast(resolvedThreshold);
                }
                try {
                    final List<PlausibilityCheck.Warning> warnings = get();
                    if (warnings.isEmpty()) {
                        sntui.showStatus("Full scan: no issues found.", true);
                    } else {
                        sntui.showStatus(String.format("Full scan completed: %d issue(s) found", warnings.size()), true);
                    }
                } catch (final Exception ex) {
                    SNTUtils.log("Full scan failed: " + ex.getMessage());
                    sntui.showStatus("Full scan failed. See log.", true);
                }
            }
        }.execute();
    }

    private void runCalibration() {
        final File[] files = sntui.guiUtils.getReconstructionFiles(null);
        if (files == null || files.length == 0) return;

        sntui.showStatus("Calibrating thresholds...", true);
        new SwingWorker<PlausibilityCalibrator.CalibrationResult, Void>() {
            @Override
            protected PlausibilityCalibrator.CalibrationResult doInBackground() {
                final List<Tree> trees = new ArrayList<>();
                for (final File f : files) {
                    try {
                        trees.addAll(Tree.listFromFile(f.getAbsolutePath()));
                    } catch (final Exception ex) {
                        SNTUtils.log("Calibration: failed to load " + f.getName() + ": " + ex.getMessage());
                    }
                }
                if (trees.isEmpty()) {
                    return null;
                }
                final PlausibilityCalibrator calibrator = new PlausibilityCalibrator(trees);
                return calibrator.calibrate();
            }

            @Override
            protected void done() {
                try {
                    final PlausibilityCalibrator.CalibrationResult result = get();
                    if (result == null) {
                        sntui.error("None of the file(s) contained valid reconstructions.");
                        sntui.showStatus("Calibration failed.", true);
                        return;
                    }
                    SNTUtils.log("PlausibilityCalibrator:\n" + result.toTable());
                    result.applyTo(monitor);
                    syncUIFromMonitor();
                    sntui.showStatus(String.format("Calibrated from %d cell(s).", result.getTreeCount()), true);
                } catch (final Exception ex) {
                    sntui.error("Calibration failed: " + ex.getMessage());
                    sntui.showStatus("Calibration failed. See log.", true);
                }
            }
        }.execute();
    }

    /**
     * Calibrates thresholds from the given in-memory trees, enables live
     * monitoring, runs a full scan, and switches SNTUI to the Assistant tab.
     * Intended to be called programmatically after autotracing completes.
     *
     * @param trees the freshly traced trees to calibrate from
     */
    public void calibrateFromTrees(final List<Tree> trees) {
        if (trees == null || trees.isEmpty()) return;
        sntui.showStatus("Calibrating thresholds...", true);
        new SwingWorker<PlausibilityCalibrator.CalibrationResult, Void>() {
            @Override
            protected PlausibilityCalibrator.CalibrationResult doInBackground() {
                final PlausibilityCalibrator calibrator = new PlausibilityCalibrator(trees);
                return calibrator.calibrate();
            }

            @Override
            protected void done() {
                try {
                    final PlausibilityCalibrator.CalibrationResult result = get();
                    if (result == null) {
                        sntui.showStatus("Auto-calibration failed.", true);
                        return;
                    }
                    SNTUtils.log("PlausibilityCalibrator (auto):\n" + result.toTable());
                    result.applyTo(monitor);
                    syncUIFromMonitor();
                    monitor.setEnabled(true);
                    liveToggle.setSelected(true);
                    sntui.selectTab("Assistant");
                    sntui.showStatus(String.format("Calibrated from %d tree(s). Scanning...", result.getTreeCount()), true);
                    // Chain directly into a full scan: all validation guards (parameters selected, paths
                    // exist, image loaded) should pass since we just calibrated from freshly traced data
                    runOnDemandAsync();
                } catch (final Exception ex) {
                    SNTUtils.log("Auto-calibration failed: " + ex.getMessage());
                    sntui.showStatus("Auto-calibration failed. See log.", true);
                }
            }
        }.execute();
    }

    private void saveCurationFile() {
        final File dir = PlausibilityCalibrator.getCurationsDirectory(sntui.getPrefs().getWorkspaceDir());
        final File suggested = new File(dir, "my-preset." + PlausibilityCalibrator.CURATION_EXTENSION);
        final File file = sntui.guiUtils.getSaveFile("Save Curation Preset", suggested,
                PlausibilityCalibrator.CURATION_EXTENSION);
        if (file == null) return;
        try {
            PlausibilityCalibrator.save(monitor, file,
                    "Curation preset saved from SNT on " + new java.util.Date());
            sntui.showStatus("Saved: " + file.getName(), true);
            populateUserPresetEntries();
        } catch (final Exception ex) {
            SNTUtils.log("Save curation failed: " + ex.getMessage());
            sntui.error("Could not save preset. See log.");
        }
    }

    private void loadCurationFile(final File file) {
        try {
            PlausibilityCalibrator.load(file, monitor);
            syncUIFromMonitor();
            sntui.showStatus(String.format("%s loaded...", file.getName()), true);
        } catch (final Exception ex) {
            SNTUtils.log("Load curation failed: " + ex.getMessage());
            sntui.error("Could not load preset. See log.");
        }
    }

    private void populateBuiltInPresetEntries() {
        final int sepIdx = findSeparatorIndex(calibrationMenu, "Built-in Presets:");
        if (sepIdx < 0) return;
        final int nextSepIdx = findSeparatorIndex(calibrationMenu, "User Presets:");
        // Remove items between the two separators
        if (nextSepIdx > sepIdx) {
            for (int i = nextSepIdx - 1; i > sepIdx; i--) {
                calibrationMenu.remove(i);
            }
        }
        // Insert built-in preset entries
        final String[] builtIns = PlausibilityCalibrator.BUILT_IN_PRESETS;
        if (builtIns.length == 0) {
            final JMenuItem emptyItem = new JMenuItem("None Available");
            emptyItem.setEnabled(false);
            calibrationMenu.insert(emptyItem, sepIdx + 1);
        } else {
            for (int i = 0; i < builtIns.length; i++) {
                final String resourceName = builtIns[i];
                final JMenuItem item = new JMenuItem(resourceName);
                item.setToolTipText("Load built-in preset: " + resourceName);
                item.addActionListener(e -> loadBuiltInPreset(resourceName));
                calibrationMenu.insert(item, sepIdx + 1 + i);
            }
        }
    }

    private int populateUserPresetEntries() {
        final int sepIdx = findSeparatorIndex(calibrationMenu, "User Presets:");
        if (sepIdx < 0) return -1;
        // Find the "Create From Current Parameters..." item that marks the end of dynamic entries
        int createIdx = -1;
        for (int i = sepIdx + 1; i < calibrationMenu.getComponentCount(); i++) {
            if (calibrationMenu.getComponent(i) instanceof JMenuItem mi
                    && "Create From Current Parameters...".equals(mi.getText())) {
                createIdx = i;
                break;
            }
        }
        // Remove dynamic entries between separator and the create item]
        int nItemsRemoved = 0;
        if (createIdx > sepIdx + 1) {
            for (int i = createIdx - 1; i > sepIdx; i--) {
                calibrationMenu.remove(i);
                nItemsRemoved++;
            }
        }
        // Re-find createIdx after removals
        createIdx = sepIdx + 1;
        for (int i = sepIdx + 1; i < calibrationMenu.getComponentCount(); i++) {
            if (calibrationMenu.getComponent(i) instanceof JMenuItem mi
                    && "Create From Current Parameters...".equals(mi.getText())) {
                createIdx = i;
                break;
            }
        }
        // Insert user preset entries before the create item
        final File[] files = PlausibilityCalibrator.listCurationFiles(sntui.getPrefs().getWorkspaceDir());
        if (files.length == 0) {
            final JMenuItem emptyItem = new JMenuItem("No User Presets Found");
            emptyItem.setEnabled(false);
            calibrationMenu.insert(emptyItem, createIdx);
        } else {
            for (int i = 0; i < files.length; i++) {
                final File f = files[i];
                final String name = f.getName().replace("." + PlausibilityCalibrator.CURATION_EXTENSION, "");
                final JMenuItem item = new JMenuItem(name);
                item.setToolTipText("Load preset from " + f.getName());
                item.addActionListener(e -> loadCurationFile(f));
                calibrationMenu.insert(item, createIdx + i);
            }
        }
        return (files.length - nItemsRemoved);
    }

    private void loadBuiltInPreset(final String presetName) {
        try {
            PlausibilityCalibrator.loadBuiltIn(presetName, monitor);
            syncUIFromMonitor();
            sntui.showStatus(String.format("%s loaded...", presetName), true);
        } catch (final Exception ex) {
            SNTUtils.log("Load built-in preset failed: " + ex.getMessage());
            sntui.error("Could not load built-in preset. See log.");
        }
    }

    private static int findSeparatorIndex(final JPopupMenu menu, final String label) {
        for (int i = 0; i < menu.getComponentCount(); i++) {
            if (menu.getComponent(i) instanceof JLabel l && label.equals(l.getText()))
                return i;
        }
        return -1;
    }

    /**
     * Refreshes all UI controls (spinners, checkboxes, toggle) from the
     * monitor's current state. Called after loading a preset or restoring
     * a session.
     */
    public void refreshFromMonitor() {
        SwingUtilities.invokeLater(() -> {
            syncUIFromMonitor();
            liveToggle.setSelected(monitor.isEnabled());
        });
    }

    private void syncUIFromMonitor() {
        // Live checks
        final PlausibilityCheck.BranchAngle ba = monitor.getLiveCheck(PlausibilityCheck.BranchAngle.class);
        if (ba != null) {
            branchAngleMinSpinner.setValue(ba.getMinAngleDeg());
            branchAngleMaxSpinner.setValue(ba.getMaxAngleDeg());
            branchAngleMinCheckbox.setSelected(ba.isEnabled());
            branchAngleMaxCheckbox.setSelected(ba.isEnabled());
            branchAngleMinSpinner.setEnabled(ba.isEnabled());
            branchAngleMaxSpinner.setEnabled(ba.isEnabled());
        }
        final PlausibilityCheck.DirectionContinuity dc = monitor.getLiveCheck(PlausibilityCheck.DirectionContinuity.class);
        if (dc != null) {
            directionSpinner.setValue(dc.getMinAlignmentDeg());
            directionCheckbox.setSelected(dc.isEnabled());
            directionSpinner.setEnabled(dc.isEnabled());
        }
        final PlausibilityCheck.RadiusContinuity rc = monitor.getLiveCheck(PlausibilityCheck.RadiusContinuity.class);
        if (rc != null) {
            radiusSpinner.setValue(rc.getMaxRatio());
            radiusCheckbox.setSelected(rc.isEnabled());
            radiusSpinner.setEnabled(rc.isEnabled());
        }
        final PlausibilityCheck.TerminalBranchLength tbl = monitor.getLiveCheck(PlausibilityCheck.TerminalBranchLength.class);
        if (tbl != null) {
            termBranchSpinner.setValue(tbl.getMinLengthUm());
            termBranchCheckbox.setSelected(tbl.isEnabled());
            termBranchSpinner.setEnabled(tbl.isEnabled());
        }
        final PlausibilityCheck.TortuosityConsistency tc = monitor.getLiveCheck(PlausibilityCheck.TortuosityConsistency.class);
        if (tc != null) {
            tortuositySpinner.setValue(tc.getMaxContractionDiff());
            tortuosityCheckbox.setSelected(tc.isEnabled());
            tortuositySpinner.setEnabled(tc.isEnabled());
        }
        final PlausibilityCheck.ConstantRadii cr = monitor.getLiveCheck(PlausibilityCheck.ConstantRadii.class);
        if (cr != null) {
            constantRadiiCheckbox.setSelected(cr.isEnabled());
        }
        // Deep checks
        final PlausibilityCheck.Crossovers co = monitor.getDeepCheck(PlausibilityCheck.Crossovers.class);
        if (co != null) {
            overlapSpinner.setValue(co.getProximityUm());
            overlapCheckbox.setSelected(co.isEnabled());
            overlapSpinner.setEnabled(co.isEnabled());
        }
        final PlausibilityCheck.RadiusJumps rj = monitor.getDeepCheck(PlausibilityCheck.RadiusJumps.class);
        if (rj != null) {
            radiusJumpsSpinner.setValue(rj.getMaxJumpRatio());
            radiusJumpsCheckbox.setSelected(rj.isEnabled());
            radiusJumpsSpinner.setEnabled(rj.isEnabled());
        }
        final PlausibilityCheck.RadiusMonotonicity rm = monitor.getDeepCheck(PlausibilityCheck.RadiusMonotonicity.class);
        if (rm != null) {
            radiusMonoSpinner.setValue(rm.getMinIncreasingRun());
            radiusMonoCheckbox.setSelected(rm.isEnabled());
            radiusMonoSpinner.setEnabled(rm.isEnabled());
        }
        final PlausibilityCheck.InvalidRadius ir = monitor.getDeepCheck(PlausibilityCheck.InvalidRadius.class);
        if (ir != null && invalidRadiusCheckbox != null) {
            invalidRadiusCheckbox.setSelected(ir.isEnabled());
        }
        final PlausibilityCheck.SignalQuality sq =
                monitor.getDeepCheck(PlausibilityCheck.SignalQuality.class);
        if (signalQualityCheckbox != null && sq != null) {
            signalQualitySpinner.setValue(sq.getMinContrast());
            signalQualityCheckbox.setSelected(sq.isEnabled());
            signalQualitySpinner.setEnabled(sq.isEnabled());
        }
        // Refresh section header tri-states
        if (liveHeaderCheckbox != null && liveCheckboxes != null)
            updateSectionHeaderState(liveHeaderCheckbox, liveCheckboxes);
        if (onDemandHeaderCheckbox != null && onDemandCheckboxes != null)
            updateSectionHeaderState(onDemandHeaderCheckbox, onDemandCheckboxes);
    }

    /**
     * Adds a row: [checkbox | spacer | spinnerArea]. The spacer column keeps
     * all spinners aligned with the signal-quality row that has an undo
     * button in that column. {@code spinnerArea} is typically a JSpinner, but
     * may be any JComponent (for example, a JPanel packing a spinner with a
     * trailing histogram button via {@link #spinnerWithHistogram}).
     */
    private void addCheckRow(final JPanel panel, final GridBagConstraints c,
                             final JCheckBox checkbox, final JComponent spinnerArea) {
        addCheckRow(panel, c, checkbox, null, spinnerArea);
    }

    /**
     * Adds a row: [checkbox | middleComponent | spinnerArea]. Use this
     * overload when the middle column should contain a button (e.g., undo)
     * instead of a spacer.
     */
    private void addCheckRow(final JPanel panel, final GridBagConstraints c,
                             final JCheckBox checkbox, final JComponent middle,
                             final JComponent spinnerArea) {
        c.gridx = 0;
        c.weightx = 1.0;
        if (spinnerArea == null) {
            // Checkbox-only row: span all columns
            c.gridwidth = GridBagConstraints.REMAINDER;
            panel.add(checkbox, c);
            c.gridy++;
            return;
        }
        c.gridwidth = 1;
        panel.add(checkbox, c);

        {
            // Column 1: middle component or fixed-width spacer
            c.gridx = 1;
            c.weightx = 0.0;
            c.gridwidth = 1;
            panel.add(Objects.requireNonNullElseGet(middle, () -> Box.createHorizontalStrut(undoButtonWidth)), c);
            // Column 2: spinner area (spinner alone, or spinner + histogram button)
            c.gridx = 2;
            c.gridwidth = GridBagConstraints.REMAINDER;
            panel.add(spinnerArea, c);
        }
        c.gridy++;
    }

    /**
     * Packs a spinner with a trailing histogram button into a single cell. The
     * button is placed at the row's end so all spinners remain visually
     * aligned across the panel. The spinner's text-field width is also tightened
     * here (default SpinnerNumberModel columns are wider than needed for our
     * value ranges) so the histogram button doesn't squeeze the checkbox label.
     */
    private JComponent spinnerWithHistogram(final JSpinner spinner, final JButton histButton) {
        tightenSpinner(spinner);
        if (histButton == null) return spinner;
        final JPanel p = new JPanel(new BorderLayout(0, 0));
        p.setOpaque(false);
        p.add(spinner, BorderLayout.CENTER);
        p.add(histButton, BorderLayout.EAST);
        return p;
    }

    /**
     * Reduces a spinner's editor to a compact column count. All curation
     * spinners hold short numeric values (typically 3-5 characters); the
     * default of ~9 columns wastes horizontal space and pushes the checkbox
     * label into truncation.
     */
    private static void tightenSpinner(final JSpinner spinner) {
        if (spinner == null) return;
        final JComponent editor = spinner.getEditor();
        if (editor instanceof JSpinner.DefaultEditor de) {
            de.getTextField().setColumns(4);
        }
    }

    /**
     * Snapshot of the paths currently visible to the Path Manager.
     */
    private java.util.Collection<Path> currentPaths() {
        if (sntui == null || sntui.plugin == null) return java.util.Collections.emptyList();
        return sntui.plugin.getPathAndFillManager().getPathsFiltered();
    }

    /**
     * Creates a tri-state checkbox styled as a section header AND a chevron
     * toggle button that collapses/expands every other component already
     * added (or yet to be added) to {@code panel}. The chevron's collapse
     * action iterates {@code panel.getComponents()} at click time, so it
     * works regardless of when check rows are appended below the header.
     */
    private FlatTriStateCheckBox createSectionHeader(final JPanel panel,
                                                     final String text,
                                                     final GridBagConstraints c) {
        final FlatTriStateCheckBox header = new FlatTriStateCheckBox();
        header.setText(text);
        header.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");

        // Chevron toggle: collapses/expands all other children of `panel`.
        // The handler walks panel.getComponents() at click time so the set
        // of rows it controls grows dynamically as the build method adds them.
        final JButton chevron = new JButton();
        GuiUtils.Buttons.makeBorderless(chevron);
        chevron.setFocusable(false);
        chevron.setToolTipText("Collapse/expand section");
        final Color arrowColor = UIManager.getColor("Spinner.buttonArrowColor");
        final boolean[] expanded = {true};
        final Runnable applyIcon = () -> IconFactory.assignIcon(chevron,
                expanded[0] ? IconFactory.GLYPH.ANGLE_DOWN : IconFactory.GLYPH.ANGLE_RIGHT,
                arrowColor, 0.8f);
        applyIcon.run();

        // Combine tri-state + chevron into a single horizontal header row.
        // BoxLayout keeps the checkbox left-anchored and the chevron right-anchored.
        final JPanel headerRow = new JPanel();
        headerRow.setOpaque(false);
        headerRow.setLayout(new BoxLayout(headerRow, BoxLayout.LINE_AXIS));
        headerRow.add(header);
        headerRow.add(Box.createHorizontalGlue());
        headerRow.add(chevron);

        chevron.addActionListener(e -> {
            expanded[0] = !expanded[0];
            applyIcon.run();
            for (final Component child : panel.getComponents()) {
                if (child == headerRow) continue;
                child.setVisible(expanded[0]);
            }
            panel.revalidate();
            panel.repaint();
        });

        final int previousTopGap = c.insets.top;
        c.insets.top = panel.getFontMetrics(header.getFont()).getHeight();
        final int prevAnchor = c.anchor;
        final int prevFill = c.fill;
        final int prevGridwidth = c.gridwidth;
        final double prevWeightx = c.weightx;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridwidth = GridBagConstraints.REMAINDER;
        c.weightx = 1.0;
        panel.add(headerRow, c);
        c.anchor = prevAnchor;
        c.fill = prevFill;
        c.gridwidth = prevGridwidth;
        c.weightx = prevWeightx;
        c.insets.top = previousTopGap;
        return header;
    }

    /**
     * Wires a tri-state section header to its child checkboxes. Clicking the
     * header selects or deselects all children; individual child changes update
     * the header to reflect mixed state.
     */
    private void wireSectionHeader(final FlatTriStateCheckBox header,
                                   final List<JCheckBox> children) {
        // Set initial state
        updateSectionHeaderState(header, children);

        // Header click → toggle all children
        header.addActionListener(e -> {
            final FlatTriStateCheckBox.State state = header.getState();
            // Indeterminate click cycles to selected; otherwise use current state
            final boolean select = state != FlatTriStateCheckBox.State.UNSELECTED;
            for (final JCheckBox cb : children) {
                if (cb.isSelected() != select) {
                    cb.setSelected(select);
                    // Fire the checkbox's own action listeners (which update checks/spinners)
                    for (final java.awt.event.ActionListener al : cb.getActionListeners()) {
                        al.actionPerformed(new ActionEvent(cb, ActionEvent.ACTION_PERFORMED, "tristate"));
                    }
                }
            }
            // After toggling, force header to the definite state (not indeterminate)
            header.setState(select ? FlatTriStateCheckBox.State.SELECTED
                    : FlatTriStateCheckBox.State.UNSELECTED);
        });

        // Each child change → update header state
        for (final JCheckBox cb : children) {
            cb.addActionListener(e -> updateSectionHeaderState(header, children));
        }
    }

    /**
     * Returns the left indent (in pixels) for child checkboxes under a
     * section header. Uses the icon-text gap for a subtle visual nesting.
     */
    private static int sectionChildIndent(final JCheckBox header) {
        return header.getIconTextGap();
    }

    /**
     * Computes the tri-state from the selected state of the children.
     */
    private void updateSectionHeaderState(final FlatTriStateCheckBox header,
                                          final List<JCheckBox> children) {
        final long selected = children.stream().filter(AbstractButton::isSelected).count();
        if (selected == 0) {
            header.setState(FlatTriStateCheckBox.State.UNSELECTED);
        } else if (selected == children.size()) {
            header.setState(FlatTriStateCheckBox.State.SELECTED);
        } else {
            header.setState(FlatTriStateCheckBox.State.INDETERMINATE);
        }
    }

    /**
     * Wires a checkbox to its associated check and optional spinner.
     *
     * @param cb      the checkbox
     * @param spinner the spinner (may be {@code null})
     * @param check   the check to enable/disable (LiveCheck or DeepCheck)
     */
    private void wireCheckbox(final JCheckBox cb, final JSpinner spinner,
                              final Object check) {
        // Set initial enabled state
        if (spinner != null) {
            spinner.setEnabled(cb.isSelected());
            enableLiveCommit(spinner);
        }
        cb.addActionListener(e -> {
            final boolean sel = cb.isSelected();
            if (check instanceof PlausibilityCheck.LiveCheck lc) lc.setEnabled(sel);
            else if (check instanceof PlausibilityCheck.DeepCheck dc) dc.setEnabled(sel);
            if (spinner != null) spinner.setEnabled(sel);
        });
    }

    /**
     * Configures the spinner's editor so typed values commit on every valid keystroke rather than only on
     * Enter / focus loss / arrow click. Fixes the case where typing a new threshold and then clicking the
     * histogram button (or running a full scan) would otherwise use the previously-committed value.
     */
    private static void enableLiveCommit(final JSpinner spinner) {
        if (spinner.getEditor() instanceof JSpinner.DefaultEditor de) {
            final javax.swing.JFormattedTextField tf = de.getTextField();
            if (tf.getFormatter() instanceof javax.swing.text.DefaultFormatter df) {
                df.setCommitsOnValidEdit(true);
            }
        }
    }

    @Override
    public void warningsUpdated(final List<PlausibilityCheck.Warning> warnings) {
        SwingUtilities.invokeLater(() -> {
            tableModel.setWarnings(warnings);
            refreshTableHeader();
        });
    }

    /**
     * Appends warnings to the table without replacing existing ones. Used by
     * external producers that want to inject items into the warnings log alongside
     * the ones already produced by the live/deep checks. The severity filter is
     * re-applied automatically; entries whose severity is currently hidden are
     * accepted into the underlying list but stay invisible until the user
     * re-enables their severity in the toolbar.
     *
     * @param warnings entries to append; {@code null} or empty is ignored.
     */
    public void addWarnings(final List<PlausibilityCheck.Warning> warnings) {
        if (warnings == null || warnings.isEmpty()) return;
        SwingUtilities.invokeLater(() -> {
            tableModel.appendWarnings(warnings);
            refreshTableHeader();
        });
    }

    /**
     * @return the per-severity accent color used in the Assistant tab's table, filter menu, and severity icons.
     */
    public static Color severityColor(final PlausibilityCheck.Severity severity) {
        if (severity == null) return SEVERITY_INFO;
        return switch (severity) {
            case ERROR -> SEVERITY_ERROR;
            case WARNING -> SEVERITY_WARNING;
            case INFO -> SEVERITY_INFO;
        };
    }

    /**
     * Applies a review-tag action (mark positive / mark negative / clear) to every {@code Path} carried by
     * the currently-selected warnings. If nothing is selected, falls back to all warnings in the table.
     * After tagging, the affected paths are highlighted in the Path Manager and the manager view is refreshed
     * so the new tag suffix is visible immediately.
     *
     * @param mutator        the per-Path action (typically a {@link CurationTags} method reference)
     * @param descriptor     short description used in the status line ("positive examples", "negative examples", ...)
     * @param requiresIssues Whether the mutator requires the  warningsTable   to be populated
     */
    private void applyReviewTag(final java.util.function.Consumer<Path> mutator,
                                final String descriptor, final boolean requiresIssues) {

        final List<PlausibilityCheck.Warning> source = tableModel.getSelectedWarnings(warningsTable, false);
        if (source.isEmpty()) {
            if (requiresIssues) sntui.error("No issues exist.");
            return;
        }
        // Deduplicate: a single path may be referenced by several warnings
        final java.util.LinkedHashSet<Path> affected = new java.util.LinkedHashSet<>();
        for (final PlausibilityCheck.Warning w : source) {
            if (w.affectedPaths() != null) affected.addAll(w.affectedPaths());
        }
        if (affected.isEmpty()) {
            sntui.error("Selected issue(s) reference no paths.");
            return;
        }
        affected.forEach(mutator);
        // Surface what changed: highlight the affected paths and refresh the
        // Path Manager so the new tag suffix is visible on each row
        final PathManagerUI pmUI = sntui.getPathManager();
        if (pmUI != null) {
            pmUI.refreshForPaths(affected);
            pmUI.setSelectedPaths(new java.util.HashSet<>(affected), this);
        }
        sntui.plugin.setUnsavedChanges(true);
        sntui.showStatus(String.format("Tagged %,d path(s) as %s.", affected.size(), descriptor), true);
    }

    private void showCuratedPathsInPathManager() {
        final PathManagerUI pmUI = sntui.getPathManager();
        if (pmUI == null) {
            sntui.error("Path Manager is not available.");
            return;
        }
        // PathManagerUI is its own JDialog rather than a tab inside SNTUI,
        // so we bring its window forward rather than calling selectTab()
        pmUI.setVisible(true);
        pmUI.toFront();
        try {
            final List<Integer> hits = pmUI.getSearchable().findAll("{cur:");
            if (hits.isEmpty()) {
                sntui.error("No paths have been tagged with 'Positive','Negative', or 'Needs Follow-up' tags.");
            }
        } catch (final Throwable t) {
            sntui.error("Could not set Path Manager filter: " + t.getMessage());
        }
    }

    private static String getDocAnchor(final String checkName) {
        // Anchor strings here must match the auto-generated header IDs in curation.md (Jekyll/kramdown:
        // all lowercase, strip punctuation, replace spaces with hyphens).
        return switch (checkName) {
            // Live checks
            case "Branch angle" -> "#fork-angle-min-";
            case "Direction continuity" -> "#fork-direction-flow-max-change-";
            case "Radius continuity" -> "#radius-continuity-max-ratio-at-fork";
            case "Inter-fork distance" -> "#inter-fork-distance-min";
            case "Terminal path length" -> "#terminal-paths-min-length";
            case "Tortuosity consistency" -> "#tortuosity-max-mismatch-at-fork";
            case "Constant radii" -> "#uniform-thickness-flag";
            // On-demand (deep) checks
            case "Crossovers" -> "#cross-over-detection-max-proximity";
            case "Bundled paths" -> "#bundle-detection-max-angle-";
            case "Missed-fork candidate" -> "#missed-fork-candidate-max-proximity";
            case "Z-extent ratio" -> "#z-extent-min-ratio";
            case "Thickness jumps" -> "#thickness-jumps-max-ratio";
            case "Thickness inversions" -> "#thickness-inversions-min-run-length";
            case "Invalid radius" -> "#invalid-thickness-flag";
            case "Path signal quality" -> "#path-signal-quality-min-contrast";
            case "Tip signal quality" -> "#tip-signal-quality-min-contrast";
            case "Path signal quality dips" -> "#path-signal-quality-dips-min-drop";
            // Scripting-only checks (no UI but Help on Issue... still routes here)
            case "Soma distance" -> "#max-distance-from-soma-root";
            case "Boundary proximity" -> "#min-distance-from-image-boundary";
            // Externally-contributed warnings (e.g. from the Seeds tab's
            // "Send Selected Seeds to Curation Assistant" submenu).
            case "Seed Review" -> "#seed-review";
            default -> "";
        };
    }

    private void refreshTableHeader() {
        final javax.swing.table.JTableHeader header = warningsTable.getTableHeader();
        if (header != null) {
            warningsTable.getColumnModel().getColumn(1).setHeaderValue(tableModel.getColumnName(1));
            header.repaint();
        }
    }

    /**
     * Generates a summary string for the status bar: shows the most severe
     * warning, suitable for display in the QUERY_KEEP status text.
     *
     * @return the summary string, or {@code null} if no warnings exist
     */
    public String getStatusSummary() {
        final List<PlausibilityCheck.Warning> warnings = monitor.getCurrentWarnings();
        if (warnings.isEmpty()) return null;
        final PlausibilityCheck.Warning top = warnings.getFirst();
        final String prefix = switch (top.severity()) {
            case ERROR -> "\u26d4 ";
            case WARNING -> "\u26a0 ";
            case INFO -> "\u2139 ";
        };
        return prefix + top.message();
    }

    private boolean noParametersSelected() {
        return noParametersSelected(null, "");
    }

    /**
     * Soft-disables any image-dependent on-demand check when no image is loaded: unticks the corresponding checkbox
     * (and fires its listener via {@code doClick()} so the underlying {@code PlausibilityCheck}  also gets disabled),
     * then surfaces a single consolidated error naming the disabled checks. Returns {@code true} when the caller
     * should abort the scan because no enabled parameters remain after the auto-disable; {@code false} when the scan
     * can proceed (either because an image is loaded, no image-based check was enabled, or other checks remain enabled).
     */
    private boolean disableImageDependentChecksIfNoImage() {
        if (sntui.plugin.getLoadedData() != null) return false;
        final List<String> disabled = new ArrayList<>(3);
        autoDisableImageCheck(monitor.getDeepCheck(PlausibilityCheck.SignalQuality.class),
                signalQualityCheckbox, "Min. signal contrast ratio", disabled);
        autoDisableImageCheck(monitor.getDeepCheck(PlausibilityCheck.UncertainTerminal.class),
                uncertainTerminalCheckbox, "Min. tip signal contrast ratio", disabled);
        autoDisableImageCheck(monitor.getDeepCheck(PlausibilityCheck.IntensityValley.class),
                intensityValleyCheckbox, "Min. signal-quality dip", disabled);
        if (disabled.isEmpty()) return false;
        final String msg = (disabled.size() == 1)
                ? "\"" + disabled.getFirst() + "\" disabled: it requires a loaded image."
                : "The following validations were disabled because no image is loaded: " + String.join(", ", disabled) + ".";
        sntui.error(msg);
        // Re-check after auto-disabling: if every parameter is now off,
        // the caller should abort the scan rather than running with nothing.
        return noParametersSelected();
    }

    /**
     * Helper for {@link #disableImageDependentChecksIfNoImage}: if the given image-dependent check is currently
     * enabled, unticks its checkbox (firing the listener) and appends its display label to {@code tracker}.
     * Does nothing if the check is null, already disabled, or the checkbox is null/unselected.
     */
    private static void autoDisableImageCheck(final PlausibilityCheck.DeepCheck check,
                                              final JCheckBox checkbox, final String label,
                                              final List<String> tracker) {
        if (check == null || !check.isEnabled()) return;
        if (checkbox != null && checkbox.isSelected()) checkbox.doClick(0);
        tracker.add(label);
    }

    private boolean noParametersSelected(final List<JCheckBox> scope, final String category) {
        // Defensive null-handling: liveCheckboxes / onDemandCheckboxes are assigned only after the panel has been
        // built. Callers that arrive before construction (script-driven scans, programmatic triggers from external
        // commands) would otherwise NPE here
        final List<JCheckBox> live = (liveCheckboxes != null) ? liveCheckboxes : Collections.emptyList();
        final List<JCheckBox> deep = (onDemandCheckboxes != null) ? onDemandCheckboxes : Collections.emptyList();
        final List<JCheckBox> checkboxes;
        if (scope != null) {
            checkboxes = scope;
        } else {
            checkboxes = new ArrayList<>(live.size() + deep.size());
            checkboxes.addAll(live);
            checkboxes.addAll(deep);
        }
        if (checkboxes.stream().noneMatch(AbstractButton::isSelected)) {
            sntui.error(String.format("At least one %s parameter needs to be selected.", category));
            return true;
        }
        return false;
    }

    private static class WarningTableModel extends AbstractTableModel {

        private final java.util.EnumSet<PlausibilityCheck.Severity> visibleSeverities =
                java.util.EnumSet.allOf(PlausibilityCheck.Severity.class);
        private List<PlausibilityCheck.Warning> allWarnings = new ArrayList<>();
        private List<PlausibilityCheck.Warning> warnings = new ArrayList<>();

        /** never null */
        List<PlausibilityCheck.Warning> getSelectedWarnings(final JTable table, final boolean allIfNoneSelected) {
            final int[] viewRows = table.getSelectedRows();
            if ( (viewRows.length == 0 && allIfNoneSelected) || viewRows.length == warnings.size()) {
                return warnings;
            }
            final List<PlausibilityCheck.Warning> result = new ArrayList<>();
            for (int viewRow : viewRows) {
                final int modelRow = table.convertRowIndexToModel(viewRow);
                if ((modelRow >= 0 && modelRow < warnings.size()))
                    result.add(warnings.get(modelRow));
            }
            return result;
        }

        void setWarnings(final List<PlausibilityCheck.Warning> warnings) {
            this.allWarnings = new ArrayList<>(warnings);
            applyFilter();
        }

        /**
         * Appends to {@link #allWarnings} and re-applies the severity filter. Counterpart of
         * {@link #setWarnings(List)} for callers that contribute warnings incrementally
         * rather than as a fresh full snapshot.
         */
        void appendWarnings(final List<PlausibilityCheck.Warning> extra) {
            if (extra == null || extra.isEmpty()) return;
            this.allWarnings.addAll(extra);
            applyFilter();
        }

        void setSeverityVisible(final PlausibilityCheck.Severity severity, final boolean visible) {
            if (visible) visibleSeverities.add(severity);
            else visibleSeverities.remove(severity);
            applyFilter();
        }

        boolean isSeverityVisible(final PlausibilityCheck.Severity severity) {
            return visibleSeverities.contains(severity);
        }

        String placeholderLine1() {
            return "No issues listed.";
        }

        String placeholderLine2() {
            return (visibleSeverities.size() < PlausibilityCheck.Severity.values().length)
                    ? "Issues could be filtered out." : null;
        }

        private void applyFilter() {
            warnings = allWarnings.stream()
                    .filter(w -> visibleSeverities.contains(w.severity()))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return warnings.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(final int column) {
            return switch (column) {
                case 1 -> warnings.isEmpty() ? "Issues" : "Issues (" + warnings.size() + ")";
                case 2 -> ""; // header icon supplied by iconHeaderRenderer
                default -> ""; // case 0
            };
        }

        @Override
        public Class<?> getColumnClass(final int column) {
            return switch (column) {
                case 0 -> PlausibilityCheck.Severity.class;
                case 2 -> Double.class;
                default -> String.class;
            };
        }

        @Override
        public Object getValueAt(final int row, final int column) {
            if (row >= warnings.size()) return null; // empty padding row
            final PlausibilityCheck.Warning w = warnings.get(row);
            return switch (column) {
                case 0 -> w.severity();
                case 1 -> w.message();
                case 2 -> w.impact();
                default -> "";
            };
        }
    }

    /**
     * JPanel subclass that implements {@link Scrollable} so that, when placed
     * inside a {@link JScrollPane}, the panel tracks the viewport's width
     * instead of sizing itself to its preferred width. Used for the curation
     * parameter region: without this, long check labels would push the row
     * past the viewport's right edge, clipping the histogram buttons (since
     * horizontal scrolling is intentionally disabled). With this, the
     * layout reflows to whatever width the JSplitPane currently allocates,
     * keeping all right-edge widgets in view as long as content's minimum
     * widths fit.
     */
    private static class WidthTrackingPanel extends JPanel implements Scrollable {
        WidthTrackingPanel(final LayoutManager layout) {
            super(layout);
        }

        /**
         * Return a {@code height} capped to ~10 rows rather than the full
         * preferred size. The full preferred would propagate all the way up
         * (JScrollPane → JSplitPane → JTabbedPane → SNTUI) and force SNTUI's
         * outer layout to allocate a tab content area tall enough to show
         * every check row at once -- adding visible vertical slack above and
         * below the JTabbedPane. The cap matches the convention used by
         * JList / JTable (advertise a *modest* preferred viewport; the
         * scrollbar handles the rest). Users wanting to see more rows at
         * once can drag the JSplitPane divider down.
         */
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            final Dimension pref = getPreferredSize();
            final int rowHeight = Math.max(24, getFont() == null ? 24 : getFont().getSize() * 2);
            final int capHeight = rowHeight * 10;
            return new Dimension(pref.width, Math.min(pref.height, capHeight));
        }

        @Override
        public int getScrollableUnitIncrement(final Rectangle visibleRect,
                                              final int orientation, final int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(final Rectangle visibleRect,
                                               final int orientation, final int direction) {
            return 64;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private static class SeverityRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(final JTable table, final Object value,
                                                       final boolean isSelected, final boolean hasFocus,
                                                       final int row, final int column) {
            super.getTableCellRendererComponent(table, "", isSelected, hasFocus, row, column);
            setHorizontalAlignment(SwingConstants.CENTER);
            if (value instanceof PlausibilityCheck.Severity sev) {
                setIcon(IconFactory.accentIcon(severityColor(sev), true));
                setToolTipText(sev.name());
            } else {
                setIcon(null);
                setToolTipText(null);
            }
            return this;
        }
    }

    /**
     * Renders the {@link PlausibilityCheck.Warning#impact()} value as a right-aligned percentage with a small
     * horizontal bar drawn behind the text. Bar width is proportional to the impact value (clamped [0, 1]).
     * NaN is displayed as an em-dash with an explanatory tooltip.
     */
    private class ImpactRenderer extends DefaultTableCellRenderer {

        /**
         * Cached impact value used by {@link #paintComponent} to draw the bar.
         */
        private double impactValue = Double.NaN;
        private boolean rowSelected = false;

        @Override
        public Component getTableCellRendererComponent(final JTable table, final Object value,
                                                       final boolean isSelected, final boolean hasFocus,
                                                       final int row, final int column) {
            super.getTableCellRendererComponent(table, "", isSelected, hasFocus, row, column);
            setHorizontalAlignment(SwingConstants.RIGHT);
            rowSelected = isSelected;
            if (value instanceof Double d && !Double.isNaN(d)) {
                impactValue = Math.clamp(d, 0, 1);
                final double pct = impactValue * 100;
                // "<1%" branch keeps the em dash reserved for "not computed":
                // a measurable-but-tiny impact is informationally different
                // from an absent one, even if both round to 0% under %.0f.
                if (pct > 0 && pct < 0.5) {
                    setText("<1% ");
                } else {
                    setText(String.format("%.0f%% ", pct));
                }
                setToolTipText(String.format(
                        "<html>Impact: <b>%.2f%%</b> of the reconstruction's total " +
                                "length is at stake if this flag turns out to be a tracing error.",
                        pct));
            } else {
                impactValue = Double.NaN;
                setText("— "); // em-dash
                // Differentiate "metric not applicable" (NONE-kind check, e.g., ConstantRadii) from "not yet computed"
                // (live warning awaiting a Full Scan)
                setToolTipText(tooltipForUncomputedImpact(table, row));
            }
            return this;
        }

        /**
         * Resolves which em-dash explanation applies by looking up the warning at the given view row and asking
         * the monitor about its check's {@link PlausibilityCheck.ImpactKind}
         */
        private String tooltipForUncomputedImpact(final JTable table, final int viewRow) {
            try {
                final int modelRow = table.convertRowIndexToModel(viewRow);
                if (modelRow >= 0 && modelRow < tableModel.warnings.size()) {
                    final PlausibilityCheck.Warning w = tableModel.warnings.get(modelRow);
                    final PlausibilityCheck.ImpactKind kind = monitor.impactKindFor(w.checkName());
                    if (kind == PlausibilityCheck.ImpactKind.NONE) {
                        return "Impact metric not applicable for this check.";
                    }
                }
            } catch (final Exception ignored) {
                // fall through to default
            }
            return "Impact not yet computed. Click \"Run Full Scan\" to compute.";
        }

        @Override
        protected void paintComponent(final Graphics g) {
            // Draw a subtle bar behind the text so the eye gets a quick
            // visual cue without having to read the percentage.
            if (!Double.isNaN(impactValue) && impactValue > 0) {
                final int w = getWidth();
                final int h = getHeight();
                final int barW = (int) Math.round(w * impactValue);
                // Go from a 'quiet' blue (low impact) to the severity-error 'pop' red (high impact)
                final int r = (int) (SEVERITY_INFO.getRed()
                        + (SEVERITY_ERROR.getRed() - SEVERITY_INFO.getRed()) * impactValue);
                final int gC = (int) (SEVERITY_INFO.getGreen()
                        + (SEVERITY_ERROR.getGreen() - SEVERITY_INFO.getGreen()) * impactValue);
                final int b = (int) (SEVERITY_INFO.getBlue()
                        + (SEVERITY_ERROR.getBlue() - SEVERITY_INFO.getBlue()) * impactValue);
                // Translucent so text remains legible and selection highlight (when present)
                // still reads as the selection color
                final int alpha = rowSelected ? 40 : 70;
                g.setColor(new Color(r, gC, b, alpha));
                g.fillRect(0, 0, barW, h);
            }
            super.paintComponent(g);
        }
    }

}
