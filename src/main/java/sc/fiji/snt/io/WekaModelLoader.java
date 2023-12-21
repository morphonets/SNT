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

package sc.fiji.snt.io;

import java.io.File;
import java.util.List;

import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import ij.IJ;
import ij.ImagePlus;
import net.imagej.ImgPlus;
import net.imagej.axis.Axes;
import net.imagej.ops.OpService;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import net.imglib2.util.Intervals;
import sc.fiji.labkit.ui.segmentation.SegmentationTool;
import sc.fiji.labkit.ui.segmentation.Segmenter;
import sc.fiji.labkit.ui.segmentation.weka.TrainableSegmentationSegmenter;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.cmds.CommonDynamicCmd;
import sc.fiji.snt.util.ImpUtils;
import trainableSegmentation.WekaSegmentation;

/**
 * GUI command for Loading pre-trained models from Labkit/TWS as secondary image
 * layer.
 *
 * @author Tiago Ferreira
 */
@Plugin(type = Command.class, initializer = "init", visible = false, label = "Load Secondary Layer from TWS/Labkit Model...")
public class WekaModelLoader extends CommonDynamicCmd {

	@Parameter(required = false, persist = false, visibility = ItemVisibility.MESSAGE)
	private final String msg = "<HTML>This command applies a pre-trained Weka model to the image being traced (active<br>"
			+ "channel/frame only), and loads the classification as a secondary tracing layer.<br>"
			+ "Labkit and Trainable Weka Segmentation (TWS) models are both supported.";

	@Parameter(required = true, label = "Model file")
	private File file;

	@Parameter(label = "Loading engine", choices = { "Labkit", "Labkit w/ GPU acceleration",
			"Trainable Weka Segmentation (TWS)" }, description = "<HTML>The program from which the model was exported.<br>"
					+ "NB: Labkit w/ GPU acceleration expects CLIJ2 access and may not be supported by all graphics cards.")
	private String engineChoice;

	@Parameter(label = "Load as", choices = { "Probability image (chosen classifier)", "Segmented image" })
	private String outputChoice;

	@Parameter(required = false, label = "Display", description = "<HTML>Wether to display the data after being loaded as "
			+ "secondart tracing layer.<br>NB: Can be done later on using View> menu commands.")
	private boolean display;

	@Parameter
	protected OpService ops;

	private boolean redirectErrorMsgsState;

	@SuppressWarnings("unused")
	private void init() {
		init(true);
		redirectErrorMsgsState = IJ.redirectingErrorMessages();
		IJ.redirectErrorMessages(true); // required to handle TWS errors
	}

