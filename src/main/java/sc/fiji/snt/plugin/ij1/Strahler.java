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
package sc.fiji.snt.plugin.ij1;

import ij.*;
import ij.gui.DialogListener;
import ij.gui.GenericDialog;
import ij.gui.ImageCanvas;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.plugin.PlugIn;
import ij.plugin.ZProjector;
import ij.process.ImageProcessor;
import ij.text.TextWindow;
import sc.fiji.analyzeSkeleton.AnalyzeSkeleton_;
import sc.fiji.analyzeSkeleton.Point;
import sc.fiji.analyzeSkeleton.SkeletonResult;
import sc.fiji.skeletonize3D.Skeletonize3D_;
import sc.fiji.snt.gui.EnhancedGenericDialog;
import sc.fiji.snt.util.ColorMaps;
import sc.fiji.snt.util.ImpUtils;
import sc.fiji.snt.util.LSystemsTree;

import java.awt.*;
import java.util.ArrayList;
import java.util.ListIterator;
import java.util.Vector;


/**
 * This class implements the ImageJ {@code Strahler Analysis} plugin. For more
 * information, visit the plugin's documentation page:
 * {@literal https://imagej.net/plugins/snt/strahler}
 *
 * @author Tiago Ferreira
 */
@SuppressWarnings("deprecation")
public class Strahler implements PlugIn, DialogListener {

	protected static final String URL = "https://imagej.net/plugins/snt/strahler";

	/* Default value for max. number of pruning cycles */
	int maxOrder = 30;

	/* Default option for loop detection */
	private int pruneChoice = AnalyzeSkeleton_.SHORTEST_BRANCH;

	/* Default option for 'root-protection' ROI */
	private boolean protectRoot = true;

	/* Default option for 'iteration-stack' output */
	private boolean outIS = false;

	private /* Default option for verbose mode */
	boolean verbose = true;

	/* Default option for tabular option */
	private boolean tabular = false;

	/* Remove isolated pixels from thinned images? */
	private boolean erodeIsolatedPixels = true;

	/* Title of main results window */
	private static final String STRAHLER_TABLE = "Strahler_Table";

	/* Title of detailed results window */
	private static final String VERBOSE_TABLE = "Strahler_Iteration_Log";

	/*
	 * Grayscale image for intensity-based pruning of skel. loops. While it is
	 * unlikely that the iterative pruning of terminal branches will cause new
	 * loops on pre-existing skeletons, offering the option to resolve loops
	 * with intensity based methods remains useful specially when analyzing
	 * non-thinned grayscale images.
	 */
	ImagePlus grayscaleImp = null;
	int grayscaleImpChoice;

	ImagePlus srcImp; // Image to be analyzed (we'll be working on a copy)
	boolean validRootRoi; // Flag assessing validity of 'root-protective' ROI
	String title; // Title of active image
	Roi rootRoi; // Reference to the "root-protecting" ROI
	ImageProcessor ip;

	/** IDE Debug method */
	public static void main(final String[] args) {

		new ImageJ(); // start ImageJ
		final ImagePlus imp = new LSystemsTree().createTreeStack("DebugImp");
		imp.setRoi(330, 510, 60, 50);
		imp.show();
		IJ.runPlugIn(imp, "ipnat.skel.Strahler", null);
		WindowManager.addWindow(imp.getWindow());
	}

