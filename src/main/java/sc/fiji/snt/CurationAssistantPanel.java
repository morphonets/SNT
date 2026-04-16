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

import ij.ImagePlus;
import ij.gui.ImageCanvas;
import ij.measure.Calibration;
import org.apache.commons.lang.StringUtils;
import sc.fiji.snt.analysis.PlausibilityCheck;
import sc.fiji.snt.analysis.PlausibilityMonitor;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.IconFactory;
import sc.fiji.snt.util.ImpUtils;
import sc.fiji.snt.util.PointInImage;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.util.ArrayList;
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
public class CurationAssistantPanel implements PlausibilityMonitor.WarningListener {

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
    private JCheckBox branchAngleMinCheckbox;
    private JCheckBox branchAngleMaxCheckbox;
    private JSpinner radiusSpinner;
    private JSpinner directionSpinner;
    private JSpinner branchAngleMinSpinner;
    private JSpinner branchAngleMaxSpinner;
    private JSpinner somaDistSpinner;
    private JSpinner termBranchSpinner;
    private JSpinner overlapSpinner;
    private JSpinner radiusJumpsSpinner;
    private JSpinner radiusMonoSpinner;
    // Action controls
    private JToggleButton liveToggle;
    private JButton onDemandButton;
    // Navigation
    private int visitingZoomPercentage;
    // Detachable table
    private JScrollPane tableScroll;
    private JDialog detachedDialog;


    public CurationAssistantPanel(final SNTUI sntui, final PlausibilityMonitor monitor) {
        this.sntui = sntui;
        this.monitor = monitor;
        this.tableModel = new WarningTableModel();
        this.warningsTable = new JTable(tableModel) {
            @Override
            protected void paintComponent(final Graphics g) {
                super.paintComponent(g);
                if (tableModel.warnings.isEmpty()) {
                    final Graphics2D g2 = (Graphics2D) g;
                    GuiUtils.setRenderingHints(g2);
                    g2.setColor(GuiUtils.getDisabledComponentColor());
                    final FontMetrics fm = g2.getFontMetrics();
                    final String line1 = tableModel.placeholderLine1();
                    final String line2 = tableModel.placeholderLine2();
                    final int lineH = fm.getHeight();
                    final int totalH = (line2 != null) ? lineH * 2 : lineH;
                    // Center within the visible viewport, not the full table
                    final Rectangle visible = getVisibleRect();
                    final int y1 = visible.y + (visible.height - totalH) / 2 + fm.getAscent();
                    g2.drawString(line1, visible.x + (visible.width - fm.stringWidth(line1)) / 2, y1);
                    if (line2 != null) {
                        g2.drawString(line2, visible.x + (visible.width - fm.stringWidth(line2)) / 2, y1 + lineH);
                    }
                }
            }
        };
        monitor.addWarningListener(this);
        configureTable();
        resetVisitingZoom();
    }

    /**
     * Unregisters this panel from the monitor. Should be called when the
     * panel is removed from the UI to avoid listener leaks.
     */
    public void dispose() {
        monitor.removeWarningListener(this);
        if (detachedDialog != null) {
            detachedDialog.dispose();
            detachedDialog = null;
        }
    }

