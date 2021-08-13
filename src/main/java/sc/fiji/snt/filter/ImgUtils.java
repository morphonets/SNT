/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2021 Fiji developers.
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

package sc.fiji.snt.filter;

import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.Type;
import net.imglib2.util.Intervals;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Static utilities for handling and manipulation of {@link RandomAccessibleInterval}s
 *
 * @author Cameron Arshadi
 */
public class ImgUtils {

    private ImgUtils() { }

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

    public static <T> RandomAccessibleInterval<T> getSubVolume(final RandomAccessibleInterval<T> img,
                                                               final long x1, final long y1, final long z1,
                                                               final long x2, final long y2, final long z2,
                                                               final int padPixels)
    {
        // Create some padding around the start and goal nodes
        long[] imgMin = Intervals.minAsLongArray(img);
        long[] imgMax = Intervals.maxAsLongArray(img);
        if (img.numDimensions() == 2) {
            imgMin = Arrays.copyOf(imgMin, 3);
            imgMax = Arrays.copyOf(imgMax, 3);
        }
        final FinalInterval interval = Intervals.createMinMax(
                Math.max(imgMin[0], Math.min(x1, x2) - padPixels),
                Math.max(imgMin[1], Math.min(y1, y2) - padPixels),
                Math.max(imgMin[2], Math.min(z1, z2) - padPixels),
                Math.min(imgMax[0], Math.max(x1, x2) + padPixels),
                Math.min(imgMax[1], Math.max(y1, y2) + padPixels),
                Math.min(imgMax[2], Math.max(z1, z2) + padPixels));
        return Views.interval(img, interval);
    }

    public static <T extends Type<T>> List<IntervalView<T>> splitIntoBlocks(final RandomAccessibleInterval<T> source,
                                                                            final long[] blockDimensions)
    {
        final List<IntervalView<T>> views = new ArrayList<>();
        for (final FinalInterval interval : createIntervals(Intervals.dimensionsAsLongArray(source), blockDimensions))
        {
            views.add(Views.interval(source, interval));
        }
        return views;
    }

    public static List<FinalInterval> createIntervals(final long[] sourceDimensions, final long[] blockDimensions) {
        final List<FinalInterval> intervals = new ArrayList<>();
        final long[] min = new long[sourceDimensions.length];
        final long[] max = new long[sourceDimensions.length];
        createBlocksRecursionLoop(intervals, sourceDimensions, blockDimensions, min, max, 0);
        return intervals;
    }

    private static void createBlocksRecursionLoop(final List<FinalInterval> intervals, final long[] sourceDimensions,
                                                  final long[] blockDimensions, final long[] min, final long[] max,
                                                  final int d)
    {
        if (d == min.length) {
            for (int m = 0; m < min.length; ++m) {
                max[m] = Math.min(min[m] + blockDimensions[m] - 1, sourceDimensions[m] - 1);
            }
            intervals.add(new FinalInterval(min, max));
        } else {
            for (min[d] = 0; min[d] < sourceDimensions[d]; min[d] += blockDimensions[d]) {
                createBlocksRecursionLoop(intervals, sourceDimensions, blockDimensions, min, max, d + 1);
            }
        }
    }

}
