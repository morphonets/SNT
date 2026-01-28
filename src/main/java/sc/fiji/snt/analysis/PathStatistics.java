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
package sc.fiji.snt.analysis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.apache.commons.math3.stat.descriptive.SummaryStatistics;

import sc.fiji.snt.Path;
import sc.fiji.snt.Tree;
import sc.fiji.snt.TreeProperties;

/**
 * A specialized version of {@link TreeStatistics} for analyzing individual paths
 * without considering their connectivity relationships.
 * <p>
 * PathStatistics provides morphometric analysis of neuronal paths while treating
 * each path as an independent entity, rather than as part of a connected tree
 * structure.
 * </p>
 * Key differences from TreeStatistics:
 * <ul>
 * <li>No graph conversion - paths are analyzed independently</li>
 * <li>Branch-related metrics are redefined to work with individual paths</li>
 * <li>Supports path-specific measurements like Path ID and number of children</li>
 * <li>Provides individual path measurement capabilities</li>
 * </ul>
 * Example usage:
 * <pre>
 * // Analyze a single path
 * PathStatistics stats = new PathStatistics(path);
 * double length = stats.getMetric("Path length").doubleValue();
 * 
 * // Analyze multiple paths independently
 * Collection&lt;Path&gt; paths = getPaths();
 * PathStatistics multiStats = new PathStatistics(paths, "My Analysis");
 * multiStats.measureIndividualPaths(Arrays.asList("Path length", "N. nodes"), true);
 * </pre>
 *
 * @author Tiago Ferreira
 * @see TreeStatistics
 * @see Path
 * @see Tree
 */
public class PathStatistics extends TreeStatistics {

	/** Flag for {@value #N_CHILDREN} analysis. */
	public static final String N_CHILDREN = "No. of children";

	/**
	 * Instantiates PathStatistics from a collection of paths.
	 * <p>
	 * Creates a PathStatistics instance for analyzing a collection of Path objects.
	 * The spatial unit is automatically determined from the paths if they all share
	 * the same calibration unit.
	 * </p>
	 *
	 * @param paths the collection of paths to be analyzed
	 * @param label the label describing the path collection
	 */
	public PathStatistics(Collection<Path> paths, String label) {
		super(new Tree(paths));
		tree.setLabel(label);
		final String unit = paths.iterator().next().getCalibration().getUnit();
		if (paths.stream().allMatch(p -> p.getCalibration().getUnit().equals(unit)))
			tree.getProperties().setProperty(TreeProperties.KEY_SPATIAL_UNIT, unit);
	}

	/**
	 * Instantiates PathStatistics from a single path.
	 * <p>
	 * Creates a PathStatistics instance for analyzing a single Path object.
	 * The path's name is used as the label for the analysis.
	 * </p>
	 *
	 * @param path the path to be analyzed
	 */
	public PathStatistics(final Path path) {
		this(Collections.singleton(path), path.getName());
	}

	/**
	 * Gets all the paths being analyzed as branches.
	 * <p>
	 * In PathStatistics, all paths are considered as branches since each path
	 * represents a distinct structural element.
	 * </p>
	 *
	 * @return the list of all paths
	 */
	@Override
	public List<Path> getBranches() {
		return tree.list();
	}

	/**
	 * Gets the number of branches (paths) being analyzed.
	 * <p>
	 * Returns the total count of paths in this PathStatistics instance.
	 * </p>
	 *
	 * @return the number of paths
	 */
	@Override
	public int getNBranches() {
		return tree.size();
	}

	/**
	 * Gets a summary metric for the analyzed paths.
	 * <p>
	 * Extends the parent class functionality to support path-specific metrics
	 * such as "Path ID". For single-path analyses, returns the specific path ID;
	 * for multi-path analyses, returns NaN for path-specific metrics.
	 * </p>
	 *
	 * @param metric the name of the metric to retrieve
	 * @return the metric value, or NaN if not applicable
	 * @throws UnknownMetricException if the metric is not recognized
	 */
	@Override
	public Number getMetric(final String metric) throws UnknownMetricException {
		if ("Path ID".equalsIgnoreCase(metric))
			return (tree.size() == 1) ? tree.list().get(0).getID() : Double.NaN;
		return super.getMetric(metric);
	}

