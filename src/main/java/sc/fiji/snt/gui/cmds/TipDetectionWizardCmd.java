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


package sc.fiji.snt.gui.cmds;

import org.scijava.command.Command;
import org.scijava.plugin.Plugin;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTUI;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.gui.TipPalette;
import sc.fiji.snt.tracing.auto.TipDetectionConfig;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.converter.BiConverter;
import net.imglib2.converter.Converters;
import net.imglib2.type.numeric.RealType;
import net.imglib2.view.Views;
import sc.fiji.snt.filter.Lazy;
import sc.fiji.snt.filter.Tubeness;
import sc.fiji.snt.seed.SeedConfidence;
import sc.fiji.snt.seed.SeedPoint;
import sc.fiji.snt.tracing.auto.TipFinder;
import sc.fiji.snt.tracing.auto.OccupancyMask;
import sc.fiji.snt.util.BigDataUtils;
import sc.fiji.snt.util.PointInCanvas;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Entry-point command for the tip detection wizard. Uses the paths selected in the Path Manager (or all of them, if
 * none is selected) as examples, and launches {@link TipPalette}.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, visible = false, initializer = "init", label = "Tip Detection Wizard...")
public class TipDetectionWizardCmd extends CommonDynamicCmd {

    private static final double CLIP_PERCENT = 5;

    protected void init() {
        super.init(true);
    }

    @Override
    public void run() {
        // No @Parameter inputs: the command relies on the state of the Path Manager
        if (isCanceled()) return;
        if (snt == null || !snt.accessToValidImageData()) {
            error("This option requires valid image data to be loaded.");
            return;
        }
        final Collection<Path> selected = (ui == null) ? null : ui.getPathManager().getSelectedPaths(true);
        final List<Path> examples = new ArrayList<>();
        if (selected != null) {
            for (final Path p : selected) {
                if (p.size() > 1) examples.add(p);
            }
        }
        if (examples.isEmpty()) {
            error("The <i>Tip Detection Wizard</i> learns from example paths. Trace (or select in the Path "
                    + "Manager) a few representative axons, from a branch point or origin towards their tips, "
                    + "and re-launch the wizard.");
            return;
        }
        final TipPalette wizard = new TipPalette(snt, examples);
        final String problem = wizard.validate();
        if (problem != null) {
            error(problem);
            return;
        }
        wizard.addListener(this::run);
        wizard.show(); // UI STATE: is currently RUNNING_CMD. wizard's dismiss will reset it
    }

    private void run(final TipDetectionConfig config, final boolean wholeVolume,
                     final TipPalette.ParseOptions options) {
        final String summary = String.format("Tip detection parameters: scales %s, tip radius %s, threshold %s, "
                        + "min. extent %s, min. purity %s", java.util.Arrays.toString(config.getScales()),
                SNTUtils.formatDouble(config.getTipRadius(), 2), SNTUtils.formatDouble(config.getThreshold(), 3),
                SNTUtils.formatDouble(config.getMinExtent(), 2), SNTUtils.formatDouble(config.getMinPurity(), 2));
        SNTUtils.log(summary);
        final Thread t = new Thread(() -> detect(config, wholeVolume, options), "SNT Tip Detection");
        t.setDaemon(true);
        t.start();
    }

    private static final long MAX_COARSE_VOXELS = 32_000_000L;

    // Temporary progress report: console, log, status bar and (Stream mode) the viewer's progress bar
    private void progress(final String msg, final int percent, final boolean indeterminate) {
        System.out.println(msg);
        SNTUtils.log(msg);
        status(msg);
        if (snt.isStreamMode() && ui != null) {
            final sc.fiji.snt.viewer.AbstractBigViewer viewer = ui.getActiveBigViewer();
            if (viewer != null) viewer.updateStatus(msg, percent, (indeterminate) ? -1 : 100);
        }
    }

    private static String formatTime(final double seconds) {
        if (Double.isNaN(seconds) || Double.isInfinite(seconds)) return "?";
        final long s = Math.round(seconds);
        return (s >= 3600) ? String.format("%dh %02dmin", s / 3600, (s % 3600) / 60)
                : (s >= 60) ? String.format("%dmin %02ds", s / 60, s % 60) : s + "s";
    }

    private void status(final String msg) {
        if (ui != null) ui.showStatus(msg, msg == null);
    }

