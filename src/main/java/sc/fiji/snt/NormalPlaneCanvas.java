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
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.util.HashMap;

import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.StackWindow;
import ij.process.ImageProcessor;
import sc.fiji.snt.hyperpanes.MultiDThreePanes;

@SuppressWarnings("serial")
class NormalPlaneCanvas extends TracerCanvas {

	static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

	private static final Color COLOR_VALID_FIT = Color.GREEN;
	private static final Color COLOR_INVALID_FIT = Color.RED;
	private static final Color COLOR_MODE = Color.ORANGE;
	private static int pixelOffset;

	private double maxScore = Double.MIN_VALUE;
	private double minScore = Double.MAX_VALUE;
	private int last_slice = -1;

    private final double[] centre_x_positions;
	private final double[] centre_y_positions;
	private final double[] radii;
	private final double[] modeRadii;
	private final double[] scores;
	private final boolean[] valid;
	private final double[] angles;

	private final Path fittedPath;
	private final SNT tracerPlugin;
	private final HashMap<Integer, Integer> indexToValidIndex = new HashMap<>();
	private String invalidFitLabel;

	protected NormalPlaneCanvas(final ImagePlus imp,
	                            final SNT plugin, final double[] centre_x_positions,
	                            final double[] centre_y_positions, final double[] radii,
	                            final double[] scores, final double[] modeRadii, final double[] angles,
	                            final boolean[] valid, final Path fittedPath)
	{
		super(resizeAsNeeded(imp), plugin, MultiDThreePanes.XY_PLANE, plugin
			.getPathAndFillManager());

		tracerPlugin = plugin;
		this.centre_x_positions = centre_x_positions;
		this.centre_y_positions = centre_y_positions;
		this.radii = radii;
		this.scores = scores;
		this.modeRadii = modeRadii;
		this.angles = angles;
		this.valid = valid;
		this.fittedPath = fittedPath;
		for (double score : scores) {
			if (score > maxScore) maxScore = score;
			if (score < minScore) minScore = score;
		}
		int a = 0;
		for (int i = 0; i < valid.length; ++i) {
			if (valid[i]) {
				indexToValidIndex.put(i, a);
				++a;
			}
		}
		invalidFitLabel = "";
		// Make ImageCanvas fully independent from SNT
		disableEvents(true);
		setDrawCrosshairs(false);
	}