	/**
	 * This method is called when the plugin is loaded.
	 *
	 * @param arg
	 *            the arguments as specified in {@code plugins.config}
	 *
	 */
	@Override
	public void run(final String arg) {

		// Retrieve analysis image and its ROI
		srcImp = WindowManager.getCurrentImage();
		if (!validRequirements(srcImp))
			return;

		title = srcImp.getTitle();
		rootRoi = srcImp.getRoi();
		validRootRoi = (rootRoi != null && rootRoi.getType() == Roi.RECTANGLE);

		// TODO: 3D Roots are special. We need to:
		// 1) Check if ROI is associated with all slices or just one
		// 2) Ignore counts above/below the ROI, as needed
		// 3) Extend ip operations to stack
		if (srcImp.getNSlices() > 1) {
			final String warning = "3D images are currently supported with the following limitations:\n"
					+ "    - 'Root-protecting' ROIs are not yet supported\n"
					+ "    - Lengths are estimated from Z-projections";
			if (IJ.macroRunning())
				IJ.log(warning);
			else
				IJ.showMessage("Warning", warning);
			validRootRoi = false;
		}

		// Retrieve grayscale image for intensity-based pruning of skel. loops
		if (!getSettings())
			return;

		// Work on a skeletonized copy since we'll be modifing the image
		if (rootRoi != null)
			srcImp.killRoi();
		final ImagePlus imp = srcImp.duplicate();
		if (rootRoi != null)
			srcImp.setRoi(rootRoi);
		ip = imp.getProcessor();
		skeletonizeWithoutHermits(imp);

		// Initialize ResultsTable: main and detailed info
		final ResultsTable rt = getTable(STRAHLER_TABLE);
		final ResultsTable logrt = getTable(VERBOSE_TABLE);

		// Analyze root
		ImagePlus rootImp;
		ImageProcessor rootIp = null;
		SkeletonResult rootResult = null;
		ArrayList<Point> rootEndpointsList = null;
		int nRootEndpoints = 0, nRootJunctions = 0;

		if (validRootRoi && verbose) {

			// Duplicate entire canvas. Ignore tree(s) outside ROI
			rootImp = imp.duplicate();
			rootIp = rootImp.getProcessor();
			rootIp.setValue(0.0);
			rootIp.fillOutside(rootRoi);

			// Get root properties
			final AnalyzeSkeleton_ root = new AnalyzeSkeleton_();
			root.setup("", rootImp);
			rootResult = root.run(pruneChoice, false, false, grayscaleImp, true, false);
			rootImp.flush();

			// We assume ROI contains only end-point branches, slab voxels and
			// no junction points. We'll thus remove end-points at ROI
			// boundaries
			nRootJunctions = sum(rootResult.getJunctions());
			rootEndpointsList = rootResult.getListOfEndPoints();
			final ListIterator<Point> it = rootEndpointsList.listIterator();
			final Rectangle r = rootRoi.getBounds();
			while (it.hasNext()) {
				final Point p = it.next();
				if (p.x == r.x || p.y == r.y || p.x == (int) (r.x + r.getWidth() - 1)
						|| p.y == (int) (r.y + r.getHeight() - 1))
					it.remove();
			}
			rootResult.setListOfEndPoints(rootEndpointsList);
			nRootEndpoints = rootEndpointsList.size();

		}

		// Initialize display images. Use Z-projections to populate
		// iteration stack when dealing with 3D skeletons
		final int nSlices = imp.getNSlices();
		ZProjector zp = null;

		final ImageStack iterationStack = new ImageStack(imp.getWidth(), imp.getHeight());
		if (nSlices > 1) {
			zp = new ZProjector(imp);
			zp.setMethod(ZProjector.MAX_METHOD);
			zp.setStartSlice(1);
			zp.setStopSlice(nSlices);
		}

		// Initialize AnalyzeSkeleton_
		final AnalyzeSkeleton_ as = new AnalyzeSkeleton_();
		as.setup("", imp);

		// Perform the iterative pruning
		int order = 1, nEndpoints = 0, nJunctions = 0, nJunctions2 = 0;
		ArrayList<Point> endpointsList = null, junctionsList = null;
		String errorMsg = "";

		do {

			IJ.showStatus("Retrieving measurements for order " + order + "...");
			IJ.showProgress(order, getMaxOrder());

			// (Re)skeletonize image
			if (order > 1)
				skeletonizeWithoutHermits(imp);

			// Get properties of loop-resolved tree(s)
			final SkeletonResult sr = as.run(pruneChoice, false, false, grayscaleImp, true, false);
			nEndpoints = sum(sr.getEndPoints());
			nJunctions = sum(sr.getJunctions());

			if (order == 1) {
				// Remember initial properties
				endpointsList = sr.getListOfEndPoints();
				junctionsList = sr.getListOfJunctionVoxels();

				// Do not include root in 1st order calculations
				nEndpoints -= nRootEndpoints;
				nJunctions -= nRootJunctions;
			}

			// Is it worth proceeding?
			if (nEndpoints == 0 || nJunctions2 == nJunctions) {
				errorMsg = "Error! Iteration " + order + " aborted: ";
				errorMsg += (nEndpoints == 0) ? "No end-poins found" : "Unsolved loop(s) detected";
				break;
			}

			// Add current tree(s) to debug animation
			ImageProcessor ipd;
			if (nSlices > 1 && zp != null) {
				zp.doProjection();
				ipd = zp.getProjection().getProcessor();
			} else {
				ipd = ip.duplicate();
			}
			iterationStack.addSlice("Order " + IJ.pad(order, 2), ipd);

			// Report properties of pruned structures
			if (verbose) {
				logrt.incrementCounter();
				logrt.addValue("Image", title);
				logrt.addValue("Structure", "Skel. at iteration " + Integer.toString(order));
				logrt.addValue("Notes", errorMsg);
				logrt.addValue("# Trees", sr.getNumOfTrees());
				logrt.addValue("# Branches", sum(sr.getBranches()));
				logrt.addValue("# End-points", nEndpoints);
				logrt.addValue("# Junctions", nJunctions);
				logrt.addValue("# Triple points", sum(sr.getTriples()));
				logrt.addValue("# Quadruple points", sum(sr.getQuadruples()));
				logrt.addValue("Average branch length", average(sr.getAverageBranchLength()));
			}

			// Remember main results
			nJunctions2 = nJunctions;

			// Eliminate end-points
			as.run(pruneChoice, true, false, grayscaleImp, true, false, rootRoi);

		} while (order++ <= getMaxOrder() && nJunctions > 0);

		// Set counter to the de facto order
		order -= 1;

		// Append root properties to log table
		if (validRootRoi && verbose) {

			// Check if ROI contains unexpected structures
			final String msg = (nRootJunctions > 0) ? "Warning: ROI contains ramified root(s)"
					: "Root-branches inferred from ROI";
			logrt.incrementCounter();
			logrt.addValue("Image", title);
			logrt.addValue("Structure", "Root");
			logrt.addValue("Notes", msg);
			logrt.addValue("# Trees", rootResult == null ? 0 : rootResult.getNumOfTrees());
			logrt.addValue("# Branches", rootResult == null ? 0 : sum(rootResult.getBranches()));
			logrt.addValue("# End-points", nRootEndpoints);
			logrt.addValue("# Junctions", nRootJunctions);
			logrt.addValue("# Triple points", rootResult == null ? 0 : sum(rootResult.getTriples()));
			logrt.addValue("# Quadruple points", rootResult == null ? 0 : sum(rootResult.getQuadruples()));
			logrt.addValue("Average branch length",
					rootResult == null ? Double.NaN : average(rootResult.getAverageBranchLength()));

		}

		// Safety check
		if (iterationStack.getSize() < 1) {
			error("Enable \"detailed\" mode and check " + VERBOSE_TABLE + " for details.");
			return;
		}

		// Create iteration stack
		final Calibration cal = srcImp.getCalibration();
		final ImagePlus imp2 = new ImagePlus("StrahlerIteration_" + title, iterationStack);
		imp2.setCalibration(cal);
		if (outIS) {
			if (validRootRoi) {
				iterationStack.addSlice("Root", rootIp);
				paintPoints(iterationStack, rootEndpointsList, 255, "Root end-points");
				imp2.setRoi(rootRoi);
			}
			paintPoints(iterationStack, endpointsList, 255, "End-points");
			paintPoints(iterationStack, junctionsList, 255, "Junction-points");
		}

		// Generate Strahler mask
		zp = new ZProjector(imp2);
		zp.setMethod(ZProjector.SUM_METHOD);
		zp.setStartSlice(1);
		zp.setStopSlice(order);
		zp.doProjection();
		final ImageProcessor ip3 = zp.getProjection().getProcessor().convertToShortProcessor(false);
		clearPoints(ip3, junctionsList); // disconnect branches
		ip3.multiply(1 / 255.0); // map intensities to Strahler orders
		final ImagePlus imp3 = new ImagePlus("StrahlerMask_" + title, ip3);
		imp3.setCalibration(cal);

		// Measure segmented orders
		double prevNbranches = Double.NaN;
		for (int i = 1; i <= order; i++) {

			// Segment branches by order
			final ImagePlus maskImp = imp3.duplicate(); // Calibration is
														// retained
			IJ.setThreshold(maskImp, i, i);
			IJ.run(maskImp, "Convert to Mask", "");

			// Analyze segmented order
			final AnalyzeSkeleton_ maskAs = new AnalyzeSkeleton_();
			maskAs.setup("", maskImp);
			final SkeletonResult maskSr = maskAs.run(pruneChoice, false, false, grayscaleImp, true, false);
			maskImp.flush();

			// Since all branches are disconnected at this stage, the n. of
			// branches is
			// the same as the # the trees unless zero-branches trees exist,
			// i.e., trees
			// with no slab voxels (defined by just an end-point). We will
			// ignore those
			// trees if the user requested it
			final int nBranches = (erodeIsolatedPixels) ? sum(maskSr.getBranches()) : maskSr.getNumOfTrees();

			// Log measurements
			rt.incrementCounter();
			rt.addValue("Image", title);
			rt.addValue("Strahler Order", i);
			rt.addValue("# Branches", nBranches);
			rt.addValue("Ramification ratios", prevNbranches / nBranches);
			rt.addValue("Average branch length", average(maskSr.getAverageBranchLength()));
			rt.addValue("Unit", cal.getUnit());
			String noteMsg = "";
			if (i == 1) {
				noteMsg = (erodeIsolatedPixels) ? "Ignoring" : "Including";
				noteMsg += " single-point arbors...";
			}
			rt.addValue("Notes", noteMsg);

			// Remember results for previous order
			prevNbranches = nBranches;

		}

		// Append any errors to last row
		rt.addValue("Notes", errorMsg);

		// Display outputs
		if (!tabular) {
			if (outIS)
				imp2.show();
			ip3.setMinAndMax(0, order);
			ColorMaps.applyPlasma(imp3, 200, false);
			if (validRootRoi)
				imp3.setRoi(rootRoi);
			imp3.show();
			addCalibrationBar(imp3, Math.min(order, 5), "Black");
		}
		if (verbose)
			logrt.show(VERBOSE_TABLE);
		rt.show(STRAHLER_TABLE);

		IJ.showProgress(0, 0);
		IJ.showTime(imp, imp.getStartTime(), "Strahler Analysis concluded... ");
		imp.flush();

	}

