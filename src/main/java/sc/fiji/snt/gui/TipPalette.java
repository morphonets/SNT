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
import sc.fiji.snt.tracing.auto.TipExamples;
import sc.fiji.snt.tracing.auto.TipFinder;
import sc.fiji.snt.util.BigDataUtils;
import sc.fiji.snt.util.ImgUtils;
import sc.fiji.snt.util.PointInCanvas;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JCheckBox;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JSpinner;
import javax.swing.UIManager;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.text.DefaultFormatterFactory;
import javax.swing.text.NumberFormatter;
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
         * @param options     resolution level and empty block settings of whole volume runs. Null if not
         *                    applicable (e.g., no image pyramid)
         */
        void tipConfigPicked(TipDetectionConfig config, boolean wholeVolume, ParseOptions options);

        /** Called when the wizard is closed */
        default void dismissed() {
        }
    }

    /**
     * Settings of whole volume runs
     *
     * @param level     the resolution level (0: full resolution)
     * @param skipEmpty whether blocks without signal (assessed at a coarser level) are skipped
     * @param madFactor the number of median absolute deviations above the median defining signal
     */
    public record ParseOptions(int level, boolean skipEmpty, double madFactor) {
    }

    private static final double MIN_SCALE_VOXELS = 0.7;
    private static final int PAIR_GAP = 16;
    // Widest label of the Advanced panel: sets the label column of its rows
    private static final String WIDEST_LABEL = "Subtract background:";

    /** Description of a level of the image pyramid */
    public static String describeLevel(final BigDataUtils.LevelInfo l) {
        return String.format("Level %d: %d x %d x %d voxels (%s x %s x %s)", l.level(), l.dims()[0], l.dims()[1],
                l.dims()[2], SNTUtils.formatDouble(l.spacing()[0], 3), SNTUtils.formatDouble(l.spacing()[1], 3),
                SNTUtils.formatDouble(l.spacing()[2], 3));
    }

    // Coarsest level at which the smallest learned scale still spans a few voxels
    private static int recommendedLevel(final TipDetectionConfig config, final List<BigDataUtils.LevelInfo> levels) {
        double minScale = Double.MAX_VALUE;
        for (final double sc : config.getScales()) minScale = Math.min(minScale, sc);
        int best = 0;
        for (final var l : levels) {
            if (minScale / Math.max(l.spacing()[0], l.spacing()[1]) >= MIN_SCALE_VOXELS) best = l.level();
        }
        return best;
    }

    // Levels of the active channel in the viewer (Stream mode), or an empty list
    private List<BigDataUtils.LevelInfo> streamLevels() {
        if (!snt.isStreamMode() || snt.getUI() == null || snt.getUI().getActiveBigViewer() == null)
            return List.of();
        return snt.getUI().getActiveBigViewer().getSourceLevels(snt.getChannel() - 1);
    }

    // Limits of the region that is read into memory
    private static final long MAX_VOXELS_PER_AXIS = 700;
    private static final long MAX_VOXELS = 80_000_000L;
    private static final int MIN_PAD_VOXELS = 8;
    private static final double WEIGHT_MAX = 2;
    // Slices (either side of the displayed one) in which overlays are drawn
    private static final int OVERLAY_BAND = 2;
    private static final Color NODE_COLOR = new Color(0, 200, 255, 160);
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
    private double[] customScales; // User-defined scales (calibrated units). Null: learned from the examples
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
            config = TipDetectionConfig.learn(example, spacing, (customScales != null) ? customScales
                    : TipExamples.scales(example.radii()),
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

    // Scales derived from the radii of the examples
    private double[] defaultScales() {
        return TipExamples.scales(example.radii());
    }

    private static String formatScales(final double[] scales) {
        final StringBuilder sb = new StringBuilder();
        for (final double s : scales) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(String.format(java.util.Locale.ROOT, "%.3g", s));
        }
        return sb.toString();
    }

    // Null if the text is not a list of positive numbers
    private static double[] parseScales(final String text) {
        final String[] tokens = text.trim().split("[,;\\s]+");
        if (tokens.length == 0 || tokens.length > 8) return null;
        final double[] values = new double[tokens.length];
        try {
            for (int i = 0; i < tokens.length; i++) {
                values[i] = Double.parseDouble(tokens[i]);
                if (!(values[i] > 0) || Double.isInfinite(values[i])) return null;
            }
        } catch (final NumberFormatException ex) {
            return null;
        }
        java.util.Arrays.sort(values);
        return values;
    }

    // Displayed scales are rounded: compare with a tolerance
    private static boolean sameScales(final double[] a, final double[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > 0.01 * Math.max(Math.abs(a[i]), Math.abs(b[i]))) return false;
        }
        return true;
    }

    // Re-learns after a change of the background and/or scales and updates the window in place
    // scales: null keeps the current ones, an empty array goes back to the ones learned from the examples
    private void changeBackground(final int channel, final double weight, final double[] scales) {
        worker.execute(() -> {
            generation.incrementAndGet(); // Cancels pending evaluations
            if (scales != null) customScales = (scales.length == 0) ? null : scales.clone();
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

    // Number as a Groovy literal
    private static String num(final double v) {
        return Double.isNaN(v) ? "Double.NaN" : Double.toString(v);
    }

    // Path of the dataset being processed, or null
    private String datasetPath() {
        if (snt.isStreamMode() && snt.getUI() != null && snt.getUI().getActiveBigViewer() != null) {
            final String path = snt.getUI().getActiveBigViewer().getPrimarySourcePath();
            if (path != null && !path.isBlank()) return path;
        }
        final ImagePlus imp = snt.getImagePlus();
        if (imp != null && imp.getOriginalFileInfo() != null && imp.getOriginalFileInfo().fileName != null
                && imp.getOriginalFileInfo().directory != null) {
            return new java.io.File(imp.getOriginalFileInfo().directory, imp.getOriginalFileInfo().fileName)
                    .getAbsolutePath();
        }
        return null;
    }

    // Opens a Groovy recipe with the current parameters, so that they can be applied (and batched) in scripts
    private void exportRecipe() {
        final String path = datasetPath();
        if (path == null) {
            if (snt.getUI() != null) snt.getUI().error("The path of the image could not be determined. Recipes "
                    + "require an image loaded from a file.");
            return;
        }
        final ParseOptions opts = (window == null) ? null : window.parseOptions();
        final java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("INPUT_PATH", path);
        values.put("SIG_CHANNEL", snt.getChannel() - 1);
        values.put("BG_CHANNEL", (config.getBackgroundChannel() > 0) ? config.getBackgroundChannel() - 1 : -1);
        values.put("BG_WEIGHT", num(config.getBackgroundWeight()));
        values.put("LEVEL", (opts == null) ? 0 : opts.level());
        values.put("SKIP_EMPTY", (opts == null) ? "false" : String.valueOf(opts.skipEmpty()));
        values.put("MAD_FACTOR", num((opts == null) ? 2d : opts.madFactor()));
        values.put("SCALES", java.util.Arrays.stream(config.getScales()).mapToObj(TipPalette::num)
                .collect(java.util.stream.Collectors.joining(", ")));
        values.put("TIP_RADIUS", num(config.getTipRadius()));
        values.put("THRESHOLD", num(config.getThreshold()));
        values.put("MIN_EXTENT", num(config.getMinExtent()));
        values.put("MIN_PURITY", num(config.getMinPurity()));
        values.put("VETO_THRESHOLD", num(config.getVetoThreshold()));
        values.put("VETO_RADIUS", num(config.getVetoRadius()));
        values.put("REF_SPACING", num(spacing[0]) + ", " + num(spacing[1]) + ", " + num(spacing[2]));
        values.put("REF_NODE_P10", num(config.getNodeScoreP10()));
        values.put("REF_NODE_MEDIAN", num(config.getNodeScoreMedian()));
        values.put("REF_BG_P50", num(config.getBackgroundP50()));
        values.put("REF_BG_P99", num(config.getBackgroundP99()));
        try {
            ScriptInstaller.newRecipe("LazyTipDetection.groovy", values, "TipDetection.groovy");
        } catch (final java.io.IOException | RuntimeException ex) {
            SNTUtils.error("Recipe could not be exported", ex);
            if (snt.getUI() != null) snt.getUI().error("Recipe could not be exported: " + ex.getMessage());
        }
    }

    private void commit(final boolean wholeVolume) {
        final List<Listener> copy = new ArrayList<>(listeners);
        ParseOptions options = null;
        try {
            if (wholeVolume && window != null) options = window.parseOptions();
        } catch (final RuntimeException ex) {
            SNTUtils.error("Tip wizard: could not read the performance settings", ex);
        }
        final ParseOptions finalOptions = options;
        if (wholeVolume) {
            dismiss(); // Clears the listeners, so use a copy
        }
        copy.forEach(l -> l.tipConfigPicked(config, wholeVolume, finalOptions));
    }

    public void dismiss() {
        generation.incrementAndGet();
        worker.shutdownNow();
        new ArrayList<>(listeners).forEach(Listener::dismissed);
        if (window != null) {
            final TipWindow w = window;
            window = null;
            try {
                if (paletteImage != null) paletteImage.changes = false; // No "save changes" prompt
                w.close();
            } catch (final RuntimeException ex) {
                SNTUtils.error("Tip wizard: window could not be closed", ex);
            }
            // Fallback: closing can be refused (or fail) silently
            if (w.isDisplayable()) {
                w.setVisible(false);
                w.dispose();
            }
        }
        if (paletteImage != null) paletteImage.flush();
        region = null;
        ridge = null;
        listeners.clear();
        if (snt.getUI() != null) snt.getUI().changeState(SNTUI.READY); // Runs on EDT
    }

    private static String fmt(final double v) {
        return Double.isNaN(v) ? "n/a" : String.format("%.3g", v);
    }

    // Value in calibrated units (unit shown in the label of the row), with its size in voxels
    private String voxelsOnly(final double calibrated) {
        if (Double.isNaN(calibrated)) return "n/a";
        final double avgSpacing = (spacing[0] + spacing[1] + spacing[2]) / 3;
        return String.format("%.3g (%.1f vox)", calibrated, calibrated / avgSpacing);
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

    // Window: image (StackWindow, AWT) + one Swing panel with the parameters (in a scroll pane) + Swing buttons.
    // Mixing AWT and Swing controls inside the same container is avoided: heavyweight components do not paint
    // correctly there
    private class TipWindow extends StackWindow {
        private static final long serialVersionUID = 1L;
        private final JSpinner thresholdSpin = GuiUtils.Fields.doubleSpinner(1, 0, 1e12, 1, 3, true);
        private final JSpinner radiusSpin = GuiUtils.Fields.doubleSpinner(1, 0, 1e9, 1, 1, true);
        private final JSpinner extentSpin = GuiUtils.Fields.doubleSpinner(0, 0, 1, 0.05, 2);
        private final JSpinner puritySpin = GuiUtils.Fields.doubleSpinner(0, 0, 1, 0.05, 2);
        private final JSpinner vetoSpin = GuiUtils.Fields.doubleSpinner(0, 0, 1e12, 1, 3, true);
        private final JSpinner bgWeightSpin = GuiUtils.Fields.doubleSpinner(1, 0, WEIGHT_MAX, 0.05, 2);
        private final JTextField scalesField = new JTextField(18);
        private final JLabel radiusLabel = new JLabel();
        private final JLabel statusLabel = new JLabel(" ");
        private final DefaultTableModel learnedModel;
        private final DefaultTableModel scalesModel = new DefaultTableModel(new String[]{"Scale [" + unit + "]", "Nodes (median)",
                "Nodes (p10)", "Background (p50)", "Background (p99)", "Contrast"}, 0) {
            private static final long serialVersionUID = 1L;

            @Override
            public boolean isCellEditable(final int r, final int col) {
                return false;
            }
        };
        private final JTextArea warnings = new JTextArea("", 3, 60);
        private final JComboBox<String> bgChoice = new JComboBox<>();
        private boolean loading; // True while spinners are set by code
        private JButton updateButton;
        private Panel southPanel;
        private CollapsiblePanel detectionCollapsible;
        private final List<CollapsiblePanel> collapsibles = new ArrayList<>();
        private JButton scalesReset;
        private JButton weightReset;
        private final java.util.List<Integer> bgChannels = new ArrayList<>();
        // Values displayed when the controls were (re)loaded: reset buttons restore them
        private double initThreshold;
        private double initRadius;
        private double initExtent;
        private double initPurity;
        private final double initBgWeight = bgWeight;
        // Whole volume settings (Stream mode)
        private final List<BigDataUtils.LevelInfo> levels = streamLevels();
        private JComboBox<String> levelChoice;
        private JCheckBox skipEmpty;
        private JSpinner madSpin;

        TipWindow(final ImagePlus imp, final ImageCanvas ic) {
            super(imp, ic);
            GuiUtils.zoomToScreenFraction(getCanvas(), 0.55, 8);
            bgWeightSpin.setValue(bgWeight);
            loadSpinners();
            thresholdSpin.addChangeListener(e -> {
                if (loading) return;
                config.setThreshold(val(thresholdSpin));
                changed();
            });
            radiusSpin.addChangeListener(e -> {
                if (loading) return;
                config.setTipRadius(val(radiusSpin));
                changed();
            });
            extentSpin.addChangeListener(e -> {
                if (loading) return;
                config.setMinExtent(val(extentSpin));
                changed();
            });
            puritySpin.addChangeListener(e -> {
                if (loading) return;
                config.setMinPurity(val(puritySpin));
                changed();
            });
            vetoSpin.addChangeListener(e -> {
                if (loading) return;
                final double v = val(vetoSpin);
                config.setVetoThreshold((v <= 0) ? Double.NaN : v); // 0: off (n/a)
                changed();
            });

            final JPanel root = new WidthTrackingPanel(new GridBagLayout());
            final GridBagConstraints c = new GridBagConstraints();
            c.fill = GridBagConstraints.HORIZONTAL;
            c.weightx = 1.0;
            c.gridx = 0;
            c.gridwidth = GridBagConstraints.REMAINDER;
            int row = 0;
            final int widthRow = row++; // Reserved for the strut that keeps the width constant

            warnings.setText(String.join("\n", config.getWarnings()));
            warnings.setEditable(false);
            warnings.setLineWrap(true);
            warnings.setWrapStyleWord(true);
            warnings.setFocusable(false);
            warnings.setOpaque(false);
            warnings.setForeground(WizardSupport.WORSE_COLOR);
            warnings.setVisible(!config.getWarnings().isEmpty());
            final JPanel detection = new JPanel(new GridBagLayout());
            final GridBagConstraints dc = new GridBagConstraints();
            dc.fill = GridBagConstraints.HORIZONTAL;
            dc.weightx = 1.0;
            dc.gridx = 0;
            dc.gridy = 0;
            detection.add(pair(paramRow("Threshold", thresholdSpin, () -> setIfValid(thresholdSpin, initThreshold),
                            null, "Threshold:"),
                    paramRow("Tip radius [" + unit + "]", radiusSpin, () -> setIfValid(radiusSpin, initRadius),
                            radiusLabel, "Tip radius [" + unit + "]:")), dc);
            dc.gridy = 1;
            detection.add(statusLabel, dc);
            dc.gridy = 2;
            detection.add(warnings, dc);
            c.gridy = row++;
            final CollapsiblePanel detectionPanel = new CollapsiblePanel("Detection", detection, false);
            detectionPanel.setTooltipText("Threshold and tip radius, number of detections and warnings");
            root.add(section(null, detectionPanel), c);

            // Expert settings, collapsed by default
            final JPanel advanced = advancedGrid();
            final CollapsiblePanel advancedPanel = new CollapsiblePanel("Advanced", advanced, true);
            advancedPanel.setTooltipText("Extra filters: min. extent and purity of detections, and the background "
                    + "channel (subtraction and veto). The veto is off (n/a) at 0");
            c.gridy = row++;
            root.add(section(null, advancedPanel), c);

            CollapsiblePanel performance = null;
            if (levels.size() > 1) {
                performance = performancePanel();
                c.gridy = row++;
                root.add(section(null, performance), c);
            }

            learnedModel = new DefaultTableModel(new String[]{"Learned from examples", "Value"}, 0) {
                private static final long serialVersionUID = 1L;

                @Override
                public boolean isCellEditable(final int r, final int col) {
                    return false;
                }
            };
            final JPanel details = new JPanel(new GridLayout(0, 1, 0, 10));
            details.add(tablePane(new JTable(learnedModel)));
            details.add(scalesPane());
            final CollapsiblePanel detailsPanel = new CollapsiblePanel("Details", details, true);
            detailsPanel.setTooltipText("Parameters learned from the example paths, and the response of each scale");
            c.gridy = row++;
            root.add(section(null, detailsPanel), c);

            detectionCollapsible = detectionPanel;
            collapsibles.add(detectionPanel);
            collapsibles.add(advancedPanel);
            if (performance != null) collapsibles.add(performance);
            collapsibles.add(detailsPanel);
            refresh(); // Fills the tables before the window is sized
            // All rows share the width of the widest content, so that the window does not resize on toggling
            int width = Math.max(details.getPreferredSize().width, Math.max(advanced.getPreferredSize().width,
                    warnings.getPreferredSize().width));
            if (performance != null) width = Math.max(width, performance.getPreferredSize().width);
            c.gridy = widthRow;
            c.weighty = 0;
            root.add(javax.swing.Box.createHorizontalStrut(width), c);
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
            // Heavyweight container: it paints its lightweight (Swing) children reliably inside the AWT window
            final Panel south = new Panel(new BorderLayout());
            southPanel = south;
            south.add(scroll, BorderLayout.CENTER);
            south.add(buttonPanel(), BorderLayout.SOUTH);
            add(south);
            pack();
            if (parent != null)
                setLocationRelativeTo(parent);
            else
                GuiUtils.Windows.center(this, (snt.getImagePlus() == null) ? null : snt.getImagePlus().getWindow());
        }

        // Boxed section: titled border (as in MaterializeRegionDialog) or a plain etched one
        private JPanel section(final String title, final Component content) {
            final JPanel panel = new JPanel(new BorderLayout());
            panel.setBorder(javax.swing.BorderFactory.createCompoundBorder((title == null)
                            ? javax.swing.BorderFactory.createEtchedBorder()
                            : javax.swing.BorderFactory.createTitledBorder(title),
                    javax.swing.BorderFactory.createEmptyBorder(2, 2, 2, 2)));
            panel.add(content, BorderLayout.CENTER);
            return panel;
        }

        private double val(final JSpinner sp) {
            return ((Number) sp.getValue()).doubleValue();
        }

        private void setIfValid(final JSpinner sp, final double value) {
            if (!Double.isNaN(value)) sp.setValue(value);
        }

        private int decimalsFor(final double v) {
            if (Double.isNaN(v) || v <= 0) return 3;
            return Math.clamp(3 - (int) Math.floor(Math.log10(v)), 0, 8);
        }

        private String patternOf(final int decimals) {
            return (decimals <= 0) ? "#0" : "#0." + "0".repeat(decimals);
        }

        private void configure(final JSpinner sp, final double value, final double min, final double max,
                               final double step, final int decimals) {
            final SpinnerNumberModel m = (SpinnerNumberModel) sp.getModel();
            m.setMinimum(min);
            m.setMaximum(max);
            m.setStepSize(step);
            m.setValue(Double.isNaN(value) ? min : Math.clamp(value, min, max));
            final JSpinner.NumberEditor ed = new JSpinner.NumberEditor(sp, patternOf(decimals));
            ed.getTextField().setColumns(8);
            sp.setEditor(ed);
        }

        // Spinner that shows n/a for 0 (veto off)
        private static class OffFormat extends java.text.DecimalFormat {
            private static final long serialVersionUID = 1L;

            OffFormat(final String pattern) {
                super(pattern);
            }

            @Override
            public StringBuffer format(final double n, final StringBuffer sb, final java.text.FieldPosition fp) {
                return (n <= 0) ? sb.append("n/a") : super.format(n, sb, fp);
            }

            @Override
            public StringBuffer format(final long n, final StringBuffer sb, final java.text.FieldPosition fp) {
                return (n <= 0) ? sb.append("n/a") : super.format(n, sb, fp);
            }

            @Override
            public Number parse(final String s, final java.text.ParsePosition pp) {
                if (s.trim().equalsIgnoreCase("n/a")) {
                    pp.setIndex(s.length());
                    return 0d;
                }
                return super.parse(s, pp);
            }
        }

        // Sets the models and displayed values of the spinners from the current config. Callers must not rely on
        // listeners being triggered
        private void loadSpinners() {
            final boolean wasLoading = loading;
            loading = true;
            try {
                final double minSpacing = Math.min(spacing[0], Math.min(spacing[1], spacing[2]));
                final double thr = config.getThreshold();
                configure(thresholdSpin, thr, 0, 1e12, Double.isNaN(thr) ? 1 : thr / 100, decimalsFor(thr));
                configure(radiusSpin, config.getTipRadius(), minSpacing, 1e9, minSpacing, 1);
                configure(extentSpin, config.getMinExtent(), 0, 1, 0.05, 2);
                configure(puritySpin, config.getMinPurity(), 0, 1, 0.05, 2);
                final boolean available = vetoRegion != null && !Double.isNaN(vetoSuggested) && vetoSuggested > 0;
                final double max = (available) ? vetoSuggested * 20 : 1;
                final double veto = Double.isNaN(config.getVetoThreshold()) ? 0 : config.getVetoThreshold();
                final SpinnerNumberModel m = (SpinnerNumberModel) vetoSpin.getModel();
                m.setMinimum(0d);
                m.setMaximum(max);
                m.setStepSize((available) ? vetoSuggested / 10 : 1);
                m.setValue(Math.clamp(veto, 0, max));
                final String pattern = patternOf(decimalsFor((available) ? vetoSuggested : Double.NaN));
                final JSpinner.NumberEditor ed = new JSpinner.NumberEditor(vetoSpin, pattern);
                final NumberFormatter nf = new NumberFormatter(new OffFormat(pattern));
                nf.setValueClass(Double.class);
                nf.setMinimum(0d);
                nf.setMaximum(max);
                ed.getTextField().setFormatterFactory(new DefaultFormatterFactory(nf));
                ed.getTextField().setColumns(8);
                vetoSpin.setEditor(ed);
                vetoSpin.setEnabled(available);
                initThreshold = config.getThreshold();
                initRadius = config.getTipRadius();
                initExtent = config.getMinExtent();
                initPurity = config.getMinPurity();
            } finally {
                loading = wasLoading;
            }
        }

        // Called on the EDT after the parameters were learned again
        void reload() {
            loadSpinners();
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
            scalesField.setText(formatScales(config.getScales()));
            syncUpdateButton();
            refresh();
        }

        private int selectedBackground() {
            final int idx = bgChoice.getSelectedIndex();
            if (bgChoice.getItemCount() == 0) return bgChannel; // Single channel
            return (idx <= 0) ? 0 : bgChannels.get(idx - 1);
        }

        // Update only has an effect if the background channel, its weight or the scales differ from the ones in use
        private void syncUpdateButton() {
            final double[] sc = parseScales(scalesField.getText());
            scalesField.setForeground((sc == null) ? GuiUtils.Colors.errorColor()
                    : UIManager.getColor("TextField.foreground"));
            if (updateButton == null) return;
            final int channel = selectedBackground();
            final boolean bgDiff = channel != bgChannel
                    || (channel > 0 && Math.abs(val(bgWeightSpin) - bgWeight) > 1e-3);
            final boolean scalesDiff = sc != null && !sameScales(sc, config.getScales());
            updateButton.setEnabled(bgDiff || scalesDiff);
        }

        // Scales (editable) and the Update button, which re-learns from the examples
        private void setupScales() {
            scalesField.setText(formatScales(config.getScales()));
            scalesField.setToolTipText("Comma-separated list of scales (" + unit + "). Press Update to apply");
            scalesField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                @Override
                public void insertUpdate(final javax.swing.event.DocumentEvent e) {
                    syncUpdateButton();
                }

                @Override
                public void removeUpdate(final javax.swing.event.DocumentEvent e) {
                    syncUpdateButton();
                }

                @Override
                public void changedUpdate(final javax.swing.event.DocumentEvent e) {
                    syncUpdateButton();
                }
            });
            scalesReset = GuiUtils.Buttons.undo("Reset scales");
            scalesReset.addActionListener(e -> scalesField.setText(formatScales(defaultScales())));
            final JButton update = new JButton("Update");
            updateButton = update;
            update.setFocusable(false);
            update.setEnabled(false);
            update.setToolTipText("Re-learns from the examples using the scales and background above");
            update.addActionListener(e -> {
                update.setEnabled(false); // Until the window is updated
                final double[] sc = parseScales(scalesField.getText());
                double[] arg = null;
                if (sc != null && !sameScales(sc, config.getScales()))
                    arg = sameScales(sc, defaultScales()) ? new double[0] : sc;
                changeBackground(selectedBackground(), val(bgWeightSpin), arg);
            });
        }

        private void fillScales() {
            scalesModel.setRowCount(0);
            for (final TipDetectionConfig.ScaleResponse r : config.getScaleResponses()) {
                scalesModel.addRow(new String[]{voxelsOnly(r.scale()), fmt(r.nodeMedian()), fmt(r.nodeP10()),
                        fmt(r.backgroundP50()), fmt(r.backgroundP99()), fmt(r.contrast())});
            }
        }

        // Label, spinner, reset button and (optionally) a note
        private JPanel paramRow(final String name, final JSpinner spinner, final Runnable reset, final JLabel note) {
            return paramRow(name, spinner, reset, note, WIDEST_LABEL);
        }

        // Label in a column as wide as the one of the Advanced panel, so that the controls of its rows line up
        private JPanel labeledRow(final String text, final JPanel controls) {
            final JPanel p = new JPanel(new BorderLayout(6, 0));
            final JLabel l = new JLabel(text);
            l.setPreferredSize(new Dimension(l.getFontMetrics(l.getFont()).stringWidth(WIDEST_LABEL) + 4,
                    l.getPreferredSize().height));
            p.add(l, BorderLayout.WEST);
            p.add(controls, BorderLayout.CENTER);
            return p;
        }

        // Two rows side by side
        private JPanel pair(final JPanel left, final JPanel right) {
            final JPanel p = new JPanel();
            p.setLayout(new javax.swing.BoxLayout(p, javax.swing.BoxLayout.X_AXIS));
            p.add(left);
            p.add(javax.swing.Box.createHorizontalStrut(PAIR_GAP));
            p.add(right);
            p.add(javax.swing.Box.createHorizontalGlue());
            return p;
        }

        // widest: text that sets the width of the label column
        private JPanel paramRow(final String name, final JSpinner spinner, final Runnable reset, final JLabel note,
                                final String widest) {
            final JPanel p = new JPanel(new BorderLayout(6, 0));
            final JLabel l = new JLabel(name + ":");
            l.setPreferredSize(new Dimension(l.getFontMetrics(l.getFont()).stringWidth(widest) + 4,
                    l.getPreferredSize().height));
            final JPanel center = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            center.add(spinner);
            final JButton resetButton = GuiUtils.Buttons.undo("Reset " + name.toLowerCase());
            resetButton.addActionListener(e -> reset.run());
            center.add(resetButton);
            if (note != null) center.add(note);
            p.add(l, BorderLayout.WEST);
            p.add(center, BorderLayout.CENTER);
            return p;
        }

        // Resolution level and empty block settings, used by whole volume runs only
        private CollapsiblePanel performancePanel() {
            final int rec = recommendedLevel(config, levels);
            final String[] labels = new String[levels.size()];
            for (int i = 0; i < labels.length; i++)
                labels[i] = describeLevel(levels.get(i)) + ((i == rec) ? " (recommended)" : "");
            levelChoice = new JComboBox<>(labels);
            levelChoice.setSelectedIndex(rec);
            levelChoice.setFocusable(false);
            levelChoice.setPrototypeDisplayValue("Level 0: 00000 x 00000 x 00000 voxels (0.000 x 0.000 x 0.000)");
            final JLabel warn = new JLabel(" ");
            warn.setForeground(WizardSupport.WORSE_COLOR);
            levelChoice.addActionListener(e -> warn.setText((levelChoice.getSelectedIndex() > rec)
                    ? "Thin axons may be lost at this level" : " "));
            skipEmpty = new JCheckBox("Skip empty blocks", true);
            skipEmpty.setFocusable(false);
            madSpin = GuiUtils.Fields.doubleSpinner(2, 0.5, 20, 0.5, 1);
            skipEmpty.addActionListener(e -> madSpin.setEnabled(skipEmpty.isSelected()));
            final JPanel panel = new JPanel(new GridLayout(0, 1));
            final JPanel levelRow = new JPanel(new BorderLayout(6, 0));
            final JLabel levelLabel = new JLabel("Level:");
            levelRow.add(levelLabel, BorderLayout.WEST);
            levelRow.add(levelChoice, BorderLayout.CENTER);
            panel.add(levelRow);
            panel.add(warn);
            final JPanel skipPanel = new JPanel(new GridBagLayout()); // Centers the checkbox vertically
            // Left edge of the checkbox: same as the one of the level choice (label width + gap of levelRow)
            skipPanel.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, levelLabel.getPreferredSize().width + 6,
                    0, 0));
            skipPanel.add(skipEmpty);
            panel.add(pair(skipPanel, paramRow("Signal (MAD)", madSpin, () -> madSpin.setValue(2d), null,
                    "Signal (MAD):")));
            final CollapsiblePanel cp = new CollapsiblePanel("Performance", panel, true);
            cp.setTooltipText("Whole volume runs only: resolution level to process, and skipping of blocks "
                    + "without signal (assessed at a coarser level)");
            return cp;
        }

        ParseOptions parseOptions() {
            if (levelChoice == null) return null;
            return new ParseOptions(levelChoice.getSelectedIndex(), skipEmpty.isSelected(), val(madSpin));
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
        private void setupBackground() {
            bgChoice.addItem("None");
            for (int ch = 1; ch <= snt.getChannelCount(); ch++) {
                if (ch == snt.getChannel()) continue;
                bgChannels.add(ch);
                bgChoice.addItem("Channel " + ch);
            }
            bgChoice.setSelectedIndex(bgChannels.indexOf(bgChannel) + 1);
            bgChoice.setFocusable(false);
            bgWeightSpin.addChangeListener(e -> syncUpdateButton());
            bgChoice.addActionListener(e -> syncUpdateButton());
            weightReset = GuiUtils.Buttons.undo("Reset weight");
            weightReset.addActionListener(e -> bgWeightSpin.setValue(initBgWeight));
        }

        private JButton resetButton(final String what, final Runnable action) {
            final JButton b = GuiUtils.Buttons.undo("Reset " + what);
            b.addActionListener(e -> action.run());
            return b;
        }

        private void put(final JPanel panel, final Component comp, final int x, final int y, final int width,
                         final int anchor, final int fill) {
            final GridBagConstraints k = new GridBagConstraints();
            k.gridx = x;
            k.gridy = y;
            k.gridwidth = width;
            k.anchor = anchor;
            k.fill = fill;
            k.insets = new Insets(2, 2, 2, 2);
            panel.add(comp, k);
        }

        // Advanced settings on a grid, so that the controls line up. Update applies to scales and background
        // (and re-learns the veto), so it sits on its own at the bottom right
        private JPanel advancedGrid() {
            setupScales();
            final boolean multi = snt.getChannelCount() > 1;
            if (multi) setupBackground();
            final JPanel g = new JPanel(new GridBagLayout());
            final int w = GridBagConstraints.WEST;
            final int e = GridBagConstraints.EAST;
            final int none = GridBagConstraints.NONE;
            put(g, new JLabel("Min. extent:"), 0, 0, 1, w, none);
            put(g, extentSpin, 1, 0, 1, w, none);
            put(g, resetButton("min. extent", () -> setIfValid(extentSpin, initExtent)), 2, 0, 1, w, none);
            put(g, new JLabel("Min. purity:"), 3, 0, 1, e, none);
            put(g, puritySpin, 4, 0, 1, w, none);
            put(g, resetButton("min. purity", () -> setIfValid(puritySpin, initPurity)), 5, 0, 1, w, none);
            put(g, new JLabel("Scales [" + unit + "]:"), 0, 1, 1, w, none);
            put(g, scalesField, 1, 1, 3, w, GridBagConstraints.HORIZONTAL);
            put(g, scalesReset, 4, 1, 1, w, none);
            int y = 2;
            if (multi) {
                put(g, new JLabel(WIDEST_LABEL), 0, y, 1, w, none);
                put(g, bgChoice, 1, y, 1, w, none);
                put(g, new JLabel("weight"), 2, y, 1, e, none);
                put(g, bgWeightSpin, 3, y, 1, e, none);
                put(g, weightReset, 4, y, 1, w, none);
                y++;
                put(g, new JLabel("Veto (background):"), 0, y, 1, w, none);
                put(g, vetoSpin, 1, y, 1, w, none);
                put(g, resetButton("veto", () -> vetoSpin.setValue(0d)), 2, y, 1, w, none);
                put(g, updateButton, 4, y, 2, e, none); // On the veto row: no empty row below
            } else {
                put(g, updateButton, 4, y, 2, e, none);
            }
            return g;
        }

        // Stop gap for occasional painting glitches (e.g., after collapsing panels quickly)
        private void rebuildControls() {
            if (southPanel == null) return;
            // Painting is reliable in the startup configuration: Detection expanded, all others collapsed
            for (final CollapsiblePanel cp : collapsibles) cp.setCollapsed(cp != detectionCollapsible);
            repaintTree(southPanel);
            southPanel.invalidate();
            southPanel.validate();
            pack();
            southPanel.repaint();
        }

        private void repaintTree(final Component comp) {
            if (comp instanceof javax.swing.JComponent jc) {
                jc.revalidate();
                jc.repaint();
            }
            if (comp instanceof Container ct) {
                for (final Component child : ct.getComponents()) repaintTree(child);
            }
        }

        private JPanel buttonPanel() {
            final JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT));
            p.setBackground(super.getBackground());

            final JPopupMenu applyMenu = new JPopupMenu();
            final JMenuItem regionItem = new JMenuItem("Detect Tips on Preview Region (\"Dry Run\")");
            regionItem.addActionListener(e -> commit(false));
            final JMenuItem wholeItem = new JMenuItem("Detect Tips on Whole Volume");
            wholeItem.addActionListener(e -> commit(true));
            final JMenuItem export = new JMenuItem("Export Recipe...", IconFactory.menuIcon(IconFactory.GLYPH.CODE));
            export.addActionListener(e -> exportRecipe());
            GuiUtils.MenuItems.addSeparator(applyMenu, "Run:");
            applyMenu.add(regionItem);
            applyMenu.add(wholeItem);
            GuiUtils.MenuItems.addSeparator(applyMenu, "Batch:");
            applyMenu.add(export);
            final JButton apply = GuiUtils.Buttons.OptionsButton("Apply/Run", applyMenu, true);
            apply.setFocusable(false);

            final JButton more = paletteOptions.buildMoreSwingButton(this, helpHtml(), pm -> {
                final JMenuItem rebuild = new JMenuItem("Rebuild Dialog", IconFactory.menuIcon(IconFactory.GLYPH.RECYCLE));
                rebuild.addActionListener(e -> rebuildControls());
                pm.add(rebuild);
            });
            more.setFocusable(false);

            p.add(apply);
            p.add(more);
            return p;
        }

        private void changed() {
            updateLabels();
            scheduleEvaluation();
        }

        private void updateLabels() {
            final double avgSpacing = (spacing[0] + spacing[1] + spacing[2]) / 3;
            radiusLabel.setText(String.format("(%.1f vox)", config.getTipRadius() / avgSpacing));
        }

        // Called on the EDT after each evaluation
        void refresh() {
            updateLabels();
            updateStatus();
            learnedModel.setRowCount(0);
            learnedModel.addRow(new String[]{"Example paths (nodes, tips)",
                    paths.size() + " (" + example.nodes().length + ", " + example.tips().length + ")"});
            learnedModel.addRow(new String[]{"Radius (min / median / max) [" + unit + "]", voxelsOnly(config.getMinRadius()) + " / "
                    + voxelsOnly(config.getMedianRadius()) + " / " + voxelsOnly(config.getMaxRadius())});
            final StringBuilder scales = new StringBuilder();
            for (final double s : config.getScales()) scales.append(voxelsOnly(s)).append("; ");
            learnedModel.addRow(new String[]{"Scales [" + unit + "]", scales.toString()});
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
            sb.append(String.format("%d detections", ev.tips().size()));
            final int sel = selected;
            if (sel >= 0 && sel < ev.tips().size())
                sb.append(String.format(" | selected: score %s", fmt(ev.tips().get(sel).score())));
            else if (!ev.tips().isEmpty())
                sb.append(" | click a detection to see its analysis sphere");
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
                    + "<li><b>Dots</b> are detections. Click a detection to see the sphere (dashed) in which it "
                    + "was analyzed</li>"
                    + "<li><b>Threshold</b>: score above which voxels are considered to be part of a structure. "
                    + "Learned from the scores along the examples</li>"
                    + "<li><b>Tip radius</b>: size of the neighborhood analyzed around each candidate (about 3x the "
                    + "diameter of the thickest structures)</li>"
                    + "<li><b>Min. extent</b>: how far (as a fraction of the radius) the structure connected to a "
                    + "candidate must reach. Rejects speckles</li>"
                    + "<li><b>Min. purity</b>: fraction of the voxels in the neighborhood that must be connected "
                    + "to the candidate. Rejects clutter</li>"
                    + "<li><b>Performance</b>: resolution level and skipping of empty blocks (whole volume runs "
                    + "only)</li>"
                    + "<li>Buttons next to each value restore the value learned from the examples</li>"
                    + "<li><i>Extra</i> detections are not necessarily false: the examples are only a sample of the "
                    + "structures in the region</li>"
                    + "</ul></div>";
        }
    }
}
