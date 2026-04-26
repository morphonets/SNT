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

package sc.fiji.snt.util;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.plugin.filter.GaussianBlur;
import ij.process.FloatProcessor;
import ij.process.ShortProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.apache.commons.math3.distribution.PoissonDistribution;

import sc.fiji.snt.Path;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;

/**
 * Rasterizes a {@link Tree} into a 3D image using frustum (truncated-cone)
 * geometry with partial-volume supersampling. Each segment between consecutive
 * nodes is modeled as a frustum whose radii are linearly interpolated from the
 * node radii. This produces a volumetric rendering that respects the thickness
 * of each neurite, unlike the skeleton-based rasterization in
 * {@link Tree#getSkeleton()}.
 * <p>
 * Optionally, Poisson shot noise and Gaussian blur (to simulate spatially
 * correlated noise) can be applied, producing realistic synthetic fluorescence
 * microscopy images.
 * </p>
 * <p>
 * Optionally, voxel intensities can be modulated by local neurite thickness via
 * {@link #setThicknessModulation(double)}, producing brighter thick neurites
 * and dimmer thin ones.
 * </p>
 * <p>
 * Pass 2 (partial-volume supersampling) and subsequent passes are parallelized
 * across z-slices for improved performance. A spatial grid is used for frustum
 * lookups to reduce computation costs.
 * </p>
 * <p>
 * Inspired by SWC2IMG (E. Meijering, imagescience.org).
 * </p>
 *
 * @author Tiago Ferreira
 * @see Tree#getSkeleton()
 * @see TreeUtils#rasterize(Tree)
 */
public class TreeToRaster {

	private static final int BORDER_XY = 10;
	private static final int BORDER_Z = 5;
	private static final int SUPER = 5; // supersampling factor per axis
	private static final int GRID_CELL_VOXELS = 16; // spatial grid granularity

	private final Tree tree;
	private double lateralRes; // spatial units per voxel (x, y)
	private double axialRes;   // spatial units per voxel (z)
	private double defaultRadius = -1; // <0 means use lateralRes/2

	// Noise parameters (disabled by default)
	private double background = 0;
	private double snr = 0;        // <=0 means no shot noise
	private double gaussSigma = 0; // <=0 means no blur

	// Thickness modulation (disabled by default)
	private double thicknessModulation = 0; // 0 = off, >0 = fraction of range

	/**
	 * Constructs a new rasterizer for the given tree. Resolution defaults to
	 * the tree's own spatial calibration if available, otherwise 1 unit
	 * isotropic.
	 *
	 * @param tree the Tree to rasterize (must not be empty)
	 */
	public TreeToRaster(final Tree tree) {
		if (tree == null || tree.isEmpty())
			throw new IllegalArgumentException("Tree is null or empty");
		this.tree = tree;
		final BoundingBox bb = tree.getBoundingBox(true);
		this.lateralRes = (bb.xSpacing > 0) ? bb.xSpacing : 1.0;
		this.axialRes = (bb.zSpacing > 0) ? bb.zSpacing : 1.0;
	}

	/**
	 * Sets the lateral (x, y) voxel size.
	 *
	 * @param lateralRes voxel size in the tree's spatial units (typically µm)
	 * @return this instance for chaining
	 */
	public TreeToRaster setLateralRes(final double lateralRes) {
		if (lateralRes <= 0)
			throw new IllegalArgumentException("Lateral resolution must be positive");
		this.lateralRes = lateralRes;
		return this;
	}

	/**
	 * Sets the axial (z) voxel size.
	 *
	 * @param axialRes voxel size in the tree's spatial units (typically µm)
	 * @return this instance for chaining
	 */
	public TreeToRaster setAxialRes(final double axialRes) {
		if (axialRes <= 0)
			throw new IllegalArgumentException("Axial resolution must be positive");
		this.axialRes = axialRes;
		return this;
	}

