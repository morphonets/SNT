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

package sc.fiji.snt.filter;

import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import net.imglib2.view.Views;
import net.imagej.ops.special.computer.AbstractUnaryComputerOp;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Computes a spectral similarity map from a multichannel (e.g., Brainbow) image.
 * For each voxel, the output encodes how well the voxel's channel-intensity vector
 * matches a reference color vector. The output is a scalar image suitable for use
 * as a secondary tracing layer.
 * <p>
 * The per-voxel score combines two terms:
 * <ol>
 *   <li><b>Cosine similarity</b>: dot product of unit-normalized voxel and reference
 *       color vectors (1 = identical direction, 0 = orthogonal)</li>
 *   <li><b>Intensity factor</b>: sigmoid falloff based on how much the voxel's total
 *       intensity deviates from the reference intensity. This prevents bright
 *       background or dim noise from producing high scores</li>
 * </ol>
 * The final output is {@code cosineSimilarity × intensityFactor}, scaled to [0, 1].
 * High values indicate voxels that match the target neuron's color and brightness.
 * <p>
 * The input image is a 4D (X, Y, Z, C) {@link RandomAccessibleInterval} where the
 * last dimension is channels. The output is a 3D (X, Y, Z) scalar image.
 * <p>
 * This filter is designed for integration with SNT's secondary layer tracing
 * infrastructure, where any standard cost function (e.g., {@code Reciprocal})
 * applied to the output produces spectrally-aware path searches.
 * <p>
 * The color-vector approach to neurite identification in multichannel images is
 * also validated in:
 * <blockquote>
 *   Leiwe et al., "Automated neuronal reconstruction with super-multicolour Tetbow
 *   labelling and threshold-based clustering of colour hues", <i>Nat Commun</i> 15,
 *   5279 (2024). <a href="https://doi.org/10.1038/s41467-024-49455-y">doi:10.1038/s41467-024-49455-y</a>
 * </blockquote>
 *
 * @param <T> the pixel type of the input multichannel image
 * @param <U> the pixel type of the output scalar image
 * @author Tiago Ferreira
 * @see sc.fiji.snt.analysis.MultiSpectralRefiner
 */
