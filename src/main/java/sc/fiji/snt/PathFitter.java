/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2024 Fiji developers.
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
import ij.ImageStack;
import ij.process.FloatProcessor;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccess;
import net.imglib2.RealRandomAccessible;
import net.imglib2.converter.Converters;
import net.imglib2.converter.RealFloatConverter;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import pal.math.ConjugateDirectionSearch;
import pal.math.MultivariateFunction;
import sc.fiji.snt.gui.cmds.PathFitterCmd;
import sc.fiji.snt.util.ImgUtils;

import java.util.Arrays;
import java.util.concurrent.Callable;

import org.scijava.prefs.PrefService;

/**
 * Class for fitting circular cross-sections around existing nodes of a
 * {@link Path} in order to compute radii (node thickness) and midpoint
 * refinement of existing coordinates.
 * 
 * @author Tiago Ferreira
 * @author Mark Longair
 * @author Cameron Arshadi
 * 
 */
public class PathFitter implements Callable<Path> {

	static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

	/** The default max radius constraining the fit. */
	public static int DEFAULT_MAX_RADIUS = 10;

	/** The default value for the smallest angle (in radians) constraining the fit. */
	public static double DEFAULT_MIN_ANGLE = Math.PI / 2;

	/**
	 * Flag specifying that the computed path should only inherit fitted radii
	 * attributes
	 */
	public static final int RADII = 1;

	/**
	 * Flag specifying that the computed path should only inherit midpoint
	 * refinement of node coordinates
	 */
	public static final int MIDPOINTS = 2;

	/**
	 * Flag specifying that the computed path should inherit both midpoint
	 * refinement of node coordinates and radii
	 */
	public static final int RADII_AND_MIDPOINTS = 4;

	/**
	 * Flag specifying that radii at invalid fit locations should fall back to
	 * {@code Double.NaN}
	 */
	public static final int FALLBACK_NAN = 8;

	/**
	 * Flag specifying that radii at invalid fit locations should fall back to the
	 * minimum voxel separation (voxel size)
	 */
	public static final int FALLBACK_MIN_SEP = 16;

	/**
	 * Flag specifying that radii at invalid fit locations should fall back to the
	 * mode of all possible fits
	 */
	public static final int FALLBACK_MODE = 32;

	private final SNT plugin;
	private RandomAccessibleInterval<? extends RealType<?>> img;
	private Path path;
	private boolean showDetailedFittingResults;
	private int fitterIndex;
	private MultiTaskProgress progress;
	private boolean succeeded;
	private int sideSearch = DEFAULT_MAX_RADIUS;
	private double minAngle = DEFAULT_MIN_ANGLE;
	private int fitScope = RADII_AND_MIDPOINTS;
	private int radiusFallback = FALLBACK_MODE;
	private Path fitted;
	private boolean fitInPlace; // backwards compatibility with v3 and earlier


	/**
	 * Instantiates a new PathFitter.
	 *
	 * @param imp the Image containing the signal to which the fit will be
	 *          performed
	 * @param path the {@link Path} to be fitted
	 */
	public PathFitter(final ImagePlus imp, final Path path) {
		if (path == null)
			throw new IllegalArgumentException("Cannot fit a null path");
		if (path.isFittedVersionOfAnotherPath())
			throw new IllegalArgumentException("Trying to fit an already fitted path");
		setImage(ImgUtils.getCtSlice(imp));
		this.plugin = null;
		this.path = path;
		this.fitterIndex = -1;
		this.progress = null;
		this.showDetailedFittingResults = false;
	}

	/**
	 * Instantiates a new PathFitter. If img has more than two dimensions, the third dimension
	 * is treated as depth.
	 *
	 * @param img the Image containing the signal to which the fit will be
	 *          performed
	 * @param path the {@link Path} to be fitted
	 */
	public PathFitter(final RandomAccessibleInterval<? extends RealType<?>> img, final Path path) {
		if (path == null)
			throw new IllegalArgumentException("Cannot fit a null path");
		if (path.isFittedVersionOfAnotherPath())
			throw new IllegalArgumentException("Trying to fit an already fitted path");
		setImage(img);
		this.plugin = null;
		this.path = path;
		this.fitterIndex = -1;
		this.progress = null;
		this.showDetailedFittingResults = false;
	}

	/**
	 * Instantiates a new PathFitter.
	 *
	 * @param plugin the {@link SNT} instance specifying input
	 *          image. The computation will be performed on the image currently
	 *          loaded by the plugin.
	 * @param path the {@link Path} to be fitted
	 */
	public PathFitter(final SNT plugin, final Path path) {
		if (path == null)
			throw new IllegalArgumentException("Cannot fit a null path");
		if (path.isFittedVersionOfAnotherPath())
			throw new IllegalArgumentException("Trying to fit an already fitted path");
		this.plugin = plugin;
		setImage(plugin.getLoadedData());
		this.path = path;
		this.fitterIndex = -1;
		this.progress = null;
	}

	/**
	 * Sets whether an interactive image of the result should be displayed.
	 *
	 * @param showAnnotatedView If true, an interactive stack (cross-section view)
	 *                          of the fit is displayed. Note that this is probably
	 *                          only useful if SNT's UI is visible and functional.
	 */
	public void setShowAnnotatedView(final boolean showAnnotatedView) {
		showDetailedFittingResults = showAnnotatedView;
	}

