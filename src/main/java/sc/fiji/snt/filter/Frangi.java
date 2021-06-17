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

import ij.ImagePlus;
import ij.measure.Calibration;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.convolution.fast_gauss.FastGauss;
import net.imglib2.algorithm.gradient.HessianMatrix;
import net.imglib2.algorithm.linalg.eigen.TensorEigenValues;
import net.imglib2.algorithm.stats.ComputeMinMax;
import net.imglib2.img.Img;
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
import sc.fiji.snt.SNTUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A.F. Frangi, W.J. Niessen, K.L. Vincken, M.A. Viergever (1998). Multiscale vessel enhancement filtering.
 * In Medical Image Computing and Computer-Assisted Intervention - MICCAI'98, W.M. Wells, A. Colchester and S.L. Delp (Eds.),
 * Lecture Notes in Computer Science, vol. 1496 - Springer Verlag, Berlin, Germany, pp. 130-137.
 *
 * @author Cameron Arshadi
 */
public class Frangi extends AbstractFilter {

    private final boolean is3D;
    private double[] scales;

    public Frangi(final RandomAccessibleInterval<? extends RealType<?>> img, final double[] scales,
                  final Calibration calibration)
    {
        super(img, calibration);
        final int nDim = img.numDimensions();
        if (nDim < 2 || nDim > 3) {
            throw new IllegalArgumentException("Only 2 or 3 dimensional images are supported.");
        }
        this.is3D = nDim == 3;
        this.scales = scales;
    }

    public Frangi(final ImagePlus imp, final double[] scales) {
        super(imp);
        final int nDim = this.img.numDimensions();
        if (nDim < 2 || nDim > 3) {
            throw new IllegalArgumentException("Only 2 or 3 dimensional images are supported.");
        }
        this.is3D = nDim == 3;
        this.scales = scales;
    }

    public void setScales(final double[] scales) {
        this.scales = scales;
    }

    @Override
    public void process() {

        this.result = null;

        if (this.isAutoBlockDimensions) {
            this.blockDimensions = ImgUtils.suggestBlockDimensions(this.imgDim);
        }

        final ExecutorService es = Executors.newFixedThreadPool(this.nThreads);

        try (final DefaultTaskExecutor ex = new DefaultTaskExecutor(es)) {
            // TODO: maybe use one of the disk-cached imglib2 data structures instead?
            final Img<FloatType> output = ArrayImgs.floats(Intervals.dimensionsAsLongArray(this.img));
            final List<IntervalView<FloatType>> blocks = ImgUtils.splitIntoBlocks(output, this.blockDimensions);
            if (blocks.size() == 0) {
                throw new IllegalStateException("Num blocks == 0");
            }

            final long[] gaussianPad = new long[this.imgDim.length];
            final long[] gaussianOffset = new long[this.imgDim.length];

            final long[] gradientPad = new long[this.imgDim.length + 1];
            gradientPad[gradientPad.length - 1] = this.is3D ? 3 : 2;
            final long[] gradientOffset = new long[this.imgDim.length + 1];

            final long[] hessianPad = new long[this.imgDim.length + 1];
            hessianPad[hessianPad.length - 1] = this.is3D ? 6 : 3;
            final long[] hessianOffset = new long[this.imgDim.length + 1];

            final List<double[]> sigmas = new ArrayList<>();
            for (final double sc : this.scales) {
                final double[] sigma;
                if (this.is3D) {
                    sigma = new double[]{sc / this.pixelWidth, sc / this.pixelHeight, sc / this.pixelDepth};
                } else {
                    sigma = new double[]{sc / this.pixelWidth, sc / this.pixelHeight};
                }
                sigmas.add(sigma);
            }

            int iter = 0;
            final int nIterations = sigmas.size() * blocks.size();
            for (final double[] sigma : sigmas) {

                // Since we might not be able to compute this over the entire hessian image at once,
                // keep track of the max over all visited blocks and use that for the current block.
                // This seems to work well enough, judging by the looks of the output.
                // At the very least, it is a lot better than using the max of each block...
                double maxSquaredFrobienusNorm = -Double.MAX_VALUE;

                for (final IntervalView<FloatType> block : blocks) {

                    final long[] blockSize = Intervals.dimensionsAsLongArray(block);

                    for (int d = 0; d < blockSize.length; ++d) {
                        gaussianPad[d] = blockSize[d] + 6;
                        gaussianOffset[d] = block.min(d) - 2;
                    }
                    RandomAccessibleInterval<FloatType> tmpGaussian = Views.translate(
                            ArrayImgs.floats(gaussianPad), gaussianOffset);

                    FastGauss.convolve(sigma, Views.extendMirrorSingle(this.img), tmpGaussian);

                    for (int d = 0; d < gradientPad.length - 1; ++d) {
                        gradientPad[d] = blockSize[d] + 4;
                        gradientOffset[d] = block.min(d) - 1;
                        hessianPad[d] = blockSize[d];
                        hessianOffset[d] = block.min(d);
                    }
                    RandomAccessibleInterval<FloatType> tmpGradient = ArrayImgs.floats(gradientPad);
                    RandomAccessibleInterval<FloatType> tmpHessian = ArrayImgs.floats(hessianPad);

                    HessianMatrix.calculateMatrix(
                            Views.extendBorder(tmpGaussian),
                            Views.translate(tmpGradient, gradientOffset),
                            Views.translate(tmpHessian, hessianOffset),
                            new OutOfBoundsBorderFactory<>(),
                            this.nThreads,
                            es);

                    // FIXME: this normalizes the filter response using the average voxel separation at a scale
                    double avgSigma = 0d;
                    for (double s : sigma) {
                        avgSigma += s;
                    }
                    avgSigma /= sigma.length;
                    final double norm = avgSigma * avgSigma;
                    LoopBuilder.setImages(tmpHessian).multiThreaded(ex).forEachPixel((h) -> h.mul(norm));

                    maxSquaredFrobienusNorm = Math.max(maxSquaredFrobenius(tmpHessian, ex), maxSquaredFrobienusNorm);
                    final double c = Math.sqrt(maxSquaredFrobienusNorm) * 0.5;

                    RandomAccessibleInterval<FloatType> tmpEigenvalues =
                            TensorEigenValues.createAppropriateResultImg(
                                    tmpHessian,
                                    new ArrayImgFactory<>(new FloatType()));
                    TensorEigenValues.calculateEigenValuesSymmetric(tmpHessian, tmpEigenvalues, this.nThreads, es);

                    RandomAccessibleInterval<FloatType> tmpFrangi = ArrayImgs.floats(blockSize);
                    if (this.is3D) {
                        frangi3D(tmpEigenvalues, tmpFrangi, 0.5, 0.5, c, ex);
                    } else {
                        frangi2D(tmpEigenvalues, tmpFrangi, 0.5, c, ex);
                    }
                    LoopBuilder.setImages(block, tmpFrangi)
                            .multiThreaded(ex)
                            .forEachPixel((b, t) -> b.set(Math.max(b.getRealFloat(), t.getRealFloat())));

                    ++iter;
                }
            }
            this.result = output;

        } catch (final OutOfMemoryError e) {
            SNTUtils.error("Out of memory when computing Frangi. Requires around " +
                    (ImgUtils.estimateMemoryRequirement(this.imgDim, this.blockDimensions) / (1024 * 1024)) +
                    "MiB for block size " + Arrays.toString(this.blockDimensions));

        } catch (final ExecutionException e) {
            SNTUtils.error(e.getMessage(), e);

        } catch (final InterruptedException e) {
            SNTUtils.error("Frangi interrupted.", e);
            Thread.currentThread().interrupt();
        }

    }

