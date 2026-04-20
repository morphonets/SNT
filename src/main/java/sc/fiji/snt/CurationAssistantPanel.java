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
import sc.fiji.snt.analysis.curation.PlausibilityCalibrator;
import sc.fiji.snt.analysis.curation.PlausibilityCheck;
import sc.fiji.snt.analysis.curation.PlausibilityMonitor;
import sc.fiji.snt.gui.FileChooser;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.IconFactory;
import sc.fiji.snt.util.ImpUtils;
import sc.fiji.snt.util.PointInImage;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.io.File;
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
    // Action controls
    private JToggleButton liveToggle;
    private JButton onDemandButton;
    // Navigation
    private int visitingZoomPercentage;
    // Menus
    private JPopupMenu optionsMenu;
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
        clearItem.addActionListener(e -> {
            tableModel.setWarnings(List.of());
            refreshTableHeader();
        });
        popup.add(clearItem);

        popup.addSeparator();

        // Preferred zoom level: inline panel with spinner + reset
        final JSpinner zoomSpinner = GuiUtils.integerSpinner(
                Math.clamp(visitingZoomPercentage, 25, 3200), 25, 3200, 50, true);
        zoomSpinner.addChangeListener(e ->
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
        resetZoomItem.addActionListener(e -> {
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
        detachItem.addActionListener(e -> {
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

        GuiUtils.addSeparator(p, "Live Monitoring Parameters:", true, c);
        c.gridy++;

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
            final boolean on = liveToggle.isSelected();
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
        onDemandButton.setEnabled(false);
        sntui.showStatus("Running Full scan...", true);
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
                    SNTUtils.log("Calibration failed: " + ex.getMessage());
                    sntui.showStatus("Calibration failed. See log.", true);
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
            sntui.showStatus("Loaded: " + file.getName(), true);
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
                final String displayName = StringUtils.capitalize(resourceName);
                final JMenuItem item = new JMenuItem(displayName);
                item.setToolTipText("Load built-in preset: " + displayName);
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
            sntui.showStatus("Loaded built-in preset: " + presetName, true);
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
