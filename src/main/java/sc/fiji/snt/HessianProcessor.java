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

package sc.fiji.snt;

import ij.IJ;
import ij.ImagePlus;
import ij.measure.Calibration;
import net.imagej.ImageJ;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.convolution.fast_gauss.FastGauss;
import net.imglib2.algorithm.gradient.HessianMatrix;
import net.imglib2.algorithm.linalg.eigen.TensorEigenValues;
import net.imglib2.algorithm.stats.ComputeMinMax;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.loops.LoopBuilder;
import net.imglib2.outofbounds.OutOfBoundsBorderFactory;
import net.imglib2.parallel.DefaultTaskExecutor;
import net.imglib2.parallel.TaskExecutor;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.RealSum;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Methods for computation of curvatures in 2D and 3D images.
 * TODO
 *
 * @author Cameron Arshadi
 */
public class HessianProcessor {

    static class TrivialProgressBar implements HessianGenerationCallback {
        public void proportionDone(double proportion) {
            if (proportion < 0)
                IJ.showProgress(1.0);
            else
                IJ.showProgress(proportion);
        }
    }

    final RandomAccessibleInterval<? extends RealType<?>> img;
    final long[] imgDim;
    final boolean is3D;
    final double pixelWidth;
    final double pixelHeight;
    final double pixelDepth;

    Img<FloatType> tubenessImg;
    RandomAccess<FloatType> tubenessAccess;
    ImgStats tubenessStats;

    Img<FloatType> frangiImg;
    RandomAccess<FloatType> frangiAccess;
    ImgStats frangiStats;

    private HessianGenerationCallback callback;

    private int nThreads;
    private long[] blockDimensions;
    private boolean isAutoBlockDimensions = true;

    // TODO: implement logic if user wants to override suggested block dimensions

    public HessianProcessor(final ImagePlus imp, final HessianGenerationCallback callback) {
        if (imp == null) {
            throw new IllegalArgumentException("BUG: ImagePlus cannot be null");
        }
        this.img = ImageJFunctions.wrapReal(imp);
        SNTUtils.log("Wrapped ImagePlus with type: " + this.img.getClass());
        this.imgDim = Intervals.dimensionsAsLongArray(this.img);
        if (this.imgDim.length < 2 || this.imgDim.length > 3) {
            throw new UnsupportedOperationException("Only 2 or 3 dimensional images are supported");
        }
        this.is3D = imp.getStackSize() > 1;
        final Calibration cal = imp.getCalibration();
        if (cal != null) {
            this.pixelWidth = cal.pixelWidth;
            this.pixelHeight = cal.pixelHeight;
            this.pixelDepth = cal.pixelDepth;
        } else {
            this.pixelWidth = 1.0d;
            this.pixelHeight = 1.0d;
            this.pixelDepth = 1.0d;
        }
        if (callback == null) {
            this.callback = new TrivialProgressBar();
        } else {
            this.callback = callback;
        }
        this.nThreads = SNTPrefs.getThreads();
    }

    public HessianProcessor(final RandomAccessibleInterval<? extends RealType<?>> img, final Calibration calibration,
                            final HessianGenerationCallback callback)
    {
        if (img == null) {
            throw new IllegalArgumentException("BUG: Image cannot be null");
        }
        this.img = img;
        this.imgDim = Intervals.dimensionsAsLongArray(img);
        this.is3D = this.imgDim.length == 3;
        this.pixelWidth = calibration.pixelWidth;
        this.pixelHeight = calibration.pixelHeight;
        this.pixelDepth = calibration.pixelDepth;
        if (callback == null) {
            this.callback = new TrivialProgressBar();
        } else {
            this.callback = callback;
        }
        this.nThreads = SNTPrefs.getThreads();
    }

    public void setProgressListener(final HessianGenerationCallback callback) {
        this.callback = callback;
    }

    public void setNumThreads(final int nThreads) {
        this.nThreads = nThreads;
    }

