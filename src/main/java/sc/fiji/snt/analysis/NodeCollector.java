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
package sc.fiji.snt.analysis;

import ij.ImagePlus;
import ij.measure.Calibration;
import ij.plugin.MontageMaker;
import net.imagej.ImgPlus;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.ImgView;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.loops.LoopBuilder;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.view.Views;
import sc.fiji.snt.Path;
import sc.fiji.snt.Tree;
import sc.fiji.snt.util.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/**
 * Collects fixed-size image neighborhoods ("crops") centered on a collection of points (typically Tree tips, branch
 * points, roots, bookmarks, or seeds) and exposes them as RAIs, an {@link ImagePlus} stack, or a montage.
 * <p>
 * The class operates on an {@link ImgPlus} so it works with lazy, tile/cell-backed sources (OME-Zarr, N5, HDF5) for
 * large datasets: with the default lazy mode, only the cells overlapping each crop are touched. Materialization to an
 * {@link ImagePlus} happens only on demand (e.g., when {@link #getStack()} or {@link #getMontage()} is called), so even
 * thousands of small (e.g. 20x20) crops can fit in-RAM.
 * <p>
 * Out-of-bounds windows (e.g. tips near the image border) are padded with zeros so that every crop in the output has
 * identical dimensions and the resulting montage forms a uniform grid.
 * <p>
 * Constructors and factories:
 * <ul>
 *   <li>{@link #NodeCollector(ImgPlus, Collection)} - any collection of
 *       {@link SNTPoint}s (tips, BPs, bookmarks, seeds, ...).</li>
 *   <li>{@link #fromTips(ImgPlus, Tree)},
 *       {@link #fromBranchPoints(ImgPlus, Tree)},
 *       {@link #fromRoots(ImgPlus, Collection)},
 *       {@link #fromNodes(ImgPlus, Tree, int)} - Tree-aware sugar.</li>
 * </ul>
 *
 * @param <T> the pixel type of the source image
 * @author Tiago Ferreira
 */
public class NodeCollector<T extends RealType<T> & NativeType<T>> {

    static { net.imagej.patcher.LegacyInjector.preinit(); }

    /**  Z-handling strategies for the per-crop window. */
    public enum Projection {
        /** Keep the z-slab as-is (output is 3D unless windowZ == 1). */
        NONE,
        /** Reduce the z-slab to a single 2D slice via max-intensity projection. */
        MIP_Z
    }

    private final ImgPlus<T> img;
    private final Collection<? extends SNTPoint> points;

    private int windowX = 20;
    private int windowY = 20;
    private int windowZ = 1;

    private int channel = -1;       // -1 -> infer from path (else 0)
    private int frame = -1;         // -1 -> infer from path (else 0)
    private boolean materialize = false;
    private boolean pointsInPixelSpace = false;
    private Projection projection = Projection.NONE;
    private Function<SNTPoint, String> labelProvider;

    /**
     * Instantiates a NodeCollector from an arbitrary collection of points.
     *
     * @param img    the source image. Lazy/cell-backed ImgPluses (OME-Zarr, N5, HDF5) are supported transparently
     * @param points the points around which crops will be extracted. Points are interpreted in calibrated units,
     *               matching the image axis metadata. If a point's {@code onPath} is set, the path's own calibration
     *               is used for pixel conversion instead; otherwise pixel coordinates are derived from the image axes
     */
    public NodeCollector(final ImgPlus<T> img, final Collection<? extends SNTPoint> points) {
        if (img == null) throw new IllegalArgumentException("img cannot be null");
        if (points == null || points.isEmpty())
            throw new IllegalArgumentException("points cannot be null or empty");
        this.img = img;
        this.points = points;
    }

    /** Convenience factory using the tips of {@code tree}. */
    public static <T extends RealType<T> & NativeType<T>> NodeCollector<T> fromTips(final ImgPlus<T> img, final Tree tree) {
        return new NodeCollector<>(img, tree.getTips());
    }

    /** Convenience factory using the branch points of {@code tree}. */
    public static <T extends RealType<T> & NativeType<T>> NodeCollector<T> fromBranchPoints(final ImgPlus<T> img, final Tree tree) {
        return new NodeCollector<>(img, tree.getBPs());
    }

    /** Convenience factory using the root of every supplied {@code tree}. */
    public static <T extends RealType<T> & NativeType<T>> NodeCollector<T> fromRoots(final ImgPlus<T> img, final Collection<Tree> trees) {
        final List<PointInImage> roots = new ArrayList<>(trees.size());
        for (final Tree t : trees) {
            final PointInImage r = t.getRoot();
            if (r != null) roots.add(r);
        }
        return new NodeCollector<>(img, roots);
    }

