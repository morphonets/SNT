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
	private Color color;
	private final TracerCanvas canvas;
	private double size = -1; // see assignRenderingSize()
	private int type;
	private boolean editable;
	protected double x;
	protected double y;

	/**
	 * Creates a path node from a {@link PointInImage}.
	 *
	 * @param pim the position of the node (z-position ignored)
	 * @param canvas the canvas to render this node. Cannot be null
	 */
	public PathNode(final PointInImage pim, final TracerCanvas canvas) {
		this.path = pim.onPath;
		this.canvas = canvas;
		setXYcoordinates(pim);
	}

	/**
	 * Creates a node from a Path position.
	 *
	 * @param path the path holding this node. Cannot be null
	 * @param index the position of this node within path
	 * @param canvas the canvas to render this node. Cannot be null
	 */
	public PathNode(final Path path, final int index, final TracerCanvas canvas) {
		this.path = path;
		this.canvas = canvas;
		color = path.getNodeColor(index);
		setXYcoordinates(path.getNodeWithoutChecks(index));

		// Define which type of node we're dealing with
		if (path.size() == 1) {
			type = HERMIT;
		}
		else if (index == 0 && path.startJoins == null) {
			type = START;
		}
		else if (index == path.size() - 1 && path.endJoins == null) {
			type = END;
		}
		else if ((index == 0 && path.startJoins != null) || (index == path.size() -
			1 && path.endJoins != null))
		{
			type = JOINT;
		}
		else {
			type = SLAB;
		}
	}

	private void setXYcoordinates(final PointInImage pim) {
		switch (canvas.getPlane()) {
			case MultiDThreePanes.XY_PLANE:
				x = canvas.myScreenXDprecise(path.canvasOffset.x + pim.x /
					path.x_spacing);
				y = canvas.myScreenYDprecise(path.canvasOffset.y + pim.y /
					path.y_spacing);
				break;
			case MultiDThreePanes.XZ_PLANE:
				x = canvas.myScreenXDprecise(path.canvasOffset.x + pim.x /
					path.x_spacing);
				y = canvas.myScreenYDprecise(path.canvasOffset.z + pim.z /
					path.z_spacing);
				break;
			case MultiDThreePanes.ZY_PLANE:
				x = canvas.myScreenXDprecise(path.canvasOffset.z + pim.z /
					path.z_spacing);
				y = canvas.myScreenYDprecise(path.canvasOffset.y + pim.y /
					path.y_spacing);
				break;
			default:
				throw new IllegalArgumentException("BUG: Unknown plane! (" + canvas
					.getPlane() + ")");
		}
	}

	private void assignRenderingSize() {
		if (size > -1) return; // size already specified via setSize()

		// TODO: set size according to path thickness?
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
		if (editable) size *= 2;
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
	public int type() {
		return type;
	}

	/**
	 * Draws this node.
	 *
	 * @param g the Graphics2D drawing instance
	 * @param c the rendering color of this node. Note that this parameter is
	 *          ignored if a color has already been defined through
	 *          {@link Path#setNodeColors(Color[])}
	 */
	public void draw(final Graphics2D g, final Color c) {

		if (path.isBeingEdited() && !editable) return; // draw only editable node

		assignRenderingSize();
		final Shape node = new Ellipse2D.Double(x - size / 2, y - size / 2, size,
			size);
		if (color == null) color = c;
		if (editable) {
			// opaque crosshair and border, transparent fill
			g.setColor(color);
			final Stroke stroke = g.getStroke();
			g.setStroke(new BasicStroke(3));
			final double length = size / 2;
			final double offset = size / 4;
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
				g.setColor(color);
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

	/**
	 * @return whether or not this node should be rendered as editable.
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
