/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2023 Fiji developers.
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
 * A flavor of TreeStatistics that does not do graph conversions, ensuring paths
 * can be measured independently of their connectivity.
 */
public class PathAnalyzer extends TreeStatistics {

	public PathAnalyzer(Collection<Path> paths, String label) {
		super(new Tree(paths));
		tree.setLabel(label);
		final String unit = paths.iterator().next().getCalibration().getUnit();
		if (paths.stream().allMatch(p -> p.getCalibration().getUnit().equals(unit)))
			tree.getProperties().setProperty(TreeProperties.KEY_SPATIAL_UNIT, unit);
	}

	private PathAnalyzer(final Path path) {
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
	public Number getMetric(final String metric) throws IllegalArgumentException {
		if ("Path ID".equalsIgnoreCase(metric))
			return (tree.size() == 1) ? tree.list().get(0).getID() : Double.NaN;
		return getMetricInternal(TreeStatistics.getNormalizedMeasurement(metric));
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
	public double getPrimaryLength() {
		return getPrimaryBranches().stream().mapToDouble(p -> p.getLength()).sum();
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
	protected Number getMetricWithoutChecks(final String metric) throws UnknownMetricException {
		if ( tree.size() == 1) {
			switch(metric) {
			case TreeStatistics.PATH_ORDER:
				return tree.get(0).getOrder();
			case TreeStatistics.PATH_LENGTH:
				return tree.get(0).getLength();
			case TreeStatistics.PATH_CHANNEL:
				return tree.get(0).getChannel();
			case TreeStatistics.PATH_FRAME:
				return tree.get(0).getFrame();
			case TreeStatistics.PATH_CONTRACTION:
				return tree.get(0).getContraction();
			case TreeStatistics.PATH_MEAN_RADIUS:
				return tree.get(0).getMeanRadius();
			case TreeStatistics.PATH_SURFACE_AREA:
				return tree.get(0).getApproximatedSurface();
			case TreeStatistics.PATH_VOLUME:
				return tree.get(0).getApproximatedVolume();
			case TreeStatistics.N_BRANCH_POINTS:
				return tree.get(0).getJunctionNodes().size();
			case TreeStatistics.PATH_N_SPINES:
				return tree.get(0).getSpineOrVaricosityCount();
			case TreeStatistics.PATH_SPINE_DENSITY:
				return tree.get(0).getSpineOrVaricosityCount() / tree.get(0).getLength();
			default:
				super.getMetricWithoutChecks(metric);
			}
		}
		return super.getMetricWithoutChecks(metric);
	}

	public void measureIndividualPaths(final Collection<String> metrics, final boolean summarize) {
		if (table == null) table = new SNTTable();
		final Collection<String> measuringMetrics = (metrics == null || metrics.isEmpty()) ? getMetrics("safe") : metrics;
		tree.list().forEach(path -> {
			final int row = getNextRow(path.getName());
			table.set(getCol("SWC Type(s)"), row, Path.getSWCtypeName(path.getSWCType(), true)); // plural heading for consistency with other commands
			measuringMetrics.forEach(metric -> {
				// The easiest here is to initialize an instance aware only of this single
				// path. Then, we can recycle the logic behind #assembleStats(). Since, all
				// getBranches() related code has been (hopefully) overridden, this will
				// effectively retrieve the single-value metric for this path.
				final PathAnalyzer analyzer = new PathAnalyzer(path);
				final SummaryStatistics dummy = new SummaryStatistics();
				analyzer.assembleStats(new StatisticsInstance(dummy), metric);
				Number value;
				try {
					value = dummy.getMax(); // since N=1, same as the value itself
				} catch (final IllegalArgumentException ignored) {
					// maybe we are handling some legacy metric description!?
					value = analyzer.getMetricInternal(metric);
				}
				table.set(getCol(metric), row, value);
			});
		});
		if (summarize && table instanceof SNTTable) {
			((SNTTable) table).summarize();
		}
		updateAndDisplayTable();
	}
}
