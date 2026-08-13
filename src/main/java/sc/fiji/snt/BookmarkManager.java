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
import ij.gui.Overlay;
import ij.gui.PointRoi;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;
import ij.process.FloatPolygon;
import org.scijava.util.ColorRGB;
import sc.fiji.snt.analysis.NodeStatistics;
import sc.fiji.snt.analysis.RoiConverter;
import sc.fiji.snt.analysis.SNTTable;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.IconFactory;
import sc.fiji.snt.util.*;
import sc.fiji.snt.viewer.AbstractBigViewer;
import sc.fiji.snt.viewer.Bvv;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * Implements the <i>Bookmark Manager</i> pane.
 *
 * @author Tiago Ferreira
 */
public class BookmarkManager {

    static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

    /** Name prefix for temporary highlight ROIs added to the image overlay. */
    private static final String HIGHLIGHT_PREFIX = "__bm_highlight_";

    private final SNTUI sntui;
    private final AbstractBigViewer viewer;
    private JToggleButton highlightToggle;
    /** Viewer-injected toolbar components (e.g. slab toggle). Added via {@link #addViewerToolbarButton}. */
    private final java.util.List<javax.swing.JComponent> viewerToolbarButtons = new java.util.ArrayList<>();
    private final GuiUtils guiUtils;
    private final BookmarkModel model;
    private final BookmarkTable table;
    /** Preferred zoom level applied when double-clicking a bookmark to visit it. */
    private final GuiUtils.VisitingZoom visitingZoom = new GuiUtils.VisitingZoom();
    private JDialog viewerFrame; // floating dialog for viewer mode (non-modal, owned by viewer frame)
    // Detachable table state, owned by the helper.
    private JPanel panel; // cached panel built by getPanel()
    private JScrollPane tableScroll; // single scroll pane wrapping `table` (created in assembleTable)
    private GridBagConstraints tableScrollGbc; // captured constraints for re-docking


    /**
     * SNT constructor: implements the <i>Bookmark Manager</i> pane embedded in SNT's UI.
     */
    public BookmarkManager(final SNTUI sntui) {
        this.sntui = sntui;
        this.viewer = null;
        this.guiUtils = sntui != null ? sntui.guiUtils : new GuiUtils();
        model = new BookmarkModel(false);
        table = assembleTable(model);
        visitingZoom.resetFor(sntui != null ? sntui.plugin.getImagePlus() : null);
    }

    /**
     * Viewer constructor: implements a standalone marker manager for a BigDataViewer-family
     * viewer (Bvv, Bdv, etc.). Markers are rendered as spheres/circles in the viewer overlay
     * and can be placed with the {@code M} key. The manager is displayed as a floating panel.
     *
     * @param viewer the viewer instance to attach to
     */
    public BookmarkManager(final AbstractBigViewer viewer) {
        this.sntui = null;
        this.viewer = viewer;
        this.guiUtils = new GuiUtils(viewer.getViewerFrame());
        model = new BookmarkModel(true);
        table = assembleTable(model);
        table.placeholderMsg = "Bookmark volume locations using M";
        // Sync overlay whenever the model changes
        model.addTableModelListener(e -> syncViewerOverlay());
    }

    /**
     * Returns the tethered {@link SNT} instance to use for pixel world conversions, in
     * either mode: SNT mode uses {@code sntui.plugin} directly; viewer mode uses
     * {@code viewer.getSNT()} (may be {@code null} for a pure-viewing BVV/BDV session with no
     * associated tracing session)
     */
    private SNT snt() {
        return (viewer != null) ? viewer.getSNT() : sntui.plugin;
    }

    /**
     * Whether this panel is destined to live inside {@link SNTUI} "Bookmarks" tab, as opposed
     * to a standalone floating dialog: true whenever an SNTUI is present.
     */
    private boolean insideSNTUI() {
        return sntui != null || (viewer != null && snt() != null && snt().getUI() != null);
    }

    /**
     * Converts pixel coordinates to this manager's canonical world/calibrated storage:
     * {@code world = pixel * spacing + offset}, accounting for both pixel spacing <b>and</b>
     * {@link SNT#getWorldOriginOffset() the world origin offset} -- not just spacing, unlike
     * {@link ij.measure.Calibration#getX(double)} and friends, which know nothing of SNT's offset.
     * This matters whenever the tethered image/source was loaded from a coordinate frame that is
     * not anchored at world (0,0,0) (see {@code BigDataLoaderCmd#applyWorldOriginOffset}), which is
     * the common case for Stream-mode BVV/BDV sources (N5/Zarr/BDV-XML with a translated
     * {@code sourceTransform}). Falls back to spacing 1 / offset 0 (i.e. pixel == world) if no
     * {@link SNT} is available.
     * <p>
     * <b>Do not substitute {@code ij.measure.Calibration}'s own {@code xOrigin/yOrigin/zOrigin}
     * fields for {@code SNT.getWorldOriginOffset()} here</b> (or vice versa, see
     * {@link SNT#setWorldOriginOffset}.
     *
     * @see #worldToPixel(Bookmark) the inverse conversion
     */
    private PointInImage pixelToWorld(final double px, final double py, final double pz) {
        final SNT snt = snt();
        if (snt == null) return new PointInImage(px, py, pz);
        final double[] offset = snt.getWorldOriginOffset();
        final double xSpacing = (snt.getPixelWidth() > 0) ? snt.getPixelWidth() : 1;
        final double ySpacing = (snt.getPixelHeight() > 0) ? snt.getPixelHeight() : 1;
        final double zSpacing = (snt.getPixelDepth() > 0) ? snt.getPixelDepth() : 1;
        return new PointInImage(px * xSpacing + offset[0], py * ySpacing + offset[1], pz * zSpacing + offset[2]);
    }

    /** In viewer mode: pushes all markers to the annotation overlay. */
    private void syncViewerOverlay() {
        if (viewer == null || viewer.annotations() == null) return;
        final List<SNTPoint> points = new ArrayList<>();
        final List<Float> sizes = new ArrayList<>();
        final List<Color> colors = new ArrayList<>();
        final Color viewerDefault = viewer.getDefaultMarkerColor();
        for (final Bookmark b : model.getDataList()) {
            points.add(b);
            sizes.add(b.size > 0 ? b.size : viewer.getDefaultMarkerSize());
            // Guarantee a non-null color: bookmark color > viewer default > yellow
            final Color c = b.getColor() != null ? b.getColor()
                          : viewerDefault        != null ? viewerDefault
                          : Color.YELLOW;
            colors.add(c);
        }
        // replaceAll() does clear + re-add with a single repaint, avoiding flicker
        viewer.annotations().replaceAll(points, sizes, colors);
    }

    /** Returns the floating dialog for viewer mode, creating it on first call. */
    public JDialog getViewerDialogPanel() {
        if (viewerFrame == null) {
            viewerFrame = new JDialog(viewer.getViewerFrame(), "Markers", false);
            viewerFrame.setDefaultCloseOperation(JDialog.HIDE_ON_CLOSE);
            viewerFrame.add(getPanel());
            viewerFrame.pack();
            // Ensure the frame is tall enough to show at least 5 table rows
            final int minHeight = table.getRowHeight() * 5
                    + table.getTableHeader().getPreferredSize().height + 120; // 120 for toolbar + description
            if (viewerFrame.getHeight() < minHeight)
                viewerFrame.setSize(viewerFrame.getWidth(), minHeight);
            viewerFrame.setMinimumSize(new Dimension(viewerFrame.getWidth(), minHeight));
            viewerFrame.setLocationRelativeTo(viewer.getViewerFrame());
        }
        return viewerFrame;
    }

    /**
     * Adds a component to the viewer toolbar section of this panel.
     * The component is appended after a separator the first time this method is called.
     * Viewer subclasses use this to inject context-specific controls (e.g. a slab-clip toggle).
     *
     * @param component the component to add; must not be {@code null}
     */
    public void addViewerToolbarButton(final javax.swing.JComponent component) {
        viewerToolbarButtons.add(component);
    }

    /** Shows or hides the floating viewer marker panel. */
    public void toggleViewerPanel() {
        final JDialog f = getViewerDialogPanel();
        guiUtils.setParent(f);
        f.setVisible(!f.isVisible());
    }

    /** Shows the marker panel, bringing it to front if already visible. */
    public void showPanel() {
        final JDialog f = getViewerDialogPanel();
        if (!f.isVisible()) f.setVisible(true);
        else f.toFront();
    }

    protected JPanel getPanel() {
        if (panel != null) return panel;
        // Assemble as a proper SNTUI tab (matching the SNT-mode constructor's own look: initTab()'s
        // margin border plus the "Bookmarks:" separator header) whenever this panel is going to be
        // shown inside an SNTUI tab, regardless of which BookmarkManager instance built it. Only a
        // true standalone viewer (no SNTUI at all) gets the plain floating-dialog styling
        final boolean insideSNTUI = insideSNTUI();
        panel = insideSNTUI
                ? SNTUI.InternalUtils.initTab() : new JPanel(new GridBagLayout());
        final GridBagConstraints gbc = GuiUtils.defaultGbc();
        gbc.fill = GridBagConstraints.BOTH;
        if (insideSNTUI) {
            SNTUI.InternalUtils.addSeparatorWithURL(panel, "Bookmarks:", true, gbc);
            gbc.gridy++;
        }
        gbc.weighty = 0.0;
        panel.add(GuiUtils.longSmallMsg(introText(insideSNTUI), panel), gbc);
        gbc.gridy++;
        panel.add(assembleHighlightToolbar(), gbc);
        gbc.gridy++;
        gbc.weighty = 0.95;
        // Use the cached scroll pane (created in assembleTable) so the DetachableTable helper can move it in and out
        // of a floating dialog. Capture the constraints used here so redockTableScroll() can  restore the pane to the
        // same grid cell after a detach.
        tableScrollGbc = (GridBagConstraints) gbc.clone();
        panel.add(tableScroll, tableScrollGbc);
        gbc.gridy++;
        gbc.weighty = 0.0;
        panel.add(assembleToolbar(), gbc);
        // Initialize column widths after layout is complete
        SwingUtilities.invokeLater(() -> resetOrResizeColumns(false, true));
        return panel;
    }

    private String introText(final boolean insideSNTUI) {
        if (viewer != null && !insideSNTUI) {
            // standalone viewer without tracing capabilities
            return """
                    To create a marker: press M in the viewer. Color and size are applied to the viewer's overlay in \
                    real time. Hold H to temporarily hide bookmarked markers.
                    """;
        } else if (viewer != null) {
            // stream mode or viewer initialized from SNTUI. Tracing capabilities available
            return """
                    This pane stores image locations that you can quickly (re)visit while tracing. \
                    Bookmarks can be saved to the workspace directory using the toolbar button or via File>Save \
                    Session.
                    
                    To create a bookmark: press M in the viewer. To bookmark other positions along paths use the menu \
                    in the navigation toolbar of the Path Manager. Hold H to temporarily hide bookmarked markers.
                    """;
        }
        // traditional (ImagePlus) mode
        return """
                This pane stores image locations that you can quickly (re)visit while tracing. \
                Bookmarks can be saved to the workspace directory using the toolbar button or via File>Save \
                Session.
                
                To create a bookmark: Right-click on the image and choose "Bookmark Cursor Location" from \
                the contextual menu (or press Shift+B). To bookmark other positions along paths use the menu \
                in the navigation toolbar of the Path Manager.
                """;
    }

    /**
     * Re-attaches the table scroll pane to its original grid cell after a
     * dock. Invoked by the {@link GuiUtils.JTables.DetachableTable} helper.
     * GridBag constraints aren't preserved across remove/add, so we replay
     * the cloned constraints captured by {@link #getPanel()}.
     */
    private void redockTableScroll() {
        if (panel == null || tableScroll == null || tableScrollGbc == null) return;
        panel.add(tableScroll, tableScrollGbc);
        panel.revalidate();
        panel.repaint();
    }

