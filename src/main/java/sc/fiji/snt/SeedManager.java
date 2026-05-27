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

import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;
import net.imglib2.display.ColorTable;
import org.scijava.command.CommandService;
import sc.fiji.snt.gui.FileDrop;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.IconFactory;
import sc.fiji.snt.gui.cmds.ImportSeedPointsCmd;
import sc.fiji.snt.gui.cmds.LoadSeedsFromLabelsImageCmd;
import sc.fiji.snt.gui.cmds.LoadSeedsFromROIsCmd;
import sc.fiji.snt.seed.*;
import sc.fiji.snt.util.ImpUtils;

import javax.swing.*;
import javax.swing.event.ChangeListener;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ItemEvent;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;

/**
 * Reusable JPanel that controls a {@link SeedOverlay}: visibility, LUT,
 * confidence range, transparency, counters, CSV import/export/clear, plus
 * an inline JTable for browsing and editing individual seeds. Used as the
 * content of SNTUI's "Seeds" tab. Multiple instances can coexist on the
 * same {@link SeedOverlay}; each registers its own listener and synchronizes
 * via the overlay (the data model is the source of truth).
 * <p>
 * Caller must invoke {@link #dispose()} when the panel is removed from its
 * parent so the overlay and table-model listeners are unregistered.
 *
 * @author Tiago Ferreira
 * @see SeedOverlay
 */
