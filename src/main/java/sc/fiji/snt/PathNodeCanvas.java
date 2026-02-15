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

package sc.fiji.snt;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.util.Objects;

import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SNTColor;
import sc.fiji.snt.hyperpanes.MultiDThreePanes;

/**
 * Convenience class used to render {@link Path} nodes (vertices) in an
 * {@link TracerCanvas}.
 *
 * @author Tiago Ferreira
 */
public class PathNodeCanvas {

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

    /** Soma rendering mode: filled circle scaled to actual radius */
    public static final int SOMA_RENDER_DEFAULT= 0;
    /** Soma rendering mode: triangle scaled to actual radius (only for SWC_SOMA tagged paths) */
    public static final int SOMA_RENDER_TRIANGLE = 1;

    // Static field to control soma rendering mode (default to current behavior)
    private static int somaRenderMode = SOMA_RENDER_DEFAULT;

    // Static field to control direction arrow display (toggled by key press)
    private static boolean showDirectionArrows = false;
    private static final int ARROW_INTERVAL = 4; // draw arrow every Nth node

    // Static reusable shapes to avoid allocations in render loop
    private static final BasicStroke EDITABLE_STROKE = new BasicStroke(4);
    private static final Line2D.Double REUSABLE_LINE = new Line2D.Double();
    private static final Ellipse2D.Double REUSABLE_ELLIPSE = new Ellipse2D.Double();
    // Reusable arrays for triangle rendering
    private static final int[] TRIANGLE_X = new int[3];
    private static final int[] TRIANGLE_Y = new int[3];

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
     * @param pim the position of the node (z-position ignored). Cannot be null.
     * @param index the index of this node within its path
     * @param canvas the canvas to render this node. Cannot be null.
     * @throws NullPointerException if pim, pim.onPath, or canvas is null
     */
    public PathNodeCanvas(final PointInImage pim, final int index, final TracerCanvas canvas) {
        Objects.requireNonNull(pim, "PointInImage cannot be null");
        Objects.requireNonNull(pim.onPath, "PointInImage.onPath cannot be null");
        this.path = pim.onPath;
        this.canvas = Objects.requireNonNull(canvas, "TracerCanvas cannot be null");
        this.index = index;
        x = getScreenCoordinateX(pim);
        y = getScreenCoordinateY(pim);
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

    /**
     * Sets the rendering mode for soma nodes (single-point paths).
     *
     * @param mode one of {@link #SOMA_RENDER_DEFAULT}, or {@link #SOMA_RENDER_TRIANGLE}
     * @throws IllegalArgumentException if mode is not a valid rendering mode
     */
    public static void setSomaRenderMode(final int mode) {
        if (mode != SOMA_RENDER_DEFAULT && mode != SOMA_RENDER_TRIANGLE) {
            throw new IllegalArgumentException("Invalid soma render mode: " + mode);
        }
        somaRenderMode = mode;
    }

    /**
     * Gets the current rendering mode for soma nodes.
     *
     * @return the current soma render mode
     */
    public static int getSomaRenderMode() {
        return somaRenderMode;
    }

    /**
     * Sets whether direction arrows should be displayed on paths.
     * When enabled, small arrows are drawn at regular intervals along paths
     * to indicate path direction (from start to end).
     *
     * @param show true to show direction arrows, false to hide them
     */
    public static void setShowDirectionArrows(final boolean show) {
        showDirectionArrows = show;
    }

    /**
     * Gets whether direction arrows are currently being displayed.
     *
     * @return true if direction arrows are shown
     */
    public static boolean isShowDirectionArrows() {
        return showDirectionArrows;
    }

    /**
     * Checks if this node's path is tagged as a soma (SWC type 1).
     *
     * @return true if path's SWC type is SWC_SOMA
     */
    private boolean isSomaTagged() {
        return path.getSWCType() == Path.SWC_SOMA;
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
        // Reset color to avoid stale values, and check bounds before accessing
        color = (index >= 0 && index < path.size()) ? path.getNodeColor(index) : null;
        if (color == null) color = fallbackColor;
        g.setColor(color);
    }

    private void assignRenderingSize() {
        if (size > -1) return; // size already specified via setSize()
        final double baseline = canvas.nodeDiameter();

        switch (type) {
            case HERMIT -> {
                if (path.hasRadii()) {
                    // Use actual radius for soma rendering
                    final double radius = path.getNodeRadius(index);
                    if (radius > 0) {
                        // Convert radius from calibrated units to screen pixels
                        final double spacing = getSpacingForPlane();
                        final double radiusInPixels = radius / spacing;
                        final double magnification = canvas.getMagnification();
                        size = 2 * radiusInPixels * magnification;
                        // Ensure minimum visible size
                        size = Math.max(size, 2 * baseline);
                    } else {
                        size = 5 * baseline; // fallback for zero/negative radius
                    }
                } else {
                    size = 5 * baseline; // original fixed behavior
                }
            }
            case START -> size = 2 * baseline;
            case END -> size = 1.5 * baseline;
            case JOINT -> size = 3 * baseline;
            default -> size = baseline;
        }
        if (isEditable()) size *= 2.5;
    }

    /**
     * Returns the pixel spacing appropriate for the current canvas plane.
     */
    private double getSpacingForPlane() {
        return switch (canvas.getPlane()) {
            case MultiDThreePanes.XY_PLANE -> (path.x_spacing + path.y_spacing) / 2.0;
            case MultiDThreePanes.XZ_PLANE -> (path.x_spacing + path.z_spacing) / 2.0;
            case MultiDThreePanes.ZY_PLANE -> (path.z_spacing + path.y_spacing) / 2.0;
            default -> path.x_spacing; // fallback
        };
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

        // Check if we should render as triangle (only for soma-tagged HERMIT nodes)
        if (type == HERMIT && somaRenderMode == SOMA_RENDER_TRIANGLE && isSomaTagged()) {
            drawTriangle(g);
            return;
        }

        // Standard ellipse rendering
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
            if (type == START) {
                // START nodes rendered as hollow circles (outline only) to indicate direction
                g.draw(REUSABLE_ELLIPSE);
                if (path.isSelected()) {
                    g.setColor(SNTColor.alphaColor(color, 30));
                    g.fill(REUSABLE_ELLIPSE);
                }
            } else if (path.isSelected()) {
                g.draw(REUSABLE_ELLIPSE);
                g.setColor(SNTColor.alphaColor(color, 80));
                g.fill(REUSABLE_ELLIPSE);
            } else {
                g.setColor(SNTColor.alphaColor(color, 50));
                g.fill(REUSABLE_ELLIPSE);
            }

            // Draw diameter line for HERMIT nodes, unless soma-tagged in SCALED mode
            if (type == HERMIT) {
                g.setColor(color); // Use full opacity for diameter line
                REUSABLE_LINE.setLine(x - size / 2, y, x + size / 2, y);
                g.draw(REUSABLE_LINE);
            }
        }
    }