    private BookmarkTable assembleTable(final BookmarkModel model) {
        final BookmarkTable table = new BookmarkTable(model);
        final JPopupMenu pMenu = assembleTablePopupMenu(table);
        table.setComponentPopupMenu(pMenu);
        // Live-sync the overlay highlights with the table selection when the
        // toggle is on. valueIsAdjusting is filtered so click-and-drag selections
        // only repaint once at the end, not on every intermediate index. Empty
        // model is guarded here to avoid showHighlights() popping an error
        // dialog during table-clear operations.
        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            if (model.getRowCount() == 0) return;
            if (viewer != null && viewer.annotations() != null) {
                // Viewer mode: highlight the selected marker in the overlay.
                final int viewRow = table.getSelectedRow();
                final int modelIdx = (viewRow < 0) ? -1
                        : table.convertRowIndexToModel(viewRow);
                viewer.annotations().setSelectedIndex(modelIdx);
            } else {
                if (highlightToggle == null || !highlightToggle.isSelected()) return;
                showHighlights();
            }
        });
        // Cache the scroll pane so the detacher can move it between dock and
        // floating dialog. BookmarkTable.getContainer() copies the table's
        // popup onto the scroll pane, so we must set the popup BEFORE this.
        tableScroll = table.getContainer();
        // Detach/Dock toggle, placed right after Resize/Reset Columns so the
        // table-management actions stay grouped. The Searchable items are
        // appended last so they remain at the bottom of the popup.
        final GuiUtils.JTables.DetachableTable tableDetacher = new GuiUtils.JTables.DetachableTable(
                tableScroll, "Bookmarks", this::redockTableScroll);
        tableDetacher.installMenuItem(pMenu);
        GuiUtils.JTables.assignSearchable(table, element -> {
            if (element == null) return "";
            if (element instanceof Color color) {
                return BookmarkTable.ColorCellEditor.getColorName(color);
            }
            return element.toString();
        }, pMenu);
        if (viewer != null) {
            // Prevent the table's searchable from consuming BVV shortcuts.
            // NONE means "do nothing" the keystroke falls through to the BVV viewer.
            for (final char key : new char[]{'m', 'M', 'b', 'B', 'p', 'P', 'r', 'R', 's', 'S', 'f', 'F'}) {
                table.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
                        .put(javax.swing.KeyStroke.getKeyStroke(key), "none");
            }
            // Click on a marker in the viewer -> select the matching table row.
            viewer.addMouseListenerToDisplay(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(final java.awt.event.MouseEvent e) {
                    if (viewer.annotations() == null) return;
                    final int modelIdx = viewer.annotations().hitTest(e.getX(), e.getY());
                    // Bounds-check against the *model* row count before converting: hitTest() can return an index
                    // that is momentarily stale relative to the table (e.g. the renderer's screenData snapshot
                    // lagging a model change), and convertRowIndexToView throws IndexOutOfBoundsException
                    if (modelIdx < 0 || modelIdx >= table.getModel().getRowCount()) return;
                    final int viewRow = table.convertRowIndexToView(modelIdx);
                    if (viewRow < 0 || viewRow >= table.getRowCount()) return;
                    table.setRowSelectionInterval(viewRow, viewRow);
                    table.scrollRectToVisible(table.getCellRect(viewRow, 0, true));
                }
            });
        }
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(final MouseEvent me) {
                if (me.getClickCount() == 2) {
                    final int col = table.columnAtPoint(me.getPoint());
                    if (col == 0) return; // let color editor handle tag column
                    if (noBookmarksError()) return;
                    final int row = table.getSelectedRow();
                    if (row == -1) {
                        guiUtils.error("No bookmark selected.");
                    } else {
                        goToRow(row);
                    }
                }
            }
        });
        if (sntui != null) {
            SNTUI.InternalUtils.addHoldToToggleKeyListener(table, sntui.plugin);
        }
        return table;
    }

    private void goToRow(final int tableRow) {
        if (viewer != null) {
            // viewer mode: animate camera to marker world position
            flyTo(tableRow);
        } else {
            final ImagePlus imp = sntui.plugin.getImagePlus();
            if (imp == null) {
                noImageOpenError();
            } else {
                goTo(tableRow, imp);
                if (!sntui.plugin.getSinglePane()) {
                    final ImagePlus zyImp = sntui.plugin.getImagePlus(SNT.ZY_PLANE);
                    if (zyImp != null) goTo(tableRow, zyImp, SNT.ZY_PLANE);
                    final ImagePlus xzImp = sntui.plugin.getImagePlus(SNT.XZ_PLANE);
                    if (xzImp != null) goTo(tableRow, xzImp, SNT.XZ_PLANE);
                }
            }
        }
    }

    private void noImageOpenError() {
        assert sntui != null;
        sntui.guiUtils.error(sntui.plugin.isStreamMode()
                ? "No image is currently open. Place markers in the BDV/BVV viewer instead (M key)."
                : "No image is currently open.");
    }

    private void resetOrResizeColumns(final boolean reset, final boolean resize) {
        if (table == null || model == null) return;
        if (reset) GuiUtils.JTables.resetColumnOrder(table);
        if (resize) GuiUtils.JTables.resizeColumns(table, columnWidthFractions());
    }

    /**
     * Preferred width fractions for the bookmark table. Both modes show all columns
     * ({@code Tag, Label, X, Y, Z, C, T, Size}); the column meaningless for the current mode
     * (C in viewer mode, Size in classic mode) just renders a dash and gets a narrower share of the width
     */
    private float[] columnWidthFractions() {
        return (viewer != null)
                ? new float[]{0.05f, 0.422f, 0.112f, 0.112f, 0.112f, 0.04f,  0.04f,  0.112f}
                : new float[]{0.05f, 0.422f, 0.112f, 0.112f, 0.112f, 0.076f, 0.076f, 0.04f};
    }

    private JPopupMenu assembleTablePopupMenu(final BookmarkTable table) {
        final JPopupMenu pMenu = new JPopupMenu();
        pMenu.add(GuiUtils.JTables.deselectSelectAllMenuItem(table,
                () -> recordCmd("clearSelection()"))); // recordCmd only records clear, not select all
        pMenu.addSeparator();

        JMenuItem mi = new JMenuItem("Rename...", IconFactory.menuIcon(IconFactory.GLYPH.PEN));
        mi.addActionListener(e -> {
            if (noBookmarksError()) return;
            final int[] rows = table.getSelectedRows();
            if (rows.length == 0) {
                guiUtils.error("No bookmark selected.");
            } else if (rows.length == 1) {
                final int modelCol = table.convertColumnIndexToView(1);
                if (table.getRowCount() > 10)
                    table.scrollRectToVisible(new Rectangle(table.getCellRect(rows[0], modelCol, true)));
                table.editCellAt(rows[0], modelCol);
            } else {
                final String seed = guiUtils.getString(
                        "Common label to be applied to " + rows.length + " entries:", // msg
                        "Bulk Renaming", // title
                        (sntui != null) ? "Bookmark" : "Marker"); // default value
                if (seed == null) return; // user pressed cancel
                int idx = 1;
                for (final int viewRow : rows) {
                    final int modelRow = table.convertRowIndexToModel(viewRow);
                    model.setValueAt(String.format("%s%03d", seed, idx++), modelRow, 1);
                }
            }
        });
        pMenu.add(mi);

        // Color tags submenu
        final JMenu tagMenu = GuiUtils.MenuItems.colorTagMenu(sntui, color -> {
            if (noBookmarksError()) return;
            for (final int modelRow : getSelectedModelRowsAllIfNone()) {
                model.setValueAt(color, modelRow, 0); // if color is null user chose "Remove Tag"
            }
            if (highlightToggle != null && highlightToggle.isSelected()) showHighlights();
        });
        tagMenu.setText("Tag");
        tagMenu.setIcon(IconFactory.menuIcon((IconFactory.GLYPH.TAG)));
        pMenu.add(tagMenu);
        mi = new JMenuItem("Grouping Tags...", IconFactory.menuIcon(IconFactory.GLYPH.FILTER));
        mi.setToolTipText("Assign distinct colors to groups or individual entries");
        mi.addActionListener(e -> applyUniqueTags());
        tagMenu.addSeparator();
        tagMenu.add(mi);
        if (viewer != null) {
            mi = new JMenuItem("Size...", IconFactory.menuIcon(IconFactory.GLYPH.CIRCLE));
            mi.addActionListener(e -> {
                if (noBookmarksError()) return;
                final int[] modelRows = getSelectedModelRowsAllIfNone();
                final Double size = guiUtils.getDouble("Marker size (in calibrated units):",
                        "Marker Size", model.getDataList().get(modelRows[modelRows.length-1]).size);
                if (size == null) return;
                if (size.isNaN() || size < 0) {
                    guiUtils.error("Invalid value: Size must be a non-negative value.");
                } else {
                    for (final int modelRow : modelRows)
                        model.getDataList().get(modelRow).size = size.floatValue();
                    model.fireTableRowsUpdated(modelRows[0], modelRows[modelRows.length-1]);
                }
            });
            pMenu.add(mi);
        }
        pMenu.addSeparator();
        if (sntui != null) {
            mi = new JMenuItem("Colocalize...", IconFactory.menuIcon(IconFactory.GLYPH.LINK));
            mi.setToolTipText("Matches bookmarks across channels within a distance threshold, replacing them with centroids");
            mi.addActionListener(e -> colocalizeBookmarks());
            pMenu.add(mi);
        }

        mi = new JMenuItem("Merge...", IconFactory.menuIcon(IconFactory.GLYPH.ARROWS_TO_CIRCLE));
        mi.setToolTipText("Merges nearby entries, replacing them with centroids");
        mi.addActionListener(e -> mergeBookmarks());
        pMenu.add(mi);
        mi = new JMenuItem("Nearest Neighbor Distribution...", IconFactory.menuIcon(IconFactory.GLYPH.CHART));
        mi.addActionListener(e -> showNNDistribution());
        pMenu.add(mi);
        pMenu.addSeparator();

        mi = new JMenuItem("Delete...", IconFactory.menuIcon(IconFactory.GLYPH.TRASH));
        mi.addActionListener(e -> {
            if (noBookmarksError()) return;
            int[] modelRows = getSelectedModelRowsAllIfNone();
            if (modelRows.length == table.getRowCount()) {
                if (!guiUtils.getConfirmation("Delete all bookmarks?", "Delete All?")) {
                    return;
                }
                reset();
                recordCmd("reset()");
                return; // Don't continue to delete rows that no longer exist
            }
            modelRows = Arrays.stream(modelRows)
                    .boxed()
                    .sorted(Comparator.reverseOrder()) // Delete from end to preserve indices
                    .mapToInt(Integer::intValue)
                    .toArray();
            for (final int modelRow : modelRows)
                model.removeRow(modelRow);
        });
        pMenu.add(mi);

        pMenu.addSeparator();
        pMenu.add(sortByDistanceMenu());
        pMenu.add(GuiUtils.JTables.resetAndResizeColumnsMenuItem(
                table, () -> recordComment("Bookmark Manager: resizeColumns()"),
                columnWidthFractions()));
        // Detach/Dock and Searchable items are appended in assembleTable() so
        // they can reference the scroll pane and stay grouped with other
        // table-management actions (the Searchable items will be the last
        // entries in the menu).
        return pMenu;
    }

    private void applyUniqueTags() {
        if (noBookmarksError()) return;
        final String[] options = new String[]{
                "<HTML>Assign a distinct color to <b>each entry</b>",
                "<HTML>Group entries sharing the <b>exact same label</b>",
                "<HTML>Group entries by common <b>starting text</b>",
                "<HTML>Group entries by common <b>ending text</b>"};
        final String choice = guiUtils.getChoice("How to apply distinct color tags?",
                "Assign Grouping Tags", options, options[0]);
        if (choice == null) return; // prompt canceled

        final int[] modelRows = getSelectedModelRowsAllIfNone();
        int colorIdx = 0;

        if (options[0].equals(choice)) {
            final Color[] distinctColors = ColorMaps.glasbeyColorsAWT(modelRows.length);
            for (final int modelRow : modelRows) {
                model.setValueAt(distinctColors[colorIdx++], modelRow, 0);
            }
            if (sntui != null) sntui.showStatus(modelRows.length + " entries recolored", true);
        } else {
            // "Exact" handles e.g. imported CSVs where entries are already duplicated verbatim
            final boolean exact = options[1].equals(choice);
            final boolean leading = options[2].equals(choice); // only consulted when !exact
            // Group bookmarks by their label: verbatim (exact) or by a stemmed leading/trailing token
            final Map<String, List<Bookmark>> groupsMap = new TreeMap<>();
            int skipped = 0;
            for (final int modelRow : modelRows) {
                final String label = model.getDataList().get(modelRow).label;
                final String key = exact ? exactGroupKey(label) : extractGroupKey(label, leading);
                if (key.isEmpty()) {
                    skipped++;
                    continue;
                }
                groupsMap.computeIfAbsent(key, k -> new ArrayList<>()).add(model.getDataList().get(modelRow));
            }

            final Color[] distinctColors = ColorMaps.glasbeyColorsAWT(groupsMap.size());
            for (final List<Bookmark> bookmarkList : groupsMap.values()) {
                for (final Bookmark bookmark : bookmarkList) {
                    bookmark.setColor(distinctColors[colorIdx]);
                }
                colorIdx++;
            }

            // NB: fire a full refresh rather than a modelRows[0]..modelRows[last] range: modelRows is only
            // guaranteed ascending in *view* order not in model-index order
            if (!groupsMap.isEmpty()) model.fireTableDataChanged();

            final String msg = groupsMap.isEmpty()
                        ? "No groups could be formed (labels may be empty, or none share a common token)."
                        : String.format("%d entries grouped into %d unique group(s)%s",
                                modelRows.length - skipped, groupsMap.size(),
                                (skipped > 0) ? String.format(", %d skipped (no usable label)", skipped) : ".");
            guiUtils.centeredMsg(msg, "Group Tagging Complete");
        }
        if (highlightToggle != null && highlightToggle.isSelected()) showHighlights();
    }

    /**
     * Extracts a grouping key from a bookmark label with no stemming: the label is used verbatim
     * (trimmed, lower-cased). Suited for labels that are already duplicated as-is (e.g. an imported
     * CSV with entries "M1", "M1", "M2", "M2"...), where stemming would incorrectly merge them.
     *
     * @param label the raw label; may be {@code null} or blank
     * @return the trimmed, lower-cased label, or an empty string if blank
     */
    private static String exactGroupKey(final String label) {
        return (label == null) ? "" : label.trim().toLowerCase();
    }

    /**
     * Extracts a grouping key from a bookmark label by splitting it at common punctuation/whitespace
     * delimiters and at letter-to-digit boundaries (e.g. "Soma 1", "Soma-2", "Soma3" all split into
     * a leading token of "soma"; "Left Soma", "Right Soma" both split into a trailing token of "soma").
     *
     * @param label   the raw label; may be {@code null} or blank
     * @param leading if true, the first non-empty token is used as the key (matches a common leading
     *                seed, e.g. an enumerated prefix); if false, the last non-empty token is used
     *                (matches a common trailing seed, e.g. a shared descriptive suffix)
     * @return the extracted key (lower-case, trimmed), or an empty string if no usable token exists
     */
    private static String extractGroupKey(final String label, final boolean leading) {
        if (label == null) return "";
        final String cleanLabel = label.trim().toLowerCase();
        if (cleanLabel.isEmpty()) return "";
        // Splits text at punctuation/spaces OR at the boundary between letters and digits
        final String[] parts = cleanLabel.split("[\\s,;:\\-()]+|(?<=[a-z])(?=\\d)");
        if (leading) {
            for (final String part : parts) {
                final String trimmed = part.trim();
                if (!trimmed.isEmpty()) return trimmed;
            }
        } else {
            for (int i = parts.length - 1; i >= 0; i--) {
                final String trimmed = parts[i].trim();
                if (!trimmed.isEmpty()) return trimmed;
            }
        }
        return "";
    }

    private int[] getSelectedModelRowsAllIfNone() {
        int[] viewRows = table.getSelectedRows();
        if (viewRows.length == 0) {
            viewRows = IntStream.range(0, table.getRowCount()).toArray();
        }
        for (int i = 0; i < viewRows.length; i++) {
            viewRows[i] = table.convertRowIndexToModel(viewRows[i]);
        }
        return viewRows;
    }

    private void colocalizeBookmarks() {
        if (noBookmarksError()) return;
        if (viewer != null) {
            guiUtils.error("Colocalization is not available in BVV mode.");
            return;
        }
        final List<Bookmark> candidates = getSelectedBookmarks();
        final long distinctChannels = candidates.stream().mapToInt(b -> b.c).distinct().count();
        if (distinctChannels < 2) {
            guiUtils.error("Colocalization requires bookmarks from at least 2 channels.");
            return;
        }
        final Double threshold = guiUtils.getDouble(
                "<HTML>Max. distance between colocalized bookmarks (physical units):",
                "Colocalize Bookmarks", 5.0);
        if (threshold == null || threshold <= 0) return;
        // Group by channel; match across channels
        final Map<Integer, List<Bookmark>> byChannel = new LinkedHashMap<>();
        for (final Bookmark b : candidates) {
            byChannel.computeIfAbsent(b.c, k -> new ArrayList<>()).add(b);
        }
        final List<Integer> channels = new ArrayList<>(byChannel.keySet());
        final List<Bookmark> seedList = byChannel.get(channels.getFirst());
        final List<List<Bookmark>> otherLists = new ArrayList<>();
        for (int ci = 1; ci < channels.size(); ci++)
            otherLists.add(byChannel.get(channels.get(ci)));
        final MergeResult result = greedyMerge(seedList, otherLists, threshold, 2, "Coloc");
        applyMergeResult(result, "Colocalize Bookmarks", "colocalized", "colocalize(" + threshold + ")");
    }

    private void mergeBookmarks() {
        if (noBookmarksError()) return;
        final List<Bookmark> candidates = getSelectedBookmarks();
        final String obj = (viewer == null) ? "bookmarks" : "markers";
        if (candidates.size() < 2) {
            guiUtils.error("At least 2 "+ obj + " are required for merging.");
            return;
        }
        final Double threshold = guiUtils.getDouble(
                "<HTML>Max. distance between "+ obj + " to be merged (physical units):",
                "Merge Locations", 5.0);
        if (threshold == null || threshold <= 0) return;
        final double thresholdSq = threshold * threshold;
        final Map<Integer, List<Bookmark>> byChannel = new LinkedHashMap<>();
        for (final Bookmark b : candidates)
            byChannel.computeIfAbsent(b.c, k -> new ArrayList<>()).add(b);
        final Set<Bookmark> allConsumed = new HashSet<>();
        final List<Bookmark> allMerged = new ArrayList<>();
        for (final Map.Entry<Integer, List<Bookmark>> entry : byChannel.entrySet()) {
            final List<Bookmark> chBookmarks = entry.getValue();
            if (chBookmarks.size() < 2) continue;
            final Set<Bookmark> consumed = new HashSet<>();
            final String chPrefix = (sntui != null) ? "Merged Ch" + entry.getKey() + " " : "Merged ";
            for (final Bookmark seed : chBookmarks) {
                if (consumed.contains(seed)) continue;
                final List<Bookmark> group = new ArrayList<>();
                group.add(seed);
                for (final Bookmark other : chBookmarks) {
                    if (other == seed || consumed.contains(other) || other.t != seed.t) continue;
                    if (seed.distanceSquaredTo(other) <= thresholdSq)
                        group.add(other);
                }
                if (group.size() >= 2) {
                    consumed.addAll(group);
                    final double cx = group.stream().mapToDouble(b -> b.x).average().orElse(seed.x);
                    final double cy = group.stream().mapToDouble(b -> b.y).average().orElse(seed.y);
                    final double cz = group.stream().mapToDouble(b -> b.z).average().orElse(seed.z);
                    final String label = model.getUniqueLabel(chPrefix);
                    final Bookmark merged = new Bookmark(label, cx, cy, cz, seed.c, seed.t, seed.getColor());
                    if (viewer != null)
                        merged.size = (float) group.stream().mapToDouble(b -> b.size).average().orElse(seed.size);
                    allMerged.add(merged);
                    allConsumed.addAll(consumed);
                }
            }
        }
        applyMergeResult(new MergeResult(allConsumed, allMerged),
                "Merge Bookmarks", "merged", "merge(" + threshold + ")");
    }

    private List<Bookmark> getSelectedBookmarks() {
        final int[] modelRows = getSelectedModelRowsAllIfNone();
        final List<Bookmark> candidates = new ArrayList<>(modelRows.length);
        for (final int modelRow : modelRows) {
            candidates.add(model.getDataList().get(modelRow));
        }
        return candidates;
    }

    private void showNNDistribution() {
        if (noBookmarksError()) return;
        final List<Bookmark> bookmarks = getSelectedBookmarks();
        if (bookmarks.size() < 2) {
            guiUtils.error("Not enough entries selected.");
        } else {
            final NodeStatistics<Bookmark> nodeStatistics = new NodeStatistics<>(bookmarks);
            nodeStatistics.getHistogram(NodeStatistics.NEAREST_NEIGHBOR_DISTANCE).show("NNDistances");
        }
    }

    private JMenu sortByDistanceMenu() {
        final JMenu menu = new JMenu("Sort by Distance");
        menu.setIcon(IconFactory.menuIcon(IconFactory.GLYPH.SORT));
        JMenuItem item = new JMenuItem("To Reference Location...", IconFactory.menuIcon(IconFactory.GLYPH.CROSSHAIR));
        item.addActionListener(e -> {
            if (noBookmarksError()) return;
            final SNTPoint input = SNTPoint.average(getSelectedBookmarks());
            final SNTPoint ref = guiUtils.getCoordinates("", "Reference Point (Physical Distances)",
                    input, 2, null);
            if (ref != null) sortByPosition(new Bookmark("reference", ref.getX(), ref.getY(), ref.getZ(), 1, 1));
        });
        menu.add(item);
        item = new JMenuItem("To Selected Row Location", IconFactory.menuIcon(IconFactory.GLYPH.POINTER));
        item.addActionListener(e -> {
            if (noBookmarksError()) return;
            final List<Bookmark> selection = getSelectedBookmarks();
            if (selection.size() > 1) {
                guiUtils.error("Select a single row to be used as reference location and re-run.");
            } else {
                sortByPosition(selection.getFirst());
            }
        });
        menu.add(item);
        return menu;
    }

    private void sortByPosition(final Bookmark origin) {
        if (table.isEditing()) table.getCellEditor().stopCellEditing();
        // Sort the backing list directly by 3D distance to origin
        model.getDataList().sort(Comparator.comparingDouble(b -> b.distanceSquaredTo(origin)));
        // Clear active column sort keys so the sorter does not re-order our result
        if (table.getRowSorter() instanceof TableRowSorter<?> sorter) sorter.setSortKeys(null);
        model.fireTableDataChanged();
    }

    /**
     * Greedy nearest-match merge. For each seed, finds the closest unconsumed
     * bookmark in each target list within the threshold. Groups with at least
     * {@code minGroupSize} members are merged to their centroid.
     *
     * @param seeds       the seed bookmarks
     * @param targetLists lists of bookmarks to match against (may include seeds)
     * @param threshold   max distance
     * @param minGroupSize minimum group size to form a merge (2 for both operations)
     * @param labelPrefix prefix for the merged bookmark label
     * @return the merge result containing consumed and merged bookmarks
     */
    private MergeResult greedyMerge(final List<Bookmark> seeds,
                                    final List<List<Bookmark>> targetLists,
                                    final double threshold, final int minGroupSize,
                                    final String labelPrefix) {
        final double thresholdSq = threshold * threshold;
        final Set<Bookmark> consumed = new HashSet<>();
        final List<Bookmark> merged = new ArrayList<>();
        for (final Bookmark seed : seeds) {
            if (consumed.contains(seed)) continue;
            final List<Bookmark> group = new ArrayList<>();
            group.add(seed);
            for (final List<Bookmark> others : targetLists) {
                Bookmark closest = null;
                double closestDistSq = Double.MAX_VALUE;
                for (final Bookmark other : others) {
                    if (other == seed || consumed.contains(other) || other.t != seed.t) continue;
                    final double distSq = seed.distanceSquaredTo(other);
                    if (distSq <= thresholdSq && distSq < closestDistSq) {
                        closestDistSq = distSq;
                        closest = other;
                    }
                }
                if (closest != null) group.add(closest);
            }
            if (group.size() >= minGroupSize) {
                consumed.addAll(group);
                final double cx = group.stream().mapToDouble(b -> b.x).average().orElse(seed.x);
                final double cy = group.stream().mapToDouble(b -> b.y).average().orElse(seed.y);
                final double cz = group.stream().mapToDouble(b -> b.z).average().orElse(seed.z);
                final String chLabel = group.stream().map(b -> "C" + b.c)
                        .distinct().collect(java.util.stream.Collectors.joining("+"));
                final String label = model.getUniqueLabel(labelPrefix + " " + chLabel + " ");
                merged.add(new Bookmark(label, cx, cy, cz, seed.c, seed.t, seed.getColor()));
            }
        }
        return new MergeResult(consumed, merged);
    }

    private void applyMergeResult(final MergeResult result, final String dialogTitle,
                                  final String verb, final String recordSuffix) {
        if (result.merged.isEmpty()) {
            guiUtils.error("No bookmarks could be " + verb + " within the specified distance.");
            return;
        }
        final String suffix = (result.merged.size()==1) ? " entry" : " entries";
        if (!guiUtils.getConfirmation(
                result.consumed.size() + " bookmarks will be replaced by "
                        + result.merged.size() + " " + verb +  suffix + ". Proceed?",
                dialogTitle)) {
            return;
        }
        model.getDataList().removeAll(result.consumed);
        model.getDataList().addAll(result.merged);
        model.fireTableDataChanged();
        if (sntui != null)
            sntui.showStatus(result.merged.size() + " " + verb + " bookmark(s) created", true);
        recordComment("Bookmark Manager: " + recordSuffix);
    }

    private record MergeResult(Set<Bookmark> consumed, List<Bookmark> merged) {}

    private void recordCmd(final String cmd) {
        if (sntui == null || sntui.getRecorder(false) == null) return;
        sntui.getRecorder(false).recordCmd("snt.getUI().getBookmarkManager()." + cmd);
    }

    private void recordComment(final String comment) {
        if (sntui == null || sntui.getRecorder(false) == null) return;
        sntui.getRecorder(false).recordComment(comment);
    }

    private JPopupMenu importMenu() {
        final JPopupMenu menu = new JPopupMenu();
        GuiUtils.addSeparator(menu, "Import:");
        JMenuItem jmi  = new JMenuItem("From CSV File...", IconFactory.menuIcon(IconFactory.GLYPH.TABLE));
        menu.add(jmi);
        jmi.addActionListener(e -> {
            final File file = (sntui != null) ? sntui.openFile("csv")
                    : guiUtils.getFile(new File(SNTPrefs.lastKnownDir(), "Markers.csv"), "csv");
            if (file != null) {
                recordCmd("load(\"" + file.getAbsolutePath() + "\")");
                loadBookmarksFromFile(file);
                if (sntui != null) sntui.showStatus(model.getDataList().size() + " listed bookmarks ", true);
            }
        });
        if (sntui != null) {
            jmi = new JMenuItem("From Image Overlay", IconFactory.menuIcon(IconFactory.GLYPH.IMAGE));
            menu.add(jmi);
            jmi.addActionListener(e -> {
                final ImagePlus imp = sntui.plugin.getImagePlus();
                if (imp == null) {
                    sntui.guiUtils.error("No image is currently loaded.");
                    return;
                }
                if (imp.getOverlay() == null || imp.getOverlay().size() == 0) {
                    sntui.guiUtils.error("Image Overlay contains no ROIs.");
                    return;
                }
                load(imp.getOverlay().toArray());
                sntui.showStatus(model.getDataList().size() + " listed bookmarks ", true);
                recordCmd("load(snt.getInstance().getImagePlus().getOverlay().toArray())");
                clearImportedOverlay(imp);
            });
            jmi = new JMenuItem("From ROI Manager", IconFactory.menuIcon(IconFactory.GLYPH.LIST_ALT));
            menu.add(jmi);
            jmi.addActionListener(e -> {
                RoiManager rm = RoiManager.getInstance2();
                if (rm == null || rm.getCount() == 0) {
                    sntui.guiUtils.error("ROI Manager is either closed or empty.");
                    return;
                }
                load(rm.getRoisAsArray());
                sntui.showStatus(model.getDataList().size() + " listed bookmarks ", true);
                recordComment("rm = ij.plugin.frame.RoiManager.getInstance2()");
                recordCmd("load(rm.getRoisAsArray())");
            });
            menu.addSeparator();
            jmi = new JMenuItem("From Workspace...", IconFactory.menuIcon('\ue066', true));
            menu.add(jmi);
            jmi.addActionListener(e -> {
                final File workspaceDir = sntui.getOrPromptForWorkspace();
                if (workspaceDir == null) return;
                final String prefix = sntui.getImageFilenamePrefix();
                final File ref = new File(workspaceDir, prefix + "_bookmarks.csv");
                final File file = (ref.exists()) ? ref : sntui.guiUtils.getFile(ref, ".csv");
                if (file != null) loadBookmarksFromFile(file);
            });
        }
        return menu;
    }

    private void clearImportedOverlay(final ImagePlus imp) {
        final Overlay ov = imp.getOverlay();
        if (ov == null || ov.size() == 0) return;
        final boolean nag = sntui.plugin.getPrefs().getTemp("clear-imported-overlay-nag", true);
        boolean wipe = sntui.plugin.getPrefs().getTemp("clear-imported-overlay", true);
        if (nag) {
            final boolean[] options = guiUtils.getPersistentConfirmation(
                    "Now that ROIs have been imported as bookmarks, remove them from the image overlay? "
                            + "(This clears all ROIs from the overlay, including any drawn by other tools.)",
                    "Remove Imported Overlay?");
            sntui.plugin.getPrefs().setTemp("clear-imported-overlay", wipe = options[0]);
            sntui.plugin.getPrefs().setTemp("clear-imported-overlay-nag", !options[1]);
        }
        if (wipe) {
            // Preserve our own highlight ROIs (named with HIGHLIGHT_PREFIX) so the
            // toggle doesn't desync when the user happens to clear during a highlight
            final Roi[] all = ov.toArray();
            for (int i = all.length - 1; i >= 0; i--) {
                final String name = all[i].getName();
                if (name != null && name.startsWith(HIGHLIGHT_PREFIX)) continue;
                ov.remove(i);
            }
            imp.updateAndDraw();
        }
    }

    private JPopupMenu exportMenu() {
        final JPopupMenu menu = new JPopupMenu();
        GuiUtils.addSeparator(menu, "Export:");
        JMenuItem jmi = new JMenuItem("To CSV File...", IconFactory.menuIcon(IconFactory.GLYPH.TABLE));
        menu.add(jmi);
        jmi.addActionListener(e -> saveToUserChosenFile(null));
        if (sntui != null) {
            jmi = new JMenuItem("To Image Overlay", IconFactory.menuIcon(IconFactory.GLYPH.IMAGE));
            jmi.setToolTipText("The Image Overlay is automatically saved in the image header of TIFF images");
            menu.add(jmi);
            jmi.addActionListener(e -> {
                if (noBookmarksError()) return;
                final ImagePlus imp = sntui.plugin.getImagePlus();
                if (imp == null) {
                    sntui.guiUtils.error("No image is currently loaded.");
                    return;
                }
                table.clearSelection();
                if (imp.getOverlay() == null) imp.setOverlay(new Overlay());
                toOverlay(imp.getOverlay());
                sntui.showStatus(model.getDataList().size() + " bookmarks exported to the Image Overlay", true);
                recordCmd("clearSelection()");
                recordCmd("toOverlay(snt.getInstance().getImagePlus().getOverlay())");
            });
            jmi = new JMenuItem("To ROI Manager", IconFactory.menuIcon(IconFactory.GLYPH.LIST_ALT));
            menu.add(jmi);
            jmi.addActionListener(e -> {
                if (noBookmarksError()) return;
                table.clearSelection();
                toRoiManager();
                recordCmd("clearSelection()");
                recordCmd("toRoiManager()");
            });
            menu.addSeparator();
            jmi = new JMenuItem("To Workspace...", IconFactory.menuIcon('\ue066', true));
            menu.add(jmi);
            jmi.addActionListener(e -> {
                final File workspaceDir = sntui.getOrPromptForWorkspace();
                if (workspaceDir == null) return;
                final String prefix = sntui.getImageFilenamePrefix();
                saveToUserChosenFile(new File(sntui.getPrefs().getWorkspaceDir(), prefix + "_bookmarks.csv"));
            });
        }
        return menu;
    }

    private void saveToUserChosenFile(final File file) {
        if (noBookmarksError()) return;
        final File saveFile = (file != null) ? file
                : (sntui != null) ? sntui.saveFile("Export Bookmarks to CSV...", "SNT_Bookmarks.csv", "csv")
                : guiUtils.getSaveFile("Export Markers to CSV...", new File(SNTPrefs.lastKnownDir(), "Markers.csv"), "csv");
        if (saveFile != null) {
            recordCmd("save(\"" + saveFile.getAbsolutePath() + "\")");
            if (saveBookMarksToFile(saveFile)) {
                if (sntui != null) sntui.showStatus("Export complete.", true);
            } else {
                if (sntui != null) sntui.showStatus("Exporting failed.", true);
                guiUtils.error("Exporting failed. See Console for details.");
            }
        }
    }

    /**
     * Adds (or refreshes) overlay highlights for the currently selected
     * bookmarks. Re-invoking re-evaluates the current selection (used by
     * the table's ListSelectionListener to keep highlights in sync). Sole
     * entry point for showing highlights; syncs {@link #highlightToggle}
     * state.
     */
    private void showHighlights() {
        if (noBookmarksError()) {
            syncHighlightToggle(false);
            return;
        }
        final ImagePlus imp = sntui.plugin.getImagePlus();
        if (imp == null) {
            noImageOpenError();
            syncHighlightToggle(false);
            return;
        }
        Overlay overlay = imp.getOverlay();
        if (overlay == null) {
            overlay = new Overlay();
            imp.setOverlay(overlay);
        }
        // Remove any existing highlights first (so re-clicking refreshes against the current selection)
        removeHighlightROIs(overlay);
        final int[] modelRows = getSelectedModelRowsAllIfNone();
        int idx = 0;
        for (final int modelRow : modelRows) {
            final Bookmark b = model.getDataList().get(modelRow);
            final PointRoi roi = b.toRoi(worldToPixel(b));
            roi.setName(HIGHLIGHT_PREFIX + idx++);
            roi.setPointType(PointRoi.DOT);
            roi.setSize(PointRoi.getDefaultSize()); // See PrefsCmd. mid size is 3: 0=tiny, 6=XXXL
            if (roi.getStrokeColor() == null)
                roi.setStrokeColor(Color.CYAN);
            overlay.add(roi);
        }
        imp.updateAndDraw();
        sntui.showStatus(modelRows.length + " bookmark(s) highlighted on overlay", true);
        syncHighlightToggle(true);
    }

    /**
     * Removes highlight ROIs from the overlay. Sole entry point for hiding
     * highlights; syncs {@link #highlightToggle} state.
     */
    private void hideHighlights() {
        final ImagePlus imp = (sntui == null) ? null : sntui.plugin.getImagePlus();
        if (imp != null && imp.getOverlay() != null && removeHighlightROIs(imp.getOverlay())) {
            imp.updateAndDraw();
            if (sntui != null) sntui.showStatus("Highlights cleared", true);
        }
        syncHighlightToggle(false);
    }

    /** Updates the toggle without retriggering its action listener. */
    private void syncHighlightToggle(final boolean selected) {
        if (highlightToggle == null || highlightToggle.isSelected() == selected) return;
        // Detach listeners briefly so toggling state programmatically doesn't loop back into show/hide
        final java.awt.event.ActionListener[] ls = highlightToggle.getActionListeners();
        for (final java.awt.event.ActionListener l : ls) highlightToggle.removeActionListener(l);
        highlightToggle.setSelected(selected);
        for (final java.awt.event.ActionListener l : ls) highlightToggle.addActionListener(l);
    }

    /**
     * Removes all highlight ROIs (those with the {@link #HIGHLIGHT_PREFIX} name)
     * from the given overlay.
     *
     * @return true if any ROIs were removed
     */
    private static boolean removeHighlightROIs(final Overlay overlay) {
        if (overlay == null) return false;
        final Roi[] rois = overlay.toArray();
        boolean removed = false;
        for (int i = rois.length - 1; i >= 0; i--) {
            if (rois[i].getName() != null && rois[i].getName().startsWith(HIGHLIGHT_PREFIX)) {
                overlay.remove(i);
                removed = true;
            }
        }
        return removed;
    }

    private JToolBar assembleHighlightToolbar() {
        final JToolBar tb = new JToolBar();
        tb.setFloatable(false);
        tb.add(GuiUtils.shortSmallMsg("To visit a bookmarked location: Double-click on its entry.", false));
        tb.add(Box.createHorizontalGlue());
        if (sntui != null) { // no functionality in BVV Markers table
            tb.addSeparator();
            highlightToggle = new JToggleButton(IconFactory.buttonIcon('\uf591', true));
            highlightToggle.setSelectedIcon(IconFactory.buttonIcon('\uf591', true, IconFactory.selectedColor()));
            highlightToggle.setToolTipText("<html>Highlight bookmark locations on the image. With nothing selected,<br>"
                    + "all bookmarks are highlighted; otherwise only the selected ones.<br>"
                    + "Click again to toggle.");
            highlightToggle.addActionListener(e -> {
                if (highlightToggle.isSelected()) showHighlights();
                else hideHighlights();
            });
            tb.add(highlightToggle);
        }
        return tb;
    }

    private JToolBar assembleToolbar() {
        final JButton impButton = GuiUtils.Buttons.OptionsButton(IconFactory.GLYPH.IMPORT, 1f, importMenu());
        impButton.setToolTipText("Import bookmarks");
        final JButton expButton = GuiUtils.Buttons.OptionsButton(IconFactory.GLYPH.EXPORT, 1f, exportMenu());
        expButton.setToolTipText("Export bookmarks");
        final JToolBar tb = new JToolBar();
        tb.setFloatable(false);
        tb.add(impButton);
        tb.add(expButton);
        tb.addSeparator();
        tb.add(GuiUtils.Buttons.toolbarButton(goToAction(), "Zoom into a specific coordinate"));
        if (viewer != null) {
            tb.add(Box.createHorizontalGlue());
            // Navigation: Prev / Next / Reset
            tb.addSeparator();
            final JButton prevButton = new JButton(IconFactory.menuIcon(IconFactory.GLYPH.NEXT));
            prevButton.setToolTipText("Fly to previous marker");
            prevButton.addActionListener(e -> {
                final int row = table.getSelectedRow();
                final int target = (row <= 0) ? table.getRowCount() - 1 : row - 1;
                if (target >= 0) {
                    table.setRowSelectionInterval(target, target);
                    table.scrollRectToVisible(table.getCellRect(target, 0, true));
                    flyTo(target);
                }
            });
            final JButton nextButton = new JButton(IconFactory.menuIcon(IconFactory.GLYPH.PREVIOUS));
            nextButton.setToolTipText("Fly to next marker");
            nextButton.addActionListener(e -> {
                final int row = table.getSelectedRow();
                final int target = (row < 0 || row >= table.getRowCount() - 1) ? 0 : row + 1;
                if (target < table.getRowCount()) {
                    table.setRowSelectionInterval(target, target);
                    table.scrollRectToVisible(table.getCellRect(target, 0, true));
                    flyTo(target);
                }
            });
            final JButton helpButton = GuiUtils.Buttons.help(null);
            helpButton.addActionListener(e -> bigViewerMarkerHelp(viewer));
            tb.add(prevButton);
            tb.add(nextButton);
            tb.addSeparator();
            tb.add(Box.createHorizontalGlue());
            // Inject any viewer-provided toolbar buttons (e.g. slab-clip toggle)
            if (!viewerToolbarButtons.isEmpty()) {
                tb.addSeparator();
                viewerToolbarButtons.forEach(tb::add);
            }
            tb.add(Box.createHorizontalGlue());
            tb.add(helpButton);
        }
        if (sntui != null) {
            tb.addSeparator();
            tb.add(Box.createHorizontalGlue());
            final JSpinner spinner = visitingZoom.buildSpinner();
            spinner.setToolTipText("The preferred zoom level (between 25 and 3200%) for visiting a bookmarked location");
            final JButton autoButton = GuiUtils.Buttons.undo();
            autoButton.setToolTipText("<HTML>Resets level to two <i>Zoom In [+]</i> operations above the current image zoom");
            autoButton.addActionListener(e -> {
                if (null == sntui.plugin.getImagePlus()) {
                    sntui.showStatus("Current zoom unknown: No image is loaded...", true);
                } else {
                    visitingZoom.resetFor(sntui.plugin.getImagePlus());
                    spinner.setValue(visitingZoom.percentage());
                }
            });
            tb.add(new JLabel("Visiting zoom level (%): "));
            tb.add(spinner);
            tb.add(autoButton);
        }
        return tb;
    }

    Action goToAction() {
        return new AbstractAction("GoTo", IconFactory.menuIcon('\ue4be', true)) {
            @Override
            public void actionPerformed(final ActionEvent e) {
                if (viewer == null && sntui.plugin.getImagePlus() == null) {
                    noImageOpenError();
                    return;
                }
                final String clipText = GuiUtils.getClipboardText();
                final String pos = guiUtils.getString(
                        "Location XYZ coordinates, in physical units (comma/space separated): ",
                        "Go To Location...",
                        (clipText != null && clipText.chars().anyMatch(Character::isDigit)) ? clipText.trim() : null);
                if (pos == null) return;
                try {
                    final PointInImage pim = SNTPoint.fromString(pos);
                    // encode position in bookmark label so it is displayed in viewer (see flyTo)
                    final Bookmark b = new Bookmark(String.format("%.2f, %.2f, %.2f", pim.x, pim.y, pim.z),
                            pim.x, pim.y, pim.z, 1, 1);
                    if (viewer != null) {
                        flyTo(b);
                    } else {
                        goTo(b, sntui.plugin.getImagePlus(), SNT.XY_PLANE);
                        if (!sntui.plugin.getSinglePane()) {
                            final ImagePlus zyImp = sntui.plugin.getImagePlus(SNT.ZY_PLANE);
                            if (zyImp != null) goTo(b, zyImp, SNT.ZY_PLANE);
                            final ImagePlus xzImp = sntui.plugin.getImagePlus(SNT.XZ_PLANE);
                            if (xzImp != null) goTo(b, xzImp, SNT.XZ_PLANE);
                        }
                        sntui.showStatus(String.format("Zoomed to %s", b.label), true);

                    }
                } catch (final Throwable ex) {
                    guiUtils.error("Could not extract a valid location from \"" + pos + "\".");
                }
            }
        };
    }

    private void bigViewerMarkerHelp(final AbstractBigViewer viewer) {
        final String viewerType = (viewer instanceof Bvv) ? "BVV" : "BDV";
        final String markerType = (viewer instanceof Bvv) ? "spheres" : "circles";
        final String MARKER_HELP_MSG =
                "<html><body style='width:350px; font-family:sans-serif'>" +
                        "<h3>Placing Markers (M key)</h3>" +
                        "Press <b>M</b> in " + viewerType + " to place a marker at the current cursor position. " +
                        "Markers are rendered as " + markerType + " and listed in this table in calibrated coordinates." +
                        "<h3>Toggling Markers (H key)</h3>" +
                        "Press <b>H</b> in the viewer to temporarily hide markers. Use <i>Rendering options</i> in the " +
                        "SNT Annotations toolbar to set their opacity." +
                        "<h3>Activating Markers</h3>" +
                        "In " + viewerType + ", click on a marker to have its row selected. In the table, select a row " +
                        "to have the marker highlighted in the viewer." +
                        "<h3>Navigation</h3>" +
                        "Double-click a row to fly to that marker. Use the <b>&uarr;</b> / <b>&darr;</b> " +
                        "buttons/keys to step through markers sequentially. Use the contextual menu for positional sorting, " +
                        "size/color adjustments, etc." +
                        "</body></html>";
        new GuiUtils((table==null) ? null : table.getParent())
                .showHTMLDialog(MARKER_HELP_MSG, "About " + viewerType + " Markers", false);
    }

    private boolean noBookmarksError() {
        final List<Bookmark> list = model.getDataList();
        if (list.isEmpty()) {
            final String msg = (viewer != null)
                    ? "No markers exist. Use the M key to place markers."
                    : "No bookmarks exist. To create one, right-click on the image and choose \"Bookmark cursor location\" (Shift+B).";
            guiUtils.error(msg);
            return true;
        }
        return false;
    }

    private void goTo(final int row, final ImagePlus imp, final int plane) {
        goTo(model.getDataList().get(table.convertRowIndexToModel(row)), imp, plane);
    }

    private void goTo(final Bookmark b, final ImagePlus imp, final int plane) {
        assert imp != null;

        // Bookmark coordinates are always world/calibrated; convert to pixel space, which is
        // what ImagePlus/ImageCanvas navigation (setPosition, ImpUtils.zoomTo) expects. Uses
        // worldToPixel() (spacing + world-origin offset), not imp.getCalibration() alone, since
        // the latter has no notion of SNT's world-origin offset (see #worldToPixel)
        final PointInImage pixelPos = worldToPixel(b);
        final double px = pixelPos.x;
        final double py = pixelPos.y;
        final double pz = pixelPos.z;

        // Transform coordinates based on plane
        final double viewX, viewY;
        viewY = switch (plane) {
            case SNT.ZY_PLANE -> {
                viewX = pz;
                yield py;
            }
            case SNT.XZ_PLANE -> {
                viewX = px;
                yield pz;
            }
            default -> {
                viewX = px;
                yield py;
            }
        };

        if (viewX > imp.getWidth() || viewY > imp.getHeight()) {
            // Only show error for the main XY plane
            if (plane == SNT.XY_PLANE) {
                sntui.guiUtils.error("Location is outside image XY dimensions");
            }
            return;
        }
        if (plane == SNT.XY_PLANE) {
            // NB: ImagePlus#setPosition uses 1-based slice indices; pz is 0-based (see worldToPixel)
            imp.setPosition(b.c, (int) pz + 1, b.t);
        }
        // Side views don't need setPosition - they show all Z by definition
        ImpUtils.zoomTo(imp, visitingZoom.fraction(), (int) viewX, (int) viewY);
    }

    private void goTo(final int row, final ImagePlus imp) {
        goTo(row, imp, SNT.XY_PLANE);
    }

    /** Viewer mode: animates the camera to the world position of the selected marker row. */
    private void flyTo(final int row) {
        flyTo(model.getDataList().get(table.convertRowIndexToModel(row)));
    }

    private void flyTo(final Bookmark b) {
        if (viewer == null) return;
        final net.imglib2.realtransform.AffineTransform3D current = viewer.getViewerTransform();
        // The viewer transform maps world -> screen. To centre the bookmark on
        // screen we keep the current rotation/scale but adjust the translation
        // so that the bookmark's world position maps to the screen centre.
        // screenPos = R * worldPos + t, so t_new = screenCentre - R * worldPos
        final double[] worldPos = {b.getX(), b.getY(), b.getZ()};
        final double[] mapped = new double[3];
        current.apply(worldPos, mapped);
        // mapped = R * worldPos + t_current, so R * worldPos = mapped - t_current
        final double rx = mapped[0] - current.get(0, 3);
        final double ry = mapped[1] - current.get(1, 3);
        final double rz = mapped[2] - current.get(2, 3);
        final double cX = viewer.getViewerWidth()  / 2.0;
        final double cY = viewer.getViewerHeight() / 2.0;
        final net.imglib2.realtransform.AffineTransform3D target = current.copy();
        target.set(cX - rx, 0, 3);
        target.set(cY - ry, 1, 3);
        target.set(   - rz, 2, 3);
        viewer.setViewerTransform(target, 300);
        // The camera move above only handles space; jump to the marker's own timepoint too
        if (viewer.getCurrentTimepoint() != b.t) viewer.setCurrentTimepoint(b.t);
        viewer.showViewerMessage(String.format("Flying to %s", b.label));
    }

    private void loadBookmarksFromFile(final File file) {
        try {
            model.populateFromFile(file);
        } catch (final Exception ex) {
            guiUtils.error(ex.getMessage() + ".");
            SNTUtils.error("loadBookmarksFromFile() failure", ex);
        }
    }

    private void loadBookmarksFromFile(final String filePathOrURL) {
        try {
            model.populateFromFile(filePathOrURL);
        } catch (final Exception ex) {
            guiUtils.error(ex.getMessage() + ".");
            SNTUtils.error("loadBookmarksFromFile() failure", ex);
        }
    }

    /**
     * BVV mode: adds a marker at the specified world coordinates.
     * The marker is auto-labeled and immediately rendered in the BVV overlay.
     *
     * @param x world x-coordinate
     * @param y world y-coordinate
     * @param z world z-coordinate
     */
    public void add(final double x, final double y, final double z) {
        final List<Bookmark> data = model.getDataList();
        // Use default color/size. Inherit label from the previous entry for continuity
        final Color color = (viewer != null) ? viewer.getDefaultMarkerColor() : data.getLast().getColor();
        final float size  = (viewer != null) ? viewer.getDefaultMarkerSize() : data.getLast().size;
        // Strip trailing (N) or bare number suffixes to recover the base label,
        // e.g. "Terminal (3)" > "Terminal", "Marker (2) (2)" > "Marker"
        final String inheritedLabel;
        if (data.isEmpty()) {
            inheritedLabel = "Marker";
        } else {
            final String prev = data.getLast().label;
            inheritedLabel = prev.replaceAll("(\\s*\\(\\d+\\))+$", "").replaceAll("\\s*\\d+$", "").strip();
        }
        final String label = model.getUniqueLabel(inheritedLabel.isBlank() ? "Marker" : inheritedLabel);
        // Channel has no live equivalent in Bvv/Bdv (see OverlayRenderer's CT-filter): always 1, displayed as a dash
        final int t = (viewer != null) ? viewer.getCurrentTimepoint() : 1;
        final Bookmark b = new Bookmark(label, x, y, z, 1, t, color);
        b.size = size;
        addOne(b);
    }

    /**
     * BVV mode: adds a marker at the specified world coordinates with a color and size.
     *
     * @param label the marker's label (will be made unique if a repeated entry exists)
     * @param x     world x-coordinate
     * @param y     world y-coordinate
     * @param z     world z-coordinate
     * @param color the marker color, or {@code null} for the viewer default
     * @param size  the sphere radius in world units; 0 uses the viewer default
     */
    public void add(final String label, final double x, final double y, final double z, final Color color, final float size) {
        final String uniqueLabel = model.getUniqueLabel(label);
        final int t = (viewer != null) ? viewer.getCurrentTimepoint() : 1;
        final Bookmark b = new Bookmark(uniqueLabel, x, y, z, 1, t, color);
        b.size = size;
        addOne(b);
    }

    /**
     * BVV mode: adds a marker at the specified world coordinates with a color and size, and default label.
     *
     * @param x     world x-coordinate
     * @param y     world y-coordinate
     * @param z     world z-coordinate
     * @param color the marker color, or {@code null} for the viewer default
     * @param size  the sphere radius in world units; 0 uses the viewer default
     */
    public void add(final double x, final double y, final double z, final Color color, final float size) {
        add("Marker", x, y, z, color, size);
    }

    /**
     * Exports bookmarks to a CSV file. X/Y/Z are written in world/calibrated units (as of the
     * world-coordinate storage unification; CSVs saved by earlier SNT versions store X/Y/Z in
     * pixel/voxel units for SNT-UI-mode bookmarks -- see the bundled bookmark-CSV migration
     * script to convert old files).
     */
    private boolean saveBookMarksToFile(final File file) {
        final SNTTable exportTable = new SNTTable();
        for (final Bookmark b : model.getDataList()) {
            exportTable.insertRow(null);
            exportTable.appendToLastRow("Tag", (b.getColor() == null) ? "" : String.format("#%06X", b.getColor().getRGB() & 0xFFFFFF));
            exportTable.appendToLastRow("Label", b.label);
            exportTable.appendToLastRow("X", b.x);
            exportTable.appendToLastRow("Y", b.y);
            exportTable.appendToLastRow("Z", b.z);
            // C has no live equivalent in Bvv/Bdv (see OverlayRenderer's CT-path-filter), and Size has
            // no live equivalent in classic mode (no adjustable marker size there): both write a dash
            // when meaningless for the current mode. T is always written, regardless of mode. Importing
            // treats a missing/non-numeric value as "unset" (see populateFromFile())
            exportTable.appendToLastRow("C", (viewer != null) ? "-" : String.valueOf(b.c));
            exportTable.appendToLastRow("T", b.t);
            exportTable.appendToLastRow("Size", (viewer != null) ? b.size : "-");
        }
        try {
            exportTable.save(file);
            return true;
        } catch (final IOException ioe) {
            SNTUtils.error("saveBookMarksToFile() failure", ioe);
        }
        return false;
    }

    /** @param x, y, z pixel/voxel coordinates (as sourced from a mouse click on {@code imp}) */
    protected void add(final int x, final int y, final int z, final ImagePlus imp) {
        final PointInImage world = pixelToWorld(x, y, z);
        add(world.x, world.y, world.z, imp.getC(), imp.getT());
        recordCmd("add(" + x + ", " + y + ", " + z  + ", " + imp.getC() + ", " + imp.getT() +")");
    }

    /**
     * Adds a bookmark at the (world/calibrated) position of the specified path node.
     *
     * @param path      the path
     * @param nodeIndex the node index
     */
    public void add(final Path path, final int nodeIndex) {
        final PointInImage node = path.getNode(nodeIndex); // world/calibrated coordinates
        final Color tag = path.hasNodeColors() ? path.getNodeColor(nodeIndex) : path.getColor();
        final String label = model.getUniqueLabel(path.getName() + " #" + nodeIndex);
        recordCmd(String.format("add(\"%s\", %d)", path.getName(), nodeIndex));
        addOne(new Bookmark(label, node.getX(), node.getY(), node.getZ(), path.getChannel(), path.getFrame(), tag));
    }

    public void remove(final Path path, final int nodeIndex) {
        final String label = path.getName() + " #" + nodeIndex;
        if (model.getDataList().removeIf(bookmark -> bookmark.label.equals(label))) {
            recordCmd(String.format("remove(\"%s\", %d)", path.getName(), nodeIndex));
            model.fireTableDataChanged();
        }
    }

    /**
     * Adds a bookmark at the specified pixel/voxel coordinates and time/channel positions.
     * Coordinates are converted to (and stored as) world/calibrated units using the active
     * image's calibration.
     *
     * @param x the x-coordinate of the bookmark, in pixels
     * @param y the y-coordinate of the bookmark, in pixels
     * @param z the z-coordinate of the bookmark, in pixels (slice index, 0-based)
     * @param c the channel position of the bookmark
     * @param t the time position of the bookmark
     */
    public void add(final int x, final int y, final int z, final int c, final int t) {
        add((double) x, (double) y, (double) z, c, t, true);
    }

    /**
     * Adds a bookmark at the specified world/calibrated coordinates and time/channel positions.
     *
     * @param x the x-coordinate of the bookmark, in calibrated units
     * @param y the y-coordinate of the bookmark, in calibrated units
     * @param z the z-coordinate of the bookmark, in calibrated units
     * @param c the channel position of the bookmark
     * @param t the time position of the bookmark
     */
    public void add(final double x, final double y, final double z, final int c, final int t) {
        add(x, y, z, c, t, false);
    }

    private void add(final double x, final double y, final double z, final int c, final int t,
                      final boolean pixelInput) {
        final PointInImage world = pixelInput ? pixelToWorld(x, y, z) : new PointInImage(x, y, z);
        addOne(new Bookmark(model.getUniqueLabel(""), world.x, world.y, world.z, c, t));
    }

    /** Sole entry point for adding a single bookmark/marker: appends it and refreshes the table. */
    private void addOne(final Bookmark b) {
        model.getDataList().add(b);
        model.fireTableDataChanged();
    }

    /**
     * Sole entry point for finishing a batch add: resizes columns (new content, e.g. a populated
     * Size column, may need more room than the placeholder dashes) then refreshes the table.
     */
    private void addBatchFinish() {
        resetOrResizeColumns(false, true);
        model.fireTableDataChanged();
    }

    /**
     * Adds multiple bookmarks with the specified label and locations.
     *
     * @param label     the label for the bookmarks
     * @param locations the list of SNTPoint (world/calibrated) locations for the bookmarks
     * @param channel   the channel position for the bookmarks
     * @param frame     the time position for the bookmarks
     * @param color     the color (category) for the bookmarks
     */
    public void add(final String label, final List<SNTPoint> locations, final int channel, final int frame, final String color) {
        final Color c = (color == null) ? null : SNTColor.fromString(color);
        locations.forEach(loc -> model.getDataList().add(new Bookmark(model.getUniqueLabel(label), //
                loc.getX(), loc.getY(), loc.getZ(), channel, frame, c)));
        addBatchFinish();
    }

    /**
     * Adds multiple bookmarks with the specified label and locations.
     *
     * @param label     the label for the bookmarks
     * @param locations the list of SNTPoint locations for the bookmarks
     * @param channel   the channel position for the bookmarks
     * @param frame     the time position for the bookmarks
     */
    public void add(final String label, final List<SNTPoint> locations, final int channel, final int frame) {
        add(label, locations, channel, frame, null);
    }

    /**
     * Adds multiple bookmarks with the specified label and locations.
     *
     * @param label     the label for the bookmarks
     * @param xyzctLocations the list of XYZCT locations, in pixel/voxel coordinates (see
     *                       {@link sc.fiji.snt.analysis.detection.Detection#xyzct()})
     */
    public void add(final String label, final List<double[]> xyzctLocations) {
        add(label, xyzctLocations, null);
    }

    /**
     * Adds multiple bookmarks with the specified label, locations, and color tag. Coordinates are
     * converted to (and stored as) world/calibrated units using the active image's calibration.
     *
     * @param label          the label prefix for the bookmarks
     * @param xyzctLocations the list of XYZCT locations, in pixel/voxel coordinates (see
     *                       {@link sc.fiji.snt.analysis.detection.Detection#xyzct()})
     * @param color          the color tag for the bookmarks, or {@code null} for no tag
     */
    public void add(final String label, final List<double[]> xyzctLocations, final Color color) {
        final AtomicInteger ai = new AtomicInteger(1);
        xyzctLocations.forEach(loc -> {
            final PointInImage world = pixelToWorld(loc[0], loc[1], loc[2]);
            model.getDataList().add(new Bookmark(model.getUniqueLabel(label + ai.getAndIncrement()), //
                    world.x, world.y, world.z, (int) loc[3], (int) loc[4], color));
        });
        addBatchFinish();
    }

    /**
     * Adds multiple bookmarks from selected path nodes
     *
     * @param map       the map of [k=Path, v=list of node indices] from which node positions are extracted
     * @param commonLabel an (optional) bookmark label suffix
     */
    public void add(final Map<Path, Set<Integer>> map, final String commonLabel) {
        final String suffix = (commonLabel == null) ? "" : commonLabel;
        final int currentN = model.getDataList().size();
        map.forEach((path, set) -> {
            final String label = String.format("%s %s", path.getName(), suffix);
            final int c = path.getChannel();
            final int t = path.getFrame();
            final Color defaultTag = path.getColor(); // Use path color as bookmark tag
            final boolean hasNodeColors = path.hasNodeColors();
            int counter = 1;
            for (final int nodeIndex : set) {
                final PointInImage node = path.getNode(nodeIndex); // world/calibrated coordinates
                final String l = (set.size()==1) ? label : label + "#" + counter++;
                final Color tag = (hasNodeColors) ? path.getNodeColor(nodeIndex) : defaultTag;
                model.getDataList().add(new Bookmark(model.getUniqueLabel(l),
                        node.getX(), node.getY(), node.getZ(), c, t, tag));
            }
        });
        addBatchFinish();
        final int added = model.getDataList().size() - currentN;
        if (sntui != null) sntui.showStatus(added + " bookmarks added", true);
    }

    /**
     * Clears the selection of bookmarks in the table. Does nothing if no selection exists.
     */
    public void clearSelection() {
        table.clearSelection();
    }

    /**
     * Clears all bookmarks.
     */
    public void reset() {
        model.setDataList(new ArrayList<>());
    }

    /**
     * Returns whether any bookmarks exist.
     *
     * @return true if there is at least one bookmark
     */
    public boolean hasBookmarks() {
        return !model.getDataList().isEmpty();
    }

    protected boolean isShowing() {
        return table.isShowing();
    }

    /**
     * Loads bookmarks from the specified file.
     *
     * @param file the file to load bookmarks from. File is expected to be a CSV file with 6 columns in the
     *             following order: Label, X, Y, Z, C, T.
     * @return true if bookmarks were loaded successfully, false otherwise
     */
    public boolean load(final File file) {
        loadBookmarksFromFile(file);
        return !model.getDataList().isEmpty();
    }

    /**
     * @param filePathOrURL local path or remote URL (e.g. {@code https://.../markers.csv}) to a CSV
     *                      file with the same layout expected by {@link #load(File)}.
     * @return true if bookmarks were loaded successfully, false otherwise.
     */
    public boolean load(final String filePathOrURL) {
        loadBookmarksFromFile(filePathOrURL);
        return !model.getDataList().isEmpty();
    }

    /**
     * Loads bookmarks from the specified list of ROIs. ROIs can be of any type. If area ROIs are provided, their
     * centroids are used as bookmark locations.
     *
     * @param rois the list of ROIs to load bookmarks from
     */
    public void load(final List<Roi> rois) {
        // Roi coordinates are always pixel-space (IJ1 convention); convert to this manager's
        // canonical world/calibrated storage.
        for (final Roi roi : rois) {
            // NB: Roi#getZPosition() is 1-based, with 0 meaning "not associated with a specific
            // slice" (see RoiConverter#getZPositions javadoc); pixelToWorld() expects 0-based.
            final double zPixel = (roi.getZPosition() > 0) ? roi.getZPosition() - 1 : 0;
            if (roi instanceof PointRoi) {
                final FloatPolygon fp = roi.getFloatPolygon();
                for (int i = 0; i < fp.npoints; i++) {
                    final PointInImage world = pixelToWorld(fp.xpoints[i], fp.ypoints[i], zPixel);
                    final Bookmark b = new Bookmark(roi.getName(),
                            world.x, world.y, world.z,
                            roi.getCPosition(), roi.getTPosition(), roi.getStrokeColor());
                    model.getDataList().add(b);
                }
            } else {
                final double[] centroid = RoiConverter.get2dCentroid(roi);
                final PointInImage world = pixelToWorld(centroid[0], centroid[1], zPixel);
                final Bookmark b = new Bookmark(roi.getName(),
                        world.x, world.y, world.z,
                        roi.getCPosition(), roi.getTPosition(), roi.getStrokeColor());
                model.getDataList().add(b);
            }
        }
        model.fireTableDataChanged();
    }

    /**
     * @see #load(List)
     */
    public void load(final Roi[] rois) {
        load(List.of(rois)); // script friendly version
    }

    /**
     * Saves bookmarks to the specified file.
     *
     * @param file the file to save bookmarks to
     * @return true if bookmarks were saved successfully, false otherwise
     */
    public boolean save(final File file) {
        return saveBookMarksToFile(file);
    }

    /**
     * @see #save(File)
     */
    public boolean save(final String filePath) {
        return save(new File(filePath));
    }

    /**
     * Returns the number of bookmarks.
     * @return the number of bookmarks currently stored in the manager.
     */
    public int getCount() {
        return model.getRowCount();
    }

    /**
     * Returns a list of ROIs representing the bookmarks.
     *
     * @param onlySelectedRows if true, only selected rows are included; otherwise, all ROIs in the manager are included
     * @return the list of ROIs (PointRoi) representing the bookmarks
     */
    public List<Roi> getROIs(final boolean onlySelectedRows) {
        final List<Roi> rois = new ArrayList<>();
        for (final int modelRow : rowsToUse(onlySelectedRows)) {
            final Bookmark b = model.getDataList().get(modelRow);
            rois.add(b.toRoi(worldToPixel(b)));
        }
        return rois;
    }

    /**
     * Model-row indices to use for a {@code onlySelectedRows} query: the selected rows if any exist
     * (and there is more than one bookmark total), otherwise every row. View indices are converted
     * to model indices where relevant (a sorted table's selection is in view order).
     */
    private int[] rowsToUse(final boolean onlySelectedRows) {
        final boolean restrict = onlySelectedRows && model.getRowCount() > 1;
        int[] rows = (restrict) ? table.getSelectedRows() : IntStream.range(0, model.getRowCount()).toArray();
        if (restrict && rows.length == 0) // no selection exists: assume all rows
            rows = IntStream.range(0, model.getRowCount()).toArray();
        if (restrict && table.getRowSorter() != null) {
            for (int i = 0; i < rows.length; i++) rows[i] = table.convertRowIndexToModel(rows[i]);
        }
        return rows;
    }

    /**
     * Converts a {@link Bookmark}'s stored world/calibrated position to pixel/voxel coordinates:
     * {@code pixel = (world - offset) / spacing}, the inverse of {@link #pixelToWorld}, accounting
     * for both pixel spacing <b>and</b> {@link SNT#getWorldOriginOffset() the world origin offset}
     * (see {@link #pixelToWorld} for why this offset matters, and why it cannot be handled via
     * {@link ij.measure.Calibration} alone -- {@code Calibration}'s own origin fields use a
     * different, non-interchangeable convention). Falls back to returning the world position
     * unchanged (spacing 1, offset 0) if no {@link SNT} is available via {@link #snt()} (e.g. a
     * pure-viewing BVV/BDV session with no associated tracing session).
     */
    private PointInImage worldToPixel(final Bookmark b) {
        final SNT snt = snt();
        if (snt == null) return new PointInImage(b.x, b.y, b.z);
        final double[] offset = snt.getWorldOriginOffset();
        final double xSpacing = (snt.getPixelWidth() > 0) ? snt.getPixelWidth() : 1;
        final double ySpacing = (snt.getPixelHeight() > 0) ? snt.getPixelHeight() : 1;
        final double zSpacing = (snt.getPixelDepth() > 0) ? snt.getPixelDepth() : 1;
        return new PointInImage((b.x - offset[0]) / xSpacing, (b.y - offset[1]) / ySpacing, (b.z - offset[2]) / zSpacing);
    }

    /**
     * Returns bookmark/marker positions in <b>pixel/voxel</b> coordinates, converted from this
     * manager's canonical world/calibrated storage via {@link #worldToPixel(Bookmark)}.
     *
     * @param onlySelectedRows if true, only selected rows are included; otherwise, all bookmarks in the manager are included
     * @return the list of Points (fresh {@link PointInImage} copies) of the bookmarks, in pixel/voxel coordinates
     */
    public List<SNTPoint> getPixelPositions(final boolean onlySelectedRows) {
        final List<SNTPoint> points = new ArrayList<>();
        for (final int modelRow : rowsToUse(onlySelectedRows)) {
            points.add(worldToPixel(model.getDataList().get(modelRow)));
        }
        return points;
    }

    /**
     * Returns bookmark/marker positions in spatially calibrated (<b>"world"</b>) coordinates, i.e.,
     * this manager's canonical storage format, returned as-is (fresh copies) in both modes.
     *
     * @param onlySelectedRows if true, only selected rows are included; otherwise, all bookmarks in the manager are included
     * @return the list of Points representing the bookmarks, in calibrated ("world") coordinates
     */
    public List<SNTPoint> getPositions(final boolean onlySelectedRows) {
        final List<SNTPoint> points = new ArrayList<>();
        for (final int modelRow : rowsToUse(onlySelectedRows)) {
            final Bookmark b = model.getDataList().get(modelRow);
            points.add(new PointInImage(b.x, b.y, b.z));
        }
        return points;
    }

    /**
     * Returns the position of the most recently added bookmark/marker, in spatially calibrated
     * ("world") coordinates (canonical storage format).
     * <p>
     * In viewer mode (BVV/BDV), this is the last marker placed with the {@code M} key (or added
     * programmatically). In SNT-UI mode, this is the last entry added to the Bookmark Manager pane.
     * Since new entries are always appended, this reflects placement order in the common case; it
     * can become stale relative to placement order after an explicit list-reordering operation
     * (e.g. the table's "Sort by Distance..." menu, or a Merge/Colocalize operation, both of which
     * re-write the backing list).
     * </p>
     *
     * @return the position of the last bookmark, or {@code null} if none have been added
     */
    public SNTPoint getLastPosition() {
        final List<Bookmark> data = model.getDataList();
        if (data.isEmpty()) return null;
        final Bookmark last = data.getLast();
        return new PointInImage(last.getX(), last.getY(), last.getZ());
    }

    /**
     * Adds the bookmark ROIs to the specified overlay. If no bookmarks are selected, all bookmarks are added,
     * otherwise only the selected bookmarks are added.
     *
     * @param overlay the overlay to add the bookmarks to. Null not allowed
     */
    public void toOverlay(final Overlay overlay) {
        removeHighlightROIs(overlay); // cull temporary highlights before exporting
        for (final Roi roi : getROIs(table.getSelectedRows().length>0))
            overlay.add(roi);
    }

    /**
     * Adds the bookmarks to the ROI Manager. If no bookmarks are selected, all bookmarks are added,
     * otherwise only the selected bookmarks are added.
     */
    public void toRoiManager() {
        RoiManager rm = RoiManager.getInstance2();
        if (rm == null) rm = new RoiManager();
        for (final Roi roi : getROIs(table.getSelectedRows().length>0))
            rm.addRoi(roi);
    }

    public static List<PointRoi> getRois(final String commonLabel, final List<double[]> xyzctLocations) {
        return getRois(commonLabel, xyzctLocations, null);
    }

    /**
     * Creates a list of {@link PointRoi}s from XYZCT locations with a color tag.
     *
     * @param commonLabel    the label prefix for each ROI
     * @param xyzctLocations the list of XYZCT locations (pixel coordinates)
     * @param color          the stroke color for the ROIs, or {@code null} for default
     * @return list of PointRoi objects
     */
    public static List<PointRoi> getRois(final String commonLabel, final List<double[]> xyzctLocations, final Color color) {
        final List<PointRoi> rois = new ArrayList<>(xyzctLocations.size());
        final AtomicInteger ai = new AtomicInteger(1);
        // Bookmark's canonical storage is world/calibrated, but this static helper is documented as
        // pixel-in/pixel-out with no SNTUI/viewer context available to convert through: pass the
        // pixel values straight through unconverted to toRoi(), so the ROI ends up at the same
        // pixel position as before this class started storing world coordinates natively.
        xyzctLocations.forEach(loc -> rois.add( //
                new Bookmark(commonLabel + ai.getAndIncrement(), //
                        loc[0], loc[1], loc[2], (int) loc[3], (int) loc[4], color)
                        .toRoi(new PointInImage(loc[0], loc[1], loc[2]))));
        return rois;
    }
}

