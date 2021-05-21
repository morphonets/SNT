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

    static class TrivialProgressDisplayer implements HessianGenerationCallback {
        public void proportionDone(double proportion) {
            if (proportion < 0)
                IJ.showProgress(1.0);
            else
                IJ.showProgress(proportion);
        }
    }

    final Img<? extends RealType<?>> img;
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

    private final HessianGenerationCallback callback;

    private int nThreads;
    private int nBlocks;

    public HessianProcessor(final ImagePlus imp, final HessianGenerationCallback callback) {
        if (imp == null) {
            throw new IllegalArgumentException("BUG: ImagePlus cannot be null");
        }
        this.img = ImageJFunctions.wrapReal(imp);
        SNTUtils.log("Wrapped ImagePlus with type: " + this.img.getClass());
        this.is3D = imp.getStackSize() > 1;
        this.imgDim = Intervals.dimensionsAsLongArray(this.img);
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
            this.callback = new TrivialProgressDisplayer();
        } else {
            this.callback = callback;
        }
        this.nThreads = SNTPrefs.getThreads();
    }

    public void setNumBlocks(final int nBlocks) {
        this.nBlocks = nBlocks;
    }

    public void setNumThreads(final int nThreads) {
        this.nThreads = nThreads;
    }

    public void processTubeness(final double sigma, final boolean computeStats) {
        SNTUtils.log("Computing Tubeness at sigma: " + sigma);
        this.callback.proportionDone(0);
        if (this.is3D) {
            processTubeness3D(sigma);
        } else {
            processTubeness2D(sigma);
        }
        SNTUtils.log("Done computing Tubeness.");
        if (computeStats) {
            SNTUtils.log("Computing Tubeness stats");
            this.tubenessStats = new ImgStats(Views.iterable(this.tubenessImg));
            this.tubenessStats.process();
            SNTUtils.log("Tubeness stats: " + this.tubenessStats);
        }
        this.callback.proportionDone(1.0);
    }

    public void processFrangi(final double[] scales, final boolean computeStats) {
        SNTUtils.log("Computing Frangi at scales: " + Arrays.toString(scales));
        this.callback.proportionDone(0);
        if (this.is3D) {
            processFrangi3D(scales);
        } else {
            processFrangi2D(scales);
        }
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

    protected RandomAccessibleInterval<FloatType> tubeness2D(final RandomAccessibleInterval<FloatType> eigenvalueRai,
                                                             final RandomAccessibleInterval<FloatType> tubenessRai,
                                                             final TaskExecutor ex)
    {
        final int d = eigenvalueRai.numDimensions() - 1;
        final IntervalView<FloatType> evs0 = Views.hyperSlice(eigenvalueRai, d, 0);
        final IntervalView<FloatType> evs1 = Views.hyperSlice(eigenvalueRai, d, 1);
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
                        result = Math.abs(e1);
                    }
                    v.set((float) result);
                }
        );
        return tubenessRai;
    }

    protected void tubeness3D(final RandomAccessibleInterval<FloatType> eigenvalueRai,
                              final RandomAccessibleInterval<FloatType> tubenessRai,
                              final TaskExecutor ex)
    {
        final int d = eigenvalueRai.numDimensions() - 1;
        final IntervalView<FloatType> evs0 = Views.hyperSlice(eigenvalueRai, d, 0);
        final IntervalView<FloatType> evs1 = Views.hyperSlice(eigenvalueRai, d, 1);
        final IntervalView<FloatType> evs2 = Views.hyperSlice(eigenvalueRai, d, 2);
        LoopBuilder.setImages(evs0, evs1, evs2, tubenessRai).multiThreaded(ex).forEachPixel(
                (ev0, ev1, ev2, v) -> {
                    double e0 = ev0.getRealDouble();
                    double e1 = ev1.getRealDouble();
                    double e2 = ev2.getRealDouble();
                    // Sort by absolute value
                    if (Math.abs(e0) > Math.abs(e1)) {
                        double temp = e0;
                        e0 = e1;
                        e1 = temp;
                    }
                    if (Math.abs(e1) > Math.abs(e2)) {
                        double temp = e1;
                        e1 = e2;
                        e2 = temp;
                    }
                    if (Math.abs(e0) > Math.abs(e1)) {
                        // skip assignment to e0, not used in filter
                        e1 = e0;
                    }
                    double result = 0;
                    if (e1 < 0 && e2 < 0) {
                        result = Math.sqrt(e1 * e2);
                    }
                    v.set((float) result);
                }
        );
    }

    protected float maxSquaredFrobenius(final RandomAccessibleInterval<FloatType> hessianRai, final TaskExecutor ex)
    {
        final long[] dims = new long[hessianRai.numDimensions() - 1];
        for (int d = 0; d < hessianRai.numDimensions() - 1; ++d) {
            dims[d] = hessianRai.dimension(d);
        }
        final RandomAccessibleInterval<FloatType> sum = ArrayImgs.floats(dims);
        for (int d = 0; d < hessianRai.dimension(hessianRai.numDimensions() - 1); ++d) {
            final IntervalView<FloatType> hessian = Views.hyperSlice(hessianRai, hessianRai.numDimensions() - 1, d);
            LoopBuilder.setImages(sum, hessian).multiThreaded(ex).forEachPixel(
                    (s, h) -> s.set(s.getRealFloat() + h.getRealFloat() * h.getRealFloat())
            );
        }
        final ComputeMinMax<FloatType> minMax = new ComputeMinMax<>(
                Views.iterable(sum), new FloatType(), new FloatType());
        minMax.setNumThreads(this.nThreads);
        minMax.process();
        return minMax.getMax().getRealFloat();
    }

    protected RandomAccessibleInterval<FloatType> frangi2D(final RandomAccessibleInterval<FloatType> eigenvalueRai,
                                                           final RandomAccessibleInterval<FloatType> frangiRai,
                                                           final double beta, final double c,
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
        return frangiRai;
    }

    protected void frangi3D(final RandomAccessibleInterval<FloatType> eigenvalueRai,
                            final RandomAccessibleInterval<FloatType> frangiRai,
                            final double alpha, final double beta, final double c,
                            final TaskExecutor ex)
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
                    if (Math.abs(e0) > Math.abs(e1)) {
                        double temp = e0;
                        e0 = e1;
                        e1 = temp;
                    }
                    if (Math.abs(e1) > Math.abs(e2)) {
                        double temp = e1;
                        e1 = e2;
                        e2 = temp;
                    }
                    if (Math.abs(e0) > Math.abs(e1)) {
                        double temp = e0;
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

    protected void processTubeness3D(final double sigma) {

        final double[] sigmaArr = new double[]{
                sigma / this.pixelWidth,
                sigma / this.pixelHeight,
                sigma / this.pixelDepth };

        final ExecutorService es = Executors.newFixedThreadPool(this.nThreads);
        final DefaultTaskExecutor ex = new DefaultTaskExecutor(es);

        final long[] bestBlockSize = computeOptimalBlockDimensions(this.imgDim);

        try {

            // TODO: maybe use one of the cached imglib2 data structures instead?
            final Img<FloatType> output = ArrayImgs.floats(Intervals.dimensionsAsLongArray(this.img));
            final List<IntervalView<FloatType>> blocks = imgToBlocks3D(output, bestBlockSize);

            if (blocks.size() == 0) {
                throw new IllegalArgumentException("Num blocks == 0");
            }

            int iter = 0;
            for (final IntervalView<FloatType> block : blocks) {

                final long[] blockSize = Intervals.dimensionsAsLongArray(block);

                RandomAccessibleInterval<FloatType> tmpGaussian = Views.translate(
                        ArrayImgs.floats(blockSize[0] + 4, blockSize[1] + 4, blockSize[2] + 4),
                        block.min(0) - 2, block.min(1) - 2, block.min(2) - 2);

                FastGauss.convolve(sigmaArr, Views.extendMirrorSingle(this.img), tmpGaussian);

                RandomAccessibleInterval<FloatType> tmpGradient = ArrayImgs.floats(
                        blockSize[0] + 2,
                        blockSize[1] + 2,
                        blockSize[2] + 2,
                        3);

                RandomAccessibleInterval<FloatType> tmpHessian = ArrayImgs.floats(
                        blockSize[0],
                        blockSize[1],
                        blockSize[2],
                        6);

                HessianMatrix.calculateMatrix(
                        Views.extendBorder(tmpGaussian),
                        Views.translate(tmpGradient, block.min(0) - 1, block.min(1) - 1, block.min(2) - 1, 0),
                        Views.translate(tmpHessian, block.min(0), block.min(1), block.min(2), 0),
                        new OutOfBoundsBorderFactory<>(),
                        this.nThreads,
                        es);

                tmpGaussian = null;
                tmpGradient = null;

                final RandomAccessibleInterval<FloatType> tmpEigenvalues = ArrayImgs.floats(
                        blockSize[0],
                        blockSize[1],
                        blockSize[2],
                        3);

                TensorEigenValues.calculateEigenValuesSymmetric(tmpHessian, tmpEigenvalues, this.nThreads, es);

                tmpHessian = null;

                tubeness3D(tmpEigenvalues, block, ex);

                this.callback.proportionDone(++iter / (double)blocks.size());

            }

            this.tubenessImg = output;
            this.tubenessAccess = this.tubenessImg.randomAccess();

        } catch (final OutOfMemoryError e) {
            IJ.error("Out of memory when computing Tubeness. Rrequires around " +
                    (estimateBlockMemory(this.imgDim, bestBlockSize) / (1024 * 1024)) + "MiB for block size " +
                    Arrays.toString(bestBlockSize) + ". Try increasing num blocks.");
            this.callback.proportionDone(-1);

        } catch (final ExecutionException e) {
            SNTUtils.error(e.getMessage(), e);
            this.callback.proportionDone(-1);

        } catch (final InterruptedException e) {
            SNTUtils.error("Tubeness interrupted.", e);
            this.callback.proportionDone(-1);
            Thread.currentThread().interrupt();

        } finally {
            ex.close();
        }

    }

    protected void processTubeness2D(final double sigma) {

        final ExecutorService es = Executors.newFixedThreadPool(this.nThreads);
        final TaskExecutor ex = new DefaultTaskExecutor(es);

        final double[] sigmaArr = new double[]{sigma / this.pixelWidth, sigma / this.pixelHeight};

        // TODO: maybe use one of the cached imglib2 data structures instead?
        RandomAccessibleInterval<FloatType> result = ArrayImgs.floats(this.imgDim);

        FastGauss.convolve(sigmaArr, Views.extendBorder(this.img), result);

        try {
            result = HessianMatrix.calculateMatrix(
                    result,
                    ArrayImgs.floats(this.imgDim[0], this.imgDim[1], this.imgDim[2], 2),
                    ArrayImgs.floats(this.imgDim[0], this.imgDim[1], this.imgDim[2], 3),
                    new OutOfBoundsBorderFactory<>(),
                    this.nThreads,
                    es);

        } catch (final ExecutionException e) {
            SNTUtils.error(e.getMessage(), e);
            ex.close();
            this.callback.proportionDone(-1);
            return;

        } catch (final InterruptedException e) {
            SNTUtils.error("Tubeness interrupted.", e);
            ex.close();
            this.callback.proportionDone(-1);
            Thread.currentThread().interrupt();
            return;
        }

        result = TensorEigenValues.calculateEigenValuesSymmetric(
                result,
                TensorEigenValues.createAppropriateResultImg(result, new ArrayImgFactory<>(new FloatType())),
                this.nThreads,
                es);

        result = tubeness2D(result, ArrayImgs.floats(this.imgDim), ex);

        ex.close();

        this.tubenessImg = (Img<FloatType>) result;
        this.tubenessAccess = this.tubenessImg.randomAccess();

    }

    protected void processFrangi3D(final double[] scales) {

        final List<double[]> sigmas = new ArrayList<>();
        for (final double sc : scales) {
            final double[] sigma = new double[]{sc / this.pixelWidth, sc / this.pixelHeight, sc / this.pixelDepth};
            sigmas.add(sigma);
        }

        final ExecutorService es = Executors.newFixedThreadPool(this.nThreads);
        final DefaultTaskExecutor ex = new DefaultTaskExecutor(es);

        final long[] bestBlockSize = computeOptimalBlockDimensions(this.imgDim);

        try {

            // TODO: maybe use one of the cached imglib2 data structures instead?
            final Img<FloatType> output = ArrayImgs.floats(Intervals.dimensionsAsLongArray(this.img));
            final List<IntervalView<FloatType>> blocks = imgToBlocks3D(output, bestBlockSize);

            if (blocks.size() == 0) {
                throw new IllegalArgumentException("Num blocks == 0");
            }

            int iter = 0;
            final int nIterations = sigmas.size() * blocks.size();
            for (final double[] sigma : sigmas) {
                for (final IntervalView<FloatType> block : blocks) {

                    final long[] blockSize = Intervals.dimensionsAsLongArray(block);

                    RandomAccessibleInterval<FloatType> tmpGaussian = Views.translate(
                            ArrayImgs.floats(blockSize[0] + 4, blockSize[1] + 4, blockSize[2] + 4),
                            block.min(0) - 2, block.min(1) - 2, block.min(2) - 2);

                    FastGauss.convolve(sigma, Views.extendMirrorSingle(this.img), tmpGaussian);

                    RandomAccessibleInterval<FloatType> tmpGradient = ArrayImgs.floats(
                            blockSize[0] + 2,
                            blockSize[1] + 2,
                            blockSize[2] + 2,
                            3);

                    RandomAccessibleInterval<FloatType> tmpHessian = ArrayImgs.floats(
                            blockSize[0],
                            blockSize[1],
                            blockSize[2],
                            6);

                    HessianMatrix.calculateMatrix(
                            Views.extendBorder(tmpGaussian),
                            Views.translate(tmpGradient, block.min(0) - 1, block.min(1) - 1, block.min(2) - 1, 0),
                            Views.translate(tmpHessian, block.min(0), block.min(1), block.min(2), 0),
                            new OutOfBoundsBorderFactory<>(),
                            this.nThreads,
                            es);

                    tmpGaussian = null;
                    tmpGradient = null;

                    final double c = Math.sqrt(maxSquaredFrobenius(tmpHessian, ex)) * 0.5;

                    // allocate storage for eigenvalues
                    RandomAccessibleInterval<FloatType> tmpEigenvalues = ArrayImgs.floats(
                            blockSize[0],
                            blockSize[1],
                            blockSize[2],
                            3);

                    TensorEigenValues.calculateEigenValuesSymmetric(tmpHessian, tmpEigenvalues, this.nThreads, es);

                    tmpHessian = null;

                    final RandomAccessibleInterval<FloatType> tmpFrangi = ArrayImgs.floats(
                            blockSize[0],
                            blockSize[1],
                            blockSize[2]);

                    frangi3D(tmpEigenvalues, tmpFrangi, 0.5, 0.5, c, ex);

                    tmpEigenvalues = null;

                    LoopBuilder.setImages(block, tmpFrangi)
                            .multiThreaded(ex)
                            .forEachPixel((f, t) -> f.set(Math.max(f.getRealFloat(), t.getRealFloat())));

                    this.callback.proportionDone(++iter / (double) nIterations);
                }
            }

            this.frangiImg = output;
            this.frangiAccess = this.frangiImg.randomAccess();

        } catch (final OutOfMemoryError e) {
            IJ.error("Out of memory when computing Frangi. Rrequires around " +
                    (estimateBlockMemory(this.imgDim, bestBlockSize) / (1024 * 1024)) + "MiB for block size " +
                    Arrays.toString(bestBlockSize) + ". Try increasing num blocks.");
            this.callback.proportionDone(-1);

        } catch (final ExecutionException e) {
            SNTUtils.error(e.getMessage(), e);
            this.callback.proportionDone(-1);

        } catch (final InterruptedException e) {
            SNTUtils.error("Frangi interrupted.", e);
            this.callback.proportionDone(-1);
            Thread.currentThread().interrupt();

        } finally {
            ex.close();
        }

    }

    protected void processFrangi2D(final double[] scales) {

        final List<double[]> sigmas = new ArrayList<>();
        for (final double sc : scales) {
            final double[] sigma = new double[]{sc / this.pixelWidth, sc / this.pixelHeight};
            sigmas.add(sigma);
        }

        final ExecutorService es = Executors.newFixedThreadPool(this.nThreads);
        final TaskExecutor ex = new DefaultTaskExecutor(es);

        this.frangiImg = ArrayImgs.floats(this.imgDim);
        RandomAccessibleInterval<FloatType> tmpFrangi = ArrayImgs.floats(this.imgDim);

        for (final double[] sigma : sigmas) {
            FastGauss.convolve(sigma, Views.extendBorder(this.img), tmpFrangi);
            try {
                tmpFrangi = HessianMatrix.calculateMatrix(
                        tmpFrangi,
                        ArrayImgs.floats(this.imgDim[0], this.imgDim[1], this.imgDim[2], 2),
                        ArrayImgs.floats(this.imgDim[0], this.imgDim[1], this.imgDim[2], 3),
                        new OutOfBoundsBorderFactory<>(),
                        this.nThreads,
                        es);

            } catch (final ExecutionException e) {
                SNTUtils.error(e.getMessage(), e);
                ex.close();
                this.callback.proportionDone(-1);
                return;

            } catch (final InterruptedException e) {
                SNTUtils.error("Frangi interrupted.", e);
                ex.close();
                this.callback.proportionDone(-1);
                Thread.currentThread().interrupt();
                return;
            }

            final double c = Math.sqrt(maxSquaredFrobenius(tmpFrangi, ex)) * 0.5;

            tmpFrangi = TensorEigenValues.calculateEigenValuesSymmetric(
                    tmpFrangi,
                    TensorEigenValues.createAppropriateResultImg(tmpFrangi,
                            new ArrayImgFactory<>(new FloatType())),
                    this.nThreads,
                    es);

            tmpFrangi = frangi2D(tmpFrangi, ArrayImgs.floats(this.imgDim), 0.5, c, ex);

            LoopBuilder.setImages(this.frangiImg, tmpFrangi).multiThreaded(ex).forEachPixel(
                    (f, t) -> f.set(Math.max(f.getRealFloat(), t.getRealFloat()))
            );
        }
        this.frangiAccess = this.frangiImg.randomAccess();

        ex.close();

    }

    private static <T extends RealType<T>> List<IntervalView<T>> imgToBlocks3D(final RandomAccessibleInterval<T> source,
                                                                               final long[] blockSize)
    {
        //long totalSum = 0L;
        final List<IntervalView<T>> views = new ArrayList<>();
        for (int zMin = 0; zMin < source.dimension(2); zMin += blockSize[2]) {
            for (int yMin = 0; yMin < source.dimension(1); yMin += blockSize[1]) {
                for (int xMin = 0; xMin < source.dimension(0); xMin += blockSize[0]) {
                    final long zMax = Math.min(zMin + blockSize[2] - 1, source.dimension(2) - 1);
                    final long yMax = Math.min(yMin + blockSize[1] - 1, source.dimension(1) - 1);
                    final long xMax = Math.min(xMin + blockSize[0] - 1, source.dimension(0) - 1);
                    final FinalInterval interval = Intervals.createMinMax(xMin, yMin, zMin, xMax, yMax, zMax);
                    //totalSum += (interval.dimension(0) * interval.dimension(1) * interval.dimension(2));
                    final IntervalView<T> current = Views.interval(source, interval);
                    views.add(current);
                }
            }
        }
        //System.out.println(totalSum == (imgDim[0] * imgDim[1] * imgDim[2]));
        return views;
    }

    private static long[] computeOptimalBlockDimensions(final long[] sourceDimensions) {
        // "Optimal" might be a bold statement here...
        final long[] blockDimensions = Arrays.copyOf(sourceDimensions, sourceDimensions.length);
        final long freeBytes = Runtime.getRuntime().freeMemory();
        while (freeBytes < estimateBlockMemory(sourceDimensions, blockDimensions)) {
            final int d = maxDimension(blockDimensions);
            blockDimensions[d] = blockDimensions[d] / 2;
            if (blockDimensions[d] <= 1) {
                throw new UnsupportedOperationException("Insufficient memory");
            }
        }
        SNTUtils.log("Computed block dimensions: " + Arrays.toString(blockDimensions));
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

    private static long estimateBlockMemory(final long[] sourceDims, final long[] blockDims) {
        // From my tests this seems somewhat reasonable as a lower bound
        final long outBytes = sourceDims[0] * sourceDims[1] * sourceDims[2] * 16;
        final long gaussBytes = (blockDims[0] + 4) * (blockDims[1] + 4) * (blockDims[2] + 4) * 16;
        final long gradientBytes = (blockDims[0] + 2) * (blockDims[1] + 2) * (blockDims[2] + 2) * 3 * 16;
        final long hessianBytes = blockDims[0] * blockDims[1] * blockDims[2] * 6 * 16;
        return outBytes + gaussBytes + gradientBytes + hessianBytes;
    }

    public static class ImgStats {

        final Iterable<? extends RealType<?>> input;
        double min;
        double max;
        double avg;
        double stdDev;

        public ImgStats(final Iterable<? extends RealType<?>> input) {
            this.input = input;
        }

        public double getMin() {
            return this.min;
        }

        public double getMax() {
            return this.max;
        }

        public double getAvg() {
            return this.avg;
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
            this.avg = realSum.getSum() / count;
            final RealSum sumsq = new RealSum();
            for (final RealType<?> type : this.input) {
                sumsq.add(Math.pow(type.getRealDouble() - this.avg, 2));
            }
            this.stdDev = Math.sqrt(sumsq.getSum() / count);
        }

        @Override
        public String toString() {
            return "min=" + this.min + ", max=" + this.max + ", avg=" + this.avg + ", stdev=" + this.stdDev;
        }

    }

    public static void main(final String[] args) {
        ImageJ ij = new ImageJ();
        ij.ui().showUI();
        SNTService snt = ij.context().getService(SNTService.class);
        SNTUtils.setDebugMode(true);
        ImagePlus imp = snt.demoImage("OP_1");
        final HessianProcessor hessian = new HessianProcessor(imp, null);
        hessian.processTubeness(0.835, true);
        hessian.showTubeness();
        hessian.processFrangi(new double[]{0.835}, true);
        hessian.showFrangi();
    }

}