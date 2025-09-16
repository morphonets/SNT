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
package sc.fiji.snt.analysis.sholl;

import java.util.Set;

import sc.fiji.snt.util.ShollPoint;

/**
 * Utility class defining a Sholl profile entry
 *
 * @author Tiago Ferreira
 */
public class ProfileEntry implements Comparable<ProfileEntry> {

	/** The entry's radius length (in physical units) */
	public double radius;

	/** The number of intersection counts associated at this entry's radius */
	public double count;

	/** The total cable length associated with this entry's radius */
	public double length;

    /** An ad-hoc measurement associated with this entry's radius */
    public double extra; // extra measurements

	/**
	 * List of intersection points associated with the entry's radius (in
	 * spatially calibrated units)
	 */
	public Set<ShollPoint> points;

	public ProfileEntry(final Number r, final Number count, final Set<ShollPoint> points) {
		this.radius = r.doubleValue();
		this.count = count.doubleValue();
		this.length = 0.0;
		this.points = points;
	}

	public ProfileEntry(final Number r, final Number count, final Number length, final Set<ShollPoint> points) {
		this.radius = r.doubleValue();
		this.count = count.doubleValue();
		this.length = length.doubleValue();
		this.points = points;
	}

	public ProfileEntry(final Number r, final Set<ShollPoint> points) {
		this.radius = r.doubleValue();
		this.count = points.size();
		this.length = 0.0;
		this.points = points;
	}

	public ProfileEntry(final Number r, final Number count) {
		this(r, count, 0, null);
	}

	public ProfileEntry(final Number r, final Number count, final Number length) {
		this.radius = r.doubleValue();
		this.count = count.doubleValue();
		this.length = length.doubleValue();
		this.points = null;
	}

	public void addPoint(final ShollPoint point) {
		points.add(point);
	}

	public void assignPoints(final Set<ShollPoint> points) {
		this.points = points;
	}

	public void removePoint(final ShollPoint point) {
		points.remove(point);
	}

	public double radiusSquared() {
		return radius * radius;
	}

	@Override
	public int compareTo(final ProfileEntry other) {
		return Double.compare(this.radius, other.radius);
	}

}
