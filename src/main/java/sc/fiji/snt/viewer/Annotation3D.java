/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2024 Fiji developers.
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

package sc.fiji.snt.viewer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import net.imagej.mesh.Mesh;
import net.imagej.mesh.Triangle;
import net.imagej.mesh.Triangles;
import net.imglib2.roi.geom.real.Polygon2D;

import org.jzy3d.colors.Color;
import org.jzy3d.maths.Coord3d;
import org.jzy3d.plot3d.primitives.Drawable;
import org.jzy3d.plot3d.primitives.LineStrip;
import org.jzy3d.plot3d.primitives.Point;
import org.jzy3d.plot3d.primitives.Polygon;
import org.jzy3d.plot3d.primitives.Scatter;
import org.jzy3d.plot3d.primitives.Shape;
import org.scijava.util.ColorRGB;
import org.scijava.util.Colors;

import sc.fiji.snt.Path;
import sc.fiji.snt.analysis.ConvexHull3D;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SNTPoint;
import sc.fiji.snt.viewer.Viewer3D.Utils;

/**
 * An Annotation3D is a triangulated surface or a cloud of points (scatter)
 * rendered in {@link Viewer3D} that can be used to highlight nodes in a
 * {@link sc.fiji.snt.Tree Tree} or locations in a
 * {@link sc.fiji.snt.viewer.OBJMesh mesh}.
 *
 * @author Tiago Ferreira
 */
public class Annotation3D {

	protected static final int SCATTER = 0;
	protected static final int SURFACE = 1;
	protected static final int STRIP = 2;
	protected static final int Q_TIP = 3;
	protected static final int MERGE = 4;
	protected static final int SURFACE_AND_VOLUME = 5;

	private final Viewer3D viewer;
	private final Collection<? extends SNTPoint> points;
	private final Drawable drawable;
	private final int type;
	private float size;
	private String label;
	private double volume = Double.NaN;

	protected Annotation3D(final Viewer3D viewer, final Collection<Annotation3D> annotations) {
		this.viewer = viewer;
		this.type = MERGE;
		points = null;
		final Shape shape = new Shape();
		for (final Annotation3D annotation : annotations) {
			shape.add(annotation.getDrawable());
		}
		drawable = shape;
	}

	protected Annotation3D(final Viewer3D viewer, final Collection<? extends SNTPoint> points, final int type) {
		this.viewer = viewer;
		this.points = points;
		this.type = type;
		size = viewer.getDefaultThickness();
		switch (type) {
		case SCATTER:
			drawable = assembleScatter();
			break;
		case SURFACE:
			drawable = assembleSurface(false);
			break;
		case SURFACE_AND_VOLUME:
			drawable = assembleSurface(true);
			break;
		case STRIP:
			drawable = assembleStrip();
			break;
		case Q_TIP:
			drawable = assembleQTip();
			break;
		default:
			throw new IllegalArgumentException("Unrecognized type " + type);
		}
		setSize(-1);
	}

	public Annotation3D(final Mesh mesh, final ColorRGB color, final String label) {
		this.viewer = null;
		this.points = null;
		type = SURFACE;
		size = Viewer3D.DEF_NODE_RADIUS;
		drawable = meshToDrawable(mesh, new Color(color.getRed(), color.getGreen(), color.getBlue()));
		this.label = label;
	}

	public Annotation3D(final Polygon2D polygon, final ColorRGB color, final String label) {
		this.viewer = null;
		this.points = null;
		type = SURFACE;
		size = Viewer3D.DEF_NODE_RADIUS;
		drawable = polygonToDrawable(polygon, new Color(color.getRed(), color.getGreen(), color.getBlue()));
		this.label = label;
	}

	public Annotation3D(final Mesh mesh, final String label) {
		this.viewer = null;
		this.points = null;
		type = SURFACE;
		size = Viewer3D.DEF_NODE_RADIUS;
		drawable = meshToDrawable(mesh);
		this.label = label;
	}

	protected Annotation3D(final Viewer3D viewer, final SNTPoint point) {
		this(viewer, Collections.singleton(point), SCATTER);
	}

	private Drawable assembleSurface(boolean computeVolume) {
		ConvexHull3D hull = new ConvexHull3D(points, computeVolume);
		hull.compute();
		volume = hull.size();
		return meshToDrawable(hull.getMesh());
	}

	public static Drawable meshToDrawable(final Mesh mesh) {
		return meshToDrawable(mesh, new Color(1f, 1f, 1f, 0.05f));
	}

	private static Drawable polygonToDrawable(final Polygon2D polygon, final Color color) {
		final Polygon polyg = new Polygon();
		polygon.vertices().forEach(vx -> {
			final double[] pos = vx.positionAsDoubleArray();
			polyg.add(new Coord3d(pos[0], pos[1], 0));
		});
		final Shape surface = new Shape(Collections.singletonList(polyg));
		surface.setColor(color.alphaSelf(0.25f));
		surface.setWireframeColor(Utils.contrastColor(color).alphaSelf(0.8f));
		surface.setFaceDisplayed(true);
		surface.setWireframeDisplayed(true);
		return surface;
	}