    public void setBlockDimensions(final long[] dimensions) {
        this.blockDimensions = dimensions;
    }

    public void setAutoBlockDimensions(boolean useAutoBlockSize) {
        this.isAutoBlockDimensions = useAutoBlockSize;
    }

    public boolean isAutoBlockDimensions() {
        return this.isAutoBlockDimensions;
    }

    public long[] getBlockDimensions() {
        return this.blockDimensions;
    }

    public void processTubeness(final double[] sigmas, final boolean computeStats) {
        SNTUtils.log("Computing Tubeness at sigma: " + Arrays.toString(sigmas));
        this.callback.proportionDone(0);
        this.tubenessImg = (Img<FloatType>) tubenessInternal(sigmas);
        if (this.tubenessImg == null) {
            return;
        }
        this.tubenessAccess = this.tubenessImg.randomAccess();
        SNTUtils.log("Done computing Tubeness.");
        if (computeStats) {
            SNTUtils.log("Computing Tubeness stats");
            this.tubenessStats = new ImgStats(Views.iterable(this.tubenessImg));
            this.tubenessStats.process();
            SNTUtils.log("Tubeness stats: " + this.tubenessStats);
        }
        this.callback.proportionDone(1.0);
    }

    public void processFrangi(final double[] sigmas, final boolean computeStats) {
        SNTUtils.log("Computing Frangi at scales: " + Arrays.toString(sigmas));
        this.callback.proportionDone(0);
        this.frangiImg = (Img<FloatType>) frangiInternal(sigmas);
        if (this.frangiImg == null) {
            return;
        }
        this.frangiAccess = this.frangiImg.randomAccess();
        SNTUtils.log("Done computing Frangi.");
        if (computeStats) {
            SNTUtils.log("Computing Frangi stats");
            this.frangiStats = new ImgStats(Views.iterable(this.frangiImg));
            this.frangiStats.process();
            SNTUtils.log("Frangi stats: " + this.frangiStats);
        }
        this.callback.proportionDone(1.0);
    }

    public ImgStats getTubenessStats() {
        return this.tubenessStats;
    }

    public ImgStats getFrangiStats() {
        return this.frangiStats;
    }

    public Img<FloatType> getTubenessImg() {
        return this.tubenessImg;
    }

    public Img<FloatType> getFrangiImg() {
        return this.frangiImg;
    }

    public void showTubeness() {
        if (this.tubenessImg == null) {
            throw new IllegalArgumentException("Tubeness Img is null. You must first call processTubeness()");
        }
        ImageJFunctions.show(this.tubenessImg, "Tubeness");
    }

    public void showFrangi() {
        if (this.frangiImg == null) {
            throw new IllegalArgumentException("Frangi Img is null. You must first call processFrangi()");
        }
        ImageJFunctions.show(this.frangiImg, "Frangi Vesselness");
    }