	/**
	 * Sets the default radius used for nodes/trees that have no radii defined.
	 * If not set, defaults to half the lateral voxel size (i.e., a 1-voxel
	 * diameter).
	 *
	 * @param radius the default radius in the tree's spatial units
	 * @return this instance for chaining
	 */
	public TreeToRaster setDefaultRadius(final double radius) {
		if (radius <= 0)
			throw new IllegalArgumentException("Default radius must be positive");
		this.defaultRadius = radius;
		return this;
	}

	/**
	 * Enables Poisson shot noise on the rasterized image, simulating photon
	 * counting noise typical of fluorescence microscopy.
	 * <p>
	 * The peak intensity is derived from the SNR and background using the
	 * photon-counting model: {@code SNR = (peak - bg) / sqrt(peak)}. Note
	 * that Poisson shot noise is ignored when using {@link #rasterizeLabels()}
	 * </p>
	 *
	 * @param snr        the signal-to-noise ratio (peak-to-noise). Must be
	 *                   positive.
	 * @param background the background intensity (photon count). Must be
	 *                   non-negative.
	 * @return this instance for chaining
	 */
	public TreeToRaster setPoissonNoise(final double snr, final double background) {
		if (snr <= 0)
			throw new IllegalArgumentException("SNR must be positive");
		if (background < 0)
			throw new IllegalArgumentException("Background must be non-negative");
		this.snr = snr;
		this.background = background;
		return this;
	}

	/**
	 * Enables Gaussian blurring of the (optionally noisy) image, simulating
	 * spatially correlated noise and optical blur. Note that Gaussian blurring
	 * is ignored when using {@link #rasterizeLabels()}.
	 *
	 * @param sigma the Gaussian sigma in the tree's spatial units (typically
	 *              µm). Must be positive.
	 * @return this instance for chaining
	 */
	public TreeToRaster setGaussianBlur(final double sigma) {
		if (sigma <= 0)
			throw new IllegalArgumentException("Gaussian sigma must be positive");
		this.gaussSigma = sigma;
		return this;
	}

	/**
	 * Enables intensity modulation based on local neurite thickness. When
	 * enabled, thicker neurites are rendered brighter and thinner neurites
	 * dimmer, proportional to the local radius.
	 * <p>
	 * The modulation factor defines what fraction of the intensity range is
	 * used for thickness variation. For example, a factor of 0.2 means the
	 * thickest frustum receives full intensity (1.0) while the thinnest
	 * receives 80% of the maximum (0.8).
	 * </p>
	 * <p>
	 * Note: This option incurs additional computation since the local radius
	 * must be resolved for every sub-voxel hit during supersampling. Also, a
	 * factor of 1.0 maps the thinnest structure to zero intensity, effectively
	 * wiping it.
	 * </p>
	 *
	 * @param factor the modulation depth in [0, 1]. 0 disables modulation
	 *               (default). Must be non-negative and at most 1.
	 * @return this instance for chaining
	 */
	public TreeToRaster setThicknessModulation(final double factor) {
		if (factor < 0 || factor > 1)
			throw new IllegalArgumentException("Thickness modulation must be in [0, 1]");
		this.thicknessModulation = factor;
		return this;
	}

