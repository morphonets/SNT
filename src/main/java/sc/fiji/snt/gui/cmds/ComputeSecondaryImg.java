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
import org.scijava.display.DisplayService;
import org.scijava.io.IOService;
import org.scijava.platform.PlatformService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.Button;

import sc.fiji.snt.SNTPrefs;
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
public class ComputeSecondaryImg<T extends RealType<T> & NativeType<T>> extends CommonDynamicCmd
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

	@Parameter(label = "Filter", choices = { FRANGI, TUBENESS, GAUSS, NONE })
	private String filter;

	@Parameter(label = "Lazy processing")
	private boolean useLazy;

	@Parameter(label = "Number of threads", min = "1", stepSize = "1")
	private int numThreads;

	@Parameter(label = "Display", required = false)
	private boolean show;

	@Parameter(label = "Save", required = false)
	private boolean save;

	@Parameter(label = "<HTML>&nbsp;", persist = false, visibility = ItemVisibility.MESSAGE)
	private String msg = "<HTML>It is assumed that the current sigma values for the primary image in<br>"
			+ "the Auto-tracing widget reflect the size of structures to be filtered.<br>"
			+ "If that is not the case, you should dismiss this prompt and adjust it.";

	@Parameter(label = "Online Help", callback = "help")
	private Button button;

	@SuppressWarnings("rawtypes")
	private RandomAccessibleInterval filteredImg;

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
		if (numThreads > SNTPrefs.getThreads()) {
			numThreads = SNTPrefs.getThreads();
		}
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
		final double[] sigmas = sntService.getPlugin().getHessianSigma("primary", true);
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
