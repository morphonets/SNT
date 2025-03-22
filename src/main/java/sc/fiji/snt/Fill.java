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

package sc.fiji.snt;

import java.io.PrintWriter;
import java.util.*;

/**
 * Defines a filled structure.
 *
 * @author Mark Longair
 * @author Tiago Ferreira
 * @author Cameron Arshadi
 */
public class Fill {

	public double distanceThreshold;
	public SNT.CostType metric;
	public double x_spacing, y_spacing, z_spacing;
	public String spacing_units;
	private final ArrayList<Node> nodeList;
	Set<Path> sourcePaths;

	public Fill() {
		nodeList = new ArrayList<>();
	}

	/**
	 * Defines a node in a filled structure.
	 * 
	 * @author Mark Longair
	 */
	public static class Node {

		public int x;
		public int y;
		public int z;
		public double distance;
		public int previous;
		public boolean open;
	}

	/**
	 * Returns the list of nodes in the filled structure.
	 *
	 * @return the list of nodes
	 */
	public List<Node> getNodeList() {
		return nodeList;
	}

	/**
	 * Adds a node to the filled structure.
	 *
	 * @param x the x-coordinate of the node
	 * @param y the y-coordinate of the node
	 * @param z the z-coordinate of the node
	 * @param distance the distance value of the node
	 * @param previous the index of the previous node
	 * @param open the status of the node (open or closed)
	 */
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

	/**
	 * Returns the set of source paths for the filled structure.
	 *
	 * @return the set of source paths
	 */
	public Set<Path> getSourcePaths() {
		return sourcePaths;
	}

	/**
	 * Sets the source paths for the filled structure using an array of paths.
	 *
	 * @param newSourcePaths the array of new source paths
	 */
	public void setSourcePaths(final Path[] newSourcePaths) {
		sourcePaths = new HashSet<>();
		Collections.addAll(sourcePaths, newSourcePaths);
	}

	/**
	 * Sets the source paths for the filled structure using a set of paths.
	 *
	 * @param newSourcePaths the set of new source paths
	 */
	public void setSourcePaths(final Set<Path> newSourcePaths) {
		sourcePaths = new HashSet<>();
		sourcePaths.addAll(newSourcePaths);
	}

	/**
	 * Sets the cost metric for the filled structure.
	 *
	 * @param cost the cost type to set
	 */
	public void setMetric(final SNT.CostType cost) {
		this.metric = cost;
	}

	public SNT.CostType getMetric() {
		return metric;
	}


	public void setSpacing(final double x_spacing, final double y_spacing,
		final double z_spacing, final String units)
	{
		this.x_spacing = x_spacing;
		this.y_spacing = y_spacing;
		this.z_spacing = z_spacing;
		this.spacing_units = SNTUtils.getSanitizedUnit(units);
	}

	/**
	 * Sets the distance threshold for the filled structure.
	 *
	 * @param threshold the threshold value to set
	 */
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


	/**
	 * Returns the estimated mean radius of the fill, assuming a cylindric shape
	 *
	 * @return the estimated mean radius
	 */
	public double getEstimatedMeanRadius() {
		// https://forum.image.sc/t/calculating-vessel-diameter-radius/84473/2
		final double[] param = new double[] { getVolume(), 0 }; // volume, length
		getSourcePaths().forEach(p -> param[1] += p.getLength());
		return Math.sqrt(param[0] / (Math.PI * param[1]));
	}

	// FIXME: the next two should just be one method, really:

	public String getSourcePathsStringMachine() {

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

	public String getSourcePathsStringHuman() {

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

	@Override
	public String toString() {
		String name = "Fill";
		if ((getSourcePaths() != null) && (getSourcePaths().size() > 0)) {
			name += " from paths: " + getSourcePathsStringHuman();
		}
		return name;
	}
}
