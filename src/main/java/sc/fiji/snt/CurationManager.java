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
    private JCheckBox somaDistCheckbox;
    private JCheckBox constantRadiiCheckbox;
    private JCheckBox tortuosityCheckbox;
    private JCheckBox overlapCheckbox;
    private JCheckBox radiusJumpsCheckbox;
    private JCheckBox radiusMonoCheckbox;
    private JCheckBox signalQualityCheckbox;
    // Checkbox groups for section-level toggling
    private List<JCheckBox> liveCheckboxes;
    private List<JCheckBox> onDemandCheckboxes;
    // Parameter spinners
    private JSpinner radiusSpinner;
    private JSpinner directionSpinner;
    private JSpinner branchAngleMinSpinner;
    private JSpinner branchAngleMaxSpinner;
    private JSpinner somaDistSpinner;
    private JSpinner termBranchSpinner;
    private JSpinner overlapSpinner;
    private JSpinner radiusJumpsSpinner;
    private JSpinner radiusMonoSpinner;
    private JSpinner tortuositySpinner;
    private JSpinner signalQualitySpinner;
    // Width of the undo button, used as a spacer for other spinner rows
    private static final int undoButtonWidth = GuiUtils.Buttons.undo().getPreferredSize().width;
    // Action controls
    private JToggleButton liveToggle;
    private JButton onDemandButton;
    // Navigation
    /** Preferred zoom level applied when navigating to a flagged issue. */
    private final GuiUtils.JTables.VisitingZoom visitingZoom = new GuiUtils.JTables.VisitingZoom();
    // Menus
    private JPopupMenu optionsMenu;
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

        // Copy issue description(s) to clipboard
        final JMenuItem copyItem = new JMenuItem("Copy Issue Description",
                IconFactory.menuIcon(IconFactory.GLYPH.CLIPBOARD));
        copyItem.addActionListener(e -> {
            if (warningsTable.getModel().getRowCount() < 1) {
                sntui.error("No issues exist.");
                return;
            }
            final int[] selectedRows = warningsTable.getSelectedRows();
            final List<PlausibilityCheck.Warning> toCopy;
            if (selectedRows.length == 0) {
                toCopy = tableModel.warnings; // copy all if none selected
            } else {
                toCopy = new ArrayList<>();
                for (final int viewRow : selectedRows) {
                    final int modelRow = warningsTable.convertRowIndexToModel(viewRow);
                    if (modelRow >= 0 && modelRow < tableModel.warnings.size())
                        toCopy.add(tableModel.warnings.get(modelRow));
                }
            }
            if (toCopy.isEmpty()) return;
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
        final JMenuItem explainItem = new JMenuItem("Help on Issue...",
                IconFactory.menuIcon(IconFactory.GLYPH.QUESTION));
        explainItem.addActionListener(e -> {
            final int viewRow = warningsTable.getSelectedRow();
            if (viewRow < 0) {
                sntui.error("No issue selected.");
                return;
            }
            final int modelRow = warningsTable.convertRowIndexToModel(viewRow);
            final PlausibilityCheck.Warning w = (modelRow >= 0 && modelRow < tableModel.warnings.size())
                        ? tableModel.warnings.get(modelRow) : null;
            final String anchor = (w != null) ? getDocAnchor(w.checkName()) : "";
            GuiUtils.openURL("https://imagej.net/plugins/snt/curation" + anchor);
        });
        popup.add(explainItem);

        popup.addSeparator();

        // Review-tag actions: mark the affected paths of the selected
        // warning(s) as positive / negative training examples. The
        // Path Manager view is refreshed afterward so the new tag is
        // visible without the user having to click anything.
        final JMenu reviewMenu = new JMenu("Mark Affected Path(s) As...");
        reviewMenu.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.TAG));
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

        reviewMenu.addSeparator();

        final JMenuItem clearReviewItem = new JMenuItem("Clear Review Status",
                IconFactory.menuIcon(IconFactory.GLYPH.TIMES));
        clearReviewItem.setToolTipText("Remove any cur:* review tag from affected paths.");
        clearReviewItem.addActionListener(e -> applyReviewTag(CurationTags::clearReview, "(review tags cleared)", false));
        reviewMenu.add(clearReviewItem);
        popup.add(reviewMenu);
        popup.addSeparator();
        popup.add(GuiUtils.MenuItems.openHelpURL("Help on Seed Reviews",
                "https://imagej.net/plugins/snt/curation#seed-review"));

        final JMenuItem showCuratedItem = new JMenuItem("Show Reviewed Paths in Path Manager",
                IconFactory.menuIcon(IconFactory.GLYPH.FILTER));
        showCuratedItem.setToolTipText("<HTML>Switches to the Path Manager and filters its list to<br>" +
                "show only paths carrying a <code>cur:*</code> review tag.");
        showCuratedItem.addActionListener(e -> showCuratedPathsInPathManager());
        popup.add(showCuratedItem);

        popup.addSeparator();

        final JMenuItem clearItem = new JMenuItem("Clear All Issues",
                IconFactory.menuIcon(IconFactory.GLYPH.TRASH));
        clearItem.addActionListener(e -> {
            tableModel.setWarnings(List.of());
            refreshTableHeader();
        });
        popup.add(clearItem);
        popup.addSeparator();
        popup.add(getVisitingZoomControls());
        popup.addSeparator();
        return popup;
    }

    private JMenu getVisitingZoomControls() {
        final JMenu presetsMenu = new JMenu("Visiting Zoom Level");
        presetsMenu.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.MAP_PIN));
        final ButtonGroup buttonGroup = new ButtonGroup();
        final int currentZl = visitingZoom.percentage();
        final Map<Integer, JRadioButtonMenuItem> presets = new TreeMap<>();
        for (final int zl : new int[]{50, 100, 200, 400, 600, 800, 1000}) {
            final JRadioButtonMenuItem item = new JRadioButtonMenuItem(zl + "%");
            item.setSelected(currentZl == zl);
            item.addActionListener(e -> {
                visitingZoom.setPercentage(zl);
                sntui.showStatus("Visiting zoom set to " + zl + "%", true);
            });
            buttonGroup.add(item);
            presetsMenu.add(item);
            presets.put(zl, item);
        }
        //presetsMenu.addSeparator();
        final JRadioButtonMenuItem chooseOther = new JRadioButtonMenuItem("Other...", !presets.containsKey(currentZl));
        buttonGroup.add(chooseOther);
        presetsMenu.add(chooseOther);
        chooseOther.addActionListener(e -> {
            final String suffixMsg;
            final ImagePlus imp = sntui.plugin.getImagePlus();
            if (imp != null) {
                suffixMsg = String.format(" (image default: %d%%):", visitingZoom.defaultPercentageFor(imp));
            } else {
                suffixMsg = ":";
            }
            final Integer zl = sntui.guiUtils.getInt(
                    "Preferred zoom level (%) when navigating to an issue" + suffixMsg,
                    "Visiting Zoom Level", visitingZoom.percentage(), 25, 3200);
            if (zl != null) {
                visitingZoom.setPercentage(zl);
                sntui.showStatus("Visiting zoom set to " + zl + "%", true);
                if (presets.get(zl) != null) presets.get(zl).setSelected(true);

            }
        });
        presetsMenu.addSeparator();
        final JMenuItem resetZoomItem = new JMenuItem("Reset Zoom Level", IconFactory.menuIcon(IconFactory.GLYPH.UNDO));
        resetZoomItem.setToolTipText(
                "<HTML>Resets level to two <i>Zoom In [+]</i> operations above the current image zoom");
        resetZoomItem.addActionListener(e -> {
            if (sntui.plugin.getImagePlus() == null) {
                sntui.showStatus("Current zoom unknown: No image is loaded...", true);
            } else {
                final int resetZl = visitingZoom.defaultPercentageFor(sntui.plugin.getImagePlus());
                visitingZoom.setPercentage(resetZl);
                final JRadioButtonMenuItem match = presets.get(resetZl);
                if (match != null) match.setSelected(true);
                else chooseOther.setSelected(true);
                sntui.showStatus("Visiting zoom reset to " + resetZl + "%", true);
            }
        });
        presetsMenu.add(resetZoomItem);
        // sync radio state every time the menu is about to open
        presetsMenu.addMenuListener(new javax.swing.event.MenuListener() {
            @Override
            public void menuSelected(javax.swing.event.MenuEvent e) {
                final int now = visitingZoom.percentage();
                final JRadioButtonMenuItem match = presets.get(now);
                if (match != null) match.setSelected(true);
                else chooseOther.setSelected(true);
            }

            @Override
            public void menuDeselected(javax.swing.event.MenuEvent e) {
            }

            @Override
            public void menuCanceled(javax.swing.event.MenuEvent e) {
            }
        });
        return presetsMenu;
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
                ImpUtils.zoomTo(imp, visitingZoom.fraction(), affected,
                        sc.fiji.snt.hyperpanes.MultiDThreePanes.XY_PLANE);
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
        panel.add(GuiUtils.longSmallMsg("Flags implausible morphology during tracing and editing. "
                + "Adjust thresholds below to calibrate for specific cell types.", panel), gbc);
        gbc.gridy++;

        // Live Monitoring Parameters
        gbc.weighty = 0.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(buildLiveParamsPanel(), gbc);
        gbc.gridy++;

        // On-demand Monitoring Parameters
        panel.add(buildOnDemandParamsPanel(), gbc);
        gbc.gridy++;

        // Toolbar (actions + table controls)
        gbc.insets.top = SNTUI.InternalUtils.MARGIN;
        panel.add(buildToolbar(), gbc);
        gbc.gridy++;

        // Warnings table: fill all remaining vertical space
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.insets.top = 0;
        tableScroll = new JScrollPane(warningsTable);
        tableScroll.setMinimumSize(new Dimension(0, 0)); // allow shrinking
        warningsTable.setPreferredScrollableViewportSize(null); // defer to layout
        panel.add(tableScroll, gbc);

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
            @Override public void actionPerformed(final ActionEvent e) { sntui.togglePathsChoice(); }
        });
        panel.getActionMap().put("togglePartsChoice", new AbstractAction() {
            @Override public void actionPerformed(final ActionEvent e) { sntui.togglePartsChoice(); }
        });
        panel.getActionMap().put("toggleChannelAndFrameChoice", new AbstractAction() {
            @Override public void actionPerformed(final ActionEvent e) { sntui.toggleChannelAndFrameChoice(); }
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
        final PlausibilityCheck.BranchAngle angleCheck =
                monitor.getLiveCheck(PlausibilityCheck.BranchAngle.class);
        branchAngleMinCheckbox = new JCheckBox("Min. fork angle (°):",
                angleCheck != null && angleCheck.isEnabled());
        branchAngleMinCheckbox.setToolTipText("Warns when a child branch emerges nearly parallel to its parent");
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
        addCheckRow(p, c, branchAngleMinCheckbox, branchAngleMinSpinner);

        // Branch angle max
        branchAngleMaxCheckbox = new JCheckBox("Max. fork angle (°):",
                angleCheck != null && angleCheck.isEnabled());
        branchAngleMaxCheckbox.setToolTipText("Warns when a child branch doubles back, nearly anti-parallel to its parent");
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
        addCheckRow(p, c, branchAngleMaxCheckbox, branchAngleMaxSpinner);

        // Direction continuity
        final PlausibilityCheck.DirectionContinuity dirCheck =
                monitor.getLiveCheck(PlausibilityCheck.DirectionContinuity.class);
        directionCheckbox = new JCheckBox("Max. direction change at fork (°):",
                dirCheck != null && dirCheck.isEnabled());
        directionCheckbox.setToolTipText("Detects possible U-turns where the child reverses the parent's trajectory");
        directionSpinner = new JSpinner(new SpinnerNumberModel(
                dirCheck != null ? dirCheck.getMinAlignmentDeg() : 30.0, 0.0, 90.0, 5.0));
        wireCheckbox(directionCheckbox, directionSpinner, dirCheck);
        directionSpinner.addChangeListener(e -> {
            if (dirCheck != null) dirCheck.setMinAlignmentDeg((Double) directionSpinner.getValue());
        });
        addCheckRow(p, c, directionCheckbox, directionSpinner);

        // Radius continuity
        final PlausibilityCheck.RadiusContinuity radiusCheck =
                monitor.getLiveCheck(PlausibilityCheck.RadiusContinuity.class);
        radiusCheckbox = new JCheckBox("Max. ratio of radius change at fork:",
                radiusCheck != null && radiusCheck.isEnabled());
        radiusCheckbox.setToolTipText("Flags forks where the child's caliber differs sharply from the parent's");
        radiusSpinner = new JSpinner(new SpinnerNumberModel(
                radiusCheck != null ? radiusCheck.getMaxRatio() : 1.5, 1.0, 10.0, 0.1));
        wireCheckbox(radiusCheckbox, radiusSpinner, radiusCheck);
        radiusSpinner.addChangeListener(e -> {
            if (radiusCheck != null) radiusCheck.setMaxRatio((Double) radiusSpinner.getValue());
        });
        addCheckRow(p, c, radiusCheckbox, radiusSpinner);

        // Terminal branch length
        final PlausibilityCheck.TerminalBranchLength termCheck =
                monitor.getLiveCheck(PlausibilityCheck.TerminalBranchLength.class);
        termBranchCheckbox = new JCheckBox("Min. length of terminal branches:",
                termCheck != null && termCheck.isEnabled());
        termBranchCheckbox.setToolTipText("Catches stub branches that may be accidental clicks or tracing artifacts");
        termBranchSpinner = new JSpinner(new SpinnerNumberModel(
                termCheck != null ? termCheck.getMinLengthUm() : 2.0, 0.1, 100.0, 0.5));
        wireCheckbox(termBranchCheckbox, termBranchSpinner, termCheck);
        termBranchSpinner.addChangeListener(e -> {
            if (termCheck != null) termCheck.setMinLengthUm((Double) termBranchSpinner.getValue());
        });
        addCheckRow(p, c, termBranchCheckbox, termBranchSpinner);

        // Soma distance
        final PlausibilityCheck.SomaDistance somaCheck =
                monitor.getLiveCheck(PlausibilityCheck.SomaDistance.class);
        somaDistCheckbox = new JCheckBox("Max. distance from soma:",
                somaCheck != null && somaCheck.isEnabled());
        somaDistCheckbox.setToolTipText("Flags paths that originate unexpectedly far from any soma marker");
        somaDistSpinner = new JSpinner(new SpinnerNumberModel(
                somaCheck != null ? somaCheck.getMaxDistUm() : 500.0, 10.0, 10000.0, 50.0));
        wireCheckbox(somaDistCheckbox, somaDistSpinner, somaCheck);
        somaDistSpinner.addChangeListener(e -> {
            if (somaCheck != null) somaCheck.setMaxDistUm((Double) somaDistSpinner.getValue());
        });
        addCheckRow(p, c, somaDistCheckbox, somaDistSpinner);

        // Tortuosity consistency
        final PlausibilityCheck.TortuosityConsistency tortCheck =
                monitor.getLiveCheck(PlausibilityCheck.TortuosityConsistency.class);
        tortuosityCheckbox = new JCheckBox("Max. tortuosity mismatch at fork:",
                tortCheck != null && tortCheck.isEnabled());
        tortuosityCheckbox.setToolTipText("Warns when a child's path sinuosity differs markedly from its parent's");
        tortuositySpinner = new JSpinner(new SpinnerNumberModel(
                tortCheck != null ? tortCheck.getMaxContractionDiff() : 0.3, 0.05, 1.0, 0.05));
        wireCheckbox(tortuosityCheckbox, tortuositySpinner, tortCheck);
        tortuositySpinner.addChangeListener(e -> {
            if (tortCheck != null) tortCheck.setMaxContractionDiff((Double) tortuositySpinner.getValue());
        });
        addCheckRow(p, c, tortuosityCheckbox, tortuositySpinner);

        // Constant radii
        final PlausibilityCheck.ConstantRadii constCheck =
                monitor.getLiveCheck(PlausibilityCheck.ConstantRadii.class);
        constantRadiiCheckbox = new JCheckBox("Flag paths with uniform radii",
                constCheck != null && constCheck.isEnabled());
        constantRadiiCheckbox.setToolTipText("Identifies paths where all nodes share the same radius, suggesting radii were not fitted");
        wireCheckbox(constantRadiiCheckbox, null, constCheck);
        addCheckRow(p, c, constantRadiiCheckbox, null);

        c.insets.left = savedLeft;

        // Collect and wire section-level toggling
        liveCheckboxes = List.of(branchAngleMinCheckbox, branchAngleMaxCheckbox,
                directionCheckbox, radiusCheckbox, termBranchCheckbox,
                somaDistCheckbox, tortuosityCheckbox, constantRadiiCheckbox);
        wireSectionHeader(liveHeaderCheckbox, liveCheckboxes);

        return p;
    }

    private JPanel buildOnDemandParamsPanel() {
        final JPanel p = new JPanel(new GridBagLayout());
        final GridBagConstraints c = GuiUtils.defaultGbc();

        onDemandHeaderCheckbox = createSectionHeader(p, "On-Demand Monitoring Parameters:", c);
        c.gridy++;
        final int savedLeft = c.insets.left;
        c.insets.left += sectionChildIndent(onDemandHeaderCheckbox);

        // Path overlap
        final PlausibilityCheck.PathOverlap overlapCheck = monitor.getDeepCheck(PlausibilityCheck.PathOverlap.class);
        // On-demand check UI controls
        overlapCheckbox = new JCheckBox("Max. proximity for path cross-overs:",
                overlapCheck != null && overlapCheck.isEnabled());
        overlapCheckbox.setToolTipText("Detects regions where distinct paths run suspiciously close, suggesting duplicate tracing");
        overlapSpinner = new JSpinner(new SpinnerNumberModel(
                overlapCheck != null ? overlapCheck.getProximityUm() : 2.0, 0.1, 100.0, 0.5));
        wireCheckbox(overlapCheckbox, overlapSpinner, overlapCheck);
        overlapSpinner.addChangeListener(e -> {
            if (overlapCheck != null) overlapCheck.setProximityUm((Double) overlapSpinner.getValue());
        });
        addCheckRow(p, c, overlapCheckbox, overlapSpinner);

        // Radius jumps
        final PlausibilityCheck.RadiusJumps jumpsCheck =
                monitor.getDeepCheck(PlausibilityCheck.RadiusJumps.class);
        radiusJumpsCheckbox = new JCheckBox("Max. ratio of abrupt radius changes:",
                jumpsCheck != null && jumpsCheck.isEnabled());
        radiusJumpsCheckbox.setToolTipText("Finds adjacent nodes with a sudden radius jump, often from fitting errors");
        radiusJumpsSpinner = new JSpinner(new SpinnerNumberModel(
                jumpsCheck != null ? jumpsCheck.getMaxJumpRatio() : 3.0, 1.5, 20.0, 0.5));
        wireCheckbox(radiusJumpsCheckbox, radiusJumpsSpinner, jumpsCheck);
        radiusJumpsSpinner.addChangeListener(e -> {
            if (jumpsCheck != null) jumpsCheck.setMaxJumpRatio((Double) radiusJumpsSpinner.getValue());
        });
        addCheckRow(p, c, radiusJumpsCheckbox, radiusJumpsSpinner);

        // Radius monotonicity
        final PlausibilityCheck.RadiusMonotonicity monoCheck =
                monitor.getDeepCheck(PlausibilityCheck.RadiusMonotonicity.class);
        radiusMonoCheckbox = new JCheckBox("Min. run length for radius inversions:",
                monoCheck != null && monoCheck.isEnabled());
        radiusMonoCheckbox.setToolTipText("Flags sustained centripetal radius increases, which violate the expected centrifugal taper");
        radiusMonoSpinner = new JSpinner(new SpinnerNumberModel(
                monoCheck != null ? monoCheck.getMinIncreasingRun() : 10, 3, 100, 1));
        wireCheckbox(radiusMonoCheckbox, radiusMonoSpinner, monoCheck);
        radiusMonoSpinner.addChangeListener(e -> {
            if (monoCheck != null) monoCheck.setMinIncreasingRun((Integer) radiusMonoSpinner.getValue());
        });
        addCheckRow(p, c, radiusMonoCheckbox, radiusMonoSpinner);

        // Image signal quality
        final PlausibilityCheck.SignalQuality signalCheck =
                monitor.getDeepCheck(PlausibilityCheck.SignalQuality.class);
        signalQualityCheckbox = new JCheckBox("Min. signal contrast ratio:",
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
        addCheckRow(p, c, signalQualityCheckbox, sqUndoBtn, signalQualitySpinner);

        c.insets.left = savedLeft;

        // Collect and wire section-level toggling
        onDemandCheckboxes = List.of(overlapCheckbox, radiusJumpsCheckbox,
                radiusMonoCheckbox, signalQualityCheckbox);
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
        final JPopupMenu filterMenu = new JPopupMenu();
        GuiUtils.addSeparator(filterMenu, "Show:");
        for (final PlausibilityCheck.Severity sev : PlausibilityCheck.Severity.values()) {
            final Color sevColor = switch (sev) {
                case ERROR -> SEVERITY_ERROR;
                case WARNING -> SEVERITY_WARNING;
                case INFO -> SEVERITY_INFO;
            };
            final String sevLabel = switch (sev) {
                case ERROR -> "Errors";
                case WARNING -> "Warnings";
                case INFO -> "Informational Notes";
            };
            final JCheckBoxMenuItem item = new JCheckBoxMenuItem(sevLabel,
                    IconFactory.accentIcon(sevColor, true), tableModel.isSeverityVisible(sev));
            item.addActionListener(e -> {
                tableModel.setSeverityVisible(sev, item.isSelected());
                refreshTableHeader();
            });
            filterMenu.add(item);
        }
        final JButton filterButton = GuiUtils.Buttons.OptionsButton(
                IconFactory.GLYPH.EYE, 1.1f, filterMenu);
        filterButton.setToolTipText("Filter warnings by severity");
        tb.add(filterButton);
        tb.addSeparator();

        // Options button
        optionsMenu = new JPopupMenu();
        GuiUtils.addSeparator(optionsMenu, "Auto-tuning:");
        final JMenuItem calibrateItem = new JMenuItem("Calibrate Thresholds from Traced Cells...");
        calibrateItem.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.CALCULATOR));
        calibrateItem.setToolTipText("Infer parameter thresholds from the statistics of existing reconstructions");
        calibrateItem.addActionListener(e -> runCalibration());
        optionsMenu.add(calibrateItem);
        GuiUtils.addSeparator(optionsMenu, "Built-in Presets:");
        populateBuiltInPresetEntries();
        GuiUtils.addSeparator(optionsMenu, "User Presets:");
        populateUserPresetEntries();
        final JMenuItem saveItem = new JMenuItem("Create From Current Parameters...");
        saveItem.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.PLUS));
        saveItem.setToolTipText("Save current thresholds and enabled states to a .curation file");
        saveItem.addActionListener(e -> saveCurationFile());
        optionsMenu.add(saveItem);
        optionsMenu.addSeparator();
        final JMenuItem refreshItem = new JMenuItem("Reload User Presets");
        refreshItem.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.REDO));
        refreshItem.addActionListener(e -> {
            final int changedItems = populateUserPresetEntries();
            sntui.showStatus(
                    (changedItems > 0) ? changedItems + " new item(s) loaded." : "No new presets detected.",
                    true);
        });
        optionsMenu.add(refreshItem);
        final JMenuItem openDirItem = new JMenuItem("Reveal User Presets Directory");
        openDirItem.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.OPEN_FOLDER));
        openDirItem.addActionListener(e -> {
            try {
                FileChooser.reveal(PlausibilityCalibrator.getCurationsDirectory(sntui.getPrefs().getWorkspaceDir()));
            } catch (final Exception ex) {
                SNTUtils.log("Could not open directory: " + ex.getMessage());
            }
        });
        optionsMenu.add(openDirItem);
        final JButton optionsButton = GuiUtils.Buttons.OptionsButton(
                IconFactory.GLYPH.OPTIONS, 1.1f, optionsMenu);
        optionsButton.setToolTipText("Options");
        tb.add(optionsButton);

        return tb;
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
        // Signal quality requires an image. Abort early if selected but no image.
        final PlausibilityCheck.SignalQuality sq =
                monitor.getDeepCheck(PlausibilityCheck.SignalQuality.class);
        if (sq != null && sq.isEnabled() && sntui.plugin.getLoadedData() == null) {
            sntui.error("\"Min. signal contrast ratio\" requires valid image data to be loaded.");
            onDemandButton.setEnabled(true);
            return;
        }
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
        final int sepIdx = findSeparatorIndex(optionsMenu, "Built-in Presets:");
        if (sepIdx < 0) return;
        final int nextSepIdx = findSeparatorIndex(optionsMenu, "User Presets:");
        // Remove items between the two separators
        if (nextSepIdx > sepIdx) {
            for (int i = nextSepIdx - 1; i > sepIdx; i--) {
                optionsMenu.remove(i);
            }
        }
        // Insert built-in preset entries
        final String[] builtIns = PlausibilityCalibrator.BUILT_IN_PRESETS;
        if (builtIns.length == 0) {
            final JMenuItem emptyItem = new JMenuItem("None Available");
            emptyItem.setEnabled(false);
            optionsMenu.insert(emptyItem, sepIdx + 1);
        } else {
            for (int i = 0; i < builtIns.length; i++) {
                final String resourceName = builtIns[i];
                final JMenuItem item = new JMenuItem(resourceName);
                item.setToolTipText("Load built-in preset: " + resourceName);
                item.addActionListener(e -> loadBuiltInPreset(resourceName));
                optionsMenu.insert(item, sepIdx + 1 + i);
            }
        }
    }

    private int populateUserPresetEntries() {
        final int sepIdx = findSeparatorIndex(optionsMenu, "User Presets:");
        if (sepIdx < 0) return -1;
        // Find the "Create From Current Parameters..." item that marks the end of dynamic entries
        int createIdx = -1;
        for (int i = sepIdx + 1; i < optionsMenu.getComponentCount(); i++) {
            if (optionsMenu.getComponent(i) instanceof JMenuItem mi
                    && "Create From Current Parameters...".equals(mi.getText())) {
                createIdx = i;
                break;
            }
        }
        // Remove dynamic entries between separator and the create item]
        int nItemsRemoved = 0;
        if (createIdx > sepIdx + 1) {
            for (int i = createIdx - 1; i > sepIdx; i--) {
                optionsMenu.remove(i);
                nItemsRemoved++;
            }
        }
        // Re-find createIdx after removals
        createIdx = sepIdx + 1;
        for (int i = sepIdx + 1; i < optionsMenu.getComponentCount(); i++) {
            if (optionsMenu.getComponent(i) instanceof JMenuItem mi
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
            optionsMenu.insert(emptyItem, createIdx);
        } else {
            for (int i = 0; i < files.length; i++) {
                final File f = files[i];
                final String name = f.getName().replace("." + PlausibilityCalibrator.CURATION_EXTENSION, "");
                final JMenuItem item = new JMenuItem(name);
                item.setToolTipText("Load preset from " + f.getName());
                item.addActionListener(e -> loadCurationFile(f));
                optionsMenu.insert(item, createIdx + i);
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
        final PlausibilityCheck.SomaDistance sd = monitor.getLiveCheck(PlausibilityCheck.SomaDistance.class);
        if (sd != null) {
            somaDistSpinner.setValue(sd.getMaxDistUm());
            somaDistCheckbox.setSelected(sd.isEnabled());
            somaDistSpinner.setEnabled(sd.isEnabled());
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
        final PlausibilityCheck.PathOverlap po = monitor.getDeepCheck(PlausibilityCheck.PathOverlap.class);
        if (po != null) {
            overlapSpinner.setValue(po.getProximityUm());
            overlapCheckbox.setSelected(po.isEnabled());
            overlapSpinner.setEnabled(po.isEnabled());
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
     * Adds a row: [checkbox | spacer | spinner]. The spacer column keeps all
     * spinners aligned with the signal-quality row that has an undo button
     * in that column.
     */
    private void addCheckRow(final JPanel panel, final GridBagConstraints c,
                             final JCheckBox checkbox, final JSpinner spinner) {
        addCheckRow(panel, c, checkbox, null, spinner);
    }

    /**
     * Adds a row: [checkbox | middleComponent | spinner]. Use this overload
     * when the middle column should contain a button (e.g., undo) instead
     * of a spacer.
     */
    private void addCheckRow(final JPanel panel, final GridBagConstraints c,
                             final JCheckBox checkbox, final JComponent middle,
                             final JSpinner spinner) {
        c.gridx = 0;
        c.weightx = 1.0;
        if (spinner == null) {
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
            // Column 2: spinner
            c.gridx = 2;
            c.gridwidth = GridBagConstraints.REMAINDER;
            panel.add(spinner, c);
        }
        c.gridy++;
    }

    /**
     * Creates a tri-state checkbox styled as a section header (matching the
     * previous {@code addSeparator} look) and adds it to the panel.
     */
    private FlatTriStateCheckBox createSectionHeader(final JPanel panel,
                                                     final String text,
                                                     final GridBagConstraints c) {
        final FlatTriStateCheckBox header = new FlatTriStateCheckBox();
        header.setText(text);
        header.putClientProperty(FlatClientProperties.STYLE_CLASS, "small");
        final int previousTopGap = c.insets.top;
        c.insets.top = panel.getFontMetrics(header.getFont()).getHeight();
        final int prevAnchor = c.anchor;
        final int prevFill = c.fill;
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.NONE;
        panel.add(header, c);
        c.anchor = prevAnchor;
        c.fill = prevFill;
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
        }
        cb.addActionListener(e -> {
            final boolean sel = cb.isSelected();
            if (check instanceof PlausibilityCheck.LiveCheck lc) lc.setEnabled(sel);
            else if (check instanceof PlausibilityCheck.DeepCheck dc) dc.setEnabled(sel);
            if (spinner != null) spinner.setEnabled(sel);
        });
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
        if (requiresIssues && warningsTable.getModel().getRowCount() < 1) {
            sntui.error("No issues exist.");
            return;
        }
        final int[] selectedRows = warningsTable.getSelectedRows();
        final List<PlausibilityCheck.Warning> source;
        if (selectedRows.length == 0) {
            source = tableModel.warnings;
        } else {
            source = new ArrayList<>(selectedRows.length);
            for (final int viewRow : selectedRows) {
                final int modelRow = warningsTable.convertRowIndexToModel(viewRow);
                if (modelRow >= 0 && modelRow < tableModel.warnings.size())
                    source.add(tableModel.warnings.get(modelRow));
            }
        }
        if (source.isEmpty()) return;
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
        sntui.showStatus(String.format("Tagged %,d path(s) as %s.",
                affected.size(), descriptor), true);
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
        return switch (checkName) {
            case "Branch angle" -> "#min-fork-angle-";
            case "Direction continuity" -> "#max-direction-change-at-fork-";
            case "Radius continuity" -> "#max-ratio-of-radius-change-at-fork";
            case "Terminal branch length" -> "#min-length-of-terminal-branches";
            case "Soma distance" -> "#max-distance-from-soma";
            case "Tortuosity consistency" -> "#max-tortuosity-mismatch-at-fork";
            case "Constant radii" -> "#flag-paths-with-uniform-radii";
            case "Path overlap" -> "#max-proximity-for-path-cross-overs";
            case "Radius jumps" -> "#max-ratio-of-abrupt-radius-changes";
            case "Radius monotonicity" -> "#min-run-length-for-radius-inversions";
            case "Image signal quality" -> "#min-signal-contrast-ratio";
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

    private boolean noParametersSelected(final List<JCheckBox> scope, final String category) {
        final List<JCheckBox> checkboxes = (scope != null) ? scope
                : new ArrayList<>(liveCheckboxes.size() + onDemandCheckboxes.size());
        if (scope == null) {
            checkboxes.addAll(liveCheckboxes);
            checkboxes.addAll(onDemandCheckboxes);
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
            return 2;
        }

        @Override
        public String getColumnName(final int column) {
            return switch (column) {
                case 0 -> "";
                case 1 -> warnings.isEmpty() ? "Issues" : "Issues (" + warnings.size() + ")";
                default -> "";
            };
        }

        @Override
        public Class<?> getColumnClass(final int column) {
            return switch (column) {
                case 0 -> PlausibilityCheck.Severity.class;
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
                default -> "";
            };
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
                final Color c = switch (sev) {
                    case ERROR -> SEVERITY_ERROR;
                    case WARNING -> SEVERITY_WARNING;
                    case INFO -> SEVERITY_INFO;
                };
                setIcon(IconFactory.accentIcon(c, true));
                setToolTipText(sev.name());
            } else {
                setIcon(null);
                setToolTipText(null);
            }
            return this;
        }
    }

}
