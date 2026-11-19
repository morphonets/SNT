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


package sc.fiji.snt.tracing.auto;

import net.imglib2.RandomAccessibleInterval;
import net.imglib2.converter.BiConverter;
import net.imglib2.converter.Converters;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import sc.fiji.snt.filter.Lazy;
import sc.fiji.snt.filter.Tubeness;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/**
 * Detects the tips of tubular structures in a volume of any size, using the parameters of a
 * {@link TipDetectionConfig} (e.g., learned by the Tip Detection Wizard). Everything is lazy: the (optionally
 * background-subtracted) signal and its Tubeness score map are computed per cell, on demand, and the volume is
 * scanned in a single parallel pass by {@link TipFinder}. Optionally, blocks without signal are skipped, as assessed
 * from a coarser copy of the volume (see {@link OccupancyMask}).
 * <p>
 * All images must be zero-min and share the same grid. Positions of the tips are in calibrated units, relative to
 * the first voxel of the signal image
 * </p>
 *
 * @author Tiago Ferreira
 */
public class TipDetector {

    private static final int CELL_3D = 64;
    private static final int CELL_2D = 256;

    /**
     * Coarse copy of the volume, used to skip blocks without signal
     *
     * @param signal     the coarse signal
     * @param background the coarse background. Null if no background is subtracted
     * @param spacing    voxel size of the coarse images
     * @param origin     world position of the first voxel of the coarse images
     * @param fineOrigin world position of the first voxel of the signal image of the detector
     * @param madFactor  the number of median absolute deviations above the median defining signal
     */
    public record Coarse(RandomAccessibleInterval<? extends RealType<?>> signal,
                         RandomAccessibleInterval<? extends RealType<?>> background, double[] spacing,
                         double[] origin, double[] fineOrigin, double madFactor) {
    }

    /**
     * Chooses the level used to look for empty blocks: the finest level coarser than the processed one that has no
     * more than {@code maxVoxels} voxels, or the coarsest one if there is none
     *
     * @param levels    the levels of the dataset, finest first
     * @param level     the level to be processed
     * @param maxVoxels max. number of voxels of the coarse level
     * @return the level, or null if the dataset has no coarser level than {@code level}
     */
    public static sc.fiji.snt.util.BigDataUtils.LevelInfo coarseLevel(
            final List<sc.fiji.snt.util.BigDataUtils.LevelInfo> levels, final int level, final long maxVoxels) {
        for (final var l : levels) {
            if (l.level() > level && l.dims()[0] * l.dims()[1] * l.dims()[2] <= maxVoxels) return l;
        }
        return (levels.size() - 1 > level) ? levels.get(levels.size() - 1) : null;
    }

    private final TipDetectionConfig config;
    private final RandomAccessibleInterval<? extends RealType<?>> signal;
    private final RandomAccessibleInterval<? extends RealType<?>> background;
    private final double[] spacing;
    private Coarse coarse;
    private DoubleConsumer progressListener;
    private Consumer<String> logger;
    private volatile TipFinder<?> finder;
    private volatile boolean canceled;

    /**
     * Creates a detector
     *
     * @param config     the parameters. If it specifies a background channel, {@code background} must be provided
     * @param signal     the signal (zero-min, 3D. A single slice is processed as a 2D image)
     * @param background the background, on the same grid as the signal. Null for none
     * @param spacing    voxel size {x, y, z} in calibrated units
     */
    public TipDetector(final TipDetectionConfig config, final RandomAccessibleInterval<? extends RealType<?>> signal,
                       final RandomAccessibleInterval<? extends RealType<?>> background, final double[] spacing) {
        this.config = config;
        this.signal = signal;
        this.background = background;
        this.spacing = spacing.clone();
    }

    /** Skips blocks without signal, as assessed from a coarse copy of the volume. Null to process all blocks */
    public TipDetector setCoarse(final Coarse coarse) {
        this.coarse = coarse;
        return this;
    }

    /** Receives the fraction of blocks processed, in {@code [0, 1]} */
    public TipDetector setProgressListener(final DoubleConsumer listener) {
        this.progressListener = listener;
        return this;
    }

    /** Receives messages about the progress of the detection */
    public TipDetector setLogger(final Consumer<String> logger) {
        this.logger = logger;
        return this;
    }

    /** Requests {@link #detect()} to return as soon as possible (it then returns null) */
    public void cancel() {
        canceled = true;
        final TipFinder<?> f = finder;
        if (f != null) f.cancel();
    }

    private void log(final String msg) {
        if (logger != null) logger.accept(msg);
    }

    /**
     * Runs the detection
     *
     * @return the tips (best first), or null if canceled
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public List<TipFinder.Tip> detect() {
        canceled = false;
        final float w = (float) config.getBackgroundWeight();
        // signal - w * background, clamped to zero
        final BiConverter<RealType, RealType, FloatType> unmix = (s, b, o) -> o
                .set(Math.max(0f, (float) (s.getRealDouble() - w * b.getRealDouble())));
        RandomAccessibleInterval src = signal;
        RandomAccessibleInterval veto = null;
        if (background != null) {
            if (!Double.isNaN(config.getVetoThreshold())) veto = background; // The veto uses the raw background
            src = Converters.convert(src, (RandomAccessibleInterval) background, unmix, new FloatType());
        }
        final RandomAccessibleInterval<FloatType> ridge;
        if (src.dimension(2) == 1) { // 2D data, held as a one-slice volume
            final RandomAccessibleInterval slice = Views.zeroMin(Views.hyperSlice(src, 2, src.min(2)));
            ridge = Views.addDimension(Lazy.process(slice, slice, new int[]{CELL_2D, CELL_2D}, new FloatType(),
                    new Tubeness(config.getScales(), new double[]{spacing[0], spacing[1]})), 0, 0);
        } else {
            ridge = Lazy.process(src, src, new int[]{CELL_3D, CELL_3D, CELL_3D}, new FloatType(),
                    new Tubeness(config.getScales(), spacing));
        }
        final TipFinder<FloatType> f = config.configure(new TipFinder<>(ridge, spacing),
                (RandomAccessibleInterval<? extends RealType<?>>) veto);
        finder = f;
        if (canceled) return null;
        if (coarse != null) {
            RandomAccessibleInterval c = coarse.signal();
            if (coarse.background() != null)
                c = Converters.convert(c, (RandomAccessibleInterval) coarse.background(), unmix, new FloatType());
            final OccupancyMask mask = OccupancyMask.build(c, coarse.spacing(), coarse.origin(), spacing,
                    coarse.fineOrigin(), coarse.madFactor());
            log(String.format("Empty blocks: threshold %.4g, %.2f%% of the coarse voxels active",
                    mask.getThreshold(), 100 * mask.getActiveFraction()));
            f.setBlockFilter(box -> mask.isActive(new long[]{box[0], box[1], box[2]},
                    new long[]{box[3], box[4], box[5]}));
        }
        if (canceled) return null;
        if (progressListener != null) f.setProgressListener(progressListener);
        final List<TipFinder.Tip> tips = f.find();
        return (canceled) ? null : tips;
    }
}
