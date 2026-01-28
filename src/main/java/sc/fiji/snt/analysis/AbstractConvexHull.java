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

import net.imagej.ops.OpService;
import org.scijava.Context;
import org.scijava.NoSuchServiceException;
import org.scijava.plugin.Parameter;

import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.util.BoundingBox;
import sc.fiji.snt.util.SNTPoint;

import java.util.Collection;

/**
 * Parent class for convex hull analysis
 * @author Cameron Arshadi
 */
public abstract class AbstractConvexHull {

    @Parameter
    protected OpService opService;

    protected final Collection<? extends SNTPoint> points;

    protected double size;
    protected double boundarySize;

    protected <T extends SNTPoint> AbstractConvexHull(final Context context, final Collection<T> points) {
        context.inject(this);
        this.points = points;
        size = -1;
        boundarySize = -1;
    }

	protected <T extends SNTPoint> AbstractConvexHull(final Collection<T> points) {
        try {
            SNTUtils.getContext().inject(this);
        } catch (final Exception e) {
            e.printStackTrace();
        }
        if (opService == null) {
			throw new NoSuchServiceException("Failed to initialize OpService");
		}
		this.points = points;
        size = -1;
        boundarySize = -1;
	}

    public abstract void compute();

    public abstract double size();

    public abstract double boundarySize();

    public abstract AbstractConvexHull intersection(final AbstractConvexHull... convexHull);

    public BoundingBox intersectionBox(final AbstractConvexHull... convexHulls) {
        BoundingBox intersectionBb = new BoundingBox(points);
        for (final AbstractConvexHull convexHull : convexHulls)
            intersectionBb = intersectionBb.intersection(new BoundingBox(convexHull.points));
        return intersectionBb;
    }

}