	/**
	 * Checks if image to be analyzed fulfills analysis requirements and warns
	 * the user if required dependencies are present (i.e, if all the required
	 * update sites have been subscribed).
	 *
	 * @param imp
	 *            the image to be analyzed
	 * @return {@code true}, if assessment was successful. If {@code false} a
	 *         macro friendly error is displayed.
	 */
	boolean validRequirements(final ImagePlus imp) {
		boolean validImp = imp != null && imp.getBitDepth() == 8;
		if (!validImp) {
			final String msg = (imp == null) ? "An 8-bit image is required but none was found."
					: imp.getTitle() + " is not an 8-bit image.";
			if (IJ.macroRunning()) {
				error("Invalid image", msg, imp);
			} else {
				final GenericDialog gd = new GenericDialog("Invalid Image");
				gd.addMessage(msg);
				gd.enableYesNoCancel("OK", "Analyze Sample Image");
				gd.hideCancelButton();
				gd.showDialog();
				if (!gd.wasOKed() && !gd.wasCanceled()) {
					final LSystemsTree lst = new LSystemsTree();
					this.srcImp = lst.sampleTree();
					this.srcImp.setRoi(58, 130, 25, 35);
					srcImp.show();
					new ij.plugin.Zoom().run("in");
					validImp = true;
				}
			}
		}
		return validImp;
	}

