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

package sc.fiji.snt.util;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * A lazy sequence of items that holds a resource (e.g., an open archive) until it is exhausted or closed. Items are
 * produced on demand, in batches (e.g., all the items parsed from a file), so that memory use is independent of the
 * number of items. Meant to be used in a loop, or in a try-with-resources statement if the loop may be left early:
 *
 * <pre>
 * try (CloseableIterator&lt;Tree&gt; trees = Tree.streamFromFile(path)) {
 *     for (Tree tree : trees) { ... }
 * }
 * </pre>
 *
 * The resource is released automatically once the last item has been retrieved. Closing is idempotent. A closed
 * iterator has no more items. Instances are not thread-safe.
 *
 * @param <T> the type of items
 * @author Tiago Ferreira
 */
public final class CloseableIterator<T> implements Iterator<T>, Iterable<T>, AutoCloseable {

	private final Supplier<? extends Iterable<? extends T>> batches;
	private final Runnable closer;
	private Iterator<? extends T> current;
	private boolean closed;

	/**
	 * Creates an iterator.
	 *
	 * @param batches supplies the next batch of items, or null when there are no more. Empty batches are skipped.
	 *                It is never called after it returned null, or after the iterator was closed
	 * @param closer  releases the resources. Called once. Null allowed
	 */
	public CloseableIterator(final Supplier<? extends Iterable<? extends T>> batches, final Runnable closer) {
		this.batches = java.util.Objects.requireNonNull(batches);
		this.closer = (closer == null) ? () -> {
		} : closer;
	}

	/** @return an iterator without items */
	public static <T> CloseableIterator<T> empty() {
		return new CloseableIterator<>(() -> null, null);
	}

	@Override
	public boolean hasNext() {
		while (!closed && (current == null || !current.hasNext())) {
			final Iterable<? extends T> batch;
			try {
				batch = batches.get();
			} catch (final RuntimeException ex) {
				close(); // do not leave the resource open if the source fails
				throw ex;
			}
			if (batch == null) {
				close();
				return false;
			}
			current = batch.iterator();
		}
		return !closed && current != null && current.hasNext();
	}

	@Override
	public T next() {
		if (!hasNext()) throw new NoSuchElementException();
		return current.next();
	}

	/** @return this instance, so that it can be used in a for-each loop. It can only be iterated once */
	@Override
	public Iterator<T> iterator() {
		return this;
	}

	/**
	 * @return the remaining items as a sequential stream. Closing the stream closes this iterator
	 */
	public Stream<T> stream() {
		return StreamSupport.stream(Spliterators.spliteratorUnknownSize(this, Spliterator.ORDERED), false)
				.onClose(this::close);
	}

	/** Releases the resources. Subsequent calls have no effect */
	@Override
	public void close() {
		if (closed) return;
		closed = true;
		current = null;
		closer.run();
	}
}
