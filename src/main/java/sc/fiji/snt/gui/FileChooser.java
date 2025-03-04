/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2025 Fiji developers.
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

package sc.fiji.snt.gui;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.icons.FlatAbstractIcon;
import com.formdev.flatlaf.util.SystemInfo;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.TableColumnModelEvent;
import javax.swing.event.TableColumnModelListener;
import javax.swing.filechooser.FileFilter;
import javax.swing.table.TableColumnModel;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.geom.*;
import java.io.File;
import java.io.Serial;
import java.util.List;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;

/**
 * Improvements to JFileChooser, namely:
 * <pre>
 * - Accessory toolbar with FlatLaf buttons to:
 *   - Navigation history: Drop-down list of recent locations
 *   - Toggle visibility of hidden files
 *   - Filter file list by string pattern
 *   - Reveal current directory in native file explorer
 *   - Rescan current directory
 * - Confirmation dialog when overriding files
 * - Fix for column widths resetting on directory change
 * - Fix for detailed view not resizing with dialog
 * - Setters for list/details view
 * - Workaround current directory defaulting to root directory on Linux
 * </pre>
 * Modifications should fail gracefully and are not expected to interfere w/ normal JFileChooser functionality. Drag
 * and drop support is provided by GuiUtils to keep dependencies to a minimum (FlatLaf is the only dependency needed).
 * TODO: submit this upstream to SciJava
 */
public class FileChooser extends JFileChooser {

    @Serial
    private static final long serialVersionUID = 9398079702362074L;
    private static final int MAX_RECENT_LOCATIONS = 8;
    private static final String RECENT_LOCATIONS_KEY = "fcLocations";
    private static final LinkedHashSet<File> recentLocations = new LinkedHashSet<>();
    private static final Logger LOGGER = Logger.getLogger(FileChooser.class.getName());
    private static Preferences prefs;
    private static FileNamePatternFilter filterPattern;
    private static List<Integer> rowWidths;
    private JTable detailsTable;
    private JToggleButton toggleHiddenFilesButton;
    private JToggleButton toggleFilterPatternButton;

    public FileChooser() {
        try {
            prefs = Preferences.userNodeForPackage(FileChooser.class);
            rowWidths = new ArrayList<>();
            loadRecentLocations();
            attachToolbar();
            setViewTypeDetails();
            detailsTable = findChildComponent(this, JTable.class);
            modCoreComponents();
        } catch (final Exception e) {
            Logger.getLogger(FileChooser.class.getName()).log(Level.SEVERE, "Failed to initialize FileChooser", e);
        }
    }

    /**
     * Resets preferences and list of recent locations.
     */
    public static void reset() {
        clearPrefs();
        filterPattern = null;
        rowWidths.clear();
    }

    private static void clearPrefs() {
        try {
            prefs.clear();
        } catch (final Exception e) {
            LOGGER.log(Level.WARNING, "Failed to clear preferences", e);
        } finally {
            recentLocations.clear();
        }
    }

    private void modCoreComponents() {
        try {
            if (detailsTable != null) {
                // column reordering is not functional: Since table model changes on every directory change order of
                // columns is always reset to default. To avoid confusion and bugs it is best to disable it altogether
                detailsTable.getTableHeader().setReorderingAllowed(false);
                // ensure table stretches the full width of the scroll pane when dialog is resized
                addComponentAdapterAsNeeded();
            }
            final JTextField tf = findChildComponent(this, JTextField.class);
            if (tf != null) tf.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        } catch (final Exception e) {
            LOGGER.log(Level.WARNING, "Failed to modify core components", e);
        }
    }

    private void attachTableColumnModelListenerAsNeeded() {
        if (detailsTable == null || isTableColumnModelListenerAdded()) return;
        // table model changes on directory change, so this needs to be called every file change
        final TableColumnModel columnModel = detailsTable.getColumnModel();
        columnModel.addColumnModelListener(new TableColumnModelListener() {
            @Override
            public void columnAdded(TableColumnModelEvent e) {
            }

            @Override
            public void columnRemoved(TableColumnModelEvent e) {
            }

            @Override
            public void columnSelectionChanged(ListSelectionEvent e) {
            }

            @Override
            public void columnMoved(TableColumnModelEvent e) {
            }

            @Override
            public void columnMarginChanged(ChangeEvent e) {
                for (int i = 0; i < columnModel.getColumnCount(); i++) {
                    rowWidths.set(i, columnModel.getColumn(i).getPreferredWidth());
                }
            }
        });
    }