	private void error(final String msg) {
		error("Strahler", msg, srcImp);
	}

	/**
	 * Gets the analysis parameters from the user.
	 *
	 * @return {@code true} if the dialog input is valid and dialog was not
	 *         dismissed.
	 */
	private boolean getSettings() {

		final EnhancedGenericDialog gd = new EnhancedGenericDialog("Strahler Analysis (IJ1 plugin)");
		final Font headerFont = new Font("SansSerif", Font.BOLD, 12);
		gd.setSmartRecording(true);

		// Part 1. Main Options
		gd.setInsets(0, 0, 0);
		gd.addMessage("Tree Classification:", headerFont);
		gd.addCheckbox("Infer root end-points from rectangular ROI", protectRoot);
		gd.addCheckbox("Ignore single-point arbors (Isolated pixels)", erodeIsolatedPixels);

		// Part 2: Loop elimination
		gd.setInsets(25, 0, 0);
		gd.addMessage("Elimination of Skeleton Loops:", headerFont);
		gd.addChoice("Method:", AnalyzeSkeleton_.pruneCyclesModes, AnalyzeSkeleton_.pruneCyclesModes[pruneChoice]);

		// 8-bit grayscale is the only image type recognized by
		// AnalyzeSkeleton_,
		// so we'll provide the user with a pre-filtered list of valid choices
		final ArrayList<Integer> validIds = new ArrayList<>();
		final ArrayList<String> validTitles = new ArrayList<>();
		final int[] ids = WindowManager.getIDList();
        for (int id : ids) {
            final ImagePlus imp = WindowManager.getImage(id);
            if (imp.getBitDepth() == 8) { // TODO: ignore composites?
                validIds.add(id);
                validTitles.add(imp.getTitle());
            }
        }
		gd.addChoice("8-bit grayscale image:", validTitles.toArray(new String[0]), title);

		// Part 3: Output
		gd.setInsets(25, 0, 0);
		gd.addMessage("Output Options:", headerFont);
		gd.addCheckbox("Display_iteration stack", outIS);
		gd.addCheckbox("Show detailed information", verbose);
		gd.addCheckbox("Tabular data only (no image output)", tabular);
		gd.setInsets(25, 0, 0);
		gd.addHyperlinkMessage("This plugin attempts at performing Strahler\n"
				+ "analysis directly from an image. For complete\n"
				+ "Strahler classification please extract paths\n"
				+ "from image and perform the analyisis on the\n"
				+ "reconstruction using SNT.", null, 
				EnhancedGenericDialog.infoColor(), "https://imagej.net/SNT");

		gd.addDialogListener(this);
		dialogItemChanged(gd, null); // update prompt

		gd.addHelp(URL);
		gd.setHelpLabel("Online Help");
		gd.showDialog();

		// Set grayscale image for intensity-based pruning of skel. loops.
		if (pruneChoice == AnalyzeSkeleton_.LOWEST_INTENSITY_VOXEL
				|| pruneChoice == AnalyzeSkeleton_.LOWEST_INTENSITY_BRANCH) {
			grayscaleImp = WindowManager.getImage(validIds.get(grayscaleImpChoice));
		} else {
			grayscaleImp = null;
		}

		return gd.wasOKed();

	}

