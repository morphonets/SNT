/*
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
package sc.fiji.snt.plugin.ij1;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.HTMLDialog;
import ij.gui.Plot;
import ij.plugin.PlugIn;
import ij.text.TextWindow;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.analysis.sholl.ShollUtils;
import sc.fiji.snt.analysis.sholl.gui.ShollPlot;
import sc.fiji.snt.util.ShollPoint;

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.image.IndexColorModel;

/**
 * Utilities for {@link Sholl_Analysis}
 *
 * @see <a href="https://github.com/tferr/ASA">https://github.com/tferr/ASA</a>
 * @see <a href="http://imagej.net/Sholl">http://imagej.net/Sholl</a>
 * @author Tiago Ferreira
 * @deprecated This IJ1 plugin is no longer needed when using IJ2 commands.
 *             Kept here for backwards compatibility
 */
@Deprecated
public class Sholl_Utils implements PlugIn {

	private static final String SRC_URL = "https://github.com/tferr/ASA";

	/**
	 * This method is called when the plugin is loaded. <code>arg</code> is
	 * specified in <code>plugins.config</code>. See
	 * {@link ij.plugin.PlugIn#run(java.lang.String)}
	 *
	 * @param arg
	 *            If <code>about</code> the "About" dialog is displayed. If
	 *            <code>sample</code>, a demo image suitable for Sholl analysis
	 *            is opened.
	 */
	@Override
	public void run(final String arg) {
		if (arg.equalsIgnoreCase("about"))
			showAbout();
		else if (arg.equalsIgnoreCase("sample"))
			displaySample();
	}

	/**
	 * Returns the plugin's sample image (File&gt;Samples&gt;ddaC Neuron).
	 *
	 * @return ddaC image, or null if image cannot be retrieved
	 * @deprecated Use {@link ShollUtils#sampleImage()} instead
	 */
	@Deprecated
	public static ImagePlus sampleImage() {
		return ShollUtils.sampleImage();
	}

	/**
	 *  Displays the ddaC sample image and returns a reference to it.
	 *
	 * @return the ddaC sample image
	 */
	protected static ImagePlus displaySample() {
		final ImagePlus imp = ShollUtils.sampleImage();
		if (imp == null) {
			IJ.showStatus("Error: Could not open ddaC.tif!");
			IJ.beep();
		} else {
			imp.show();
		}
		return imp;
	}

	/**
	 * Returns an IndexColorModel similar to MATLAB's jet color map.
	 *
	 * @param backgroundGray
	 *            the gray value (8-bit scale) to be used as the first entry of
	 *            the LUT. It is ignored if negative.
	 * @return The "Jet" LUT with the specified background entry
	 * @see <a href=
	 *      "https://list.nih.gov/cgi-bin/wa.exe?A2=IMAGEJ;c8cb4d8d.1306">Jerome
	 *      Mutterer alternative</a>
	 */
	public static IndexColorModel matlabJetColorMap(final int backgroundGray) {
		return matlabJetColorMap(backgroundGray, -1);
	}

	protected static IndexColorModel matlabJetColorMap(final int backgroundGray, final int foregroundGray) {

		// Initialize colors arrays (zero-filled by default)
		final byte[] reds = new byte[256];
		final byte[] greens = new byte[256];
		final byte[] blues = new byte[256];

		// Set greens, index 0-32; 224-255: 0
		for (int i = 0; i < 256 / 4; i++) // index 32-96
			greens[i + 256 / 8] = (byte) (i * 255 * 4 / 256);
		for (int i = 256 * 3 / 8; i < 256 * 5 / 8; ++i) // index 96-160
			greens[i] = (byte) 255;
		for (int i = 0; i < 256 / 4; i++) // index 160-224
			greens[i + 256 * 5 / 8] = (byte) (255 - (i * 255 * 4 / 256));

		// Set blues, index 224-255: 0
		for (int i = 0; i < 256 * 7 / 8; i++) // index 0-224
			blues[i] = greens[(i + 256 / 4) % 256];

		// Set reds, index 0-32: 0
		for (int i = 256 / 8; i < 256; i++) // index 32-255
			reds[i] = greens[(i + 256 * 6 / 8) % 256];

		// Set background and foreground colors
		if (backgroundGray >= 0) {
			reds[0] = greens[0] = blues[0] = (byte) backgroundGray;
		}
		if (foregroundGray >= 0) {
			reds[255] = greens[255] = blues[255] = (byte) foregroundGray;
		}

		return new IndexColorModel(8, 256, reds, greens, blues);

	}

