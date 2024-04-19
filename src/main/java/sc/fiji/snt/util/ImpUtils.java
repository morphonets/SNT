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

package sc.fiji.snt.util;

import java.awt.image.IndexColorModel;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import ij.plugin.*;
import org.scijava.convert.ConvertService;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.io.Opener;
import ij.process.ByteProcessor;
import ij.process.ImageConverter;
import ij.process.ImageStatistics;
import ij.process.LUT;
import ij.process.StackConverter;
import net.imagej.Dataset;
import net.imglib2.display.ColorTable;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;

/**
 * Static utilities for handling and manipulation of {@link ImagePlus}s
 *
 * @author Tiago Ferreira
 *
 */
public class ImpUtils {

	static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

	private ImpUtils() {} // prevent class instantiation

	public static void removeIsolatedPixels(final ImagePlus binaryImp) {
		final ImageStack stack = binaryImp.getStack();
		for (int i = 1; i <= stack.getSize(); i++)
			((ByteProcessor) stack.getProcessor(i)).erode(8, 0);
	}

	public static ImagePlus getMIP(final ImagePlus imp) {
		return getMIP(imp, 1, imp.getNSlices());
	}

	public static ImagePlus getMIP(final ImagePlus imp, final int startSlice, final int stopSlice) {
		final ImagePlus mip = ZProjector.run(imp, "max", startSlice, stopSlice);
		if (mip.getNChannels() == 1)
			mip.setLut(imp.getLuts()[0]); // assume single-channel image
		mip.copyScale(imp);
		new ContrastEnhancer().stretchHistogram(mip, 0.35);
		return mip;
	}

	public static ImagePlus getMIP(final Collection<ImagePlus> imps) {
		return getMIP(toStack(imps));
	}

	public static ImagePlus toStack(final Collection<ImagePlus> imps) {
		return ImagesToStack.run(imps.toArray(new ImagePlus[0]));
	}

	public static void convertTo32bit(final ImagePlus imp) throws IllegalArgumentException {
		if (imp.getBitDepth() == 32)
			return;
		if (imp.getNSlices() == 1)
			new ImageConverter(imp).convertToGray32();
		else
			new StackConverter(imp).convertToGray32();
	}

	public static void convertTo8bit(final ImagePlus imp) {
		if (imp.getType() != ImagePlus.GRAY8) {
			final boolean doScaling = ImageConverter.getDoScaling();
			ImageConverter.setDoScaling(true);
			new ImageConverter(imp).convertToGray8();
			ImageConverter.setDoScaling(doScaling);
		}
	}

	public static ImagePlus convertRGBtoComposite(final ImagePlus imp) {
		if (imp.getType() == ImagePlus.COLOR_RGB) {
			imp.hide();
			final boolean isShowing = imp.isVisible();
			final ImagePlus res = CompositeConverter.makeComposite(imp);
			imp.flush();
			if (isShowing) res.show();
			return res;
		}
		return imp;
	}

	public static ImagePlus open(final File file) {
		return open(file, null);
	}

	public static ImagePlus open(final File file, final String title) {
		final boolean redirecting = IJ.redirectingErrorMessages();
		IJ.redirectErrorMessages(true);
		final ImagePlus imp = IJ.openImage(file.getAbsolutePath());
		if (title != null)
			imp.setTitle(title);
		IJ.redirectErrorMessages(redirecting);
		return imp;
	}

	public static boolean sameXYZDimensions(final ImagePlus imp1, final ImagePlus imp2) {
		return imp1.getWidth() == imp2.getWidth() && imp1.getHeight() == imp2.getHeight()
				&& imp1.getNSlices() == imp2.getNSlices();
	}

	public static boolean sameCTDimensions(final ImagePlus imp1, final ImagePlus imp2) {
		return imp1.getNChannels() == imp2.getNChannels() && imp2.getNFrames() == imp1.getNFrames();
	}

	public static boolean sameCalibration(final ImagePlus imp1, final ImagePlus imp2) {
		return imp1.getCalibration().equals(imp2.getCalibration());
	}

	public static ImagePlus getFrame(final ImagePlus imp, final int frame) {
		imp.deleteRoi(); // will call saveRoi
		final ImagePlus extracted = new Duplicator().run(imp, 1, imp.getNChannels(), 1, imp.getNSlices(), frame, frame);
		extracted.setCalibration(imp.getCalibration());
		imp.restoreRoi();
		extracted.setRoi(imp.getRoi());
		extracted.setProp("extracted-frame", frame);
		return extracted;
	}
	