	public void setProgressCallback(final int fitterIndex, final MultiTaskProgress progress) {
		this.fitterIndex = fitterIndex;
		this.progress = progress;
	}

	public void readPreferences() {
		final PrefService prefService = SNTUtils.getContext().getService(PrefService.class);
		final String fitTypeString = prefService.get(PathFitterCmd.class, PathFitterCmd.FITCHOICE_KEY);
		fitScope = (fitTypeString== null) ? RADII_AND_MIDPOINTS : Integer.parseInt(fitTypeString);
		final String fallbackTypeString = prefService.get(PathFitterCmd.class, PathFitterCmd.FALLBACKCHOICE_KEY);
		radiusFallback = (fallbackTypeString == null) ? FALLBACK_MODE : Integer.parseInt(fallbackTypeString);
		final String maxRadiuString = prefService.get(PathFitterCmd.class, PathFitterCmd.MAXRADIUS_KEY);
		sideSearch = (maxRadiuString == null) ? DEFAULT_MAX_RADIUS : Integer.parseInt(maxRadiuString);
		final String minAngleString = prefService.get(PathFitterCmd.class, PathFitterCmd.MINANGLE_KEY);
		minAngle = (minAngleString == null) ? DEFAULT_MIN_ANGLE : Double.parseDouble(minAngleString);
		fitInPlace = prefService.getBoolean(PathFitterCmd.class, PathFitterCmd.FITINPLACE_KEY, false);
		if (plugin != null) {
			final boolean secondary = prefService.getBoolean(PathFitterCmd.class, PathFitterCmd.SECLAYER_KEY, false);
			final RandomAccessibleInterval<? extends RealType<?>> img = (secondary && plugin.isSecondaryDataAvailable()) ? plugin.getSecondaryData()
					: plugin.getLoadedData();
			setImage(img);
		}
	}

	public void applySettings(final PathFitter pf) {
		fitScope = pf.fitScope;
		radiusFallback = pf.radiusFallback;
		sideSearch = pf.sideSearch;
		minAngle = pf.minAngle;
		fitInPlace = pf.fitInPlace;
		img = pf.img;
	}

	/**
	 * Takes the signal from the image specified in the constructor to fit
	 * cross-section circles around the nodes of input path. Computation of fit is
	 * confined to the neighborhood specified by {@link #setMaxRadius(int)}.
	 * Note that connectivity of path may need to be rebuilt upon fit.
	 *
	 * @return the reference to the computed result. This Path is automatically
	 *         set as the fitted version of input Path.
	 * @throws IllegalArgumentException If path already has been fitted, and its
	 *           fitted version not nullified
	 * @see #setScope(int)
	 */
	@Override
	public Path call() throws IllegalArgumentException {
		fitCircles();
		if (fitted == null) {
			succeeded = false;
			return null;
		}
		succeeded = true;
		// Common properties have been set using Path#creatPath(),
		path.setFitted(fitted);
		path.setUseFitted(true);
		if (fitInPlace) {
			path.replaceNodesWithFittedVersion();
		}
		return fitted;
	}

	/**
	 * @return the path being fitted
	 */
	public Path getPath() {
		return path;
	}

	/**
	 * Checks whether the fit succeeded.
	 *
	 * @return true if fit was successful, false otherwise
	 */
	public boolean getSucceeded() {
		return succeeded;
	}

	/**
	 * Gets the current max radius (in pixels)
	 *
	 * @return the maximum radius currently being considered, or
	 *         {@link #DEFAULT_MAX_RADIUS} if {@link #setMaxRadius(int)} has not
	 *         been called
	 */
	public int getMaxRadius() {
		return sideSearch;
	}

	/**
	 * Sets the max radius (side search) for constraining the fit.
	 *
	 * @param maxRadius the new maximum radius
	 */
	public void setMaxRadius(final int maxRadius) {
		this.sideSearch = maxRadius;
	}

	/**
	 * Sets the fitting scope.
	 *
	 * @param scope Either {@link #RADII}, {@link #MIDPOINTS}, or
	 *          {@link #RADII_AND_MIDPOINTS}
	 */
	public void setScope(final int scope) {
		if (scope != PathFitter.RADII_AND_MIDPOINTS && scope != PathFitter.RADII &&
			scope != PathFitter.MIDPOINTS)
		{
			throw new IllegalArgumentException(
				" Invalid flag. Only RADII, RADII, or RADII_AND_MIDPOINTS allowed");
		}
		this.fitScope = scope;
	}

	/**
	 * Sets the fallback strategy for radii at locations in which fitting failed
	 *
	 * @param scope Either {@link #FALLBACK_MIN_SEP}, {@link #FALLBACK_MODE}, or
	 *              {@link #FALLBACK_NAN}
	 */
	public void setRadiusFallback(final int fallback) {
		if (fallback != PathFitter.FALLBACK_MODE && fallback != PathFitter.FALLBACK_MIN_SEP &&
				fallback != PathFitter.FALLBACK_NAN)
			{
				throw new IllegalArgumentException(
					" Invalid flag. Only RADII, RADII, or RADII_AND_MIDPOINTS allowed");
			}
		radiusFallback = fallback;
	}


