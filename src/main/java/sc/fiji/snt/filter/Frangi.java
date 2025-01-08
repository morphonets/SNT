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
import net.imglib2.type.numeric.real.DoubleType;
import net.imglib2.util.Intervals;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;
import org.scijava.Priority;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * A.F. Frangi, W.J. Niessen, K.L. Vincken, M.A. Viergever (1998). Multiscale vessel enhancement filtering.
 * In Medical Image Computing and Computer-Assisted Intervention - MICCAI'98, W.M. Wells, A. Colchester and S.L. Delp (Eds.),
 * Lecture Notes in Computer Science, vol. 1496 - Springer Verlag, Berlin, Germany, pp. 130-137.
 *
 * @author Cameron Arshadi
 */
@Plugin(type = Ops.Filter.FrangiVesselness.class, priority = Priority.NORMAL)
public class Frangi<T extends RealType<T>, U extends RealType<U>> extends
        AbstractUnaryComputerOp<RandomAccessibleInterval<T>, RandomAccessibleInterval<U>>
        implements Ops.Filter.FrangiVesselness, Consumer<RandomAccessibleInterval<U>>
{

    @Parameter
    private double[] spacing;
    @Parameter
    private double[] scales;
    @Parameter
    private double stackMax;
    @Parameter
    private int numThreads;


    public Frangi(final double[] scales, final double[] spacing, final double stackMax) {
        this(scales, spacing, stackMax, Runtime.getRuntime().availableProcessors());
    }

    public Frangi(final double[] scales, final double[] spacing, final double stackMax, final int numThreads) {
        this.scales = scales;
        this.spacing = spacing;
        this.stackMax = stackMax;
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
    public void compute(final RandomAccessibleInterval<T> input, final RandomAccessibleInterval<U> output) {

        final int nDim = input.numDimensions();
        if (nDim > 3 || nDim < 2) {
            throw new IllegalArgumentException("Only 2D and 3D images are supported");
        }
        if (output.numDimensions() != nDim) {
            throw new IllegalArgumentException("input and output must have same dimensions");
        }
        final boolean is3D = (nDim == 3);

        if (scales == null)
            throw new IllegalArgumentException("scales array is null");

        if (spacing == null)
            throw new IllegalArgumentException("spacing array is null");

        if (stackMax <= 0)
            throw new IllegalArgumentException("stackMax must be greater than 0");

        if (numThreads < 1)
            this.numThreads = 1;
        else
            this.numThreads = Math.min(numThreads, Runtime.getRuntime().availableProcessors());

        // Convert the desired scales (physical units) into sigmas (pixel units)
        final List<double[]> sigmas = new ArrayList<>();
        for (final double sc : scales) {
            final double[] sigma = new double[nDim];
            for (int d = 0; d < nDim; ++d) {
                sigma[d] = sc / spacing[d];
            }
            sigmas.add(sigma);
        }

        final long[] gaussianPad = new long[nDim];
        final long[] gaussianOffset = new long[nDim];

        final long[] gradientPad = new long[nDim + 1];
        gradientPad[gradientPad.length - 1] = nDim;
        final long[] gradientOffset = new long[nDim + 1];

        final long[] hessianPad = new long[nDim + 1];
        hessianPad[hessianPad.length - 1] = nDim * (nDim + 1) / 2;
        final long[] hessianOffset = new long[nDim + 1];

        final ExecutorService es = Executors.newFixedThreadPool(numThreads);

        try (TaskExecutor ex = new DefaultTaskExecutor(es)) {
            for (final double[] sigma : sigmas) {

                final long[] blockSize = Intervals.dimensionsAsLongArray(output);

                for (int d = 0; d < blockSize.length; ++d) {
                    gaussianPad[d] = blockSize[d] + 6;
                    gaussianOffset[d] = output.min(d) - 2;
                }
                RandomAccessibleInterval<DoubleType> tmpGaussian = Views.translate(
                        ArrayImgs.doubles(gaussianPad), gaussianOffset);

                FastGauss.convolve(sigma, Views.extendBorder(input), tmpGaussian);

                for (int d = 0; d < gradientPad.length - 1; ++d) {
                    gradientPad[d] = blockSize[d] + 4;
                    gradientOffset[d] = output.min(d) - 1;
                    hessianPad[d] = blockSize[d];
                    hessianOffset[d] = output.min(d);
                }
                RandomAccessibleInterval<DoubleType> tmpGradient = ArrayImgs.doubles(gradientPad);
                RandomAccessibleInterval<DoubleType> tmpHessian = ArrayImgs.doubles(hessianPad);

                HessianMatrix.calculateMatrix(
                        Views.extendBorder(tmpGaussian),
                        Views.translate(tmpGradient, gradientOffset),
                        Views.translate(tmpHessian, hessianOffset),
                        new OutOfBoundsBorderFactory<>(),
                        numThreads,
                        es);

                // FIXME: this normalizes the filter response using the average voxel separation at a scale
                double avgSigma = 0d;
                for (double s : sigma) {
                    avgSigma += s;
                }
                avgSigma /= sigma.length;
                final double norm = avgSigma * avgSigma;
                LoopBuilder.setImages(tmpHessian).multiThreaded(ex).forEachPixel((h) -> h.mul(norm));

                RandomAccessibleInterval<DoubleType> tmpEigenvalues = TensorEigenValues.createAppropriateResultImg(
                        tmpHessian,
                        new ArrayImgFactory<>(new DoubleType()));

                TensorEigenValues.calculateEigenValuesSymmetric(
                        tmpHessian,
                        tmpEigenvalues,
                        numThreads,
                        es);

                final double c = stackMax / 4.0;
                RandomAccessibleInterval<DoubleType> tmpFrangi = ArrayImgs.doubles(blockSize);
                if (is3D) {
                    frangi3D(tmpEigenvalues, tmpFrangi, 0.5, 0.5, c, ex);
                } else {
                    frangi2D(tmpEigenvalues, tmpFrangi, 0.5, c, ex);
                }
                LoopBuilder.setImages(output, tmpFrangi).multiThreaded(ex).forEachPixel((b, t) ->
                        b.setReal(Math.max(b.getRealDouble(), t.getRealDouble())));

            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

        } catch (ExecutionException e) {
            e.printStackTrace();

        } catch (OutOfMemoryError e) {
            System.err.println("Out of memory computing Frangi. Try Lazy processing instead.");
            e.printStackTrace();
        }

    }

    private static void frangi2D(final RandomAccessibleInterval<DoubleType> eigenvalueRai,
                                 final RandomAccessibleInterval<DoubleType> frangiRai,
                                 final double beta, final double c, final TaskExecutor ex)
    {
        final double betaDen = 2 * beta * beta;
        final double cDen = 2 * c * c;

        final int d = eigenvalueRai.numDimensions() - 1;
        final IntervalView<DoubleType> evs0 = Views.hyperSlice(eigenvalueRai, d, 0);
        final IntervalView<DoubleType> evs1 = Views.hyperSlice(eigenvalueRai, d, 1);

        LoopBuilder.setImages(evs0, evs1, frangiRai).multiThreaded(ex).forEachPixel(
                (ev0, ev1, v) -> {
                    double e0 = ev0.getRealDouble();
                    double e1 = ev1.getRealDouble();
                    // Sort by absolute value
                    if (Math.abs(e0) > Math.abs(e1)) {
                        double temp = e0;
                        e0 = e1;
                        e1 = temp;
                    }
                    double result = 0;
                    if (e1 < 0) {
                        final double rb = e0 / e1;
                        final double rbsq = rb * rb;
                        final double s = Math.sqrt(e0 * e0 + e1 * e1);
                        final double ssq = s * s;
                        result = Math.exp(-rbsq / betaDen) * (1 - Math.exp(-ssq / cDen));
                        if (Double.isNaN(result)) {
                            result = 0;
                        }
                    }
                    v.setReal(result);
                }
        );
    }

    private static void frangi3D(final RandomAccessibleInterval<DoubleType> eigenvalueRai,
                                 final RandomAccessibleInterval<DoubleType> frangiRai,
                                 final double alpha, final double beta, final double c, final TaskExecutor ex)
    {
        final double alphaDen = 2 * alpha * alpha;
        final double betaDen = 2 * beta * beta;
        final double cDen = 2 * c * c;

        final int d = eigenvalueRai.numDimensions() - 1;
        final IntervalView<DoubleType> evs0 = Views.hyperSlice(eigenvalueRai, d, 0);
        final IntervalView<DoubleType> evs1 = Views.hyperSlice(eigenvalueRai, d, 1);
        final IntervalView<DoubleType> evs2 = Views.hyperSlice(eigenvalueRai, d, 2);

        LoopBuilder.setImages(evs0, evs1, evs2, frangiRai).multiThreaded(ex).forEachPixel(
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
                        temp = e0;
                        e0 = e1;
                        e1 = temp;
                    }
                    double result = 0;
                    if (e1 < 0 && e2 < 0) {
                        final double rb = Math.abs(e0) / Math.sqrt(Math.abs(e1 * e2));
                        final double rbsq = rb * rb;
                        final double ra = Math.abs(e1) / Math.abs(e2);
                        final double rasq = ra * ra;
                        final double s = Math.sqrt(e0 * e0 + e1 * e1 + e2 * e2);
                        final double ssq = s * s;
                        result = (1 - Math.exp(-rasq / alphaDen)) *
                                Math.exp(-rbsq / betaDen) *
                                (1 - Math.exp(-ssq / cDen));
                        if (Double.isNaN(result)) {
                            result = 0;
                        }
                    }
                    v.setReal(result);
                }
        );
    }

}
