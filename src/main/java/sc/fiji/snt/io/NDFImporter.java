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

package sc.fiji.snt.io;

import java.awt.Color;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import ij.gui.Overlay;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.RoiConverter;

/**
 * Importer for NeuronJ data files.
 *
 * @author Tiago Ferreira
 */
public class NDFImporter {

	private static final String NJ_NAME = "NeuronJ";
	private static final String NJ_VERSION = "1.4.3"; // latest supported version

	static final Color[] NJ_COLORS = { // Colors supported by NeuronJ as of v.1.4.3
			Color.BLACK, Color.BLUE, Color.CYAN, Color.GREEN, Color.MAGENTA, //
			Color.ORANGE, Color.PINK, Color.RED, Color.YELLOW };

	private final Color[] typeColors = { // Default type colors as of v.1.4.3. 11 elements
			Color.MAGENTA, Color.RED, Color.BLUE, Color.RED, Color.BLUE, Color.YELLOW, //
			Color.MAGENTA, Color.MAGENTA, Color.MAGENTA, Color.MAGENTA, Color.MAGENTA //
	};

	private final String[] types = { // Default types as of v.1.4.3. 11 elements
			"Default", "Axon", "Dendrite", "Primary", "Secondary", "Tertiary", "Type 06", //
			"Type 07", "Type 08", "Type 09", "Type 10" //
	};

	private final String[] clusters = { // Default types as of v.1.4.3. 11 elements
			"Default", "Cluster 01", "Cluster 02", "Cluster 03", "Cluster 04", "Cluster 05", //
			"Cluster 06", "Cluster 07", "Cluster 08", "Cluster 09", "Cluster 10" //
	};

	// These fields are stored in NDF files but not really used by SNT
	private int lineWidth = 1; // Line width for render tracings
	private int appear; // Neurite appearance (bright = 0 and dark = 1)
	private float scale; // Scale at which eigenvalues are computed
	private float gamma; // Cost component weight factor
	private int snapRange; // Half-window size for snapping cursor to locally lowest cost
	private int dijkRange; // Window size for shortest-path searching
	private int halfSmoothRange; // Smoothing
	private int subSampleFactor; // Subsampling
	private String version;

	private File file;
	private InputStream is;
	private Collection<Tree> parsedTrees;

	/**
	 * @param is the InputStream of ndf data
	 */
	public NDFImporter(final InputStream is) {
		this.is = is;
		this.file = null;
	}

	/**
	 * @param filePath the path to the ndf file to be imported
	 */
	public NDFImporter(final String filePath) {
		this(new File(filePath));
	}

	/**
	 * @param file the ndf file to be imported
	 */
	public NDFImporter(final File file) {
		this.file = file;
		this.is = null;
	}

	/**
	 * Extracts tracings from the specified file path
	 * 
	 * @return the collection of imported trees
	 * @throws IOException if file could not be parsed
	 */
	public Collection<Tree> getTrees() throws IOException {
		if (parsedTrees == null)
			parsedTrees = getTreesInternal();
		return parsedTrees;
	}

	/**
	 * Converts tracings into ROIs
	 * 
	 * @return the Overlay containing the converted tracings
	 * @throws IOException if file could not be parsed
	 */
	public Overlay getROIs() throws IOException {
		getTrees();
		final Overlay overlay = new Overlay();
		for (final Tree tree : parsedTrees) {
			final RoiConverter converter = new RoiConverter(tree);
			converter.setStrokeWidth(lineWidth);
			converter.convertPaths(overlay);
		}
		return overlay;
	}


	/**
	 * @return the properties ('NDF metadata') of the parsed file
	 */
	public Map<String, String> getProperties() {
		final Map<String, String> map = new HashMap<>();
		try {
			getTrees();
		} catch (final IOException e) {
			throw new IllegalArgumentException(e.getMessage());
		}
		map.put("Version", version);
		map.put("Neurite appearance (bright=0; dark=1)", String.valueOf(appear));
		map.put("Eigenvalue scale", String.format("%.2f", scale));
		map.put("Gamma (cost factor)", String.format("%.2f", gamma));
		map.put("Line width", String.valueOf(lineWidth));
		map.put("Snapping cursor window", String.valueOf(snapRange));
		map.put("Search range", String.valueOf(dijkRange));
		map.put("Smooting", String.valueOf(halfSmoothRange));
		map.put("Sub-sampling", String.valueOf(subSampleFactor));
		return map;
	}

	private BufferedReader getBufferedReader() throws FileNotFoundException {
		if (is == null)
			return  new BufferedReader(new FileReader(file));
		return new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
	}

