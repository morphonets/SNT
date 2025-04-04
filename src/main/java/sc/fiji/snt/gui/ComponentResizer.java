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

package sc.fiji.snt.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.HashMap;
import java.util.Map;

/**
 * The ComponentResizer allows you to resize a component by dragging a border of
 * the component. see
 * https://tips4java.wordpress.com/2009/09/13/resizing-components/ license:
 * re-use without restriction (https://tips4java.wordpress.com/about/)
 *
 * @author Rob Camick
 * <p>
 *         //TODO: Hava a look at
 *         https://tips4java.wordpress.com/2009/09/13/resizing-components/#comments
 *         for multi-screen support
 *
 */
class ComponentResizer extends MouseAdapter {

	private final static Dimension MINIMUM_SIZE = new Dimension(10, 10);
	private final static Dimension MAXIMUM_SIZE = new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE);

	private static final Map<Integer, Integer> cursors = new HashMap<>();
	static {
		cursors.put(1, Cursor.N_RESIZE_CURSOR);
		cursors.put(2, Cursor.W_RESIZE_CURSOR);
		cursors.put(4, Cursor.S_RESIZE_CURSOR);
		cursors.put(8, Cursor.E_RESIZE_CURSOR);
		cursors.put(3, Cursor.NW_RESIZE_CURSOR);
		cursors.put(9, Cursor.NE_RESIZE_CURSOR);
		cursors.put(6, Cursor.SW_RESIZE_CURSOR);
		cursors.put(12, Cursor.SE_RESIZE_CURSOR);
	}

	private Insets dragInsets;
	private Dimension snapSize;

	private int direction;
	protected static final int NORTH = 1;
	protected static final int WEST = 2;
	protected static final int SOUTH = 4;
	protected static final int EAST = 8;

	private Cursor sourceCursor;
	private boolean resizing;
	private Rectangle bounds;
	private Point pressed;
	private boolean autoscrolls;

	private Dimension minimumSize = MINIMUM_SIZE;
	private Dimension maximumSize = MAXIMUM_SIZE;

	/**
	 * Convenience contructor. All borders are resizable in increments of a single
	 * pixel. Components must be registered separately.
	 */
	public ComponentResizer() {
		this(new Insets(5, 5, 5, 5), new Dimension(1, 1));
	}

	/**
	 * Convenience contructor. All borders are resizable in increments of a single
	 * pixel. Components can be registered when the class is created or they can be
	 * registered separately afterwards.
	 *
	 * @param components components to be automatically registered
	 */
	public ComponentResizer(final Component... components) {
		this(new Insets(5, 5, 5, 5), new Dimension(1, 1), components);
	}

	/**
	 * Convenience contructor. Eligible borders are resisable in increments of a
	 * single pixel. Components can be registered when the class is created or they
	 * can be registered separately afterwards.
	 *
	 * @param dragInsets Insets specifying which borders are eligible to be resized.
	 * @param components components to be automatically registered
	 */
	public ComponentResizer(final Insets dragInsets, final Component... components) {
		this(dragInsets, new Dimension(1, 1), components);
	}

	/**
	 * Create a ComponentResizer.
	 *
	 * @param dragInsets Insets specifying which borders are eligible to be resized.
	 * @param snapSize   Specify the dimension to which the border will snap to when
	 *                   being dragged. Snapping occurs at the halfway mark.
	 * @param components components to be automatically registered
	 */
	public ComponentResizer(final Insets dragInsets, final Dimension snapSize, final Component... components) {
		setDragInsets(dragInsets);
		setSnapSize(snapSize);
		registerComponent(components);
	}

	/**
	 * Get the drag insets
	 *
	 * @return the drag insets
	 */
	public Insets getDragInsets() {
		return dragInsets;
	}

	/**
	 * Set the drag dragInsets. The insets specify an area where mouseDragged events
	 * are recognized from the edge of the border inwards. A value of 0 for any size
	 * will imply that the border is not resizable. Otherwise the appropriate drag
	 * cursor will appear when the mouse is inside the resizable border area.
	 *
	 * @param dragInsets Insets to control which borders are resizeable.
	 */
	public void setDragInsets(final Insets dragInsets) {
		validateMinimumAndInsets(minimumSize, dragInsets);

		this.dragInsets = dragInsets;
	}

	/**
	 * Get the components maximum size.
	 *
	 * @return the maximum size
	 */
	public Dimension getMaximumSize() {
		return maximumSize;
	}

	/**
	 * Specify the maximum size for the component. The component will still be
	 * constrained by the size of its parent.
	 *
	 * @param maximumSize the maximum size for a component.
	 */
	public void setMaximumSize(final Dimension maximumSize) {
		this.maximumSize = maximumSize;
	}

	/**
	 * Get the components minimum size.
	 *
	 * @return the minimum size
	 */
	public Dimension getMinimumSize() {
		return minimumSize;
	}

	/**
	 * Specify the minimum size for the component. The minimum size is constrained
	 * by the drag insets.
	 *
	 * @param minimumSize the minimum size for a component.
	 */
	public void setMinimumSize(final Dimension minimumSize) {
		validateMinimumAndInsets(minimumSize, dragInsets);

		this.minimumSize = minimumSize;
	}

	/**
	 * Remove listeners from the specified component
	 *
	 * @param components the component the listeners are removed from
	 */
	public void deregisterComponent(final Component... components) {
		for (final Component component : components) {
			component.removeMouseListener(this);
			component.removeMouseMotionListener(this);
		}
	}

	/**
	 * Add the required listeners to the specified component
	 *
	 * @param components the component the listeners are added to
	 */
	public void registerComponent(final Component... components) {
		for (final Component component : components) {
			component.addMouseListener(this);
			component.addMouseMotionListener(this);
		}
	}

	/**
	 * Get the snap size.
	 *
	 * @return the snap size.
	 */
	public Dimension getSnapSize() {
		return snapSize;
	}

	/**
	 * Control how many pixels a border must be dragged before the size of the
	 * component is changed. The border will snap to the size once dragging has
	 * passed the halfway mark.
	 *
	 * @param snapSize Dimension object allows you to separately spcify a horizontal
	 *                 and vertical snap size.
	 */
	public void setSnapSize(final Dimension snapSize) {
		this.snapSize = snapSize;
	}

	/**
	 * When the components minimum size is less than the drag insets then we can't
	 * determine which border should be resized so we need to prevent this from
	 * happening.
	 */
	private void validateMinimumAndInsets(final Dimension minimum, final Insets drag) {
		final int minimumWidth = drag.left + drag.right;
		final int minimumHeight = drag.top + drag.bottom;

		if (minimum.width < minimumWidth || minimum.height < minimumHeight) {
			final String message = "Minimum size cannot be less than drag insets";
			throw new IllegalArgumentException(message);
		}
	}

	/**
	 */
	@Override
	public void mouseMoved(final MouseEvent e) {
		final Component source = e.getComponent();
		final Point location = e.getPoint();
		direction = 0;

		if (location.x < dragInsets.left)
			direction += WEST;

		if (location.x > source.getWidth() - dragInsets.right - 1)
			direction += EAST;

		if (location.y < dragInsets.top)
			direction += NORTH;

		if (location.y > source.getHeight() - dragInsets.bottom - 1)
			direction += SOUTH;

		// Mouse is no longer over a resizable border

		if (direction == 0) {
			source.setCursor(sourceCursor);
		} else // use the appropriate resizable cursor
		{
			final int cursorType = cursors.get(direction);
			final Cursor cursor = Cursor.getPredefinedCursor(cursorType);
			source.setCursor(cursor);
		}
	}

	@Override
	public void mouseEntered(final MouseEvent e) {
		if (!resizing) {
			final Component source = e.getComponent();
			sourceCursor = source.getCursor();
		}
	}

	@Override
	public void mouseExited(final MouseEvent e) {
		if (!resizing) {
			final Component source = e.getComponent();
			source.setCursor(sourceCursor);
		}
	}

	@Override
	public void mousePressed(final MouseEvent e) {
		// The mouseMoved event continually updates this variable

		if (direction == 0)
			return;

		// Setup for resizing. All future dragging calculations are done based
		// on the original bounds of the component and mouse pressed location.

		resizing = true;

		final Component source = e.getComponent();
		pressed = e.getPoint();
		SwingUtilities.convertPointToScreen(pressed, source);
		bounds = source.getBounds();

		// Making sure autoscrolls is false will allow for smoother resizing
		// of components

		if (source instanceof JComponent) {
			final JComponent jc = (JComponent) source;
			autoscrolls = jc.getAutoscrolls();
			jc.setAutoscrolls(false);
		}
	}

	/**
	 * Restore the original state of the Component
	 */
	@Override
	public void mouseReleased(final MouseEvent e) {
		resizing = false;

		final Component source = e.getComponent();
		source.setCursor(sourceCursor);

		if (source instanceof JComponent) {
			((JComponent) source).setAutoscrolls(autoscrolls);
		}
	}

	/**
	 * Resize the component ensuring location and size is within the bounds of the
	 * parent container and that the size is within the minimum and maximum
	 * constraints.
	 * <p>
	 * All calculations are done using the bounds of the component when the resizing
	 * started.
	 */
	@Override
	public void mouseDragged(final MouseEvent e) {
		if (!resizing)
			return;

		final Component source = e.getComponent();
		final Point dragged = e.getPoint();
		SwingUtilities.convertPointToScreen(dragged, source);

		changeBounds(source, direction, bounds, pressed, dragged);
	}

	protected void changeBounds(final Component source, final int direction, final Rectangle bounds,
			final Point pressed, final Point current) {
		// Start with original locaton and size

		int x = bounds.x;
		int y = bounds.y;
		int width = bounds.width;
		int height = bounds.height;

		// Resizing the West or North border affects the size and location

		if (WEST == (direction & WEST)) {
			int drag = getDragDistance(pressed.x, current.x, snapSize.width);
			final int maximum = Math.min(width + x, maximumSize.width);
			drag = getDragBounded(drag, snapSize.width, width, minimumSize.width, maximum);

			x -= drag;
			width += drag;
		}

		if (NORTH == (direction & NORTH)) {
			int drag = getDragDistance(pressed.y, current.y, snapSize.height);
			final int maximum = Math.min(height + y, maximumSize.height);
			drag = getDragBounded(drag, snapSize.height, height, minimumSize.height, maximum);

			y -= drag;
			height += drag;
		}

		// Resizing the East or South border only affects the size

		if (EAST == (direction & EAST)) {
			int drag = getDragDistance(current.x, pressed.x, snapSize.width);
			final Dimension boundingSize = getBoundingSize(source);
			final int maximum = Math.min(boundingSize.width - x, maximumSize.width);
			drag = getDragBounded(drag, snapSize.width, width, minimumSize.width, maximum);
			width += drag;
		}

		if (SOUTH == (direction & SOUTH)) {
			int drag = getDragDistance(current.y, pressed.y, snapSize.height);
			final Dimension boundingSize = getBoundingSize(source);
			final int maximum = Math.min(boundingSize.height - y, maximumSize.height);
			drag = getDragBounded(drag, snapSize.height, height, minimumSize.height, maximum);
			height += drag;
		}

		source.setBounds(x, y, width, height);
		source.validate();
	}

	/*
	 * Determine how far the mouse has moved from where dragging started
	 */
	private int getDragDistance(final int larger, final int smaller, final int snapSize) {
		final int halfway = snapSize / 2;
		int drag = larger - smaller;
		drag += (drag < 0) ? -halfway : halfway;
		drag = (drag / snapSize) * snapSize;

		return drag;
	}

	/*
	 * Adjust the drag value to be within the minimum and maximum range.
	 */
	private int getDragBounded(int drag, final int snapSize, final int dimension, final int minimum,
			final int maximum) {
		while (dimension + drag < minimum)
			drag += snapSize;

		while (dimension + drag > maximum)
			drag -= snapSize;

		return drag;
	}

	/*
	 * Keep the size of the component within the bounds of its parent.
	 */
	private Dimension getBoundingSize(final Component source) {
		if (source instanceof Window) {
			final GraphicsEnvironment env = GraphicsEnvironment.getLocalGraphicsEnvironment();
			final Rectangle bounds = env.getMaximumWindowBounds();
			return new Dimension(bounds.width, bounds.height);
		} else {
			return source.getParent().getSize();
		}
	}
}
