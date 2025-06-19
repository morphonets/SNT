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

package sc.fiji.snt.analysis;

import net.imagej.ImageJ;
import net.imglib2.RealPoint;
import net.imglib2.roi.geom.real.DefaultWritablePolygon2D;
import net.imglib2.roi.geom.real.Polygon2D;
import org.scijava.Context;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.Tree;
import sc.fiji.snt.util.BoundingBox;
import sc.fiji.snt.util.SNTPoint;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Computes the convex hull of a set of 2D points.
 * @author Cameron Arshadi
 */
public class ConvexHull2D extends AbstractConvexHull {

    private Polygon2D hull;

    public <T extends SNTPoint> ConvexHull2D(final Context context, final Collection<T> points) {
        super(context, points);
    }

    public <T extends SNTPoint> ConvexHull2D(final Collection<T> points) {
        super(points);
    }

    @Override
    public void compute() {
        final Polygon2D pol = new DefaultWritablePolygon2D(
                points.stream().map(p -> new RealPoint(p.getX(), p.getY())).collect(Collectors.toList()));
        hull = opService.geom().convexHull(pol);
    }

    @Override
    public double size() {
        if (size ==-1) {
            if (hull == null) compute();
            size = opService.geom().size(hull).getRealDouble();
        }
        return size;
    }

    @Override
    public double boundarySize() {
        if (boundarySize ==-1) {
            if (hull == null) compute();
            boundarySize = opService.geom().boundarySize(hull).getRealDouble();
        }
        return boundarySize;
    }

    public Polygon2D getPolygon() {
        if (hull == null) compute();
        return hull;
    }

    @Override
    public ConvexHull2D intersection(final AbstractConvexHull... convexHulls) {
        final List<SNTPoint> allPoints = new ArrayList<>();
        for (final AbstractConvexHull ch : convexHulls)
            allPoints.addAll(ch.points);
        final BoundingBox intersectionBb = intersectionBox(convexHulls);
        allPoints.removeIf(p-> !(intersectionBb.contains2D(p)));
        return new ConvexHull2D(allPoints);
    }

    public static void main(String[] args) {
        ImageJ ij = new ImageJ();
        SNTService snt = ij.get(SNTService.class);
        Tree t = snt.demoTree("fractal");
        ConvexHull2D hull = new ConvexHull2D(t.getNodes());
        hull.compute();
        System.out.println(hull.size());
        System.out.println(hull.boundarySize());
    }

}