public class SeedManager extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final String MSG_SYNOPSIS = """
                Seeds are candidate 3D points (e.g. deep-learning detections, ROI centroids, segmentation masks) \
                used as anchors by autotracers and other commands. Filter by confidence, color by attribute \
                (confidence, index, source, or type), and inspect/edit/delete seeds in the table below.
                
                Double-click a row to navigate to its location; Alt+Click on the canvas (while SNT is paused) \
                to edit the nearest seed.""";

    private final SNT snt;
    private final SNTUI sntui;
    private final SeedOverlay overlay;
    private final SeedOverlay.SeedOverlayListener overlayListener;
    private final Action toggleVisibilityAction;
    private JLabel countLabel;
    private LutRamp lutRamp;
    private JComboBox<SeedOverlay.ColorMode> colorModeCombo;
    private JSlider lowerSlider;
    private JSlider upperSlider;
    private JSpinner lowerSpinner;
    private JSpinner upperSpinner;
    private JTable seedTable;
    private SeedTableModel tableModel;
    private JScrollPane tableScroll;
    private GridBagConstraints tableScrollGbc;
    private GuiUtils.JTables.DetachableTable tableDetacher;
    /**
     * Zoom level applied when navigating to a seed by double-clicking a table
     * row. Initialised to roughly two zoom steps above the current canvas
     * magnification (see {@link GuiUtils.JTables.VisitingZoom#resetFor}).
     */
    private final GuiUtils.JTables.VisitingZoom visitingZoom = new GuiUtils.JTables.VisitingZoom();

    /**
     * Guards against feedback loops between widget listeners and overlay fires.
     */
    private boolean updatingFromOverlay;

    /**
     * Creates a panel bound to the given SNT instance's {@link SeedOverlay}.
     * Registers an overlay listener immediately; call {@link #dispose()} to
     * unregister.
     */
    public SeedManager(final SNTUI sntui) {
        super();
        this.sntui = sntui;
        this.snt = sntui.plugin;
        this.overlay = snt.getSeedOverlay();
        this.overlayListener = source -> SwingUtilities.invokeLater(this::refreshFromOverlay);
        toggleVisibilityAction = toggleVisibilityAction();
        visitingZoom.resetFor(snt.getImagePlus());
        buildLayout();
        overlay.addListener(overlayListener);
        refreshFromOverlay();
        // Apply default column widths once the layout has been realized
        SwingUtilities.invokeLater(this::resizeColumns);
    }

    /**
     * Unregisters the overlay listener and the table-model listener, and
     * closes the detached-table dialog if it's open. Idempotent.
     * <p>
     * Must be called when this panel is removed from its parent:
     * {@code overlay.addListener(overlayListener)} pins this panel to the
     * overlay's lifetime, so skipping {@code dispose()} leaks the panel
     * and its table model until SNT shuts down.
     */
    public void dispose() {
        overlay.removeListener(overlayListener);
        if (tableModel != null) tableModel.dispose();
        if (tableDetacher != null && tableDetacher.isDetached()) {
            tableDetacher.dock();
        }
    }

    private void buildLayout() {
        setLayout(new GridBagLayout());
        // We want each top-level row to span the full panel width
        final GridBagConstraints gbc = GuiUtils.defaultGbc();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1;
        gbc.gridx = 0;
        gbc.gridy = 0;

        // Seeds section: heading + short synopsis
        SNTUI.InternalUtils.addSeparatorWithURL(this, "Seeds:", false, gbc);
        gbc.gridy++;
        final JTextArea synopsis = GuiUtils.longSmallMsg(MSG_SYNOPSIS, this);
        add(synopsis, gbc);
        gbc.gridy++;

        // Display section
        GuiUtils.addSeparator(this, "Display:", true, gbc);
        gbc.gridy++;
        add(buildDisplayRow(), gbc);
        gbc.gridy++;
        GuiUtils.addSeparator(this, "Confidence Filtering:", true, gbc);
        gbc.gridy++;

        add(buildSliderRow("Lower:", true), gbc);
        gbc.gridy++;
        add(buildSliderRow("Upper:", false), gbc);
        gbc.gridy++;
        // Wrap countLabel in a toolbar-styled panel so its left/right margins
        // line up with the slider rows above (and the display toolbar above
        // those). Adding the bare JLabel here would float against the panel's
        // raw insets.
        countLabel = new JLabel();
        final JPanel countRow = toolbarStyledRow();
        countRow.add(countLabel);
        add(countRow, gbc);
        gbc.gridy++;

        // Seeds (table) section
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weighty = 1;
        tableScroll = buildTablePane();
        // Capture the constraints used here so redockTableScroll() can restore
        // the pane to the same grid cell after a detach (RELATIVE would not
        // work because the bottom toolbar sits below this row).
        tableScrollGbc = (GridBagConstraints) gbc.clone();
        add(tableScroll, tableScrollGbc);
        gbc.gridy++;

        // Action toolbar
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weighty = 0;
        add(bottomToolbar(), gbc);
    }

    private JToolBar buildDisplayRow() {
        final JToolBar p = new JToolBar();
        p.setFloatable(false);

        final JToggleButton visibilityToggle = GuiUtils.Buttons.toolbarToggleButton(
                toggleVisibilityAction,
                "Show/hide seeds",
                IconFactory.GLYPH.EYE, IconFactory.GLYPH.EYE_SLASH);
        p.add(visibilityToggle);

        p.add(Box.createHorizontalGlue());
        lutRamp = new LutRamp(overlay::getColorTable, overlay::getLowConfidence, overlay::getHighConfidence);
        p.add(lutRamp);
        p.add(GuiUtils.Buttons.OptionsButton(IconFactory.GLYPH.COLOR2, 1f, colorTablePopupMenu()));
        colorModeCombo = buildColorModeCombo();
        p.add(colorModeCombo);
        return p;
    }

    private JPopupMenu colorTablePopupMenu() {
        final JPopupMenu popupMenu = new JPopupMenu();
        GuiUtils.addSeparator(popupMenu, "Color Table:");
        final ButtonGroup bg = new ButtonGroup();
        final String[] lutChoices = {"Distinct", "Fire", "Ice", "Plasma", "Red-Green", "Spectrum", "Viridis"};
        for (final String choice : lutChoices) {
            final JCheckBoxMenuItem menuItem = new JCheckBoxMenuItem(choice);
            bg.add(menuItem);
            popupMenu.add(menuItem);
            menuItem.addItemListener(e -> {
                if (e.getStateChange() == ItemEvent.SELECTED) overlay.setColorTable(choice);
            });
        }
        popupMenu.addSeparator();
        popupMenu.add(getTransparencyMenuItem());
        return popupMenu;
    }

    private JMenuItem getTransparencyMenuItem() {
        final JMenuItem menuItem = new JMenuItem("Transparency...");
        menuItem.setToolTipText("Adjust seed transparency");
        menuItem.addActionListener(e -> {
            // overlay.getTransparency() is an OPACITY multiplier (1.0 = fully opaque),
            // same misnomer as Bvv.RenderingOptions. The prompt asks for transparency
            // (0% = opaque, 100% = invisible)
            final int defaultPct = (int) Math.round((1.0 - overlay.getTransparency()) * 100);
            final Integer pct = sntui.guiUtils.getPercentage("Seed-overlay transparency (%):",
                    "Transparency", defaultPct);
            if (pct == null) return;
            overlay.setTransparency((100 - pct) / 100.0);
        });
        return menuItem;
    }

    /**
     * Compact dropdown that switches between {@link SeedOverlay.ColorMode}
     * values. The custom renderer prettifies the enum names so the user
     * sees {@code Confidence / Index / Type / Source} instead of the
     * uppercase constants.
     */
    private JComboBox<SeedOverlay.ColorMode> buildColorModeCombo() {
        final JComboBox<SeedOverlay.ColorMode> combo =
                new JComboBox<>(SeedOverlay.ColorMode.values());
        combo.setSelectedItem(overlay.getColorMode());
        combo.setToolTipText("<HTML>How seeds are colored:" +
                "<dl>" +
                "<dt><b>Confidence</b></dt><dd>LUT sampled by the seed's confidence within the filter window.</dd>" +
                "<dt><b>Index</b></dt><dd>One distinct LUT slot per seed (e.g. for Distinct/Glasbey).</dd>" +
                "<dt><b>Type</b></dt><dd>Seeds sharing the same <i>type</i> share a slot.</dd>" +
                "<dt><b>Source</b></dt><dd>Seeds sharing the same <i>source</i> share a slot.</dd>" +
                "</dl>The confidence range still gates <i>visibility</i> in every mode.");
        combo.setRenderer(new DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;
            @Override
            public Component getListCellRendererComponent(final JList<?> list, final Object value,
                                                          final int index, final boolean isSelected,
                                                          final boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof SeedOverlay.ColorMode m) {
                    setText(switch (m) {
                        case CONFIDENCE -> "Confidence";
                        case INDEX -> "Index";
                        case TYPE -> "Type";
                        case SOURCE -> "Source";
                    });
                }
                return this;
            }
        });
        // Prevent the toolbar from stretching the combo to its full width.
        combo.setMaximumSize(combo.getPreferredSize());
        combo.addActionListener(e -> {
            if (updatingFromOverlay) return;
            overlay.setColorMode((SeedOverlay.ColorMode) combo.getSelectedItem());
        });
        return combo;
    }

    /**
     * Returns a {@link JPanel} wearing the L&amp;F-defined {@code ToolBar.border}
     * and a horizontal {@link BoxLayout}, so non-icon rows line up vertically
     * with adjacent {@link JToolBar}s
     */
    private static JPanel toolbarStyledRow() {
        final JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.LINE_AXIS));
        javax.swing.border.Border border = UIManager.getBorder("ToolBar.border");
        if (border == null) border = BorderFactory.createEmptyBorder(0, SNTUI.InternalUtils.MARGIN,
                0, SNTUI.InternalUtils.MARGIN);
        p.setBorder(border);
        p.setOpaque(false);
        return p;
    }

    /**
     * Either of the two confidence-limit slider rows. {@code lower=true} drives
     * the lower bound; {@code lower=false} drives the upper bound. Each row
     * keeps the bounds ordered ({@code low ≤ high}) by pushing the other slider
     * if the user crosses it. A {@link JSpinner} replaces the previous read-only
     * value label so the user can type/scroll an exact value (resolution 0.01,
     * matching the slider's integer step).
     */
    private JPanel buildSliderRow(final String labelText, final boolean lower) {
        final JPanel tb = toolbarStyledRow();
        final JLabel label = new JLabel(labelText);
        label.setToolTipText(lower
                ? "Hide seeds with confidence below this value"
                : "Hide seeds with confidence above this value");
        tb.add(label);

        final double initialValue = lower ? overlay.getLowConfidence() : overlay.getHighConfidence();
        final int initialPct = (int) Math.round(initialValue * 100);
        final JSlider slider = new JSlider(0, 100, initialPct);
        slider.setToolTipText(label.getToolTipText());
        final JSpinner spinner = GuiUtils.doubleSpinner(initialValue, 0.0, 1.0, 0.01, 2);
        spinner.setToolTipText(label.getToolTipText());

        // Slider -> overlay (canonical writer for confidence range). Also pushes
        // the partner slider if the user crosses it, and syncs our spinner.
        slider.addChangeListener(e -> {
            if (updatingFromOverlay) return;
            if (lower) {
                if (slider.getValue() > upperSlider.getValue()) {
                    upperSlider.setValue(slider.getValue());
                }
            } else {
                if (slider.getValue() < lowerSlider.getValue()) {
                    lowerSlider.setValue(slider.getValue());
                }
            }
            final double target = slider.getValue() / 100.0;
            if (((Number) spinner.getValue()).doubleValue() != target) {
                spinner.setValue(target);
            }
            overlay.setConfidenceRange(
                    lowerSlider.getValue() / 100.0,
                    upperSlider.getValue() / 100.0);
        });

        // Spinner -> slider (slider's own listener then drives the overlay and
        // partner-crossing logic). No-op when the rounded percentage matches.
        final ChangeListener spinnerListener = e -> {
            if (updatingFromOverlay) return;
            final double v = ((Number) spinner.getValue()).doubleValue();
            final int pct = (int) Math.round(v * 100);
            if (slider.getValue() != pct) slider.setValue(pct);
        };
        spinner.addChangeListener(spinnerListener);

        tb.add(slider);
        tb.add(spinner);

        if (lower) {
            lowerSlider = slider;
            lowerSpinner = spinner;
        } else {
            upperSlider = slider;
            upperSpinner = spinner;
        }
        return tb;
    }

    private JScrollPane buildTablePane() {
        tableModel = new SeedTableModel(overlay, snt);
        seedTable = new JTable(tableModel) {
            private static final long serialVersionUID = 1L;
            @Override
            protected void paintComponent(final Graphics g) {
                super.paintComponent(g);
                if (getModel().getRowCount() != 0) return;
                paintEmptyStatePlaceholder((Graphics2D) g, this);
            }
        };
        seedTable.setAutoCreateRowSorter(true);
        seedTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        seedTable.setFillsViewportHeight(true);
        seedTable.setRowHeight(seedTable.getRowHeight() + 2);

        // Hide rows whose seed confidence is outside [low, high]. The sorter
        // listens to fireTableDataChanged events; SeedOverlay -> model -> table
        // change propagation re-evaluates the predicate automatically, so the
        // table view updates as the user moves the confidence sliders/spinners.
        @SuppressWarnings("unchecked") final TableRowSorter<SeedTableModel> sorter =
                (TableRowSorter<SeedTableModel>) seedTable.getRowSorter();
        sorter.setRowFilter(tableModel.confidenceRangeFilter());

        // Bidirectional selection sync: table -> overlay
        seedTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || updatingFromOverlay) return;
            pushTableSelectionToOverlay();
        });

        // Double-click a row -> navigate the canvas to that seed (mirrors the
        // Bookmark Manager pattern). Editing is available via the toolbar
        // button and right-click menu; all cells are read-only, so there's
        // no need to skip "editable" cells.
        seedTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(final java.awt.event.MouseEvent e) {
                if (e.getClickCount() != 2 || javax.swing.SwingUtilities.isRightMouseButton(e)) return;
                final int viewRow = seedTable.rowAtPoint(e.getPoint());
                if (viewRow < 0) return;
                navigateToRow(viewRow);
            }
        });

        // Context menu (right-click on a row): selection toggle, Edit, Delete,
        // column resize/reset (parity with the Bookmark Manager table menu).
        final JPopupMenu menu = new JPopupMenu();
        menu.add(GuiUtils.JTables.deselectSelectAllMenuItem(seedTable, null));
        menu.addSeparator();

        JMenuItem jmi = new JMenuItem("Edit...", IconFactory.menuIcon(IconFactory.GLYPH.PEN));
        jmi.setToolTipText("Edit selected seed(s)");
        jmi.addActionListener(e -> editSelected());
        menu.add(jmi);
        jmi = new JMenuItem("Delete...", IconFactory.menuIcon(IconFactory.GLYPH.TRASH));
        jmi.setToolTipText("Remove selected seed(s) from the overlay");
        jmi.addActionListener( e -> deleteSelected());
        menu.add(jmi);
        menu.addSeparator();

        menu.add(GuiUtils.JTables.resetAndResizeColumnsMenuItem(
                seedTable, () -> recordComment("Seed Manager: resizeColumns()"),
                seedColumnWidthFractions()));
        menu.addSeparator();
        seedTable.setComponentPopupMenu(menu);

        final JScrollPane scroll = new JScrollPane(seedTable);
        scroll.setPreferredSize(new Dimension(250, 250)); // otherwise SNTUI resizes

        // Drag-and-drop: dropping CSV / ROI / labels-image files invokes the
        // corresponding import command. SNTUI has its own dialog-wide fileDrop
        // which runs later in the SNTUI constructor and recursively overwrites
        // drop targets on every child. We use the invokeLater wueueing so that
        // this FileDrop is installed last and wins (both for the initial
        // setDropTarget call and for subsequent HierarchyListener fires, e.g.
        // when the table is detached/docked).
        SwingUtilities.invokeLater(() -> new FileDrop(scroll,
                files -> SwingUtilities.invokeLater(() -> handleDroppedFiles(files))));

        // Detach / dock table  helper
        tableDetacher = new GuiUtils.JTables.DetachableTable(
                scroll, "Seeds", this::redockTableScroll,
                new Dimension(600, 320));
        tableDetacher.installMenuItem(menu);
        return scroll;
    }

    private static void paintEmptyStatePlaceholder(final Graphics2D g2, final JTable table) {
        GuiUtils.setRenderingHints(g2);
        g2.setColor(GuiUtils.getDisabledComponentColor());
        final FontMetrics fm = g2.getFontMetrics();
        final Rectangle vis = table.getVisibleRect();
        final String[] lines = {
                "No seeds loaded.",
                "Use the Import menu or drop a file here.",
                "Supported: CSV | ROIs (.roi, .zip) | labels image (.tif)"
        };
        final int top = vis.y + Math.max(fm.getHeight() + 8, vis.height / 3);
        for (int i = 0; i < lines.length; i++) {
            final String s = lines[i];
            g2.drawString(s, vis.x + (vis.width - fm.stringWidth(s)) / 2, top + i * fm.getHeight());
        }
    }

    private void handleDroppedFiles(final File[] files) {
        if (files == null || files.length == 0) return;
        String kind = null;
        for (final File f : files) {
            final String k = classifyFile(f);
            if (k == null) {
                sntui.error("Unsupported file type: " + f.getName() + ".\nSupported: .csv, .roi/.zip, .tif/.tiff.");
                return;
            }
            if (kind == null) kind = k;
            else if (!kind.equals(k)) {
                sntui.error("Please drop files of a single kind at a time (CSV, ROIs, or labels image).");
                return;
            }
        }
        switch (kind) {
            case "csv"    -> dispatchCsvDrop(files);
            case "roi"    -> dispatchRoiDrop(files);
            case "labels" -> dispatchLabelsDrop(files);
            default -> { /* unreachable */ }
        }
    }

    private static String classifyFile(final File f) {
        final String n = f.getName().toLowerCase(Locale.ROOT);
        if (n.endsWith(".csv") || n.endsWith(".tsv")) return "csv";
        if (n.endsWith(".roi") || n.endsWith(".zip")) return "roi";
        if (n.endsWith(".tif") || n.endsWith(".tiff")) return "labels";
        return null;
    }

    private void dispatchCsvDrop(final File[] files) {
        if (files.length > 1) {
            SNTUtils.log("Multiple CSV files dropped; importing the first: " + files[0].getName());
        }
        final CommandService cs = getCommandService();
        if (cs == null) return;
        recordComment("Seed Manager: drag-and-drop CSV: " + files[0].getName());
        cs.run(ImportSeedPointsCmd.class, true, "csvFile", files[0], "replace", overlay.isEmpty());
    }

    private void dispatchRoiDrop(final File[] files) {
        RoiManager rm = RoiManager.getInstance2();
        if (rm == null) rm = new RoiManager();
        final int before = rm.getCount();
        for (final File f : files) rm.runCommand("Open", f.getAbsolutePath());
        if (rm.getCount() == before) {
            sntui.error("Could not load ROIs from the dropped file(s).");
            return;
        }
        recordComment("Seed Manager: drag-and-drop ROIs (" + files.length + " file(s))");
        loadFromROIs();
    }

    private void dispatchLabelsDrop(final File[] files) {
        if (files.length > 1) {
            SNTUtils.log("Multiple labels images dropped; opening the first: " + files[0].getName());
        }
        final ImagePlus imp = ImpUtils.open(files[0]);
        if (imp == null) {
            sntui.error("Could not open " + files[0].getName() + " as an image.");
            return;
        }
        imp.show();
        recordComment("Seed Manager: drag-and-drop labels image: " + files[0].getName());
        loadFromLabelsImage();
    }

    private void recordComment(final String comment) {
        if (sntui == null || sntui.getRecorder(false) == null) return;
        sntui.getRecorder(false).recordComment(comment);
    }

    /**
     * Re-attaches the table scroll pane to its original grid cell after a
     * dock. Invoked by the {@link GuiUtils.JTables.DetachableTable} helper:
     * GridBag constraints aren't preserved across remove/add so we replay the
     * cloned {@link GridBagConstraints} captured at build time.
     */
    private void redockTableScroll() {
        if (tableScroll == null || tableScrollGbc == null) return;
        add(tableScroll, tableScrollGbc);
        revalidate();
        repaint();
    }

    private void resizeColumns() {
        if (seedTable == null || tableModel == null) return;
        GuiUtils.JTables.resetColumnOrder(seedTable);
        GuiUtils.JTables.resizeColumns(seedTable, seedColumnWidthFractions());
    }

    /** Preferred column width fractions for the seed table */
    private float[] seedColumnWidthFractions() {
        // Columns: X, Y, Z, Conf, Radius, Type, Source [, Status]
        return (tableModel.getColumnCount() >= 8)
                ? new float[]{0.09f, 0.09f, 0.09f, 0.09f, 0.10f, 0.16f, 0.22f, 0.16f}
                : new float[]{0.11f, 0.11f, 0.11f, 0.11f, 0.12f, 0.18f, 0.26f};
    }

    /**
     * Navigates the canvas (all panes, when multi-pane) to the seed for a
     * given view row. Mirrors {@code BookmarkManager.goTo}: physical->voxel
     * coordinate conversion, optional channel/frame from the seed metadata,
     * and the visiting-zoom percentage selected on the bottom toolbar.
     */
    private void navigateToRow(final int viewRow) {
        if (viewRow < 0) return;
        final int modelRow = seedTable.convertRowIndexToModel(viewRow);
        if (modelRow < 0 || modelRow >= overlay.size()) return;
        goToSeed(overlay.get(modelRow));
    }

    private void goToSeed(final SeedPoint s) {
        if (s == null) return;
        final ImagePlus imp = snt.getImagePlus();
        if (imp == null) {
            sntui.error("No image is currently open.");
            return;
        }
        final double sx = snt.getPixelWidth();
        final double sy = snt.getPixelHeight();
        final double sz = snt.getPixelDepth();
        final double vx = s.x / sx;
        final double vy = s.y / sy;
        final double vz = s.z / sz;
        final int nz = imp.getNSlices();
        // Are we navigating to outside the image?
        if (vx < 0 || vx > imp.getWidth() || vy < 0 || vy > imp.getHeight() || vz < 0 || vz > nz) {
            sntui.error("Seed location is outside image dimensions.");
            return;
        }
        // Set channel/frame from seed if specified (-1 = unset -> keep current);
        // canonical voxel->slice mapping (1-based, clamped) shared by every pane so the side panes don't drift by one
        final int c = (s.channel >= 1) ? Math.min(s.channel, imp.getNChannels()) : imp.getC();
        final int t = (s.frame >= 1) ? Math.min(s.frame, imp.getNFrames()) : imp.getT();
        final int zSlice = voxelToSlice(vz, nz);
        imp.setPosition(c, zSlice, t);
        final double zoom = visitingZoom.fraction();
        ImpUtils.zoomTo(imp, zoom, (int) vx, (int) vy);
        if (!snt.getSinglePane()) {
            // Side panes show all Z by definition - no setPosition needed -
            // but the in-pane "depth" axis still uses the same voxel-to-slice
            // mapping for consistency with the main pane.
            final ImagePlus zyImp = snt.getImagePlus(SNT.ZY_PLANE);
            if (zyImp != null) ImpUtils.zoomTo(zyImp, zoom, voxelToSlice(vz, nz) - 1, (int) vy);
            final ImagePlus xzImp = snt.getImagePlus(SNT.XZ_PLANE);
            if (xzImp != null) ImpUtils.zoomTo(xzImp, zoom, (int) vx, voxelToSlice(vz, nz) - 1);
        }
    }

    /**
     * Maps a voxel-Z coordinate to a 1-based ImageJ slice index, clamped to
     * {@code [1, nSlices]}.
     */
    private static int voxelToSlice(final double vz, final int nSlices) {
        return Math.max(1, Math.min(nSlices, (int) Math.round(vz) + 1));
    }


    // Bidirectional selection sync
    private void pushTableSelectionToOverlay() {
        final int[] viewRows = seedTable.getSelectedRows();
        final java.util.List<SeedPoint> selected = new java.util.ArrayList<>(viewRows.length);
        for (final int viewRow : viewRows) {
            final int modelRow = seedTable.convertRowIndexToModel(viewRow);
            if (modelRow >= 0 && modelRow < overlay.size()) {
                selected.add(overlay.get(modelRow));
            }
        }
        overlay.setSelectedSeeds(selected);
    }

    private void pullOverlaySelectionToTable() {
        if (seedTable == null) return;
        final Set<SeedPoint> selected = overlay.getSelectedSeeds();
        seedTable.getSelectionModel().setValueIsAdjusting(true);
        try {
            seedTable.clearSelection();
            if (selected.isEmpty()) return;
            final int n = overlay.size();
            for (int i = 0; i < n; i++) {
                if (selected.contains(overlay.get(i))) {
                    final int viewRow = seedTable.convertRowIndexToView(i);
                    if (viewRow >= 0) seedTable.addRowSelectionInterval(viewRow, viewRow);
                }
            }
        } finally {
            seedTable.getSelectionModel().setValueIsAdjusting(false);
        }
    }

    // Refresh from overlay state
    private void refreshFromOverlay() {
        updatingFromOverlay = true;
        try {
            toggleVisibilityAction.putValue(Action.SELECTED_KEY, overlay.isVisible());
            updateCountLabel();
            final int loVal = (int) Math.round(overlay.getLowConfidence() * 100);
            final int hiVal = (int) Math.round(overlay.getHighConfidence() * 100);
            if (lowerSlider.getValue() != loVal) lowerSlider.setValue(loVal);
            if (upperSlider.getValue() != hiVal) upperSlider.setValue(hiVal);
            lowerSpinner.setValue(overlay.getLowConfidence());
            upperSpinner.setValue(overlay.getHighConfidence());
            if (colorModeCombo != null && colorModeCombo.getSelectedItem() != overlay.getColorMode()) {
                colorModeCombo.setSelectedItem(overlay.getColorMode());
            }
            pullOverlaySelectionToTable();
            if (lutRamp != null) lutRamp.repaint();
        } finally {
            updatingFromOverlay = false;
        }
    }

    private void updateCountLabel() {
        final int total = overlay.size();
        final int shown = overlay.visibleSize();
        if (total == 0) {
            countLabel.setText("No seeds loaded");
            countLabel.setIcon(null);
        } else if (shown == total) {
            countLabel.setText(String.format("All %,d seeds shown", total));
            countLabel.setIcon(null);
        } else {
            countLabel.setText(String.format("%,d of %,d shown", shown, total));
            countLabel.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.FILTER));
        }
    }

    private JToolBar bottomToolbar() {
        final JButton impButton = GuiUtils.Buttons.OptionsButton(IconFactory.GLYPH.IMPORT, 1f, importMenu());
        impButton.setToolTipText("Import seeds");
        final JButton expButton = GuiUtils.Buttons.OptionsButton(IconFactory.GLYPH.EXPORT, 1f, exportMenu());
        expButton.setToolTipText("Export seeds");
        final JButton traceButton = GuiUtils.Buttons.OptionsButton(IconFactory.GLYPH.ROBOT, 1f, seedTracingMenu());
        traceButton.setToolTipText("<HTML>Run auto-tracers from listed seeds.");

        // Visiting-zoom spinner (BookmarkManager parity): applied when
        // double-clicking a row to fly to the seed's location.
        final JSpinner zoomSpinner = visitingZoom.buildSpinner();
        final JLabel zoomLabel = new JLabel("Visiting zoom level (%):");
        zoomLabel.setToolTipText(zoomSpinner.getToolTipText());

        final JButton resetZoomButton = GuiUtils.Buttons.undo();
        resetZoomButton.setToolTipText("<HTML>Resets level to two <i>Zoom In [+]</i> operations above the current image zoom");
        resetZoomButton.addActionListener(e -> {
            if (null == snt.getImagePlus()) {
                sntui.showStatus("Current zoom unknown: No image is loaded...", true);
            } else {
                visitingZoom.resetFor(snt.getImagePlus());
                zoomSpinner.setValue(visitingZoom.percentage());
            }
        });

        final JToolBar tb = new JToolBar();
        tb.setFloatable(false);
        tb.add(impButton);
        tb.add(expButton);
        tb.addSeparator();
        tb.add(Box.createHorizontalGlue());
        tb.add(traceButton);
        tb.addSeparator();
        tb.add(Box.createHorizontalGlue());
        tb.add(zoomLabel);
        tb.add(zoomSpinner);
        tb.add(resetZoomButton);
        return tb;
    }

    /**
     * Invokes {@code AutotraceFromSeedsCmd}. If the user has any rows
     * selected in the table, the command is pre-populated with
     * {@code "Selection only"} as the source filter; otherwise the user's
     * choice in the harvester is honored (default: visible).
     */
    private void traceFromSeeds() {
        if (noSeedsError()) return;
        final CommandService cs = getCommandService();
        if (cs == null) return;
        final boolean hasSelection = !overlay.getSelectedSeeds().isEmpty();
        // Record intent. The CommandService.run call below pops the harvester
        // where the user can still adjust GWDT knobs; the comment documents
        // which seed-source we pre-targeted before the dialog opened.
        recordComment("Seed Manager: traceFromSeeds(source=" + (hasSelection ? "selection" : "harvester-default") + ")");
        if (hasSelection) {
            cs.run(sc.fiji.snt.plugin.AutotraceFromSeedsCmd.class, true,
                    "sourceFilter", sc.fiji.snt.plugin.AutotraceFromSeedsCmd.SOURCE_SELECTION);
        } else {
            cs.run(sc.fiji.snt.plugin.AutotraceFromSeedsCmd.class, true);
        }
    }

    private JPopupMenu importMenu() {
        final JPopupMenu menu = new JPopupMenu();
        GuiUtils.addSeparator(menu, "Import:");
        JMenuItem jmi = new JMenuItem("From CSV File...", IconFactory.menuIcon(IconFactory.GLYPH.TABLE));
        jmi.setToolTipText("Loads seeds from tabular data");
        menu.add(jmi);
        jmi.addActionListener(e -> {
            final CommandService cs = getCommandService();
            // Pass no input map -> SciJava harvester prompts the user for the
            // CSV file + units + append flag.
            if (cs != null) cs.run(ImportSeedPointsCmd.class, true);
        });
        jmi = new JMenuItem("From Labels/Masks Image...", IconFactory.menuIcon(IconFactory.GLYPH.IMAGE));
        jmi.setToolTipText("Extracts seeds from a segmented image as produced by e.g., cellpose, Labkit, StarDist, etc.");
        menu.add(jmi);
        jmi.addActionListener(e -> loadFromLabelsImage());
        jmi = new JMenuItem("From ROI Manager...", IconFactory.menuIcon(IconFactory.GLYPH.LIST_ALT));
        jmi.setToolTipText("Extracts seeds from ROI centroids");
        menu.add(jmi);
        jmi.addActionListener(e -> {
            // Pre-flight: catch the empty case early so the user gets a
            // friendly message instead of the SciJava harvester then an
            // error. The command itself re-validates defensively.
            final RoiManager rm = RoiManager.getInstance2();
            if (rm == null || rm.getCount() == 0) {
                sntui.error("ROI Manager is either closed or empty.");
                return;
            }
            loadFromROIs();
        });
        menu.addSeparator();
        jmi = new JMenuItem("From Workspace...", IconFactory.menuIcon('\ue066', true));
        jmi.setToolTipText("Loads stored seeds");
        menu.add(jmi);
        jmi.addActionListener(e -> loadFromSessionDir());
        return menu;
    }

    private JPopupMenu exportMenu() {
        final JPopupMenu menu = new JPopupMenu();
        GuiUtils.addSeparator(menu, "Export:");
        JMenuItem jmi = new JMenuItem("To CSV File...", IconFactory.menuIcon(IconFactory.GLYPH.TABLE));
        menu.add(jmi);
        jmi.addActionListener(e -> exportToFile());
        jmi = new JMenuItem("To ROI Manager...", IconFactory.menuIcon(IconFactory.GLYPH.LIST_ALT));
        jmi.setToolTipText( "Stores selected seed(s) (or all seeds when none are selected) in the ROI Manager.");
        menu.add(jmi);
        jmi.addActionListener(e -> {
            final RoiManager rm = RoiManager.getInstance2();
            if (rm != null && rm.getCount() > 0 && sntui.guiUtils.getConfirmation("Clear existing ROI Manager ROIs?",
                    "Clear ROI Manager")) {
                rm.reset();
            }
            saveToROIs();
        });
        menu.addSeparator();
        jmi = new JMenuItem("To Workspace...", IconFactory.menuIcon('\ue066', true));
        jmi.setToolTipText("Saves seeds to the current session as seeds.csv");
        menu.add(jmi);
        jmi.addActionListener(e -> saveToSessionDir());
        return menu;
    }

    private JPopupMenu seedTracingMenu() {
        final JPopupMenu menu = new JPopupMenu();
        GuiUtils.addSeparator(menu, "Seeds as Roots:");
        JMenuItem jmi = new JMenuItem("Grayscale Image (1 Seed → 1 Tree)...");
        jmi.addActionListener(e -> traceFromSeeds());
        jmi.setToolTipText("<HTML>Runs GWDT auto-tracing from seeds.<br>" +
                "Pre-targets the current table selection; falls back to visible seeds if no rows are selected.");
        menu.add(jmi);
        // TODO: Implement Seeds as Tips, etc.
        return menu;
    }

    private void loadFromSessionDir() {
        final File sessionDir = getSessionDir();
        if (sessionDir == null) {
            sntui.error("Session directory is not accessible.");
            return;
        }
        final File file = new File(sessionDir, "seeds.csv");
        if (!file.exists() || !file.canRead()) {
            sntui.error("No seeds.csv found in current session (" + sessionDir.getAbsolutePath() + ").");
            return;
        }
        final CommandService cs = getCommandService();
        if (cs != null)
            cs.run(ImportSeedPointsCmd.class, true,
                    "csvFile", file, // input file
                    "unitsChoice", ImportSeedPointsCmd.UNITS_PHYSICAL, // dropdown choice
                    "replace", overlay.isEmpty()// append checkbox
            );
    }

    private void saveToSessionDir() {
        if (noSeedsError()) return;
        final File sessionDir = getSessionDir();
        if (sessionDir == null) {
            sntui.error("Session directory is not accessible.");
            return;
        }
        final File file = new File(sessionDir, "seeds.csv");
        if (file.exists() && !sntui.guiUtils.getConfirmation(
                "Overwrite existing seeds.csv in " + sessionDir.getName() + "?",
                "Overwrite Seeds?")) {
            return;
        }
        try {
            overlay.saveAs(file);
            SNTUtils.log("Exported " + overlay.size() + " seeds to " + file.getAbsolutePath());
            sntui.showStatus(String.format("%,d seed(s) exported to workspace", overlay.size()), true);
        } catch (final IOException ex) {
            sntui.error("Could not save seeds: " + ex.getMessage());
        }
    }

    /**
     * Opens the ROI-importer command. The harvester prompts for type label,
     * confidence (default 1.0), and append flag; the command reads the
     * RoiManager directly.
     */
    private void loadFromROIs() {
        final CommandService cs = getCommandService();
        if (cs != null) cs.run(LoadSeedsFromROIsCmd.class, true);
    }

    private void saveToROIs() {
        if (noSeedsError()) return;
        Collection<SeedPoint> sel = overlay.getSelectedSeeds();
        if (sel.isEmpty())
            sel = overlay.list();
        final List<Roi> rois;
        try {
            rois = SeedRois.toRois(sel, snt.getImagePlus());
        } catch (final Throwable ex) {
            sntui.error("Failed to convert seeds to ROIs: " + ex.getMessage());
            return;
        }
        if (rois.isEmpty()) {
            sntui.error("No ROIs could be produced from the " + sel.size() + " selected seeds.");
            return;
        }
        RoiManager rm = RoiManager.getInstance2();
        if (rm == null) rm = new RoiManager();
        rois.forEach(rm::addRoi);
        sntui.showStatus(String.format("%d ROI(s) exported", rois.size()), true);
    }

    /**
     * Opens the labels-image command. The harvester prompts for type label,
     * minimum confidence, and append flag; the labels image is picked
     * interactively via {@code ChooseDataset} inside the command.
     */
    private void loadFromLabelsImage() {
        final CommandService cs = getCommandService();
        if (cs != null) cs.run(LoadSeedsFromLabelsImageCmd.class, true);
    }

    private File getSessionDir() {
        final File workspaceDir = sntui.getOrPromptForWorkspace();
        if (workspaceDir == null)
            return null;
        if (snt.getPrefs().workspaceIsValid()) {
            final File sessionsDir = snt.getPrefs().getSessionsDir();
            return Arrays.stream(Objects.requireNonNull(sessionsDir.listFiles()))
                    .filter(f -> f.isDirectory() && f.getName().startsWith("SNT_Session_"))
                    .max(Comparator.comparing(File::getName)).orElse(sessionsDir);
        }
        return null;
    }

    private CommandService getCommandService() {
        final CommandService cs = snt.getContext().getService(CommandService.class);
        if (cs == null) {
            SNTUtils.log("CommandService unavailable; cannot import.");
            sntui.error("Could not run import command.");
        }
        return cs;
    }

    private void exportToFile() {
        if (noSeedsError()) return;
        final File file = sntui.guiUtils.getSaveFile("Export Seed Points (CSV)",
                new File("seeds.csv"), "csv");
        if (file == null) return;
        try {
            overlay.saveAs(file);
            SNTUtils.log("Exported " + overlay.size() + " seeds to " + file.getAbsolutePath());
        } catch (final IOException ex) {
            sntui.error("Could not save seeds: " + ex.getMessage());
        }
    }
    
    /**
     * Groups stateful Swing {@link Action}s used by toolbar buttons and the
     * table context menu so they can be shared (and their enabled/selected
     * state mass-refreshed) from {@link #refreshFromOverlay}.
     */
    private Action toggleVisibilityAction() {
        final Action toggleVisibility = new AbstractAction("Show/hide seeds") {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(final ActionEvent e) {
                if (updatingFromOverlay) return;
                if (noSeedsError()) { // Reset the JToggleButton
                    putValue(Action.SELECTED_KEY, overlay.isVisible());
                    return;
                }
                overlay.setVisible(!overlay.isVisible());
            }
        };
        toggleVisibility.putValue(Action.SHORT_DESCRIPTION, "Show/hide seeds on canvas");
        toggleVisibility.putValue(Action.SELECTED_KEY, overlay.isVisible());
        return toggleVisibility;
    }

    /**
     * Dispatches the edit dialog dynamically:
     * <ul>
     *   <li>exactly 1 selection -> single-mode dialog (all 9 fields)
     *   <li>0 selections -> bulk mode over the rows currently shown in the
     *       table (i.e., after the confidence-range filter: what the user
     *       sees, not every seed in the overlay)
     *   <li>2+ selections -> bulk mode over the selected seeds
     * </ul>
     */
    private void editSelected() {
        if (noSeedsError()) return;

        final Set<SeedPoint> sel = overlay.getSelectedSeeds();
        if (sel.size() == 1) {
            final SeedPoint seed = sel.iterator().next();
            final int idx = overlay.indexOf(seed);
            if (idx >= 0) SeedPointEditDialog.editAt(this, overlay, idx);
            return;
        }
        final List<Integer> indices = new ArrayList<>();
        if (sel.isEmpty()) {
            // "No selection" = whatever rows are currently visible in the
            // table. Walk the sorter's view so we mirror exactly what the
            // user sees (in particular: rows hidden by the confidence-range
            // filter are excluded).
            final javax.swing.RowSorter<?> sorter =
                    (seedTable != null) ? seedTable.getRowSorter() : null;
            final int viewCount = (sorter != null)
                    ? sorter.getViewRowCount()
                    : ((seedTable != null) ? seedTable.getRowCount() : overlay.size());
            for (int v = 0; v < viewCount; v++) {
                final int modelRow = (sorter != null) ? sorter.convertRowIndexToModel(v) : v;
                if (modelRow >= 0 && modelRow < overlay.size()) indices.add(modelRow);
            }
        } else {
            for (final SeedPoint s : sel) {
                final int idx = overlay.indexOf(s);
                if (idx >= 0) indices.add(idx);
            }
        }
        if (indices.isEmpty()) {
            return;
        }
        SeedPointEditDialog.editBulk(this, overlay, indices);
    }

    private boolean noSeedsError() {
        if (overlay.isEmpty()) {
            sntui.error("No seeds exist. Import or generate seeds first.");
            return true;
        }
        return false;
    }

    private void deleteSelected() {
        if (noSeedsError()) return;
        final Set<SeedPoint> sel = overlay.getSelectedSeeds();
        if (sel.isEmpty()) {
            // No selection -> "clear all" prompt. Do NOT fall through to the
            if (sntui.guiUtils.getConfirmation("Remove all " + overlay.size() + " seed(s)?", "Clear Seed Overlay?")) {
                overlay.clear();
            }
            return;
        }
        if (sntui.guiUtils.getConfirmation("Delete " + sel.size() + " selected seed(s)?", "Delete Selected Seeds?")) {
            overlay.removeSelected();
        }
    }

    /**
     * Small component that paints a horizontal ramp of the active LUT,
     * darkened outside the [low, high] window so users see the effective
     * mapping at a glance.
     */
    private static final class LutRamp extends JComponent {
        private static final long serialVersionUID = 1L;
        private static final int RAMP_HEIGHT = (int) GuiUtils.uiFontSize();
        private final java.util.function.Supplier<ColorTable> tableSupplier;
        private final java.util.function.DoubleSupplier lowSupplier;
        private final java.util.function.DoubleSupplier highSupplier;

        LutRamp(final java.util.function.Supplier<ColorTable> table,
                final java.util.function.DoubleSupplier low,
                final java.util.function.DoubleSupplier high) {
            this.tableSupplier = table;
            this.lowSupplier = low;
            this.highSupplier = high;
            setPreferredSize(new Dimension(150, RAMP_HEIGHT));
            setMinimumSize(new Dimension(50, RAMP_HEIGHT));
        }

        @Override
        protected void paintComponent(final Graphics g0) {
            final Graphics2D g = (Graphics2D) g0;
            final int w = getWidth();
            final int h = getHeight();
            final ColorTable table = tableSupplier.get();
            if (table == null || w <= 0 || h <= 0) return;
            final int len = table.getLength();
            for (int x = 0; x < w; x++) {
                final double t = (double) x / Math.max(1, w - 1);
                int idx = (int) Math.round(t * (len - 1));
                if (idx < 0) idx = 0;
                else if (idx >= len) idx = len - 1;
                final int r = table.get(ColorTable.RED, idx);
                final int gr = table.get(ColorTable.GREEN, idx);
                final int b = table.get(ColorTable.BLUE, idx);
                g.setColor(new Color(r, gr, b));
                g.fillRect(x, 0, 1, h);
            }
            // Dim the regions outside [low, high]
            final double low = lowSupplier.getAsDouble();
            final double high = highSupplier.getAsDouble();
            g.setColor(new Color(0, 0, 0, 120));
            final int lowX = (int) Math.round(low * w);
            final int highX = (int) Math.round(high * w);
            if (lowX > 0) g.fillRect(0, 0, lowX, h);
            if (highX < w) g.fillRect(highX, 0, w - highX, h);
            // Border
            g.setColor(getForeground());
            g.drawRect(0, 0, w - 1, h - 1);
        }
    }
}