    /**
     * Convenience factory sampling every {@code everyNth} node across all paths in {@code tree}.
     *
     * @param everyNth stride (1 = every node)
     */
    public static <T extends RealType<T> & NativeType<T>> NodeCollector<T> fromNodes(final ImgPlus<T> img, final Tree tree, final int everyNth) {
        if (everyNth < 1) throw new IllegalArgumentException("everyNth must be >= 1");
        final List<PointInImage> nodes = new ArrayList<>();
        for (final Path p : tree.list()) {
            for (int i = 0; i < p.size(); i += everyNth) nodes.add(p.getNode(i));
        }
        return new NodeCollector<>(img, nodes);
    }

    /**
     * Sets the size of the crop window in pixels.
     *
     * @param wxy XY edge length (in pixels). Must be {@code >= 1}
     * @param wz  Z slab thickness (in pixels). 1 -> 2D crop; values {@code > 1} produce a 3D slab that can be
     *            optionally projected via {@link #setProjection(Projection)}
     */
    public NodeCollector<T> setWindow(final int wxy, final int wz) {
        return setWindow(wxy, wxy, wz);
    }

    /** Sets the per-axis crop window (X, Y, Z) in pixels. */
    public NodeCollector<T> setWindow(final int wx, final int wy, final int wz) {
        if (wx < 1 || wy < 1 || wz < 1)
            throw new IllegalArgumentException("Window dimensions must be >= 1");
        this.windowX = wx;
        this.windowY = wy;
        this.windowZ = wz;
        return this;
    }

    /**
     * Sets the channel to crop from (0-indexed). By default, the channel of the point's {@link Path#getChannel()
     * owning Path} is used, falling back to 0 for points with no associated Path.
     *
     * @param channel 0-indexed channel, or a negative value to restore the "infer from path" default
     */
    public NodeCollector<T> setChannel(final int channel) {
        this.channel = channel;
        return this;
    }

    /**
     * Sets the time frame to crop from (0-indexed). By default, the frame of the point's owning Path is used, falling
     * back to 0 for points with no associated Path.
     */
    public NodeCollector<T> setFrame(final int frame) {
        this.frame = frame;
        return this;
    }

    /**
     * If true, crops are materialized to contiguous arrays. If false (default), each crop is a lazy
     * {@link Views#interval} view into the source, suitable for very large lazy-loaded sources. Materialization is
     * forced when an {@link ImagePlus} is requested via {@link #getStack()} or {@link #getMontage()}.
     */
    public NodeCollector<T> setMaterialize(final boolean materialize) {
        this.materialize = materialize;
        return this;
    }

    /**
     * If true, the {@code x/y/z} of input points are treated as pixel coordinates (no calibration conversion is
     * performed).
     * <p>
     * If false (default), points are assumed to be in calibrated units; SNT points carrying an {@code onPath}
     * reference are unscaled via the path's calibration, otherwise the image's axis metadata is used.
     * </p>
     */
    public NodeCollector<T> setPointsInPixelSpace(final boolean pointsInPixelSpace) {
        this.pointsInPixelSpace = pointsInPixelSpace;
        return this;
    }

    /**
     * Sets the z-projection applied to each crop. {@link Projection#NONE} (default) returns the full slab;
     * {@link Projection#MIP_Z} reduces the z-slab of each crop to a single 2D slice via max-intensity projection,
     * which is usually what is wanted for a printable montage.
     */
    public NodeCollector<T> setProjection(final Projection projection) {
        this.projection = (projection == null) ? Projection.NONE : projection;
        return this;
    }

    /**
     * Provides per-crop slice labels (used by {@link #getStack()} and {@link #getMontage(int, int, double, int, boolean)}
     * when labels are enabled). If unset, labels default to {@code "tip-i  pathName"}.
     */
    public NodeCollector<T> setLabelProvider(final Function<SNTPoint, String> labelProvider) {
        this.labelProvider = labelProvider;
        return this;
    }

    /**
     * Returns the per-point crops as a list of {@link ImgPlus}es.
     * <p>
     * If {@link #setMaterialize(boolean) materialize} is false (default), the returned ImgPluses are zero-padded lazy
     * views (out-of-bounds samples yield the zero value of {@code T}). If true, each ImgPlus is backed by a contiguous
     * {@link Img}.
     * </p>
     */
    public List<ImgPlus<T>> getCrops() {
        final List<ImgPlus<T>> out = new ArrayList<>(points.size());
        for (final SNTPoint p : points) out.add(cropAround(p));
        return out;
    }