	/** Displays the Sholl's plugin "about" info box */
	private void showAbout() {

		final String version = SNTUtils.VERSION + " " + SNTUtils.buildDate();
		final String header1 = "Sholl Analysis " + version;
		final String header2 = "Quantitative Sholl-based morphometry of untraced images";
		final String author = "Tiago Ferreira";
		final String authorURL = "http://imagej.net/User:Tiago";
		final String contributorsURL = SRC_URL + "/graphs/contributors";
		final String releaseNotesURL = SRC_URL + "/blob/master/Notes.md#release-notes-for-sholl-analysis";
		final String javadocsURL = "http://tferr.github.io/ASA/apidocs/";
		final String forumURL = "http://forum.imagej.net/search?q=sholl";
		final String msURL = "http://www.nature.com/nmeth/journal/v11/n10/full/nmeth.3125.html";
		final String spacer = "&nbsp;&nbsp;|&nbsp;&nbsp;";

		final StringBuilder sb = new StringBuilder();
		sb.append("<html>");
		sb.append("<div WIDTH=480>");
		sb.append("<div align='center'>");
		sb.append("<b>").append(header1).append("</b><br>");
		sb.append("<b>" + header2 + "</b><br>");
		sb.append("<a href='" + authorURL + "'>" + author + "</a> and ");
		sb.append("<a href='" + contributorsURL + "'>contributors</a>");
		sb.append("</div>");
		sb.append("<br><br>");
		sb.append("Released under <a href='https://opensource.org/licenses/GPL-3.0.html'>GPL-3.0</a>. ");
		sb.append("Many thanks to Tom Maddock for writing version 1.0; "
				+ "Johannes Schindelin, Wayne Rasband, Mark Longair, Stephan Preibisch "
				+ "and Bio-Formats team for code snippets.");
		sb.append("<br><br>");
		sb.append("<b>Resources:</b><br>");
		// sb.append("<a href='"+ msURL + "'>Manuscript</a>" + inLineSpacer);
		sb.append("<a href='" + Sholl_Analysis.URL + "'>Documentation</a>" + spacer);
		sb.append("<a href='" + forumURL + "'>Forum</a>" + spacer);
		sb.append("<a href='" + releaseNotesURL + "'>Release Notes</a>" + spacer);
		sb.append("<a href='" + SRC_URL + "'>Source</a>" + spacer);
		sb.append("<a href='" + javadocsURL + "'>Javadoc</a>");
		sb.append("<br><br>");
		sb.append("<b>Citation:</b><br>");
		sb.append("Ferreira et al. ");
		sb.append("<a href='" + msURL + "'>Nat Methods 11, 982-4 (2014)</a>");
		sb.append("</div>");
		sb.append("</html>");
		new HTMLDialog("About Sholl Analysis...", sb.toString());
	}

	/*
	 * Converts an integer to its ordinal (http://stackoverflow.com/a/6810409)
	 */
	protected static String ordinal(final int i) {
		final String[] sufixes = new String[] { "th", "st", "nd", "rd", "th", "th", "th", "th", "th", "th" };
		switch (i % 100) {
		case 11:
		case 12:
		case 13:
			return i + "th";
		default:
			return i + sufixes[i % 10];

		}
	}

	/**
	 * Calculates the centroid of a non-self-intersecting closed polygon. It is
	 * assumed that <code>xpoints</code> and <code>ypoints</code> have the same
	 * size
	 *
	 * @param xpoints
	 *            X coordinates of vertices
	 * @param ypoints
	 *            Y coordinates of vertices
	 * @return the centroid {x,y} coordinates
	 * @see <a href="http://en.wikipedia.org/wiki/Centroid#Centroid_of_polygon">
	 *      Centroid of polygon </a>
	 */
	public static double[] baryCenter(final double[] xpoints, final double[] ypoints) {

		double area = 0, sumx = 0, sumy = 0;
		for (int i = 1; i < xpoints.length; i++) {
			final double cfactor = (xpoints[i - 1] * ypoints[i]) - (xpoints[i] * ypoints[i - 1]);
			sumx += (xpoints[i - 1] + xpoints[i]) * cfactor;
			sumy += (ypoints[i - 1] + ypoints[i]) * cfactor;
			area += cfactor / 2;
		}
		return new double[] { sumx / (6 * area), sumy / (6 * area) };

	}

	@Deprecated
	public static void makePlotLabel(final Plot plot, final String label, final Color color) {
		((ShollPlot) plot).drawLabel(label, color);
	}

	@Deprecated
	public static void markPlotPoint(final Plot plot, final double[] coordinates, final Color color) {
		((ShollPlot) plot).markPoint(new ShollPoint(coordinates[0], coordinates[1]), color);
	}

	/**
	 * Retrieves text from the system clipboard.
	 *
	 * @return the text contents of the clipboard or an empty string if no text
	 *         could be retrieved
	 */
	protected static String getClipboardText() {
		String text = "";
		try {
			final Toolkit toolkit = Toolkit.getDefaultToolkit();
			final Clipboard clipboard = toolkit.getSystemClipboard();
			text = (String) clipboard.getData(DataFlavor.stringFlavor);
		} catch (final Exception e) {
			// if (IJ.debugMode) IJ.handleException(e);
		}
		return text;
	}

	protected static TextWindow getTextWindow(final String windowtitle) {
		final Frame f = WindowManager.getFrame(windowtitle);
		if (f == null || !(f instanceof TextWindow))
			return null;
		return (TextWindow) f;
	}

	/**
	 * Retrieves the Sholl Analysis version
	 *
	 * @return the version or a non-empty placeholder string if version could
	 *         not be retrieved.
	 * @deprecated Use {@link SNTUtils#VERSION} instead
	 *
	 */
	@Deprecated
	public static String version() {
		return SNTUtils.VERSION;
	}

}
