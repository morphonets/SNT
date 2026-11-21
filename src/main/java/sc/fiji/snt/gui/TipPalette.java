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

import ij.ImagePlus;
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
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.filter.Tubeness;
import sc.fiji.snt.tracing.auto.AutoTraceConfig;
import sc.fiji.snt.tracing.auto.SavedTipDetection;
import sc.fiji.snt.tracing.auto.TipDetectionConfig;
import sc.fiji.snt.tracing.auto.TipTrainingPaths;
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
public class TipPalette extends WizardPalette {

    /** Receives the outcome of the wizard */
    public interface Listener {
        /**
         * Called when the user accepts the parameters
         *
         * @param config      the (possibly adjusted) parameters
         * @param wholeVolume true to detect in the whole volume, false to detect in the preview region (a dry
         *                    run)
         * @param options     resolution level, empty block and crop settings of whole volume runs. Null if not
         *                    applicable (e.g., no image pyramid)
         * @param previewBox  {min, max} (voxels, inclusive, in the grid of the image) of the preview region. Only
         *                    for dry runs: null otherwise
         */
        void tipConfigPicked(TipDetectionConfig config, boolean wholeVolume, ParseOptions options,
                             long[][] previewBox);

        /** Called when the wizard is closed */
        default void dismissed() {
        }
    }

    /**
     * Settings of whole volume runs
     *
     * @param level     the resolution level (0: full resolution)
     * @param skipEmpty whether blocks without signal (assessed at a coarser level) are skipped
     * @param deviationFactor the number of median absolute deviations above the median defining signal
     * @param restrictToCrop whether the run is restricted to the footprint of the materialized crop (Stream mode)
     */
    public record ParseOptions(int level, boolean skipEmpty, double deviationFactor, boolean restrictToCrop) {
    }

    private static final double MIN_SCALE_VOXELS = 0.7;
    private static final int PAIR_GAP = 16;
    // Names of the parameters (ASCII source: sigma is a unicode escape)
    private static final String L_SCORE = "Min. Tubeness score";
    private static final String L_RADIUS = "Analysis radius";
    private static final String L_LENGTH = "Min. length";
    private static final String L_CONNECTED = "Min. connected fraction";
    private static final String L_BACKGROUND = "Max. background";
    private static final String L_RADII = "Neurite radii (\u03c3)";
    private static final String L_EMPTY = "Skip blocks darker than background + k x absolute deviation, with k";
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
        for (final double sc : config.getNeuriteRadii()) minScale = Math.min(minScale, sc);
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

    private final List<Path> paths;
    private final List<Listener> listeners = new ArrayList<>();

