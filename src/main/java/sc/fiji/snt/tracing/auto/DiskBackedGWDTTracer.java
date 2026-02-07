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

package sc.fiji.snt.tracing.auto;

import ij.ImagePlus;
import net.imagej.ImgPlus;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import sc.fiji.snt.tracing.auto.gwdt.DiskBackedStorageBackend;
import sc.fiji.snt.tracing.auto.gwdt.StorageBackend;
import sc.fiji.snt.util.ImgUtils;

import java.util.Arrays;

/**
 * Disk-backed GWDT tracer for very large images.
 * <p>
 * Uses disk-based caching to process images of arbitrary size with
 * bounded memory usage (~500MB-1GB). Temporary files are automatically
 * created in the system temp directory and deleted after tracing.
 * </p>
 * <p>
 * Trade-offs:
 * <ul>
 *   <li>Memory: Constant (~500MB-1GB) regardless of image size</li>
 *   <li>Speed: 2-5× slower than {@link GWDTTracer} due to disk I/O</li>
 *   <li>Disk: Requires ~25 bytes per voxel temporary storage</li>
 *   <li>Best for: Images > 2GB or systems with limited RAM</li>
 * </ul>
 * </p>
 * <p>
 * Example use cases:
 * <ul>
 *   <li>Whole-brain light-sheet images (4096×4096×1000)</li>
 *   <li>High-resolution tile scans</li>
 *   <li>Processing on laptops with limited RAM</li>
 * </ul>
 * </p>
 *
 * @param <T> pixel type
 * @author Tiago Ferreira
 * @see AbstractGWDTTracer
 * @see GWDTTracer
 */
public class DiskBackedGWDTTracer<T extends RealType<T>> extends AbstractGWDTTracer<T> {

    /**
     * Creates a new DiskBackedGWDTTracer.
     *
     * @param source  the grayscale image to trace
     * @param spacing voxel dimensions [x, y, z] in physical units
     */
    public DiskBackedGWDTTracer(final RandomAccessibleInterval<T> source, final double[] spacing) {
        super(source, spacing);
    }

    /**
     * Creates a new DiskBackedGWDTTracer from an ImgPlus.
     *
     * @param source the grayscale image to trace
     */
    public DiskBackedGWDTTracer(final ImgPlus<T> source) {
        this(source, ImgUtils.getSpacing(source));
    }

    /**
     * Creates a new DiskBackedGWDTTracer with isotropic spacing (1.0 for each dimension).
     */
    public DiskBackedGWDTTracer(final RandomAccessibleInterval<T> source) {
        this(source, createIsotropicSpacing(source.numDimensions()));
    }

    /**
     * Creates a new DiskBackedGWDTTracer from an ImagePlus.
     *
     * @param source the grayscale image to trace
     */
    public DiskBackedGWDTTracer(final ImagePlus source) {
        this(ImgUtils.getCtSlice(source), getSpacing(source, source.getNSlices() > 1 ? 3 : 2));
    }

    @Override
    protected StorageBackend createStorageBackend() {
        return new DiskBackedStorageBackend(dims);
    }

    private static double[] createIsotropicSpacing(final int nDims) {
        final double[] spacing = new double[nDims];
        Arrays.fill(spacing, 1.0);
        return spacing;
    }

    private static double[] getSpacing(final ImagePlus imp, final int nDims) {
        final double[] spacing;
        if (nDims == 2) {
            spacing = new double[]{
                    imp.getCalibration().pixelWidth,
                    imp.getCalibration().pixelHeight
            };
        } else {
            spacing = new double[]{
                    imp.getCalibration().pixelWidth,
                    imp.getCalibration().pixelHeight,
                    imp.getCalibration().pixelDepth
            };
        }
        return spacing;
    }

    @Override
    protected double[] getSpacing() {
        return spacing;
    }

    @Override
    protected long[] getDimensions() {
        return dims;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static DiskBackedGWDTTracer<?> create(final ImgPlus<?> source) {
        return new DiskBackedGWDTTracer(source);
    }

    @SuppressWarnings({"rawtypes"})
    public static DiskBackedGWDTTracer<?> create(final ImagePlus source) {
        return new DiskBackedGWDTTracer(source);
    }


}
