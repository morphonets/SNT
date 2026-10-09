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

package sc.fiji.snt.viewer;

import bdv.tools.brightness.ConverterSetup;
import bdv.util.MipmapTransforms;
import bdv.viewer.Source;
import bvv.vistools.BvvFunctions;
import bvv.vistools.BvvOptions;
import bvv.vistools.BvvStackSource;
import mpicbg.spim.data.generic.AbstractSpimData;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.loops.LoopBuilder;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.view.Views;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.io.SpimDataUtils;
import sc.fiji.snt.util.BoundingBox;

import javax.swing.*;
import java.util.Arrays;

/**
 * {@link UnmixEngine} for {@link Bvv}: on request, the mix is computed eagerly via {@link LoopBuilder} on a
 * slab-cropped sub-volume, then displayed as a new in-memory {@link BvvStackSource}, while the original channels
 * are hidden. Cropping to the slab keeps the materialized volume small, so the computation remains fast even on
 * network storage.
 */
class BvvUnmixEngine implements UnmixEngine {

    // Memory model shared by the slab pre-check and the compute-time guard: signal, background
    // and mixed copies of 16-bit voxels, limited to half of the free heap
    private static final long BYTES_PER_VOXEL = 2L * 3L;
    private static final int RECOMPUTE_DELAY_MS = 500;

    private final Bvv owner;
    private final ChannelGroup group;
    private final boolean hasPyramid;
    private final boolean slabRequired;
    private final double zCal;
    private final AbstractBigViewer.PathRenderingOptions renderingOptions;
    private Host host;

