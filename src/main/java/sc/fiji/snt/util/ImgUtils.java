/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2025 Fiji developers.
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

package sc.fiji.snt.util;

import ij.ImagePlus;
import ij.measure.Calibration;
import net.imagej.Dataset;
import net.imagej.ImgPlus;
import net.imagej.axis.Axes;
import net.imagej.axis.AxisType;
import net.imagej.axis.CalibratedAxis;
import net.imagej.axis.DefaultLinearAxis;
import net.imglib2.*;
import net.imglib2.display.ColorTable;
import net.imglib2.img.Img;
import net.imglib2.img.ImgView;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.loops.LoopBuilder;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.util.Intervals;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Static utilities for handling and manipulation of {@link RandomAccessibleInterval}s
 *
 * @author Cameron Arshadi
 */
public class ImgUtils {

    static {
        net.imagej.patcher.LegacyInjector.preinit();
    } // required for _every_ class that imports ij. classes

    private ImgUtils() {
    }

    // ============== Axis Utilities ==============

    /**
     * Find dimension indices for X, Y, Z axes in an ImgPlus.
     *
     * @param img the ImgPlus
     * @return int array {xIdx, yIdx, zIdx}, with -1 for missing axes
     */
    public static int[] findSpatialAxisIndices(final ImgPlus<?> img) {
        int xIdx = -1, yIdx = -1, zIdx = -1;
        for (int d = 0; d < img.numDimensions(); d++) {
            final AxisType type = img.axis(d).type();
            if (type == Axes.X) xIdx = d;
            else if (type == Axes.Y) yIdx = d;
            else if (type == Axes.Z) zIdx = d;
        }
        return new int[]{xIdx, yIdx, zIdx};
    }

    /**
     * Find dimension indices for X, Y, Z axes, with fallback to assumed ZYX order.
     *
     * @param img the ImgPlus
     * @return int array {xIdx, yIdx, zIdx}
     */
    public static int[] findSpatialAxisIndicesWithFallback(final ImgPlus<?> img) {
        final int[] indices = findSpatialAxisIndices(img);
        final int ndims = img.numDimensions();

        // Fallback if axes not properly labeled
        if (indices[0] == -1 || indices[1] == -1) {
            if (ndims >= 3) {
                indices[2] = 0; // Z
                indices[1] = 1; // Y
                indices[0] = 2; // X
            } else if (ndims == 2) {
                indices[1] = 0; // Y
                indices[0] = 1; // X
            }
        }
        return indices;
    }

    /**
     * Get the origin offset for a specific axis from an ImgPlus.
     *
     * @param img      the ImgPlus
     * @param axisType the axis type (e.g., Axes.X, Axes.Y, Axes.Z)
     * @return the origin offset in calibrated units, or 0 if not found
     */
    public static double getOrigin(final ImgPlus<?> img, final AxisType axisType) {
        for (int d = 0; d < img.numDimensions(); d++) {
            final CalibratedAxis axis = img.axis(d);
            if (axis.type() == axisType) {
                return axis.calibratedValue(0);
            }
        }
        return 0.0;
    }

    /**
     * Extracts voxel spacing from ImgPlus axis metadata.
     *
     * @param img the ImgPlus with calibrated axes
     * @return array of spacing values, one per dimension (e.g., {x, y, z})
     */
    public static double[] getSpacing(final ImgPlus<?> img) {
        final double[] spacing = new double[img.numDimensions()];
        for (int d = 0; d < spacing.length; d++) {
            spacing[d] = img.axis(d).averageScale(0, 1);
        }
        return spacing;
    }

    /**
     * Get the origin offsets as {xOrigin, yOrigin, zOrigin} from an ImgPlus.
     *
     * @param img the ImgPlus
     * @return array of {xOrigin, yOrigin, zOrigin} in calibrated units
     */
    public static double[] getOrigins(final ImgPlus<?> img) {
        return new double[]{
                getOrigin(img, Axes.X),
                getOrigin(img, Axes.Y),
                getOrigin(img, Axes.Z)
        };
    }

    // ============== Calibration Conversion ==============

    /**
     * Extracts ImageJ1 Calibration from ImgPlus axes, including origin offsets.
     *
     * @param imgPlus the source ImgPlus
     * @return Calibration with pixel sizes, unit, and origins
     */
    public static Calibration getCalibration(final ImgPlus<?> imgPlus) {
        final Calibration cal = new Calibration();
        for (int d = 0; d < imgPlus.numDimensions(); d++) {
            final CalibratedAxis axis = imgPlus.axis(d);
            final double scale = axis.averageScale(0, 1);
            final double origin = axis.calibratedValue(0);
            final AxisType type = axis.type();

            if (type == Axes.X) {
                cal.pixelWidth = scale;
                cal.xOrigin = origin;
                cal.setUnit(axis.unit());
            } else if (type == Axes.Y) {
                cal.pixelHeight = scale;
                cal.yOrigin = origin;
            } else if (type == Axes.Z) {
                cal.pixelDepth = scale;
                cal.zOrigin = origin;
            }
        }
        return cal;
    }

