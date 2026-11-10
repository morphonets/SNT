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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Static utilities for file I/O, namely the detection of reconstruction file formats.
 *
 * @author Tiago Ferreira
 */
public final class IOUtils {

	/** Number of bytes inspected when guessing the format of a file */
	private static final int SNIFF_BYTES = 4096;

	/** Default maximum number of files returned by {@link #listReconstructionFiles(File...)} */
	private static final int MAX_LISTED_FILES = 10000;

	/** Maximum depth of the folder search of {@link #listReconstructionFiles(int, File...)} */
	private static final int MAX_SEARCH_DEPTH = 16;

	/** Maximum number of folder entries examined by {@link #listTopLevelReconstructionFiles} */
	private static final int MAX_SCANNED_ENTRIES = 10000;
	private static final Map<String, FileType> EXTENSION_TO_TYPE = new HashMap<>();
	static {
		for (final FileType type : FileType.values()) {
			for (final String ext : type.extensions)
				EXTENSION_TO_TYPE.put(ext, type);
		}
	}

	private IOUtils() {
		// static methods only
	}

	/**
	 * The kinds of files recognized from their names (extensions) alone. Content-based detection of
	 * reconstruction formats is provided by {@link #detectFormat(File)}
	 */
	public enum FileType {
		// NB: the declaration order of reconstruction types is also the preference order when
		// pairing files, see getReconstructionExtensions(): native format first
		/** SNT TRACES (compressed or not) */
		TRACES("traces"),
		/** SWC and extended SWC */
		SWC("swc", "eswc"),
		/** MouseLight JSON */
		JSON("json"),
		/** NeuronJ NDF */
		NDF("ndf"),
		/**
		 * XML. May be a Neurolucida reconstruction, which cannot be told apart by name, see
		 * {@link IOUtils#isReconstructionFile(File, boolean)}
		 */
		XML("xml"),
		/**
		 * Core images, i.e., those opened by {@link ImpUtils}. Out-of-core images (see {@link ImgUtils}
		 * and {@link BigDataUtils}) are not covered. Currently limited to TIFF
		 */
		IMAGE("tif", "tiff"),
		/** ImageJ ROI */
		ROI("roi"),
		/** Comma-separated text */
		CSV("csv"),
		/** Imaris file */
		IMS("ims"),
		/** Zip archive. May hold ROIs or reconstructions, which requires inspecting its contents */
		ZIP("zip"),
		/** None of the above */
		UNKNOWN();

		private final String[] extensions;

		FileType(final String... extensions) {
			this.extensions = extensions;
		}

		/** @return the (lower case) extensions of this type, without the leading period */
		public String[] extensions() {
			return extensions.clone();
		}

		/** @return true if files of this type are reconstructions by name alone: SWC, TRACES, JSON, NDF */
		public boolean isReconstruction() {
			return this == SWC || this == TRACES || this == JSON || this == NDF;
		}

		/** @return true if this is a reconstruction type, or XML (which may be Neurolucida) */
		public boolean isReconstructionCandidate() {
			return isReconstruction() || this == XML;
		}
	}

	/** The reconstruction formats that can be detected by {@link IOUtils} */
	public enum ReconstructionFormat {
		/** Gzip-compressed XML (typically .traces) */
		COMPRESSED_XML,
		/** Uncompressed XML (typically .traces) */
		UNCOMPRESSED_XML,
		/** SWC (also the fallback for unrecognized content) */
		SWC,
		/** MouseLight JSON */
		ML_JSON,
		/** NeuronJ NDF */
		NDF,
		/** Neurolucida XML */
		NEUROLUCIDA
	}

	/**
	 * Guesses the format of a reconstruction file from its first bytes (not its extension): gzip magic
	 * number, XML (Neurolucida if the header holds an {@code <mbf>} root element), JSON
	 * (<code>'&#123;'</code>), NDF ({@code '/'}), or SWC otherwise.
	 *
	 * @param is                    the stream to be inspected. If it supports marking, it is reset
	 *                              after inspection (unless {@code closeStreamAfterGuess} is true)
	 * @param closeStreamAfterGuess whether {@code is} should be closed after inspection
	 * @return the detected format
	 * @throws IOException if an IO error occurs
	 */
	public static ReconstructionFormat detectFormat(final InputStream is, final boolean closeStreamAfterGuess)
			throws IOException {
		if (is.markSupported()) is.mark(SNIFF_BYTES);
		final byte[] buf = new byte[SNIFF_BYTES];
		final int bytesRead = is.readNBytes(buf, 0, buf.length);
		if (closeStreamAfterGuess) is.close();
		else if (is.markSupported()) is.reset();
		return detectFormat(buf, bytesRead);
	}

