/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2021 Fiji developers.
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

import ij.IJ;
import ij.ImagePlus;
import ij.io.FileInfo;
import net.imagej.ImageJ;
import net.imagej.legacy.LegacyService;
import net.imagej.ops.OpService;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.numeric.RealType;
import net.imglib2.view.Views;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.io.IOService;
import org.scijava.platform.PlatformService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.util.FileUtils;
import org.scijava.widget.Button;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.filter.AbstractFilter;
import sc.fiji.snt.filter.Frangi;
import sc.fiji.snt.filter.Tubeness;
import sc.fiji.snt.gui.GuiUtils;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Arrays;

/**
 * Implements the "Generate Secondary Image" command.
 *
 * @author Tiago Ferreira
 * @author Cameron Arshadi
 */
@Plugin(type = Command.class, visible = false, initializer = "init", label = "Compute \"Secondary Image\"")
public class ComputeSecondaryImg extends CommonDynamicCmd {

	private static final String NONE = "None. Duplicate primary image";
	private static final String FRANGI = "Frangi";
	private static final String TUBENESS = "Tubeness";

	@Parameter
	private LegacyService legacyService;

	@Parameter
	private PlatformService platformService;

	@Parameter
	private OpService ops;

	@Parameter
	private IOService io;

	@Parameter(label = "Filter", choices = { FRANGI, TUBENESS, NONE })
	private String filter;

	@Parameter(label = "Display", required = false)
	private boolean show;

	@Parameter(label = "Save", required = false)
	private boolean save;

	@Parameter(label = "N.B.:", persist = false, visibility = ItemVisibility.MESSAGE)
	private String msg = "<HTML>It is assumed that the current sigma value for the primary image in<br>"
			+ "the Auto-tracing widget reflects the size of structures to be filtered.<br>"
			+ "If that is not the case, you should dismiss this prompt and adjust it.";

	@Parameter(label = "Online Help", callback = "help")
	private Button button;

	private ImagePlus filteredImp;

	protected void init() {
		super.init(true);
		if (!snt.accessToValidImageData()) {
			error("Valid image data is required for computation.");
			return;
		}

	}

	@SuppressWarnings("unused")
	private void help() {
		final String url = "https://imagej.net/SNT:_Manual#Tracing_on_Secondary_Image";
		try {
			platformService.open(new URL(url));
		} catch (final IOException e) {
			msg("<HTML><div WIDTH=400>Web page could not be open. " + "Please visit " + url
					+ " using your web browser.", "Error");
		}
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {
		final ImagePlus inputImp = sntService.getPlugin().getLoadedDataAsImp();
		if (NONE.equals(filter)) {
			filteredImp = inputImp;
			apply();
			return;
		}
		final RandomAccessibleInterval<? extends RealType<?>> in = Views.dropSingletonDimensions(
				sntService.getPlugin().getLoadedData());
		final double[] sigmas = sntService.getPlugin().getHessianSigma("primary", true);
		AbstractFilter processor;
		switch (filter) {
			case FRANGI:
				processor = new Frangi(in, sigmas, inputImp.getCalibration());
				break;
			case TUBENESS:
				processor = new Tubeness(in, sigmas, inputImp.getCalibration());
				break;
			default:
				throw new IllegalArgumentException("Unrecognized filter " + filter);
		}
		processor.process();
		filteredImp = ImageJFunctions.wrap(processor.getResult(),
				"scales=" + Arrays.toString(sigmas));
		filteredImp.setDimensions(inputImp.getNChannels(), inputImp.getNSlices(), inputImp.getNFrames());
		filteredImp.copyScale(inputImp);
		apply();
	}

	private void apply() {
		final File file = (save) ? getSaveFile() : null;
		if (file != null) {
			//TODO: Move to IOService, once it supports saving of ImagePlus
			final boolean saved = IJ.saveAsTiff(filteredImp, file.getAbsolutePath());
			SNTUtils.log("Saving to " + file.getAbsolutePath() + "... " + ((saved) ? "success" : "failed"));
			if (!saved)
				msg("An error occured while saving image.", "IO Error");
		}
		snt.loadSecondaryImage(filteredImp);
		snt.setSecondaryImage(file);
		if (show) {
			filteredImp.resetDisplayRange();
			filteredImp.show();
		}
		resetUI();
	}

	private File getSaveFile() {
		final String impTitle = sntService.getPlugin().getImagePlus().getTitle();
		final String filename = impTitle.replace("." + FileUtils.getExtension(impTitle), "");
		final FileInfo fInfo = sntService.getPlugin().getImagePlus().getOriginalFileInfo();
		File file;
		if (fInfo != null && fInfo.directory != null && !fInfo.directory.isEmpty()) {
			file = new File(fInfo.directory, filename + "[" + filter + "].tif");
		} else {
			file = new File(System.getProperty("user.home"), filename + "[" + filter + "].tif");
		}
		int i = 0;
		while (file.exists()) {
			i++;
			file = new File(file.getAbsolutePath().replace("].tif", "-" + String.valueOf(i) + "].tif"));
		}
		return legacyService.getIJ1Helper().saveDialog("Save \"Filtered Image\"", file, ".tif");
	}

	/* IDE debug method **/
	public static void main(final String[] args) {
		GuiUtils.setLookAndFeel();
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();
		ij.command().run(ComputeSecondaryImg.class, true);
	}
}
