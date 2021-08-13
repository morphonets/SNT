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

import net.imagej.ops.Ops;
import net.imagej.ops.special.computer.AbstractUnaryComputerOp;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.convolution.fast_gauss.FastGauss;
import net.imglib2.algorithm.gradient.HessianMatrix;
import net.imglib2.algorithm.linalg.eigen.TensorEigenValues;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.loops.LoopBuilder;
import net.imglib2.outofbounds.OutOfBoundsBorderFactory;
import net.imglib2.parallel.DefaultTaskExecutor;
import net.imglib2.parallel.TaskExecutor;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import sc.fiji.snt.SNTUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;


/**
 * Y. Sato, S. Nakajima, N. Shiraga, H. Atsumi, S. Yoshida, T. Koller, G. Gerig, and R. Kikinis,
 * “Three-dimensional multi-scale line filter for segmentation and visualization of curvilinear
 * structures in medical images,”
 * Med Image Anal., vol. 2, no. 2, pp. 143-168, June 1998.
 *
 * @author Cameron Arshadi
 */
@Plugin(type = Ops.Filter.Tubeness.class)
public class Tubeness<T extends RealType<T>, U extends RealType<U>> extends
        AbstractUnaryComputerOp<RandomAccessibleInterval<T>, RandomAccessibleInterval<U>>
        implements Ops.Filter.Tubeness, Consumer<RandomAccessibleInterval<U>>
{

    @Parameter
    private double[] spacing;
    @Parameter
    private double[] scales;
    @Parameter
    private int numThreads;

    public Tubeness(final double[] scales, final double[] spacing) {
        this(scales, spacing, Runtime.getRuntime().availableProcessors());
    }

    public Tubeness(final double[] scales, final double[] spacing, final int numThreads) {
        this.scales = scales;
        this.spacing = spacing;
        this.numThreads = numThreads;
    }

    @Override
    public void run() {
        compute(in(), out());
    }

    @Override
    public RandomAccessibleInterval<U> run(final RandomAccessibleInterval<U> output) {
        compute(in(), output);
        return output;
    }

    @Override
    public void accept(final RandomAccessibleInterval<U> output) {
        compute(in(), output);
    }

    @Override
    public void compute(RandomAccessibleInterval<T> input, RandomAccessibleInterval<U> output) {

        if (scales == null)
            throw new IllegalArgumentException("scales array is null");

        if (spacing == null)
            throw new IllegalArgumentException("spacing array is null");

        if (numThreads < 1)
            this.numThreads = 1;
        else
            this.numThreads = Math.min(numThreads, Runtime.getRuntime().availableProcessors());

        final int nDim = input.numDimensions();
        if (nDim > 3 || nDim < 2) {
            throw new IllegalArgumentException("Only 2D and 3D images are supported");
        }
        final boolean is3D = (nDim == 3);

        // Convert the desired scales into pixel units
        final List<double[]> sigmas = new ArrayList<>();
        for (final double sc : scales) {
            final double[] sigma;
            if (is3D) {
                sigma = new double[]{sc / spacing[0], sc / spacing[1], sc / spacing[2]};
            } else {
                sigma = new double[]{sc / spacing[0], sc / spacing[1]};
            }
            sigmas.add(sigma);
        }

        final long[] gaussianPad = new long[nDim];
        final long[] gaussianOffset = new long[nDim];

        final long[] gradientPad = new long[nDim + 1];
        gradientPad[gradientPad.length - 1] = is3D ? 3 : 2;
        final long[] gradientOffset = new long[nDim + 1];

        final long[] hessianPad = new long[nDim + 1];
        hessianPad[hessianPad.length - 1] = is3D ? 6 : 3;
        final long[] hessianOffset = new long[nDim + 1];

        final ExecutorService es = Executors.newFixedThreadPool(numThreads);
        final TaskExecutor ex = new DefaultTaskExecutor(es);

        for (final double[] sigma : sigmas) {

            final long[] blockSize = Intervals.dimensionsAsLongArray(output);

            for (int d = 0; d < blockSize.length; ++d) {
                gaussianPad[d] = blockSize[d] + 6;
                gaussianOffset[d] = output.min(d) - 2;
            }
            RandomAccessibleInterval<FloatType> tmpGaussian = Views.translate(
                    ArrayImgs.floats(gaussianPad), gaussianOffset);

            FastGauss.convolve(sigma, Views.extendBorder(input), tmpGaussian);

            for (int d = 0; d < gradientPad.length - 1; ++d) {
                gradientPad[d] = blockSize[d] + 4;
                gradientOffset[d] = output.min(d) - 1;
                hessianPad[d] = blockSize[d];
                hessianOffset[d] = output.min(d);
            }
            RandomAccessibleInterval<FloatType> tmpGradient = ArrayImgs.floats(gradientPad);
            RandomAccessibleInterval<FloatType> tmpHessian = ArrayImgs.floats(hessianPad);

            try {
                HessianMatrix.calculateMatrix(
                        Views.extendBorder(tmpGaussian),
                        Views.translate(tmpGradient, gradientOffset),
                        Views.translate(tmpHessian, hessianOffset),
                        new OutOfBoundsBorderFactory<>(),
                        numThreads,
                        es);
            } catch (InterruptedException | ExecutionException e) {
                SNTUtils.error("Error during hessian matrix computation", e);
                return;
            }

            RandomAccessibleInterval<FloatType> tmpEigenvalues = TensorEigenValues.createAppropriateResultImg(
                    tmpHessian,
                    new ArrayImgFactory<>(new FloatType()));

            TensorEigenValues.calculateEigenValuesSymmetric(
                    tmpHessian,
                    tmpEigenvalues,
                    numThreads,
                    es);

            // FIXME: this normalizes the filter response using the average voxel separation at a scale
            double avgSigma = 0d;
            for (double s : sigma) {
                avgSigma += s;
            }
            avgSigma /= sigma.length;

            RandomAccessibleInterval<FloatType> tmpTubeness = ArrayImgs.floats(blockSize);
            if (is3D) {
                tubeness3D(tmpEigenvalues, tmpTubeness, avgSigma, ex);
            } else {
                tubeness2D(tmpEigenvalues, tmpTubeness, avgSigma, ex);
            }
            LoopBuilder.setImages(output, tmpTubeness)
                    .multiThreaded(ex)
                    .forEachPixel((b, t) -> b.setReal(Math.max(b.getRealDouble(), t.getRealDouble())));

        }
        ex.close();
    }

    private static void tubeness2D(final RandomAccessibleInterval<FloatType> eigenvalueRai,
                                   final RandomAccessibleInterval<FloatType> tubenessRai, double sigma,
                                   final TaskExecutor ex)
    {
        final int d = eigenvalueRai.numDimensions() - 1;
        final IntervalView<FloatType> evs0 = Views.hyperSlice(eigenvalueRai, d, 0);
        final IntervalView<FloatType> evs1 = Views.hyperSlice(eigenvalueRai, d, 1);
        // normalize filter response for fair comparison at multiple scales
        final double norm = sigma * sigma;

        LoopBuilder.setImages(evs0, evs1, tubenessRai).multiThreaded(ex).forEachPixel(
                (ev0, ev1, v) -> {
                    double e0 = ev0.getRealDouble();
                    double e1 = ev1.getRealDouble();
                    // Sort by absolute value
                    if (Math.abs(e0) > Math.abs(e1)) {
                        e1 = e0;
                    }
                    double result = 0;
                    if (e1 < 0) {
                        result = norm * Math.abs(e1);
                    }
                    v.setReal(result);
                }
        );
    }

    private static void tubeness3D(final RandomAccessibleInterval<FloatType> eigenvalueRai,
                                   final RandomAccessibleInterval<FloatType> tubenessRai, double sigma,
                                   final TaskExecutor ex)
    {
        final int d = eigenvalueRai.numDimensions() - 1;
        final IntervalView<FloatType> evs0 = Views.hyperSlice(eigenvalueRai, d, 0);
        final IntervalView<FloatType> evs1 = Views.hyperSlice(eigenvalueRai, d, 1);
        final IntervalView<FloatType> evs2 = Views.hyperSlice(eigenvalueRai, d, 2);
        // normalize filter response for fair comparison at multiple scales
        final double norm = sigma * sigma;

        LoopBuilder.setImages(evs0, evs1, evs2, tubenessRai).multiThreaded(ex).forEachPixel(
                (ev0, ev1, ev2, v) -> {
                    double e0 = ev0.getRealDouble();
                    double e1 = ev1.getRealDouble();
                    double e2 = ev2.getRealDouble();
                    // Sort by absolute value
                    double temp;
                    if (Math.abs(e0) > Math.abs(e1)) {
                        temp = e0;
                        e0 = e1;
                        e1 = temp;
                    }
                    if (Math.abs(e1) > Math.abs(e2)) {
                        temp = e1;
                        e1 = e2;
                        e2 = temp;
                    }
                    if (Math.abs(e0) > Math.abs(e1)) {
                        // skip assignment to e0, not used in filter
                        e1 = e0;
                    }
                    double result = 0;
                    if (e1 < 0 && e2 < 0) {
                        result = norm * Math.sqrt(e1 * e2);
                    }
                    v.setReal(result);
                }
        );
    }

}