	@Override
	protected void drawOverlay(final Graphics2D g) {

		final int z = imp.getZ() - 1;
		final Color fitColor = (valid[z]) ? COLOR_VALID_FIT : COLOR_INVALID_FIT;

		// build label
		final double normScore = 1 - ((scores[z] - minScore) / (maxScore - minScore)); // 0-1 normalization
		setAnnotationsColor(fitColor);
		setCanvasLabel(String.format("r=%.2f ∠%.1f° QS: %.2f", radii[z], Math.toDegrees(angles[z]), normScore));

		// mark center
		g.setColor(fitColor);
		g.fill(new Ellipse2D.Double(myScreenXDprecise(centre_x_positions[z], true) - 3,
			myScreenYDprecise(centre_y_positions[z], true) - 3, 6, 6));

		// show diameter
		g.setStroke(new BasicStroke(2));
		final double x_top_left = myScreenXDprecise(centre_x_positions[z] -
			radii[z], true);
		final double y_top_left = myScreenYDprecise(centre_y_positions[z] -
			radii[z], true);
		final double diameter = myScreenXDprecise(centre_x_positions[z] +
			radii[z], true) - myScreenXDprecise(centre_x_positions[z] - radii[z], true);
		g.draw(new Ellipse2D.Double(x_top_left, y_top_left, diameter, diameter));

		// draw displacement of centroids
		g.draw(new Line2D.Double(myScreenXDprecise(centre_x_positions[z], true),
				myScreenYDprecise(centre_y_positions[z], true), myScreenXDprecise(imp.getWidth() / 2.0),
				myScreenXDprecise(imp.getWidth() / 2.0)));

		if (!valid[z]) {
			g.drawString(invalidFitLabel,
					(float) myScreenXDprecise(0), (float) myScreenYDprecise(imp.getHeight() - 1));
		}

		// show mode. Draw on the image center (pixelOffset ignored)
		g.setColor(COLOR_MODE);
		g.setStroke(new BasicStroke(3, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[] { 9 }, 0));
		final double modeOvalX = myScreenXDprecise(imp.getWidth() / 2.0 -
			modeRadii[z]);
		final double modeOvalY = myScreenYDprecise(imp.getHeight() / 2.0 -
			modeRadii[z]);
		final double modeOvalDiameter = myScreenXDprecise(imp.getWidth() / 2.0 +
			modeRadii[z]) - modeOvalX;
		g.draw(new Ellipse2D.Double(modeOvalX, modeOvalY, modeOvalDiameter,
			modeOvalDiameter));

		// Show the angle between this one and the other two
		// so we can see where the path is "pinched":
		final double h = imp.getWidth();
		final double centreX = imp.getWidth() / 2.0;
		final double centreY = imp.getHeight() / 2.0;
		final double halfAngle = angles[z] / 2;
		final double rightX = centreX + h * Math.sin(halfAngle);
		final double rightY = centreY - h * Math.cos(halfAngle);
		final double leftX = centreX + h * Math.sin(-halfAngle);
		final double leftY = centreX - h * Math.cos(halfAngle);
		//g.setStroke(new BasicStroke(1));
		g.draw(new Line2D.Double(myScreenXDprecise(centreX), myScreenYDprecise(
			centreY), myScreenXDprecise(rightX), myScreenYDprecise(rightY)));
		g.draw(new Line2D.Double(myScreenXDprecise(centreX), myScreenYDprecise(
			centreY), myScreenXDprecise(leftX), myScreenYDprecise(leftY)));

		super.drawOverlay(g); // draw canvas label
		if (!syncWithTracingCanvas() || z == last_slice) {
			// fittedPath.setEditableNode(-1);
			return;
		}

		final Integer fittedIndex = indexToValidIndex.get(z);
		if (fittedIndex != null) {
			final int px = fittedPath.getXUnscaled(fittedIndex);
			final int py = fittedPath.getYUnscaled(fittedIndex);
			final int pz = fittedPath.getZUnscaled(fittedIndex);
			tracerPlugin.setZPositionAllPanes(px, py, pz);
			last_slice = z;
            int last_editable_node = fittedIndex;
			fittedPath.setEditableNode(last_editable_node);
		}

	}

	private boolean syncWithTracingCanvas() {
		return (tracerPlugin.isUIready() && tracerPlugin
			.getUIState() == SNTUI.EDITING);
	}

	protected void showImage() {
		final StackWindow win = new StackWindow(imp, this);
		while (magnification < Math.min(pixelOffset * 2 + 10, 32))
			zoomIn(0, 0);
		win.addWindowListener(new WindowAdapter() {

			@Override
			public void windowActivated(final WindowEvent e) {
				if (syncWithTracingCanvas()) {
					tracerPlugin.selectPath(fittedPath, false);
				}
			}
		});
		win.setVisible(true);
	}

	public double myScreenXDprecise(final double ox, final boolean includePixelOffset) {
		return super.myScreenXDprecise(ox + ((includePixelOffset) ? pixelOffset : 0));
	}

	public double myScreenYDprecise(final double oy, final boolean includePixelOffset) {
		return super.myScreenYDprecise(oy + ((includePixelOffset) ? pixelOffset : 0));
	}

	private static ImagePlus resizeAsNeeded(final ImagePlus imp) {
		if (imp.getWidth() > 19) {
			pixelOffset = 0;
			return imp;
		}
		final ImageStack stackOld = imp.getStack();
		final ImageProcessor ipOld = imp.getStack().getProcessor(1);
		final ImageStack stackNew = new ImageStack(20, 20, stackOld.getColorModel());
		pixelOffset = (20 - imp.getWidth()) / 2;
		ImageProcessor ipNew;
		for (int i = 1; i <= stackOld.size(); i++) {
			ipNew = ipOld.createProcessor(20, 20);
			ipNew.setValue(0.0);
			ipNew.fill();
			ipNew.insert(stackOld.getProcessor(i), pixelOffset, pixelOffset);
			stackNew.addSlice(imp.getStack().getSliceLabel(i), ipNew);
		}
		imp.setStack(null, stackNew);
		return imp;
	}

	public void setInvalidFitLabel(final String label) {
		this.invalidFitLabel = label;
	}

}
