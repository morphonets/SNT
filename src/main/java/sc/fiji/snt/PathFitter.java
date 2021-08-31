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

import java.util.Arrays;
import java.util.concurrent.Callable;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;
import pal.math.ConjugateDirectionSearch;
import pal.math.MultivariateFunction;

/**
 * Class for fitting circular cross-sections around existing nodes of a
 * {@link Path} in order to compute radii (node thickness) and midpoint
 * refinement of existing coordinates.
 * 
 * @author Tiago Ferreira
 * @author Mark Longair
 * 
 */
public class PathFitter implements Callable<Path> {

	/** The default max radius constraining the fit. */
	public static int DEFAULT_MAX_RADIUS = 10;

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

	private final SNT plugin;
	private ImagePlus imp;
	private Path path;
	private boolean showDetailedFittingResults;
	private int fitterIndex;
	private MultiTaskProgress progress;
	private boolean succeeded;
	private int sideSearch = DEFAULT_MAX_RADIUS;
	private int fitScope = RADII_AND_MIDPOINTS;
	private Path fitted;


	/**
	 * Checks whether the fit succeeded.
	 *
	 * @return true if fit was successful, false otherwise
	 */
	public boolean getSucceeded() {
		return succeeded;
	}

	/**
	 * Instantiates a new PathFitter.
	 *
	 * @param imp the Image containing the signal to which the fit will be
	 *          performed
	 * @param path the {@link Path} to be fitted
	 */
	public PathFitter(final ImagePlus imp, final Path path) {
		if (path == null) throw new IllegalArgumentException(
			"Cannot fit a null path");
		if (path.isFittedVersionOfAnotherPath()) throw new IllegalArgumentException(
			"Trying to fit an already fitted path");
		setImp(imp);
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
	public PathFitter(final SNT plugin, final Path path)
	{
		if (path == null) throw new IllegalArgumentException(
			"Cannot fit a null path");
		if (path.isFittedVersionOfAnotherPath()) throw new IllegalArgumentException(
			"Trying to fit an already fitted path");
		this.plugin = plugin;
		setImp(plugin.getLoadedDataAsImp());
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

	public void setProgressCallback(final int fitterIndex,
		final MultiTaskProgress progress)
	{
		this.fitterIndex = fitterIndex;
		this.progress = progress;
	}

	/**
	 * Takes the signal from the image specified in the constructor to fit
	 * cross-section circles around the nodes of input path. Computation of fit is
	 * confined to the neighborhood specified by {@link #setMaxRadius(int)}.
	 * Note that connectivity of path may need to be rebuild upon fit.
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
		return fitted;
	}

	public Path getPath() {
		return path;
	}

	/**
	 * Gets the current max radius
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

	private void fitCircles() {

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
		final int width = imp.getWidth();
		final int height = imp.getHeight();
		final int depth = imp.getNSlices();
		final ImageStack stack = new ImageStack(sideSearch, sideSearch);

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

		final int[] xs_in_image = new int[totalPoints];
		final int[] ys_in_image = new int[totalPoints];
		final int[] zs_in_image = new int[totalPoints];

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
				x_basis_in_plane, y_basis_in_plane, imp);

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

			// SNT.log("addingPoint: " + x_in_image + "," + y_in_image + "," +
			// z_in_image);

			xs_in_image[i] = x_in_image;
			ys_in_image[i] = y_in_image;
			zs_in_image[i] = z_in_image;

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
			if (angles[i] < (Math.PI / 2)) valid[i] = false;
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
				// The if we're gone too far without a
				// successfully optimized datapoint,
				// add the original one:
				final boolean goneTooFar = i -
					lastValidIndex >= Path.noMoreThanOneEvery;
				boolean nextValid = false;
				if (i < (totalPoints - 1)) if (valid[i + 1]) nextValid = true;

				if ((goneTooFar && !nextValid) || firstOrLast) {
					valid[i] = true;
					xs_in_image[i] = path.getXUnscaled(i);
					ys_in_image[i] = path.getYUnscaled(i);
					zs_in_image[i] = path.getZUnscaled(i);
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

		fitted.setFittedCircles(fittedPoints, fitted_ts_x, fitted_ts_y, fitted_ts_z, fitted_rs, //
				fitted_optimized_x, fitted_optimized_y, fitted_optimized_z);

		SNTUtils.log("Done. With " + fittedPoints + "/" + totalPoints +
			" accepted fits");
		if (showDetailedFittingResults) {
			SNTUtils.log("Generating annotated cross view stack");
			final ImagePlus imp = new ImagePlus("Cross-section View " + fitted
				.getName(), stack);
			imp.setCalibration(this.imp.getCalibration());
			if (plugin == null) {
				imp.show();
			}
			else {
				final NormalPlaneCanvas normalCanvas = new NormalPlaneCanvas(imp,
					plugin, centre_x_positionsUnscaled, centre_y_positionsUnscaled,
					rsUnscaled, scores, modeRadiiUnscaled, angles, valid, fitted);
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
		final double step, // step is in the same units as the _spacing,
		// etc. variables.
		final double ox, /* These are scaled now */
		final double oy, final double oz, final double nx, final double ny,
		final double nz, final double[] x_basis_vector, /*
																										* The basis vectors are returned here
																										*/
		final double[] y_basis_vector, /* they *are* scaled by _spacing */
		final ImagePlus image)
	{

		final float[] result = new float[side * side];

		final double epsilon = 0.000001;

		/*
		 * To find an arbitrary vector in the normal plane, do the cross product with
		 * (0,0,1), unless the normal is parallel to that, in which case we cross it
		 * with (0,1,0) instead...
		 */

		double ax, ay, az;

		if (Math.abs(nx) < epsilon && Math.abs(ny) < epsilon) {
			// Cross with (0,1,0):
			ax = nz;
			ay = 0;
			az = -nx;
		}
		else {
			// Cross with (0,0,1):
			ax = -ny;
			ay = nx;
			az = 0;
		}

		/*
		 * Now to find the other vector in that plane, do the cross product of
		 * (ax,ay,az) with (nx,ny,nz)
		 */

		double bx = ay * nz - az * ny;
		double by = az * nx - ax * nz;
		double bz = ax * ny - ay * nx;

		/* Normalize a and b */

		final double a_size = Math.sqrt(ax * ax + ay * ay + az * az);
		ax = ax / a_size;
		ay = ay / a_size;
		az = az / a_size;

		final double b_size = Math.sqrt(bx * bx + by * by + bz * bz);
		bx = bx / b_size;
		by = by / b_size;
		bz = bz / b_size;

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

		final int width = image.getWidth();
		final int height = image.getHeight();
		final int depth = image.getNSlices();
		final float[][] v = new float[depth][];
		final ImageStack s = image.getStack();
		final int imageType = image.getType();
		final int arraySize = width * height;
		if (imageType == ImagePlus.GRAY8 || imageType == ImagePlus.COLOR_256) {
			for (int z = 0; z < depth; ++z) {
				final byte[] bytePixels = (byte[]) s.getPixels(z + 1);
				final float[] fa = new float[arraySize];
				for (int i = 0; i < arraySize; ++i)
					fa[i] = bytePixels[i] & 0xFF;
				v[z] = fa;
			}
		}
		else if (imageType == ImagePlus.GRAY16) {
			for (int z = 0; z < depth; ++z) {
				final short[] shortPixels = (short[]) s.getPixels(z + 1);
				final float[] fa = new float[arraySize];
				for (int i = 0; i < arraySize; ++i)
					fa[i] = shortPixels[i];
				v[z] = fa;
			}
		}
		else if (imageType == ImagePlus.GRAY32) {
			for (int z = 0; z < depth; ++z) {
				v[z] = (float[]) s.getPixels(z + 1);
			}
		}

		for (int grid_i = 0; grid_i < side; ++grid_i) {
			for (int grid_j = 0; grid_j < side; ++grid_j) {

				final double midside_grid = ((side - 1) / 2.0f);

				final double gi = midside_grid - grid_i;
				final double gj = midside_grid - grid_j;

				final double vx = ox + gi * ax_s + gj * bx_s;
				final double vy = oy + gi * ay_s + gj * by_s;
				final double vz = oz + gi * az_s + gj * bz_s;

				// So now denormalize to pixel co-ordinates:

				final double image_x = vx / path.x_spacing;
				final double image_y = vy / path.y_spacing;
				final double image_z = vz / path.z_spacing;

				/*
				 * And do a trilinear interpolation to find the value there:
				 */

				final double x_d = image_x - Math.floor(image_x);
				final double y_d = image_y - Math.floor(image_y);
				final double z_d = image_z - Math.floor(image_z);

				final int x_f = (int) Math.floor(image_x);
				final int x_c = (int) Math.ceil(image_x);
				final int y_f = (int) Math.floor(image_y);
				final int y_c = (int) Math.ceil(image_y);
				final int z_f = (int) Math.floor(image_z);
				final int z_c = (int) Math.ceil(image_z);

				/*
				 * Check that these values aren't poking off the edge of the screen - if so then
				 * make them zero.
				 */

				double fff;
				double cff;
				double fcf;
				double ccf;

				double ffc;
				double cfc;
				double fcc;
				double ccc;

				if ((x_f < 0) || (x_c < 0) || (y_f < 0) || (y_c < 0) || (z_f < 0) ||
					(z_c < 0) || (x_f >= width) || (x_c >= width) || (y_f >= height) ||
					(y_c >= height) || (z_f >= depth) || (z_c >= depth))
				{

					fff = 0;
					cff = 0;
					fcf = 0;
					ccf = 0;
					ffc = 0;
					cfc = 0;
					fcc = 0;
					ccc = 0;

				}
				else {

					fff = v[z_f][width * y_f + x_f];
					cff = v[z_c][width * y_f + x_f];

					fcf = v[z_f][width * y_c + x_f];
					ccf = v[z_c][width * y_c + x_f];

					ffc = v[z_f][width * y_f + x_c];
					cfc = v[z_c][width * y_f + x_c];

					fcc = v[z_f][width * y_c + x_c];
					ccc = v[z_c][width * y_c + x_c];

				}

				// Now we should be OK to do the interpolation for real:

				final double i1 = (1 - z_d) * (fff) + (cff) * z_d;
				final double i2 = (1 - z_d) * (fcf) + (ccf) * z_d;

				final double j1 = (1 - z_d) * (ffc) + (cfc) * z_d;
				final double j2 = (1 - z_d) * (fcc) + (ccc) * z_d;

				final double w1 = i1 * (1 - y_d) + i2 * y_d;
				final double w2 = j1 * (1 - y_d) + j2 * y_d;

				final double value_f = w1 * (1 - x_d) + w2 * x_d;

				result[grid_j * side + grid_i] = (float) value_f;
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
	 * @param imp the Image containing the signal to which the fit will be
	 *          performed
	 */
	public void setImp(final ImagePlus imp) {
		if (imp == null) throw new IllegalArgumentException(
				"Cannot fit a null image");
		this.imp = imp;
	}

	private class CircleAttempt implements MultivariateFunction,
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
