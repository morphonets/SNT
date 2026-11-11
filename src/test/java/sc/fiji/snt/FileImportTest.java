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
import sc.fiji.snt.util.BigDataUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the import of reconstructions from files: zip archives ({@link Tree#listFromFile(String)}) and
 * plain local paths ({@link PathAndFillManager#importSWCs(Map, org.scijava.util.ColorRGB)})
 */
public class FileImportTest {

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	private static final String SWC = "1 1 0 0 0 1 -1\n2 3 10 0 0 1 1\n3 3 20 0 0 1 2\n";

	// Zip archives

	private static void add(final ZipOutputStream zos, final String name, final String content)
		throws IOException
	{
		zos.putNextEntry(new ZipEntry(name));
		zos.write(content.getBytes(StandardCharsets.UTF_8));
		zos.closeEntry();
	}

	@Test
	public void testZipOfSwcs() throws IOException {
		final File zip = new File(tmp.newFolder(), "cells.zip");
		try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip
			.toPath())))
		{
			add(zos, "a.swc", SWC);
			add(zos, "nested/b.swc", SWC);
			add(zos, "__MACOSX/._a.swc", "binary debris");
			add(zos, ".hidden.swc", "not a reconstruction");
			add(zos, "readme.txt", "ignored");
		}
		final Collection<Tree> trees = Tree.listFromFile(zip.getAbsolutePath());
		assertNotNull(trees);
		assertEquals(2, trees.size());
		for (final Tree t : trees)
			assertTrue("source is the archive", zip.getAbsolutePath().equals(t
				.getProperties().getProperty(TreeProperties.KEY_FILE)));
	}

	@Test
	public void testZipWithoutReconstructions() throws IOException {
		final File zip = new File(tmp.newFolder(), "none.zip");
		try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip
			.toPath())))
		{
			add(zos, "readme.txt", "ignored");
		}
		assertTrue(Tree.listFromFile(zip.getAbsolutePath()).isEmpty());
	}

	// Local paths that are also valid URIs
	private File swc(final String name) throws IOException {
		final File f = new File(tmp.getRoot(), name);
		Files.writeString(f.toPath(), SWC);
		return f;
	}

	@Test
	public void testIsRemoteUrl() {
		assertTrue(BigDataUtils.isRemoteUrl("https://example.org/a.swc"));
		assertTrue(BigDataUtils.isRemoteUrl("https:/example.org/a.swc")); // as mangled by File
		assertTrue(BigDataUtils.isRemoteUrl("file:///tmp/a.swc"));
		assertTrue(BigDataUtils.isRemoteUrl("s3://bucket/a.zarr"));
		assertFalse(BigDataUtils.isRemoteUrl("/tmp/1234/a.swc"));
		assertFalse(BigDataUtils.isRemoteUrl("relative/a.swc"));
		assertFalse(BigDataUtils.isRemoteUrl("C:/data/a.swc"));
		assertFalse(BigDataUtils.isRemoteUrl("c:\\data\\a.swc"));
		assertFalse(BigDataUtils.isRemoteUrl("/tmp/with space/a.swc"));
		assertFalse(BigDataUtils.isRemoteUrl(null));
	}

	@Test
	public void testImportSWCsFromPlainPaths() throws IOException {
		final PathAndFillManager pafm = new PathAndFillManager();
		pafm.setHeadless(true);
		final Map<String, String> files = new java.util.LinkedHashMap<>();
		files.put("a", swc("a.swc").getAbsolutePath());
		files.put("b", swc("b.swc").getAbsolutePath());
		final java.util.List<Tree> trees = pafm.importSWCs(files, null);
		assertEquals(2, trees.size());
		for (final Tree t : trees)
			assertFalse(t.getLabel(), t.isEmpty());
	}
}
