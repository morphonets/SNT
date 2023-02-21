/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2023 Fiji developers.
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
import net.imagej.mesh.Mesh;
import net.imagej.mesh.naive.NaiveDoubleMesh;
import org.scijava.Context;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.Tree;
import sc.fiji.snt.util.SNTPoint;

import java.util.Collection;

/**
 * @author Cameron Arshadi
 */
public class ConvexHull3D extends AbstractConvexHull {

    private final boolean computeSize;

    private Mesh hull;

    public <T extends SNTPoint> ConvexHull3D(final Context context, final Collection<T> points,
            final boolean computeSize)
    {
        super(context, points);
        this.computeSize = computeSize;
    }

    public <T extends SNTPoint> ConvexHull3D(final Collection<T> points, final boolean computeSize) {
        super(points);
        this.computeSize = computeSize;
    }

    public Mesh getMesh() {
        return hull;
    }

    @Override
    public void compute()
    {
        final Mesh mesh = new NaiveDoubleMesh();
        points.forEach(v -> mesh.vertices().add(v.getX(), v.getY(), v.getZ()));
        hull = (Mesh) opService.geom().convexHull(mesh).get(0);
        if (computeSize) {
            boundarySize = opService.geom().boundarySize(hull).getRealDouble();
            size = opService.geom().size(hull).getRealDouble();
        }
    }

    public static void main(String[] args) {
        ImageJ ij = new ImageJ();
        SNTService snt = ij.get(SNTService.class);
        Tree t = snt.demoTree("OP_1");
        ConvexHull3D hull = new ConvexHull3D(t.getNodes(), true);
        hull.compute();
        System.out.println(hull.size());
        System.out.println(hull.boundarySize());
    }

}