    private boolean isTableColumnModelListenerAdded() {
        if (detailsTable == null) return false;
        for (final TableColumnModelListener listener : detailsTable.getListeners(TableColumnModelListener.class)) {
            if (listener != null) return true;
        }
        return false;
    }

    private void addComponentAdapterAsNeeded() {
        if (detailsTable == null || isComponentAdapterAdded()) return;
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                final JScrollPane scrollPane = findChildComponent(FileChooser.this, JScrollPane.class);
                if (scrollPane != null) {
                    scrollPane.addComponentListener(new ComponentAdapter() {
                        @Override
                        public void componentResized(ComponentEvent e) {
                            adjustFirstColumnWidth(scrollPane);
                        }
                    });
                }
            }
        });
    }

    private void adjustFirstColumnWidth(final JScrollPane scrollPane) {
        assert detailsTable != null;
        final int totalWidth = scrollPane.getViewport().getWidth();
        int otherColumnsWidth = 0;
        for (int i = 1; i < detailsTable.getColumnCount(); i++) {
            otherColumnsWidth += detailsTable.getColumnModel().getColumn(i).getPreferredWidth();
        }
        final int firstColumnWidth = totalWidth - otherColumnsWidth;
        if (firstColumnWidth > 0) {
            detailsTable.getColumnModel().getColumn(0).setPreferredWidth(firstColumnWidth);
        }
    }

    private boolean isComponentAdapterAdded() {
        for (final ComponentListener listener : getComponentListeners()) {
            if (listener instanceof ComponentAdapter) {
                return true;
            }
        }
        return false;
    }

    private void adjustColumnWidths() {
        if (detailsTable == null) return;
        final TableColumnModel columnModel = detailsTable.getColumnModel();
        final int nCols = columnModel.getColumnCount();
        if (rowWidths.isEmpty()) { // first time detailsTable is displayed
            initializeColumnWidths(nCols);
        }
        for (int i = 0; i < Math.min(nCols, rowWidths.size()); i++) {
            columnModel.getColumn(i).setPreferredWidth(rowWidths.get(i));
        }
    }

    private void initializeColumnWidths(int nCols) {
        assert detailsTable != null;
        final FontMetrics fontMetrics = detailsTable.getFontMetrics(detailsTable.getFont());
        if (nCols > 0) rowWidths.add(fontMetrics.stringWidth(" A_REALLY_LONG_FILENAME_INCLUDING_EXTENSION.EXT"));
        if (nCols > 1) rowWidths.add(fontMetrics.stringWidth(" 999.99 MB"));
        if (nCols > 2) rowWidths.add(fontMetrics.stringWidth(" 99/99/99, 99:99 PM"));
    }

    private void savePrefs() {
        final StringBuilder recentLocationsString = new StringBuilder();
        for (final File dir : recentLocations) {
            recentLocationsString.append(dir.getAbsolutePath()).append(";");
        }
        try {
            prefs.put(RECENT_LOCATIONS_KEY, recentLocationsString.toString());
        } catch (final Exception e) {
            LOGGER.log(Level.WARNING, "Failed to save preferences", e);
        }
    }

    /**
     * Checks if the current view type is list view.
     *
     * @return true if the current view type is list view, false otherwise.
     */
    @SuppressWarnings("unused")
    public boolean isListView() {
        final Action list = getActionMap().get("viewTypeList");
        return list != null && Boolean.TRUE.equals(list.getValue(Action.SELECTED_KEY));
    }

    /**
     * Checks if the current view type is details view.
     *
     * @return true if the current view type is details view, false otherwise.
     */
    @SuppressWarnings("unused")
    public boolean isDetailsView() {
        final Action details = getActionMap().get("viewTypeDetails");
        return details != null && Boolean.TRUE.equals(details.getValue(Action.SELECTED_KEY));
    }

    /**
     * Sets the file view type to "list".
     */
    @SuppressWarnings("unused")
    public void setViewTypeList() {
        final Action details = getActionMap().get("viewTypeList");
        if (details != null) details.actionPerformed(null);
    }

    /**
     * Sets the file view type to "details".
     */
    public void setViewTypeDetails() {
        final Action details = getActionMap().get("viewTypeDetails");
        if (details != null) details.actionPerformed(null);
    }

    private <T> T findChildComponent(final Container container, final Class<T> cls) {
        for (final Component comp : container.getComponents()) {
            if (cls.isInstance(comp)) {
                return cls.cast(comp);
            } else if (comp instanceof Container) {
                final T c = findChildComponent((Container) comp, cls);
                if (c != null) return c;
            }
        }
        return null;
    }

    private void addRecentLocation(File dir) {
        if (dir == null || recentLocations == null) return;
        if (!dir.isDirectory()) dir = dir.getParentFile();
        recentLocations.remove(dir);
        recentLocations.addFirst(dir);
        if (recentLocations.size() > MAX_RECENT_LOCATIONS) recentLocations.removeLast();
    }

    @Override
    public File getCurrentDirectory() {
        final File currentDir = super.getCurrentDirectory();
        if (currentDir == null || (SystemInfo.isLinux && Arrays.asList(File.listRoots()).contains(currentDir))) {
            // Workaround a bug in Linux where somehow current directory is always root!?
            try {
                return new File(System.getProperty("user.home"));
            } catch (final Exception e) {
                LOGGER.log(Level.WARNING, "Failed to locate home folder", e);
            }
        }
        return currentDir;
    }

    @Override
    public void setCurrentDirectory(final File dir) {
        super.setCurrentDirectory(dir);
        if (isVisible() && null != dir) {
            adjustColumnWidths();
            attachTableColumnModelListenerAsNeeded();
            addRecentLocation(dir);
        }
    }

    private void loadRecentLocations() {
        String recentLocationsString;
        try {
            recentLocationsString = prefs.get(RECENT_LOCATIONS_KEY, "");
        } catch (final Exception e) {
            LOGGER.log(Level.WARNING, "Failed to read recent locations", e);
            recentLocationsString = "";
        }
        if (!recentLocationsString.isEmpty()) {
            final String[] paths = recentLocationsString.split(";");
            for (final String path : paths) {
                if (path == null || path.isEmpty()) continue;
                try {
                    final File dir = new File(path);
                    if (dir.exists() && dir.isDirectory()) {
                        recentLocations.add(dir);
                    }
                } catch (final SecurityException e) {
                    LOGGER.log(Level.WARNING, "Failed to load recent location: " + path, e);
                }
            }
        }
    }

    private void showRecentLocationsMenu(final JButton button) {
        final JPopupMenu popupMenu = new JPopupMenu();
        if (recentLocations.isEmpty()) {
            final JMenuItem jmi = new JMenuItem("No recent locations available...");
            jmi.setEnabled(false);
            popupMenu.add(jmi);
        } else {
            for (final File location : recentLocations) {
                String path = location.getAbsolutePath();
                if (path.length() > 50) {
                    path = "..." + path.substring(path.length() - 47);
                }
                final JMenuItem menuItem = new JMenuItem(path);
                menuItem.setEnabled(!location.equals(getCurrentDirectory()));
                menuItem.addActionListener(e -> {
                    if (!location.exists()) {
                        error("Directory does not exist.");
                    } else {
                        setCurrentDirectory(location);
                    }
                });
                popupMenu.add(menuItem);
            }
            popupMenu.addSeparator();
            final JMenuItem cItem = new JMenuItem("Clear Recent Locations");
            cItem.addActionListener(e -> clearPrefs());
            popupMenu.add(cItem);
        }
        popupMenu.show(button, 0, button.getHeight());
    }

    @Override
    public void setFileHidingEnabled(final boolean b) {
        if (toggleHiddenFilesButton != null) toggleHiddenFilesButton.setSelected(!b);
        super.setFileHidingEnabled(b);
    }

    @Override
    public void approveSelection() {
        final File f = getSelectedFile();
        if (f.exists() && getDialogType() == SAVE_DIALOG) {
            final int result = JOptionPane.showConfirmDialog(this, //
                    String.format("%s already exists.%nOverwrite?", f.getName()), "Override File?", //
                    JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (result == JOptionPane.YES_OPTION) {
                super.approveSelection();
            } else if (result == JOptionPane.CANCEL_OPTION) {
                cancelSelection();
            }
        } else {
            savePrefs();
            super.approveSelection();
        }
    }

    @Override
    public void cancelSelection() {
        savePrefs();
        super.cancelSelection();
    }

    private void attachToolbar() {
        toggleHiddenFilesButton = new JToggleButton(new HiddenFilesIcon());
        toggleHiddenFilesButton.setToolTipText("Toggle visibility of hidden files");
        toggleHiddenFilesButton.addItemListener(e -> setFileHidingEnabled(!toggleHiddenFilesButton.isSelected()));
        final JButton rescanButton = new JButton(new ReloadFilesIcon());
        rescanButton.setToolTipText("Refresh contents");
        rescanButton.addActionListener(e -> rescanCurrentDirectory());
        toggleFilterPatternButton = new JToggleButton(new FilterFilesIcon(), filterPattern != null && filterPattern.valid());
        toggleFilterPatternButton.setToolTipText("Filter current file list");
        toggleFilterPatternButton.addActionListener(e -> applyFilterPattern()); // cannot be ItemListener!
        final JButton revealButton = new JButton(new RevealFilesIcon());
        revealButton.setToolTipText("Show current directory in native file explorer");
        revealButton.addActionListener(e -> {
            final File f = (getSelectedFile() == null || isMultiSelectionEnabled()) ? getCurrentDirectory() : getSelectedFile();
            try {
                reveal(f);
            } catch (final Exception ex) {
                fileNotAccessibleError(f);
                LOGGER.log(Level.WARNING, "Failed to reveal file: " + f, e);
            }
        });
        final JButton recentLocationsButton = new JButton(new RecentLocationsIcon());
        recentLocationsButton.setToolTipText("Recent locations");
        recentLocationsButton.addActionListener(e -> showRecentLocationsMenu(recentLocationsButton));
        mod(toggleHiddenFilesButton, rescanButton, toggleFilterPatternButton, revealButton, recentLocationsButton);
        final JToolBar toolBar = new JToolBar(JToolBar.VERTICAL);
        toolBar.setFloatable(false);
        toolBar.setBorderPainted(false);
        toolBar.addSeparator();
        toolBar.add(toggleHiddenFilesButton);
        toolBar.add(toggleFilterPatternButton);
        toolBar.addSeparator();
        toolBar.add(rescanButton);
        toolBar.add(revealButton);
        toolBar.addSeparator();
        toolBar.add(recentLocationsButton);
        setAccessory(toolBar);
    }

    private void mod(final AbstractButton... buttons) {
        for (final AbstractButton b : buttons) { // as per FlatFileChooser.* icons
            b.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
            b.setFocusable(false);
        }
    }

    private void applyFilterPattern() {
        final String result = (String) JOptionPane.showInputDialog(this, //
                "List only directories and filenames containing:", "Filter by Pattern", JOptionPane.PLAIN_MESSAGE, //
                null, null, (filterPattern == null) ? "" : filterPattern.pattern);
        if (result != null && !result.isEmpty()) {
            filterPattern = new FileNamePatternFilter(result);
            addChoosableFileFilter(filterPattern);
            setFileFilter(filterPattern);
        } else {
            filterPattern = null;
            setFileFilter((isAcceptAllFileFilterUsed()) ? getAcceptAllFileFilter() : getFirstNonPatternFileFilter());
        }
    }

    private FileFilter getFirstNonPatternFileFilter() {
        return Arrays.stream(getChoosableFileFilters()).filter(ff -> !(ff instanceof FileNamePatternFilter)).findFirst().orElse(null);
    }

    @Override
    public void setFileFilter(final FileFilter filter) {
        if (toggleFilterPatternButton != null)
            toggleFilterPatternButton.setSelected(filter instanceof FileNamePatternFilter);
        super.setFileFilter(filter);
    }

    /**
     * Displays an error message dialog.
     *
     * @param msg the error message to display.
     */
    public void error(final String msg) {
        JOptionPane.showMessageDialog(this, msg, "Error", JOptionPane.ERROR_MESSAGE);
    }

    private void fileNotAccessibleError(final File dir) {
        if (dir == null) error("Directory does not seem to be accessible.");
        else error("Could not access\n" + dir.getAbsolutePath());
    }

    /**
     * Reveals the specified file in the native file explorer.
     *
     * @param file the file to be revealed. If the file is not a directory, its parent directory is used
     * @throws SecurityException if a security manager exists and its checkRead method denies read access to the file.
     * @throws UnsupportedOperationException if the current platform does not support desktop browsing.
     * @throws IllegalArgumentException if file is null or does not exist.
     */
    public static void reveal(final File file) throws SecurityException, UnsupportedOperationException, IllegalArgumentException {
        if (file == null) {
            throw new IllegalArgumentException("File is null");
        }
        final File dir = (file.isDirectory()) ? file : file.getParentFile();
        try {
            Desktop.getDesktop().browseFileDirectory(file);
        } catch (final UnsupportedOperationException ue) {
            if (SystemInfo.isLinux) try {
                Runtime.getRuntime().exec(new String[]{"xdg-open", dir.getAbsolutePath()});
            } catch (final Exception ignored) {
                throw ue;
            }
        }
    }

    private static class FileNamePatternFilter extends FileFilter {
        final String pattern;

        FileNamePatternFilter(final String pattern) {
            this.pattern = pattern;
        }

        boolean valid() {
            return pattern != null && !pattern.isEmpty();
        }

        @Override
        public boolean accept(final File f) {
            return f.getName().contains(pattern);
        }

        @Override
        public String getDescription() {
            return "Files and folders containing '" + pattern + "'";
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            return Objects.equals(pattern, ((FileNamePatternFilter) obj).pattern);
        }

        @Override
        public int hashCode() {
            return Objects.hash(pattern);
        }
    }

    /* Icon definitions, mostly duplicated from FlatLaf */
    private static class BaseIcon extends FlatAbstractIcon {
        Area area;

        public BaseIcon() {
            super(16, 16, UIManager.getColor("Actions.Grey")); // see FlatFileChooser.* icons
        }

        void prepGraphics(final Graphics2D g) {
            // from FlatFileViewComputerIcon
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setStroke(new BasicStroke(1, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        }

        @Override
        protected void paintIcon(final Component c, final Graphics2D g) {
            // do nothing by default
        }
    }

    private static class RevealFilesIcon extends BaseIcon {

        @Override
        protected void paintIcon(final Component c, final Graphics2D g) {
            prepGraphics(g);
            // from FlatFileViewComputerIcon
            g.draw(new RoundRectangle2D.Float(2.5f, 3.5f, 11, 7, 2, 2));
            g.drawLine(8, 11, 8, 12);
            g.draw(new Line2D.Float(4.5f, 12.5f, 11.5f, 12.5f));
        }
    }

    private static class HiddenFilesIcon extends BaseIcon {
        @Override
        protected void paintIcon(final Component c, final Graphics2D g) {
            prepGraphics(g);
            if (area == null) {
                // converted from FlatLaf /demo/icons/show.svg (16px viewBox)
                final Path2D path = new Path2D.Double();
                path.moveTo(8, 3);
                path.curveTo(4.81818182, 3, 2.10090909, 5.07333333, 1, 8);
                path.curveTo(2.10090909, 10.9266667, 4.81818182, 13, 8, 13);
                path.curveTo(11.1818182, 13, 13.8990909, 10.9266667, 15, 8);
                path.curveTo(13.8990909, 5.07333333, 11.1818182, 3, 8, 3);
                path.closePath();
                path.moveTo(8, 11.5);
                path.curveTo(6.068, 11.5, 4.5, 9.932, 4.5, 8);
                path.curveTo(4.5, 6.068, 6.068, 4.5, 8, 4.5);
                path.curveTo(9.932, 4.5, 11.5, 6.068, 11.5, 8);
                path.curveTo(11.5, 9.932, 9.932, 11.5, 8, 11.5);
                path.closePath();
                path.moveTo(8, 6);
                path.curveTo(6.89333333, 6, 6, 6.89333333, 6, 8);
                path.curveTo(6, 9.10666667, 6.89333333, 10, 8, 10);
                path.curveTo(9.10666667, 10, 10, 9.10666667, 10, 8);
                path.curveTo(10, 6.89333333, 9.10666667, 6, 8, 6);
                path.closePath();
                area = new Area(path);
            }
            g.fill(area);
        }
    }

    private static class RecentLocationsIcon extends BaseIcon {

        @Override
        protected void paintIcon(final Component c, final Graphics2D g) {
            prepGraphics(g);
            if (area == null) {
                area = new Area(new Ellipse2D.Float(0, 0, 16, 16));
                area.subtract(new Area(new Ellipse2D.Float(1, 1, 14, 14)));
                final Path2D clockHand = new Path2D.Double();
                clockHand.moveTo(8.72, 8.487);
                clockHand.lineTo(11.568, 9.981);
                clockHand.lineTo(10.96, 11.448);
                clockHand.lineTo(7.28, 9.513);
                clockHand.lineTo(7.28, 4);
                clockHand.lineTo(8.72, 4);
                clockHand.closePath();
                area.add(new Area(clockHand));
            }
            g.fill(area);

        }

    }

    private static class FilterFilesIcon extends BaseIcon {

        @Override
        protected void paintIcon(final Component c, final Graphics2D g) {
            prepGraphics(g);
            if (area == null) { // from FlatSearchIcon
                final Path2D path = new Path2D.Double();
                path.moveTo(1.10651019, 2.626245);
                path.curveTo(1.28700007, 2.243388, 1.66985738, 2, 2.0937351, 2);
                path.lineTo(13.907618, 2);
                path.curveTo(14.331496, 2, 14.714353, 2.243388, 14.894843, 2.626245);
                path.curveTo(15.075333, 3.009102, 15.020639, 3.460327, 14.752639, 3.788491);
                path.lineTo(9.7508815, 9.900534);
                path.lineTo(9.7508815, 13.376332);
                path.curveTo(9.7508815, 13.70723, 9.5649222, 14.010781, 9.2668404, 14.158455);
                path.curveTo(8.9687587, 14.306128, 8.616983, 14.276046, 8.3517176, 14.076415);
                path.lineTo(6.6005127, 12.76376);
                path.curveTo(6.3790024, 12.599678, 6.2504717, 12.339882, 6.2504717, 12.063678);
                path.lineTo(6.2504717, 9.900534);
                path.lineTo(1.24597964, 3.785756);
                path.curveTo(0.98071421, 3.460327, 0.92328562, 3.006368, 1.10651019, 3.626245);
                path.closePath();
                area = new Area(path);
            }
            g.draw(area);//g.fill(area);
        }
    }

    private static class ReloadFilesIcon extends BaseIcon {

        @Override
        protected void paintIcon(final Component c, final Graphics2D g) {
            prepGraphics(g);
            // converted from FlatLaf /demo/icons/refresh.svg (16px viewBox) scaled to 14px
            if (area == null) {
                final Path2D p = new Path2D.Float();
                p.moveTo(10.820312, 10.605469);
                p.curveTo(9.796875, 11.6875, 8.328125, 12.332031, 6.722656, 12.246094);
                p.curveTo(4.136719, 12.109375, 2.085938, 10.125, 1.785156, 7.640625);
                p.lineTo(3.410156, 7.78125);
                p.curveTo(3.75, 9.363281, 5.117188, 10.582031, 6.808594, 10.671875);
                p.curveTo(7.945312, 10.730469, 8.988281, 10.265625, 9.703125, 9.488281);
                p.lineTo(7.886719, 7.667969);
                p.lineTo(12.261719, 7.667969);
                p.lineTo(12.261719, 12.042969);
                p.closePath();
                g.fill(p);
                p.moveTo(3.183594, 3.398438);
                p.curveTo(4.203125, 2.320312, 5.671875, 1.675781, 7.273438, 1.761719);
                p.curveTo(9.824219, 1.894531, 11.855469, 3.828125, 12.199219, 6.265625);
                p.lineTo(10.570312, 6.125);
                p.curveTo(10.195312, 4.589844, 8.851562, 3.417969, 7.191406, 3.328125);
                p.curveTo(6.054688, 3.269531, 5.011719, 3.734375, 4.296875, 4.511719);
                p.lineTo(6.121094, 6.335938);
                p.lineTo(1.746094, 6.335938);
                p.lineTo(1.746094, 1.960938);
                p.lineTo(3.183594, 3.398438);
                p.closePath();
                area = new Area(p);
            }
            g.fill(area);
        }
    }

}
