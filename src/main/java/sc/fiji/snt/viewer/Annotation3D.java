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

package sc.fiji.snt.viewer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.jzy3d.colors.Color;
import org.jzy3d.colors.ColorMapper;
import org.jzy3d.colors.IMultiColorable;
import org.jzy3d.colors.colormaps.AbstractColorMap;
import org.jzy3d.colors.colormaps.ColorMapGrayscale;
import org.jzy3d.colors.colormaps.ColorMapHotCold;
import org.jzy3d.colors.colormaps.ColorMapRBG;
import org.jzy3d.colors.colormaps.ColorMapRainbow;
import org.jzy3d.colors.colormaps.ColorMapRedAndGreen;
import org.jzy3d.colors.colormaps.ColorMapWhiteBlue;
import org.jzy3d.colors.colormaps.ColorMapWhiteGreen;
import org.jzy3d.colors.colormaps.ColorMapWhiteRed;
import org.jzy3d.maths.BoundingBox3d;
import org.jzy3d.maths.Coord3d;
import org.jzy3d.maths.Range;
import org.jzy3d.plot3d.primitives.Drawable;
import org.jzy3d.plot3d.primitives.LineStrip;
import org.jzy3d.plot3d.primitives.ParallelepipedComposite;
import org.jzy3d.plot3d.primitives.Point;
import org.jzy3d.plot3d.primitives.Polygon;
import org.jzy3d.plot3d.primitives.Scatter;
import org.jzy3d.plot3d.primitives.Shape;
import org.jzy3d.plot3d.primitives.Wireframeable;
import org.jzy3d.plot3d.primitives.enlightables.EnlightableSphere;
import org.scijava.util.ColorRGB;

