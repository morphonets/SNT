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

import net.imagej.ImageJ;
import net.imglib2.RealPoint;
import net.imglib2.roi.geom.real.DefaultWritablePolygon2D;
import net.imglib2.roi.geom.real.Polygon2D;
import org.scijava.Context;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.Tree;
import sc.fiji.snt.util.SNTPoint;

import java.util.Collection;
import java.util.stream.Collectors;

/**
 * @author Cameron Arshadi
 */
public class ConvexHull2D extends AbstractConvexHull {

    private final boolean computeSize;

    private Polygon2D hull;

    public <T extends SNTPoint> ConvexHull2D(final Context context, final Collection<T> points,
            final boolean computeSize)
    {
        super(context, points);
        this.computeSize = computeSize;
    }

    public <T extends SNTPoint> ConvexHull2D(final Collection<T> points, final boolean computeSize) {
        super(points);
        this.computeSize = computeSize;
    }

    @Override
    public void compute() {
        final Polygon2D pol = new DefaultWritablePolygon2D(
                points.stream().map(p -> new RealPoint(p.getX(), p.getY())).collect(Collectors.toList()));
        hull = opService.geom().convexHull(pol);
        if (computeSize) {
            boundarySize = opService.geom().boundarySize(hull).getRealDouble();
            size = opService.geom().size(hull).getRealDouble();
        }
    }

    public Polygon2D getPolygon() {
        return hull;
    }

    public static void main(String[] args) {
        ImageJ ij = new ImageJ();
        SNTService snt = ij.get(SNTService.class);
        Tree t = snt.demoTree("fractal");
        ConvexHull2D hull = new ConvexHull2D(t.getNodes(), true);
        hull.compute();
        System.out.println(hull.size());
        System.out.println(hull.boundarySize());
    }

}
