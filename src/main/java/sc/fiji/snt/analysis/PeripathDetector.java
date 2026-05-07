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
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import sc.fiji.snt.Path;

import java.util.Collection;
import java.util.List;

/**
 * @deprecated Use {@link sc.fiji.snt.analysis.detection.PeripathDetector}
 *             instead. This class is retained for backward compatibility and
 *             delegates all calls to the new package.
 */
@Deprecated
public class PeripathDetector {

    static { net.imagej.patcher.LegacyInjector.preinit(); }

    private PeripathDetector() {}

    /**
     * @deprecated Use {@link sc.fiji.snt.analysis.detection.PeripathDetector.Config} instead.
     */
    @Deprecated
    public static final class Config extends sc.fiji.snt.analysis.detection.PeripathDetector.Config {
    }

    /**
     * @deprecated Use {@link sc.fiji.snt.analysis.detection.Detection} instead.
     */
    @Deprecated
    public static final class Detection extends sc.fiji.snt.analysis.detection.Detection {
        public Detection(final double x, final double y, final double z,
                         final double intensity, final Path path,
                         final int nodeIndex, final double distanceFromSkeleton) {
            super(x, y, z, intensity, path, nodeIndex, distanceFromSkeleton);
        }
    }

    /**
     * @deprecated Use {@link sc.fiji.snt.analysis.detection.PeripathDetector#detect} instead.
     */
    @Deprecated
    public static <T extends RealType<T>> List<sc.fiji.snt.analysis.detection.Detection> detect(
            final Collection<Path> paths,
            final RandomAccessibleInterval<? extends RealType<?>> img,
            final sc.fiji.snt.analysis.detection.PeripathDetector.Config cfg) {
        return sc.fiji.snt.analysis.detection.PeripathDetector.detect(paths, img, cfg);
    }

    /**
     * @deprecated Use {@link sc.fiji.snt.analysis.detection.PeripathDetector#createTorusMask} instead.
     */
    @Deprecated
    public static <T extends RealType<T>> void createTorusMask(
            final Collection<Path> paths,
            final RandomAccessibleInterval<T> output,
            final sc.fiji.snt.analysis.detection.PeripathDetector.Config cfg,
            final double fillValue) {
        sc.fiji.snt.analysis.detection.PeripathDetector.createTorusMask(paths, output, cfg, fillValue);
    }

    /**
     * @deprecated Use {@link sc.fiji.snt.analysis.detection.PeripathDetector#createTorusMask} instead.
     */
    @Deprecated
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void createTorusMask(final Collection<Path> paths,
                                        final ImagePlus output,
                                        final sc.fiji.snt.analysis.detection.PeripathDetector.Config cfg,
                                        final double fillValue) {
        sc.fiji.snt.analysis.detection.PeripathDetector.createTorusMask(paths, output, cfg, fillValue);
    }
}
