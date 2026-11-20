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

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import sc.fiji.snt.Tree;
import sc.fiji.snt.util.IOUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Importers downloading remote SWC files should extend this interface.
 *
 * @author Tiago Ferreira
 */
public interface RemoteSWCLoader {

	public boolean isDatabaseAvailable();

	public String getReconstructionURL(String cellId);

	public BufferedReader getReader(final String cellId);

	public Tree getTree(final String cellId);

	/**
	 * Retrieves multiple reconstructions concurrently, see {@link RemoteRetriever}.
	 *
	 * @param cellIds   the ids of the cells to be retrieved
	 * @param nThreads  the maximum number of simultaneous retrievals (see {@link RemoteRetriever#DEFAULT_THREADS})
	 * @param progress  the progress listener. Null allowed
	 * @param cancelled polled while waiting for retrievals. Null allowed
	 * @return the trees, in the order of {@code cellIds}. Cells that could not be retrieved are represented by an
	 *         empty Tree
	 */
	default List<Tree> getTrees(final Collection<String> cellIds, final int nThreads,
			final RemoteRetriever.ProgressListener progress, final BooleanSupplier cancelled) {
		return RemoteRetriever.retrieve(new ArrayList<>(cellIds), this::getTree, nThreads, progress, cancelled);
	}

	/**
	 * Opens a character stream to a remote text resource using the shared HTTP client. Transient failures (HTTP 429
	 * and 5xx) are retried a couple of times with increasing delays. Closing the reader releases the connection
	 *
	 * @param url the resource URL
	 * @return the reader. The caller is responsible for closing it
	 * @throws IOException if the URL is invalid, the resource could not be reached, or the server response was not
	 *                     successful
	 */
	static BufferedReader openReader(final String url) throws IOException {
		final Request request;
		try {
			request = new Request.Builder().url(url).build();
		} catch (final IllegalArgumentException e) {
			throw new IOException("Invalid URL: " + url, e);
		}
		IOException failure = null;
		for (int attempt = 0; attempt < 3; attempt++) {
			if (attempt > 0) {
				try {
					Thread.sleep(500L << attempt);
				} catch (final InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new InterruptedIOException("Interrupted while retrying " + url);
				}
			}
			final Response response = IOUtils.httpClient().newCall(request).execute();
			final ResponseBody body = response.body();
			if (response.isSuccessful() && body != null) return new BufferedReader(body.charStream());
			final int code = response.code();
			response.close();
			failure = new IOException("Unsuccessful response (" + code + ") from " + url);
			if (code != 429 && code < 500) break; // not transient
		}
		throw failure;
	}

}