	/* Retrieve dialog options using the DialogListener interface */
	@Override
	public boolean dialogItemChanged(final GenericDialog gd, final java.awt.AWTEvent e) {

		protectRoot = gd.getNextBoolean();
		erodeIsolatedPixels = gd.getNextBoolean();
		pruneChoice = gd.getNextChoiceIndex();
		grayscaleImpChoice = gd.getNextChoiceIndex();
		outIS = gd.getNextBoolean();
		verbose = gd.getNextBoolean();
		tabular = gd.getNextBoolean();

		// Enable/Disable key components of GenericDialog
		if (!IJ.macroRunning()) {
			final Choice cImgChoice = (Choice) gd.getChoices().elementAt(1);
			final Vector<?> checkboxes = gd.getCheckboxes();
			final Checkbox roiOption = (Checkbox) checkboxes.elementAt(0);
			final Checkbox stackOption = (Checkbox) checkboxes.elementAt(2);

			cImgChoice.setEnabled(pruneChoice == AnalyzeSkeleton_.LOWEST_INTENSITY_VOXEL
					|| pruneChoice == AnalyzeSkeleton_.LOWEST_INTENSITY_BRANCH);
			roiOption.setEnabled(validRootRoi);
			stackOption.setEnabled(!tabular);

		}

		return !gd.wasCanceled();

	}

	/**
	 * Returns the sum of the values in the input array, or zero if the array is
	 * empty or {@code null}.
	 *
	 * @param array
	 *            array of values to be summed
	 * @return the sum of elements in the array. Returns zero if array is
	 *         {@code null} or empty.
	 */
	int sum(final int[] array) {
		int sum = 0;
		if (array != null)
			for (final int i : array)
				sum += i;
		return sum;
	}

	/**
	 * Returns the sum of the values in the input array, or zero if the array is
	 * empty or {@code null}.
	 *
	 * @param array
	 *            array of values to be summed
	 * @return the sum of elements in the array. Returns zero if array is
	 *         {@code null} or empty.
	 */
	double sum(final double[] array) {
		double sum = 0; // TODO Use org.apache.commons.math3.stat.StatUtils?
		if (array != null)
			for (final double i : array)
				sum += i;
		return sum;
	}

	/**
	 * Returns the arithmetic mean of the values in the input array, or
	 * {@code Double.NaN} if the array is empty or {@code null}.
	 *
	 * @param array
	 *            array of values to be averaged
	 * @return the arithmetic mean of the array. Returns {@code Double.NaN} if
	 *         array is {@code null} or empty.
	 */
	double average(final double[] array) {
		if (array != null && array.length > 0)
			return sum(array) / array.length; // TODO Use
												// org.apache.commons.math3.stat.StatUtils?
		return Double.NaN;
	}