    /**
     * Draws the node as an equilateral triangle pointing upward, sized to the node's radius.
     * The triangle is centered on the node position with height equal to the diameter.
     */
    private void drawTriangle(final Graphics2D g) {
        // Equilateral triangle with height = size (diameter)
        // For equilateral triangle: height = (sqrt(3)/2) * side
        // So side = height * 2/sqrt(3) = size * 2/sqrt(3)
        // Half-width = side/2 = size / sqrt(3)
        final double halfWidth = size / Math.sqrt(3);
        final double halfHeight = size / 2;

        // Triangle pointing upward, centered at (x, y)
        // Top vertex
        TRIANGLE_X[0] = (int) Math.round(x);
        TRIANGLE_Y[0] = (int) Math.round(y - halfHeight);
        // Bottom-left vertex
        TRIANGLE_X[1] = (int) Math.round(x - halfWidth);
        TRIANGLE_Y[1] = (int) Math.round(y + halfHeight);
        // Bottom-right vertex
        TRIANGLE_X[2] = (int) Math.round(x + halfWidth);
        TRIANGLE_Y[2] = (int) Math.round(y + halfHeight);

        if (editable) {
            final Stroke stroke = g.getStroke();
            g.setStroke(EDITABLE_STROKE);

            // Draw crosshair extending from triangle
            final double length = size / 1.5;
            final double offset = halfWidth + 2;

            REUSABLE_LINE.setLine(x - offset - length, y, x - offset, y);
            g.draw(REUSABLE_LINE);
            REUSABLE_LINE.setLine(x + offset + length, y, x + offset, y);
            g.draw(REUSABLE_LINE);
            REUSABLE_LINE.setLine(x, y - halfHeight - length - 2, x, y - halfHeight - 2);
            g.draw(REUSABLE_LINE);
            REUSABLE_LINE.setLine(x, y + halfHeight + length + 2, x, y + halfHeight + 2);
            g.draw(REUSABLE_LINE);

            g.drawPolygon(TRIANGLE_X, TRIANGLE_Y, 3);
            g.setColor(SNTColor.alphaColor(color, 20));
            g.fillPolygon(TRIANGLE_X, TRIANGLE_Y, 3);
            g.setStroke(stroke);
        } else {
            if (path.isSelected()) {
                g.drawPolygon(TRIANGLE_X, TRIANGLE_Y, 3);
                g.setColor(SNTColor.alphaColor(color, 80));
                g.fillPolygon(TRIANGLE_X, TRIANGLE_Y, 3);
            } else {
                g.setColor(SNTColor.alphaColor(color, 50));
                g.fillPolygon(TRIANGLE_X, TRIANGLE_Y, 3);
            }
        }
    }

