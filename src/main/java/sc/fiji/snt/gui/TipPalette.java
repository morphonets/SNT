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
import ij.gui.GUI;
import ij.gui.ImageCanvas;
import ij.gui.StackWindow;
import ij.measure.Calibration;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.loops.LoopBuilder;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTUI;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.filter.Tubeness;
import sc.fiji.snt.tracing.auto.AutoTraceConfig;
import sc.fiji.snt.tracing.auto.TipDetectionConfig;
import sc.fiji.snt.tracing.auto.TipDetectionConfig.TipStatus;
import sc.fiji.snt.tracing.auto.TipExamples;
import sc.fiji.snt.tracing.auto.TipFinder;
import sc.fiji.snt.util.ImgUtils;
import sc.fiji.snt.util.PointInCanvas;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.Scrollable;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SNT "Tip Detection Wizard": learns the parameters of {@link TipFinder} from example paths (see
 * {@link TipDetectionConfig}) and previews the result on the region spanned by the paths. The preview is independent
 * of the image loaded in SNT's canvas (it reads the data directly from the source, so it is unaffected by crops), and
 * is read into memory: the detection runs live when the sliders are adjusted.
 *
 * @author Tiago Ferreira
 */
public class TipPalette extends Thread {

    /** Receives the outcome of the wizard */
    public interface Listener {
        /**
         * Called when the user accepts the parameters
         *
         * @param config      the (possibly adjusted) parameters
         * @param wholeVolume true to detect in the whole volume, false to detect in the region currently
         *                    loaded (a dry run)
         */
        void tipConfigPicked(TipDetectionConfig config, boolean wholeVolume);

        /** Called when the wizard is closed */
        default void dismissed() {
        }
    }

    // Limits of the region that is read into memory
    private static final long MAX_VOXELS_PER_AXIS = 700;
    private static final long MAX_VOXELS = 80_000_000L;
    private static final int MIN_PAD_VOXELS = 8;
    private static final int SLIDER_STEPS = 1000;
    private static final double WEIGHT_MAX = 2;
    // Slices (either side of the displayed one) in which overlays are drawn
    private static final int OVERLAY_BAND = 2;
    private static final Color NODE_COLOR = new Color(0, 200, 255, 160);
    private static final Color MATCHED_COLOR = new Color(0, 220, 0);
    private static final Color MISSED_COLOR = new Color(255, 60, 60);
    private static final Color SKIPPED_COLOR = Color.GRAY;
    private static final Color DETECTION_COLOR = new Color(250, 215, 100);
    private static final Color SELECTED_COLOR = Color.ORANGE;

    private final SNT snt;
    private final List<Path> paths;
    private final List<Listener> listeners = new ArrayList<>();
    private Window parent;

    private double[] spacing;
    private String unit;
    private double[] voxelOffset;
    private WizardSupport.Region region;
    private TipDetectionConfig.Example example;
    private TipDetectionConfig config;
    private RandomAccessibleInterval<FloatType> ridge;
    private volatile TipDetectionConfig.Evaluation evaluation;
    private volatile int selected = -1;
    private String failure;
    private int bgChannel = 0; // 1-based. 0: none
    private double bgWeight = 1;
    private RandomAccessibleInterval<FloatType> vetoRegion; // Raw background of the region, if any
    private double vetoSuggested = Double.NaN; // Learned veto threshold. The veto is off until the user enables it
    private double fixedPad = Double.NaN; // calibrated padding of the first read, reused when the background changes