import net.imagej.mesh.Mesh;
import net.imagej.mesh.Triangle;
import net.imagej.mesh.Triangles;
import net.imglib2.roi.geom.real.Polygon2D;
import sc.fiji.snt.Path;
import sc.fiji.snt.analysis.AbstractConvexHull;
import sc.fiji.snt.analysis.ConvexHull2D;
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
	protected static final int PLANE = 6;
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
		case SURFACE, SURFACE_AND_VOLUME:
			drawable = assembleSurface();
			break;
		case STRIP:
			drawable = assembleStrip();
			break;
		case Q_TIP:
			drawable = assembleQTip();
			break;
		case PLANE:
			drawable = assemblePlane();
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

	public Annotation3D(final AbstractConvexHull hull, final ColorRGB color, final String label) {
		this.viewer = null;
		this.points = null;
		type = SURFACE;
		size = Viewer3D.DEF_NODE_RADIUS;
		this.label = label;
		if (hull instanceof ConvexHull3D) {
			drawable = meshToDrawable(((ConvexHull3D) hull).getMesh(), new Color(color.getRed(), color.getGreen(), color.getBlue()));
		} else if (hull instanceof ConvexHull2D) {
			drawable = polygonToDrawable(((ConvexHull2D) hull).getPolygon(), new Color(color.getRed(), color.getGreen(), color.getBlue()));
		} else {
			throw new IllegalArgumentException("Unsupported ConvexHull");
		}
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

	private Drawable assembleSurface() {
		ConvexHull3D hull = new ConvexHull3D(points);
		hull.compute();
		volume = hull.size();
		return meshToDrawable(hull.getMesh());
	}

	public static Drawable meshToDrawable(final Mesh mesh) {
		return meshToDrawable(mesh, new Color(1f, 1f, 1f, 0.05f));
	}

	public static final List<String> COLORMAPS = List.of("grayscale", "hotcold", "rainbow", "rbg", "redgreen",
			"whiteblue", "whitegreen", "whitered");

	public boolean isColorCodeAllowed() {
		return drawable instanceof IMultiColorable;
	}

	/**
	 *
	 * @param colormap one of {@link #COLORMAPS}, i.e., "grayscale", "hotcold", "rgb", "redgreen", "whiteblue", etc.
	 * @param axis the range axis: either "x", "y", or "z".
     */
	public void colorCode(final String colormap, final String axis) {
		if (!isColorCodeAllowed())
			throw new IllegalArgumentException("The current " + getType() + "annot. cannot be colorcoded");
		AbstractColorMap cm = switch (colormap.toLowerCase()) {
            case "grayscale" -> new ColorMapGrayscale();
            case "hotcold" -> new ColorMapHotCold();
            case "rainbow" -> new ColorMapRainbow();
            case "rbg" -> new ColorMapRBG();
            case "redgreen", "redandgreen" -> new ColorMapRedAndGreen();
            case "whiteblue", "whiteandblue" -> new ColorMapWhiteBlue();
            case "whitegreen", "whiteandgreen" -> new ColorMapWhiteGreen();
            case "whitered", "whiteandred" -> new ColorMapWhiteRed();
            default -> throw new IllegalArgumentException("Invalid colormap. Valid options: " + COLORMAPS.toString());
        };
        ((IMultiColorable) drawable).setColorMapper(new ColorMapper(cm, getRange(axis.toLowerCase())));
	}


	Range getRange(final String axis) {
        return switch (axis) {
            case "x" -> drawable.getBounds().getXRange();
            case "y" -> drawable.getBounds().getYRange();
            default -> drawable.getBounds().getZRange();
        };

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
		if (points.size() == 1) {
			return new EnlightableSphere(coords[0], size, 15, colors[0]);
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

	private Drawable assemblePlane() {
		final Iterator<? extends SNTPoint> it = points.iterator();
		return new Parallelepiped(it.next(), it.next());
	}

	/**
	 * Sets the annotation width.
	 *
	 * @param floatSize the new width. A negative value will set width to
	 *             {@link Viewer3D}'s default.
	 */
	public void setSize(final Number floatSize) {
		final float size = floatSize.floatValue();
		this.size = (size < 0) ? viewer.getDefaultThickness() : size;
		if (drawable == null)
			return;
		switch (type) {
		case SCATTER:
			if (drawable instanceof Scatter)
				((Scatter) drawable).setWidth(this.size);
			else if (drawable instanceof EnlightableSphere)
				((EnlightableSphere) drawable).setVolume(size);
			break;
		case SURFACE:
		case SURFACE_AND_VOLUME:
			((Shape) drawable).setWireframeWidth(this.size);
			break;
		case STRIP:
			((LineStrip) drawable).setWidth(this.size);
			break;
		case PLANE:
			((Parallelepiped) drawable).setWireframeWidth(this.size);
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
	 * @param color               the color to render the annotation. If the
	 *                            annotation contains a wireframe, the wireframe is
	 *                            rendered using a "contrast" color computed from
	 *                            this one.
	 * @param transparencyPercent the color transparency (in percentage)
	 */
	public void setColor(final ColorRGB color, final double transparencyPercent) {
		Color fallback = getDrawableColor();
		if (fallback == null) fallback = Color.YELLOW;
		final float a = (transparencyPercent == -1) ? fallback.a : 	(float)( (100 - transparencyPercent) / 100);
		final Color c = (color == null) ? fallback : colorFromColorRGB(color);
		c.a = a;
		if (drawable == null)
			return;
		switch (type) {
		case SCATTER:
			if (drawable instanceof Scatter) {
				((Scatter) drawable).setColors(null);
				((Scatter) drawable).setColor(c);
			} else if (drawable instanceof EnlightableSphere)
				((EnlightableSphere) drawable).setColor(c);
			break;
		case SURFACE:
		case SURFACE_AND_VOLUME:
			((Shape) drawable).setColor(c);
			((Shape) drawable).setWireframeColor(Viewer3D.Utils.contrastColor(c));
			break;
		case STRIP:
			((LineStrip) drawable).setColor(c);
			break;
		case PLANE:
			((Parallelepiped) drawable).setColor(c);
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

	public ColorRGB getColor() {
		final Color c = getDrawableColor();
		if (c == null) return null;
		return new ColorRGB((int) (c.r * 255 + 0.5), (int) (c.g * 255 + 0.5), (int) (c.b * 255 + 0.5));
	}

	protected Color getDrawableColor() {
		if (drawable == null)
			return null;
		switch (type) {
		case SCATTER:
			if (drawable instanceof Scatter)
				return ((Scatter) drawable).getColor();
			else if (drawable instanceof EnlightableSphere)
				return ((EnlightableSphere) drawable).getColor();
		case SURFACE:
		case SURFACE_AND_VOLUME:
			return ((Shape) drawable).getColor();
		case STRIP:
			return ((LineStrip) drawable).getColor();
		case PLANE:
			return ((Parallelepiped) drawable).getColor();
		case Q_TIP:
		case MERGE:
			for (final Drawable drawable : ((Shape) drawable).getDrawables()) {
				if (drawable instanceof LineStrip) {
					return ((LineStrip) drawable).getColor();
				} else if (drawable instanceof Scatter) {
					return ((Scatter) drawable).getColor();
				} else if (drawable instanceof Shape) {
					return ((Shape) drawable).getColor();
				}
			}
			return null;
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
	 * Script friendly method to assign a transparency to the annotation.
	 *
	 * @param transparencyPercent the color transparency (in percentage)
	 */
	public void setTransparency(final double transparencyPercent) {
		setColor(getColor(), transparencyPercent);
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
		setColor(new ColorRGB(color), 0);
	}

	/**
	 * Script friendly method to assign a wireframe color to the annotation.
	 *
	 * @param color the wireframe color. Ignored if the annotation has no wireframe.
	 */
	public void setWireframeColor(final String color) {
		setWireframeColor(new ColorRGB(color));
	}

	/**
	 * Assigns a wireframe color to the annotation.
	 *
	 * @param color the wireframe color. Ignored if the annotation has no wireframe.
	 */
	public void setWireframeColor(final ColorRGB color) {
		if (drawable instanceof Wireframeable) {
			((Wireframeable) drawable).setWireframeColor(colorFromColorRGB(color));
		}
	}

	/**
	 * Determines whether the mesh bounding box should be displayed.
	 * 
	 * @param boundingBoxColor the color of the mesh bounding box. If null, no
	 *                         bounding box is displayed
	 */
	public void setBoundingBoxColor(final ColorRGB boundingBoxColor) {
		final Color c = colorFromColorRGB(boundingBoxColor);
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
	 *         {@code surface}, {@code line}, {@code plane}, or {@code mixed} (composite shape).
	 */
	public String getType() {
        return switch (type) {
            case SCATTER -> "cloud";
            case SURFACE, SURFACE_AND_VOLUME -> "surface";
            case STRIP, Q_TIP -> "line";
            case PLANE -> "plane";
            case MERGE -> "mixed";
            default -> "unknown";
        };
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

	private Color colorFromColorRGB(final ColorRGB boundingBoxColor) {
        return (boundingBoxColor == null) ? null
                : new Color(boundingBoxColor.getRed(), boundingBoxColor.getGreen(), boundingBoxColor.getBlue(),
                        boundingBoxColor.getAlpha());
	}

	private static class Parallelepiped extends ParallelepipedComposite {


		Parallelepiped(final SNTPoint p1, final SNTPoint p2) {
			 super(new BoundingBox3d(p1.getX(), p2.getX(), p1.getY(), p2.getY(), p1.getZ(), p2.getZ()));
		}
	
		@Override
		public void setColor(final Color c) {
			super.setColor(c);
			super.setWireframeColor(c);
			if (quads != null) { // this is not needed!?
				for (final Polygon quad : quads) {
					quad.setColor(c);
					quad.setWireframeColor(c);
				}
			}
		}
	}
}