	private static Drawable meshToDrawable(Mesh mesh, final Color color) {
		final Triangles faces = mesh.triangles();
		final Iterator<Triangle> faceIter = faces.iterator();
		final ArrayList<ArrayList<Coord3d>> coord3dFaces = new ArrayList<>();
		while (faceIter.hasNext()) {
			final ArrayList<Coord3d> simplex = new ArrayList<>();
			final Triangle t = faceIter.next();
			simplex.add(new Coord3d(t.v0x(), t.v0y(), t.v0z()));
			simplex.add(new Coord3d(t.v1x(), t.v1y(), t.v1z()));
			simplex.add(new Coord3d(t.v2x(), t.v2y(), t.v2z()));
			coord3dFaces.add(simplex);
		}
		final List<Polygon> polygons = new ArrayList<>();
		for (final ArrayList<Coord3d> face : coord3dFaces) {
			final Polygon polygon = new Polygon();
			polygon.add(new Point(face.get(0)));
			polygon.add(new Point(face.get(1)));
			polygon.add(new Point(face.get(2)));
			polygons.add(polygon);
		}
		final Shape surface = new Shape(polygons);
		surface.setColor(color.alphaSelf(0.25f));
		surface.setWireframeColor(Utils.contrastColor(color).alphaSelf(0.8f));
		surface.setFaceDisplayed(true);
		surface.setWireframeDisplayed(true);
		return surface;

	}

	private Drawable assembleScatter() {
		final Coord3d[] coords = new Coord3d[points.size()];
		final Color[] colors = new Color[points.size()];
		int idx = 0;
		for (final SNTPoint point : points) {
			coords[idx] = new Coord3d(point.getX(), point.getY(), point.getZ());
			if (point instanceof PointInImage && ((PointInImage) point).getPath() != null) {
				final Path path = ((PointInImage) point).getPath();
				final int nodeIndex = path.getNodeIndex(((PointInImage) point));
				if (nodeIndex < -1) {
					colors[idx] = viewer.getDefColor();
				} else {
					colors[idx] = viewer
							.fromAWTColor((path.hasNodeColors()) ? path.getNodeColor(nodeIndex) : path.getColor());
				}
			} else {
				colors[idx] = viewer.getDefColor();
			}
			idx++;
		}
		final Scatter scatter = new Scatter();
		scatter.setData(coords);
		scatter.setColors(colors);
		return scatter;
	}

	private Drawable assembleStrip() {
		final ArrayList<Point> linePoints = new ArrayList<>(points.size());
		for (final SNTPoint point : points) {
			if (point == null)
				continue;
			final Coord3d coord = new Coord3d(point.getX(), point.getY(), point.getZ());
			Color color = viewer.getDefColor();
			if (point instanceof PointInImage && ((PointInImage) point).getPath() != null) {
				final Path path = ((PointInImage) point).getPath();
				final int nodeIndex = path.getNodeIndex(((PointInImage) point));
				if (nodeIndex > -1) {
					color = viewer
							.fromAWTColor((path.hasNodeColors()) ? path.getNodeColor(nodeIndex) : path.getColor());
				}
			}
			linePoints.add(new Point(coord, color));
		}
		final LineStrip line = new LineStrip();
		line.addAll(linePoints);
		// line.setShowPoints(true);
		// line.setStipple(true);
		// line.setStippleFactor(2);
		// line.setStipplePattern((short) 0xAAAA);
		return line;
	}

	private Drawable assembleQTip() {
		final Shape shape = new Shape();
		final LineStrip line = (LineStrip) assembleStrip();
		shape.add(line);
		if (line.getPoints().size() >= 2)
			shape.add(assembleScatter());
		return shape;
	}

	/**
	 * Sets the annotation width.
	 *
	 * @param size the new width. A negative value will set width to
	 *             {@link Viewer3D}'s default.
	 */
	public void setSize(final float size) {
		this.size = (size < 0) ? viewer.getDefaultThickness() : size;
		if (drawable == null)
			return;
		switch (type) {
		case SCATTER:
			((Scatter) drawable).setWidth(this.size);
			break;
		case SURFACE:
		case SURFACE_AND_VOLUME:
			((Shape) drawable).setWireframeWidth(this.size);
			break;
		case STRIP:
			((LineStrip) drawable).setWidth(this.size);
			break;
		case Q_TIP:
		case MERGE:
			setShapeWidth(size);
			break;
		default:
			throw new IllegalArgumentException("Unrecognized type " + type);
		}
	}