	/**
	 * Rasterizes the tree into a 32-bit (float) image using partial-volume
	 * supersampling. Voxel values represent the fraction of the supersampled
	 * sub-voxels that fall inside the neuron structure (0.0 = background,
	 * 1.0 = fully inside), unless noise has been enabled via
	 * {@link #setPoissonNoise(double, double)}, in which case values represent
	 * simulated photon counts.
	 *
	 * @return the rasterized {@link ImagePlus}
	 */
	public ImagePlus rasterize() {

		final RasterContext ctx = prepareRasterization();
		final int width = ctx.width, height = ctx.height, depth = ctx.depth;
		final int xo = ctx.xo, yo = ctx.yo, zo = ctx.zo;
		final FrustumGrid grid = ctx.grid;
		final List<Frustum> frustums = ctx.frustums;

		// img starts as the flag map from Pass 1; Pass 2 overwrites flagged
		// voxels with sub-voxel hit counts, then we normalize in-place
		final float[][] img = ctx.flagMap;

		// Pass 2: partial-volume supersampling (parallel by z-slice)
		final boolean doModulation = thicknessModulation > 0;
		final float[][] radImg = doModulation ? new float[depth][width * height] : null;
		final double[] sliceMaxDensity = new double[depth];

		IntStream.range(0, depth).parallel().forEach(z -> {
			final float[] slice = img[z];
			final float[] radSlice = doModulation ? radImg[z] : null;
			double localMax = 0;
			for (int y = 0; y < height; y++) {
				final int row = y * width;
				for (int x = 0; x < width; x++) {
					final int idx = row + x;
					if (slice[idx] != -1) continue;
					int count = 0;
					double voxelMaxR = 0;
					for (int kk = 0; kk < SUPER; kk++) {
						final double wz = (z - zo + (kk + 0.5) / SUPER) * axialRes;
						for (int jj = 0; jj < SUPER; jj++) {
							final double wy = (y - yo + (jj + 0.5) / SUPER) * lateralRes;
							for (int ii = 0; ii < SUPER; ii++) {
								final double wx = (x - xo + (ii + 0.5) / SUPER) * lateralRes;
								if (doModulation) {
									final double r = grid.maxRadiusAt(wx, wy, wz);
									if (r >= 0) {
										count++;
										if (r > voxelMaxR) voxelMaxR = r;
									}
								} else {
									if (grid.containsAny(wx, wy, wz)) count++;
								}
							}
						}
					}
					slice[idx] = count;
					if (doModulation) radSlice[idx] = (float) voxelMaxR;
					if (count > localMax) localMax = count;
				}
			}
			sliceMaxDensity[z] = localMax;
		});

		double maxDensity = 0;
		for (final double d : sliceMaxDensity)
			if (d > maxDensity) maxDensity = d;

		// Normalize density to [0, 1]
		final double superCubed = (double) SUPER * SUPER * SUPER;
		if (maxDensity > 0) {
			final float norm = (float) superCubed;
			IntStream.range(0, depth).parallel().forEach(z -> {
				final float[] slice = img[z];
				for (int i = 0; i < slice.length; i++) {
					if (slice[i] > 0) slice[i] /= norm;
				}
			});
		}

		// Thickness modulation (before adding noise)
		if (doModulation && maxDensity > 0) {
			double globalMinR = Double.MAX_VALUE;
			double globalMaxR = -Double.MAX_VALUE;
			for (final Frustum f : frustums) {
				final double lo = Math.min(f.sr, f.er);
				final double hi = Math.max(f.sr, f.er);
				if (lo < globalMinR) globalMinR = lo;
				if (hi > globalMaxR) globalMaxR = hi;
			}
			final double fRange = globalMaxR - globalMinR;
			if (fRange > 0) {
				SNTUtils.log("TreeToRaster: applying thickness modulation ("
						+ thicknessModulation + "), radius range [" + globalMinR + ", " + globalMaxR + "]");
				final double fMinR = globalMinR;
			IntStream.range(0, depth).parallel().forEach(z -> {
					final float[] slice = img[z];
					final float[] radSlice = radImg[z];
					for (int i = 0; i < slice.length; i++) {
						if (slice[i] > 0 && radSlice[i] > 0) {
							final double normR = (radSlice[i] - fMinR) / fRange;
							final double mod = (1 - thicknessModulation)
									+ thicknessModulation * normR;
							slice[i] *= (float) mod;
						}
					}
				});
			}
		}

		// Pass 3 (optional): Poisson shot noise
		if (snr > 0 && maxDensity > 0) {
			SNTUtils.log("TreeToRaster: applying Poisson noise (SNR=" + snr
					+ ", background=" + background + ")");
			// peak from SNR: SNR = (peak - bg)/sqrt(peak)
			final double temp = snr + Math.sqrt(snr * snr + 4 * background);
			final double peak = 0.25 * temp * temp;
			final double scale = peak - background; // density already in [0,1]
			// Note: Poisson sampling uses thread-local RNG implicitly via
			// PoissonDistribution, so parallel should be safe here
			IntStream.range(0, depth).parallel().forEach(z -> {
				final float[] slice = img[z];
				for (int i = 0; i < slice.length; i++) {
					final double mean = scale * slice[i] + background;
					slice[i] = (float) poissonSample(mean);
				}
			});
		}

		// Pass 4 (optional): Gaussian blur
		// TODO: consider replacing with imglib2 FastGauss.convolve() for
		// built-in parallelism and better separable-kernel performance
		if (gaussSigma > 0) {
			SNTUtils.log("TreeToRaster: applying Gaussian blur (sigma=" + gaussSigma + ")");
			final double sigmaXY = gaussSigma / lateralRes; // convert to pixels
			final double sigmaZ = gaussSigma / axialRes;
			applyGaussianBlur3D(img, width, height, depth, sigmaXY, sigmaZ);
		}

		// Build ImagePlus
		final ImageStack stack = new ImageStack(width, height);
		for (int z = 0; z < depth; z++) stack.addSlice("", img[z]);
		final ImagePlus imp = new ImagePlus("Raster " + tree.getLabel(), stack);
		imp.setCalibration(buildCalibration());
		if (snr <= 0)
			imp.setDisplayRange(0, 1);
		else
			imp.resetDisplayRange();
		return imp;
	}

