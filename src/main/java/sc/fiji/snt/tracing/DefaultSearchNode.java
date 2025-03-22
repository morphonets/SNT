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

package sc.fiji.snt.tracing;

import org.jheaps.AddressableHeap;
import sc.fiji.snt.Path;

/**
 * A {@link SearchNode} which can maintain both a from-start and from-goal search state.
 */
public class DefaultSearchNode implements SearchNode, Comparable<DefaultSearchNode> {

	public int x;
	public int y;
	public int z;

	public AddressableHeap.Handle<DefaultSearchNode, Void> heapHandle;

	public double g; // cost of the path so far (up to and including this node)
	public double h; // heuristic esimate of the cost of going from here to the
	// goal

	public double f; // should always be the sum of g and h

	public DefaultSearchNode predecessor;

	public DefaultSearchNode getPredecessor() {
		return predecessor;
	}

	public void setPredecessor(final DefaultSearchNode p) {
		this.predecessor = p;
	}

	/*
	 * This must be one of:
	 *
	 * SearchThread.OPEN_FROM_START SearchThread.CLOSED_FROM_START
	 * SearchThread.OPEN_FROM_GOAL SearchThread.CLOSED_FROM_GOAL SearchThread.FREE
	 */

	public byte searchStatus;

	public DefaultSearchNode() {}

	public DefaultSearchNode(final int x, final int y, final int z, final double g,
							 final double h, final DefaultSearchNode predecessor, final byte searchStatus)
	{
		this.x = x;
		this.y = y;
		this.z = z;
		this.g = g;
		this.h = h;
		this.f = g + h;
		this.predecessor = predecessor;
		this.searchStatus = searchStatus;
	}

	public AddressableHeap.Handle<DefaultSearchNode, Void> getHandle() {
		return heapHandle;
	}


	public void setHandle(AddressableHeap.Handle<DefaultSearchNode, Void> handle) {
		this.heapHandle = handle;
	}

	@Override
	public int getX() {
		return x;
	}

	@Override
	public int getY() {
		return y;
	}

	@Override
	public int getZ() {
		return z;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		DefaultSearchNode other = (DefaultSearchNode) o;

		if (x != other.x) return false;
		if (y != other.y) return false;
		return z == other.z;
	}

	@Override
	public int hashCode() {
		int result = x;
		result = 31 * result + y;
		result = 31 * result + z;
		return result;
	}

	public void setFrom(final DefaultSearchNode another) {
		this.x = another.x;
		this.y = another.y;
		this.z = another.z;
		this.g = another.g;
		this.h = another.h;
		this.f = another.f;
		this.predecessor = another.predecessor;
		this.searchStatus = another.searchStatus;
	}

	/* This is used by PriorityQueue: */

	@Override
	public int compareTo(final DefaultSearchNode o) {

		int compare_f_result = 0;
		if (f > o.f) compare_f_result = 1;
		else if (f < o.f) compare_f_result = -1;

		if (compare_f_result != 0) {

			return compare_f_result;

		}
		else {

			// Annoyingly, we need to distinguish between nodes with the
			// same priority, but which are at different locations.

			int x_compare = 0;
			if (x > o.x) x_compare = 1;
			if (x < o.x) x_compare = -1;

			if (x_compare != 0) return x_compare;

			int y_compare = 0;
			if (y > o.y) y_compare = 1;
			if (y < o.y) y_compare = -1;

			if (y_compare != 0) return y_compare;

			int z_compare = 0;
			if (z > o.z) z_compare = 1;
			if (z < o.z) z_compare = -1;

			return z_compare;

		}

	}

	@Override
	public String toString() {
		String searchStatusString = "BUG: unknown!";
		if (searchStatus == SearchThread.OPEN_FROM_START) searchStatusString =
			"open from start";
		else if (searchStatus == SearchThread.CLOSED_FROM_START)
			searchStatusString = "closed from start";
		else if (searchStatus == SearchThread.OPEN_FROM_GOAL) searchStatusString =
			"open from goal";
		else if (searchStatus == SearchThread.CLOSED_FROM_GOAL) searchStatusString =
			"closed from goal";
		else if (searchStatus == SearchThread.FREE) searchStatusString = "free";
		return "(" + x + "," + y + "," + z + ") h: " + h + " g: " + g + " f: " + f +
			" [" + searchStatusString + "]";
	}

	public Path asPath(final double x_spacing, final double y_spacing,
					   final double z_spacing, final String spacing_units)
	{
		final Path creversed = new Path(x_spacing, y_spacing, z_spacing,
			spacing_units);
		DefaultSearchNode p = this;
		do {
			creversed.addPointDouble(p.x * x_spacing, p.y * y_spacing, p.z *
				z_spacing);
			p = p.predecessor;
		}
		while (p != null);
		return creversed.reversed();
	}

	public Path asPathReversed(final double x_spacing, final double y_spacing,
		final double z_spacing, final String spacing_units)
	{
		final Path result = new Path(x_spacing, y_spacing, z_spacing,
			spacing_units);
		DefaultSearchNode p = this;
		do {
			result.addPointDouble(p.x * x_spacing, p.y * y_spacing, p.z * z_spacing);
			p = p.predecessor;
		}
		while (p != null);
		return result;
	}

}
