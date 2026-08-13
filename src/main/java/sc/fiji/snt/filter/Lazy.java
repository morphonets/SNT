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
import sc.fiji.snt.SNTUtils;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Static helpers for creating lazy filtered images
 * <p>
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
        return createImg(targetInterval, blockSize, type, loader, SNTUtils.getCacheDir().toPath());
    }

    /**
     * As {@link #createImg(Interval, int[], NativeType, CellLoader)}, but the disk cache is created
     * directly under {@code cacheDir} rather than SNT's shared {@link SNTUtils#getCacheDir()}. Use this
     * overload when the caller needs to track and explicitly remove this specific cache later (e.g. it
     * backs a long-lived image that may be replaced several times within a session, so its disk usage
     * would otherwise accumulate until the JVM exits).
     */
    public static <T extends NativeType<T>> CachedCellImg<T, ?> createImg(
            final Interval targetInterval,
            final int[] blockSize,
            final T type,
            final CellLoader<T> loader,
            final Path cacheDir)
    {
        return new DiskCachedCellImgFactory<>(
                type,
                DiskCachedCellImgOptions.options()
                        .cellDimensions(blockSize)
                        .cacheType(CacheOptions.CacheType.SOFTREF)
                        .initializeCellsAsDirty(true)
                        // Nested under SNTUtils#getCacheDir() (or a caller-supplied subdirectory of it)
                        // rather than left at this library's own default temp location, so this cache
                        // stays discoverable at a fixed, SNT-owned path alongside DiskBackedStorageBackend's
                        .tempDirectory(cacheDir)
                        // Explicit rather than relying on the library's own default: unless a caller
                        // tracks and removes cacheDir itself (see the overload above), this cache is
                        // never disposed of explicitly elsewhere in SNT (unlike DiskBackedStorageBackend,
                        // whose dispose() removes its own temp dir immediately after each run), so a
                        // JVM-exit shutdown hook is this cache's only fallback cleanup path
                        .deleteCacheDirectoryOnExit(true))
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
                new UnaryComputerOpCellLoader<>(
                        source,
                        op));
    }

    /**
     * As {@link #process(RandomAccessibleInterval, Interval, int[], NativeType, UnaryComputerOp)}, but the
     * disk cache is created under the caller-supplied {@code cacheDir} -- see
     * {@link #createImg(Interval, int[], NativeType, CellLoader, Path)} for when to use this.
     */
    public static <I, O extends NativeType<O>> CachedCellImg<O, ?> process(
            final RandomAccessibleInterval<I> source,
            final Interval sourceInterval,
            final int[] blockSize,
            final O type,
            final UnaryComputerOp<RandomAccessibleInterval<I>, RandomAccessibleInterval<O>> op,
            final Path cacheDir) {

        return createImg(
                sourceInterval,
                blockSize,
                type,
                new UnaryComputerOpCellLoader<>(
                        source,
                        op),
                cacheDir);
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
                new UnaryComputerOpCellLoader<>(
                        source,
                        opService,
                        opClass,
                        opArgs));
    }

}
