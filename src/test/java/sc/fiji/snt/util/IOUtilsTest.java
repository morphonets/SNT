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

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import sc.fiji.snt.util.IOUtils.FileType;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for {@link IOUtils}: name-based classification, folder listings, and zip handling
 */
public class IOUtilsTest {

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	// File types
	@Test
	public void testGetType() {
		final Object[][] table = { { "cell.swc", FileType.SWC }, { "cell.ESWC",
			FileType.SWC }, { "a.b.traces", FileType.TRACES }, { "x.JSON",
				FileType.JSON }, { "x.ndf", FileType.NDF }, { "n.xml", FileType.XML },
			{ "img.tif", FileType.IMAGE }, { "img.TIFF", FileType.IMAGE }, { "r.roi",
				FileType.ROI }, { "t.csv", FileType.CSV }, { "t.tsv", FileType.UNKNOWN }, { "t.ims", FileType.IMS }, {
					"RoiSet.zip", FileType.ZIP }, { "photo.png", FileType.UNKNOWN }, {
						"noext", FileType.UNKNOWN }, { "trailingdot.", FileType.UNKNOWN }, {
							"old.traces.gz", FileType.UNKNOWN }, { "", FileType.UNKNOWN }, {
								"/some.dir/noext", FileType.UNKNOWN }, { "/some.dir/a.swc",
									FileType.SWC }, { "C:\\data.v2\\a.traces", FileType.TRACES } };
		for (final Object[] row : table)
			assertEquals((String) row[0], row[1], IOUtils.getType((String) row[0]));
		assertEquals(FileType.UNKNOWN, IOUtils.getType((String) null));
		assertEquals(FileType.UNKNOWN, IOUtils.getType((File) null));
		assertEquals(FileType.SWC, IOUtils.getType(new File("x/y.swc")));
	}

	@Test
	public void testReconstructionExtensions() {
		assertEquals(List.of("traces", "swc", "eswc", "json", "ndf"), Arrays.asList(
			IOUtils.getReconstructionExtensions()));
		assertEquals(List.of("traces", "swc", "eswc", "json", "ndf", "xml"), Arrays
			.asList(IOUtils.getReconstructionExtensions(true)));
	}

	@Test
	public void testPredicates() {
		assertTrue(IOUtils.isSWCFile(new File("a.swc")));
		assertFalse(IOUtils.isSWCFile(new File("a.traces")));
		assertFalse(IOUtils.isSWCFile(null));
		assertTrue(IOUtils.isReconstructionFile(new File("a.ndf")));
		assertFalse("xml needs sniffing", IOUtils.isReconstructionFile(new File(
			"a.xml")));
		assertFalse(IOUtils.isReconstructionFile(new File("missing.xml"), true));
		assertTrue(IOUtils.isImageFile(new File("a.tif")));
		assertFalse(IOUtils.isImageFile(new File("a.png")));
		assertEquals(List.of("tif", "tiff"), Arrays.asList(IOUtils
			.getImageExtensions()));
	}

	// Folder listings
	private static File write(final File dir, final String name, final String content)
			throws IOException {
		final File f = new File(dir, name);
		Files.writeString(f.toPath(), content);
		return f;
	}

	@Test
	public void testTopLevelOnlyAndXmlSniffing() throws IOException {
		final File root = tmp.newFolder();
		final File sub = new File(root, "sub");
		assertTrue(sub.mkdir());
		write(root, "b.swc", "");
		write(root, "A.traces", "");
		write(root, "neurolucida.xml", "<?xml version=\"1.0\"?><mbf version=\"4\"></mbf>");
		write(root, "other.xml", "<?xml version=\"1.0\"?><OME></OME>");
		write(root, ".hidden.swc", "");
		write(root, "readme.txt", "");
		write(sub, "nested.swc", "");
		assertTrue(new File(root, "folder.swc").mkdir()); // a folder is not a file
		final List<File> found = IOUtils.listTopLevelReconstructionFiles(root, 100);
		assertEquals(List.of("A.traces", "b.swc", "neurolucida.xml"), found.stream().map(
			File::getName).toList());
	}

	@Test
	public void testMaxFiles() throws IOException {
		final File root = tmp.newFolder();
		for (int i = 0; i < 50; i++)
			write(root, "cell" + i + ".swc", "");
		assertEquals(10, IOUtils.listTopLevelReconstructionFiles(root, 10).size());
		assertEquals(50, IOUtils.listTopLevelReconstructionFiles(root, 500).size());
	}