    /**
     * Convert an ImgPlus to an ImageJ1 ImagePlus.
     * <p>
     * Transfers calibration (pixel sizes, unit) and origin offsets from ImgPlus
     * axis metadata to ImagePlus Calibration. Handles dimension interpretation
     * so Z is treated as slices rather than channels.
     * </p>
     *
     * @param <T> the pixel type
     * @param img the source ImgPlus
     * @return ImagePlus with calibration and origin offsets
     */
    public static <T extends NumericType<T>> ImagePlus toImagePlus(final ImgPlus<T> img) {
        return toImagePlus(img, null);
    }

    /**
     * Convert an ImgPlus to an ImageJ1 ImagePlus.
     * <p>
     * Transfers calibration (pixel sizes, unit) and origin offsets from ImgPlus
     * axis metadata to ImagePlus Calibration. Handles dimension interpretation
     * so Z is treated as slices rather than channels.
     * </p>
     *
     * @param <T>  the pixel type
     * @param img  the source ImgPlus
     * @param name name for the ImagePlus, or null to use ImgPlus name
     * @return ImagePlus with calibration and origin offsets
     */
    public static <T extends NumericType<T>> ImagePlus toImagePlus(final ImgPlus<T> img, final String name) {

        // Determine name
        final String impName;
        if (name != null && !name.isEmpty()) {
            impName = name;
        } else {
            final String srcName = img.getName();
            impName = (srcName != null && !srcName.isEmpty()) ? srcName : "image";
        }

        // Wrap to ImagePlus
        final ImagePlus wrapped = ImageJFunctions.wrap(img, impName);

        // Duplicate to force native ImageJ stack (contiguous arrays)
        final ImagePlus imp = wrapped.duplicate();
        imp.setTitle(impName);

        // Fix dimension interpretation: wrap() often misinterprets Z as channels
        // For 3D single-channel images, ensure Z is slices not channels
        final int stackSize = imp.getStackSize();
        if (stackSize > 1 && imp.getNChannels() > 1 && imp.getNSlices() == 1) {
            // Likely Z was interpreted as channels - fix it
            imp.setDimensions(1, stackSize, 1); // nChannels=1, nSlices=Z, nFrames=1
        }

        // Transfer calibration including origins
        final Calibration cal = getCalibration(img);
        imp.setCalibration(cal);

        return imp;
    }

    /**
     * Convert an ImgPlus to an ImagePlus, cropping to a bounding box.
     * <p>
     * Convenience method that combines {@link #crop(ImgPlus, long[], long[], long, boolean)}
     * and {@link #toImagePlus(ImgPlus, String)}.
     * </p>
     *
     * @param <T>       the pixel type
     * @param img       the source ImgPlus
     * @param bboxMin   minimum corner as {x, y, z} in pixel coordinates
     * @param bboxMax   maximum corner as {x, y, z} in pixel coordinates
     * @param padPixels padding around the bounding box
     * @return ImagePlus with calibration and origin offsets from crop region
     */
    public static <T extends NumericType<T> & NativeType<T>> ImagePlus toImagePlus(
            final ImgPlus<T> img,
            final long[] bboxMin,
            final long[] bboxMax,
            final long padPixels,
            final boolean materialize) {

        final ImgPlus<T> cropped = crop(img, bboxMin, bboxMax, padPixels, materialize);
        return toImagePlus(cropped);
    }

    /**
     * Convert an ImgPlus to an ImagePlus, cropping to a bounding box.
     * Convenience overload without padding.
     */
    public static <T extends NumericType<T> & NativeType<T>> ImagePlus toImagePlus(
            final ImgPlus<T> img,
            final long[] bboxMin,
            final long[] bboxMax) {
        return toImagePlus(img, bboxMin, bboxMax, 0, true);
    }

    /**
     * @deprecated Use {@link #getCalibration(ImgPlus)} instead
     */
    @Deprecated
    public static Calibration imgPlusToCalibration(final ImgPlus<?> imgPlus) {
        return getCalibration(imgPlus);
    }

    /**
     * Crop a region from an ImgPlus using (x, y, z) pixel coordinates.
     * <p>
     * Returns a new ImgPlus with calibration preserved and origin offset
     * stored in the axis metadata. The cropped region maintains lazy loading
     * if the source is lazy. Coordinates are clamped to image bounds.
     * </p>
     *
     * @param <T>         the pixel type
     * @param img         the source ImgPlus
     * @param bboxMin     minimum corner as {x, y, z} in pixel coordinates
     * @param bboxMax     maximum corner as {x, y, z} in pixel coordinates
     * @param materialize If false, only a view backed by the source ImgPlus is returned.
     *                    If true, data is copied to a contiguous array
     * @return cropped ImgPlus with origin offset in axis metadata
     */
    public static <T extends NumericType<T> & NativeType<T>> ImgPlus<T> crop(
            final ImgPlus<T> img,
            final long[] bboxMin,
            final long[] bboxMax,
            final boolean materialize) {
        return crop(img, bboxMin, bboxMax, 0, materialize);
    }