/**
 * A bookmark/marker entry. Coordinates ({@code x}, {@code y}, {@code z}, inherited from
 * {@link PointInImage} via {@link Path.PathNode}) are <b>always</b> in spatially calibrated
 * ("world") units, regardless of whether this entry originated in SNT-UI (classic) mode or
 * viewer (BVV/BDV) mode. {@code c} (channel) and {@code t} (frame/timepoint) remain plain
 * 1-based hyperstack indices in both modes: they have no calibrated/"world" analogue that any
 * consumer (ImagePlus#setPosition, AbstractBigViewer#setCurrentTimepoint, etc.) actually uses.
 */
class Bookmark extends Path.PathNode {
    String label;
    final int c;
    int t;
    float size; // sphere radius in world units; 0 means "use viewer default"

    /** @param x, y, z world/calibrated coordinates */
    Bookmark(final String label, double x, double y, double z, int c, int t) {
        this(label, x, y, z, c, t, null);
    }

    /** @param x, y, z world/calibrated coordinates */
    Bookmark(final String label, double x, double y, double z, int c, int t, final Color category) {
        super(x, y, z);
        this.label = label;
        this.c = c;
        this.t = t;
        setColor(category);
    }

    Object get(final int entry) {
        return switch (entry) {
            case 0 -> getColor();
            case 1 -> label;
            case 2 -> x;
            case 3 -> y;
            case 4 -> z;
            case 5 -> c;  // channel (unused/always 1 in viewer mode; BookmarkModel shows a dash there)
            case 6 -> t;  // frame/timepoint
            case 7 -> size; // marker sphere radius (unused/blank in classic mode)
            default -> null;
        };
    }