	/* Paints point positions. */
	void paintPoints(final ImageStack stack, final ArrayList<Point> points, final int value, final String sliceLabel) {
		if (points != null) {
			final ImageProcessor ipp = stack.getProcessor(1).createProcessor(stack.getWidth(), stack.getHeight());
					//ip.createProcessor(stack.getWidth(), stack.getHeight());
            for (final Point point : points) {
                ipp.putPixel(point.x, point.y, value);
            }
			stack.addSlice(sliceLabel, ipp);
		}
	}

	/* Clears point positions */
	private void clearPoints(final ImageProcessor processor, final ArrayList<Point> points) {
		if (points != null) {
            for (final Point point : points) {
                processor.putPixel(point.x, point.y, 0);
            }
		}
	}

	/*
	 * Skeletonization method that erodes the thinned structure in order to
	 * eliminate isolated pixels. Thinning and pruning may give rise to single
	 * point arbors. These 'debris' trees have 1 end-point but no branches or
	 * junctions. If present they overestimate the total number of end-points
	 */
	private void skeletonizeWithoutHermits(final ImagePlus imp) {
		final Skeletonize3D_ thin = new Skeletonize3D_();
		thin.setup("", imp);
		thin.run(null);
		if (erodeIsolatedPixels)
			ImpUtils.removeIsolatedPixels(imp);
	}

	/**
	 * Runs {@link ij.plugin.CalibrationBar} on the specified image using
	 * sensible settings.
	 *
	 * @param imp
	 *            the image to processed
	 * @param nLabels
	 *            the n. of labels in the calibration bar
	 * @param color
	 *            Labels' foreground color as per {@link ij.plugin.Colors}
	 **/
	private void addCalibrationBar(final ImagePlus imp, final int nLabels, final String color) {
		final ImageCanvas ic = imp.getCanvas();
		double zoom = (imp.getHeight() > 200) ? 1.0 : 0.8;
		final double mag = (ic != null) ? ic.getMagnification() : 1.0;
		if (zoom <= 1 && mag < 1)
			zoom = 1.0 / mag;
		IJ.run(imp, "Calibration Bar...",
				"fill=None label=" + color + " number=" + nLabels + " zoom=" + zoom + " overlay");
	}

	/**
	 * Returns the maximum Strahler order being considered by the plugin.
	 *
	 * @return The maximum number of pruning cycles of end-point branches that
	 *         the plugin should perform
	 * @see #setMaxOrder(int)
	 */
	public int getMaxOrder() {
		return maxOrder;
	}

	/**
	 * Sets the maximum Strahler order to be considered by the plugin.
	 *
	 * @param maxOrder
	 *            The maximum number of pruning cycles of end-point branches
	 *            that the plugin should perform
	 * @see #getMaxOrder()
	 */
	public void setMaxOrder(final int maxOrder) {
		this.maxOrder = maxOrder;
	}

	/**
	 * Returns the ResultsTable of the specified window title (if open) or a new
	 * ResultsTable with appropriated properties (precision of 5 decimal places,
	 * no row numbers, "NaN" padding of empty cells)
	 *
	 * @param title
	 *            the window title of the table
	 * @return a referenced to the opened ResultsTable or a new one if the
	 *         window of the specified title is not associated to a valid
	 *         ResultsTable
	 */
	static ResultsTable getTable(final String title) {
		ResultsTable rt = null;
		final Window window = WindowManager.getWindow(title);
		if (window != null)
			rt = ((TextWindow) window).getTextPanel().getResultsTable();
		if (rt == null)
			rt = new ResultsTable();
		rt.setPrecision(5);
		rt.setNaNEmptyCells(true);
		rt.showRowNumbers(false);
		return rt;
	}

	/**
	 * Displays an error message that will not disrupt macro calls. This is
	 * useful for batch processing of images: Even if the analysis of a
	 * particular image fails, remaining images can still be analyzed by the
	 * same macro
	 */
	static void error(final String title, final String msg, final ImagePlus imp) {
		final String impMsg = "Error while processing " + imp.getTitle();
		if (IJ.macroRunning())
			IJ.log("\n>>>: " + title + " " + impMsg + "\n" + msg);
		else
			IJ.error(title, impMsg + "\n" + msg);
	}
}