    private void configureTable() {
        warningsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        warningsTable.setFillsViewportHeight(true);
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
        sevCol.setHeaderRenderer(new SeverityHeaderRenderer());
        // Message column: fill, with tooltip for truncated text
        final javax.swing.table.TableColumn msgCol = warningsTable.getColumnModel().getColumn(1);
        msgCol.setPreferredWidth(300);
        msgCol.setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(final JTable table, final Object value,
                                                           final boolean isSelected, final boolean hasFocus,
                                                           final int row, final int column) {
                super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (value instanceof String text && !text.isEmpty()) {
                    final FontMetrics fm = getFontMetrics(getFont());
                    final int colWidth = table.getColumnModel().getColumn(column).getWidth();
                    setToolTipText(fm.stringWidth(text) > colWidth - 4 ? text : null);
                } else {
                    setToolTipText(null);
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

        final JMenuItem clearItem = new JMenuItem("Clear All",
                IconFactory.menuIcon(IconFactory.GLYPH.TRASH));
        clearItem.addActionListener(_ -> {
            tableModel.setWarnings(List.of());
            refreshTableHeader();
        });
        popup.add(clearItem);

        popup.addSeparator();

        // Preferred zoom level: inline panel with spinner + reset
        final JSpinner zoomSpinner = GuiUtils.integerSpinner(
                Math.clamp(visitingZoomPercentage, 25, 3200), 25, 3200, 50, true);
        zoomSpinner.addChangeListener(_ ->
                visitingZoomPercentage = (int) zoomSpinner.getValue());
        zoomSpinner.setToolTipText(
                "Preferred zoom level (25\u2013\u20093200%) when navigating to a warning location");

        final JPanel zoomPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        zoomPanel.setOpaque(false);
        zoomPanel.add(new JLabel("Visiting Zoom (%):"));
        zoomPanel.add(zoomSpinner);
        popup.add(zoomPanel);

        // Reset zoom
        final JMenuItem resetZoomItem = new JMenuItem("Reset Zoom Level",
                IconFactory.menuIcon(IconFactory.GLYPH.UNDO));
        resetZoomItem.setToolTipText(
                "<HTML>Resets level to two <i>Zoom In [+]</i> operations above the current image zoom");
        resetZoomItem.addActionListener(_ -> {
            if (sntui.plugin.getImagePlus() == null) {
                sntui.showStatus("Current zoom unknown: No image is loaded...", true);
            } else {
                resetVisitingZoom();
                visitingZoomPercentage = Math.clamp(visitingZoomPercentage, 25, 3200);
                zoomSpinner.setValue(visitingZoomPercentage);
            }
        });
        popup.add(resetZoomItem);

        popup.addSeparator();

        // Detach / dock table
        final JMenuItem detachItem = new JMenuItem("Detach Table",
                IconFactory.menuIcon(IconFactory.GLYPH.EXTERNAL_LINK));
        detachItem.addActionListener(_ -> {
            if (detachedDialog == null) {
                detachTable();
                detachItem.setText("Dock Table");
            } else {
                dockTable();
                detachItem.setText("Detach Table");
            }
        });
        popup.add(detachItem);

        // Sync spinner and detach label whenever the popup is shown
        popup.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(final javax.swing.event.PopupMenuEvent e) {
                zoomSpinner.setValue(Math.clamp(visitingZoomPercentage, 25, 3200));
                detachItem.setText(detachedDialog == null ? "Detach Table" : "Dock Table");
            }

            @Override
            public void popupMenuWillBecomeInvisible(final javax.swing.event.PopupMenuEvent e) {
            }

            @Override
            public void popupMenuCanceled(final javax.swing.event.PopupMenuEvent e) {
            }
        });

        return popup;
    }

