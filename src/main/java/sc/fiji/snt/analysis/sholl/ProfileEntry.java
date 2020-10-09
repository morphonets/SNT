/*
 * #%L
 * Sholl Analysis plugin for ImageJ.
 * %%
 * Copyright (C) 2005 - 2020 Tiago Ferreira.
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

	/**
	 * List of intersection points associated with the entry's radius (in
	 * spatially calibrated units)
	 */
	public Set<UPoint> points;

	public ProfileEntry(final Number r, final Number count, final Set<UPoint> points) {
		this.radius = r.doubleValue();
		this.count = count.doubleValue();
		this.points = points;
	}

	public ProfileEntry(final Number r, final Set<UPoint> points) {
		this.radius = r.doubleValue();
		this.count = points.size();
		this.points = points;
	}

	public ProfileEntry(final Number r, final Number count) {
		this(r, count, null);
	}

	public void addPoint(final UPoint point) {
		points.add(point);
	}

	public void assignPoints(final Set<UPoint> points) {
		this.points = points;
	}

	public void removePoint(final UPoint point) {
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
