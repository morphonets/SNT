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
import sc.fiji.snt.tracing.auto.gwdt.SparseStorageBackend;
import sc.fiji.snt.tracing.auto.gwdt.StorageBackend;
import sc.fiji.snt.util.ImgUtils;

/**
 * Sparse GWDT tracer using hash map storage for memory efficiency.
 * <p>
 * Uses hash maps to store only non-default values, achieving 10-100× memory
 * reduction compared to {@link GWDTTracer} for sparse neuronal structures.
 * </p>
 * Trade-offs:
 * <ul>
 *   <li>Memory: 10-100× less than array storage</li>
 *   <li>Speed: ~1.5-2× slower due to hash lookups</li>
 *   <li>Best for: Thin structures, lots of background, limited RAM</li>
 * </ul>
 *
 * Example: A 1024×1024×100 16-bit image:
 * <ul>
 *   <li>GWDTTracer (array): ~2.5GB RAM</li>
 *   <li>SparseGWDTTracer: ~100-500MB RAM for sparse structures (5-10% foreground),
 *       up to ~4GB for dense structures (>40% foreground)</li>
 *   <li>DiskBackedGWDTTracer: ~500MB RAM + ~100GB temporary disk</li>
 * </ul>
 *
 * @param <T> pixel type
 * @author Tiago Ferreira
 * @see AbstractGWDTTracer
 * @see GWDTTracer
 */
public class SparseGWDTTracer<T extends RealType<T>> extends AbstractGWDTTracer<T> {

    /**
     * Creates a new SparseGWDTTracer.
     *
     * @param source  the grayscale image to trace
     * @param spacing voxel dimensions [x, y, z] in physical units
     */
    public SparseGWDTTracer(final RandomAccessibleInterval<T> source, final double[] spacing) {
        super(source, spacing);
    }

    /**
     * Creates a new SparseGWDTTracer from an ImgPlus.
     *
     * @param source the grayscale image to trace
     */
    public SparseGWDTTracer(final ImgPlus<T> source) {
        this(source, ImgUtils.getSpacing(source));
    }

    /**
     * Creates a new SparseGWDTTracer with isotropic spacing (1.0 for each dimension).
     */
    public SparseGWDTTracer(final RandomAccessibleInterval<T> source) {
        this(source, createIsotropicSpacing(source.numDimensions()));
    }

    /**
     * Creates a new SparseGWDTTracer from an ImagePlus.
     *
     * @param source the grayscale image to trace
     */
    public SparseGWDTTracer(final ImagePlus source) {
        this(ImgUtils.getCtSlice(source), getSpacing(source, source.getNSlices() > 1 ? 3 : 2));
    }

    @Override
    protected StorageBackend createStorageBackend() {
        return new SparseStorageBackend(dims);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static SparseGWDTTracer<?> create(final ImgPlus<?> source) {
        return new SparseGWDTTracer(source);
    }

    @SuppressWarnings({"rawtypes"})
    public static SparseGWDTTracer<?> create(final ImagePlus source) {
        return new SparseGWDTTracer(source);
    }

}
