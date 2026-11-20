/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2026 Fiji developers.
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
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import sc.fiji.snt.util.IOUtils;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Importer for retrieving SWC data from neuromorpho.org. Besides downloading
 * reconstructions, it exposes the metadata, morphometry and query endpoints of
 * the NeuroMorpho REST API.
 * <p>
 * Three versions of a reconstruction can be retrieved, see {@link Version}.
 * By default, {@link Version#CCF} is requested, falling back to
 * {@link Version#CNG} for cells without a CCF registration.
 * </p>
 *
 * @author Tiago Ferreira
 */
public class NeuroMorphoLoader implements RemoteSWCLoader {

	/** The flavors of SWC files hosted by NeuroMorpho.org */
	public enum Version {
		/** Registered to the Allen CCF. Not available for all cells */
		CCF("/ccf/", ".CNG.CCF.swc"),
		/** Standardized (CNG) version */
		CNG("/CNG%20version/", ".CNG.swc"),
		/** Original file as deposited by the authors */
		SOURCE("/Source-Version/", ".swc");

		private final String folder;
		private final String suffix;

		Version(final String folder, final String suffix) {
			this.folder = folder;
			this.suffix = suffix;
		}
	}

	private record Resolved(String url, Version version) {}

	private static final String BASE_URL = "https://neuromorpho.org/api/";
	private static final String FILES_URL = "https://neuromorpho.org/dableFiles/";
	private static final String NEURON_BASE_URL = BASE_URL + "neuron/name/";
	private static final String SELECT_URL = BASE_URL + "neuron/select";
	private static final int MAX_PAGE_SIZE = 500;
	private static final MediaType JSON_TYPE = MediaType.parse("application/json");
	private Version version = Version.CCF;

	private JSONObject getJSon(final String url, final String anchor) {
		final Request request = new Request.Builder().url(url + anchor).build();
		return execute(request);
	}

	private JSONObject execute(final Request request) {
		try (final Response response = IOUtils.httpClient().newCall(request).execute()) {
			if (!response.isSuccessful() || response.body() == null) return null;
			return new JSONObject(response.body().string());
		}
		catch (final IOException | JSONException e) {
			SNTUtils.error("Unexpected response from " + request.url(), e);
			return null;
		}
	}

	private static boolean exists(final String url) {
		final Request request = new Request.Builder().url(url).head().build();
		try (final Response response = IOUtils.httpClient().newCall(request).execute()) {
			return response.isSuccessful();
		}
		catch (final IOException e) {
			return false;
		}
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
		return "UP".equals(jObject.optString("status"));
	}

	private Resolved resolve(final String cellId) {
		final JSONObject json = getNeuronInfo(cellId);
		if (json == null) return null;
		final String root = FILES_URL + json.getString("archive").toLowerCase()
				.replace(" ", "%20");
		final String filename = cellId.replace(" ", "%20");
		if (version == Version.CCF) {
			final String ccfUrl = root + Version.CCF.folder + filename + Version.CCF.suffix;
			if (exists(ccfUrl)) return new Resolved(ccfUrl, Version.CCF);
			SNTUtils.log("No CCF version for " + cellId + ". Using CNG version");
			return new Resolved(root + Version.CNG.folder + filename + Version.CNG.suffix, Version.CNG);
		}
		return new Resolved(root + version.folder + filename + version.suffix, version);
	}

	/**
	 * Gets the URL of the SWC file associated with the specified cell ID. If the
	 * CCF version is requested but not available, the URL of the CNG version is
	 * returned.
	 *
	 * @param cellId the ID of the cell to be retrieved
	 * @return the reconstruction URL, or null if cell ID was not found or could
	 *         not be retrieved
	 */
	@Override
	public String getReconstructionURL(final String cellId) {
		final Resolved r = resolve(cellId);
		return (r == null) ? null : r.url();
	}

	/**
	 * Gets the SWC data associated with the specified cell ID as a reader
	 *
	 * @param cellId the ID of the cell to be retrieved
	 * @return the character stream containing the data, or null if cell ID
	 *         was not found or could not be retrieved
	 */
	@Override
	public BufferedReader getReader(final String cellId) {
		try {
			final String urlString = getReconstructionURL(cellId);
			if (urlString == null) return null;
			final InputStream is = new URI(urlString).toURL().openStream();
			return new BufferedReader(new InputStreamReader(is));
		}
		catch (final IOException | URISyntaxException e) {
			return null;
		}
	}

	/**
	 * Sets the version of the SWC files to be retrieved
	 *
	 * @param version the version. Null restores the default ({@link Version#CCF})
	 */
	public void setVersion(final Version version) {
		this.version = (version == null) ? Version.CCF : version;
	}

	/**
	 * Gets the version of the SWC files to be retrieved
	 *
	 * @return the requested version
	 */
	public Version getVersion() {
		return version;
	}

	/**
	 * Enables or disables the use of source version URLs.
	 *
	 * @param enable true to retrieve the source version, false to retrieve the
	 *               CNG version
	 * @deprecated use {@link #setVersion(Version)}
	 */
	@Deprecated
	public void enableSourceVersion(final boolean enable) {
		version = enable ? Version.SOURCE : Version.CNG;
	}

	/**
	 * Gets the collection of Paths for the specified cell ID
	 *
	 * @param cellId the ID of the cell to be retrieved
	 * @return the data for the specified cell as a {@link Tree}, or null if data
	 *         could not be retrieved. The version actually retrieved is stored in
	 *         the Tree properties under the key {@value #KEY_VERSION}
	 */
	@Override
	public Tree getTree(final String cellId) {
		final Resolved r = resolve(cellId);
		if (r == null) return null;
		final PathAndFillManager pafm = new PathAndFillManager();
		pafm.setHeadless(true);
		Tree tree = null;
		if (pafm.importSWC(cellId, r.url())) {
			tree = new Tree(pafm.getPaths());
			tree.setLabel(cellId);
			tree.getProperties().setProperty(Tree.KEY_SOURCE, "NeuroMorpho");
			tree.getProperties().setProperty(KEY_VERSION, r.version().name());
		}
		pafm.dispose();
		return tree;
	}

	/** Tree property holding the {@link Version} of a retrieved cell */
	public static final String KEY_VERSION = "neuromorpho-version";

	/**
	 * Gets the metadata of a neuron.
	 *
	 * @param cellId the neuron name
	 * @return the JSON record (see neuron fields in the NeuroMorpho API), or null
	 *         if the neuron was not found
	 */
	public JSONObject getNeuronInfo(final String cellId) {
		return getJSon(NEURON_BASE_URL, cellId);
	}

	/**
	 * Gets the morphometry of a neuron as computed by NeuroMorpho.org.
	 *
	 * @param cellId the neuron name
	 * @return the JSON record (e.g., "length", "n_bifs", "branch_Order"), or null
	 *         if the neuron was not found
	 */
	public JSONObject getMorphometry(final String cellId) {
		return getJSon(BASE_URL + "morphometry/name/", cellId);
	}

	/**
	 * Lists the neuron fields that can be used in queries.
	 *
	 * @return the field names (e.g., "species", "brain_region"), or an empty list
	 */
	public List<String> getFields() {
		final JSONObject json = getJSon(BASE_URL, "neuron/fields");
		final List<String> list = new ArrayList<>();
		if (json != null) toStrings(json.optJSONArray("Neuron Fields"), list);
		return list;
	}

	/**
	 * Lists the values present in the database for a neuron field.
	 *
	 * @param field a name returned by {@link #getFields()}
	 * @return the values, or an empty list
	 */
	public List<String> getFieldValues(final String field) {
		final List<String> list = new ArrayList<>();
		for (int page = 0, total = 1; page < total; page++) {
			final JSONObject json = getJSon(BASE_URL + "neuron/fields/" + field, "?page=" + page);
			if (json == null) break;
			toStrings(json.optJSONArray("fields"), list);
			total = json.optJSONObject("page", new JSONObject()).optInt("totalPages", 1);
		}
		return list;
	}

	/**
	 * Counts neurons per value of a neuron field.
	 *
	 * @param field a name returned by {@link #getFields()}
	 * @return the neuron count of each value, or an empty map
	 */
	public Map<String, Integer> getPartition(final String field) {
		final Map<String, Integer> map = new LinkedHashMap<>();
		for (int page = 0, total = 1; page < total; page++) {
			final JSONObject json = getJSon(BASE_URL + "neuron/partition/" + field, "?page=" + page);
			if (json == null) break;
			final JSONObject fields = json.optJSONObject("fields");
			if (fields != null) fields.keySet().forEach(k -> map.put(k, fields.getInt(k)));
			total = json.optJSONObject("page", new JSONObject()).optInt("totalPages", 1);
		}
		return map;
	}

	/**
	 * Retrieves the names of all neurons matching the specified criteria. Values
	 * of a field are combined with OR, fields are combined with AND.
	 * <pre>{@code
	 * loader.getNeuronNames(Map.of("species", List.of("mouse"),
	 *                              "brain_region", List.of("hippocampus")));
	 * }</pre>
	 *
	 * @param criteria map of neuron field to accepted values. See
	 *                 {@link #getFields()} and {@link #getFieldValues(String)}
	 * @return the matching neuron names (empty if none or if the query failed)
	 */
	public List<String> getNeuronNames(final Map<String, ? extends Collection<String>> criteria) {
		return getNeuronNames(criteria, Integer.MAX_VALUE);
	}

	/**
	 * Same as {@link #getNeuronNames(Map)}, but stops after retrieving the
	 * specified number of names.
	 *
	 * @param criteria map of neuron field to accepted values
	 * @param limit    the maximum number of names to retrieve
	 * @return the matching neuron names (at most {@code limit})
	 */
	public List<String> getNeuronNames(final Map<String, ? extends Collection<String>> criteria,
			final int limit) {
		final JSONObject body = new JSONObject();
		criteria.forEach((k, v) -> body.put(k, new JSONArray(v)));
		final List<String> names = new ArrayList<>();
		for (int page = 0, total = 1; page < total; page++) {
			final Request request = new Request.Builder()
					.url(SELECT_URL + "?size=" + Math.min(limit, MAX_PAGE_SIZE) + "&page=" + page)
					.post(RequestBody.create(body.toString(), JSON_TYPE)).build();
			final JSONObject json = execute(request);
			if (json == null) break;
			final JSONObject embedded = json.optJSONObject("_embedded");
			if (embedded == null) break;
			final JSONArray neurons = embedded.optJSONArray("neuronResources");
			for (int i = 0; neurons != null && i < neurons.length(); i++)
				names.add(neurons.getJSONObject(i).getString("neuron_name"));
			if (names.size() >= limit) break;
			total = json.optJSONObject("page", new JSONObject()).optInt("totalPages", 1);
		}
		return (names.size() > limit) ? names.subList(0, limit) : names;
	}

	/**
	 * Retrieves multiple neurons.
	 *
	 * @param cellIds the neuron names, e.g., as returned by
	 *                {@link #getNeuronNames(Map)}
	 * @return the neurons that could be retrieved
	 */
	public List<Tree> getTrees(final Collection<String> cellIds) {
		final List<Tree> trees = new ArrayList<>();
		for (final String id : cellIds) {
			final Tree tree = getTree(id);
			if (tree != null) trees.add(tree);
		}
		return trees;
	}

	private static void toStrings(final JSONArray array, final List<String> list) {
		for (int i = 0; array != null && i < array.length(); i++)
			list.add(array.getString(i));
	}

	/**
	 * Convenience method for retrieving SWC data,
	 *
	 * @param cellId the ID of the cell to be retrieved (case-sensitive). It may be the neuron name or its qualified
	 *               filename. E.g., "cnic_002" or "cnic_002.swc" or "cnic_002.CNG.swc". Examples:
	 *               <pre>{@code
	 *               "cnic_002" -> CCF version of neuron cnic_002 is retrieved (CNG if no CCF version exists)
	 *               "cnic_002.CNG.CCF.swc" -> same as above
	 *               "cnic_002.CNG.swc" -> CNG version of neuron cnic_002 is retrieved
	 *               "cnic_002.swc" -> Source version of neuron cnic_002 is retrieved
	 *               }</pre>
	 * @return the specified neuron as a {@link Tree} object, or null if data could not be retrieved
	 * @throws IOException if an error occurs while retrieving the data
	 */
	public static Tree get(final String cellId) throws IOException {
		if (cellId == null) return null;
		String cellName = cellId;
		Version v = Version.CCF;
		// Longest suffix first
		for (final Version candidate : new Version[] { Version.CCF, Version.CNG, Version.SOURCE }) {
			if (cellId.endsWith(candidate.suffix)) {
				cellName = cellId.substring(0, cellId.length() - candidate.suffix.length());
				v = candidate;
				break;
			}
		}
		final NeuroMorphoLoader loader = new NeuroMorphoLoader();
		loader.version = v;
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
		loader.setVersion(Version.SOURCE);
		urlPath = loader.getReconstructionURL(cellId);
		System.out.println("URL :" + urlPath);
		pafm.importSWC(cellId, urlPath);
		final SNT snt = new SNT(ij.context(), pafm);
		snt.initialize(false, 1, 1);
		snt.startUI();
		System.out.println("# All done");
	}
}