	/**
	 * Sets whether fitting should occur "in place".
	 *
	 * @param replaceNodes If true, the nodes of the input Path will be replaced by
	 *                     those of the fitted result. If false, the fitted result
	 *                     is kept as a separated Path linked to the input as per
	 *                     {@link Path#getFitted()}. Note that in the latter case,
	 *                     some topological operations (e.g., forking) performed on
	 *                     the fitted result may not percolate to the unfitted Path.
	 */
	public void setReplaceNodes(final boolean replaceNodes) {
		fitInPlace = replaceNodes;
	}

	private String getScopeAsString() {
		switch (fitScope) {
			case RADII_AND_MIDPOINTS:
				return "radii and midpoint refinement";
			case RADII:
				return "radii";
			case MIDPOINTS:
				return "midpoint refinement";
			default:
				return "Unrecognized option";
		}
	}

	private <T extends RealType<T>> void fitCircles() {

		SNTUtils.log("Fitting " + path.getName() + ", Scope: " + getScopeAsString() +
			", Max radius: " + sideSearch);
		final boolean fitRadii = (fitScope == PathFitter.RADII_AND_MIDPOINTS ||
			fitScope == PathFitter.RADII);
		final boolean fitPoints = (fitScope == PathFitter.RADII_AND_MIDPOINTS ||
			fitScope == PathFitter.MIDPOINTS);
		final boolean outputRadii = fitRadii || path.hasRadii();
		final int totalPoints = path.size();
		final int pointsEitherSide = 4;

		fitted = path.createPath();
		SNTUtils.log("  Generating cross-section stack (" + totalPoints +
			"slices/nodes)");
		final int width = (int) img.dimension(0);
		final int height = (int) img.dimension(1);
		final int depth = (int) (img.numDimensions() > 2 ? img.dimension(2) : 1);
		final ImageStack stack = new ImageStack(sideSearch, sideSearch);

		// Prepare the interpolated image for when we generate the cross-section stack
		@SuppressWarnings("unchecked")
		final RandomAccessibleInterval<FloatType> floatImage = Converters.convert(
				(RandomAccessibleInterval<T>) img, new RealFloatConverter<>(), new FloatType());
		final RealRandomAccessible<FloatType> interpolant = Views.interpolate(
				Views.extendZero(floatImage), new NLinearInterpolatorFactory<>());
		final RealRandomAccess<FloatType> realRandomAccess = interpolant.realRandomAccess();

		// We assume that the first and the last in the stack are fine;
		final double[] centre_x_positionsUnscaled = new double[totalPoints];
		final double[] centre_y_positionsUnscaled = new double[totalPoints];
		final double[] rs = new double[totalPoints];
		final double[] rsUnscaled = new double[totalPoints];

		final double[] ts_x = new double[totalPoints];
		final double[] ts_y = new double[totalPoints];
		final double[] ts_z = new double[totalPoints];

		final double[] optimized_x = new double[totalPoints];
		final double[] optimized_y = new double[totalPoints];
		final double[] optimized_z = new double[totalPoints];

		final double[] scores = new double[totalPoints];
		final double[] moved = new double[totalPoints];
		final boolean[] valid = new boolean[totalPoints];

		final double scaleInNormalPlane = path.getMinimumSeparation();
		final double[] tangent = new double[3];

		if (progress != null) progress.updateProgress(0d, fitterIndex);

		final double[] startValues = new double[3];
		startValues[0] = sideSearch / 2.0;
		startValues[1] = sideSearch / 2.0;
		startValues[2] = 3;

		SNTUtils.log("  Searches starting at: " + startValues[0] + "," + startValues[1] +
			" radius: " + startValues[2]);

		for (int i = 0; i < totalPoints; ++i) {

			SNTUtils.log("  Node " + i + ". Computing tangents...");

			path.getTangent(i, pointsEitherSide, tangent);

			final double x_world = path.precise_x_positions[i];
			final double y_world = path.precise_y_positions[i];
			final double z_world = path.precise_z_positions[i];

			final double[] x_basis_in_plane = new double[3];
			final double[] y_basis_in_plane = new double[3];

			final float[] normalPlane = squareNormalToVector(sideSearch,
				scaleInNormalPlane, // This is in the same units as
				// the _spacing, etc. variables.
				x_world, y_world, z_world, // These are scaled now
				tangent[0], tangent[1], tangent[2], //
				x_basis_in_plane, y_basis_in_plane, realRandomAccess);

			// Now at this stage, try to optimize a circle in there...

			// NB these aren't normalized
			ts_x[i] = tangent[0];
			ts_y[i] = tangent[1];
			ts_z[i] = tangent[2];

			final ConjugateDirectionSearch optimizer = new ConjugateDirectionSearch();
//			if (SNT.isDebugMode()) optimizer.prin = 1; // debugging level
			optimizer.step = sideSearch / 4.0;

			float minValueInSquare = Float.MAX_VALUE;
			float maxValueInSquare = Float.MIN_VALUE;
			for (int j = 0; j < (sideSearch * sideSearch); ++j) {
				final float value = normalPlane[j];
				maxValueInSquare = Math.max(value, maxValueInSquare);
				minValueInSquare = Math.min(value, minValueInSquare);
			}

			final CircleAttempt attempt = new CircleAttempt(startValues, normalPlane,
				minValueInSquare, maxValueInSquare, sideSearch);

			try {
				optimizer.optimize(attempt, startValues, 2, 2);
			}
			catch (final ConjugateDirectionSearch.OptimizationError e) {
				SNTUtils.log("  Failure :" + e.getMessage());
				fitted = null;
				return;
			}

			centre_x_positionsUnscaled[i] = startValues[0];
			centre_y_positionsUnscaled[i] = startValues[1];
			rsUnscaled[i] = startValues[2];
			rs[i] = scaleInNormalPlane * rsUnscaled[i];

			scores[i] = attempt.min;

			// Now we calculate the real co-ordinates of the new centre:

			final double x_from_centre_in_plane = startValues[0] - (sideSearch / 2.0);
			final double y_from_centre_in_plane = startValues[1] - (sideSearch / 2.0);

			moved[i] = scaleInNormalPlane * Math.sqrt(x_from_centre_in_plane *
				x_from_centre_in_plane + y_from_centre_in_plane *
					y_from_centre_in_plane);

			// SNT.log("Vector to new centre from original: " + x_from_centre_in_plane
			// + "," + y_from_centre_in_plane);

			double centre_real_x = x_world;
			double centre_real_y = y_world;
			double centre_real_z = z_world;

			SNTUtils.log("    Original coordinates: " + centre_real_x + "," +
				centre_real_y + "," + centre_real_z);

			// FIXME: I really think these should be +=, but it seems clear from
			// the results that I've got a sign wrong somewhere :(

			centre_real_x -= x_basis_in_plane[0] * x_from_centre_in_plane +
				y_basis_in_plane[0] * y_from_centre_in_plane;
			centre_real_y -= x_basis_in_plane[1] * x_from_centre_in_plane +
				y_basis_in_plane[1] * y_from_centre_in_plane;
			if (depth > 1)
				centre_real_z -= x_basis_in_plane[2] * x_from_centre_in_plane +
					y_basis_in_plane[2] * y_from_centre_in_plane;

			SNTUtils.log("    Adjusted coordinates: " + centre_real_x + "," +
				centre_real_y + "," + centre_real_z);

			optimized_x[i] = centre_real_x;
			optimized_y[i] = centre_real_y;
			optimized_z[i] = centre_real_z;

			if (progress != null) progress.updateProgress(((double) i + 1) /
				totalPoints, fitterIndex);

			if (!fitRadii && !showDetailedFittingResults) continue;

			int x_in_image = (int) Math.round(centre_real_x / path.x_spacing);
			int y_in_image = (int) Math.round(centre_real_y / path.y_spacing);
			int z_in_image = (int) Math.round(centre_real_z / path.z_spacing);

//			SNT.log("  Adjusted center image position: " + x_in_image + "," + y_in_image + "," + z_in_image);

			if (x_in_image < 0) x_in_image = 0;
			if (x_in_image >= width) x_in_image = width - 1;
			if (y_in_image < 0) y_in_image = 0;
			if (y_in_image >= height) y_in_image = height - 1;
			if (z_in_image < 0) z_in_image = 0;
			if (z_in_image >= depth) z_in_image = depth - 1;

			// SNT.log("Adding a real slice.");
			final FloatProcessor bp = new FloatProcessor(sideSearch, sideSearch);
			bp.setPixels(normalPlane);
			stack.addSlice("Node " + (i + 1), bp);

		}

		/*
		 * Now at each point along the path we calculate the mode of the radii in the
		 * nearby region:
		 */
		final int modeEitherSide = 4;
		final double[] modeRadiiUnscaled = new double[totalPoints];
		final double[] modeRadii = new double[totalPoints];
		final double[] valuesForMode = new double[modeEitherSide * 2 + 1];

		for (int i = 0; i < totalPoints; ++i) {
			final int minIndex = i - modeEitherSide;
			final int maxIndex = i + modeEitherSide;
			int c = 0;
			for (int modeIndex = minIndex; modeIndex <= maxIndex; ++modeIndex) {
				if (modeIndex < 0) valuesForMode[c] = Double.MIN_VALUE;
				else if (modeIndex >= totalPoints) valuesForMode[c] = Double.MAX_VALUE;
				else {
					if (rsUnscaled[modeIndex] < 1) valuesForMode[c] = 1;
					else valuesForMode[c] = rsUnscaled[modeIndex];
				}
				++c;
			}
			Arrays.sort(valuesForMode);
			modeRadiiUnscaled[i] = valuesForMode[modeEitherSide];
			modeRadii[i] = scaleInNormalPlane * modeRadiiUnscaled[i];
			valid[i] = moved[i] < modeRadiiUnscaled[i];
		}

		// Calculate the angle between the vectors from the point to the one on
		// either side:
		final double[] angles = new double[totalPoints];
		// Set the end points to 180 degrees:
		angles[0] = angles[totalPoints - 1] = Math.PI;
		for (int i = 1; i < totalPoints - 1; ++i) {
			// If there's no previously valid one then
			// just use the first:
			int previousValid = 0;
			for (int j = 0; j < i; ++j)
				if (valid[j]) previousValid = j;
			// If there's no next valid one then just use
			// the first:
			int nextValid = totalPoints - 1;
			for (int j = totalPoints - 1; j > i; --j)
				if (valid[j]) nextValid = j;
			final double adiffx = optimized_x[previousValid] - optimized_x[i];
			final double adiffy = optimized_y[previousValid] - optimized_y[i];
			final double adiffz = optimized_z[previousValid] - optimized_z[i];
			final double bdiffx = optimized_x[nextValid] - optimized_x[i];
			final double bdiffy = optimized_y[nextValid] - optimized_y[i];
			final double bdiffz = optimized_z[nextValid] - optimized_z[i];
			final double adotb = adiffx * bdiffx + adiffy * bdiffy + adiffz * bdiffz;
			final double asize = Math.sqrt(adiffx * adiffx + adiffy * adiffy +
				adiffz * adiffz);
			final double bsize = Math.sqrt(bdiffx * bdiffx + bdiffy * bdiffy +
				bdiffz * bdiffz);
			angles[i] = Math.acos(adotb / (asize * bsize));
			if (angles[i] < minAngle) valid[i] = false;
		}

		/*
		 * Repeatedly build an array indicating how many other valid circles each one
		 * overlaps with, and remove the worst culprits on each run until they're all
		 * gone... This is horrendously inefficient (O(n^3) in the worst case) but I'm
		 * more sure of its correctness than other things I've tried, and there should
		 * be few overlapping circles.
		 */
		final int[] overlapsWith = new int[totalPoints];
		boolean someStillOverlap = true;
		while (someStillOverlap) {
			someStillOverlap = false;
			int maximumNumberOfOverlaps = -1;
			for (int i = 0; i < totalPoints; ++i) {
				overlapsWith[i] = 0;
				if (!valid[i]) continue;
				for (int j = 0; j < totalPoints; ++j) {
					if (!valid[j]) continue;
					if (i == j) continue;
					if (circlesOverlap(ts_x[i], ts_y[i], ts_z[i], optimized_x[i],
						optimized_y[i], optimized_z[i], rs[i], ts_x[j], ts_y[j], ts_z[j],
						optimized_x[j], optimized_y[j], optimized_z[j], rs[j]))
					{
						++overlapsWith[i];
						someStillOverlap = true;
					}
				}
				if (overlapsWith[i] > maximumNumberOfOverlaps) maximumNumberOfOverlaps =
					overlapsWith[i];
			}
			if (maximumNumberOfOverlaps <= 0) {
				break;
			}
			// Now we've built the array, go through and
			// remove the worst offenders:
			for (int i = 0; i < totalPoints; ++i) {
				if (!valid[i]) continue;
				int n = totalPoints;
				for (int j = totalPoints - 1; j > i; --j)
					if (valid[j]) n = j;
				if (overlapsWith[i] == maximumNumberOfOverlaps) {
					// If the next valid one has the same number, and that
					// has a larger radius, remove that one instead...
					if (n < totalPoints && overlapsWith[n] == maximumNumberOfOverlaps &&
						rs[n] > rs[i])
					{
						valid[n] = false;
					}
					else {
						valid[i] = false;
					}
					break;
				}
			}
		}

		int lastValidIndex = 0;
		int fittedPoints = 0;
		for (int i = 0; i < totalPoints; ++i) {

			final boolean firstOrLast = (i == 0 || i == (totalPoints - 1));

			if (!valid[i]) {
				// Then if we're gone too far without a
				// successfully optimized datapoint,
				// add the original one:
				final boolean goneTooFar = i -
					lastValidIndex >= Path.noMoreThanOneEvery;
				boolean nextValid = false;
				if (i < (totalPoints - 1) &&  (valid[i + 1])) nextValid = true;

				if ((goneTooFar && !nextValid) || firstOrLast) {
					valid[i] = true;
					optimized_x[i] = path.precise_x_positions[i];
					optimized_y[i] = path.precise_y_positions[i];
					optimized_z[i] = path.precise_z_positions[i];
					rsUnscaled[i] = 1;
					rs[i] = scaleInNormalPlane;
					modeRadiiUnscaled[i] = 1;
					modeRadii[i] = scaleInNormalPlane;
					centre_x_positionsUnscaled[i] = sideSearch / 2.0;
					centre_y_positionsUnscaled[i] = sideSearch / 2.0;
				}
			}

			if (valid[i]) {
				if (rs[i] < scaleInNormalPlane) {
					rsUnscaled[i] = 1;
					rs[i] = scaleInNormalPlane;
				}
				// NB: We'll add the points to the path in bulk later on
				fittedPoints++;
				lastValidIndex = i;
			}
		}

		final double[] fitted_ts_x = (outputRadii) ? new double[fittedPoints]
			: null;
		final double[] fitted_ts_y = (outputRadii) ? new double[fittedPoints]
			: null;
		final double[] fitted_ts_z = (outputRadii) ? new double[fittedPoints]
			: null;
		final double[] fitted_rs = (outputRadii) ? new double[fittedPoints] : null;
		final double[] fitted_optimized_x = new double[fittedPoints];
		final double[] fitted_optimized_y = new double[fittedPoints];
		final double[] fitted_optimized_z = new double[fittedPoints];

		int added = 0;
		for (int i = 0; i < totalPoints; ++i) {
			if (!valid[i]) continue;
			fitted_optimized_x[added] = (fitPoints) ? optimized_x[i]
				: path.precise_x_positions[i];
			fitted_optimized_y[added] = (fitPoints) ? optimized_y[i]
				: path.precise_y_positions[i];
			fitted_optimized_z[added] = (fitPoints) ? optimized_z[i]
				: path.precise_z_positions[i];
			if (outputRadii) {
				fitted_ts_x[added] = (fitRadii) ? ts_x[i] : path.tangents_x[i];
				fitted_ts_y[added] = (fitRadii) ? ts_y[i] : path.tangents_y[i];
				fitted_ts_z[added] = (fitRadii) ? ts_z[i] : path.tangents_z[i];
				fitted_rs[added] = (fitRadii) ? rs[i] : path.radii[i];
			}
			++added;
		}

		if (outputRadii) {
			switch (radiusFallback) {
			case FALLBACK_MODE:
				for (int i = 0; i < fitted_rs.length; i++) {
					if (!valid[i])
						fitted_rs[i] = modeRadiiUnscaled[i] * scaleInNormalPlane;
				}
				break;
			case FALLBACK_NAN:
				for (int i = 0; i < fitted_rs.length; i++) {
					if (!valid[i])
						fitted_rs[i] = Double.NaN;
				}
				break;
			default:
				for (int i = 0; i < fitted_rs.length; i++) {
					if (!valid[i])
						fitted_rs[i] = scaleInNormalPlane;
				}
				break;
			}
		}

		fitted.setFittedCircles(fittedPoints, fitted_ts_x, fitted_ts_y, fitted_ts_z, fitted_rs, //
				fitted_optimized_x, fitted_optimized_y, fitted_optimized_z);

		SNTUtils.log("Done. With " + fittedPoints + "/" + totalPoints +
			" accepted fits");
		if (showDetailedFittingResults) {
			SNTUtils.log("Generating annotated cross view stack");
			final ImagePlus imp = new ImagePlus("Cross-section View " + fitted.getName(), stack);
			imp.setCalibration(path.getCalibration());
			imp.getCalibration().pixelWidth = path.getMinimumSeparation();
			imp.getCalibration().pixelHeight = path.getMinimumSeparation();
			imp.getCalibration().pixelDepth = path.getMinimumSeparation();
			if (plugin == null) {
				imp.show();
			} else {
				final NormalPlaneCanvas normalCanvas = new NormalPlaneCanvas(imp,
					plugin, centre_x_positionsUnscaled, centre_y_positionsUnscaled,
					rsUnscaled, scores, modeRadiiUnscaled, angles, valid, fitted);
				switch (radiusFallback) {
				case FALLBACK_MODE:
					normalCanvas.setInvalidFitLabel("Invalid fit, r reset: mode");
					break;
				case FALLBACK_NAN:
					normalCanvas.setInvalidFitLabel("Invalid fit, r reset: NaN");
					break;
				case FALLBACK_MIN_SEP:
					normalCanvas.setInvalidFitLabel("Invalid fit, r reset: min sep.");
					break;
				default:
					break;
				}
				normalCanvas.showImage();
			}
		}

	}

