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

import ij.IJ;
import ij.ImagePlus;
import ij.gui.ImageCanvas;
import ij.gui.Roi;
import ij.gui.StackWindow;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.loops.LoopBuilder;
import net.imglib2.type.NativeType;
import net.imglib2.view.Views;
import org.jspecify.annotations.NonNull;
import sc.fiji.snt.*;
import sc.fiji.snt.analysis.CandidatePathMetrics;
import sc.fiji.snt.analysis.CandidatePathMetrics.Metric;
import sc.fiji.snt.SNT.CostType;
import sc.fiji.snt.util.PointInCanvas;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SNTColor;

import javax.swing.Icon;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * SNT "Cost Function Wizard": runs A* with each of the {@link CostType} variants between two endpoints and displays
 * all candidate paths as colored overlays on a single cropped {@link ImagePlus}. The user picks the preferred variant
 * from a drop-down; SNT then adopts that {@code CostType} and (when applicable) replaces the probe path with the
 * chosen variant.
 *
 * @author Tiago Ferreira
 */
public class CostPalette extends Thread {

    /**
     * Voxel-space padding added around the probe path's bounding box.
     */
    private static final int PROBE_PADDING = 10;
    // Max extent (voxels) of the probe segment along any axis in Stream mode. A* over lazy or remote data
    // between more distant endpoints reads a very large region
    private static final int MAX_STREAM_PROBE_SPAN = 500;
    // Below this separation (voxels) between endpoints, candidate paths are near-identical and metrics are noise
    private static final int MIN_METRICS_SPAN = 10;
    // Time budget for the metrics, computed in the background after the window is displayed
    private static final int METRICS_TIMEOUT_MS = 30000;

    /**
     * Per-CostType overlay color. Index follows {@code CostType.values()}.
     */
    private static final Color[] COLORS = SNTColor.okabeIto4Colors().toArray(new Color[0]);
    // OKABE_ITO_ORANGE_COLOR: RECIPROCAL
    // KABE_ITO_BLUE_COLOR: DIFFERENCE
    // OKABE_ITO_GREEN_COLOR: DIFF_SQUARED
    // OKABE_ITO_VERMILLION_COLOR: PROBABILITY

    /**
     * Result of one A* probe run
     */
    // explored: points considered by the search (open + closed). allocated: bytes allocated by the search thread
    // (-1 if unavailable)
    private record Probe(CostType type, Path path, Path local, long elapsedMs, long explored, long allocated, String error) {
        boolean ok() {
            return path != null && error == null;
        }
    }

    /**
     * Listener notified when the user picks (or cancels).
     */
    public interface Listener {
        /**
         * User picked a CostType; {@code chosenPath} is the corresponding A* result.
         */
        void costFunctionPicked(final CostType chosen, final Path chosenPath);

        /**
         * User dismissed without picking.
         */
        default void dismissed() {
        }
    }

    // Bounds derived from the probe endpoints (voxel coordinates)
    private int x_min;
    private int y_min;
    private int z_min;
    private int z_max;
    private int croppedWidth, croppedHeight, croppedDepth;

    private final SNT snt;
    private final ImagePlus image;
    private final PointInImage startWorld;
    private final PointInImage endWorld;
    private final List<Listener> listeners = new ArrayList<>();

    private final Probe[] probes = new Probe[CostType.values().length];
    // [probe index][Metric ordinal]. Only valid when metricsState == MetricsState.DONE
    private double[][] metrics;
    private enum MetricsState {COMPUTING, DONE, FAILED}
    private MetricsState metricsState = MetricsState.COMPUTING; // accessed on the EDT
    private String metricsSkipReason; // non-null if metrics are not computed at all
    private volatile java.util.concurrent.ExecutorService metricsExecutor;
    private volatile java.util.concurrent.Future<?> metricsTask;
    private volatile javax.swing.Timer metricsTimeout;
    private ImagePlus paletteImage;
    // In-memory copy of the region being searched (grid origin at the crop's corner). Reading it once, up front, spares
    // the searches and the metrics from hitting lazy/remote data, which is orders of magnitude slower
    private RandomAccessibleInterval<?> regionData;
    private WizardWindow paletteWindow;
    private PaletteOptions paletteOptions;
    private MultiPathTracerCanvas canvas;
    private Window parent;
    private CostType selectedType;
    // Search image type in effect when the probes were run (the live setting may change while the wizard is open)
    private SNT.SearchImageType searchImageType;