	public static ImagePlus getChannel(final ImagePlus imp, final int channel) {
		imp.deleteRoi(); // will call saveRoi
		final ImagePlus extracted = new Duplicator().run(imp, channel, channel, 1, imp.getNSlices(), 1, imp.getNFrames());
		extracted.setCalibration(imp.getCalibration());
		imp.restoreRoi();
		extracted.setRoi(imp.getRoi());
		extracted.setProp("extracted-channel", channel);
		return extracted;
	}

	public static void removeSlices(final ImageStack stack, Collection<String> labels) {
		int count = 0;
		for (int i = 1; i <= stack.size(); i++) {
			if ((i - count) > stack.getSize())
				break;
			if (labels.contains(stack.getSliceLabel(i))) {
				stack.deleteSlice(i - count);
				count++;
			}
		}
	}

	public static double[] getMinMax(final ImagePlus imp) {
		final ImageStatistics imgStats = imp.getStatistics(ImageStatistics.MIN_MAX);
		return new double[] { imgStats.min, imgStats.max };
	}

	public static List<String> getSliceLabels(final ImageStack stack) {
		final List<String> set = new ArrayList<>();
		for (int slice = 1; slice <= stack.size(); slice++) {
			final String label = stack.getSliceLabel(slice);
			if (label != null)
				set.add(label);
		}
		return set;
	}

	public static Dataset toDataset(final ImagePlus imp) {
		final ConvertService convertService = SNTUtils.getContext().getService(ConvertService.class);
		return convertService.convert(imp, Dataset.class);
	}


	/* see net.imagej.legacy.translate.ColorTableHarmonizer */
	public static void applyColorTable(final ImagePlus imp, final ColorTable cTable) {
		final byte[] reds = new byte[256];
		final byte[] greens = new byte[256];
		final byte[] blues = new byte[256];
		for (int i = 0; i < 256; i++) {
			reds[i] = (byte) cTable.getResampled(ColorTable.RED, 256, i);
			greens[i] = (byte) cTable.getResampled(ColorTable.GREEN, 256, i);
			blues[i] = (byte) cTable.getResampled(ColorTable.BLUE, 256, i);
		}
		imp.setLut(new LUT(reds, greens, blues));
	}

	public static void setLut(final ImagePlus imp, final String lutName) {
		final IndexColorModel model = ij.plugin.LutLoader.getLut(lutName);
		if (model != null) {
			imp.getProcessor().setColorModel(model);
		} else {
			SNTUtils.error("LUT not found: " + lutName);
		}
	}

	public static ImagePlus create(final String title, final int width, final int height, final int depth,
			final int bitDepth) {
		return ij.IJ.createImage(title, width, height, depth, bitDepth);
	}

	public static boolean isBinary(final ImagePlus imp) {
		 return imp != null && imp.getProcessor() != null && imp.getProcessor().isBinary();
	}

	public static boolean isVirtualStack(final ImagePlus imp) {
		 return imp != null && imp.getStack() != null && imp.getStack().size() > 1 && imp.getStack().isVirtual();
	}

	public static ImagePlus combineSkeletons(final Collection<Tree> trees) {
		final List<ImagePlus> imps = new ArrayList<>(trees.size());
		final int[]  v = {1};
		trees.forEach( tree -> imps.add(tree.getSkeleton2D(v[0]++)));
		final ImagePlus imp = ImpUtils.getMIP(imps);
		imp.setTitle("Skeletonized Trees");
		ColorMaps.applyMagmaColorMap(imp, 128, false);
		return imp;
	}

	public static ImagePlus combineSkeletons(final Collection<Tree> trees, final boolean asMontage) {
		if (!asMontage) return combineSkeletons(trees);
		final List<ImagePlus> imps = new ArrayList<>(trees.size());
		trees.forEach( tree -> imps.add(tree.getSkeleton2D()));
		ImagePlus imp = ImagesToStack.run(imps.toArray(new ImagePlus[0]));
		int gridCols, gridRows;
		if (trees.size() < 11) {
			gridCols = trees.size();
			gridRows = 1;
		} else {
			gridCols = (int)Math.sqrt(trees.size());
			gridRows = gridCols;
			int n = trees.size() - gridCols*gridRows;
			if (n>0) gridCols += (int)Math.ceil((double)n/gridRows);
		}
		imp = new MontageMaker().makeMontage2(imp, gridCols, gridRows, 1, 1, trees.size(), 1, 0, true);
		imp.setTitle("Skeletonized Trees");
		return imp;
	}