	/**
	 * Rasterizes the tree into a 16-bit label image where each voxel is
	 * assigned the 1-based index of the {@link Path} that owns it (as
	 * ordered by {@link Tree#list()}). Background voxels are 0.
	 * <p>
	 * At intersection sites where multiple paths overlap, the path with
	 * the largest local radius wins (thickest-wins priority), ensuring
	 * that thin branches crossing a thick trunk do not overwrite it.
	 * </p>
	 * <p>
	 * The returned image has the same dimensions and calibration as the
	 * density image produced by {@link #rasterize()}, so the two can be
	 * used as overlays. Noise and blur settings are ignored for label
	 * images.
	 * </p>
	 *
	 * @return a 16-bit {@link ImagePlus} of path labels
	 */
	public ImagePlus rasterizeLabels() {

		final RasterContext ctx = prepareRasterization();
		final int width = ctx.width, height = ctx.height, depth = ctx.depth;
		final int xo = ctx.xo, yo = ctx.yo, zo = ctx.zo;
		final FrustumGrid grid = ctx.grid;
		final float[][] flagMap = ctx.flagMap;

		// For each flagged voxel, supersample and pick the path whose
		// frustum has the largest local radius (thickest-wins)
		final short[][] labels = new short[depth][width * height];

		IntStream.range(0, depth).parallel().forEach(z -> {
			final float[] flags = flagMap[z];
			final short[] labelSlice = labels[z];
			for (int y = 0; y < height; y++) {
				final int row = y * width;
				for (int x = 0; x < width; x++) {
					final int idx = row + x;
					if (flags[idx] != -1) continue;
					double bestR = -1;
					int bestId = 0;
					for (int kk = 0; kk < SUPER; kk++) {
						final double wz = (z - zo + (kk + 0.5) / SUPER) * axialRes;
						for (int jj = 0; jj < SUPER; jj++) {
							final double wy = (y - yo + (jj + 0.5) / SUPER)
									* lateralRes;
							for (int ii = 0; ii < SUPER; ii++) {
								final double wx = (x - xo + (ii + 0.5) / SUPER)
										* lateralRes;
								final Frustum[] cell = grid.cellAt(wx, wy, wz);
								for (final Frustum f : cell) {
									final double r = f.localRadius(wx, wy, wz);
									if (r > bestR) {
										bestR = r;
										bestId = f.pathId;
									}
								}
							}
						}
					}
					labelSlice[idx] = (short) bestId;
				}
			}
		});

		// Build 16-bit ImagePlus
		final ImageStack stack = new ImageStack(width, height);
		for (int z = 0; z < depth; z++)
			stack.addSlice("", new ShortProcessor(width, height, labels[z], null));
		final ImagePlus imp = new ImagePlus("Labels " + tree.getLabel(), stack);
		imp.setCalibration(buildCalibration());
		imp.resetDisplayRange();
		return imp;
	}

