/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2025 Fiji developers.
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
package sc.fiji.snt.analysis.sholl.parsers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ShortProcessor;
import net.imagej.display.ColorTables;
import net.imglib2.display.ColorTable;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.sholl.Profile;
import sc.fiji.snt.analysis.sholl.ProfileEntry;
import sc.fiji.snt.analysis.sholl.math.LinearProfileStats;
import sc.fiji.snt.util.ImpUtils;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SNTPoint;
import sc.fiji.snt.util.ShollPoint;

/**
 * A {@link Parser} for extracting Sholl Profiles from a {@link Tree}.
 *
 * @author Tiago Ferreira
 */
public class TreeParser implements Parser {

	static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

	/**
	 * Flag for defining the profile center as the average position of root nodes
	 * of all primary Paths.
	 */
	public static final int ROOT_NODES_ANY = 0;

	/**
	 * Flag for defining the profile center as the average position of root nodes
	 * of Paths tagged as Apical Dendrite.
	 */
	public static final int ROOT_NODES_APICAL_DENDRITE = 1;

	/**
	 * Flag for defining the profile center as the average position of root nodes
	 * of Paths tagged as Axon.
	 */
	public static final int ROOT_NODES_AXON = 2;

	/**
	 * Flag for defining the profile center as the average position of root nodes
	 * of Paths tagged as Custom.
	 */
	public static final int ROOT_NODES_CUSTOM = 3;

	/**
	 * Flag for defining the profile center as the average position of root nodes
	 * of Paths tagged as (Basal) Dendrite
	 */
	public static final int ROOT_NODES_DENDRITE = 4;

	/**
	 * Flag for defining the profile center as the average position of root nodes
	 * of primary Paths tagged as Soma
	 */
	public static final int ROOT_NODES_SOMA = 5;

	/**
	 * Flag for defining the profile center as the average position of root nodes
	 * of _ANY_ Paths tagged as Soma, independently of connectivity
	 */
	public static final int ROOT_NODES_SOMA_ANY= 7;

	/**
	 * Flag for defining the profile center as the average position of root nodes
	 * of Paths tagged as Undefined
	 */
	public static final int ROOT_NODES_UNDEFINED = 6;

	private final Tree tree;
	private List<ComparableShollPoint> shollPointsList;
	private PointInImage center;
	private double stepSize = 0;
	private Profile profile;
	private volatile boolean running = true;
	private double[] squaredRangeStarts;
	private int[] crossingsPastEach;
	private boolean skipSomaticSegments;

	/**
	 * Instantiates a new Tree Parser.
	 *
	 * @param tree the Tree to be profiled
	 */
	public TreeParser(final Tree tree) {
		this.tree = tree;
	}

	/**
	 * Computes the center of the Profile.
	 *
	 * @param choice the flag specifying the center (e.g.,
	 *          {@link #ROOT_NODES_SOMA}, {@link #ROOT_NODES_ANY}, etc.)
	 * @throws IllegalArgumentException if choice is not a recognized flag or if
	 *           no Paths in the Tree match the choice criteria
	 */
	public void setCenter(final int choice) throws IllegalArgumentException {
		switch (choice) {
			case ROOT_NODES_ANY:
				center = getCenter(-1, true);
				if (center == null && !tree.isEmpty())
					center = tree.list().get(0).getNode(0);
				break;
			case ROOT_NODES_UNDEFINED:
				center = getCenter(Path.SWC_UNDEFINED, true);
				break;
			case ROOT_NODES_SOMA:
				center = getCenter(Path.SWC_SOMA, true);
				break;
			case ROOT_NODES_SOMA_ANY:
				center = getCenter(Path.SWC_SOMA, false);
				break;
			case ROOT_NODES_AXON:
				center = getCenter(Path.SWC_AXON, true);
				break;
			case ROOT_NODES_DENDRITE:
				center = getCenter(Path.SWC_DENDRITE, true);
				break;
			case ROOT_NODES_APICAL_DENDRITE:
				center = getCenter(Path.SWC_APICAL_DENDRITE, true);
				break;
			case ROOT_NODES_CUSTOM:
				center = getCenter(Path.SWC_CUSTOM, true);
				break;
			default:
				throw new IllegalArgumentException("Center choice was not understood");
		}
		if (center == null) throw new IllegalArgumentException(
			"Tree does not contain Paths matching specified choice");
	}

	/**
	 * Returns the center coordinates
	 *
	 * @return the point defining the center, or null if it has not yet been set.
	 */
	public PointInImage getCenter() {
		return (center == null) ? null : new ShollPoint(center.x, center.y, center.z);
	}

	private PointInImage getCenter(final int swcType, final boolean primaryPathsOnly) {
		final List<PointInImage> points = new ArrayList<>();
		for (final Path p : tree.list()) {
			if (primaryPathsOnly && !p.isPrimary()) continue;
			if (swcType < 0 || p.getSWCType() == swcType) {
				points.add(p.getNode(0));
			}
		}
		return (points.isEmpty()) ? null : SNTPoint.average(points);
	}