    /**
     * Crop a region from an ImgPlus using (x, y, z) pixel coordinates.
     * <p>
     * Returns a new ImgPlus with calibration preserved and origin offset
     * stored in the axis metadata. The cropped region maintains lazy loading
     * if the source is lazy. Coordinates are clamped to image bounds.
     * </p>
     *
     * @param <T>         the pixel type
     * @param img         the source ImgPlus
     * @param bboxMin     minimum corner as {x, y, z} in pixel coordinates
     * @param bboxMax     maximum corner as {x, y, z} in pixel coordinates
     * @param padPixels   padding to add around the bounding box (clamped to image bounds)
     * @param materialize If false, only a view backed by the source ImgPlus is returned.
     *                    If true, data is copied to a contiguous array, and singleton dimensions removed.
     * @return cropped ImgPlus with origin offset in axis metadata
     */
    public static <T extends NumericType<T> & NativeType<T>> ImgPlus<T> crop(
            final ImgPlus<T> img,
            final long[] bboxMin,
            final long[] bboxMax,
            final long padPixels,
            final boolean materialize) {
        return crop(img, bboxMin, bboxMax, padPixels, materialize, materialize);
    }

    /**
     * Crop a region from an ImgPlus using (x, y, z) pixel coordinates.
     * <p>
     * Returns a new ImgPlus with calibration preserved and origin offset
     * stored in the axis metadata. The cropped region maintains lazy loading
     * if the source is lazy. Coordinates are clamped to image bounds.
     * </p>
     *
     * @param <T>         the pixel type
     * @param img         the source ImgPlus
     * @param bboxMin     minimum corner as {x, y, z} in pixel coordinates
     * @param bboxMax     maximum corner as {x, y, z} in pixel coordinates
     * @param padPixels   padding to add around the bounding box (clamped to image bounds)
     * @param materialize If false, only a view backed by the source ImgPlus is returned.
     *                    If true, data is copied to a contiguous array
     * @param dropSingletonDimensions If true, singleton dimensions are removed
     * @return cropped ImgPlus with origin offset in axis metadata
     */
    public static <T extends NumericType<T> & NativeType<T>> ImgPlus<T> crop(
            final ImgPlus<T> img,
            final long[] bboxMin,
            final long[] bboxMax,
            final long padPixels,
            final boolean materialize,
            final boolean dropSingletonDimensions) {
        final int ndims = img.numDimensions();
        if (ndims < 2) {
            throw new IllegalArgumentException("Image must have at least 2 dimensions");
        }

        final int[] axisIdx = findSpatialAxisIndicesWithFallback(img);
        final int xIdx = axisIdx[0], yIdx = axisIdx[1], zIdx = axisIdx[2];

        // Build interval in image dimension order, clamped to image bounds
        final long[] min = new long[ndims];
        final long[] max = new long[ndims];
        for (int d = 0; d < ndims; d++) {
            min[d] = 0;
            max[d] = img.dimension(d) - 1;
        }

        // Map user (x, y, z) to image dimensions with padding and clamping
        if (xIdx >= 0 && bboxMin.length > 0) {
            min[xIdx] = Math.max(0, Math.min(bboxMin[0], bboxMax[0]) - padPixels);
            max[xIdx] = Math.min(img.dimension(xIdx) - 1, Math.max(bboxMin[0], bboxMax[0]) + padPixels);
        }
        if (yIdx >= 0 && bboxMin.length > 1) {
            min[yIdx] = Math.max(0, Math.min(bboxMin[1], bboxMax[1]) - padPixels);
            max[yIdx] = Math.min(img.dimension(yIdx) - 1, Math.max(bboxMin[1], bboxMax[1]) + padPixels);
        }
        if (zIdx >= 0 && bboxMin.length > 2) {
            min[zIdx] = Math.max(0, Math.min(bboxMin[2], bboxMax[2]) - padPixels);
            max[zIdx] = Math.min(img.dimension(zIdx) - 1, Math.max(bboxMin[2], bboxMax[2]) + padPixels);
        }

        final RandomAccessibleInterval<T> cropped = Views.interval(img, new FinalInterval(min, max));
        final Img<T> croppedImg;
        if (materialize) {
            // Copy to contiguous ArrayImg
            final ArrayImgFactory<T> factory = new ArrayImgFactory<>(cropped.getType());
            final Img<T> copy = factory.create(cropped);
            LoopBuilder.setImages(cropped, copy).forEachPixel((s, t) -> t.set(s));
            croppedImg = copy;
        } else {
            croppedImg = ImgView.wrap(cropped);
        }
        final ImgPlus<T> result = new ImgPlus<>(croppedImg);

        // Build new axes with origin offsets
        for (int d = 0; d < ndims; d++) {
            final CalibratedAxis srcAxis = img.axis(d);
            final double scale = srcAxis.averageScale(0, 1);
            final double origin = min[d] * scale;
            final String unit = srcAxis.unit();

            final DefaultLinearAxis newAxis = (unit != null && !unit.isEmpty())
                    ? new DefaultLinearAxis(srcAxis.type(), unit, scale, origin)
                    : new DefaultLinearAxis(srcAxis.type(), scale, origin);
            result.setAxis(newAxis, d);
        }

        // Set name
        final String srcName = img.getName();
        result.setName((srcName != null ? srcName : "image") + "_crop");

        return (dropSingletonDimensions) ? dropSingletonDimensions(result) : result;
    }

