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
package sc.fiji.snt.analysis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import sc.fiji.snt.Path;
import sc.fiji.snt.Tree;

/*
 * A flavor of TreeStatistics that does not do graph conversions, ensuring paths
 * can me measured independently of their connectivity.
 */
public class PathAnalyzer extends TreeStatistics {

	public PathAnalyzer(Collection<Path> paths, String label) {
		super(new Tree(paths));
		tree.setLabel(label);
	}

	private PathAnalyzer(final Path path) {
		super(new Tree(Collections.singleton(path)));
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

	public void measureIndividualPaths(final Collection<String> metrics) {
		if (table == null) table = new SNTTable();
		final Collection<String> measuringMetrics = (metrics == null || metrics.isEmpty()) ? getMetrics() : metrics;
		tree.list().forEach( path -> {
			final int row = getNextRow(path.getName());
			table.set(getCol("SWC Type"), row, Path.getSWCtypeName(path.getSWCType(), true));
			measuringMetrics.forEach(metric -> {
				table.set(getCol(metric), row,  new PathAnalyzer(path).getMetricInternal(metric));
			});
		});
		if (getContext() != null) updateAndDisplayTable();
	}
}
