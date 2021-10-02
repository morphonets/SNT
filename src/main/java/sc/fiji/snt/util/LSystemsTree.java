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
package sc.fiji.snt.util;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.plugin.PlugIn;
import ij.process.ImageProcessor;

/**
 * Lindenmayer systems utilities
 */
 // See http://fractalfoundation.org/OFC/OFC-2-4.html
public class LSystemsTree implements PlugIn {

	/** Debug method */
	public static void main(final String[] args) {
		IJ.runPlugIn("ipnat.skel.LSystemsTree", "");
	}

	@Override
	public void run(final String ignored) {
		final LSystemsTree lst = new LSystemsTree();
		final ImagePlus imp = IJ.altKeyDown() ? lst.createTreeStack("Fractal Tree") : lst.sampleTree();
		imp.show();
	}

	public ImagePlus createTreeStack(final String title) {
		final int width = 720;
		final int height = 560;
		final ImagePlus imp = IJ.createImage(title, "8-bit black", width, height, 10);
		final ImageStack stack = imp.getImageStack();
		String label = "root";
		for (int i = 1; i <= 10; i++) {
			stack.setSliceLabel(label, i);
			final ImageProcessor ip = stack.getProcessor(i);
			ip.setColor(255);
			ip.setLineWidth(1);
			drawTree(ip, width / 2, height - 10, -90, i);
			label = "Order " + (i + 1);
		}
		return imp;
	}

	/** Creates a simple fractal tree. Tree may not be a formal skeleton */
	public ImagePlus sampleTree() {
		return createTree("TreeV", 140, 170, -90, 5);
	}

	public ImagePlus createTree(final String title, final int width, final int height, final double angle,
			final int recursions) {
		final ImagePlus imp = IJ.createImage(title, "8-bit black", width, height, 1);
		final ImageProcessor ip = imp.getProcessor();
		ip.setColor(255);
		ip.setLineWidth(1);
		drawTree(ip, width / 2, height - 10, angle, recursions);
		return imp;
	}


	private void drawTree(final ImageProcessor ip, final int x1, final int y1, final double angle,
			final int recursions) {
		// See http://codehackersblog.blogspot.com/2015/06/l-systems-tree-fractal-in-java.html
		if (recursions < 0)
			return;
		final int x2 = x1 + (int) (Math.cos(Math.toRadians(angle)) * recursions * 10);
		final int y2 = y1 + (int) (Math.sin(Math.toRadians(angle)) * recursions * 10);
		ip.drawLine(x1, y1, x2, y2);
		drawTree(ip, x2, y2, angle - 20, recursions - 1);
		drawTree(ip, x2, y2, angle + 20, recursions - 1);
	}

}
