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
import net.imagej.axis.CalibratedAxis;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.Localizable;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.display.ColorTable;
import net.imglib2.img.Img;
import net.imglib2.img.ImgView;
import net.imglib2.img.display.imagej.ImageJFunctions;
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
public class ImgUtils
{

	static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

    private ImgUtils() {}


    /** Extracts IJ;s ImagePlus Calibration from ImgPlus CalibratedAxis */
    public static Calibration imgPlusToCalibration(final ImgPlus<?> imgPlus) {
        final Calibration cal = new Calibration();
        for (int d = 0; d < imgPlus.numDimensions(); d++) {
            final CalibratedAxis axis = imgPlus.axis(d);
            final double scale = axis.averageScale(0, 1);
            if (axis.type() == Axes.X) {
                cal.pixelWidth = scale;
                cal.setUnit(axis.unit());
            } else if (axis.type() == Axes.Y) {
                cal.pixelHeight = scale;
            } else if (axis.type() == Axes.Z) {
                cal.pixelDepth = scale;
            }
        }
        return cal;
    }

    /**
     * @param dimensions
     * @return the index of the largest dimension
     */
    public static int maxDimension(final long[] dimensions )
    {
        long dimensionMax = Long.MIN_VALUE;
        int dimensionArgMax = -1;
        for ( int d = 0; d < dimensions.length; ++d )
        {
            final long size = dimensions[ d ];
            if ( size > dimensionMax )
            {
                dimensionMax = size;
                dimensionArgMax = d;
            }
        }
        return dimensionArgMax;
    }

    /**
     * Get a 3D sub-volume of an image, given two corner points and specified padding. If the input is 2D, a
     * singleton dimension is added. If necessary, the computed sub-volume is clamped at the min and max of each
     * dimension of the input interval.
     *
     * @param img the source interval
     * @param x1 x-coordinate of the first corner point
     * @param y1 y-coordinate of the first corner point
     * @param z1 z-coordinate of the first corner point
     * @param x2 x-coordinate of the second corner point
     * @param y2 y-cooridnate of the second corner point
     * @param z2 z-coordinate of the second corner point
     * @param padPixels the amount of padding in each dimension, in pixels
     * @param <T>
     * @return the subvolume
     */
    public static < T > RandomAccessibleInterval< T > subVolume( RandomAccessibleInterval< T > img,
                                                                 final long x1, final long y1, final long z1,
                                                                 final long x2, final long y2, final long z2,
                                                                 final long padPixels )
    {
        if ( img.numDimensions() == 2 )
            img = Views.addDimension(img, 0, 0);
        // Create some padding around the start and goal nodes
        long[] imgMin = Intervals.minAsLongArray( img );
        long[] imgMax = Intervals.maxAsLongArray( img );
        final Interval interval = Intervals.createMinMax(
                Math.max( imgMin[ 0 ], Math.min( x1, x2 ) - padPixels ),
                Math.max( imgMin[ 1 ], Math.min( y1, y2 ) - padPixels ),
                Math.max( imgMin[ 2 ], Math.min( z1, z2 ) - padPixels ),
                Math.min( imgMax[ 0 ], Math.max( x1, x2 ) + padPixels ),
                Math.min( imgMax[ 1 ], Math.max( y1, y2 ) + padPixels ),
                Math.min( imgMax[ 2 ], Math.max( z1, z2 ) + padPixels ) );
        return Views.interval( img, interval );
    }

    /**
     * Get an N-D sub-interval of an N-D image, given two corner points and specified padding. If necessary, the computed
     * sub-interval is clamped at the min and max of each dimension of the input interval.
     *
     * @param img       the source interval
     * @param p1        the first corner point
     * @param p2        the second corner point
     * @param padPixels the amount of padding in each dimension, in pixels
     * @param <T>
     * @return the sub-interval
     */
    public static < T > RandomAccessibleInterval< T > subInterval( final RandomAccessibleInterval< T > img,
                                                                   final Localizable p1, final Localizable p2,
                                                                   final long padPixels )
    {
        final long[] imgMin = Intervals.minAsLongArray( img );
        final long[] imgMax = Intervals.maxAsLongArray( img );
        final int nDim = img.numDimensions();
        final long[] minmax = new long[ 2 * nDim ];
        for ( int d = 0; d < nDim; ++d )
        {
            minmax[ d ] = Math.max( imgMin[ d ], Math.min( p1.getLongPosition( d ), p2.getLongPosition( d ) ) - padPixels );
            minmax[ d + nDim ] = Math.min( imgMax[ d ], Math.max( p1.getLongPosition( d ), p2.getLongPosition( d ) ) + padPixels );
        }
        return Views.interval( img, Intervals.createMinMax( minmax ) );
    }

