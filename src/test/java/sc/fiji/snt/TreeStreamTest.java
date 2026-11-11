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

package sc.fiji.snt;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import sc.fiji.snt.util.CloseableIterator;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Tests lazy loading of reconstructions: {@link Tree#streamFromFile(String)} and friends */
public class TreeStreamTest {

	private static final String SWC = "# test\n1 1 0 0 0 1 -1\n2 3 10 0 0 1 1\n3 3 20 5 0 1 2\n";

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private File folderOfSWCs(final int n) throws IOException {
		final File dir = tmp.newFolder();
		for (int i = 0; i < n; i++)
			Files.writeString(new File(dir, "cell" + i + ".swc").toPath(), SWC);
		return dir;
	}

	private File zipOfSWCs(final int n) throws IOException {
		final File zip = new File(tmp.getRoot(), "cells.zip");
		try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip.toPath()))) {
			for (int i = 0; i < n; i++) {
				zos.putNextEntry(new ZipEntry("sub/cell" + i + ".swc"));
				zos.write(SWC.getBytes(StandardCharsets.UTF_8));
				zos.closeEntry();
			}
			// debris that must be ignored
			zos.putNextEntry(new ZipEntry("__MACOSX/sub/._cell0.swc"));
			zos.write(new byte[] { 0, 1, 2 });
			zos.closeEntry();
			zos.putNextEntry(new ZipEntry("readme.txt"));
			zos.write("hi".getBytes(StandardCharsets.UTF_8));
			zos.closeEntry();
		}
		return zip;
	}

	private static List<Tree> drain(final CloseableIterator<Tree> it) {
		final List<Tree> list = new ArrayList<>();
		it.forEach(list::add);
		return list;
	}

	@Test
	public void testZipStreamMatchesList() throws IOException {
		final File zip = zipOfSWCs(3);
		final List<Tree> streamed = drain(Tree.streamFromFile(zip.getAbsolutePath()));
		assertEquals(3, streamed.size());
		assertEquals(3, Tree.listFromFile(zip.getAbsolutePath()).size());
		for (final Tree t : streamed) {
			assertFalse(t.isEmpty());
			assertEquals(zip.getAbsolutePath(), t.getProperties().get(Tree.KEY_FILE));
		}
	}

	@Test
	public void testDirStreamIsSortedAndMatchesList() throws IOException {
		final File dir = folderOfSWCs(12);
		final List<Tree> streamed = drain(Tree.streamFromFile(dir.getAbsolutePath()));
		assertEquals(12, streamed.size());
		final List<String> labels = streamed.stream().map(Tree::getLabel).toList();
		final List<String> sorted = new ArrayList<>(labels);
		sorted.sort(String.CASE_INSENSITIVE_ORDER);
		assertEquals(sorted, labels);
		assertEquals(12, Tree.listFromDir(dir.getAbsolutePath()).size());
	}

	@Test
	public void testStreamingIsLazy() throws IOException {
		final File dir = folderOfSWCs(5);
		try (CloseableIterator<Tree> it = Tree.streamFromDir(dir.getAbsolutePath(), "", (String[]) null)) {
			assertTrue(it.hasNext());
			assertEquals("cell0", it.next().getLabel());
			// leaving early is fine
		}
	}

	@Test
	public void testBadEntriesAreSkipped() throws IOException {
		final File dir = folderOfSWCs(2);
		Files.writeString(new File(dir, "broken.swc").toPath(), "not an swc\n");
		assertEquals(2, drain(Tree.streamFromFile(dir.getAbsolutePath())).size());
	}

	@Test
	public void testSingleFileAndInvalidInput() throws IOException {
		final File dir = folderOfSWCs(1);
		final File swc = new File(dir, "cell0.swc");
		assertEquals(1, drain(Tree.streamFromFile(swc.getAbsolutePath())).size());
		assertEquals(0, drain(Tree.streamFromFile(null)).size());
		// As in listFromFile(), a missing path is an error, whereas unreadable entries are skipped
		try {
			Tree.streamFromFile(new File(dir, "missing.zip").getAbsolutePath());
			throw new AssertionError("expected IllegalArgumentException");
		} catch (final IllegalArgumentException expected) {
			// ok
		}
	}

	@Test
	public void testCloseableIteratorContract() {
		final AtomicInteger closed = new AtomicInteger();
		final List<List<Integer>> batches = new ArrayList<>(List.of(List.of(1, 2), List.of(), List.of(3)));
		final CloseableIterator<Integer> it = new CloseableIterator<>(
				() -> batches.isEmpty() ? null : batches.remove(0), closed::incrementAndGet);
		final List<Integer> got = new ArrayList<>();
		it.forEach(got::add);
		assertEquals(List.of(1, 2, 3), got);
		assertEquals("closed once exhausted", 1, closed.get());
		it.close();
		assertEquals("close is idempotent", 1, closed.get());
		assertFalse(it.hasNext());
		try {
			it.next();
			throw new AssertionError("expected NoSuchElementException");
		} catch (final NoSuchElementException expected) {
			// ok
		}
	}

	@Test
	public void testStreamClosesIterator() {
		final AtomicInteger closed = new AtomicInteger();
		final CloseableIterator<Integer> it = new CloseableIterator<>(() -> List.of(1, 2, 3), closed::incrementAndGet);
		try (var s = it.stream()) {
			assertEquals(1, s.limit(1).count());
		}
		assertEquals(1, closed.get());
	}
}