    public CostPalette(final SNT snt, final PointInImage startWorld, final PointInImage endWorld) {
        this.snt = snt;
        this.image = snt.getLoadedDataAsImp();
        this.startWorld = startWorld;
        this.endWorld = endWorld;
        setParent(snt.getUI());
    }

    public void setParent(final Window parent) {
        this.parent = parent;
    }

    public void addListener(final Listener l) {
        if (l != null) listeners.add(l);
    }

    public void show() {
        start();
    }

    /**
     * Checks if the probe segment can be used: both endpoints must lie within the data being traced
     * and, in Stream mode, be close enough to avoid reading a very large region of lazy/remote data.
     *
     * @return a message describing the problem, or null if the probe segment is usable
     */
    public String validateProbe() {
        final RandomAccessibleInterval<?> data = (snt.isTracingOnSecondaryImageActive())
                ? snt.getSecondaryData() : snt.getLoadedData();
        if (data == null) return "No valid image data loaded.";
        final long[] s = { voxelX(startWorld), voxelY(startWorld), voxelZ(startWorld) };
        final long[] e = { voxelX(endWorld), voxelY(endWorld), voxelZ(endWorld) };
        final int nDim = Math.min(3, data.numDimensions());
        for (int d = 0; d < nDim; d++) {
            if (s[d] < data.min(d) || s[d] > data.max(d) || e[d] < data.min(d) || e[d] > data.max(d)) {
                final PointInCanvas off = snt.getActiveCanvasPixelOffset();
                final String details = String.format("start=%s end=%s, loaded data=%s, offset=(%.1f,%.1f,%.1f), "
                                + "crop=%b", Arrays.toString(s), Arrays.toString(e),
                        Arrays.toString(data.minAsLongArray()) + "-" + Arrays.toString(data.maxAsLongArray()),
                        off.x, off.y, off.z, snt.isMaterializedCrop());
                SNTUtils.log("Cost-function wizard: probe out of bounds: " + details);
                return "The probe segment lies outside the image data currently loaded"
                        + ((snt.isStreamMode()) ? " (is a crop active?)." : ".") + "<br><small>" + details
                        + "</small>";
            }
            if (snt.isStreamMode() && Math.abs(s[d] - e[d]) > MAX_STREAM_PROBE_SPAN) {
                // Report in calibrated units: the limit is in voxels, so it differs per axis if spacing is anisotropic
                final double[] spacing = { snt.getPixelWidth(), snt.getPixelHeight(), snt.getPixelDepth() };
                final String units = snt.getSpacingUnits();
                return String.format("The probe segment is too long for streamed data (%s: %s %s between its "
                                + "endpoints, but the limit is %s %s). Select a shorter path over a "
                                + "representative segment.", "XYZ".charAt(d),
                        SNTUtils.formatDouble(Math.abs(s[d] - e[d]) * spacing[d], 1), units,
                        SNTUtils.formatDouble(MAX_STREAM_PROBE_SPAN * spacing[d], 1), units);
            }
        }
        return null;
    }

    @Override
    public void run() {
        WizardSupport.busy(snt, this::build);
    }

    private void build() {
        try {
            computeBounds();
            searchImageType = snt.getSearchImageType();
            WizardSupport.status(snt, "Cost wizard: reading region...");
            if (readRegion()) {
                WizardSupport.status(snt, "Cost wizard: tracing with " + CostType.values().length
                        + " cost functions...");
                runProbes();
                metricsSkipReason = shortSegmentReason();
                buildAndDisplay();
                if (paletteWindow != null && metricsSkipReason == null) startMetrics();
            }
            if (paletteWindow == null) { // image was not displayed
                if (snt.getUI()!=null) {
                    snt.getUI().error("The Cost Function Wizard could not be created. " +
                            "Make sure probe segment (last traced path or path currently selected in the Manager) " +
                            " is within image bounds.");
                } else {
                    SNTUtils.log("Cost-function wizard failed: probe segments out-of-bounds!?");
                }
            }
        } catch (final Throwable t) {
            SNTUtils.error(t);
            SNTUtils.log("Cost-function wizard failed: " + t.getMessage());
        }
    }

