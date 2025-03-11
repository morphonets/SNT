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

package sc.fiji.snt.util;

import sc.fiji.snt.Path;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.annotation.BrainAnnotation;

import java.awt.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Defines a node in an SWC reconstruction. The SWC file format is detailed
 * <a href= "https://swc-specification.readthedocs.io/en/latest/">here</a>.
 *
 * @author Tiago Ferreira
 */
public class SWCPoint extends PointInImage implements Comparable<SWCPoint> {

	/** The sample number of this node */
	public int id;

	/**
	 * The SWC-type flag of this node ({@link Path#SWC_SOMA},
	 * {@link Path#SWC_DENDRITE}, etc.)
	 */
	public int type;

	/** The parent id of this node */
	public int parent;

	/** The radius of reconstructed structure at this node */
	public double radius;

	private final List<SWCPoint> nextPoints;
	private SWCPoint previousPoint;

	/** Optional attribute: BrainAnnotation */
	private BrainAnnotation annotation;

	private String tags;

	private Color color;

	public SWCPoint(final int id, final int type, final double x, final double y,
		final double z, final double radius, final int parent)
	{
		super(x, y, z);
		nextPoints = new ArrayList<>();
		this.id = id;
		this.type = type;
		this.radius = radius;
		this.parent = parent;
	}

	/**
	 * Returns the X-distance from previous point.
	 *
	 * @return the X-distance from previous point or {@code Double.NaN} if no
	 *         previousPoint exists.
	 */
	public double xSeparationFromPreviousPoint() {
		return (previousPoint == null) ? Double.NaN : Math.abs(this.x -
			previousPoint.x);
	}

	/**
	 * Returns the Y-distance from previous point.
	 *
	 * @return the Y-distance from previous point or {@code Double.NaN} if no
	 *         previousPoint exists.
	 */
	public double ySeparationFromPreviousPoint() {
		return (previousPoint == null) ? Double.NaN : Math.abs(this.y -
			previousPoint.y);
	}

	/**
	 * Returns the Z-distance from previous point.
	 *
	 * @return the Z-distance from previous point or {@code Double.NaN} if no
	 *         previousPoint exists.
	 */
	public double zSeparationFromPreviousPoint() {
		return (previousPoint == null) ? Double.NaN : (this.z - previousPoint.z);
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Object#toString()
	 */
	@Override
	public String toString() {
		return "SWCPoint [" + id + "] " + Path.getSWCtypeName(type, false) + " " +
			"(" + x + "," + y + "," + z + ") " + "radius: " + radius + ", " +
			"[previous: " + parent + "]";
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Comparable#compareTo(java.lang.Object)
	 */
	@Override
	public int compareTo(final SWCPoint o) {
		return Integer.compare(id, o.id);
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Object#equals(java.lang.Object)
	 */
	@Override
	public boolean equals(final Object o) {
		return this == o;
	}
	
	@Override
	public int hashCode() {
		return System.identityHashCode(this);
	}

	/**
	 * Converts a collection of SWC points into a Reader.
	 *
	 * @param points the collection of SWC points to be converted into a space
	 *               separated String. Points should be sorted by sample number to
	 *               ensure valid connectivity.
	 * @return the Reader
	 */
	public static StringReader collectionAsReader(final Collection<SWCPoint> points) {
		final String SEP = " ";
		final StringBuilder sb = new StringBuilder();
		for (final SWCPoint p : points) {
			sb.append(p.id).append(SEP) //
					.append(p.type).append(SEP) // see https://github.com/morphonets/SNT/issues/147
					.append(String.format(Locale.US, "%.6f", p.x)).append(SEP) //
					.append(String.format(Locale.US, "%.6f", p.y)).append(SEP) //
					.append(String.format(Locale.US, "%.6f", p.z)).append(SEP) //
					.append(String.format(Locale.US, "%.6f", p.radius)).append(SEP) //
					.append(p.parent).append(System.lineSeparator());
		}
		return new StringReader(sb.toString());
	}

	/**
	 * Prints a list of points as space-separated values.
	 *
	 * @param points the collections of SWC points to be printed.
	 * @param pw the PrintWriter to write to.
	 * @see SWCPoint#collectionAsReader(Collection)
	 */
	public static void flush(final Collection<SWCPoint> points,
		final PrintWriter pw)
	{
		try (BufferedReader br = new BufferedReader(collectionAsReader(points))) {
			br.lines().forEach(pw::println);
		}
		catch (final IOException e) {
			SNTUtils.error("IO Error", e);
		}
	}

	@Override
	public double getX() {
		return x;
	}

	@Override
	public double getY() {
		return y;
	}

	@Override
	public double getZ() {
		return z;
	}

	@Override
	public void setAnnotation(final BrainAnnotation annotation) {
		this.annotation = annotation;
	}

	@Override
	public BrainAnnotation getAnnotation() {
		return annotation;
	}

	/**
	 * Returns the preceding node (if any)
	 * 
	 * @return the previous node or null if set by {@link #setPrevious(SWCPoint)} has not been called
	 */
	public SWCPoint previous() {
		return previousPoint;
	}

	/**
	 * Sets the preceding node in the reconstruction
	 * 
	 * @param previousNode the previous node preceding this one
	 */
	public void setPrevious(final SWCPoint previousNode) {
		this.previousPoint = previousNode;
	}

	/**
	 * Returns the list holding the subsequent nodes in the reconstructed structure
	 * after this one.
	 * 
	 * @return the list of "next points"
	 */
	public List<SWCPoint> getNextPoints() {
		return nextPoints;
	}

	public void setTags(final String tags) {
		this.tags = tags;
	}

	public void setColor(final Color color) {
		this.color = color;
	}

	public String getTags() {
		return tags;
	}

	public Color getColor() {
		return color;
	}

}