    /**
     * Partition the source rai into a list of {@link IntervalView} with given dimensions. If the block dimensions are not
     * multiples of the image dimensions, some blocks will have truncated dimensions.
     *
     * @param source the source rai
     * @param blockDimensions the target block size
     * @param <T>
     * @return the list of blocks
     */
    public static < T > List< IntervalView< T > > splitIntoBlocks( final RandomAccessibleInterval< T > source,
                                                                   final long[] blockDimensions )
    {
        final List< IntervalView< T > > views = new ArrayList<>();
        for ( final Interval interval : createIntervals( Intervals.dimensionsAsLongArray( source ), blockDimensions ) )
            views.add( Views.interval( source, interval ) );

        return views;
    }

    /**
     * Partition the source dimensions into a list of {@link Interval}s with given dimensions. If the block dimensions
     * are not multiples of the image dimensions, some blocks will have slightly different dimensions.
     *
     * @param sourceDimensions the source dimensions
     * @param blockDimensions the target block size
     * @return the list of Intervals
     */
    public static List< Interval > createIntervals( final long[] sourceDimensions, final long[] blockDimensions )
    {
        final List< Interval > intervals = new ArrayList<>();
        final long[] min = new long[ sourceDimensions.length ];
        final long[] max = new long[ sourceDimensions.length ];
        createBlocksRecursionLoop( intervals, sourceDimensions, blockDimensions, min, max, 0 );
        return intervals;
    }

    private static void createBlocksRecursionLoop( final List< Interval > intervals, final long[] sourceDimensions,
                                                   final long[] blockDimensions, final long[] min, final long[] max,
                                                   final int d )
    {
        if ( d == min.length )
        {
            for ( int m = 0; m < min.length; ++m )
                max[ m ] = Math.min( min[ m ] + blockDimensions[ m ] - 1, sourceDimensions[ m ] - 1 );

            intervals.add( new FinalInterval( min, max ) );
        }
        else
        {
            for ( min[ d ] = 0; min[ d ] < sourceDimensions[ d ]; min[ d ] += blockDimensions[ d ] )
                createBlocksRecursionLoop( intervals, sourceDimensions, blockDimensions, min, max, d + 1 );
        }
    }

    /**
     * Convert a {@link RandomAccessibleInterval} to an {@link ImagePlus}. If the input has 3 dimensions,
     * the 3rd dimension is treated as depth.
     *
     * @param rai the source rai
     * @param title the title for the converted ImagePlus
     * @param <T>
     * @return the ImagePlus
     */
    public static < T extends NumericType< T > > ImagePlus raiToImp( final RandomAccessibleInterval< T > rai,
                                                                     final String title )
    {
        RandomAccessibleInterval< T > axisCorrected = rai;
        if ( rai.numDimensions() == 3 )
            axisCorrected = Views.permute( Views.addDimension( rai, 0, 0 ), 2, 3 );

        return ImageJFunctions.wrap( axisCorrected, title );
    }

    /**
     * Get a 3D view of a {@link Dataset} at the specified channel and frame. If the Dataset is 2D, a singleton dimension
     * is added.
     *
     * @param dataset the input Dataset
     * @param channelIndex the channel position, 0-indexed
     * @param frameIndex the time position, 0-indexed
     * @param <T>
     * @return the view rai
     */
    public static < T extends RealType< T > > RandomAccessibleInterval< T > getCtSlice3d( final Dataset dataset,
                                                                                          final int channelIndex,
                                                                                          final int frameIndex )
    {
        RandomAccessibleInterval< T > slice = getCtSlice( dataset, channelIndex, frameIndex );
        // bump to 3D
        if ( slice.numDimensions() == 2 )
            slice = Views.addDimension( slice, 0, 0 );

        return slice;
    }