    protected void frangi2D(final RandomAccessibleInterval<FloatType> eigenvalueRai,
                            final RandomAccessibleInterval<FloatType> frangiRai,
                            final double beta, final double c, final TaskExecutor ex)
    {
        final double betaDen = 2 * beta * beta;
        final double cDen = 2 * c * c;

        final int d = eigenvalueRai.numDimensions() - 1;
        final IntervalView<FloatType> evs0 = Views.hyperSlice(eigenvalueRai, d, 0);
        final IntervalView<FloatType> evs1 = Views.hyperSlice(eigenvalueRai, d, 1);

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
                    v.set((float) result);
                }
        );
    }

    protected void frangi3D(final RandomAccessibleInterval<FloatType> eigenvalueRai,
                            final RandomAccessibleInterval<FloatType> frangiRai,
                            final double alpha, final double beta, final double c, final TaskExecutor ex)
    {
        final double alphaDen = 2 * alpha * alpha;
        final double betaDen = 2 * beta * beta;
        final double cDen = 2 * c * c;

        final int d = eigenvalueRai.numDimensions() - 1;
        final IntervalView<FloatType> evs0 = Views.hyperSlice(eigenvalueRai, d, 0);
        final IntervalView<FloatType> evs1 = Views.hyperSlice(eigenvalueRai, d, 1);
        final IntervalView<FloatType> evs2 = Views.hyperSlice(eigenvalueRai, d, 2);

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
                    v.set((float) result);
                }
        );
    }

    protected double maxSquaredFrobenius(final RandomAccessibleInterval<FloatType> hessianRai, final TaskExecutor ex)
    {
        final long[] dims = new long[hessianRai.numDimensions() - 1];
        for (int d = 0; d < hessianRai.numDimensions() - 1; ++d) {
            dims[d] = hessianRai.dimension(d);
        }
        final RandomAccessibleInterval<FloatType> sum = ArrayImgs.floats(dims);
        for (int d = 0; d < hessianRai.dimension(hessianRai.numDimensions() - 1); ++d) {
            final IntervalView<FloatType> hessian = Views.hyperSlice(
                    hessianRai, hessianRai.numDimensions() - 1, d);
            LoopBuilder.setImages(sum, hessian).multiThreaded(ex).forEachPixel(
                    (s, h) -> {
                        final float hf = h.getRealFloat();
                        s.set(s.getRealFloat() + hf * hf);
                    }
            );
        }
        final ComputeMinMax<FloatType> minMax = new ComputeMinMax<>(
                Views.iterable(sum), new FloatType(), new FloatType());
        minMax.setNumThreads(this.nThreads);
        minMax.process();
        return minMax.getMax().getRealDouble();
    }

}
