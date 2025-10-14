/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2025 Fiji developers.
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

import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.ImageCanvas;
import ij.gui.Roi;
import ij.io.Opener;
import ij.plugin.*;
import ij.process.*;
import net.imagej.Dataset;
import net.imagej.ops.OpService;
import net.imglib2.display.ColorTable;
import org.scijava.convert.ConvertService;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.RoiConverter;

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

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

	public static ImagePlus open(final String filePathOrUrl) {
		return open(filePathOrUrl, null);
	}

	public static ImagePlus open(final File file, final String title) {
		return open(file.getAbsolutePath(), title);
	}

	public static ImagePlus open(final String filePathOrUrl, final String title) {
		final boolean redirecting = IJ.redirectingErrorMessages();
		IJ.redirectErrorMessages(true);
		final ImagePlus imp = IJ.openImage(filePathOrUrl);
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

	public static ImagePlus getCT(final ImagePlus imp, final int channel, final int frame) {
		imp.deleteRoi(); // will call saveRoi
		final ImagePlus extracted = new Duplicator().run(imp, channel, channel, 1, imp.getNSlices(), frame, frame);
		extracted.setCalibration(imp.getCalibration());
		imp.restoreRoi();
		extracted.setRoi(imp.getRoi());
		extracted.setProp("extracted-channel", channel);
		extracted.setProp("extracted-frame", frame);
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
		ColorMaps.applyPlasma(imp, 128, false);
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
	 * Retrieves an ImagePlus from the system clipboard
	 *
	 * @param asMultiChannel if true and clipboard contains RGB data image is returned as composite (RGB/8-bit grayscale otherwise)
	 * @return the image stored in the system clipboard or null if no image found
	 */
	public static ImagePlus getSystemClipboard(final boolean asMultiChannel) {
		try {
			final Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
			final Transferable transferable = clipboard.getContents(null);
			if (transferable != null && transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) {
				final Image img = (Image) transferable.getTransferData(DataFlavor.imageFlavor);
				final int width = img.getWidth(null);
				final int height = img.getHeight(null);
				final BufferedImage bi = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
				final Graphics g = bi.createGraphics();
				g.drawImage(img, 0, 0, null);
				g.dispose();
				final ImagePlus result = new ImagePlus("Clipboard", bi);
				final boolean isGray = isGrayscale(bi, width, height);
				if (isGray) convertTo8bit(result);
				return (asMultiChannel && !isGray) ? convertRGBtoComposite(result) : result;
			}
		} catch (final Throwable e) {
			SNTUtils.error("No image in clipboard", e);
		}
		return null;
	}

	private static boolean isGrayscale(final BufferedImage image, final int width, final int height) {
		for (int i = 0; i < width; i++) { // https://stackoverflow.com/a/36173328
			for (int j = 0; j < height; j++) {
				final int pixel = image.getRGB(i, j);
				final int red = (pixel >> 16) & 0xff;
				final int green = (pixel >> 8) & 0xff;
				if (red != green || green != ((pixel) & 0xff)) return false;
			}
		}
		return true;
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
			return open("https://github.com/morphonets/SNT/raw/0b3451b8e62464a270c9aab372b4f651c4cf9af7/src/test/resources/OP_1.tif", null);
		} else if (nImg.equalsIgnoreCase("rat_hippocampal_neuron") || (nImg.contains("hip") && nImg.contains("multichannel"))) {
			return open("http://wsr.imagej.net/images/Rat_Hippocampal_Neuron.zip", null);
		} else if (nImg.contains("4d") || nImg.contains("701")) {
			return cil701();
		} else if (nImg.contains("multipolar") || nImg.contains("810")) {
			return cil810();
		} else if (nImg.contains("timelapse")) {
			return (!nImg.contains("binary")) ? cil701()
					: open("https://github.com/morphonets/misc/raw/00369266e14f1a1ff333f99f0f72ef64077270da/dataset-demos/timelapse-binary-demo.zip", null);
		}
		throw new IllegalArgumentException("Not a recognized demoImage argument: " + img);
	}

	public static ImagePlus getCurrentImage() {
		// FIXME: Requiring LegacyService() to access the current image is problematic due to
		// initialization problems in SNTService and SNTUtils, so I'll use this as a workaround
		return ij.WindowManager.getCurrentImage();
	}

	public static void zoomTo(final ImagePlus imp, final double zoomMagnification, final int x, final int y) {
		final ImageCanvas canvas = imp.getCanvas();
		final double currentMag = imp.getCanvas().getMagnification();
		if (currentMag < zoomMagnification) {
			// Zoom in on location. This will likely resize the ImageWindow
			Zoom.set(imp, zoomMagnification, x, y);
		} else if (canvas.getSrcRect().width < imp.getWidth() || canvas.getSrcRect().height < imp.getHeight()) {
			// the image is already zoomed in. Do not resize ImageWindow
			Zoom.set(imp, currentMag, x, y);
		}
	}

    public static double zoomTo(final ImagePlus imp, final Collection<Path> paths) {
        final Roi existingRoi = imp.getRoi();
        final Roi zoomRoi = RoiConverter.get2DBoundingBox(paths, RoiConverter.XY_PLANE);
        imp.setRoi(zoomRoi);
        final double currentMag = imp.getCanvas().getMagnification();
        Zoom.toSelection(imp);
        imp.setPosition(imp.getChannel(), zoomRoi.getZPosition(), imp.getFrame());
        imp.setRoi(existingRoi);
        return imp.getCanvas().getMagnification() - currentMag;
    }

	/**
	 * Checks if a given point (in image coordinates) is currently visible in an image
	 *
	 * @param imp the ImagePlus to check
	 * @param x the x coordinate in image pixels
	 * @param y the y coordinate in image pixels
	 * @return true if the point is visible in the current view, false otherwise
	 */
	public static boolean isPointVisible(final ImagePlus imp, final int x, final int y) {
		final ImageCanvas canvas = imp.getCanvas();
		if (canvas == null) return false;
		// Get the current view bounds in image coordinates. Check if the point is within the visible rectangle
		return canvas.getSrcRect().contains(x, y);
	}

	public static double nextZoomLevel(final double level) {
		return ImageCanvas.getHigherZoomLevel(level);
	}

	public static double previousZoomLevel(final double level) {
		return ImageCanvas.getLowerZoomLevel(level);
	}

	public static String imageTypeToString(final int type) {
		return switch (type) {
			case ImagePlus.GRAY8 -> "GRAY8 (8-bit grayscale (unsigned))";
			case ImagePlus.GRAY16 -> "GRAY16 (16-bit grayscale (unsigned))";
			case ImagePlus.GRAY32 -> "GRAY32 (32-bit floating-point grayscale)";
			case ImagePlus.COLOR_256 -> "COLOR_256 (8-bit indexed color)";
			case ImagePlus.COLOR_RGB -> "COLOR_RGB (32-bit RGB color)";
			default -> "Unknown (value: " + type + ")";
		};
	}

    /**
     * Converts the specified image into ascii art.
     *
     * @param imp The image to be converted to ascii art
     * @param dilate whether the image should be dilated before conversion. Ignored if image is not binary
     * @param width Desired width for the ascii art. Set it to -1 to use image width
     * @param height Desired height for the ascii art. Set it to -1 to use image height
     * @return ascii art
     */
    public static String ascii(final ImagePlus imp, final boolean dilate, final int width, final int height) {
        final ImagePlus imp2 = (imp.getNSlices() > 1) ? getMIP(imp) : imp;
        if (imp2.getProcessor() instanceof ByteProcessor bp && (dilate || imp.getProperty("skel") != null))
            bp.dilate(1, 0);
        final int w = (width > 0) ? width : Math.min(width, imp2.getWidth());
        final int h = (height > 0) ? height : Math.min(height, imp2.getHeight());
        imp2.setProcessor(imp2.getProcessor().resize(w, h));
        return SNTUtils.getContext().getService(OpService.class).image().ascii(ImgUtils.getCtSlice(imp2).view());
    }

	public static void invertLut(final ImagePlus imp) {
		if (imp.getType() == ImagePlus.COLOR_RGB) {
			return;
		}
		if (imp.isComposite()) {
			final CompositeImage ci = (CompositeImage)imp;
			final LUT lut = ci.getChannelLut();
			if (lut!=null) ci.setChannelLut(lut.createInvertedLut());
		} else {
			final ImageProcessor ip = imp.getProcessor();
			ip.invertLut();
			if (imp.getStackSize()>1) imp.getStack().setColorModel(ip.getColorModel());
		}
		imp.updateAndRepaintWindow();
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
		final ImagePlus imp = open("https://cildata.crbs.ucsd.edu/media/images/701/701.tif", null);
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