    // Reads the probe's bounding box into memory. Returns false if the region is unusable
    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean readRegion() {
        final Roi existingRoi = image.getRoi();
        image.setRoi(x_min, y_min, croppedWidth, croppedHeight);
        paletteImage = image.crop((z_min + 1) + "-" + (z_max + 1));
        image.setRoi(existingRoi);
        if (paletteImage == null || paletteImage.getWidth() < 2 || paletteImage.getHeight() < 2) return false;
        if (snt.isTracingOnSecondaryImageActive()) {
            // The wizard displays the primary image, but searches (and metrics) use the secondary one
            final RandomAccessibleInterval<?> data = snt.getSecondaryData();
            final long[] min = data.minAsLongArray();
            final long[] max = data.maxAsLongArray();
            min[0] = x_min;
            max[0] = x_min + croppedWidth - 1;
            min[1] = y_min;
            max[1] = y_min + croppedHeight - 1;
            if (min.length > 2) {
                min[2] = z_min;
                max[2] = z_min + croppedDepth - 1;
            }
            regionData = copyOf(Views.zeroMin(Views.interval((RandomAccessibleInterval) data, min, max)));
        } else {
            // A view of the pixels just read
            regionData = ImageJFunctions.wrapReal(paletteImage);
        }
        return true;
    }

    private static <T extends NativeType<T>> RandomAccessibleInterval<T> copyOf(final RandomAccessibleInterval<T> view) {
        final Img<T> copy = new ArrayImgFactory<>(view.getType()).create(view);
        LoopBuilder.setImages(view, copy).forEachPixel((a, b) -> b.set(a));
        return copy;
    }

    private void computeBounds() {
        final int sx = voxelX(startWorld);
        final int sy = voxelY(startWorld);
        final int sz = voxelZ(startWorld);
        final int ex = voxelX(endWorld);
        final int ey = voxelY(endWorld);
        final int ez = voxelZ(endWorld);
        x_min = clamp(Math.min(sx, ex) - PROBE_PADDING, 0, image.getWidth() - 1);
        int x_max = clamp(Math.max(sx, ex) + PROBE_PADDING, 0, image.getWidth() - 1);
        y_min = clamp(Math.min(sy, ey) - PROBE_PADDING, 0, image.getHeight() - 1);
        int y_max = clamp(Math.max(sy, ey) + PROBE_PADDING, 0, image.getHeight() - 1);
        z_min = clamp(Math.min(sz, ez) - 1, 0, image.getNSlices() - 1);
        z_max = clamp(Math.max(sz, ez) + 1, 0, image.getNSlices() - 1);
        croppedWidth = (x_max - x_min) + 1;
        croppedHeight = (y_max - y_min) + 1;
        croppedDepth = (z_max - z_min) + 1;
    }

    // 4 parallel A* probes (one per CostType)
    private void runProbes() {
        final CostType[] types = CostType.values();
        @SuppressWarnings("unchecked") final CompletableFuture<Probe>[] futures = new CompletableFuture[types.length];
        for (int i = 0; i < types.length; i++) {
            final CostType type = types[i];
            futures[i] = CompletableFuture.supplyAsync(() -> probeOnce(type));
        }
        try {
            CompletableFuture.allOf(futures).get();
            for (int i = 0; i < futures.length; i++) probes[i] = futures[i].get();
        } catch (final InterruptedException | ExecutionException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Cost-function probes interrupted: " + ex.getMessage(), ex);
        }
    }

    private String shortSegmentReason() {
        final int span = Math.max(Math.abs(voxelX(startWorld) - voxelX(endWorld)), Math.max(
                Math.abs(voxelY(startWorld) - voxelY(endWorld)), Math.abs(voxelZ(startWorld) - voxelZ(endWorld))));
        return (span < MIN_METRICS_SPAN) ? "Segment too short for metrics (endpoints are less than "
                + MIN_METRICS_SPAN + " voxels apart)" : null;
    }

