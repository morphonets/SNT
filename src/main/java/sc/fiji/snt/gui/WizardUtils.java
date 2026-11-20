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

package sc.fiji.snt.gui;

import ij.measure.Calibration;
import ij.process.ImageStatistics;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.converter.Converter;
import net.imglib2.converter.Converters;
import net.imglib2.loops.LoopBuilder;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNT.CostType;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.analysis.CandidatePathMetrics.Metric;
import sc.fiji.snt.gui.cmds.PathFitterCmd;
import sc.fiji.snt.tracing.TracerThread;
import sc.fiji.snt.tracing.cost.*;
import sc.fiji.snt.tracing.heuristic.Euclidean;
import sc.fiji.snt.util.ImgUtils;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

/**
 * Helpers shared by the preview wizards ({@link CostPalette}, {@link SigmaPalette}): running a single A* probe between
 * two points and reporting its footprint, and choosing the sampling radius of the path metrics.
 *
 * @author Tiago Ferreira
 */
final class WizardUtils {

    private WizardUtils() {}

    /**
     * Grid coordinate of a world coordinate along one axis: {@code world / spacing + offset}
     *
     * @param world   the world (calibrated) coordinate
     * @param spacing the voxel size
     * @param offset  the offset, in voxels, between the world origin and the grid of the image
     * @return the (fractional) voxel coordinate
     */
    static double gridCoord(final double world, final double spacing, final double offset) {
        return world / Math.max(1e-9, spacing) + offset;
    }

    /**
     * Places a wizard window next to its parent or, if there is none, over SNT's image window
     *
     * @param window the window
     * @param parent the parent window. May be null
     * @param snt    the SNT instance
     */
    static void place(final Window window, final Window parent, final SNT snt) {
        if (parent != null)
            window.setLocationRelativeTo(parent);
        else
            GuiUtils.Windows.center(window, (snt.getImagePlus() == null) ? null : snt.getImagePlus().getWindow());
    }

    /**
     * Crops an image to a box, leaving its ROI as it was
     *
     * @param image  the image
     * @param x      left edge (pixels)
     * @param y      top edge (pixels)
     * @param width  width (pixels)
     * @param height height (pixels)
     * @param zFirst first slice (1-based)
     * @param zLast  last slice (1-based)
     * @return the cropped image
     */
    static ij.ImagePlus cropImp(final ij.ImagePlus image, final int x, final int y, final int width,
                                final int height, final int zFirst, final int zLast) {
        final ij.gui.Roi existingRoi = image.getRoi();
        image.setRoi(x, y, width, height);
        final ij.ImagePlus cropped = image.crop(zFirst + "-" + zLast);
        image.setRoi(existingRoi);
        return cropped;
    }

    /**
     * Message for data that lies outside of the image data currently loaded
     *
     * @param snt     the SNT instance
     * @param subject the subject and verb, e.g., "The training paths lie"
     * @param detail  details to be appended after the complement (e.g., " (12% of nodes)"). May be empty
     * @return the message
     */
    static String outsideDataMessage(final SNT snt, final String subject, final String detail) {
        return subject + " outside the image data currently loaded" + detail
                + ((snt.isStreamMode()) ? " (is a crop active?)." : ".");
    }

    /**
     * Table model of the preview tables of the wizards: a label column, the time column and one column per metric
     *
     * @param firstColumn name of the label column
     * @param units       the spatial units (for the header of the metrics)
     * @param ms          the metrics
     * @param rows        number of rows
     * @param label       the label of a row
     * @param failed      whether a row has no result ("n/a" is shown for all but the label)
     * @param time        the time column of a row
     * @param metric      the text of a metric (row, index of the metric) of a row that did not fail
     * @return the model
     */
    static AbstractTableModel metricsModel(final String firstColumn, final String units, final Metric[] ms,
                                           final int rows, final java.util.function.IntFunction<String> label,
                                           final java.util.function.IntPredicate failed,
                                           final java.util.function.IntFunction<String> time,
                                           final java.util.function.BiFunction<Integer, Integer, String> metric) {
        return new AbstractTableModel() {
            private static final long serialVersionUID = 1L;

            @Override
            public int getRowCount() {
                return rows;
            }

            @Override
            public int getColumnCount() {
                return ms.length + 2; // label, time, metrics
            }

            @Override
            public String getColumnName(final int col) {
                if (col == 0) return firstColumn;
                if (col == 1) return "Time (ms)";
                return ms[col - 2].columnName(units);
            }

            @Override
            public Object getValueAt(final int row, final int col) {
                if (col == 0) return label.apply(row);
                if (failed.test(row)) return "n/a";
                if (col == 1) return time.apply(row);
                return metric.apply(row, col - 2);
            }
        };
    }