	/** Shared state computed once for both {@link #rasterize()} and {@link #rasterizeLabels()}. */
	private static class RasterContext {
		int width, height, depth;
		int xo, yo, zo;
		List<Frustum> frustums;
		FrustumGrid grid;
		float[][] flagMap; // -1 where any frustum overlaps, 0 elsewhere
	}

	private RasterContext prepareRasterization() {
		final double defR = (defaultRadius > 0) ? defaultRadius : lateralRes / 2;
		final double[] bounds = worldBounds(defR);

		final int xm = (int) Math.floor(bounds[0] / lateralRes);
		final int ym = (int) Math.floor(bounds[2] / lateralRes);
		final int zm = (int) Math.floor(bounds[4] / axialRes);
		final int xM = (int) Math.ceil(bounds[1] / lateralRes);
		final int yM = (int) Math.ceil(bounds[3] / lateralRes);
		final int zM = (int) Math.ceil(bounds[5] / axialRes);

		final RasterContext ctx = new RasterContext();
		ctx.width  = (xM - xm) + 2 * BORDER_XY;
		ctx.height = (yM - ym) + 2 * BORDER_XY;
		ctx.depth  = Math.max((zM - zm) + 2 * BORDER_Z, 1);
		ctx.xo = BORDER_XY - xm;
		ctx.yo = BORDER_XY - ym;
		ctx.zo = BORDER_Z  - zm;

		SNTUtils.log("TreeToRaster: allocating " + ctx.width + "x" + ctx.height
				+ "x" + ctx.depth);

		ctx.frustums = buildFrustums(defR);
		ctx.grid = new FrustumGrid(ctx.frustums, bounds, lateralRes, axialRes);

		// Pass 1: coarse voxel map
		ctx.flagMap = new float[ctx.depth][ctx.width * ctx.height];
		for (final Frustum f : ctx.frustums) {
			final int fxm = ctx.xo + (int) Math.floor(f.xMin / lateralRes);
			final int fym = ctx.yo + (int) Math.floor(f.yMin / lateralRes);
			final int fzm = ctx.zo + (int) Math.floor(f.zMin / axialRes);
			final int fxM = ctx.xo + (int) Math.ceil(f.xMax / lateralRes);
			final int fyM = ctx.yo + (int) Math.ceil(f.yMax / lateralRes);
			final int fzM = ctx.zo + (int) Math.ceil(f.zMax / axialRes);
			for (int z = Math.max(fzm, 0); z < Math.min(fzM, ctx.depth); z++) {
				final float[] slice = ctx.flagMap[z];
				for (int y = Math.max(fym, 0); y < Math.min(fyM, ctx.height); y++) {
					final int row = y * ctx.width;
					for (int x = Math.max(fxm, 0); x < Math.min(fxM, ctx.width); x++) {
						slice[row + x] = -1;
					}
				}
			}
		}
		return ctx;
	}

	private Calibration buildCalibration() {
		final Calibration cal = new Calibration();
		cal.pixelWidth = lateralRes;
		cal.pixelHeight = lateralRes;
		cal.pixelDepth = axialRes;
		final String unit = tree.getBoundingBox().getUnit();
		if (unit != null && !unit.isEmpty()) {
			cal.setXUnit(unit);
			cal.setYUnit(unit);
			cal.setZUnit(unit);
		}
		return cal;
	}

	private double[] worldBounds(final double defR) {
		double xMin = Double.MAX_VALUE, xMax = -Double.MAX_VALUE;
		double yMin = Double.MAX_VALUE, yMax = -Double.MAX_VALUE;
		double zMin = Double.MAX_VALUE, zMax = -Double.MAX_VALUE;
		for (final Path p : tree.list()) {
			for (int i = 0; i < p.size(); i++) {
				final double x = p.getNode(i).x;
				final double y = p.getNode(i).y;
				final double z = p.getNode(i).z;
				final double r = (p.hasRadii()) ? p.getNodeRadius(i) : defR;
				if (x - r < xMin) xMin = x - r;
				if (x + r > xMax) xMax = x + r;
				if (y - r < yMin) yMin = y - r;
				if (y + r > yMax) yMax = y + r;
				if (z - r < zMin) zMin = z - r;
				if (z + r > zMax) zMax = z + r;
			}
		}
		return new double[] { xMin, xMax, yMin, yMax, zMin, zMax };
	}