    // Mask of non-empty regions from a coarse level, processed with the same background unmixing
    @SuppressWarnings({"unchecked", "rawtypes"})
    private OccupancyMask buildMask(final sc.fiji.snt.viewer.AbstractBigViewer viewer, final int srcIdx,
                                    final int bgIdx, final BiConverter<RealType, RealType, FloatType> unmix,
                                    final List<BigDataUtils.LevelInfo> levels, final TipPalette.ParseOptions opts) {
        BigDataUtils.LevelInfo coarse = null;
        for (final var l : levels) {
            if (l.level() <= opts.level()) continue;
            final long n = l.dims()[0] * l.dims()[1] * l.dims()[2];
            if (n <= MAX_COARSE_VOXELS) {
                coarse = l;
                break;
            }
        }
        if (coarse == null && levels.size() - 1 > opts.level()) coarse = levels.get(levels.size() - 1);
        if (coarse == null) {
            progress("Skip empty blocks: no coarser level available, processing all blocks", 0, true);
            return null;
        }
        progress("Skip empty blocks: building mask from " + TipPalette.describeLevel(coarse), 0, true);
        RandomAccessibleInterval c = Views.zeroMin(viewer.getSourceData(srcIdx, coarse.level()));
        if (bgIdx >= 0) {
            final RandomAccessibleInterval cbg = Views.zeroMin(viewer.getSourceData(bgIdx, coarse.level()));
            c = Converters.convert(c, cbg, unmix, new FloatType());
        }
        final var fine = levels.get(opts.level());
        final OccupancyMask mask = OccupancyMask.build(c, coarse.spacing(), coarse.origin(), fine.spacing(),
                fine.origin(), opts.madFactor());
        progress(String.format("Skip empty blocks: threshold %s, %s%% of the coarse voxels active",
                SNTUtils.formatDouble(mask.getThreshold(), 2),
                SNTUtils.formatDouble(100 * mask.getActiveFraction(), 2)), 0, true);
        return mask;
    }

