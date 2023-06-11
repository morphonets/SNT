/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2023 Fiji developers.
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

package sc.fiji.snt.gui.cmds;

import java.io.File;
import java.util.Collection;
import java.util.stream.IntStream;

import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import net.imagej.ImageJ;
import sc.fiji.snt.PathAndFillManager;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.io.NDFImporter;

/**
 * Command for importing a NeuronJ NDF file.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, initializer = "init", label = "Import NeuronJ NDF")
public class NDFImporterCmd extends CommonDynamicCmd {

	@Parameter(label = "File")
	private File file;

	@Parameter(required = false, label = "Apply spatial calibration", //
			description = "The NDF file contains pixel coordinates. Scale them to physical units?")
	private boolean scale;

	@Parameter(required = false, label = "Replace existing paths")
	private boolean clearExisting;

	@Parameter(required = false, label = "Log file properties to console")
	private boolean properties;

	@SuppressWarnings("unused")
	private void init() {
		super.init(false);
		if (snt != null && !snt.accessToValidImageData())
			resolveInput("scale");
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {

		final PathAndFillManager pafm = sntService.getPathAndFillManager();

		status("Importing file. Please wait...", false);
		SNTUtils.log("Importing file " + file);

		final int lastExistingPathIdx = pafm.size() - 1;
		try {
			final NDFImporter importer = new NDFImporter(file);
			final Collection<Tree> trees = importer.getTrees();
			if (scale && snt != null) {
				trees.forEach(tree -> {
					if (snt.accessToValidImageData())
						tree.assignImage(snt.getImagePlus());
					tree.scale(snt.getPixelWidth(), snt.getPixelHeight(), 1);
				});
			}
			pafm.addTrees(trees);
			if (pafm.size() - 1 == lastExistingPathIdx) {
				error("No reconstructions could be retrieved. Invalid file?");
				status("Error... No reconstructions imported", true);
				return;
			} else if (!snt.accessToValidImageData()) {
				snt.rebuildDisplayCanvases();
			}
			if (clearExisting) {
				final int[] indices = IntStream.rangeClosed(0, lastExistingPathIdx).toArray();
				pafm.deletePaths(indices);
			}
			if (properties) {
				System.out.println();
				System.out.println("NDF file properties:");
				System.out.println("    Path: " + file.getAbsolutePath());
				importer.getProperties().forEach((k, v) -> {
					System.out.println("    " + k + ": " + v);
				});
				System.out.println();
			}

			if (snt != null)
				notifyExternalDataLoaded();
			status("Successfully imported " + trees.size() + " tracing(s)...", true);

		} catch (final Exception e) {
			error(e.getMessage());
		} finally {
			resetUI(pafm.size() > lastExistingPathIdx);
		}

	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		ij.command().run(NDFImporterCmd.class, true);
	}

}