    // Metrics sample the source data around the candidate paths, which can be slow (remote data) or long (very long
    // paths). Compute them in the background so the wizard window is not delayed, and give up after a time budget.
    // Paths' node coordinates are read, never modified, until the user commits (which cancels the task first)
    private void startMetrics() {
        final Path[] paths = new Path[probes.length];
        // The local paths match the grid of the in-memory region
        for (int i = 0; i < probes.length; i++) paths[i] = (probes[i].ok()) ? probes[i].local : null;
        final RandomAccessibleInterval<?> img = WizardSupport.as3D(regionData);
        final int radius = WizardSupport.metricsRadius();
        metricsExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, "SNT-CostPalette-metrics");
            t.setDaemon(true);
            return t;
        });
        metricsTask = metricsExecutor.submit(() -> {
            try {
                final double[][] result = CandidatePathMetrics.compute(paths, img, radius,
                        () -> Thread.currentThread().isInterrupted());
                javax.swing.SwingUtilities.invokeLater(() -> finishMetrics(result));
            } catch (final java.util.concurrent.CancellationException ignored) {
                // dismissed or timed out: nothing to do
            } catch (final Throwable t) {
                SNTUtils.log("Cost-function wizard: metrics could not be computed: " + t.getMessage());
                javax.swing.SwingUtilities.invokeLater(() -> finishMetrics(null));
            }
        });
        metricsTimeout = new javax.swing.Timer(METRICS_TIMEOUT_MS, e -> {
            if (metricsState == MetricsState.COMPUTING) {
                SNTUtils.log("Cost-function wizard: metrics timed out");
                cancelMetrics();
                finishMetrics(null);
            }
        });
        metricsTimeout.setRepeats(false);
        metricsTimeout.start();
    }

    // EDT. A null result means failure/timeout. Ignored once the outcome is settled
    private void finishMetrics(final double[][] result) {
        if (metricsState != MetricsState.COMPUTING) return;
        metrics = result;
        metricsState = (result == null) ? MetricsState.FAILED : MetricsState.DONE;
        if (metricsTimeout != null) metricsTimeout.stop();
        if (paletteWindow != null) {
            paletteWindow.refreshMetrics();
            if (result == null) new GuiUtils(paletteWindow).tempMsg("Metrics unavailable (timed out or failed)", 4000);
        }
    }

    private void cancelMetrics() {
        if (metricsTimeout != null) metricsTimeout.stop();
        if (metricsTask != null) metricsTask.cancel(true);
        if (metricsExecutor != null) metricsExecutor.shutdownNow();
    }

    // Single A* probe with a given cost function on the in-memory region, without altering the plugin's active state
    private Probe probeOnce(final CostType type) {
        final int[] start = {voxelX(startWorld) - x_min, voxelY(startWorld) - y_min, voxelZ(startWorld) - z_min};
        final int[] end = {voxelX(endWorld) - x_min, voxelY(endWorld) - y_min, voxelZ(endWorld) - z_min};
        final WizardSupport.TraceResult r = WizardSupport.trace(snt, WizardSupport.as3D(regionData),
                snt.getCalibration(), type, start, end, 0);
        return new Probe(type, r.ok() ? toDataGrid(r.path()) : null, r.path(), r.elapsedMs(), r.explored(),
                r.allocated(), r.error());
    }

    // Translates a path found in the region's grid to the grid of the data (what the search of a whole image yields)
    private Path toDataGrid(final Path local) {
        final double dx = x_min * snt.getPixelWidth();
        final double dy = y_min * snt.getPixelHeight();
        final double dz = z_min * snt.getPixelDepth();
        final Path path = new Path(snt.getPixelWidth(), snt.getPixelHeight(), snt.getPixelDepth(),
                snt.getSpacingUnits());
        for (int i = 0; i < local.size(); i++) {
            final PointInImage node = local.getNode(i);
            path.addPointDouble(node.x + dx, node.y + dy, node.z + dz);
        }
        return path;
    }

    // NB: uses SNT's own spacing, as SNT#createSearch(double...) does, not image's calibration: in Stream
    // mode image is a bare wrap of the loaded data and may not carry the true voxel size
    // p is a true-world coordinate (Path node convention); image (snt.getLoadedDataAsImp()) wraps
    // snt.getLoadedData()/ctSlice3d directly, so its own pixel grid is whatever
    // snt.getActiveCanvasPixelOffset() currently corrects for (crop-local when a crop is
    // materialized, the raw streamed source's own voxel grid otherwise), same conversion as
    // SNT#createSearch(double...)/testPathTo()
    private int voxelX(final PointInImage p) {
        return (int) Math.round(p.x / snt.getPixelWidth() + snt.getActiveCanvasPixelOffset().x);
    }

    private int voxelY(final PointInImage p) {
        return (int) Math.round(p.y / snt.getPixelHeight() + snt.getActiveCanvasPixelOffset().y);
    }

    private int voxelZ(final PointInImage p) {
        return (int) Math.round(p.z / Math.max(1e-9, snt.getPixelDepth())
                + snt.getActiveCanvasPixelOffset().z);
    }

    private void buildAndDisplay() {
        // paletteImage: the source image cropped to the probe bounding box (see readRegion())
        paletteImage.setTitle("Cost Function Sel. Wizard");

        // Shared LUT/MIP/snapshot/dismiss for the wizard window's "More »" button
        paletteOptions = new PaletteOptions(paletteImage, image, "CostFunction_Snapshot", this::dismiss);

        // Shift each probe path into the cropped image's coordinate space by setting a canvasOffset matching the crop
        // origin. Path.drawPathAsPoints adds the canvasOffset when computing display coordinates, so the overlay aligns
        // with the cropped image we hand to the wizard window.
        for (final Probe p : probes) {
            if (p.ok()) p.path.setCanvasOffset(new PointInCanvas(-x_min, -y_min, -z_min));
        }

        // Initial slice: middle of the probe Z range
        final int initialZ = clamp((voxelZ(startWorld) + voxelZ(endWorld)) / 2 - z_min, 0, croppedDepth - 1);
        paletteImage.setZ(initialZ + 1);

        // Default selection: SNT's currently-active cost function (so users immediately see which one is in use),
        // falling back to the first successful probe if SNT's current type didn't produce a path.
        selectedType = defaultSelection();

        // Draw  each probe path via Path#drawPathAsPoints
        canvas = new MultiPathTracerCanvas(paletteImage, snt, snt.getPathAndFillManager());
        syncEitherSideFromSourceCanvas();
        refreshCanvasEntries();
        paletteWindow = new WizardWindow(paletteImage, canvas);
    }

    /**
     * Re-reads {@code isJustNearSlices() / getEitherSide()} from SNT's main XY canvas and applies the depth-band radius
     * to the wizard canvas. Called during build and whenever the wizard window regains focus, so users can tweak
     * near-slice rendering in SNTUI and have the wizard pick it up automatically.
     */
    private void syncEitherSideFromSourceCanvas() {
        if (canvas == null) return;
        final sc.fiji.snt.TracerCanvas srcCanvas = snt.getCanvas(SNT.XY_PLANE);
        if (srcCanvas != null && srcCanvas.isJustNearSlices()) {
            canvas.setEitherSide(srcCanvas.getEitherSide());
        } else {
            // User disabled near-slice rendering on the main canvas; revert to MultiPathTracerCanvas's
            // "precise perusing" default
            canvas.setEitherSide(1);
        }
        if (paletteImage != null && paletteImage.getCanvas() != null) {
            paletteImage.getCanvas().repaint();
        }
    }

    /**
     * Pushes the current per-CostType paths + selection state to the canvas. Called during build and whenever the
     * dropdown selection changes so the newly-selected variant becomes the highlighted entry.
     */
    private void refreshCanvasEntries() {
        if (canvas == null) return;
        canvas.clearEntries();
        final CostType[] types = CostType.values();
        for (int i = 0; i < types.length; i++) {
            final Probe p = probes[i];
            if (!p.ok()) continue;
            canvas.addEntry(p.path, COLORS[i], types[i] == selectedType);
        }
        if (paletteImage != null && paletteImage.getCanvas() != null) {
            paletteImage.getCanvas().repaint();
        }
    }

    private CostType firstSuccessful() {
        for (final Probe p : probes) if (p != null && p.ok()) return p.type;
        return null;
    }

    /**
     * Prefer SNT's currently-active cost function so users immediately see which one is in use; if its probe failed
     * (no path), fall back to the first successful probe.
     */
    private CostType defaultSelection() {
        final CostType current = snt.getCostType();
        if (current != null) {
            final int idx = indexOf(current);
            if (idx >= 0 && probes[idx] != null && probes[idx].ok()) return current;
        }
        return firstSuccessful();
    }

    private int indexOf(final CostType t) {
        if (t == null) return -1;
        final CostType[] values = CostType.values();
        for (int i = 0; i < values.length; i++) if (values[i] == t) return i;
        return -1;
    }

    /**
     * Rebuilds the canvas's entry list so the newly-selected variant is the highlighted one.
     */
    private void highlightSelected() {
        refreshCanvasEntries();
    }

    private void commit() {
        if (selectedType == null) {
            new GuiUtils(paletteWindow).error("No cost function selected.");
            return;
        }
        final Probe chosen = probes[indexOf(selectedType)];
        if (chosen == null || !chosen.ok()) {
            new GuiUtils(paletteWindow).error("The selected variant did not produce a path.");
            return;
        }
        // chosen.path's nodes are raw TracerThread/asPath() output: voxelIndex * spacing, where voxelIndex
        // was computed (via voxelX/Y/Z above) against this session's live activeCanvasPixelOffset at probe
        // time - not true world coordinates. Resetting canvasOffset to a hardcoded (0,0,0), only works if
        // no materialized crop and no world origin offset - see SNT#getWorldOriginOffset(), in Stream mode
        // Bake the FULL live offset out of the node coordinates once, now, turning / them into true world
        // (same idea as GWDTTracerCommonCmd#applyWorldOriginOffsetIfAny(List<Tree>), but done in a single
        // step here since this path has no interim/in-progress state to worry about), then  stamp
        // canvasOffset from that same live offset so the path still samples/renders correctly
        cancelMetrics(); // metrics read the path's nodes, which are about to be translated
        final PointInCanvas liveOffset = snt.getActiveCanvasPixelOffset();
        if (liveOffset.x != 0 || liveOffset.y != 0 || liveOffset.z != 0) {
            final ij.measure.Calibration pathCal = chosen.path.getCalibration();
            new Tree(List.of(chosen.path)).translate(-liveOffset.x * pathCal.pixelWidth,
                    -liveOffset.y * pathCal.pixelHeight, -liveOffset.z * pathCal.pixelDepth);
        }
        chosen.path.setCanvasOffset(liveOffset);
        listeners.forEach(l -> l.costFunctionPicked(chosen.type, chosen.path));
        dismiss();
    }

    public void dismiss() {
        cancelMetrics();
        new ArrayList<>(listeners).forEach(Listener::dismissed);
        if (paletteWindow != null) paletteWindow.close();
        if (paletteImage != null) paletteImage.flush();
        regionData = null; // release (metrics were cancelled above)
        listeners.clear();
        if (snt.getUI() != null) snt.getUI().changeState(SNTUI.READY); // Runs on EDT
    }

    private static int clamp(final int v, final int lo, final int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // Small filled square matching a candidate's overlay color
    private record Swatch(Color color) implements Icon {
        @Override
        public void paintIcon(final Component c, final Graphics g, final int x, final int y) {
            g.setColor(color);
            g.fillRect(x, y, getIconWidth(), getIconHeight());
        }

        @Override
        public int getIconWidth() {
            return 10;
        }

        @Override
        public int getIconHeight() {
            return 10;
        }
    }

    // Window: cropped image (StackWindow) + AWT control panel
    private class WizardWindow extends StackWindow {
        private static final long serialVersionUID = 1L;
        // All controls below are AWT, NOT Swing. ImageJ's StackWindow extends java.awt.Frame!
        // Parenting Swing JComponents directly under it produces erratic repainting on resize, components
        // disappearing under the heavyweight ImageCanvas, and z-order glitches (same w/ SigmaPalette)
        private final Choice combo;
        private final Label statsLabel;
        private JTable metricsTable; // null if metrics are not available

        WizardWindow(final ImagePlus imp, final ImageCanvas ic) {
            super(imp, ic);
            // Match the source SNT canvas zoom so the probe reads at the user's familiar scale, making sure the wizard
            // window doesn't render very small (when zoomed out on a huge stack) or very large (when zoomed-in on a
            // small image). HiDPI scale factor is the same as SigmaPalette's behavior: This is AWT not Swing
            final ImagePlus src = snt.getImagePlus();
            if (src != null && src.getCanvas() != null) {
                final double srcMag = src.getCanvas().getMagnification(); // from 1/72.0 to 32.0
                final double mag = Math.clamp(srcMag, 1.0, 8.0) * GuiUtils.uiScale();
                final ImageCanvas thisIc = getCanvas();
                thisIc.setMagnification(mag);
                thisIc.setSize((int) Math.round(imp.getWidth() * mag), (int) Math.round(imp.getHeight() * mag));
            } else {
                // Stream mode: no source canvas to match
                GuiUtils.zoomToScreenFraction(getCanvas(), 0.55, 8);
            }

            final Panel root = new Panel(new GridBagLayout());
            final GridBagConstraints c = new GridBagConstraints();
            c.fill = GridBagConstraints.HORIZONTAL;
            c.weightx = 1.0;
            c.gridx = 0;
            c.gridwidth = GridBagConstraints.REMAINDER;
            //c.insets = new Insets(2, 6, 2, 6);

            final CostType[] types = CostType.values();
            combo = new Choice();
            combo.setFocusable(false); // keyboard shortcuts (zoom, etc.) only work when image canvas has focus
            for (final CostType t : types)
                combo.add(t.toString());
            if (selectedType != null) {
                final String sType = selectedType.toString();
                combo.setForeground(getSelectionColor(types, sType));
                combo.select(sType);
            }
            combo.addItemListener(e -> {
                final String chosen = combo.getSelectedItem();
                for (final CostType t : types) {
                    if (t.toString().equals(chosen)) {
                        applySelection(t);
                        break;
                    }
                }
            });
            statsLabel = new Label(" ");

            final Panel row1 = new Panel(new FlowLayout(FlowLayout.LEFT));
            row1.add(new Label("ƒ(x):"));
            row1.add(combo);
            row1.add(statsLabel);
            c.gridy = 0;
            root.add(row1, c);
            c.gridy = 1;
            if (metricsSkipReason != null) {
                root.add(new Label(metricsSkipReason), c);
            } else {
                root.add(assembleMetricsTable(types), c);
            }
            c.gridy = 2;
            root.add(assembleButtonPanel(), c);

            add(root);
            pack();
            updateStats();
            highlightSelected();
            requestFocusInWindow(); // required to trigger keylistener events
            if (parent != null)
                setLocationRelativeTo(parent);
            else
                GuiUtils.centerWindow(this, (snt.getImagePlus() == null) ? null : snt.getImagePlus().getWindow());
            new GuiUtils(this.getCanvas()).tempMsg(
                    "Scroll the stack to inspect each variant; pick from the dropdown then Apply.", 4000);
        }

        // Single place through which the selected cost function changes (drop-down or table double-click)
        private void applySelection(final CostType t) {
            selectedType = t;
            final String name = t.toString();
            combo.select(name);
            // Recolor AFTER selectedType has been updated
            combo.setForeground(getSelectionColor(CostType.values(), name));
            updateStats();
            highlightSelected();
            if (metricsTable != null) metricsTable.repaint();
            getCanvas().requestFocusInWindow(); // so that keyboard shortcuts (zoom, etc.) keep working
        }

        private void refreshMetrics() {
            if (metricsTable != null) metricsTable.repaint();
        }

        private JScrollPane assembleMetricsTable(final CostType[] types) {
            final var nameRenderer = new DefaultTableCellRenderer() {
                private static final long serialVersionUID = 1L;

                @Override
                public Component getTableCellRendererComponent(final JTable t, final Object value,
                                                               final boolean isSel, final boolean hasFocus,
                                                               final int row, final int col) {
                    super.getTableCellRendererComponent(t, value, false, false, row, col);
                    setIcon(new Swatch(COLORS[row]));
                    final boolean current = types[row] == selectedType;
                    setFont(current ? t.getFont().deriveFont(Font.BOLD) : t.getFont());
                    setHorizontalAlignment(SwingConstants.LEFT);
                    return this;
                }
            };
            final String selectTip = "Double-click a row to select that cost function";
            final WizardSupport.MetricsTable mt = WizardSupport.metricsTable(getTableModel(types, Metric.values()),
                    nameRenderer, selectTip, selectTip,
                    "<html><div width=300>Compute time of each search. Variants are computed in parallel, so timings "
                            + "are indicative only. Speed is not a measure of path quality",
                    false,
                    (row, m) -> probes[row].ok() && metricsState == MetricsState.DONE
                            && CandidatePathMetrics.isBest(metrics, m, row),
                    null,
                    (row, clicks) -> {
                        if (clicks == 2 && row < types.length) applySelection(types[row]);
                    },
                    types.length); // one row per cost function, so that the table never scrolls
            metricsTable = mt.table();
            return mt.pane();
        }

        private Panel assembleButtonPanel() {
            final Panel buttonPanel = new Panel(new FlowLayout(FlowLayout.LEFT));
            final Button applyButton = new Button("Apply Choice");
            applyButton.setFocusable(false);
            applyButton.addActionListener(e -> commit());
            final Button bcButton = new Button("B&C");
            bcButton.setFocusable(false);
            bcButton.addActionListener(e -> IJ.doCommand("Brightness/Contrast..."));
            final Button more = paletteOptions.buildMoreButton(this, helpHtml(), null);
            more.setFocusable(false);
            buttonPanel.add(applyButton);
            buttonPanel.add(bcButton);
            buttonPanel.add(more);
            return buttonPanel;
        }

        private Color getSelectionColor(final CostType[] types, final String comboSelection) {
            for (int i = 0; i < types.length; i++) {
                final CostType t = types[i];
                if (t.toString().equals(comboSelection)) {
                    return COLORS[i];
                }
            }
            return GuiUtils.Colors.disabledComponentColor();
        }

        private void updateStats() {
            final int idx = indexOf(selectedType);
            if (idx < 0) {
                statsLabel.setText(" ");
                return;
            }
            final Probe p = probes[idx];
            if (!p.ok()) {
                statsLabel.setText("  Failed: " + p.error);
            } else {
                final int n = p.path.size();
                statsLabel.setText(String.format(
                        " %dms (%.2fms/node) | %d nodes | %,d points explored | %s allocated         ",
                        p.elapsedMs, p.elapsedMs / (double) Math.max(1, n), n, p.explored,
                        WizardSupport.formatBytes(p.allocated)));
            }
        }

        @Override
        public String createSubtitle() {
            return (probes.length > 0)
                    ? String.format("Preview · %d functions evaluated, %s data structure", probes.length, searchImageType)
                    : String.format("Cost Function Preview (%s data structure)", searchImageType);
        }

        @Override
        public void windowActivated(final WindowEvent e) {
            // Re-pull near-slice settings from the user's main XY canvas whenever the wizard window regains focus.
            // Lets users toggle depth-band rendering in SNTUI and have the wizard pick it up automatically
            super.windowActivated(e);
            syncEitherSideFromSourceCanvas();
        }

        @Override
        public void windowClosing(final WindowEvent e) {
            dismiss();
            super.windowClosing(e);
        }

        private String helpHtml() {
            return "<HTML><div WIDTH=500><p>"
                    + "The <a href=\"https://imagej.net/plugins/snt/manual#algorithm-settings\">Cost Function Wizard</a> "
                    + "runs a path search with each of the  " + CostType.values().length + " "
                    + "available cost functions and overlays the resulting paths in distinct colours on the cropped "
                    + "source image.</p>"
                    + "<p><b>How to choose:</b></p>"
                    + "<ul>"
                    + "<li>Scroll Z to inspect how each variant traces through depth</li>"
                    + "<li>Pick the variant whose path best follows the underlying signal from the <i>Choose</i> "
                    + "drop-down: The selected variant's path is highlighted</li>"
                    + "<li>The status text shows the compute time of the selected variant (lower = faster), its path "
                    + "length in nodes, how many points the search had to explore (lower = more directed search) and "
                    + "the memory allocated by the search, followed by the search image type in use (see SNT's "
                    + "options). Timings are indicative only: variants are computed in parallel</li>"
                    + "<li>The table lists quality metrics for each path. The best value in each column is "
                    + "highlighted; hover over a column header for details. The metrics are descriptive, not a "
                    + "score: intensity-based metrics inherently favor intensity-based cost functions. "
                    + "Double-click a row to select that cost function</li>"
                    + "<li>Click <i>Apply Choice</i> to adopt the selected cost function; if the preview is being "
                    + "generated from a Path Manager selection, the wizard will also replace the preview path with the "
                    + "chosen variant.</li>"
                    + "</ul>"
                    + "<p>Use the <i>More &raquo;</i> menu for LUT, MIP overlay, and other options.</p>";
        }
    }

    private @NonNull AbstractTableModel getTableModel(CostType[] types, Metric[] ms) {
        final String units = snt.getSpacingUnits();
        final AbstractTableModel model = new AbstractTableModel() {
            private static final long serialVersionUID = 1L;

            @Override
            public int getRowCount() {
                return types.length;
            }

            @Override
            public int getColumnCount() {
                return ms.length + 2; // name, time, metrics
            }

            @Override
            public String getColumnName(final int col) {
                if (col == 0) return "Cost function";
                if (col == 1) return "Time (ms)";
                final Metric m = ms[col - 2];
                return m.columnName(units);
            }

            @Override
            public Object getValueAt(final int row, final int col) {
                if (col == 0) return types[row].toString();
                if (!probes[row].ok()) return "n/a";
                if (col == 1) return String.valueOf(probes[row].elapsedMs); // available immediately
                if (metricsState == MetricsState.FAILED) return "n/a";
                if (metricsState == MetricsState.COMPUTING) return "...";
                return ms[col - 2].format(metrics[row][col - 2]);
            }
        };
        return model;
    }
}