    /**
     * Crop a region from a RandomAccessibleInterval using (x, y, z) pixel coordinates.
     * <p>
     * For RAIs without axis metadata, assumes ZYX dimension order.
     * Returns a view (no data copy) with the specified bounds, clamped to image bounds.
     * </p>
     * <p>
     * <b>Important:</b> This method assumes ZYX dimension order (dim0=Z, dim1=Y, dim2=X),
     * which is common for OME-ZARR and N5 datasets. For images with different axis orders,
     * wrap as ImgPlus with proper axis metadata and use {@link #crop(ImgPlus, long[], long[], boolean)}.
     * </p>
     *
     * @param <T>       the pixel type
     * @param rai       the source RandomAccessibleInterval
     * @param bboxMin   minimum corner as {x, y, z} in pixel coordinates
     * @param bboxMax   maximum corner as {x, y, z} in pixel coordinates
     * @param padPixels padding to add around the bounding box
     * @return cropped view as RandomAccessibleInterval
     */
    public static <T> RandomAccessibleInterval<T> crop(
            final RandomAccessibleInterval<T> rai,
            final long[] bboxMin,
            final long[] bboxMax,
            final long padPixels) {

        final int ndims = rai.numDimensions();
        final long[] imgMin = Intervals.minAsLongArray(rai);
        final long[] imgMax = Intervals.maxAsLongArray(rai);
        final long[] min = new long[ndims];
        final long[] max = new long[ndims];

        if (ndims >= 3) {
            // Assume ZYX order: dim0=Z, dim1=Y, dim2=X
            min[2] = Math.max(imgMin[2], Math.min(bboxMin[0], bboxMax[0]) - padPixels);
            max[2] = Math.min(imgMax[2], Math.max(bboxMin[0], bboxMax[0]) + padPixels);
            min[1] = Math.max(imgMin[1], Math.min(bboxMin[1], bboxMax[1]) - padPixels);
            max[1] = Math.min(imgMax[1], Math.max(bboxMin[1], bboxMax[1]) + padPixels);
            min[0] = Math.max(imgMin[0], Math.min(bboxMin[2], bboxMax[2]) - padPixels);
            max[0] = Math.min(imgMax[0], Math.max(bboxMin[2], bboxMax[2]) + padPixels);
        } else if (ndims == 2) {
            min[1] = Math.max(imgMin[1], Math.min(bboxMin[0], bboxMax[0]) - padPixels);
            max[1] = Math.min(imgMax[1], Math.max(bboxMin[0], bboxMax[0]) + padPixels);
            min[0] = Math.max(imgMin[0], Math.min(bboxMin[1], bboxMax[1]) - padPixels);
            max[0] = Math.min(imgMax[0], Math.max(bboxMin[1], bboxMax[1]) + padPixels);
        } else {
            min[0] = Math.max(imgMin[0], bboxMin[0] - padPixels);
            max[0] = Math.min(imgMax[0], bboxMax[0] + padPixels);
        }

        return Views.interval(rai, new FinalInterval(min, max));
    }

    /**
     * @param dimensions
     * @return the index of the largest dimension
     */
    public static int maxDimension(final long[] dimensions) {
        long dimensionMax = Long.MIN_VALUE;
        int dimensionArgMax = -1;
        for (int d = 0; d < dimensions.length; ++d) {
            final long size = dimensions[d];
            if (size > dimensionMax) {
                dimensionMax = size;
                dimensionArgMax = d;
            }
        }
        return dimensionArgMax;
    }

    /**
     * Get a 3D sub-volume of an image, given two corner points and specified padding.
     * <p>
     * Coordinates are in XYZ order. If the input is 2D, a singleton dimension is added.
     * The sub-volume is clamped to image bounds.
     * </p>
     *
     * @param img       the source interval
     * @param x1        x-coordinate of the first corner point
     * @param y1        y-coordinate of the first corner point
     * @param z1        z-coordinate of the first corner point
     * @param x2        x-coordinate of the second corner point
     * @param y2        y-coordinate of the second corner point
     * @param z2        z-coordinate of the second corner point
     * @param padPixels the amount of padding in each dimension, in pixels
     * @param <T>       the pixel type
     * @return the sub-volume
     * @see #crop(RandomAccessibleInterval, long[], long[], long)
     */
    public static <T> RandomAccessibleInterval<T> subVolume(
            RandomAccessibleInterval<T> img,
            final long x1, final long y1, final long z1,
            final long x2, final long y2, final long z2,
            final long padPixels) {

        if (img.numDimensions() == 2) {
            img = Views.addDimension(img, 0, 0);
        }
        // Note: subVolume assumes XYZ dimension order (dim0=X, dim1=Y, dim2=Z)
        // This differs from crop() which assumes ZYX for raw RAI
        final long[] imgMin = Intervals.minAsLongArray(img);
        final long[] imgMax = Intervals.maxAsLongArray(img);
        final Interval interval = Intervals.createMinMax(
                Math.max(imgMin[0], Math.min(x1, x2) - padPixels),
                Math.max(imgMin[1], Math.min(y1, y2) - padPixels),
                Math.max(imgMin[2], Math.min(z1, z2) - padPixels),
                Math.min(imgMax[0], Math.max(x1, x2) + padPixels),
                Math.min(imgMax[1], Math.max(y1, y2) + padPixels),
                Math.min(imgMax[2], Math.max(z1, z2) + padPixels));
        return Views.interval(img, interval);
    }

