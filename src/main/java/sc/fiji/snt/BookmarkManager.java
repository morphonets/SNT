/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2024 Fiji developers.
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
import ij.gui.PointRoi;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;
import ij.process.FloatPolygon;
import sc.fiji.snt.analysis.SNTTable;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.util.ImpUtils;
import sc.fiji.snt.util.PointInCanvas;
import sc.fiji.snt.util.SNTPoint;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EventObject;
import java.util.List;
import java.util.Objects;
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
        final String msg = "This pane stores image locations that you can use to quickly (re)visit while " //
                + "tracing. Bookmarks do not persist across sessions and must be imported/exported manually.\n\n" //
                + "To create a bookmark: Right-click on the image and choose \"Bookmark cursor location\" from the " //
                + "contextual menu (or press Shift+B).\n"//
                + "To visit a bookmarked location: Double-click on its entry.";
        gbc.weighty = 0.1;
        container.add(sntui.largeMsg(msg), gbc);
        gbc.gridy++;
        gbc.weighty = 0.95;
        container.add(table.getContainer(), gbc);
        gbc.gridy++;
        gbc.weighty = 0.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        container.add(assembleZoomPanel(), gbc);
        gbc.gridy++;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        gbc.fill = GridBagConstraints.NONE;
        container.add(assembleButtonPanel(), gbc);
        resizeColumns();
        return container;
    }

    private BookmarkTable assembleTable(final BookmarkModel model) {
        final BookmarkTable table = new BookmarkTable(model);
        table.setComponentPopupMenu(assembleTablePopupMenu());
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(final MouseEvent me) {
                if (noBookmarksError()) return;
                final int row = table.getSelectedRow();
                if (row == -1) {
                    sntui.guiUtils.error("No bookmark selected.");
                    return;
                }
                if (me.getClickCount() == 2) {
                    final ImagePlus imp = sntui.plugin.getImagePlus();
                    if (imp == null) sntui.guiUtils.error("No bookmark selected or no image is currently open.");
                    else goTo(row, imp);
                }
            }
        });
        return table;
    }

    private void resizeColumns() {
        // https://stackoverflow.com/a/26046778
        float[] columnWidthPercentage = {0.675f, 0.075f, 0.075f, 0.075f, 0.05f, 0.05f};
        int tW = table.getColumnModel().getTotalColumnWidth();
        TableColumn column;
        final TableColumnModel jTableColumnModel = table.getColumnModel();
        int cantCols = jTableColumnModel.getColumnCount();
        for (int i = 0; i < cantCols; i++) {
            column = jTableColumnModel.getColumn(i);
            int pWidth = Math.round(columnWidthPercentage[i] * tW);
            column.setPreferredWidth(pWidth);
        }
    }

    private JPopupMenu assembleTablePopupMenu() {
        final JPopupMenu pMenu = new JPopupMenu();
        JMenuItem  mi = new JMenuItem("Deselect / Select All");
        mi.addActionListener(e -> {
            clearSelection();
            recordCmd("clearSelection()");
        });
        pMenu.add(mi);
        pMenu.addSeparator();
        mi = new JMenuItem("Remove All...");
        mi.addActionListener(e -> {
            if (!noBookmarksError() && sntui.guiUtils.getConfirmation("Delete all bookmarks?", "Delete All?")) {
                reset();
                recordCmd("reset()");
            }
        });
        pMenu.add(mi);
        mi = new JMenuItem("Remove Selected Row(s)");
        mi.addActionListener( e -> {
            if (noBookmarksError()) return;
            final int[] rows = table.getSelectedRows();
            for (int i = 0; i < rows.length; i++)
                model.removeRow(rows[i] - i);
        });
        pMenu.add(mi);
        pMenu.addSeparator();
        mi = new JMenuItem("Rename Selected Bookmark...");
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
        mi = new JMenuItem("Reset Columns");
        mi.addActionListener(e -> {
            resizeColumns();
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
            final File file = sntui.openCsvFile();
            if (file != null) {
                recordCmd("load(\"" + file.getAbsolutePath() + "\")");
                loadBookmarksFromFile(file);
                sntui.showStatus(model.getDataList().size() + " listed bookmarks ", true);
            }
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
        jmi = new JMenuItem("To ROI Manager");
        menu.add(jmi);
        jmi.addActionListener(e -> {
            table.clearSelection();
            toRoiManager();
            recordCmd("clearSelection()");
            recordCmd("toRoiManager()");
        });
        return menu;
    }

    private JPanel assembleButtonPanel() {
        final JButton impButton = new JButton("Import...");
        final JPopupMenu impMenu = importMenu();
        impButton.addActionListener(e -> impMenu.show(impButton, impButton.getWidth() / 2, impButton.getHeight() / 2));
        final JButton expButton = new JButton("Export...");
        final JPopupMenu expMenu = exportMenu();
        expButton.addActionListener(e -> {
            if (!noBookmarksError()) expMenu.show(expButton, expButton.getWidth() / 2, expButton.getHeight() / 2);
        });
        final JPanel buttonPanel = new JPanel(new GridLayout(0, 2));
        buttonPanel.setBorder(new EmptyBorder(SNTUI.InternalUtils.MARGIN, 0, SNTUI.InternalUtils.MARGIN, 0));
        buttonPanel.add(impButton);
        buttonPanel.add(expButton);
        return buttonPanel;
    }

    void resetVisitingZoom() {
        try {
            final double currentMag = sntui.plugin.getImagePlus().getCanvas().getMagnification();
            final double nextUp1x = ImageCanvas.getHigherZoomLevel(currentMag);
            final double nextUp2x = ImageCanvas.getHigherZoomLevel(nextUp1x);
            visitingZoomPercentage = (int) Math.round(nextUp2x * 100);
        } catch (final NullPointerException ignored) {
            visitingZoomPercentage = 600;
        }
    }

    private JPanel assembleZoomPanel() {
        final JSpinner spinner = GuiUtils.integerSpinner(visitingZoomPercentage, 100, 3200, 100, true);
        spinner.addChangeListener(e -> visitingZoomPercentage = (int) spinner.getValue());
        final JButton autoButton = new JButton("Auto");
        autoButton.addActionListener(e -> {
            if (null == sntui.plugin.getImagePlus()) {
                sntui.showStatus("Current zoom unknown: No image is loaded...", true);
            } else {
                resetVisitingZoom();
                spinner.setValue(visitingZoomPercentage);
            }
        });
        final JPanel p = new JPanel();
        p.setLayout(new GridBagLayout());
        final GridBagConstraints c = GuiUtils.defaultGbc();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridwidth = 3;
        c.weightx = 0.8;
        p.add(GuiUtils.leftAlignedLabel("Preferred zoom level (%): ", true));
        c.gridx = 1;
        c.weightx = 0.2;
        p.add(spinner, c);
        c.gridx = 2;
        p.add(autoButton);
        GuiUtils.addTooltip(p, "The preferred zoom level (between 100 and 3200%) for visiting a bookmarked location.<br>"
                + "<i>Auto</i> increases the level by two <i>Zoom In [+]</i> operations.");
        return p;
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
            ioe.printStackTrace();
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

    public void add(final int x, final int y, final int z, final int c, final int t) {
        model.getDataList().add(new Bookmark(model.getUniqueLabel(""), x, y, z, c, t));
        model.fireTableDataChanged();
    }

    public void add(final String label, final List<SNTPoint> locations, final int channel, final int frame) {
        locations.forEach(loc -> model.getDataList().add(new Bookmark(model.getUniqueLabel(label), //
                (int) loc.getX(), (int) loc.getY(), (int) loc.getZ(), channel, frame)));
        model.fireTableDataChanged();
    }

    public void clearSelection() {
        table.clearSelection();
    }

    public void reset() {
        model.setDataList(new ArrayList<>());
    }

    protected boolean isShowing() {
        return table.isShowing();
    }

    public boolean load(final File file) {
        loadBookmarksFromFile(file);
        return !model.getDataList().isEmpty();
    }

    public boolean load(final String filePath) {
        return load(new File(filePath));
    }

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

    public void load(final Roi[] rois) {
        load(List.of(rois)); // script friendly version
    }

    public boolean save(final File file) {
        loadBookmarksFromFile(file);
        return !model.getDataList().isEmpty();
    }

    public boolean save(final String filePath) {
        return save(new File(filePath));
    }

    public List<Roi> getROIs(final boolean onlySelectedRows) {
        final List<Roi> rois = new ArrayList<>();
        int[] rows = (onlySelectedRows) ? table.getSelectedRows() : IntStream.range(0, model.getRowCount()).toArray();
        if (onlySelectedRows && rows.length == 0) // no selection exists: assume all rows
            rows = IntStream.range(0, model.getRowCount()).toArray();
        for (final int row : rows) {
            final Bookmark b = model.getDataList().get(row);
            final PointRoi roi = new PointRoi(b.x, b.y);
            roi.setPosition(b.c, (int) b.z, b.t);
            roi.setName(b.label);
            rois.add(roi);
        }
        return rois;
    }

    public void toRoiManager() {
        RoiManager rm = RoiManager.getInstance2();
        if (rm == null) rm = new RoiManager();
        for (final Roi roi : getROIs(table.getSelectedRows().length>0))
            rm.addRoi(roi);
    }
}

class Bookmark extends PointInCanvas {
    String label;
    int c;
    int t;

    Bookmark(final String label, double x, double y, double z, int c, int t) {
        super(x, y, z);
        this.label = label;
        this.c = c;
        this.t = t;
    }

    Object get(final int entry) {
        switch (entry) {
            case 0:
                return label;
            case 1:
                return x;
            case 2:
                return y;
            case 3:
                return z;
            case 4:
                return c;
            case 5:
                return t;
            default:
                return null;
        }
    }
}

class CellEditor extends DefaultCellEditor {

    public CellEditor() {
        super(new JTextField());
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
        if (null == candidate || candidate.isEmpty() || candidate.isBlank())
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
            throw new IOException("Unexpected column header(s) in CSV file");
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
        switch (column) {
            case 0:
                return String.class;
            case 1:
            case 2:
            case 3:
                return Double.class;
            default:
                return Integer.class;
        }
    }

}