    // The mixed source (null if none)
    private BvvStackSource<?> mixedSource;
    // Slab bounds (world-space Z) at which the mixed source was computed
    private final double[] computedSlabZ = {Double.NaN, Double.NaN};
    // Display ranges (sigMin, sigMax, subMin, subMax) used for the last computation
    private final double[] computedDisplayRanges = {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
    private javax.swing.Timer recomputeTimer;
    private boolean computing;
    private String recomputeReason; // non-null if a recomputation is pending
    // View state (display ranges and slab bounds) last seen while a recomputation was pending
    private double[] lastObservedState;
    // View state for which a recomputation was refused (not enough memory): not retried until it changes
    private double[] refusedState;
    private volatile SwingWorker<?, ?> worker;

    /**
     * @param owner    the viewer displaying the channels
     * @param group    the channels to unmix
     * @param spimData the backing SpimData (may be {@code null} for in-memory sources)
     */
    BvvUnmixEngine(final Bvv owner, final ChannelGroup group, final AbstractSpimData<?> spimData) {
        this.owner = owner;
        this.group = group;
        hasPyramid = group.channelSource(0).getSpimSource().getNumMipmapLevels() > 1;
        slabRequired = spimData != null || hasPyramid;
        final double[] cal = owner.getCal();
        zCal = (cal != null && cal.length > 2 && cal[2] > 0) ? cal[2] : 1.0;
        renderingOptions = owner.getRenderingOptions();
    }

    private static long memoryBudgetBytes() {
        return SNTUtils.getHeapInfo().availableMB() * 1024L * 1024L / 2;
    }

    /**
     * The mip level best matching the current view, with its XY plane size and Z spacing
     */
    private record LevelInfo(int level, long xyPixels, double zSpacing) {
        /**
         * Max. slab thickness (world units) that fits the memory budget (crop is padded by up to 2 slices)
         */
        double maxThickness() {
            final long maxSlices = memoryBudgetBytes() / (BYTES_PER_VOXEL * Math.max(1L, xyPixels));
            return Math.max(0L, maxSlices - 2L) * zSpacing;
        }
    }

    private LevelInfo levelInfo(final Source<?> src) {
        final int level = MipmapTransforms.getBestMipMapLevel(owner.getViewerTransform(), src, 0);
        final AffineTransform3D srcToWorld = new AffineTransform3D();
        src.getSourceTransform(0, level, srcToWorld);
        final double mipZCal = Math.sqrt(
                srcToWorld.get(0, 2) * srcToWorld.get(0, 2) +
                        srcToWorld.get(1, 2) * srcToWorld.get(1, 2) +
                        srcToWorld.get(2, 2) * srcToWorld.get(2, 2));
        final var rai = src.getSource(0, level);
        return new LevelInfo(level, rai.dimension(0) * rai.dimension(1), mipZCal > 0 ? mipZCal : zCal);
    }

    @Override
    public void attach(final Host host) {
        this.host = host;
        recomputeTimer = new javax.swing.Timer(RECOMPUTE_DELAY_MS, e -> {
            if (host.isActive() && !computing && host.signalIndex() != host.backgroundIndex())
                apply(host.signalIndex(), host.backgroundIndex(), host.weight());
        });
        recomputeTimer.setRepeats(false);
    }

    @Override
    public boolean isLive() {
        return false;
    }

    @Override
    public String sliderHint() {
        return "Computed on slider release.";
    }

    @Override
    public String enableHint() {
        return "Large volumes may require an active thin Slab View.";
    }

    @Override
    public boolean isBusy() {
        return computing;
    }

    @Override
    public boolean hasResult() {
        return mixedSource != null;
    }

    @Override
    public String validate(final int sigIdx, final int subIdx, final boolean active) {
        recomputeReason = null;
        final double zMin = renderingOptions.getSlabZMin();
        final double zMax = renderingOptions.getSlabZMax();
        final boolean slabActive = zMin != Double.NEGATIVE_INFINITY;
        final LevelInfo li = slabRequired ? levelInfo(group.channelSource(sigIdx).getSpimSource()) : null;
        final double maxSlabThickness = (li == null) ? Double.POSITIVE_INFINITY : li.maxThickness();
        final boolean slabThin = slabActive && (zMax - zMin) <= maxSlabThickness;

        if (mixedSource != null) {
            final boolean slabComputed = !Double.isNaN(computedSlabZ[0]);
            final boolean slabMoved = slabComputed
                    && (zMin < computedSlabZ[0] - zCal || zMax > computedSlabZ[1] + zCal);
            if (slabComputed && (!slabActive || (slabRequired && !slabThin))) {
                clear();
            } else if (active && (slabMoved || rangesChanged(sigIdx, subIdx))) {
                recomputeReason = slabMoved ? "Slab moved" : "B&C levels changed";
                scheduleRecompute(sigIdx, subIdx, zMin, zMax);
            }
        }

        if (slabRequired && !slabActive)
            return "Slab view required to limit memory usage. Please enable it.";
        if (slabRequired && !slabThin) {
            if (maxSlabThickness < li.zSpacing())
                return "Not enough free memory at the current zoom level "
                        + "(zoom out to use a coarser resolution level).";
            final String unit = owner.getPhysicalUnit();
            final boolean unitKnown = unit != null && !unit.isBlank() && !BoundingBox.UNSET_SPACING_UNIT.equals(unit);
            // Floor to 1 decimal, as typed in the (1-decimal) thickness spinner, so the value is valid
            return String.format("Slab too thick for memory safety at this zoom level "
                            + "(max. thickness should be %.1f%s, i.e., %d slices).",
                    Math.floor(maxSlabThickness * 10) / 10, unitKnown ? " " + unit : "",
                    (int) (maxSlabThickness / li.zSpacing()));
        }
        return null;
    }

    @Override
    public String status(final int sigIdx, final int subIdx, final double w) {
        if (recomputeReason != null) return recomputeReason + ": Recomputing...";
        if (mixedSource == null) return "Release slider to compute unmixing";
        return String.format("Unmixed: %s - %.2f x %s", host.channelName(sigIdx), w, host.channelName(subIdx));
    }

    private boolean rangesChanged(final int sigIdx, final int subIdx) {
        final ConverterSetup csS = group.channelSetup(sigIdx);
        final ConverterSetup csB = group.channelSetup(subIdx);
        return csS.getDisplayRangeMin() != computedDisplayRanges[0]
                || csS.getDisplayRangeMax() != computedDisplayRanges[1]
                || csB.getDisplayRangeMin() != computedDisplayRanges[2]
                || csB.getDisplayRangeMax() != computedDisplayRanges[3];
    }

    private double[] viewState(final int sigIdx, final int subIdx, final double zMin, final double zMax) {
        final ConverterSetup csS = group.channelSetup(sigIdx);
        final ConverterSetup csB = group.channelSetup(subIdx);
        return new double[]{csS.getDisplayRangeMin(), csS.getDisplayRangeMax(),
                csB.getDisplayRangeMin(), csB.getDisplayRangeMax(), zMin, zMax};
    }

    /**
     * Debounces recomputations: the timer restarts whenever the view state changes (e.g., while dragging
     * B&C sliders), and is just left running if the state is the same as in the previous (periodic) check
     */
    private void scheduleRecompute(final int sigIdx, final int subIdx, final double zMin, final double zMax) {
        final double[] state = viewState(sigIdx, subIdx, zMin, zMax);
        if (Arrays.equals(state, refusedState)) {
            recomputeReason = null;
        } else if (!Arrays.equals(state, lastObservedState)) {
            lastObservedState = state;
            recomputeTimer.restart();
        } else if (!recomputeTimer.isRunning()) {
            recomputeTimer.start();
        }
    }

    @Override
    public void clear() {
        if (worker != null && !worker.isDone()) worker.cancel(true);
        if (recomputeTimer != null) recomputeTimer.stop();
        recomputeReason = null;
        lastObservedState = null;
        refusedState = null;
        if (mixedSource != null) {
            mixedSource.removeFromBdv();
            mixedSource = null;
            computedSlabZ[0] = Double.NaN;
            computedSlabZ[1] = Double.NaN;
            Arrays.fill(computedDisplayRanges, Double.NaN);
        }
        group.setActive(true);
        owner.repaint();
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void apply(final int sigIdx, final int subIdx, final double w) {
        if (computing) return;
        final String sigName = host.channelName(sigIdx);
        final String subName = host.channelName(subIdx);

        final Source<?> sigSource = group.channelSource(sigIdx).getSpimSource();
        final LevelInfo li = levelInfo(sigSource);
        final int bestLevel = li.level();

        final RandomAccessibleInterval<RealType<?>> sigTyped =
                (RandomAccessibleInterval<RealType<?>>) sigSource.getSource(0, bestLevel);
        final RandomAccessibleInterval<RealType<?>> subTyped =
                (RandomAccessibleInterval<RealType<?>>) group.channelSource(subIdx).getSpimSource().getSource(0, bestLevel);

        final AffineTransform3D sigSrcToWorld = new AffineTransform3D();
        sigSource.getSourceTransform(0, bestLevel, sigSrcToWorld);
        final double effZCal = li.zSpacing();

        final double slabZMin = renderingOptions.getSlabZMin();
        final double slabZMax = renderingOptions.getSlabZMax();
        final RandomAccessibleInterval<RealType<?>> sigCropped;
        final RandomAccessibleInterval<RealType<?>> subCropped;
        final double[] calOffset;
        if (slabZMin != Double.NEGATIVE_INFINITY && sigTyped.numDimensions() >= 3) {
            final long zMinPx = Math.max(sigTyped.min(2), (long) Math.floor(slabZMin / effZCal));
            final long zMaxPx = Math.min(sigTyped.max(2), (long) Math.ceil(slabZMax / effZCal));
            sigCropped = Views.interval(sigTyped,
                    new long[]{sigTyped.min(0), sigTyped.min(1), zMinPx},
                    new long[]{sigTyped.max(0), sigTyped.max(1), zMaxPx});
            subCropped = Views.interval(subTyped,
                    new long[]{subTyped.min(0), subTyped.min(1), zMinPx},
                    new long[]{subTyped.max(0), subTyped.max(1), zMaxPx});
            calOffset = new double[]{
                    sigTyped.min(0) * sigSrcToWorld.get(0, 0),
                    sigTyped.min(1) * sigSrcToWorld.get(1, 1),
                    zMinPx * effZCal};
        } else {
            sigCropped = sigTyped;
            subCropped = subTyped;
            calOffset = null;
        }

        final ConverterSetup csSig = group.channelSetup(sigIdx);
        final ConverterSetup csSub = group.channelSetup(subIdx);
        final double sigMin = csSig.getDisplayRangeMin();
        final double sigMax = csSig.getDisplayRangeMax();
        final double subMin = csSub.getDisplayRangeMin();
        final double subMax = csSub.getDisplayRangeMax();
        final double sigRange = sigMax - sigMin;
        final double subRange = subMax - subMin;
        final double rangeScale = (subRange > 0) ? sigRange / subRange : 1.0;

        // Memory guard for pyramid sources
        if (slabRequired) {
            final long[] cropDims = {
                    sigCropped.max(0) - sigCropped.min(0) + 1,
                    sigCropped.max(1) - sigCropped.min(1) + 1,
                    sigCropped.max(2) - sigCropped.min(2) + 1};
            final long nPixels = cropDims[0] * cropDims[1] * cropDims[2];
            final long estimatedBytes = nPixels * BYTES_PER_VOXEL;
            final long freeHeapMB = SNTUtils.getHeapInfo().availableMB();
            if (estimatedBytes > memoryBudgetBytes()) {
                refusedState = viewState(sigIdx, subIdx, slabZMin, slabZMax);
                host.setStatus(String.format("Slab too large at mip level %d (%d MB needed, %d MB free). "
                                + "Reduce slab thickness or zoom in to save memory.",
                        bestLevel, estimatedBytes / (1024 * 1024), freeHeapMB));
                return;
            }
        }

        refusedState = null;
        computing = true;
        host.setBusy(true);
        host.setStatus(String.format("Computing (level %d)...", bestLevel));
        owner.updateStatus("Computing channel unmixing...", 0, -1);
        if (worker != null && !worker.isDone()) worker.cancel(true);

        final SwingWorker<Void, Void> w0 = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                final RandomAccessibleInterval<RealType<?>> sigZM = Views.zeroMin(sigCropped);
                final RandomAccessibleInterval<RealType<?>> subZM = Views.zeroMin(subCropped);
                final long[] cropSize = sigZM.dimensionsAsLongArray();

                final ArrayImg<UnsignedShortType, ?> sigMat;
                final ArrayImg<UnsignedShortType, ?> subMat;
                final ArrayImg<UnsignedShortType, ?> mixed;
                try {
                    sigMat = ArrayImgs.unsignedShorts(cropSize[0], cropSize[1], cropSize[2]);
                    LoopBuilder.setImages(sigZM, sigMat)
                            .forEachPixel((a, out) -> out.setReal(a.getRealDouble()));
                    if (isCancelled()) return null;

                    subMat = ArrayImgs.unsignedShorts(cropSize[0], cropSize[1], cropSize[2]);
                    LoopBuilder.setImages(subZM, subMat)
                            .forEachPixel((a, out) -> out.setReal(a.getRealDouble()));
                    if (isCancelled()) return null;

                    mixed = ArrayImgs.unsignedShorts(cropSize[0], cropSize[1], cropSize[2]);
                    LoopBuilder.setImages(sigMat, subMat, mixed)
                            .multiThreaded()
                            .forEachPixel((a, b, out) -> out.setReal(
                                    UnmixEngine.mix(a.getRealDouble(), b.getRealDouble(), w, sigMin, subMin, rangeScale)));
                } catch (final OutOfMemoryError oom) {
                    SwingUtilities.invokeLater(() -> {
                        host.setBusy(false);
                        computing = false;
                        owner.updateStatus("", 0, 0);
                        host.setStatus("Out of memory. Enable Slab View to reduce memory usage.");
                        host.deselectEnable();
                        host.requestCheck();
                    });
                    return null;
                } catch (final RuntimeException ex) {
                    // e.g., a failed block read from the backing store
                    SNTUtils.log("Unmixing failed: " + ex);
                    final String msg = String.valueOf(ex.getMessage());
                    SwingUtilities.invokeLater(() -> {
                        host.setBusy(false);
                        computing = false;
                        owner.updateStatus("", 0, 0);
                        host.setStatus("Unmixing failed: " + msg);
                        host.deselectEnable();
                        host.requestCheck();
                    });
                    return null;
                }

                if (isCancelled()) return null;

                SwingUtilities.invokeLater(() -> {
                    if (isCancelled()) {
                        finish("Cancelled");
                        return;
                    }
                    if (mixedSource != null) mixedSource.removeFromBdv();
                    final BvvOptions addOpt = new BvvOptions().addTo(owner.getBvvHandle());
                    final String srcName = String.format("%s − %.2f × %s", sigName, w, subName);
                    final String unit = owner.getPhysicalUnit();
                    final AffineTransform3D srcT = new AffineTransform3D();
                    srcT.set(sigSrcToWorld);
                    if (calOffset != null) {
                        srcT.set(calOffset[0], 0, 3);
                        srcT.set(calOffset[1], 1, 3);
                        srcT.set(calOffset[2], 2, 3);
                    }
                    final double[] mipCal = new double[]{
                            sigSrcToWorld.get(0, 0),
                            sigSrcToWorld.get(1, 1),
                            effZCal};
                    final SpimDataUtils.CalibratedSource<UnsignedShortType> src =
                            new SpimDataUtils.CalibratedSource<>(mixed,
                                    new UnsignedShortType(),
                                    srcT, srcName, mipCal, unit);
                    mixedSource = BvvFunctions.show((Source) src, 1, addOpt);
                    group.setActive(false);
                    final BvvStackSource<?> ms = mixedSource;
                    final javax.swing.Timer applyRange = new javax.swing.Timer(100, ev -> {
                        ms.setDisplayRange(sigMin, sigMax);
                        ms.setColor(new ARGBType(0x0000FFFF));
                        owner.repaint();
                    });
                    applyRange.setRepeats(false);
                    applyRange.start();
                    if (slabZMin == Double.NEGATIVE_INFINITY) {
                        computedSlabZ[0] = Double.NaN;
                        computedSlabZ[1] = Double.NaN;
                    } else {
                        computedSlabZ[0] = slabZMin;
                        computedSlabZ[1] = slabZMax;
                    }
                    computedDisplayRanges[0] = sigMin;
                    computedDisplayRanges[1] = sigMax;
                    computedDisplayRanges[2] = subMin;
                    computedDisplayRanges[3] = subMax;
                    if (hasPyramid && owner.getViewer() != null) {
                        owner.getViewer().getViewer().showMessage(
                                "Unmixed result is single-resolution (level " + bestLevel + ")");
                    }
                    finish(String.format("Showing %s − %.2f × %s (level %d)", sigName, w, subName, bestLevel));
                });
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                } catch (final java.util.concurrent.CancellationException ignored) {
                    SwingUtilities.invokeLater(() -> finish("Cancelled"));
                } catch (final Exception ex) {
                    SwingUtilities.invokeLater(() -> finish("Error: " + ex.getMessage()));
                }
            }
        };
        worker = w0;
        w0.execute();
    }

    /**
     * Ends a computation: resets busy state, reports {@code status}, and asks the UI to re-validate
     */
    private void finish(final String status) {
        host.setBusy(false);
        computing = false;
        owner.updateStatus("", 0, 0);
        host.setStatus(status);
        host.requestCheck();
    }
}