    /**
     * Builds a {@link PointRoi} for this bookmark. ROIs are always pixel-space (IJ1 convention);
     * callers must supply this bookmark's world coordinates already converted to pixels (see
     * {@code BookmarkManager#worldToPixel}, which -- unlike a plain {@link ij.measure.Calibration}
     * conversion -- correctly accounts for any world-origin offset).
     */
    PointRoi toRoi(final PointInImage pixelPos) {
        final PointRoi roi = new PointRoi(pixelPos.x, pixelPos.y);
        if (getColor() != null)
            roi.setStrokeColor(getColor());
        // NB: ROI CZT positions use 1-based indices (see ShollOverlay#toRoi); pixelPos.z is 0-based.
        roi.setPosition(c, (int) pixelPos.z + 1, t);
        roi.setName(label);
        return roi;
    }
}

class CellEditor extends DefaultCellEditor {

    public CellEditor() {
        super(new JTextField());
        GuiUtils.addClearButton((JTextField) editorComponent);
        setClickCountToStart(3); // triple click necessary to start editing a cell
    }
}

class BookmarkTable extends JTable {

    String placeholderMsg =   "Bookmark image locations using Shift+B";

    BookmarkTable(final BookmarkModel model) {
        super(model);
        setAutoCreateRowSorter(true);
        setShowHorizontalLines(true);
        setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        setPreferredScrollableViewportSize(getPreferredSize());
        setFillsViewportHeight(true);
        setColumnSelectionAllowed(false);
        setRowSelectionAllowed(true);
        setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        setDefaultEditor(String.class, new CellEditor());
        // Set up color column renderer and editor
        setDefaultRenderer(Color.class, new ColorCellRenderer());
        setDefaultEditor(Color.class, new ColorCellEditor());
        // Set icon header for Tag column
        getColumnModel().getColumn(0).setHeaderRenderer(
                GuiUtils.JTables.iconHeaderRenderer(IconFactory.buttonIcon(IconFactory.GLYPH.TAG, .9f),
                        "Tag (click to sort by category)"));
    }