    // Seeds are placed in world coordinates: world = (voxel - defaultOffset) * spacing
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void detect(final TipDetectionConfig config, final boolean wholeVolume,
                        final TipPalette.ParseOptions opts) {
        if (ui != null) javax.swing.SwingUtilities.invokeLater(() -> ui.changeState(SNTUI.RUNNING_CMD));
        try {
            final var viewer = (opts != null && ui != null) ? ui.getActiveBigViewer() : null;
            final boolean useViewer = viewer != null;
            RandomAccessibleInterval src;
            RandomAccessibleInterval veto = null; // Raw background channel, if enabled
            final boolean is2D;
            double[] spacing = {snt.getPixelWidth(), snt.getPixelHeight(), snt.getPixelDepth()};
            final PointInCanvas def = snt.getDefaultCanvasPixelOffset();
            final double[] defOffset = {def.x, def.y, def.z};
            // Offset (world units) added to the position of tips in the score map
            final double[] shift = new double[3];
            final float w = (float) config.getBackgroundWeight();
            final BiConverter<RealType, RealType, FloatType> unmix = (s, b, o) ->
                    o.set(Math.max(0f, (float) (s.getRealDouble() - w * b.getRealDouble()))); // signal - w * bg
            final boolean useBg = config.getBackgroundChannel() > 0;
            OccupancyMask mask = null;
            if (useViewer) {
                is2D = false;
                final int srcIdx = snt.getChannel() - 1;
                final int bgIdx = config.getBackgroundChannel() - 1;
                final var levels = viewer.getSourceLevels(srcIdx);
                final var info = levels.get(opts.level());
                final var info0 = levels.get(0);
                spacing = info.spacing();
                for (int d = 0; d < 3; d++)
                    shift[d] = info.origin()[d] - info0.origin()[d] - defOffset[d] * info0.spacing()[d];
                src = viewer.getSourceData(srcIdx, opts.level());
                src = Views.zeroMin(src);
                if (useBg) {
                    final RandomAccessibleInterval<?> bgRai = viewer.getSourceData(bgIdx, opts.level());
                    if (bgRai == null) {
                        error("Background channel " + config.getBackgroundChannel() + " could not be read.");
                        return;
                    }
                    final RandomAccessibleInterval bg = Views.zeroMin(bgRai);
                    if (!Double.isNaN(config.getVetoThreshold())) veto = bg;
                    src = Converters.convert(src, bg, unmix, new FloatType());
                }
                if (opts.skipEmpty()) {
                    mask = buildMask(viewer, srcIdx, (useBg) ? bgIdx : -1, unmix, levels, opts);
                }
            } else {
                RandomAccessibleInterval<?> source = snt.getFullSourceData();
                is2D = source.numDimensions() == 2;
                if (is2D) source = Views.addDimension((RandomAccessibleInterval) source, 0, 0);
                final long[] min = source.minAsLongArray();
                final long[] max = source.maxAsLongArray();
                if (!wholeVolume) {
                    // Loaded region: crop-local voxel v is v + (default - active) in the source grid
                    final PointInCanvas act = snt.getActiveCanvasPixelOffset();
                    final double[] actOffset = {act.x, act.y, act.z};
                    final RandomAccessibleInterval<?> loaded = snt.getLoadedData();
                    for (int d = 0; d < loaded.numDimensions(); d++) {
                        min[d] = Math.max(min[d], Math.round(defOffset[d] - actOffset[d]));
                        max[d] = Math.min(max[d], min[d] + loaded.dimension(d) - 1);
                    }
                }
                for (int d = 0; d < 3; d++) shift[d] = (min[d] - defOffset[d]) * spacing[d];
                src = Views.zeroMin(Views.interval(source, min, max));
                if (useBg) {
                    RandomAccessibleInterval<?> bgSource = snt.getFullSourceData(config.getBackgroundChannel());
                    if (bgSource == null) {
                        error("Background channel " + config.getBackgroundChannel() + " could not be read.");
                        return;
                    }
                    if (is2D) bgSource = Views.addDimension((RandomAccessibleInterval) bgSource, 0, 0);
                    final RandomAccessibleInterval bg = Views.zeroMin(Views.interval(bgSource, min, max));
                    if (!Double.isNaN(config.getVetoThreshold())) veto = bg;
                    // Lazy: result = signal - weight * background, clamped to zero
                    src = Converters.convert(src, bg, unmix, new FloatType());
                }
            }
            final long[] dims = src.dimensionsAsLongArray();
            status(String.format("Tip detection: %s voxels in %s...",
                    String.format("%dx%dx%d", dims[0], dims[1], dims[2]), (wholeVolume) ? "whole volume" : "region"));
            final RandomAccessibleInterval<FloatType> ridge;
            if (is2D) {
                final RandomAccessibleInterval slice = Views.zeroMin(Views.hyperSlice(src, 2, src.min(2)));
                ridge = Views.addDimension(Lazy.process(slice, slice, new int[]{256, 256}, new FloatType(),
                        new Tubeness(config.getScales(), new double[]{spacing[0], spacing[1]})), 0, 0);
            } else {
                ridge = Lazy.process(src, src, new int[]{64, 64, 64}, new FloatType(),
                        new Tubeness(config.getScales(), spacing));
            }
            final TipFinder<FloatType> finder = config.configure(new TipFinder<>(ridge, spacing),
                    (RandomAccessibleInterval<? extends RealType<?>>) veto);
            if (mask != null) {
                final OccupancyMask m = mask;
                finder.setBlockFilter(box -> m.isActive(new long[]{box[0], box[1], box[2]},
                        new long[]{box[3], box[4], box[5]}));
            }
            if (ui != null) ui.setAbortHook(finder::cancel);
            final int[] lastPercent = {-1};
            final long t0 = System.currentTimeMillis();
            progress("Tip detection: started (first update when a block completes)", 0, true);
            finder.setProgressListener(f -> {
                final int pct = (int) (f * 100);
                synchronized (lastPercent) {
                    if (pct == lastPercent[0]) return;
                    lastPercent[0] = pct;
                }
                final double elapsed = (System.currentTimeMillis() - t0) / 1000d;
                final double left = (f > 0) ? elapsed * (1 - f) / f : Double.NaN;
                progress(String.format("Tip detection: %d%% (%s elapsed, ~%s left)", pct, formatTime(elapsed),
                        formatTime(left)), pct, false);
            });
            final List<TipFinder.Tip> tips = finder.find();
            if (tips == null) {
                SNTUtils.log("Tip detection canceled");
                return;
            }
            if (tips.isEmpty()) {
                msg("No tips detected. Try lowering the threshold in the wizard.", "Tip Detection");
                return;
            }
            // Confidence: score ranked within the batch, robust to outliers
            final double[] scores = tips.stream().mapToDouble(TipFinder.Tip::score).toArray();
            final double[] confidence = SeedConfidence.percentileClipNormalize(scores, CLIP_PERCENT);
            final double radius = Double.isNaN(config.getMedianRadius()) ? 0 : config.getMedianRadius();
            final List<SeedPoint> seeds = new ArrayList<>(tips.size());
            for (int i = 0; i < tips.size(); i++) {
                final double[] p = tips.get(i).position();
                final double[] ww = new double[3];
                for (int d = 0; d < 3; d++) ww[d] = p[d] + shift[d];
                seeds.add(new SeedPoint(ww[0], ww[1], ww[2], confidence[i], radius, snt.getChannel(), snt.getFrame(),
                        "tip", "TipFinder"));
            }
            snt.getSeedOverlay().addAll(seeds);
            msg(String.format("%d tip(s) added to the Seed Manager. Use its confidence sliders to filter them.",
                    seeds.size()), "Tip Detection Complete");
        } catch (final Throwable t) {
            error("Tip detection failed: " + t.getMessage());
            SNTUtils.error("Tip detection failed", t);
        } finally {
            if (ui != null) ui.setAbortHook(null);
            status(null);
            if (snt.isStreamMode() && ui != null) {
                final sc.fiji.snt.viewer.AbstractBigViewer viewer = ui.getActiveBigViewer();
                if (viewer != null) viewer.updateStatus(null, 0, 0); // Hides the progress bar
            }
            if (ui != null) javax.swing.SwingUtilities.invokeLater(() -> ui.changeState(SNTUI.READY));
        }
    }
}