    private ImagePlus paletteImage;
    private PaletteOptions paletteOptions;
    private TipWindow window;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "TipPalette-evaluation");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger generation = new AtomicInteger();

    /**
     * @param snt   the SNT instance (in Stream mode, or with a loaded image)
     * @param paths the example paths. Should be traced towards the tips of the structures
     */
    public TipPalette(final SNT snt, final List<Path> paths) {
        this.snt = snt;
        this.paths = new ArrayList<>(paths);
        setParent(snt.getUI());
    }

    public void setParent(final Window parent) {
        this.parent = parent;
    }

    public void addListener(final Listener l) {
        if (l != null) listeners.add(l);
    }

    /**
     * Checks that the example paths can be used
     *
     * @return a message describing the problem, or null if the paths are usable
     */
    public String validate() {
        if (snt.getFullSourceData() == null) return "No valid image data loaded.";
        if (paths.isEmpty()) return "No example paths: trace, or select in the Path Manager, a few representative "
                + "axons (traced towards their tips), and re-launch the wizard.";
        // Unset (non-positive) channel/frame values match anything
        final java.util.Set<String> ct = new java.util.TreeSet<>();
        for (final Path p : paths) {
            if (p.getChannel() > 0 && p.getFrame() > 0)
                ct.add("channel " + p.getChannel() + ", frame " + p.getFrame());
        }
        if (ct.size() > 1) return "The example paths belong to different channels/frames (" + String.join("; ", ct)
                + "). Select paths from a single channel and frame.";
        for (final Path p : paths) {
            if ((p.getChannel() > 0 && p.getChannel() != snt.getChannel())
                    || (p.getFrame() > 0 && p.getFrame() != snt.getFrame()))
                return "The example paths were traced in channel " + p.getChannel() + ", frame " + p.getFrame()
                        + " but SNT is showing channel " + snt.getChannel() + ", frame " + snt.getFrame()
                        + ". Switch to the paths' channel/frame and re-launch the wizard.";
        }
        // All nodes must lie within the image: padding would otherwise hide paths that are outside
        final double[] sp = {snt.getPixelWidth(), snt.getPixelHeight(), snt.getPixelDepth()};
        final PointInCanvas off = snt.getDefaultCanvasPixelOffset();
        final double inside = AutoTraceConfig.fractionInBounds(paths, snt.getFullSourceData(), sp,
                new double[]{off.x, off.y, off.z});
        if (!(inside >= 1d))
            return "Some example paths lie outside the image data currently loaded ("
                    + Math.round((1 - inside) * 100) + "% of nodes)"
                    + ((snt.isStreamMode()) ? " (is a crop active?)." : ".") + " Select paths within the image.";
        return null;
    }

    public void show() {
        start();
    }

    @Override
    public void run() {
        WizardSupport.busy(snt, this::build);
    }

    private void build() {
        try {
            if (!learn()) {
                final String msg = (failure != null) ? failure : "The Tip Detection Wizard could not be created.";
                if (snt.getUI() != null) snt.getUI().error(msg);
                SNTUtils.log("Tip wizard failed: " + msg);
                dismiss();
                return;
            }
            WizardSupport.status(snt, "Tip wizard: preparing preview...");
            buildAndDisplay();
        } catch (final Throwable t) {
            SNTUtils.error(t);
            SNTUtils.log("Tip wizard failed: " + t.getMessage());
            dismiss();
        }
    }

    // Reads the region spanned by the examples, learns from them and evaluates the result. False on failure
    private boolean learn() {
        final RandomAccessibleInterval<?> source = WizardSupport.as3D(snt.getFullSourceData());
        spacing = new double[]{snt.getPixelWidth(), snt.getPixelHeight(), snt.getPixelDepth()};
        unit = snt.getSpacingUnits();
        final PointInCanvas offset = snt.getDefaultCanvasPixelOffset();
        voxelOffset = new double[]{offset.x, offset.y, offset.z};

        final long[][] bounds = TipExamples.voxelBounds(paths, spacing, voxelOffset);
        if (bounds == null) {
            failure = "The example paths have no nodes.";
            return false;
        }
        final double inside = AutoTraceConfig.fractionInBounds(paths, source, spacing, voxelOffset);
        if (!(inside > 0.9)) {
            failure = "The example paths lie outside the image data currently loaded"
                    + ((snt.isStreamMode()) ? " (is a crop active?)." : ".");
            return false;
        }

        // The region is padded by the analysis radius, as tips closer than that to its border cannot be evaluated.
        // The radius is only known once the examples have been analyzed: if the first guess is too small, read again
        double padCalibrated = Double.isNaN(fixedPad) ? estimatePadding() : fixedPad;
        for (int attempt = 0; attempt < 2; attempt++) {
            final long[] pad = new long[3];
            for (int d = 0; d < 3; d++) pad[d] = (long) Math.ceil(padCalibrated / spacing[d]) + MIN_PAD_VOXELS;
            final long[][] padded = WizardSupport.padAndClamp(bounds, pad, source);
            failure = WizardSupport.checkRegionSize(padded, spacing, unit, MAX_VOXELS_PER_AXIS, MAX_VOXELS);
            if (failure != null) return false;
            WizardSupport.status(snt, "Tip wizard: reading region...");
            region = WizardSupport.readRegion(source, padded);
            if (!subtractBackground(region, padded)) return false;

            // The region is the same when the background changes: the examples (radii, nodes, tips) are kept, so that
            // the scales do not vary from one update to the next
            if (example == null || Double.isNaN(fixedPad) || attempt > 0) {
                final double[] regionOffset = new double[3];
                for (int d = 0; d < 3; d++) regionOffset[d] = voxelOffset[d] - region.min()[d];
                WizardSupport.status(snt, "Tip wizard: analyzing example paths...");
                example = TipExamples.fromPaths(paths, region.data(), spacing, regionOffset);
            }
            WizardSupport.status(snt, "Tip wizard: computing score maps...");
            config = TipDetectionConfig.learn(example, spacing, TipExamples.scales(example.radii()),
                    this::scoreMap, vetoRegion);
            if (config.getTipRadius() > padCalibrated && attempt == 0 && Double.isNaN(fixedPad)) {
                padCalibrated = config.getTipRadius();
                continue;
            }
            break;
        }
        fixedPad = padCalibrated;
        SNTUtils.log(String.format("Tip wizard: background channel %d (weight %.2f), scales %s, threshold %s",
                bgChannel, bgWeight, java.util.Arrays.toString(config.getScales()), config.getThreshold()));
        config.setBackground(bgChannel, bgWeight);
        vetoSuggested = config.getVetoThreshold();
        config.setVetoThreshold(Double.NaN); // Off
        ridge = scoreMap(config.getScales());
        WizardSupport.status(snt, "Tip wizard: detecting tips...");
        evaluation = config.evaluate(ridge, example, vetoRegion);
        return true;
    }

    // Subtracts the weighted background channel from the signal (in place), clamping to zero
    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean subtractBackground(final WizardSupport.Region signal, final long[][] box) {
        vetoRegion = null;
        if (bgChannel <= 0) return true;
        final RandomAccessibleInterval<?> bgSource = snt.getFullSourceData(bgChannel);
        if (bgSource == null) {
            failure = "Channel " + bgChannel + " could not be read (does it have the same dimensions as the "
                    + "active channel?).";
            return false;
        }
        WizardSupport.status(snt, "Tip wizard: reading background...");
        final WizardSupport.Region bg = WizardSupport.readRegion(bgSource, box);
        vetoRegion = bg.data(); // Raw: read before subtracting
        final float w = (float) bgWeight;
        LoopBuilder.setImages(signal.data(), bg.data()).multiThreaded()
                .forEachPixel((s, b) -> s.set(Math.max(0f, s.get() - w * b.get())));
        return true;
    }

    // Re-learns after a change of the background and updates the window in place
    private void changeBackground(final int channel, final double weight) {
        worker.execute(() -> {
            generation.incrementAndGet(); // Cancels pending evaluations
            final double previousVeto = config.getVetoThreshold();
            final int previousChannel = bgChannel;
            bgChannel = channel;
            bgWeight = weight;
            WizardSupport.busy(snt, () -> {
                if (!learn()) {
                    final String msg = (failure != null) ? failure : "Tip detection could not be updated.";
                    if (snt.getUI() != null) snt.getUI().error(msg);
                    failure = null;
                    bgChannel = 0;
                    learn(); // Back to the signal alone
                }
                // Same background: the veto stays as the user set it
                if (!Double.isNaN(previousVeto) && vetoRegion != null && bgChannel == previousChannel) {
                    config.setVetoThreshold(previousVeto);
                    evaluation = config.evaluate(ridge, example, vetoRegion);
                }
            });
            selected = -1;
            EventQueue.invokeLater(() -> {
                if (window == null) return;
                // Same dimensions as before: the window and its canvas are kept
                paletteImage.setStack(ImgUtils.raiToImp(region.data(), "Tip Detection Wizard").getStack());
                paletteImage.resetDisplayRange();
                paletteImage.updateAndDraw();
                window.reload();
            });
        });
    }

    // 6x the largest radius of the paths that have one, or a few voxels otherwise
    private double estimatePadding() {
        double maxRadius = 0;
        for (final Path p : paths) {
            if (!p.hasRadii()) continue;
            for (int i = 0; i < p.size(); i++) maxRadius = Math.max(maxRadius, p.getNode(i).getRadius());
        }
        final double avgSpacing = (spacing[0] + spacing[1] + spacing[2]) / 3;
        return (maxRadius > 0) ? 6 * maxRadius : 10 * avgSpacing;
    }

    private RandomAccessibleInterval<FloatType> scoreMap(final double[] scales) {
        final RandomAccessibleInterval<FloatType> data = region.data();
        if (data.dimension(2) == 1) { // 2D data, held as a one-slice volume
            final RandomAccessibleInterval<FloatType> slice = Views.zeroMin(Views.hyperSlice(data, 2, data.min(2)));
            final Img<FloatType> out2D = new ArrayImgFactory<>(new FloatType()).create(slice);
            new Tubeness<FloatType, FloatType>(scales, new double[]{spacing[0], spacing[1]}).compute(slice, out2D);
            return Views.addDimension(out2D, 0, 0);
        }
        final Img<FloatType> out = new ArrayImgFactory<>(new FloatType()).create(data);
        new Tubeness<FloatType, FloatType>(scales, spacing).compute(data, out);
        return out;
    }

    private void buildAndDisplay() {
        paletteImage = ImgUtils.raiToImp(region.data(), "Tip Detection Wizard");
        final Calibration cal = paletteImage.getCalibration();
        cal.pixelWidth = spacing[0];
        cal.pixelHeight = spacing[1];
        cal.pixelDepth = spacing[2];
        cal.setUnit(unit);
        paletteImage.resetDisplayRange();
        paletteOptions = new PaletteOptions(paletteImage, null, "TipDetection_Snapshot", this::dismiss);
        // Middle of the example tips (or of the region) along Z
        paletteImage.setZ(Math.max(1, Math.min(paletteImage.getNSlices(), (int) (paletteImage.getNSlices() / 2d))));
        window = new TipWindow(paletteImage, new TipCanvas(paletteImage));
    }

    // Re-runs the detection with the current parameters (latest request wins)
    private void scheduleEvaluation() {
        final int id = generation.incrementAndGet();
        worker.submit(() -> {
            if (id != generation.get()) return;
            try {
                final TipDetectionConfig.Evaluation ev = config.evaluate(ridge, example, vetoRegion);
                if (id != generation.get()) return;
                evaluation = ev;
                selected = -1;
                EventQueue.invokeLater(() -> {
                    if (window != null) window.refresh();
                });
            } catch (final RuntimeException ex) {
                SNTUtils.log("Tip wizard: detection failed: " + ex.getMessage());
            }
        });
    }

    private void commit(final boolean wholeVolume) {
        final List<Listener> copy = new ArrayList<>(listeners);
        if (wholeVolume) {
            dismiss(); // Clears the listeners, so use a copy
        }
        copy.forEach(l -> l.tipConfigPicked(config, wholeVolume));
    }

    public void dismiss() {
        generation.incrementAndGet();
        worker.shutdownNow();
        new ArrayList<>(listeners).forEach(Listener::dismissed);
        if (window != null) window.close();
        if (paletteImage != null) paletteImage.flush();
        region = null;
        ridge = null;
        listeners.clear();
        if (snt.getUI() != null) snt.getUI().changeState(SNTUI.READY); // Runs on EDT
    }

    private static String fmt(final double v) {
        return Double.isNaN(v) ? "n/a" : String.format("%.3g", v);
    }

    // Value in calibrated units, with its size in voxels
    private String withVoxels(final double calibrated) {
        if (Double.isNaN(calibrated)) return "n/a";
        final double avgSpacing = (spacing[0] + spacing[1] + spacing[2]) / 3;
        return String.format("%.3g %s (%.1f vox)", calibrated, unit, calibrated / avgSpacing);
    }

    // Canvas: draws the examples, the detections, and the analysis sphere of the selected detection
    private class TipCanvas extends ImageCanvas {
        private static final long serialVersionUID = 1L;

        TipCanvas(final ImagePlus imp) {
            super(imp);
            disablePopupMenu(true);
            setShowCursorStatus(false);
            setCursor(new Cursor(Cursor.HAND_CURSOR));
        }

        @Override
        public void paint(final Graphics g) {
            super.paint(g);
            final TipDetectionConfig.Evaluation ev = evaluation;
            if (config == null || ev == null) return;
            final Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            final int z = imp.getSlice() - 1;
            g2.setColor(NODE_COLOR);
            for (final long[] n : example.nodes()) {
                if (Math.abs(n[2] - z) <= 1) fillDot(g2, n[0], n[1], 2);
            }
            final long[][] tips = example.tips();
            for (int i = 0; i < tips.length; i++) {
                if (Math.abs(tips[i][2] - z) > OVERLAY_BAND) continue;
                final TipStatus status = ev.status()[i];
                g2.setColor((status == TipStatus.MATCHED) ? MATCHED_COLOR
                        : (status == TipStatus.MISSED) ? MISSED_COLOR : SKIPPED_COLOR);
                g2.setStroke(new BasicStroke(2f));
                ringAt(g2, tips[i][0], tips[i][1], 9);
            }
            final List<TipFinder.Tip> found = ev.tips();
            final int sel = selected;
            for (int i = 0; i < found.size(); i++) {
                final double[] v = voxel(found.get(i));
                if (Math.abs(v[2] - z) > OVERLAY_BAND) continue;
                g2.setColor((i == sel) ? SELECTED_COLOR : DETECTION_COLOR);
                fillDot(g2, v[0], v[1], (i == sel) ? 5 : 4);
            }
            if (sel >= 0 && sel < found.size()) {
                final double[] v = voxel(found.get(sel));
                final double dz = (v[2] - z) * spacing[2];
                final double r2 = config.getTipRadius() * config.getTipRadius() - dz * dz;
                if (r2 > 0) {
                    g2.setColor(SELECTED_COLOR);
                    g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                            new float[]{6f, 4f}, 0f));
                    final double rx = Math.sqrt(r2) / spacing[0] * getMagnification();
                    final double ry = Math.sqrt(r2) / spacing[1] * getMagnification();
                    g2.draw(new java.awt.geom.Ellipse2D.Double(screenXD(v[0] + 0.5) - rx, screenYD(v[1] + 0.5) - ry,
                            2 * rx, 2 * ry));
                }
            }
        }

        private double[] voxel(final TipFinder.Tip tip) {
            return new double[]{tip.position()[0] / spacing[0], tip.position()[1] / spacing[1],
                    tip.position()[2] / spacing[2]};
        }

        private void fillDot(final Graphics2D g2, final double x, final double y, final int radius) {
            final double sx = screenXD(x + 0.5);
            final double sy = screenYD(y + 0.5);
            g2.fill(new java.awt.geom.Ellipse2D.Double(sx - radius, sy - radius, 2 * radius, 2 * radius));
        }

        private void ringAt(final Graphics2D g2, final double x, final double y, final int radius) {
            final double sx = screenXD(x + 0.5);
            final double sy = screenYD(y + 0.5);
            g2.draw(new java.awt.geom.Ellipse2D.Double(sx - radius, sy - radius, 2 * radius, 2 * radius));
        }

        // Selects the detection closest to the click (within the slices in which detections are drawn)
        @Override
        public void mousePressed(final MouseEvent e) {
            final TipDetectionConfig.Evaluation ev = evaluation;
            if (ev == null) return;
            final int z = imp.getSlice() - 1;
            int best = -1;
            double bestD = 15 * 15;
            for (int i = 0; i < ev.tips().size(); i++) {
                final double[] v = voxel(ev.tips().get(i));
                if (Math.abs(v[2] - z) > OVERLAY_BAND) continue;
                final double dx = screenXD(v[0] + 0.5) - e.getX();
                final double dy = screenYD(v[1] + 0.5) - e.getY();
                final double d = dx * dx + dy * dy;
                if (d < bestD) {
                    bestD = d;
                    best = i;
                }
            }
            selected = best;
            repaint();
            if (window != null) window.updateStatus();
        }

        @Override
        public void mouseReleased(final MouseEvent e) {
            // not used
        }

        @Override
        public void mouseDragged(final MouseEvent e) {
            // not used
        }

        @Override
        public void mouseClicked(final MouseEvent e) {
            // not used
        }
    }

    // Panel that follows the width of its scroll pane
    private static class WidthTrackingPanel extends JPanel implements Scrollable {
        private static final long serialVersionUID = 1L;

        WidthTrackingPanel(final LayoutManager layout) {
            super(layout);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(final Rectangle r, final int orientation, final int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(final Rectangle r, final int orientation, final int direction) {
            return Math.max(16, r.height - 16);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    // Window: image (StackWindow, AWT) + one Swing panel with the parameters (in a scroll pane) + AWT buttons. Mixing
    // AWT and Swing controls inside the same container is avoided: heavyweight components do not paint correctly there
    private class TipWindow extends StackWindow {
        private static final long serialVersionUID = 1L;
        private final JSlider thresholdBar;
        private final JSlider radiusBar;
        private final JSlider extentBar;
        private final JSlider purityBar;
        private final JLabel thresholdLabel = new JLabel();
        private final JLabel radiusLabel = new JLabel();
        private final JLabel extentLabel = new JLabel();
        private final JLabel purityLabel = new JLabel();
        private final JLabel vetoLabel = new JLabel();
        private JSlider vetoBar;
        private double vetoLo;
        private double vetoHi;
        private final JLabel statusLabel = new JLabel(" ");
        private final DefaultTableModel learnedModel;
        private double thresholdLo;
        private double thresholdHi;
        private double radiusLo;
        private double radiusHi;
        private final DefaultTableModel scalesModel = new DefaultTableModel(new String[]{"Scale", "Nodes (median)",
                "Nodes (p10)", "Background (p50)", "Background (p99)", "Contrast"}, 0) {
            private static final long serialVersionUID = 1L;

            @Override
            public boolean isCellEditable(final int r, final int col) {
                return false;
            }
        };
        private final JTextArea warnings = new JTextArea("", 3, 60);
        private final JComboBox<String> bgChoice = new JComboBox<>();
        private boolean loading; // True while sliders are set by code
        private JButton updateButton;
        private final java.util.List<Integer> bgChannels = new ArrayList<>();
        private JSlider bgWeightBar;
        private JLabel bgWeightLabel;

        TipWindow(final ImagePlus imp, final ImageCanvas ic) {
            super(imp, ic);
            GuiUtils.zoomToScreenFraction(getCanvas(), 0.55, 8);
            configureRanges();
            final double thr = config.getThreshold();
            thresholdBar = bar(positionOf(thr, thresholdLo, thresholdHi, true));
            radiusBar = bar(positionOf(config.getTipRadius(), radiusLo, radiusHi, false));
            extentBar = bar((int) Math.round(config.getMinExtent() * SLIDER_STEPS));
            purityBar = bar((int) Math.round(config.getMinPurity() * SLIDER_STEPS));
            vetoBar = bar(SLIDER_STEPS); // Far right: off
            configureVeto();
            vetoBar.addChangeListener(e -> {
                if (loading) return;
                config.setVetoThreshold(vetoBar.getValue() >= SLIDER_STEPS ? Double.NaN
                        : valueOf(vetoBar.getValue(), vetoLo, vetoHi, true));
                changed();
            });
            thresholdBar.addChangeListener(e -> {
                if (loading) return;
                config.setThreshold(valueOf(thresholdBar.getValue(), thresholdLo, thresholdHi, true));
                changed();
            });
            radiusBar.addChangeListener(e -> {
                if (loading) return;
                config.setTipRadius(valueOf(radiusBar.getValue(), radiusLo, radiusHi, false));
                changed();
            });
            extentBar.addChangeListener(e -> {
                if (loading) return;
                config.setMinExtent(extentBar.getValue() / (double) SLIDER_STEPS);
                changed();
            });
            purityBar.addChangeListener(e -> {
                if (loading) return;
                config.setMinPurity(purityBar.getValue() / (double) SLIDER_STEPS);
                changed();
            });

            final JPanel root = new WidthTrackingPanel(new GridBagLayout());
            final GridBagConstraints c = new GridBagConstraints();
            c.fill = GridBagConstraints.HORIZONTAL;
            c.weightx = 1.0;
            c.gridx = 0;
            c.gridwidth = GridBagConstraints.REMAINDER;
            int row = 0;
            for (final Component comp : new Component[]{
                    sliderRow("Threshold", thresholdBar, thresholdLabel),
                    sliderRow("Tip radius", radiusBar, radiusLabel)}) {
                c.gridy = row++;
                root.add(comp, c);
            }
            c.gridy = row++;
            root.add(statusLabel, c);

            warnings.setText(String.join("\n", config.getWarnings()));
            warnings.setEditable(false);
            warnings.setLineWrap(true);
            warnings.setWrapStyleWord(true);
            warnings.setFocusable(false);
            warnings.setOpaque(false);
            warnings.setForeground(WizardSupport.WORSE_COLOR);
            warnings.setVisible(!config.getWarnings().isEmpty());
            c.gridy = row++;
            root.add(warnings, c);

            // Expert settings, collapsed by default
            final JPanel advanced = new JPanel(new GridLayout(0, 1));
            advanced.add(sliderRow("Min. extent", extentBar, extentLabel));
            advanced.add(sliderRow("Min. purity", purityBar, purityLabel));
            if (snt.getChannelCount() > 1) {
                advanced.add(backgroundRow());
                advanced.add(sliderRow("Veto (background)", vetoBar, vetoLabel));
            }
            final CollapsiblePanel advancedPanel = new CollapsiblePanel("Advanced", advanced, true);
            advancedPanel.setTooltipText("Extra filters: min. extent and purity of detections, and the background "
                    + "channel (subtraction and veto)");
            c.gridy = row++;
            root.add(advancedPanel, c);

            learnedModel = new DefaultTableModel(new String[]{"Learned from examples", "Value"}, 0) {
                private static final long serialVersionUID = 1L;

                @Override
                public boolean isCellEditable(final int r, final int col) {
                    return false;
                }
            };
            final JPanel details = new JPanel(new GridLayout(0, 1));
            details.add(tablePane(new JTable(learnedModel)));
            details.add(scalesPane());
            final CollapsiblePanel detailsPanel = new CollapsiblePanel("Details", details, true);
            detailsPanel.setTooltipText("Parameters learned from the example paths, and the response of each scale");
            c.gridy = row++;
            root.add(detailsPanel, c);

            refresh(); // Fills the tables before the window is sized
            final JScrollPane scroll = new JScrollPane(root, javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                    javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER) {
                private static final long serialVersionUID = 1L;

                // Height capped, so that the window does not outgrow the screen
                @Override
                public Dimension getPreferredSize() {
                    final Dimension d = super.getPreferredSize();
                    final int cap = (int) (Toolkit.getDefaultToolkit().getScreenSize().height * 0.35);
                    return new Dimension(d.width, Math.min(d.height, cap));
                }
            };
            scroll.setBorder(null);
            scroll.getVerticalScrollBar().setUnitIncrement(16);
            final Panel south = new Panel(new BorderLayout());
            south.add(scroll, BorderLayout.CENTER);
            south.add(buttonPanel(), BorderLayout.SOUTH);
            add(south);
            pack();
            if (parent != null)
                setLocationRelativeTo(parent);
            else
                GuiUtils.Windows.center(this, (snt.getImagePlus() == null) ? null : snt.getImagePlus().getWindow());
            new GuiUtils(this.getCanvas()).tempMsg("Click a detection to see its analysis radius", 4000);
        }

        // The veto needs a background channel. Its slider is off (n/a) at its right end: moving it left enables it
        private void configureVeto() {
            final boolean available = vetoRegion != null && !Double.isNaN(vetoSuggested) && vetoSuggested > 0;
            vetoBar.setEnabled(available);
            vetoBar.setValue(SLIDER_STEPS);
            if (available) {
                vetoLo = vetoSuggested / 20;
                vetoHi = vetoSuggested * 20;
                if (!Double.isNaN(config.getVetoThreshold()))
                    vetoBar.setValue(Math.min(SLIDER_STEPS - 1, positionOf(config.getVetoThreshold(), vetoLo, vetoHi, true)));
            }
        }

        private void configureRanges() {
            final double minSpacing = Math.min(spacing[0], Math.min(spacing[1], spacing[2]));
            final double thr = config.getThreshold();
            thresholdLo = (Double.isNaN(thr) ? 1e-3 : Math.max(1e-6, thr / 50));
            thresholdHi = (Double.isNaN(thr) ? 1 : thr * 20);
            radiusLo = 3 * minSpacing;
            radiusHi = Math.max(2 * config.getTipRadius(), 2 * radiusLo);
        }

        // Called on the EDT after the parameters were learned again
        void reload() {
            loading = true;
            try {
                reloadSliders();
            } finally {
                loading = false;
            }
            final int nScales = scalesModel.getRowCount();
            fillScales();
            warnings.setText(String.join("\n", config.getWarnings()));
            final boolean showWarnings = !config.getWarnings().isEmpty();
            if (nScales != scalesModel.getRowCount() || showWarnings != warnings.isVisible()) {
                warnings.setVisible(showWarnings);
                pack();
            }
            loading = true;
            try {
                if (bgChoice.getItemCount() > 0) bgChoice.setSelectedIndex(bgChannels.indexOf(bgChannel) + 1);
            } finally {
                loading = false;
            }
            syncUpdateButton();
            refresh();
        }

        // Update only has an effect if the background channel or its weight differ from the ones in use
        private void syncUpdateButton() {
            if (updateButton == null) return;
            final int idx = bgChoice.getSelectedIndex();
            final int channel = (idx <= 0) ? 0 : bgChannels.get(idx - 1);
            final double weight = bgWeightBar.getValue() * WEIGHT_MAX / SLIDER_STEPS;
            updateButton.setEnabled(channel != bgChannel || (channel > 0 && Math.abs(weight - bgWeight) > 1e-3));
        }

        private void reloadSliders() {
            configureRanges();
            thresholdBar.setValue(positionOf(config.getThreshold(), thresholdLo, thresholdHi, true));
            radiusBar.setValue(positionOf(config.getTipRadius(), radiusLo, radiusHi, false));
            extentBar.setValue((int) Math.round(config.getMinExtent() * SLIDER_STEPS));
            purityBar.setValue((int) Math.round(config.getMinPurity() * SLIDER_STEPS));
            configureVeto();
        }

        private void fillScales() {
            scalesModel.setRowCount(0);
            for (final TipDetectionConfig.ScaleResponse r : config.getScaleResponses()) {
                scalesModel.addRow(new String[]{withVoxels(r.scale()), fmt(r.nodeMedian()), fmt(r.nodeP10()),
                        fmt(r.backgroundP50()), fmt(r.backgroundP99()), fmt(r.contrast())});
            }
        }

        private JSlider bar(final int position) {
            final JSlider sl = new JSlider(0, SLIDER_STEPS, Math.clamp(position, 0, SLIDER_STEPS));
            sl.setFocusable(false);
            return sl;
        }

        private JPanel sliderRow(final String name, final JSlider bar, final JLabel value) {
            final JPanel p = new JPanel(new BorderLayout(6, 0));
            final JLabel l = new JLabel(name + ":");
            l.setPreferredSize(new Dimension(l.getFontMetrics(l.getFont()).stringWidth("Veto (background):") + 4,
                    l.getPreferredSize().height));
            value.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
            value.setPreferredSize(new Dimension(value.getFontMetrics(value.getFont()).stringWidth("0") * 22,
                    value.getPreferredSize().height));
            p.add(l, BorderLayout.WEST);
            p.add(bar, BorderLayout.CENTER);
            p.add(value, BorderLayout.EAST);
            return p;
        }

        // No scroll pane of its own: the table takes the height of all its rows (the controls panel scrolls)
        private JPanel tablePane(final JTable table) {
            table.setRowHeight(table.getFontMetrics(table.getFont()).getHeight() + 6);
            table.setFocusable(false);
            final JPanel pane = new JPanel(new BorderLayout());
            pane.add(table.getTableHeader(), BorderLayout.NORTH);
            pane.add(table, BorderLayout.CENTER);
            return pane;
        }

        private JPanel scalesPane() {
            fillScales();
            return tablePane(new JTable(scalesModel));
        }

        // Background channel and weight: the Update button re-learns from the examples
        private JPanel backgroundRow() {
            final JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT));
            bgChoice.addItem("None");
            for (int ch = 1; ch <= snt.getChannelCount(); ch++) {
                if (ch == snt.getChannel()) continue;
                bgChannels.add(ch);
                bgChoice.addItem("Channel " + ch);
            }
            bgChoice.setSelectedIndex(bgChannels.indexOf(bgChannel) + 1);
            bgChoice.setFocusable(false);
            bgWeightBar = bar((int) Math.round(bgWeight / WEIGHT_MAX * SLIDER_STEPS));
            bgWeightLabel = new JLabel(String.format("%.2f", bgWeight));
            bgWeightBar.addChangeListener(e -> {
                bgWeightLabel.setText(String.format("%.2f", bgWeightBar.getValue() * WEIGHT_MAX / SLIDER_STEPS));
                syncUpdateButton();
            });
            bgChoice.addActionListener(e -> syncUpdateButton());
            final JButton update = new JButton("Update");
            updateButton = update;
            update.setFocusable(false);
            update.setEnabled(false);
            update.addActionListener(e -> {
                update.setEnabled(false); // Until the window is updated
                final int idx = bgChoice.getSelectedIndex();
                changeBackground((idx <= 0) ? 0 : bgChannels.get(idx - 1),
                        bgWeightBar.getValue() * WEIGHT_MAX / SLIDER_STEPS);
            });
            p.add(new JLabel("Subtract background:"));
            p.add(bgChoice);
            p.add(new JLabel("weight"));
            p.add(bgWeightBar);
            p.add(bgWeightLabel);
            p.add(update);
            return p;
        }

        private Panel buttonPanel() {
            final Panel p = new Panel(new FlowLayout(FlowLayout.LEFT));
            final Button apply = new Button("Apply...");
            apply.setFocusable(false);
            final PopupMenu applyMenu = new PopupMenu();
            final MenuItem regionItem = new MenuItem("Apply to Loaded Region (Dry Run)");
            regionItem.addActionListener(e -> commit(false));
            final MenuItem wholeItem = new MenuItem("Apply to Whole Volume");
            wholeItem.addActionListener(e -> commit(true));
            applyMenu.add(regionItem);
            applyMenu.add(wholeItem);
            apply.add(applyMenu);
            apply.addActionListener(e -> applyMenu.show(apply, 0, apply.getHeight()));
            final Button bc = new Button("B&C");
            bc.setFocusable(false);
            bc.addActionListener(e -> IJ.doCommand("Brightness/Contrast..."));
            final Button more = paletteOptions.buildMoreButton(this, helpHtml(), null);
            more.setFocusable(false);
            p.add(apply);
            p.add(bc);
            p.add(more);
            return p;
        }

        // Slider position <-> value (log scale for the threshold, as scores span orders of magnitude)
        private int positionOf(final double value, final double lo, final double hi, final boolean log) {
            if (Double.isNaN(value)) return 0;
            final double f = (log) ? Math.log(value / lo) / Math.log(hi / lo) : (value - lo) / (hi - lo);
            return (int) Math.round(Math.max(0, Math.min(1, f)) * SLIDER_STEPS);
        }

        private double valueOf(final int position, final double lo, final double hi, final boolean log) {
            final double f = position / (double) SLIDER_STEPS;
            return (log) ? lo * Math.pow(hi / lo, f) : lo + f * (hi - lo);
        }

        private void changed() {
            updateLabels();
            scheduleEvaluation();
        }

        private void updateLabels() {
            thresholdLabel.setText(fmt(config.getThreshold()));
            radiusLabel.setText(withVoxels(config.getTipRadius()));
            extentLabel.setText(String.format("%.2f", config.getMinExtent()));
            purityLabel.setText(String.format("%.2f", config.getMinPurity()));
            vetoLabel.setText(Double.isNaN(config.getVetoThreshold()) ? "n/a" : fmt(config.getVetoThreshold()));
        }

        // Called on the EDT after each evaluation
        void refresh() {
            updateLabels();
            updateStatus();
            learnedModel.setRowCount(0);
            learnedModel.addRow(new String[]{"Example paths (nodes, tips)",
                    paths.size() + " (" + example.nodes().length + ", " + example.tips().length + ")"});
            learnedModel.addRow(new String[]{"Radius (min / median / max)", withVoxels(config.getMinRadius()) + " / "
                    + withVoxels(config.getMedianRadius()) + " / " + withVoxels(config.getMaxRadius())});
            final StringBuilder scales = new StringBuilder();
            for (final double s : config.getScales()) scales.append(withVoxels(s)).append("; ");
            learnedModel.addRow(new String[]{"Scales", scales.toString()});
            learnedModel.addRow(new String[]{"Scores at nodes (p10 / median)", fmt(config.getNodeScoreP10()) + " / "
                    + fmt(config.getNodeScoreMedian())});
            learnedModel.addRow(new String[]{"Scores elsewhere (p50 / p99)", fmt(config.getBackgroundP50()) + " / "
                    + fmt(config.getBackgroundP99())});
            getCanvas().repaint();
        }

        void updateStatus() {
            final TipDetectionConfig.Evaluation ev = evaluation;
            if (ev == null) {
                statusLabel.setText("Parameters are incomplete: no detection possible");
                return;
            }
            final StringBuilder sb = new StringBuilder();
            sb.append(String.format("%d of %d example tips found | %d detections (%d extra)", ev.matched(),
                    ev.pathEnds(), ev.tips().size(), ev.extra()));
            if (ev.skipped() > 0)
                sb.append(String.format(" | %d example tip(s) too close to the border: not evaluated", ev.skipped()));
            final int sel = selected;
            if (sel >= 0 && sel < ev.tips().size())
                sb.append(String.format(" | selected: score %s", fmt(ev.tips().get(sel).score())));
            statusLabel.setText(sb.toString());
        }

        @Override
        public String createSubtitle() {
            return String.format("Preview | %s x %s x %s voxels", region.data().dimension(0),
                    region.data().dimension(1), region.data().dimension(2));
        }

        @Override
        public void windowClosing(final WindowEvent e) {
            dismiss();
            super.windowClosing(e);
        }

        private String helpHtml() {
            return "<HTML><div WIDTH=500><p>The <i>Tip Detection Wizard</i> learns the parameters of the tip "
                    + "detector from the example paths, and previews the detections on the region they span. The "
                    + "preview reads the data directly from the source: it is not affected by crops.</p>"
                    + "<ul>"
                    + "<li><b>Rings</b> mark the ends of the example paths: green if a detection was found nearby, "
                    + "red if not, gray if too close to the border of the region to be evaluated. <b>Dots</b> are "
                    + "detections. Click a detection to see the sphere (dashed) in which it was analyzed</li>"
                    + "<li><b>Threshold</b>: score above which voxels are considered to be part of a structure. "
                    + "Learned from the scores along the examples</li>"
                    + "<li><b>Tip radius</b>: size of the neighborhood analyzed around each candidate (about 3x the "
                    + "diameter of the thickest structures)</li>"
                    + "<li><b>Min. extent</b>: how far (as a fraction of the radius) the structure connected to a "
                    + "candidate must reach. Rejects speckles</li>"
                    + "<li><b>Min. purity</b>: fraction of the voxels in the neighborhood that must be connected "
                    + "to the candidate. Rejects clutter</li>"
                    + "<li><i>Extra</i> detections are not necessarily false: the examples are only a sample of the "
                    + "structures in the region</li>"
                    + "</ul></div>";
        }
    }
}