    /**
     * Get a view of the {@link Dataset} at the specified channel and frame.
     *
     * @param dataset the input Dataset
     * @param channelIndex the channel position, 0-indexed
     * @param frameIndex the time position, 0-indexed
     * @param <T>
     * @return the view RAI
     */
    public static < T extends RealType< T > > RandomAccessibleInterval< T > getCtSlice( final Dataset dataset,
                                                                                        final int channelIndex,
                                                                                        final int frameIndex )
    {
        @SuppressWarnings("unchecked")
        RandomAccessibleInterval< T > slice = (RandomAccessibleInterval< T >) dataset;
        if ( dataset.getFrames() > 1 )
            slice = Views.hyperSlice( slice, dataset.dimensionIndex( Axes.TIME ), frameIndex );

        // Assuming time always comes after channel, we can use the same index as the Dataset
        if ( dataset.getChannels() > 1 )
            slice = Views.hyperSlice( slice, dataset.dimensionIndex( Axes.CHANNEL ), channelIndex );

        return slice;
    }

    public static < T extends RealType< T > > RandomAccessibleInterval< T > getCtSlice( final ImagePlus imp )
    {
        RandomAccessibleInterval< T > img = ImgUtils.impToRealRai5d( imp );
        // Extract the relevant part of the imp
        img = Views.hyperSlice( img, 2, imp.getChannel() - 1 );
        img = Views.hyperSlice( img, 3, imp.getFrame() - 1 );
        // If Z is a singleton dimension, drop it
        return Views.dropSingletonDimensions( img );
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
     * Get a view of the {@link ImagePlus} at the specified channel and frame.
     *
     * @param imp the input ImagePlus
     * @param channel the channel position, 1-indexed (as per ImagePlus convention)
     * @param frame the time position, 1-indexed (as per ImagePlus convention)
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
        if ( img.numDimensions() == 2 )
        	img = Views.addDimension( img, 0, 0 );
		return img;
	}

    /**
     * Wrap an {@link ImagePlus} to a {@link RandomAccessibleInterval} such that the number of dimensions in
     * the resulting rai is 5 and the axis order is XYCZT.
     * Axes that are not present in the input imp have singleton dimensions in the rai.
     * <p>
     * For example, given a 2D, multichannel imp, the dimensions of the result rai are
     * [ |X|, |Y|, |C|, 1, 1 ]
     * @param imp
     * @param <T>
     * @return the 5D rai
     */
    public static < T extends RealType< T > > RandomAccessibleInterval< T > impToRealRai5d(
            final ImagePlus imp ) {
        // Note that ImageJFunctions.wrapReal will keep the same dimensions of the input ImagePlus, like so:
        // XY imp -> [X,Y]; XYZ -> [X,Y,Z]; XYC -> [X,Y,C]; XYT -> [X,Y,T]; XYCZT -> [X,Y,C,Z,T], ie., a 2D ImagePlus
        // does not have other zero dimensions
        RandomAccessibleInterval<T> out = ImageJFunctions.wrapReal(imp);
        if (imp.getNChannels() <= 1) { // No C axis
            out = Views.permute(Views.addDimension(out, 0, 0), 2,  out.numDimensions());
        }
        if ( imp.getNSlices() <= 1 ) { // No Z axis
            out = Views.permute(Views.addDimension(out, 0, 0), 3,  out.numDimensions());
        }
        if ( imp.getNFrames() <= 1 ) { // No T axis
            out = Views.permute(Views.addDimension(out, 0, 0), 4,  out.numDimensions());
        }
        return out;
    }

    /**
     * Checks if pos is outside the bounds given by min and max
     * @param pos the position to check
     * @param min the minimum of the interval
     * @param max the maximum of the interval
     * @return true if pos is out of bounds, false otherwise
     */
    public static boolean outOfBounds( final long[] pos, final long[] min, final long[] max )
    {
        for ( int d = 0; d < pos.length; d++ )
            if ( pos[ d ] < min[ d ] || pos[ d ] > max[ d ] )
                return true;

        return false;
    }

}
