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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package sc.fiji.snt.viewer;

import java.util.Locale;
import bdv.tools.brightness.ConverterSetup;
import bdv.util.BdvFunctions;
import bdv.util.BdvHandle;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import bdv.viewer.Interpolation;
import bdv.viewer.Source;
import mpicbg.spim.data.sequence.VoxelDimensions;
import net.imglib2.AbstractWrappedInterval;
import net.imglib2.Interval;
import net.imglib2.Localizable;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.converter.BiConverter;
import net.imglib2.converter.Converters;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.view.Views;

import sc.fiji.snt.SNTUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link UnmixEngine} for {@link Bdv}: BDV only renders the visible plane, so the mix is never materialized.
 * It is displayed as a source whose pixels are computed on demand from the signal and background channels, which
 * allows the weight and the display ranges to be changed live (the source simply reads their current values).
 * The original channels are hidden while the mixed source is shown.
 */
class BdvUnmixEngine implements UnmixEngine {

    private final Bdv owner;
    private final ChannelGroup group;
    private final Mixer mixer = new Mixer();
    private Host host;

    // The mixed source (null if none), and the channels it was built from
    private BdvStackSource<?> mixedSource;
    private int mixedSig = -1;
    private int mixedSub = -1;

    BdvUnmixEngine(final Bdv owner, final ChannelGroup group) {
        this.owner = owner;
        this.group = group;
    }

    @Override
    public void attach(final Host host) {
        this.host = host;
    }

    @Override
    public boolean isLive() {
        return true;
    }

    @Override
    public String sliderHint() {
        return "Updates live.";
    }

    @Override
    public boolean isBusy() {
        return false;
    }

    @Override
    public boolean hasResult() {
        return mixedSource != null;
    }

    @Override
    public String validate(final int sigIdx, final int subIdx, final boolean active) {
        // A result only exists while unmixing is enabled (also at w=0), so no need to check 'active'
        if (mixedSource == null) return null;
        if (sigIdx == subIdx) {
            clear();
        } else if (sigIdx != mixedSig || subIdx != mixedSub) {
            apply(sigIdx, subIdx, host.weight());
        } else {
            refreshRanges(sigIdx, subIdx);
        }
        return null;
    }

    @Override
    public String status(final int sigIdx, final int subIdx, final double w) {
        if (mixedSource == null) return "Move slider to unmix";
        return (w <= 0) ? "No subtraction (w=0)" : String.format(Locale.US, "Unmixed: %s - %.2f x %s", host.channelName(sigIdx), w, host.channelName(subIdx));
    }

    @Override
    public void apply(final int sigIdx, final int subIdx, final double w) {
        mixer.w = w;
        if (mixedSource != null && sigIdx == mixedSig && subIdx == mixedSub) {
            refreshRanges(sigIdx, subIdx); // only the weight changed
            owner.repaint();
            return;
        }
        if (mixedSource != null) mixedSource.removeFromBdv();
        mixer.setRanges(group.channelSetup(sigIdx), group.channelSetup(subIdx));
        final Source<?> src = new MixedSource(group.channelSource(sigIdx).getSpimSource(),
                group.channelSource(subIdx).getSpimSource(), mixer,
                String.format("%s − w × %s", host.channelName(sigIdx), host.channelName(subIdx)));
        final BdvHandle handle = owner.getBdvHandle();
        mixedSource = BdvFunctions.show(src, handle.getViewerPanel().state().getNumTimepoints(),
                BdvOptions.options().addTo(handle));
        mixedSource.setDisplayRange(mixer.sigMin, mixer.sigMax);
        // Opaque cyan: BDV accumulates the alpha of the displayed sources, so a color with alpha 0 renders as black
        mixedSource.setColor(new ARGBType(ARGBType.rgba(0, 255, 255, 255)));
        mixedSig = sigIdx;
        mixedSub = subIdx;
        group.setActive(false);
        owner.repaint();
    }

    /** Picks up changes in the display ranges of the channels (B&C) */
    private void refreshRanges(final int sigIdx, final int subIdx) {
        if (mixer.setRanges(group.channelSetup(sigIdx), group.channelSetup(subIdx))) {
            mixedSource.setDisplayRange(mixer.sigMin, mixer.sigMax);
            owner.repaint();
        }
    }

    @Override
    public void clear() {
        if (mixedSource != null) {
            mixedSource.removeFromBdv();
            mixedSource = null;
        }
        mixedSig = mixedSub = -1;
        group.setActive(true);
        owner.repaint();
    }

    /** Pixel-wise mixing, reading the current weight and normalisation (volatile: updated from the EDT) */
    private static final class Mixer implements BiConverter<RealType<?>, RealType<?>, UnsignedShortType> {

        volatile double w;
        volatile double sigMin;
        volatile double sigMax;
        volatile double subMin;
        volatile double rangeScale = 1.0;

        /** @return whether any of the display ranges changed */
        boolean setRanges(final ConverterSetup sig, final ConverterSetup sub) {
            final double sMin = sig.getDisplayRangeMin();
            final double sMax = sig.getDisplayRangeMax();
            final double bMin = sub.getDisplayRangeMin();
            final double bRange = sub.getDisplayRangeMax() - bMin;
            final double scale = (bRange > 0) ? (sMax - sMin) / bRange : 1.0;
            final boolean changed = sMin != sigMin || sMax != sigMax || bMin != subMin || scale != rangeScale;
            sigMin = sMin;
            sigMax = sMax;
            subMin = bMin;
            rangeScale = scale;
            return changed;
        }