	private List<Frustum> buildFrustums(final double defR) {
		final List<Frustum> frustums = new ArrayList<>();
		final List<Path> paths = tree.list();
		for (int pi = 0; pi < paths.size(); pi++) {
			final Path p = paths.get(pi);
			final int id = pi + 1; // 1-based path ID (0 = background)
			// Bridge frustum: connect parent's branch node to this path's
			// first node so that fork/junction points are contiguous
			final Path parent = p.getParentPath();
			if (parent != null && p.size() > 0) {
				final int bpIdx = p.getBranchPointIndex();
				if (bpIdx >= 0 && bpIdx < parent.size()) {
					final PointInImage bp = parent.getNode(bpIdx);
					final PointInImage n0 = p.getNode(0);
					final double rBp = (parent.hasRadii()) ? parent.getNodeRadius(bpIdx) : defR;
					final double r0 = (p.hasRadii()) ? p.getNodeRadius(0) : defR;
					frustums.add(new Frustum(bp.x, bp.y, bp.z, rBp,
							n0.x, n0.y, n0.z, r0, id));
				}
			}
			for (int i = 0; i < p.size() - 1; i++) {
				final PointInImage n0 = p.getNode(i);
				final PointInImage n1 = p.getNode(i + 1);
				final double r0 = (p.hasRadii()) ? p.getNodeRadius(i) : defR;
				final double r1 = (p.hasRadii()) ? p.getNodeRadius(i + 1) : defR;
				frustums.add(new Frustum(n0.x, n0.y, n0.z, r0,
						n1.x, n1.y, n1.z, r1, id));
			}
			if (p.size() == 1) {
				final PointInImage n = p.getNode(0);
				final double r = (p.hasRadii()) ? p.getNodeRadius(0) : defR;
				frustums.add(new Frustum(n.x, n.y, n.z, r, n.x, n.y, n.z, r, id));
			}
		}
		return frustums;
	}

	/**
	 * Draws a single Poisson sample for the given mean. For very small means
	 * the distribution degenerates, so we clamp.
	 */
	private static double poissonSample(final double mean) {
		if (mean <= 0) return 0;
		// PoissonDistribution throws for mean > ~1e7; for large means
		// the Gaussian approximation N(mean, mean) is excellent
		if (mean > 1e6) {
			return Math.max(0, mean + Math.sqrt(mean)
					* java.util.concurrent.ThreadLocalRandom.current()
							.nextGaussian());
		}
		return new PoissonDistribution(mean).sample();
	}

	/**
	 * Applies separable 3D Gaussian blur using IJ1's GaussianBlur (XY) plus
	 * manual 1D Gaussian along Z.
	 */
	private static void applyGaussianBlur3D(final float[][] img,
			final int width, final int height, final int depth,
			final double sigmaXY, final double sigmaZ) {

		// XY blur per slice (parallel)
		if (sigmaXY > 0) {
			IntStream.range(0, depth).parallel().forEach(z -> {
				final GaussianBlur gb = new GaussianBlur();
				final FloatProcessor fp = new FloatProcessor(width, height, img[z]);
				gb.blurGaussian(fp, sigmaXY, sigmaXY, 0.0002);
			});
		}

		// Z blur: 1D Gaussian convolution per (x,y) column
		if (sigmaZ > 0 && depth > 1) {
			final int kRadius = (int) Math.ceil(3 * sigmaZ);
			final double[] kernel = new double[2 * kRadius + 1];
			double sum = 0;
			for (int k = -kRadius; k <= kRadius; k++) {
				kernel[k + kRadius] = Math.exp(-0.5 * k * k / (sigmaZ * sigmaZ));
				sum += kernel[k + kRadius];
			}
			for (int k = 0; k < kernel.length; k++) kernel[k] /= sum;

			final int pixelsPerSlice = width * height;
			// Parallel over pixel columns
			IntStream.range(0, pixelsPerSlice).parallel().forEach(idx -> {
				final float[] column = new float[depth];
				final float[] result = new float[depth];
				for (int z = 0; z < depth; z++) column[z] = img[z][idx];
				for (int z = 0; z < depth; z++) {
					double val = 0;
					for (int k = -kRadius; k <= kRadius; k++) {
						final int zz = Math.min(Math.max(z + k, 0), depth - 1);
						val += column[zz] * kernel[k + kRadius];
					}
					result[z] = (float) val;
				}
				for (int z = 0; z < depth; z++) img[z][idx] = result[z];
			});
		}
	}

