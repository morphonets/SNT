/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2022 Fiji developers.
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

import net.imagej.ops.Op;
import net.imagej.ops.OpService;
import net.imagej.ops.special.computer.UnaryComputerOp;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.cache.img.*;
import net.imglib2.cache.img.optional.CacheOptions;
import net.imglib2.type.NativeType;
import net.imglib2.util.Intervals;

import java.util.function.Consumer;

/**
 * Static helpers for creating lazy filtered images
 *
 * This Class is a direct port of code written by the Saalfeld lab at Janelia Research Campus, original source at
 * <a href=https://github.com/saalfeldlab/hot-knife/blob/master/src/main/java/org/janelia/saalfeldlab/hotknife/util/Lazy.java>https://github.com/saalfeldlab/hot-knife</a>
 *
 * @author Cameron Arshadi
 */
public class Lazy {

    private Lazy() { }

    public static <T extends NativeType<T>> CachedCellImg<T, ?> createImg(
            final Interval targetInterval,
            final int[] blockSize,
            final T type,
            final CellLoader<T> loader)
    {
        return new DiskCachedCellImgFactory<>(
                type,
                DiskCachedCellImgOptions.options()
                        .cellDimensions(blockSize)
                        .cacheType(CacheOptions.CacheType.SOFTREF)
                        .initializeCellsAsDirty(true))
                .create(Intervals.dimensionsAsLongArray(targetInterval), loader);
    }

    /**
     * Create a {@link DiskCachedCellImg} with a cell generator {@link Consumer}.
     *
     * @param targetInterval
     * @param blockSize
     * @param type
     * @param op
     * @return
     */
    public static <T extends NativeType<T>> CachedCellImg<T, ?> process(
            final Interval targetInterval,
            final int[] blockSize,
            final T type,
            final Consumer<RandomAccessibleInterval<T>> op)
    {
        return createImg(
                targetInterval,
                blockSize,
                type,
                op::accept);
    }

    /**
     * Create a {@link DiskCachedCellImg} with a cell generator {@link UnaryComputerOp}.
     *
     * @param source
     * @param sourceInterval
     * @param blockSize
     * @param type
     * @param op
     * @return
     */
    public static <I, O extends NativeType<O>> CachedCellImg<O, ?> process(
            final RandomAccessibleInterval<I> source,
            final Interval sourceInterval,
            final int[] blockSize,
            final O type,
            final UnaryComputerOp<RandomAccessibleInterval<I>, RandomAccessibleInterval<O>> op) {

        return createImg(
                sourceInterval,
                blockSize,
                type,
                new UnaryComputerOpCellLoader<I, O, RandomAccessibleInterval<I>>(
                        source,
                        op));
    }

    /**
     * Create a {@link DiskCachedCellImg} with a cell generator
     * {@link UnaryComputerOp} provided by an {@link OpService}.
     *
     * @param source
     * @param sourceInterval
     * @param blockSize
     * @param type
     * @param opService
     * @param opClass
     * @param opArgs
     * @return
     */
    public static <I, O extends NativeType<O>, P extends Op> CachedCellImg<O, ?> process(
            final RandomAccessibleInterval<I> source,
            final Interval sourceInterval,
            final int[] blockSize,
            final O type,
            final OpService opService,
            final Class<P> opClass,
            final Object... opArgs) {

        return createImg(
                sourceInterval,
                blockSize,
                type,
                new UnaryComputerOpCellLoader<I, O, RandomAccessibleInterval<I>>(
                        source,
                        opService,
                        opClass,
                        opArgs));
    }

}
