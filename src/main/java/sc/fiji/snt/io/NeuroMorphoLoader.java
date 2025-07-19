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

import net.imagej.ImageJ;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;
import sc.fiji.snt.PathAndFillManager;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

/**
 * Importer for retrieving SWC data from neuromorpho.org.
 *
 * @author Tiago Ferreira
 */
public class NeuroMorphoLoader implements RemoteSWCLoader {

	private static final String BASE_URL = "https://neuromorpho.org/api/";
	private final static String NEURON_BASE_URL = BASE_URL + "neuron/name/";
    private boolean sourceVersion;

	private JSONObject getJSon(final String url, final String anchor) {
		Response response = null;
		final OkHttpClient client = new OkHttpClient();
		final Request request = new Request.Builder().url(url + anchor).build();
		String resStr = null;
		try {
			response = client.newCall(request).execute();
			if (!response.isSuccessful()) {
				response.close();
				return null;
			}
			resStr = response.body().string();
		}
		catch (final IOException e) {
			SNTUtils.error("Unexpected response from " + url + anchor, e);
		}
		finally {
			if (response != null) response.close();
			else return null;
		}
		return (resStr == null) ? null : new JSONObject(resStr);
	}

	/**
	 * Checks whether a connection to the NeuroMorpho database can be established.
	 *
	 * @return true, if an HHTP connection could be established, false otherwise
	 */
	@Override
	public boolean isDatabaseAvailable() {
		final JSONObject jObject = getJSon(BASE_URL, "health");
		if (jObject == null) return false;
        String lastKnownStatus = (String) jObject.get("status");
		return "UP".equals(lastKnownStatus);
	}

	/**
	 * Gets the URL of the SWC file ('CNG version') associated with the specified
	 * cell ID.
	 *
	 * @param cellId the ID of the cell to be retrieved
	 * @return the reconstruction URL, or null if cell ID was not found or could
	 *         not be retrieved
	 */
	@Override
	public String getReconstructionURL(final String cellId) {
		final JSONObject json = getJSon(NEURON_BASE_URL, cellId);
		if (json == null) return null;
		final StringBuilder sb = new StringBuilder();
		sb.append("https://neuromorpho.org/dableFiles/");
		final String archive = (String) json.get("archive");
		sb.append(archive.toLowerCase().replaceAll(" ", "%20"));
		final String filename = cellId.replaceAll(" ", "%20");
		if (sourceVersion) {
			sb.append("/Source-Version/");
			sb.append(filename);
			sb.append(".swc");
		} else {
			sb.append("/CNG%20version/");
			sb.append(filename);
			sb.append(".CNG.swc");
		}
		return sb.toString();
	}

	/**
	 * Gets the SWC data ('CNG version') associated with the specified cell ID as
	 * a reader
	 *
	 * @param cellId the ID of the cell to be retrieved
	 * @return the character stream containing the data, or null if cell ID
	 *         was not found or could not be retrieved
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
	 * Enables or disables the use of source version URLs.
	 *
	 * @param enable true to enable source version URLs, false otherwise
	 */
	public void enableSourceVersion(final boolean enable) {
		sourceVersion = enable;
	}

	/**
	 * Gets the collection of Paths for the specified cell ID
	 *
	 * @param cellId the ID of the cell to be retrieved
	 * @return the data ('CNG version') for the specified cell as a {@link Tree},
	 *         or null if data could not be retrieved
	 */
	@Override
	public Tree getTree(final String cellId) {
		final String url = getReconstructionURL(cellId);
		if (url == null) return null;
		final PathAndFillManager pafm = new PathAndFillManager();
		pafm.setHeadless(true);
		Tree tree = null;
		if (pafm.importSWC(cellId, url)) {
			tree = new Tree(pafm.getPaths());
			tree.setLabel(cellId);
			tree.getProperties().setProperty(Tree.KEY_SOURCE, "NeuroMorpho");
		}
		pafm.dispose();
		return tree;
	}

	/**
	 * Convenience method for retrieving SWC data,
	 *
	 * @param cellId the ID of the cell to be retrieved (case-sensitive). It may be the neuron name or its qualified
	 *               filename. E.g., "cnic_002" or "cnic_002.swc" or "cnic_002.CNG.swc". By default, the standardized
	 *               (CNG) version is assumed. Examples:
	 *               <pre>{@code
	 *               "cnic_002" -> CNG version of neuron cnic_002 is retrieved
	 *               "cnic_002.CNG.swc" -> CNG version of neuron cnic_002 is retrieved
	 *               "cnic_002.swc" -> Source version of neuron cnic_002 is retrieved
	 *               }</pre>
	 * @return the specified neuron as a {@link Tree} object, or null if data could not be retrieved
	 * @throws IOException if an error occurs while retrieving the data
	 */
	public static Tree get(final String cellId) throws IOException {
		if (cellId == null) return null;
		String cellName = cellId;
		boolean sourceVersion = false;
		if (cellId.endsWith(".swc")) {
			sourceVersion = true;
			cellName = cellName.substring(0, cellId.length() - 4);
			if (cellId.endsWith(".CNG")) {
				cellName = cellName.substring(0, cellId.length() - 4);
				sourceVersion = false;
			}
		}
		final NeuroMorphoLoader loader = new NeuroMorphoLoader();
		loader.sourceVersion = sourceVersion;
		return loader.getTree(cellName);
	}

	/*
	 * IDE debug method
	 *
	 * @throws IOException
	 */
	public static void main(final String... args) throws IOException {
		final String cellId = "cnic_002";
		final ImageJ ij = new ImageJ();
		final PathAndFillManager pafm = new PathAndFillManager();
		final NeuroMorphoLoader loader = new NeuroMorphoLoader();

		System.out.println("Neuromorpho available: " + loader
			.isDatabaseAvailable());
		System.out.println("# Getting neuron " + cellId);
		String urlPath = loader.getReconstructionURL(cellId);
		System.out.println("URL: " + urlPath);
		pafm.importSWC(cellId, urlPath);
		loader.enableSourceVersion(true);
		urlPath = loader.getReconstructionURL(cellId);
		System.out.println("URL :" + urlPath);
		pafm.importSWC(cellId, urlPath);
		final SNT snt = new SNT(ij.context(), pafm);
		snt.initialize(false, 1, 1);
		snt.startUI();
		System.out.println("# All done");
	}
}
