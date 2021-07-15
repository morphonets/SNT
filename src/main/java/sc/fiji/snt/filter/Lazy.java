package sc.fiji.snt.filter;

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


}
