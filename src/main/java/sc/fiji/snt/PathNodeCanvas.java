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

package sc.fiji.snt;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
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
class PathNodeCanvas {

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

    // Static reusable shapes to avoid allocations in render loop
    private static final BasicStroke EDITABLE_STROKE = new BasicStroke(4);
    private static final Line2D.Double REUSABLE_LINE = new Line2D.Double();
    private static final Ellipse2D.Double REUSABLE_ELLIPSE = new Ellipse2D.Double();

	private final Path path;
	private final int index;
	private final TracerCanvas canvas;
	private double size = -1; // see assignRenderingSize()
	private int slice = -1; // see getSlice()
	private int type;
	private boolean editable;
	private final double x;
	private final double y;
	private Color color;

	/**
	 * Creates a path node from a {@link PointInImage}.
	 *
	 * @param pim the position of the node (z-position ignored)
	 * @param canvas the canvas to render this node. Cannot be null
	 */
	public PathNodeCanvas(final PointInImage pim, final int index, final TracerCanvas canvas) {
		this.path = pim.onPath;
		this.canvas = canvas;
		this.index = index;
		x = getScreenCoordinateX(pim);
		y = getScreenCoordinateY(pim);
	}

	public PathNodeCanvas(final Path path, final int index, final int type, final TracerCanvas canvas) {
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
	public PathNodeCanvas(final Path path, final int index, final TracerCanvas canvas) {
		this(path.getNodeWithoutChecks(index), index, canvas);

		// Define which type of node we're dealing with
		if (path.size() == 1) {
			type = HERMIT;
		}
		else if (index == 0) {
			type = (path.parentPath == null) ? START : JOINT;
		}
		else if (index == path.size() - 1) {
			type = END;
		}
		else {
			type = SLAB;
		}
	}

	private double getScreenCoordinateX(final PointInImage pim) {
        return switch (canvas.getPlane()) {
            case MultiDThreePanes.XY_PLANE, MultiDThreePanes.XZ_PLANE ->
                    canvas.myScreenXDprecise(path.canvasOffset.x + pim.x / path.x_spacing);
            case MultiDThreePanes.ZY_PLANE ->
                    canvas.myScreenXDprecise(path.canvasOffset.z + pim.z / path.z_spacing);
            default ->
                    throw new IllegalArgumentException("BUG: Unknown plane! (" + canvas.getPlane() + ")");
        };
	}

	private double getScreenCoordinateY(final PointInImage pim) {
        return switch (canvas.getPlane()) {
            case MultiDThreePanes.XY_PLANE, MultiDThreePanes.ZY_PLANE ->
                    canvas.myScreenYDprecise(path.canvasOffset.y + pim.y / path.y_spacing);
            case MultiDThreePanes.XZ_PLANE ->
                    canvas.myScreenYDprecise(path.canvasOffset.z + pim.z / path.z_spacing);
            default ->
                    throw new IllegalArgumentException("BUG: Unknown plane! (" + canvas.getPlane() + ")");
        };
	}

    private double getScreenCoordinateX(final double pimX, final double pimZ) {
        return switch (canvas.getPlane()) {
            case MultiDThreePanes.XY_PLANE, MultiDThreePanes.XZ_PLANE ->
                    canvas.myScreenXDprecise(path.canvasOffset.x + pimX / path.x_spacing);
            case MultiDThreePanes.ZY_PLANE ->
                    canvas.myScreenXDprecise(path.canvasOffset.z + pimZ / path.z_spacing);
            default ->
                    throw new IllegalArgumentException("BUG: Unknown plane! (" + canvas.getPlane() + ")");
        };
    }

    private double getScreenCoordinateY(final double pimY, final double pimZ) {
        return switch (canvas.getPlane()) {
            case MultiDThreePanes.XY_PLANE, MultiDThreePanes.ZY_PLANE ->
                    canvas.myScreenYDprecise(path.canvasOffset.y + pimY / path.y_spacing);
            case MultiDThreePanes.XZ_PLANE ->
                    canvas.myScreenYDprecise(path.canvasOffset.z + pimZ / path.z_spacing);
            default ->
                    throw new IllegalArgumentException("BUG: Unknown plane! (" + canvas.getPlane() + ")");
        };
    }

	protected int getSlice() {
		if (slice == -1) {
			switch (canvas.getPlane()) {
                case MultiDThreePanes.XY_PLANE -> slice = path.getZUnscaled(index);
                case MultiDThreePanes.XZ_PLANE -> slice = path.getYUnscaled(index);
                case MultiDThreePanes.ZY_PLANE -> slice = path.getXUnscaled(index);
                default -> throw new IllegalArgumentException("BUG: Unknown plane: " + canvas.getPlane());
            }
		}
		return slice;
	}

	private void assignColor(final Graphics2D g, final Color fallbackColor) {
        if (path.size() > 0) color = path.getNodeColor(index);
		if (color == null) color = fallbackColor;
        g.setColor(color);
	}

	private void assignRenderingSize() {
		if (size > -1) return; // size already specified via setSize()
		final double baseline = canvas.nodeDiameter();
        switch (type) {
            case HERMIT -> size = 5 * baseline;
            case START -> size = 2 * baseline;
            case END -> size = 1.5 * baseline;
            case JOINT -> size = 3 * baseline;
            default -> size = baseline;
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
	 * @return the node type: PathNodeCanvas.END, PathNodeCanvas.JOINT, PathNodeCanvas.SLAB, etc.
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
        assignColor(g, fallbackColor);

        if (path.isBeingEdited() && !isEditable()) {
            return;
        }

        assignRenderingSize();
        REUSABLE_ELLIPSE.setFrame(x - size / 2, y - size / 2, size, size);

        if (editable) {
            final Stroke stroke = g.getStroke();
            g.setStroke(EDITABLE_STROKE);
            final double length = size / 1.5;
            final double offset = size / 3;

            REUSABLE_LINE.setLine(x - offset - length, y, x - offset, y);
            g.draw(REUSABLE_LINE);
            REUSABLE_LINE.setLine(x + offset + length, y, x + offset, y);
            g.draw(REUSABLE_LINE);
            REUSABLE_LINE.setLine(x, y - offset - length, x, y - offset);
            g.draw(REUSABLE_LINE);
            REUSABLE_LINE.setLine(x, y + offset + length, x, y + offset);
            g.draw(REUSABLE_LINE);

            g.draw(REUSABLE_ELLIPSE);
            g.setColor(SNTColor.alphaColor(color, 20));
            g.fill(REUSABLE_ELLIPSE);
            g.setStroke(stroke);
        } else {
            if (path.isSelected()) {
                g.draw(REUSABLE_ELLIPSE);
                g.setColor(SNTColor.alphaColor(color, 80));
                g.fill(REUSABLE_ELLIPSE);
            } else {
                g.setColor(SNTColor.alphaColor(color, 50));
                g.fill(REUSABLE_ELLIPSE);
            }
        }
    }

    protected void drawConnection(final Graphics2D g, final PathNodeCanvas other) {
        REUSABLE_LINE.setLine(x, y, other.x, other.y);
        g.draw(REUSABLE_LINE);
    }

	private double getSizeInPlane(final double xSize, final double ySize, final double zSize) {
        return switch (canvas.getPlane()) {
            case MultiDThreePanes.XY_PLANE -> Math.sqrt(xSize * xSize + ySize * ySize);
            case MultiDThreePanes.XZ_PLANE -> Math.sqrt(xSize * xSize + zSize * zSize);
            case MultiDThreePanes.ZY_PLANE -> Math.sqrt(zSize * zSize + ySize * ySize);
            default -> throw new IllegalArgumentException("BUG: Unknown plane! (" + canvas.getPlane() + ")");
        };
	}

    protected void drawDiameter(final Graphics2D g2, final int slice, final int either_side) {
        if (!path.hasRadii()) return;

        // Cross the tangents with a unit z vector:
        final double n_x = 0;
        final double n_y = 0;
        final double n_z = 1;
        final double[] tangent = path.getNodeWithChecks(index).getTangent();
        final double t_x = (tangent != null && tangent.length >= 3) ? tangent[0] : 0.0;
        final double t_y = (tangent != null && tangent.length >= 3) ? tangent[1] : 0.0;
        final double t_z = (tangent != null && tangent.length >= 3) ? tangent[2] : 1.0;
        final double cross_x = n_y * t_z - n_z * t_y;
        final double cross_y = n_z * t_x - n_x * t_z;
        final double cross_z = n_x * t_y - n_y * t_x;

        final double sizeInPlane = getSizeInPlane(cross_x, cross_y, cross_z);
        final double normalized_cross_x = cross_x / sizeInPlane;
        final double normalized_cross_y = cross_y / sizeInPlane;
        final double normalized_cross_z = cross_z / sizeInPlane;
        final double zdiff = Math.abs((slice - getSlice()) * path.z_spacing);
        final double realRadius = path.getNodeWithChecks(index).getRadius();

        if (either_side < 0 || zdiff <= realRadius) {
            final double effective_radius = (either_side < 0)
                    ? realRadius
                    : Math.sqrt(realRadius * realRadius - zdiff * zdiff);

            final PointInImage node = path.getNodeWithChecks(index);
            final double leftX = node.x + normalized_cross_x * effective_radius;
            final double leftY = node.y + normalized_cross_y * effective_radius;
            final double leftZ = node.z + normalized_cross_z * effective_radius;
            final double rightX = node.x - normalized_cross_x * effective_radius;
            final double rightY = node.y - normalized_cross_y * effective_radius;
            final double rightZ = node.z - normalized_cross_z * effective_radius;

            REUSABLE_LINE.setLine(
                    getScreenCoordinateX(leftX, leftZ),
                    getScreenCoordinateY(leftY, leftZ),
                    getScreenCoordinateX(rightX, rightZ),
                    getScreenCoordinateY(rightY, rightZ));
            g2.draw(REUSABLE_LINE);
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
