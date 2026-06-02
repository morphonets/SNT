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
import ij.measure.Calibration;
import ij.process.ImageStatistics;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import sc.fiji.snt.*;
import sc.fiji.snt.SNT.CostType;
import sc.fiji.snt.tracing.TracerThread;
import sc.fiji.snt.tracing.cost.*;
import sc.fiji.snt.tracing.heuristic.Euclidean;
import sc.fiji.snt.tracing.heuristic.Heuristic;
import sc.fiji.snt.util.ImgUtils;
import sc.fiji.snt.util.PointInCanvas;
import sc.fiji.snt.util.PointInImage;

import java.awt.*;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
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

    /**
     * Per-CostType overlay color. Index follows {@code CostType.values()}.
     */
    private static final Color[] COLORS = {
            new Color(0, 255, 255), // RECIPROCAL   : cyan
            new Color(255, 200, 0),   // DIFFERENCE   : amber
            new Color(255, 0, 200), // DIFF_SQUARED : magenta
            new Color(0, 255, 0),  // PROBABILITY  : green
    };

    /**
     * Result of one A* probe run
     */
    private record Probe(CostType type, Path path, double meanCost, long elapsedMs, String error) {
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
    private ImagePlus paletteImage;
    private WizardWindow paletteWindow;
    private PaletteOptions paletteOptions;
    private MultiPathTracerCanvas canvas;
    private Window parent;
    private CostType selectedType;

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

    @Override
    public void run() {
        try {
            computeBounds();
            runProbes();
            buildAndDisplay();
        } catch (final Throwable t) {
            t.printStackTrace();
            SNTUtils.log("Cost-function wizard failed: " + t.getMessage());
        }
    }

    private void computeBounds() {
        final Calibration cal = image.getCalibration();
        final int sx = (int) Math.round(startWorld.x / cal.pixelWidth);
        final int sy = (int) Math.round(startWorld.y / cal.pixelHeight);
        final int sz = (int) Math.round(startWorld.z / Math.max(1e-9, cal.pixelDepth));
        final int ex = (int) Math.round(endWorld.x / cal.pixelWidth);
        final int ey = (int) Math.round(endWorld.y / cal.pixelHeight);
        final int ez = (int) Math.round(endWorld.z / Math.max(1e-9, cal.pixelDepth));
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

    // Single A* probe with a given cost function without altering the plugin's active state
    private Probe probeOnce(final CostType type) {
        final long t0 = System.currentTimeMillis();
        try {
            final boolean useSecondary = snt.isTracingOnSecondaryImageActive();
            @SuppressWarnings({"rawtypes"}) final RandomAccessibleInterval img =
                    useSecondary ? snt.getSecondaryData() : snt.getLoadedData();
            final int x_start = voxelX(startWorld), y_start = voxelY(startWorld), z_start = voxelZ(startWorld);
            final int x_end = voxelX(endWorld), y_end = voxelY(endWorld), z_end = voxelZ(endWorld);
            final ImageStatistics stats = new ImageStatistics();
            @SuppressWarnings({"unchecked", "rawtypes"}) final RandomAccessibleInterval sub = ImgUtils.subInterval(img,
                    new net.imglib2.Point(x_start, y_start, z_start),
                    new net.imglib2.Point(x_end, y_end, z_end), 10);
            snt.computeImgStats(sub, stats, type);

            final Cost cost = costFor(type, stats);
            final Heuristic heur = new Euclidean(snt.getCalibration());
            @SuppressWarnings({"unchecked"}) final TracerThread tracer = new TracerThread(snt, img,
                    x_start, y_start, z_start, x_end, y_end, z_end, cost, heur);
            tracer.run(); // synchronous on this worker thread
            final Path result = tracer.getResult();
            if (result == null) {
                return new Probe(type, null, Double.NaN,
                        System.currentTimeMillis() - t0, "no path found");
            }
            return new Probe(type, result, sampleMeanCost(result, img, cost),
                    System.currentTimeMillis() - t0, null);
        } catch (final Throwable ex) {
            return new Probe(type, null, Double.NaN,
                    System.currentTimeMillis() - t0,
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private static Cost costFor(final CostType type, final ImageStatistics st) {
        return switch (type) {
            case RECIPROCAL -> new Reciprocal(st.min, st.max);
            case DIFFERENCE -> new Difference(st.min, st.max);
            case DIFFERENCE_SQUARED -> new DifferenceSq(st.min, st.max);
            case PROBABILITY -> new OneMinusErf(st.max, st.mean, st.stdDev);
        };
    }

    @SuppressWarnings({"rawtypes"})
    private double sampleMeanCost(final Path p, final RandomAccessibleInterval img, final Cost cost) {
        if (p.size() == 0) return Double.NaN;
        final net.imglib2.RandomAccess ra = img.randomAccess();
        double sum = 0;
        int n = 0;
        for (int i = 0; i < p.size(); i++) {
            ra.setPosition(new long[]{p.getXUnscaled(i), p.getYUnscaled(i), p.getZUnscaled(i)});
            final double v = ((RealType<?>) ra.get()).getRealDouble();
            sum += cost.costMovingTo(v);
            n++;
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    private int voxelX(final PointInImage p) {
        return (int) Math.round(p.x / image.getCalibration().pixelWidth);
    }

    private int voxelY(final PointInImage p) {
        return (int) Math.round(p.y / image.getCalibration().pixelHeight);
    }

    private int voxelZ(final PointInImage p) {
        return (int) Math.round(p.z / Math.max(1e-9, image.getCalibration().pixelDepth));
    }

    private void buildAndDisplay() {
        // Crop the source image to the probe bounding box
        final Roi existingRoi = image.getRoi();
        image.setRoi(x_min, y_min, croppedWidth, croppedHeight);
        paletteImage = image.crop((z_min + 1) + "-" + (z_max + 1));
        image.setRoi(existingRoi);
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
        // Reset the canvas offset we installed for the cropped preview so the path's coordinates are back in
        // source-image space before the listener/PathAndFillManager consumes it
        chosen.path.setCanvasOffset(new PointInCanvas(0, 0, 0));
        listeners.forEach(l -> l.costFunctionPicked(chosen.type, chosen.path));
        dismiss();
    }

    public void dismiss() {
        new ArrayList<>(listeners).forEach(Listener::dismissed);
        if (paletteWindow != null) paletteWindow.close();
        if (paletteImage != null) paletteImage.flush();
        listeners.clear();
        if (snt.getUI() != null) snt.getUI().changeState(SNTUI.READY); // Runs on EDT
    }

    private static int clamp(final int v, final int lo, final int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // Window: cropped image (StackWindow) + AWT control panel
    private class WizardWindow extends StackWindow {
        private static final long serialVersionUID = 1L;
        // All controls below are AWT, NOT Swing. ImageJ's StackWindow extends java.awt.Frame!
        // Parenting Swing JComponents directly under it produces erratic repainting on resize, components
        // disappearing under the heavyweight ImageCanvas, and z-order glitches (same w/ SigmaPalette)
        private final Choice combo;
        private final Label statsLabel;

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
                        selectedType = t;
                        break;
                    }
                }
                // Recolor AFTER selectedType has been updated
                combo.setForeground(getSelectionColor(types, chosen));
                updateStats();
                highlightSelected();
            });
            statsLabel = new Label(" ");

            final Panel row1 = new Panel(new FlowLayout(FlowLayout.LEFT));
            row1.add(new Label("Choose:"));
            row1.add(combo);
            row1.add(statsLabel);
            c.gridy = 0;
            root.add(row1, c);
            c.gridy = 1;
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
            new GuiUtils(this).tempMsg(
                    "Scroll the stack to inspect each variant; pick from the dropdown then Apply.", 4000);
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
            return GuiUtils.getDisabledComponentColor();
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
                statsLabel.setText(String.format(" Mean cost: %.3f · %dms · %d nodes       ",
                        p.meanCost, p.elapsedMs, p.path.size()));
            }
        }

        @Override
        public String createSubtitle() {
            return (probes.length > 0) ? String.format("Preview · %d functions evaluated", probes.length) : "Cost Function Preview:";
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
                    + "<li>The status text shows the mean cost along each variant's path (lower = better fit) and its "
                    + "compute time (lower = faster).</li>"
                    + "<li>Click <i>Apply Choice</i> to adopt the selected cost function; if the preview is being "
                    + "generated from a Path Manager selection, the wizard will also replace the preview path with the "
                    + "chosen variant.</li>"
                    + "</ul>"
                    + "<p>Use the <i>More &raquo;</i> menu for LUT, MIP overlay, and other options.</p>";
        }
    }
}
