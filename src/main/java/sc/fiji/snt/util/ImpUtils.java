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

import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.ImageCanvas;
import ij.gui.ImageWindow;
import ij.gui.Roi;
import ij.io.Opener;
import ij.measure.Calibration;
import ij.plugin.*;
import ij.process.*;
import net.imagej.Dataset;
import net.imagej.ImgPlus;
import net.imagej.axis.Axes;
import net.imagej.axis.CalibratedAxis;
import net.imagej.axis.DefaultLinearAxis;
import net.imagej.ops.OpService;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.display.ColorTable;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.loops.LoopBuilder;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.util.Intervals;
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

    /** Creates ImgPlus axes from IJ1 Calibration */
    public static CalibratedAxis[] calibrationToAxes(final Calibration cal, final int numDimensions) {
        final CalibratedAxis[] axes = new CalibratedAxis[numDimensions];
        if (numDimensions >= 1) {
            axes[0] = new DefaultLinearAxis(Axes.X, cal.getUnit(), cal.pixelWidth);
        }
        if (numDimensions >= 2) {
            axes[1] = new DefaultLinearAxis(Axes.Y, cal.getUnit(), cal.pixelHeight);
        }
        if (numDimensions >= 3) {
            axes[2] = new DefaultLinearAxis(Axes.Z, cal.getUnit(), cal.pixelDepth);
        }
        return axes;
    }

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

    /**
     * Saves the specified image.
     *
     * @param imp      The image to be saved
     * @param filePath The file path should end with ".tif", or one of the supported extensions (".jpg", ".zip", etc.)
     */
    public static void save(final ImagePlus imp, final String filePath) {
        final boolean redirecting = IJ.redirectingErrorMessages();
        IJ.redirectErrorMessages(true);
        IJ.save(imp, filePath);
        IJ.redirectErrorMessages(redirecting);
    }

    /**
     * Returns the file path of an image.
     * @param imp the image to be parsed
     * @return the absolute file path of the image or "unknown" if not known
     */
    public static String getFilePath(final ImagePlus imp) {
        return  (imp != null && (imp.getOriginalFileInfo() != null && imp.getOriginalFileInfo().directory != null))
                ? imp.getOriginalFileInfo().directory + imp.getOriginalFileInfo().fileName
                : "unknown";
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

    /**
     * Binarize an ImagePlus using lower and upper thresholds.
     * Pixels within [lower, upper] become 255 (white), others become 0 (black).
     *
     * @param imp   the image to be binarized
     * @param lower the lower threshold (inclusive)
     * @param upper the upper threshold (inclusive)
     * @throws IllegalArgumentException if image is not grayscale
     */
    public static void binarize(final ImagePlus imp, final double lower, final double upper) {
        if (imp instanceof CompositeImage || imp.getType() == ImagePlus.COLOR_RGB) {
            throw new IllegalArgumentException("Image is not grayscale");
        }

        final ImageStack oldStack = imp.getStack();
        final int width = oldStack.getWidth();
        final int height = oldStack.getHeight();
        final int nPixels = width * height;
        final ImageStack newStack = new ImageStack(width, height);

        for (int z = 1; z <= oldStack.getSize(); z++) {
            final Object pixels = oldStack.getProcessor(z).getPixels();
            final byte[] binary = new byte[nPixels];

            switch (pixels) {
                case byte[] p -> {
                    final int lo = (int) lower, hi = (int) upper;
                    for (int i = 0; i < nPixels; i++) {
                        int v = p[i] & 0xFF;
                        binary[i] = (v >= lo && v <= hi) ? (byte) 255 : 0;
                    }
                }
                case short[] p -> {
                    final int lo = (int) lower, hi = (int) upper;
                    for (int i = 0; i < nPixels; i++) {
                        int v = p[i] & 0xFFFF;
                        binary[i] = (v >= lo && v <= hi) ? (byte) 255 : 0;
                    }
                }
                case float[] p -> {
                    final float lo = (float) lower, hi = (float) upper;
                    for (int i = 0; i < nPixels; i++) {
                        binary[i] = (p[i] >= lo && p[i] <= hi) ? (byte) 255 : 0;
                    }
                }
                case null, default -> {
                    final ImageProcessor ip = oldStack.getProcessor(z);
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                            final float v = ip.getPixelValue(x, y);
                            binary[y * width + x] = (v >= lower && v <= upper) ? (byte) 255 : 0;
                        }
                    }
                }
            }

            final String label = oldStack.getSliceLabel(z);
            newStack.addSlice(label, new ByteProcessor(width, height, binary));
        }
        imp.setStack(newStack); // updates the display
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
        if (canvas == null) return;

        // Save window and canvas size
        final ImageWindow win = imp.getWindow();
        final Dimension windowSize = (win != null) ? win.getSize() : null;
        final Dimension canvasSize = canvas.getSize();

        final double currentMag = canvas.getMagnification();
        if (currentMag < zoomMagnification) {
            // Zoom in on location
            Zoom.set(imp, zoomMagnification, x, y);
        } else if (canvas.getSrcRect().width < imp.getWidth() || canvas.getSrcRect().height < imp.getHeight()) {
            // Image is already zoomed in - just pan to location
            Zoom.set(imp, currentMag, x, y);
        }

        // Restore window size if it shrank
        if (win != null && windowSize != null) {
            final Dimension newSize = win.getSize();
            if (newSize.width < windowSize.width || newSize.height < windowSize.height) {
                win.setSize(windowSize);
                canvas.setSize(canvasSize);

                // Manually set source rectangle to center on point
                final double mag = canvas.getMagnification();
                int srcWidth = (int) (canvasSize.width / mag);
                int srcHeight = (int) (canvasSize.height / mag);
                int srcX = x - srcWidth / 2;
                int srcY = y - srcHeight / 2;

                // Clamp to image bounds
                srcX = Math.max(0, Math.min(srcX, imp.getWidth() - srcWidth));
                srcY = Math.max(0, Math.min(srcY, imp.getHeight() - srcHeight));

                Rectangle newSrcRect = new Rectangle(srcX, srcY, srcWidth, srcHeight);
                canvas.setSourceRect(newSrcRect);
                canvas.setMagnification(mag);
                canvas.repaint();
                imp.updateAndDraw();
            }
        }
    }

    /**
     * Zooms the image canvas to optimally display the specified paths in the XY plane.
     * <p>
     * This is a convenience method equivalent to calling
     * {@link #zoomTo(ImagePlus, Collection, int)} with {@code RoiConverter.XY_PLANE}.
     *
     * @param imp   the ImagePlus whose canvas should be adjusted
     * @param paths the collection of paths to zoom to
     * @return the resulting magnification level, or 0 if no zoom was performed
     * @see #zoomTo(ImagePlus, Collection, int)
     */
    public static double zoomTo(final ImagePlus imp, final Collection<Path> paths) {
        return zoomTo(imp, paths, RoiConverter.XY_PLANE);
    }

    /**
     * Zooms the image canvas to optimally display the specified paths in the given plane.
     * <p>
     * The zoom behavior depends on the relationship between the paths' bounding box
     * and the current visible area:
     * <ul>
     *   <li><b>Zoom in:</b> When the bounding box is fully visible but occupies less than
     *       25% of the visible area, the view zooms in to better frame the paths.</li>
     *   <li><b>Zoom out:</b> When the bounding box extends beyond the visible area
     *       (paths partially or fully outside view), the view zooms out to show all paths.</li>
     *   <li><b>No change (but re-center):</b> When the bounding box already fills more than
     *       25% of the visible area, no zoom adjustment is made but the view is centered
     *       on the bounding box.</li>
     * </ul>
     *
     * @param imp   the ImagePlus whose canvas should be adjusted
     * @param paths the collection of paths to zoom to
     * @param plane the viewing plane (RoiConverter.XY_PLANE, ZY_PLANE, or XZ_PLANE)
     * @return the resulting magnification level, or 0 if no zoom was performed
     *         (e.g., canvas is null)
     */
    public static double zoomTo(final ImagePlus imp, final Collection<Path> paths, final int plane) {
        final ImageCanvas canvas = imp.getCanvas();
        if (canvas == null) return 0;

        final Roi existingRoi = imp.getRoi();
        final Roi zoomRoi = RoiConverter.get2DBoundingBox(paths, plane);
        final Rectangle roiBounds = zoomRoi.getBounds();
        final Rectangle visibleRect = canvas.getSrcRect();
        final double currentMag = canvas.getMagnification();

        // Get window and canvas size - this is what we want to preserve
        final ImageWindow win = imp.getWindow();
        final Dimension windowSize = (win != null) ? win.getSize() : null;
        final Dimension canvasSize = canvas.getSize();

        // Calculate bounding box center
        final int centerX = roiBounds.x + roiBounds.width / 2;
        final int centerY = roiBounds.y + roiBounds.height / 2;

        final boolean contained = visibleRect.contains(roiBounds);

        double resultMag;

        if (contained) {
            // ROI is fully visible - check if we should zoom in or just center
            double roiArea = roiBounds.getWidth() * roiBounds.getHeight();
            double visibleArea = visibleRect.getWidth() * visibleRect.getHeight();
            double fillRatio = roiArea / visibleArea;

            if (fillRatio < 0.25) {
                // ROI is very small in view - zoom in to frame it better
                imp.setRoi(zoomRoi);
                Zoom.toSelection(imp);
                imp.setRoi(existingRoi);
                final double newMag = canvas.getMagnification();
                // But never zoom out
                if (newMag < currentMag) {
                    Zoom.set(imp, currentMag, centerX, centerY);
                    resultMag = currentMag;
                } else {
                    resultMag = newMag;
                }
            } else {
                // ROI fills enough of the view - just center without zooming
                Zoom.set(imp, currentMag, centerX, centerY);
                resultMag = currentMag;
            }
        } else {
            // ROI extends beyond visible area - calculate minimum zoom to fit it
            int canvasScreenWidth = canvasSize.width;
            int canvasScreenHeight = canvasSize.height;

            // Magnification needed to fit ROI in canvas (with a small margin)
            final double margin = 0.95;
            double magToFit = Math.min(
                    (canvasScreenWidth * margin) / roiBounds.width,
                    (canvasScreenHeight * margin) / roiBounds.height
            );

            // Apply zoom
            Zoom.set(imp, magToFit, centerX, centerY);
            resultMag = canvas.getMagnification();

            // Restore window size if it shrank
            if (win != null && windowSize != null) {
                final Dimension newWinSize = win.getSize();
                if (newWinSize.width < windowSize.width || newWinSize.height < windowSize.height) {
                    win.setSize(windowSize);
                    canvas.setSize(canvasSize);

                    // Manually set source rectangle to center on ROI
                    int srcWidth = (int) (canvasSize.width / resultMag);
                    int srcHeight = (int) (canvasSize.height / resultMag);
                    int srcX = centerX - srcWidth / 2;
                    int srcY = centerY - srcHeight / 2;

                    // Clamp to image bounds
                    srcX = Math.max(0, Math.min(srcX, imp.getWidth() - srcWidth));
                    srcY = Math.max(0, Math.min(srcY, imp.getHeight() - srcHeight));

                    Rectangle newSrcRect = new Rectangle(srcX, srcY, srcWidth, srcHeight);
                    canvas.setSourceRect(newSrcRect);
                    canvas.setMagnification(resultMag);
                    canvas.repaint();
                    imp.updateAndDraw();
                }
            }
        }

        return resultMag;
    }

    /**
     * Zooms the image canvas to the specified magnification level, centered on the
     * bounding box of the given paths.
     *
     * @param imp       the ImagePlus whose canvas should be adjusted
     * @param zoomLevel the exact magnification level to apply
     * @param paths     the collection of paths to center on
     * @param plane     the viewing plane (RoiConverter.XY_PLANE, ZY_PLANE, or XZ_PLANE)
     */
    public static void zoomTo(final ImagePlus imp, final double zoomLevel, final Collection<Path> paths, final int plane) {
        final ImageCanvas canvas = imp.getCanvas();
        if (canvas == null) return;

        // Save window and canvas size
        final ImageWindow win = imp.getWindow();
        final Dimension windowSize = (win != null) ? win.getSize() : null;
        final Dimension canvasSize = canvas.getSize();

        final Roi zoomRoi = RoiConverter.get2DBoundingBox(paths, plane);
        final Rectangle roiBounds = zoomRoi.getBounds();
        final int centerX = roiBounds.x + roiBounds.width / 2;
        final int centerY = roiBounds.y + roiBounds.height / 2;

        Zoom.set(imp, zoomLevel, centerX, centerY);

        // Restore window size if it shrank
        if (win != null && windowSize != null) {
            final Dimension newSize = win.getSize();
            if (newSize.width < windowSize.width || newSize.height < windowSize.height) {
                win.setSize(windowSize);
                canvas.setSize(canvasSize);

                // Manually set source rectangle to center on ROI
                int srcWidth = (int) (canvasSize.width / zoomLevel);
                int srcHeight = (int) (canvasSize.height / zoomLevel);
                int srcX = centerX - srcWidth / 2;
                int srcY = centerY - srcHeight / 2;

                // Clamp to image bounds
                srcX = Math.max(0, Math.min(srcX, imp.getWidth() - srcWidth));
                srcY = Math.max(0, Math.min(srcY, imp.getHeight() - srcHeight));

                Rectangle newSrcRect = new Rectangle(srcX, srcY, srcWidth, srcHeight);
                canvas.setSourceRect(newSrcRect);
                canvas.setMagnification(zoomLevel);
                canvas.repaint();
                imp.updateAndDraw();
            }
        }
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
        final int w = (width > 0) ? width : imp2.getWidth();
        final int h = (height > 0) ? height : imp2.getHeight();
        imp2.setProcessor(imp2.getProcessor().resize(w, h));
        return SNTUtils.getContext().getService(OpService.class).image().ascii(ImgUtils.getCtSlice(imp2).view());
    }

    /**
     * Converts the specified image into an easy displayable form, i.e., a non-composite 2D image
     * If the image is a timelapse, only the first frame is considered; if 3D, a MIP is retrieved;
     * if multichannel a RGB version is obtained. The image is flattened if its Overlay has ROIs.
     *
     * @param imp The image to be converted
     * @param frame The frame to be considered (ignored if image is not a timelapse)
     * @return a 2D 'flattened' version of the image
     */
    public static ImagePlus convertToSimple2D(final ImagePlus imp, final int frame) {
        ImagePlus imp2;
        // Handle time series
        if (imp.getNFrames() > 1) {
            final int f = Math.max(1, Math.min(frame, imp.getNFrames()));
            imp2 = new Duplicator().run(imp, 1, imp.getNChannels(), 1, imp.getNSlices(), f, f);
            if (imp2.getNSlices() > 1)
                imp2 = getMIP(imp2);
        }
        // Handle Z-stacks
        else if (imp.getNSlices() > 1) {
            imp2 = getMIP(imp);
        }
        // Single slice image
        else {
            imp2 = imp;
        }
        // Convert composite to RGB
        if (imp2 instanceof CompositeImage) {
            new ImageConverter(imp2).convertToRGB();
        }
        // Flatten overlay if present
        if (imp.getOverlay() != null && imp.getOverlay().size() > 1) {
            imp2.setOverlay(imp.getOverlay());
            imp2 = imp2.flatten();
        }
        return imp2;
    }

    /**
     * Crops the image around non-background values. Does nothing if the image does not have
     * non-background values.
     *
     * @param imp             The image to be cropped
     * @param backgroundValue the background value typically 'black': 0 for 8-/16bit, 0x000000 for RGB),
     *                        or white (255 for 8-bit, 65535 for 16-bir, 0xFFFFFF for RGB)
     */
    public static void crop(final ImagePlus imp, final Number backgroundValue) {
        final Roi cropRoi = getForegroundRect(imp, backgroundValue);
        if (cropRoi != null) {
            final Roi existingRoi = imp.getRoi();
            imp.setRoi(cropRoi);
            imp.setStack(imp.crop().getStack());
            imp.setRoi(existingRoi);
        }
    }

    /**
     * Rotates an image 90 degrees.
     * @param imp the image to be rotated
     * @param direction either 'left' or 'right'
     */
    public static void rotate90(final ImagePlus imp, final String direction) {
        switch(direction.toLowerCase()) {
            case "left" -> ij.IJ.run(imp, "Rotate 90 Degrees Left", "");
            case "right" -> ij.IJ.run(imp, "Rotate 90 Degrees Right", "");
        }
    }

    /**
     * Returns the cropping rectangle around non-background values.
     *
     * @param imp             The image to be parsed
     * @param backgroundValue the background value typically 'black': 0, or white (255 for 8-bit/RGB,
     *                        or 65535 for 16-bit)
     * @return the rectangular ROI defining non-background bounds
     */
    public static Roi getForegroundRect(final ImagePlus imp, final Number backgroundValue) {
        final ImageProcessor ip = imp.getProcessor();
        int w = ip.getWidth(), h = ip.getHeight();
        int minX = w, maxX = 0, minY = h, maxY = 0;
        if (imp.getType() == ImagePlus.GRAY32) {
            float bgVal = backgroundValue.floatValue();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (Math.abs(ip.getPixelValue(x, y) - bgVal) > 0.0001f) {
                        if (x < minX) minX = x;
                        if (x > maxX) maxX = x;
                        if (y < minY) minY = y;
                        if (y > maxY) maxY = y;
                    }
                }
            }
        } else {
            int bgVal = backgroundValue.intValue();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (ip.getPixelValue(x, y) != bgVal) {
                        if (x < minX) minX = x;
                        if (x > maxX) maxX = x;
                        if (y < minY) minY = y;
                        if (y > maxY) maxY = y;
                    }
                }
            }
        }
        // Return null if all pixels were background
        return (minX > maxX) ? null : new Roi(minX, minY, maxX - minX + 1, maxY - minY + 1);
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
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        classLoader = (classLoader != null) ? classLoader : ImpUtils.class.getClassLoader();
        final InputStream is = classLoader.getResourceAsStream(path);
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

    /**
     * Convert an ImagePlus to an ImgPlus with calibration and origin metadata.
     * <p>
     * Creates an ImgPlus with proper axis types (X, Y, Z, Channel, Time) and
     * transfers calibration including pixel sizes, units, and origin offsets.
     * </p>
     *
     * @param imp the source ImagePlus
     * @return ImgPlus with calibrated axes
     */
    public static <T extends RealType<T> & NativeType<T>> ImgPlus<T> toImgPlus(final ImagePlus imp) {
        // Wrap ImagePlus as RAI
        final RandomAccessibleInterval<T> rai = ImageJFunctions.wrapReal(imp);

        // Create contiguous copy to avoid virtual stack issues
        final long[] dims = Intervals.dimensionsAsLongArray(rai);
        final Img<T> img = copyToArrayImg(rai);

        // Build axes from calibration
        final Calibration cal = imp.getCalibration();
        final String unit = cal.getUnit();
        final List<CalibratedAxis> axes = new ArrayList<>();

        // ImageJFunctions.wrap() produces XY(C)(Z)(T) order for ImagePlus
        // but actual order depends on ImagePlus dimensions
        final int nChannels = imp.getNChannels();
        final int nSlices = imp.getNSlices();
        final int nFrames = imp.getNFrames();

        // X axis (always present)
        axes.add(new DefaultLinearAxis(Axes.X, unit, cal.pixelWidth, cal.xOrigin));

        // Y axis (always present)
        axes.add(new DefaultLinearAxis(Axes.Y, unit, cal.pixelHeight, cal.yOrigin));

        // Remaining axes depend on stack organization
        // ImageJFunctions wraps as [X, Y, ...] where ... follows IJ's dimension order
        if (nChannels > 1 && nSlices == 1 && nFrames == 1) {
            // Just channels
            axes.add(new DefaultLinearAxis(Axes.CHANNEL, "channel", 1.0, 0));
        } else if (nChannels == 1 && nSlices > 1 && nFrames == 1) {
            // Just Z
            axes.add(new DefaultLinearAxis(Axes.Z, unit, cal.pixelDepth, cal.zOrigin));
        } else if (nChannels == 1 && nSlices == 1 && nFrames > 1) {
            // Just time
            axes.add(new DefaultLinearAxis(Axes.TIME, cal.getTimeUnit(), cal.frameInterval, 0));
        } else if (nChannels > 1 || nSlices > 1 || nFrames > 1) {
            // Multiple dimensions - ImageJ orders as XYCZT
            if (nChannels > 1) {
                axes.add(new DefaultLinearAxis(Axes.CHANNEL, "channel", 1.0, 0));
            }
            if (nSlices > 1) {
                axes.add(new DefaultLinearAxis(Axes.Z, unit, cal.pixelDepth, cal.zOrigin));
            }
            if (nFrames > 1) {
                axes.add(new DefaultLinearAxis(Axes.TIME, cal.getTimeUnit(), cal.frameInterval, 0));
            }
        }

        // Create ImgPlus with axes
        return new ImgPlus<>(img,
                imp.getTitle() == null ? "image" : imp.getTitle(),
                axes.toArray(new CalibratedAxis[0]));
    }

    /**
     * Convert an ImagePlus to a 3D (XYZ) ImgPlus, extracting a single channel/frame if needed.
     * <p>
     * Useful for analysis that expects simple 3D images without channel/time dimensions.
     * </p>
     *
     * @param imp     the source ImagePlus
     * @param channel channel to extract (1-based), ignored if single channel
     * @param frame   frame to extract (1-based), ignored if single frame
     * @return 3D ImgPlus with X, Y, Z axes
     */
    public static <T extends RealType<T> & NativeType<T>> ImgPlus<T> toImgPlus3D(
            final ImagePlus imp,
            final int channel,
            final int frame) {
        // Extract single channel/frame if needed
        ImagePlus imp3D = imp;
        if (imp.getNChannels() > 1 || imp.getNFrames() > 1) {
            final int c = Math.max(1, Math.min(channel, imp.getNChannels()));
            final int f = Math.max(1, Math.min(frame, imp.getNFrames()));
            imp3D = new Duplicator().run(imp, c, c, 1, imp.getNSlices(), f, f);
            imp3D.setCalibration(imp.getCalibration());
        }
        return toImgPlus(imp3D);
    }

    private static <T extends RealType<T> & NativeType<T>> Img<T> copyToArrayImg(final RandomAccessibleInterval<T> rai) {
        final ArrayImgFactory<T> factory = new ArrayImgFactory<>(rai.getType());
        final Img<T> copy = factory.create(rai);
        LoopBuilder.setImages(rai, copy).forEachPixel((src, dst) -> dst.set(src));
        return copy;
    }
}