    private void detachTable() {
        if (detachedDialog != null || panel == null) return;
        panel.remove(tableScroll);
        panel.revalidate();
        panel.repaint();

        final Window owner = SwingUtilities.getWindowAncestor(panel);
        detachedDialog = new JDialog(owner instanceof Frame f ? f : null,
                "Issues (Curation Assistant)", false);
        detachedDialog.getRootPane().putClientProperty("Window.style", "small");
        detachedDialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        detachedDialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(final java.awt.event.WindowEvent e) {
                dockTable();
            }
        });
        detachedDialog.getContentPane().add(tableScroll, BorderLayout.CENTER);
        detachedDialog.setSize(500, 200);
        if (owner != null) detachedDialog.setLocationRelativeTo(owner);
        detachedDialog.setVisible(true);
    }

    private void dockTable() {
        if (detachedDialog == null || panel == null) return;
        detachedDialog.getContentPane().remove(tableScroll);
        detachedDialog.dispose();
        detachedDialog = null;

        // Re-add to the panel at the bottom with fill constraints
        final GridBagConstraints gbc = GuiUtils.defaultGbc();
        gbc.gridy = GridBagConstraints.RELATIVE;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        panel.add(tableScroll, gbc);
        panel.revalidate();
        panel.repaint();
    }

    private void navigateToWarning(final PlausibilityCheck.Warning warning) {
        // Select affected paths so they render in selectedColor
        final List<Path> affected = warning.affectedPaths();
        if (!affected.isEmpty()) {
            sntui.getPathManager().setSelectedPaths(new java.util.HashSet<>(affected), this);
        }
        // Navigate to the warning location
        final PointInImage location = warning.location();
        if (location == null) return;
        final ImagePlus imp = sntui.plugin.getImagePlus();
        if (imp == null) return;
        final Calibration cal = imp.getCalibration();
        final int px = (int) cal.getRawX(location.x);
        final int py = (int) cal.getRawY(location.y);
        final int pz = (int) cal.getRawZ(location.z);
        imp.setPosition(imp.getC(), pz + 1, imp.getT());
        ImpUtils.zoomTo(imp, visitingZoomPercentage / 100.0, px, py);
        sntui.plugin.setZPositionAllPanes(px, py, pz);
    }

    private void resetVisitingZoom() {
        try {
            final ImagePlus imp = sntui.plugin.getImagePlus();
            final ImageCanvas canvas = (imp == null) ? null : imp.getCanvas();
            if (canvas == null) {
                visitingZoomPercentage = 600;
                return;
            }
            final double currentMag = canvas.getMagnification();
            final double nextUp1x = ImageCanvas.getHigherZoomLevel(currentMag);
            final double nextUp2x = ImageCanvas.getHigherZoomLevel(nextUp1x);
            visitingZoomPercentage = (int) Math.round(nextUp2x * 100);
        } catch (final NullPointerException ignored) {
            visitingZoomPercentage = 600;
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
        SNTUI.InternalUtils.addSeparatorWithURL(panel, "Curation Assistant:", false, gbc);
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
        return panel;
    }

    private JPanel buildLiveParamsPanel() {
        final JPanel p = new JPanel(new GridBagLayout());
        final GridBagConstraints c = GuiUtils.defaultGbc();

        SNTUI.InternalUtils.addSeparatorWithURL(p, "Live Monitoring Parameters:", true, c);
        c.gridy++;

        // Branch angle min
        final PlausibilityCheck.BranchAngle angleCheck =
                monitor.getLiveCheck(PlausibilityCheck.BranchAngle.class);
        branchAngleMinCheckbox = new JCheckBox("Min. fork angle (\u00b0):",
                angleCheck != null && angleCheck.isEnabled());
        branchAngleMinCheckbox.setToolTipText("Warns when a child branch emerges nearly parallel to its parent");
        branchAngleMinSpinner = new JSpinner(new SpinnerNumberModel(
                angleCheck != null ? angleCheck.getMinAngleDeg() : 10.0, 0.0, 90.0, 5.0));
        branchAngleMinCheckbox.addActionListener(_ -> {
            if (angleCheck != null) angleCheck.setEnabled(
                    branchAngleMinCheckbox.isSelected() || branchAngleMaxCheckbox.isSelected());
            branchAngleMinSpinner.setEnabled(branchAngleMinCheckbox.isSelected());
        });
        branchAngleMinSpinner.addChangeListener(_ -> {
            if (angleCheck != null) angleCheck.setMinAngleDeg((Double) branchAngleMinSpinner.getValue());
        });
        addCheckRow(p, c, branchAngleMinCheckbox, branchAngleMinSpinner);

        // Branch angle max
        branchAngleMaxCheckbox = new JCheckBox("Max. fork angle (\u00b0):",
                angleCheck != null && angleCheck.isEnabled());
        branchAngleMaxCheckbox.setToolTipText("Warns when a child branch doubles back, nearly anti-parallel to its parent");
        branchAngleMaxSpinner = new JSpinner(new SpinnerNumberModel(
                angleCheck != null ? angleCheck.getMaxAngleDeg() : 170.0, 90.0, 180.0, 5.0));
        branchAngleMaxCheckbox.addActionListener(_ -> {
            if (angleCheck != null) angleCheck.setEnabled(
                    branchAngleMinCheckbox.isSelected() || branchAngleMaxCheckbox.isSelected());
            branchAngleMaxSpinner.setEnabled(branchAngleMaxCheckbox.isSelected());
        });
        branchAngleMaxSpinner.addChangeListener(_ -> {
            if (angleCheck != null) angleCheck.setMaxAngleDeg((Double) branchAngleMaxSpinner.getValue());
        });
        addCheckRow(p, c, branchAngleMaxCheckbox, branchAngleMaxSpinner);

        // Direction continuity
        final PlausibilityCheck.DirectionContinuity dirCheck =
                monitor.getLiveCheck(PlausibilityCheck.DirectionContinuity.class);
        final JCheckBox directionCheckbox = new JCheckBox("Max. direction change at fork (\u00b0):",
                dirCheck != null && dirCheck.isEnabled());
        directionCheckbox.setToolTipText("Detects possible U-turns where the child reverses the parent's trajectory");
        directionSpinner = new JSpinner(new SpinnerNumberModel(
                dirCheck != null ? dirCheck.getMinAlignmentDeg() : 30.0, 0.0, 90.0, 5.0));
        wireCheckbox(directionCheckbox, directionSpinner, dirCheck);
        directionSpinner.addChangeListener(_ -> {
            if (dirCheck != null) dirCheck.setMinAlignmentDeg((Double) directionSpinner.getValue());
        });
        addCheckRow(p, c, directionCheckbox, directionSpinner);

        // Radius continuity
        final PlausibilityCheck.RadiusContinuity radiusCheck =
                monitor.getLiveCheck(PlausibilityCheck.RadiusContinuity.class);
        final JCheckBox radiusCheckbox = new JCheckBox("Max. ratio of radius change at fork:",
                radiusCheck != null && radiusCheck.isEnabled());
        radiusCheckbox.setToolTipText("Flags forks where the child's caliber differs sharply from the parent's");
        radiusSpinner = new JSpinner(new SpinnerNumberModel(
                radiusCheck != null ? radiusCheck.getMaxRatio() : 1.5, 1.0, 10.0, 0.1));
        wireCheckbox(radiusCheckbox, radiusSpinner, radiusCheck);
        radiusSpinner.addChangeListener(_ -> {
            if (radiusCheck != null) radiusCheck.setMaxRatio((Double) radiusSpinner.getValue());
        });
        addCheckRow(p, c, radiusCheckbox, radiusSpinner);

        // Terminal branch length
        final PlausibilityCheck.TerminalBranchLength termCheck =
                monitor.getLiveCheck(PlausibilityCheck.TerminalBranchLength.class);
        final JCheckBox termBranchCheckbox = new JCheckBox("Min. length of terminal branches (\u00b5m):",
                termCheck != null && termCheck.isEnabled());
        termBranchCheckbox.setToolTipText("Catches stub branches that may be accidental clicks or tracing artifacts");
        termBranchSpinner = new JSpinner(new SpinnerNumberModel(
                termCheck != null ? termCheck.getMinLengthUm() : 2.0, 0.1, 100.0, 0.5));
        wireCheckbox(termBranchCheckbox, termBranchSpinner, termCheck);
        termBranchSpinner.addChangeListener(_ -> {
            if (termCheck != null) termCheck.setMinLengthUm((Double) termBranchSpinner.getValue());
        });
        addCheckRow(p, c, termBranchCheckbox, termBranchSpinner);

        // Soma distance
        final PlausibilityCheck.SomaDistance somaCheck =
                monitor.getLiveCheck(PlausibilityCheck.SomaDistance.class);
        final JCheckBox somaDistCheckbox = new JCheckBox("Max. distance from soma (\u00b5m):",
                somaCheck != null && somaCheck.isEnabled());
        somaDistCheckbox.setToolTipText("Flags paths that originate unexpectedly far from any soma marker");
        somaDistSpinner = new JSpinner(new SpinnerNumberModel(
                somaCheck != null ? somaCheck.getMaxDistUm() : 500.0, 10.0, 10000.0, 50.0));
        wireCheckbox(somaDistCheckbox, somaDistSpinner, somaCheck);
        somaDistSpinner.addChangeListener(_ -> {
            if (somaCheck != null) somaCheck.setMaxDistUm((Double) somaDistSpinner.getValue());
        });
        addCheckRow(p, c, somaDistCheckbox, somaDistSpinner);

        // Constant radii
        final PlausibilityCheck.ConstantRadii constCheck =
                monitor.getLiveCheck(PlausibilityCheck.ConstantRadii.class);
        final JCheckBox constantRadiiCheckbox = new JCheckBox("Flag paths with uniform radii",
                constCheck != null && constCheck.isEnabled());
        constantRadiiCheckbox.setToolTipText("Identifies paths where all nodes share the same radius, suggesting radii were not fitted");
        wireCheckbox(constantRadiiCheckbox, null, constCheck);
        addCheckRow(p, c, constantRadiiCheckbox, null);

        // Tortuosity consistency
        final PlausibilityCheck.TortuosityConsistency tortCheck =
                monitor.getLiveCheck(PlausibilityCheck.TortuosityConsistency.class);
        final JCheckBox tortuosityCheckbox = new JCheckBox("Flag paths with inconsistent tortuosity",
                tortCheck != null && tortCheck.isEnabled());
        tortuosityCheckbox.setToolTipText("Warns when a child's path sinuosity differs markedly from its parent's");
        wireCheckbox(tortuosityCheckbox, null, tortCheck);
        addCheckRow(p, c, tortuosityCheckbox, null);

        return p;
    }

    private JPanel buildOnDemandParamsPanel() {
        final JPanel p = new JPanel(new GridBagLayout());
        final GridBagConstraints c = GuiUtils.defaultGbc();

        SNTUI.InternalUtils.addSeparatorWithURL(p, "On-Demand Monitoring Parameters:", true, c);
        c.gridy++;

        // Path overlap
        final PlausibilityCheck.PathOverlap overlapCheck =
                monitor.getDeepCheck(PlausibilityCheck.PathOverlap.class);
        // On-demand check UI controls
        final JCheckBox overlapCheckbox = new JCheckBox("Max. proximity for path cross-overs (\u00b5m):",
                overlapCheck != null && overlapCheck.isEnabled());
        overlapCheckbox.setToolTipText("Detects regions where distinct paths run suspiciously close, suggesting duplicate tracing");
        overlapSpinner = new JSpinner(new SpinnerNumberModel(
                overlapCheck != null ? overlapCheck.getProximityUm() : 2.0, 0.1, 100.0, 0.5));
        wireCheckbox(overlapCheckbox, overlapSpinner, overlapCheck);
        overlapSpinner.addChangeListener(_ -> {
            if (overlapCheck != null) overlapCheck.setProximityUm((Double) overlapSpinner.getValue());
        });
        addCheckRow(p, c, overlapCheckbox, overlapSpinner);

        // Radius jumps
        final PlausibilityCheck.RadiusJumps jumpsCheck =
                monitor.getDeepCheck(PlausibilityCheck.RadiusJumps.class);
        final JCheckBox radiusJumpsCheckbox = new JCheckBox("Max. ratio of abrupt radius changes:",
                jumpsCheck != null && jumpsCheck.isEnabled());
        radiusJumpsCheckbox.setToolTipText("Finds adjacent nodes with a sudden radius jump, often from fitting errors");
        radiusJumpsSpinner = new JSpinner(new SpinnerNumberModel(
                jumpsCheck != null ? jumpsCheck.getMaxJumpRatio() : 3.0, 1.5, 20.0, 0.5));
        wireCheckbox(radiusJumpsCheckbox, radiusJumpsSpinner, jumpsCheck);
        radiusJumpsSpinner.addChangeListener(_ -> {
            if (jumpsCheck != null) jumpsCheck.setMaxJumpRatio((Double) radiusJumpsSpinner.getValue());
        });
        addCheckRow(p, c, radiusJumpsCheckbox, radiusJumpsSpinner);

        // Radius monotonicity
        final PlausibilityCheck.RadiusMonotonicity monoCheck =
                monitor.getDeepCheck(PlausibilityCheck.RadiusMonotonicity.class);
        final JCheckBox radiusMonoCheckbox = new JCheckBox("Min. run length for radius inversions:",
                monoCheck != null && monoCheck.isEnabled());
        radiusMonoCheckbox.setToolTipText("Flags sustained centripetal radius increases, which violate the expected centrifugal taper");
        radiusMonoSpinner = new JSpinner(new SpinnerNumberModel(
                monoCheck != null ? monoCheck.getMinIncreasingRun() : 10, 3, 100, 1));
        wireCheckbox(radiusMonoCheckbox, radiusMonoSpinner, monoCheck);
        radiusMonoSpinner.addChangeListener(_ -> {
            if (monoCheck != null) monoCheck.setMinIncreasingRun((Integer) radiusMonoSpinner.getValue());
        });
        addCheckRow(p, c, radiusMonoCheckbox, radiusMonoSpinner);

        return p;
    }

    private JToolBar buildToolbar() {
        final JToolBar tb = new JToolBar();
        tb.setFloatable(false);

        // Live monitoring toggle
        liveToggle = new JToggleButton();
        liveToggle.setSelected(monitor.isEnabled());
        IconFactory.assignIcon(liveToggle, IconFactory.GLYPH.HEART_CIRCLE_BOLT, IconFactory.GLYPH.HEART_PULSE, 1.1f);
        liveToggle.setToolTipText("Enable live monitoring");
        liveToggle.addActionListener(_ -> {
            final boolean on = liveToggle.isSelected();
            monitor.setEnabled(on);
        });
        tb.add(liveToggle);
        tb.addSeparator();
        // Run Full Scan button
        onDemandButton = new JButton(IconFactory.buttonIcon(IconFactory.GLYPH.STETHOSCOPE, 1.1f));
        onDemandButton.setToolTipText("Run full scan.\nScans all paths using both live and on-demand parameters");
        onDemandButton.addActionListener(_ -> runOnDemandAsync());
        tb.add(onDemandButton);
        tb.addSeparator();
        tb.add(Box.createHorizontalGlue());
        tb.addSeparator();

        // Filter button: restrict table by severity
        final JPopupMenu filterMenu = new JPopupMenu();
        GuiUtils.addSeparator(filterMenu, "Show");
        for (final PlausibilityCheck.Severity sev : PlausibilityCheck.Severity.values()) {
            final Color sevColor = switch (sev) {
                case ERROR -> SEVERITY_ERROR;
                case WARNING -> SEVERITY_WARNING;
                case INFO -> SEVERITY_INFO;
            };
            final JCheckBoxMenuItem item = new JCheckBoxMenuItem(
                    StringUtils.capitalize(sev.name().toLowerCase()), IconFactory.accentIcon(sevColor, true), tableModel.isSeverityVisible(sev));
            item.addActionListener(_ -> {
                tableModel.setSeverityVisible(sev, item.isSelected());
                refreshTableHeader();
            });
            filterMenu.add(item);
        }
        final JButton filterButton = GuiUtils.Buttons.OptionsButton(
                IconFactory.GLYPH.EYE, 1.1f, filterMenu);
        filterButton.setToolTipText("Filter warnings by severity");
        tb.add(filterButton);

        // Options button (placeholder for future presets, etc.)
        final JPopupMenu optionsMenu = new JPopupMenu();
        final JMenuItem placeholder = new JMenuItem("Options (TBD)");
        placeholder.setEnabled(false);
        optionsMenu.add(placeholder);
        final JButton optionsButton = GuiUtils.Buttons.OptionsButton(
                IconFactory.GLYPH.OPTIONS, 1.1f, optionsMenu);
        optionsButton.setToolTipText("Options");
        tb.add(optionsButton);

        return tb;
    }

    private void runOnDemandAsync() {
        onDemandButton.setEnabled(false);
        sntui.showStatus("Running Full scan\u2026", true);
        final List<Path> paths = sntui.plugin.getPathAndFillManager().getPathsFiltered();
        if (paths.isEmpty()) {
            sntui.error("There are no traced paths.");
            onDemandButton.setEnabled(true);
            return;
        }
        new SwingWorker<List<PlausibilityCheck.Warning>, Void>() {
            @Override
            protected List<PlausibilityCheck.Warning> doInBackground() {
                return monitor.runFullScan(paths);
            }

            @Override
            protected void done() {
                onDemandButton.setEnabled(true);
                try {
                    final List<PlausibilityCheck.Warning> warnings = get();
                    if (warnings.isEmpty()) {
                        sntui.showStatus("Full scan: no issues found.", true);
                    }
                } catch (final Exception ex) {
                    SNTUtils.log("Full scan failed: " + ex.getMessage());
                    sntui.showStatus("Full scan failed. See log.", true);
                }
            }
        }.execute();
    }

    private void addCheckRow(final JPanel panel, final GridBagConstraints c,
                             final JCheckBox checkbox, final JSpinner spinner) {
        c.gridx = 0;
        c.weightx = 1.0;
        if (spinner != null) {
            c.gridwidth = 1;
            panel.add(checkbox, c);
            c.gridx = 1;
            c.weightx = 0.0;
            c.gridwidth = GridBagConstraints.REMAINDER;
            //final int fs = (int) GuiUtils.uiFontSize();
            //spinner.setPreferredSize(new Dimension(fs * 4, fs + 6));
            panel.add(spinner, c);
        } else {
            c.gridwidth = GridBagConstraints.REMAINDER;
            panel.add(checkbox, c);
        }
        c.gridy++;
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
        cb.addActionListener(_ -> {
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

    private static class WarningTableModel extends AbstractTableModel {

        private static final int MIN_ROWS = 10;
        private final java.util.EnumSet<PlausibilityCheck.Severity> visibleSeverities =
                java.util.EnumSet.allOf(PlausibilityCheck.Severity.class);
        private List<PlausibilityCheck.Warning> allWarnings = new ArrayList<>();
        private List<PlausibilityCheck.Warning> warnings = new ArrayList<>();

        void setWarnings(final List<PlausibilityCheck.Warning> warnings) {
            this.allWarnings = new ArrayList<>(warnings);
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
            return Math.max(MIN_ROWS, warnings.size());
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

    /**
     * Icon-only header for the severity column (DANGER glyph).
     */
    private static class SeverityHeaderRenderer extends DefaultTableCellRenderer {
        SeverityHeaderRenderer() {
            setHorizontalAlignment(SwingConstants.CENTER);
            setIcon(IconFactory.buttonIcon(IconFactory.GLYPH.DANGER, .9f));
            setToolTipText("Severity (click to sort)");
        }

        @Override
        public Component getTableCellRendererComponent(final JTable table, final Object value,
                                                       final boolean isSelected, final boolean hasFocus,
                                                       final int row, final int column) {
            if (table != null) {
                final javax.swing.table.JTableHeader header = table.getTableHeader();
                if (header != null) {
                    setForeground(header.getForeground());
                    setBackground(header.getBackground());
                    setFont(header.getFont());
                }
            }
            setText("");
            setBorder(UIManager.getBorder("TableHeader.cellBorder"));
            return this;
        }
    }
}
