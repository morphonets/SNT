/*-
 * #%L
 * Sholl Analysis plugin for ImageJ.
 * %%
 * Copyright (C) 2005 - 2020 Tiago Ferreira.
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
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.IntStream;

import net.imglib2.display.ColorTable;
import net.imglib2.display.ColorTable8;

import org.scijava.table.DoubleTable;
import org.scijava.table.TableLoader;
import org.scijava.util.VersionUtils;

import ij.ImagePlus;
import ij.io.Opener;
import ij.process.LUT;

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
		final URL url = getResource("csv/ddaCsample.csv");
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
		final URL url = getResource("images/ddaC.tif");
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

	/**
	 * Retrieves Sholl Analysis version
	 *
	 * @return the version or a non-empty place holder string if version could
	 *         not be retrieved.
	 *
	 */
	public static String version() {
		final String VERSION = VersionUtils.getVersion(ShollUtils.class);
		return (VERSION == null) ? "X Dev" : VERSION;
	}


	/**
	 * Retrieves Sholl Analysis implementation date
	 *
	 * @return the implementation date or an empty strong if date could not be
	 *         retrieved.
	 */
	public static String buildDate() {
		String BUILD_DATE = "";
		final Class<ShollUtils> clazz = ShollUtils.class;
		final String className = clazz.getSimpleName() + ".class";
		final String classPath = clazz.getResource(className).toString();
		final String manifestPath = classPath.substring(0, classPath.lastIndexOf("!") + 1) + "/META-INF/MANIFEST.MF";
		try {
			final Manifest manifest = new Manifest(new URL(manifestPath).openStream());
			final Attributes attr = manifest.getMainAttributes();
			BUILD_DATE = attr.getValue("Implementation-Date");
			BUILD_DATE = BUILD_DATE.substring(0, BUILD_DATE.lastIndexOf("T"));
		} catch (final Exception ignored) {
			BUILD_DATE = "";
		}
		return BUILD_DATE;
	}

	public static String getElapsedTime(final long fromStart) {
		final long time = System.currentTimeMillis() - fromStart;
		if (time < 1000)
			return String.format("%02d msec", time);
		else if (time < 90000)
			return String.format("%02d sec", TimeUnit.MILLISECONDS.toSeconds(time));
		return String.format("%02d min, %02d sec", TimeUnit.MILLISECONDS.toMinutes(time),
				TimeUnit.MILLISECONDS.toSeconds(time)
						- TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(time)));
	}

	/* see net.imagej.legacy.translate.ColorTableHarmonizer */
	public static LUT getLut(final ColorTable cTable) {
		final byte[] reds = new byte[256];
		final byte[] greens = new byte[256];
		final byte[] blues = new byte[256];
		for (int i = 0; i < 256; i++) {
			reds[i] = (byte) cTable.getResampled(ColorTable.RED, 256, i);
			greens[i] = (byte) cTable.getResampled(ColorTable.GREEN, 256, i);
			blues[i] = (byte) cTable.getResampled(ColorTable.BLUE, 256, i);
		}
		return new LUT(reds, greens, blues);
	}
}