    /**
     * Outcome of a single A* run
     *
     * @param path      the path (null if the search failed)
     * @param elapsedMs the search time
     * @param explored  points considered by the search (open + closed)
     * @param allocated bytes allocated by the search thread (-1 if unavailable)
     * @param error     the failure reason, or null
     */
    record TraceResult(Path path, long elapsedMs, long explored, long allocated, String error) {
        boolean ok() {
            return path != null && error == null;
        }
    }

    static Cost costFor(final CostType type, final ImageStatistics st) {
        return switch (type) {
            case RECIPROCAL -> new Reciprocal(st.min, st.max);
            case DIFFERENCE -> new Difference(st.min, st.max);
            case DIFFERENCE_SQUARED -> new DifferenceSq(st.min, st.max);
            case PROBABILITY -> new OneMinusErf(st.max, st.mean, st.stdDev);
            case PROBABILITY_FLOOR -> new OneMinusErfFloor(st.max, st.mean, st.stdDev);
        };
    }

    /**
     * Runs a bidirectional A* search on in-memory data, synchronously on the calling thread. Independent of SNT's
     * own image data, other than for the statistics and search image type
     *
     * @param snt            the SNT instance
     * @param img            the data to search (e.g., a filtered crop)
     * @param cal            the voxel calibration of img. Nodes of the returned path are {@code voxelIndex * spacing}
     * @param type           the cost function
     * @param start          start voxel (x, y, z) in img's grid
     * @param end            end voxel (x, y, z) in img's grid
     * @param timeoutSeconds search timeout (0 for none)
     * @return the outcome
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static TraceResult trace(final SNT snt, final RandomAccessibleInterval img, final Calibration cal,
                             final CostType type, final int[] start, final int[] end, final int timeoutSeconds) {
        final long t0 = System.currentTimeMillis();
        final long mem0 = allocatedBytes();
        try {
            final ImageStatistics stats = new ImageStatistics();
            final RandomAccessibleInterval sub = ImgUtils.subInterval(img,
                    new net.imglib2.Point(start[0], start[1], start[2]),
                    new net.imglib2.Point(end[0], end[1], end[2]), 10);
            snt.computeImgStats(sub, stats, type);
            final TracerThread tracer = new TracerThread(img, cal, start[0], start[1], start[2], end[0], end[1],
                    end[2], timeoutSeconds, 1000L, snt.getSearchImageType(), costFor(type, stats),
                    new Euclidean(cal));
            tracer.run();
            final Path result = tracer.getResult();
            final long elapsed = System.currentTimeMillis() - t0;
            final long mem1 = allocatedBytes();
            final long allocated = (mem0 < 0 || mem1 < 0) ? -1 : mem1 - mem0;
            return new TraceResult(result, elapsed, tracer.pointsConsideredInSearch(), allocated,
                    (result == null) ? "no path found" : null);
        } catch (final Throwable ex) {
            return new TraceResult(null, System.currentTimeMillis() - t0, 0, -1,
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    /**
     * @return bytes allocated so far by the calling thread (HotSpot only), or -1 if unsupported. Searches run
     * synchronously on the calling thread, so the difference across a search approximates its memory footprint
     */
    static long allocatedBytes() {
        try {
            if (java.lang.management.ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean tmx
                    && tmx.isThreadAllocatedMemorySupported()) {
                return tmx.getThreadAllocatedBytes(Thread.currentThread().threadId());
            }
        } catch (final Throwable ignored) {
            // not HotSpot or not permitted
        }
        return -1;
    }

    /**
     * Shows the busy cursor on SNT's dialog and image window (where the user is most likely looking) while a wizard
     * prepares its window. Safe to call from any thread
     *
     * @param snt the SNT instance
     * @return a task that restores the cursors that were in place
     */
    static Runnable busyCursor(final SNT snt) {
        final java.util.List<java.awt.Component> targets = new java.util.ArrayList<>();
        if (snt.getUI() != null) targets.add(snt.getUI());
        final ij.ImagePlus imp = snt.getImagePlus();
        if (imp != null && imp.getWindow() != null) targets.add(imp.getWindow());
        final java.util.Map<java.awt.Component, java.awt.Cursor> previous = new java.util.LinkedHashMap<>(); // EDT only
        javax.swing.SwingUtilities.invokeLater(() -> {
            for (final java.awt.Component c : targets) {
                previous.put(c, c.getCursor());
                c.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
            }
        });
        return () -> javax.swing.SwingUtilities.invokeLater(() -> previous.forEach(java.awt.Component::setCursor));
    }

    /**
     * Shows a message in SNT's status bar (progress feedback for users that do not have the console open)
     *
     * @param snt the SNT instance
     * @param msg the message, or null to restore the default status
     */
    static void status(final SNT snt, final String msg) {
        if (snt.getUI() != null) snt.getUI().showStatus(msg, msg == null);
    }

