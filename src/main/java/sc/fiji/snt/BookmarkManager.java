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
import sc.fiji.snt.analysis.RoiConverter;
import sc.fiji.snt.analysis.SNTTable;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.IconFactory;
import sc.fiji.snt.util.*;
import sc.fiji.snt.viewer.Bvv;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
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
    private final Bvv bvv;
    /** BVV-injected toolbar components (e.g. slab toggle). Added via {@link #addBvvToolbarButton}. */
    private final java.util.List<javax.swing.JComponent> bvvToolbarButtons = new java.util.ArrayList<>();
    private final GuiUtils guiUtils;
    private final BookmarkModel model;
    private final BookmarkTable table;
    /** Preferred zoom level applied when double-clicking a bookmark to visit it. */
    private final GuiUtils.JTables.VisitingZoom visitingZoom = new GuiUtils.JTables.VisitingZoom();
    private JDialog bvvFrame; // floating dialog for BVV mode (non-modal, owned by viewer frame)
    // Detachable table state, owned by the helper.
    private JPanel panel; // cached panel built by getPanel()
    private JScrollPane tableScroll; // single scroll pane wrapping `table` (created in assembleTable)
    private GridBagConstraints tableScrollGbc; // captured constraints for re-docking


    /**
     * SNT constructor: implements the <i>Bookmark Manager</i> pane embedded in SNT's UI.
     */
    public BookmarkManager(final SNTUI sntui) {
        this.sntui = sntui;
        this.bvv = null;
        this.guiUtils = sntui != null ? sntui.guiUtils : new GuiUtils();
        model = new BookmarkModel(false);
        table = assembleTable(model);
        visitingZoom.resetFor(sntui != null ? sntui.plugin.getImagePlus() : null);
    }

    /**
     * BVV constructor: implements a standalone marker manager for a BVV viewer.
     * Markers are rendered as spheres in the BVV overlay and can be placed with
     * the {@code M} key. The manager is displayed as a floating panel.
     *
     * @param bvv the BVV viewer instance to attach to
     */
    public BookmarkManager(final Bvv bvv) {
        this.sntui = null;
        this.bvv = bvv;
        this.guiUtils = new GuiUtils(bvv.getViewerFrame());
        model = new BookmarkModel(true);
        table = assembleTable(model);
        // Sync overlay whenever the model changes
        model.addTableModelListener(e -> syncBvvOverlay());
    }

    /** In BVV mode: pushes all markers to the annotation overlay. */
    private void syncBvvOverlay() {
        if (bvv == null || bvv.annotations() == null) return;
        final List<SNTPoint> points = new ArrayList<>();
        final List<Float> sizes = new ArrayList<>();
        final List<Color> colors = new ArrayList<>();
        for (final Bookmark b : model.getDataList()) {
            points.add(b);
            sizes.add(b.size > 0 ? b.size : bvv.getRenderingOptions().getMinThickness());
            colors.add(b.getColor() != null ? b.getColor() : bvv.getRenderingOptions().fallbackColor);
        }
        bvv.annotations().clear();
        for (int i = 0; i < points.size(); i++)
            bvv.annotations().addAnnotation(points.get(i), sizes.get(i), colors.get(i));
    }

    /** Returns the floating dialog for BVV mode, creating it on first call. */
    public JDialog getBvvPanel() {
        if (bvvFrame == null) {
            bvvFrame = new JDialog(bvv.getViewerFrame(), "BVV Markers", false);
            bvvFrame.setDefaultCloseOperation(JDialog.HIDE_ON_CLOSE);
            bvvFrame.add(getPanel());
            bvvFrame.pack();
            // Ensure the frame is tall enough to show at least 5 table rows
            final int minHeight = table.getRowHeight() * 5
                    + table.getTableHeader().getPreferredSize().height + 120; // 120 for toolbar + description
            if (bvvFrame.getHeight() < minHeight)
                bvvFrame.setSize(bvvFrame.getWidth(), minHeight);
            bvvFrame.setMinimumSize(new Dimension(bvvFrame.getWidth(), minHeight));
            bvvFrame.setLocationRelativeTo(bvv.getViewerFrame());
        }
        return bvvFrame;
    }

    /**
     * Adds a component to the BVV toolbar section of this panel.
     * The component is appended after a separator the first time this method is called.
     * Bvv uses this to inject context-specific controls (e.g. a slab-clip toggle).
     *
     * @param component the component to add; must not be {@code null}
     */
    public void addBvvToolbarButton(final javax.swing.JComponent component) {
        bvvToolbarButtons.add(component);
    }

    /** Shows or hides the floating BVV marker panel. */
    public void toggleBvvPanel() {
        final JDialog f = getBvvPanel();
        guiUtils.setParent(f);
        f.setVisible(!f.isVisible());
    }

    /** Alias for {@link #toggleBvvPanel()} shows the marker panel. */
    public void showPanel() {
        final JDialog f = getBvvPanel();
        if (!f.isVisible()) f.setVisible(true);
        else f.toFront();
    }

    protected JPanel getPanel() {
        if (panel != null) return panel;
        panel = (sntui != null)
                ? SNTUI.InternalUtils.getTab() : new JPanel(new GridBagLayout());
        final GridBagConstraints gbc = GuiUtils.defaultGbc();
        gbc.fill = GridBagConstraints.BOTH;
        if (sntui != null) {
            SNTUI.InternalUtils.addSeparatorWithURL(panel, "Bookmarks:", true, gbc);
            gbc.gridy++;
        }
        final String msg = (bvv != null)
                ? "Place markers with the M key. Double-click a row to fly to that location. " +
                "Color and size are applied to the BVV overlay in real time. Hold H to temporarily " +
                "hide markers."
                : """
                This pane stores image locations that you can quickly (re)visit while \
                tracing. Bookmarks can be saved to the workspace directory using the \
                toolbar button or via File>Save Session.

                To create a bookmark: Right-click on the image and choose "Bookmark Cursor \
                Location" from the contextual menu (or press Shift+B). To bookmark crossovers \
                and other positions along paths use the menu in the navigation toolbar of the \
                Path Manager.
                """;
        gbc.weighty = 0.0;
        panel.add(GuiUtils.longSmallMsg(msg, panel), gbc);
        gbc.gridy++;
        panel.add(assembleHighlightToolbar(), gbc);
        gbc.gridy++;
        gbc.weighty = 0.95;
        // Use the cached scroll pane (created in assembleTable) so the
        // DetachableTable helper can move it in and out of a floating dialog.
        // Capture the constraints used here so redockTableScroll() can
        // restore the pane to the same grid cell after a detach.
        tableScrollGbc = (GridBagConstraints) gbc.clone();
        panel.add(tableScroll, tableScrollGbc);
        gbc.gridy++;
        gbc.weighty = 0.0;
        panel.add(assembleToolbar(), gbc);
        // Initialize column widths after layout is complete
        SwingUtilities.invokeLater(() -> resetOrResizeColumns(false, true));
        return panel;
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
        if (bvv != null) {
            // Prevent the table's searchable from consuming BVV shortcuts.
            // NONE means "do nothing" the keystroke falls through to the BVV viewer.
            for (final char key : new char[]{'m', 'M', 'b', 'B', 'p', 'P', 'r', 'R', 's', 'S', 'f', 'F'}) {
                table.getInputMap(javax.swing.JComponent.WHEN_FOCUSED)
                        .put(javax.swing.KeyStroke.getKeyStroke(key), "none");
            }
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
                        return;
                    }
                    if (bvv != null) {
                        // BVV mode: animate camera to marker world position
                        flyTo(row);
                    } else {
                        final ImagePlus imp = sntui.plugin.getImagePlus();
                        if (imp == null) {
                            sntui.guiUtils.error("No image is currently open.");
                        } else {
                            goTo(row, imp);
                            if (!sntui.plugin.getSinglePane()) {
                                final ImagePlus zyImp = sntui.plugin.getImagePlus(SNT.ZY_PLANE);
                                if (zyImp != null) goTo(row, zyImp, SNT.ZY_PLANE);
                                final ImagePlus xzImp = sntui.plugin.getImagePlus(SNT.XZ_PLANE);
                                if (xzImp != null) goTo(row, xzImp, SNT.XZ_PLANE);
                            }
                        }
                    }
                }
            }
        });
        if (sntui != null) {
            SNTUI.InternalUtils.addHoldToToggleKeyListener(table, sntui.plugin);
        }
        return table;
    }

    private void resetOrResizeColumns(final boolean reset, final boolean resize) {
        if (table == null || model == null) return;
        if (reset) GuiUtils.JTables.resetColumnOrder(table);
        if (resize) GuiUtils.JTables.resizeColumns(table, columnWidthFractions());
    }

    /**
     * Preferred width fractions for the bookmark table: SNT mode shows
     * {@code {Tag, Label, X, Y, Z, C, T}} (7 columns) and BVV mode shows
     * {@code {Tag, Label, X, Y, Z, Size}} (6 columns).
     */
    private float[] columnWidthFractions() {
        return (bvv != null)
                ? new float[]{0.05f, 0.50f, 0.12f, 0.12f, 0.12f, 0.09f}
                : new float[]{0.05f, 0.58f, 0.09f, 0.09f, 0.09f, 0.05f, 0.05f};
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
                if (table.getRowCount() > 10)
                    table.scrollRectToVisible(new Rectangle(table.getCellRect(rows[0], 0, true)));
                table.editCellAt(rows[0], 1); // Column 1 is now Label
            } else {
                final String seed = guiUtils.getString(
                        "Common label to be applied to " + rows.length + " bookmarks:", // msg
                        "Bulk Renaming", // title
                        "Bookmark"); // default value
                if (seed == null) return; // user pressed cancel
                int idx = 1;
                for (final int viewRow : rows) {
                    final int modelRow = table.convertRowIndexToModel(viewRow);
                    model.setValueAt(String.format("%s %02d", seed, idx++), modelRow, 1);
                }
            }
        });
        pMenu.add(mi);

        // Color tags submenu
        final JMenu tagMenu = GuiUtils.MenuItems.colorTagMenu(sntui, color -> {
            if (noBookmarksError()) return;
            for (final int viewRow : getSelectedRowsAllIfNone()) {
                final int modelRow = table.convertRowIndexToModel(viewRow);
                model.setValueAt(color, modelRow, 0); // if color is null user chose "Remove Tag"
            }
        });
        tagMenu.setText("Tag");
        tagMenu.setIcon(IconFactory.menuIcon((IconFactory.GLYPH.TAG)));
        pMenu.add(tagMenu);
        mi = new JMenuItem("Distinct Tags", IconFactory.menuIcon(IconFactory.GLYPH.SHUFFLE));
        mi.addActionListener(e -> {
            if (noBookmarksError()) return;
            final int[] rows = getSelectedRowsAllIfNone();
            final Color[] distinctColors =  ColorMaps.glasbeyColorsAWT(rows.length);
            int colorIdx = 0;
            for (final int viewRow : rows) {
                final int modelRow = table.convertRowIndexToModel(viewRow);
                model.setValueAt(distinctColors[colorIdx++], modelRow, 0);
            }
        });
        tagMenu.addSeparator();
        tagMenu.add(mi);
        pMenu.addSeparator();
        if (bvv != null) {
            mi = new JMenuItem("Size...", IconFactory.menuIcon(IconFactory.GLYPH.CIRCLE));
            mi.addActionListener(e -> {
                if (noBookmarksError()) return;
                final int[] rows = getSelectedRowsAllIfNone();
                final int lastCol = model.getColumnCount() - 1;
                final Double size = guiUtils.getDouble("Marker size (in calibrated units):",
                        "Marker Size", (float)model.getValueAt(rows[rows.length-1], lastCol));
                if (size != null) {
                    for (final int viewRow : rows) {
                        final int modelRow = table.convertRowIndexToModel(viewRow);
                        model.setValueAt(size, modelRow, lastCol);
                    }
                }
            });
            pMenu.add(mi);
            pMenu.addSeparator();
        }
        if (sntui != null) {
            mi = new JMenuItem("Colocalize...", IconFactory.menuIcon(IconFactory.GLYPH.LINK));
            mi.setToolTipText("Matches bookmarks across channels within a distance threshold, replacing them with centroids");
            mi.addActionListener(e -> colocalizeBookmarks());
            pMenu.add(mi);
            mi = new JMenuItem("Merge...", IconFactory.menuIcon(IconFactory.GLYPH.ARROWS_TO_CIRCLE));
            mi.setToolTipText("Merges nearby bookmarks within each channel, replacing them with centroids");
            mi.addActionListener(e -> mergeBookmarks());
            pMenu.add(mi);
            pMenu.addSeparator();
        }
        mi = new JMenuItem("Delete...", IconFactory.menuIcon(IconFactory.GLYPH.TRASH));
        mi.addActionListener(e -> {
            if (noBookmarksError()) return;
            final int[] viewRows = getSelectedRowsAllIfNone();
            if (viewRows.length == table.getRowCount()) {
                if (!guiUtils.getConfirmation("Delete all bookmarks?", "Delete All?")) {
                    return;
                }
                reset();
                recordCmd("reset()");
                return; // Don't continue to delete rows that no longer exist
            }
            // Convert view indices to model indices (handles sorted table)
            final int[] modelRows = Arrays.stream(viewRows)
                    .map(table::convertRowIndexToModel)
                    .boxed()
                    .sorted(Comparator.reverseOrder()) // Delete from end to preserve indices
                    .mapToInt(Integer::intValue)
                    .toArray();
            for (final int modelRow : modelRows)
                model.removeRow(modelRow);
        });
        pMenu.add(mi);

        pMenu.addSeparator();
        pMenu.add(GuiUtils.JTables.resetAndResizeColumnsMenuItem(
                table, () -> recordComment("Bookmark Manager: resizeColumns()"),
                columnWidthFractions()));
        // Detach/Dock and Searchable items are appended in assembleTable() so
        // they can reference the scroll pane and stay grouped with other
        // table-management actions (the Searchable items will be the last
        // entries in the menu).
        return pMenu;
    }

    private int[] getSelectedRowsAllIfNone() {
        int[] viewRows = table.getSelectedRows();
        if (viewRows.length == 0) {
            viewRows = IntStream.range(0, table.getRowCount()).toArray();
        }
        return viewRows;
    }

    private void colocalizeBookmarks() {
        if (noBookmarksError()) return;
        if (bvv != null) {
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
                "<HTML>Max. distance between colocalized bookmarks<br>(in pixel units):",
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
        if (bvv != null) {
            guiUtils.error("Merge is not available in BVV mode.");
            return;
        }
        final List<Bookmark> candidates = getSelectedBookmarks();
        if (candidates.size() < 2) {
            guiUtils.error("At least 2 bookmarks are required for merging.");
            return;
        }
        final Double threshold = guiUtils.getDouble(
                "<HTML>Max. distance between bookmarks to merge<br>(in pixel units):",
                "Merge Bookmarks", 5.0);
        if (threshold == null || threshold <= 0) return;
        // Group by channel; merge within each channel independently
        final Map<Integer, List<Bookmark>> byChannel = new LinkedHashMap<>();
        for (final Bookmark b : candidates) {
            byChannel.computeIfAbsent(b.c, k -> new ArrayList<>()).add(b);
        }
        final Set<Bookmark> allConsumed = new HashSet<>();
        final List<Bookmark> allMerged = new ArrayList<>();
        for (final Map.Entry<Integer, List<Bookmark>> entry : byChannel.entrySet()) {
            final List<Bookmark> chBookmarks = entry.getValue();
            if (chBookmarks.size() < 2) continue;
            // Each bookmark is a potential seed; remaining in same channel are targets
            final List<Bookmark> seeds = new ArrayList<>(chBookmarks);
            final List<List<Bookmark>> targets = List.of(new ArrayList<>(chBookmarks));
            final MergeResult chResult = greedyMerge(seeds, targets, threshold, 2,
                    "Merged C" + entry.getKey());
            allConsumed.addAll(chResult.consumed);
            allMerged.addAll(chResult.merged);
        }
        applyMergeResult(new MergeResult(allConsumed, allMerged),
                "Merge Bookmarks", "merged", "merge(" + threshold + ")");
    }

    private List<Bookmark> getSelectedBookmarks() {
        final int[] viewRows = getSelectedRowsAllIfNone();
        final List<Bookmark> candidates = new ArrayList<>();
        for (final int viewRow : viewRows) {
            candidates.add(model.getDataList().get(table.convertRowIndexToModel(viewRow)));
        }
        return candidates;
    }

    /**
     * Greedy nearest-match merge. For each seed, finds the closest unconsumed
     * bookmark in each target list within the threshold. Groups with at least
     * {@code minGroupSize} members are merged to their centroid.
     *
     * @param seeds       the seed bookmarks
     * @param targetLists lists of bookmarks to match against (may include seeds)
     * @param threshold   max distance (pixel units)
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
        if (!guiUtils.getConfirmation(
                result.consumed.size() + " bookmarks will be replaced by "
                        + result.merged.size() + " " + verb + " entries. Proceed?",
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

    private void highlightBookmarks() {
        if (noBookmarksError()) return;
        final ImagePlus imp = sntui.plugin.getImagePlus();
        if (imp == null) {
            sntui.guiUtils.error("No image is currently open.");
            return;
        }
        Overlay overlay = imp.getOverlay();
        if (overlay == null) {
            overlay = new Overlay();
            imp.setOverlay(overlay);
        }
        // Remove any existing highlights first
        removeHighlightROIs(overlay);
        final int[] rows = getSelectedRowsAllIfNone();
        int idx = 0;
        for (final int viewRow : rows) {
            final int modelRow = table.convertRowIndexToModel(viewRow);
            final Bookmark b = model.getDataList().get(modelRow);
            final PointRoi roi = b.toRoi();
            roi.setName(HIGHLIGHT_PREFIX + idx++);
            roi.setPointType(PointRoi.CIRCLE);
            if (roi.getStrokeColor() == null)
                roi.setStrokeColor(Color.CYAN);
            overlay.add(roi);
        }
        imp.updateAndDraw();
        sntui.showStatus(rows.length + " bookmark(s) highlighted on overlay", true);
    }

    private void clearHighlights() {
        final ImagePlus imp = (sntui == null) ? null : sntui.plugin.getImagePlus();
        if (imp == null || imp.getOverlay() == null) return;
        if (removeHighlightROIs(imp.getOverlay())) {
            imp.updateAndDraw();
            sntui.showStatus("Highlights cleared", true);
        }
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
            final JButton highlightButton = new JButton(IconFactory.buttonIcon('\uf0eb', false));
            highlightButton.setToolTipText("Highlight selected bookmarks on the image (all if none selected)");
            highlightButton.addActionListener(e -> highlightBookmarks());
            final JButton clearHighlightButton = GuiUtils.Buttons.undo();
            clearHighlightButton.setToolTipText("Remove bookmark highlights from the image");
            clearHighlightButton.addActionListener(e -> clearHighlights());
            tb.add(highlightButton);
            tb.add(clearHighlightButton);
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
        tb.addSeparator();
        tb.add(expButton);
        if (bvv != null) {
            tb.add(Box.createHorizontalGlue());
            // Navigation: Prev / Next / Reset
            tb.addSeparator();
            final JButton prevButton = new JButton(IconFactory.menuIcon(IconFactory.GLYPH.NEXT));
            prevButton.setToolTipText("Fly to previous marker");
            prevButton.addActionListener(e -> {
                final int row = table.getSelectedRow();
                final int target = (row <= 0) ? table.getRowCount() - 1 : row - 1;
                if (target >= 0) { table.setRowSelectionInterval(target, target); flyTo(target); }
            });
            final JButton nextButton = new JButton(IconFactory.menuIcon(IconFactory.GLYPH.PREVIOUS));
            nextButton.setToolTipText("Fly to next marker");
            nextButton.addActionListener(e -> {
                final int row = table.getSelectedRow();
                final int target = (row < 0 || row >= table.getRowCount() - 1) ? 0 : row + 1;
                if (target < table.getRowCount()) { table.setRowSelectionInterval(target, target); flyTo(target); }
            });
            final JButton resetButton = new JButton(IconFactory.menuIcon(IconFactory.GLYPH.EXPAND));
            resetButton.setToolTipText("Reset view to fit volume");
            resetButton.addActionListener(e -> bvv.resetView());
            final JButton helpButton = GuiUtils.Buttons.help(null);
            helpButton.addActionListener(e -> displayMarkerHelp());
            tb.add(prevButton);
            tb.add(nextButton);
            tb.addSeparator();
            tb.add(resetButton);
            tb.addSeparator();
            tb.add(Box.createHorizontalGlue());
            // Inject any BVV-provided toolbar buttons (e.g. slab-clip toggle)
            if (!bvvToolbarButtons.isEmpty()) {
                tb.addSeparator();
                bvvToolbarButtons.forEach(tb::add);
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

    private void displayMarkerHelp() {
        final String MARKER_HELP_MSG =
                "<html><body style='width:350px; font-family:sans-serif'>" +
                        "<h3>Placing Markers (M key)</h3>" +
                        "Press <b>M</b> in the BVV viewer to place a marker at the current cursor position. " +
                        "Markers are rendered as spheres at 3D world coordinates and listed in this table." +
                        "<h3>Important: View Orientation</h3>" +
                        "Marker coordinates are computed by projecting the 2D cursor position onto the " +
                        "<em>focal plane</em> — the plane at the centre of the volume that faces the camera. " +
                        "This projection is only accurate when the view is aligned to a principal axis " +
                        "(X&ndash;Y, X&ndash;Z, or Y&ndash;Z), because the focal plane then cuts cleanly " +
                        "through the volume. When the volume is rotated to an oblique angle the focal plane " +
                        "may not intersect the data at all, and the resulting coordinates can fall far outside " +
                        "the image bounds. Markers placed in that situation are automatically rejected." +
                        "<p><b>Tip:</b> Use the <em>Reset</em> button to restore the default axis-aligned view " +
                        "before placing markers, then rotate freely to inspect them.</p>" +
                        "<h3>Navigation</h3>" +
                        "Double-click a row to fly to that marker. Use the <b>&uarr;</b> / <b>&darr;</b> " +
                        "buttons to step through markers in order without touching the table with the mouse." +
                        "</body></html>";
        new GuiUtils((table==null) ? null : table.getParent())
                .showHTMLDialog(MARKER_HELP_MSG, "About Markers", false);
    }

    private boolean noBookmarksError() {
        final List<Bookmark> list = model.getDataList();
        if (list.isEmpty()) {
            final String msg = (bvv != null)
                    ? "No markers exist. Use the M key to place markers."
                    : "No bookmarks exist. To create one, right-click on the image and choose \"Bookmark cursor location\" (Shift+B).";
            guiUtils.error(msg);
            return true;
        }
        return false;
    }

    private void goTo(final int row, final ImagePlus imp, final int plane) {
        assert imp != null;
        final Bookmark b = model.getDataList().get(table.convertRowIndexToModel(row));

        // Transform coordinates based on plane
        final double viewX, viewY;
        viewY = switch (plane) {
            case SNT.ZY_PLANE -> {
                viewX = b.z;
                yield b.y;
            }
            case SNT.XZ_PLANE -> {
                viewX = b.x;
                yield b.z;
            }
            default -> {
                viewX = b.x;
                yield b.y;
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
            imp.setPosition(b.c, (int) b.z, b.t);
        }
        // Side views don't need setPosition - they show all Z by definition
        ImpUtils.zoomTo(imp, visitingZoom.fraction(), (int) viewX, (int) viewY);
    }

    private void goTo(final int row, final ImagePlus imp) {
        goTo(row, imp, SNT.XY_PLANE);
    }

    /** BVV mode: animates the camera to the world position of the selected marker row. */
    private void flyTo(final int row) {
        if (bvv == null) return;
        final Bookmark b = model.getDataList().get(table.convertRowIndexToModel(row));
        final net.imglib2.realtransform.AffineTransform3D current = new net.imglib2.realtransform.AffineTransform3D();
        bvv.getViewerPanel().state().getViewerTransform(current);
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
        final double cX = bvv.getViewerPanel().getWidth()  / 2.0;
        final double cY = bvv.getViewerPanel().getHeight() / 2.0;
        final net.imglib2.realtransform.AffineTransform3D target = current.copy();
        target.set(cX - rx, 0, 3);
        target.set(cY - ry, 1, 3);
        target.set(   - rz, 2, 3);
        bvv.getViewerPanel().setTransformAnimator(
                new bdv.viewer.animate.SimilarityTransformAnimator(current, target, 0, 0, 300));
        bvv.getViewer().getViewer().showMessage(String.format("Flying to %s", b.label));
    }

    private void loadBookmarksFromFile(final File file) {
        try {
            model.populateFromFile(file);
        } catch (final Exception ex) {
            guiUtils.error(ex.getMessage() + ".");
            SNTUtils.error("loadBookmarksFromFile() failure", ex);
        }
    }

    /**
     * BVV mode: adds a marker at the specified world coordinates.
     * The marker is auto-labelled and immediately rendered in the BVV overlay.
     *
     * @param x world x-coordinate
     * @param y world y-coordinate
     * @param z world z-coordinate
     */
    public void add(final double x, final double y, final double z) {
        // Inherit color, size, and label stem from the previous entry for continuity
        final List<Bookmark> data = model.getDataList();
        final Color inheritedColor = data.isEmpty() ? null : data.getLast().getColor();
        final float inheritedSize  = data.isEmpty() ? 0f   : data.getLast().size;
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
        final Bookmark b = new Bookmark(label, x, y, z, 1, 1, inheritedColor);
        b.size = inheritedSize;
        model.getDataList().add(b);
        model.fireTableDataChanged();
    }

    /**
     * BVV mode: adds a marker at the specified world coordinates with a color and size.
     *
     * @param x     world x-coordinate
     * @param y     world y-coordinate
     * @param z     world z-coordinate
     * @param color the marker color, or {@code null} for the viewer default
     * @param size  the sphere radius in world units; 0 uses the viewer default
     */
    public void add(final double x, final double y, final double z, final Color color, final float size) {
        final String label = model.getUniqueLabel("Marker");
        final Bookmark b = new Bookmark(label, x, y, z, 1, 1, color);
        b.size = size;
        model.getDataList().add(b);
        model.fireTableDataChanged();
    }

    private boolean saveBookMarksToFile(final File file) {
        final SNTTable exportTable = new SNTTable();
        for (final Bookmark b : model.getDataList()) {
            exportTable.insertRow(null);
            exportTable.appendToLastRow("Tag", (b.getColor() == null) ? "" : String.format("#%06X", b.getColor().getRGB() & 0xFFFFFF));
            exportTable.appendToLastRow("Label", b.label);
            exportTable.appendToLastRow("X", b.x);
            exportTable.appendToLastRow("Y", b.y);
            exportTable.appendToLastRow("Z", b.z);
            if (bvv != null) {
                exportTable.appendToLastRow("Size", b.size);
            } else {
                exportTable.appendToLastRow("C", b.c);
                exportTable.appendToLastRow("T", b.t);
            }
        }
        try {
            exportTable.save(file);
            return true;
        } catch (final IOException ioe) {
            SNTUtils.error("saveBookMarksToFile() failure", ioe);
        }
        return false;
    }

    protected void add(final int x, final int y, final int z, final ImagePlus imp) {
        add(x, y, z, imp.getC(), imp.getT());
        recordCmd("add(" + x + ", " + y + ", " + z  + ", " + imp.getC() + ", " + imp.getT() +")");
    }

    public void add(final Path path, final int nodeIndex) {
        final PointInCanvas node = path.getPointInCanvas(nodeIndex);
        final Color tag = path.hasNodeColors() ? path.getNodeColor(nodeIndex) : path.getColor();
        final String label = model.getUniqueLabel(path.getName() + " #" + nodeIndex);
        model.getDataList().add(new Bookmark(label,
                (int) node.getX(), (int) node.getY(), (int) node.getZ(),
                path.getChannel(), path.getFrame(), tag));
        recordCmd(String.format("add(\"%s\", %d)", path.getName(), nodeIndex));
        model.fireTableDataChanged();
    }

    public void remove(final Path path, final int nodeIndex) {
        final String label = path.getName() + " #" + nodeIndex;
        if (model.getDataList().removeIf(bookmark -> bookmark.label.equals(label))) {
            recordCmd(String.format("remove(\"%s\", %d)", path.getName(), nodeIndex));
            model.fireTableDataChanged();
        }
    }

    /**
     * Adds a bookmark at the specified coordinates and time/channel positions.
     *
     * @param x the x-coordinate of the bookmark
     * @param y the y-coordinate of the bookmark
     * @param z the z-coordinate of the bookmark
     * @param c the channel position of the bookmark
     * @param t the time position of the bookmark
     */
    public void add(final int x, final int y, final int z, final int c, final int t) {
        model.getDataList().add(new Bookmark(model.getUniqueLabel(""), x, y, z, c, t));
        model.fireTableDataChanged();
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
        locations.forEach(loc -> model.getDataList().add(new Bookmark(model.getUniqueLabel(label), //
                (int) loc.getX(), (int) loc.getY(), (int) loc.getZ(), channel, frame)));
        resetOrResizeColumns(false, true);
        model.fireTableDataChanged();
    }

    /**
     * Adds multiple bookmarks with the specified label and locations.
     *
     * @param label     the label for the bookmarks
     * @param xyzctLocations the list of XYZCT locations
     */
    public void add(final String label, final List<double[]> xyzctLocations) {
        add(label, xyzctLocations, null);
    }

    /**
     * Adds multiple bookmarks with the specified label, locations, and color tag.
     *
     * @param label          the label prefix for the bookmarks
     * @param xyzctLocations the list of XYZCT locations
     * @param color          the color tag for the bookmarks, or {@code null} for no tag
     */
    public void add(final String label, final List<double[]> xyzctLocations, final Color color) {
        AtomicInteger ai = new AtomicInteger(1);
        xyzctLocations.forEach(loc -> model.getDataList().add( //
                new Bookmark(model.getUniqueLabel(label + ai.getAndIncrement()), //
                        (int) loc[0], (int) loc[1], (int) loc[2], (int) loc[3], (int) loc[4], color)));
        resetOrResizeColumns(false, true);
        model.fireTableDataChanged();
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
                final PointInCanvas node = path.getPointInCanvas(nodeIndex);
                final String l = (set.size()==1) ? label : label + "#" + counter++;
                final Color tag = (hasNodeColors) ? path.getNodeColor(nodeIndex) : defaultTag;
                model.getDataList().add(new Bookmark(model.getUniqueLabel(l),
                        (int) node.getX(), (int) node.getY(), (int) node.getZ(), c, t, tag));
            }
        });
        resetOrResizeColumns(false, true);
        model.fireTableDataChanged();
        sntui.showStatus(model.getDataList().size() - currentN + " bookmarks added", true);
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
     * @see #load(File)
     */
    public boolean load(final String filePath) {
        return load(new File(filePath));
    }

    /**
     * Loads bookmarks from the specified list of ROIs. ROIs can be of any type. If area ROIs are provided, their
     * centroids are used as bookmark locations.
     *
     * @param rois the list of ROIs to load bookmarks from
     */
    public void load(final List<Roi> rois) {
        for (final Roi roi : rois) {
            if (roi instanceof PointRoi) {
                final FloatPolygon fp = roi.getFloatPolygon();
                for (int i = 0; i < fp.npoints; i++) {
                    final Bookmark b = new Bookmark(roi.getName(), fp.xpoints[i], fp.ypoints[i],
                            roi.getZPosition(), roi.getCPosition(), roi.getTPosition(), roi.getStrokeColor());
                    model.getDataList().add(b);
                }
            } else {
                final double[] centroid = RoiConverter.get2dCentroid(roi);
                final Bookmark b = new Bookmark(roi.getName(), centroid[0], centroid[1],
                        roi.getZPosition(), roi.getCPosition(), roi.getTPosition(), roi.getStrokeColor());
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
        int[] rows = (onlySelectedRows) ? table.getSelectedRows() : IntStream.range(0, model.getRowCount()).toArray();
        if (onlySelectedRows && rows.length == 0) // no selection exists: assume all rows
            rows = IntStream.range(0, model.getRowCount()).toArray();
        for (final int row : rows) {
            // Convert view index to model index (handles sorted table)
            final int modelRow = (onlySelectedRows && table.getRowSorter() != null)
                    ? table.convertRowIndexToModel(row) : row;
            final Bookmark b = model.getDataList().get(modelRow);
            rois.add(b.toRoi());
        }
        return rois;
    }

    /**
     * Returns a list of points representing the bookmarks.
     *
     * @param onlySelectedRows if true, only selected rows are included; otherwise, all ROIs in the manager are included
     * @return the list of Points representing the bookmarks
     */
    public List<SNTPoint> getPixelPositions(final boolean onlySelectedRows) {
        final List<SNTPoint> points = new ArrayList<>();
        int[] rows = (onlySelectedRows) ? table.getSelectedRows() : IntStream.range(0, model.getRowCount()).toArray();
        if (onlySelectedRows && rows.length == 0) // no selection exists: assume all rows
            rows = IntStream.range(0, model.getRowCount()).toArray();
        for (final int row : rows) {
            // Convert view index to model index (handles sorted table)
            final int modelRow = (onlySelectedRows && table.getRowSorter() != null)
                    ? table.convertRowIndexToModel(row) : row;
            final Bookmark b = model.getDataList().get(modelRow);
            points.add(b);
        }
        return points;
    }

    /**
     * Returns a list of points representing the bookmarks.
     *
     * @param onlySelectedRows if true, only selected rows are included; otherwise, all ROIs in the manager are included
     * @return the list of Points representing the bookmarks
     */
    @SuppressWarnings("unchecked")
    public List<SNTPoint> getPositions(final boolean onlySelectedRows) {
        final ij.measure.Calibration cal = sntui.plugin.getPathAndFillManager().getBoundingBox(false).getCalibration();
        final List<Path.PathNode> pixelPoints = (List<Path.PathNode>) (List<?>) getPixelPositions(onlySelectedRows);
        for (final Path.PathNode pxP : pixelPoints) {
            pxP.x = cal.getX(pxP.getX());
            pxP.y = cal.getY(pxP.getY());
            pxP.z = cal.getZ(pxP.getZ());
        }
        return (List<SNTPoint>) (List<?>) pixelPoints;  // spatially calibrated positions
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
        xyzctLocations.forEach(loc -> rois.add( //
                new Bookmark(commonLabel + ai.getAndIncrement(), //
                        (int) loc[0], (int) loc[1], (int) loc[2], (int) loc[3], (int) loc[4], color).toRoi()));
        return rois;
    }
}

class Bookmark extends Path.PathNode {
    String label;
    final int c;
    int t;
    float size; // sphere radius in world units; 0 means "use viewer default"

    Bookmark(final String label, double x, double y, double z, int c, int t) {
        this(label, x, y, z, c, t, null);
    }

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
            case 5 -> c;  // SNT mode: channel; BVV mode: size (accessed via separate index)
            case 6 -> t;
            case 7 -> size; // BVV size column
            default -> null;
        };
    }

    PointRoi toRoi() {
        final PointRoi roi = new PointRoi(x, y);
        if (getColor() != null)
            roi.setStrokeColor(getColor());
        roi.setPosition(c, (int) z, t);
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
            final String msg = "Bookmark image locations using Shift+B";
            final java.awt.Rectangle visible = getVisibleRect();
            g2.drawString(msg, visible.x + (visible.width - fm.stringWidth(msg)) / 2,
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

    private static final String[] SNT_HEADER = {"Tag", "Label", "X", "Y", "Z", "C", "T"};
    private static final String[] BVV_HEADER = {"Tag", "Label", "X", "Y", "Z", "Size"};
    private final String[] HEADER;
    private final boolean bvvMode;
    private List<Bookmark> dataList = new ArrayList<>();

    BookmarkModel(final boolean bvvMode) {
        this.bvvMode = bvvMode;
        this.HEADER = bvvMode ? BVV_HEADER : SNT_HEADER;
    }

    List<Bookmark> getDataList() {
        return dataList;
    }

    void setDataList(final List<Bookmark> dataList) {
        this.dataList = dataList;
        fireTableDataChanged();
    }

    String getUniqueLabel(final String candidate) {
        final String base = (candidate == null || candidate.isBlank())
                ? (bvvMode ? "Marker" : String.format("Bookmark %02d", 1 + getDataList().size()))
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
        final SNTTable table = new SNTTable(file.getAbsolutePath());
        final int tagIdx = table.getColumnIndex(HEADER[0]);
        final int lIdx   = table.getColumnIndex(HEADER[1]);
        final int xIdx   = table.getColumnIndex(HEADER[2]);
        final int yIdx   = table.getColumnIndex(HEADER[3]);
        final int zIdx   = table.getColumnIndex(HEADER[4]);
        final int sizeIdx = bvvMode ? table.getColumnIndex("Size") : -1;
        final int cIdx = bvvMode ? -1 : table.getColumnIndex(HEADER[5]);
        final int tIdx = bvvMode ? -1 : table.getColumnIndex(HEADER[6]);

        if (lIdx == -1 || xIdx == -1 || yIdx == -1 || zIdx == -1)
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
            final Bookmark b = new Bookmark((String) table.get(lIdx, i),
                    (double) table.get(xIdx, i), (double) table.get(yIdx, i), (double) table.get(zIdx, i),
                    (cIdx == -1) ? 1 : (int) ((double) table.get(cIdx, i)),
                    (tIdx == -1) ? 1 : (int) ((double) table.get(tIdx, i)),
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
        if (bvvMode && col == 5) return dataList.get(row).size; // Size column
        return dataList.get(row).get(col);
    }

    @Override
    public boolean isCellEditable(final int row, final int col) {
        if (row >= dataList.size()) return false;
        if (bvvMode) return col == 0 || col == 1 || col == 5; // Tag, Label, Size
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
        } else if (bvvMode && columnIndex == 5) {
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
        if (bvvMode && column == 5) return Float.class;
        return switch (column) {
            case 2, 3, 4 -> Double.class;
            default -> Integer.class;
        };
    }

}