	private boolean circlesOverlap(final double n1x, final double n1y,
		final double n1z, final double c1x, final double c1y, final double c1z,
		final double radius1, final double n2x, final double n2y, final double n2z,
		final double c2x, final double c2y, final double c2z, final double radius2)
		throws IllegalArgumentException
	{
		/*
		 * Roughly following the steps described here:
		 * http://local.wasp.uwa.edu.au/~pbourke/geometry/planeplane/
		 */
		final double epsilon = 0.000001;
		/*
		 * Take the cross product of n1 and n2 to see if they are colinear, in which
		 * case there is overlap:
		 */
		final double crossx = n1y * n2z - n1z * n2y;
		final double crossy = n1z * n2x - n1x * n2z;
		final double crossz = n1x * n2y - n1y * n2x;
		if (Math.abs(crossx) < epsilon && Math.abs(crossy) < epsilon && Math.abs(
			crossz) < epsilon)
		{
			// Then they don't overlap unless they're in
			// the same plane:
			final double cdiffx = c2x - c1x;
			final double cdiffy = c2y - c1y;
			final double cdiffz = c2z - c1z;
			final double cdiffdotn1 = cdiffx * n1x + cdiffy * n1y + cdiffz * n1z;
			return Math.abs(cdiffdotn1) < epsilon;
		}
		final double n1dotn1 = n1x * n1x + n1y * n1y + n1z * n1z;
		final double n2dotn2 = n2x * n2x + n2y * n2y + n2z * n2z;
		final double n1dotn2 = n1x * n2x + n1y * n2y + n1z * n2z;

		final double det = n1dotn1 * n2dotn2 - n1dotn2 * n1dotn2;
		if (Math.abs(det) < epsilon) {
			SNTUtils.log("WARNING: det was nearly zero: " + det);
			return true;
		}

		// A vector r in the plane is defined by:
		// n1 . r = (n1 . c1) = d1

		final double d1 = n1x * c1x + n1y * c1y + n1z * c1z;
		final double d2 = n2x * c2x + n2y * c2y + n2z * c2z;

		final double constant1 = (d1 * n2dotn2 - d2 * n1dotn2) / det;
		final double constant2 = (d2 * n1dotn1 - d1 * n1dotn2) / det;

		/*
		 * So points on the line, paramaterized by u are now:
		 *
		 * constant1 n1 + constant2 n2 + u ( n1 x n2 )
		 *
		 * To find if the two circles overlap, we need to find the values of u where
		 * each crosses that line, in other words, for the first circle:
		 *
		 * radius1 = |constant1 n1 + constant2 n2 + u ( n1 x n2 ) - c1|
		 *
		 * => 0 = [ (constant1 n1 + constant2 n2 - c1).(constant1 n1 + constant2 n2 -
		 * c1) - radius1 ^ 2 ] + [ 2 * ( n1 x n2 ) . ( constant1 n1 + constant2 n2 - c1
		 * ) ] * u [ ( n1 x n2 ) . ( n1 x n2 ) ] * u^2 ]
		 *
		 * So we solve that quadratic:
		 *
		 */
		final double a1 = crossx * crossx + crossy * crossy + crossz * crossz;
		final double b1 = 2 * (crossx * (constant1 * n1x + constant2 * n2x - c1x) +
			crossy * (constant1 * n1y + constant2 * n2y - c1y) + crossz * (constant1 *
				n1z + constant2 * n2z - c1z));
		final double c1 = (constant1 * n1x + constant2 * n2x - c1x) * (constant1 *
			n1x + constant2 * n2x - c1x) + (constant1 * n1y + constant2 * n2y - c1y) *
				(constant1 * n1y + constant2 * n2y - c1y) + (constant1 * n1z +
					constant2 * n2z - c1z) * (constant1 * n1z + constant2 * n2z - c1z) -
			radius1 * radius1;

		final double a2 = a1;
		final double b2 = 2 * (crossx * (constant1 * n1x + constant2 * n2x - c2x) +
			crossy * (constant1 * n1y + constant2 * n2y - c2y) + crossz * (constant1 *
				n1z + constant2 * n2z - c2z));
		final double c2 = (constant1 * n1x + constant2 * n2x - c2x) * (constant1 *
			n1x + constant2 * n2x - c2x) + (constant1 * n1y + constant2 * n2y - c2y) *
				(constant1 * n1y + constant2 * n2y - c2y) + (constant1 * n1z +
					constant2 * n2z - c2z) * (constant1 * n1z + constant2 * n2z - c2z) -
			radius2 * radius2;

		// So now calculate the discriminants:
		final double discriminant1 = b1 * b1 - 4 * a1 * c1;
		final double discriminant2 = b2 * b2 - 4 * a2 * c2;

		if (discriminant1 < 0 || discriminant2 < 0) {
			// Then one of the circles doesn't even reach the line:
			return false;
		}

		if (Math.abs(a1) < epsilon) {
			SNTUtils.warn("CirclesOverlap: a1 was nearly zero: " + a1);
			return true;
		}

		final double u1_1 = Math.sqrt(discriminant1) / (2 * a1) - b1 / (2 * a1);
		final double u1_2 = -Math.sqrt(discriminant1) / (2 * a1) - b1 / (2 * a1);

		final double u2_1 = Math.sqrt(discriminant2) / (2 * a2) - b2 / (2 * a2);
		final double u2_2 = -Math.sqrt(discriminant2) / (2 * a2) - b2 / (2 * a2);

		final double u1_smaller = Math.min(u1_1, u1_2);
		final double u1_larger = Math.max(u1_1, u1_2);

		final double u2_smaller = Math.min(u2_1, u2_2);
		final double u2_larger = Math.max(u2_1, u2_2);

		// Non-overlapping cases:
		if (u1_larger < u2_smaller) return false;
		if (u2_larger < u1_smaller) return false;

		// Totally overlapping cases:
		if (u1_smaller <= u2_smaller && u2_larger <= u1_larger) return true;
		if (u2_smaller <= u1_smaller && u1_larger <= u2_larger) return true;

		// Partially overlapping cases:
		if (u1_smaller <= u2_smaller && u2_smaller <= u1_larger &&
			u1_larger <= u2_larger) return true;
		if (u2_smaller <= u1_smaller && u1_smaller <= u2_larger &&
			u2_larger <= u1_larger) return true;

		/*
		 * We only reach here if something has gone badly wrong, so dump helpful values
		 * to aid in debugging:
		 */
		SNTUtils.log("CirclesOverlap seems to have failed: Current settings");
		SNTUtils.log("det: " + det);
		SNTUtils.log("discriminant1: " + discriminant1);
		SNTUtils.log("discriminant2: " + discriminant2);
		SNTUtils.log("n1: (" + n1x + "," + n1y + "," + n1z + ")");
		SNTUtils.log("n2: (" + n2x + "," + n2y + "," + n2z + ")");
		SNTUtils.log("c1: (" + c1x + "," + c1y + "," + c1z + ")");
		SNTUtils.log("c2: (" + c2x + "," + c2y + "," + c2z + ")");
		SNTUtils.log("radius1: " + radius1);
		SNTUtils.log("radius2: " + radius2);

		throw new IllegalArgumentException("Some overlapping case missed: " +
			"u1_smaller=" + u1_smaller + "u1_larger=" + u1_larger + "u2_smaller=" +
			u2_smaller + "u2_larger=" + u2_larger);
	}