	@Test
	public void testRecursiveListingIsBounded() throws IOException {
		final File root = tmp.newFolder();
		File dir = root;
		// 30 levels deep: only the first 16 are searched
		for (int i = 0; i < 30; i++) {
			dir = new File(dir, "d" + i);
			assertTrue(dir.mkdir());
			write(dir, "cell" + i + ".swc", "");
		}
		assertEquals(16, IOUtils.listReconstructionFiles(root).size());
		// Cap on number of files
		final File flat = tmp.newFolder();
		for (int i = 0; i < 50; i++)
			write(flat, "c" + i + ".swc", "");
		assertEquals(5, IOUtils.listReconstructionFiles(5, flat).size());
		assertEquals(50, IOUtils.listReconstructionFiles(flat).size());
	}

	@Test
	public void testSymlinkLoopsAreNotFollowed() throws IOException {
		final File root = tmp.newFolder();
		write(root, "a.swc", "");
		final File sub = new File(root, "sub");
		assertTrue(sub.mkdir());
		write(sub, "b.swc", "");
		try {
			Files.createSymbolicLink(new File(sub, "loop").toPath(), root.toPath());
		} catch (final UnsupportedOperationException | IOException ex) {
			return; // file system does not support symlinks
		}
		assertEquals(2, IOUtils.listReconstructionFiles(root).size());
	}

	@Test
	public void testDegenerateInput() throws IOException {
		assertTrue(IOUtils.listTopLevelReconstructionFiles(null, 10).isEmpty());
		assertTrue(IOUtils.listTopLevelReconstructionFiles(tmp.newFolder(), 0).isEmpty());
		assertTrue(IOUtils.listTopLevelReconstructionFiles(new File(tmp.getRoot(),
			"missing"), 10).isEmpty());
		assertTrue(IOUtils.listTopLevelReconstructionFiles(write(tmp.getRoot(), "f.swc", ""),
			10).isEmpty());
	}

	// Zip archives
	private File zip(final String... entriesAndContents) throws IOException {
		final File zip = new File(tmp.newFolder(), "test.zip");
		try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip.toPath()))) {
			for (int i = 0; i < entriesAndContents.length; i += 2) {
				zos.putNextEntry(new ZipEntry(entriesAndContents[i]));
				zos.write(entriesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
				zos.closeEntry();
			}
		}
		return zip;
	}

	@Test
	public void testIsReconstructionEntry() {
		assertTrue(IOUtils.isReconstructionEntry("a.swc"));
		assertTrue(IOUtils.isReconstructionEntry("sub/dir/a.TRACES"));
		assertTrue(IOUtils.isReconstructionEntry("sub\\a.json"));
		assertFalse(IOUtils.isReconstructionEntry("__MACOSX/._a.swc"));
		assertFalse(IOUtils.isReconstructionEntry("sub/__MACOSX/a.swc"));
		assertFalse(IOUtils.isReconstructionEntry(".hidden.swc"));
		assertFalse(IOUtils.isReconstructionEntry("sub/.hidden.swc"));
		assertFalse(IOUtils.isReconstructionEntry("readme.txt"));
		assertFalse(IOUtils.isReconstructionEntry("image.tif"));
	}

	@Test
	public void testExtractReconstructionsOnly() throws IOException {
		final File zip = zip("a.swc", "x", "nested/b.swc", "x", "readme.txt", "x", "__MACOSX/._a.swc", "x");
		final File dir = IOUtils.extractZip(zip, true);
		try {
			assertTrue(new File(dir, "a.swc").isFile());
			assertTrue(new File(dir, "nested/b.swc").isFile());
			assertFalse(new File(dir, "readme.txt").exists());
			assertFalse(new File(dir, "__MACOSX").exists());
			assertEquals(2, IOUtils.listReconstructionFiles(dir).size());
		} finally {
			IOUtils.deleteRecursively(dir);
		}
		assertFalse(dir.exists());
		assertTrue("archive is left untouched", zip.exists());
	}

	@Test
	public void testExtractAll() throws IOException {
		final File dir = IOUtils.extractZip(zip("a.swc", "x", "readme.txt", "x"), false);
		try {
			assertTrue(new File(dir, "readme.txt").isFile());
		} finally {
			IOUtils.deleteRecursively(dir);
		}
	}

	@Test
	public void testZipSlipIsRejected() throws IOException {
		final File zip = zip("../evil.swc", "x");
		try {
			IOUtils.extractZip(zip, false);
			fail("zip-slip not detected");
		} catch (final IOException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("zip-slip"));
		}
	}

	@Test
	public void testByteBudgetIsEnforcedWhileCopying() throws IOException {
		final String big = "0123456789".repeat(100); // 1000 bytes
		final File zip = zip("a.swc", "x", "b.swc", big);
		try {
			IOUtils.extractZip(zip, true, 100);
			fail("budget not enforced");
		} catch (final IOException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("too large"));
		}
		// Generous budget: fine
		IOUtils.deleteRecursively(IOUtils.extractZip(zip, true, 10_000));
	}
}