    /**
     * Get an N-D sub-interval of an N-D image, given two corner points and specified padding.
     * <p>
     * Works in native dimension order (no XYZ remapping).
     * The sub-interval is clamped to image bounds.
     * </p>
     *
     * @param img       the source interval
     * @param p1        the first corner point
     * @param p2        the second corner point
     * @param padPixels the amount of padding in each dimension, in pixels
     * @param <T>       the pixel type
     * @return the sub-interval
     */
    public static <T> RandomAccessibleInterval<T> subInterval(
            final RandomAccessibleInterval<T> img,
            final Localizable p1,
            final Localizable p2,
            final long padPixels) {

        final long[] imgMin = Intervals.minAsLongArray(img);
        final long[] imgMax = Intervals.maxAsLongArray(img);
        final int nDim = img.numDimensions();
        final long[] minmax = new long[2 * nDim];
        for (int d = 0; d < nDim; ++d) {
            minmax[d] = Math.max(imgMin[d], Math.min(p1.getLongPosition(d), p2.getLongPosition(d)) - padPixels);
            minmax[d + nDim] = Math.min(imgMax[d], Math.max(p1.getLongPosition(d), p2.getLongPosition(d)) + padPixels);
        }
        return Views.interval(img, Intervals.createMinMax(minmax));
    }

    /**
     * Partition the source rai into a list of {@link IntervalView} with given dimensions. If the block dimensions are not
     * multiples of the image dimensions, some blocks will have truncated dimensions.
     *
     * @param source          the source rai
     * @param blockDimensions the target block size
     * @param <T>
     * @return the list of blocks
     */
    public static <T> List<IntervalView<T>> splitIntoBlocks(final RandomAccessibleInterval<T> source,
                                                            final long[] blockDimensions) {
        final List<IntervalView<T>> views = new ArrayList<>();
        for (final Interval interval : createIntervals(Intervals.dimensionsAsLongArray(source), blockDimensions))
            views.add(Views.interval(source, interval));

        return views;
    }

    /**
     * Partition the source dimensions into a list of {@link Interval}s with given dimensions. If the block dimensions
     * are not multiples of the image dimensions, some blocks will have slightly different dimensions.
     *
     * @param sourceDimensions the source dimensions
     * @param blockDimensions  the target block size
     * @return the list of Intervals
     */
    public static List<Interval> createIntervals(final long[] sourceDimensions, final long[] blockDimensions) {
        final List<Interval> intervals = new ArrayList<>();
        final long[] min = new long[sourceDimensions.length];
        final long[] max = new long[sourceDimensions.length];
        createBlocksRecursionLoop(intervals, sourceDimensions, blockDimensions, min, max, 0);
        return intervals;
    }

    private static void createBlocksRecursionLoop(final List<Interval> intervals, final long[] sourceDimensions,
                                                  final long[] blockDimensions, final long[] min, final long[] max,
                                                  final int d) {
        if (d == min.length) {
            for (int m = 0; m < min.length; ++m)
                max[m] = Math.min(min[m] + blockDimensions[m] - 1, sourceDimensions[m] - 1);

            intervals.add(new FinalInterval(min, max));
        } else {
            for (min[d] = 0; min[d] < sourceDimensions[d]; min[d] += blockDimensions[d])
                createBlocksRecursionLoop(intervals, sourceDimensions, blockDimensions, min, max, d + 1);
        }
    }

    /**
     * Convert a {@link RandomAccessibleInterval} to an {@link ImagePlus}. If the input has 3 dimensions,
     * the 3rd dimension is treated as depth.
     *
     * @param rai   the source rai
     * @param title the title for the converted ImagePlus
     * @param <T>
     * @return the ImagePlus
     */
    public static <T extends NumericType<T>> ImagePlus raiToImp(final RandomAccessibleInterval<T> rai,
                                                                final String title) {
        RandomAccessibleInterval<T> axisCorrected = rai;
        if (rai.numDimensions() == 3)
            axisCorrected = Views.permute(Views.addDimension(rai, 0, 0), 2, 3);

        return ImageJFunctions.wrap(axisCorrected, title);
    }

    /**
     * Get a 3D view of a {@link Dataset} at the specified channel and frame. If the Dataset is 2D, a singleton dimension
     * is added.
     *
     * @param dataset      the input Dataset
     * @param channelIndex the channel position, 0-indexed
     * @param frameIndex   the time position, 0-indexed
     * @param <T>
     * @return the view rai
     */
    public static <T extends RealType<T>> RandomAccessibleInterval<T> getCtSlice3d(final Dataset dataset,
                                                                                   final int channelIndex,
                                                                                   final int frameIndex) {
        RandomAccessibleInterval<T> slice = getCtSlice(dataset, channelIndex, frameIndex);
        // bump to 3D
        if (slice.numDimensions() == 2)
            slice = Views.addDimension(slice, 0, 0);

        return slice;
    }