    protected void drawConnection(final Graphics2D g, final PathNodeCanvas other) {
        REUSABLE_LINE.setLine(x, y, other.x, other.y);
        g.draw(REUSABLE_LINE);
    }

    /**
     * Draws a direction arrow pointing from this node toward the next node.
     * The arrow is drawn as a ">" symbol at the midpoint between nodes.
     * Only draws if direction arrows are enabled and this is an appropriate node
     * (every Nth node based on ARROW_INTERVAL).
     *
     * @param g    the Graphics2D drawing instance
     * @param next the next node in the path (toward the end)
     */
    protected void drawDirectionArrow(final Graphics2D g, final PathNodeCanvas next) {
        if (!showDirectionArrows || next == null) return;
        if (type == HERMIT || type == END) return; // no arrow for single-point or end nodes
        if (index % ARROW_INTERVAL != 0 && type != START) return; // every Nth node, but always at START

        // Calculate direction vector
        final double dx = next.x - this.x;
        final double dy = next.y - this.y;
        final double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 4) return; // too short to draw arrow

        // Normalize direction
        final double nx = dx / len;
        final double ny = dy / len;

        // Arrow position: midpoint between this node and next
        final double midX = (this.x + next.x) / 2;
        final double midY = (this.y + next.y) / 2;

        // Arrow size: scale with magnification via nodeDiameter, with sensible bounds
        // nodeDiameter already accounts for magnification (returns magnification when mag is 4-16)
        final double baseSize = canvas.nodeDiameter();
        final double arrowSize = Math.max(14, Math.min(baseSize * 6.5, len * 0.55));

        // Perpendicular vector for arrow wings
        final double px = -ny;
        final double py = nx;

        // Arrow tip is ahead of midpoint, base is behind
        final double tipX = midX + nx * arrowSize * 0.5;
        final double tipY = midY + ny * arrowSize * 0.5;
        final double baseX = midX - nx * arrowSize * 0.5;
        final double baseY = midY - ny * arrowSize * 0.5;

        // Draw ">" shape: two lines from base corners to tip
        final double wingSpread = arrowSize * 0.5;
        final double wingX = baseX + px * wingSpread;
        final double wingY = baseY + py * wingSpread;
        final double wingX2 = baseX - px * wingSpread;
        final double wingY2 = baseY - py * wingSpread;

        REUSABLE_LINE.setLine(wingX, wingY, tipX, tipY);
        g.draw(REUSABLE_LINE);
        REUSABLE_LINE.setLine(wingX2, wingY2, tipX, tipY);
        g.draw(REUSABLE_LINE);
    }

    /**
     * Checks if direction arrows are enabled and this node should skip diameter rendering.
     * When direction arrows are shown, diameter lines are hidden to reduce visual clutter.
     *
     * @return true if diameter rendering should be skipped
     */
    public boolean skipDiameterForDirectionArrows() {
        return showDirectionArrows;
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
        if (sizeInPlane == 0) {
            // Cannot draw diameter: tangent is parallel to view normal
            // Fall back to simple horizontal diameter line
            final double realRadius = path.getNodeWithChecks(index).getRadius();
            final PointInImage node = path.getNodeWithChecks(index);
            REUSABLE_LINE.setLine(
                    getScreenCoordinateX(node.x - realRadius, node.z),
                    getScreenCoordinateY(node.y, node.z),
                    getScreenCoordinateX(node.x + realRadius, node.z),
                    getScreenCoordinateY(node.y, node.z));
            g2.draw(REUSABLE_LINE);
            return;
        }
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