    JScrollPane getContainer() {
        final JScrollPane js = new JScrollPane(this);
        js.setComponentPopupMenu(getComponentPopupMenu()); // allow popupmenu to be displayed when clicking below last row
        return js;
    }

    @Override
    protected void paintComponent(final java.awt.Graphics g) {
        super.paintComponent(g);
        if (getModel().getRowCount() == 0) {
            final java.awt.Graphics2D g2 = (java.awt.Graphics2D) g;
            GuiUtils.setRenderingHints(g2);
            g2.setColor(GuiUtils.getDisabledComponentColor());
            final java.awt.FontMetrics fm = g2.getFontMetrics();
            final java.awt.Rectangle visible = getVisibleRect();
            g2.drawString(placeholderMsg, visible.x + (visible.width - fm.stringWidth(placeholderMsg)) / 2,
                    visible.y + (visible.height - fm.getHeight()) / 2 + fm.getAscent());
        }
    }

    @Override
    public boolean editCellAt(int row, int column, EventObject e) {
        // Reject keyboard-initiated editing: rename is only available via the
        // context menu so that single-key shortcuts (H, O) are not consumed.
        if (e instanceof java.awt.event.KeyEvent) return false;
        final boolean result = super.editCellAt(row, column, e);
        final Component editor = getEditorComponent();
        if (editor instanceof JTextField textField) {
            textField.requestFocus();
            textField.selectAll();
        }
        return result;
    }