	private Collection<Tree> getTreesInternal() throws IOException {
		if (file == null)
			SNTUtils.log("Loading NeuronJ tracings");
		else
			SNTUtils.log("Loading tracings from " + file.getAbsolutePath());
		final HashMap<Integer, Tree> map = new HashMap<>();
		try {
			final BufferedReader br = getBufferedReader();
			if (!br.readLine().startsWith("// " + NJ_NAME + " Data File")) {
				br.close();
				throw new IOException("Not a recognizable NDF file.");
			}
			version = br.readLine();
			if (version.compareTo(NJ_VERSION) <= 0) {
				SNTUtils.log("   Opened " + NJ_NAME + " version " + version + " data file");
				br.readLine(); // Parameters
				if (version.compareTo("1.4.0") >= 0)
					appear = Integer.valueOf(br.readLine()).intValue();
				else
					appear = 0; // Bright neurites by default for older file versions
				scale = Float.valueOf(br.readLine()).floatValue();
				gamma = Float.valueOf(br.readLine()).floatValue();
				snapRange = Integer.valueOf(br.readLine()).intValue();
				dijkRange = Integer.valueOf(br.readLine()).intValue();
				halfSmoothRange = Integer.valueOf(br.readLine()).intValue();
				subSampleFactor = Integer.valueOf(br.readLine()).intValue();
				if (version.compareTo("1.1.0") >= 0)
					lineWidth = Integer.valueOf(br.readLine()).intValue();
				if (version.compareTo("1.1.0") < 0) {
					br.readLine(); // Skip pixel x-size
					br.readLine(); // Skip pixel y-size
					br.readLine(); // Skip pixel units
					br.readLine(); // Skip auto-save option
					br.readLine(); // Skip log option
				}
				SNTUtils.log("   Read parameters");

				br.readLine(); // Type names and colors
				for (int i = 0; i <= 10; ++i) {
					types[i] = br.readLine();
					typeColors[i] = NJ_COLORS[Integer.valueOf(br.readLine()).intValue()];
				}
				SNTUtils.log("   Read type names and colors");

				br.readLine(); // Cluster names
				for (int i = 0; i <= 10; ++i)
					clusters[i] = br.readLine();
				SNTUtils.log("   Read cluster names");

				// Tracings
				String line = br.readLine();
				while (line.startsWith("// Tracing")) {
					final Path tracing = new Path();
					final int id = Integer.valueOf(br.readLine()).intValue();
					final int type = Integer.valueOf(br.readLine()).intValue();
					final int cluster = Integer.valueOf(br.readLine()).intValue();
					final String label = br.readLine();
					tracing.setName(label + "[" + id + "]");
					line = br.readLine();
					while (line.startsWith("// Segment")) {
						line = br.readLine();
						while (!line.startsWith("//")) {
							final int x = Integer.valueOf(line).intValue();
							final int y = Integer.valueOf(br.readLine()).intValue();
							tracing.addPointDouble(x, y, 0);
							line = br.readLine();
						}
						if (tracing.getLength() > 0.0) {
							setProperties(tracing, type, cluster);
							if (map.get(cluster) == null) {
								final Tree tree = new Tree();
								tree.setLabel(clusters[cluster]);
								map.put(cluster, tree);
							}
							map.get(cluster).add(tracing);
						}
					}
				}
				SNTUtils.log("   Read tracings");
			} else {
				br.close();
				throw new IllegalStateException(
						"Data file version " + version + " while running version " + NJ_VERSION);
			}

			br.close();
			SNTUtils.log("   Effectuated read data");
			SNTUtils.log("Done");

		} catch (final NumberFormatException | IllegalStateException e) {
			SNTUtils.error("Error reading from file", e);
		} catch (final Throwable e) {
			SNTUtils.error("Unable to read from file", e);
		}
		return map.values();
	}

	private void setProperties(final Path path, final int ndfType, final int clusterIdx) {
		try {
			switch (types[ndfType]) {
			case "Axon":
				path.setSWCType(Path.SWC_AXON);
				break;
			case "Dendrite":
				path.setSWCType(Path.SWC_DENDRITE);
				break;
			case "Default":
				// do nothing
				break;
			default:
				path.setSWCType(Path.SWC_UNDEFINED);
				break;
			}
			path.setColor(typeColors[ndfType]);
		} catch (final Exception ignored) {
			// do nothing
		}
	}

	/**
	 * @return the last known version of NeuronJ known to work this importer
	 */
	public static String supportedVersion() {
		return NJ_VERSION;
	}

	/*
	 * IDE debug method
	 *
	 * @throws IOException
	 */
	public static void main(final String... args) throws IOException {
		final String dirPath = "/home/tferr/code/morphonets/SNT/src/test/resources/";
		final NDFImporter importer = new NDFImporter(dirPath + "neurites.ndf");
		final sc.fiji.snt.viewer.Viewer2D v2d = new sc.fiji.snt.viewer.Viewer2D();
		importer.getTrees().forEach(tree -> v2d.add(tree));
		v2d.show();
	}

}
