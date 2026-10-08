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

package sc.fiji.snt.util;

import ij.ImagePlus;
import ij.measure.Calibration;
import io.scif.services.DatasetIOService;
import net.imagej.Dataset;
import net.imagej.DatasetService;
import net.imagej.ImgPlus;
import net.imagej.axis.Axes;
import net.imagej.axis.AxisType;
import net.imagej.axis.CalibratedAxis;
import net.imagej.axis.DefaultLinearAxis;
import net.imagej.axis.LinearAxis;
import net.imglib2.*;
import net.imglib2.RandomAccess;
import net.imglib2.algorithm.stats.ComputeMinMax;
import net.imglib2.converter.Converters;
import net.imglib2.converter.RealUnsignedShortConverter;
import net.imglib2.display.ColorTable;
import net.imglib2.img.Img;
import net.imglib2.img.ImgView;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.img.imageplus.ImagePlusImg;
import net.imglib2.img.imageplus.ImagePlusImgFactory;
import net.imglib2.exception.ImgLibException;
import net.imglib2.loops.LoopBuilder;
import net.imglib2.type.NativeType;
import net.imglib2.type.logic.BitType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import org.scijava.Context;
import org.scijava.io.IOService;
import sc.fiji.snt.SNTUtils;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Static utilities for handling and manipulation of {@link RandomAccessibleInterval}s
 *
 * @author Cameron Arshadi
 */
public class ImgUtils {

    static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

