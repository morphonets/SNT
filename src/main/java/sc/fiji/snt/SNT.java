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

package sc.fiji.snt;

import java.awt.Color;
import java.awt.Component;
import java.awt.Window;
import java.awt.event.KeyListener;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

import javax.swing.*;

import com.google.common.collect.Lists;
import org.scijava.Context;
import org.scijava.NullContextException;
import org.scijava.app.StatusService;
import org.scijava.command.CommandService;
import org.scijava.convert.ConvertService;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.util.ColorRGB;
import org.scijava.vecmath.Color3f;
import org.scijava.vecmath.Point3d;
import org.scijava.vecmath.Point3f;

import amira.AmiraMeshDecoder;
import amira.AmiraParameters;
import features.GaussianGenerationCallback;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Prefs;
import ij.gui.ImageRoi;
import ij.gui.ImageWindow;
import ij.gui.NewImage;
import ij.gui.Overlay;
import ij.gui.StackWindow;
import ij.measure.Calibration;
import ij.measure.Measurements;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;
import ij.process.LUT;
import ij.process.ShortProcessor;
import ij3d.Content;
import ij3d.ContentConstants;
import ij3d.ContentCreator;
import ij3d.Image3DUniverse;
import io.scif.services.DatasetIOService;
import net.imagej.Dataset;
import net.imagej.legacy.LegacyService;
import sc.fiji.snt.event.SNTEvent;
import sc.fiji.snt.event.SNTListener;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.SWCImportOptionsDialog;
import sc.fiji.snt.hyperpanes.MultiDThreePanes;
import sc.fiji.snt.plugin.ShollAnalysisTreeCmd;
import sc.fiji.snt.util.BoundingBox;
import sc.fiji.snt.util.PointInCanvas;
import sc.fiji.snt.util.PointInImage;


/**
 * Implements the SNT plugin.
 *
 * @author Tiago Ferreira
 * @author Cameron Arshadi
 */