    /** Renderer for the Tag column header: displays icon instead of text */
    private static class ColorCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(final JTable table, final Object value, final boolean isSelected,
                                                       final boolean hasFocus, final int row, final int column) {
            super.getTableCellRendererComponent(table, "", isSelected, hasFocus, row, column);
            setHorizontalAlignment(SwingConstants.CENTER);
            if (value instanceof Color color) {
                setIcon(IconFactory.accentIcon(color, true));
                setToolTipText(String.format("Tag: %s (click to change)", ColorCellEditor.getColorName(color)));
            } else {
                setIcon(null);
                setToolTipText("No tag (click to assign)");
            }
            return this;
        }
    }

    /**
     * Editor for the Tag/Color column - shows color chooser on click
     */
    static class ColorCellEditor extends AbstractCellEditor implements javax.swing.table.TableCellEditor {
        private final JButton editorButton;
        private final JPopupMenu colorChooserPopMenu;
        private Color currentColor;

        private static HashMap<String, Color> PRESET_TAGS;

        private static HashMap<String, Color> getPresetTags() {
            if (PRESET_TAGS == null) {
                PRESET_TAGS = new LinkedHashMap<>(); // LinkedHashMap keeps insertion order
                PRESET_TAGS.put("Red", new Color(255, 101, 101));
                PRESET_TAGS.put("Orange", new Color(255, 164, 91));
                PRESET_TAGS.put("Yellow", new Color(255, 214, 84));
                PRESET_TAGS.put("Green", new Color(104, 210, 124));
                PRESET_TAGS.put("Blue", new Color(77, 160, 255));
                PRESET_TAGS.put("Purple", new Color(215, 93, 231));
                PRESET_TAGS.put("Gray", new Color(165, 165, 169));
            }
            return PRESET_TAGS;
        }

        static String getColorName(Color color) {
            for (final Map.Entry<String, Color> entry : getPresetTags().entrySet()) {
                if (entry.getValue().getRGB() == color.getRGB()) {
                    return entry.getKey();
                }
            }
            return SNTColor.colorToString(color); // fallback for custom colors
        }

        ColorCellEditor() {
            editorButton = new JButton();
            editorButton.setBorderPainted(false);
            editorButton.setContentAreaFilled(false);
            colorChooserPopMenu = GuiUtils.MenuItems.colorTagPopup(editorButton, color -> {
                currentColor = color;
                fireEditingStopped();
            });
            final long[] lastShowTime = {0}; // Track when popup was last shown
            editorButton.addActionListener(e -> {
                // Prevent double-click from showing popup twice (within 500ms)
                final long now = System.currentTimeMillis();
                if (now - lastShowTime[0] > 500) {
                    lastShowTime[0] = now;
                    colorChooserPopMenu.show(editorButton, 0, editorButton.getHeight());
                }
            });
        }

        @Override
        public Object getCellEditorValue() {
            return currentColor;
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value,
                                                     boolean isSelected, int row, int column) {
            currentColor = (value instanceof Color) ? (Color) value : null;
            if (currentColor != null) {
                editorButton.setIcon(IconFactory.accentIcon(currentColor, true));
            } else {
                editorButton.setIcon(null);
            }
            return editorButton;
        }
    }

}