	/**
	 * Returns one of the demo images bundled with SNT image associated with the
	 * demo (fractal) tree.
	 *
	 * @param img a string describing the type of demo image. Options include:
	 *            'fractal' for the L-system toy neuron; 'ddaC' for the C4 ddaC
	 *            drosophila neuron (demo image initially distributed with the Sholl
	 *            plugin); 'OP1'/'OP_1' for the DIADEM OP_1 dataset; 'cil701' and
	 *            'cil810' for the respective Cell Image Library entries, and
	 *            'binary timelapse' for a small 4-frame sequence of neurite growth
	 * @return the demo image, or null if data could no be retrieved
     */
	public static ImagePlus demo(final String img) {
		if (img == null)
			throw new IllegalArgumentException("demoImage(): argument cannot be null");
		final String nImg = img.toLowerCase().trim();
		if (nImg.contains("fractal") || nImg.contains("tree")) {
			return demoImageInternal("tests/TreeV.tif", "TreeV.tif");
		} else if (nImg.contains("dda") || nImg.contains("c4") || nImg.contains("sholl")) {
			return demoImageInternal("tests/ddaC.tif", "Drosophila_ddaC_Neuron.tif");
		} else if (nImg.contains("op")) {
			return ij.IJ.openImage(
					"https://github.com/morphonets/SNT/raw/0b3451b8e62464a270c9aab372b4f651c4cf9af7/src/test/resources/OP_1.tif");
		} else if (nImg.equalsIgnoreCase("rat_hippocampal_neuron") || (nImg.contains("hip") && nImg.contains("multichannel"))) {
			return ij.IJ.openImage("http://wsr.imagej.net/images/Rat_Hippocampal_Neuron.zip");
		} else if (nImg.contains("4d") || nImg.contains("701")) {
			return cil701();
		} else if (nImg.contains("multipolar") || nImg.contains("810")) {
			return cil810();
		} else if (nImg.contains("timelapse")) {
			return (!nImg.contains("binary")) ? cil701()
					: ij.IJ.openImage(
							"https://github.com/morphonets/misc/raw/00369266e14f1a1ff333f99f0f72ef64077270da/dataset-demos/timelapse-binary-demo.zip");
		}
		throw new IllegalArgumentException("Not a recognized demoImage argument: " + img);
	}

	public static ImagePlus getCurrentImage() {
		// FIXME: Requiring LegacyService() to access the current image is problematic due to
		// initialization problems in SNTService and SNTUtils, so I'll use this as a workaround
		return ij.WindowManager.getCurrentImage();
	}
	private static ImagePlus demoImageInternal(final String path, final String displayTitle) {
		final ClassLoader classloader = Thread.currentThread().getContextClassLoader();
		final InputStream is = classloader.getResourceAsStream(path);
		final boolean redirecting = IJ.redirectingErrorMessages();
		IJ.redirectErrorMessages(true);
		final ImagePlus imp = new Opener().openTiff(is, displayTitle);
		IJ.redirectErrorMessages(redirecting);
		return imp;
	}

	private static ImagePlus cil810() {
		ImagePlus imp = IJ.openImage("https://cildata.crbs.ucsd.edu/media/images/810/810.tif");
		if (imp != null) {
			imp.setDimensions(imp.getNSlices(), 1, 1);
			imp.getStack().setSliceLabel("N-cadherin", 1);
			imp.getStack().setSliceLabel("V-glut 1/2", 2);
			imp.getStack().setSliceLabel("NMDAR", 3);
			imp.getCalibration().setUnit("um");
			imp.getCalibration().pixelWidth = 0.113;
			imp.getCalibration().pixelHeight = 0.113;
			imp.setTitle("CIL_Dataset_#810.tif");
			imp = new ij.CompositeImage(imp, ij.CompositeImage.COMPOSITE);
		}
		return imp;
	}

	private static ImagePlus cil701() {
		final ImagePlus imp = IJ.openImage("https://cildata.crbs.ucsd.edu/media/images/701/701.tif");
		if (imp != null) {
			imp.setDimensions(1, 1, imp.getNSlices());
			imp.getCalibration().setUnit("um");
			imp.getCalibration().pixelWidth = 0.169;
			imp.getCalibration().pixelHeight = 0.169;
			imp.getCalibration().frameInterval = 3000;
			imp.getCalibration().setTimeUnit("s");
			imp.setTitle("CIL_Dataset_#701.tif");
		}
		return imp;
	}

}
