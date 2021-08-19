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

import ij.ImagePlus;
import ij.measure.Calibration;
import net.imagej.ImageJ;
import net.imagej.legacy.LegacyService;
import net.imagej.ops.OpService;
import net.imagej.util.Images;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.cell.CellImgFactory;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.command.Interactive;
import org.scijava.display.DisplayService;
import org.scijava.io.IOService;
import org.scijava.module.MutableModuleItem;
import org.scijava.platform.PlatformService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.Button;

import sc.fiji.snt.SNTPrefs;
import sc.fiji.snt.SNTUI;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.filter.*;
import sc.fiji.snt.gui.GuiUtils;

import java.io.File;
import java.io.IOException;
import java.net.URL;

/**
 * Implements the "Generate Secondary Image" command.
 *
 * @author Tiago Ferreira
 * @author Cameron Arshadi
 */
@Plugin(type = Command.class, visible = false, initializer = "init", label = "Compute \"Secondary Image\"")
public class ComputeSecondaryImg<T extends RealType<T> & NativeType<T>> extends CommonDynamicCmd implements Interactive
{

	private static final String NONE = "None. Duplicate primary image";
	private static final String FRANGI = "Frangi Vesselness";
	private static final String TUBENESS = "Tubeness";
	private static final String GAUSS = "Gaussian Blur";
	private static final String MEDIAN = "Median";

	@Parameter
	private DisplayService displayService;

	@Parameter
	private LegacyService legacyService;

	@Parameter
	private PlatformService platformService;

	@Parameter
	private OpService ops;