	/**
	 * Sets the center of the profile.
	 *
	 * @param center the focal point of the profile
	 */
	public void setCenter(final PointInImage center) {
		if (successful()) throw new UnsupportedOperationException(
			"setCenter() must be called before parsing data");
		this.center = center;
	}

	/**
	 * Sets the center of the profile.
	 *
	 * @param coordinates the array holding the focal point coordinates of the profile
	 */
	public void setCenter(final double[] coordinates) {
		if (successful()) throw new UnsupportedOperationException(
			"setCenter() must be called before parsing data");
		if (coordinates.length < 3)
			center = new PointInImage(coordinates[0], coordinates[1], 0);
		else
			center = new PointInImage(coordinates[0], coordinates[1], coordinates[2]);
	}

	/**
	 * Sets the radius step size.
	 *
	 * @param stepSize the radius step size
	 */
	public void setStepSize(final double stepSize) {
		if (successful()) throw new UnsupportedOperationException(
			"setStepSize() must be called before parsing data");
		this.stepSize = (stepSize < 0) ? 0 : stepSize;
	}

	/* (non-Javadoc)
	 * @see sholl.parsers.Parser#parse()
	 */
	@Override
	public void parse() throws IllegalArgumentException {
		if (tree == null || tree.isEmpty()) {
			throw new IllegalArgumentException("Invalid tree");
		}
		if (center == null) {
			throw new IllegalArgumentException(
				"Data cannot be parsed unless a center is specified");
		}
		profile = new Profile();
		if (tree.getLabel() != null) profile.setIdentifier(tree.getLabel());
		profile.setNDimensions((tree.is3D()) ? 3 : 2);
		profile.setCenter(new ShollPoint(center));
		if (tree.getBoundingBox(false) != null) profile.setSpatialCalibration(tree
			.getBoundingBox(false).getCalibration());
		profile.getProperties().setProperty(KEY_SOURCE, SRC_TRACES);
		assembleSortedShollPointList();
		assembleProfileAfterAssemblingSortedShollPointList();
	}

	/* (non-Javadoc)
	 * @see sholl.parsers.Parser#successful()
	 */
	@Override
	public boolean successful() {
		return profile != null && profile.size() > 0;
	}

	/* (non-Javadoc)
	 * @see sholl.parsers.Parser#terminate()
	 */
	@Override
	public void terminate() {
		running = false;
	}

	/* (non-Javadoc)
	 * @see sholl.parsers.Parser#getProfile()
	 */
	@Override
	public Profile getProfile() {
		return profile;
	}

	private void assembleSortedShollPointList() {
		shollPointsList = new ArrayList<>();
		final PointInImage soma = tree.getRoot();
		final boolean skipFirstNode = isSkipSomaticSegments() && soma != null && soma.onPath.size() == 1
				&& soma.onPath.getSWCType() == Path.SWC_SOMA;
		tree.list().forEach(p -> {
			if (!running || p.size() == 0 || (skipFirstNode && p.equals(soma.onPath)))
				return;
			for (int i = (skipFirstNode) ? 1 : 0; i < p.size() - 1; ++i) {
				final PointInImage pim1 = p.getNode(i);
				final PointInImage pim2 = p.getNode(i + 1);
				final double distanceSquaredFirst = pim1.distanceSquaredTo(center);
				final double distanceSquaredSecond = pim2.distanceSquaredTo(center);
				final boolean nearer = distanceSquaredFirst < distanceSquaredSecond;
				shollPointsList.add(new ComparableShollPoint(distanceSquaredFirst, nearer));
				shollPointsList.add(new ComparableShollPoint(distanceSquaredSecond, !nearer));
			}
		});
		// Ensure we are not keeping duplicated data points
		shollPointsList = shollPointsList.stream().distinct().collect(Collectors
			.toList());
		Collections.sort(shollPointsList);
	}

	private void assembleProfileAfterAssemblingSortedShollPointList() {
		final int n = shollPointsList.size();
		squaredRangeStarts = new double[n];
		crossingsPastEach = new int[n];
		int currentCrossings = 0;
		for (int i = 0; i < n; ++i) {
			final ComparableShollPoint p = shollPointsList.get(i);
			if (p.nearer) ++currentCrossings;
			else--currentCrossings;
			squaredRangeStarts[i] = p.distanceSquared;
			crossingsPastEach[i] = currentCrossings;
		}
		if (!running) return;
		if (stepSize > 0) { // Discontinuous sampling
			final double minDistance = Math.sqrt(squaredRangeStarts[0]);
			final double maxDistance = Math.sqrt(squaredRangeStarts[n - 1]);
			for (int i = 0; i < Math.ceil(maxDistance / stepSize); ++i) {
				final double x = i * stepSize;
				if ( x >= minDistance) {
					final double y = crossingsAtDistanceSquared(x * x);
					profile.add(new ProfileEntry(x, y, null));
				}
			}
		}
		else { // Continuous sampling
            for (double squaredRangeStart : squaredRangeStarts) {
                final double x = Math.sqrt(squaredRangeStart);
                final double y = crossingsAtDistanceSquared(squaredRangeStart);
                profile.add(new ProfileEntry(x, y, null));
            }
		}

	}

