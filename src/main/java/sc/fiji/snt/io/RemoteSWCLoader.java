/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2024 Fiji developers.
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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.apache.commons.io.IOUtils;

import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;

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

	static boolean download(final String urlString, final File destinationFile) {
		try {
			final BufferedReader br = new BufferedReader(new InputStreamReader(new URI(urlString).toURL().openStream()));
			final BufferedWriter bw = Files.newBufferedWriter(destinationFile.toPath(), StandardCharsets.UTF_8);
//			final char[] buffer = new char[8192];
//			int n;
//			while (-1 != (n = br.read())) {
//				bw.write(buffer, 0, n);
//			}
//			br.close();
//			bw.close();
			return IOUtils.copy(br, bw) > 0 && destinationFile.exists() && destinationFile.length() > 0;
		} catch (final IOException | URISyntaxException e) {
			SNTUtils.error("Download failed", e);
		}
		return false;
	}

}