public class SNT extends MultiDThreePanes implements
	SearchProgressCallback, GaussianGenerationCallback, PathAndFillListener
{

	@Parameter
	private Context context;
	@Parameter
	protected StatusService statusService;
	@Parameter
	protected LegacyService legacyService;
	@Parameter
	private LogService logService;
	@Parameter
	protected DatasetIOService datasetIOService;
	@Parameter
	protected ConvertService convertService;

	protected static boolean verbose = false; // FIXME: Use prefservice

	protected static final int MIN_SNAP_CURSOR_WINDOW_XY = 2;
	protected static final int MIN_SNAP_CURSOR_WINDOW_Z = 0;
	protected static final int MAX_SNAP_CURSOR_WINDOW_XY = 40;
	protected static final int MAX_SNAP_CURSOR_WINDOW_Z = 10;

	protected static final String startBallName = "Start point";
	protected static final String targetBallName = "Target point";
	protected static final int ballRadiusMultiplier = 5;

	protected final PathAndFillManager pathAndFillManager;
	protected final SNTPrefs prefs;
	private GuiUtils guiUtils;

	/* Legacy 3D Viewer. This is all deprecated stuff */
	protected Image3DUniverse univ;
	protected boolean use3DViewer;
	private Content imageContent;
	protected ImagePlus colorImage;
	protected static final int DISPLAY_PATHS_SURFACE = 1;
	protected static final int DISPLAY_PATHS_LINES = 2;
	protected static final int DISPLAY_PATHS_LINES_AND_DISCS = 3;
	private int paths3DDisplay = 1;

	/* UI and tracing preferences */
	volatile protected int cursorSnapWindowXY;
	volatile protected int cursorSnapWindowZ;
	volatile protected boolean autoCanvasActivation;
	volatile protected boolean panMode;
	volatile protected boolean snapCursor;
	volatile protected boolean unsavedPaths = false;
	volatile protected boolean showOnlySelectedPaths;
	volatile protected boolean showOnlyActiveCTposPaths;
	volatile protected boolean activateFinishedPath;
	volatile protected boolean requireShiftToFork;

	private boolean manualOverride = false;
	private double fillThresholdDistance = 0.03d;

	/*
	 * Just for convenience, keep casted references to the superclass's
	 * InteractiveTracerCanvas objects:
	 */
	private InteractiveTracerCanvas xy_tracer_canvas;
	private InteractiveTracerCanvas xz_tracer_canvas;
	private InteractiveTracerCanvas zy_tracer_canvas;

	/* Image properties */
	protected int width, height, depth;
	protected int imageType = -1;
	protected double x_spacing = 1;
	protected double y_spacing = 1;
	protected double z_spacing = 1;
	protected String spacing_units = SNTUtils.getSanitizedUnit(null);
	protected int channel;
	protected int frame;
	private LUT lut;

	/* loaded pixels (main image) */
	protected byte[][] slices_data_b;
	protected short[][] slices_data_s;
	protected float[][] slices_data_f;
	volatile protected float stackMax;
	volatile protected float stackMin;

	/* Hessian-based analysis */
	private volatile boolean hessianEnabled = false;
	protected final HessianCaller primaryHessian;
	protected final HessianCaller secondaryHessian;

	/* tracing threads */
	private TracerThread currentSearchThread = null;
	private ManualTracerThread manualSearchThread = null;

	/*
	 * Fields for tracing on secondary data: a filtered image. This can work in one
	 * of two ways: image is loaded into memory or we waive its file path to a
	 * third-party class that will parse it
	 */
	protected boolean doSearchOnSecondaryData;
	protected float[][] secondaryData;
	protected File secondaryImageFile = null;
	volatile protected float stackMaxSecondary = Float.MIN_VALUE;
	volatile protected float stackMinSecondary = Float.MAX_VALUE;
	protected boolean tubularGeodesicsTracingEnabled = false;
	protected TubularGeodesicsTracer tubularGeodesicsThread;

	protected boolean nbaStarSearchEnabled = false;
	protected BidirectionalAStarSearch nbaStarSearch = null;

	/*
	 * pathUnfinished indicates that we have started to create a path, but not yet
	 * finished it (in the sense of moving on to a new path with a differen starting
	 * point.) //FIXME: this may be redundant - check that.
	 */
	volatile private boolean pathUnfinished = false;
	private Path editingPath; // Path being edited when in 'Edit Mode'
	private Path previousEditingPath; // reference to the 'last selected' path when in 'Edit Mode'

	/* Labels */
	private String[] materialList;
	private byte[][] labelData;

	protected volatile boolean loading = false;
	private volatile boolean lastStartPointSet = false;

	protected double last_start_point_x;
	protected double last_start_point_y;
	protected double last_start_point_z;

	private Path endJoin;
	private PointInImage endJoinPoint;

	// Any method that deals with these two fields should be synchronized.
	protected Path temporaryPath = null; // result of A* search that hasn't yet been confirmed 
	protected Path currentPath = null;

	/* GUI */
	protected SNTUI ui;
	protected volatile boolean tracingHalted = false; // Tracing functions paused?

	final Set<FillerThread> fillerSet = new HashSet<>();
	ExecutorService fillerThreadPool;

	ExecutorService tracerThreadPool;

	/* Colors */
	private static final Color DEFAULT_SELECTED_COLOR = Color.GREEN;
	protected static final Color DEFAULT_DESELECTED_COLOR = Color.MAGENTA;
	protected static final Color3f DEFAULT_SELECTED_COLOR3F = new Color3f(
		Color.GREEN);
	protected static final Color3f DEFAULT_DESELECTED_COLOR3F = new Color3f(
		Color.MAGENTA);
	protected Color3f selectedColor3f = DEFAULT_SELECTED_COLOR3F;
	protected Color3f deselectedColor3f = DEFAULT_DESELECTED_COLOR3F;
	protected Color selectedColor = DEFAULT_SELECTED_COLOR;
	protected Color deselectedColor = DEFAULT_DESELECTED_COLOR;
	protected boolean displayCustomPathColors = true;


	/**
	 * Instantiates SNT in 'Tracing Mode'.
	 *
	 * @param context the SciJava application context providing the services
	 *          required by the class
	 * @param sourceImage the source image
	 * @throws IllegalArgumentException If sourceImage is of type 'RGB'
	 */
	public SNT(final Context context, final ImagePlus sourceImage)
		throws IllegalArgumentException
	{

		if (context == null) throw new NullContextException();
		if (sourceImage.getStackSize() == 0) throw new IllegalArgumentException(
			"Uninitialized image object");
		if (sourceImage.getType() == ImagePlus.COLOR_RGB)
			throw new IllegalArgumentException(
				"RGB images are not supported. Please convert to multichannel and re-run");

		context.inject(this);
		SNTUtils.setPlugin(this);
		prefs = new SNTPrefs(this);
		pathAndFillManager = new PathAndFillManager(this);
		setFieldsFromImage(sourceImage);
		prefs.loadPluginPrefs();
		primaryHessian = new HessianCaller(this, HessianCaller.PRIMARY);
		secondaryHessian = new HessianCaller(this, HessianCaller.SECONDARY);
	}

	/**
	 * Instantiates SNT in 'Analysis Mode'
	 *
	 * @param context the SciJava application context providing the services
	 *          required by the class
	 * @param pathAndFillManager The PathAndFillManager instance to be associated
	 *          with the plugin
	 */
	public SNT(final Context context,
	           final PathAndFillManager pathAndFillManager)
	{

		if (context == null) throw new NullContextException();
		if (pathAndFillManager == null) throw new IllegalArgumentException(
			"pathAndFillManager cannot be null");
		this.pathAndFillManager = pathAndFillManager;

		context.inject(this);
		SNTUtils.setPlugin(this);
		prefs = new SNTPrefs(this);
		pathAndFillManager.plugin = this;
		pathAndFillManager.addPathAndFillListener(this);
		pathAndFillManager.setHeadless(true);

		// Inherit spacing from PathAndFillManager{
		final BoundingBox box = pathAndFillManager.getBoundingBox(false);
		x_spacing = box.xSpacing;
		y_spacing = box.ySpacing;
		z_spacing = box.zSpacing;
		spacing_units = box.getUnit();

		// now load preferences and disable auto-tracing features
		prefs.loadPluginPrefs();
		tracingHalted = true;
		enableAstar(false);
		enableSnapCursor(false);
		pathAndFillManager.setHeadless(false);
		primaryHessian = new HessianCaller(this, HessianCaller.PRIMARY);
		secondaryHessian = new HessianCaller(this, HessianCaller.SECONDARY);
	}

	private void setFieldsFromImage(final ImagePlus sourceImage) {
		xy = sourceImage;
		width = sourceImage.getWidth();
		height = sourceImage.getHeight();
		depth = sourceImage.getNSlices();
		imageType = sourceImage.getType();
		singleSlice = depth == 1;
		setSinglePane(single_pane);
		final Calibration calibration = sourceImage.getCalibration();
		if (calibration != null) {
			x_spacing = calibration.pixelWidth;
			y_spacing = calibration.pixelHeight;
			z_spacing = calibration.pixelDepth;
			spacing_units = SNTUtils.getSanitizedUnit(calibration.getUnit());
		}
		if ((x_spacing == 0.0) || (y_spacing == 0.0) || (z_spacing == 0.0)) {
			throw new IllegalArgumentException(
				"One dimension of the calibration information was zero: (" + x_spacing +
					"," + y_spacing + "," + z_spacing + ")");
		}
		if (accessToValidImageData() && !isDisplayCanvas(sourceImage)) {
			pathAndFillManager.assignSpatialSettings(sourceImage);
			if (sourceImage.getOriginalFileInfo() != null) {
				final String dir = sourceImage.getOriginalFileInfo().directory;
				final String name = sourceImage.getOriginalFileInfo().fileName;
				if (dir != null && name != null)
					prefs.setRecentFile(new File(dir, name));
			}
		} else {
			pathAndFillManager.syncSpatialSettingsWithPlugin();
		}
	}

	/**
	 * Rebuilds display canvases, i.e., the placeholder canvases used when no
	 * valid image data exists (a single-canvas is rebuilt if only the XY view is
	 * active).
	 * <p>
	 * Useful when multiple files are imported and imported paths 'fall off' the
	 * dimensions of current canvas(es). If there is not enough memory to
	 * accommodate enlarged dimensions, the resulting canvas will be a 2D image.
	 * </p>
	 *
	 * @throws IllegalArgumentException if valid image data exists
	 */
	public void rebuildDisplayCanvases() throws IllegalArgumentException {
		if (accessToValidImageData()) throw new IllegalArgumentException(
			"Attempting to rebuild canvas(es) when valid data exists");
		rebuildDisplayCanvasesInternal();
	}

	/**
	 * Rebuilds display canvas(es) to ensure all paths are contained in the image.
	 * Does nothing if placeholder canvas(es) are not being used.
	 * 
	 * @see #rebuildDisplayCanvases()
	 */
	public void updateDisplayCanvases() {
		if (!accessToValidImageData() && getImagePlus() == null) {
			SNTUtils.log("Rebuilding canvases...");
			rebuildDisplayCanvasesInternal();
		}
	}

	private void rebuildDisplayCanvasesInternal() {
		if (!pathAndFillManager.getBoundingBox(false).hasDimensions()) {
			pathAndFillManager.resetSpatialSettings(false);
			pathAndFillManager.updateBoundingBox();
		}
		initialize(getSinglePane(), 1, 1);
		updateUIFromInitializedImp(xy.isVisible());
		pauseTracing(true, false);
		updateTracingViewers(false);
	}

	private void updateUIFromInitializedImp(final boolean showImp) {
		if (getUI() != null) getUI().inputImageChanged();
		if (showImp) {
			xy.show();
			if (zy != null) zy.show();
			if (xz != null) xz.show();
		}
		if (accessToValidImageData()) getPrefs().setTemp(SNTPrefs.NO_IMAGE_ASSOCIATED_DATA, false);
	}

	private void nullifyCanvases() {
		if (xy != null) {
			xy.changes = false;
			xy.close();
			xy = null;
		}
		if (zy != null) {
			zy.changes = false;
			zy.close();
			zy = null;
		}
		if (xz != null) {
			xz.changes = false;
			xz.close();
			xz = null;
		}
		xy_canvas = null;
		xz_canvas = null;
		zy_canvas = null;
		xy_window = null;
		xz_window = null;
		zy_window = null;
		xy_tracer_canvas = null;
		xz_tracer_canvas = null;
		zy_tracer_canvas = null;
		slices_data_b = null;
		slices_data_s = null;
		slices_data_f = null;
		nullifyHessian();
	}

	public boolean accessToValidImageData() {
		return getImagePlus() != null && !isDisplayCanvas(xy);
	}

	private void setIsDisplayCanvas(final ImagePlus imp) {
		imp.setProperty("Info", "SNT Display Canvas");
	}

	protected boolean isDisplayCanvas(final ImagePlus imp) {
		return "SNT Display Canvas".equals(imp.getInfoProperty());
	}

	private void assembleDisplayCanvases() {
		nullifyCanvases();
		if (pathAndFillManager.size() == 0) {
			// not enough information to proceed. Assemble a dummy canvas instead
			xy = NewImage.createByteImage("Display Canvas", 1, 1, 1,
				NewImage.FILL_BLACK);
			setFieldsFromImage(xy);
			setIsDisplayCanvas(xy);
			return;
		}
		BoundingBox box = pathAndFillManager.getBoundingBox(false);
		if (!box.hasDimensions()) box = pathAndFillManager.getBoundingBox(true);

		final double[] dims = box.getDimensions(false);
		width = (int) Math.round(dims[0]);
		height = (int) Math.round(dims[1]);
		depth = (int) Math.round(dims[2]);
		spacing_units = box.getUnit();
		singleSlice = prefs.is2DDisplayCanvas() || depth < 2;
		setSinglePane(single_pane);

		// Make canvas 2D if there is not enough memory (>80%) for a 3D stack
		// TODO: Remove ij.IJ dependency
		final double MEM_FRACTION = 0.8d;
		final long memNeeded = (long) width * height * depth; // 1 byte per pixel
		final long memMax = IJ.maxMemory(); // - 100*1024*1024;
		final long memInUse = IJ.currentMemory();
		final long memAvailable = (long) (MEM_FRACTION * (memMax - memInUse));
		if (memMax > 0 && memNeeded > memAvailable) {
			singleSlice = true;
			depth = 1;
			SNTUtils.log(
				"Not enough memory for displaying 3D stack. Defaulting to 2D canvas");
		}

		// Enlarge canvas for easier access to edge nodes. Center all paths in
		// canvas without translating their coordinates. This is more relevant
		// for e.g., files with negative coordinates
		final int XY_PADDING = 50;
		final int Z_PADDING = (singleSlice) ? 0 : 2;
		width += XY_PADDING;
		height += XY_PADDING;
		depth += Z_PADDING;
		final PointInImage unscaledOrigin = box.unscaledOrigin();
		final PointInCanvas canvasOffset = new PointInCanvas(-unscaledOrigin.x +
			XY_PADDING / 2, -unscaledOrigin.y + XY_PADDING / 2, -unscaledOrigin.z +
				Z_PADDING / 2);
		for (final Path p : pathAndFillManager.getPaths()) {
			p.setCanvasOffset(canvasOffset);
		}

		// Create image
		imageType = ImagePlus.GRAY8;
		xy = NewImage.createByteImage("Display Canvas", width, height, (singleSlice) ? 1 : depth,
			NewImage.FILL_BLACK);
		setIsDisplayCanvas(xy);
		xy.setCalibration(box.getCalibration());
		x_spacing = box.xSpacing;
		y_spacing = box.ySpacing;
		z_spacing = box.zSpacing;
		spacing_units = box.getUnit();
	}

	@Override
	public void initialize(final ImagePlus imp) {
		nullifyCanvases();
		xy = imp;
		setFieldsFromImage(imp);
		changeUIState(SNTUI.LOADING);
		initialize(getSinglePane(), channel = imp.getC(), frame = imp.getT());
		tracingHalted = !inputImageLoaded();
		updateUIFromInitializedImp(imp.isVisible());
	}

	/**
	 * Initializes the plugin by assembling all the required tracing views
	 *
	 * @param singlePane if true only the XY view will be generated, if false XY,
	 *          ZY, XZ views are created
	 * @param channel the channel to be traced. Ignored when no valid image data
	 *          exists.
	 * @param frame the frame to be traced. Ignored when no valid image data
	 *          exists.
	 */
	public void initialize(final boolean singlePane, final int channel,
		final int frame)
	{
		if (!accessToValidImageData()) {
			this.channel = 1;
			this.frame = 1;
			assembleDisplayCanvases();
		}
		else {
			this.channel = channel;
			this.frame = frame;
			if (channel<1) this.channel = 1;
			if (channel>xy.getNChannels()) this.channel = xy.getNChannels();
			if (frame<1) this.frame = 1;
			if (frame>xy.getNFrames()) this.frame = xy.getNFrames();
		}

		setSinglePane(singlePane);
		final Overlay sourceImageOverlay = xy.getOverlay();
		initialize(xy, frame);
		xy.setOverlay(sourceImageOverlay);

		xy_tracer_canvas = (InteractiveTracerCanvas) xy_canvas;
		xz_tracer_canvas = (InteractiveTracerCanvas) xz_canvas;
		zy_tracer_canvas = (InteractiveTracerCanvas) zy_canvas;
		addListener(xy_tracer_canvas);

		if (accessToValidImageData()) loadData();

		if (!single_pane) {
			final double min = xy.getDisplayRangeMin();
			final double max = xy.getDisplayRangeMax();
			xz.setDisplayRange(min, max);
			zy.setDisplayRange(min, max);
			addListener(xz_tracer_canvas);
			addListener(zy_tracer_canvas);
		}

	}

	private void addListener(final InteractiveTracerCanvas canvas) {
		final QueueJumpingKeyListener listener = new QueueJumpingKeyListener(this,
			canvas);
		setAsFirstKeyListener(canvas, listener);
	}

	public void reloadImage(final int channel, final int frame) {
		if (getImagePlus() == null || getImagePlus().getProcessor() == null)
			throw new IllegalArgumentException("No image has yet been loaded.");
		if (frame < 1 || channel < 1 || frame > getImagePlus().getNFrames() ||
			channel > getImagePlus().getNChannels())
			throw new IllegalArgumentException("Invalid position: C=" + channel +
				" T=" + frame);
		this.channel = channel;
		this.frame = frame;
		final boolean currentSinglePane = getSinglePane();
		setFieldsFromImage(getImagePlus()); // In case image properties changed outside SNT 
		setSinglePane(currentSinglePane);
		loadData(); // will call nullifyHessian();
		if (use3DViewer && imageContent != null) {
			updateImageContent(prefs.get3DViewerResamplingFactor());
		}
	}

	public void rebuildZYXZpanes() {
		single_pane = false;
		reloadZYXZpanes(frame);
		xy_tracer_canvas = (InteractiveTracerCanvas) xy_canvas;
		addListener(xy_tracer_canvas);
		zy_tracer_canvas = (InteractiveTracerCanvas) zy_canvas;
		addListener(zy_tracer_canvas);
		xz_tracer_canvas = (InteractiveTracerCanvas) xz_canvas;
		addListener(xz_tracer_canvas);
		if (!xy.isVisible()) xy.show();
		if (!zy.isVisible()) zy.show();
		if (!xz.isVisible()) xz.show();
	}

	private void loadData() {
		statusService.showStatus("Loading data...");
		final ImageStack s = xy.getStack();
		switch (imageType) {
			case ImagePlus.GRAY8:
			case ImagePlus.COLOR_256:
				slices_data_b = new byte[depth][];
				for (int z = 0; z < depth; ++z)
					slices_data_b[z] = (byte[]) s.getPixels(xy.getStackIndex(channel, z +
						1, frame));
				stackMin = 0;
				stackMax = 255;
				break;
			case ImagePlus.GRAY16:
				slices_data_s = new short[depth][];
				for (int z = 0; z < depth; ++z)
					slices_data_s[z] = (short[]) s.getPixels(xy.getStackIndex(channel, z +
						1, frame));
				break;
			case ImagePlus.GRAY32:
				slices_data_f = new float[depth][];
				for (int z = 0; z < depth; ++z)
					slices_data_f[z] = (float[]) s.getPixels(xy.getStackIndex(channel, z +
						1, frame));
				break;
		}
		statusService.showStatus("Finding stack minimum / maximum");
		xy.deleteRoi(); // if a ROI exists, compute min/ max for entire image
		final ImageStatistics stats = xy.getStatistics(Measurements.MIN_MAX);
		stackMin = (float) stats.min;
		stackMax = (float) stats.max;
		nullifyHessian(); // ensure it will be reloaded
		updateLut();
	}

	public void startUI() {
		GuiUtils.setLookAndFeel();
		final SNT thisPlugin = this;
		ui = new SNTUI(thisPlugin);
		guiUtils = new GuiUtils(ui);
		SwingUtilities.invokeLater(() -> { ui.displayOnStarting(); });
	}

	public void loadTracings(final File file) {
		if (file != null && file.exists()) {
			if (isUIready()) ui.changeState(SNTUI.LOADING);
			if (pathAndFillManager.load(file.getAbsolutePath())) {
				prefs.setRecentFile(file);
			}
			if (isUIready()) ui.resetState();
		}
	}

	protected boolean isChangesUnsaved() {
		return unsavedPaths && pathAndFillManager.size() > 0;
	}

	public PathAndFillManager getPathAndFillManager() {
		return pathAndFillManager;
	}

	protected InteractiveTracerCanvas getXYCanvas() {
		return xy_tracer_canvas;
	}

	protected InteractiveTracerCanvas getXZCanvas() {
		return xz_tracer_canvas;
	}

	protected InteractiveTracerCanvas getZYCanvas() {
		return zy_tracer_canvas;
	}

	public ImagePlus getImagePlus() {
		//return (isDummy()) ? xy : getImagePlus(XY_PLANE);
		return getImagePlus(XY_PLANE);
	}

	protected double getImpDiagonalLength(final boolean scaled,
		final boolean xyOnly)
	{
		final double x = (scaled) ? x_spacing * width : width;
		final double y = (scaled) ? y_spacing * height : height;
		if (xyOnly) {
			return Math.sqrt(x * x + y * y);
		} else {
			final double z = (scaled) ? z_spacing * depth : depth;
			return Math.sqrt(x * x + y * y + z * z);
		}
	}

	/* This overrides the method in ThreePanes... */
	@Override
	public InteractiveTracerCanvas createCanvas(final ImagePlus imagePlus,
		final int plane)
	{
		return new InteractiveTracerCanvas(imagePlus, this, plane,
			pathAndFillManager);
	}

	public void cancelSearch(final boolean cancelFillToo) {
		// TODO: make this better
		if (tracerThreadPool != null) {
			tracerThreadPool.shutdownNow();
			try {
				long timeout = 1000L;
				boolean terminated = tracerThreadPool.awaitTermination(timeout, TimeUnit.MILLISECONDS);
				if (terminated) {
					SNTUtils.log("Search cancelled.");
				} else {
					SNTUtils.log("Failed to terminate search within " + timeout + "ms");
				}
			} catch (InterruptedException e) {
				e.printStackTrace();
			} finally {
				tracerThreadPool = null;
			}
		}
		if (currentSearchThread != null) {
			removeThreadToDraw(currentSearchThread);
			currentSearchThread = null;
		}
		if (nbaStarSearch != null) {
			removeThreadToDraw(nbaStarSearch);
			nbaStarSearch = null;
		}
		if (manualSearchThread != null) {
			removeThreadToDraw(manualSearchThread);
			manualSearchThread = null;
		}
		if (tubularGeodesicsThread != null) {
			tubularGeodesicsThread.requestStop();
			removeThreadToDraw(tubularGeodesicsThread);
			tubularGeodesicsThread = null;
		}
		endJoin = null;
		endJoinPoint = null;
		if (cancelFillToo && fillerThreadPool != null) {
			stopFilling();
		}
	}

	@Override
	public void threadStatus(final SearchInterface source, final int status) {
		// Ignore this information.
	}

	public void changeUIState(final int newState) {
		if (ui != null) ui.changeState(newState);
	}

	protected int getUIState() {
		return (ui == null) ? -1 : ui.getState();
	}

	synchronized protected void saveFill() throws IllegalArgumentException {
		if (fillerSet.isEmpty()) {
			throw new IllegalArgumentException("No fills available.");
		}

		for (final FillerThread fillerThread : fillerSet) {
			pathAndFillManager.addFill(fillerThread.getFill());
			removeThreadToDraw(fillerThread);
		}
		fillerSet.clear();
		changeUIState(SNTUI.WAITING_TO_START_PATH);
		if (getUI() != null)
			getUI().getFillManager().changeState(FillManagerUI.READY);
	}

	synchronized protected void discardFill(final boolean updateState) {
		if (fillerSet.isEmpty()) {
			SNTUtils.log("No Fill(s) to discard...");
		}
		for (FillerThread filler : fillerSet) {
			removeThreadToDraw(filler);
		}
		fillerSet.clear();
		fillerThreadPool = null;
		changeUIState(SNTUI.WAITING_TO_START_PATH);
		if (getUI() != null)
			getUI().getFillManager().changeState(FillManagerUI.READY);
	}

	synchronized protected void stopFilling() throws IllegalArgumentException {

		if (fillerThreadPool == null) {
			throw new IllegalArgumentException("No filler threads are currently running.");
		}
		fillerThreadPool.shutdown();
		try {
			// Wait a while for existing tasks to terminate
			if (!fillerThreadPool.awaitTermination(1L, TimeUnit.SECONDS)) {
				fillerThreadPool.shutdownNow(); // Cancel currently executing tasks
				// Wait a while for tasks to respond to being cancelled
				if (!fillerThreadPool.awaitTermination(1L, TimeUnit.SECONDS))
					System.err.println("Filler did not terminate");
			}
		} catch (InterruptedException ie) {
			// (Re-)Cancel if current thread also interrupted
			fillerThreadPool.shutdownNow();
			// Preserve interrupt status
			Thread.currentThread().interrupt();
		} finally {
			fillerThreadPool = null;
			if (getUI() != null)
				getUI().getFillManager().changeState(FillManagerUI.ENDED);
		}

	}

	synchronized protected void startFilling() throws IllegalArgumentException {
		if (fillerSet.isEmpty()) {
			throw new IllegalArgumentException("No Filters loaded");
		}
		if (fillerThreadPool != null) {
			throw new IllegalArgumentException("Filler already running");
		}
		if (getUI() != null)
			getUI().getFillManager().changeState(FillManagerUI.STARTED);
		fillerThreadPool = Executors.newFixedThreadPool(Math.max(1, SNTPrefs.getThreads()));
		final List<Future<?>> futures = new ArrayList<>();
		for (final FillerThread fillerThread : fillerSet) {
			final Future<?> result = fillerThreadPool.submit(fillerThread);
			futures.add(result);
		}
		SwingWorker<Object, Object> worker = new SwingWorker<Object, Object>() {
			@Override
			protected Object doInBackground() throws Exception {
				for (final Future<?> future : futures) {
					future.get();
				}
				return null;
			}
			@Override
			protected void done() {
				stopFilling();
				SNTUtils.log("All fills completed.");
				if (ui != null) {
					ui.getFillManager().allFillsFinished(true);
				}
			}
		};
		worker.execute();

	}

	/* Listeners */
	protected List<SNTListener> listeners = Collections.synchronizedList(
		new ArrayList<SNTListener>());

	public void addListener(final SNTListener listener) {
		listeners.add(listener);
	}

	public void notifyListeners(final SNTEvent event) {
		for (final SNTListener listener : listeners.toArray(new SNTListener[0])) {
			listener.onEvent(event);
		}
	}

	protected boolean anyListeners() {
		return listeners.size() > 0;
	}

	/*
	 * Now a couple of callback methods, which get information about the progress of
	 * the search.
	 */

	@Override
	public void finished(final SearchInterface source, final boolean success) {

		if (source == currentSearchThread || source == nbaStarSearch || source == tubularGeodesicsThread ||
				source == manualSearchThread)
		{
			removeSphere(targetBallName);

			if (success) {
				final Path result = source.getResult();
				if (result == null) {
					if (pathAndFillManager.enableUIupdates)
						SNTUtils.error("Bug! Succeeded, but null result.");
					else
						SNTUtils.error("Scripted path yielded a null result.");
					return;
				}
				if (endJoin != null) {
					result.setEndJoin(endJoin, endJoinPoint);
				}
				setTemporaryPath(result);
				if (ui == null) {
					confirmTemporary(false);
				} else {
					if (ui.confirmTemporarySegments) {
						changeUIState(SNTUI.QUERY_KEEP);
					} else {
						confirmTemporary(true);
					}
				}
			} else {
				SNTUtils.log("Failed to find route.");
				changeUIState(SNTUI.PARTIAL_PATH);
			}

			if (source == currentSearchThread) {
				currentSearchThread = null;
			} else if (source == manualSearchThread) {
				manualSearchThread = null;
			} else if (source == nbaStarSearch) {
				nbaStarSearch = null;
			}

			removeThreadToDraw(source);
			updateTracingViewers(false);

		}

	}

	@Override
	public void pointsInSearch(final SearchInterface source, final long inOpen,
		final long inClosed)
	{
		// Just use this signal to repaint the canvas, in case there's
		// been no mouse movement.
		updateTracingViewers(false);
	}

	public void justDisplayNearSlices(final boolean value, final int eitherSide) {

		xy_tracer_canvas.just_near_slices = value;
		if (!single_pane) {
			xz_tracer_canvas.just_near_slices = value;
			zy_tracer_canvas.just_near_slices = value;
		}

		xy_tracer_canvas.eitherSide = eitherSide;
		if (!single_pane) {
			xz_tracer_canvas.eitherSide = eitherSide;
			zy_tracer_canvas.eitherSide = eitherSide;
		}

		updateTracingViewers(false);

	}

	protected boolean uiReadyForModeChange() {
		return isUIready() && (getUIState() == SNTUI.WAITING_TO_START_PATH ||
			getUIState() == SNTUI.TRACING_PAUSED);
	}

	// if (uiReadyForModeChange(SNTUI.ANALYSIS_MODE)) {
	// getGuiUtils().tempMsg("Tracing image not available");
	// return;
	// }
	protected Path getEditingPath() {
		return editingPath;
	}

	protected Path getPreviousEditingPath() {
		return previousEditingPath;
	}

	protected int getEditingNode() {
		return (getEditingPath() == null) ? -1 : getEditingPath()
			.getEditableNodeIndex();
	}

	/**
	 * Assesses if activation of 'Edit Mode' is possible.
	 *
	 * @return true, if possible, false otherwise
	 */
	public boolean editModeAllowed() {
		return editModeAllowed(false);
	}

	protected boolean editModeAllowed(final boolean warnUserIfNot) {
		final boolean uiReady = uiReadyForModeChange() || isEditModeEnabled();
		if (warnUserIfNot && !uiReady) {
			discreteMsg("Please finish current operation before editing paths");
			return false;
		}
		detectEditingPath();
		final boolean pathExists = editingPath != null;
		if (warnUserIfNot && !pathExists) {
			discreteMsg("You must select a single path in order to edit it");
			return false;
		}
		final boolean validPath = pathExists && !editingPath.getUseFitted();
		if (warnUserIfNot && !validPath) {
			discreteMsg(
				"Only unfitted paths can be edited.<br>Run \"Un-fit volume\" to proceed");
			return false;
		}
		return uiReady && pathExists && validPath;
	}

	protected void setEditingPath(final Path path) {
		if (previousEditingPath != null) previousEditingPath.setEditableNode(-1);
		previousEditingPath = editingPath;
		editingPath = path;
	}

	protected void detectEditingPath() {
		editingPath = getSingleSelectedPath();
	}

	protected Path getSingleSelectedPath() {
		final Collection<Path> sPaths = getSelectedPaths();
		if (sPaths == null || sPaths.size() != 1) return null;
		return getSelectedPaths().iterator().next();
	}

	protected void enableEditMode(final boolean enable) {
		if (enable) {
			changeUIState(SNTUI.EDITING);
			if (isUIready() && !getUI().nearbySlices()) getUI().togglePartsChoice();
		}
		else {
			if (ui != null) ui.resetState();
		}
		if (enable && pathAndFillManager.getSelectedPaths().size() == 1) {
			editingPath = getSelectedPaths().iterator().next();
		}
		else {
			if (editingPath != null) editingPath.setEditableNode(-1);
			editingPath = null;
		}
		setDrawCrosshairsAllPanes(!enable);
		setLockCursorAllPanes(enable);
		xy_tracer_canvas.setEditMode(enable);
		if (!single_pane) {
			xz_tracer_canvas.setEditMode(enable);
			zy_tracer_canvas.setEditMode(enable);
		}
		updateTracingViewers(false);
	}

	protected void pause(final boolean pause) {
		if (pause) {
			if (!uiReadyForModeChange()) {
				guiUtils.error("Please finish/abort current task before pausing SNT.");
				return;
			}
			if (xy != null && accessToValidImageData())
				xy.setProperty("snt-changes", xy.changes);
			changeUIState(SNTUI.SNT_PAUSED);
			disableEventsAllPanes(true);
			setDrawCrosshairsAllPanes(false);
			setCanvasLabelAllPanes(InteractiveTracerCanvas.SNT_PAUSED_LABEL);
		}
		else {
			if (xy != null && xy.isLocked() && ui != null && !getConfirmation(
				"Image appears to be locked by another process. Activate SNT nevertheless?",
				"Image Locked")) {
				return;
			}
			disableEventsAllPanes(false);
			pauseTracing(tracingHalted, false);
			if (xy != null && accessToValidImageData() && xy.getProperty("snt-changes") != null) {
				final boolean changes = (boolean) xy.getProperty("snt-changes") && xy.changes;
				if (!changes && xy.changes && ui != null && guiUtils.getConfirmation("<HTML><div WIDTH=500>" //
							+ "Image seems to have been modified since you last paused SNT. "
							+ "Would you like to reload it so that SNT can access the modified pixel data?", //
							"Changes Detected. Reload Image?", "Yes. Reload Image", "No. Use Cached Data")) {
					ui.loadImagefromGUI(channel, frame);
				}
				xy.setProperty("snt-changes", false);
			}
		}
	}

	protected void pauseTracing(final boolean pause,
		final boolean validateChange)
	{
		if (pause) {
			if (validateChange && !uiReadyForModeChange()) {
				guiUtils.error(
					"Please finish/abort current task before pausing tracing.");
				return;
			}
			tracingHalted = true;
			changeUIState(SNTUI.TRACING_PAUSED);
			setDrawCrosshairsAllPanes(false);
			setCanvasLabelAllPanes(InteractiveTracerCanvas.TRACING_PAUSED_LABEL);
			enableSnapCursor(snapCursor && accessToValidImageData());
		}
		else {
			tracingHalted = false;
			changeUIState(SNTUI.WAITING_TO_START_PATH);
			setDrawCrosshairsAllPanes(true);
			setCanvasLabelAllPanes(null);
		}
	}

	protected boolean isEditModeEnabled() {
		return isUIready() && SNTUI.EDITING == getUIState();
	}

	protected void updateCursor(final double new_x, final double new_y,
		final double new_z)
	{
		xy_tracer_canvas.updateCursor(new_x, new_y, new_z);
		if (!single_pane) {
			xz_tracer_canvas.updateCursor(new_x, new_y, new_z);
			zy_tracer_canvas.updateCursor(new_x, new_y, new_z);
		}

	}

	synchronized public void loadLabelsFile(final String path) {

		final AmiraMeshDecoder d = new AmiraMeshDecoder();

		if (!d.open(path)) {
			guiUtils.error("Could not open the labels file '" + path + "'");
			return;
		}

		final ImageStack stack = d.getStack();

		final ImagePlus labels = new ImagePlus("Label file for Tracer", stack);

		if ((labels.getWidth() != width) || (labels.getHeight() != height) ||
			(labels.getNSlices() != depth))
		{
			guiUtils.error(
				"The size of that labels file doesn't match the size of the image you're tracing.");
			return;
		}

		// We need to get the AmiraParameters object for that image...

		final AmiraParameters parameters = d.parameters;

		materialList = parameters.getMaterialList();

		labelData = new byte[depth][];
		for (int z = 0; z < depth; ++z) {
			labelData[z] = (byte[]) stack.getPixels(xy.getStackIndex(channel, z + 1,
				frame));
		}

	}

	/** Assumes UI is available */
	synchronized protected void loadTracesFile(File file) {
		loading = true;
		if (file == null) file = ui.openFile("Open .traces File...", ".traces");
		if (file == null) return; // user pressed cancel;
		if (!file.exists()) {
			guiUtils.error(file.getAbsolutePath() + " is no longer available");
			loading = false;
			return;
		}
		final int guessedType = pathAndFillManager.guessTracesFileType(file
			.getAbsolutePath());
		switch (guessedType) {
			case PathAndFillManager.TRACES_FILE_TYPE_COMPRESSED_XML:
				if (pathAndFillManager.loadCompressedXML(file.getAbsolutePath()))
					unsavedPaths = false;
				break;
			case PathAndFillManager.TRACES_FILE_TYPE_UNCOMPRESSED_XML:
				if (pathAndFillManager.loadUncompressedXML(file
					.getAbsolutePath())) unsavedPaths = false;
				break;
			default:
				guiUtils.error(file.getAbsolutePath() +
					" is not a valid traces file.");
				break;
		}
		loading = false;
	}

	/** Assumes UI is available */
	synchronized protected void loadSWCFile(File file) {
		loading = true;
		if (file == null) file = ui.openFile("Open (e)SWC File...", "swc");
		if (file == null) return; // user pressed cancel;
		if (!file.exists()) {
			guiUtils.error(file.getAbsolutePath() + " is no longer available");
			loading = false;
			return;
		}
		final int guessedType = pathAndFillManager.guessTracesFileType(file
			.getAbsolutePath());
		switch (guessedType) {
			case PathAndFillManager.TRACES_FILE_TYPE_SWC: {
				final SWCImportOptionsDialog swcImportDialog =
					new SWCImportOptionsDialog(getUI(), "SWC import options for " + file
						.getName());
				if (swcImportDialog.succeeded() && pathAndFillManager.importSWC(
					file.getAbsolutePath(), swcImportDialog.getIgnoreCalibration(),
					swcImportDialog.getXOffset(), swcImportDialog.getYOffset(),
					swcImportDialog.getZOffset(), swcImportDialog.getXScale(),
					swcImportDialog.getYScale(), swcImportDialog.getZScale(),
					swcImportDialog.getReplaceExistingPaths())) unsavedPaths = false;
				break;
			}
			default:
				guiUtils.error(file.getAbsolutePath() +
					" does not seem to contain valid SWC data.");
				break;
		}
		loading = false;
	}

	public void mouseMovedTo(final double x_in_pane, final double y_in_pane,
		final int in_plane, final boolean sync_panes_modifier_down,
		final boolean join_modifier_down)
	{

		double x, y, z;

		final double[] pd = new double[3];
		findPointInStackPrecise(x_in_pane, y_in_pane, in_plane, pd);
		x = pd[0];
		y = pd[1];
		z = pd[2];

		final boolean editing = isEditModeEnabled() && editingPath != null &&
			editingPath.isSelected();
		final boolean joining = !editing && join_modifier_down && pathAndFillManager
			.anySelected();

		PointInImage pim = null;
		if (joining) {
			// find the nearest node to this cursor position
			pim = pathAndFillManager.nearestJoinPointOnSelectedPaths(x, y, z);
		}
		else if (editing) {
			// find the nearest node to this cursor 2D position.
			// then activate the Z-slice of the retrieved node
			final int eNode = editingPath.indexNearestToCanvasPosition2D(x, y,
				xy_tracer_canvas.nodeDiameter());
			if (eNode != -1) {
				pim = editingPath.getNodeWithoutChecks(eNode);
				editingPath.setEditableNode(eNode);
			}
		}
		if (pim != null) {
			x = pim.x / x_spacing;
			y = pim.y / y_spacing;
			z = pim.z / z_spacing;
			setCursorTextAllPanes((joining) ? " Fork Point" : null);
		}
		else {
			setCursorTextAllPanes(null);
		}

		final int ix = (int) Math.round(x);
		final int iy = (int) Math.round(y);
		final int iz = (int) Math.round(z);

		if (sync_panes_modifier_down || editing) setZPositionAllPanes(ix, iy, iz);

		String statusMessage = "";
		if (editing && editingPath.getEditableNodeIndex() > -1) {
			statusMessage = "Node " + editingPath.getEditableNodeIndex() + ", ";
//			System.out.println("unscaled "+ editingPath.getPointInCanvas(editingPath
//					.getEditableNodeIndex()));
//			System.out.println("scaled "+ editingPath.getPointInImage(editingPath
//					.getEditableNodeIndex()));
		}
		statusMessage += "World: (" + SNTUtils.formatDouble(ix * x_spacing, 2) + ", " +
			SNTUtils.formatDouble(iy * y_spacing, 2) + ", " + SNTUtils.formatDouble(iz *
				z_spacing, 2) + ");";
		if (labelData != null) {
			final byte b = labelData[iz][iy * width + ix];
			final int m = b & 0xFF;
			final String material = materialList[m];
			statusMessage += ", " + material;
		}
		statusMessage += " Image: (" + ix + ", " + iy + ", " + (iz + 1) + ")";
		updateCursor(x, y, z);
		statusService.showStatus(statusMessage);
		repaintAllPanes(); // Or the crosshair isn't updated...

		if (!fillerSet.isEmpty()) {
			for (FillerThread fillerThread : fillerSet) {
				final double distance = fillerThread.getDistanceAtPoint(ix, iy, iz);
				ui.getFillManager().showMouseThreshold((float)distance);
			}
		}
	}

	// When we set temporaryPath, we also want to update the display:

	@SuppressWarnings("deprecation")
	synchronized public void setTemporaryPath(final Path path) {

		final Path oldTemporaryPath = this.temporaryPath;

		xy_tracer_canvas.setTemporaryPath(path);
		if (!single_pane) {
			zy_tracer_canvas.setTemporaryPath(path);
			xz_tracer_canvas.setTemporaryPath(path);
		}

		temporaryPath = path;

		if (temporaryPath != null) temporaryPath.setName("Temporary Path");
		if (use3DViewer) {

			if (oldTemporaryPath != null) {
				oldTemporaryPath.removeFrom3DViewer(univ);
			}
			if (temporaryPath != null) temporaryPath.addTo3DViewer(univ, getXYCanvas()
				.getTemporaryPathColor(), null);
		}
	}

	@SuppressWarnings("deprecation")
	synchronized public void setCurrentPath(final Path path) {
		final Path oldCurrentPath = this.currentPath;
		currentPath = path;
		if (currentPath != null) {
			if (pathAndFillManager.getPathFromID(currentPath.getID()) == null)
				currentPath.setName("Current Path");
			path.setSelected(true); // so it is rendered as an active path
		}
		xy_tracer_canvas.setCurrentPath(path);
		if (!single_pane) {
			zy_tracer_canvas.setCurrentPath(path);
			xz_tracer_canvas.setCurrentPath(path);
		}
		if (use3DViewer) {
			if (oldCurrentPath != null) {
				oldCurrentPath.removeFrom3DViewer(univ);
			}
			if (currentPath != null) currentPath.addTo3DViewer(univ, getXYCanvas()
				.getTemporaryPathColor(), null);
		}
	}

	synchronized public Path getCurrentPath() {
		return currentPath;
	}

	protected void setPathUnfinished(final boolean unfinished) {

		this.pathUnfinished = unfinished;
		xy_tracer_canvas.setPathUnfinished(unfinished);
		if (!single_pane) {
			zy_tracer_canvas.setPathUnfinished(unfinished);
			xz_tracer_canvas.setPathUnfinished(unfinished);
		}
	}

	void addThreadToDraw(final SearchInterface s) {
		xy_tracer_canvas.addSearchThread(s);
		if (!single_pane) {
			zy_tracer_canvas.addSearchThread(s);
			xz_tracer_canvas.addSearchThread(s);
		}
	}

	void removeThreadToDraw(final SearchInterface s) {
		xy_tracer_canvas.removeSearchThread(s);
		if (!single_pane) {
			zy_tracer_canvas.removeSearchThread(s);
			xz_tracer_canvas.removeSearchThread(s);
		}
	}

	int[] selectedPaths = null;

	/*
	 * Create a new 8-bit ImagePlus of the same dimensions as this image, but with
	 * values set to either 255 (if there's a point on a path there) or 0
	 */
	synchronized public ImagePlus makePathVolume(final Collection<Path> paths) {

		final short[][] snapshot_data = new short[depth][];

		for (int i = 0; i < depth; ++i)
			snapshot_data[i] = new short[width * height];

		pathAndFillManager.setPathPointsInVolume(paths, snapshot_data, (short) 255, width,
			height, depth);

		final ImageStack newStack = new ImageStack(width, height);

		for (int i = 0; i < depth; ++i) {
			final ShortProcessor thisSlice = new ShortProcessor(width, height);
			thisSlice.setPixels(snapshot_data[i]);
			newStack.addSlice(null, thisSlice.convertToByteProcessor(false));
		}

		final ImagePlus newImp = new ImagePlus(xy.getShortTitle() +
			" Rendered Paths", newStack);
		newImp.setCalibration(xy.getCalibration());
		return newImp;
	}

	synchronized public ImagePlus makePathVolume() {
		return makePathVolume(pathAndFillManager.getPaths());
	}

	/* Start a search thread looking for the goal in the arguments: */
	synchronized void testPathTo(final double world_x, final double world_y,
		final double world_z, final PointInImage joinPoint)
	{
		testPathTo(world_x, world_y, world_z, joinPoint, -1); // GUI execution
	}

	synchronized private void testPathTo(final double world_x, final double world_y,
		final double world_z, final PointInImage joinPoint, final int minPathSize)// Script execution
	{
		if (!lastStartPointSet) {
			statusService.showStatus(
				"No initial start point has been set.  Do that with a mouse click." +
					" (Or a Shift-" + GuiUtils.ctrlKey() +
					"-click if the start of the path should join another neurite.");
			return;
		}

		if (temporaryPath != null) {
			statusService.showStatus(
				"There's already a temporary path; Press 'N' to cancel it or 'Y' to keep it.");
			return;
		}

		double real_x_end, real_y_end, real_z_end;

		int x_end, y_end, z_end;
		if (joinPoint == null) {
			real_x_end = world_x;
			real_y_end = world_y;
			real_z_end = world_z;
		}
		else {
			real_x_end = joinPoint.x;
			real_y_end = joinPoint.y;
			real_z_end = joinPoint.z;
			endJoin = joinPoint.onPath;
			endJoinPoint = joinPoint;
		}

		addSphere(targetBallName, real_x_end, real_y_end, real_z_end, getXYCanvas()
			.getTemporaryPathColor(), x_spacing * ballRadiusMultiplier);

		x_end = (int) Math.round(real_x_end / x_spacing);
		y_end = (int) Math.round(real_y_end / y_spacing);
		z_end = (int) Math.round(real_z_end / z_spacing);

		if (tracerThreadPool == null || tracerThreadPool.isShutdown()) {
			tracerThreadPool = Executors.newSingleThreadExecutor();
		}

		if (tubularGeodesicsTracingEnabled) {

			// Then useful values are:
			// oofFile.getAbsolutePath() - the filename of the OOF file
			// last_start_point_[xyz] - image coordinates of the start point
			// [xyz]_end - image coordinates of the end point

			// [xyz]_spacing

			tubularGeodesicsThread = new TubularGeodesicsTracer(secondaryImageFile,
				(int) Math.round(last_start_point_x), (int) Math.round(
					last_start_point_y), (int) Math.round(last_start_point_z), x_end,
				y_end, z_end, x_spacing, y_spacing, z_spacing, spacing_units);
			addThreadToDraw(tubularGeodesicsThread);
			tubularGeodesicsThread.addProgressListener(this);
			tubularGeodesicsThread.start();
		}

		else if (!isAstarEnabled()) {
			manualSearchThread = new ManualTracerThread(this, last_start_point_x,
				last_start_point_y, last_start_point_z, x_end, y_end, z_end);
			addThreadToDraw(manualSearchThread);
			manualSearchThread.addProgressListener(this);
			tracerThreadPool.execute(manualSearchThread);
		}
		else {
			if (nbaStarSearchEnabled) {
				nbaStarSearch = new BidirectionalAStarSearch(this, (int) Math.round(last_start_point_x),
						(int) Math.round(last_start_point_y), (int) Math.round(last_start_point_z), x_end, y_end, z_end);
				addThreadToDraw(nbaStarSearch);
				nbaStarSearch.setDrawingColors(Color.CYAN, Color.ORANGE); // Stabilized, Rejected
				nbaStarSearch.setMinExpectedSizeOfResult(-1);
				nbaStarSearch.setDrawingThreshold(-1);
				nbaStarSearch.addProgressListener(this);
				tracerThreadPool.submit(nbaStarSearch);
			} else {
				currentSearchThread = new TracerThread(this, (int) Math.round(last_start_point_x),
						(int) Math.round(last_start_point_y), (int) Math.round(last_start_point_z), x_end, y_end, z_end);
				addThreadToDraw(currentSearchThread);
				currentSearchThread.setDrawingColors(Color.CYAN, null);// TODO: Make this color a preference
				currentSearchThread.setMinExpectedSizeOfResult(minPathSize);
				currentSearchThread.setDrawingThreshold(-1);
				currentSearchThread.addProgressListener(this);
				tracerThreadPool.execute(currentSearchThread);
			}

		}

	}

	public BidirectionalAStarSearch createSearch(final double world_x, final double world_y,
												 final double world_z, final PointInImage joinPoint) {
		if (!lastStartPointSet) {
			statusService.showStatus(
					"No initial start point has been set.  Do that with a mouse click." +
							" (Or a Shift-" + GuiUtils.ctrlKey() +
							"-click if the start of the path should join another neurite.");
			return null;
		}

		if (temporaryPath != null) {
			statusService.showStatus(
					"There's already a temporary path; Press 'N' to cancel it or 'Y' to keep it.");
			return null;
		}

		double real_x_end, real_y_end, real_z_end;

		int x_end, y_end, z_end;
		if (joinPoint == null) {
			real_x_end = world_x;
			real_y_end = world_y;
			real_z_end = world_z;
		}
		else {
			real_x_end = joinPoint.x;
			real_y_end = joinPoint.y;
			real_z_end = joinPoint.z;
			endJoin = joinPoint.onPath;
			endJoinPoint = joinPoint;
		}

		addSphere(targetBallName, real_x_end, real_y_end, real_z_end, getXYCanvas()
				.getTemporaryPathColor(), x_spacing * ballRadiusMultiplier);

		x_end = (int) Math.round(real_x_end / x_spacing);
		y_end = (int) Math.round(real_y_end / y_spacing);
		z_end = (int) Math.round(real_z_end / z_spacing);

		return new BidirectionalAStarSearch(this, (int) Math.round(last_start_point_x),
				(int) Math.round(last_start_point_y), (int) Math.round(last_start_point_z), x_end, y_end, z_end);

	}

	public BidirectionalAStarSearch createSearch2(final double start_x, final double start_y, final double start_z,
												  final double world_x, final double world_y, final double world_z) {

		int x_start, y_start, z_start;
		int x_end, y_end, z_end;

		x_start = (int) Math.round(start_x / x_spacing);
		y_start = (int) Math.round(start_y / y_spacing);
		z_start = (int) Math.round(start_z / z_spacing);

		x_end = (int) Math.round(world_x / x_spacing);
		y_end = (int) Math.round(world_y / y_spacing);
		z_end = (int) Math.round(world_z / z_spacing);

		return new BidirectionalAStarSearch(this, x_start,
				y_start, z_start, x_end, y_end, z_end);

	}

	synchronized public void confirmTemporary(final boolean updateTracingViewers) {

		if (temporaryPath == null)
			// Just ignore the request to confirm a path (there isn't one):
			return;

		currentPath.add(temporaryPath);

		final PointInImage last = currentPath.lastPoint();
		last_start_point_x = (int) Math.round(last.x / x_spacing);
		last_start_point_y = (int) Math.round(last.y / y_spacing);
		last_start_point_z = (int) Math.round(last.z / z_spacing);

		if (currentPath.endJoins == null) {
			setTemporaryPath(null);
			changeUIState(SNTUI.PARTIAL_PATH);
			if (updateTracingViewers)
				updateTracingViewers(true);
		}
		else {
			setTemporaryPath(null);
			// Since joining onto another path for the end must finish the path:
			finishedPath();
		}

		/*
		 * This has the effect of removing the path from the 3D viewer and adding it
		 * again:
		 */
		setCurrentPath(currentPath);
	}

	synchronized public void cancelTemporary() {

		if (!lastStartPointSet) {
			discreteMsg(
				"No initial start point has been set yet.<br>Do that with a mouse click or a Shift+" +
					GuiUtils.ctrlKey() +
					"-click if the start of the path should join another.");
			return;
		}

		if (temporaryPath == null) {
			discreteMsg("There is no temporary path to discard");
			return;
		}

		removeSphere(targetBallName);

		if (temporaryPath.endJoins != null) {
			temporaryPath.unsetEndJoin();
		}

		setTemporaryPath(null);

		endJoin = null;
		endJoinPoint = null;

		updateTracingViewers(false);
	}

	/**
	 * Cancels the temporary path.
	 */
	synchronized public void cancelPath() {

		// Is there an unconfirmed path? If so, warn people about it...
		if (temporaryPath != null) {
			discreteMsg(
				"You need to confirm the last segment before canceling the path.");
			return;
		}

		if (currentPath != null) {
			if (currentPath.startJoins != null) currentPath.unsetStartJoin();
			if (currentPath.endJoins != null) currentPath.unsetEndJoin();
		}

		removeSphere(targetBallName);
		removeSphere(startBallName);

		setCurrentPath(null);
		setTemporaryPath(null);

		lastStartPointSet = false;
		setPathUnfinished(false);

		updateTracingViewers(true);
	}

	/**
	 * Automatically traces a path from a point A to a point B. See
	 * {@link #autoTrace(List, PointInImage)} for details.
	 *
	 * @param start the {@link PointInImage} the starting point of the path
	 * @param end the {@link PointInImage} the terminal point of the path
	 * @param forkPoint the {@link PointInImage} fork point of the parent
	 *          {@link Path} from which the searched path should branch off, or
	 *          null if the the path should not have any parent.
	 * @return the path a reference to the computed path.
	 * @see #autoTrace(List, PointInImage)
	 */
	public Path autoTrace(final PointInImage start, final PointInImage end,
		final PointInImage forkPoint)
	{
		final ArrayList<PointInImage> list = new ArrayList<>();
		list.add(start);
		list.add(end);
		return autoTrace(list, forkPoint);
	}

	/**
	 * Automatically traces a path from a list of points and adds it to the active
	 * {@link PathAndFillManager} instance. Note that this method still requires
	 * SNT's UI. For headless auto-tracing have a look at {@link TracerThread}.
	 * <p>
	 * SNT's UI will remain blocked in "search mode" until the Path computation
	 * completes. Tracing occurs through the active {@link SearchInterface}
	 * selected in the UI, i.e., {@link TracerThread} (the default A* search),
	 * {@link TubularGeodesicsTracer}, etc.
	 * <p>
	 * All input {@link PointInImage} must be specified in real world coordinates.
	 * <p>
	 *
	 * @param pointList the list of {@link PointInImage} containing the nodes to
	 *          be used as target goals during the search. If the search cannot
	 *          converge into a target point, such point is omitted from path, if
	 *          Successful, target point will be included in the final path. the
	 *          final path. The first point in the list is the start of the path,
	 *          the last its terminus. Null objects not allowed.
	 * @param forkPoint the {@link PointInImage} fork point of the parent
	 *          {@link Path} from which the searched path should branch off, or
	 *          null if the the path should not have any parent.
	 * @return the path a reference to the computed path. It is added to the Path
	 *         Manager list.If a path cannot be fully computed from the specified
	 *         list of points, a single-point path is generated.
	 */
	public Path autoTrace(final List<PointInImage> pointList,
						  final PointInImage forkPoint)
	{
		if (pointList == null || pointList.size() == 0)
			throw new IllegalArgumentException("pointList cannot be null or empty");

		final boolean existingEnableUIupdates = pathAndFillManager.enableUIupdates;
		pathAndFillManager.enableUIupdates = false;

		// Ensure there are no incomplete tracings around and disable UI
		if (ui != null && ui.getState() != SNTUI.READY) ui.abortCurrentOperation();
		final SNTUI existingUI = getUI();
		changeUIState(SNTUI.SEARCHING);
		ui = null;

		// Start path from first point in list
		final PointInImage start = pointList.get(0);
		startPath(start.x, start.y, start.z, forkPoint);

		final int secondNodeIdx = (pointList.size() == 1) ? 0 : 1;
		final int nNodes = pointList.size();

		if (tracerThreadPool == null || tracerThreadPool.isShutdown()) {
			tracerThreadPool = Executors.newSingleThreadExecutor();
		}

		// Now keep appending nodes to temporary path
		for (int i = secondNodeIdx; i < nNodes; i++) {
			// Append node and wait for search to be finished
			final PointInImage node = pointList.get(i);

			BidirectionalAStarSearch pathSearch = createSearch(node.x, node.y, node.z, null);
			pathSearch.setDrawingColors(Color.CYAN, Color.ORANGE);
			pathSearch.setDrawingThreshold(-1);
			addThreadToDraw(pathSearch);
			pathSearch.addProgressListener(this);

			Future<Path> result = tracerThreadPool.submit(pathSearch);
			Path pathResult = null;
			try {
				pathResult = result.get();
			} catch (InterruptedException | ExecutionException e) {
				SNTUtils.error("Error during auto-trace", e);
			} catch(Throwable t) {
				SNTUtils.error("Unknown error during trace", t);
			}

			removeThreadToDraw(pathSearch);

			if (pathResult == null) {
				SNTUtils.log("Auto-trace result was null.");
				return null;
			}

			if (endJoin != null) {
				pathResult.setEndJoin(endJoin, endJoinPoint);
			}
			setTemporaryPath(pathResult);
			confirmTemporary(true);
		}

		finishedPath();

		// restore UI state
		showStatus(0, 0, "Tracing Complete");

		pathAndFillManager.enableUIupdates = existingEnableUIupdates;
		ui = existingUI;
		if (existingEnableUIupdates) pathAndFillManager.resetListeners(null);

		changeUIState(SNTUI.READY);

		return pathAndFillManager.getPath(pathAndFillManager.size() - 1);

	}

	public Path autoTrace2(final List<PointInImage> pointList)
	{
		if (pointList == null || pointList.size() == 0)
			throw new IllegalArgumentException("pointList cannot be null or empty");

		if (tracerThreadPool == null || tracerThreadPool.isShutdown()) {
			tracerThreadPool = Executors.newSingleThreadExecutor();
		}

		Path fullPath = new Path(x_spacing, y_spacing, z_spacing, spacing_units);

		// Now keep appending nodes to temporary path
		for (int i=0; i<pointList.size()-1; i++) {
			// Append node and wait for search to be finished
			final PointInImage start = pointList.get(i);
			final PointInImage end = pointList.get(i+1);

			BidirectionalAStarSearch pathSearch = createSearch2(start.x, start.y, start.z, end.x, end.y, end.z);

			Future<Path> result = tracerThreadPool.submit(pathSearch);
			Path pathResult = null;
			try {
				pathResult = result.get();
			} catch (InterruptedException | ExecutionException e) {
				SNTUtils.error("Error during auto-trace", e);
			} catch(Throwable t) {
				SNTUtils.error("Unknown error during trace", t);
			}

			if (pathResult == null) {
				SNTUtils.log("Auto-trace result was null.");
				return null;
			}
			fullPath.add(pathResult);
		}

		return fullPath;

	}

	synchronized protected void replaceCurrentPath(final Path path) {
		if (currentPath != null) {
			discreteMsg("An active temporary path already exists...");
			return;
		}
//		if (getUIState() != SNTUI.WAITING_TO_START_PATH) {
//			discreteMsg("Please finish current operation before extending "+ path.getName());
//			return;
//		}
		unsavedPaths = true;
		lastStartPointSet = true;
		selectPath(path, false);
		setPathUnfinished(true);
		setCurrentPath(path);
		last_start_point_x = (int) Math.round(path.lastPoint().x / x_spacing);
		last_start_point_y = (int) Math.round(path.lastPoint().y / y_spacing);
		last_start_point_z = (int) Math.round(path.lastPoint().z / z_spacing);
		setTemporaryPath(null);
		changeUIState(SNTUI.PARTIAL_PATH);
		updateAllViewers();
	}

	synchronized protected void finishedPath() {

		if (currentPath == null) {
			// this can happen through repeated hotkey presses
			if (ui != null) discreteMsg("No temporary path to finish...");
			return;
		}

		// Is there an unconfirmed path? If so, confirm it first
		if (temporaryPath != null) confirmTemporary(false);

		if (justFirstPoint() && ui != null && ui.confirmTemporarySegments && !getConfirmation(
			"Create a single point path? (such path is typically used to mark the cell soma)",
			"Create Single Point Path?"))
		{
			return;
		}

		if (justFirstPoint()) {
			final PointInImage p = new PointInImage(last_start_point_x * x_spacing,
				last_start_point_y * y_spacing, last_start_point_z * z_spacing);
			p.onPath = currentPath;
			currentPath.addPointDouble(p.x, p.y, p.z);
			currentPath.endJoinsPoint = p;
			currentPath.startJoinsPoint = p;
			cancelSearch(false);
		}
		else {
			removeSphere(startBallName);
		}

		removeSphere(targetBallName);
		if (pathAndFillManager.getPathFromID(currentPath.getID()) == null)
			pathAndFillManager.addPath(currentPath, true, false, false);
		unsavedPaths = true;
		lastStartPointSet = false;
		if (activateFinishedPath) selectPath(currentPath, false);
		setPathUnfinished(false);
		setCurrentPath(null);

		// ... and change the state of the UI
		changeUIState(SNTUI.WAITING_TO_START_PATH);
		updateTracingViewers(true);
	}

	synchronized protected void clickForTrace(final Point3d p, final boolean join) {
		final double x_unscaled = p.x / x_spacing;
		final double y_unscaled = p.y / y_spacing;
		final double z_unscaled = p.z / z_spacing;
		setZPositionAllPanes((int) x_unscaled, (int) y_unscaled, (int) z_unscaled);
		clickForTrace(p.x, p.y, p.z, join);
	}

	synchronized protected void clickForTrace(final double world_x,
		final double world_y, final double world_z, final boolean join)
	{

		PointInImage joinPoint = null;

		if (join) {
			joinPoint = pathAndFillManager.nearestJoinPointOnSelectedPaths(world_x /
				x_spacing, world_y / y_spacing, world_z / z_spacing);
		}

		// FIXME: in some of the states this doesn't make sense; check for them:
		if (currentSearchThread != null) return;

		if (temporaryPath != null) return;

		if (!fillerSet.isEmpty()) {
			setFillThresholdFrom(world_x, world_y, world_z);
			return;
		}

		if (pathUnfinished) {
			/*
			 * Then this is a succeeding point, and we should start a search.
			 */
			try {
				testPathTo(world_x, world_y, world_z, joinPoint);
				changeUIState(SNTUI.SEARCHING);
			} catch (final Exception ex) {
				if (getUI() != null) {
					getUI().error(ex.getMessage());
					getUI().enableHessian(false);
					getUI().reset();
				} else {
					ex.printStackTrace();
				}
			}
		}
		else {
			/* This is an initial point. */
			startPath(world_x, world_y, world_z, joinPoint);
			changeUIState(SNTUI.PARTIAL_PATH);
		}

	}

	synchronized protected void clickForTrace(final double x_in_pane_precise,
		final double y_in_pane_precise, final int plane, final boolean join)
	{

		final double[] p = new double[3];
		findPointInStackPrecise(x_in_pane_precise, y_in_pane_precise, plane, p);

		final double world_x = p[0] * x_spacing;
		final double world_y = p[1] * y_spacing;
		final double world_z = p[2] * z_spacing;

		clickForTrace(world_x, world_y, world_z, join);
	}

	public void setFillThresholdFrom(final double world_x, final double world_y,
		final double world_z)
	{
		double min_dist = Double.POSITIVE_INFINITY;
		for (FillerThread fillerThread : fillerSet) {
			final double distance = fillerThread.getDistanceAtPoint(world_x / x_spacing,
					world_y / y_spacing, world_z / z_spacing);
			if (distance > 0 && distance < min_dist) {
				min_dist = distance;
			}
		}
		if (min_dist == Double.POSITIVE_INFINITY) {
			min_dist = -1.0f;
		}
		setFillThreshold(min_dist);

	}

	/**
	 * Sets the fill threshold distance. Typically, this value is set before a
	 * filling operation as a starting value for the {@link FillerThread}.
	 *
	 * @param distance the new threshold distance. Set it to {@code -1} to use SNT's
	 *                 default.
	 * @throws IllegalArgumentException If distance is not a valid positive value
	 */
	public void setFillThreshold(final double distance) throws IllegalArgumentException {
		if (distance != -1d && (Double.isNaN(distance) || distance <= 0))
			throw new IllegalArgumentException("Threshold distance must be a valid positive value");
		this.fillThresholdDistance = (distance == -1d) ? 0.03d : distance;
		if (ui != null)
			ui.getFillManager().updateThresholdWidget(fillThresholdDistance);
		fillerSet.forEach(f -> f.setThreshold(fillThresholdDistance)); // fillerSet never null
	}

	synchronized void startPath(final double world_x, final double world_y,
		final double world_z, final PointInImage joinPoint)
	{

		endJoin = null;
		endJoinPoint = null;

		if (lastStartPointSet) {
			statusService.showStatus(
				"The start point has already been set; to finish a path press 'F'");
			return;
		}

		setPathUnfinished(true);
		lastStartPointSet = true;

		final Path path = new Path(x_spacing, y_spacing, z_spacing, spacing_units);
		path.setCTposition(channel, frame);
		path.setName("New Path");

		Color ballColor;

		double real_last_start_x, real_last_start_y, real_last_start_z;

		if (joinPoint == null) {
			real_last_start_x = world_x;
			real_last_start_y = world_y;
			real_last_start_z = world_z;
			ballColor = getXYCanvas().getTemporaryPathColor();
		}
		else {
			real_last_start_x = joinPoint.x;
			real_last_start_y = joinPoint.y;
			real_last_start_z = joinPoint.z;
			path.setStartJoin(joinPoint.onPath, joinPoint);
			ballColor = Color.GREEN;
		}

		last_start_point_x = real_last_start_x / x_spacing;
		last_start_point_y = real_last_start_y / y_spacing;
		last_start_point_z = real_last_start_z / z_spacing;

		addSphere(startBallName, real_last_start_x, real_last_start_y,
			real_last_start_z, ballColor, x_spacing * ballRadiusMultiplier);

		setCurrentPath(path);
	}

	protected void addSphere(final String name, final double x, final double y,
		final double z, final Color color, final double radius)
	{
		if (use3DViewer) {
			final List<Point3f> sphere = customnode.MeshMaker.createSphere(x, y, z,
				radius);
			univ.addTriangleMesh(sphere, new Color3f(color), name);
		}
	}

	protected void removeSphere(final String name) {
		if (use3DViewer) univ.removeContent(name);
	}

	/*
	 * Return true if we have just started a new path, but have not yet added any
	 * connections to it, otherwise return false.
	 */
	private boolean justFirstPoint() {
		return pathUnfinished && (currentPath.size() == 0);
	}

	protected void startSholl(final PointInImage centerScaled) {
		setZPositionAllPanes((int) Math.round(centerScaled.x), (int) Math.round(
			centerScaled.y), (int) Math.round(centerScaled.z));
		setShowOnlySelectedPaths(false);
		SNTUtils.log("Starting Sholl Analysis centered at " + centerScaled);
		final Map<String, Object> input = new HashMap<>();
		input.put("snt", this);
		input.put("center", centerScaled);
		final Tree tree = new Tree(getPathAndFillManager().getPathsFiltered());
		input.put("tree", tree);
		final CommandService cmdService = getContext().getService(
			CommandService.class);
		cmdService.run(ShollAnalysisTreeCmd.class, true, input);
	}

	public ImagePlus getFilledVolume(final boolean asMask) {
		if (fillerSet.isEmpty()) return null;
		FillerThread filler = fillerSet.iterator().next();
		return FillUtils.fillsAsImagePlus(fillerSet, filler.imagePlus, !asMask,
				filler.imageType);
	}

	protected int guessResamplingFactor() {
		if (width == 0 || height == 0 || depth == 0) throw new IllegalArgumentException(
			"Can't call guessResamplingFactor() before width, height and depth are set...");
		/*
		 * This is about right for me, but probably should be related to the free memory
		 * somehow. However, those calls are so notoriously unreliable on Java that it's
		 * probably not worth it.
		 */
		final long maxSamplePoints = 500 * 500 * 100;
		int level = 0;
		while (true) {
			final long samplePoints = (long) (width >> level) *
				(long) (height >> level) * (depth >> level);
			if (samplePoints < maxSamplePoints) return (1 << level);
			++level;
		}
	}

	protected boolean isUIready() {
		if (ui == null) return false;
		return ui.isVisible();
	}

	public void addFillerThread(final FillerThread filler) {
		fillerSet.add(filler);
		filler.addProgressListener(this);
		filler.addProgressListener(ui.getFillManager());
		addThreadToDraw(filler);
		changeUIState(SNTUI.FILLING_PATHS);
	}

	synchronized public void initPathsToFill(final Set<Path> fromPaths) {

		fillerSet.clear();
		final int threads = Math.max(1, SNTPrefs.getThreads());
		final List<Path> fromPathsList = new ArrayList<>(fromPaths);
		SNTUtils.log("# Paths to fill: " + fromPathsList.size());
		final int tasks = fromPathsList.size();
		final int chunkSize = (tasks + threads - 1) / threads;
		SNTUtils.log("# Paths per FillerThread: " + chunkSize);
		List<List<Path>> chunked = Lists.partition(fromPathsList, chunkSize);
		for (List<Path> chunk : chunked) {
			final FillerThread filler = new FillerThread(xy, stackMin, stackMax, true, // reciprocal
					fillThresholdDistance, // Initial threshold to display
					5000); // reportEveryMilliseconds
			addThreadToDraw(filler);
			filler.addProgressListener(this);
			filler.addProgressListener(ui.getFillManager());
			filler.setSourcePaths(chunk);
			fillerSet.add(filler);
		}
		SNTUtils.log("# FillerThreads: " + fillerSet.size());

		if (ui != null) ui.setFillListVisible(true);

		changeUIState(SNTUI.FILLING_PATHS);
	}

	protected void setFillTransparent(final boolean transparent) {
		xy_tracer_canvas.setFillTransparent(transparent);
		if (!single_pane) {
			xz_tracer_canvas.setFillTransparent(transparent);
			zy_tracer_canvas.setFillTransparent(transparent);
		}
	}

	public double getMinimumSeparation() {
		return (is2D()) ? Math.min(Math.abs(x_spacing), Math.abs(y_spacing))
				: Math.min(Math.abs(x_spacing), Math.min(Math.abs(y_spacing), Math.abs(z_spacing)));
	}

	protected double getAverageSeparation() {
		return (is2D()) ? (x_spacing + y_spacing) / 2 : (x_spacing + y_spacing + z_spacing) / 3;
	}

	/**
	 * Retrieves the pixel data of the main image currently loaded in memory as an
	 * ImagePlus object. Returned image is always a single channel image.
	 *
	 * @return the loaded data corresponding to the C,T position currently being
	 *         traced, or null if no image data has been loaded into memory.
	 */
	public ImagePlus getLoadedDataAsImp() {
		if (!inputImageLoaded()) return null;
		final ImageStack stack = new ImageStack(xy.getWidth(), xy.getHeight());
		switch (imageType) {
			case ImagePlus.GRAY8:
			case ImagePlus.COLOR_256:
				for (int z = 0; z < depth; ++z) {
					final ImageProcessor ip = new ByteProcessor(xy.getWidth(), xy
						.getHeight());
					ip.setPixels(slices_data_b[z]);
					stack.addSlice(ip);
				}
				break;
			case ImagePlus.GRAY16:
				for (int z = 0; z < depth; ++z) {
					final ImageProcessor ip = new ShortProcessor(xy.getWidth(), xy
						.getHeight());
					ip.setPixels(slices_data_s[z]);
					stack.addSlice(ip);
				}
				break;
			case ImagePlus.GRAY32:
				for (int z = 0; z < depth; ++z) {
					final ImageProcessor ip = new FloatProcessor(xy.getWidth(), xy
						.getHeight());
					ip.setPixels(slices_data_f[z]);
					stack.addSlice(ip);
				}
				break;
			default:
				throw new IllegalArgumentException("Bug: unsupported type somehow");
		}
		final ImagePlus imp = new ImagePlus("C" + channel + "F" + frame, stack);
		updateLut(); // If the LUT meanwhile changed, update it
		imp.setLut(lut); // ignored if null
		imp.copyScale(xy);
		imp.setFileInfo(xy.getOriginalFileInfo());
		return imp;
	}

	public void startHessian(final String image, final double sigma, final double max, final boolean wait) {
		final HessianCaller hc = getHessianCaller(image);
		hc.setSigmaAndMax(sigma, max);
		if (wait) {
			try {
				hc.start().join();
			} catch (final InterruptedException e) {
				SNTUtils.error(e.getMessage(), e);
			}
		} else hc.start();
	}

	/**
	 * Returns the file of the 'secondary image', if any.
	 *
	 * @return the secondary image file, or null if no file has been set
	 */
	protected File getFilteredImageFile() {
		return secondaryImageFile;
	}

	/**
	 * Assesses if the 'secondary image' has been loaded into memory. Note that while
	 * some tracer Threads will load the image into memory, others may waive the loading
	 * to third party libraries
	 *
	 * @return true, if image has been loaded into memory.
	 */
	public boolean isSecondaryImageLoaded() {
		return secondaryData != null;
	}

	protected boolean inputImageLoaded() {
		return slices_data_b != null || slices_data_s != null || slices_data_f != null;
	}

	protected boolean isTracingOnSecondaryImageAvailable() {
		return isSecondaryImageLoaded() || tubularGeodesicsTracingEnabled;
	}

	/**
	 * Specifies the 'secondary image' to be used during a tracing session.
	 *
	 * @param file The file containing the 'secondary image'
	 */
	public void setSecondaryImage(final File file) {
		secondaryImageFile = file;
		if (ui != null) ui.updateFilteredImageFileWidget();
	}

	/**
	 * Loads the 'secondary image' specified by {@link #setSecondaryImage(File)} into
	 * memory as 32-bit data.
	 * 
	 * @param file The file to be loaded
	 * 
	 * @throws IOException              If image could not be loaded
	 * @throws IllegalArgumentException if dimensions are unexpected, or image type
	 *                                  is not supported
	 * @see #isSecondaryImageLoaded()
	 * @see #getSecondaryDataAsImp()
	 */
	public void loadSecondaryImage(final File file) throws IOException, IllegalArgumentException {
		final ImagePlus imp = openCachedDataImage(file);
		loadSecondaryImage(imp, true);
		setSecondaryImage(isSecondaryImageLoaded() ? file : null);
	}

	public void loadSecondaryImage(final ImagePlus imp) throws IllegalArgumentException {
		loadSecondaryImage(imp, true);
	}

	public void setSecondaryImageMinMax(final float min, final float max) {
		stackMinSecondary = min;
		stackMaxSecondary = max;
	}

	public float[] getSecondaryImageMinMax() {
		return new float[] { stackMinSecondary, stackMaxSecondary };
	}

	protected void loadSecondaryImage(final ImagePlus imp, final boolean changeUIState) throws IllegalArgumentException {
		if (imp != null && secondaryImageFile != null && secondaryImageFile.getName().toLowerCase().contains(".oof")) {
			showStatus(0, 0, "Optimally Oriented Flux image detected");
			SNTUtils.log("Optimally Oriented Flux image detected. Image won't be cached...");
			tubularGeodesicsTracingEnabled = true;
			return;
		}
		if (changeUIState) changeUIState(SNTUI.CACHING_DATA);
		loadCachedData(imp, secondaryData = new float[depth][]);
		File file = null;
		if (isSecondaryImageLoaded() && (imp.getFileInfo() != null)) {
			file = new File(imp.getFileInfo().directory, imp.getFileInfo().fileName);
		}
		setSecondaryImage(file);
		if (changeUIState) {
			changeUIState(SNTUI.WAITING_TO_START_PATH);
			if (getUI() != null) {
				getUI().enableHessian(false);
				getUI().enableSecondaryImgTracing(true);
			}
		}
	}

	public void loadTubenessImage(final String type, final File file) throws IOException, IllegalArgumentException {
		final ImagePlus imp = openCachedDataImage(file);
		final HessianCaller hc = getHessianCaller(type);
		loadTubenessImage(hc, imp, true);
		hc.sigma = -1;
	}

	public void loadTubenessImage(final String type, final ImagePlus imp) throws IllegalArgumentException {
		loadTubenessImage(getHessianCaller(type), imp, true);
	}

	protected void loadTubenessImage(final HessianCaller hc, final ImagePlus imp, final boolean changeUIState) throws IllegalArgumentException {
		if (xy == null) throw new IllegalArgumentException(
				"Data can only be loaded after tracing image is known");
		if (!compatibleImp(imp)) {
				throw new IllegalArgumentException("Dimensions do not match those of  " + xy.getTitle()
				+ ". If this unexpected, check under 'Image>Properties...' that CZT axes are not swapped.");
		}
		if (changeUIState) changeUIState(SNTUI.CACHING_DATA);
		SNTUtils.log("Loading tubeness image multiplier=" + hc.multiplier + " max=" + stackMaxSecondary);
		loadCachedData(imp, hc.cachedTubeness = new float[depth][]);
		hc.setSigmaAndMax(hc.getSigma(true), stackMaxSecondary);
		if (changeUIState) {
			getUI().updateHessianPanel(hc);
			changeUIState(SNTUI.WAITING_TO_START_PATH);
		}
	}

	private void loadCachedData(final ImagePlus imp, final float[][] destination) {
		showStatus(0, 0, "Loading secondary image");
		SNTUtils.convertTo32bit(imp);
		final ImageStack s = imp.getStack();
		for (int z = 0; z < depth; ++z) {
			showStatus(z, depth, "Loading image/Computing range...");
			final int pos = imp.getStackIndex(channel, z + 1, frame);
			destination[z] = (float[]) s.getPixels(pos);
			for (int y = 0; y < height; ++y) {
				for (int x = 0; x < width; ++x) {
					final float v = destination[z][y * width + x];
					if (v < stackMinSecondary) stackMinSecondary = v;
					if (v > stackMaxSecondary) stackMaxSecondary = v;
				}
			}
		}
		showStatus(0, 0, null);
	}

	private boolean compatibleImp(final ImagePlus imp) {
		return imp.getNChannels() <= channel && imp.getNFrames() <= frame && imp.getWidth() == xy.getWidth()
				&& imp.getHeight() == xy.getHeight() && imp.getNSlices() == xy.getNSlices();
	}

	private ImagePlus openCachedDataImage(final File file) throws IOException {
		if (xy == null) throw new IllegalArgumentException(
			"Data can only be loaded after main tracing image is known");
		if (!SNTUtils.fileAvailable(file)) {
			throw new IllegalArgumentException("File path of input data unknown");
		}
		ImagePlus imp = (ImagePlus) legacyService.getIJ1Helper().openImage(file.getAbsolutePath());
		if (imp == null) {
			final Dataset ds = datasetIOService.open(file.getAbsolutePath());
			if (ds == null)
				throw new IllegalArgumentException("Image could not be loaded by IJ.");
			imp = convertService.convert(ds, ImagePlus.class);
		}
		if (!compatibleImp(imp)) {
			throw new IllegalArgumentException("Dimensions do not match those of  " + xy.getTitle()
			+ ". If this unexpected, check under 'Image>Properties...' that CZT axes are not swapped.");
		}
		return imp;
	}

	/**
	 * Retrieves the 'secondary image' data currently loaded in memory as an
	 * ImagePlus object. Returned image is always of 32-bit type.
	 *
	 * @return the loaded data or null if no image has been loaded.
	 * @see #isSecondaryImageLoaded()
	 * @see #loadSecondaryImage(ImagePlus)
	 * @see #loadSecondaryImage(File)
	 */
	public ImagePlus getSecondaryDataAsImp() {
		return (isSecondaryImageLoaded()) ? getFilteredDataFromCachedData("Secondary Data", secondaryData) : null;
	}

	protected ImagePlus getCachedTubenessDataAsImp(final String type) {
		final HessianCaller hc = getHessianCaller(type);
		return (hc.cachedTubeness == null) ? null : getFilteredDataFromCachedData("Tubeness Data ["+ type + "]", hc.cachedTubeness);
	}

	private ImagePlus getFilteredDataFromCachedData(final String title, final float[][] data) {
		final ImageStack stack = new ImageStack(xy.getWidth(), xy.getHeight());
		for (int z = 0; z < depth; ++z) {
			final FloatProcessor ip = new FloatProcessor(xy.getWidth(), xy.getHeight());
			if (data[z]==null) continue;
			ip.setPixels(data[z]);
			stack.addSlice(ip);
		}
		final ImagePlus impFiltered = new ImagePlus(title, stack);
		updateLut();
		impFiltered.setLut(lut);
		impFiltered.copyScale(xy);
		return impFiltered;
	}

	public synchronized void enableHessian(final boolean enable) {
		if (enable) {
			if (isTracingOnSecondaryImageActive())
				secondaryHessian.start();
			else
				primaryHessian.start();
		}
		hessianEnabled = enable;
	}

	protected synchronized void cancelGaussian() {
		primaryHessian.cancelGaussianGeneration();
		secondaryHessian.cancelGaussianGeneration();
	}

	private void nullifyHessian() {
		hessianEnabled = false;
		primaryHessian.nullify();
		secondaryHessian.nullify();
	}

	public SNTPrefs getPrefs() {
		return prefs;
	}

	// This is the implementation of GaussianGenerationCallback
	@Override
	public void proportionDone(final double proportion) {
		if (proportion < 0) {
			nullifyHessian();
			if (ui != null) ui.gaussianCalculated(false);
			statusService.showProgress(1, 1);
			return;
		}
		else if (proportion >= 1.0) {
			hessianEnabled = true;
			if (ui != null) ui.gaussianCalculated(true);
		}
		statusService.showProgress((int) proportion, 1); // FIXME:
	}

	@Deprecated
	public void showCorrespondencesTo(final File tracesFile, final Color c,
		final double maxDistance)
	{

		final PathAndFillManager pafmTraces = new PathAndFillManager(this);
		if (!pafmTraces.load(tracesFile.getAbsolutePath())) {
			guiUtils.error("Failed to load traces from: " + tracesFile
				.getAbsolutePath());
			return;
		}

		final List<Point3f> linePoints = new ArrayList<>();

		// Now find corresponding points from the first one, and draw lines to
		// them:
		final List<NearPoint> cp = pathAndFillManager.getCorrespondences(pafmTraces,
			maxDistance);
		int done = 0;
		for (final NearPoint np : cp) {
			if (np != null) {
				// SNT.log("Drawing:");
				// SNT.log(np.toString());

				linePoints.add(new Point3f((float) np.near.x, (float) np.near.y,
					(float) np.near.z));
				linePoints.add(new Point3f((float) np.closestIntersection.x,
					(float) np.closestIntersection.y, (float) np.closestIntersection.z));

				final String ballName = univ.getSafeContentName("ball " + done);
				final List<Point3f> sphere = customnode.MeshMaker.createSphere(
					np.near.x, np.near.y, np.near.z, Math.abs(x_spacing / 2));
				univ.addTriangleMesh(sphere, new Color3f(c), ballName);
			}
			++done;
		}
		univ.addLineMesh(linePoints, new Color3f(Color.RED), "correspondences",
			false);

		for (int pi = 0; pi < pafmTraces.size(); ++pi) {
			final Path p = pafmTraces.getPath(pi);
			if (p.getUseFitted()) continue;
			p.addAsLinesTo3DViewer(univ, c, null);
		}
		// univ.resetView();
	}

	protected void setShowOnlySelectedPaths(final boolean showOnlySelectedPaths,
		final boolean updateGUI)
	{
		this.showOnlySelectedPaths = showOnlySelectedPaths;
		if (updateGUI) {
			updateTracingViewers(true);
		}
	}

	protected void setShowOnlyActiveCTposPaths(
		final boolean showOnlyActiveCTposPaths, final boolean updateGUI)
	{
		this.showOnlyActiveCTposPaths = showOnlyActiveCTposPaths;
		if (updateGUI) {
			updateTracingViewers(true);
		}
	}

	public void setShowOnlySelectedPaths(final boolean showOnlySelectedPaths) {
		setShowOnlySelectedPaths(showOnlySelectedPaths, true);
	}

	/**
	 * Gets the Image associated with a view pane.
	 *
	 * @param pane the flag specifying the view either
	 *          {@link MultiDThreePanes#XY_PLANE},
	 *          {@link MultiDThreePanes#XZ_PLANE} or
	 *          {@link MultiDThreePanes#ZY_PLANE}.
	 * @return the image associate with the specified view, or null if the view is
	 *         not available
	 */
	public ImagePlus getImagePlus(final int pane) {
		ImagePlus imp = null;
		switch (pane) {
			case XY_PLANE:
				if (xy != null && isDummy()) return null;
				imp = xy;
				break;
			case XZ_PLANE:
				imp = xz;
				break;
			case ZY_PLANE:
				imp = zy;
				break;
			default:
				break;
		}
		return (imp == null || imp.getProcessor() == null) ? null : imp;
	}

	protected ImagePlus getMainImagePlusWithoutChecks() {
		return xy;
	}

	protected void error(final String msg) {
		new GuiUtils(getActiveWindow()).error(msg);
	}

	protected void showMessage(final String msg, final String title) {
		new GuiUtils(getActiveWindow()).centeredMsg(msg, title);
	}

	private Component getActiveCanvas() {
		if (!isUIready()) return null;
		final List<Component> components = new ArrayList<>();
		components.add(xy_canvas);
		components.add(xz_canvas);
		components.add(zy_canvas);
		if (univ != null) components.add(univ.getCanvas());
		for (final Component c : components) {
			if (c != null && c.isFocusOwner()) return c;
		}
		return null;
	}

	private Component getActiveWindow() {
		if (!isUIready()) return null;
		if (ui.isActive()) return ui;
		final Window[] images = { xy_window, xz_window, zy_window };
		for (final Window win : images) {
			if (win != null && win.isActive()) return win;
		}
		final Window[] frames = { ui.getPathManager(), ui.getFillManager() };
		for (final Window frame : frames) {
			if (frame.isActive()) return frame;
		}
		return ui.recViewerFrame;
	}

	public boolean isOnlySelectedPathsVisible() {
		return showOnlySelectedPaths;
	}

	protected void updateTracingViewers(final boolean includeLegacy3Dviewer) {
		repaintAllPanes();
		if (includeLegacy3Dviewer) update3DViewerContents();
	}

	protected void updateNonTracingViewers() {
		if (getUI() == null) return;
		if (getUI().recViewer != null) {
			new Thread(() -> {
				getUI().recViewer.syncPathManagerList();
			}).start();
		}
		if (getUI().sciViewSNT != null) {
			new Thread(() -> {
				getUI().sciViewSNT.syncPathManagerList();
			}).start();
		}
	}

	public void updateAllViewers() {
		updateTracingViewers(true);
		updateNonTracingViewers();
		if (getUI()!=null) getUI().getPathManager().update();
	}

	/*
	 * Whatever the state of the paths, update the 3D viewer to make sure that
	 * they're the right colour, the right version (fitted or unfitted) is being
	 * used and whether the path should be displayed at all - it shouldn't if the
	 * "Show only selected paths" option is set.
	 */
	@Deprecated
	private void update3DViewerContents() {
		if (use3DViewer && univ != null) {
			new Thread(() -> {
				pathAndFillManager.update3DViewerContents();
			}).start();
		}
	}

	/**
	 * Gets the instance of the legacy 3D viewer universe. Note that the legacy 3D
	 * viewer is now deprecated.
	 *
	 * @return the a reference to the 3DUniverse or null if no universe has been
	 *         set
	 */
	@Deprecated
	protected Image3DUniverse get3DUniverse() {
		return univ;
	}

	protected void set3DUniverse(final Image3DUniverse universe) {
		univ = universe;
		use3DViewer = universe != null;
		if (use3DViewer) {
			// ensure there are no duplicated listeners
			univ.removeUniverseListener(pathAndFillManager);
			univ.addUniverseListener(pathAndFillManager);
			update3DViewerContents();
		}
	}

	@Deprecated
	protected void updateImageContent(final int resamplingFactor) {
		if (univ == null || xy == null) return;

		new Thread(() -> {

			// The legacy 3D viewer works only with 8-bit or RGB images
			final ImagePlus loadedImp = getLoadedDataAsImp();
			ContentCreator.convert(loadedImp);
			final String cTitle = xy.getTitle() + "[C=" + channel + " T=" + frame +
				"]";
			final Content c = ContentCreator.createContent( //
				univ.getSafeContentName(cTitle), // unique descriptor
				loadedImp, // grayscale image
				ContentConstants.VOLUME, // rendering option
				resamplingFactor, // resampling factor
				0, // time point: loadedImp does not have T dimension
				null, // new Color3f(Color.WHITE), // Default color
				Content.getDefaultThreshold(loadedImp, ContentConstants.VOLUME), // threshold
				new boolean[] { true, true, true } // displayed channels
			);

			c.setTransparency(0.5f);
			c.setLocked(true);
			if (imageContent != null) {
				univ.removeContent(imageContent.getName());
			}
			imageContent = c;
			univ.addContent(c);
			univ.setAutoAdjustView(false);
		}).start();
	}

	protected void setSelectedColor(final Color newColor) {
		selectedColor = newColor;
		selectedColor3f = new Color3f(newColor);
		updateTracingViewers(true);
	}

	protected void setDeselectedColor(final Color newColor) {
		deselectedColor = newColor;
		deselectedColor3f = new Color3f(newColor);
		if (getUI() != null && getUI().recViewer != null) {
			getUI().recViewer.setDefaultColor(new ColorRGB(newColor.getRed(), newColor
				.getGreen(), newColor.getBlue()));
			if (pathAndFillManager.size() > 0) getUI().recViewer.syncPathManagerList();
		}
		updateTracingViewers(true);
	}

	// FIXME: this can be very slow ... Perhaps do it in a separate thread?
	@Deprecated
	protected void setColorImage(final ImagePlus newColorImage) {
		colorImage = newColorImage;
		update3DViewerContents();
	}

	@Deprecated
	protected void setPaths3DDisplay(final int paths3DDisplay) {
		this.paths3DDisplay = paths3DDisplay;
		update3DViewerContents();
	}

	@Deprecated
	protected int getPaths3DDisplay() {
		return this.paths3DDisplay;
	}

	public void selectPath(final Path p, final boolean addToExistingSelection) {
		final HashSet<Path> pathsToSelect = new HashSet<>();
		if (p.isFittedVersionOfAnotherPath()) pathsToSelect.add(p.fittedVersionOf);
		else pathsToSelect.add(p);
		if (isEditModeEnabled()) { // impose a single editing path
			if (ui != null) ui.getPathManager().setSelectedPaths(pathsToSelect, this);
			setEditingPath(p);
			return;
		}
		if (addToExistingSelection) {
			pathsToSelect.addAll(ui.getPathManager().getSelectedPaths(false));
		}
		if (ui != null) ui.getPathManager().setSelectedPaths(pathsToSelect, this);
	}

	public Collection<Path> getSelectedPaths() {
		if (ui.getPathManager() != null) {
			return ui.getPathManager().getSelectedPaths(false);
		}
		throw new IllegalArgumentException(
			"getSelectedPaths was called when PathManagerUI was null");
	}

	@Override
	public void setPathList(final String[] newList, final Path justAdded,
		final boolean expandAll)
	{}

	@Override
	public void setFillList(final String[] newList) {}

	// Note that rather unexpectedly the p.setSelcted calls make sure that
	// the colour of the path in the 3D viewer is right... (FIXME)
	@Override
	public void setSelectedPaths(final HashSet<Path> selectedPathsSet,
		final Object source)
	{
		if (source == this) return;
		for (int i = 0; i < pathAndFillManager.size(); ++i) {
			final Path p = pathAndFillManager.getPath(i);
			if (selectedPathsSet.contains(p)) {
				p.setSelected(true);
			}
			else {
				p.setSelected(false);
			}
		}
	}

	/**
	 * This method will: 1) remove the existing {@link KeyListener}s from the
	 * component 'c'; 2) instruct 'firstKeyListener' to call those KeyListener if
	 * it has not dealt with the key; and 3) set 'firstKeyListener' as the
	 * KeyListener for 'c'.
	 *
	 * @param c the Component to which the Listener should be attached
	 * @param firstKeyListener the first key listener
	 */
	private static void setAsFirstKeyListener(final Component c,
		final QueueJumpingKeyListener firstKeyListener)
	{
		if (c == null) return;
		final KeyListener[] oldKeyListeners = c.getKeyListeners();
		for (final KeyListener kl : oldKeyListeners) {
			c.removeKeyListener(kl);
		}
		firstKeyListener.addOtherKeyListeners(oldKeyListeners);
		c.addKeyListener(firstKeyListener);
		setAsFirstKeyListener(c.getParent(), firstKeyListener);
	}

	protected synchronized void findSnappingPointInXYview(final double x_in_pane,
		final double y_in_pane, final double[] point)
	{

		// if (width == 0 || height == 0 || depth == 0)
		// throw new RuntimeException(
		// "Can't call findSnappingPointInXYview() before width, height and
		// depth are set...");

		final int[] window_center = new int[3];
		findPointInStack((int) Math.round(x_in_pane), (int) Math.round(y_in_pane),
			MultiDThreePanes.XY_PLANE, window_center);
		int startx = window_center[0] - cursorSnapWindowXY;
		if (startx < 0) startx = 0;
		int starty = window_center[1] - cursorSnapWindowXY;
		if (starty < 0) starty = 0;
		int startz = window_center[2] - cursorSnapWindowZ;
		if (startz < 0) startz = 0;
		int stopx = window_center[0] + cursorSnapWindowXY;
		if (stopx > width) stopx = width;
		int stopy = window_center[1] + cursorSnapWindowXY;
		if (stopy > height) stopy = height;
		int stopz = window_center[2] + cursorSnapWindowZ;
		if (cursorSnapWindowZ == 0) {
			++stopz;
		}
		else if (stopz > depth) {
			stopz = depth;
		}

		final ArrayList<int[]> pointsAtMaximum = new ArrayList<>();
		float currentMaximum = stackMin;
		for (int x = startx; x < stopx; ++x) {
			for (int y = starty; y < stopy; ++y) {
				for (int z = startz; z < stopz; ++z) {
					float v = stackMin;
					final int xyIndex = y * width + x;
					switch (imageType) {
						case ImagePlus.GRAY8:
						case ImagePlus.COLOR_256:
							v = 0xFF & slices_data_b[z][xyIndex];
							break;
						case ImagePlus.GRAY16:
							v = slices_data_s[z][xyIndex];
							break;
						case ImagePlus.GRAY32:
							v = slices_data_f[z][xyIndex];
							break;
						default:
							throw new IllegalArgumentException("Unknown image type: " + imageType);
					}
					if (v == stackMin)
						continue;
					else if (v > currentMaximum) {
						pointsAtMaximum.add(new int[] { x, y, z });
						currentMaximum = v;
					}
					else if (v == currentMaximum) {
						pointsAtMaximum.add(new int[] { x, y, z });
					}
				}
			}
		}

		if (pointsAtMaximum.isEmpty()) {
			point[0] = window_center[0];
			point[1] = window_center[1];
			point[2] = window_center[2];
		} else {
			final int[] snapped_p = pointsAtMaximum.get(pointsAtMaximum.size() / 2);
			if (window_center[2] != snapped_p[2]) xy.setZ(snapped_p[2] + 1);
			point[0] = snapped_p[0];
			point[1] = snapped_p[1];
			point[2] = snapped_p[2];
		}

	}

	protected void clickAtMaxPoint(final int x_in_pane, final int y_in_pane,
			final int plane)
		{
		clickAtMaxPoint(x_in_pane, y_in_pane, plane, false);
		}

	protected void clickAtMaxPoint(final int x_in_pane, final int y_in_pane,
		final int plane, final boolean join)
	{

		SNTUtils.log("Looking for maxima at x=" + x_in_pane + " y=" + y_in_pane + " on pane " + plane);
		final int[][] pointsToConsider = findAllPointsAlongLine(x_in_pane, y_in_pane, plane);

		final ArrayList<int[]> pointsAtMaximum = new ArrayList<>();
		float currentMaximum = stackMin;
		for (int[] ints : pointsToConsider) {
			float v = stackMin;
			final int[] p = ints;
			final int xyIndex = p[1] * width + p[0];
			switch (imageType) {
				case ImagePlus.GRAY8:
				case ImagePlus.COLOR_256:
					v = 0xFF & slices_data_b[p[2]][xyIndex];
					break;
				case ImagePlus.GRAY16:
					v = slices_data_s[p[2]][xyIndex];
					break;
				case ImagePlus.GRAY32:
					v = slices_data_f[p[2]][xyIndex];
					break;
				default:
					throw new IllegalArgumentException("Unknow image type: " + imageType);
			}
			if (v == stackMin) {
				continue;
			} else if (v > currentMaximum) {
				pointsAtMaximum.add(p);
				currentMaximum = v;
			}
			else if (v == currentMaximum) {
				pointsAtMaximum.add(p);
			}
		}
		/*
		 * Take the middle of those points, and pretend that was the point that was
		 * clicked on.
		 */
		if (pointsAtMaximum.isEmpty()) {
			discreteMsg("No maxima at " + x_in_pane + ", " + y_in_pane);
			return;
		}
		final int[] p = pointsAtMaximum.get(pointsAtMaximum.size() / 2);
		SNTUtils.log(" Detected: x=" + p[0] + ", y=" + p[1] + ", z=" + p[2] + ", value=" + stackMax);
		setZPositionAllPanes(p[0], p[1], p[2]);
		if (!tracingHalted) { // click only if tracing
			clickForTrace(p[0] * x_spacing, p[1] * y_spacing, p[2] * z_spacing, join);
		}
	}

	private ImagePlus[] getXYZYXZDataGray8(final boolean filteredData) {
		ImagePlus xy8 = null;
		if(filteredData) {
			if (tubularGeodesicsTracingEnabled)
				try {
					xy8 = openCachedDataImage(secondaryImageFile);
				} catch (final IOException e) {
					SNTUtils.error("IOerror", e);
					return null;
				}
			else 
				xy8 = getSecondaryDataAsImp();
		} else 
			xy8 = getLoadedDataAsImp();
		SNTUtils.convertTo8bit(xy8);
		final ImagePlus[] views = (single_pane) ? new ImagePlus[] { null, null } : MultiDThreePanes.getZYXZ(xy8, 1);
		return new ImagePlus[] { xy8, views[0], views[1] };
	}

	private void updateLut() {
		final LUT[] luts = xy.getLuts(); // never null
		if (luts.length > 0) lut = luts[channel - 1];
	}

	/**
	 * Overlays a semi-transparent MIP (8-bit scaled) of the data being traced
	 * over the tracing canvas(es). Does nothing if image is 2D. Note that with
	 * multidimensional images, only the C,T position being traced is projected.
	 *
	 * @param opacity (alpha), in the range 0.0-1.0, where 0.0 is none (fully
	 *          transparent) and 1.0 is fully opaque. Setting opacity to zero
	 *          clears previous MIPs.
	 */
	public void showMIPOverlays(final double opacity) {
		showMIPOverlays(false, opacity);
	}

	protected void showMIPOverlays(final boolean filteredData, final double opacity) {
		if (is2D() || !accessToValidImageData()) return;
		final String identifer = (filteredData) ? MIP_OVERLAY_IDENTIFIER_PREFIX + "2"
				: MIP_OVERLAY_IDENTIFIER_PREFIX + "1";
		if (opacity == 0d) {
			removeMIPOverlayAllPanes(identifer);
			//this.unzoomAllPanes();
			return;
		}
		final ImagePlus[] paneImps = new ImagePlus[] { xy, zy, xz };
		final ImagePlus[] paneMips = getXYZYXZDataGray8(filteredData);
		if (paneMips != null) showMIPOverlays(paneImps, paneMips, identifer,opacity);
	}

	private void showMIPOverlays(ImagePlus[] paneImps, ImagePlus[] paneMips, final String overlayIdentifier,
			final double opacity) {

		// Create a MIP Z-projection of the active channel
		for (int i = 0; i < paneImps.length; i++) {
			final ImagePlus paneImp = paneImps[i];
			final ImagePlus mipImp = paneMips[i];
			if (paneImp == null || mipImp == null || paneImp.getNSlices() == 1)
				continue;

			Overlay existingOverlay = paneImp.getOverlay();
			if (existingOverlay == null) existingOverlay = new Overlay();
			final ImagePlus overlay = SNTUtils.getMIP(mipImp);

			// (This logic is taken from OverlayCommands.)
			final ImageRoi roi = new ImageRoi(0, 0, overlay.getProcessor());
			roi.setName(overlayIdentifier);
			roi.setOpacity(opacity);
			existingOverlay.add(roi);
			paneImp.setOverlay(existingOverlay);
			paneImp.setHideOverlay(false);
		}
	}

	protected void discreteMsg(final String msg) { /* HTML format */
		if (pathAndFillManager.enableUIupdates)
			new GuiUtils(getActiveCanvas()).tempMsg(msg);
	}

	protected boolean getConfirmation(final String msg, final String title) {
		return new GuiUtils(getActiveWindow()).getConfirmation(msg, title);
	}

	protected void toggleSnapCursor() {
		enableSnapCursor(!snapCursor);
	}

	/**
	 * Enables SNT's XYZ snap cursor feature. Does nothing if no image data is
	 * available
	 *
	 * @param enable whether cursor snapping should be enabled
	 */
	public synchronized void enableSnapCursor(final boolean enable) {
		final boolean validImage = accessToValidImageData();
		snapCursor = enable && validImage;
		if (isUIready()) {
			if (enable && !validImage) {
				ui.noValidImageDataError();
			}
			ui.useSnapWindow.setSelected(snapCursor);
			ui.snapWindowXYsizeSpinner.setEnabled(snapCursor);
			ui.snapWindowZsizeSpinner.setEnabled(snapCursor && !is2D());
		}
	}

	public void enableAutoActivation(final boolean enable) {
		autoCanvasActivation = enable;
	}

	public void enableAutoSelectionOfFinishedPath(final boolean enable) {
		activateFinishedPath = enable;
	}

	protected boolean isTracingOnSecondaryImageActive() {
		return doSearchOnSecondaryData && isSecondaryImageLoaded();
	}

	/**
	 * Toggles the A* search algorithm (enabled by default)
	 *
	 * @param enable true to enable A* search, false otherwise
	 */
	public void enableAstar(final boolean enable) {
		manualOverride = !enable;
		if (ui != null) ui.enableAStarGUI(enable);
	}

	/**
	 * Checks if A* search is enabled
	 *
	 * @return true, if A* search is enabled, otherwise false
	 */
	public boolean isAstarEnabled() {
		return !manualOverride;
	}

	/**
	 * Checks if Hessian analysis is enabled
	 *
	 * @return true, if Hessian analysis is enabled, otherwise false
	 */
	public boolean isHessianEnabled(final String image) {
		if ("secondary".equalsIgnoreCase(image))
			return hessianEnabled && isTracingOnSecondaryImageAvailable() && secondaryHessian.sigma > -1;
		return hessianEnabled && primaryHessian.sigma > -1;
	}

	public double getHessianSigma(final String image, final boolean physicalUnits) {
		return getHessianCaller(image).getSigma(physicalUnits);
	}

	public boolean isTubenessImageCached(final String image) {
		final HessianCaller hc = getHessianCaller(image);
		return hc != null && hc.cachedTubeness != null;
	}

	/**
	 * @return true if the image currently loaded does not have a depth (Z)
	 *         dimension
	 */
	public boolean is2D() {
		return singleSlice;
	}

	protected boolean drawDiametersXY = Prefs.get(
		"tracing.Simple_Neurite_Tracer.drawDiametersXY", "false").equals("true");

	public void setDrawDiametersXY(final boolean draw) {
		drawDiametersXY = draw;
		repaintAllPanes();
	}

	public boolean getDrawDiametersXY() {
		return drawDiametersXY;
	}

	@Override
	public void closeAndResetAllPanes() {
		// Dispose xz/zy images unless the user stored some annotations (ROIs)
		// on the image overlay or modified them somehow. In that case, restore
		// them to the user
		removeMIPOverlayAllPanes();
		if (!single_pane) {
			final ImagePlus[] impPanes = { xz, zy };
			final ImageWindow[] winPanes = { xz_window, zy_window };
			for (int i = 0; i < impPanes.length; ++i) {
				if (impPanes[i] == null) continue;
				final Overlay overlay = impPanes[i].getOverlay();
				if (!impPanes[i].changes && (overlay == null || impPanes[i].getOverlay()
					.size() == 0)) impPanes[i].close();
				else {
					winPanes[i] = new ImageWindow(impPanes[i]);
					winPanes[i].getCanvas().add(ij.Menus.getPopupMenu());
					impPanes[i].setOverlay(overlay);
				}
			}
		}
		// Restore main view
		final Overlay overlay = (xy == null) ? null : xy.getOverlay();
		if (xy != null && overlay == null && !accessToValidImageData()) {
			xy.close();
			return;
		}
		if (xy != null && xy.getImage() != null) {
			if (original_xy_canvas != null) {
				xy_window = (xy.getNSlices()==1) ? new ImageWindow(xy, original_xy_canvas) : new StackWindow(xy, original_xy_canvas);
			} else
				xy_window = (xy.getNSlices()==1) ? new ImageWindow(xy) : new StackWindow(xy);
			xy.setOverlay(overlay);
			xy_window.getCanvas().add(ij.Menus.getPopupMenu());
		}

	}

	public Context getContext() {
		return context;
	}

	/**
	 * Gets the main UI.
	 *
	 * @return the main dialog of SNT's UI
	 */
	public SNTUI getUI() {
		return ui;
	}

	/* (non-Javadoc)
	 * @see MultiDThreePanes#showStatus(int, int, java.lang.String)
	 */
	@Override
	public void showStatus(final int progress, final int maximum,
		final String status)
	{
		if (status == null) {
			statusService.clearStatus();
			statusService.showProgress(0, 0);
		} else
			statusService.showStatus(progress, maximum, status);
		if (isUIready()) getUI().showStatus(status, true);
	}

	public HessianCaller getHessianCaller(final String image) {
		return ("secondary".equalsIgnoreCase(image)) ? secondaryHessian : primaryHessian;
	}
}