	private float[] squareNormalToVector(final int side, // The number of samples
																 // in x and y in the
																 // plane, separated by
																 // step
																 final double step,
																 // step is in the same units as the _spacing,
																 // etc. variables.
																 final double ox, /* These are scaled now */
																 final double oy, final double oz, final double nx,
																 final double ny, final double nz,
																 final double[] x_basis_vector, /* The basis vectors are returned here */
																 final double[] y_basis_vector, /* they *are* scaled by _spacing */
																 final RealRandomAccess<FloatType> realRandomAccess) /* This should be from an interpolated image */
	{

		final float[] result = new float[side * side];

		final double epsilon = 1e-6;

		// If the image is 2D, use the XY plane as our basis by default
		double ax = 1;
		double ay = 0;
		double az = 0;

		double bx = 0;
		double by = 1;
		double bz = 0;

		final int nDim = realRandomAccess.numDimensions();

		if (nDim > 2) {

			/*
			 * To find an arbitrary vector in the normal plane, do the cross product with
			 * (0,0,1), unless the normal is parallel to that, in which case we cross it
			 * with (0,1,0) instead...
			 */

			if (Math.abs(nx) < epsilon && Math.abs(ny) < epsilon) {
				// Cross with (0,1,0):
				ax = nz;
				ay = 0;
				az = -nx;
			} else {
				// Cross with (0,0,1):
				ax = -ny;
				ay = nx;
				az = 0;
			}

			/*
			 * Now to find the other vector in that plane, do the cross product of
			 * (ax,ay,az) with (nx,ny,nz)
			 */

			bx = ay * nz - az * ny;
			by = az * nx - ax * nz;
			bz = ax * ny - ay * nx;

			/* Normalize a and b */

			final double a_size = Math.sqrt(ax * ax + ay * ay + az * az);
			ax = ax / a_size;
			ay = ay / a_size;
			az = az / a_size;

			final double b_size = Math.sqrt(bx * bx + by * by + bz * bz);
			bx = bx / b_size;
			by = by / b_size;
			bz = bz / b_size;

		}

		/* Scale them with spacing... */

		final double ax_s = ax * step;
		final double ay_s = ay * step;
		final double az_s = az * step;

		final double bx_s = bx * step;
		final double by_s = by * step;
		final double bz_s = bz * step;

//		SNT.log("a (in normal plane) is " + ax + "," + ay + "," + az);
//		SNT.log("b (in normal plane) is " + bx + "," + by + "," + bz);

//		// a and b must be perpendicular:
//		final double a_dot_b = ax * bx + ay * by + az * bz;

//		// ... and each must be perpendicular to the normal
//		final double a_dot_n = ax * nx + ay * ny + az * nz;
//		final double b_dot_n = bx * nx + by * ny + bz * nz;

//		SNT.log("a_dot_b: " + a_dot_b);
//		SNT.log("a_dot_n: " + a_dot_n);
//		SNT.log("b_dot_n: " + b_dot_n);

		final double[] position = new double[nDim];

		for (int grid_i = 0; grid_i < side; ++grid_i) {
			for (int grid_j = 0; grid_j < side; ++grid_j) {

				final double midside_grid = ((side - 1) / 2.0f);

				final double gi = midside_grid - grid_i;
				final double gj = midside_grid - grid_j;

				// So now denormalize to pixel co-ordinates:

				position[0] = (ox + gi * ax_s + gj * bx_s) / path.x_spacing;
				position[1] = (oy + gi * ay_s + gj * by_s) / path.y_spacing;
				if (nDim > 2)
					position[2] = (oz + gi * az_s + gj * bz_s) / path.z_spacing;

				/*
				 * And do n-linear interpolation to find the value there:
				 */

				result[grid_j * side + grid_i] = realRandomAccess.setPositionAndGet(position).getRealFloat();
			}
		}

		x_basis_vector[0] = ax_s;
		x_basis_vector[1] = ay_s;
		x_basis_vector[2] = az_s;

		y_basis_vector[0] = bx_s;
		y_basis_vector[1] = by_s;
		y_basis_vector[2] = bz_s;

		return result;
	}

