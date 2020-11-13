/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2020 Fiji developers.
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

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Stroke;
import java.util.ArrayList;

import ij.ImagePlus;
import sc.fiji.snt.hyperpanes.MultiDThreePanesCanvas;
import sc.fiji.snt.hyperpanes.PaneOwner;

@SuppressWarnings("serial")
class TracerCanvas extends MultiDThreePanesCanvas {

	protected PathAndFillManager pathAndFillManager;

	protected boolean just_near_slices = false;
	protected int eitherSide;
	private final ArrayList<SearchInterface> searchThreads = new ArrayList<>();
	private double nodeSize = -1;

	public TracerCanvas(final ImagePlus imagePlus, final PaneOwner owner,
		final int plane, final PathAndFillManager pathAndFillManager)
	{

		super(imagePlus, owner, plane);
		this.pathAndFillManager = pathAndFillManager;
	}

	public void addSearchThread(final SearchInterface s) {
		synchronized (searchThreads) {
			searchThreads.add(s);
		}
	}

	public void removeSearchThread(final SearchInterface s) {
		synchronized (searchThreads) {
			int index = -1;
			for (int i = 0; i < searchThreads.size(); ++i) {
				final SearchInterface inList = searchThreads.get(i);
				if (s == inList) index = i;
			}
			if (index >= 0) searchThreads.remove(index);
		}
	}

	@Override
	protected void drawOverlay(final Graphics2D g) {

		/*
		 * int current_z = -1;
		 *
		 * if( plane == ThreePanes.XY_PLANE ) { current_z = imp.getZ() - 1; }
		 */

		super.drawOverlay(g); // render crosshairs, cursor text and canvas label

		final int current_z = imp.getZ() - 1;

		synchronized (searchThreads) {
			for (final SearchInterface st : searchThreads)
				st.drawProgressOnSlice(plane, current_z, this, g);
		}

		final SNT plugin = pathAndFillManager.getPlugin();
		final Color selectedColor = plugin.selectedColor;
		final Color deselectedColor = plugin.deselectedColor;

		final boolean drawDiametersXY = plugin.getDrawDiametersXY();

		if (pathAndFillManager != null) {
			final Stroke stroke = g.getStroke();
			for (int i = 0; i < pathAndFillManager.size(); ++i) {
				final Path p = pathAndFillManager.getPath(i);
				if (p == null) continue;

				if (p.fittedVersionOf != null) continue;

				Path drawPath = p;

				// If the path suggests using the fitted version, draw that
				// instead:
				if (p.useFitted) {
					drawPath = p.getFitted();
				}

				final boolean isSelected = pathAndFillManager.isSelected(drawPath);
				if (!isSelected && plugin.isOnlySelectedPathsVisible()) continue;
				if (plugin.showOnlyActiveCTposPaths && (imp.getC() != drawPath
					.getChannel() || imp.getT() != drawPath.getFrame()))
				{
					continue;
				}

				final boolean customColor = (drawPath.hasCustomColor &&
					plugin.displayCustomPathColors);
				Color color = deselectedColor;
				if (isSelected && !customColor) color = selectedColor;
				else if (customColor) color = drawPath.getColor();

				if (just_near_slices) {
					drawPath.drawPathAsPoints(this, g, color, plane, (isSelected &&
						customColor), drawDiametersXY, current_z, eitherSide);
				}
				else {
					drawPath.drawPathAsPoints(this, g, color, plane, (isSelected &&
						customColor), drawDiametersXY);
				}
				g.setStroke(stroke);
			}
		}

	}

	/* Keep another Graphics for double-buffering... */

	private int backBufferWidth;
	private int backBufferHeight;

	private Graphics2D backBufferGraphics;
	private Image backBufferImage;

	protected void resetBackBuffer() {

		if (backBufferGraphics != null) {
			backBufferGraphics.dispose();
			backBufferGraphics = null;
		}

		if (backBufferImage != null) {
			backBufferImage.flush();
			backBufferImage = null;
		}

		backBufferWidth = getSize().width;
		backBufferHeight = getSize().height;

		if (backBufferWidth > 0 && backBufferHeight > 0) {
			backBufferImage = createImage(backBufferWidth, backBufferHeight);
			backBufferGraphics = getGraphics2D(backBufferImage.getGraphics());
		}
	}

	@Override
	public void paint(final Graphics g) {

		if (backBufferWidth != getSize().width ||
			backBufferHeight != getSize().height || backBufferImage == null ||
			backBufferGraphics == null) resetBackBuffer();

		super.paint(backBufferGraphics);
		drawOverlay(backBufferGraphics);
		g.drawImage(backBufferImage, 0, 0, this);
	}

	/**
	 * Returns the MultiDThreePanes plane associated with this canvas.
	 *
	 * @return Either MultiDThreePanes.XY_PLANE, XZ_PLANE, or ZY_PLANE
	 */
	public int getPlane() {
		return super.plane;
	}

	/**
	 * Returns the diameter of path nodes rendered at current magnification.
	 *
	 * @return the baseline rendering diameter of a path node
	 */
	public double nodeDiameter() {
		if (nodeSize < 0) {
			if (magnification < 4) return 2;
			else if (magnification > 16) return magnification / 2;
			else return magnification;
		}
		return nodeSize;
	}

	/**
	 * Sets the baseline for rendering diameter of path nodes
	 *
	 * @param diameter the diameter to be used when rendering path nodes. Set it
	 *          to -1 for adopting the default value. Set it to zero to suppress
	 *          node rendering
	 */
	public void setNodeDiameter(final double diameter) {
		nodeSize = diameter;
	}

}
