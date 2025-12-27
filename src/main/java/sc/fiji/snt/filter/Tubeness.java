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

import net.imagej.ImgPlus;
import net.imagej.ops.Ops;
import net.imagej.ops.special.computer.AbstractUnaryComputerOp;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.convolution.fast_gauss.FastGauss;
import net.imglib2.algorithm.gradient.HessianMatrix;
import net.imglib2.algorithm.linalg.eigen.TensorEigenValues;
import net.imglib2.img.array.ArrayImg;
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
import sc.fiji.snt.util.ImgUtils;

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
@Plugin(type = Ops.Filter.Tubeness.class, priority = Priority.NORMAL)
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

    /**
     * Empty constructor required for proper loading by OpService. Should not be
     * called directly.
     */
    public Tubeness() {
        // empty constructor: fix for https://github.com/morphonets/SNT/issues/173
    }

    /**
     * Constructs a Tubeness filter with the specified scales and spacing.
     * Uses the default number of threads (available processors).
     *
     * @param scales the scales for multi-scale analysis
     * @param spacing the pixel spacing in each dimension
     */
    public Tubeness(final double[] scales, final double[] spacing) {
        this(scales, spacing, Runtime.getRuntime().availableProcessors());
    }

    /**
     * Constructs a Tubeness filter with the specified scales, spacing, and number of threads.
     *
     * @param scales the scales for multi-scale analysis
     * @param spacing the pixel spacing in each dimension
     * @param numThreads the number of threads to use for computation
     */
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

        try (final TaskExecutor ex = new DefaultTaskExecutor(es)) {
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

                RandomAccessibleInterval<DoubleType> tmpEigenvalues = TensorEigenValues.createAppropriateResultImg(
                        tmpHessian,
                        new ArrayImgFactory<>(new DoubleType()));

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

                RandomAccessibleInterval<DoubleType> tmpTubeness = ArrayImgs.doubles(blockSize);
                if (is3D) {
                    tubeness3D(tmpEigenvalues, tmpTubeness, avgSigma, ex);
                } else {
                    tubeness2D(tmpEigenvalues, tmpTubeness, avgSigma, ex);
                }
                LoopBuilder.setImages(output, tmpTubeness)
                        .multiThreaded(ex)
                        .forEachPixel((b, t) -> b.setReal(Math.max(b.getRealDouble(), t.getRealDouble())));

            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

        } catch (ExecutionException e) {
            e.printStackTrace();

        } catch (OutOfMemoryError e) {
            System.err.println("Out of memory computing Tubeness. Try Lazy processing instead.");
            e.printStackTrace();
        }

    }

    private void tubeness2D(final RandomAccessibleInterval<DoubleType> eigenvalueRai,
                            final RandomAccessibleInterval<DoubleType> tubenessRai,
                            final double sigma,
                            final TaskExecutor ex)
    {
        final int d = eigenvalueRai.numDimensions() - 1;
        final IntervalView<DoubleType> evs0 = Views.hyperSlice(eigenvalueRai, d, 0);
        final IntervalView<DoubleType> evs1 = Views.hyperSlice(eigenvalueRai, d, 1);
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

    private void tubeness3D(final RandomAccessibleInterval<DoubleType> eigenvalueRai,
                            final RandomAccessibleInterval<DoubleType> tubenessRai,
                            final double sigma,
                            final TaskExecutor ex)
    {
        final int d = eigenvalueRai.numDimensions() - 1;
        final IntervalView<DoubleType> evs0 = Views.hyperSlice(eigenvalueRai, d, 0);
        final IntervalView<DoubleType> evs1 = Views.hyperSlice(eigenvalueRai, d, 1);
        final IntervalView<DoubleType> evs2 = Views.hyperSlice(eigenvalueRai, d, 2);
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

    /**
     * Apply multiscale tubeness filter to an ImgPlus.
     * <p>
     * Spacing is extracted from axis metadata.
     * </p>
     *
     * @param <T>    input pixel type
     * @param img    input image (2D or 3D) with calibrated axes
     * @param scales scales for multiscale analysis (in physical units)
     * @return filtered ImgPlus with same axes as input
     */
    public static <T extends RealType<T>> ImgPlus<DoubleType> apply(
            final ImgPlus<T> img,
            final double[] scales) {
        return apply(img, scales, Runtime.getRuntime().availableProcessors());
    }

    /**
     * Apply multiscale tubeness filter to an ImgPlus.
     *
     * @param <T>        input pixel type
     * @param img        input image (2D or 3D) with calibrated axes
     * @param scales     scales for multiscale analysis (in physical units)
     * @param numThreads number of threads to use
     * @return filtered ImgPlus with same axes as input
     */
    public static <T extends RealType<T>> ImgPlus<DoubleType> apply(
            final ImgPlus<T> img,
            final double[] scales,
            final int numThreads) {

        final int nDim = img.numDimensions();

        // Extract spacing from axes
        final double[] spacing = new double[nDim];
        for (int d = 0; d < nDim; d++) {
            spacing[d] = img.axis(d).averageScale(0, 1);
        }

        // Create output
        final long[] dims = Intervals.dimensionsAsLongArray(img);
        final ArrayImg<DoubleType, ?> output = ArrayImgs.doubles(dims);

        // Run filter
        final Tubeness<T, DoubleType> tubeness = new Tubeness<>(scales, spacing, numThreads);
        tubeness.compute(img, output);

        // Wrap with axes from source
        return ImgUtils.wrapWithAxes(output, img, "Tubeness");
    }

    /**
     * Apply single-scale tubeness filter to an ImgPlus.
     *
     * @param <T>   input pixel type
     * @param img   input image (2D or 3D) with calibrated axes
     * @param scale scale for analysis (in physical units)
     * @return filtered ImgPlus with same axes as input
     */
    public static <T extends RealType<T>> ImgPlus<DoubleType> apply(
            final ImgPlus<T> img,
            final double scale) {
        return apply(img, new double[]{scale});
    }
}
