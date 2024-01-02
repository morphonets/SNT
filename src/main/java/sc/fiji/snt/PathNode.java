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

package sc.fiji.snt;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;

import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SNTColor;
import sc.fiji.snt.hyperpanes.MultiDThreePanes;

/**
 * Convenience class used to render {@link Path} nodes (vertices) in an
 * {@link TracerCanvas}.
 *
 * @author Tiago Ferreira
 */
class PathNode {

	/** Flag describing a start point node */
	public static final int START = 1;
	/** Flag describing an end point node */
	public static final int END = 2;
	/** Flag describing a fork point node */
	public static final int JOINT = 3;
	/** Flag describing a slab node */
	public static final int SLAB = 4;
	/** Flag describing a single point path */
	public static final int HERMIT = 5;

	private final Path path;
	private final int index;
	private final TracerCanvas canvas;
	private double size = -1; // see assignRenderingSize()
	private int slice = -1; // see getSlice()
	private int type;
	private boolean editable;
	private double x;
	private double y;
	private Color color;

	/**
	 * Creates a path node from a {@link PointInImage}.
	 *
	 * @param pim the position of the node (z-position ignored)
	 * @param canvas the canvas to render this node. Cannot be null
	 */
	public PathNode(final PointInImage pim, final int index, final TracerCanvas canvas) {
		this.path = pim.onPath;
		this.canvas = canvas;
		this.index = index;
		x = getScreenCoordinateX(pim);
		y = getScreenCoordinateY(pim);
	}

	public PathNode(final Path path, final int index, final int type, final TracerCanvas canvas) {
		this(path.getNodeWithoutChecks(index), index, canvas);
		this.type = type;
	}

	/**
	 * Creates a node from a Path position.
	 *
	 * @param path the path holding this node. Cannot be null
	 * @param index the position of this node within path
	 * @param canvas the canvas to render this node. Cannot be null
	 */
	public PathNode(final Path path, final int index, final TracerCanvas canvas) {
		this(path.getNodeWithoutChecks(index), index, canvas);

		// Define which type of node we're dealing with
		if (path.size() == 1) {
			type = HERMIT;
		}
		else if (index == 0) {
			type = (path.startJoins == null) ? START : JOINT;
		}
		else if (index == path.size() - 1) {
			type = END;
		}
		else {
			type = SLAB;
		}
	}

	private double getScreenCoordinateX(final PointInImage pim) {
		switch (canvas.getPlane()) {
			case MultiDThreePanes.XY_PLANE:
			case MultiDThreePanes.XZ_PLANE:
				return canvas.myScreenXDprecise(path.canvasOffset.x + pim.x /
					path.x_spacing);
			case MultiDThreePanes.ZY_PLANE:
				return canvas.myScreenXDprecise(path.canvasOffset.z + pim.z /
					path.z_spacing);
			default:
				throw new IllegalArgumentException("BUG: Unknown plane! (" + canvas
					.getPlane() + ")");
		}
	}

	private double getScreenCoordinateY(final PointInImage pim) {
		switch (canvas.getPlane()) {
			case MultiDThreePanes.XY_PLANE:
				return canvas.myScreenYDprecise(path.canvasOffset.y + pim.y /
					path.y_spacing);
			case MultiDThreePanes.XZ_PLANE:
				return canvas.myScreenYDprecise(path.canvasOffset.z + pim.z /
					path.z_spacing);
			case MultiDThreePanes.ZY_PLANE:
				return canvas.myScreenYDprecise(path.canvasOffset.y + pim.y /
					path.y_spacing);
			default:
				throw new IllegalArgumentException("BUG: Unknown plane! (" + canvas
					.getPlane() + ")");
		}
	}

	protected int getSlice() {
		if (slice == -1) {
			switch (canvas.getPlane()) {
			case MultiDThreePanes.XY_PLANE:
				slice = path.getZUnscaled(index);
				break;
			case MultiDThreePanes.XZ_PLANE:
				slice = path.getYUnscaled(index);
				break;
			case MultiDThreePanes.ZY_PLANE:
				slice = path.getXUnscaled(index);
				break;
			default:
				throw new IllegalArgumentException("BUG: Unknown plane: " + canvas.getPlane());
			}
		}
		return slice;
	}

	private void assignColor(final Graphics2D g, final Color fallbackColor) {
		if (color == null) {
			if (path.hasNodeColors())
				color = path.getNodeColor(index);
			else 
				color = fallbackColor;
			g.setColor(color);
		}
	}

	private void assignRenderingSize() {
		if (size > -1) return; // size already specified via setSize()

		final double baseline = canvas.nodeDiameter();
		switch (type) {
			case HERMIT:
				size = 5 * baseline;
				break;
			case START:
				size = 2 * baseline;
				break;
			case END:
				size = 1.5 * baseline;
				break;
			case JOINT:
				size = 3 * baseline;
				break;
			case SLAB:
			default:
				size = baseline;
		}
		if (isEditable()) size *= 2.5;
	}

	/**
	 * @return the rendering diameter of this node.
	 */
	public double getSize() {
		return size;
	}

	/**
	 * @param size the rendering diameter of this node. Set it to -1 to use the
	 *          default value.
	 * @see TracerCanvas#nodeDiameter()
	 */
	public void setSize(final double size) {
		this.size = size;
	}