    private ImgUtils() {
    }


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
     * Returns the voxel spacing for a specific axis type, defaulting to 1.0 if
     * the axis is absent or has zero scale. Unlike {@link #getSpacing(ImgPlus)},
     * this is axis-order-independent.
     */
    public static double getSpacing(final ImgPlus<?> img, final net.imagej.axis.AxisType axisType) {
        for (int d = 0; d < img.numDimensions(); d++) {
            final CalibratedAxis axis = img.axis(d);
            if (axis.type() == axisType) {
                final double s = axis.averageScale(0, 1);
                return s == 0 ? 1.0 : s;
            }
        }
        return 1.0;
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

        // Fix dimension interpretation: ImageJFunctions.wrap() maps dimensions naively
        // by index (dim2>C, dim3>Z) regardless of axis metadata, so any axis order
        // other than XYCZT produces wrong C/Z/T values. Always derive them from the
        // ImgPlus axis metadata instead.
        int nC = 1, nZ = 1, nT = 1;
        for (int d = 0; d < img.numDimensions(); d++) {
            final AxisType type = img.axis(d).type();
            if      (type == Axes.CHANNEL) nC = (int) img.dimension(d);
            else if (type == Axes.Z)       nZ = (int) img.dimension(d);
            else if (type == Axes.TIME)    nT = (int) img.dimension(d);
        }
        imp.setDimensions(nC, nZ, nT);

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

        // Start with full extent for all dimensions: only spatial dims are cropped
        final long[] min = imgMin.clone();
        final long[] max = imgMax.clone();

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
        final int[] blockSize = new int[blockDimensions.length];
        for (int d = 0; d < blockSize.length; d++) blockSize[d] = (int) blockDimensions[d];
        return net.imglib2.algorithm.util.Grids.collectAllContainedIntervals(sourceDimensions, blockSize);
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
     * Faster, single-copy alternative to {@code raiToImp(rai, title).duplicate()}. That combination routes
     * through a lazy {@link net.imglib2.img.display.imagej.ImageJVirtualStack} (which performs a full copy
     * of every slice on first/every access) and then {@link ImagePlus#duplicate()} (which performs a SECOND
     * full, single-threaded copy of every stack slice. For large crops this double-copy, single-threaded path
     * may be quite heavy.
     * <p>
     * This method instead copies {@code rai} directly into an {@link ImagePlusImg}-backed destination, in one
     * pass, optionally multithreaded via {@link LoopBuilder}. {@link ImagePlusImg}'s own per-plane pixel arrays
     * ARE the {@link ImagePlus}'s eventual {@code ImageStack} slice arrays (see {@code ByteImagePlus} and its
     * siblings), so {@link ImagePlusImg#getImagePlus()} needs no further copy.
     * <p>
     * Only pixel types {@link ImagePlusImg} can back with a real {@link ImagePlus} are supported: in practice
     * {@code byte}/{@code short}/{@code int}/{@code float} {@link NativeType}s, i.e. everything
     * {@link #raiToImp(RandomAccessibleInterval, String)} is normally called with here. Anything else throws
     * {@link ImgLibException}; callers wanting a guaranteed-to-work fallback should catch it and fall back to
     * {@code raiToImp(rai, title).duplicate()}.
     *
     * @param rai   same contract as {@link #raiToImp(RandomAccessibleInterval, String)}
     * @param title the title for the returned ImagePlus
     * @param <T>   the pixel type; must be {@link NativeType} (required by {@link ImagePlusImgFactory})
     * @return an eagerly-populated, real (non-virtual) ImagePlus, built in a single copy pass
     * @throws ImgLibException if {@code rai}'s pixel type has no {@link ImagePlus}-backed
     *                         {@link ImagePlusImg} representation
     */
    public static <T extends NativeType<T>> ImagePlus raiToImpFast(final RandomAccessibleInterval<T> rai,
                                                                     final String title) throws ImgLibException {
        RandomAccessibleInterval<T> axisCorrected = rai;
        if (rai.numDimensions() == 3)
            axisCorrected = Views.permute(Views.addDimension(rai, 0, 0), 2, 3);

        final ImagePlusImgFactory<T> factory = new ImagePlusImgFactory<>(axisCorrected.getType().createVariable());
        final ImagePlusImg<T, ?> dest = factory.create(axisCorrected);
        LoopBuilder.setImages(axisCorrected, dest).multiThreaded().forEachPixel((in, out) -> out.set(in));

        final ImagePlus imp = dest.getImagePlus();
        imp.setTitle(title);
        return imp;
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

        final int timeDim = dataset.dimensionIndex(Axes.TIME);
        final int channelDim = dataset.dimensionIndex(Axes.CHANNEL);

        // Slice TIME first, then CHANNEL. After removing the TIME dimension,
        // any axis that was positioned after it shifts down by one
        if (dataset.getFrames() > 1 && timeDim >= 0) {
            slice = Views.hyperSlice(slice, timeDim, frameIndex);
        }

        if (dataset.getChannels() > 1 && channelDim >= 0) {
            // Adjust channel index if TIME was removed before it
            final int adjustedChannelDim = (dataset.getFrames() > 1 && timeDim >= 0 && timeDim < channelDim)
                    ? channelDim - 1
                    : channelDim;
            slice = Views.hyperSlice(slice, adjustedChannelDim, channelIndex);
        }

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
            extractedChannel = Math.max(channel, 0);  // Negative -> default to 0
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
            extractedTime = Math.max(time, 0);  // Negative -> default to 0
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

        // Initialize color table slots before copying channel metadata.
        // ImgPlus.setColorTable uses ArrayList.set(), which throws if the list is empty.
        final int destChannelDim = result.dimensionIndex(Axes.CHANNEL);
        final int numDestChannels = destChannelDim >= 0 ? (int) result.dimension(destChannelDim) : 1;
        result.initializeColorTables(numDestChannels);

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
     * Gets a 4D (X, Y, Z, C) view of the {@link ImagePlus} at the current time frame,
     * retaining all channels. This is useful for multichannel/spectral operations
     * that need access to all channel data simultaneously.
     * <p>
     * The returned RAI has axis order [X, Y, Z, C], obtained from the canonical
     * 5D [X, Y, C, Z, T] representation by fixing T and permuting C past Z.
     *
     * @param imp the input ImagePlus (must have at least 2 channels)
     * @param <T> the pixel type
     * @return 4D RAI with axis order [X, Y, Z, C], or null if the image has fewer than 2 channels
     */
    public static <T extends RealType<T>> RandomAccessibleInterval<T> getXYZCImage(final ImagePlus imp) {
        if (imp.getNChannels() < 2) return null;
        // impToRealRai5d returns [X, Y, C, Z, T]
        RandomAccessibleInterval<T> img = impToRealRai5d(imp);
        // Fix T at current frame (0-indexed)
        img = Views.hyperSlice(img, 4, imp.getFrame() - 1);
        // Now [X, Y, C, Z]: permute to [X, Y, Z, C]
        img = Views.permute(img, 2, 3);
        return img;
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
            result.setAxis(source.axis(d).copy(), d);
        }

        return result;
    }

    /**
     * Wraps a RandomAccessibleInterval as an ImgPlus with calibrated axes.
     * <p>
     * Creates an ImgPlus with proper spatial axis metadata (X, Y, Z) and the
     * specified voxel spacing. This is useful when working with RAIs that lack
     * calibration metadata.
     * </p>
     *
     * @param rai     the source image
     * @param spacing voxel spacing for each dimension (e.g., [x, y] or [x, y, z]).
     *                If null, defaults to 1.0 for all dimensions. If shorter than
     *                the number of dimensions, missing values default to 1.0.
     *                If longer, extra values are ignored (with warning logged).
     * @param unit    the spatial unit (e.g., "µm", "mm"), or null for no unit
     * @return an ImgPlus with calibrated spatial axes
     * @throws IllegalArgumentException if rai is null or any spacing value is not positive
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static ImgPlus<?> wrapWithSpacing(
            final RandomAccessibleInterval<?> rai, final double[] spacing, final String unit) {

        if (rai == null) {
            throw new IllegalArgumentException("Source image cannot be null");
        }

        final int nDims = rai.numDimensions();

        // Validate and build spacing array
        final double[] validatedSpacing = new double[nDims];
        for (int d = 0; d < nDims; d++) {
            if (spacing == null || d >= spacing.length) {
                validatedSpacing[d] = 1.0;
            } else if (spacing[d] <= 0) {
                throw new IllegalArgumentException(
                        "Invalid spacing[" + d + "]=" + spacing[d] + ". Spacing must be positive.");
            } else {
                validatedSpacing[d] = spacing[d];
            }
        }

        // Log dimension mismatch
//        if (spacing != null && spacing.length != nDims) {
//            SNTUtils.log("ImgUtils.wrapWithSpacing: Spacing length (" + spacing.length +
//                    ") != image dimensions (" + nDims + "). Using: " + Arrays.toString(validatedSpacing));
//        }

        // Wrap RAI as Img if needed - use raw types to avoid generic binding issues
        final Img img = (rai instanceof Img) ? (Img) rai : ImgView.wrap((RandomAccessibleInterval) rai);
        final ImgPlus result = new ImgPlus(img);

        // Create calibrated axes: XYZCT matches the canonical order used by
        // permuteToXYZCT(), impToRealRai5d(), and BVV. Previously this was XYZTC,
        // which would mis-label Channel as Time (and vice versa) for 5D images
        final AxisType[] defaultAxes = {Axes.X, Axes.Y, Axes.Z, Axes.CHANNEL, Axes.TIME};
        for (int d = 0; d < nDims; d++) {
            final AxisType axisType = (d < defaultAxes.length) ? defaultAxes[d] : Axes.unknown();
            final DefaultLinearAxis axis = (unit != null && !unit.isEmpty())
                    ? new DefaultLinearAxis(axisType, unit, validatedSpacing[d], 0.0)
                    : new DefaultLinearAxis(axisType, validatedSpacing[d], 0.0);
            result.setAxis(axis, d);
        }

        return result;
    }

    /**
     * Wraps a RandomAccessibleInterval as an ImgPlus with calibrated axes (no unit).
     *
     * @param rai     the source image
     * @param spacing voxel spacing for each dimension
     * @return an ImgPlus with calibrated spatial axes
     * @throws IllegalArgumentException if rai is null or any spacing value is not positive
     * @see #wrapWithSpacing(RandomAccessibleInterval, double[], String)
     */
    public static ImgPlus<?> wrapWithSpacing(final RandomAccessibleInterval<?> rai, final double[] spacing) {
        return wrapWithSpacing(rai, spacing, null);
    }

    /**
     * Computes a type-preserving max-intensity projection along the Z axis of an {@link ImgPlus}.
     * <p>
     * The Z axis is located via {@link Axes#Z} metadata, falling back to dim 2 if no labeled Z axis is found. The input
     * pixel type {@code T} is preserved in the output, and the non-Z axes' calibration is copied across.
     * <p>
     * If the source has no Z axis (e.g., a 2D image), the source is returned unchanged. If the Z axis exists but has
     * depth 1, a 2D copy is returned.
     *
     * @param <T>    pixel type (must support comparison; satisfied by every  {@link RealType})
     * @param source the input image
     * @return a 2D max-intensity projection preserving {@code T}, or the original source if it has no Z axis
     */
    public static <T extends RealType<T> & NativeType<T>> ImgPlus<T> maxIntensityProjection(final ImgPlus<T> source) {
        if (source == null) throw new IllegalArgumentException("source cannot be null");

        // Locate Z; if no labeled Z axis assume dim 2
        int zIdx = -1;
        for (int d = 0; d < source.numDimensions(); d++)
            if (source.axis(d).type() == Axes.Z) { zIdx = d; break; }
        if (zIdx < 0 && source.numDimensions() >= 3) zIdx = 2;
        if (zIdx < 0) return source;

        final long w = source.dimension(0);
        final long h = source.dimension(1);
        final long depth = source.dimension(zIdx);

        final ArrayImgFactory<T> factory = new ArrayImgFactory<>(source.getType());
        final Img<T> mip = factory.create(w, h);

        // Walk pixel by pixel; for the typical small slabs this is negligible
        final RandomAccess<T> dst = mip.randomAccess();
        final RandomAccess<T> src = source.randomAccess();
        final T tmp = source.getType().createVariable();
        for (long y = 0; y < h; y++) {
            for (long x = 0; x < w; x++) {
                src.setPosition(x, 0);
                src.setPosition(y, 1);
                src.setPosition(0L, zIdx);
                tmp.set(src.get());
                for (long z = 1; z < depth; z++) {
                    src.setPosition(z, zIdx);
                    if (src.get().compareTo(tmp) > 0) tmp.set(src.get());
                }
                dst.setPosition(x, 0);
                dst.setPosition(y, 1);
                dst.get().set(tmp);
            }
        }
        final ImgPlus<T> out = new ImgPlus<>(mip, source.getName() + "_MIP");
        // Copy non-Z axes
        int target = 0;
        for (int d = 0; d < source.numDimensions() && target < 2; d++) {
            if (d == zIdx) continue;
            out.setAxis(source.axis(d).copy(), target++);
        }
        return out;
    }

    /**
     * Result of {@link #maxProjectionWithDepth}.
     *
     * @param max   the max-intensity projection (type preserving)
     * @param depth for each pixel of {@code max}, the index of the plane (in the coordinates of the projected
     *              source) at which the maximum was found. The first plane wins on ties
     */
    public record MaxProjection<T extends RealType<T>>(Img<T> max, Img<FloatType> depth) {}

    /**
     * Computes a slab max-intensity projection of a (possibly lazy, e.g., cell-cached) image along an arbitrary
     * axis, also keeping track of the depth at which each maximum occurs. The source is read plane by plane and
     * only the 2D results are held in memory, so this is suitable for slabs of large datasets (see
     * {@link #maxIntensityProjection(ImgPlus)} for the ImgPlus/Z-only counterpart). To project a multichannel
     * image, slice the channel first, e.g., with {@link Views#hyperSlice(RandomAccessibleInterval, int, long)}
     *
     * @param <T>  the pixel type
     * @param rai  the 3D source
     * @param axis the dimension to project along (e.g., 2 for Z in XYZ data)
     * @param min  first plane of the slab (clamped to the source bounds)
     * @param max  last plane of the slab, inclusive (clamped to the source bounds)
     * @return the projection and its depth map
     * @throws IllegalArgumentException if {@code rai} is not 3D, {@code axis} is invalid, or the slab does not
     *                                  overlap the source
     */
    public static <T extends RealType<T> & NativeType<T>> MaxProjection<T> maxProjectionWithDepth(
            final RandomAccessibleInterval<T> rai, final int axis, final long min, final long max) {
        if (rai == null) throw new IllegalArgumentException("rai cannot be null");
        if (rai.numDimensions() != 3) throw new IllegalArgumentException("Only 3D images are supported");
        if (axis < 0 || axis > 2) throw new IllegalArgumentException("Invalid axis: " + axis);
        final long lo = Math.max(Math.min(min, max), rai.min(axis));
        final long hi = Math.min(Math.max(min, max), rai.max(axis));
        if (lo > hi) throw new IllegalArgumentException("Slab does not overlap the image");

        final long[] dims = new long[2];
        for (int d = 0, i = 0; d < 3; d++)
            if (d != axis) dims[i++] = rai.dimension(d);
        final Img<T> mip = new ArrayImgFactory<>(rai.getType().createVariable()).create(dims);
        final Img<FloatType> depth = new ArrayImgFactory<>(new FloatType()).create(dims);

        // Lazy sources (e.g., Imaris/HDF5) are not safe to read from many threads at once (the HDF5 lib serializes on a
        // global monitor and has crashed the JVM under contention). Stage each plane serially, reduce in parallel
        final Img<T> plane = new ArrayImgFactory<>(rai.getType().createVariable()).create(dims);
        for (long p = lo; p <= hi; p++) {
            LoopBuilder.setImages(Views.zeroMin(Views.hyperSlice(rai, axis, p)), plane)
                    .forEachPixel((s, t) -> t.set(s));
            if (p == lo) {
                LoopBuilder.setImages(plane, mip, depth).multiThreaded().forEachPixel((s, m, d) -> {
                    m.set(s);
                    d.setReal(lo);
                });
                continue;
            }
            final float z = p;
            LoopBuilder.setImages(plane, mip, depth).multiThreaded().forEachPixel((s, m, d) -> {
                if (s.compareTo(m) > 0) {
                    m.set(s);
                    d.setReal(z);
                }
            });
        }
        return new MaxProjection<>(mip, depth);
    }

    /**
     * Computes a depth color-coded slab max-intensity projection (analogous to ImageJ's "Temporal-Color Code"
     * or "Hyperstack Depth Color" tools). Hue is determined by the depth of the maximum within the slab, and
     * brightness by the intensity of the maximum.
     *
     * @param <T>        the pixel type
     * @param rai        the 3D single-channel source
     * @param axis       the dimension to project along (e.g., 2 for Z in XYZ data)
     * @param min        first plane of the slab (clamped to the source bounds)
     * @param max        last plane of the slab, inclusive (clamped to the source bounds)
     * @param lut        the color table mapping depth to color (e.g., {@link ColorMaps#get(String)}). Null
     *                   defaults to {@link ColorMaps#VIRIDIS}
     * @param displayMin the intensity mapped to black. Ignored (together with {@code displayMax}) if
     *                   {@code displayMax <= displayMin}, in which case the range of the projection is used
     * @param displayMax the intensity mapped to full color brightness
     * @return a 2D RGB image (uncalibrated)
     * @see #maxProjectionWithDepth(RandomAccessibleInterval, int, long, long)
     */
    public static <T extends RealType<T> & NativeType<T>> ImagePlus depthCodedProjection(
            final RandomAccessibleInterval<T> rai, final int axis, final long min, final long max,
            final ColorTable lut, final double displayMin, final double displayMax) {
        final MaxProjection<T> proj = maxProjectionWithDepth(rai, axis, min, max);
        final ColorTable table = (lut == null) ? ColorMaps.VIRIDIS : lut;
        final long lo = Math.max(Math.min(min, max), rai.min(axis));
        final long hi = Math.min(Math.max(min, max), rai.max(axis));

        double dMin = displayMin;
        double dMax = displayMax;
        if (dMax <= dMin) {
            dMin = Double.MAX_VALUE;
            dMax = -Double.MAX_VALUE;
            for (final T t : proj.max()) {
                final double v = t.getRealDouble();
                if (v < dMin) dMin = v;
                if (v > dMax) dMax = v;
            }
        }
        final double range = (dMax > dMin) ? dMax - dMin : 1;
        final double depthRange = (hi > lo) ? hi - lo : 1;
        final int nColors = table.getLength();

        final int w = (int) proj.max().dimension(0);
        final int h = (int) proj.max().dimension(1);
        final int[] pixels = new int[w * h];
        final Cursor<T> mc = Views.flatIterable(proj.max()).cursor();
        final Cursor<FloatType> dc = Views.flatIterable(proj.depth()).cursor();
        int idx = 0;
        while (mc.hasNext()) {
            final double brightness = Math.max(0, Math.min(1, (mc.next().getRealDouble() - dMin) / range));
            final int c = (int) Math.round((dc.next().get() - lo) / depthRange * (nColors - 1));
            final int r = (int) Math.round(table.get(ColorTable.RED, c) * brightness);
            final int g = (int) Math.round(table.get(ColorTable.GREEN, c) * brightness);
            final int b = (int) Math.round(table.get(ColorTable.BLUE, c) * brightness);
            pixels[idx++] = (r << 16) | (g << 8) | b;
        }
        return new ImagePlus("DepthCoded_MIP", new ij.process.ColorProcessor(w, h, pixels));
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
     * Computes an approximate percentile by random sampling.
     *
     * @param source     the input image
     * @param percentile the percentile to compute (0-100)
     * @return the approximate percentile value
     */
    public static double computePercentile(final RandomAccessibleInterval<? extends RealType<?>> source,
                                           final double percentile) {

        final long totalPixels = source.size();
        final int maxSamples = 100_000;
        final double sampleRate = Math.min(1.0, (double) maxSamples / totalPixels);

        final List<Double> samples = new ArrayList<>();
        final Random rand = new Random(42);  // Fixed seed for reproducibility

        final Cursor<? extends RealType<?>> cursor = Views.flatIterable(source).cursor();
        while (cursor.hasNext()) {
            cursor.fwd();
            if (sampleRate >= 1.0 || rand.nextDouble() < sampleRate) {
                samples.add(cursor.get().getRealDouble());
            }
        }

        if (samples.isEmpty()) {
            return 0;
        }

        Collections.sort(samples);
        final int index = (int) Math.round((percentile / 100.0) * (samples.size() - 1));
        return samples.get(Math.min(index, samples.size() - 1));
    }

    public static boolean isBinary(final RandomAccessibleInterval<?> rai) {
        if (rai == null || rai.size() == 0) return false;
        // Instant check for BitType
        if (rai.firstElement() instanceof BitType) return true;
        // Early exit on 3rd unique value
        final Set<Double> unique = new HashSet<>(4);
        boolean hasRealPixels = false;
        for (final Object pixel : Views.flatIterable(rai)) {
            if (pixel instanceof RealType) {
                hasRealPixels = true;
                unique.add(((RealType<?>) pixel).getRealDouble());
                if (unique.size() > 2) return false;  // Early exit
            }
        }
        return hasRealPixels && unique.size() <= 2;
    }

    /**
     * Opens an image file using SCIFIO, supporting lazy loading for large formats.
     * <p>
     * Supports standard formats (TIFF, PNG, JPEG) and big data formats with
     * lazy loading (N5, Zarr, HDF5, OME-TIFF). Large datasets are opened as
     * virtual/lazy images without loading the entire file into memory.
     * </p>
     *
     * @param filePathOrUrl path to image file or URL
     * @return ImgPlus (may be lazy-loaded for large formats), or null if opening fails
     */
    public static ImgPlus<?> open(final String filePathOrUrl) {
        if (filePathOrUrl == null || filePathOrUrl.isEmpty()) {
            throw new IllegalArgumentException("File path or URL cannot be null or empty");
        }
        try {
            final Context context = SNTUtils.getContext();
            final IOService io = context.getService(IOService.class);
            if (io == null) {
                throw new IllegalStateException("IOService not available");
            }
            SNTUtils.log("Opening: " + filePathOrUrl);
            final Object opened = io.open(filePathOrUrl);
            if (opened instanceof Dataset) {
                final ImgPlus<?> imgPlus = ((Dataset) opened).getImgPlus();
                SNTUtils.log("Opened as Dataset (SCIFIO): " +
                        imgPlus.numDimensions() + "D, " +
                        imgPlus.getImg().getClass().getSimpleName());
                SNTUtils.log(axisReport(imgPlus));
                return imgPlus;
            } else if (opened instanceof ImgPlus<?> imgPlus) {
                SNTUtils.log("Opened as ImgPlus: " +
                        imgPlus.getImg().getClass().getSimpleName());
                SNTUtils.log(axisReport(imgPlus));
                return imgPlus;
            } else if (opened instanceof Img) {
                SNTUtils.log("Opened as Img, wrapping in ImgPlus");
                return new ImgPlus<>((Img<?>) opened);
            } else if (opened != null) {
                throw new IllegalArgumentException(
                        "Unsupported type returned by IOService: " +
                                opened.getClass().getName());
            }
            SNTUtils.error("Failed to open: " + filePathOrUrl);
            return null;

        } catch (final IOException e) {
            SNTUtils.error("Failed to open: " + filePathOrUrl, e);
            return null;
        }
    }

    /**
     * Downsamples an {@link ImgPlus} so that no spatial dimension exceeds
     * {@code maxDimSize}. Only spatial axes (X, Y, Z) are downsampled;
     * Channel and Time axes are left untouched.
     * <p>
     * Uses nearest-neighbor subsampling ({@link Views#subsample}) for speed.
     * The calibration of downsampled axes is scaled accordingly so that the
     * physical extent of the image is preserved.
     * </p>
     *
     * @param imgPlus    the image to downsample
     * @param maxDimSize maximum allowed size for any spatial dimension
     *                   (e.g. {@code GL_MAX_3D_TEXTURE_SIZE})
     * @param <T>        pixel type
     * @return a (possibly lazy) downsampled ImgPlus, or the original if no
     *         dimension exceeds the limit
     */
    public static <T extends RealType<T>> ImgPlus<T> downsampleToFit(final ImgPlus<T> imgPlus,
                                                                      final int maxDimSize) {
        return downsampleToFit(imgPlus, maxDimSize, Long.MAX_VALUE);
    }

    /**
     * Same as {@link #downsampleToFit(ImgPlus, int)}, but additionally caps the number of voxels of the spatial
     * (X, Y, Z) volume, e.g., to fit BVV's single-texture capacity. After satisfying {@code maxDimSize}, the step of
     * the axis with the finest physical voxel size is repeatedly increased until the cap is met, which keeps the
     * resulting voxels as isotropic as possible
     *
     * @param imgPlus    the image to downsample
     * @param maxDimSize maximum allowed size for any spatial dimension
     * @param maxVoxels  maximum allowed number of voxels in the spatial volume (per channel/frame)
     * @param <T>        pixel type
     * @return a (possibly lazy) downsampled ImgPlus, or the original if no limit is exceeded
     */
    public static <T extends RealType<T>> ImgPlus<T> downsampleToFit(final ImgPlus<T> imgPlus,
                                                                      final int maxDimSize, final long maxVoxels) {
        final int nDims = imgPlus.numDimensions();
        final long[] steps = new long[nDims];
        boolean needsDownsample = false;
        for (int d = 0; d < nDims; d++) {
            final AxisType type = imgPlus.axis(d).type();
            if (type == Axes.CHANNEL || type == Axes.TIME) {
                steps[d] = 1;
            } else {
                steps[d] = Math.max(1, (long) Math.ceil((double) imgPlus.dimension(d) / maxDimSize));
                if (steps[d] > 1) needsDownsample = true;
            }
        }
        while (spatialVoxels(imgPlus, steps) > maxVoxels) {
            int finest = -1;
            double finestSize = Double.MAX_VALUE;
            for (int d = 0; d < nDims; d++) {
                final AxisType type = imgPlus.axis(d).type();
                if (type == Axes.CHANNEL || type == Axes.TIME || imgPlus.dimension(d) / steps[d] < 2) continue;
                final double size = imgPlus.averageScale(d) * steps[d];
                if (size < finestSize) {
                    finestSize = size;
                    finest = d;
                }
            }
            if (finest < 0) break; // nothing left to subsample
            steps[finest]++;
            needsDownsample = true;
        }
        if (!needsDownsample) return imgPlus;

        final StringBuilder sb = new StringBuilder("ImgUtils.downsampleToFit: steps=[");
        for (int d = 0; d < nDims; d++) {
            if (d > 0) sb.append(',');
            sb.append(steps[d]);
        }
        sb.append(']');

        final RandomAccessibleInterval<T> sub = Views.subsample(imgPlus, steps);
        final Img<T> wrapped = ImgView.wrap(sub, imgPlus.factory());
        // Build axis array with adjusted calibration
        final CalibratedAxis[] axes = new CalibratedAxis[nDims];
        for (int d = 0; d < nDims; d++) {
            final CalibratedAxis src = imgPlus.axis(d);
            final double scale = imgPlus.averageScale(d) * steps[d];
            axes[d] = new DefaultLinearAxis(src.type(), src.unit(), scale);
        }
        final ImgPlus<T> result = new ImgPlus<>(wrapped, imgPlus.getName(), axes);
        sb.append(" >> dims=[");
        for (int d = 0; d < nDims; d++) {
            if (d > 0) sb.append(',');
            sb.append(result.dimension(d));
        }
        sb.append(']');
        SNTUtils.log(sb.toString());
        return result;
    }

    private static double spatialVoxels(final ImgPlus<?> imgPlus, final long[] steps) {
        double voxels = 1;
        for (int d = 0; d < imgPlus.numDimensions(); d++) {
            final AxisType type = imgPlus.axis(d).type();
            if (type == Axes.CHANNEL || type == Axes.TIME) continue;
            voxels *= Math.ceil((double) imgPlus.dimension(d) / steps[d]);
        }
        return voxels;
    }

    /**
     * Checks whether the spatial (X, Y, Z) volume of an {@link ImgPlus} has more voxels than the given limit.
     * Channel and Time axes are ignored.
     *
     * @param imgPlus   the image to check
     * @param maxVoxels maximum allowed number of voxels per channel/frame
     * @return {@code true} if the spatial volume exceeds the limit
     */
    public static boolean exceedsVoxelCount(final ImgPlus<?> imgPlus, final long maxVoxels) {
        final long[] ones = new long[imgPlus.numDimensions()];
        java.util.Arrays.fill(ones, 1L);
        return spatialVoxels(imgPlus, ones) > maxVoxels;
    }

    /**
     * Checks whether any spatial dimension of an {@link ImgPlus} exceeds the
     * given limit. Only X, Y, and Z axes are considered.
     *
     * @param imgPlus    the image to check
     * @param maxDimSize maximum allowed size (e.g., {@code GL_MAX_3D_TEXTURE_SIZE})
     * @return {@code true} if at least one spatial dimension exceeds the limit
     */
    public static boolean exceedsDimension(final ImgPlus<?> imgPlus, final int maxDimSize) {
        for (int d = 0; d < imgPlus.numDimensions(); d++) {
            final AxisType type = imgPlus.axis(d).type();
            if (type == Axes.CHANNEL || type == Axes.TIME) continue;
            if (imgPlus.dimension(d) > maxDimSize) return true;
        }
        return false;
    }

    /**
     * Copies a (possibly lazy/remote) view into a local, contiguous  {@link ArrayImgFactory}-backed image. Use before
     * repeated random-access operations on a view backed by something expensive to re-read, e.g. a remote N5/Zarr source.
     *
     * @param source the view to copy
     * @param <T>    pixel type
     * @return a local, fully-realized copy of {@code source}
     */
    public static <T extends NativeType<T>> Img<T> materialize(final RandomAccessibleInterval<T> source) {
        final ArrayImgFactory<T> factory = new ArrayImgFactory<>(source.getType());
        final Img<T> copy = factory.create(source);
        LoopBuilder.setImages(source, copy).multiThreaded().forEachPixel((s, t) -> t.set(s));
        return copy;
    }

    /**
     * Copies a (possibly lazy/remote) view into a local {@link net.imglib2.img.cell.CellImg}. Unlike
     * {@link #materialize}, the result is an {@code AbstractCellImg}, which is what BVV requires to stream tiles
     * through its GPU cache instead of uploading the whole volume as a single 3D texture. It also avoids the 2^31
     * elements limit of {@code ArrayImg}
     *
     * @param source   the view to copy
     * @param cellSize edge length of the cells (clamped to the image dimensions)
     * @param <T>      pixel type
     * @return a local, zero-min, fully-realized, cell-based copy of {@code source}
     */
    public static <T extends NativeType<T>> Img<T> materializeTiled(final RandomAccessibleInterval<T> source,
                                                                    final int cellSize) {
        final RandomAccessibleInterval<T> zeroMin = Views.zeroMin(source);
        final int[] cellDims = new int[source.numDimensions()];
        for (int d = 0; d < cellDims.length; d++)
            cellDims[d] = (int) Math.max(1, Math.min(cellSize, source.dimension(d)));
        final Img<T> copy = new net.imglib2.img.cell.CellImgFactory<>(source.getType(), cellDims).create(zeroMin);
        LoopBuilder.setImages(zeroMin, copy).multiThreaded().forEachPixel((s, t) -> t.set(s));
        return copy;
    }

    /**
     * Writes an image as a multiscale OME-Zarr (OME-NGFF v0.5, Zarr v3) directory. Level 0 is streamed from
     * {@code img} one slab of Z planes at a time (so every plane is read once), and each coarser level is computed
     * from the previous one, one block at a time, by averaging 2x2x2 neighborhoods. Peak memory is therefore a
     * single slab plus the blocks in flight, regardless of image size. Chunks are zstd-compressed and, for larger levels, grouped into shards (one file per shard)
     * <p>
     * Levels are halved along X, Y and Z until a level fits a 3D texture (2048 voxels per side, and
     * {@link sc.fiji.snt.viewer.BvvUtils#MAX_SINGLE_TEXTURE_VOXELS}), with a minimum of 3 levels
     *
     * @param img      image with X, Y, Z leading, an optional channel axis, and no time series (see
     *                 {@link #normalizeToXYZ})
     * @param zarrDir  the output directory (typically ending in {@code .ome.zarr}), which should not exist
     * @param nThreads number of threads used for computing and compressing blocks
     * @param progress optional receiver of short status messages, or {@code null}
     * @param <T>      pixel type
     * @throws IllegalArgumentException if the axes layout is not XYZ[C]
     * @throws IOException              if writing fails
     */
    public static <T extends RealType<T> & NativeType<T>> void saveAsOmeZarr(final ImgPlus<T> img,
                                                                              final File zarrDir,
                                                                              final int nThreads,
                                                                              final java.util.function.Consumer<String> progress)
            throws IOException {
        if (!hasXYZLeading(img))
            throw new IllegalArgumentException("OME-Zarr export requires X, Y, Z[, C] axes: " + axisReport(img));
        RandomAccessibleInterval<T> rai = img;
        final int tDim = img.dimensionIndex(Axes.TIME);
        if (tDim >= 0) {
            if (img.dimension(tDim) > 1)
                throw new IllegalArgumentException("OME-Zarr export does not support time series: " + axisReport(img));
            rai = Views.hyperSlice(rai, tDim, 0);
        }
        final RandomAccessibleInterval<T> src = Views.zeroMin(rai);
        final int nDim = src.numDimensions(); // 3 (XYZ) or 4 (XYZC)
        final long[] dims0 = Intervals.dimensionsAsLongArray(src);
        final int[] blockSize = new int[nDim];
        // Chunks match the cells BVV streams (64^3, see BvvUtils), a whole multiple of its 32^3 GPU tiles: reading a
        // cell then decodes exactly one chunk
        for (int d = 0; d < 3; d++) blockSize[d] = (int) Math.min(OME_ZARR_CHUNK_SIZE, dims0[d]);
        if (nDim == 4) blockSize[3] = 1;
        final long[] blockDims = new long[nDim];
        for (int d = 0; d < nDim; d++) blockDims[d] = blockSize[d];

        final List<long[]> levelDims = omeZarrLevelDims(dims0);
        // zstd decodes much faster than gzip at similar ratios, and it is an official zarr v3 codec.
        // Level 3: higher levels give little extra compression but slow down writes (and reads)
        final org.janelia.scicomp.n5.zstandard.ZstandardCompression compression =
                new org.janelia.scicomp.n5.zstandard.ZstandardCompression(OME_ZARR_ZSTD_LEVEL);
        compression.setUseChecksums(false);
        final org.janelia.saalfeldlab.n5.DataType dataType = n5DataType(src.getType());
        final java.util.concurrent.ExecutorService exec = java.util.concurrent.Executors
                .newFixedThreadPool(Math.max(1, nThreads));
        final org.janelia.saalfeldlab.n5.N5Writer n5 = new org.janelia.saalfeldlab.n5.universe.N5Factory()
                .openWriter(org.janelia.saalfeldlab.n5.universe.StorageFormat.ZARR3, zarrDir.getAbsolutePath());
        // dimension names in N5 order (the Zarr v3 writer reverses them to C order on write)
        final String[] dimNames = (nDim == 4) ? new String[]{"x", "y", "z", "c"} : new String[]{"x", "y", "z"};
        try {
            // Level 0, one slab of unit-deep Z planes at a time, where a unit is the smallest region that can be
            // written independently: a shard (or a chunk, if the array is not sharded). Concurrent writes into
            // the same shard can corrupt it, so each task writes whole units only. Each slab is further split into
            // XY tiles (a multiple of the unit edge), so memory does not grow with image width
            final int[] shard0 = omeZarrShardShape(dims0, blockSize);
            final int[] unit = shard0 != null ? shard0 : blockSize;
            final int tileTarget = shard0 != null ? OME_ZARR_SHARDED_TILE_SIZE : OME_ZARR_TILE_SIZE;
            final long tileX = (long) unit[0] * Math.max(1, tileTarget / unit[0]);
            final long tileY = (long) unit[1] * Math.max(1, tileTarget / unit[1]);
            final long[] unitDims = new long[nDim];
            for (int d = 0; d < nDim; d++) unitDims[d] = unit[d];
            createZarrV3Dataset(n5, "s0", dims0, blockSize, shard0, dataType, compression, dimNames);
            final int nCh = (nDim == 4) ? (int) dims0[3] : 1;
            for (int c = 0; c < nCh; c++) {
                for (long z0 = 0; z0 < dims0[2]; z0 += unit[2]) {
                    for (long y0 = 0; y0 < dims0[1]; y0 += tileY) {
                        for (long x0 = 0; x0 < dims0[0]; x0 += tileX) {
                            if (progress != null) progress.accept(String.format(Locale.US,
                                    "Converting: level 0, channel %d/%d, plane %d/%d", c + 1, nCh, z0 + 1, dims0[2]));
                            final long[] min = new long[nDim];
                            final long[] max = new long[nDim];
                            for (int d = 0; d < nDim; d++) max[d] = dims0[d] - 1;
                            min[0] = x0;
                            max[0] = Math.min(x0 + tileX, dims0[0]) - 1;
                            min[1] = y0;
                            max[1] = Math.min(y0 + tileY, dims0[1]) - 1;
                            min[2] = z0;
                            max[2] = Math.min(z0 + unit[2], dims0[2]) - 1;
                            if (nDim == 4) min[3] = max[3] = c;
                            // One read of each voxel (a single-copy local tile), units are then compressed in parallel
                            final Img<T> slab = materializeTiled(Views.interval(src, min, max), blockSize[0]);
                            final long gridC = c;
                            final List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
                            for (final Interval block : createIntervals(Intervals.dimensionsAsLongArray(slab),
                                    unitDims)) {
                                futures.add(exec.submit(() -> {
                                    // Position in the chunk grid (not the shard grid, which is what the unit
                                    // size would suggest): units are aligned to whole chunks by construction
                                    final long[] grid = new long[nDim];
                                    for (int d = 0; d < 3; d++) grid[d] = (min[d] + block.min(d)) / blockSize[d];
                                    if (nDim == 4) grid[3] = gridC;
                                    org.janelia.saalfeldlab.n5.imglib2.N5Utils.saveBlock(
                                            Views.zeroMin(Views.interval(slab, block)), n5, "s0", grid);
                                    return null;
                                }));
                            }
                            awaitAll(futures);
                        }
                    }
                }
            }
            // Coarser levels, each averaged from the (already written) previous one
            for (int l = 1; l < levelDims.size(); l++) {
                final String dataset = "s" + l;
                final long[] dims = levelDims.get(l);
                createZarrV3Dataset(n5, dataset, dims, blockSize, omeZarrShardShape(dims, blockSize), dataType, compression,
                        dimNames);
                final RandomAccessibleInterval<T> prev = org.janelia.saalfeldlab.n5.imglib2.N5Utils
                        .open(n5, "s" + (l - 1));
                // Average 2x2x2 (XYZ) neighborhoods, leaving any channel axis alone. Offset.HALF_PIXEL gives
                // ceil(n/2) pixels per axis, matching levelDims
                final boolean[] downsampleInDim = new boolean[nDim];
                Arrays.fill(downsampleInDim, 0, 3, true);
                final net.imglib2.algorithm.blocks.BlockSupplier<T> downsampler = net.imglib2.algorithm.blocks.BlockSupplier
                        .of(Views.extendBorder(prev))
                        .andThen(net.imglib2.algorithm.blocks.downsample.Downsample.downsample(
                                net.imglib2.algorithm.blocks.ComputationType.AUTO,
                                net.imglib2.algorithm.blocks.downsample.Downsample.Offset.HALF_PIXEL,
                                downsampleInDim))
                        .threadSafe();
                // The unit of work is a shard (or a chunk, if the level is not sharded): a shard must be written by a
                // single task, since concurrent writes into the same shard can corrupt it
                final int[] shard = omeZarrShardShape(dims, blockSize);
                final long[] levelUnitDims = new long[nDim];
                for (int d = 0; d < nDim; d++) levelUnitDims[d] = shard != null ? shard[d] : blockSize[d];
                final List<Interval> blocks = createIntervals(dims, levelUnitDims);
                // Blocks in flight stay in memory: bound their number by their size as well as by the thread count
                final long unitBytes = Math.max(1L, Intervals.numElements(levelUnitDims)
                        * Math.max(1L, (long) Math.ceil(src.getType().getBitsPerPixel() / 8.0)));
                final int maxInFlight = (int) Math.max(1L, Math.min(4L * Math.max(1, nThreads),
                        Runtime.getRuntime().maxMemory() / 8 / unitBytes));
                final List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
                int done = 0;
                for (final Interval block : blocks) {
                    futures.add(exec.submit(() -> {
                        final long[] grid = new long[nDim];
                        for (int d = 0; d < nDim; d++) grid[d] = block.min(d) / blockSize[d];
                        final RandomAccessibleInterval<T> data = (shard == null)
                                ? net.imglib2.algorithm.blocks.BlockAlgoUtils.arrayImg(downsampler, block)
                                : downsampleShard(downsampler, block, blockDims, src.getType());
                        org.janelia.saalfeldlab.n5.imglib2.N5Utils.saveBlock(data, n5, dataset, grid);
                        return null;
                    }));
                    if (futures.size() >= maxInFlight) {
                        awaitAll(futures);
                        futures.clear();
                    }
                    if (progress != null && (++done % 64 == 0)) progress.accept(String.format(Locale.US,
                            "Converting: level %d/%d, block %d/%d", l, levelDims.size() - 1, done, blocks.size()));
                }
                awaitAll(futures);
            }
            writeOmeNgffMetadata(n5, img, levelDims, nDim == 4);
        } finally {
            // After a failure other blocks may still be queued: stop them before the writer is closed
            exec.shutdownNow();
            n5.close();
        }
    }

    /** Edge length (voxels) of the chunks of exported OME-Zarr arrays: the cell size BVV streams */
    private static final int OME_ZARR_CHUNK_SIZE = sc.fiji.snt.viewer.BvvUtils.CELL_SIZE;

    /** Edge length (voxels, a multiple of {@link #OME_ZARR_CHUNK_SIZE}) of the XY tiles read into memory at once */
    private static final int OME_ZARR_TILE_SIZE = 2048;

    /**
     * Target edge length (voxels) of the XY tiles read into memory at once when the array is sharded. A tile spans
     * a whole shard deep, so it is smaller than {@link #OME_ZARR_TILE_SIZE} to bound the slab (~512 MB for 256^3
     * shards of 16-bit data)
     */
    private static final int OME_ZARR_SHARDED_TILE_SIZE = 1024;

    /** Zstandard compression level of exported OME-Zarr arrays */
    private static final int OME_ZARR_ZSTD_LEVEL = 3;

    /**
     * Whether exported OME-Zarr arrays are sharded (many chunks per file). The write paths of
     * {@link #saveAsOmeZarr} are shard-aligned (one task per shard), since concurrent writes into the same shard can
     * corrupt it
     */
    private static final boolean OME_ZARR_SHARDING = true;

    /** Target edge length (voxels) of the shards of exported OME-Zarr arrays */
    private static final int OME_ZARR_SHARD_SIZE = 256;

    /** Arrays with fewer chunks than this are not sharded (the number of files is not a concern) */
    private static final int OME_ZARR_MIN_CHUNKS_FOR_SHARDING = 1000;

    /**
     * Gets the shard shape for an array of the given dimensions, or {@code null} if it should not be sharded. Each
     * edge is a whole multiple of the chunk edge (a Zarr v3 requirement), at most {@link #OME_ZARR_SHARD_SIZE}
     * voxels rounded up to the chunk size, and may exceed the array size along small axes. The channel axis (if
     * any) is never grouped
     *
     * @param dims      the array dimensions
     * @param chunkSize the chunk shape
     * @return the shard shape, or {@code null} for an unsharded array
     */
    private static int[] omeZarrShardShape(final long[] dims, final int[] chunkSize) {
        if (!OME_ZARR_SHARDING) return null;
        long nChunks = 1;
        for (int d = 0; d < dims.length; d++) nChunks *= (dims[d] + chunkSize[d] - 1) / chunkSize[d];
        if (nChunks < OME_ZARR_MIN_CHUNKS_FOR_SHARDING) return null;
        final int[] shard = new int[dims.length];
        for (int d = 0; d < dims.length; d++) {
            if (d >= 3) { // channel axis
                shard[d] = chunkSize[d];
                continue;
            }
            final int edge = (int) Math.min(OME_ZARR_SHARD_SIZE, dims[d]);
            shard[d] = ((edge + chunkSize[d] - 1) / chunkSize[d]) * chunkSize[d];
        }
        return shard;
    }

    /**
     * Creates a Zarr v3 dataset
     *
     * @param chunkSize the chunk shape (the unit of compression and of BVV cells)
     * @param shardSize the shard shape (a whole multiple of {@code chunkSize} per axis), or {@code null} to write
     *                  one file per chunk
     */
    private static void createZarrV3Dataset(final org.janelia.saalfeldlab.n5.N5Writer n5, final String path,
                                            final long[] dims, final int[] chunkSize, final int[] shardSize,
                                            final org.janelia.saalfeldlab.n5.DataType dataType,
                                            final org.janelia.saalfeldlab.n5.Compression compression,
                                            final String[] dimNames) {
        // In the n5-zarr builder, blockSize is the outer (shard) size and chunkSize the inner one. With only one of
        // them set the array is not sharded
        final org.janelia.saalfeldlab.n5.zarr.v3.ZarrV3DatasetAttributes.Builder builder =
                org.janelia.saalfeldlab.n5.zarr.v3.ZarrV3DatasetAttributes.builder(dims, dataType)
                        .compression(compression).dimensionNames(dimNames);
        if (shardSize != null) builder.blockSize(shardSize).chunkSize(chunkSize);
        else builder.blockSize(chunkSize);
        n5.createDataset(path, builder.build());
    }

    /**
     * Computes the region {@code shard} of a downsampled level into a single array, one chunk-sized piece at a time.
     * Computing a whole shard in one go would need an input block 8x as large (2x per axis), per thread
     *
     * @param downsampler the supplier of downsampled blocks
     * @param shard       the region to compute (in the coordinates of the downsampled level)
     * @param chunkDims   the size of the pieces
     * @param type        the pixel type
     * @return a zero-min image of the size of {@code shard}
     */
    private static <T extends NativeType<T>> RandomAccessibleInterval<T> downsampleShard(
            final net.imglib2.algorithm.blocks.BlockSupplier<T> downsampler, final Interval shard,
            final long[] chunkDims, final T type) {
        final Img<T> out = new net.imglib2.img.array.ArrayImgFactory<>(type).create(
                Intervals.dimensionsAsLongArray(shard));
        // The same data in the coordinates of the level, to copy each piece in place
        final RandomAccessibleInterval<T> placed = Views.translate(out, shard.minAsLongArray());
        for (final Interval piece : createIntervals(Intervals.dimensionsAsLongArray(shard), chunkDims)) {
            // createIntervals works in zero-min space: shift the piece to the position of the shard
            final long[] min = new long[piece.numDimensions()];
            final long[] max = new long[piece.numDimensions()];
            for (int d = 0; d < min.length; d++) {
                min[d] = shard.min(d) + piece.min(d);
                max[d] = shard.min(d) + piece.max(d);
            }
            final Interval region = new net.imglib2.FinalInterval(min, max);
            final RandomAccessibleInterval<T> computed = net.imglib2.algorithm.blocks.BlockAlgoUtils
                    .arrayImg(downsampler, region);
            net.imglib2.loops.LoopBuilder.setImages(Views.zeroMin(computed),
                    Views.zeroMin(Views.interval(placed, region))).forEachPixel((a, b) -> b.set(a));
        }
        return out;
    }

    private static <T extends NativeType<T>> org.janelia.saalfeldlab.n5.DataType n5DataType(final T type) {
        final org.janelia.saalfeldlab.n5.DataType dataType = org.janelia.saalfeldlab.n5.imglib2.N5Utils.dataType(type);
        if (dataType == null) throw new IllegalArgumentException("Unsupported pixel type for OME-Zarr export: "
                + type.getClass().getSimpleName());
        return dataType;
    }

    private static void awaitAll(final List<java.util.concurrent.Future<?>> futures) throws IOException {
        try {
            for (final java.util.concurrent.Future<?> f : futures) f.get();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while writing", e);
        } catch (final java.util.concurrent.ExecutionException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof OutOfMemoryError oom) throw oom;
            throw new IOException("Could not write block: " + cause, cause);
        }
    }

    /**
     * Dimensions of the levels of the pyramid written by {@link #saveAsOmeZarr}. Only the first three (spatial)
     * dimensions are halved, so any channel dimension is passed through unchanged
     */
    private static List<long[]> omeZarrLevelDims(final long[] dims0) {
        final List<long[]> levels = new ArrayList<>();
        long[] d = dims0.clone();
        while (true) {
            levels.add(d);
            final boolean fits = d[0] <= 2048 && d[1] <= 2048 && d[2] <= 2048
                    && d[0] * d[1] * d[2] <= sc.fiji.snt.viewer.BvvUtils.MAX_SINGLE_TEXTURE_VOXELS;
            if (fits && levels.size() >= 3) break;
            if (d[0] <= 32 && d[1] <= 32 && d[2] <= 32) break; // nothing left to halve
            final long[] next = d.clone();
            for (int i = 0; i < 3; i++) next[i] = Math.max(1, (d[i] + 1) / 2);
            d = next;
        }
        return levels;
    }

    /**
     * Writes the OME-NGFF 0.5 root metadata with n5-universe's own writer, which nests {@code multiscales} under
     * {@code ome}, sets {@code ome/version}, reverses axes and transforms to Zarr (C) order and names the array
     * dimensions. All arrays here are in N5 (F) order: X, Y, Z[, C]
     */
    private static void writeOmeNgffMetadata(final org.janelia.saalfeldlab.n5.N5Writer n5, final ImgPlus<?> img,
                                              final List<long[]> levelDims, final boolean hasChannels)
            throws IOException {
        final String unit = spatialNgffUnit(img);
        if (unit == null) SNTUtils.log("OME-Zarr export: unrecognized spatial unit(s) " + unitReport(img)
                + "; axes will be written without units");
        final int nDim = hasChannels ? 4 : 3;
        final org.janelia.saalfeldlab.n5.universe.metadata.axes.Axis[] axes =
                new org.janelia.saalfeldlab.n5.universe.metadata.axes.Axis[nDim];
        final String[] names = {"x", "y", "z"};
        for (int d = 0; d < 3; d++)
            axes[d] = new org.janelia.saalfeldlab.n5.universe.metadata.axes.Axis(
                    org.janelia.saalfeldlab.n5.universe.metadata.axes.Axis.SPACE, names[d], unit);
        if (hasChannels) axes[3] = new org.janelia.saalfeldlab.n5.universe.metadata.axes.Axis(
                org.janelia.saalfeldlab.n5.universe.metadata.axes.Axis.CHANNEL, "c");
        final double[] cal = {img.averageScale(0), img.averageScale(1), img.averageScale(2)};
        final int nLevels = levelDims.size();
        final String[] paths = new String[nLevels];
        final double[][] scales = new double[nLevels][nDim];
        final double[][] translations = new double[nLevels][nDim];
        // Per-axis downsampling factor: an axis is halved at every level unless it is already a single voxel wide
        final double[] factor = {1, 1, 1};
        for (int l = 0; l < nLevels; l++) {
            paths[l] = "s" + l;
            if (l > 0) for (int d = 0; d < 3; d++) if (levelDims.get(l - 1)[d] > 1) factor[d] *= 2;
            for (int d = 0; d < nDim; d++) {
                final boolean spatial = d < 3;
                scales[l][d] = spatial ? cal[d] * factor[d] : 1;
                // 2x2x2 averaging puts level-l voxel centers (f - 1) / 2 level-0 voxels from the level-0 grid
                translations[l][d] = spatial ? cal[d] * (factor[d] - 1) / 2 : 0;
            }
        }
        final String name = (img.getName() == null || img.getName().isBlank()) ? "image" : img.getName();
        final org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.OmeNgffMetadata metadata =
                org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.OmeNgffMetadata.buildForWriting(nDim, name,
                        "0.5", axes, paths, scales, translations);
        try {
            // NB: the root group must be given as "./" not "/". See https://github.com/saalfeldlab/n5-universe/issues/64
            new org.janelia.saalfeldlab.n5.universe.metadata.ome.ngff.OmeNgffMetadataParser(n5)
                    .writeMetadata(metadata, n5, "./");
        } catch (final Exception e) {
            throw new IOException("Could not write OME-NGFF metadata: " + e.getMessage(), e);
        }
    }

    /** Root metadata files of N5 and Zarr (v2/v3) containers */
    private static final List<String> CONTAINER_METADATA = List.of("zarr.json", ".zgroup", ".zarray", ".zattrs",
            "attributes.json");

    /**
     * Checks whether a local directory has the root metadata of an N5 or Zarr (v2/v3) container, i.e., whether
     * it can be opened as one. An empty or incomplete directory (hidden files such as {@code .DS_Store} do not
     * count) fails this check. Does not validate the metadata's content
     *
     * @param dir the directory to check
     * @return true if {@code dir} is a directory containing {@code zarr.json}, {@code .zgroup}, {@code .zarray},
     *         {@code .zattrs} or {@code attributes.json}
     */
    public static boolean hasN5ZarrMetadata(final File dir) {
        return dir != null && dir.isDirectory() && CONTAINER_METADATA.stream().anyMatch(n -> new File(dir, n).isFile());
    }

    /**
     * Resolves a path to a local OME-Zarr (OME-NGFF v0.5) container directory. Accepts the directory itself or its
     * {@code zarr.json} file (as returned by some file choosers)
     *
     * @param path the path to check
     * @return the container directory, or null if {@code path} is not a local OME-Zarr v0.5 container
     */
    public static File getLocalOmeZarrDir(final String path) {
        if (path == null || path.isBlank() || path.contains("://")) return null;
        File f = new File(path);
        if (f.isFile() && "zarr.json".equals(f.getName())) f = f.getParentFile();
        return (f != null && f.isDirectory() && new File(f, "zarr.json").isFile()) ? f : null;
    }

    /**
     * The spatial calibration stored in the metadata of an OME-Zarr
     *
     * @param spacing the level 0 voxel size along X, Y and Z
     * @param unit    the OME-NGFF unit name of the spatial axes (e.g., "micrometer"), or null if not set
     */
    public record OmeZarrCalibration(double[] spacing, String unit) {
    }

    private static com.google.gson.JsonObject readOmeZarrRoot(final File zarrDir) throws IOException {
        return com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(
                new File(zarrDir, "zarr.json").toPath(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
    }

    /** Index of the x, y, z axes (in that order) of an OME-NGFF multiscales entry, or null if any is missing */
    private static int[] spatialAxisIndices(final com.google.gson.JsonObject multiscale) {
        final int[] idx = {-1, -1, -1};
        final var axes = multiscale.getAsJsonArray("axes");
        for (int i = 0; i < axes.size(); i++) {
            final var name = axes.get(i).getAsJsonObject().get("name");
            if (name == null) continue;
            final int d = "xyz".indexOf(name.getAsString().toLowerCase(Locale.ROOT));
            if (d >= 0 && name.getAsString().length() == 1) idx[d] = i;
        }
        return (idx[0] < 0 || idx[1] < 0 || idx[2] < 0) ? null : idx;
    }

    /**
     * Reads the spatial calibration from the root metadata of a local OME-Zarr (OME-NGFF v0.5) container
     *
     * @param path the container directory (or its {@code zarr.json}, see {@link #getLocalOmeZarrDir})
     * @return the calibration of the first multiscale image, or null if unavailable
     */
    public static OmeZarrCalibration getOmeZarrCalibration(final String path) {
        final File dir = getLocalOmeZarrDir(path);
        if (dir == null) return null;
        try {
            final var ms = readOmeZarrRoot(dir).getAsJsonObject("attributes").getAsJsonObject("ome")
                    .getAsJsonArray("multiscales").get(0).getAsJsonObject();
            final int[] idx = spatialAxisIndices(ms);
            if (idx == null) return null;
            final var s0 = ms.getAsJsonArray("datasets").get(0).getAsJsonObject()
                    .getAsJsonArray("coordinateTransformations");
            double[] spacing = null;
            for (final var t : s0) {
                final var o = t.getAsJsonObject();
                if ("scale".equals(o.get("type").getAsString())) {
                    spacing = new double[3];
                    for (int d = 0; d < 3; d++) spacing[d] = o.getAsJsonArray("scale").get(idx[d]).getAsDouble();
                }
            }
            if (spacing == null) return null;
            final var u = ms.getAsJsonArray("axes").get(idx[0]).getAsJsonObject().get("unit");
            return new OmeZarrCalibration(spacing, (u == null || u.isJsonNull()) ? null : u.getAsString());
        } catch (final Exception e) {
            SNTUtils.log("Could not read calibration from " + path + ": " + e);
            return null;
        }
    }

    /**
     * Sets the unit of the spatial axes in the root metadata of a local OME-Zarr (OME-NGFF v0.5) container. Only the
     * metadata file is rewritten
     *
     * @param path the container directory (or its {@code zarr.json}, see {@link #getLocalOmeZarrDir})
     * @param unit the unit (e.g., "um", "\u00b5m", "micrometer"). Must be recognized
     * @return true if the metadata was updated
     */
    public static boolean setOmeZarrUnit(final String path, final String unit) {
        return setOmeZarrCalibration(path, null, unit);
    }

    /**
     * Sets the spacing and/or unit in the root metadata of a local OME-Zarr (OME-NGFF v0.5) container. Spacing is
     * the level 0 voxel size: the scale and translation of every pyramid level are rescaled by the ratio between
     * the new and the stored spacing, so the pyramid stays consistent. Only the metadata file is rewritten
     *
     * @param path    the container directory (or its {@code zarr.json}, see {@link #getLocalOmeZarrDir})
     * @param spacing the new X, Y, Z voxel size, or null to leave it unchanged
     * @param unit    the new unit (see {@link #setOmeZarrUnit}), or null/unrecognized to leave it unchanged
     * @return true if the metadata was updated
     */
    public static boolean setOmeZarrCalibration(final String path, final double[] spacing, final String unit) {
        final File dir = getLocalOmeZarrDir(path);
        final String ngff = ngffUnit(unit);
        if (dir == null || (ngff == null && spacing == null)) return false;
        try {
            final OmeZarrCalibration old = (spacing == null) ? null : getOmeZarrCalibration(path);
            if (spacing != null && old == null) return false;
            final com.google.gson.JsonObject root = readOmeZarrRoot(dir);
            boolean changed = false;
            for (final var msEl : root.getAsJsonObject("attributes").getAsJsonObject("ome")
                    .getAsJsonArray("multiscales")) {
                final var ms = msEl.getAsJsonObject();
                final int[] idx = spatialAxisIndices(ms);
                if (idx == null) continue;
                final var axes = ms.getAsJsonArray("axes");
                if (ngff != null) for (final int i : idx) {
                    axes.get(i).getAsJsonObject().addProperty("unit", ngff);
                    changed = true;
                }
                if (spacing == null) continue;
                final var transforms = new java.util.ArrayList<com.google.gson.JsonElement>();
                for (final var ds : ms.getAsJsonArray("datasets"))
                    ds.getAsJsonObject().getAsJsonArray("coordinateTransformations").forEach(transforms::add);
                if (ms.has("coordinateTransformations")) ms.getAsJsonArray("coordinateTransformations")
                        .forEach(transforms::add);
                for (final var t : transforms) {
                    final var o = t.getAsJsonObject();
                    final String type = o.get("type").getAsString();
                    if (!"scale".equals(type) && !"translation".equals(type)) continue;
                    final var values = o.getAsJsonArray(type);
                    for (int d = 0; d < 3; d++) {
                        values.set(idx[d], new com.google.gson.JsonPrimitive(
                                values.get(idx[d]).getAsDouble() * spacing[d] / old.spacing()[d]));
                        changed = true;
                    }
                }
            }
            if (changed) java.nio.file.Files.writeString(new File(dir, "zarr.json").toPath(),
                    new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(root),
                    java.nio.charset.StandardCharsets.UTF_8);
            return changed;
        } catch (final Exception e) {
            SNTUtils.error("Could not update calibration of " + path, e);
            return false;
        }
    }

    /**
     * Returns the first recognized spatial unit (as an OME-NGFF name) among the X, Y and Z axes of {@code img}, or
     * null if none is recognized. Bio-Formats readers do not always set the unit on every axis (or spell it as
     * expected), so all spatial axes are checked, not just the first
     *
     * @param img the image
     * @return the OME-NGFF unit name (e.g., "micrometer"), or null if uncalibrated/unrecognized
     */
    public static String spatialNgffUnit(final ImgPlus<?> img) {
        for (int d = 0; d < Math.min(3, img.numDimensions()); d++) {
            final String u = ngffUnit(img.axis(d).unit());
            if (u != null) return u;
        }
        return null;
    }

    private static String unitReport(final ImgPlus<?> img) {
        final StringBuilder sb = new StringBuilder();
        for (int d = 0; d < Math.min(3, img.numDimensions()); d++)
            sb.append(d > 0 ? ", '" : "'").append(img.axis(d).unit()).append('\'');
        return sb.toString();
    }

    /** Maps common unit spellings to the UDUNITS-2 names required by OME-NGFF, or null if unknown/uncalibrated */
    private static String ngffUnit(final String unit) {
        if (unit == null) return null;
        return switch (unit.trim().toLowerCase(Locale.ROOT)) {
            case "um", "\u00b5m", "\u03bcm", "micron", "microns", "micrometer", "micrometre", "micrometers",
                 "micrometres" -> "micrometer";
            case "nm", "nanometer", "nanometre", "nanometers" -> "nanometer";
            case "mm", "millimeter", "millimetre", "millimeters" -> "millimeter";
            case "m", "meter", "metre", "meters" -> "meter";
            default -> null; // e.g. "pixel"
        };
    }

    /**
     * Saves an image to disk as a TIFF file. This is the save counterpart of
     * {@link #open(String)}.
     * <p>
     * The image is copied into a 32-bit float representation prior to saving,
     * ensuring compatibility regardless of the source pixel type.
     * For lower bit-depth output use
     * {@link #save(RandomAccessibleInterval, String, RealType)}.
     * </p>
     *
     * @param img      the image to save; may be virtual or transformed
     * @param filePath the destination file path; a {@code .tif} extension is
     *                 appended automatically if absent
     * @throws Exception if the image cannot be serialized or written to disk
     */
    public static void save(final RandomAccessibleInterval<? extends RealType<?>> img,
                            final String filePath) throws Exception {
        save(img, filePath, new FloatType());
    }

    /**
     * Saves an image to disk as a TIFF file, converting pixel values to the
     * specified output type before writing. This is the save counterpart of
     * {@link #open(String)}.
     * <p>
     * The image is materialized into a concrete in-memory {@link Img} of the
     * requested type, so the caller must ensure sufficient RAM is available.
     * Conversion is done via {@link RealType#setReal(double)}, which clamps
     * values to the target type's range automatically (e.g. 16-bit unsigned
     * values are clamped to [0, 65535]).
     * </p>
     * Typical usage:
     * <pre>
     *   ImgUtils.save(rai, "/path/to/out.tif", new UnsignedShortType());
     *   ImgUtils.save(rai, "/path/to/out.tif", new UnsignedByteType());
     *   ImgUtils.save(rai, "/path/to/out.tif", new FloatType());
     * </pre>
     *
     * @param <T>      the output pixel type; must be a {@link NativeType} so
     *                 that an {@link ArrayImgFactory} can allocate it
     * @param img      the image to save; may be virtual or transformed
     * @param filePath the destination file path; a {@code .tif} extension is
     *                 appended automatically if absent
     * @param outType  an instance of the desired output pixel type, e.g.
     *                 {@code new UnsignedShortType()} for 16-bit output
     * @throws Exception if the image cannot be serialized or written to disk
     */
    public static <T extends RealType<T> & NativeType<T>> void save(
            final RandomAccessibleInterval<? extends RealType<?>> img,
            final String filePath,
            final T outType) throws Exception {
        final String path = (filePath.endsWith(".tif") || filePath.endsWith(".tiff"))
                ? filePath : filePath + ".tif";
        final Img<T> output = new ArrayImgFactory<>(outType).create(img);
        // Single-threaded: concurrent chunk reads from lazy/network-backed sources
        // (HDF5, N5, Zarr on network mounts) saturate the I/O client and cause hangs
        LoopBuilder.setImages(img, output).forEachPixel((in, out) -> out.setReal(in.getRealDouble()));
        final Context ctx = SNTUtils.getContext();
        final Dataset dataset = ctx.service(DatasetService.class).create(output);
        ctx.service(DatasetIOService.class).save(dataset, path);
    }

    /**
     * Opens an image file as an ImgPlus.
     *
     * @param file the file to open
     * @return the opened image as ImgPlus, or null if opening fails
     * @throws IllegalArgumentException if file is null
     */
    public static ImgPlus<?> open(final File file) {
        if (file == null)
            throw new IllegalArgumentException("File cannot be null");
        return open(file.getAbsolutePath());
    }

    /**
     * Checks if an ImgPlus contains packed RGB data (ARGBType).
     *
     * @param img the image to check
     * @return true if the image uses ARGBType pixels
     */
    public static boolean isRGB(final ImgPlus<?> img) {
        if (img == null || img.size() == 0) return false;
        return img.firstElement() instanceof net.imglib2.type.numeric.ARGBType;
    }

    /**
     * Checks if an ImgPlus appears to be a multi-channel RGB image
     * (3 channels of 8-bit data, not packed ARGB).
     */
    public static boolean isMultiChannelRGB(final ImgPlus<?> img) {
        if (img == null) return false;
        final int channelDim = img.dimensionIndex(Axes.CHANNEL);
        if (channelDim < 0) return false;
        return img.dimension(channelDim) == 3
                && img.firstElement() instanceof net.imglib2.type.numeric.integer.UnsignedByteType;
    }

    /**
     * Extracts spacing units from an ImgPlus.
     *
     * @param img the image
     * @return the unit string (e.g., "µm"), or null if not calibrated
     */
    public static String getSpacingUnits(final ImgPlus<?> img) {
        if (img == null || img.numDimensions() == 0) {
            return null;
        }
        final CalibratedAxis axis = img.axis(0);
        return axis != null ? axis.unit() : null;
    }

    /**
     * Returns a 16-bit view of the given RAI if its pixel type is {@link FloatType},
     * normalizing values to the full 16-bit range using the data's actual min/max.
     * Returns the original RAI unchanged for any other type. No data is copied.
     *
     * @param rai the source image
     * @return a 16-bit RAI, or the original RAI if not float
     */
    @SuppressWarnings("unchecked")
    public static <T extends RealType<T>> RandomAccessibleInterval<? extends RealType<?>>
    toUnsignedShortIfFloat(final RandomAccessibleInterval<T> rai) {
        if (!(rai.getType() instanceof FloatType))
            return rai;
        final FloatType min = new FloatType();
        final FloatType max = new FloatType();
        ComputeMinMax.computeMinMax((RandomAccessibleInterval<FloatType>) rai, min, max);
        final double minVal = min.get();
        final double maxVal = max.get() > minVal ? max.get() : minVal + 1; // guard against flat images
        return Converters.convert(
                (RandomAccessibleInterval<FloatType>) rai,
                new RealUnsignedShortConverter<>(minVal, maxVal),
                new UnsignedShortType());
    }

    /**
     * Returns a 16-bit view of the given RAI if its pixel type is {@link FloatType},
     * normalizing to the given min/max range.
     *
     * @param rai the source image
     * @param min the minimum value for normalization (maps to 0)
     * @param max the maximum value for normalization (maps to 65535)
     * @return a 16-bit RAI, or the original RAI if not float
     */
    @SuppressWarnings("unchecked")
    public static <T extends RealType<T>> RandomAccessibleInterval<? extends RealType<?>>
    toUnsignedShortIfFloat(final RandomAccessibleInterval<T> rai,
                           final double min, final double max) {
        if (!(rai.getType() instanceof FloatType))
            return rai;
        return Converters.convert(
                (RandomAccessibleInterval<FloatType>) rai,
                new RealUnsignedShortConverter<>(min, max),
                new UnsignedShortType());
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

    /**
     * Builds a one-line diagnostic string listing all axes of an {@link ImgPlus}
     * with their type, size, and scale. Used by {@link #open(String)} to report
     * the axis layout of freshly opened images.
     *
     * @param img the image to describe
     * @return a human-readable axis summary, e.g.
     *         {@code "Axes: [X 512 (1.0µm)] [Y 512 (1.0µm)] [Z 60 (2.0µm)] [Channel 3 (1.0)]"}
     */
    public static String axisReport(final ImgPlus<?> img) {
        final StringBuilder sb = new StringBuilder("Axes: ");
        for (int d = 0; d < img.numDimensions(); d++) {
            final CalibratedAxis axis = img.axis(d);
            final String unit = (axis.unit() != null && !axis.unit().isBlank()) ? axis.unit() : "px";
            sb.append(String.format("[%s %d (%.3g%s)]",
                    axis.type().getLabel(),
                    img.dimension(d),
                    axis.averageScale(0, 1),
                    unit));
            if (d < img.numDimensions() - 1) sb.append(' ');
        }
        return sb.toString();
    }

    /**
     * Returns a new {@link ImgPlus} with two axes swapped. The underlying data
     * is a lazy permuted view (no pixel data is copied). Axis metadata (type,
     * scale, unit) is reordered to match the new dimension order.
     * Example: swap Z and Channel in an XYZCT image:
     * <pre>
     *   {@code
     *   ImgPlus<?> xyzct = ImgUtils.open("image.tif");
     *   ImgPlus<?> xyczT = ImgUtils.swapAxes(xyzct, Axes.Z, Axes.CHANNEL);
     *   }
     * </pre>
     *
     * @param <T>   the pixel type
     * @param img   the source image
     * @param axis1 the first axis type to swap
     * @param axis2 the second axis type to swap
     * @return a new ImgPlus with the two axes permuted
     * @throws IllegalArgumentException if either axis type is not found in the image
     */
    public static <T extends net.imglib2.type.Type<T>> ImgPlus<T> swapAxes(final ImgPlus<T> img,
                                                                           final AxisType axis1,
                                                                           final AxisType axis2) {
        final int d1 = img.dimensionIndex(axis1);
        final int d2 = img.dimensionIndex(axis2);
        if (d1 < 0)
            throw new IllegalArgumentException("Axis not found: " + axis1.getLabel());
        if (d2 < 0)
            throw new IllegalArgumentException("Axis not found: " + axis2.getLabel());
        return swapDimensions(img, d1, d2);
    }

    /** Index-based swap, safe when several axes share the same (e.g., unknown) type */
    private static <T extends net.imglib2.type.Type<T>> ImgPlus<T> swapDimensions(final ImgPlus<T> img,
                                                                                  final int d1, final int d2) {
        if (d1 == d2) return img; // nothing to do

        final RandomAccessibleInterval<T> permuted = Views.permute(img, d1, d2);
        // ImgView.wrap(RAI) requires T extends Type<T>; the two-arg overload has no such bound
        final Img<T> permutedImg = ImgView.wrap(permuted, img.getImg().factory());

        // Build new CalibratedAxis array with the two axes swapped
        final CalibratedAxis[] axes = new CalibratedAxis[img.numDimensions()];
        for (int d = 0; d < img.numDimensions(); d++) {
            axes[d] = img.axis(d).copy();
        }
        final CalibratedAxis tmp = axes[d1];
        axes[d1] = axes[d2];
        axes[d2] = tmp;

        return new ImgPlus<>(permutedImg, img.getName(), axes);
    }

    /**
     * Reorders the axes of an {@link ImgPlus} to the canonical XYZCT order
     * expected by BVV and most ImageJ tools, applying swaps as needed.
     * Axes not present in the image are left in place. No pixel data is copied.
     * <p>
     * This is useful when SCIFIO opens an image with axis order XYCZT (as
     * ImageJ saves it) but BVV expects XYZCT, causing Z and C to be misinterpreted.
     * </p>
     *
     * @param <T> the pixel type
     * @param img the source image; may have any axis order
     * @return a new ImgPlus reordered to XYZCT (axes already in canonical order
     *         are returned unchanged)
     */
    public static <T extends net.imglib2.type.Type<T>> ImgPlus<T> permuteToXYZCT(final ImgPlus<T> img) {
        // The canonical target order for BVV: X, Y, Z, Channel, Time
        final AxisType[] canonical = {Axes.X, Axes.Y, Axes.Z, Axes.CHANNEL, Axes.TIME};
        ImgPlus<T> result = img;
        // Walk the canonical axes that are present, placing each at the next free position.
        // Positions are ranked among present axes only: comparing against the canonical
        // index would break images lacking any of the five (e.g., XYZT without C)
        int pos = 0;
        for (final AxisType type : canonical) {
            final int current = result.dimensionIndex(type);
            if (current < 0) continue; // axis absent
            if (current != pos) result = swapDimensions(result, current, pos);
            pos++;
        }
        if (result != img)
            SNTUtils.log("Permuted axes > " + axisReport(result));
        return result;
    }

    /** Returns whether the first three axes of an image are X, Y and Z (in that order) */
    public static boolean hasXYZLeading(final ImgPlus<?> img) {
        return img.numDimensions() >= 3
                && img.axis(0).type() == Axes.X
                && img.axis(1).type() == Axes.Y
                && img.axis(2).type() == Axes.Z;
    }

    private static boolean isUnlabeled(final AxisType type) {
        return type == null || "Unknown".equalsIgnoreCase(type.getLabel());
    }

    /**
     * Normalizes the axes of an {@link ImgPlus} for volume viewers and tracing: singleton axes  are dropped and the
     * remaining ones are ordered X, Y, Z, [C], [T], with no pixel copy.
     * <ul>
     * <li>Unlabeled axes: if exactly 3 axes are present and none is labeled, they are assumed to be XYZ; if X and Y are
     * labeled and a single third axis is unlabeled, it is assumed to be Z. Both cases are logged. Anything else
     * unlabeled is rejected as ambiguous</li>
     * <li>Images without a Z axis after singleton removal (2D, or 2D plus C/T) are returned as is: callers are expected
     * to reject them as non-volumetric</li>
     * <li>Images with Z but any other non-singleton axis than C or T (e.g., Position, Series) are rejected</li>
     * </ul>
     * Images already in the normalized layout are returned unchanged (same instance).
     *
     * @param <T> the pixel type
     * @param img the source image
     * @return the normalized image, or {@code img} if no change was needed
     * @throws IllegalArgumentException if the axis layout is ambiguous or unsupported
     */
    public static <T extends NumericType<T>> ImgPlus<T> normalizeToXYZ(final ImgPlus<T> img) {
        if (img == null || img.numDimensions() < 3) return img;
        // Singletons first: an unlabeled axis of size 1 is harmless and must not trigger the ambiguity check
        ImgPlus<T> result = labelUnknownAxes(dropSingletonDimensions(img));
        if (result.dimensionIndex(Axes.Z) < 0) return result; // 2D: not our call
        result = permuteToXYZCT(result);
        if (!hasXYZLeading(result)) {
            throw new IllegalArgumentException("Unsupported axis layout: " + axisReport(result)
                    + ". Expected X, Y, Z leading");
        }
        for (int d = 3; d < result.numDimensions(); d++) {
            final AxisType type = result.axis(d).type();
            if (type != Axes.CHANNEL && type != Axes.TIME) {
                throw new IllegalArgumentException("Unsupported non-spatial axis '" + type.getLabel()
                        + "' with " + result.dimension(d) + " elements: " + axisReport(result)
                        + ". Only Channel and Time are supported");
            }
        }
        if (result != img) SNTUtils.log("Normalized axes > " + axisReport(result));
        return result;
    }

    private static <T extends NumericType<T>> ImgPlus<T> labelUnknownAxes(final ImgPlus<T> img) {
        final int n = img.numDimensions();
        int nUnlabeled = 0;
        for (int d = 0; d < n; d++)
            if (isUnlabeled(img.axis(d).type())) nUnlabeled++;
        if (nUnlabeled == 0) return img;
        final boolean allUnlabeled3D = n == 3 && nUnlabeled == 3;
        final boolean unlabeledZ = n == 3 && nUnlabeled == 1 && isUnlabeled(img.axis(2).type())
                && img.axis(0).type() == Axes.X && img.axis(1).type() == Axes.Y;
        if (!allUnlabeled3D && !unlabeledZ) {
            throw new IllegalArgumentException("Ambiguous axis layout (unlabeled axes): " + axisReport(img));
        }
        final AxisType[] assumed = {Axes.X, Axes.Y, Axes.Z};
        final net.imagej.axis.CalibratedAxis[] axes = new net.imagej.axis.CalibratedAxis[n];
        for (int d = 0; d < n; d++) {
            axes[d] = img.axis(d).copy();
            if (isUnlabeled(axes[d].type())) axes[d].setType(assumed[d]);
        }
        SNTUtils.log("Unlabeled axes assumed to be XYZ: " + axisReport(img) + " (" + img.dimension(0) + "x"
                + img.dimension(1) + "x" + img.dimension(2) + ")");
        return new ImgPlus<>(img.getImg(), img.getName(), axes);
    }

    /**
     * Checks whether two images have compatible spatial dimensions (width,
     * height, and optionally depth). This is useful for verifying that a
     * label/segmentation image matches the image that paths were traced on.
     *
     * @param img1 first image
     * @param img2 second image
     * @return {@code true} if the first 2 (or 3, if both are 3D+) dimensions
     *         match
     */
    public static boolean haveSameSpatialDimensions(final RandomAccessibleInterval<?> img1,
                                                     final RandomAccessibleInterval<?> img2) {
        if (img1 == null || img2 == null) return false;
        final int nDims = Math.min(img1.numDimensions(), img2.numDimensions());
        final int spatialDims = Math.min(nDims, 3); // compare X, Y, and Z if present
        for (int d = 0; d < spatialDims; d++) {
            if (img1.dimension(d) != img2.dimension(d)) return false;
        }
        return true;
    }

    /**
     * Checks whether an {@link ImagePlus} and a {@link RandomAccessibleInterval}
     * have compatible spatial dimensions (width, height, and optionally depth).
     *
     * @param imp the ImagePlus
     * @param rai the RandomAccessibleInterval (XY or XYZ)
     * @return {@code true} if spatial dimensions match
     */
    public static boolean haveSameSpatialDimensions(final ImagePlus imp,
                                                     final RandomAccessibleInterval<?> rai) {
        if (imp == null || rai == null) return false;
        if (imp.getWidth() != rai.dimension(0)) return false;
        if (imp.getHeight() != rai.dimension(1)) return false;
        return rai.numDimensions() <= 2 || imp.getNSlices() == rai.dimension(2);
    }

    /**
     * Checks whether a {@link RandomAccessibleInterval} appears to be a label
     * (segmentation) image. A label image is expected to contain non-negative
     * integer values with 0 as background and a bounded number of unique
     * classes.
     * <p>
     * The heuristics are:
     * <ul>
     *   <li>All values must be non-negative and integer-valued (i.e.,
     *       {@code value == Math.floor(value)}).</li>
     *   <li>The number of unique non-zero values must not exceed
     *       {@code maxClasses}.</li>
     * </ul>
     * The scan terminates early if either condition is violated.
     *
     * @param img        the image to check
     * @param maxClasses maximum number of distinct non-zero labels allowed
     * @return {@code true} if the image passes all label-image heuristics
     */
    public static boolean isLabelImage(final RandomAccessibleInterval<? extends RealType<?>> img,
                                        final int maxClasses) {
        final Set<Integer> uniqueLabels = new HashSet<>();
        final Cursor<? extends RealType<?>> cursor = img.cursor();
        while (cursor.hasNext()) {
            final double raw = cursor.next().getRealDouble();
            if (raw == 0) continue;
            if (raw < 0 || raw != Math.floor(raw)) return false;
            uniqueLabels.add((int) raw);
            if (uniqueLabels.size() > maxClasses) return false;
        }
        return true;
    }

    /**
     * Checks whether a {@link RandomAccessibleInterval} appears to be a label
     * image, using a default maximum of 500 classes.
     *
     * @param img the image to check
     * @return {@code true} if the image passes label-image heuristics
     * @see #isLabelImage(RandomAccessibleInterval, int)
     */
    public static boolean isLabelImage(final RandomAccessibleInterval<? extends RealType<?>> img) {
        return isLabelImage(img, 500);
    }

    /**
     * Resolves pixel spacing for a {@link RandomAccessibleInterval}. If
     * explicit spacing is provided and has enough dimensions, it is used
     * directly. Otherwise, axis metadata is extracted from {@link ImgPlus}
     * if available. Falls back to isotropic spacing of 1.0.
     *
     * @param img      the image
     * @param explicit explicit spacing array, or {@code null}
     * @return pixel spacing array with one entry per image dimension
     */
    public static double[] resolveSpacing(final RandomAccessibleInterval<?> img,
                                           final double[] explicit) {
        final int nDims = img.numDimensions();
        if (explicit != null && explicit.length >= nDims) {
            return Arrays.copyOf(explicit, nDims);
        }
        if (img instanceof ImgPlus<?> imgPlus) {
            final double[] spacing = new double[nDims];
            for (int d = 0; d < nDims; d++) {
                final CalibratedAxis axis = imgPlus.axis(d);
                spacing[d] = (axis instanceof LinearAxis la)
                        ? Math.abs(la.scale()) : 1.0;
                if (spacing[d] <= 0) spacing[d] = 1.0;
            }
            return spacing;
        }
        final double[] spacing = new double[nDims];
        Arrays.fill(spacing, 1.0);
        return spacing;
    }

}