	/**
	 * A uniform 3D grid that bins frustums by their axis-aligned bounding boxes.
	 * For a given world-coordinate query point, only the frustums in the
	 * corresponding cell need to be tested, reducing the per-sub-voxel cost
	 * from O(N) to approximately O(1).
	 */
	private static class FrustumGrid {

		private final Frustum[][] cells; // cells[flatIdx] = frustums in that cell
		private final int gw, gh, gd;
		private final double cellW, cellH, cellD;
		private final double ox, oy, oz; // world-coordinate origin

		FrustumGrid(final List<Frustum> frustums, final double[] worldBounds,
				final double lateralRes, final double axialRes) {
			final double xMin = worldBounds[0], xMax = worldBounds[1];
			final double yMin = worldBounds[2], yMax = worldBounds[3];
			final double zMin = worldBounds[4], zMax = worldBounds[5];

			cellW = lateralRes * GRID_CELL_VOXELS;
			cellH = lateralRes * GRID_CELL_VOXELS;
			cellD = Math.max(axialRes * GRID_CELL_VOXELS, 1e-9);

			ox = xMin;
			oy = yMin;
			oz = zMin;
			gw = Math.max((int) Math.ceil((xMax - xMin) / cellW), 1);
			gh = Math.max((int) Math.ceil((yMax - yMin) / cellH), 1);
			gd = Math.max((int) Math.ceil((zMax - zMin) / cellD), 1);

			// Build temporary lists
			@SuppressWarnings("unchecked")
			final List<Frustum>[] tmp = new ArrayList[gw * gh * gd];
			for (int i = 0; i < tmp.length; i++)
				tmp[i] = new ArrayList<>();

			for (final Frustum f : frustums) {
				final int x0 = clamp((int) ((f.xMin - ox) / cellW), gw);
				final int x1 = clamp((int) ((f.xMax - ox) / cellW), gw);
				final int y0 = clamp((int) ((f.yMin - oy) / cellH), gh);
				final int y1 = clamp((int) ((f.yMax - oy) / cellH), gh);
				final int z0 = clamp((int) ((f.zMin - oz) / cellD), gd);
				final int z1 = clamp((int) ((f.zMax - oz) / cellD), gd);
				for (int z = z0; z <= z1; z++)
					for (int y = y0; y <= y1; y++)
						for (int x = x0; x <= x1; x++)
							tmp[z * gw * gh + y * gw + x].add(f);
			}

			// Convert to arrays for cache-friendly iteration
			cells = new Frustum[tmp.length][];
			for (int i = 0; i < tmp.length; i++)
				cells[i] = tmp[i].toArray(new Frustum[0]);
		}

		private static int clamp(final int v, final int max) {
			return Math.max(0, Math.min(v, max - 1));
		}

		private Frustum[] cellAt(final double wx, final double wy,
				final double wz) {
			final int gx = clamp((int) ((wx - ox) / cellW), gw);
			final int gy = clamp((int) ((wy - oy) / cellH), gh);
			final int gz = clamp((int) ((wz - oz) / cellD), gd);
			return cells[gz * gw * gh + gy * gw + gx];
		}

		/**
		 * Returns {@code true} if the point is inside any frustum in the
		 * corresponding grid cell.
		 */
		boolean containsAny(final double wx, final double wy,
				final double wz) {
			for (final Frustum f : cellAt(wx, wy, wz))
				if (f.contains(wx, wy, wz)) return true;
			return false;
		}

