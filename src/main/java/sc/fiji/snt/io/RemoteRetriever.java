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

import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Retrieves multiple remote reconstructions concurrently. Network-bound steps (lookup of the file location, download)
 * run on a small, bounded pool of threads. The fetching function is expected to be self-contained: Loaders in this
 * package build each {@link Tree} in a private {@link sc.fiji.snt.PathAndFillManager}, so they can be used as is.
 * Adding the retrieved trees to a shared manager (e.g., {@link sc.fiji.snt.PathAndFillManager#addTrees}) remains the
 * responsibility of the caller, and should happen in a single call from a single thread.
 *
 * @author Tiago Ferreira
 */
public final class RemoteRetriever {

	/**
	 * Default number of simultaneous connections. Matches the connection pool of {@link sc.fiji.snt.util.IOUtils#httpClient()}
	 * and is polite to public databases
	 */
	public static final int DEFAULT_THREADS = 4;

	/** Receives progress updates. Always called from the thread that invoked {@link #retrieve} */
	@FunctionalInterface
	public interface ProgressListener {
		/**
		 * @param done  the number of reconstructions processed so far (successfully or not)
		 * @param total the total number of reconstructions
		 * @param id    the id of the reconstruction that was just processed
		 */
		void onProgress(int done, int total, String id);
	}

	private RemoteRetriever() {
		// static methods only
	}

	/**
	 * Retrieves reconstructions concurrently.
	 *
	 * @param ids       the ids of the reconstructions to be retrieved
	 * @param fetcher   the function retrieving a single reconstruction. It may return null or throw if the
	 *                  reconstruction could not be retrieved
	 * @param nThreads  the maximum number of simultaneous retrievals
	 * @param progress  the progress listener. Null allowed
	 * @param cancelled polled while waiting. If it returns true pending retrievals are abandoned. Null allowed
	 * @return the retrieved trees, in the same order as {@code ids}. Reconstructions that could not be retrieved (or
	 *         that were abandoned) are represented by an empty Tree labeled after the id, so the list never contains
	 *         null elements
	 */
	public static List<Tree> retrieve(final List<String> ids, final Function<String, Tree> fetcher,
			final int nThreads, final ProgressListener progress, final BooleanSupplier cancelled) {
		final int total = ids.size();
		final List<Tree> list = new ArrayList<>(total);
		if (total == 0) return list;
		final AtomicReferenceArray<Tree> results = new AtomicReferenceArray<>(total);
		final ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(nThreads, total)), r -> {
			final Thread t = new Thread(r, "SNT-remote-retriever");
			t.setDaemon(true);
			return t;
		});
		final CompletionService<Integer> completion = new ExecutorCompletionService<>(pool);
		try {
			for (int i = 0; i < total; i++) {
				final int idx = i;
				completion.submit(() -> {
					results.set(idx, fetch(ids.get(idx), fetcher));
					return idx;
				});
			}
			for (int done = 0; done < total;) {
				if (cancelled != null && cancelled.getAsBoolean()) break;
				final Future<Integer> finished = completion.poll(200, TimeUnit.MILLISECONDS);
				if (finished == null) continue;
				done++;
				if (progress != null) progress.onProgress(done, total, ids.get(finished.get()));
			}
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (final ExecutionException e) {
			SNTUtils.error("Retrieval failed", e);
		} finally {
			pool.shutdownNow();
		}
		for (int i = 0; i < total; i++) {
			final Tree tree = results.get(i);
			if (tree == null) {
				final Tree empty = new Tree();
				empty.setLabel(ids.get(i));
				list.add(empty);
			} else {
				list.add(tree);
			}
		}
		return list;
	}

	private static Tree fetch(final String id, final Function<String, Tree> fetcher) {
		try {
			return fetcher.apply(id);
		} catch (final Exception e) {
			SNTUtils.log("Could not retrieve " + id + ": " + e.getMessage());
			return null;
		}
	}

}