    /**
     * Get a view of the {@link Dataset} at the specified channel and frame.
     *
     * @param dataset      the input Dataset
     * @param channelIndex the channel position, 0-indexed
     * @param frameIndex   the time position, 0-indexed
     * @param <T>
     * @return the view RAI
     */
    public static <T extends RealType<T>> RandomAccessibleInterval<T> getCtSlice(final Dataset dataset,
                                                                                 final int channelIndex,
                                                                                 final int frameIndex) {
        @SuppressWarnings("unchecked")
        RandomAccessibleInterval<T> slice = (RandomAccessibleInterval<T>) dataset;
        if (dataset.getFrames() > 1)
            slice = Views.hyperSlice(slice, dataset.dimensionIndex(Axes.TIME), frameIndex);

        // Assuming time always comes after channel, we can use the same index as the Dataset
        if (dataset.getChannels() > 1)
            slice = Views.hyperSlice(slice, dataset.dimensionIndex(Axes.CHANNEL), channelIndex);

        return slice;
    }

    public static <T extends RealType<T>> RandomAccessibleInterval<T> getCtSlice(final ImagePlus imp) {
        RandomAccessibleInterval<T> img = ImgUtils.impToRealRai5d(imp);
        // Extract the relevant part of the imp
        img = Views.hyperSlice(img, 2, imp.getChannel() - 1);
        img = Views.hyperSlice(img, 3, imp.getFrame() - 1);
        // If Z is a singleton dimension, drop it
        return Views.dropSingletonDimensions(img);
    }

    /**
     * Extract a specific channel/time slice from an ImgPlus.
     *
     * @param imgPlus source image
     * @param channel channel index to extract, or null to keep all/squeeze singleton
     * @param time    time index to extract, or null to keep all/squeeze singleton
     * @return SliceResult with extracted image and tracked indices
     */
    public static <T extends RealType<T>> SliceResult<T> getCtSlice(
            final ImgPlus<T> imgPlus,
            final int channel,
            final int time) {

        RandomAccessibleInterval<T> view = imgPlus;

        int channelDim = imgPlus.dimensionIndex(Axes.CHANNEL);
        int timeDim = imgPlus.dimensionIndex(Axes.TIME);

        int extractedChannel;
        int extractedTime;

        // Track dimension shifts after slicing
        int dimOffset = 0;

        // Always remove channel dimension if it exists
        if (channelDim >= 0) {
            long numChannels = imgPlus.dimension(channelDim);
            extractedChannel = channel < 0 ? 0 : channel;  // Negative → default to 0
            if (extractedChannel >= numChannels) {
                throw new IllegalArgumentException(
                        "Channel index " + extractedChannel + " out of bounds [0, " + numChannels + ")");
            }
            view = Views.hyperSlice(view, channelDim - dimOffset, extractedChannel);
            dimOffset++;
        } else {
            if (channel > 0) {  // User requested specific channel, but none exists
                throw new IllegalArgumentException(
                        "Channel " + channel + " requested but image has no channel axis");
            }
            extractedChannel = -1;
        }

        // Always remove time dimension if it exists
        if (timeDim >= 0) {
            long numTimepoints = imgPlus.dimension(timeDim);
            extractedTime = Math.max(time, 0);  // Negative → default to 0
            if (extractedTime >= numTimepoints) {
                throw new IllegalArgumentException(
                        "Time index " + extractedTime + " out of bounds [0, " + numTimepoints + ")");
            }
            view = Views.hyperSlice(view, timeDim - dimOffset, extractedTime);
        } else {
            if (time > 0) {  // User requested specific time, but none exists
                throw new IllegalArgumentException(
                        "Time " + time + " requested but image has no time axis");
            }
            extractedTime = -1;
        }

        // Wrap and create result
        final Img<T> wrappedImg = ImgView.wrap(view);
        final ImgPlus<T> result = new ImgPlus<>(wrappedImg);

        // Preserve basic metadata
        result.setName(buildSliceName(imgPlus.getName(), extractedChannel, extractedTime));
        if (imgPlus.getSource() != null) {
            result.setSource(imgPlus.getSource());
        }

        // Copy axes for remaining dimensions
        copyAxes(imgPlus, result, channelDim, timeDim, extractedChannel, extractedTime);

        // Copy channel metadata for the extracted channel
        copyChannelMetadata(imgPlus, result, extractedChannel);

        // Copy properties
        final Map<String, Object> srcProps = imgPlus.getProperties();
        if (srcProps != null && !srcProps.isEmpty()) {
            result.getProperties().putAll(srcProps);
        }

        return new SliceResult<>(result, extractedChannel, extractedTime);
    }

    /**
     * Extracts a channel/time slice by squeezing singleton dimensions.
     *
     * <p>
     * Convenience overload that removes any singleton (size=1) channel
     * or time dimensions from the image. Non-singleton C/T dimensions are preserved.
     * </p>
     *
     * @param <T>     pixel type
     * @param imgPlus source image
     * @return result containing the squeezed image and indices of any removed
     * singleton dimensions (0 if squeezed, -1 if axis didn't exist or wasn't squeezed)
     * @see #getCtSlice(ImgPlus, int, int) for extracting specific C/T indices
     */
    public static <T extends RealType<T>> SliceResult<T> getCtSlice(final ImgPlus<T> imgPlus) {
        return getCtSlice(imgPlus, 0, 0);
    }