class BookmarkModel extends AbstractTableModel {

    // Same columns in both modes (no more classic-SNT-vs-BVV/BDV header split): C is meaningless in
    // viewer mode (no single "current channel" concept there, see OverlayRenderer's CT-path-filter) and
    // is rendered as a dash; Size is meaningless in classic mode (no adjustable marker size there) and
    // is likewise rendered as a dash. Both are still stored on every Bookmark regardless of mode
    private static final String[] HEADER = {"Tag", "Label", "X", "Y", "Z", "C", "T", "Size"};
    private final boolean bvvMode;
    private List<Bookmark> dataList = new ArrayList<>();

    BookmarkModel(final boolean bvvMode) {
        this.bvvMode = bvvMode;
    }

    List<Bookmark> getDataList() {
        return dataList;
    }

    void setDataList(final List<Bookmark> dataList) {
        this.dataList = dataList;
        fireTableDataChanged();
    }

    String getUniqueLabel(final String candidate) {
        final String prefix = (bvvMode) ? "Marker" : "Bookmark";
        final String base = (candidate == null || candidate.isBlank())
                ? String.format("%s%03d", prefix, 1 + getDataList().size())
                : candidate;
        if (getDataList().stream().noneMatch(b -> base.equalsIgnoreCase(b.label)))
            return base;
        int i = 2;
        while (true) {
            final String attempt = base + " (" + i + ")";
            if (getDataList().stream().noneMatch(b -> attempt.equalsIgnoreCase(b.label)))
                return attempt;
            i++;
        }
    }