	/**
	 * Guesses the format of a reconstruction file from its first bytes. See
	 * {@link #detectFormat(InputStream, boolean)}
	 *
	 * @param file the file to be inspected
	 * @return the detected format
	 * @throws IOException if the file could not be read
	 */
	public static ReconstructionFormat detectFormat(final File file) throws IOException {
		return detectFormat(Files.newInputStream(file.toPath()), true);
	}

	private static ReconstructionFormat detectFormat(final byte[] buf, final int bytesRead) {
		if (bytesRead < 2) return ReconstructionFormat.SWC;
		if (buf[0] == (byte) 0x1f && buf[1] == (byte) 0x8b) { // standard gzip magic number
			return ReconstructionFormat.COMPRESSED_XML;
		} else if (bytesRead >= 6 && buf[0] == '<' && buf[1] == '?' && buf[2] == 'x' && buf[3] == 'm'
				&& buf[4] == 'l' && buf[5] == ' ') {
			// XML: distinguish SNT traces from Neurolucida by checking for <mbf> root
			final String header = new String(buf, 0, bytesRead, StandardCharsets.ISO_8859_1);
			return (header.contains("<mbf")) ? ReconstructionFormat.NEUROLUCIDA
					: ReconstructionFormat.UNCOMPRESSED_XML;
		} else if (buf[0] == '{') {
			return ReconstructionFormat.ML_JSON;
		} else if (buf[0] == '/') {
			return ReconstructionFormat.NDF;
		}
		return ReconstructionFormat.SWC;
	}

	/**
	 * Returns the extensions (lower case, without the leading period) of the supported reconstruction
	 * formats that can be identified by name alone, i.e., not including Neurolucida .xml files. The
	 * order is that of preference when looking for a file paired to another (e.g., an image): the
	 * native TRACES format first
	 *
	 * @return a new array of extensions, e.g., "traces", "swc", etc.
	 * @see #getReconstructionExtensions(boolean)
	 */
	public static String[] getReconstructionExtensions() {
		return getReconstructionExtensions(false);
	}

	/**
	 * Returns the extensions (lower case, without the leading period) of the supported reconstruction
	 * formats, optionally including "xml". Neurolucida files cannot be told apart from other XML
	 * files by name, so "xml" is only suitable for, e.g., file choosers, with files being validated
	 * afterwards (see {@link #isReconstructionFile(File, boolean)})
	 *
	 * @param includeXml whether "xml" should be included
	 * @return a new array of extensions, e.g., "traces", "swc", "eswc", etc.
	 */
	public static String[] getReconstructionExtensions(final boolean includeXml) {
		return Arrays.stream(FileType.values())
				.filter(includeXml ? FileType::isReconstructionCandidate : FileType::isReconstruction)
				.flatMap(type -> Arrays.stream(type.extensions)).toArray(String[]::new);
	}

	/**
	 * Returns the extensions (lower case, without the leading period) of the core image formats. See
	 * {@link FileType#IMAGE}
	 *
	 * @return a new array of extensions
	 */
	public static String[] getImageExtensions() {
		return FileType.IMAGE.extensions();
	}

	/**
	 * Classifies a file from its name alone (case-insensitive). The file is not accessed
	 *
	 * @param file the file to be classified. Null allowed
	 * @return the type of the file, {@link FileType#UNKNOWN} if it is not recognized
	 */
	public static FileType getType(final File file) {
		return (file == null) ? FileType.UNKNOWN : getType(file.getName());
	}

	/**
	 * Classifies a file from its name alone (case-insensitive). The file is not accessed
	 *
	 * @param name the file name or path (leading folders are ignored). Null allowed
	 * @return the type of the file, {@link FileType#UNKNOWN} if it is not recognized
	 */
	public static FileType getType(final String name) {
		if (name == null) return FileType.UNKNOWN;
		final int dot = name.lastIndexOf('.');
		final int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
		if (dot < 0 || dot < separator || dot == name.length() - 1) return FileType.UNKNOWN;
		return EXTENSION_TO_TYPE.getOrDefault(name.substring(dot + 1).toLowerCase(Locale.ROOT),
				FileType.UNKNOWN);
	}

