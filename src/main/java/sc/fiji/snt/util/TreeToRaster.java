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

	private final Tree tree;
	private double lateralRes; // spatial units per voxel (x, y)
	private double axialRes;   // spatial units per voxel (z)
	private double defaultRadius = -1; // <0 means use lateralRes/2

	// Noise parameters (disabled by default)
	private double background = 0;
	private double snr = 0;        // <=0 means no shot noise
	private double gaussSigma = 0; // <=0 means no blur

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
	 * photon-counting model: {@code SNR = (peak - bg) / sqrt(peak)}.
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
	 * spatially correlated noise and optical blur.
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

		// Effective default radius
		final double defR = (defaultRadius > 0) ? defaultRadius : lateralRes / 2;

		// Compute world-coordinate bounds (including radii)
		final double[] bounds = worldBounds(defR);
		final double xMin = bounds[0], xMax = bounds[1];
		final double yMin = bounds[2], yMax = bounds[3];
		final double zMin = bounds[4], zMax = bounds[5];

		// Image dimensions in voxels
		final int xm = (int) Math.floor(xMin / lateralRes);
		final int ym = (int) Math.floor(yMin / lateralRes);
		final int zm = (int) Math.floor(zMin / axialRes);
		final int xM = (int) Math.ceil(xMax / lateralRes);
		final int yM = (int) Math.ceil(yMax / lateralRes);
		final int zM = (int) Math.ceil(zMax / axialRes);

		final int width  = (xM - xm) + 2 * BORDER_XY;
		final int height = (yM - ym) + 2 * BORDER_XY;
		final int depth  = Math.max((zM - zm) + 2 * BORDER_Z, 1);

		// Offset: world(0,0,0) -> voxel(xo,yo,zo)
		final int xo = BORDER_XY - xm;
		final int yo = BORDER_XY - ym;
		final int zo = BORDER_Z  - zm;

		SNTUtils.log("TreeToRaster: allocating " + width + "x" + height + "x"
				+ depth + " (float)");

		// Build segment list
		final java.util.List<Frustum> frustums = buildFrustums(defR);

		// --- Pass 1: coarse voxel map (flag voxels overlapping any frustum) ---
		final float[][] img = new float[depth][width * height];
		for (final Frustum f : frustums) {
			final int fxm = xo + (int) Math.floor(f.xMin / lateralRes);
			final int fym = yo + (int) Math.floor(f.yMin / lateralRes);
			final int fzm = zo + (int) Math.floor(f.zMin / axialRes);
			final int fxM = xo + (int) Math.ceil(f.xMax / lateralRes);
			final int fyM = yo + (int) Math.ceil(f.yMax / lateralRes);
			final int fzM = zo + (int) Math.ceil(f.zMax / axialRes);
			for (int z = Math.max(fzm, 0); z < Math.min(fzM, depth); z++) {
				final float[] slice = img[z];
				for (int y = Math.max(fym, 0); y < Math.min(fyM, height); y++) {
					final int row = y * width;
					for (int x = Math.max(fxm, 0); x < Math.min(fxM, width); x++) {
						slice[row + x] = -1; // flag for supersampling
					}
				}
			}
		}

		// --- Pass 2: partial-volume supersampling on flagged voxels ---
		final double superCubed = (double) SUPER * SUPER * SUPER;
		double maxDensity = 0;
		for (int z = 0; z < depth; z++) {
			final float[] slice = img[z];
			for (int y = 0; y < height; y++) {
				final int row = y * width;
				for (int x = 0; x < width; x++) {
					final int idx = row + x;
					if (slice[idx] != -1) continue;
					int count = 0;
					for (int kk = 0; kk < SUPER; kk++) {
						final double wz = (z - zo + (kk + 0.5) / SUPER) * axialRes;
						for (int jj = 0; jj < SUPER; jj++) {
							final double wy = (y - yo + (jj + 0.5) / SUPER) * lateralRes;
							for (int ii = 0; ii < SUPER; ii++) {
								final double wx = (x - xo + (ii + 0.5) / SUPER) * lateralRes;
								if (containsAny(frustums, wx, wy, wz)) count++;
							}
						}
					}
					slice[idx] = count;
					if (count > maxDensity) maxDensity = count;
				}
			}
		}

		// --- Pass 3 (optional): Poisson shot noise ---
		if (snr > 0 && maxDensity > 0) {
			SNTUtils.log("TreeToRaster: applying Poisson noise (SNR=" + snr
					+ ", background=" + background + ")");
			// peak from SNR: SNR = (peak - bg)/sqrt(peak)
			final double temp = snr + Math.sqrt(snr * snr + 4 * background);
			final double peak = 0.25 * temp * temp;
			final double scale = (peak - background) / maxDensity;
			for (int z = 0; z < depth; z++) {
				final float[] slice = img[z];
				for (int i = 0; i < slice.length; i++) {
					final double mean = scale * slice[i] + background;
					slice[i] = (float) poissonSample(mean);
				}
			}
		} else if (maxDensity > 0) {
			// Normalize to [0, 1]
			final float norm = (float) superCubed;
			for (int z = 0; z < depth; z++) {
				final float[] slice = img[z];
				for (int i = 0; i < slice.length; i++) {
					if (slice[i] > 0) slice[i] /= norm;
				}
			}
		}

		// --- Pass 4 (optional): Gaussian blur ---
		if (gaussSigma > 0) {
			SNTUtils.log("TreeToRaster: applying Gaussian blur (sigma="
					+ gaussSigma + ")");
			final double sigmaXY = gaussSigma / lateralRes; // convert to pixels
			final double sigmaZ = gaussSigma / axialRes;
			applyGaussianBlur3D(img, width, height, depth, sigmaXY, sigmaZ);
		}

		// Build ImagePlus
		final ImageStack stack = new ImageStack(width, height);
		for (int z = 0; z < depth; z++) stack.addSlice("", img[z]);
		final ImagePlus imp = new ImagePlus("Raster " + tree.getLabel(), stack);
		final Calibration cal = imp.getCalibration();
		cal.pixelWidth = lateralRes;
		cal.pixelHeight = lateralRes;
		cal.pixelDepth = axialRes;
		final String unit = tree.getBoundingBox().getUnit();
		if (unit != null && !unit.isEmpty()) {
			cal.setXUnit(unit);
			cal.setYUnit(unit);
			cal.setZUnit(unit);
		}
		if (snr <= 0)
			imp.setDisplayRange(0, 1);
		else
			imp.resetDisplayRange();
		return imp;
	}

	// -- Internals --

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

	private java.util.List<Frustum> buildFrustums(final double defR) {
		final java.util.List<Frustum> frustums = new java.util.ArrayList<>();
		for (final Path p : tree.list()) {
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
							n0.x, n0.y, n0.z, r0));
				}
			}
			for (int i = 0; i < p.size() - 1; i++) {
				final PointInImage n0 = p.getNode(i);
				final PointInImage n1 = p.getNode(i + 1);
				final double r0 = (p.hasRadii()) ? p.getNodeRadius(i) : defR;
				final double r1 = (p.hasRadii()) ? p.getNodeRadius(i + 1) : defR;
				frustums.add(new Frustum(n0.x, n0.y, n0.z, r0,
						n1.x, n1.y, n1.z, r1));
			}
			if (p.size() == 1) {
				final PointInImage n = p.getNode(0);
				final double r = (p.hasRadii()) ? p.getNodeRadius(0) : defR;
				frustums.add(new Frustum(n.x, n.y, n.z, r, n.x, n.y, n.z, r));
			}
		}
		return frustums;
	}

	private static boolean containsAny(final java.util.List<Frustum> frustums,
			final double x, final double y, final double z) {
		for (final Frustum f : frustums) {
			if (f.contains(x, y, z)) return true;
		}
		return false;
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
					* new java.util.Random().nextGaussian());
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

		// XY blur per slice
		if (sigmaXY > 0) {
			final GaussianBlur gb = new GaussianBlur();
			for (int z = 0; z < depth; z++) {
				final FloatProcessor fp = new FloatProcessor(width, height, img[z]);
				gb.blurGaussian(fp, sigmaXY, sigmaXY, 0.0002);
			}
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

			final float[] column = new float[depth];
			final float[] result = new float[depth];
			final int pixelsPerSlice = width * height;
			for (int idx = 0; idx < pixelsPerSlice; idx++) {
				// Extract column
				for (int z = 0; z < depth; z++) column[z] = img[z][idx];
				// Convolve
				for (int z = 0; z < depth; z++) {
					double val = 0;
					for (int k = -kRadius; k <= kRadius; k++) {
						final int zz = Math.min(Math.max(z + k, 0), depth - 1);
						val += column[zz] * kernel[k + kRadius];
					}
					result[z] = (float) val;
				}
				// Write back
				for (int z = 0; z < depth; z++) img[z][idx] = result[z];
			}
		}
	}

	// -- Frustum (truncated cone) geometry --

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

		Frustum(final double sx, final double sy, final double sz,
				final double sr, final double ex, final double ey,
				final double ez, final double er) {
			this.sx = sx; this.sy = sy; this.sz = sz; this.sr = sr;
			this.ex = ex; this.ey = ey; this.ez = ez; this.er = er;
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
					if (vx * vx + vy * vy + vz * vz - proj * proj <= rad * rad)
						return true;
				} else {
					final double ddx = x - ex, ddy = y - ey, ddz = z - ez;
					if (ddx * ddx + ddy * ddy + ddz * ddz <= er * er)
						return true;
				}
			} else {
				if (vx * vx + vy * vy + vz * vz <= sr * sr)
					return true;
			}
			return false;
		}
	}
}
