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

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Defines a filled structure.
 * 
 * @author Mark Longair
 * @author Tiago Ferreira
 */
public class Fill {

	public double distanceThreshold;

	/**
	 * Defines a node in a filled structure.
	 * 
	 * @author Mark Longair
	 */
	public class Node {

		public int x;
		public int y;
		public int z;
		public double distance;
		public int previous;
		public boolean open;
	}

	ArrayList<Node> nodeList;

	public Fill() {
		nodeList = new ArrayList<>();
	}

	public void add(final int x, final int y, final int z, final double distance,
		final int previous, final boolean open)
	{
		final Node n = new Node();
		n.x = x;
		n.y = y;
		n.z = z;
		n.distance = distance;
		n.previous = previous;
		n.open = open;
		nodeList.add(n);
	}

	Set<Path> sourcePaths;

	public void setSourcePaths(final Path[] newSourcePaths) {
		sourcePaths = new HashSet<>();
		Collections.addAll(sourcePaths, newSourcePaths);
	}

	public void setSourcePaths(final Set<Path> newSourcePaths) {
		sourcePaths = new HashSet<>();
		sourcePaths.addAll(newSourcePaths);
	}

	public SNT.CostType metric;

	public void setMetric(final SNT.CostType cost) {
		this.metric = cost;
	}

	public SNT.CostType getMetric() {
		return metric;
	}

	public double x_spacing, y_spacing, z_spacing;
	public String spacing_units;

	public void setSpacing(final double x_spacing, final double y_spacing,
		final double z_spacing, final String units)
	{
		this.x_spacing = x_spacing;
		this.y_spacing = y_spacing;
		this.z_spacing = z_spacing;
		this.spacing_units = SNTUtils.getSanitizedUnit(units);
	}

	public void setThreshold(final double threshold) {
		this.distanceThreshold = threshold;
	}

	public double getThreshold() {
		return distanceThreshold;
	}

	public void writeNodesXML(final PrintWriter pw) {

		int i = 0;
		for (final Node n : nodeList) {
			pw.println("    <node id=\"" + i + "\" " + "x=\"" + n.x + "\" " + "y=\"" +
				n.y + "\" " + "z=\"" + n.z + "\" " + ((n.previous >= 0)
					? "previousid=\"" + n.previous + "\" " : "") + "distance=\"" +
				n.distance + "\" status=\"" + (n.open ? "open" : "closed") + "\"/>");
			++i;
		}
	}

	public void writeXML(final PrintWriter pw, final int fillIndex) {
		pw.print("  <fill id=\"" + fillIndex + "\"");
		if ((sourcePaths != null) && (sourcePaths.size() > 0)) {
			pw.print(" frompaths=\"");
			pw.print(getSourcePathsStringMachine());
			pw.print("\"");
		}
		pw.print(" volume=\"" + getVolume() + "\"");
		pw.println(" metric=\"" + getMetric().toString() + "\" threshold=\"" + getThreshold() +
			"\">");
		writeNodesXML(pw);
		pw.println("  </fill>");
	}

	/**
	 * Returns the Fill volume. It assumes that the volume is just the number of
	 * sub-threshold nodes multiplied by x_spacing * y_spacing * z_spacing.
	 *
	 * @return the volume
	 */
	public double getVolume() {
		int subThresholdNodes = 0;
		for (final Node n : nodeList) {
			if (n.distance <= distanceThreshold) ++subThresholdNodes;
		}
		return subThresholdNodes * x_spacing * y_spacing * z_spacing;
	}

	// FIXME: the next two should just be one method, really:

	protected String getSourcePathsStringMachine() {

		final StringBuilder result = new StringBuilder();

		boolean first = true;
		for (final Path p : sourcePaths) {
			if (first) {
				first = false;
			}
			else result.append(", ");
			result.append("").append(p.getID());
		}

		return result.toString();
	}

	protected String getSourcePathsStringHuman() {

		final StringBuilder result = new StringBuilder();
		final Path[] sortedSourcePaths = sourcePaths.toArray(new Path[] {});
		Arrays.sort(sortedSourcePaths);

		for (int j = 0; j < sortedSourcePaths.length; ++j) {
			final Path p = sortedSourcePaths[j];
			if (j != 0) result.append(", ");
			result.append("(").append(p.getID()).append(")");
		}

		return result.toString();
	}
}