    private double[] spacing;
    private String unit;
    private WizardUtils.Region region;
    private TipDetectionConfig.TrainingPaths example;
    private TipDetectionConfig config;
    private RandomAccessibleInterval<FloatType> ridge;
    private volatile TipDetectionConfig.Evaluation evaluation;
    private volatile int selected = -1;
    private String failure;
    private int bgChannel = 0; // 1-based. 0: none
    private double bgWeight = 1;
    private RandomAccessibleInterval<FloatType> backgroundRegion; // Raw background of the region, if any
    private double maxBackgroundSuggested = Double.NaN; // Learned max. background. The test is off until the user enables it
    private double[] customScales; // User-defined scales (calibrated units). Null: learned from the examples
    private double fixedPad = Double.NaN; // calibrated padding of the first read, reused when the background changes

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
        super(snt);
        this.paths = new ArrayList<>(paths);
        setParent(snt.getUI());
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
        if (paths.isEmpty()) return "No training paths: trace, or select in the Path Manager, a few representative "
                + "axons (traced towards their tips), and re-launch the wizard.";
        // Unset (non-positive) channel/frame values match anything
        final java.util.Set<String> ct = new java.util.TreeSet<>();
        for (final Path p : paths) {
            if (p.getChannel() > 0 && p.getFrame() > 0)
                ct.add("channel " + p.getChannel() + ", frame " + p.getFrame());
        }
        if (ct.size() > 1) return "The training paths belong to different channels/frames (" + String.join("; ", ct)
                + "). Select paths from a single channel and frame.";
        for (final Path p : paths) {
            if ((p.getChannel() > 0 && p.getChannel() != snt.getChannel())
                    || (p.getFrame() > 0 && p.getFrame() != snt.getFrame()))
                return "The training paths were traced in channel " + p.getChannel() + ", frame " + p.getFrame()
                        + " but SNT is showing channel " + snt.getChannel() + ", frame " + snt.getFrame()
                        + ". Switch to the paths' channel/frame and re-launch the wizard.";
        }
        // All nodes must lie within the image: padding would otherwise hide paths that are outside
        final double[] sp = {snt.getPixelWidth(), snt.getPixelHeight(), snt.getPixelDepth()};
        final PointInCanvas off = snt.getDefaultCanvasPixelOffset();
        final double inside = AutoTraceConfig.fractionInBounds(paths, snt.getFullSourceData(), sp,
                new double[]{off.x, off.y, off.z});
        if (!(inside >= 1d))
            return WizardUtils.outsideDataMessage(snt, "Some training paths lie",
                    " (" + Math.round((1 - inside) * 100) + "% of nodes)") + " Select paths within the image.";
        return null;
    }

    public void show() {
        start();
    }

    @Override
    protected void build() {
        try {
            if (!learn()) {
                final String msg = (failure != null) ? failure : "The Tip Detection Wizard could not be created.";
                if (snt.getUI() != null) snt.getUI().error(msg);
                SNTUtils.log("Tip wizard failed: " + msg);
                dismiss();
                return;
            }
            WizardUtils.status(snt, "Tip wizard: preparing preview...");
            buildAndDisplay();
        } catch (final Throwable t) {
            SNTUtils.error(t);
            SNTUtils.log("Tip wizard failed: " + t.getMessage());
            dismiss();
        }
    }

    // Reads the region spanned by the examples, learns from them and evaluates the result. False on failure
    private boolean learn() {
        final RandomAccessibleInterval<?> source = WizardUtils.as3D(snt.getFullSourceData());
        spacing = new double[]{snt.getPixelWidth(), snt.getPixelHeight(), snt.getPixelDepth()};
        unit = snt.getSpacingUnits();
        final PointInCanvas offset = snt.getDefaultCanvasPixelOffset();
        final double[] voxelOffset = new double[]{offset.x, offset.y, offset.z};

        final long[][] bounds = TipTrainingPaths.voxelBounds(paths, spacing, voxelOffset);
        if (bounds == null) {
            failure = "The training paths have no nodes.";
            return false;
        }
        final double inside = AutoTraceConfig.fractionInBounds(paths, source, spacing, voxelOffset);
        if (!(inside > 0.9)) {
            failure = WizardUtils.outsideDataMessage(snt, "The training paths lie", "");
            return false;
        }

        // The region is padded by the analysis radius, as tips closer than that to its border cannot be evaluated.
        // The radius is only known once the examples have been analyzed: if the first guess is too small, read again
        double padCalibrated = Double.isNaN(fixedPad) ? estimatePadding() : fixedPad;
        for (int attempt = 0; attempt < 2; attempt++) {
            final long[] pad = new long[3];
            for (int d = 0; d < 3; d++) pad[d] = (long) Math.ceil(padCalibrated / spacing[d]) + MIN_PAD_VOXELS;
            final long[][] padded = WizardUtils.padAndClamp(bounds, pad, source);
            failure = WizardUtils.checkRegionSize(padded, spacing, unit, MAX_VOXELS_PER_AXIS, MAX_VOXELS);
            if (failure != null) return false;
            WizardUtils.status(snt, "Tip wizard: reading region...");
            region = WizardUtils.readRegion(source, padded);
            if (!subtractBackground(region, padded)) return false;

            // The region is the same when the background changes: the examples (radii, nodes, tips) are kept, so that
            // the scales do not vary from one update to the next
            if (example == null || Double.isNaN(fixedPad) || attempt > 0) {
                final double[] regionOffset = new double[3];
                for (int d = 0; d < 3; d++) regionOffset[d] = voxelOffset[d] - region.min()[d];
                WizardUtils.status(snt, "Tip wizard: analyzing training paths...");
                example = TipTrainingPaths.fromPaths(paths, region.data(), spacing, regionOffset);
            }
            WizardUtils.status(snt, "Tip wizard: computing score maps...");
            config = TipDetectionConfig.learn(example, spacing, (customScales != null) ? customScales
                    : TipTrainingPaths.scales(example.radii()),
                    this::scoreMap, backgroundRegion);
            if (config.getAnalysisRadius() > padCalibrated && attempt == 0 && Double.isNaN(fixedPad)) {
                padCalibrated = config.getAnalysisRadius();
                continue;
            }
            break;
        }
        fixedPad = padCalibrated;
        SNTUtils.log(String.format("Tip wizard: background channel %d (weight %.2f), scales %s, threshold %s",
                bgChannel, bgWeight, java.util.Arrays.toString(config.getNeuriteRadii()), config.getMinScore()));
        config.setBackground(bgChannel, bgWeight);
        maxBackgroundSuggested = config.getMaxBackground();
        config.setMaxBackground(Double.NaN); // Off
        ridge = scoreMap(config.getNeuriteRadii());
        WizardUtils.status(snt, "Tip wizard: detecting tips...");
        evaluation = config.evaluate(ridge, example, backgroundRegion);
        return true;
    }

    // Subtracts the weighted background channel from the signal (in place), clamping to zero
    private boolean subtractBackground(final WizardUtils.Region signal, final long[][] box) {
        backgroundRegion = null;
        if (bgChannel <= 0) return true;
        final RandomAccessibleInterval<?> bgSource = snt.getFullSourceData(bgChannel);
        if (bgSource == null) {
            failure = "Channel " + bgChannel + " could not be read (does it have the same dimensions as the "
                    + "active channel?).";
            return false;
        }
        WizardUtils.status(snt, "Tip wizard: reading background...");
        final WizardUtils.Region bg = WizardUtils.readRegion(bgSource, box);
        backgroundRegion = bg.data(); // Raw: read before subtracting
        final float w = (float) bgWeight;
        LoopBuilder.setImages(signal.data(), bg.data()).multiThreaded()
                .forEachPixel((s, b) -> s.set(Math.max(0f, s.get() - w * b.get())));
        return true;
    }

    // Scales derived from the radii of the examples
    private double[] defaultScales() {
        return TipTrainingPaths.scales(example.radii());
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
            final double previousMaxBackground = config.getMaxBackground();
            // Not affected by the background or the scales: kept as set by the user
            final double previousRadius = config.getAnalysisRadius();
            final double previousMinLength = config.getMinLength();
            final double previousMinConnectedFraction = config.getMinConnectedFraction();
            final int previousChannel = bgChannel;
            bgChannel = channel;
            bgWeight = weight;
            WizardUtils.busy(snt, () -> {
                if (!learn()) {
                    final String msg = (failure != null) ? failure : "Tip detection could not be updated.";
                    if (snt.getUI() != null) snt.getUI().error(msg);
                    failure = null;
                    bgChannel = 0;
                    learn(); // Back to the signal alone
                }
                if (!Double.isNaN(previousRadius)) config.setAnalysisRadius(previousRadius);
                config.setMinLength(previousMinLength);
                config.setMinConnectedFraction(previousMinConnectedFraction);
                // Same background: max. background stays as the user set it
                if (!Double.isNaN(previousMaxBackground) && backgroundRegion != null && bgChannel == previousChannel)
                    config.setMaxBackground(previousMaxBackground);
                evaluation = config.evaluate(ridge, example, backgroundRegion);
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
        return (maxRadius > 0) ? 6 * maxRadius : 10 * avgSpacing();
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
        paletteOptions = new PaletteOptions(paletteImage, null, "TipDetection_Snapshot");
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
                final TipDetectionConfig.Evaluation ev = config.evaluate(ridge, example, backgroundRegion);
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
        values.put("DEVIATION_FACTOR", num((opts == null) ? 2d : opts.deviationFactor()));
        values.put("NEURITE_RADII", java.util.Arrays.stream(config.getNeuriteRadii()).mapToObj(TipPalette::num)
                .collect(java.util.stream.Collectors.joining(", ")));
        values.put("ANALYSIS_RADIUS", num(config.getAnalysisRadius()));
        values.put("MIN_SCORE", num(config.getMinScore()));
        values.put("MIN_LENGTH", num(config.getMinLength()));
        values.put("MIN_CONNECTED_FRACTION", num(config.getMinConnectedFraction()));
        values.put("MAX_BACKGROUND", num(config.getMaxBackground()));
        values.put("BACKGROUND_RADIUS", num(config.getBackgroundRadius()));
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

    // Saves the parameters in the tip detection folder of the workspace, so that the run can be repeated without the
    // training paths (see TipDetectionSavedCmd)
    private void saveParameters() {
        try {
            final java.io.File workspace = (snt.getUI() == null) ? null : snt.getUI().getOrPromptForWorkspace();
            final java.io.File dir = (workspace == null) ? null : snt.getPrefs().getTipDetectionDir();
            if (dir == null) return;
            ParseOptions o = null;
            try {
                if (window != null) o = window.parseOptions();
            } catch (final RuntimeException ignored) {
                // Defaults below
            }
            if (o == null) o = new ParseOptions(0, false, 2d, false);
            final java.io.File f = SavedTipDetection.save(dir, snt.getImageFilenamePrefix(), snt.getChannel(),
                    o.level(), o.skipEmpty(), o.deviationFactor(), config);
            SNTUtils.log("Tip detection parameters saved to " + f);
        } catch (final java.io.IOException | RuntimeException ex) {
            SNTUtils.error("Tip detection parameters could not be saved", ex);
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
            saveParameters();
            dismiss(); // Clears the listeners, so use a copy
        }
        final long[][] box = (wholeVolume || region == null) ? null : new long[][]{region.min().clone(),
                {region.min()[0] + region.data().dimension(0) - 1, region.min()[1] + region.data().dimension(1) - 1,
                        region.min()[2] + region.data().dimension(2) - 1}};
        copy.forEach(l -> l.tipConfigPicked(config, wholeVolume, finalOptions, box));
    }

    @Override
    protected void beforeDismiss() {
        generation.incrementAndGet();
        worker.shutdownNow();
    }

    @Override
    protected void notifyDismissed() {
        new ArrayList<>(listeners).forEach(Listener::dismissed);
    }

    @Override
    protected StackWindow windowToClose() {
        final TipWindow w = window;
        window = null; // A closing window must not be found again
        return w;
    }

    @Override
    protected void afterDismiss() {
        region = null;
        ridge = null;
    }

    @Override
    protected void clearListeners() {
        listeners.clear();
    }

    private static String fmt(final double v) {
        return Double.isNaN(v) ? "n/a" : String.format("%.3g", v);
    }

    // Value in calibrated units (unit shown in the label of the row), with its size in voxels
    private String voxelsOnly(final double calibrated) {
        if (Double.isNaN(calibrated)) return "n/a";
        return String.format("%.3g (%.1f vox)", calibrated, calibrated / avgSpacing());
    }

    private double avgSpacing() {
        return (spacing[0] + spacing[1] + spacing[2]) / 3;
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
                final double r2 = config.getAnalysisRadius() * config.getAnalysisRadius() - dz * dz;
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
    private class TipWindow extends PaletteWindow {
        private static final long serialVersionUID = 1L;
        private final JSpinner thresholdSpin = GuiUtils.Fields.doubleSpinner(1, 0, 1e12, 1, 3, true);
        private final JSpinner radiusSpin = GuiUtils.Fields.doubleSpinner(1, 0, 1e9, 1, 1, true);
        private final JSpinner minLengthSpin = GuiUtils.Fields.doubleSpinner(0, 0, 1, 0.05, 2);
        private final JSpinner minConnectedFractionSpin = GuiUtils.Fields.doubleSpinner(0, 0, 1, 0.05, 2);
        private final JSpinner maxBackgroundSpin = GuiUtils.Fields.doubleSpinner(0, 0, 1e12, 1, 3, true);
        private final JSpinner bgWeightSpin = GuiUtils.Fields.doubleSpinner(1, 0, WEIGHT_MAX, 0.05, 2);
        private final JTextField scalesField = new JTextField(18);
        private final JLabel statusLabel = new JLabel(" ");
        private final DefaultTableModel learnedModel;
        private final DefaultTableModel scalesModel = new DefaultTableModel(new String[]{"Radius (\u03c3) [" + unit + "]", "Nodes (median)",
                "Nodes (P10)", "Background (P50)", "Background (P99)", "Contrast"}, 0) {
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
        private final Panel southPanel;
        private final CollapsiblePanel detectionCollapsible;
        private final CollapsiblePanel advancedCollapsible;
        private final CollapsiblePanel performanceCollapsible;
        private final CollapsiblePanel detailsCollapsible;
        private JButton applyButton;
        private JButton moreButton;
        private final List<CollapsiblePanel> collapsibles = new ArrayList<>();
        private JButton scalesReset;
        private JButton weightReset;
        private final java.util.List<Integer> bgChannels = new ArrayList<>();
        // Values displayed when the controls were (re)loaded: reset buttons restore them
        private double initThreshold;
        private double initRadius;
        private double initMinLength;
        private double initMinConnectedFraction;
        private final double initBgWeight = bgWeight;
        // Whole volume settings (Stream mode)
        private final List<BigDataUtils.LevelInfo> levels = streamLevels();
        private JComboBox<String> levelChoice;
        private JCheckBox skipEmpty;
        private JSpinner madSpin;

        TipWindow(final ImagePlus imp, final ImageCanvas ic) {
            super(imp, ic, TipPalette.this::dismiss);
            GuiUtils.zoomToScreenFraction(getCanvas(), 0.55, 8);
            bgWeightSpin.setValue(bgWeight);
            loadSpinners();
            thresholdSpin.addChangeListener(e -> {
                if (loading) return;
                config.setMinScore(val(thresholdSpin));
                changed();
            });
            radiusSpin.addChangeListener(e -> {
                if (loading) return;
                config.setAnalysisRadius(val(radiusSpin));
                changed();
            });
            minLengthSpin.addChangeListener(e -> {
                if (loading) return;
                config.setMinLength(val(minLengthSpin));
                changed();
            });
            minConnectedFractionSpin.addChangeListener(e -> {
                if (loading) return;
                config.setMinConnectedFraction(val(minConnectedFractionSpin));
                changed();
            });
            maxBackgroundSpin.addChangeListener(e -> {
                if (loading) return;
                final double v = val(maxBackgroundSpin);
                config.setMaxBackground((v <= 0) ? Double.NaN : v); // 0: off (n/a)
                changed();
            });

            tip(thresholdSpin, "Tubeness (ridge) score above which voxels are considered part of a structure.<br>"
                    + "Learned as the 10th percentile of the scores along the training paths");
            tip(radiusSpin, "Radius of the sphere analyzed around each candidate (about 3x the diameter of the "
                    + "thickest neurites).<br>Also the min. distance between tips, and the margin to the volume faces "
                    + "where tips are not sought");
            updateLengthTip();
            tip(minConnectedFractionSpin, "Fraction of the structure voxels in the sphere that must be connected to the candidate."
                    + "<br>Low values mean the candidate sits in clutter");
            tip(maxBackgroundSpin, "Candidates over background brighter than this are rejected (n/a: off).<br>"
                    + "In the intensity units of the background channel");
            tip(bgWeightSpin, "Weight of the background channel: signal - weight x background");

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
            warnings.setMargin(new java.awt.Insets(8, 0, 0, 8)); // gap from top/right. Align w/ status label on left
            warnings.setForeground(WizardUtils.WORSE_COLOR);
            warnings.setVisible(!config.getWarnings().isEmpty());
            final JPanel detection = new JPanel(new GridBagLayout());
            final GridBagConstraints dc = new GridBagConstraints();
            dc.fill = GridBagConstraints.HORIZONTAL;
            dc.weightx = 1.0;
            dc.gridx = 0;
            dc.gridy = 0;
            detection.add(pair(paramRow(L_SCORE, thresholdSpin, () -> setIfValid(thresholdSpin, initThreshold),
                            null, L_SCORE + ":"),
                    paramRow(L_RADIUS + " [" + unit + "]", radiusSpin, () -> setIfValid(radiusSpin, initRadius),
                            null, L_RADIUS + " [" + unit + "]:")), dc);
            dc.gridy = 1;
            detection.add(statusLabel, dc);
            dc.gridy = 2;
            detection.add(warnings, dc);
            c.gridy = row++;
            final CollapsiblePanel detectionPanel = new CollapsiblePanel("Detection", detection, false);
            detectionPanel.setTooltipText("Tubeness score and analysis radius, number of detections and warnings");
            root.add(section(null, detectionPanel), c);

            // Expert settings, collapsed by default
            final JPanel advanced = advancedGrid();
            final CollapsiblePanel advancedPanel = new CollapsiblePanel("Advanced", advanced, true);
            advancedPanel.setTooltipText("Extra filters (min. length and connected fraction), neurite radii, and the "
                    + "background channel (subtraction and maximum). The maximum background is off (n/a) at 0");
            c.gridy = row++;
            root.add(section(null, advancedPanel), c);

            CollapsiblePanel performance = null;
            if (levels.size() > 1) {
                performance = performancePanel();
                c.gridy = row++;
                root.add(section(null, performance), c);
            }

            learnedModel = new DefaultTableModel(new String[]{"Learned from paths", "Value"}, 0) {
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
            detailsPanel.setTooltipText("Parameters learned from the training paths, and the response of each scale");
            c.gridy = row++;
            root.add(section(null, detailsPanel), c);

            detectionCollapsible = detectionPanel;
            advancedCollapsible = advancedPanel;
            performanceCollapsible = performance;
            detailsCollapsible = detailsPanel;
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
            fitCanvasToPanel(south);
            add(south);
            pack();
            WizardUtils.place(this, parent, snt);
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

        // Spinner that shows n/a for 0 (max. background off)
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
                final double thr = config.getMinScore();
                configure(thresholdSpin, thr, 0, 1e12, Double.isNaN(thr) ? 1 : thr / 100, decimalsFor(thr));
                configure(radiusSpin, config.getAnalysisRadius(), minSpacing, 1e9, minSpacing, 1);
                configure(minLengthSpin, config.getMinLength(), 0, 1, 0.05, 2);
                configure(minConnectedFractionSpin, config.getMinConnectedFraction(), 0, 1, 0.05, 2);
                final boolean available = backgroundRegion != null && !Double.isNaN(maxBackgroundSuggested) && maxBackgroundSuggested > 0;
                final double max = (available) ? maxBackgroundSuggested * 20 : 1;
                final double maxBackground = Double.isNaN(config.getMaxBackground()) ? 0 : config.getMaxBackground();
                final SpinnerNumberModel m = (SpinnerNumberModel) maxBackgroundSpin.getModel();
                m.setMinimum(0d);
                m.setMaximum(max);
                m.setStepSize((available) ? maxBackgroundSuggested / 10 : 1);
                m.setValue(Math.clamp(maxBackground, 0, max));
                final String pattern = patternOf(decimalsFor((available) ? maxBackgroundSuggested : Double.NaN));
                final JSpinner.NumberEditor ed = new JSpinner.NumberEditor(maxBackgroundSpin, pattern);
                final NumberFormatter nf = new NumberFormatter(new OffFormat(pattern));
                nf.setValueClass(Double.class);
                nf.setMinimum(0d);
                nf.setMaximum(max);
                ed.getTextField().setFormatterFactory(new DefaultFormatterFactory(nf));
                ed.getTextField().setColumns(8);
                maxBackgroundSpin.setEditor(ed);
                maxBackgroundSpin.setEnabled(available);
                initThreshold = config.getMinScore();
                initRadius = config.getAnalysisRadius();
                initMinLength = config.getMinLength();
                initMinConnectedFraction = config.getMinConnectedFraction();
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
            scalesField.setText(formatScales(config.getNeuriteRadii()));
            syncUpdateButton();
            refresh();
        }

        private int selectedBackground() {
            final int idx = bgChoice.getSelectedIndex();
            if (bgChoice.getItemCount() == 0) return bgChannel; // Single channel
            return (idx <= 0) ? 0 : bgChannels.get(idx - 1);
        }

        // Re-learn only has an effect if the background channel, its weight or the scales differ from the ones in use
        private void syncUpdateButton() {
            final double[] sc = parseScales(scalesField.getText());
            scalesField.setForeground((sc == null) ? GuiUtils.Colors.errorColor()
                    : UIManager.getColor("TextField.foreground"));
            if (updateButton == null) return;
            final int channel = selectedBackground();
            final boolean bgDiff = channel != bgChannel
                    || (channel > 0 && Math.abs(val(bgWeightSpin) - bgWeight) > 1e-3);
            final boolean scalesDiff = sc != null && !sameScales(sc, config.getNeuriteRadii());
            updateButton.setEnabled(bgDiff || scalesDiff);
        }

        // Neurite radii (editable) and the Re-learn button, which re-learns from the examples
        private void setupScales() {
            scalesField.setText(formatScales(config.getNeuriteRadii()));
            tip(scalesField, "Radii of the neurites to detect (comma-separated, " + unit + "): the scales (sigma) of the "
                    + "Tubeness filter.<br>Press Re-learn to apply");
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
            final JButton update = new JButton("Re-learn");
            updateButton = update;
            update.setFocusable(false);
            update.setEnabled(false);
            update.setToolTipText("<html>Re-learns the Tubeness score from the training paths using the radii and background above.<br>"
                    + "Analysis radius, min. length and min. connected fraction are kept</html>");
            update.addActionListener(e -> {
                update.setEnabled(false); // Until the window is updated
                final double[] sc = parseScales(scalesField.getText());
                double[] arg = null;
                if (sc != null && !sameScales(sc, config.getNeuriteRadii()))
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
            // A materialized crop holds full resolution data: its entry follows the levels
            final boolean cropAvailable = snt.isMaterializedCrop();
            final String[] labels = new String[levels.size() + ((cropAvailable) ? 1 : 0)];
            for (int i = 0; i < levels.size(); i++)
                labels[i] = describeLevel(levels.get(i)) + ((i == rec) ? " (recommended)" : "");
            if (cropAvailable) labels[levels.size()] = "Materialized crop only (level 0)";
            levelChoice = new JComboBox<>(labels);
            levelChoice.setSelectedIndex(rec);
            levelChoice.setFocusable(false);
            levelChoice.setPrototypeDisplayValue("Level 0: 00000 x 00000 x 00000 voxels (0.000 x 0.000 x 0.000)");
            final JLabel warn = new JLabel(" ");
            warn.setForeground(WizardUtils.WORSE_COLOR);
            levelChoice.addActionListener(e -> warn.setText((levelChoice.getSelectedIndex() > rec && levelChoice.getSelectedIndex() < levels.size())
                    ? "Thin axons may be lost at this level" : " "));
            skipEmpty = new JCheckBox("Skip empty blocks", true);
            skipEmpty.setFocusable(false);
            madSpin = GuiUtils.Fields.doubleSpinner(2, 0.5, 20, 0.5, 1);
            skipEmpty.addActionListener(e -> madSpin.setEnabled(skipEmpty.isSelected()));
            tip(levelChoice, "Resolution level to process. Coarser levels are faster but may miss thin axons."
                    + ((cropAvailable) ? "<br>The last option processes only the footprint of the materialized crop, "
                    + "a quick way to test on a subset of the volume" : ""));
            tip(skipEmpty, "Skip blocks without signal, as assessed from a coarser level");
            tip(madSpin, "A block has signal if its coarse level exceeds the median + k times the<br>absolute deviation (a robust standard deviation). Lower k to skip fewer blocks");
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
            panel.add(pair(skipPanel, paramRow(L_EMPTY, madSpin, () -> madSpin.setValue(2d), null,
                    L_EMPTY + ":")));
            final CollapsiblePanel cp = new CollapsiblePanel("Performance", panel, true);
            cp.setTooltipText("Whole volume runs only: resolution level to process, and skipping of blocks "
                    + "without signal (assessed at a coarser level)");
            return cp;
        }

        ParseOptions parseOptions() {
            if (levelChoice == null) return null;
            final boolean crop = levelChoice.getSelectedIndex() >= levels.size();
            return new ParseOptions((crop) ? 0 : levelChoice.getSelectedIndex(), skipEmpty.isSelected(),
                    val(madSpin), crop);
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

        // Background channel and weight: the Re-learn button re-learns from the examples
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

        // Advanced settings on a grid, so that the controls line up. Re-learn applies to scales and background
        // (and re-learns the score), so it sits on its own at the bottom right
        private JPanel advancedGrid() {
            setupScales();
            final boolean multi = snt.getChannelCount() > 1;
            if (multi) setupBackground();
            final JPanel g = new JPanel(new GridBagLayout());
            final int w = GridBagConstraints.WEST;
            final int e = GridBagConstraints.EAST;
            final int none = GridBagConstraints.NONE;
            put(g, new JLabel(L_LENGTH + ":"), 0, 0, 1, w, none);
            put(g, minLengthSpin, 1, 0, 1, w, none);
            put(g, resetButton("min. length", () -> setIfValid(minLengthSpin, initMinLength)), 2, 0, 1, w, none);
            put(g, new JLabel(L_CONNECTED + ":"), 3, 0, 1, e, none);
            put(g, minConnectedFractionSpin, 4, 0, 1, w, none);
            put(g, resetButton("min. connected fraction", () -> setIfValid(minConnectedFractionSpin, initMinConnectedFraction)), 5, 0, 1, w, none);
            put(g, new JLabel(L_RADII + " [" + unit + "]:"), 0, 1, 1, w, none);
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
                put(g, new JLabel(L_BACKGROUND + ":"), 0, y, 1, w, none);
                put(g, maxBackgroundSpin, 1, y, 1, w, none);
                put(g, resetButton("max. background", () -> maxBackgroundSpin.setValue(0d)), 2, y, 1, w, none);
                put(g, updateButton, 4, y, 2, e, none); // On the max. background row: no empty row below
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
            refreshLayout(southPanel);
        }

        /* Starts the tour of the controls, or moves to its next callout if it is already running */
        private void startTour() {
            runTour(this::registerCallouts);
        }

        // Only controls that are always visible are pointed at (panels are described by their headers)
        private void registerCallouts(final String group) {
            detectionCollapsible.setCollapsed(false);
            int order = 0;
            final Component canvas = getCanvas();
            if (canvas.isShowing() && canvas.getWidth() > 0) {
                CalloutManager.add(canvas, new Rectangle(canvas.getWidth() / 2, canvas.getHeight() / 2, 2, 2),
                        CalloutManager.AUTO, "Preview of the region spanned by the training paths.<br>"
                                + "Dots are detections. Click one to see the sphere in which it was analyzed.<br>"
                                + "Scroll through the stack to inspect other slices.", group, order++);
            }
            CalloutManager.add(thresholdSpin, CalloutManager.AUTO, "<b>Min. Tubeness score</b><br>"
                    + "Score above which voxels are considered part of a structure.<br>"
                    + "Learned from the training paths. Lower it to detect more tips, raise it for fewer.<br>"
                    + "The reset button restores the learned value.", group, order++);
            CalloutManager.add(radiusSpin, CalloutManager.AUTO, "<b>Analysis radius</b><br>"
                    + "Radius of the sphere analyzed around each candidate (about 3x the diameter of the<br>"
                    + "thickest neurites). Also the min. distance between tips.", group, order++);
            CalloutManager.add(statusLabel, CalloutManager.AUTO, "<b>Detections</b><br>"
                    + "Number of tips found in the preview. <i>Extra</i> detections are not necessarily false:<br>"
                    + "the training paths are only a sample of the structures in the region.", group, order++);
            CalloutManager.add(advancedCollapsible, CalloutManager.AUTO, "<b>Advanced</b><br>"
                    + "Min. length and min. connected fraction reject speckles and clutter. Neurite radii can<br>"
                    + "be edited (press Re-learn). In multi-channel images, a channel can be subtracted from<br>"
                    + "the signal and used to reject candidates over bright background (max. background).", group, order++);
            if (performanceCollapsible != null) {
                CalloutManager.add(performanceCollapsible, CalloutManager.AUTO, "<b>Performance</b><br>"
                        + "Whole volume runs only: resolution level to process, whether blocks without signal<br>"
                        + "are skipped (assessed at a coarser level), and processing only the materialized crop.", group, order++);
            }
            CalloutManager.add(detailsCollapsible, CalloutManager.AUTO, "<b>Details</b><br>"
                    + "What was learned from the training paths, and the response of each scale.", group, order++);
            CalloutManager.add(applyButton, CalloutManager.AUTO, "<b>Apply/Run</b><br>"
                    + "Detect tips in the preview region (a quick dry run) or in the whole volume.<br>"
                    + "Detections are added to the Seed Manager. Export Recipe... generates a<br>"
                    + "script to apply these parameters to other datasets.", group, order++);
            CalloutManager.add(moreButton, CalloutManager.AUTO, "<b>Actions/Options</b><br>"
                    + "Display options (LUT, MIP overlay, B&amp;C), snapshot, and help.", group, order);
        }

        private JPanel buttonPanel() {
            final JPanel p = newButtonRow();

            final JPopupMenu applyMenu = new JPopupMenu();
            final JMenuItem regionItem = new JMenuItem("Detect Tips in Preview Region (\"Dry Run\")");
            regionItem.addActionListener(e -> commit(false));
            final JMenuItem wholeItem = new JMenuItem("Detect Tips in Whole Volume");
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
            applyButton = apply;

            final JButton more = paletteOptions.getOptionsButton(pm -> {
                paletteOptions.addRebuildItem(pm, this::rebuildControls);
                GuiUtils.MenuItems.addSeparator(pm, "Help:");
                final JMenuItem help = new JMenuItem("Help...", IconFactory.menuIcon(IconFactory.GLYPH.QUESTION_CIRCLE));
                help.addActionListener(e -> new GuiUtils(parent).showHTMLDialog(helpHtml(), "Help", false));
                pm.add(help);
                paletteOptions.addTourItem(pm, this::startTour);

            });
            more.setFocusable(false);
            moreButton = more;

            p.add(apply);
            p.add(more);
            return p;
        }

        private void changed() {
            updateLabels();
            scheduleEvaluation();
        }

        // Min. length is a fraction of the radius: its absolute value is part of the tooltip
        private void updateLengthTip() {
            tip(minLengthSpin, String.format("How far the structure connected to a candidate must reach, as a fraction "
                    + "of the analysis radius (currently %.3g %s).<br>Rejects speckles", val(minLengthSpin)
                    * val(radiusSpin), unit));
        }

        private void updateLabels() {
            updateLengthTip();
        }

        // Called on the EDT after each evaluation
        void refresh() {
            updateLabels();
            updateStatus();
            learnedModel.setRowCount(0);
            learnedModel.addRow(new String[]{"Training paths (nodes, tips)",
                    paths.size() + " (" + example.nodes().length + ", " + example.tips().length + ")"});
            learnedModel.addRow(new String[]{"Radius (min / median / max) [" + unit + "]", voxelsOnly(config.getMinRadius()) + " / "
                    + voxelsOnly(config.getMedianRadius()) + " / " + voxelsOnly(config.getMaxRadius())});
            final StringBuilder scales = new StringBuilder();
            for (final double s : config.getNeuriteRadii()) scales.append(voxelsOnly(s)).append("; ");
            learnedModel.addRow(new String[]{L_RADII + " [" + unit + "]", scales.toString()});
            learnedModel.addRow(new String[]{"Scores at nodes (P10 / median)", fmt(config.getNodeScoreP10()) + " / "
                    + fmt(config.getNodeScoreMedian())});
            learnedModel.addRow(new String[]{"Scores elsewhere (P50 / P99)", fmt(config.getBackgroundP50()) + " / "
                    + fmt(config.getBackgroundP99())});
            getCanvas().repaint();
        }

        void updateStatus() {
            final TipDetectionConfig.Evaluation ev = evaluation;
            if (ev == null) {
                statusLabel.setText("Set " + (Double.isNaN(config.getMinScore()) ? L_SCORE
                        : L_RADIUS) + " to preview detections");
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

        private String helpHtml() {
            return "<HTML><div WIDTH=500><p>The <i>Tip Detection Wizard</i> learns the parameters of the tip "
                    + "detector from the training paths, and previews the detections on the region they span. The "
                    + "preview reads the data directly from the source: it is not affected by crops.</p>"
                    + "<ul>"
                    + "<li><b>Dots</b> are detections. Click a detection to see the sphere (dashed) in which it "
                    + "was analyzed</li>"
                    + "<li><b>Min. Tubeness score</b>: score above which voxels are considered to be part of a "
                    + "structure. Learned from the scores along the training paths (10th percentile)</li>"
                    + "<li><b>Analysis radius</b>: radius of the sphere analyzed around each candidate (about 3x "
                    + "the diameter of the thickest neurites). Also the min. distance between tips</li>"
                    + "<li><b>Min. length</b>: how far (as a fraction of the analysis radius) the structure "
                    + "connected to a candidate must reach. Rejects speckles</li>"
                    + "<li><b>Min. connected fraction</b>: fraction of the structure voxels in the sphere that "
                    + "must be connected to the candidate. Rejects clutter</li>"
                    + "<li><b>Neurite radii</b>: scales (sigma) of the Tubeness filter. Edit and press "
                    + "<i>Re-learn</i> to apply</li>"
                    + "<li><b>Max. background</b>: candidates over background brighter than this are rejected</li>"
                    + "<li><b>Performance</b>: resolution level and skipping of empty blocks (whole volume runs "
                    + "only)</li>"
                    + "<li>Buttons next to each value restore the value learned from the training paths</li>"
                    + "<li><i>Extra</i> detections are not necessarily false: the training paths are only a sample of the "
                    + "structures in the region</li>"
                    + "</ul></div>";
        }
    }
}
