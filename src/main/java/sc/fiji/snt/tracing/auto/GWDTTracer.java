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
import sc.fiji.snt.Tree;
import sc.fiji.snt.tracing.auto.gwdt.ArrayStorageBackend;
import sc.fiji.snt.tracing.auto.gwdt.StorageBackend;
import sc.fiji.snt.util.ImgUtils;

import java.util.List;

/**
 * In-memory GWDT tracer using array storage.
 * <p>
 * Fast but memory-intensive. Best for images &lt; 1GB.
 * Uses APP2-style algorithm: Gray-Weighted Distance Transform,
 * Fast Marching, and hierarchical pruning.
 * </p>
 *
 * @param <T> pixel type
 * @author Tiago Ferreira
 * @see AbstractGWDTTracer
 * @see <a href="https://pubmed.ncbi.nlm.nih.gov/23603332/">PMID: 23603332</a>
 */
public class GWDTTracer<T extends RealType<T>> extends AbstractGWDTTracer<T> {

    /**
     * Creates a new GWDTTracer.
     *
     * @param source  the grayscale image to trace
     * @param spacing voxel dimensions [x, y, z] in physical units
     */
    public GWDTTracer(final RandomAccessibleInterval<T> source, final double[] spacing) {
        super(source, spacing);
    }

    /**
     * Creates a new GWDTTracer from an ImgPlus.
     *
     * @param source the grayscale image to trace
     */
    public GWDTTracer(final ImgPlus<T> source) {
        this(source, ImgUtils.getSpacing(source));
    }

    /**
     * Creates a new GWDTTracer with isotropic spacing (1.0 for each dimension).
     */
    public GWDTTracer(final RandomAccessibleInterval<T> source) {
        this(source, createIsotropicSpacing(source.numDimensions()));
    }

    /**
     * Creates a new GWDTTracer from an ImagePlus.
     *
     * @param source the grayscale image to trace
     */
    public GWDTTracer(final ImagePlus source) {
        this(ImgUtils.getCtSlice(source), getSpacing(source, source.getNSlices() > 1 ? 3 : 2));
    }

    @Override
    protected StorageBackend createStorageBackend() {
        return new ArrayStorageBackend(dims);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static GWDTTracer<?> create(final ImgPlus<?> source) {
        return new GWDTTracer(source);
    }

    @SuppressWarnings({"rawtypes"})
    public static GWDTTracer<?> create(final ImagePlus source) {
        return new GWDTTracer(source);
    }

    public static void main(String[] args) {
        ImagePlus imp = new sc.fiji.snt.SNTService().demoImage("OP1");
        //imp = sc.fiji.snt.util.ImpUtils.getMIP(imp);
        final GWDTTracer<?> tracer = new GWDTTracer<>(imp);
        tracer.setSeedPhysical(new double[]{11.208050, 141.749, 0.000});
        tracer.setVerbose(true);
        final List<Tree> trees = tracer.traceTrees();
        System.out.println("Trees: " + (trees != null ? trees.size() : 0));
        if (trees != null && !trees.isEmpty()) {
            final sc.fiji.snt.viewer.Viewer3D viewer = new sc.fiji.snt.viewer.Viewer3D();
            trees.forEach(tree -> tree.setColor("red"));
            viewer.addTrees(trees, "red");
            final Tree ref = new sc.fiji.snt.SNTService().demoTree("OP1");
            ref.translate(1, 1, 0);
            ref.setColor("blue");
            viewer.add(ref);
            viewer.show();
        }
    }
}
