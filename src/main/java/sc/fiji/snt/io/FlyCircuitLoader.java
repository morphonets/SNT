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

package sc.fiji.snt.io;

import sc.fiji.snt.PathAndFillManager;
import sc.fiji.snt.Tree;
import sc.fiji.snt.gui.GuiUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

/**
 * Absurdly simple importer for retrieving SWC data from
 * <a href="http://www.flycircuit.tw">FlyCircuit</a>.
 *
 * @author Tiago Ferreira
 */
public class FlyCircuitLoader implements RemoteSWCLoader {

	private static final String BASE_URL =
			"http://www.flycircuit.tw/flycircuitSourceData/NeuronData_v1.2/";
	private static final String SWC_PREFIX = "_seg001_linesetTransformRelease.swc";
	private static final String VERSION = "1.2";

	/**
	 * Checks whether a connection to the FlyCircuit database can be established.
	 *
	 * @return true, if an HHTP connection could be established, false otherwise
	 */
	@Override
	public boolean isDatabaseAvailable() {
		HttpURLConnection connection = null;
		boolean isOnline = false;
		try {
			final URL url = new URI(BASE_URL).toURL();
			connection = (HttpURLConnection) url.openConnection();
			connection.setRequestMethod("GET");
			connection.setConnectTimeout(5000);
			connection.connect();
			isOnline = connection
				.getResponseCode() == HttpURLConnection.HTTP_FORBIDDEN;
		}
		catch (final IOException | URISyntaxException ignored) {
			// do nothing
		}
		finally {
			connection.disconnect();
		}
		return isOnline;
	}

	/**
	 * Gets the URL of the SWC file associated with the specified cell ID.
	 *
	 * @param cellId the ID of the cell to be retrieved
	 * @return the reconstruction URL
	 */
	@Override
	public String getReconstructionURL(final String cellId) {
		final StringBuilder sb = new StringBuilder();
		sb.append(BASE_URL).append(cellId).append("/").append(cellId.replaceAll(" ", "%20"));
		sb.append(SWC_PREFIX);
		return sb.toString();
	}

	/**
	 * Gets the SWC data associated with the specified cell ID as a reader
	 *
	 * @param cellId the ID of the cell to be retrieved
	 * @return the character stream containing the data, or null if cell ID was
	 *         not found or could not be retrieved
	 */
	@Override
	public BufferedReader getReader(final String cellId) {
		try {
			final URL url = new URI(getReconstructionURL(cellId)).toURL();
			final InputStream is = url.openStream();
			return new BufferedReader(new InputStreamReader(is));
		}
		catch (final IOException | URISyntaxException e) {
			return null;
		}
	}

	/**
	 * Gets the collection of Paths for the specified cell ID
	 *
	 * @param cellId the ID of the cell to be retrieved
	 * @return the data for the specified cell as a {@link Tree}, or null if data
	 *         could not be retrieved
	 */
	@Override
	public Tree getTree(final String cellId) {
		final PathAndFillManager pafm = new PathAndFillManager(1, 1, 1, GuiUtils.micrometer());
		pafm.setHeadless(true);
		Tree tree = null;
		if (pafm.importSWC(cellId, getReconstructionURL(cellId))) {
			tree = new Tree(pafm.getPaths());
			tree.setLabel(cellId);
			tree.getProperties().setProperty(Tree.KEY_SPATIAL_UNIT, GuiUtils.micrometer());
			tree.getProperties().setProperty(Tree.KEY_SOURCE, "FlyCircuit"+VERSION);
		}
		pafm.dispose();
		return tree;
	}

	/*
	 * IDE debug method
	 *
	 * @throws IOException
	 */
	public static void main(final String... args) {
		final String cellId = "VGlut-F-400787";
		final FlyCircuitLoader loader = new FlyCircuitLoader();
		final Tree tree = loader.getTree(cellId);

		System.out.println("# Getting neuron " + cellId);
		System.out.println("FlyCircuit available: " + loader.isDatabaseAvailable());
		System.out.println("URL :" + loader.getReconstructionURL(cellId));
		System.out.println("Successful import: " + (tree != null));
		System.out.println(cellId + " contains  " + tree.size() + " paths");
		System.out.println("# All done");
	}
}