	@Parameter
	private IOService io;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE, label = HEADER_HTML + "Filtering:")
	private String HEADER1;

	@Parameter(label = "Filter", choices = { FRANGI, TUBENESS, GAUSS, NONE })
	private String filter;

	@Parameter(label = "Size(s) of structures being traced", description="Comma separated list", required = false)
	private String sizeOfStructuresString;
	
	@Parameter(label = "Select Visually", callback="triggerSigmaPalette")
	private Button triggerSigmaPalette;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE, label = HEADER_HTML + "Performance/Memory:")
	private String HEADER2;

	@Parameter(label = "Strategy", choices = { "Save memory (Dilute computation time across searches)",
			"Cache filtering (Compute once and store result in RAM)" })
	private String useLazyChoice;

	@Parameter(label = "Number of threads", min = "1", stepSize = "1")
	private int numThreads;

	@Parameter(required = false, visibility = ItemVisibility.MESSAGE, label = HEADER_HTML + "Options:")
	private String HEADER3;

	@Parameter(label = "Display", required = false)
	private boolean show;

	@Parameter(label = "Save", required = false)
	private boolean save;

	@Parameter(label = "Online Help", callback = "help")
	private Button button;

	@Parameter(required = false, persist = false)
	private boolean skipPrompt;

	@SuppressWarnings("rawtypes")
	private RandomAccessibleInterval filteredImg;
	private boolean useLazy;

	protected void init() {
		super.init(true);
		if (skipPrompt) {
			resolveInput("numThreads");
			numThreads = SNTPrefs.getThreads();
			resolveInput("button");
			resolveInput("msg");
		}
		resolveInput("skipPrompt");
		if (!snt.accessToValidImageData()) {
			error("Valid image data is required for computation.");
			return;
		}
	}

	@SuppressWarnings("unused")
	private void triggerSigmaPalette() {
		ui.changeState(SNTUI.WAITING_FOR_SIGMA_POINT_I);
		snt.setCanvasLabelAllPanes("Choosing Sigma");
		final String buttonLabel = (ui.getState() == SNTUI.WAITING_FOR_SIGMA_POINT_I) ? "Now click on a representative structure" : "Select Visually";
		final MutableModuleItem<Button> mmi = getInfo().getMutableInput("triggerSigmaPalette", Button.class);
		mmi.setLabel(buttonLabel);
	}

	@SuppressWarnings("unused")
	private void help() {
		final String url = "https://imagej.net/SNT:_Manual#Tracing_on_Secondary_Image";
		try {
			platformService.open(new URL(url));
		} catch (final IOException e) {
			error("Web page could not be open. " + "Please visit " + url + " using your web browser.");
		}
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Runnable#run()
	 */
	@SuppressWarnings("unchecked")
	@Override
	public void run() {
		if (isCanceled() || !snt.accessToValidImageData())
			return;
		if (numThreads > SNTPrefs.getThreads()) {
			numThreads = SNTPrefs.getThreads();
		}
		useLazy = useLazyChoice.toLowerCase().startsWith("save memory");

		final int cellDim = 30; // side length for cell
		if (NONE.equals(filter)) {
			final RandomAccessibleInterval<T> loadedData = sntService.getPlugin().getLoadedData();
			final RandomAccessibleInterval<T> copy = ops.create().img(loadedData);
			Images.copy(loadedData, copy);
			filteredImg = copy;
			apply();
			return;
		}
		final RandomAccessibleInterval<T> data = sntService.getPlugin().getLoadedData();
		final RandomAccessibleInterval<T> in = Views.dropSingletonDimensions(data);
		final double[] sigmas = sntService.getPlugin().getHessianSigma(true);
		final Calibration cal = sntService.getPlugin().getImagePlus().getCalibration();
		final double[] spacing = new double[]{cal.pixelWidth, cal.pixelHeight, cal.pixelDepth};
		switch (filter) {
			case FRANGI: {
				Frangi<T, FloatType> op = new Frangi<>(
						sigmas,
						spacing,
						sntService.getPlugin().getStats().max,
						numThreads);

				if (useLazy) {
					filteredImg = Lazy.process(
							in,
							in,
							new int[]{cellDim, cellDim, cellDim},
							new FloatType(),
							op);
				} else {
					filteredImg = ops.create().img(in, new FloatType(), new CellImgFactory<>(new FloatType()));;
					op.compute(in, filteredImg);
				}

				break;
			}
			case TUBENESS: {
				Tubeness<T, FloatType> op = new Tubeness<>(sigmas, spacing, numThreads);
				if (useLazy) {
					filteredImg = Lazy.process(
							in,
							in,
							new int[]{cellDim, cellDim, cellDim},
							new FloatType(),
							op);
				} else {
					filteredImg = ops.create().img(in, new FloatType(), new CellImgFactory<>(new FloatType()));
					op.compute(in, filteredImg);
				}

				break;
			}
			case GAUSS: {
				final double sig = sigmas[0]; // just pick the first sigma I guess...
				if (useLazy) {
					filteredImg = Lazy.process(
							in,
							in,
							new int[]{cellDim, cellDim, cellDim},
							new FloatType(),
							ops,
							net.imagej.ops.filter.gauss.DefaultGaussRAI.class,
							(Object) new double[]{sig / spacing[0], sig / spacing[1], sig / spacing[2]});
				} else {
					filteredImg = ops.create().img(in, new FloatType(), new CellImgFactory<>(new FloatType()));
					ops.filter().gauss(
							filteredImg,
							in,
							sig / spacing[0], sig / spacing[1], sig / spacing[2]);
				}

				break;
			}

			default:
				throw new IllegalArgumentException("Unrecognized filter " + filter);
		}

		apply();
	}

	@SuppressWarnings("unchecked")
	private void apply() {
		snt.loadSecondaryImage(filteredImg, !useLazy);
		if (show) {
			displayService.createDisplay(getImageName(), filteredImg); // virtual stack!?
//			ImageJFunctions.show(filteredImg, getImageName());
		}
		if (save) {
			try {
				io.save(filteredImg, getSaveFile().getAbsolutePath());
			} catch (final IOException e) {
				error("An error occurred when trying to save image. See console for details");
				e.printStackTrace();
			}
		}
		resetUI();
	}

	private String getImageName() {
		final String basename = SNTUtils.stripExtension(sntService.getPlugin().getImagePlus().getTitle());
		final String sfx = (NONE.equals(filter)) ? "DUP" : filter;
		return basename + " Sec Img [" + sfx + "].tif";
	}

	private File getSaveFile() {
		File file = new File(sntService.getPlugin().getPrefs().getRecentDir(), getImageName());
		file = SNTUtils.getUniquelySuffixedTifFile(file);
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