    private static <T extends RealType<T>> void copyAxes(
            ImgPlus<T> source,
            ImgPlus<T> dest,
            int channelDim,
            int timeDim,
            int extractedChannel,
            int extractedTime) {

        int destAxisIndex = 0;
        for (int d = 0; d < source.numDimensions(); d++) {
            // Skip dimensions that were sliced out
            final boolean wasSliced = (d == channelDim && extractedChannel >= 0)
                    || (d == timeDim && extractedTime >= 0);

            if (!wasSliced && destAxisIndex < dest.numDimensions()) {
                dest.setAxis(source.axis(d).copy(), destAxisIndex++);
            }
        }
    }

    private static <T extends RealType<T>> void copyChannelMetadata(
            ImgPlus<T> source,
            ImgPlus<T> dest,
            int extractedChannel) {

        // Determine source channel index for metadata
        final int srcChannel = Math.max(0, extractedChannel);

        // Check how many channels dest has
        final int destChannelDim = dest.dimensionIndex(Axes.CHANNEL);
        final int numDestChannels = destChannelDim >= 0 ? (int) dest.dimension(destChannelDim) : 1;

        if (extractedChannel >= 0) {
            // Single channel extracted - copy its metadata to channel 0
            copyChannelProps(source, srcChannel, dest, 0);
        } else {
            // Multiple channels remain - copy all
            for (int c = 0; c < numDestChannels; c++) {
                copyChannelProps(source, c, dest, c);
            }
        }
    }

    private static <T extends RealType<T>> void copyChannelProps(
            ImgPlus<T> source, int srcChannel,
            ImgPlus<T> dest, int destChannel) {
        final double min = source.getChannelMinimum(srcChannel);
        final double max = source.getChannelMaximum(srcChannel);
        if (!Double.isNaN(min)) dest.setChannelMinimum(destChannel, min);
        if (!Double.isNaN(max)) dest.setChannelMaximum(destChannel, max);
        final ColorTable lut = source.getColorTable(srcChannel);
        if (lut != null) dest.setColorTable(lut, destChannel);
    }

    private static String buildSliceName(String baseName, final int channel, final int time) {
        if (baseName == null) baseName = "image";
        final StringBuilder sb = new StringBuilder(baseName);
        if (channel >= 0 || time >= 0) {
            sb.append(" [");
            if (channel >= 0) sb.append("C=").append(channel);
            if (channel >= 0 && time >= 0) sb.append(", ");
            if (time >= 0) sb.append("T=").append(time);
            sb.append("]");
        }
        return sb.toString();
    }

    /**
     * Get a view of the {@link ImagePlus} at the specified channel and frame.
     *
     * @param imp     the input ImagePlus
     * @param channel the channel position, 1-indexed (as per ImagePlus convention)
     * @param frame   the time position, 1-indexed (as per ImagePlus convention)
     * @param <T>
     * @return the view RAI
     */
    public static <T extends RealType<T>> RandomAccessibleInterval<T> getCtSlice3d(final ImagePlus imp, final int channel,
                                                                                   final int frame) {
        RandomAccessibleInterval<T> img = ImgUtils.impToRealRai5d(imp);
        // Extract the relevant part of the imp
        img = Views.hyperSlice(img, 2, channel - 1);
        img = Views.hyperSlice(img, 3, frame - 1);
        // bump to 3D
        if (img.numDimensions() == 2)
            img = Views.addDimension(img, 0, 0);
        return img;
    }

    /**
     * Wrap an {@link ImagePlus} to a {@link RandomAccessibleInterval} such that the number of dimensions in
     * the resulting rai is 5 and the axis order is XYCZT.
     * Axes that are not present in the input imp have singleton dimensions in the rai.
     * <p>
     * For example, given a 2D, multichannel imp, the dimensions of the result rai are
     * [ |X|, |Y|, |C|, 1, 1 ]
     *
     * @param imp
     * @param <T>
     * @return the 5D rai
     */
    public static <T extends RealType<T>> RandomAccessibleInterval<T> impToRealRai5d(
            final ImagePlus imp) {
        // Note that ImageJFunctions.wrapReal will keep the same dimensions of the input ImagePlus, like so:
        // XY imp -> [X,Y]; XYZ -> [X,Y,Z]; XYC -> [X,Y,C]; XYT -> [X,Y,T]; XYCZT -> [X,Y,C,Z,T], ie., a 2D ImagePlus
        // does not have other zero dimensions
        RandomAccessibleInterval<T> out = ImageJFunctions.wrapReal(imp);
        if (imp.getNChannels() <= 1) { // No C axis
            out = Views.permute(Views.addDimension(out, 0, 0), 2, out.numDimensions());
        }
        if (imp.getNSlices() <= 1) { // No Z axis
            out = Views.permute(Views.addDimension(out, 0, 0), 3, out.numDimensions());
        }
        if (imp.getNFrames() <= 1) { // No T axis
            out = Views.permute(Views.addDimension(out, 0, 0), 4, out.numDimensions());
        }
        return out;
    }

