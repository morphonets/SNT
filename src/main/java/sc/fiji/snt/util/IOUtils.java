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
import java.nio.file.Files;

/**
 * Static utilities for file I/O, namely the detection of reconstruction file formats.
 *
 * @author Tiago Ferreira
 */
public final class IOUtils {

	/** Number of bytes inspected when guessing the format of a file */
	private static final int SNIFF_BYTES = 4096;
	private static final String[] RECONSTRUCTION_EXTENSIONS = { "swc", "eswc", "traces", "json", "ndf" };

	private IOUtils() {
		// static methods only
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
	 * number, XML (Neurolucida if the header holds an {@code <mbf>} root element), JSON ({@code '{'}),
	 * NDF ({@code '/'}), or SWC otherwise.
	 *
	 * @param is                  the stream to be inspected. If it supports marking, it is reset
	 *                            after inspection (unless {@code closeStreamAfterGuess} is true)
	 * @param closeStreamAfterGuess whether {@code is} should be closed after inspection
	 * @return the detected format
	 * @throws IOException if an I/O error occurs
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
	 * formats that can be identified by name alone, i.e., not including Neurolucida .xml files
	 *
	 * @return a new array of extensions, e.g., "swc", "traces", etc.
	 */
	public static String[] getReconstructionExtensions() {
		return RECONSTRUCTION_EXTENSIONS.clone();
	}

	/**
	 * Checks if a file has an SWC extension, i.e., .swc or .eswc (extended SWC, which holds extra
	 * columns that are ignored on import). Case-insensitive.
	 *
	 * @param file the file to be tested
	 * @return true if the file name ends with ".swc" or ".eswc"
	 */
	public static boolean isSWCFile(final File file) {
		if (file == null) return false;
		final String lName = file.getName().toLowerCase();
		return lName.endsWith(".swc") || lName.endsWith(".eswc");
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
		final String lName = file.getName().toLowerCase();
		for (final String ext : RECONSTRUCTION_EXTENSIONS) {
			if (lName.endsWith("." + ext)) return true;
		}
		if (!sniffXml || !lName.endsWith(".xml")) return false;
		try {
			return detectFormat(file) == ReconstructionFormat.NEUROLUCIDA;
		} catch (final IOException | RuntimeException ex) {
			return false;
		}
	}
}