        @Override
        public void convert(final RealType<?> a, final RealType<?> b, final UnsignedShortType out) {
            out.setReal(UnmixEngine.mix(a.getRealDouble(), b.getRealDouble(), w, sigMin, subMin, rangeScale));
        }
    }

    /**
     * Wraps a view so that a failed pixel read (e.g., an I/O error while loading a block) yields 0 instead of
     * propagating: BDV's PainterThread dies on any uncaught exception, freezing the display for good. Failed
     * blocks are not cached by the loader, so they are retried at the next repaint
     */
    private static final class Guarded extends AbstractWrappedInterval<RandomAccessibleInterval<UnsignedShortType>>
            implements RandomAccessibleInterval<UnsignedShortType> {

        private static volatile boolean warned;

        Guarded(final RandomAccessibleInterval<UnsignedShortType> source) {
            super(source);
        }

        @Override
        public RandomAccess<UnsignedShortType> randomAccess() {
            return new GuardedAccess(sourceInterval.randomAccess());
        }

        @Override
        public RandomAccess<UnsignedShortType> randomAccess(final Interval interval) {
            return new GuardedAccess(sourceInterval.randomAccess(interval));
        }

        @Override
        public UnsignedShortType getType() {
            return sourceInterval.getType();
        }
    }

    private static final class GuardedAccess implements RandomAccess<UnsignedShortType> {

        private final RandomAccess<UnsignedShortType> ra;
        private final UnsignedShortType zero = new UnsignedShortType(0);

        GuardedAccess(final RandomAccess<UnsignedShortType> ra) {
            this.ra = ra;
        }

        @Override
        public UnsignedShortType get() {
            try {
                return ra.get();
            } catch (final RuntimeException ex) {
                if (!Guarded.warned) {
                    Guarded.warned = true;
                    SNTUtils.log("Unmixing: pixel read failed, block skipped: " + ex);
                }
                return zero;
            }
        }

        @Override
        public GuardedAccess copy() {
            return new GuardedAccess(ra.copy());
        }

        public GuardedAccess copyRandomAccess() {
            return copy();
        }

        @Override
        public int numDimensions() {
            return ra.numDimensions();
        }

        @Override
        public long getLongPosition(final int d) {
            return ra.getLongPosition(d);
        }

        @Override
        public void localize(final long[] position) {
            ra.localize(position);
        }

        @Override
        public void localize(final int[] position) {
            ra.localize(position);
        }

        @Override
        public void fwd(final int d) {
            ra.fwd(d);
        }

        @Override
        public void bck(final int d) {
            ra.bck(d);
        }

        @Override
        public void move(final int distance, final int d) {
            ra.move(distance, d);
        }

        @Override
        public void move(final long distance, final int d) {
            ra.move(distance, d);
        }

        @Override
        public void move(final Localizable localizable) {
            ra.move(localizable);
        }

        @Override
        public void move(final int[] distance) {
            ra.move(distance);
        }

        @Override
        public void move(final long[] distance) {
            ra.move(distance);
        }

        @Override
        public void setPosition(final Localizable localizable) {
            ra.setPosition(localizable);
        }

        @Override
        public void setPosition(final int[] position) {
            ra.setPosition(position);
        }

        @Override
        public void setPosition(final long[] position) {
            ra.setPosition(position);
        }

        @Override
        public void setPosition(final int position, final int d) {
            ra.setPosition(position, d);
        }

        @Override
        public void setPosition(final long position, final int d) {
            ra.setPosition(position, d);
        }
    }

    /** A source that mixes the signal and background sources on demand, using the signal's geometry */
    private static final class MixedSource implements Source<UnsignedShortType> {

        private final Source<?> sig;
        private final Source<?> sub;
        private final Mixer mixer;
        private final String name;
        private final UnsignedShortType type = new UnsignedShortType();
        private final Map<Long, RandomAccessibleInterval<UnsignedShortType>> views = new ConcurrentHashMap<>();

        MixedSource(final Source<?> sig, final Source<?> sub, final Mixer mixer, final String name) {
            this.sig = sig;
            this.sub = sub;
            this.mixer = mixer;
            this.name = name;
        }

        @Override
        public boolean isPresent(final int t) {
            return sig.isPresent(t) && sub.isPresent(t);
        }

        @Override
        @SuppressWarnings("unchecked")
        public RandomAccessibleInterval<UnsignedShortType> getSource(final int t, final int level) {
            return views.computeIfAbsent(((long) t << 16) | level, k -> new Guarded(Converters.convert(
                    (RandomAccessibleInterval<RealType<?>>) sig.getSource(t, level),
                    (RandomAccessibleInterval<RealType<?>>) sub.getSource(t, level),
                    mixer, new UnsignedShortType())));
        }

        @Override
        public RealRandomAccessible<UnsignedShortType> getInterpolatedSource(final int t, final int level,
                                                                             final Interpolation method) {
            final RandomAccessible<UnsignedShortType> extended = Views.extendZero(getSource(t, level));
            return (method == Interpolation.NLINEAR)
                    ? Views.interpolate(extended, new NLinearInterpolatorFactory<>())
                    : Views.interpolate(extended, new NearestNeighborInterpolatorFactory<>());
        }

        @Override
        public void getSourceTransform(final int t, final int level, final AffineTransform3D transform) {
            sig.getSourceTransform(t, level, transform);
        }

        @Override
        public UnsignedShortType getType() {
            return type;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public VoxelDimensions getVoxelDimensions() {
            return sig.getVoxelDimensions();
        }

        @Override
        public int getNumMipmapLevels() {
            return sig.getNumMipmapLevels();
        }
    }
}