	/**
	 * Checks if a file is a core image, based on its extension only. See {@link FileType#IMAGE}
	 *
	 * @param file the file to be tested
	 * @return true if the extension is that of a core image format
	 */
	public static boolean isImageFile(final File file) {
		return getType(file) == FileType.IMAGE;
	}

	/**
	 * Checks if a file has an SWC extension, i.e., .swc or .eswc (extended SWC, which holds extra
	 * columns that are ignored on import). Case-insensitive.
	 *
	 * @param file the file to be tested
	 * @return true if the file name ends with ".swc" or ".eswc"
	 */
	public static boolean isSWCFile(final File file) {
		return getType(file) == FileType.SWC;
	}

	/**
	 * Checks if a file is a reconstruction file based on its extension only (SWC, TRACES, JSON or NDF).
	 * Neurolucida XML files are not recognized by this method, see
	 * {@link #isReconstructionFile(File, boolean)}
	 *
	 * @param file the file to be tested
	 * @return true if the extension is that of a supported reconstruction format
	 */
	public static boolean isReconstructionFile(final File file) {
		return isReconstructionFile(file, false);
	}

	/**
	 * Checks if a file is a reconstruction file. Apart from the extension-only check, XML files are
	 * recognized as Neurolucida reconstructions if {@code sniffXml} is true and their header is that
	 * of a Neurolucida file. Only the first few KB of XML files are read.
	 *
	 * @param file     the file to be tested
	 * @param sniffXml whether .xml files should be inspected
	 * @return true if the file is a supported reconstruction file
	 */
	public static boolean isReconstructionFile(final File file, final boolean sniffXml) {
		final FileType type = getType(file);
		if (type.isReconstruction()) return true;
		if (!sniffXml || type != FileType.XML) return false;
		try {
			return detectFormat(file) == ReconstructionFormat.NEUROLUCIDA;
		} catch (final IOException | RuntimeException ex) {
			return false;
		}
	}

	/**
	 * Flattens files and directories (searched recursively) into a list of reconstruction files, as
	 * defined by {@link #isReconstructionFile(File, boolean)} with {@code sniffXml} set to true. Null
	 * entries, unreadable directories and non-reconstruction files are skipped. Typically used to
	 * process dropped items. The search is bounded: at most 10,000 files are returned. See
	 * {@link #listReconstructionFiles(int, File...)}. May block on slow file systems: do not call it
	 * from the Event Dispatch Thread
	 *
	 * @param files the files and/or directories to be flattened. Null allowed
	 * @return the reconstruction files found (empty if none)
	 */
	public static List<File> listReconstructionFiles(final File... files) {
		return listReconstructionFiles(MAX_LISTED_FILES, files);
	}

	/**
	 * Flattens files and directories (searched recursively) into a list of reconstruction files, as
	 * defined by {@link #isReconstructionFile(File, boolean)} with {@code sniffXml} set to true. The
	 * search stops once {@code maxFiles} files have been found, does not descend more than 16 levels
	 * below the specified items, and does not follow symbolic links to folders below the specified items (which guards against
	 * cycles). Null entries, unreadable directories and non-reconstruction files are skipped. May
	 * block on slow file systems: do not call it from the Event Dispatch Thread
	 *
	 * @param maxFiles the maximum number of files to return
	 * @param files    the files and/or directories to be flattened. Null allowed
	 * @return the reconstruction files found (empty if none)
	 */
	public static List<File> listReconstructionFiles(final int maxFiles, final File... files) {
		return listFiles(maxFiles, f -> isReconstructionFile(f, true), files);
	}

	/**
	 * Flattens files and directories (searched recursively) into a list of sources that
	 * {@link sc.fiji.snt.Tree#listFromFile(String)} can read: reconstruction files (see
	 * {@link #isReconstructionFile(File, boolean)} with {@code sniffXml} set to true) and zip archives of
	 * reconstructions (see {@link #isReconstructionZip(File)}). Same limits as
	 * {@link #listReconstructionFiles(File...)}. May block on slow file systems: do not call it from the Event
	 * Dispatch Thread
	 *
	 * @param files the files and/or directories to be flattened. Null allowed
	 * @return the sources found (empty if none)
	 */
	public static List<File> listReconstructionSources(final File... files) {
		return listFiles(MAX_LISTED_FILES, f -> isReconstructionFile(f, true) || isReconstructionZip(f), files);
	}