		/**
		 * Returns the maximum local interpolated radius among all frustums
		 * that contain the point, or -1 if the point is outside all frustums.
		 */
		double maxRadiusAt(final double wx, final double wy,
				final double wz) {
			double max = -1;
			for (final Frustum f : cellAt(wx, wy, wz)) {
				final double r = f.localRadius(wx, wy, wz);
				if (r > max) max = r;
			}
			return max;
		}

	}

	/**
	 * A truncated cone (frustum) connecting two nodes with potentially different
	 * radii. The containment test uses projection onto the segment axis with
	 * linearly interpolated radius, matching the SWC2IMG approach.
	 */
	private static class Frustum {
		final double sx, sy, sz, sr;
		final double ex, ey, ez, er;
		final double dx, dy, dz;
		final double len2, len;
		final double xMin, xMax, yMin, yMax, zMin, zMax;
		final int pathId; // 1-based path identifier (0 = unset)

		Frustum(final double sx, final double sy, final double sz,
				final double sr, final double ex, final double ey,
				final double ez, final double er, final int pathId) {
			this.sx = sx; this.sy = sy; this.sz = sz; this.sr = sr;
			this.ex = ex; this.ey = ey; this.ez = ez; this.er = er;
			this.pathId = pathId;
			dx = ex - sx; dy = ey - sy; dz = ez - sz;
			len2 = dx * dx + dy * dy + dz * dz;
			len = Math.sqrt(len2);
			xMin = Math.min(sx - sr, ex - er);
			xMax = Math.max(sx + sr, ex + er);
			yMin = Math.min(sy - sr, ey - er);
			yMax = Math.max(sy + sr, ey + er);
			zMin = Math.min(sz - sr, ez - er);
			zMax = Math.max(sz + sr, ez + er);
		}

		/**
		 * Point-in-frustum test:
		 * <ol>
		 * <li>Project onto the segment axis.</li>
		 * <li>If within the segment, interpolate radius and test perpendicular
		 *     distance.</li>
		 * <li>Otherwise, test against the nearest endpoint sphere.</li>
		 * </ol>
		 */
		boolean contains(final double x, final double y, final double z) {
			final double vx = x - sx;
			final double vy = y - sy;
			final double vz = z - sz;
			final double dot = dx * vx + dy * vy + dz * vz;

			if (dot > 0) {
				if (dot < len2) {
					final double proj = dot / len;
					final double frac = proj / len;
					final double rad = frac * er + (1 - frac) * sr;
					return vx * vx + vy * vy + vz * vz - proj * proj <= rad * rad;
				} else {
					final double ddx = x - ex, ddy = y - ey, ddz = z - ez;
					return ddx * ddx + ddy * ddy + ddz * ddz <= er * er;
				}
			} else {
				return vx * vx + vy * vy + vz * vz <= sr * sr;
			}
		}

		/**
		 * Returns the interpolated local radius at the given point if it lies
		 * inside this frustum, or -1 if the point is outside. Within the
		 * frustum body the radius is linearly interpolated along the segment
		 * axis; at the endpoint caps the corresponding endpoint radius is
		 * returned.
		 */
		double localRadius(final double x, final double y, final double z) {
			final double vx = x - sx;
			final double vy = y - sy;
			final double vz = z - sz;
			final double dot = dx * vx + dy * vy + dz * vz;

			if (dot > 0) {
				if (dot < len2) {
					final double proj = dot / len;
					final double frac = proj / len;
					final double rad = frac * er + (1 - frac) * sr;
					if (vx * vx + vy * vy + vz * vz - proj * proj <= rad * rad)
						return rad;
				} else {
					final double ddx = x - ex, ddy = y - ey, ddz = z - ez;
					if (ddx * ddx + ddy * ddy + ddz * ddz <= er * er)
						return er;
				}
			} else {
				if (vx * vx + vy * vy + vz * vz <= sr * sr)
					return sr;
			}
			return -1;
		}
	}
}