    /**
     * Runs a wizard's preparation task with the busy cursor on, restoring cursors and status afterwards
     *
     * @param snt  the SNT instance
     * @param task the task
     */
    static void busy(final SNT snt, final Runnable task) {
        final Runnable restoreCursor = busyCursor(snt);
        try {
            task.run();
        } finally {
            status(snt, null);
            restoreCursor.run();
        }
    }

    /**
     * The tracer and the metrics work on 3D data: adds a singleton Z dimension to 2D data
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static RandomAccessibleInterval<?> as3D(final RandomAccessibleInterval<?> rai) {
        return (rai.numDimensions() == 2) ? Views.addDimension((RandomAccessibleInterval) rai, 0, 0) : rai;
    }

    /**
     * A metrics table and the scroll pane holding it
     */
    record MetricsTable(JTable table, JScrollPane pane) {
    }

    /**
     * Assembles the table of path metrics shared by the preview wizards. Columns are: name (the first), time, and one
     * for each {@link Metric}, in that order. The table is Swing inside an AWT window, which is safe because it sits in
     * its own row below the canvas. It is not focusable, so it never steals key events (zoom hotkeys) from the canvas
     *
     * @param model        the table model
     * @param nameRenderer the renderer of the first column
     * @param tableTip     tooltip of the table
     * @param nameTip      tooltip of the header of the first column
     * @param timeTip      tooltip of the header of the time column
     * @param hasReference whether the table has a reference row (see {@link Metric#tooltip(boolean)})
     * @param best         whether the value of a metric in a row is the best of its column
     * @param worse        whether the value of a metric in a row is worse than the reference (null for none)
     * @param onRowClick   called with the row and click count when a row is clicked (null for none)
     * @param visibleRows  the number of rows visible without scrolling
     * @return the table and its scroll pane
     */
    static MetricsTable metricsTable(final AbstractTableModel model, final TableCellRenderer nameRenderer,
                                     final String tableTip, final String nameTip, final String timeTip,
                                     final boolean hasReference, final BiPredicate<Integer, Metric> best,
                                     final BiPredicate<Integer, Metric> worse,
                                     final BiConsumer<Integer, Integer> onRowClick, final int visibleRows) {
        final Metric[] ms = Metric.values();
        final JTable table = new JTable(model);
        table.setFocusable(false);
        table.setRowSelectionAllowed(false);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setRowHeight(table.getFontMetrics(table.getFont()).getHeight() + 6);
        table.setToolTipText(tableTip);
        table.setTableHeader(new JTableHeader(table.getColumnModel()) {
            private static final long serialVersionUID = 1L;

            @Override
            public String getToolTipText(final MouseEvent e) {
                final int col = columnAtPoint(e.getPoint());
                if (col < 1) return nameTip;
                if (col == 1) return timeTip;
                return ms[col - 2].tooltip(hasReference);
            }
        });
        table.getTableHeader().setFocusable(false);
        table.getTableHeader().setReorderingAllowed(false);

        table.getColumnModel().getColumn(0).setCellRenderer(nameRenderer);
        // No highlight: faster is not better quality
        final DefaultTableCellRenderer timeRenderer = new DefaultTableCellRenderer();
        timeRenderer.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        table.getColumnModel().getColumn(1).setCellRenderer(timeRenderer);
        final GuiUtils.Tables.BestCellRenderer metricRenderer = new GuiUtils.Tables.BestCellRenderer(
                (row, col) -> col > 1 && best.test(row, ms[col - 2])) {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getTableCellRendererComponent(final JTable t, final Object value,
                                                           final boolean isSel, final boolean hasFocus,
                                                           final int row, final int col) {
                final Component comp = super.getTableCellRendererComponent(t, value, isSel, hasFocus, row, col);
                // NB: DefaultTableCellRenderer.setForeground() is sticky (it sets unselectedForeground), so the
                // default color must be restored explicitly on every call
                if (worse != null) comp.setForeground((col > 1 && worse.test(row, ms[col - 2])) ? WORSE_COLOR
                        : t.getForeground());
                return comp;
            }
        };
        for (int col = 2; col < model.getColumnCount(); col++)
            table.getColumnModel().getColumn(col).setCellRenderer(metricRenderer);
        if (onRowClick != null) {
            table.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(final MouseEvent e) {
                    final int row = table.rowAtPoint(e.getPoint());
                    if (row >= 0) onRowClick.accept(row, e.getClickCount());
                }
            });
        }
        // Snap every column to its content (header included), with a bit of padding for the bold 'best' cells
        for (int col = 0; col < table.getColumnCount(); col++)
            GuiUtils.Tables.autoSizeColumn(table, col, 6);
        final JScrollPane pane = new JScrollPane(table);
        pane.setFocusable(false);
        table.setPreferredScrollableViewportSize(
                new Dimension(table.getPreferredSize().width, table.getRowHeight() * visibleRows));
        return new MetricsTable(table, pane);
    }

    /**
     * Text color of table cells that are worse than the reference
     */
    static final Color WORSE_COLOR = GuiUtils.Colors.errorColor();

    static String formatBytes(final long bytes) {
        if (bytes < 0) return "n/a";
        if (bytes < 1024 * 1024) return String.format("%.0fKB", bytes / 1024d);
        return String.format("%.1fMB", bytes / (1024d * 1024d));
    }

    /**
     * @return the sampling radius (voxels) for path metrics: half of the maximum neurite radius the user is willing
     * to fit, read from the same preference PathFitter uses (default if the user never fitted paths)
     */
    static int metricsRadius() {
        int maxRadius = PathFitterCmd.DEFAULT_SEARCH_RADIUS_PIXELS;
        try {
            final String pref = SNTUtils.getContext().getService(org.scijava.prefs.PrefService.class)
                    .get(PathFitterCmd.class, PathFitterCmd.MAX_SEARCH_RADIUS_PX_KEY);
            if (pref != null) maxRadius = Integer.parseInt(pref);
        } catch (final RuntimeException ignored) {
            // use default
        }
        return Math.max(3, (int) Math.round(maxRadius / 2d));
    }

    /**
     * A region of an image read into memory
     *
     * @param data the pixel values (zero-min, float)
     * @param min  the position {x, y, z} of the first voxel of the region in the grid of the image it was read from
     */
    record Region(RandomAccessibleInterval<FloatType> data, long[] min) {
    }

    /**
     * Pads a box and clamps it to the bounds of an image
     *
     * @param bounds     {min, max} of the box (voxels, inclusive)
     * @param padVoxels  the padding {x, y, z} in voxels
     * @param image      the image the box refers to
     * @return {min, max} of the padded box
     */
    static long[][] padAndClamp(final long[][] bounds, final long[] padVoxels,
                                final RandomAccessibleInterval<?> image) {
        final long[] min = new long[3];
        final long[] max = new long[3];
        for (int d = 0; d < 3; d++) {
            min[d] = Math.max(image.min(d), bounds[0][d] - padVoxels[d]);
            max[d] = Math.min(image.max(d), bounds[1][d] + padVoxels[d]);
        }
        return new long[][]{min, max};
    }

    /**
     * Checks that a box is small enough to be read into memory
     *
     * @param bounds          {min, max} of the box (voxels, inclusive)
     * @param spacing         voxel size {x, y, z}, to report distances in calibrated units
     * @param unit            the spatial unit
     * @param maxVoxelsPerAxis the maximum extent along any axis
     * @param maxVoxels       the maximum number of voxels
     * @return a message describing the problem, or null if the box is acceptable
     */
    static String checkRegionSize(final long[][] bounds, final double[] spacing, final String unit,
                                  final long maxVoxelsPerAxis, final long maxVoxels) {
        long total = 1;
        for (int d = 0; d < 3; d++) {
            final long n = bounds[1][d] - bounds[0][d] + 1;
            total *= n;
            if (n > maxVoxelsPerAxis) {
                return String.format("The region is too large to be previewed (%s: %s %s, but the limit is %s %s). "
                                + "Select representative paths that span a smaller region.", "XYZ".charAt(d),
                        SNTUtils.formatDouble(n * spacing[d], 1), unit,
                        SNTUtils.formatDouble(maxVoxelsPerAxis * spacing[d], 1), unit);
            }
        }
        if (total > maxVoxels)
            return String.format("The region is too large to be previewed (%,d voxels, but the limit is %,d). "
                    + "Select representative paths that span a smaller region.", total, maxVoxels);
        return null;
    }

    /**
     * Copies a box of an image into memory as float. Reading is done by all threads, but may still take a while if
     * the image is lazy or remote: do not call this on the EDT
     *
     * @param image  the image (2D data is treated as a single plane)
     * @param bounds {min, max} of the box (voxels, inclusive) in the grid of the image. Must be within its bounds
     * @return the region
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Region readRegion(final RandomAccessibleInterval<?> image, final long[][] bounds) {
        final RandomAccessibleInterval view = Views.zeroMin(Views.interval((RandomAccessibleInterval) as3D(image),
                bounds[0], bounds[1]));
        final Converter<RealType, FloatType> toFloat = (in, out) -> out.setReal(in.getRealDouble());
        final RandomAccessibleInterval<FloatType> converted = Converters.convert(view, toFloat, new FloatType());
        final Img<FloatType> copy = new ArrayImgFactory<>(new FloatType()).create(converted);
        LoopBuilder.setImages(converted, copy).multiThreaded().forEachPixel((in, out) -> out.set(in));
        return new Region(copy, bounds[0].clone());
    }
}
