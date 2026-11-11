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

package sc.fiji.snt.gui;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import sc.fiji.snt.gui.FileInspector.Budget;
import sc.fiji.snt.gui.FileInspector.Item;
import sc.fiji.snt.gui.FileInspector.Kind;
import sc.fiji.snt.gui.FileInspector.Result;

import javax.swing.SwingUtilities;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for {@link FileInspector}: classification, and resilience to large
 * folders and stalled file systems.
 */
public class FileInspectorTest {

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private static final Budget FAST = new Budget(Duration.ofMillis(300), Duration
			.ofSeconds(2), 500, 200, 64L * 1024 * 1024);

	private File touch(final File dir, final String name) throws IOException {
		final File f = new File(dir, name);
		assertTrue(f.createNewFile());
		return f;
	}

	private static Kind kindOf(final Result r, final int i) {
		return r.items().get(i).kind();
	}

	@Test
	public void testFileClassification() throws IOException {
		final File dir = tmp.newFolder();
		final File zipPlain = new File(dir, "data.zip");
		try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(
			zipPlain.toPath())))
		{
			zos.putNextEntry(new ZipEntry("readme.txt"));
			zos.closeEntry();
		}
		final File zipRois = new File(dir, "RoiSet.zip");
		try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(
			zipRois.toPath())))
		{
			zos.putNextEntry(new ZipEntry("0001-0002.roi"));
			zos.closeEntry();
		}
		final File[] files = { touch(dir, "a.swc"), touch(dir, "b.TRACES"), touch(
			dir, "c.tif"), touch(dir, "d.csv"), touch(dir, "e.roi"), zipPlain,
			zipRois, touch(dir, "f.json"), touch(dir, "g.xml"), touch(dir, "h.ndf"),
			touch(dir, "noext"), new File(dir, "does-not-exist.swc") };
		final Kind[] expected = { Kind.SWC, Kind.TRACES, Kind.IMAGE, Kind.CSV,
			Kind.ROI, Kind.ZIP, Kind.ROI_ZIP, Kind.JSON, Kind.XML, Kind.NDF,
			Kind.UNKNOWN, Kind.MISSING };
		final Result r = new FileInspector(FAST).inspectNow(files);
		assertFalse(r.degraded());
		for (int i = 0; i < expected.length; i++)
			assertEquals(files[i].getName(), expected[i], kindOf(r, i));
	}

	@Test
	public void testReconstructionZip() throws IOException {
		final File dir = tmp.newFolder();
		final File zip = new File(dir, "cells.zip");
		try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip
			.toPath())))
		{
			zos.putNextEntry(new ZipEntry("__MACOSX/._a.swc"));
			zos.closeEntry();
			zos.putNextEntry(new ZipEntry("sub/a.swc"));
			zos.closeEntry();
		}
		final File debrisOnly = new File(dir, "debris.zip");
		try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(
			debrisOnly.toPath())))
		{
			zos.putNextEntry(new ZipEntry("__MACOSX/._a.swc"));
			zos.closeEntry();
			zos.putNextEntry(new ZipEntry(".hidden.swc"));
			zos.closeEntry();
		}
		final Result r = new FileInspector(FAST).inspectNow(zip, debrisOnly);
		assertEquals(Kind.RECONSTRUCTION_ZIP, kindOf(r, 0));
		assertEquals(Kind.ZIP, kindOf(r, 1));
	}

	@Test
	public void testSentinelFoldersAreNotListed() throws IOException {
		final File root = tmp.newFolder();
		final File session = new File(root, "SNT_Session_2026-11-10_10-00-00");
		final File zarr = new File(root, "volume.zarr");
		final File n5 = new File(root, "nested");
		final File zgroup = new File(root, "zgroup-folder");
		for (final File d : new File[] { session, zarr, n5, zgroup })
			assertTrue(d.mkdirs());
		touch(session, "session.properties");
		touch(n5, "attributes.json");
		touch(zgroup, ".zgroup");
		// Many entries that must never be read
		for (final File d : new File[] { zarr, n5, zgroup }) {
			for (int i = 0; i < 3000; i++)
				touch(d, "chunk" + i);
		}
		final long t0 = System.nanoTime();
		final Result r = new FileInspector(FAST).inspectNow(session, zarr, n5,
			zgroup);
		assertEquals(Kind.SESSION_DIR, kindOf(r, 0));
		assertEquals(Kind.ZARR_DIR, kindOf(r, 1));
		assertEquals(Kind.N5_DIR, kindOf(r, 2));
		assertEquals(Kind.ZARR_DIR, kindOf(r, 3));
		for (final Item i : r.items())
			assertEquals("no listing for " + i.file(), 0, i.entries());
		assertTrue("fast", System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(1));
	}

	@Test
	public void testSessionsFolder() throws IOException {
		final File root = tmp.newFolder();
		assertTrue(new File(root, "SNT_Session_2026-11-09_10-00-00").mkdir());
		assertTrue(new File(root, "SNT_Session_2026-11-10_10-00-00").mkdir());
		touch(root, "a.swc");
		final Item i = new FileInspector(FAST).inspectNow(root).items().getFirst();
		assertEquals(Kind.SESSIONS_DIR, i.kind());
		assertEquals(Integer.valueOf(2), i.contents().get(Kind.SESSION_DIR));
		assertEquals(Integer.valueOf(1), i.contents().get(Kind.SWC));
	}

	@Test
	public void testGenericFolderListingIsCapped() throws IOException {
		final File root = tmp.newFolder();
		for (int i = 0; i < 1000; i++)
			touch(root, "cell" + i + ".swc");
		final Item i = new FileInspector(FAST).inspectNow(root).items().getFirst();
		assertEquals(Kind.DIRECTORY, i.kind());
		assertTrue(i.truncated());
		assertEquals(200, i.entries());
		assertEquals(Integer.valueOf(200), i.contents().get(Kind.SWC));
	}

	@Test
	public void testMixedFolderSummary() throws IOException {
		final File root = tmp.newFolder();
		touch(root, "a.swc");
		touch(root, "b.swc");
		touch(root, "c.tif");
		touch(root, ".DS_Store");
		final Item i = new FileInspector(FAST).inspectNow(root).items().getFirst();
		assertEquals(Kind.DIRECTORY, i.kind());
		assertFalse(i.truncated());
		assertEquals(3, i.entries() - 1); // hidden file counted but not classified
		assertTrue(i.describe(), i.describe().contains("2 SWC files"));
		assertTrue(i.describe(), i.describe().contains("1 image"));
	}

	@Test
	public void testSymlinkLoopIsHarmless() throws IOException {
		final File root = tmp.newFolder();
		final Path loop = new File(root, "loop").toPath();
		try {
			Files.createSymbolicLink(loop, root.toPath());
		} catch (final UnsupportedOperationException | IOException ex) {
			return; // file system does not support symlinks
		}
		final Result r = new FileInspector(FAST).inspectNow(root);
		assertEquals(Kind.DIRECTORY, kindOf(r, 0));
	}

	@Test
	public void testPathLimit() {
		final File[] many = new File[600];
		for (int i = 0; i < many.length; i++)
			many[i] = new File("/nonexistent-root/x" + i + ".swc");
		final Result r = new FileInspector(FAST).inspectNow(many);
		assertEquals(500, r.items().size());
		assertEquals(100, r.skipped());
		assertTrue(r.describe(), r.describe().endsWith("and 100 more"));
	}

	@Test
	public void testNullsAreIgnored() {
		final Result r = new FileInspector(FAST).inspectNow((File[]) null);
		assertTrue(r.items().isEmpty());
		assertNull(r.uniformKind());
		assertTrue(new FileInspector(FAST).inspectNow(new File[] { null })
			.items().isEmpty());
	}

	/** Simulates a blocking, uninterruptible file system call */
	private static Item hang(final File f) {
		final long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while (System.nanoTime() < end) {
			try {
				Thread.sleep(50);
			} catch (final InterruptedException ignored) {
				// like blocking native I/O: ignore interrupts
			}
		}
		return new Item(f, Kind.UNKNOWN, 0, false, java.util.Map.of());
	}

	@Test
	public void testManyFilesOnOneMountAreNotThrottled() throws IOException {
		// Only abandoned (timed out) probes count against a mount: healthy ones never do
		final File dir = tmp.newFolder();
		final File[] files = new File[40];
		for (int i = 0; i < files.length; i++)
			files[i] = touch(dir, "cell" + i + ".swc");
		final Result r = new FileInspector(FAST).inspectNow(files);
		assertFalse(r.degraded());
		assertEquals(files.length, r.count(Kind.SWC));
	}

	@Test
	public void testStalledMountDoesNotHangAndTripsBreaker() throws IOException {
		final AtomicInteger stalledCalls = new AtomicInteger();
		final File stalled1 = new File("/stalled-mount/share/a.swc");
		final File stalled2 = new File("/stalled-mount/share/deep/b.swc");
		final File local = touch(tmp.newFolder(), "ok.swc");
		final FileInspector di = new FileInspector(FAST, f -> {
			if (f.getPath().startsWith("/stalled-mount")) {
				stalledCalls.incrementAndGet();
				return hang(f);
			}
			return new Item(f, FileInspector.kindOfName(f.getName()), 0, false,
				java.util.Map.of());
		});

		long t0 = System.nanoTime();
		final Result r1 = di.inspectNow(stalled1, local);
		final long ms1 = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
		assertTrue("returned in " + ms1 + " ms", ms1 < 1500);
		assertTrue(r1.degraded());
		assertEquals(Kind.UNREACHABLE, kindOf(r1, 0));
		assertEquals("healthy items are unaffected", Kind.SWC, kindOf(r1, 1));
		assertEquals(1, stalledCalls.get());

		// Same mount: skipped immediately, probe not invoked again
		t0 = System.nanoTime();
		final Result r2 = di.inspectNow(stalled2, local);
		final long ms2 = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
		assertTrue("breaker: returned in " + ms2 + " ms", ms2 < 150);
		assertEquals(Kind.UNREACHABLE, kindOf(r2, 0));
		assertEquals(Kind.SWC, kindOf(r2, 1));
		assertEquals(1, stalledCalls.get());
	}

	@Test
	public void testTotalBudgetIsRespected() {
		final Budget b = new Budget(Duration.ofSeconds(10), Duration.ofMillis(500),
			500, 200, 1024);
		final File[] files = new File[8];
		for (int i = 0; i < files.length; i++)
			files[i] = new File("/stalled-" + i + "/share/x.swc");
		final FileInspector di = new FileInspector(b, FileInspectorTest::hang);
		final long t0 = System.nanoTime();
		final Result r = di.inspectNow(files);
		final long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
		assertTrue("returned in " + ms + " ms", ms < 1500);
		assertTrue(r.degraded());
		assertEquals(files.length, r.items().size());
		assertEquals(files.length, r.count(Kind.UNREACHABLE));
	}

	@Test
	public void testEdtGuard() throws Exception {
		final AtomicReference<Throwable> thrown = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() -> {
			try {
				new FileInspector(FAST).inspectNow(new File("x"));
			} catch (final Throwable t) {
				thrown.set(t);
			}
		});
		assertTrue(String.valueOf(thrown.get()),
			thrown.get() instanceof IllegalStateException);
	}

	@Test
	public void testAsyncKeepsEdtResponsive() throws Exception {
		final FileInspector di = new FileInspector(new Budget(Duration.ofSeconds(1),
			Duration.ofSeconds(2), 500, 200, 1024), FileInspectorTest::hang);
		final CountDownLatch done = new CountDownLatch(1);
		final AtomicReference<Result> result = new AtomicReference<>();
		final AtomicReference<Boolean> onEdt = new AtomicReference<>();
		di.inspect(new File[] { new File("/stalled-async/share/x.swc") }, r -> {
			onEdt.set(SwingUtilities.isEventDispatchThread());
			result.set(r);
			done.countDown();
		});
		// While the probe is stuck, the EDT must keep serving events promptly
		long worst = 0;
		for (int i = 0; i < 10; i++) {
			final CountDownLatch pong = new CountDownLatch(1);
			final long t0 = System.nanoTime();
			SwingUtilities.invokeLater(pong::countDown);
			assertTrue(pong.await(2, TimeUnit.SECONDS));
			worst = Math.max(worst, System.nanoTime() - t0);
			Thread.sleep(50);
		}
		assertTrue("EDT latency " + TimeUnit.NANOSECONDS.toMillis(worst) + " ms",
			TimeUnit.NANOSECONDS.toMillis(worst) < 250);
		if (!done.await(5, TimeUnit.SECONDS)) fail("callback was not called");
		assertEquals(Boolean.TRUE, onEdt.get());
		assertTrue(result.get().degraded());
		assertEquals(Kind.UNREACHABLE, result.get().items().getFirst().kind());
	}

	@Test
	public void testCallbackExceptionDoesNotEscape() throws Exception {
		final CountDownLatch called = new CountDownLatch(1);
		new FileInspector(FAST).inspect(new File[0], r -> {
			called.countDown();
			throw new IllegalStateException("boom");
		});
		assertTrue(called.await(5, TimeUnit.SECONDS));
		// EDT must still be alive
		final AtomicLong pong = new AtomicLong();
		SwingUtilities.invokeAndWait(() -> pong.set(1));
		assertEquals(1, pong.get());
	}
}
