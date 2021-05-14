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
import net.imagej.ImageJ;
import ij.ImagePlus;
import ij.measure.Calibration;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.algorithm.convolution.fast_gauss.FastGauss;
import net.imglib2.algorithm.gradient.HessianMatrix;
import net.imglib2.algorithm.linalg.eigen.TensorEigenValues;
import net.imglib2.algorithm.stats.ComputeMinMax;
import net.imglib2.exception.IncompatibleTypeException;
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
import java.util.Comparator;
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
public class HessianAnalyzer {
    static class TrivialProgressDisplayer implements HessianGenerationCallback {
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

    Img<FloatType> gaussianImg;
    RandomAccess<FloatType> gaussianAccess;

    Img<FloatType> tubenessImg;
    RandomAccess<FloatType> tubenessAccess;
    ImgStats tubenessStats;

    Img<FloatType> frangiImg;
    RandomAccess<FloatType> frangiAccess;
    ImgStats frangiStats;

    private final HessianGenerationCallback callback;

    private int nThreads;


    public HessianAnalyzer(final ImagePlus imp, final HessianGenerationCallback callback) {
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

    public void setNumThreads(final int nThreads) {
        this.nThreads = nThreads;
    }

    public void processGaussian(final double[] sigmas) {
        SNTUtils.log("Computing Gaussian at sigmas: " + Arrays.toString(sigmas));
        this.gaussianImg = (Img<FloatType>) gauss(sigmas);
        this.gaussianAccess = this.gaussianImg.randomAccess();
        SNTUtils.log("Done computing Gaussian.");
    }

    public void processTubeness(final double sigma, final boolean computeStats) {
        SNTUtils.log("Computing Tubeness at sigma: " + sigma);
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
    }

    public void processFrangi(final double[] scales, final boolean computeStats) {
        SNTUtils.log("Computing Frangi at scales: " + Arrays.toString(scales));
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
    }

    public ImgStats getTubenessStats() {
        return this.tubenessStats;
    }

    public ImgStats getFrangiStats() {
        return this.frangiStats;
    }

    public Img<FloatType> getGaussianImg() {
        return this.gaussianImg;
    }

    public Img<FloatType> getTubenessImg() {
        return this.tubenessImg;
    }

    public Img<FloatType> getFrangiImg() {
        return this.frangiImg;
    }

    public void showGaussian() {
        if (this.gaussianImg == null) {
            throw new IllegalArgumentException("Gaussian Img is null. You must first call processGauss()");
        }
        ImageJFunctions.show(this.gaussianImg, "Gaussian");
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
                    final List<Double> dl = Arrays.asList(e0, e1);
                    dl.sort(Comparator.comparingDouble(Math::abs));
                    e1 = dl.get(1);
                    double result = 0;
                    if (e1 < 0) {
                        result = Math.abs(e1);
                    }
                    v.set((float) result);
                }
        );
        return tubenessRai;
    }