    /**
     * Materializes the crops into a multi-slice {@link ImagePlus} (one slice per point, in iteration order).
     */
    public ImagePlus getStack() {
        final List<ImgPlus<T>> crops = getCrops();
        final List<ImagePlus> imps = new ArrayList<>(crops.size());
        int idx = 0;
        for (final ImgPlus<T> c : crops) {
            final ImagePlus imp = ImgUtils.toImagePlus(c);
            imp.setTitle(buildLabel(pointAt(idx), idx));
            imps.add(imp);
            idx++;
        }
        final ImagePlus stack = ImpUtils.toStack(imps);
        stack.setTitle("Node Crops [" + imps.size() + "]");
        propagateCalibration(stack);
        return stack;
    }

    /**
     * Convenience for {@link #getMontage(int, int, double, int, boolean)} with an automatic grid (cols = ceil(sqrt(n))),
     * scale 1, no border, without labels.
     */
    public ImagePlus getMontage() {
        return getMontage(-1, -1, 1.0, 0, false);
    }

    /**
     * Builds a montage of the crops via IJ1's {@link MontageMaker}.
     *
     * @param cols   number of columns
     * @param rows   number of rows
     * @param scale  resizing scale of each panel
     * @param border border thickness in pixels
     * @param labels whether to render slice labels on each panel
     * @return the montage image
     */
    public ImagePlus getMontage(final int cols, final int rows, final double scale,
                                final int border, final boolean labels) {
        final ImagePlus stack = getStack();
        final int n = stack.getStackSize();
        final int[] layout = (cols < 1 || rows < 1) ? getColsAndRows() : new int[]{cols, rows};
        final ImagePlus montage = new MontageMaker().makeMontage2(
                stack, layout[0], layout[1], scale, 1, n, 1, border, labels);
        montage.setTitle("Node Crops Montage [" + n + "]");
        return montage;
    }

    private int[] getColsAndRows() {
        final int n = points.size();
        final int cols, rows;
        if (n < 11) {
            cols = n;
            rows = 1;
        } else {
            final int s = (int) Math.sqrt(n);
            rows = s;
            cols = s + (int) Math.ceil((n - s * s) / (double) s);
        }
        return new int[] {cols, rows};
    }

    private ImgPlus<T> cropAround(final SNTPoint point) {
        // 1) Resolve C/T (per-point: point's Path overrides class defaults only when class default is "infer", i.e. negative)
        final int cIdx = (channel >= 0) ? channel : inferChannel(point);
        final int tIdx = (frame >= 0) ? frame : inferFrame(point);

        // 2) Extract a 3D (or 2D) slice from the source ImgPlus
        final ImgPlus<T> spatial = ImgUtils.getCtSlice(img, cIdx, tIdx).img();

        // 3) Convert point to pixel coords
        final long[] px = toPixelCoords(point);

        // 4) Build the bbox (centered, half-window on each side; window is  sampled inclusively across windowX/Y/Z pixels)
        final long halfX = windowX / 2;
        final long halfY = windowY / 2;
        final long halfZ = windowZ / 2;
        // For odd window sizes [px - half, px + half] -> exactly windowX pixels.
        // For even, we bias the high side so [px - half, px + (window - half - 1)].
        final long[] bMin = new long[]{px[0] - halfX,
                px[1] - halfY,
                px[2] - halfZ};
        final long[] bMax = new long[]{px[0] + (windowX - halfX - 1),
                px[1] + (windowY - halfY - 1),
                px[2] + (windowZ - halfZ - 1)};

        // 5) Crop with zero-padding (do NOT clamp to image bounds, so all  crops have identical dimensions for a uniform montage)
        final ImgPlus<T> crop = zeroPaddedCrop(spatial, bMin, bMax);

        // 6) Optional z-projection (gracefully no-ops if there's no Z axis)
        return (projection == Projection.MIP_Z) ? ImgUtils.maxIntensityProjection(crop) : crop;
    }

