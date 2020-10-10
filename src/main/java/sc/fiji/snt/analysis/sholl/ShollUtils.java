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
package sc.fiji.snt.analysis.sholl;

import java.awt.Color;
import java.io.IOException;
import java.net.URL;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.PrimitiveIterator.OfInt;
import java.util.stream.IntStream;

import net.imglib2.display.ColorTable8;

import org.scijava.table.DoubleTable;
import org.scijava.table.TableLoader;

import ij.ImagePlus;
import ij.io.Opener;

/**
 * Static utilities.
 * 
 * @author Tiago Ferreira
 * 
 */
public class ShollUtils {

	/* Plugin Information */
	public static final String URL = "https://imagej.net/Sholl_Analysis";

	private ShollUtils() {
	}

	public static String d2s(final double d) {
		return new DecimalFormat("#.###").format(d);
	}

	// this method is from BAR
	private static URL getResource(final String resourcePath) {
		final ClassLoader loader = Thread.currentThread().getContextClassLoader();
		URL resource = null;
		try {
			final Enumeration<URL> resources = loader.getResources(resourcePath);
			while (resources.hasMoreElements()) {
				resource = resources.nextElement();
				if (resource.toString().contains(resourcePath))
					return resource;
			}
		} catch (final IOException exc) {
			// proceed with return null;
		}
		return resource;
	}

	public static DoubleTable csvSample() {
		final URL url = getResource("tests/ddaCsample.csv");
		if (url == null)
			throw new IllegalArgumentException("Could not retrieve ddaCsample.csv");
		final TableLoader loader = new TableLoader();
		try {
			// NB: this will fail for headings containing whitespace[
			return loader.valuesFromTextFile(url);
		} catch (final IOException exc) {
			exc.printStackTrace();
			return null;
		}
	}

	public static ArrayList<Double> getRadii(final double startRadius, final double incStep, final double endRadius) {

		if (Double.isNaN(startRadius) || Double.isNaN(incStep) || Double.isNaN(endRadius) || incStep <= 0
				|| endRadius < startRadius) {
			throw new IllegalArgumentException("Invalid parameters: " + startRadius + "," + incStep + "," + endRadius);
		}
		final int size = (int) ((endRadius - startRadius) / incStep) + 1;
		final ArrayList<Double> radii = new ArrayList<>();
		for (final OfInt it = IntStream.range(0, size).iterator(); it.hasNext();) {
			radii.add(startRadius + it.nextInt() * incStep);
		}
		return radii;

	}

	public static ColorTable8 constantLUT(final Color color) {
		final byte[][] values = new byte[3][256];
		for (int i = 0; i < 256; i++) {
			values[0][i] = (byte) color.getRed();
			values[1][i] = (byte) color.getGreen();
			values[2][i] = (byte) color.getBlue();
		}
		return new ColorTable8(values);
	}

	public static String extractHemiShellFlag(final String string) {
		if (string == null || string.trim().isEmpty())
			return ProfileProperties.HEMI_NONE;
		final String flag = string.toLowerCase();
		if (flag.contains("none") || flag.contains("full"))
			return ProfileProperties.HEMI_NONE;
		if (flag.contains("above") || flag.contains("north"))
			return ProfileProperties.HEMI_NORTH;
		else if (flag.contains("below") || flag.contains("south"))
			return ProfileProperties.HEMI_SOUTH;
		else if (flag.contains("left") || flag.contains("east"))
			return ProfileProperties.HEMI_EAST;
		else if (flag.contains("right") || flag.contains("west"))
			return ProfileProperties.HEMI_WEST;
		return flag;
	}

	/**
	 * Returns the plugin's sample image (File&gt;Samples&gt;ddaC Neuron).
	 *
	 * @return ddaC image, or null if image cannot be retrieved
	 */
	public static ImagePlus sampleImage() {
		final URL url = getResource("tests/ddaC.tif");
		if (url == null)
			throw new NullPointerException("Could not retrieve ddaC.tif");
		ImagePlus imp = null;
		try {
			final Opener opener = new Opener();
			imp = opener.openTiff(url.openStream(), "Drosophila_ddaC_Neuron.tif");
		} catch (final IOException exc) {
			exc.printStackTrace();
		}
		return imp;
	}

}