	/**
	 * Sets the target image
	 *
	 * @param img the Image containing the signal to which the fit will be
	 *          performed
	 */
	public void setImage(final RandomAccessibleInterval<? extends RealType<?>> img) {
		if (img == null) throw new IllegalArgumentException(
				"Cannot fit a null image");
		this.img = img;
	}

	private static class CircleAttempt implements MultivariateFunction,
		Comparable<CircleAttempt>
	{

		double min;
		float[] data;
		float minValueInData;
		float maxValueInData;
		int side;

		public CircleAttempt(final double[] start, final float[] data,
			final float minValueInData, final float maxValueInData, final int side)
		{

			this.data = data;
			this.minValueInData = minValueInData;
			this.maxValueInData = maxValueInData;
			this.side = side;

			min = Double.MAX_VALUE;
		}

		@Override
		public int compareTo(final CircleAttempt o) {
			return Double.compare(min, o.min);
		}

		@Override
		public int getNumArguments() {
			return 3;
		}

		@Override
		public double getLowerBound(final int n) {
			return 0;
		}

		@Override
		public double getUpperBound(final int n) {
			return side;
		}

		@Override
		public double evaluate(final double[] x) {
			final double badness = evaluateCircle(x[0], x[1], x[2]);

			if (badness < min) {
				x.clone();
				min = badness;
			}

			return badness;
		}

		public double evaluateCircle(final double x, final double y,
			final double r)
		{

			final double maximumPointPenalty = (maxValueInData - minValueInData) *
				(maxValueInData - minValueInData);

			double badness = 0;

			for (int i = 0; i < side; ++i) {
				for (int j = 0; j < side; ++j) {
					final float value = data[j * side + i];
					if (r * r > ((i - x) * (i - x) + (j - y) * (j - y))) badness +=
						(maxValueInData - value) * (maxValueInData - value);
					else badness += (value - minValueInData) * (value - minValueInData);
				}
			}

			for (double ic = (x - r); ic <= (x + r); ++ic) {
				for (double jc = (y - r); jc <= (y + r); ++jc) {
					if (ic < 0 || ic > side || jc < 0 || jc > side) badness +=
						maximumPointPenalty;
				}
			}

			badness /= (side * side);

			return badness;
		}

	}

}