    protected void tubeness2D(final RandomAccessibleInterval<FloatType> eigenvalueRai,
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
                    v.set((float) result);
                }
        );
    }

    protected void tubeness3D(final RandomAccessibleInterval<FloatType> eigenvalueRai,
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
                    v.set((float) result);
                }
        );
    }

    protected double maxSquaredFrobenius(final RandomAccessibleInterval<FloatType> hessianRai, final TaskExecutor ex) {
        final long[] dims = new long[hessianRai.numDimensions() - 1];
        for (int d = 0; d < hessianRai.numDimensions() - 1; ++d) {
            dims[d] = hessianRai.dimension(d);
        }
        final RandomAccessibleInterval<FloatType> sum = ArrayImgs.floats(dims);
        for (int d = 0; d < hessianRai.dimension(hessianRai.numDimensions() - 1); ++d) {
            final IntervalView<FloatType> hessian = Views.hyperSlice(hessianRai, hessianRai.numDimensions() - 1, d);
            LoopBuilder.setImages(sum, hessian).multiThreaded(ex).forEachPixel(
                    (s, h) -> {
                        float sf = s.getRealFloat();
                        float hf = h.getRealFloat();
                        s.set(sf + hf * hf);
                    }
            );
        }
        final ComputeMinMax<FloatType> minMax = new ComputeMinMax<>(
                Views.iterable(sum), new FloatType(), new FloatType());
        minMax.setNumThreads(this.nThreads);
        minMax.process();
        return minMax.getMax().getRealDouble();
    }

    protected void frangi2D(final RandomAccessibleInterval<FloatType> eigenvalueRai,
                            final RandomAccessibleInterval<FloatType> frangiRai, final double beta, final double c,
                            final TaskExecutor ex)
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
                        result = (1 - Math.exp(-rasq / alphaDen)) * Math.exp(-rbsq / betaDen) * (1 - Math.exp(-ssq / cDen));
                        if (Double.isNaN(result)) {
                            result = 0;
                        }
                    }
                    v.set((float) result);
                }
        );
    }

    protected RandomAccessibleInterval<FloatType> tubenessInternal(final double[] scales) {

        if (this.isAutoBlockDimensions) {
            this.blockDimensions = suggestBlockDimensions(this.imgDim);
        }

        final ExecutorService es = Executors.newFixedThreadPool(this.nThreads);
        try (TaskExecutor ex = new DefaultTaskExecutor(es)) {
            // TODO: maybe use one of the cached imglib2 data structures instead?
            RandomAccessibleInterval<FloatType> output = ArrayImgs.floats(this.imgDim);
            final List<IntervalView<FloatType>> blocks = splitIntoBlocks(output, this.blockDimensions);
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
            for (final double sc : scales) {
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

                    RandomAccessibleInterval<FloatType> tmpEigenvalues =
                            TensorEigenValues.createAppropriateResultImg(
                                    tmpHessian,
                                    new ArrayImgFactory<>(new FloatType()));
                    TensorEigenValues.calculateEigenValuesSymmetric(tmpHessian, tmpEigenvalues, this.nThreads, es);

                    // FIXME: this normalizes the filter response using the average voxel separation at a scale
                    double avgSigma = 0d;
                    for (double s : sigma) {
                        avgSigma += s;
                    }
                    avgSigma /= sigma.length;

                    RandomAccessibleInterval<FloatType> tmpTubeness = ArrayImgs.floats(blockSize);
                    if (this.is3D) {
                        tubeness3D(tmpEigenvalues, tmpTubeness, avgSigma, ex);
                    } else {
                        tubeness2D(tmpEigenvalues, tmpTubeness, avgSigma, ex);
                    }
                    LoopBuilder.setImages(block, tmpTubeness)
                            .multiThreaded(ex)
                            .forEachPixel((b, t) -> b.set(Math.max(b.getRealFloat(), t.getRealFloat())));

                    this.callback.proportionDone(iter / (double) nIterations);
                    ++iter;
                }
            }
            return output;

        } catch (final OutOfMemoryError e) {
            IJ.error("Out of memory when computing Tubeness. Requires around " +
                    (estimateMemoryRequirement(this.imgDim, this.blockDimensions) / (1024 * 1024)) + "MiB for block size " +
                    Arrays.toString(this.blockDimensions));
            this.callback.proportionDone(-1);

        } catch (final ExecutionException e) {
            SNTUtils.error(e.getMessage(), e);
            this.callback.proportionDone(-1);

        } catch (final InterruptedException e) {
            SNTUtils.error("Tubeness interrupted.", e);
            this.callback.proportionDone(-1);
            Thread.currentThread().interrupt();
        }
        return null;
    }

    protected RandomAccessibleInterval<FloatType> frangiInternal(final double[] scales) {

        if (this.isAutoBlockDimensions) {
            this.blockDimensions = suggestBlockDimensions(this.imgDim);
        }

        final ExecutorService es = Executors.newFixedThreadPool(this.nThreads);

        try (final DefaultTaskExecutor ex = new DefaultTaskExecutor(es)) {
            // TODO: maybe use one of the disk cached imglib2 data structures instead?
            final Img<FloatType> output = ArrayImgs.floats(Intervals.dimensionsAsLongArray(this.img));
            final List<IntervalView<FloatType>> blocks = splitIntoBlocks(output, this.blockDimensions);
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
            for (final double sc : scales) {
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

                    this.callback.proportionDone(iter / (double) nIterations);
                    ++iter;
                }
            }
            return output;

        } catch (final OutOfMemoryError e) {
            IJ.error("Out of memory when computing Frangi. Requires around " +
                    (estimateMemoryRequirement(this.imgDim, this.blockDimensions) / (1024 * 1024)) + "MiB for block size " +
                    Arrays.toString(this.blockDimensions));
            this.callback.proportionDone(-1);

        } catch (final ExecutionException e) {
            SNTUtils.error(e.getMessage(), e);
            this.callback.proportionDone(-1);

        } catch (final InterruptedException e) {
            SNTUtils.error("Frangi interrupted.", e);
            this.callback.proportionDone(-1);
            Thread.currentThread().interrupt();
        }
        return null;
    }

    protected static <T extends RealType<T>> List<IntervalView<T>> splitIntoBlocks(final RandomAccessibleInterval<T> source,
                                                                                   final long[] blockDimensions) {
        final List<IntervalView<T>> views = new ArrayList<>();
        for (final FinalInterval interval : createBlocks(Intervals.dimensionsAsLongArray(source), blockDimensions)) {
            views.add(Views.interval(source, interval));
        }
        return views;
    }

    private static List<FinalInterval> createBlocks(final long[] sourceDimensions, final long[] blockDimensions) {
        final List<FinalInterval> intervals = new ArrayList<>();
        final long[] min = new long[sourceDimensions.length];
        final long[] max = new long[sourceDimensions.length];
        createBlocksRecursionLoop(intervals, sourceDimensions, blockDimensions, min, max, 0);
        return intervals;
    }

    private static void createBlocksRecursionLoop(final List<FinalInterval> intervals, final long[] sourceDimensions,
                                                  final long[] blockDimensions, final long[] min, final long[] max,
                                                  final int d) {
        if (d == min.length) {
            for (int m = 0; m < min.length; ++m) {
                max[m] = Math.min(min[m] + blockDimensions[m] - 1, sourceDimensions[m] - 1);
            }
            intervals.add(new FinalInterval(min, max));
        } else {
            for (min[d] = 0; min[d] < sourceDimensions[d]; min[d] += blockDimensions[d]) {
                createBlocksRecursionLoop(intervals, sourceDimensions, blockDimensions, min, max, d + 1);
            }
        }
    }

    protected static long[] suggestBlockDimensions(final long[] sourceDimensions) {
        // "Optimal" might be a bold statement here...
        //final long slack = 209715200L; // 200 MiB
        final long slack = 0L;
        // dimensionLowerBound has to be > 1
        // Ideally, it should be at minimum twice the diameter of the largest tubular process, in pixels
        final long dimensionLowerBound = 8L;
        final long[] blockDimensions = Arrays.copyOf(sourceDimensions, sourceDimensions.length);
        // FIXME: something more elegant
        System.gc();
        final long usedMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        final long totalFreeMemory = Runtime.getRuntime().maxMemory() - usedMemory;
        long memoryEstimate = estimateMemoryRequirement(sourceDimensions, blockDimensions);
        while (totalFreeMemory - memoryEstimate < slack) {
            final int d = maxDimension(blockDimensions);
            blockDimensions[d] = (long) Math.ceil(blockDimensions[d] / 2.0);
            if (blockDimensions[d] < dimensionLowerBound) {
                throw new UnsupportedOperationException("Insufficient memory");
            }
            memoryEstimate = estimateMemoryRequirement(sourceDimensions, blockDimensions);
        }
        SNTUtils.log("Computed block dimensions: " + Arrays.toString(blockDimensions));
        SNTUtils.log("Estimated memory usage (MiB): " + (memoryEstimate / (1024 * 1024)));
        return blockDimensions;
    }

    private static int maxDimension(final long[] dimensions) {
        long dimensionMax = Long.MIN_VALUE;
        int dimensionArgMax = -1;
        for (int d = 0; d < dimensions.length; ++d) {
            final long size = dimensions[d];
            if (size > dimensionMax) {
                dimensionMax = size;
                dimensionArgMax = d;
            }
        }
        return dimensionArgMax;
    }

    protected static long estimateMemoryRequirement(final long[] sourceDimensions, final long[] blockDimensions) {
        // FIXME: This certainly isn't ideal, though I have yet to run out of memory on a 64-bit jre...
        long outputBytes = 1L, gaussBytes = 1L, gradientBytes = 1L, hessianBytes = 1L;
        for (int d = 0; d < sourceDimensions.length; ++d) {
            outputBytes *= sourceDimensions[d];
            gaussBytes *= (blockDimensions[d] + 6);
            gradientBytes *= (blockDimensions[d] + 4);
            hessianBytes *= blockDimensions[d];
        }
        outputBytes *= 16;
        gaussBytes *= 16;
        gradientBytes *= 16;
        hessianBytes *= 16;
        if (sourceDimensions.length == 3) {
            gradientBytes *= 3;
            hessianBytes *= 6;
        } else if (sourceDimensions.length == 2) {
            gradientBytes *= 2;
            hessianBytes *= 3;
        } else {
            throw new UnsupportedOperationException("Only 2 and 3 dimensional images are supported.");
        }
        return outputBytes + gaussBytes + gradientBytes + hessianBytes;
    }

    public static class ImgStats {

        final Iterable<? extends RealType<?>> input;
        public double min;
        public double max;
        public double mean;
        public double stdDev;

        public ImgStats(final Iterable<? extends RealType<?>> input) {
            this.input = input;
        }

        public double getMin() {
            return this.min;
        }

        public double getMax() {
            return this.max;
        }

        public double getMean() {
            return this.mean;
        }

        public double getStdDev() {
            return this.stdDev;
        }

        public void process() {
            final RealSum realSum = new RealSum();
            long count = 0;
            this.min = Double.MAX_VALUE;
            this.max = -Double.MAX_VALUE;
            for (final RealType<?> type : this.input) {
                final double d = type.getRealDouble();
                this.min = Math.min(this.min, d);
                this.max = Math.max(this.max, d);
                realSum.add(d);
                ++count;
            }
            this.mean = realSum.getSum() / count;
            final RealSum sumsq = new RealSum();
            for (final RealType<?> type : this.input) {
                sumsq.add(Math.pow(type.getRealDouble() - this.mean, 2));
            }
            this.stdDev = Math.sqrt(sumsq.getSum() / count);
        }

        @Override
        public String toString() {
            return "min=" + this.min + ", max=" + this.max + ", mean=" + this.mean + ", stdDev=" + this.stdDev;
        }

    }

    public static void main(final String[] args) {
        ImageJ ij = new ImageJ();
        ij.ui().showUI();
        SNTService snt = ij.context().getService(SNTService.class);
        SNTUtils.setDebugMode(true);
        ImagePlus imp = snt.demoImage("OP_1");
        final HessianProcessor hessian = new HessianProcessor(imp, null);
        hessian.processTubeness(new double[]{0.5, 0.7, 0.9, 1.1}, true);
        hessian.showTubeness();
        hessian.processFrangi(new double[]{0.5, 0.7, 0.9, 1.1}, true);
        hessian.showFrangi();
    }

}