	/**
	 * Gets the primary branches from the analyzed paths.
	 * <p>
	 * Returns only those paths that are marked as primary branches, typically
	 * those that originate directly from the root or soma.
	 * </p>
	 *
	 * @return the list of primary paths
	 */
	@Override
	public List<Path> getPrimaryBranches() {
		final ArrayList<Path> paths = new ArrayList<>();
		for (final Path path : tree.list()) {
			if (path.isPrimary()) paths.add(path);
		}
		return paths;
	}

	/**
	 * Gets the terminal branches from the analyzed paths.
	 * <p>
	 * Returns paths that have children, representing non-terminal segments.
	 * Note: This implementation differs from typical terminal branch definition
	 * as it returns paths with children rather than leaf paths.
	 * </p>
	 *
	 * @return the list of paths with children
	 */
	@Override
	public List<Path> getTerminalBranches() {
		final ArrayList<Path> paths = new ArrayList<>();
		for (final Path path : tree.list()) {
			if (!path.getChildren().isEmpty()) paths.add(path);
		}
		return paths;
	}

	/**
	 * Gets the total length of primary branches.
	 * <p>
	 * Calculates the sum of lengths of all paths marked as primary branches.
	 * </p>
	 *
	 * @return the total length of primary branches
	 */
	@Override
	public double getPrimaryLength() {
		return getPrimaryBranches().stream().mapToDouble(Path::getLength).sum();
	}

	/**
	 * Gets the total length of terminal branches.
	 * <p>
	 * Calculates the sum of lengths of all terminal branches as defined by
	 * {@link #getTerminalBranches()}.
	 * </p>
	 *
	 * @return the total length of terminal branches
	 */
	@Override
	public double getTerminalLength() {
		return getTerminalBranches().stream().mapToDouble(Path::getLength).sum();
	}

	/**
	 * Gets the inner branches from the analyzed paths.
	 * <p>
	 * In PathStatistics, inner branches are equivalent to primary branches.
	 * </p>
	 *
	 * @return the list of inner branches (same as primary branches)
	 */
	@Override
	public List<Path> getInnerBranches() {
		return getPrimaryBranches();
	}

	/**
	 * Gets the total length of inner branches.
	 * <p>
	 * In PathStatistics, this returns the same value as {@link #getPrimaryLength()}.
	 * </p>
	 *
	 * @return the total length of inner branches
	 */
	@Override
	public double getInnerLength() {
		return getPrimaryLength();
	}

	@Override
	protected int getCol(final String header) {
		// This flavor of TreeStatistics handles only Paths,
		// so we'll avoid logging any references to branch
		return super.getCol(header.replace("Branch", "Path"));
	}

	@Override
	protected void assembleStats(final StatisticsInstance stat, final String measurement) {
		if (N_CHILDREN.equalsIgnoreCase(measurement)) {
			for (final Path p : getBranches())
				stat.addValue(p.getChildren().size());
		} else {
			super.assembleStats(stat, measurement);
		}
	}

	@Override
	public SummaryStatistics getSummaryStats(final String metric) {
		if (N_CHILDREN.equalsIgnoreCase(metric)) {
			final SummaryStatistics sStats = new SummaryStatistics();
			assembleStats(new StatisticsInstance(sStats), N_CHILDREN);
			return sStats;
		}
		return super.getSummaryStats(metric);
	}