	/**
	 * Flattens files and directories (searched recursively) into a list of files accepted by a filter. The search
	 * stops once {@code maxFiles} files have been found, does not descend more than 16 levels below the specified
	 * items, and does not follow symbolic links to folders below the specified items (which guards against cycles).
	 * Null entries and unreadable directories are skipped. May block on slow file systems: do not call it from the
	 * Event Dispatch Thread
	 *
	 * @param maxFiles the maximum number of files to return
	 * @param filter   the condition files (not folders) must meet to be listed
	 * @param files    the files and/or directories to be flattened. Null allowed
	 * @return the files found (empty if none)
	 */
	public static List<File> listFiles(final int maxFiles, final java.util.function.Predicate<File> filter,
			final File... files) {
		final List<File> result = new ArrayList<>();
		collectFiles(files, filter, result, maxFiles, 0);
		return result;
	}

	/**
	 * Lists the reconstruction files at the top level of a folder (not recursive), as defined by
	 * {@link #isReconstructionFile(File, boolean)} with {@code sniffXml} set to true. Unlike
	 * {@link #listReconstructionFiles(File...)}, the search is bounded: at most {@code maxFiles}
	 * files are returned (the first ones by name), and at most 10,000 folder entries are examined.
	 * Hidden files are skipped, symbolic links are not followed into folders, and an unreadable
	 * folder yields the files found so far. May block on slow file systems: do not call it from
	 * the Event Dispatch Thread
	 *
	 * @param dir      the folder to be searched
	 * @param maxFiles the maximum number of files to return
	 * @return the reconstruction files found, sorted by name (empty if none)
	 */
	public static List<File> listTopLevelReconstructionFiles(final File dir, final int maxFiles) {
		final List<File> result = new ArrayList<>();
		if (dir == null || maxFiles <= 0) return result;
		// Candidates are selected by name alone and sorted, so that the first maxFiles files are returned
		// regardless of the order in which the file system lists them. Only the files that make the cut
		// are sniffed (XML)
		final List<File> candidates = new ArrayList<>();
		int scanned = 0;
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir.toPath())) {
			for (final Path path : ds) {
				if (++scanned > MAX_SCANNED_ENTRIES) break;
				final File f = path.toFile();
				if (f.getName().startsWith(".") || !getType(f).isReconstructionCandidate()) continue;
				if (Files.isRegularFile(path)) candidates.add(f);
			}
		} catch (final IOException | RuntimeException ex) {
			// unreadable folder: keep what was found
		}
		candidates.sort(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
		for (final File f : candidates) {
			if (result.size() >= maxFiles) break;
			if (isReconstructionFile(f, true)) result.add(f);
		}
		return result;
	}

	// Extraction folders still on disk. Emptied (recursively) when the JVM exits
	private static final java.util.Set<File> TEMP_ROOTS = java.util.concurrent.ConcurrentHashMap.newKeySet();
	static {
		Runtime.getRuntime().addShutdownHook(new Thread(() -> TEMP_ROOTS.forEach(IOUtils::deleteRecursively),
				"SNT-IOUtils-cleanup"));
	}

	/**
	 * Deletes a file or folder, including its contents. Failures are ignored (best effort)
	 *
	 * @param file the file or folder to be deleted. Null allowed
	 */
	public static void deleteRecursively(final File file) {
		if (file == null) return;
		TEMP_ROOTS.remove(file);
		try (java.util.stream.Stream<Path> walk = Files.walk(file.toPath())) {
			walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
		} catch (final IOException | RuntimeException ignored) {
			// best effort
		}
	}

	/**
	 * Extracts a local zip archive into a new temporary directory. The folder is deleted when the JVM exits, but
	 * callers that no longer need the contents should delete it earlier, see {@link #deleteRecursively(File)}. The
	 * archive is left untouched.
	 *
	 * @param zipFile             the archive
	 * @param reconstructionsOnly if true, only entries that may be reconstruction files (see
	 *                            {@link FileType#isReconstructionCandidate()}) are extracted, skipping
	 *                            hidden entries and those under {@code __MACOSX/}
	 * @return the temporary directory holding the extracted contents
	 * @throws IOException if the archive could not be read, if it contains an entry that would extract outside
	 *                     the target directory ("zip-slip") or that has an invalid name, or if its contents would take
	 *                     more than half of the free space of the temporary folder (e.g., a "zip bomb"). The number of
	 *                     entries is not limited. Nothing is left on disk if the extraction fails
	 */
	public static File extractZip(final File zipFile, final boolean reconstructionsOnly) throws IOException {
		final long free = new File(System.getProperty("java.io.tmpdir")).getUsableSpace(); // 0 if unknown
		return extractZip(zipFile, reconstructionsOnly, (free > 0) ? free / 2 : Long.MAX_VALUE);
	}

	/**
	 * Extracts the entries of a local zip archive accepted by a filter into a new temporary directory. Same
	 * safeguards as {@link #extractZip(File, boolean)}.
	 *
	 * @param zipFile     the archive
	 * @param entryFilter the condition entry names (using '/' or '\\' as separators) must meet to be extracted.
	 *                    Folder entries are skipped
	 * @return the temporary directory holding the extracted contents
	 * @throws IOException see {@link #extractZip(File, boolean)}
	 * @see #isReconstructionEntry(String)
	 * @see #isSWCEntry(String)
	 */
	public static File extractZip(final File zipFile, final Predicate<String> entryFilter) throws IOException {
		final long free = new File(System.getProperty("java.io.tmpdir")).getUsableSpace(); // 0 if unknown
		return extractZip(zipFile, java.util.Objects.requireNonNull(entryFilter), (free > 0) ? free / 2 : Long.MAX_VALUE);
	}

	// maxBytes: extraction fails once more than this number of bytes is written
	static File extractZip(final File zipFile, final boolean reconstructionsOnly, final long maxBytes)
			throws IOException {
		final Predicate<String> filter = reconstructionsOnly ? IOUtils::isReconstructionEntry : null;
		return extractZip(zipFile, filter, maxBytes);
	}

	// entryFilter: null extracts everything, including (empty) folders
	private static File extractZip(final File zipFile, final Predicate<String> entryFilter, final long maxBytes)
			throws IOException {
		final Path targetDir = Files.createTempDirectory(null).normalize();
		TEMP_ROOTS.add(targetDir.toFile());
		long extractedBytes = 0;
		final byte[] buf = new byte[64 * 1024];
		try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
				new java.io.BufferedInputStream(Files.newInputStream(zipFile.toPath())))) {
			java.util.zip.ZipEntry entry;
			while ((entry = zis.getNextEntry()) != null) {
				final Path entryPath;
				try {
					entryPath = targetDir.resolve(entry.getName()).normalize();
				} catch (final java.nio.file.InvalidPathException ex) {
					throw new IOException("Zip entry '" + entry.getName() + "' has an invalid name", ex);
				}
				if (!entryPath.startsWith(targetDir)) {
					throw new IOException("Zip entry '" + entry.getName()
							+ "' would extract outside the target directory (zip-slip)");
				}
				if (entry.isDirectory()) {
					if (entryFilter == null) Files.createDirectories(entryPath);
				} else if (entryFilter == null || entryFilter.test(entry.getName())) {
					if (entryPath.getParent() != null) Files.createDirectories(entryPath.getParent());
					try (java.io.OutputStream out = Files.newOutputStream(entryPath)) {
						int n;
						while ((n = zis.read(buf)) > 0) {
							// Checked while copying: a single huge entry must not fill the disk
							extractedBytes += n;
							if (extractedBytes > maxBytes) {
								throw new IOException("'" + zipFile.getName() + "' is too large to be extracted: its "
										+ "contents exceed half of the free space available in the temporary folder");
							}
							out.write(buf, 0, n);
						}
					}
				}
				zis.closeEntry();
			}
		} catch (final IOException | RuntimeException ex) {
			deleteRecursively(targetDir.toFile());
			throw ex;
		}
		return targetDir.toFile();
	}

	/** Whether a zip entry may be a reconstruction file: by name, and not hidden/macOS resource-fork debris */
	public static boolean isReconstructionEntry(final String entryName) {
		final String leaf = visibleLeaf(entryName);
		return leaf != null && getType(leaf).isReconstructionCandidate();
	}

	/** Whether a zip entry is a SWC file: by name, and not hidden/macOS resource-fork debris */
	public static boolean isSWCEntry(final String entryName) {
		final String leaf = visibleLeaf(entryName);
		return leaf != null && getType(leaf) == FileType.SWC;
	}

	// The file name of a zip entry, or null if the entry is hidden or macOS resource-fork debris
	private static String visibleLeaf(final String entryName) {
		final String name = entryName.replace('\\', '/');
		if (name.startsWith("__MACOSX/") || name.contains("/__MACOSX/")) return null;
		final String leaf = name.substring(name.lastIndexOf('/') + 1);
		return leaf.startsWith(".") ? null : leaf;
	}

	/** What a zip archive holds, as far as SNT is concerned */
	public enum ZipContents {
		/** At least one ImageJ ROI file (wins over reconstructions) */
		ROIS,
		/** At least one reconstruction file, and no ROIs */
		RECONSTRUCTIONS,
		/** None of the above, or an unreadable archive */
		OTHER
	}

	/**
	 * Sniffs a zip archive: only the names of its first {@code maxEntries} entries are examined (the archive's
	 * central directory is read, but nothing is extracted). May block on slow file systems: do not call it from
	 * the Event Dispatch Thread
	 *
	 * @param zip        the archive
	 * @param maxEntries the maximum number of entries examined
	 * @return the contents of the archive
	 */
	public static ZipContents sniffZip(final File zip, final int maxEntries) {
		boolean reconstructions = false;
		try (ZipFile zf = new ZipFile(zip)) {
			final var entries = zf.entries();
			for (int i = 0; i < maxEntries && entries.hasMoreElements(); i++) {
				final ZipEntry e = entries.nextElement();
				if (e.isDirectory()) continue;
				if (e.getName().toLowerCase(Locale.ROOT).endsWith(".roi")) return ZipContents.ROIS;
				reconstructions |= isReconstructionEntry(e.getName());
			}
		} catch (final IOException | RuntimeException ex) {
			// corrupt or unreadable: neither
		}
		return reconstructions ? ZipContents.RECONSTRUCTIONS : ZipContents.OTHER;
	}

	/**
	 * Counts the entries of a zip archive that may be reconstruction files, see {@link #isReconstructionEntry(String)}.
	 * May block on slow file systems: do not call it from the Event Dispatch Thread
	 *
	 * @param zip the archive
	 * @return the number of entries (0 if the archive cannot be read)
	 */
	public static int countReconstructionEntries(final File zip) {
		try (ZipFile zf = new ZipFile(zip)) {
			return (int) zf.stream().filter(e -> !e.isDirectory() && isReconstructionEntry(e.getName())).count();
		} catch (final IOException | RuntimeException ex) {
			return 0;
		}
	}

	/**
	 * Checks if a zip archive holds reconstruction files (and no ROIs). See {@link #sniffZip(File, int)}
	 *
	 * @param zip the archive
	 * @return true if the first entries of the archive include reconstruction files
	 */
	public static boolean isReconstructionZip(final File zip) {
		return getType(zip) == FileType.ZIP && sniffZip(zip, 50) == ZipContents.RECONSTRUCTIONS;
	}

	/** Name of the file that identifies a folder as an SNT session */
	public static final String SESSION_MANIFEST = "session.properties";

	/** Prefix of the folders created for each saved SNT session */
	public static final String SESSION_PREFIX = "SNT_Session_";

	/**
	 * Returns the most recent session folder in a folder of sessions
	 *
	 * @param sessionsDir the folder holding {@value #SESSION_PREFIX} folders. Null allowed
	 * @return the latest session (by name), or null if none exists or the folder is missing/unreadable
	 */
	public static File latestSession(final File sessionsDir) {
		final File[] children = (sessionsDir == null) ? null : sessionsDir.listFiles();
		if (children == null) return null;
		return Arrays.stream(children).filter(f -> f.isDirectory() && f.getName().startsWith(SESSION_PREFIX))
				.max(Comparator.comparing(File::getName)).orElse(null);
	}

	private static void collectFiles(final File[] files, final java.util.function.Predicate<File> filter,
			final List<File> result, final int maxFiles, final int depth) {
		if (files == null) return;
		for (final File f : files) {
			if (result.size() >= maxFiles) return;
			if (f == null) continue;
			if (f.isDirectory()) {
				if (depth <= MAX_SEARCH_DEPTH && (depth == 0 || !Files.isSymbolicLink(f.toPath())))
					collectFiles(f.listFiles(), filter, result, maxFiles, depth + 1);
			} else if (f.isFile() && filter.test(f)) {
				result.add(f);
			}
		}
	}
}
