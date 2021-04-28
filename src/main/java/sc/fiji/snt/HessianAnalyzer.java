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

import ij.ImagePlus;
import ij.measure.Calibration;
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
import org.scijava.Context;

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

    private int nThreads;

    public HessianAnalyzer(final ImagePlus imp) {
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
        this.nThreads = SNTPrefs.getThreads();
    }

    public void setNumThreads(final int nThreads) {
        this.nThreads = nThreads;
    }

    protected RandomAccessibleInterval<FloatType> tubeness2D(final RandomAccessibleInterval<FloatType> eigenvalueRai,
                                                             final TaskExecutor ex)
    {
        final int d = eigenvalueRai.numDimensions() - 1;
        final IntervalView<FloatType> evs0 = Views.hyperSlice(eigenvalueRai, d, 0);
        final IntervalView<FloatType> evs1 = Views.hyperSlice(eigenvalueRai, d, 1);
        final RandomAccessibleInterval<FloatType> tubenessRai = ArrayImgs.floats(Intervals.dimensionsAsLongArray(evs0));
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
                                                             final TaskExecutor ex)
    {
        final int d = eigenvalueRai.numDimensions() - 1;
        final IntervalView<FloatType> evs0 = Views.hyperSlice(eigenvalueRai, d, 0);
        final IntervalView<FloatType> evs1 = Views.hyperSlice(eigenvalueRai, d, 1);
        final IntervalView<FloatType> evs2 = Views.hyperSlice(eigenvalueRai, d, 2);
        final RandomAccessibleInterval<FloatType> tubenessRai = ArrayImgs.floats(Intervals.dimensionsAsLongArray(evs0));
        LoopBuilder.setImages(evs0, evs1, evs2, tubenessRai).multiThreaded(ex).forEachPixel(
                (ev0, ev1, ev2, v) -> {
                    float e0 = ev0.getRealFloat();
                    float e1 = ev1.getRealFloat();
                    float e2 = ev2.getRealFloat();
                    final List<Float> dl = Arrays.asList(e0, e1, e2);
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

    protected float maxSquaredFrobenius(final RandomAccessibleInterval<FloatType> hessianRai, final TaskExecutor ex) {
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
                                                           final double beta, final double c, final TaskExecutor ex)
    {
        final double betaDen = 2 * beta * beta;
        final double cDen = 2 * c * c;

        final int d = eigenvalueRai.numDimensions() - 1;
        final IntervalView<FloatType> evs0 = Views.hyperSlice(eigenvalueRai, d, 0);
        final IntervalView<FloatType> evs1 = Views.hyperSlice(eigenvalueRai, d, 1);

        final RandomAccessibleInterval<FloatType> vesselnessRai = ArrayImgs.floats(Intervals.dimensionsAsLongArray(evs0));

        LoopBuilder.setImages(evs0, evs1, vesselnessRai).multiThreaded(ex).forEachPixel(
                (ev0, ev1, v) -> {
                    float e0 = ev0.getRealFloat();
                    float e1 = ev1.getRealFloat();
                    final List<Float> dl = Arrays.asList(e0, e1);
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
        return vesselnessRai;
    }

    protected RandomAccessibleInterval<FloatType> frangi3D(final RandomAccessibleInterval<FloatType> eigenvalueRai,
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

        final RandomAccessibleInterval<FloatType> vesselnessRai = ArrayImgs.floats(Intervals.dimensionsAsLongArray(evs0));

        LoopBuilder.setImages(evs0, evs1, evs2, vesselnessRai).multiThreaded(ex).forEachPixel(
                (ev0, ev1, ev2, v) -> {
                    float e0 = ev0.getRealFloat();
                    float e1 = ev1.getRealFloat();
                    float e2 = ev2.getRealFloat();
                    final List<Float> dl = Arrays.asList(e0, e1, e2);
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
        return vesselnessRai;
    }

    protected RandomAccessibleInterval<FloatType> hessianMatrix(final RandomAccessibleInterval<FloatType> gaussianRai,
                                                                final int nThreads, final ExecutorService es)
            throws ExecutionException, InterruptedException
    {
        // FIXME: THIS USES A LOT OF MEMORY
        return HessianMatrix.calculateMatrix(
                Views.extendBorder(gaussianRai),
                this.is3D ? ArrayImgs.floats(this.imgDim[0], this.imgDim[1], this.imgDim[2], 3) :
                        ArrayImgs.floats(this.imgDim[0], this.imgDim[1], 2),
                this.is3D ? ArrayImgs.floats(this.imgDim[0], this.imgDim[1], this.imgDim[2], 6) :
                        ArrayImgs.floats(this.imgDim[0], this.imgDim[1], 3),
                new OutOfBoundsBorderFactory<>(),
                nThreads,
                es);
    }

    protected RandomAccessibleInterval<FloatType> hessianEigenvalues(final RandomAccessibleInterval<FloatType> hessianMatrix,
                                                                     final int nThreads, final ExecutorService es)
            throws IncompatibleTypeException
    {
        return TensorEigenValues.calculateEigenValuesSymmetric(hessianMatrix,
                TensorEigenValues.createAppropriateResultImg(hessianMatrix, new ArrayImgFactory<>(new FloatType())),
                nThreads, es);
    }

    protected RandomAccessibleInterval<FloatType> gauss(final double[] sigmas) {
        RandomAccessibleInterval<FloatType> gaussian = ArrayImgs.floats(this.imgDim);
        FastGauss.convolve(sigmas, Views.extendBorder(this.img), gaussian);
        return gaussian;
    }

    public void processGaussian(final double[] sigmas) {
        SNTUtils.log("Computing Gaussian at sigmas: " + Arrays.toString(sigmas));
        this.gaussianImg = (Img<FloatType>) gauss(sigmas);
        this.gaussianAccess = this.gaussianImg.randomAccess();
        SNTUtils.log("Done computing Gaussian.");
    }

    public void processTubeness(final double sigma, final boolean computeStats) {

        SNTUtils.log("Computing Tubeness at sigma: " + sigma);

        final ExecutorService es = Executors.newFixedThreadPool(this.nThreads);
        final TaskExecutor ex = new DefaultTaskExecutor(es);

        double[] sigmaArr;
        if (this.is3D) {
            sigmaArr = new double[]{sigma / this.pixelWidth, sigma / this.pixelHeight, sigma / this.pixelDepth};
        } else {
            sigmaArr = new double[]{sigma / this.pixelWidth, sigma / this.pixelHeight};
        }

        RandomAccessibleInterval<FloatType> result;
        try {
            result = hessianMatrix(gauss(sigmaArr), this.nThreads, es);
        } catch (final ExecutionException e) {
            SNTUtils.error(e.getMessage(), e);
            es.shutdownNow();
            ex.close();
            return;
        } catch (final InterruptedException e) {
            SNTUtils.error("Tubeness interrupted.", e);
            es.shutdownNow();
            ex.close();
            Thread.currentThread().interrupt();
            return;
        }

        result = hessianEigenvalues(result, this.nThreads, es);
        result = is3D ? tubeness3D(result, ex) : tubeness2D(result, ex);

        es.shutdown();
        ex.close();

        this.tubenessImg = (Img<FloatType>) result;
        this.tubenessAccess = this.tubenessImg.randomAccess();

        SNTUtils.log("Done computing Tubeness.");

        if (computeStats) {
            SNTUtils.log("Computing Tubeness stats");
            this.tubenessStats = new ImgStats(Views.iterable(this.tubenessImg));
            this.tubenessStats.process();
            SNTUtils.log("Tubeness stats: " + this.tubenessStats);
        }

    }

    public void processFrangi(double[] scales, boolean computeStats) {

        SNTUtils.log("Computing Frangi at scales: " + Arrays.toString(scales));

        final List<double[]> sigmas = new ArrayList<>();
        if (this.is3D) {
            for (final double sc : scales) {
                final double[] sigma = new double[]{sc / this.pixelWidth, sc / this.pixelHeight, sc / this.pixelDepth};
                sigmas.add(sigma);
            }
        } else {
            for (final double sc : scales) {
                final double[] sigma = new double[]{sc / this.pixelWidth, sc / this.pixelHeight};
                sigmas.add(sigma);
            }
        }

        final ExecutorService es = Executors.newFixedThreadPool(this.nThreads);
        final TaskExecutor ex = new DefaultTaskExecutor(es);

        this.frangiImg = ArrayImgs.floats(Intervals.dimensionsAsLongArray(this.img));
        for (final double[] sigma : sigmas) {
            RandomAccessibleInterval<FloatType> result = gauss(sigma);
            try {
                result = hessianMatrix(result, this.nThreads, es);
            } catch (final ExecutionException e) {
                SNTUtils.error(e.getMessage(), e);
                es.shutdownNow();
                ex.close();
                return;
            } catch (final InterruptedException e) {
                SNTUtils.error("Frangi interrupted.", e);
                es.shutdownNow();
                ex.close();
                Thread.currentThread().interrupt();
                return;
            }
            final double c = Math.sqrt(maxSquaredFrobenius(result, ex)) * 0.5;
            result = hessianEigenvalues(result, this.nThreads, es);
            result = this.is3D ? frangi3D(result, 0.5, 0.5, c, ex) : frangi2D(result, 0.5, c, ex);
            LoopBuilder.setImages(this.frangiImg, result).multiThreaded(ex).forEachPixel(
                    (resultv, v) -> resultv.set(Math.max(resultv.getRealFloat(), v.getRealFloat()))
            );
        }
        this.frangiAccess = this.frangiImg.randomAccess();

        es.shutdown();
        ex.close();

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
        SNTService snt = new SNTService();
        snt.setContext(new Context());
        SNTUtils.setDebugMode(true);
        final HessianAnalyzer hessian = new HessianAnalyzer(new SNTService().demoImage("OP_1"));
        hessian.processTubeness(0.84, true);
        hessian.showTubeness();
        hessian.processFrangi(new double[]{0.33, 0.66, 0.99}, true);
        hessian.showFrangi();
    }

}