	/**
	 * Gets a specific metric value for an individual path.
	 * <p>
	 * This method provides direct access to morphometric properties of individual
	 * paths, including geometric measurements, connectivity information, and
	 * structural characteristics. It supports all standard path metrics plus
	 * PathStatistics-specific measurements.
	 * </p>
	 * Supported metrics include:
	 * <ul>
	 * <li>Geometric: length, volume, surface area, mean radius</li>
	 * <li>Structural: number of nodes, branch points, children</li>
	 * <li>Angular: extension angles in XY, XZ, ZY planes</li>
	 * <li>Morphological: contraction, fractal dimension, spine density</li>
	 * <li>Metadata: path ID, channel, frame, order</li>
	 * </ul>
	 *
	 * @param metric the name of the metric to retrieve
	 * @param path the specific path to measure
	 * @return the metric value for the specified path
	 * @throws UnknownMetricException if the metric is not recognized
	 */
	public Number getMetric(final String metric, final Path path) throws UnknownMetricException {
		switch (metric) {
			case "Path ID":
				return path.getID();
			case PATH_CHANNEL:
				return path.getChannel();
			case PATH_CONTRACTION:
				return path.getContraction();
			case PATH_EXT_ANGLE:
				return path.getExtensionAngle3D(false);
			case PATH_EXT_ANGLE_REL:
				return path.getExtensionAngle3D(true);
			case PATH_EXT_ANGLE_XY:
				return path.getExtensionAngleXY();
			case PATH_EXT_ANGLE_XZ:
				return path.getExtensionAngleXZ();
			case PATH_EXT_ANGLE_ZY:
				return path.getExtensionAngleZY();
			case PATH_FRACTAL_DIMENSION:
				return path.getFractalDimension();
			case PATH_FRAME:
				return path.getFrame();
			case PATH_LENGTH:
			case LENGTH:
				return path.getLength();
			case PATH_MEAN_RADIUS:
				return path.getMeanRadius();
			case PATH_N_SPINES:
			case N_SPINES:
				return path.getSpineOrVaricosityCount();
			case PATH_ORDER:
				return path.getOrder();
			case PATH_SPINE_DENSITY:
				return path.getSpineOrVaricosityCount() / path.getLength();
			case PATH_SURFACE_AREA:
				return path.getApproximatedSurface();
			case PATH_VOLUME:
			case VOLUME:
				return path.getApproximatedVolume();
			case N_CHILDREN:
				return path.getChildren().size();
			case N_PATH_NODES:
			case N_NODES:
				return path.size();
			case N_BRANCH_POINTS:
				return path.getBranchPoints().size();
			default:
				// A generic metric not directly associated with the Path class!?
				// we can recycle the logic behind #assembleStats(). Since, all
				// getBranches() related code has been (hopefully) overridden, this will
				// effectively retrieve the single-value metric for this path.
				final PathStatistics analyzer = new PathStatistics(path);
				final SummaryStatistics dummy = new SummaryStatistics();
				analyzer.assembleStats(new StatisticsInstance(dummy), metric);
				return dummy.getMax(); // since N=1, same as the value itself
		}
	}

	/**
	 * Measures specified metrics for each individual path and creates a detailed table.
	 * <p>
	 * This method generates a comprehensive measurement table where each row represents
	 * an individual path and columns contain the requested morphometric measurements.
	 * This is particularly useful for comparative analysis of path properties or
	 * for exporting detailed morphometric data.
	 * </p>
	 * The generated table includes:
	 * <ul>
	 * <li>Path identification information (name, SWC type)</li>
	 * <li>All requested morphometric measurements</li>
	 * <li>Optional summary statistics (if summarize is true)</li>
	 * </ul>
	 *
	 * @param metrics the collection of metric names to measure for each path.
	 *                If null or empty, a default "safe" set of metrics is used
	 * @param summarize if true, adds summary statistics to the table
	 */
	public void measureIndividualPaths(final Collection<String> metrics, final boolean summarize) {
		if (table == null) table = new SNTTable();
		final Collection<String> measuringMetrics = (metrics == null || metrics.isEmpty()) ? getMetrics("safe") : metrics;
		tree.list().forEach(path -> {
			final int row = getNextRow(path.getName());
			table.set(getCol("SWC Type(s)"), row, Path.getSWCtypeName(path.getSWCType(), true)); // plural heading for consistency with other commands
			measuringMetrics.forEach(metric -> {
				table.set(getCol(metric), row, new PathStatistics(path).getMetric(metric, path));
			});
		});
		if (summarize && table instanceof SNTTable) {
			((SNTTable) table).summarize();
		}
		updateAndDisplayTable();
	}
}
