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
package sc.fiji.snt.analysis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.apache.commons.math3.stat.descriptive.SummaryStatistics;

import sc.fiji.snt.Path;
import sc.fiji.snt.Tree;
import sc.fiji.snt.TreeProperties;

/*
 * A flavor of TreeStatistics that does not do graph conversions, ensuring paths can be measured independently of
 * their connectivity.
 */
public class PathStatistics extends TreeStatistics {

	/** Flag for {@value #N_CHILDREN} analysis. */
	public static final String N_CHILDREN = "No. of children";

	public PathStatistics(Collection<Path> paths, String label) {
		super(new Tree(paths));
		tree.setLabel(label);
		final String unit = paths.iterator().next().getCalibration().getUnit();
		if (paths.stream().allMatch(p -> p.getCalibration().getUnit().equals(unit)))
			tree.getProperties().setProperty(TreeProperties.KEY_SPATIAL_UNIT, unit);
	}

	public PathStatistics(final Path path) {
		this(Collections.singleton(path), path.getName());
	}

	@Override
	public List<Path> getBranches() {
		return tree.list();
	}

	@Override
	public int getNBranches() {
		return tree.size();
	}

	@Override
	public Number getMetric(final String metric) throws UnknownMetricException {
		if ("Path ID".equalsIgnoreCase(metric))
			return (tree.size() == 1) ? tree.list().get(0).getID() : Double.NaN;
		return super.getMetric(metric);
	}

	@Override
	public List<Path> getPrimaryBranches() {
		final ArrayList<Path> paths = new ArrayList<>();
		for (final Path path : tree.list()) {
			if (path.isPrimary()) paths.add(path);
		}
		return paths;
	}

	@Override
	public List<Path> getTerminalBranches() {
		final ArrayList<Path> paths = new ArrayList<>();
		for (final Path path : tree.list()) {
			if (!path.getChildren().isEmpty()) paths.add(path);
		}
		return paths;
	}

	@Override
	public double getPrimaryLength() {
		return getPrimaryBranches().stream().mapToDouble(Path::getLength).sum();
	}

	@Override
	public double getTerminalLength() {
		return getTerminalBranches().stream().mapToDouble(Path::getLength).sum();
	}

	@Override
	public List<Path> getInnerBranches() {
		return getPrimaryBranches();
	}

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

	public Number getMetric(final String metric, final Path path) throws UnknownMetricException {
		switch (metric) {
			case "Path ID":
				return path.getID();
			case PATH_CHANNEL:
				return path.getChannel();
			case PATH_CONTRACTION:
				return path.getContraction();
			case PATH_EXT_ANGLE_XY:
			case PATH_EXT_ANGLE_REL_XY:
				return path.getExtensionAngleXY(metric.toLowerCase().contains("rel"));
			case PATH_EXT_ANGLE_XZ:
			case PATH_EXT_ANGLE_REL_XZ:
				return path.getExtensionAngleXZ(metric.toLowerCase().contains("rel"));
			case PATH_EXT_ANGLE_ZY:
			case PATH_EXT_ANGLE_REL_ZY:
				return path.getExtensionAngleZY(metric.toLowerCase().contains("rel"));
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
				return path.getJunctionNodes().size();
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