    /**
     * Checks if pos is outside the bounds given by min and max
     *
     * @param pos the position to check
     * @param min the minimum of the interval
     * @param max the maximum of the interval
     * @return true if pos is out of bounds, false otherwise
     */
    public static boolean outOfBounds(final long[] pos, final long[] min, final long[] max) {
        for (int d = 0; d < pos.length; d++)
            if (pos[d] < min[d] || pos[d] > max[d])
                return true;

        return false;
    }

    /**
     * Remove singleton dimensions from an ImgPlus, preserving axis metadata.
     *
     * @param <T>  the pixel type
     * @param img  the source ImgPlus (e.g., 5D XYZCT with C=1, T=1)
     * @return ImgPlus with singleton dimensions removed
     */
    public static <T extends NumericType<T>> ImgPlus<T> dropSingletonDimensions(final ImgPlus<T> img) {
        // Count non-singleton dimensions
        final int ndims = img.numDimensions();
        final List<Integer> keepDims = new ArrayList<>();
        for (int d = 0; d < ndims; d++) {
            if (img.dimension(d) > 1) {
                keepDims.add(d);
            }
        }

        if (keepDims.size() == ndims) {
            return img; // No singleton dimensions
        }

        // Progressively slice out singleton dimensions (from highest to lowest to preserve indices)
        RandomAccessibleInterval<T> view = img;
        for (int d = ndims - 1; d >= 0; d--) {
            if (img.dimension(d) == 1) {
                view = Views.hyperSlice(view, d, 0);
            }
        }

        // Build new ImgPlus with remaining axes
        final Img<T> wrapped = ImgView.wrap(view);
        final ImgPlus<T> result = new ImgPlus<>(wrapped);
        result.setName(img.getName());

        for (int i = 0; i < keepDims.size(); i++) {
            result.setAxis(img.axis(keepDims.get(i)), i);
        }

        return result;
    }

    /**
     * Wrap a RandomAccessibleInterval with axis metadata from a source ImgPlus.
     * <p>
     * Useful for wrapping op results with proper calibration.
     * </p>
     *
     * @param <T>    the pixel type
     * @param rai    the RAI to wrap
     * @param source the source ImgPlus providing axis metadata
     * @param name   name for the result
     * @return ImgPlus with copied axis metadata
     * @throws IllegalArgumentException if dimensions don't match
     */
    public static <T extends NumericType<T>> ImgPlus<T> wrapWithAxes(
            final RandomAccessibleInterval<T> rai,
            final ImgPlus<?> source,
            final String name) {

        final int ndims = rai.numDimensions();
        if (ndims != source.numDimensions()) {
            throw new IllegalArgumentException(
                    "Dimension mismatch: RAI has " + ndims + ", source has " + source.numDimensions());
        }

        final Img<T> wrapped = (rai instanceof Img) ? (Img<T>) rai : ImgView.wrap(rai);
        final ImgPlus<T> result = new ImgPlus<>(wrapped);
        result.setName(name != null ? name : "result");

        for (int d = 0; d < ndims; d++) {
            result.setAxis(source.axis(d), d);
        }

        return result;
    }

    /**
     * Computes the mean intensity of an image.
     *
     * @param source the input image
     * @return the mean intensity value
     */
    public static double computeMeanIntensity(
            final RandomAccessibleInterval<? extends RealType<?>> source) {
        double sum = 0;
        long count = 0;
        final Cursor<? extends RealType<?>> cursor = Views.flatIterable(source).cursor();
        while (cursor.hasNext()) {
            cursor.fwd();
            sum += cursor.get().getRealDouble();
            count++;
        }
        return count > 0 ? sum / count : 0;
    }

    /**
     * Computes basic intensity statistics of an image.
     *
     * @param source the input image
     * @return array of [min, max, mean]
     */
    public static double[] computeIntensityStats(
            final RandomAccessibleInterval<? extends RealType<?>> source) {
        double min = Double.MAX_VALUE;
        double max = Double.NEGATIVE_INFINITY;
        double sum = 0;
        long count = 0;

        final Cursor<? extends RealType<?>> cursor = Views.flatIterable(source).cursor();
        while (cursor.hasNext()) {
            cursor.fwd();
            final double val = cursor.get().getRealDouble();
            if (val < min) min = val;
            if (val > max) max = val;
            sum += val;
            count++;
        }

        return new double[]{
                count > 0 ? min : 0,
                count > 0 ? max : 0,
                count > 0 ? sum / count : 0
        };
    }

    /**
     * Result of a channel/time slice extraction from an {@link ImgPlus}.
     *
     * @param <T>          pixel type
     * @param img          the extracted image slice with preserved metadata
     * @param channelIndex the channel index that was extracted (0-based),
     *                     or -1 if no channel axis existed or no channel was extracted
     * @param timeIndex    the time index that was extracted (0-based),
     *                     or -1 if no time axis existed or no timepoint was extracted
     */
    public record SliceResult<T extends RealType<T>>(ImgPlus<T> img, int channelIndex, int timeIndex) {
    }

}
