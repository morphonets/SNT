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
package sc.fiji.snt.viewer.geditor;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.dnd.DropTargetListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.text.NumberFormat;
import java.util.TooManyListenersException;

import javax.swing.BorderFactory;
import javax.swing.JComponent;

import com.mxgraph.swing.mxGraphComponent;
import com.mxgraph.util.mxEvent;
import com.mxgraph.util.mxPoint;
import com.mxgraph.util.mxEventSource.mxIEventListener;
import com.mxgraph.view.mxGraph;
import sc.fiji.snt.gui.GuiUtils;

/**
 * Component that displays a ruler for a JGraph component.
 * 
 * @author Gaudenz Alder info (at) jgraph (dot) com Copyright (c) 2001-2005
 */
class EditorRuler extends JComponent implements MouseMotionListener,
		DropTargetListener
{
	
	private static final long serialVersionUID = -6310912355878668096L;
	
	/** Constant for horizontal orientation */
	public static int ORIENTATION_HORIZONTAL = 0;
	/** Constant for vertical orientation */
	public static int ORIENTATION_VERTICAL = 1;

	/**
	 * Internal constant used to describe the screen resolution (DPI). Default
	 * is 72.
	 */
	protected static int INCH = 72;

	/**
	 * Internal constant used to describe the screen resolution (DPI). Default
	 * is 72.
	 */
	protected static int DEFAULT_PAGESCALE = 1;

	/**
	 * Internal constant used to describe the screen resolution (DPI). Default
	 * is 72.
	 */
	protected static boolean DEFAULT_ISMETRIC = true;

	/**
	 * Holds the shared number formatter.
	 * 
	 * @see NumberFormat#getInstance()
	 */
	public static final NumberFormat numberFormat = NumberFormat.getInstance();

	/**
	 * Configuers the number format.
	 */
	static
	{
		numberFormat.setMaximumFractionDigits(2);
	}

	/**
	 * Defines the inactive background border. Default is a not-so-dark gray.
	 */
	protected Color inactiveBackground = new Color(170, 170, 170);

	/**
	 * Specifies the orientation.
	 */
	protected int orientation = ORIENTATION_HORIZONTAL;

	/**
	 * Specified that start and length of the active region, ie the region to
	 * paint with the background border. This is used for example to indicate
	 * the printable region of a graph.
	 */
	protected int activeoffset, activelength;

	/**
	 * Specifies the scale for the metrics.
	 */
	protected double scale = DEFAULT_PAGESCALE;

	/**
	 * Specifies the unit system.
	 */
	protected boolean metric = DEFAULT_ISMETRIC;

	/**
	 * 
	 */
	protected Font labelFont = new Font("Tahoma", Font.PLAIN, 9);

	/**
	 * Specifies height or width of the ruler. Default is 15 pixels.
	 */
	protected int rulerSize = 16;

	/**
	 * Specifies the minimum distance between two major ticks. Default is 30.
	 */
	protected int tickDistance = 30;

	/**
	 * Reference to the attached graph.
	 */
	protected mxGraphComponent graphComponent;

	/**
	 * Holds the current and first mouse point.
	 */
	protected Point mouse = new Point();

	/**
	 * Parameters to control the display.
	 */
	protected double increment, units;

	/**
	 * 
	 */
	protected transient mxIEventListener repaintHandler = (source, evt) -> repaint();

	/**
	 * Constructs a new ruler for the specified graph and orientation.
	 * 
	 * @param graphComponent
	 *            The graphComponent to create the ruler for.
	 * @param orientation
	 *            The orientation to use for the ruler.
	 */
	EditorRuler(mxGraphComponent graphComponent, int orientation)
	{
		this.orientation = orientation;
		this.graphComponent = graphComponent;
		updateIncrementAndUnits();

		graphComponent.getGraph().getView().addListener(
				mxEvent.SCALE, repaintHandler);
		graphComponent.getGraph().getView().addListener(
				mxEvent.TRANSLATE, repaintHandler);
		graphComponent.getGraph().getView().addListener(
				mxEvent.SCALE_AND_TRANSLATE, repaintHandler);

		graphComponent.getGraphControl().addMouseMotionListener(this);

		DropTarget dropTarget = graphComponent.getDropTarget();

		try
		{
			if (dropTarget != null)
			{
				dropTarget.addDropTargetListener(this);
			}
		}
		catch (TooManyListenersException tmle)
		{
			// should not happen... swing drop target is multicast
		}

		setBorder(BorderFactory.createLineBorder(Color.black));
	}

	/**
	 * Sets the start of the active region in pixels.
	 * 
	 * @param offset
	 *            The start of the active region.
	 */
	public void setActiveOffset(int offset)
	{
		activeoffset = (int) (offset * scale);
	}

	/**
	 * Sets the length of the active region in pixels.
	 * 
	 * @param length
	 *            The length of the active region.
	 */
	public void setActiveLength(int length)
	{
		activelength = (int) (length * scale);
	}

	/**
	 * Returns true if the ruler uses metric units.
	 * 
	 * @return Returns if the ruler is metric.
	 */
	public boolean isMetric()
	{
		return metric;
	}

	/**
	 * Sets if the ruler uses metric units.
	 * 
	 * @param isMetric
	 *            Whether to use metric units.
	 */
	public void setMetric(boolean isMetric)
	{
		this.metric = isMetric;
		updateIncrementAndUnits();
		repaint();
	}

	/**
	 * Returns the ruler's horizontal or vertical size.
	 * 
	 * @return Returns the rulerSize.
	 */
	public int getRulerSize()
	{
		return rulerSize;
	}

	/**
	 * Sets the ruler's horizontal or vertical size.
	 * 
	 * @param rulerSize
	 *            The rulerSize to set.
	 */
	public void setRulerSize(int rulerSize)
	{
		this.rulerSize = rulerSize;
	}

	/**
	 * 
	 */
	public void setTickDistance(int tickDistance)
	{
		this.tickDistance = tickDistance;
	}

	/**
	 * 
	 */
	public int getTickDistance()
	{
		return tickDistance;
	}

	/**
	 * Returns the preferred size by replacing the respective component of the
	 * graph's preferred size with {@link #rulerSize}.
	 * 
	 * @return Returns the preferred size for the ruler.
	 */
	public Dimension getPreferredSize()
	{
		Dimension dim = graphComponent.getGraphControl().getPreferredSize();

		if (orientation == ORIENTATION_VERTICAL)
		{
			dim.width = rulerSize;
		}
		else
		{
			dim.height = rulerSize;
		}

		return dim;
	}

	/*
	 * (non-Javadoc)
	 * @see java.awt.dnd.DropTargetListener#dragEnter(java.awt.dnd.DropTargetDragEvent)
	 */
	public void dragEnter(DropTargetDragEvent arg0)
	{
		// empty
	}

	/*
	 * (non-Javadoc)
	 * @see java.awt.dnd.DropTargetListener#dragExit(java.awt.dnd.DropTargetEvent)
	 */
	public void dragExit(DropTargetEvent arg0)
	{
		// empty
	}

	/*
	 * (non-Javadoc)
	 * @see java.awt.dnd.DropTargetListener#dragOver(java.awt.dnd.DropTargetDragEvent)
	 */
	public void dragOver(final DropTargetDragEvent arg0)
	{
		updateMousePosition(arg0.getLocation());
	}

	/*
	 * (non-Javadoc)
	 * @see java.awt.dnd.DropTargetListener#drop(java.awt.dnd.DropTargetDropEvent)
	 */
	public void drop(DropTargetDropEvent arg0)
	{
		// empty
	}

	/*
	 * (non-Javadoc)
	 * @see java.awt.dnd.DropTargetListener#dropActionChanged(java.awt.dnd.DropTargetDragEvent)
	 */
	public void dropActionChanged(DropTargetDragEvent arg0)
	{
		// empty
	}

	/*
	 * (non-Javadoc)
	 */
	public void mouseMoved(MouseEvent e)
	{
		updateMousePosition(e.getPoint());
	}

	/*
	 * (non-Javadoc)
	 */
	public void mouseDragged(MouseEvent e)
	{
		updateMousePosition(e.getPoint());
	}

	/**
	 * Repaints the mouse position.
	 */
	protected void updateMousePosition(Point pt)
	{
		Point old = mouse;
		mouse = pt;
		repaint(old.x, old.y);
		repaint(mouse.x, mouse.y);
	}

	/**
	 * Updates the local variables used for painting based on the current scale
	 * and unit system.
	 */
	protected void updateIncrementAndUnits()
	{
		double graphScale = graphComponent.getGraph().getView().getScale();

		if (metric)
		{
			units = INCH / 2.54; // 2.54 dots per centimeter
			units *= graphComponent.getPageScale() * graphScale;
			increment = units;
		}
		else
		{
			units = INCH;
			units *= graphComponent.getPageScale() * graphScale;
			increment = units / 2;
		}
	}

	/**
	 * Repaints the ruler between the specified 0 and x or y depending on the
	 * orientation.
	 * 
	 * @param x
	 *            The endpoint for repainting a horizontal ruler.
	 * @param y
	 *            The endpoint for repainting a vertical ruler.
	 */
	public void repaint(int x, int y)
	{
		if (orientation == ORIENTATION_VERTICAL)
		{
			repaint(0, y, rulerSize, 1);
		}
		else
		{
			repaint(x, 0, 1, rulerSize);
		}
	}

	/**
	 * Paints the ruler.
	 * 
	 * @param g
	 *            The graphics to paint the ruler to.
	 */
	public void paintComponent(Graphics g)
	{
		mxGraph graph = graphComponent.getGraph();
		Rectangle clip = g.getClipBounds();
		updateIncrementAndUnits();

		// Fills clipping area with background.
		if (activelength > 0 && inactiveBackground != null)
		{
			g.setColor(inactiveBackground);
		}
		else
		{
			g.setColor(getBackground());
		}

		g.fillRect(clip.x, clip.y, clip.width, clip.height);

		// Draws the active region.
		g.setColor(getBackground());
		Point2D p = new Point2D.Double(activeoffset, activelength);

		if (orientation == ORIENTATION_HORIZONTAL)
		{
			g.fillRect((int) p.getX(), clip.y, (int) p.getY(), clip.height);
		}
		else
		{
			g.fillRect(clip.x, (int) p.getX(), clip.width, (int) p.getY());
		}

		double left = clip.getX();
		double top = clip.getY();
		double right = left + clip.getWidth();
		double bottom = top + clip.getHeight();

		// Fetches some global display state information
		mxPoint trans = graph.getView().getTranslate();
		double scale = graph.getView().getScale();
		double tx = trans.getX() * scale;
		double ty = trans.getY() * scale;

		// Sets the distance of the grid lines in pixels
		double stepping = increment;

		if (stepping < tickDistance)
		{
			int count = (int) Math
					.round(Math.ceil(tickDistance / stepping) / 2) * 2;
			stepping = count * stepping;
		}

		// Creates a set of strokes with individual dash offsets
		// for each direction
		GuiUtils.setRenderingHints(((Graphics2D) g));
		g.setFont(labelFont);
		g.setColor(Color.black);

		int smallTick = rulerSize - rulerSize / 3;
		int middleTick = rulerSize / 2;

		// TODO: Merge into a single drawing loop for both orientations
		if (orientation == ORIENTATION_HORIZONTAL)
		{
			double xs = Math.floor((left - tx) / stepping) * stepping + tx;
			double xe = Math.ceil(right / stepping) * stepping;
			xe += (int) Math.ceil(stepping);

			for (double x = xs; x <= xe; x += stepping)
			{
				// FIXME: Workaround for rounding errors when adding stepping to
				// xs or ys multiple times (leads to double grid lines when zoom
				// is set to eg. 121%)
				double xx = Math.round((x - tx) / stepping) * stepping + tx;

				int ix = (int) Math.round(xx);
				g.drawLine(ix, rulerSize, ix, 0);

				String text = format((x - tx) / increment);
				g.drawString(text, ix + 2, labelFont.getSize());

				ix += (int) Math.round(stepping / 4);
				g.drawLine(ix, rulerSize, ix, smallTick);

				ix += (int) Math.round(stepping / 4);
				g.drawLine(ix, rulerSize, ix, middleTick);

				ix += (int) Math.round(stepping / 4);
				g.drawLine(ix, rulerSize, ix, smallTick);
			}
		}
		else
		{
			double ys = Math.floor((top - ty) / stepping) * stepping + ty;
			double ye = Math.ceil(bottom / stepping) * stepping;
			ye += (int) Math.ceil(stepping);

			for (double y = ys; y <= ye; y += stepping)
			{
				// FIXME: Workaround for rounding errors when adding stepping to
				// xs or ys multiple times (leads to double grid lines when zoom
				// is set to eg. 121%)
				y = Math.round((y - ty) / stepping) * stepping + ty;

				int iy = (int) Math.round(y);
				g.drawLine(rulerSize, iy, 0, iy);

				String text = format((y - ty) / increment);

				// Rotates the labels in the vertical ruler
				AffineTransform at = ((Graphics2D) g).getTransform();
				((Graphics2D) g).rotate(-Math.PI / 2, 0, iy);
				g.drawString(text, 1, iy + labelFont.getSize());
				((Graphics2D) g).setTransform(at);

				iy += (int) Math.round(stepping / 4);
				g.drawLine(rulerSize, iy, smallTick, iy);

				iy += (int) Math.round(stepping / 4);
				g.drawLine(rulerSize, iy, middleTick, iy);

				iy += (int) Math.round(stepping / 4);
				g.drawLine(rulerSize, iy, smallTick, iy);
			}
		}

		// Draw Mouseposition
		g.setColor(Color.green);

		if (orientation == ORIENTATION_HORIZONTAL)
		{
			g.drawLine(mouse.x, rulerSize, mouse.x, 0);
		}
		else
		{
			g.drawLine(rulerSize, mouse.y, 0, mouse.y);
		}
	}

	/**
	 * Fixes the formatting of -0.
	 */
	private String format(double value)
	{
		String text = numberFormat.format(value);

		if (text.equals("-0"))
		{
			text = "0";
		}

		return text;
	}

}