	/**
	 * Returns the type of node.
	 *
	 * @return the node type: PathNode.END, PathNode.JOINT, PathNode.SLAB, etc.
	 */
	public int getType() {
		return type;
	}

	public void setType(final int type) {
		this.type = type;
	}

	/**
	 * Draws this node.
	 *
	 * @param g             the Graphics2D drawing instance
	 * @param fallbackColor the rendering color of this node. Note that this
	 *                      parameter is ignored if a color has already been defined
	 *                      through {@link Path#setNodeColors(Color[])}
	 */
	public void draw(final Graphics2D g, final Color fallbackColor) {

		// Ensure subsequent shapes associated with this node (diameter,
		// segments, etc.) are rendered with the appropriate color
		assignColor(g, fallbackColor);

		if (path.isBeingEdited() && !isEditable()) {
			return; // draw only editable node
		}

		assignRenderingSize();
		final Shape node = new Ellipse2D.Double(x - size / 2, y - size / 2, size, size);
		if (editable) {
			// opaque crosshair and border, transparent fill
			final Stroke stroke = g.getStroke();
			g.setStroke(new BasicStroke(4));
			final double length = size / 1.5;
			final double offset = size / 3;
			g.draw(new Line2D.Double(x - offset - length, y, x - offset, y));
			g.draw(new Line2D.Double(x + offset + length, y, x + offset, y));
			g.draw(new Line2D.Double(x, y - offset - length, x, y - offset));
			g.draw(new Line2D.Double(x, y + offset + length, x, y + offset));
			g.draw(node);
			g.setColor(SNTColor.alphaColor(color, 20));
			g.fill(node);
			g.setStroke(stroke);
		}
		else {

			if (path.isSelected()) {
				// opaque border and more opaque fill
				g.draw(node);
				g.setColor(SNTColor.alphaColor(color, 80));
				g.fill(node);
			}
			else {
				// semi-border and more transparent fill
				g.setColor(SNTColor.alphaColor(color, 50));
				g.fill(node);
			}

		}

		// g.setColor(c); // not really needed

	}

	protected void drawConnection(final Graphics2D g, final PathNode other) {
		g.draw(new Line2D.Double(x, y, other.x, other.y));
	}

	private double getSizeInPlane(final double xSize, final double ySize, final double zSize) {
		switch (canvas.getPlane()) {
			case MultiDThreePanes.XY_PLANE:
				return Math.sqrt(xSize * xSize + ySize * ySize);
			case MultiDThreePanes.XZ_PLANE:
				return Math.sqrt(xSize * xSize + zSize * zSize);
			case MultiDThreePanes.ZY_PLANE:
				return Math.sqrt(zSize * zSize + ySize * ySize);
			default:
				throw new IllegalArgumentException("BUG: Unknown plane! (" + canvas
					.getPlane() + ")");
		}
	}

	protected void drawDiameter(final Graphics2D g2, final int slice, final int either_side) {
		if (!path.hasRadii() ) return; // cannot proceed

		// Cross the tangents with a unit z vector:
		final double n_x = 0;
		final double n_y = 0;
		final double n_z = 1;
		final double t_x = path.tangents_x[index];
		final double t_y = path.tangents_y[index];
		final double t_z = path.tangents_z[index];
		final double cross_x = n_y * t_z - n_z * t_y;
		final double cross_y = n_z * t_x - n_x * t_z;
		final double cross_z = n_x * t_y - n_y * t_x;

		final double sizeInPlane = getSizeInPlane(cross_x, cross_y, cross_z);
		final double normalized_cross_x = cross_x / sizeInPlane;
		final double normalized_cross_y = cross_y / sizeInPlane;
		final double normalized_cross_z = cross_z / sizeInPlane;
		final double zdiff = Math.abs((slice - getSlice()) * path.z_spacing);
		final double realRadius = path.radii[index];

		if (either_side < 0 || zdiff <= realRadius) {
			double effective_radius;
			if (either_side < 0) {
				effective_radius = realRadius;
			} else {
				effective_radius = Math.sqrt(realRadius * realRadius - zdiff * zdiff);
			}
			final PointInImage left = new PointInImage();
			final PointInImage right = new PointInImage();
			left.x = path.precise_x_positions[index] + normalized_cross_x * effective_radius;
			left.y = path.precise_y_positions[index] + normalized_cross_y * effective_radius;
			left.z = path.precise_z_positions[index] + normalized_cross_z * effective_radius;
			right.x = path.precise_x_positions[index] - normalized_cross_x * effective_radius;
			right.y = path.precise_y_positions[index] - normalized_cross_y * effective_radius;
			right.z = path.precise_z_positions[index] - normalized_cross_z * effective_radius;
			g2.draw(new Line2D.Double(getScreenCoordinateX(left), getScreenCoordinateY(left),
					getScreenCoordinateX(right), getScreenCoordinateY(right)));
		}
	}

	/**
	 * @return whether this node should be rendered as editable.
	 */
	public boolean isEditable() {
		return editable;
	}

	/**
	 * Enables the node as editable/non-editable.
	 *
	 * @param editable true to render the node as editable.
	 */
	public void setEditable(final boolean editable) {
		this.editable = editable;
	}

}
