/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2022 Fiji developers.
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

import io.scif.services.DatasetIOService;

import java.io.File;

import net.imagej.ImageJ;

import org.scijava.command.Command;
import org.scijava.convert.ConvertService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import ij.IJ;
import ij.ImagePlus;

/**
 * Implements the 'Choose Tracing Image (From File)...' command.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, initializer = "init", visible = false,
	label = "Change Tracing Image")
public class OpenDatasetCmd extends CommonDynamicCmd implements Command {

	@Parameter
	private DatasetIOService ioService;

	@Parameter
	private ConvertService convertService;

	@Parameter(label = "New tracing image:")
	private File file;

	@Override
	public void run() {
		init(true);
		try {
			// In theory we should be able to use ioService but the
			// following seems to always generate a virtual stack
//			final Dataset ds = ioService.open(file.getAbsolutePath());
//			final ImagePlus imp = convertService.convert(ds, ImagePlus.class);
//			snt.initialize(imp);
			final ImagePlus imp = IJ.openImage(file.getAbsolutePath());
			if (imp.getType()==ImagePlus.COLOR_RGB) {
				error("Invalid Image: RGB images not supported.");
			} else {
				snt.initialize(imp);
			}
		}
		catch (final Throwable ex) {
			error("Loading of image failed (" + ex.getMessage() +
				" error). See Console for details.");
			ex.printStackTrace();
		} finally {
			resetUI();
		}
	}

	public static void main(final String... args) {
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		ij.command().run(OpenDatasetCmd.class, true);
	}

}
