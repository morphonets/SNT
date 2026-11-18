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

    private void run(final TipDetectionConfig config, final boolean wholeVolume) {
        final String summary = String.format("Tip detection parameters: scales %s, tip radius %s, threshold %s, "
                        + "min. extent %s, min. purity %s", java.util.Arrays.toString(config.getScales()),
                SNTUtils.formatDouble(config.getTipRadius(), 2), SNTUtils.formatDouble(config.getThreshold(), 3),
                SNTUtils.formatDouble(config.getMinExtent(), 2), SNTUtils.formatDouble(config.getMinPurity(), 2));
        SNTUtils.log(summary);
        final Thread t = new Thread(() -> detect(config, wholeVolume), "SNT Tip Detection");
        t.setDaemon(true);
        t.start();
    }

    private void status(final String msg) {
        if (ui != null) ui.showStatus(msg, msg == null);
    }

    // Seeds are placed in world coordinates: world = (voxel - defaultOffset) * spacing
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void detect(final TipDetectionConfig config, final boolean wholeVolume) {
        try {
            RandomAccessibleInterval<?> source = snt.getFullSourceData();
            final boolean is2D = source.numDimensions() == 2;
            if (is2D) source = Views.addDimension((RandomAccessibleInterval) source, 0, 0);
            final double[] spacing = {snt.getPixelWidth(), snt.getPixelHeight(), snt.getPixelDepth()};
            final PointInCanvas def = snt.getDefaultCanvasPixelOffset();
            final double[] defOffset = {def.x, def.y, def.z};
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
            RandomAccessibleInterval src = Views.zeroMin(Views.interval(source, min, max));
            RandomAccessibleInterval veto = null; // Raw background channel, if enabled
            if (config.getBackgroundChannel() > 0) {
                RandomAccessibleInterval<?> bgSource = snt.getFullSourceData(config.getBackgroundChannel());
                if (bgSource == null) {
                    error("Background channel " + config.getBackgroundChannel() + " could not be read.");
                    return;
                }
                if (is2D) bgSource = Views.addDimension((RandomAccessibleInterval) bgSource, 0, 0);
                final RandomAccessibleInterval bg = Views.zeroMin(Views.interval(bgSource, min, max));
                if (!Double.isNaN(config.getVetoThreshold())) veto = bg;
                final float w = (float) config.getBackgroundWeight();
                // Lazy: result = signal - weight * background, clamped to zero
                src = Converters.convert(src, bg, (BiConverter<RealType, RealType, FloatType>) (s, b, o) ->
                        o.set(Math.max(0f, (float) (s.getRealDouble() - w * b.getRealDouble()))), new FloatType());
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
            final TipFinder<FloatType> finder = config.configure(new TipFinder<>(ridge, spacing), veto);
            final int[] lastPercent = {-1};
            finder.setProgressListener(f -> {
                final int pct = (int) (f * 100);
                synchronized (lastPercent) {
                    if (pct == lastPercent[0]) return;
                    lastPercent[0] = pct;
                }
                status("Tip detection: " + pct + "%");
            });
            final List<TipFinder.Tip> tips = finder.find();
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
                final double[] w = new double[3];
                for (int d = 0; d < 3; d++) w[d] = p[d] + (min[d] - defOffset[d]) * spacing[d];
                seeds.add(new SeedPoint(w[0], w[1], w[2], confidence[i], radius, snt.getChannel(), snt.getFrame(),
                        "tip", "TipFinder"));
            }
            snt.getSeedOverlay().addAll(seeds);
            msg(String.format("%d tip(s) added to the Seed Manager. Use its confidence sliders to filter them.",
                    seeds.size()), "Tip Detection Complete");
        } catch (final Throwable t) {
            error("Tip detection failed: " + t.getMessage());
            SNTUtils.error("Tip detection failed", t);
        } finally {
            status(null);
        }
    }
}