	private void setShapeWidth(final float size) {
		for (final Drawable drawable : ((Shape) drawable).getDrawables()) {
			if (drawable instanceof LineStrip) {
				((LineStrip) drawable).setWidth(this.size / 4);
			} else if (drawable instanceof Scatter) {
				((Scatter) drawable).setWidth(this.size);
			} else if (drawable instanceof Shape) {
				((Shape) drawable).setWireframeWidth(size);
			}
		}
	}

	/**
	 * Assigns a color to the annotation.
	 * 
	 * @param color               the color to render the annotation. (If the
	 *                            annotation contains a wireframe, the wireframe is
	 *                            rendered using a "contrast" color computed from
	 *                            this one.
	 * @param transparencyPercent the color transparency (in percentage)
	 */
	public void setColor(final ColorRGB color, final double transparencyPercent) {
		final ColorRGB inputColor = (color == null) ? Colors.WHITE : color;
		final Color c = new Color(inputColor.getRed(), inputColor.getGreen(), inputColor.getBlue(),
				(int) Math.round((100 - transparencyPercent) * 255 / 100));
		if (drawable == null)
			return;
		switch (type) {
		case SCATTER:
			((Scatter) drawable).setColors(null);
			((Scatter) drawable).setColor(c);
			break;
		case SURFACE:
		case SURFACE_AND_VOLUME:
			((Shape) drawable).setColor(c);
			((Shape) drawable).setWireframeColor(Viewer3D.Utils.contrastColor(c));
			break;
		case STRIP:
			((LineStrip) drawable).setColor(c);
			break;
		case Q_TIP:
		case MERGE:
			for (final Drawable drawable : ((Shape) drawable).getDrawables()) {
				if (drawable instanceof LineStrip) {
					((LineStrip) drawable).setColor(c);
				} else if (drawable instanceof Scatter) {
					((Scatter) drawable).setColors(null);
					((Scatter) drawable).setColor(c);
				} else if (drawable instanceof Shape) {
					((Shape) drawable).setColor(c);
					((Shape) drawable).setWireframeColor(c);
				}
			}
			break;
		default:
			throw new IllegalArgumentException("Unrecognized type " + type);
		}
	}

	/**
	 * Script friendly method to assign a color to the annotation.
	 *
	 * @param color               the color to render the imported file, either a 1)
	 *                            HTML color codes starting with hash ({@code #}), a
	 *                            color preset ("red", "blue", etc.), or integer
	 *                            triples of the form {@code r,g,b} and range
	 *                            {@code [0, 255]}
	 * @param transparencyPercent the color transparency (in percentage)
	 */
	public void setColor(final String color, final double transparencyPercent) {
		setColor(new ColorRGB(color), transparencyPercent);
	}

	/**
	 * Script friendly method to assign a color to the annotation.
	 *
	 * @param color the color to render the imported file, either a 1) HTML color
	 *              codes starting with hash ({@code #}), a color preset ("red",
	 *              "blue", etc.), or integer triples of the form {@code r,g,b} and
	 *              range {@code [0, 255]}
	 */
	public void setColor(final String color) {
		setColor(new ColorRGB(color), 10d);
	}

	/**
	 * Determines whether the mesh bounding box should be displayed.
	 * 
	 * @param boundingBoxColor the color of the mesh bounding box. If null, no
	 *                         bounding box is displayed
	 */
	public void setBoundingBoxColor(final ColorRGB boundingBoxColor) {
		final Color c = (boundingBoxColor == null) ? null
				: new Color(boundingBoxColor.getRed(), boundingBoxColor.getGreen(), boundingBoxColor.getBlue(),
						boundingBoxColor.getAlpha());
		drawable.setBoundingBoxColor(c);
		drawable.setBoundingBoxDisplayed(c != null);
	}

	/**
	 * Gets the annotation label
	 *
	 * @return the label, as listed in Reconstruction Viewer's list.
	 */
	public String getLabel() {
		return label;
	}

	/**
	 * Gets the type of this annotation.
	 *
	 * @return the annotation type. Either {@code cloud} (point cloud),
	 *         {@code surface}, {@code line}, or {@code mixed} (composite shape).
	 */
	public String getType() {
		switch (type) {
		case SCATTER:
			return "cloud";
		case SURFACE:
		case SURFACE_AND_VOLUME:
			return "surface";
			case STRIP:
		case Q_TIP:
			return "line";
		case MERGE:
			return "mixed";
		default:
			return "unknown";
		}
	}

	protected void setLabel(final String label) {
		this.label = label;
	}

	/**
	 * Returns the center of this annotation bounding box.
	 *
	 * @return the barycentre of this annotation. All coordinates are set to
	 *         Double.NaN if the bounding box is not available.
	 */
	public SNTPoint getBarycentre() {
		final Coord3d center = drawable.getBarycentre();
		return new PointInImage(center.x, center.y, center.z);
	}
	
	public double getVolume() {
		return volume;
	}

	/**
	 * Returns the AbstractDrawable associated with this annotation.
	 *
	 * @return the AbstractDrawable
	 */
	public Drawable getDrawable() {
		return drawable;
	}

}