	private int crossingsAtDistanceSquared(final double distanceSquared) {
		int minIndex = 0;
		int maxIndex = squaredRangeStarts.length - 1;
		if (distanceSquared < squaredRangeStarts[minIndex]) return 1;
		else if (distanceSquared > squaredRangeStarts[maxIndex]) return 0;
		while (maxIndex - minIndex > 1) {
			final int midPoint = (maxIndex + minIndex) / 2;
			if (distanceSquared < squaredRangeStarts[midPoint]) maxIndex = midPoint;
			else minIndex = midPoint;
		}
		return crossingsPastEach[minIndex];
	}

	private static class ComparableShollPoint implements Comparable<ComparableShollPoint> {

		private final boolean nearer;
		private final double distanceSquared;

		ComparableShollPoint(final double distanceSquared, final boolean nearer) {
			this.distanceSquared = distanceSquared;
			this.nearer = nearer;
		}

		@Override
		public int compareTo(final ComparableShollPoint other) {
			if (this.distanceSquared < other.distanceSquared) return -1;
			else if (other.distanceSquared < this.distanceSquared) return 1;
			return 0;
		}

		/*
		 * (non-Javadoc)
		 *
		 * @see java.lang.Object#equals(java.lang.Object)
		 */
		@Override
		public boolean equals(final Object o) {
			if (this == o) return true;
			if (o == null) return false;
			if (!(o instanceof ComparableShollPoint)) return false;
			final ComparableShollPoint other = (ComparableShollPoint) o;
			return nearer == other.nearer
					&& Double.doubleToLongBits(distanceSquared) == Double.doubleToLongBits(other.distanceSquared);
		}
	}

	/**
	 * Gets the labels image.
	 *
	 * @param templateImg the template img
	 * @param cTable the c table
	 * @return the labels image
	 */
	public ImagePlus getLabelsImage(final ImagePlus templateImg,
		final ColorTable cTable)
	{
		if (templateImg == null) throw new IllegalArgumentException(
			"Template image cannot be null");
		if (!successful() || crossingsPastEach == null ||
			squaredRangeStarts == null || center == null)
			throw new UnsupportedOperationException("Data has not been parsed");
		final int width = templateImg.getWidth();
		final int height = templateImg.getHeight();
		final int depth = templateImg.getNSlices();
		final Calibration c = templateImg.getCalibration();
		double x_spacing = 1;
		double y_spacing = 1;
		double z_spacing = 1;
		if (c != null) {
			x_spacing = c.pixelWidth;
			y_spacing = c.pixelHeight;
			z_spacing = c.pixelDepth;
		}
		final ImageStack stack = new ImageStack(width, height);
		for (int z = 0; z < depth; ++z) {
			final short[] pixels = new short[width * height];
			for (int y = 0; y < height; ++y) {
				for (int x = 0; x < width; ++x) {
					final PointInImage point = new PointInImage(x_spacing * x, y_spacing *
						y, z_spacing * z);
					pixels[y * width + x] = (short) crossingsAtDistanceSquared(point
						.distanceSquaredTo(center));
				}
			}
			final ShortProcessor sp = new ShortProcessor(width, height);
			sp.setPixels(pixels);
			stack.addSlice("", sp);
		}
		final ImagePlus result = new ImagePlus("Labels Image", stack);
		ImpUtils.applyColorTable(result, (cTable == null) ? ColorTables.ICE : cTable);
		result.setDisplayRange(0, new LinearProfileStats(profile).getMax());
		result.setCalibration(templateImg.getCalibration());
		return result;
	}

	/* IDE debug method */
	public static void main(final String... args) {
		final Tree tree = new Tree(SNTUtils.randomPaths());
		final TreeParser parser = new TreeParser(tree);
		parser.setCenter(ROOT_NODES_ANY);
		parser.parse();
		parser.getProfile().plot().show();
	}

	/**
	 * Returns the tree associated with this parser
	 * 
	 * @return the profiled tree
	 */
	public Tree getTree() {
		return tree;
	}

	/** @see #setSkipSomaticSegments(boolean) */
	public boolean isSkipSomaticSegments() {
		return skipSomaticSegments;
	}

	/**
	 * If primary paths start far-away from the soma (expected to be defined by a
	 * single-point (soma centroid)), should segments between the soma and neurite
	 * be ignored. Useful when somas are large relatively to the length of arbor, as
	 * in microglia cells. See e.g.,
	 * <a href="https://forum.image.sc/t//51707/">this forum thread</a>.
	 * 
	 * @param skipSomaticSegments the skipFirsNode to set
	 */
	public void setSkipSomaticSegments(boolean skipSomaticSegments) {
		this.skipSomaticSegments = skipSomaticSegments;
	}

}