public class SpectralSimilarity<T extends RealType<T>, U extends RealType<U>> extends
        AbstractUnaryComputerOp<RandomAccessibleInterval<T>, RandomAccessibleInterval<U>>
        implements Consumer<RandomAccessibleInterval<U>>
{

    private final double[] referenceColor;
    private final double[] normalizedRef;
    private final double refMagnitude;
    private final double refSum;
    private final int numThreads;
    private final double intensityTolerance;

    /**
     * Constructs a spectral similarity filter.
     *
     * @param referenceColor the reference color vector (one value per channel,
     *                       unnormalized intensities). Typically the average
     *                       color sampled from representative paths.
     * @param numThreads     number of threads for parallel computation
     */
    public SpectralSimilarity(final double[] referenceColor, final int numThreads) {
        this(referenceColor, numThreads, 2.0);
    }

    /**
     * Constructs a spectral similarity filter with custom intensity tolerance.
     *
     * @param referenceColor     the reference color vector (one value per channel)
     * @param numThreads         number of threads for parallel computation
     * @param intensityTolerance controls how tolerant the intensity matching is.
     *                           A value of 2.0 means voxels with total intensity
     *                           between 0.25× and 4× the reference are scored highly.
     *                           Higher values = more tolerant.
     */
    public SpectralSimilarity(final double[] referenceColor, final int numThreads,
                              final double intensityTolerance) {
        this.referenceColor = referenceColor.clone();
        this.numThreads = Math.max(1, Math.min(numThreads, Runtime.getRuntime().availableProcessors()));
        this.intensityTolerance = intensityTolerance;

        // Precompute normalized reference and magnitude
        this.refSum = channelSum(referenceColor);
        double mag = 0;
        for (final double v : referenceColor) mag += v * v;
        this.refMagnitude = Math.sqrt(mag);
        this.normalizedRef = referenceColor.clone();
        normalizeVector(this.normalizedRef);
    }

    /**
     * Returns the reference color vector used by this filter.
     * @return a copy of the reference color array
     */
    public double[] getReferenceColor() {
        return referenceColor.clone();
    }

    // -- Shared static utilities (used by MultiSpectralRefiner, ComputeSecondaryImg, etc.) --

    /**
     * Converts a path node from calibrated (real-world) coordinates to pixel
     * coordinates by dividing by the voxel spacing and rounding.
     *
     * @param node     the node in calibrated coordinates
     * @param xSpacing voxel width
     * @param ySpacing voxel height
     * @param zSpacing voxel depth
     * @return pixel coordinates as {@code [x, y, z]}
     */
    public static int[] nodeToPixelCoords(final sc.fiji.snt.util.PointInImage node,
                                          final double xSpacing, final double ySpacing,
                                          final double zSpacing) {
        return new int[]{
                (int) Math.round(node.x / xSpacing),
                (int) Math.round(node.y / ySpacing),
                (int) Math.round(node.z / zSpacing)
        };
    }

    /**
     * Normalizes a vector to unit length in place. If the vector has zero
     * magnitude, it is left unchanged.
     *
     * @param vec the vector to normalize
     */
    public static void normalizeVector(final double[] vec) {
        double mag = 0;
        for (final double v : vec) mag += v * v;
        mag = Math.sqrt(mag);
        if (mag > 0) {
            for (int i = 0; i < vec.length; i++) vec[i] /= mag;
        }
    }

    /**
     * Sums all elements of a vector. Typically used to compute the total
     * intensity across channels of a color vector.
     *
     * @param vec the vector
     * @return the sum of all elements
     */
    public static double channelSum(final double[] vec) {
        double sum = 0;
        for (final double v : vec) sum += v;
        return sum;
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

    /**
     * Computes the spectral similarity map.
     *
     * @param input  4D input image (X, Y, Z, C) where the last dimension is channels
     * @param output 3D output image (X, Y, Z): same spatial dimensions as input
     */
    @Override
    public void compute(final RandomAccessibleInterval<T> input, final RandomAccessibleInterval<U> output) {
        final int inputDims = input.numDimensions();
        final int outputDims = output.numDimensions();

        if (inputDims != 4) {
            throw new IllegalArgumentException(
                    "Input must be 4D (X, Y, Z, C), got " + inputDims + "D");
        }
        if (outputDims != 3) {
            throw new IllegalArgumentException(
                    "Output must be 3D (X, Y, Z), got " + outputDims + "D");
        }

        final int nChannels = (int) input.dimension(3);
        if (nChannels != referenceColor.length) {
            throw new IllegalArgumentException(
                    "Input has " + nChannels + " channels but reference color has "
                            + referenceColor.length + " components");
        }

        // Extract per-channel 3D views
        @SuppressWarnings("unchecked")
        final RandomAccessibleInterval<T>[] channels = new RandomAccessibleInterval[nChannels];
        for (int c = 0; c < nChannels; c++) {
            channels[c] = Views.hyperSlice(input, 3, c);
        }

        // Parallel processing: one Z-slice per task, each with thread-local RandomAccess
        final ExecutorService es = Executors.newFixedThreadPool(numThreads);
        try {
            final long minZ = output.min(2);
            final long maxZ = output.max(2);
            final List<Future<?>> futures = new ArrayList<>((int) (maxZ - minZ + 1));

            for (long z = minZ; z <= maxZ; z++) {
                final long zSlice = z;
                futures.add(es.submit(() -> processZSlice(channels, output, nChannels, zSlice)));
            }

            for (final Future<?> f : futures) {
                try {
                    f.get();
                } catch (final Exception e) {
                    throw new RuntimeException("Error computing spectral similarity", e);
                }
            }
        } finally {
            es.shutdown();
        }
    }

    /**
     * Processes a single Z-slice of the output image. Each call creates its own
     * thread-local RandomAccess instances for safe concurrent execution.
     */
    @SuppressWarnings("unchecked")
    private void processZSlice(final RandomAccessibleInterval<T>[] channels,
                               final RandomAccessibleInterval<U> output,
                               final int nChannels, final long z) {
        // Thread-local random accesses
        final RandomAccess<T>[] localAccess = new RandomAccess[nChannels];
        for (int c = 0; c < nChannels; c++) {
            localAccess[c] = channels[c].randomAccess();
        }
        final RandomAccess<U> outAccess = output.randomAccess();

        for (long y = output.min(1); y <= output.max(1); y++) {
            for (long x = output.min(0); x <= output.max(0); x++) {
                // Read all channels at this spatial position
                double voxelSum = 0;
                double dotProd = 0;
                double magVoxel = 0;
                for (int c = 0; c < nChannels; c++) {
                    localAccess[c].setPosition(x, 0);
                    localAccess[c].setPosition(y, 1);
                    localAccess[c].setPosition(z, 2);
                    final double v = localAccess[c].get().getRealDouble();
                    voxelSum += v;
                    dotProd += v * normalizedRef[c];
                    magVoxel += v * v;
                }

                // Cosine similarity (normalizedRef already unit-length, so magRef = 1)
                final double magV = Math.sqrt(magVoxel);
                double cosSim = (magV > 0) ? dotProd / magV : 0;
                cosSim = Math.max(0, cosSim); // clamp negatives

                // Intensity factor: soft falloff based on log-ratio of intensities.
                // Gaussian-like falloff in log-space so response is symmetric for
                // voxels that are N× brighter or N× dimmer than the reference
                double intensityFactor;
                if (refSum > 0 && voxelSum > 0) {
                    final double logRatio = Math.log(voxelSum / refSum);
                    intensityFactor = Math.exp(-(logRatio * logRatio)
                            / (2.0 * intensityTolerance * intensityTolerance));
                } else if (refSum <= 0 && voxelSum <= 0) {
                    intensityFactor = 1.0; // both zero/dark
                } else {
                    intensityFactor = 0.0; // one is bright, the other dark
                }

                outAccess.setPosition(x, 0);
                outAccess.setPosition(y, 1);
                outAccess.setPosition(z, 2);
                outAccess.get().setReal(cosSim * intensityFactor);
            }
        }
    }

    /**
     * Computes the average color vector from a set of 3D positions in a
     * multichannel image. This is the standard way to derive a reference color
     * for spectral similarity tracing from existing traced paths.
     *
     * @param <T>        pixel type
     * @param input      4D image (X, Y, Z, C)
     * @param positions  array of [x, y, z] pixel coordinates to sample
     * @return the average color vector (one value per channel)
     */
    public static <T extends RealType<T>> double[] averageColorAtPositions(
            final RandomAccessibleInterval<T> input, final int[][] positions) {
        final int nChannels = (int) input.dimension(3);
        final double[] avg = new double[nChannels];
        if (positions.length == 0) return avg;

        for (int c = 0; c < nChannels; c++) {
            final RandomAccess<T> access = Views.hyperSlice(input, 3, c).randomAccess();
            for (final int[] pos : positions) {
                access.setPosition(pos[0], 0);
                access.setPosition(pos[1], 1);
                access.setPosition(pos[2], 2);
                avg[c] += access.get().getRealDouble();
            }
            avg[c] /= positions.length;
        }
        return avg;
    }

    /**
     * Computes the average color vector from a set of 3D positions in a
     * multichannel image represented as per-channel {@link ij.ImageStack}s.
     *
     * @param channelStacks one ImageStack per channel
     * @param positions     array of [x, y, z] pixel coordinates to sample
     * @return the average color vector (one value per channel)
     */
    public static double[] averageColorAtPositions(
            final ij.ImageStack[] channelStacks, final int[][] positions) {
        final int nChannels = channelStacks.length;
        final double[] avg = new double[nChannels];
        if (positions.length == 0) return avg;

        for (int c = 0; c < nChannels; c++) {
            for (final int[] pos : positions) {
                avg[c] += channelStacks[c].getVoxel(pos[0], pos[1], pos[2]);
            }
            avg[c] /= positions.length;
        }
        return avg;
    }

    /**
     * Computes the average color vector from path node positions.
     * Convenience method that extracts pixel positions from SNT Paths
     * using the provided spacing.
     *
     * @param <T>       pixel type
     * @param input     4D image (X, Y, Z, C)
     * @param paths     list of paths to sample
     * @param xSpacing  x pixel spacing
     * @param ySpacing  y pixel spacing
     * @param zSpacing  z pixel spacing
     * @return the average color vector (one value per channel)
     */
    public static <T extends RealType<T>> double[] averageColorFromPaths(
            final RandomAccessibleInterval<T> input,
            final java.util.List<sc.fiji.snt.Path> paths,
            final double xSpacing, final double ySpacing, final double zSpacing) {
        // Count total nodes
        int totalNodes = 0;
        for (final sc.fiji.snt.Path p : paths) totalNodes += p.size();
        if (totalNodes == 0) return new double[(int) input.dimension(3)];

        // Extract pixel positions
        final int[][] positions = new int[totalNodes][3];
        int idx = 0;
        for (final sc.fiji.snt.Path p : paths) {
            for (int i = 0; i < p.size(); i++) {
                positions[idx++] = nodeToPixelCoords(p.getNode(i), xSpacing, ySpacing, zSpacing);
            }
        }
        return averageColorAtPositions(input, positions);
    }
}