	@Override
	protected void resetUI() {
		super.resetUI();
		IJ.redirectErrorMessages(redirectErrorMsgsState);
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {

		final boolean tws = engineChoice == null || engineChoice.toLowerCase().contains("tws");
		if (file == null || !validModelExtension(file, tws)) {
			error("Filename does not feature a valid extension.");
			return;
		}
		if (!SNTUtils.fileAvailable(file) || !snt.accessToValidImageData()) {
			error("Model file is not available or no tracing image exists.");
			return;
		}
		final boolean pmap = outputChoice != null && outputChoice.toLowerCase().contains("prob");
		try {
			if (tws) {
				loadSecondaryLayerUsingTWS(pmap);
			} else {
				loadSecondaryLayerUsingLabkit(pmap);
			}
			if (display && snt.isSecondaryDataAvailable()) {
				final ImagePlus impResult = snt.getSecondaryDataAsImp();
				impResult.setDisplayRange(0, snt.getSecondaryImageMinMax()[1]);
				impResult.show();
			}
		} catch (final Throwable t) {
			if (t instanceof OutOfMemoryError) {
				error("Out of Memory: There is not enough RAM to load model as secondary layer!");
			} else {
				error(t.getClass().getSimpleName() + ": An error occured. See Console" + ((tws) ? "/Log window" : "")
						+ " for details.");
				t.printStackTrace();
			}
		} finally {
			status("Loading complete", true);
			resetUI();
		}
	}

	private boolean validModelExtension(final File file, final boolean isTWS) {
		final List<String> extensions = (isTWS) ? List.of("model", "xml", "gz", "classifier")
				: List.of("model", "classifier"); // does labkit use .model extensions?
		for (final String ext : extensions) {
			if (file.getName().toLowerCase().endsWith(ext))
				return true;
		}
		return false;
	}

	private boolean loadSecondaryLayerUsingTWS(final boolean pmap) {
		status("1/4 Preparing model...", false);
		final ImagePlus inputImp = snt.getLoadedDataAsImp();
		final WekaSegmentation segmentator = new WekaSegmentation(inputImp);
		status("2/4 Loading model...", false);
		final boolean loaded = segmentator.loadClassifier(file.getAbsolutePath());
		if (!loaded) {
			error("File could not be loaded. Make sure it is valid and accessible.");
			return false;
		}
		status("3/4 Applying model...", false);
		segmentator.applyClassifier(pmap);
		final ImagePlus classified = segmentator.getClassifiedImage();
		if (pmap) {
			final List<String> classes = ImpUtils.getSliceLabels(classified.getStack());
			final String classChoice = getClassChoice(classes);
			if (classChoice == null) {
				return false;
			} else {
				classes.remove(classChoice);
				ImpUtils.removeSlices(classified.getStack(), classes);
			}
			if (classified.getNSlices() != inputImp.getNSlices()) {
				error("Could not extract class. Please extract it manually from " + classified.getTitle()
						+ ", and load it as secondary layer (<i>Load Precomputed: From Open Image...</i>)");
				return false;
			}
		}
		status("4/4 Loading as II layer...", false);
		snt.flushSecondaryData();
		snt.loadSecondaryImage(classified);
		snt.setUseSubVolumeStats(true);
		return snt.isSecondaryDataAvailable();
	}

	@SuppressWarnings("unchecked")
	private <T extends RealType<T>> boolean loadSecondaryLayerUsingLabkit(final boolean pmap) {

		status("1/4 Preparing model...", false);
		final Segmenter segmenter = new TrainableSegmentationSegmenter(getContext());
		final SegmentationTool segTool = new SegmentationTool();
		segTool.setContext(getContext());

		status("2/4 Loading model...", false);
		segmenter.openModel(file.getAbsolutePath());
		segTool.setSegmenter(segmenter);
		segTool.setUseGpu(engineChoice != null && engineChoice.contains("GPU"));

		status("3/4 Applying model...", false);
		final ImgPlus<T> img = ImgPlus.wrapRAI((RandomAccessibleInterval<T>) snt.getLoadedData());
		ImgPlus<T> result = null;
		if (pmap) {
			final List<String> classes = segmenter.classNames();
			final String classChoice = getClassChoice(classes);
			if (classChoice != null) {
				result = (ImgPlus<T>) segTool.probabilityMap(img);
				result = getChannel(result, classes.indexOf(classChoice));
				result.setName(result.getName() + " [" + classChoice + "]");
			}
		} else {
			result = (ImgPlus<T>) segTool.segment(img);
		}
		if (result != null) {
			status("4/4 Loading as II layer...", false);
			snt.flushSecondaryData();
			snt.loadSecondaryImage(result, false);
			snt.setUseSubVolumeStats(true);
		}
		return snt.isSecondaryDataAvailable();
	}

	private String getClassChoice(final List<String> classes) {
		if (classes.size() == 1)
			return classes.get(0);
		return new GuiUtils().getChoice(
				"Model has " + classes.size() + " classifier classes. Which one should be loaded?", "Choose Class...",
				classes.toArray(new String[0]), "");

	}

	private <T extends RealType<T>> ImgPlus<T> getChannel(final ImgPlus<T> img, final int channel) {
		final long tLen = img.dimension(img.dimensionIndex(Axes.TIME));
		if (tLen > 2)
			throw new IllegalArgumentException("Unexpected time dimension");
		final long cLen = img.dimension(img.dimensionIndex(Axes.CHANNEL));
		if (channel < 0 || channel >= cLen)
			throw new IllegalArgumentException("Channel " + channel + " is out of bounds:  " + 0 + "-" + cLen);
		final long xLen = img.dimension(img.dimensionIndex(Axes.X));
		final long yLen = img.dimension(img.dimensionIndex(Axes.Y));
		final long zLen = img.dimension(img.dimensionIndex(Axes.Z));
		if (zLen == 1) {
			return ops.transform().crop(img, Intervals.createMinMax(0, 0, channel, xLen - 1, yLen - 1, channel)); // XYC
		} else {
			return ops.transform().crop(img,
					Intervals.createMinMax(0, 0, 0, channel, xLen - 1, yLen - 1, zLen - 1, channel)); // XYZC
		}
	}

}