    protected RandomAccessibleInterval<FloatType> tubeness3D(final RandomAccessibleInterval<FloatType> eigenvalueRai,
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
                    final List<Double> dl = Arrays.asList(e0, e1, e2);
                    dl.sort(Comparator.comparingDouble(Math::abs));
                    e1 = dl.get(1);
                    e2 = dl.get(2);
                    double result = 0;
                    if (e1 < 0 && e2 < 0) {
                        result = Math.sqrt(e1 * e2);
                    }
                    v.set((float) result);
                }
        );
        return tubenessRai;
    }

    protected float maxSquaredFrobenius(final RandomAccessibleInterval<FloatType> hessianRai, final TaskExecutor ex)
    {
        final RandomAccessibleInterval<FloatType> sum = ArrayImgs.floats(this.imgDim);
        for (int d = 0; d < hessianRai.dimension(hessianRai.numDimensions() - 1); ++d) {
            final IntervalView<FloatType> hessian = Views.hyperSlice(hessianRai, hessianRai.numDimensions() - 1, d);
            LoopBuilder.setImages(sum, hessian).multiThreaded(ex).forEachPixel(
                    (s, h) -> s.set(s.getRealFloat() + h.getRealFloat() * h.getRealFloat())
            );
        }
        final ComputeMinMax<FloatType> minMax = new ComputeMinMax<>(Views.iterable(sum), new FloatType(), new FloatType());
        minMax.setNumThreads(Runtime.getRuntime().availableProcessors());
        minMax.process();
        return minMax.getMax().getRealFloat();
    }

    protected RandomAccessibleInterval<FloatType> frangi2D(final RandomAccessibleInterval<FloatType> eigenvalueRai,
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
                    final List<Double> dl = Arrays.asList(e0, e1);
                    dl.sort(Comparator.comparingDouble(Math::abs));
                    e0 = dl.get(0);
                    e1 = dl.get(1);
                    double result = 0;
                    if (e1 < 0) {
                        double rb = e0 / e1;
                        double rbsq = rb * rb;
                        double s = Math.sqrt(e0 * e0 + e1 * e1);
                        double ssq = s * s;
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

    protected RandomAccessibleInterval<FloatType> frangi3D(final RandomAccessibleInterval<FloatType> eigenvalueRai,
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
                    final List<Double> dl = Arrays.asList(e0, e1, e2);
                    dl.sort(Comparator.comparingDouble(Math::abs));
                    e0 = dl.get(0);
                    e1 = dl.get(1);
                    e2 = dl.get(2);
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
        return frangiRai;
    }

    protected RandomAccessibleInterval<FloatType> hessian2D(final RandomAccessibleInterval<FloatType> gaussianRai,
                                                            final int nThreads, final ExecutorService es)
            throws ExecutionException, InterruptedException
    {
        return HessianMatrix.calculateMatrix(
                Views.extendBorder(gaussianRai),
                ArrayImgs.floats(this.imgDim[0], this.imgDim[1], 2),
                ArrayImgs.floats(this.imgDim[0], this.imgDim[1], 3),
                new OutOfBoundsBorderFactory<>(),
                nThreads,
                es);
    }

    protected RandomAccessibleInterval<FloatType> hessianEigenvalues(final RandomAccessibleInterval<FloatType> hessianRai,
                                                                     final RandomAccessibleInterval<FloatType> eigenvalueRai,
                                                                     final int nThreads, final ExecutorService es)
            throws IncompatibleTypeException
    {
        return TensorEigenValues.calculateEigenValuesSymmetric(hessianRai, eigenvalueRai, nThreads, es);
    }

    protected RandomAccessibleInterval<FloatType> gauss(final double[] sigmas)
    {
        RandomAccessibleInterval<FloatType> gaussian = ArrayImgs.floats(this.imgDim);
        FastGauss.convolve(sigmas, Views.extendBorder(this.img), gaussian);
        return gaussian;
    }

    protected void processTubeness3D(final double sigma)
    {

        callback.proportionDone(0.0);

        double[] sigmaArr = new double[]{sigma / this.pixelWidth, sigma / this.pixelHeight, sigma / this.pixelDepth};

        final ExecutorService es = Executors.newFixedThreadPool(this.nThreads);
        final TaskExecutor ex = new DefaultTaskExecutor(es);

        final Img<FloatType> output = ArrayImgs.floats(this.imgDim);

//        final RandomAccessibleInterval< FloatType > gaussian = ArrayImgs.floats(this.imgDim);
//        FastGauss.convolve(sigmaArr, Views.extendBorder(this.img), gaussian);

        // FIXME: check if this makes sense
        final RandomAccessibleInterval<FloatType> tmpGaussian = ArrayImgs.floats(
                output.dimension(0),
                output.dimension(1),
                5);

        // allocate storage for gradient
        final RandomAccessibleInterval<FloatType> tmpGradient = ArrayImgs.floats(
                output.dimension(0),
                output.dimension(1),
                3,
                3);

        // allocate storage for hessian
        final RandomAccessibleInterval<FloatType> tmpHessian = ArrayImgs.floats(
                output.dimension(0),
                output.dimension(1),
                1,
                6);

        final RandomAccessibleInterval<FloatType> tmpEigenvalues = ArrayImgs.floats(
                output.dimension(0),
                output.dimension(1),
                1,
                3
        );

        for (int z = 0; z < output.dimension(output.numDimensions() - 1); ++z) {

            final FinalInterval interval = Intervals.createMinMax(
                    output.min(0), output.min(1), z,
                    output.max(0), output.max(1), z);

            // FIXME: check if this makes sense
            RandomAccessibleInterval<FloatType> gaussian = Views.translate(tmpGaussian, 0, 0, z - 2);
            FastGauss.convolve(sigmaArr, Views.extendMirrorSingle(this.img), gaussian);

            // gradient contains the 3 slices centered at z, i.e. [z-1, z, z+1]
            RandomAccessibleInterval<FloatType> gradient = Views.translate(tmpGradient, 0, 0, z - 1, 0);

            // hessian is the single slice at z
            RandomAccessibleInterval<FloatType> hessian = Views.translate(tmpHessian, 0, 0, z, 0);

            try {
                HessianMatrix.calculateMatrix(
                        gaussian,
                        gradient,
                        hessian,
                        new OutOfBoundsBorderFactory<>(),
                        Runtime.getRuntime().availableProcessors(),
                        es);
            } catch (final ExecutionException e) {
                SNTUtils.error(e.getMessage(), e);
                es.shutdownNow();
                ex.close();
                callback.proportionDone(-1);
                return;
            } catch (final InterruptedException e) {
                SNTUtils.error("Tubeness interrupted.", e);
                es.shutdownNow();
                ex.close();
                callback.proportionDone(-1);
                Thread.currentThread().interrupt();
                return;
            }

            hessianEigenvalues(hessian, tmpEigenvalues, nThreads, es);

            tubeness3D(tmpEigenvalues, Views.interval(output, interval), ex);

            callback.proportionDone((double) z / output.dimension(output.numDimensions() - 1));

        }

        es.shutdown();
        ex.close();

        this.tubenessImg = output;
        this.tubenessAccess = this.tubenessImg.randomAccess();

        callback.proportionDone(1.0);

    }

    protected void processTubeness2D(final double sigma)
    {

        callback.proportionDone(0.0);

        final ExecutorService es = Executors.newFixedThreadPool(this.nThreads);
        final TaskExecutor ex = new DefaultTaskExecutor(es);

        double[] sigmaArr;
        sigmaArr = new double[]{sigma / this.pixelWidth, sigma / this.pixelHeight};

        RandomAccessibleInterval<FloatType> result;
        try {
            result = hessian2D(gauss(sigmaArr), this.nThreads, es);
        } catch (final ExecutionException e) {
            SNTUtils.error(e.getMessage(), e);
            es.shutdownNow();
            ex.close();
            callback.proportionDone(-1);
            return;
        } catch (final InterruptedException e) {
            SNTUtils.error("Tubeness interrupted.", e);
            es.shutdownNow();
            ex.close();
            callback.proportionDone(-1);
            Thread.currentThread().interrupt();
            return;
        }

        result = hessianEigenvalues(result, TensorEigenValues.createAppropriateResultImg(result,
                new ArrayImgFactory<>(new FloatType())), this.nThreads, es);

        result = tubeness2D(result, ArrayImgs.floats(this.imgDim), ex);

        es.shutdown();
        ex.close();

        this.tubenessImg = (Img<FloatType>) result;
        this.tubenessAccess = this.tubenessImg.randomAccess();

        callback.proportionDone(1.0);

    }

    protected void processFrangi3D(double[] scales)
    {

        callback.proportionDone(0.0);

        final List<double[]> sigmas = new ArrayList<>();
        for (final double sc : scales) {
            final double[] sigma = new double[]{sc / this.pixelWidth, sc / this.pixelHeight, sc / this.pixelDepth};
            sigmas.add(sigma);
        }

        final ExecutorService es = Executors.newFixedThreadPool(this.nThreads);
        final TaskExecutor ex = new DefaultTaskExecutor(es);

        this.frangiImg = ArrayImgs.floats(Intervals.dimensionsAsLongArray(this.img));

        // FIXME: check if this makes sense
        final RandomAccessibleInterval<FloatType> tmpGaussian = ArrayImgs.floats(
                this.img.dimension(0),
                this.img.dimension(1),
                5);

        // allocate storage for gradient
        final RandomAccessibleInterval<FloatType> tmpGradient = ArrayImgs.floats(
                this.img.dimension(0),
                this.img.dimension(1),
                3,
                3);

        // allocate storage for hessian
        final RandomAccessibleInterval<FloatType> tmpHessian = ArrayImgs.floats(
                this.img.dimension(0),
                this.img.dimension(1),
                1,
                6);

        final RandomAccessibleInterval<FloatType> tmpEigenvalues = ArrayImgs.floats(
                this.imgDim[0],
                this.imgDim[1],
                1,
                3);

        final RandomAccessibleInterval<FloatType> tmpFrangi = ArrayImgs.floats(
                this.imgDim[0],
                this.imgDim[1],
                1);

        int nIterations = sigmas.size() * (int) this.img.dimension(this.img.numDimensions() - 1);
        int iter = 0;
        for (final double[] sigma : sigmas) {

            for (int z = 0; z < this.img.dimension(this.img.numDimensions() - 1); ++z) {

                final FinalInterval interval = Intervals.createMinMax(
                        this.img.min(0), this.img.min(1), z,
                        this.img.max(0), this.img.max(1), z);

                // FIXME: check if this makes sense
                RandomAccessibleInterval<FloatType> gaussian = Views.translate(tmpGaussian, 0, 0, z - 2);
                FastGauss.convolve(sigma, Views.extendMirrorSingle(this.img), gaussian);

                // gradient contains the 3 slices centered at z, i.e. [z-1, z, z+1]
                RandomAccessibleInterval<FloatType> gradient = Views.translate(tmpGradient, 0, 0, z - 1, 0);

                // hessian is the single slice at z
                RandomAccessibleInterval<FloatType> hessian = Views.translate(tmpHessian, 0, 0, z, 0);

                try {
                    HessianMatrix.calculateMatrix(
                            gaussian,
                            gradient,
                            hessian,
                            new OutOfBoundsBorderFactory<>(),
                            Runtime.getRuntime().availableProcessors(),
                            es);
                } catch (final ExecutionException e) {
                    SNTUtils.error(e.getMessage(), e);
                    es.shutdownNow();
                    ex.close();
                    callback.proportionDone(-1);
                    return;
                } catch (final InterruptedException e) {
                    SNTUtils.error("Frangi interrupted.", e);
                    es.shutdownNow();
                    ex.close();
                    callback.proportionDone(-1);
                    Thread.currentThread().interrupt();
                    return;
                }

                hessianEigenvalues(hessian, tmpEigenvalues, nThreads, es);

                frangi3D(tmpEigenvalues, tmpFrangi, 0.5, 0.5, 200, ex);

                LoopBuilder.setImages(Views.interval(this.frangiImg, interval), tmpFrangi)
                        .multiThreaded(ex)
                        .forEachPixel((f, t) -> f.set(Math.max(f.getRealFloat(), t.getRealFloat())));

                callback.proportionDone(++iter / (double) nIterations);

            }

        }

        es.shutdown();
        ex.close();

        this.frangiAccess = this.frangiImg.randomAccess();

        callback.proportionDone(1.0);

    }

    protected void processFrangi2D(double[] scales)
    {

        callback.proportionDone(0.0);

        final List<double[]> sigmas = new ArrayList<>();
        for (final double sc : scales) {
            final double[] sigma = new double[]{sc / this.pixelWidth, sc / this.pixelHeight};
            sigmas.add(sigma);
        }

        final ExecutorService es = Executors.newFixedThreadPool(this.nThreads);
        final TaskExecutor ex = new DefaultTaskExecutor(es);

        this.frangiImg = ArrayImgs.floats(Intervals.dimensionsAsLongArray(this.img));
        for (final double[] sigma : sigmas) {
            RandomAccessibleInterval<FloatType> tmpFrangi = gauss(sigma);
            try {
                tmpFrangi = hessian2D(tmpFrangi, this.nThreads, es);
            } catch (final ExecutionException e) {
                SNTUtils.error(e.getMessage(), e);
                es.shutdownNow();
                ex.close();
                callback.proportionDone(-1);
                return;
            } catch (final InterruptedException e) {
                SNTUtils.error("Frangi interrupted.", e);
                es.shutdownNow();
                ex.close();
                callback.proportionDone(-1);
                Thread.currentThread().interrupt();
                return;
            }

            final double c = Math.sqrt(maxSquaredFrobenius(tmpFrangi, ex)) * 0.5;

            tmpFrangi = hessianEigenvalues(tmpFrangi, TensorEigenValues.createAppropriateResultImg(tmpFrangi,
                    new ArrayImgFactory<>(new FloatType())), this.nThreads, es);

            tmpFrangi = frangi2D(tmpFrangi, ArrayImgs.floats(this.imgDim), 0.5, c, ex);

            LoopBuilder.setImages(this.frangiImg, tmpFrangi).multiThreaded(ex).forEachPixel(
                    (f, t) -> f.set(Math.max(f.getRealFloat(), t.getRealFloat()))
            );
        }
        this.frangiAccess = this.frangiImg.randomAccess();

        callback.proportionDone(1.0);

        es.shutdown();
        ex.close();

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

    public static void main(final String[] args) throws ExecutionException, InterruptedException {
        ImageJ ij = new ImageJ();
        ij.ui().showUI();
        SNTService snt = ij.context().getService(SNTService.class);
        SNTUtils.setDebugMode(true);
        final HessianAnalyzer hessian = new HessianAnalyzer(snt.demoImage("OP_1"), null);
        //hessian.processTubeness(0.84, true);
        //hessian.showTubeness();
        hessian.processFrangi(new double[]{1.0}, true);
        hessian.showFrangi();
    }

}