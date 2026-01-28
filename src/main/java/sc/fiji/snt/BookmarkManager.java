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
import ij.gui.Overlay;
import ij.gui.PointRoi;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;
import ij.process.FloatPolygon;
import sc.fiji.snt.analysis.SNTTable;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.IconFactory;
import sc.fiji.snt.util.ImpUtils;
import sc.fiji.snt.util.PointInCanvas;
import sc.fiji.snt.util.SNTPoint;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
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

    private final SNTUI sntui;
    private final BookmarkModel model;
    private final BookmarkTable table;
    private int visitingZoomPercentage;


    /**
     * Implements the <i>Bookmark Manager</i> pane.
     * This class manages bookmarks for image locations, allowing users to quickly revisit specific locations.
     * Bookmarks can be imported/exported and do not persist across sessions.
     *
     * @see SNTUI
     * @see Bookmark
     */
    public BookmarkManager(final SNTUI sntui) {
        this.sntui = sntui;
        model = new BookmarkModel();
        table = assembleTable(model);
        resetVisitingZoom();
    }

    protected JPanel getPanel() {
        final JPanel container = SNTUI.InternalUtils.getTab();
        final GridBagConstraints gbc = GuiUtils.defaultGbc();
        gbc.fill = GridBagConstraints.BOTH;
        SNTUI.InternalUtils.addSeparatorWithURL(container, "Bookmarks:", true, gbc);
        gbc.gridy++;
        final String msg = """
                This pane stores image locations that you can use to quickly (re)visit while \
                tracing. Bookmarks are not saved by default and must be exported manually.
                
                To create a bookmark: Right-click on the image and choose "Bookmark Cursor Location" \
                from the contextual menu (or press Shift+B).
                To visit a bookmarked location: Double-click on its entry.
                """;
        gbc.weighty = 0.0;
        container.add(GuiUtils.longSmallMsg(msg, container), gbc);
        gbc.gridy++;
        gbc.weighty = 0.95;
        container.add(table.getContainer(), gbc);
        gbc.gridy++;
        gbc.weighty = 0.0;
        container.add(assembleToolbar(), gbc);
        return container;
    }

    private BookmarkTable assembleTable(final BookmarkModel model) {
        final BookmarkTable table = new BookmarkTable(model);
        table.setComponentPopupMenu(assembleTablePopupMenu());
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(final MouseEvent me) {
                if (me.getClickCount() == 2) {
                    if (noBookmarksError()) return;
                    final int row = table.getSelectedRow();
                    if (row == -1) {
                        sntui.guiUtils.error("No bookmark selected.");
                        return;
                    }
                    final ImagePlus imp = sntui.plugin.getImagePlus();
                    if (imp == null) sntui.guiUtils.error("No image is currently open.");
                    else goTo(row, imp);
                }
            }
        });
        return table;
    }

    private void resetOrResizeColumns(final boolean reset, final boolean resize) {
        assert table != null;
        assert model != null;
        if (reset) { // https://stackoverflow.com/q/63420045
            final TableColumnModel tcm = table.getColumnModel();
            for (int i = 0; i < model.getColumnCount() - 1; i++) {
                int location = tcm.getColumnIndex(model.getColumnName(i));
                tcm.moveColumn(location, i);
            }
        }
        if (resize) { // https://stackoverflow.com/a/26046778
            final float[] columnWidthPercentage = {0.63f, 0.09f, 0.09f, 0.09f, 0.05f, 0.05f};
            final int tW = table.getColumnModel().getTotalColumnWidth();
            TableColumn column;
            final TableColumnModel jTableColumnModel = table.getColumnModel();
            int cantCols = jTableColumnModel.getColumnCount();
            for (int i = 0; i < cantCols; i++) {
                column = jTableColumnModel.getColumn(i);
                int pWidth = Math.round(columnWidthPercentage[i] * tW);
                column.setPreferredWidth(pWidth);
            }
        }
    }

    private JPopupMenu assembleTablePopupMenu() {
        final JPopupMenu pMenu = new JPopupMenu();
        JMenuItem mi = new JMenuItem("Deselect / Select All", IconFactory.menuIcon(IconFactory.GLYPH.CHECK_DOUBLE));
        mi.addActionListener(e -> {
            if (table.getSelectedRows().length > 0) {
                table.clearSelection();
            } else if (model.getRowCount() > 0) {
                table.setRowSelectionInterval(0, model.getRowCount() - 1);
            }
            recordCmd("clearSelection()"); // Note: only records clear, not select all
        });
        pMenu.add(mi);
        pMenu.addSeparator();
        mi = new JMenuItem("Remove All...", IconFactory.menuIcon(IconFactory.GLYPH.TRASH));
        mi.addActionListener(e -> {
            if (!noBookmarksError() && sntui.guiUtils.getConfirmation("Delete all bookmarks?", "Delete All?")) {
                reset();
                recordCmd("reset()");
            }
        });
        pMenu.add(mi);
        mi = new JMenuItem("Remove Selected Row(s)", IconFactory.menuIcon(IconFactory.GLYPH.TRASH));
        mi.addActionListener(e -> {
            if (noBookmarksError()) return;
            final int[] viewRows = table.getSelectedRows();
            if (viewRows.length == 0) {
                sntui.guiUtils.error("No bookmark selected.");
                return;
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
        mi = new JMenuItem("Rename Selected Bookmark...", IconFactory.menuIcon(IconFactory.GLYPH.PEN));
        mi.addActionListener(e -> {
            if (noBookmarksError()) return;
            final int row = table.getSelectedRow();
            if (row == -1) {
                sntui.guiUtils.error("No bookmark selected.");
            } else {
                if (table.getRowCount() > 10)
                    table.scrollRectToVisible(new Rectangle(table.getCellRect(row, 0, true)));
                table.editCellAt(row, 0);
            }
        });
        pMenu.add(mi);
        pMenu.addSeparator();
        mi = new JMenuItem("Reset Columns", IconFactory.menuIcon(IconFactory.GLYPH.UNDO));
        mi.addActionListener(e -> {
            resetOrResizeColumns(true, true);
            recordComment("Bookmark Manager: resizeColumns()");
        });
        pMenu.add(mi);
        return pMenu;
    }

    private void recordCmd(final String cmd) {
        if (null != sntui.getRecorder(false))
            sntui.getRecorder(false).recordCmd("snt.getUI().getBookmarkManager()." + cmd);
    }

    private void recordComment(final String comment) {
        if (null != sntui.getRecorder(false))
            sntui.getRecorder(false).recordComment(comment);
    }

    private JPopupMenu importMenu() {
        final JPopupMenu menu = new JPopupMenu();
        JMenuItem jmi = new JMenuItem("From CSV File...");
        menu.add(jmi);
        jmi.addActionListener(e -> {
            final File file = sntui.openFile("csv");
            if (file != null) {
                recordCmd("load(\"" + file.getAbsolutePath() + "\")");
                loadBookmarksFromFile(file);
                sntui.showStatus(model.getDataList().size() + " listed bookmarks ", true);
            }
        });
        jmi = new JMenuItem("From Image Overlay");
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
        jmi = new JMenuItem("From ROI Manager");
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
        return menu;
    }

    private JPopupMenu exportMenu() {
        final JPopupMenu menu = new JPopupMenu();
        JMenuItem jmi = new JMenuItem("To CSV File...");
        menu.add(jmi);
        jmi.addActionListener(e -> {
            if (noBookmarksError()) return;
            final File saveFile = sntui.saveFile("Export Bookmarks to CSV...",
                    "SNT_Bookmarks.csv", "csv");
            if (saveFile != null) {
                recordCmd("save(\"" + saveFile.getAbsolutePath() + "\")");
                if (saveBookMarksToFile(saveFile)) {
                    sntui.showStatus("Export complete.", true);
                } else {
                    sntui.showStatus("Exporting failed.", true);
                    sntui.guiUtils.error("Exporting failed. See Console for details.");
                }
            }
        });
        jmi = new JMenuItem("To Image Overlay");
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
        jmi = new JMenuItem("To ROI Manager");
        menu.add(jmi);
        jmi.addActionListener(e -> {
            if (noBookmarksError()) return;
            table.clearSelection();
            toRoiManager();
            recordCmd("clearSelection()");
            recordCmd("toRoiManager()");
        });
        return menu;
    }

    void resetVisitingZoom() {
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

    private JToolBar assembleToolbar() {
        final JButton impButton = GuiUtils.Buttons.OptionsButton(IconFactory.GLYPH.IMPORT, 1f, importMenu());
        impButton.setToolTipText("Import bookmarks");
        final JButton expButton = GuiUtils.Buttons.OptionsButton(IconFactory.GLYPH.EXPORT, 1f, exportMenu());
        expButton.setToolTipText("Export bookmarks");
        final JSpinner spinner = GuiUtils.integerSpinner(Math.clamp(visitingZoomPercentage, 25, 3200),
                25, 3200, 50, true);
        spinner.addChangeListener(e -> visitingZoomPercentage = (int) spinner.getValue());
        spinner.setToolTipText("The preferred zoom level (between 25 and 3200%) for visiting a bookmarked location");
        final JButton autoButton = GuiUtils.Buttons.undo();
        autoButton.setToolTipText("<HTML>Resets level to two <i>Zoom In [+]</i> operations above the current image zoom");
        autoButton.addActionListener(e -> {
            if (null == sntui.plugin.getImagePlus()) {
                sntui.showStatus("Current zoom unknown: No image is loaded...", true);
            } else {
                resetVisitingZoom();
                visitingZoomPercentage = Math.clamp(visitingZoomPercentage, 25, 3200);
                spinner.setValue(visitingZoomPercentage);
            }
        });
        final JToolBar tb = new JToolBar();
        tb.setFloatable(false);
        tb.add(impButton);
        tb.addSeparator();
        tb.add(expButton);
        tb.addSeparator();
        tb.add(Box.createHorizontalGlue());
        tb.add(new JLabel("Preferred zoom level (%): "));
        tb.add(spinner);
        tb.add(autoButton);
        return tb;
    }

    private void goTo(final int row, final ImagePlus imp) {
        assert imp != null;
        final Bookmark b = model.getDataList().get(table.convertRowIndexToModel(row));
        if (b.x > imp.getWidth() || b.y > imp.getHeight()) {
            sntui.guiUtils.error("Location is outside image XY dimensions");
            return;
        }
        imp.setPosition(b.c, (int) b.z, b.t);
        ImpUtils.zoomTo(imp, (double) visitingZoomPercentage / 100, (int) b.x, (int) b.y);
    }

    private void loadBookmarksFromFile(final File file) {
        try {
            model.populateFromFile(file);
        } catch (final Exception ex) {
            sntui.guiUtils.error(ex.getMessage());
            SNTUtils.error("loadBookmarksFromFile() failure", ex);
        }
    }

    private boolean saveBookMarksToFile(final File file) {
        final SNTTable exportTable = new SNTTable();
        for (final Bookmark b : model.getDataList()) {
            exportTable.appendToLastRow("Label", b.label);
            exportTable.appendToLastRow("X", b.x);
            exportTable.appendToLastRow("Y", b.y);
            exportTable.appendToLastRow("Z", b.z);
            exportTable.appendToLastRow("C", b.c);
            exportTable.appendToLastRow("T", b.t);
        }
        try {
            exportTable.save(file);
            return true;
        } catch (final IOException ioe) {
            SNTUtils.error("saveBookMarksToFile() failure", ioe);
        }
        return false;
    }

    private boolean noBookmarksError() {
        final List<Bookmark> list = model.getDataList();
        if (list.isEmpty()) {
            sntui.guiUtils.error("No bookmarks exist. To create one, right-click on the image and choose "//
                    + "\"Bookmark cursor location\" (Shift+B).");
            return true;
        }
        return false;
    }

    protected void add(final int x, final int y, final int z, final ImagePlus imp) {
        add(x, y, z, imp.getC(), imp.getT());
        recordCmd("add(" + x + ", " + y + ", " + z  + ", " + imp.getC() + ", " + imp.getT() +")");
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
        AtomicInteger ai = new AtomicInteger(1);
        xyzctLocations.forEach(loc -> model.getDataList().add( //
                new Bookmark(model.getUniqueLabel(label + ai.getAndIncrement()), //
                        (int) loc[0], (int) loc[1], (int) loc[2], (int) loc[3], (int) loc[4])));
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
            int counter = 1;
            for (final int nodeIndex : set) {
                final PointInCanvas node = path.getPointInCanvas(nodeIndex);
                final String l = (set.size()==1) ? label : label + "#" + counter++;
                model.getDataList().add(new Bookmark(model.getUniqueLabel(l),
                        (int) node.getX(), (int) node.getY(), (int) node.getZ(), c, t));
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
                            roi.getZPosition(), roi.getCPosition(), roi.getTPosition());
                    model.getDataList().add(b);
                }
            } else {
                final double[] centroid = roi.getContourCentroid();
                final Bookmark b = new Bookmark(roi.getName(), centroid[0], centroid[1],
                        roi.getZPosition(), roi.getCPosition(), roi.getTPosition());
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
            final PointRoi roi = new PointRoi(b.x, b.y);
            roi.setPosition(b.c, (int) b.z, b.t);
            roi.setName(b.label);
            rois.add(roi);
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
            points.add(SNTPoint.of(b.x, b.y, b.z));
        }
        return points;
    }

    /**
     * Returns a list of points representing the bookmarks.
     *
     * @param onlySelectedRows if true, only selected rows are included; otherwise, all ROIs in the manager are included
     * @return the list of Points representing the bookmarks
     */
    public List<SNTPoint> getPositions(final boolean onlySelectedRows) {
        final ij.measure.Calibration cal = sntui.plugin.getPathAndFillManager().getBoundingBox(false).getCalibration();
        List<SNTPoint> pixelPoints = getPixelPositions(onlySelectedRows);
        final List<SNTPoint> calPoints = new ArrayList<>(pixelPoints.size());
        for (SNTPoint pxP : pixelPoints) {
            calPoints.add(SNTPoint.of(cal.getX(pxP.getX()), cal.getY(pxP.getY()), cal.getZ(pxP.getZ())));
        }
        return calPoints;
    }

    /**
     * Adds the bookmark ROIs to the specified overlay. If no bookmarks are selected, all bookmarks are added,
     * otherwise only the selected bookmarks are added.
     *
     * @param overlay the overlay to add the bookmarks to. Null not allowed
     */
    public void toOverlay(final Overlay overlay) {
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
}

class Bookmark extends PointInCanvas {
    String label;
    final int c;
    int t;

    Bookmark(final String label, double x, double y, double z, int c, int t) {
        super(x, y, z);
        this.label = label;
        this.c = c;
        this.t = t;
    }

    Object get(final int entry) {
        return switch (entry) {
            case 0 -> label;
            case 1 -> x;
            case 2 -> y;
            case 3 -> z;
            case 4 -> c;
            case 5 -> t;
            default -> null;
        };
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
        setColumnSelectionAllowed(false);
        setRowSelectionAllowed(true);
        setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        setDefaultEditor(String.class, new CellEditor());
    }

    JScrollPane getContainer() {
        final JScrollPane js = new JScrollPane(this);
        js.setComponentPopupMenu(getComponentPopupMenu()); // allow popupmenu to be displayed when clicking below last row
        return js;
    }

    @Override
    public boolean editCellAt(int row, int column, EventObject e) {
        final boolean result = super.editCellAt(row, column, e);
        final JTextField editor = (JTextField) getEditorComponent();
        if (editor != null) {
            editor.requestFocus();
            editor.selectAll();
        }
        return result;
    }
}

class BookmarkModel extends AbstractTableModel {

    private final String[] HEADER = {"Label", "X", "Y", "Z", "C", "T"};
    private List<Bookmark> dataList = new ArrayList<>();

    List<Bookmark> getDataList() {
        return dataList;
    }

    void setDataList(final List<Bookmark> dataList) {
        this.dataList = dataList;
        fireTableDataChanged();
    }

    String[] getHeader() {
        return HEADER;
    }

    String getUniqueLabel(final String candidate) {
        if (null == candidate || candidate.isBlank())
            return String.format("Bookmark %02d", 1 + getDataList().size());
        if (getDataList().stream().anyMatch(b -> candidate.equalsIgnoreCase(b.label))) return candidate + " (2)";
        return candidate;
    }

    void populateFromFile(final File file) throws IOException {
        final SNTTable table = new SNTTable(file.getAbsolutePath());
        final int lIdx = table.getColumnIndex(getHeader()[0]);
        final int xIdx = table.getColumnIndex(getHeader()[1]);
        final int yIdx = table.getColumnIndex(getHeader()[2]);
        final int zIdx = table.getColumnIndex(getHeader()[3]);
        final int cIdx = table.getColumnIndex(getHeader()[4]);
        final int tIdx = table.getColumnIndex(getHeader()[5]);

        if (lIdx == -1 || xIdx == -1 || yIdx == -1 || zIdx == -1)
            throw new IOException("Unexpected column header(s) in CSV file.");
        final List<Bookmark> dataList = new ArrayList<>();
        for (int i = 0; i < table.getRowCount(); i++) {
            dataList.add(new Bookmark((String) table.get(lIdx, i), // label
                    (double) table.get(xIdx, i), (double) table.get(yIdx, i), (double) table.get(zIdx, i), // x,y,z
                    (cIdx == -1) ? 1 : (int) ((double) table.get(cIdx, i)), // c
                    (tIdx == -1) ? 1 : (int) ((double) table.get(tIdx, i)) // t
            ));
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
        Object value = null;
        if (row < dataList.size()) {
            value = dataList.get(row).get(col);
        }
        return value;
    }

    @Override
    public boolean isCellEditable(final int row, final int col) {
        return 0 == col && getValueAt(row, col) != null;
    }

    @Override
    public void setValueAt(final Object aValue, final int rowIndex, final int columnIndex) {
        if (0 == columnIndex && !Objects.equals(aValue, dataList.get(rowIndex).label)) {
            dataList.get(rowIndex).label = getUniqueLabel((String) aValue);
        } else {
            super.setValueAt(aValue, rowIndex, columnIndex);
        }
    }

    @Override
    public Class<?> getColumnClass(int column) {
        return switch (column) {
            case 0 -> String.class;
            case 1, 2, 3 -> Double.class;
            default -> Integer.class;
        };
    }

}