    void populateFromFile(final File file) throws IOException {
        populateFromFile(file.getAbsolutePath());
    }

    /**
     * @param filePathOrURL local path or remote URL (e.g. {@code https://.../markers.csv}) to a bookmarks CSV file.
     *                      Remote URLs are handled directly by {@link SNTTable}'s own constructor
     *                      (download-to-temp-file); callers with a {@link File} that  may actually be a URL
     *                      round-tripped through it (e.g. a {@code File}-typed SciJava parameter) should pass the
     *                      repaired string here rather than going  through {@link #populateFromFile(File)}, which
     *                      always calls {@code getAbsolutePath()}.
     *                      <p>
     *                      X/Y/Z are read at face value and stored as world/calibrated coordinates. CSVs
     *                      exported by SNT versions prior to the world-coordinate storage unification store
     *                      X/Y/Z in pixel/voxel units for SNT-UI-mode bookmarks; run the bundled bookmark-CSV
     *                      migration script on such files before loading them, or positions will be
     *                      misinterpreted as world coordinates.
     */
    void populateFromFile(final String filePathOrURL) throws IOException {
        final SNTTable table = new SNTTable(filePathOrURL);
        int tagIdx = table.findColumnIndex(HEADER[0]);
        final int lIdx    = table.findColumnIndex(HEADER[1]);
        final int xIdx    = table.findColumnIndex(HEADER[2]);
        final int yIdx    = table.findColumnIndex(HEADER[3]);
        final int zIdx    = table.findColumnIndex(HEADER[4]);
        // Look up C/T/Size regardless of mode: older single-mode CSVs (classic: no Size; viewer: no C/T)
        // simply won't have the missing column, and findColumnIndex() returning -1 for it is already
        // handled below by defaulting the value, exactly as before this method stopped mode-gating the lookup
        final int cIdx    = table.findColumnIndex(HEADER[5]);
        final int tIdx    = table.findColumnIndex(HEADER[6]);
        final int sizeIdx = table.findColumnIndex(HEADER[7]);

        if (xIdx == -1 || yIdx == -1 || zIdx == -1)
            throw new IOException("Unexpected column header(s) in CSV file.");
        final List<Bookmark> dataList = new ArrayList<>();
        for (int i = 0; i < table.getRowCount(); i++) {
            Color category = null;
            if (tagIdx != -1) {
                final Object tagValue = table.get(tagIdx, i);
                if (tagValue instanceof String tagStr && !tagStr.isEmpty()) {
                    try {
                        final ColorRGB c = SNTColor.valueOf(tagStr);
                        category = new Color(c.getRed(), c.getGreen(), c.getBlue());
                    } catch (final IllegalArgumentException ignored) {}
                }
            }
            final String label = (lIdx != -1) ? ((String) table.get(lIdx, i)) : String.format("Marker%03d", i+1);
            // C may be "-" (dash placeholder written for viewer-mode exports) rather than numeric,
            // so only trust it when it is actually a Number, same as the existing Size handling below
            final Bookmark b = new Bookmark(label,
                    (double) table.get(xIdx, i), (double) table.get(yIdx, i), (double) table.get(zIdx, i),
                    (cIdx != -1 && table.get(cIdx, i) instanceof Number cNum) ? cNum.intValue() : 1,
                    (tIdx != -1 && table.get(tIdx, i) instanceof Number tNum) ? tNum.intValue() : 1,
                    category);
            if (sizeIdx != -1 && table.get(sizeIdx, i) instanceof Number n)
                b.size = n.floatValue();
            dataList.add(b);
        }
        setDataList(dataList);
    }

    void removeRow(final int row) {
        dataList.remove(row);
        fireTableRowsDeleted(row, row);
    }

    @Override
    public int getRowCount() {
        return dataList.size();
    }

    @Override
    public int getColumnCount() {
        return HEADER.length;
    }

    @Override
    public String getColumnName(final int col) {
        return HEADER[col];
    }

    @Override
    public Object getValueAt(final int row, final int col) {
        if (row >= dataList.size()) return null;
        // C: no live "current channel" concept in Bvv/Bdv (see OverlayRenderer's CT-path-filter), shown as a dash
        if (col == 5 && bvvMode) return "-";
        // Size: meaningless in classic mode (no adjustable marker size there), shown as a dash
        if (col == 7) return bvvMode ? dataList.get(row).size : "-";
        return dataList.get(row).get(col);
    }

    @Override
    public boolean isCellEditable(final int row, final int col) {
        if (row >= dataList.size()) return false;
        if (bvvMode) return col == 0 || col == 1 || col == 7; // Tag, Label, Size
        return col == 0 || col == 1; // Tag, Label
    }

    @Override
    public void setValueAt(final Object aValue, final int rowIndex, final int columnIndex) {
        if (columnIndex == 0) {
            dataList.get(rowIndex).setColor((Color) aValue);
            fireTableCellUpdated(rowIndex, columnIndex);
        } else if (columnIndex == 1 && !Objects.equals(aValue, dataList.get(rowIndex).label)) {
            dataList.get(rowIndex).label = getUniqueLabel((String) aValue);
            fireTableCellUpdated(rowIndex, columnIndex);
        } else if (bvvMode && columnIndex == 7) {
            try {
                dataList.get(rowIndex).size = Float.parseFloat(String.valueOf(aValue));
                fireTableCellUpdated(rowIndex, columnIndex);
            } catch (final NumberFormatException ignored) {}
        } else {
            super.setValueAt(aValue, rowIndex, columnIndex);
        }
    }

    @Override
    public Class<?> getColumnClass(int column) {
        if (column == 0) return Color.class;
        if (column == 1) return String.class;
        if (column == 5 && bvvMode) return String.class; // C: dash placeholder
        if (column == 7) return bvvMode ? Float.class : String.class; // Size: dash placeholder in classic mode
        return switch (column) {
            case 2, 3, 4 -> Double.class;
            default -> Integer.class; // 5 (C, classic mode), 6 (T, both modes)
        };
    }

}