    /**
     * Like {@link ImgUtils#crop} but pads out-of-bounds regions with zeros. Preserves calibration.
     */
    private ImgPlus<T> zeroPaddedCrop(final ImgPlus<T> spatial,
                                      final long[] bMin, final long[] bMax) {
        final int nDims = spatial.numDimensions();
        final int[] axisIdx = ImgUtils.findSpatialAxisIndicesWithFallback(spatial);
        final int xIdx = axisIdx[0], yIdx = axisIdx[1], zIdx = axisIdx[2];

        final long[] min = new long[nDims];
        final long[] max = new long[nDims];
        for (int d = 0; d < nDims; d++) {
            min[d] = spatial.min(d);
            max[d] = spatial.max(d);
        }
        if (xIdx >= 0) {
            min[xIdx] = bMin[0];
            max[xIdx] = bMax[0];
        }
        if (yIdx >= 0) {
            min[yIdx] = bMin[1];
            max[yIdx] = bMax[1];
        }
        if (zIdx >= 0) {
            min[zIdx] = bMin[2];
            max[zIdx] = bMax[2];
        }

        // Zero-padded view: extendZero gives the zero value of T outside the
        // image, Views.interval then carves out exactly the requested window
        final RandomAccessibleInterval<T> view = Views.interval(Views.extendZero(spatial), new FinalInterval(min, max));

        final RandomAccessibleInterval<T> rai;
        if (materialize) {
            final ArrayImgFactory<T> factory = new ArrayImgFactory<>(spatial.getType());
            final Img<T> copy = factory.create(view);
            LoopBuilder.setImages(view, copy).forEachPixel((s, t) -> t.set(s));
            rai = copy;
        } else {
            rai = view;
        }
        // Wrap back to ImgPlus, preserving axes & calibration (origin offsets updated to reflect the crop position)
        final ImgPlus<T> out = new ImgPlus<>(ImgView.wrap(rai), spatial.getName() + "_crop");
        for (int d = 0; d < nDims; d++) {
            final net.imagej.axis.CalibratedAxis src = spatial.axis(d);
            final double scale = src.averageScale(0, 1);
            final double origin = min[d] * scale;
            final String unit = src.unit();
            final net.imagej.axis.DefaultLinearAxis a =
                    (unit != null && !unit.isEmpty())
                            ? new net.imagej.axis.DefaultLinearAxis(src.type(), unit, scale, origin)
                            : new net.imagej.axis.DefaultLinearAxis(src.type(), scale, origin);
            out.setAxis(a, d);
        }
        return out;
    }


    private long[] toPixelCoords(final SNTPoint p) {
        // Pixel-space override: use coords as-is (e.g., clicked points)
        if (pointsInPixelSpace)
            return new long[]{Math.round(p.getX()), Math.round(p.getY()), Math.round(p.getZ())};
        // Prefer Path-based unscaling when available (matches Path#getXUnscaled, etc.); fall back to ImgPlus axes
        if (p instanceof PointInImage pim && pim.onPath != null) {
            final PointInCanvas q = pim.getUnscaledPoint();
            return new long[]{Math.round(q.x), Math.round(q.y), Math.round(q.z)};
        }
        final double[] spacing = ImgUtils.getSpacing(img);
        final double[] origins = ImgUtils.getOrigins(img);
        final double sx = spacing[0] == 0 ? 1 : spacing[0];
        final double sy = spacing[1] == 0 ? 1 : spacing[1];
        final double sz = spacing.length > 2 && spacing[2] != 0 ? spacing[2] : 1;
        return new long[]{
                Math.round((p.getX() - origins[0]) / sx),
                Math.round((p.getY() - origins[1]) / sy),
                Math.round((p.getZ() - origins[2]) / sz)
        };
    }

    private int inferChannel(final SNTPoint p) {
        if (p instanceof PointInImage pim && pim.onPath != null)
            return Math.max(0, pim.onPath.getChannel() - 1); // Path C/T are 1-indexed
        return 0;
    }

    private int inferFrame(final SNTPoint p) {
        if (p instanceof PointInImage pim && pim.onPath != null)
            return Math.max(0, pim.onPath.getFrame() - 1);
        return 0;
    }

    private String buildLabel(final SNTPoint p, final int idx) {
        if (labelProvider != null) {
            final String s = labelProvider.apply(p);
            if (s != null) return s;
        }
        if (p instanceof PointInImage pim && pim.onPath != null)
            return idx + "  " + pim.onPath.getName();
        return "node-" + idx;
    }

    private SNTPoint pointAt(final int idx) {
        // points is a Collection; iterate cheaply (caller passes sequential idx)
        int i = 0;
        for (final SNTPoint p : points) {
            if (i == idx) return p;
            i++;
        }
        return null;
    }

    private void propagateCalibration(final ImagePlus stack) {
        final Calibration cal = ImgUtils.getCalibration(img);
        // Reset origins: each slice has its own origin from the crop, so the
        // stack's global origin is not meaningful - propagate scale + unit only
        final Calibration sc = stack.getCalibration();
        sc.pixelWidth = cal.pixelWidth;
        sc.pixelHeight = cal.pixelHeight;
        sc.pixelDepth = cal.pixelDepth;
        sc.setUnit(cal.getUnit());
    }

}
