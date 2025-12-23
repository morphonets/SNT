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

package sc.fiji.snt;

import amira.AmiraMeshDecoder;
import amira.AmiraParameters;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.*;
import ij.measure.Calibration;
import ij.process.ColorProcessor;
import ij.process.ImageStatistics;
import ij.process.ShortProcessor;
import ij3d.*;
import io.scif.services.DatasetIOService;
import net.imagej.Dataset;
import net.imagej.ImgPlus;
import net.imagej.axis.Axes;
import net.imagej.axis.AxisType;
import net.imagej.axis.CalibratedAxis;
import net.imagej.display.ColorTables;
import net.imagej.ops.OpService;
import net.imagej.ops.special.computer.AbstractUnaryComputerOp;
import net.imglib2.*;
import net.imglib2.Point;
import net.imglib2.RandomAccess;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.cache.img.DiskCachedCellImg;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.type.logic.BitType;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.IntegerType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.integer.UnsignedIntType;
import net.imglib2.type.numeric.integer.UnsignedLongType;
import net.imglib2.type.numeric.integer.UnsignedShortType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import org.apache.commons.lang3.StringUtils;
import org.scijava.Context;
import org.scijava.NullContextException;
import org.scijava.app.StatusService;
import org.scijava.command.CommandService;
import org.scijava.convert.ConvertService;
import org.scijava.plugin.Parameter;
import org.scijava.util.ColorRGB;
import org.jogamp.vecmath.Color3f;
import org.jogamp.vecmath.Point3d;
import org.jogamp.vecmath.Point3f;
import sc.fiji.snt.event.SNTEvent;
import sc.fiji.snt.event.SNTListener;
import sc.fiji.snt.filter.Frangi;
import sc.fiji.snt.filter.Lazy;
import sc.fiji.snt.filter.Tubeness;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.hyperpanes.MultiDThreePanes;
import sc.fiji.snt.plugin.ShollAnalysisTreeCmd;
import sc.fiji.snt.tracing.*;
import sc.fiji.snt.tracing.artist.FillerThreadArtist;
import sc.fiji.snt.tracing.artist.SearchArtist;
import sc.fiji.snt.tracing.artist.SearchArtistFactory;
import sc.fiji.snt.tracing.cost.*;
import sc.fiji.snt.tracing.heuristic.Dijkstra;
import sc.fiji.snt.tracing.heuristic.Euclidean;
import sc.fiji.snt.tracing.heuristic.Heuristic;
import sc.fiji.snt.util.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;


/**
 * Implements the SNT plugin.
 *
 * @author Tiago Ferreira
 * @author Cameron Arshadi
 */
public class SNT extends MultiDThreePanes implements
	SearchProgressCallback, HessianGenerationCallback, PathAndFillListener
{

	static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

	@Parameter
	private Context context;
	@Parameter
	private DatasetIOService datasetIOService;
	@Parameter
	protected StatusService statusService;
	@Parameter
	private ConvertService convertService;
	@Parameter
	private OpService opService;


	public enum SearchType {
		ASTAR, NBASTAR;
		@Override
		public String toString() {
			return StringUtils.capitalize(super.toString().toLowerCase());
		}
	}

	public enum SearchImageType {
		ARRAY, MAP;
		@Override
		public String toString() {
			return StringUtils.capitalize(super.toString().toLowerCase());
		}
	}
	public enum CostType {
		RECIPROCAL, DIFFERENCE, DIFFERENCE_SQUARED, PROBABILITY;

		public String getDescription() {
            return switch (this) {
                case RECIPROCAL -> "Robust under a wide range of image conditions";
                case DIFFERENCE -> "Faster on images with right-shifted intensity distributions (i.e., mean >> 0)";
                case DIFFERENCE_SQUARED -> "Similar to Difference, usually faster";
                case PROBABILITY ->
                        "Fast, especially on noisy or distribution-offset images. Use with real-time statistics";
            };
		}

		@Override
		public String toString() {
            if (this == CostType.DIFFERENCE_SQUARED) {
                return "Difference Sq."; // OtherWise too wide for FillManagerUI type label!?
            }
            return StringUtils.capitalize(super.toString().toLowerCase());
        }
	}
	public enum HeuristicType {
		EUCLIDEAN, DIJKSTRA;
		@Override
		public String toString() {
			return StringUtils.capitalize(super.toString().toLowerCase());
		}
	}
	public enum FilterType {
		TUBENESS, FRANGI, GAUSS, MEDIAN;
		@Override
		public String toString() {
			return StringUtils.capitalize(super.toString().toLowerCase());
		}
	}

	protected static boolean verbose = false; // FIXME: Use prefservice

	protected static final int MIN_SNAP_CURSOR_WINDOW_XY = 2;
	protected static final int MIN_SNAP_CURSOR_WINDOW_Z = 0;
	protected static final int MAX_SNAP_CURSOR_WINDOW_XY = 40;
	protected static final int MAX_SNAP_CURSOR_WINDOW_Z = 10;

	protected static final String startBallName = "Start point";
	protected static final String targetBallName = "Target point";
	protected static final int ballRadiusMultiplier = 5;

	private final PathAndFillManager pathAndFillManager;
	private final SNTPrefs prefs;
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
	protected volatile int cursorSnapWindowXY;
	protected volatile int cursorSnapWindowZ;
	protected volatile boolean autoCanvasActivation;
	protected volatile boolean panMode;
	protected volatile boolean snapCursor;
	protected volatile boolean showOnlySelectedPaths;
	protected volatile boolean showOnlyActiveCTposPaths;
	protected volatile boolean activateFinishedPath;
	protected volatile boolean requireShiftToFork;
	protected volatile boolean autoCT;
	private boolean drawDiameters;

	private boolean manualOverride = false;
	private double fillThresholdDistance = 0.1d;

	/*
	 * Just for convenience, keep cast references to the superclass's
	 * InteractiveTracerCanvas objects:
	 */
	InteractiveTracerCanvas xy_tracer_canvas;
	InteractiveTracerCanvas xz_tracer_canvas;
	InteractiveTracerCanvas zy_tracer_canvas;

	/* Image properties */
	protected int width, height, depth;
	protected int imageType = -1;
	protected double x_spacing = 1;
	protected double y_spacing = 1;
	protected double z_spacing = 1;
	protected String spacing_units = SNTUtils.getSanitizedUnit(null);
	protected int channel;
	protected int frame;

	/* all tracing and filling-related functions are performed on the Imgs */
	@SuppressWarnings("rawtypes")
	RandomAccessibleInterval ctSlice3d;

	/* statistics for main image*/
	private final ImageStatistics stats = new ImageStatistics();

	/* filter type */
	protected FilterType filterType = FilterType.TUBENESS;

	/* current selected search algorithm type */
	private SearchType searchType = SearchType.ASTAR;

	/* Search image type */
	protected SearchImageType searchImageType = SearchImageType.MAP;

	/* Cost function and heuristic estimate for search */
	private CostType costType = CostType.RECIPROCAL;
	private HeuristicType heuristicType = HeuristicType.EUCLIDEAN;

	/* Compute image statistics on the bounding box sub-volume given by the start and goal nodes */
	protected volatile boolean isUseSubVolumeStats = true;

	/* adjustable parameters for cost functions */
	// This should be less than 1, prevents meandering path
	protected volatile double oneMinusErfZFudge = 0.8;

	/* tracing threads */
	private AbstractSearch currentSearchThread = null;
	private ManualTracerThread manualSearchThread = null;

	/* Search artists */
	Map<SearchInterface, SearchArtist> searchArtists = new HashMap<>();

	/*
	 * Fields for tracing on secondary data: a filtered image. This can work in one
	 * of two ways: image is loaded into memory, or we waive its file path to a
	 * third-party class that will parse it
	 */
	protected boolean doSearchOnSecondaryData;
	@SuppressWarnings("rawtypes")
	protected RandomAccessibleInterval secondaryData;
	protected File secondaryImageFile = null;
	private final ImageStatistics statsSecondary = new ImageStatistics();
	protected boolean tubularGeodesicsTracingEnabled = false;
	protected TubularGeodesicsTracer tubularGeodesicsThread;

	/*
	 * pathUnfinished indicates that we have started to create a path, but not yet
	 * finished it (in the sense of moving on to a new path with a differen starting
	 * point.) //FIXME: this may be redundant - check that.
	 */
	private volatile boolean pathUnfinished = false;
	private Path editingPath; // Path being edited when in 'Edit Mode'
	private Path previousEditingPath; // reference to the 'last selected' path when in 'Edit Mode'

	/* Labels */
	private String[] materialList;
	private byte[][] labelData;

	private volatile boolean lastStartPointSet = false;

	protected double last_start_point_x;
	protected double last_start_point_y;
	protected double last_start_point_z;

	// Any method that deals with these two fields should be synchronized.
	protected Path temporaryPath = null; // result of A* search that hasn't yet been confirmed 
	protected Path currentPath = null;

	/* GUI */
	protected SNTUI ui;
	protected volatile boolean tracingHalted = false; // Tracing functions paused?

	/* Insertion order is used to assign label values in a labeling image */
	Set<FillerThread> fillerSet = new LinkedHashSet<>();
	ExecutorService fillerThreadPool;

	ExecutorService tracerThreadPool;

	/* Colors */
	protected static final Color DEFAULT_SELECTED_COLOR = Color.GREEN;
	protected static final Color DEFAULT_DESELECTED_COLOR = Color.MAGENTA;
	protected static final Color3f DEFAULT_SELECTED_COLOR3F = Utils.toColor3f(Color.GREEN);
	protected static final Color3f DEFAULT_DESELECTED_COLOR3F = Utils.toColor3f(Color.MAGENTA);
	protected Color3f selectedColor3f = DEFAULT_SELECTED_COLOR3F;
	protected Color3f deselectedColor3f = DEFAULT_DESELECTED_COLOR3F;
	protected Color selectedColor = DEFAULT_SELECTED_COLOR;
	protected Color deselectedColor = DEFAULT_DESELECTED_COLOR;
	protected boolean displayCustomPathColors = true;


    /**
     * Script-friendly constructor for Instantiating and initializing SNT in
     * 'Tracing Mode' (typically headless operations). The channel/frame to
     * be traced is assumed to be the image's active CT position.
     *
     * @param sourceImage the source image
     * @throws IllegalArgumentException If sourceImage is of type 'RGB'
     */
    public SNT(final ImagePlus sourceImage) throws IllegalArgumentException {
        this(SNTUtils.getContext(), sourceImage);
        initialize(true, sourceImage.getChannel(), sourceImage.getFrame());
    }

    /**
     * Script-friendly constructor for Instantiating SNT in 'Tracing Mode' (typically headless operations)
     *
     * @param sourceImage the source image
     */
    public <T extends RealType<T>> SNT(final ImgPlus<T> sourceImage) throws IllegalArgumentException {
        this(sourceImage, 0, 0);
    }

    /**
     * Script-friendly constructor for Instantiating SNT in 'Tracing Mode' (typically headless operations)
     *
     * @param sourceImage the source image
     * @param channel     channel index to extract (index 0)
     * @param timePoint   time index to extract (index 0)
     */
    public <T extends RealType<T>> SNT(final ImgPlus<T> sourceImage, final int channel,
        final int timePoint) throws IllegalArgumentException {
        if (sourceImage == null || sourceImage.size() == 0) {
            throw new IllegalArgumentException("Uninitialized image object");
        }

        // Extract/squeeze to get 2D/3D image
        final ImgUtils.SliceResult<T> sliceResult = ImgUtils.getCtSlice(sourceImage, channel, timePoint);
        final ImgPlus<T> processedImage = sliceResult.img();
        final int nDim = processedImage.numDimensions();
        if (nDim < 2 || nDim > 3) {
            throw new IllegalArgumentException(
                    "Expected 2D (XY) or 3D (XYZ) image, but got " + nDim + "D. " +
                            "Extract the desired channel/timepoint before loading.");
        }

        // Log what was extracted for debugging
        if (sliceResult.channelIndex() >= 0) {
            SNTUtils.log("Using channel " + sliceResult.channelIndex());
        }
        if (sliceResult.timeIndex() >= 0) {
            SNTUtils.log("Using timepoint " + sliceResult.timeIndex());
        }
        SNTUtils.getContext().inject(this);
        SNTUtils.setPlugin(this);
        pathAndFillManager = new PathAndFillManager(this);
        prefs = new SNTPrefs(this);
        setFieldsFromImgPlus(processedImage);
        prefs.loadPluginPrefs();
    }

	/**
	 * Instantiates SNT in 'Tracing Mode'.
	 *
	 * @param context the SciJava application context providing the services
	 *          required by the class
	 * @param sourceImage the source image
	 * @throws IllegalArgumentException If sourceImage is of type 'RGB'
	 */
	public SNT(final Context context, final ImagePlus sourceImage) throws IllegalArgumentException {

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
	}

	/**
	 * Instantiates SNT in 'Analysis Mode'
	 *
	 * @param context the SciJava application context providing the services
	 *          required by the class
	 * @param pathAndFillManager The PathAndFillManager instance to be associated
	 *          with the plugin
	 */
	public SNT(final Context context, final PathAndFillManager pathAndFillManager) {

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

		// Inherit spacing from PathAndFillManager
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
	}

    private <T extends RealType<T>> void setFieldsFromImgPlus(final ImgPlus<T> imgPlus) {
        xy = null;
        ctSlice3d = imgPlus;
        width = (int) imgPlus.dimension(0);
        height = (int) imgPlus.dimension(1);
        depth = imgPlus.numDimensions() > 2 ? (int) imgPlus.dimension(2) : 1;
        imageType = getImagePlusType(imgPlus.firstElement());
        singleSlice = depth == 1;
        setSinglePane(single_pane);

        // Extract calibration from axes
        x_spacing = getAxisScale(imgPlus, Axes.X, 0);
        y_spacing = getAxisScale(imgPlus, Axes.Y, 1);
        z_spacing = imgPlus.numDimensions() > 2 ? getAxisScale(imgPlus, Axes.Z, 2) : 1.0;

        final CalibratedAxis xAxis = imgPlus.axis(0);
        if (xAxis != null && xAxis.unit() != null) {
            spacing_units = SNTUtils.getSanitizedUnit(xAxis.unit());
        }

        if ((x_spacing == 0.0) || (y_spacing == 0.0) || (z_spacing == 0.0)) {
            throw new IllegalArgumentException(
                    "One dimension of the calibration information was zero: (" + x_spacing +
                            "," + y_spacing + "," + z_spacing + ")");
        }

        if (accessToValidImageData()) {
            pathAndFillManager.assignSpatialSettings(imgPlus);
            final String source = imgPlus.getSource();
            if (source != null && !source.isEmpty()) {
                final File sourceFile = new File(source);
                if (sourceFile.getParentFile() != null) {
                    prefs.setRecentDir(sourceFile.getParentFile());
                }
            }
            stats.min = 0;
            stats.max = 0;
        } else {
            pathAndFillManager.syncSpatialSettingsWithPlugin();
        }
    }

    private double getAxisScale(final ImgPlus<?> imgPlus, final AxisType axisType, final int dimFallback) {
        final int d = imgPlus.dimensionIndex(axisType);
        final int dim = d >= 0 ? d : dimFallback;
        if (dim < imgPlus.numDimensions()) {
            final CalibratedAxis axis = imgPlus.axis(dim);
            if (axis != null) {
                final double scale = axis.averageScale(0, 1);
                return Double.isNaN(scale) || scale == 0 ? 1.0 : scale;
            }
        }
        return 1.0;
    }

    private int getImagePlusType(final Object type) {
        if (type instanceof UnsignedByteType) return ImagePlus.GRAY8;
        if (type instanceof UnsignedShortType) return ImagePlus.GRAY16;
        if (type instanceof FloatType) return ImagePlus.GRAY32;
        if (type instanceof ARGBType) return ImagePlus.COLOR_RGB;
        return ImagePlus.GRAY32; // default for other RealTypes
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
					prefs.setRecentDir(new File(dir));
			}
			// Adjust and reset min/max
			if (sourceImage.getProcessor().isBinary()) {
				stats.min = 0;
				stats.max = 255;
			} else {
				stats.min = 0;
				stats.max = 0;
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

	private void nullifyCanvases(final boolean disposeXY) {
		if (xy != null) {
			xy.changes = false;
			if (disposeXY) xy.close();
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
	}

	public boolean accessToValidImageData() {
		// NB: Currently we are assuming that image data comes from an ImagePlus. This
		// needs to be changed when ImagePlus requirements are lift. Note that xy may
		// be null (e.g., image has been manually closed), but its cached data ctSlice3d
		// may still be available. Return ctSlice3d != null is problematic on its own,
		// because there are several calls to this method that happen _before_ ctSlice3d
		// has been assembled, but _after_ an image has been specified
		return getImagePlus() != null && !isDisplayCanvas(xy);
	}

	private void setIsDisplayCanvas(final ImagePlus imp) {
		imp.setProperty("Info", "SNT Display Canvas\n");
	}

	protected boolean isDisplayCanvas(final ImagePlus imp) {
		return "SNT Display Canvas\n".equals(imp.getInfoProperty());
	}

	private void setIsCachedData(final ImagePlus imp) {
		// NB: somehow setProperty/getProperty does not work with virtual stacks,
		// so we'll brand the image title instead
		imp.setTitle(String.format("Cached Data [C%dT%d]", channel, frame));
	}

	protected boolean isCachedData(final ImagePlus imp) {
		return imp.getTitle().equals(String.format("Cached Data [C%dT%d]", channel, frame));
	}

	private void assembleDisplayCanvases() {
		nullifyCanvases(true);
		if (pathAndFillManager.size() == 0) {
			// not enough information to proceed. Assemble a dummy canvas instead
			xy = ImpUtils.create("Display Canvas", 1, 1, 1, 8);
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
		final long memMax = ij.IJ.maxMemory(); // - 100*1024*1024;
		final long memInUse = ij.IJ.currentMemory();
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
			(double) XY_PADDING / 2, -unscaledOrigin.y + (double) XY_PADDING / 2, -unscaledOrigin.z +
				(double) Z_PADDING / 2);
		for (final Path p : pathAndFillManager.getPaths()) {
			p.setCanvasOffset(canvasOffset);
		}

		// Create image
		imageType = ImagePlus.GRAY8;
		xy = ImpUtils.create("Display Canvas", width, height, (singleSlice) ? 1 : depth, 8);
		setIsDisplayCanvas(xy);
		xy.setCalibration(box.getCalibration());
		x_spacing = box.xSpacing;
		y_spacing = box.ySpacing;
		z_spacing = box.zSpacing;
		spacing_units = box.getUnit();
	}

	@Override
	public void initialize(final ImagePlus imp) {
		if (imp == null) {
			initialize(true, 1, 1);
			return;
		}
		final Roi sourceImageROI = imp.getRoi();
		final boolean sameImp = imp == xy;
		if (accessToValidImageData() && getPrefs().getTemp(SNTPrefs.RESTORE_LOADED_IMGS, false)) {
			rebuildWindow(xy);
			xy = null;
		}
		nullifyCanvases(!sameImp);
		setFieldsFromImage(imp);
		changeUIState(SNTUI.LOADING);
		initialize(getSinglePane(), channel = imp.getC(), frame = imp.getT());
		tracingHalted = !accessToValidImageData();
		updateUIFromInitializedImp(imp.isVisible());
		xy.setRoi(sourceImageROI);
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

		if (accessToValidImageData()) {
			loadDatasetFromImagePlus(getImagePlus());
		}

		if (!single_pane) {
			final double min = xy.getDisplayRangeMin();
			final double max = xy.getDisplayRangeMax();
			xz.setDisplayRange(min, max);
			zy.setDisplayRange(min, max);
			addListener(xz_tracer_canvas);
			addListener(zy_tracer_canvas);
		}

	}

	public void initialize(final boolean singlePane, final int channel,
						   final int frame, final boolean computeStackStats) {
		setUseSubVolumeStats(!computeStackStats); // This MUST be called before initialize()
		initialize(singlePane, channel, frame);
	}

	private void addListener(final InteractiveTracerCanvas canvas) {
        if (!GraphicsEnvironment.isHeadless()) {
            final QueueJumpingKeyListener listener = new QueueJumpingKeyListener(this, canvas);
            setAsFirstKeyListener(canvas, listener);
        }
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
		loadDatasetFromImagePlus(getImagePlus()); // will call nullifySigmaHelper();
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

	@SuppressWarnings("unchecked")
	private void loadDatasetFromImagePlus(final ImagePlus imp) {
		statusService.showStatus("Loading data...");
		this.ctSlice3d = ImgUtils.getCtSlice3d(imp, channel - 1, frame - 1);
		SNTUtils.log("Dimensions of input dataset [W,H,C,Z,T]: " + Arrays.toString(imp.getDimensions()));
		SNTUtils.log(String.format("Dimensions:of imported XYZ volume (C=%d,T=%d): %s", channel, frame,
				Arrays.toString(Intervals.dimensionsAsLongArray(this.ctSlice3d))));
		statusService.showStatus("Finding stack minimum / maximum");
		final boolean restoreROI = imp.getRoi() instanceof PointRoi;
		if (restoreROI) imp.saveRoi();
		imp.deleteRoi(); // if a ROI exists, compute min/ max for entire image
		if (restoreROI) imp.restoreRoi();
		if (!getUseSubVolumeStats()) {
			SNTUtils.log("Computing stack statistics");
			computeImgStats(this.ctSlice3d, getStats());
		}
	}

	public void startUI() {
		try {
			GuiUtils.setLookAndFeel();
			final SNT thisPlugin = this;
			ui = new SNTUI(thisPlugin);
			guiUtils = new GuiUtils(ui);
			ui.displayOnStarting();
		} catch (final Exception e) {
			e.printStackTrace();
		}
	}

	public boolean loadTracings(final File file) {
		if (file != null && file.exists()) {
			if (isUIready()) ui.changeState(SNTUI.LOADING);
			final boolean success = pathAndFillManager.load(file.getAbsolutePath());
			if (success) prefs.setRecentDir(file);
			if (isUIready()) ui.resetState();
			return success;
		}
		return false;
	}

	protected boolean isUnsavedChanges() {
		return pathAndFillManager.unsavedPaths && pathAndFillManager.size() > 0;
	}

	protected void setUnsavedChanges(final boolean b) {
		pathAndFillManager.unsavedPaths = b;
	}

	public PathAndFillManager getPathAndFillManager() {
		return pathAndFillManager;
	}

	InteractiveTracerCanvas getXYCanvas() {
		return xy_tracer_canvas;
	}

	InteractiveTracerCanvas getXZCanvas() {
		return xz_tracer_canvas;
	}

	InteractiveTracerCanvas getZYCanvas() {
		return zy_tracer_canvas;
	}

	public Dataset getDataset() {
		return (getImagePlus() == null) ? null : convertService.convert(getImagePlus(), Dataset.class);
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

	protected void dispose() {
		getPrefs().savePluginPrefs(true);
		// dispose data structures
		cancelSearch(true); // will discard tracerThreadPool, fillerThreadPool, currentSearchThread, manualSearchThread, tubularGeodesicsThread
		flushSecondaryData(); // will discard secondaryData
		if (searchArtists != null) searchArtists.clear();
		if (fillerSet != null) fillerSet.clear();
		if (pathAndFillManager != null) pathAndFillManager.dispose();
		if (univ != null && univ.getWindow() != null) univ.getWindow().dispose();
		if (ui != null) ui.dispose();
		notifyListeners(new SNTEvent(SNTEvent.QUIT));
		closeAndResetAllPanes();
		colorImage = null;
		ctSlice3d = null;
		currentPath = null;
		editingPath = null;
		fillerSet = null;
		imageContent = null;
		labelData = null;
		materialList = null;
		previousEditingPath = null;
		searchArtists = null;
		selectedColor = deselectedColor = null;
		selectedColor3f = deselectedColor3f = null;
		temporaryPath = null;
		ui = null;
		univ = null;
		xy_tracer_canvas = null;
		xz_tracer_canvas = null;
		zy_tracer_canvas = null;
		SNTUtils.setPlugin(null);
	}

	public void cancelSearch(final boolean cancelFillToo) {
		// TODO: make this better
		if (tracerThreadPool != null) {
			tracerThreadPool.shutdownNow();
			try {
				// FIXME: interrupting a search can fail if the search is waiting on a get() call for a
				//  DiskCachedCellImg. The search thread only checks itself for interruption at the start of each iteration
				//  of the main while-loop. Increasing the timeout is just a temporary band-aid until we find a
				//  proper solution...
				final long timeout = 10L;
				final boolean terminated = tracerThreadPool.awaitTermination(timeout, TimeUnit.SECONDS);
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
		if (manualSearchThread != null) {
			manualSearchThread = null;
		}
		if (tubularGeodesicsThread != null) {
			tubularGeodesicsThread.requestStop();
			tubularGeodesicsThread = null;
		}
		if (cancelFillToo && fillerThreadPool != null) {
			stopFilling();
			discardFill();
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

	protected synchronized void saveFill() {
		if (fillerSet.isEmpty()) {
			throw new IllegalArgumentException("No fills available.");
		}

		for (final FillerThread fillerThread : fillerSet) {
			pathAndFillManager.addFill(fillerThread.getFill());
			removeThreadToDraw(fillerThread);
		}
		fillerSet.clear();
		pathAndFillManager.getLoadedFills().clear();
		fillerThreadPool = null;
		changeUIState(SNTUI.WAITING_TO_START_PATH);
		if (getUI() != null)
			getUI().getFillManager().changeState(FillManagerUI.State.READY);
	}

	protected synchronized void discardFill() {
		if (fillerSet.isEmpty()) {
			SNTUtils.log("No Fill(s) to discard...");
		}
		for (FillerThread filler : fillerSet) {
			removeThreadToDraw(filler);
		}
		fillerSet.clear();
		pathAndFillManager.getLoadedFills().clear();
		fillerThreadPool = null;
		changeUIState(SNTUI.WAITING_TO_START_PATH);
		if (getUI() != null)
			getUI().getFillManager().changeState(FillManagerUI.State.READY);
	}

	protected synchronized void stopFilling() {

		if (fillerThreadPool == null) {
			SNTUtils.log("No filler threads are currently running.");
			return;
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
				getUI().getFillManager().changeState(FillManagerUI.State.STOPPED);
		}

	}

	protected synchronized void startFilling() {
		if (fillerSet.isEmpty()) {
			throw new IllegalArgumentException("No Filters loaded");
		}
		if (fillerThreadPool != null) {
			throw new IllegalArgumentException("Filler already running");
		}
		if (getUI() != null)
			getUI().getFillManager().changeState(FillManagerUI.State.STARTED);
		fillerThreadPool = Executors.newFixedThreadPool(Math.max(1, SNTPrefs.getThreads()));
		final List<Future<?>> futures = new ArrayList<>();
		for (final FillerThread fillerThread : fillerSet) {
			final Future<?> result = fillerThreadPool.submit(fillerThread);
			futures.add(result);
		}
		SwingWorker<Object, Object> worker = new SwingWorker<>() {
			@Override
			protected Object doInBackground() throws Exception {
				for (final Future<?> future : futures) {
					future.get();
				}
				return null;
			}

			@Override
			protected void done() {
				// FIXME: this is a bad solution to make sure we get the correct state when cancelling
				stopFilling();
				if (ui != null) {
					if (fillerSet.isEmpty()) {
						// This means someone called discardFills() before all future tasks returned
						ui.getFillManager().changeState(FillManagerUI.State.READY);
					} else {
						boolean allSucceeded = fillerSet.stream()
								.noneMatch(f -> f.getExitReason() == SearchThread.CANCELLED);
						ui.getFillManager().changeState(
								allSucceeded ? FillManagerUI.State.ENDED : FillManagerUI.State.STOPPED);
					}
				}
			}
		};
		worker.execute();

	}

	/* Listeners */
	protected List<SNTListener> listeners = Collections.synchronizedList(
			new ArrayList<>());

	public void addListener(final SNTListener listener) {
		listeners.add(listener);
	}

	public void notifyListeners(final SNTEvent event) {
		for (final SNTListener listener : listeners.toArray(new SNTListener[0])) {
			listener.onEvent(event);
		}
	}

	protected boolean anyListeners() {
		return !listeners.isEmpty();
	}

	/*
	 * Now a couple of callback methods, which get information about the progress of
	 * the search.
	 */

	@Override
	public void finished(final SearchInterface source, final boolean success) {

		if (source == currentSearchThread ||  source == tubularGeodesicsThread || source == manualSearchThread)
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

	protected void justDisplayNearSlices(final boolean value, final int eitherSide) {
		getXYCanvas().just_near_slices = value;
        getXYCanvas().eitherSide = eitherSide;
        if (!single_pane) {
			getXZCanvas().just_near_slices = value;
			getZYCanvas().just_near_slices = value;
            getXZCanvas().eitherSide = eitherSide;
            getZYCanvas().eitherSide = eitherSide;
        }
		updateTracingViewers(false);
	}

	protected boolean uiReadyForModeChange() {
		return isUIready() && (getUIState() == SNTUI.WAITING_TO_START_PATH ||
			getUIState() == SNTUI.TRACING_PAUSED);
	}

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
				"Only non-fitted paths can be edited.<br>Run \"Refine›Un-fit Path\" to proceed");
			return false;
		}
		return uiReady && pathExists && validPath;
	}

	protected void setEditingPath(final Path path) {
		if (previousEditingPath != null) {
			previousEditingPath.setEditableNode(-1);
			previousEditingPath.setEditableNodeLocked(false);
		}
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
			// We used to automatically enable hiding of out-of-focus nodes.
			// But without notifying user, this seems not intuitive, so disabling it for now.
//			if (isUIready() && !getUI().nearbySlices()) getUI().togglePartsChoice();
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
		getXYCanvas().setEditMode(enable);
		if (!single_pane) {
			getXZCanvas().setEditMode(enable);
			getZYCanvas().setEditMode(enable);
		}
		updateTracingViewers(false);
	}

	protected void pause(final boolean pause, final boolean hideSideViewsOnPause) {
		if (pause) {
			if (ui != null && ui.getState() != SNTUI.SNT_PAUSED && !uiReadyForModeChange()) {
				guiUtils.error("Please finish/abort current task before pausing SNT.");
				return;
			}
			if (xy != null && accessToValidImageData())
				xy.setProperty("snt-changes", xy.changes);
			changeUIState(SNTUI.SNT_PAUSED);
			disableEventsAllPanes(true);
			setDrawCrosshairsAllPanes(false);
			setCanvasLabelAllPanes(InteractiveTracerCanvas.SNT_PAUSED_LABEL);
			if (hideSideViewsOnPause) {
				setSideViewsVisible(false);
				getPrefs().setTemp("restoreviews", true);
			}
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
			setSideViewsVisible(getPrefs().getTemp("restoreviews", true));
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
		getXYCanvas().updateCursor(new_x, new_y, new_z);
		if (!single_pane) {
			getXZCanvas().updateCursor(new_x, new_y, new_z);
			getZYCanvas().updateCursor(new_x, new_y, new_z);
		}

	}

	public synchronized void loadLabelsFile(final String path) {

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
	protected synchronized boolean loadTracesFile(File file) {
		if (file == null)
			file = ui.openReconstructionFile("traces");
		if (file == null)
			return false; // user pressed cancel;
		if (!file.exists()) {
			guiUtils.error(file.getAbsolutePath() + " is no longer available");
			return false;
		}
		final int guessedType = pathAndFillManager.guessTracesFileType(file.getAbsolutePath());
        return switch (guessedType) {
            case PathAndFillManager.TRACES_FILE_TYPE_COMPRESSED_XML ->
                    pathAndFillManager.loadCompressedXML(file.getAbsolutePath());
            case PathAndFillManager.TRACES_FILE_TYPE_UNCOMPRESSED_XML ->
                    pathAndFillManager.loadUncompressedXML(file.getAbsolutePath());
            default -> {
                guiUtils.error(file.getAbsolutePath() + " is not a valid traces file.");
                yield false;
            }
        };
	}

	@SuppressWarnings("unused")
	protected synchronized boolean loadSWCFile(File file) {
		if (getUI() != null) {
			// backwards compatibility
			getUI().runCommand("SWC...", file.getAbsolutePath());
			return false; // no way to know if file was actually imported via GUI
		}
		final int guessedType = pathAndFillManager.guessTracesFileType(file.getAbsolutePath());
		if (guessedType == PathAndFillManager.TRACES_FILE_TYPE_SWC) {
			return pathAndFillManager.importSWC(file.getAbsolutePath(), false, 0, 0, 0, 1, 1, 1, 1, false);
		} else {
			error(file.getAbsolutePath() + " does not seem to contain valid SWC data.");
		}
		return false;
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
		else if (editing && !editingPath.isEditableNodeLocked()) {
			// find the nearest node to this cursor 2D position.
			// then activate the Z-slice of the retrieved node
			final int eNode = editingPath.indexNearestToCanvasPosition2D(x, y,
					getXYCanvas().nodeDiameter());
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
	public synchronized void setTemporaryPath(final Path path) {

		final Path oldTemporaryPath = this.temporaryPath;

		getXYCanvas().setTemporaryPath(path);
		if (!single_pane) {
			getZYCanvas().setTemporaryPath(path);
			getXZCanvas().setTemporaryPath(path);
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
	public synchronized void setCurrentPath(final Path path) {
		final Path oldCurrentPath = this.currentPath;
		currentPath = path;
		if (currentPath != null) {
			if (pathAndFillManager.getPathFromID(currentPath.getID()) == null)
				currentPath.setName("Current Path");
			path.setSelected(true); // so it is rendered as an active path
		}
		getXYCanvas().setCurrentPath(path);
		if (!single_pane) {
			getZYCanvas().setCurrentPath(path);
			getXZCanvas().setCurrentPath(path);
		}
		if (use3DViewer) {
			if (oldCurrentPath != null) {
				oldCurrentPath.removeFrom3DViewer(univ);
			}
			if (currentPath != null) currentPath.addTo3DViewer(univ, getXYCanvas()
				.getTemporaryPathColor(), null);
		}
	}

	public synchronized Path getCurrentPath() {
		return currentPath;
	}

	protected void setPathUnfinished(final boolean unfinished) {

		this.pathUnfinished = unfinished;
		pathAndFillManager.unsavedPaths = true;
		getXYCanvas().setPathUnfinished(unfinished);
		if (!single_pane) {
			getZYCanvas().setPathUnfinished(unfinished);
			getXZCanvas().setPathUnfinished(unfinished);
		}
	}

	void addThreadToDraw(final SearchInterface s) {
		SearchArtist artist = new SearchArtistFactory().create(s);
		searchArtists.put(s, artist);
		getXYCanvas().addSearchArtist(artist);
		if (!single_pane) {
			getZYCanvas().addSearchArtist(artist);
			getXZCanvas().addSearchArtist(artist);
		}
	}

	void removeThreadToDraw(final SearchInterface s) {
		SearchArtist artist = searchArtists.get(s);
		if (artist == null) return;
		getXYCanvas().removeSearchArtist(artist);
		if (!single_pane) {
			getZYCanvas().removeSearchArtist(artist);
			getXZCanvas().removeSearchArtist(artist);
		}
	}

	/**
	 * Rasterizes centerline of paths into an ImagePlus
	 * @param labelsImage If true, each path has a unique intensity; otherwise all nodes are set to 255 (8-bit Binary)
	 * @return the ImagePlus with embedded centerlines
	 */
	public synchronized ImagePlus makePathVolume(final Collection<Path> paths, final boolean labelsImage) {

		final short[][] snapshot_data = new short[depth][];

		for (int i = 0; i < depth; ++i)
			snapshot_data[i] = new short[width * height];

		pathAndFillManager.setPathPointsInVolume(paths, snapshot_data, (labelsImage) ? (short)-1 : (short) 255, width);

		final ImageStack newStack = new ImageStack(width, height);

		for (int i = 0; i < depth; ++i) {
			final ShortProcessor thisSlice = new ShortProcessor(width, height);
			thisSlice.setPixels(snapshot_data[i]);
			newStack.addSlice(null, thisSlice.convertToByteProcessor(false));
		}

		final ImagePlus newImp = new ImagePlus(xy.getShortTitle() +
			" Rendered Paths", newStack);
		newImp.setCalibration(getCalibration());
		return newImp;
	}

	/* Start a search thread looking for the goal in the arguments: */
	synchronized Future<?> testPathTo(final double world_x, final double world_y,
									  final double world_z, final PointInImage joinPoint) {
		return testPathTo(world_x, world_y, world_z, joinPoint, -1); // GUI execution
	}

	private synchronized Future<?> testPathTo(final double world_x,
											  final double world_y,
											  final double world_z,
											  final PointInImage joinPoint,
											  final int minPathSize)
	{

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
		if (joinPoint == null) {
			real_x_end = world_x;
			real_y_end = world_y;
			real_z_end = world_z;
		} else {
			real_x_end = joinPoint.x;
			real_y_end = joinPoint.y;
			real_z_end = joinPoint.z;
		}

		addSphere(
				targetBallName,
				real_x_end,
				real_y_end,
				real_z_end,
				getXYCanvas().getTemporaryPathColor(),
				x_spacing * ballRadiusMultiplier);

		final int x_start = (int) Math.round(last_start_point_x);
		final int y_start = (int) Math.round(last_start_point_y);
		final int z_start = (int) Math.round(last_start_point_z);

		final int x_end = (int) Math.round(real_x_end / x_spacing);
		final int y_end = (int) Math.round(real_y_end / y_spacing);
		final int z_end = (int) Math.round(real_z_end / z_spacing);

		if (tracerThreadPool == null || tracerThreadPool.isShutdown()) {
			tracerThreadPool = Executors.newSingleThreadExecutor();
		}

		if (tubularGeodesicsTracingEnabled) {

			// Then useful values are:
			// oofFile.getAbsolutePath() - the filename of the OOF file
			// last_start_point_[xyz] - image coordinates of the start point
			// [xyz]_end - image coordinates of the end point

			// [xyz]_spacing

			tubularGeodesicsThread = new TubularGeodesicsTracer(
					secondaryImageFile,
					x_start,
					y_start,
					z_start,
					x_end,
					y_end,
					z_end,
					x_spacing,
					y_spacing,
					z_spacing,
					spacing_units);
			tubularGeodesicsThread.addProgressListener(this);
			return tracerThreadPool.submit(tubularGeodesicsThread);
		}

		if (!isAstarEnabled()) {
			manualSearchThread = new ManualTracerThread(
					this,
					last_start_point_x,
					last_start_point_y,
					last_start_point_z,
					x_end,
					y_end,
					z_end);
			manualSearchThread.addProgressListener(this);
			return tracerThreadPool.submit(manualSearchThread);
		}

		currentSearchThread = createSearch(x_start, y_start, z_start, x_end, y_end, z_end);
		addThreadToDraw(currentSearchThread);
		currentSearchThread.addProgressListener(this);
		return tracerThreadPool.submit(currentSearchThread);
	}

	protected <T extends RealType<T>> ImageStatistics computeImgStats(final Iterable<T> in,
																	final ImageStatistics imgStats) {
		final Pair<T, T> minMax = opService.stats().minMax(in);
		imgStats.min = minMax.getA().getRealDouble();
		imgStats.max = minMax.getB().getRealDouble();
		imgStats.mean = opService.stats().mean(in).getRealDouble();
		imgStats.stdDev = opService.stats().stdDev(in).getRealDouble();
		SNTUtils.log("Subvolume statistics: min=" + imgStats.min +
				", max=" + imgStats.max +
				", mean=" + imgStats.mean +
				", stdDev=" + imgStats.stdDev);
		return imgStats;
	}

	private <T extends RealType<T>> ImageStatistics computeImgStats(final Iterable<T> in,
																	final ImageStatistics imgStats,
																	final CostType costType)
	{
		switch (costType) {
			case PROBABILITY: {
				imgStats.max = opService.stats().max(in).getRealDouble();
				imgStats.mean = opService.stats().mean(in).getRealDouble();
				imgStats.stdDev = opService.stats().stdDev(in).getRealDouble();
				SNTUtils.log("Subvolume statistics: max=" + imgStats.max +
						", mean=" + imgStats.mean +
						", stdDev=" + imgStats.stdDev);
				break;
			}
			case RECIPROCAL:
			case DIFFERENCE:
			case DIFFERENCE_SQUARED: {
				final Pair<T, T> minMax = opService.stats().minMax(in);
				imgStats.min = minMax.getA().getRealDouble();
				imgStats.max = minMax.getB().getRealDouble();
				SNTUtils.log("Subvolume statistics: min=" + imgStats.min +
						", max=" + imgStats.max);
				break;
			}
			default: {
				final Pair<T, T> minMax = opService.stats().minMax(in);
				imgStats.min = minMax.getA().getRealDouble();
				imgStats.max = minMax.getB().getRealDouble();
				imgStats.mean = opService.stats().mean(in).getRealDouble();
				imgStats.stdDev = opService.stats().stdDev(in).getRealDouble();
				SNTUtils.log("Subvolume statistics: min=" + imgStats.min +
						", max=" + imgStats.max +
						", mean=" + imgStats.mean +
						", stdDev=" + imgStats.stdDev);
			}
		}
		if (imgStats.min == imgStats.max) {
			// This can happen if the image data in the bounding box between the start and goal is uniform
			//  (e.g., a black region)
			imgStats.min = 0;
			imgStats.max = Math.pow(2, 16) - 1;
		}
		return imgStats;
	}

	private AbstractSearch createSearch(final double world_x_start,
										final double world_y_start,
										final double world_z_start,
										final double world_x_end,
										final double world_y_end,
										final double world_z_end)
	{
		return createSearch(
				(int) Math.round(world_x_start / x_spacing),
				(int) Math.round(world_y_start / y_spacing),
				(int) Math.round(world_z_start / z_spacing),
				(int) Math.round(world_x_end / x_spacing),
				(int) Math.round(world_y_end / y_spacing),
				(int) Math.round(world_z_end / z_spacing));
	}

	/* This method uses the plugin's current search parameters to construct an isolated A* search instance using
	 * the given start and end voxel coordinates. */

	private <T extends RealType<T>> AbstractSearch createSearch(final int x_start,
																final int y_start,
																final int z_start,
																final int x_end,
																final int y_end,
																final int z_end)
	{
		final boolean useSecondary = isTracingOnSecondaryImageActive();

		final RandomAccessibleInterval<T> img = useSecondary ? getSecondaryData() : getLoadedData();

		final ImageStatistics imgStats = useSecondary ? statsSecondary : stats;
		if (isUseSubVolumeStats)
		{
			SNTUtils.log("Computing local statistics...");
			computeImgStats(
					ImgUtils.subInterval(img, new Point(x_start, y_start, z_start), new Point(x_end, y_end, z_end), 10),
					imgStats, costType);
		}

		Cost costFunction;
		switch (costType)
		{
			case RECIPROCAL:
				costFunction = new Reciprocal(imgStats.min, imgStats.max);
				break;
			case PROBABILITY:
				OneMinusErf cost = new OneMinusErf(imgStats.max, imgStats.mean, imgStats.stdDev);
				cost.setZFudge(oneMinusErfZFudge);
				costFunction = cost;
				break;
			case DIFFERENCE:
				costFunction = new Difference(imgStats.min, imgStats.max);
				break;
			case DIFFERENCE_SQUARED:
				costFunction = new DifferenceSq(imgStats.min, imgStats.max);
				break;
			default:
				throw new IllegalArgumentException("BUG: Unknown cost function " + costType);
		}

		Heuristic heuristic = switch (heuristicType) {
            case EUCLIDEAN -> new Euclidean(getCalibration());
            case DIJKSTRA -> new Dijkstra();
            default -> throw new IllegalArgumentException("BUG: Unknown heuristic " + heuristicType);
        };

        return switch (searchType) {
            case ASTAR -> new TracerThread(
                    this,
                    img,
                    x_start,
                    y_start,
                    z_start,
                    x_end,
                    y_end,
                    z_end,
                    costFunction,
                    heuristic);
            case NBASTAR -> new BiSearch(
                    this,
                    img,
                    x_start,
                    y_start,
                    z_start,
                    x_end,
                    y_end,
                    z_end,
                    costFunction,
                    heuristic);
            default -> throw new IllegalArgumentException("BUG: Unknown search class");
        };
	}

	public synchronized void confirmTemporary(final boolean updateTracingViewers) {

		if (temporaryPath == null)
			// Just ignore the request to confirm a path (there isn't one):
			return;

		currentPath.add(temporaryPath);

		final PointInImage last = currentPath.lastNode();
		last_start_point_x = (int) Math.round(last.x / x_spacing);
		last_start_point_y = (int) Math.round(last.y / y_spacing);
		last_start_point_z = (int) Math.round(last.z / z_spacing);

		{
			setTemporaryPath(null);
			changeUIState(SNTUI.PARTIAL_PATH);
			if (updateTracingViewers)
				updateTracingViewers(true);
		}

		/*
		 * This has the effect of removing the path from the 3D viewer and adding it
		 * again:
		 */
		setCurrentPath(currentPath);
	}

	public synchronized void cancelTemporary() {

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
		setTemporaryPath(null);
		updateTracingViewers(false);
	}

	/**
	 * Cancels the temporary path.
	 */
	public synchronized void cancelPath() {

		// Is there an unconfirmed path? If so, warn people about it...
		if (temporaryPath != null) {
			discreteMsg(
				"You need to confirm the last segment before canceling the path.");
			return;
		}

		if (currentPath != null && currentPath.parentPath != null) {
			currentPath.detachFromParent();
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
	 * Constructs and enables a lazy instance of a {@link Tubeness} filter. The image is filtered locally during path
	 * searches. Filtered data is stored in a disk cache when memory runs full. This method is slated for redesign.
	 *
	 * @param image the image to filter, either "primary" or "secondary".
	 * @param sigma the scale parameter for the Tubeness filter, in physical units.
	 * @param max   the maximum pixel intensity in the Tubeness image beyond which the cost function of the A* search
	 *              is minimized.
	 * @param wait  this parameter does nothing.
	 */
	@Deprecated
	public void startHessian(final String image, final double sigma, final double max, final boolean wait)
	{
		startHessian(image, "tubeness", new double[]{sigma}, 0, max, "lazy", SNTPrefs.getThreads());
	}


	/**
	 * Constructs and enables an instance of a hessian eigenvalue filter. If the strategy is "preprocess",
	 * the entire filtered image, including intensity statistics, are pre-computed and stored in memory.
	 * If the strategy is "lazy", the image is filtered and measured locally during path searches,
	 * and filtered data is stored in a disk cache when memory runs full. If you are tracing over a large image or if
	 * you are in a memory-limited environment, you should choose "lazy".
	 *
	 * @param image    the image to filter, either "primary" or "secondary".
	 * @param filter   the hessian filter type, either "tubeness" or "frangi"
	 * @param scales   the scale parameters for the Hessian filter, in physical units. Computation time increases linearly
	 *                 with the number of scales.
	 * @param strategy either "lazy" or "preprocess"
	 * @param nThreads number of threads to use in the computation
	 * @param <T>
	 */
	public <T extends RealType<T>> void startHessian(final String image, final String filter, final double[] scales,
													 final String strategy, final int nThreads)
	{
		// set an initial min max just in case
		startHessian(image, filter, scales, 0, 255, strategy, nThreads);
		if (strategy.equalsIgnoreCase("lazy")) {
			setUseSubVolumeStats(true);
		} else if (strategy.equalsIgnoreCase("preprocess")) {
			final RandomAccessibleInterval<T> data = getSecondaryData();
			computeImgStats(data, getStatsSecondary(), getCostType());
		} else {
			throw new IllegalArgumentException("Unknown strategy: " + strategy);
		}
	}

	/**
	 * Constructs and enables an instance of a hessian eigenvalue filter. If the strategy is "preprocess",
	 * the entire filtered image is pre-computed and stored in memory.
	 * If the strategy is "lazy", the image is filtered locally during path searches, and filtered data is stored in
	 * a disk cache when memory runs full. If you are tracing over a large image or if you are in a memory-limited
	 * environment, you should choose "lazy".
	 *
	 * @param image    the image to filter, either "primary" or "secondary".
	 * @param filter   the hessian filter type, either "tubeness" or "frangi"
	 * @param scales   the scale parameters for the Hessian filter, in physical units. Computation time increases linearly
	 *                 with the number of scales.
	 * @param min      the minimum pixel intensity in the filtered image beyond which the cost function of the A* search is
	 *                 maximized.
	 * @param max      the maximum pixel intensity in the filtered image beyond which the cost function of the A* search
	 *                 is minimized.
	 * @param strategy either "lazy" or "preprocess"
	 * @param nThreads number of threads to use in the computation
	 * @param <T>
	 */
	public <T extends RealType<T>> void startHessian(final String image, final String filter, final double[] scales,
													 final double min, final double max, final String strategy,
													 final int nThreads)
	{
		final boolean useSecondary = "secondary".equalsIgnoreCase(image);
		final RandomAccessibleInterval<T> data = useSecondary ? getSecondaryData() : getLoadedData();
		final double[] spacing = new double[]{getPixelWidth(), getPixelHeight(), getPixelDepth()};
		final ImageStatistics stats = useSecondary ? getStatsSecondary() : getStats();
		final AbstractUnaryComputerOp<RandomAccessibleInterval<T>, RandomAccessibleInterval<FloatType>> op;
		// I'm not using the FilterType enums here since some of them are not hessian-based filters, e.g., gauss.
		if (filter.equalsIgnoreCase("tubeness")) {
			op = new Tubeness<>(scales, spacing, nThreads);
		} else if (filter.equalsIgnoreCase("frangi")) {
			op = new Frangi<>(scales, spacing, stats.max, nThreads);
		} else {
			throw new IllegalArgumentException("Unknown filter: " + filter);
		}
		final RandomAccessibleInterval<FloatType> filtered;
		if (strategy.equalsIgnoreCase("lazy")) {
			filtered = Lazy.process(
				data,
				data,
				new int[]{32, 32, 32},
				new FloatType(),
				op);
		} else if (strategy.equalsIgnoreCase("preprocess")) {
			filtered = opService.create().img(data, new FloatType());
			op.compute(data, filtered);
		} else {
			throw new IllegalArgumentException("Unknown strategy: " + strategy);
		}
		flushSecondaryData();
		loadSecondaryImage(filtered, false);
		setSecondaryImageMinMax(min, max);
		doSearchOnSecondaryData = true;
	}

	/**
	 * Automatically traces a path from a point A to a point B. See
	 * {@link #autoTrace(List, PointInImage)} for details.
	 *
	 * @param start the {@link PointInImage} the starting point of the path
	 * @param end the {@link PointInImage} the terminal point of the path
	 * @param forkPoint the {@link PointInImage} fork point of the parent
	 *          {@link Path} from which the searched path should branch off, or
	 *          null if the path should not have any parent.
	 * @return the path a reference to the computed path.
	 * @see #autoTrace(List, PointInImage)
	 */
	@SuppressWarnings("unused") // used for snt scripts
	public Path autoTrace(final SNTPoint start, final SNTPoint end,
		final PointInImage forkPoint)
	{
		final List<SNTPoint> list = new ArrayList<>();
		list.add(start);
		list.add(end);
		return autoTrace(list, forkPoint);
	}

	/**
	 * Automatically traces a path from a point A to a point B. See
	 * {@link #autoTrace(List, PointInImage)} for details.
	 *
	 * @param start the {@link PointInImage} the starting point of the path
	 * @param end the {@link PointInImage} the terminal point of the path
	 * @param forkPoint the {@link PointInImage} fork point of the parent
	 *          {@link Path} from which the searched path should branch off, or
	 *          null if the path should not have any parent.
	 * @param headless  whether search should occur headless
	 * @return the path a reference to the computed path.
	 * @see #autoTrace(List, PointInImage)
	 */
	@SuppressWarnings("unused") // used for snt scripts
	public Path autoTrace(final SNTPoint start, final SNTPoint end, final PointInImage forkPoint,
						  final boolean headless)
	{
		return autoTrace(List.of(start, end), forkPoint, headless);
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
	 *          Successful, target point will be included in the final path. The
	 *          final path. The first point in the list is the start of the path,
	 *          the last its terminus. Null objects not allowed.
	 * @param forkPoint the {@link PointInImage} fork point of the parent
	 *          {@link Path} from which the searched path should branch off, or
	 *          null if the path should not have any parent.
	 * @return the path a reference to the computed path. It is added to the Path
	 *         Manager list. If a path cannot be fully computed from the specified
	 *         list of points, a single-point path is generated.
	 */
	public Path autoTrace(final List<SNTPoint> pointList, final PointInImage forkPoint)
	{
		if (pointList == null || pointList.isEmpty())
			throw new IllegalArgumentException("pointList cannot be null or empty");

		final boolean existingEnableUIupdates = pathAndFillManager.enableUIupdates;
		pathAndFillManager.enableUIupdates = false;

		// Ensure there are no incomplete tracings around and disable UI
		if (ui != null && ui.getState() != SNTUI.READY) ui.abortCurrentOperation();
		final SNTUI existingUI = getUI();
		changeUIState(SNTUI.SEARCHING);
		ui = null;

		// Start path from first point in list
		final SNTPoint start = pointList.getFirst();
		startPath(start.getX(), start.getY(), start.getZ(), forkPoint);

		final int secondNodeIdx = (pointList.size() == 1) ? 0 : 1;
		final int nNodes = pointList.size();

		// Now keep appending nodes to temporary path
		for (int i = secondNodeIdx; i < nNodes; i++) {
			// Append node and wait for search to be finished
			final SNTPoint node = pointList.get(i);

			Future<?> result = testPathTo(node.getX(), node.getY(), node.getZ(), null);
			try {
				result.get();
			} catch (InterruptedException | ExecutionException e) {
				SNTUtils.error("Error during auto-trace", e);
			}
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

	/**
	 * Automatically traces a path from a point A to a point B. See
	 * {@link #autoTrace(List, PointInImage)} for details.
	 *
	 * @param pointList the list of {@link PointInImage} containing the nodes to
	 *          be used as target goals during the search. If the search cannot
	 *          converge into a target point, such point is omitted from path, if
	 *          Successful, target point will be included in the final path. The
	 *          final path. The first point in the list is the start of the path,
	 *          the last its terminus. Null objects not allowed.
	 * @param forkPoint the {@link PointInImage} fork point of the parent
	 *          {@link Path} from which the searched path should branch off, or
	 *          null if the path should not have any parent.
	 * @param headless  whether search should occur headless
	 * @return the path a reference to the computed path.
	 * @see #autoTrace(List, PointInImage)
	 */
	public Path autoTrace(final List<SNTPoint> pointList, final PointInImage forkPoint, final boolean headless) {
		if (headless || GraphicsEnvironment.isHeadless()) {
			return autoTraceHeadless(pointList, forkPoint);
		}
		return autoTrace(pointList, forkPoint);
	}

	private Path autoTraceHeadless(final List<SNTPoint> pointList, final PointInImage forkPoint) {
		if (pointList == null || pointList.isEmpty())
			throw new IllegalArgumentException("pointList cannot be null or empty");

		if (tracerThreadPool == null || tracerThreadPool.isShutdown()) {
			tracerThreadPool = Executors.newSingleThreadExecutor();
		}

		Path fullPath = new Path(x_spacing, y_spacing, z_spacing, spacing_units);

		// Now keep appending nodes to temporary path
		for (int i = 0; i < pointList.size() - 1; i++) {
			// Append node and wait for search to be finished
			final SNTPoint start = pointList.get(i);
			final SNTPoint end = pointList.get(i + 1);
			AbstractSearch pathSearch = createSearch(
					start.getX(),
					start.getY(),
					start.getZ(),
					end.getX(),
					end.getY(),
					end.getZ());
			Future<?> result = tracerThreadPool.submit(pathSearch);
			Path pathResult = null;
			try {
				result.get();
				pathResult = pathSearch.getResult();
			} catch (InterruptedException | ExecutionException e) {
				SNTUtils.error("Error during auto-trace", e);
			}
			if (pathResult == null) {
				SNTUtils.log("Auto-trace result was null.");
				return null;
			}
			fullPath.add(pathResult);
		}

		if (forkPoint != null) {
			fullPath.setBranchFrom(forkPoint.getPath(), forkPoint);
		}
		return fullPath;
	}

	protected synchronized void replaceCurrentPath(final Path path) {
		if (currentPath != null) {
			discreteMsg("An active temporary path already exists...");
			return;
		}
		lastStartPointSet = true;
		selectPath(path, false);
		setPathUnfinished(true);
		setCurrentPath(path);
		last_start_point_x = (int) Math.round(path.lastNode().x / x_spacing);
		last_start_point_y = (int) Math.round(path.lastNode().y / y_spacing);
		last_start_point_z = (int) Math.round(path.lastNode().z / z_spacing);
		setTemporaryPath(null);
		changeUIState(SNTUI.PARTIAL_PATH);
		updateAllViewers();
	}

	protected synchronized void finishedPath() {

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
			// Branch point will be set when path is connected to parent
			cancelSearch(false);
		}
		else {
			removeSphere(startBallName);
		}

		removeSphere(targetBallName);
		if (pathAndFillManager.getPathFromID(currentPath.getID()) == null)
			pathAndFillManager.addPath(currentPath, true, false, false);
		lastStartPointSet = false;
		if (activateFinishedPath) selectPath(currentPath, false);
		setPathUnfinished(false);
		setCurrentPath(null);

		// ... and change the state of the UI
		changeUIState(SNTUI.WAITING_TO_START_PATH);
		updateTracingViewers(true);
		if (getUI() != null && getUI().getRecorder(false) != null) {
			final String cmmnt = String.format("  (%3f,%.3f,%.3f)\nEnd of new path [%s]",
					last_start_point_x * x_spacing,	last_start_point_y * y_spacing, last_start_point_z * z_spacing,
					pathAndFillManager.getPath(pathAndFillManager.size()-1).getName());
			getUI().getRecorder(false).recordComment(cmmnt);
		}
	}

	protected synchronized void clickForTrace(final Point3d p, final boolean join) {
		final double x_unscaled = p.x / x_spacing;
		final double y_unscaled = p.y / y_spacing;
		final double z_unscaled = p.z / z_spacing;
		setZPositionAllPanes((int) x_unscaled, (int) y_unscaled, (int) z_unscaled);
		clickForTrace(p.x, p.y, p.z, join);
	}

	protected synchronized void clickForTrace(final double world_x,
		final double world_y, final double world_z, final boolean join)
	{

		// In some of the states this doesn't make sense; check for them:
		if (currentSearchThread != null || temporaryPath != null)
			return;
		if (!fillerSet.isEmpty()) {
			setFillThresholdFrom(world_x, world_y, world_z);
			return;
		}

		PointInImage joinPoint = null;
		if (join) {
			joinPoint = pathAndFillManager.nearestJoinPointOnSelectedPaths(world_x /
					x_spacing, world_y / y_spacing, world_z / z_spacing);
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
					getUI().reset();
				}
				SNTUtils.error(ex.getMessage(), ex);
			} finally {
				if (getUI() != null && getUI().getRecorder(false) != null) {
					final String cmmnt = String.format("  (%3f,%.3f,%.3f)", world_x, world_y, world_z);
					getUI().getRecorder(false).recordComment(cmmnt);
				}
			}
		}
		else {
			/* This is an initial point. */
			if (autoCT && (channel != xy.getC() || frame != xy.getT())) {
				reloadImage(xy.getC(), xy.getT());
				if (ui != null) ui.ctPositionChanged();
			}
			startPath(world_x, world_y, world_z, joinPoint);
			changeUIState(SNTUI.PARTIAL_PATH);
			if (getUI() != null && getUI().getRecorder(false) != null) {
				String cmmnt = String.format("Start of new path\n  (%3f,%.3f,%.3f); fork point: %s", world_x, world_y,
						world_z, ((joinPoint == null) ? "none" : joinPoint));
				getUI().getRecorder(false).recordComment(cmmnt);
			}
		}

	}

	protected synchronized void clickForTrace(final double x_in_pane_precise,
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

	public void setStoreExtraFillNodes(final boolean storeExtraFillNodes) {
		fillerSet.forEach(f -> f.setStoreExtraNodes(storeExtraFillNodes));
	}

	public void setStopFillAtThreshold(final boolean stopFillAtThreshold) {
		fillerSet.forEach(f -> f.setStopAtThreshold(stopFillAtThreshold));
	}

	synchronized void startPath(final double world_x, final double world_y,
		final double world_z, final PointInImage joinPoint)
	{

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
			path.setBranchFrom(joinPoint.onPath, joinPoint);
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
			univ.addTriangleMesh(sphere, Utils.toColor3f(color), name);
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
		SwingUtilities.invokeLater(() -> {
			setZPositionAllPanes((int) Math.round(centerScaled.x), (int) Math.round(centerScaled.y),
					(int) Math.round(centerScaled.z));
			setShowOnlySelectedPaths(false);
			SNTUtils.log("Starting Sholl Analysis centered at " + centerScaled);
			final Map<String, Object> input = new HashMap<>();
			input.put("snt", this);
			input.put("center", centerScaled);
            final Collection<Tree> trees = (getUI() == null) ? getPathAndFillManager().getTrees() :
                    getUI().getPathManager().getMultipleTrees();
            if (trees == null) return;
			input.put("tree", Tree.merge(trees));
			final CommandService cmdService = getContext().getService(CommandService.class);
			cmdService.run(ShollAnalysisTreeCmd.class, true, input);
		});
	}

	public ImagePlus getFilledBinaryImp() {
		if (fillerSet.isEmpty()) return null;
		final FillConverter converter = new FillConverter(fillerSet);
		final RandomAccessibleInterval<BitType> out = Util.getSuitableImgFactory(getLoadedData(), new BitType())
				.create(getLoadedData());
		converter.convertBinary(out);
		final ImagePlus imp = ImgUtils.raiToImp(out, "Fill");
		imp.copyScale(getImagePlus());
		imp.resetDisplayRange();
		return imp;
	}

	public <T extends RealType<T>> ImagePlus getFilledImp() {
		if (fillerSet.isEmpty()) return null;
		final FillConverter converter = new FillConverter(fillerSet);
		final RandomAccessibleInterval<T> in = getLoadedData();
		final RandomAccessibleInterval<T> out = Util.getSuitableImgFactory(in, in.getType()).create(in);
		converter.convert(in, out);
		final ImagePlus imp = ImgUtils.raiToImp(out, "Fill");
		imp.copyScale(getImagePlus());
		imp.resetDisplayRange();
		return imp;
	}

	public ImagePlus getFilledDistanceImp() {
		if (fillerSet.isEmpty()) return null;
		final FillConverter converter = new FillConverter(fillerSet);
		final RandomAccessibleInterval<FloatType> out = Util.getSuitableImgFactory(
				getLoadedData(), new FloatType()).create(getLoadedData());
		converter.convertDistance(out);
		final ImagePlus imp = ImgUtils.raiToImp(out, "Fill");
		imp.copyScale(getImagePlus());
		ImpUtils.applyColorTable(imp, ColorTables.FIRE);
		imp.resetDisplayRange();
		return imp;
	}

	@SuppressWarnings("unchecked")
	public <T extends IntegerType<T>> ImagePlus getFilledLabelImp() {
		if (fillerSet.isEmpty())
			return null;
		final RandomAccessibleInterval<T> out;
		final T t;
		if (fillerSet.size() < Math.pow(2, 8)) {
			t = (T) new UnsignedByteType();
		} else if (fillerSet.size() < Math.pow(2, 16)) {
			t = (T) new UnsignedShortType();
		} else if (fillerSet.size() < Math.pow(2, 32)) {
			t = (T) new UnsignedIntType();
		} else {
			t = (T) new UnsignedLongType();
		}
		out = Util.getSuitableImgFactory(getLoadedData(), t).create(getLoadedData());
 		final FillConverter converter = new FillConverter(fillerSet);
 		converter.convertLabels(out);
		final ImagePlus imp = ImgUtils.raiToImp(out, "Fill");
		imp.copyScale(getImagePlus());
		imp.resetDisplayRange();
		ImpUtils.setLut(imp, "glasbey_on_dark");
		return imp;
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

	public synchronized void initPathsToFill(final Set<Path> fromPaths) {
		fillerSet.clear();
		pathAndFillManager.getLoadedFills().clear();
		final boolean useSecondary = isTracingOnSecondaryImageActive();
		final RandomAccessibleInterval<? extends RealType<?>> data = useSecondary ? getSecondaryData() : getLoadedData();
		final ImageStatistics imgStats = useSecondary ? getStatsSecondary() : getStats();
		Cost costFunction;
		switch (costType) {
			case RECIPROCAL:
				if (invalidStatsError(useSecondary)) {
					return;
				}
				costFunction = new Reciprocal(imgStats.min, imgStats.max);
				break;
			case PROBABILITY:
				if (invalidStatsError(useSecondary) && imgStats.stdDev == 0) {
					return;
				}
				costFunction = new OneMinusErf(imgStats.max, imgStats.mean, imgStats.stdDev);
				break;
			case DIFFERENCE:
				if (invalidStatsError(useSecondary)) {
					return;
				}
				costFunction = new Difference(imgStats.min, imgStats.max);
				break;
			case DIFFERENCE_SQUARED:
				if (invalidStatsError(useSecondary)) {
					return;
				}
				costFunction = new DifferenceSq(imgStats.min, imgStats.max);
				break;
			default:
				throw new IllegalArgumentException("BUG: Unrecognized cost function " + costType);
		}
		final FillerThread filler = new FillerThread(
				data,
				getCalibration(),
				fillThresholdDistance,
				1000,
				costFunction);
		addThreadToDraw(filler);
		filler.addProgressListener(this);
		if (getUI() != null) filler.addProgressListener(ui.getFillManager());
		filler.setSourcePaths(fromPaths);
		fillerSet.add(filler);
		if (getUI() != null) ui.setFillListVisible(true);
		changeUIState(SNTUI.FILLING_PATHS);
	}

	protected <T extends RealType<T>> boolean invalidStatsError(final boolean isSecondary) {
		final boolean invalidStats = (isSecondary) ? getStatsSecondary().max == 0 : getStats().max == 0;
		final boolean compute = invalidStats && getUI() != null && getUI().guiUtils.getConfirmation(
				"Statistics for the " + (isSecondary ? "Secondary Layer" : "main image") //
						+ " have not been computed yet, but are required to better understand the image being traced. "
						+ "You can either compute them now for the whole image, or you can dismiss this prompt and "
						+ "trace a (small) path over a relevant feature, which will compute statistics locally.", //
				"Unknown Image Statistics", "Compute Now", "Dismiss");
		if (compute) {
			final RandomAccessibleInterval<T> data = (isSecondary) ? getSecondaryData() : getLoadedData();
			computeImgStats(data, (isSecondary) ? getStatsSecondary() : getStats(), CostType.RECIPROCAL);
		} else if (getUI() == null) {
			error("Statistics for the " + (isSecondary ? "Secondary Layer" : "main image")
					+ " have not been computed yet. Please trace small path over a relevant feature to compute them.");
		}
		return (isSecondary) ? getStatsSecondary().max == 0 : getStats().max == 0;
	}

	protected void setFillTransparent(final boolean transparent) {
		getXYCanvas().setFillTransparent(transparent);
		if (!single_pane) {
			getXZCanvas().setFillTransparent(transparent);
			getZYCanvas().setFillTransparent(transparent);
		}
		searchArtists.values().stream()
				.filter(a -> a instanceof FillerThreadArtist)
				.forEach(a -> ((FillerThreadArtist) a)
						.setOpacity(transparent ? 50 : 100));

	}

	public double getMinimumSeparation() {
		return (is2D()) ? Math.min(Math.abs(x_spacing), Math.abs(y_spacing))
				: Math.min(Math.abs(x_spacing), Math.min(Math.abs(y_spacing), Math.abs(z_spacing)));
	}

	public double getAverageSeparation() {
		return (is2D()) ? (x_spacing + y_spacing) / 2 : (x_spacing + y_spacing + z_spacing) / 3;
	}

	/**
	 * Retrieves the pixel data of the main image currently loaded in memory as an
	 * ImagePlus object. Returned image is always a single channel image.
	 *
	 * @return the loaded data corresponding to the C,T position currently being
	 *         traced, or null if no image data has been loaded into memory.
	 */
	public <T extends RealType<T>> ImagePlus getLoadedDataAsImp() {
		if (ctSlice3d == null)
			return null;
		final RandomAccessibleInterval<T> data = getLoadedData();
		final ImagePlus imp = ImgUtils.raiToImp(data, "LoadedData");
		imp.copyScale(xy);
		imp.resetDisplayRange();
		setIsCachedData(imp);
		return imp;
	}

	@SuppressWarnings("unchecked")
	public <T extends RealType<T>> RandomAccessibleInterval<T> getLoadedData() {
		return (ctSlice3d == null) ? null : Views.dropSingletonDimensions(ctSlice3d);
	}

	@SuppressWarnings("rawtypes")
	public <T> IterableInterval getLoadedIterable() {
		return ctSlice3d;
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
	public boolean isSecondaryDataAvailable() {
		return getSecondaryData() != null;
	}

	protected boolean isSecondaryImageFileLoaded() {
		return secondaryImageFile != null;
	}

	protected boolean isTracingOnSecondaryImageAvailable() {
		return isSecondaryDataAvailable() || tubularGeodesicsTracingEnabled;
	}

	/**
	 * Specifies the 'secondary image' to be used during a tracing session.
	 *
	 * @param file The file containing the 'secondary image'
	 */
	public void setSecondaryImage(final File file) {
		secondaryImageFile = file;
		if (ui != null) ui.updateSecLayerWidgets();
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
	 * @see #isSecondaryDataAvailable()
	 * @see #getSecondaryDataAsImp()
	 */
	public void loadSecondaryImage(final File file) throws IOException, IllegalArgumentException {
		final ImagePlus imp = openCachedDataImage(file);
		loadSecondaryImage(imp, true);
		setSecondaryImage(isSecondaryDataAvailable() && isSecondaryImageFileLoaded() ? file : null);
	}

	public void loadSecondaryImage(final ImagePlus imp) throws IllegalArgumentException {
		loadSecondaryImage(imp, true);
	}

	public <T extends RealType<T>> void loadSecondaryImage(final RandomAccessibleInterval<T> img,
														   final boolean computeStatistics)
	{
		loadSecondaryImage(img, true, computeStatistics);
	}

	public void setSecondaryImageMinMax(final double min, final double max) {
		statsSecondary.min = min;
		statsSecondary.max = max;
	}

	public double[] getSecondaryImageMinMax() {
		return new double[] { statsSecondary.min, statsSecondary.max };
	}

	protected void loadSecondaryImage(final ImagePlus imp, final boolean changeUIState) {
		assert imp != null;
		if (secondaryImageFile != null && secondaryImageFile.getName().toLowerCase().contains(".oof")) {
			showStatus(0, 0, "Optimally Oriented Flux image detected");
			SNTUtils.log("Optimally Oriented Flux image detected. Image won't be cached...");
			tubularGeodesicsTracingEnabled = true;
			return;
		}
		if (changeUIState) changeUIState(SNTUI.CACHING_DATA);
		imp.setPosition( channel, xy.getSlice(), frame ); // important! sets the channel/frame to be imported. Does nothing if image is not a hyperstack
		secondaryData = ImgUtils.getCtSlice(imp);
		SNTUtils.log("Secondary data dimensions: " +
				Arrays.toString(Intervals.dimensionsAsLongArray(secondaryData)));
		ImageStatistics imgStats = imp.getStatistics(ImageStatistics.MIN_MAX | ImageStatistics.MEAN |
				ImageStatistics.STD_DEV);
		statsSecondary.min = imgStats.min;
		statsSecondary.max = imgStats.max;
		statsSecondary.mean = imgStats.mean;
		statsSecondary.stdDev = imgStats.stdDev;
		File file = null;
		if ((imp.getFileInfo() != null)) {
			file = new File(imp.getFileInfo().directory, imp.getFileInfo().fileName);
		}
		setSecondaryImage(file);
		enableSecondaryLayerTracing(true);
		if (changeUIState) {
			changeUIState(SNTUI.WAITING_TO_START_PATH);
		}
	}

	protected <T extends RealType<T>> void loadSecondaryImage(final RandomAccessibleInterval<T> img,
															  final boolean changeUIState,
															  final boolean computeStatistics)
	{
		assert img != null;
		if (secondaryImageFile != null && secondaryImageFile.getName().toLowerCase().contains(".oof")) {
			showStatus(0, 0, "Optimally Oriented Flux image detected");
			SNTUtils.log("Optimally Oriented Flux image detected. Image won't be cached...");
			tubularGeodesicsTracingEnabled = true;
			return;
		}
		if (changeUIState) changeUIState(SNTUI.CACHING_DATA);
		secondaryData =  img;
		SNTUtils.log("Secondary data dimensions: " +
				Arrays.toString(Intervals.dimensionsAsLongArray(secondaryData)));
		if (computeStatistics) {
			final OpService opService = getContext().getService(OpService.class);
			final Pair<T, T> minMax = opService.stats().minMax(img);
			final double mean = opService.stats().mean(img).getRealDouble();
			final double stdDev = opService.stats().stdDev(img).getRealDouble();
			statsSecondary.min = minMax.getA().getRealDouble();
			statsSecondary.max = minMax.getB().getRealDouble();
			statsSecondary.mean = mean;
			statsSecondary.stdDev = stdDev;
		}
		enableSecondaryLayerTracing(true);
		if (changeUIState) {
			changeUIState(SNTUI.WAITING_TO_START_PATH);
		}
	}

	public void enableSecondaryLayerTracing(final boolean enable) {
		if (enable) {
			if (!accessToValidImageData()) {
				if (getUI() != null) {
					getUI().noValidImageDataError();
				} else {
					throw new UnsupportedOperationException("This option requires valid image data to be loaded.");
				}
				doSearchOnSecondaryData = false;
			} else if (!isSecondaryDataAvailable()) {
				if (getUI() != null) {
					getUI().noSecondaryDataAvailableError();
				} else {
					throw new UnsupportedOperationException("No secondary image has been defined.");
				}
				doSearchOnSecondaryData = false;
			} else {
				doSearchOnSecondaryData = true;
			}
		} else {
			doSearchOnSecondaryData = false;
		}
		if (getUI() != null) {
			getUI().setSecondaryLayerTracingSelected(doSearchOnSecondaryData);
		}
	}

	public void flushSecondaryData() {
		// TODO: Is this all we need to do, and is it in the correct order?
		if (secondaryData instanceof DiskCachedCellImg<?, ?> img) {
            SNTUtils.log("Shutting down IoSync...");
			img.shutdown();
		}
		if (secondaryData instanceof CachedCellImg<?, ?> img) {
            SNTUtils.log("Invalidating cache...");
			if (img.getCache() != null)
				img.getCache().invalidateAll();
		}
		secondaryData = null;
		setSecondaryImage(null);
		if (getUI() != null) {
			getUI().disableSecondaryLayerComponents();
		}
	}

	private ImagePlus openCachedDataImage(final File file) throws IOException {
		if (xy == null) throw new IllegalArgumentException(
			"Data can only be loaded after main tracing image is known");
		if (!SNTUtils.fileAvailable(file)) {
			throw new IllegalArgumentException("File path of input data unknown");
		}
		ImagePlus imp = ImpUtils.open(file);
		if (imp == null) {
			final Dataset ds = datasetIOService.open(file.getAbsolutePath());
			if (ds == null)
				throw new IllegalArgumentException("Image could not be loaded by IJ.");
			imp = convertService.convert(ds, ImagePlus.class);
		}
		if (!ImpUtils.sameXYZDimensions(imp, xy)) {
			// We are imposing only XYZ dimensions to e.g., allow for loading of single-channel
			// p-maps. Hopefully, being lax about CT dimensions won't cause issues downstream
			throw new IllegalArgumentException("Dimensions do not match those of  " + xy.getTitle()
			+ ". If this is unexpected, check under 'Image>Properties...' that CZT axes are not swapped.");
		}
		return imp;
	}

	/**
	 * Retrieves the 'secondary image' data currently loaded in memory as an
	 * ImagePlus object. Returned image is always of 32-bit type.
	 *
	 * @return the loaded data or null if no image has been loaded.
	 * @see #isSecondaryDataAvailable()
	 * @see #loadSecondaryImage(ImagePlus)
	 * @see #loadSecondaryImage(File)
	 */
	@SuppressWarnings({"unchecked"})
	public <T extends NumericType<T>> ImagePlus getSecondaryDataAsImp() {
		if (secondaryData == null) {
			return null;
		}
		RandomAccessibleInterval<T> img = secondaryData;
		if (secondaryData.numDimensions() == 3) {
			img = Views.permute(Views.addDimension(img, 0,0), 2,3);
		}
		final ImagePlus imp = ImageJFunctions.wrap(img, "Secondary Layer");
		imp.copyScale(xy);
		imp.resetDisplayRange();
		return imp;
	}

	public <T extends RealType<T>> RandomAccessibleInterval<T> getSecondaryData() {
		@SuppressWarnings("unchecked")
		final RandomAccessibleInterval<T> data  = secondaryData;
		return data;
	}

	public SNTPrefs getPrefs() {
		return prefs;
	}

	// This is the implementation of HessianGenerationCallback
	@Override
	public void proportionDone(final double proportion) {
		if (proportion < 0) {
			if (ui != null) ui.gaussianCalculated(false);
			statusService.showProgress(1, 1);
			return;
		}
		else if (proportion >= 1.0) {
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
				linePoints.add(new Point3f((float) np.near.x, (float) np.near.y,
					(float) np.near.z));
				linePoints.add(new Point3f((float) np.closestIntersection.x,
					(float) np.closestIntersection.y, (float) np.closestIntersection.z));

				final String ballName = univ.getSafeContentName("ball " + done);
				final List<Point3f> sphere = customnode.MeshMaker.createSphere(
					np.near.x, np.near.y, np.near.z, Math.abs(x_spacing / 2));
				univ.addTriangleMesh(sphere, Utils.toColor3f(c), ballName);
			}
			++done;
		}
		univ.addLineMesh(linePoints, Utils.toColor3f(Color.RED), "correspondences", false);

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

	private void setSideViewsVisible(final boolean visible) {
		if (xz != null && xz.getWindow() != null)
			xz.getWindow().setVisible(visible);
		if (zy != null && zy.getWindow() != null)
			zy.getWindow().setVisible(visible);
	}

	protected void error(final String msg) {
		new GuiUtils(getActiveWindow()).error(msg);
	}

	protected void showMessage(final String msg, final String title) {
		new GuiUtils(getActiveWindow()).centeredMsg(msg, title);
	}

	protected InteractiveTracerCanvas getTracingCanvas() {
		return xy_tracer_canvas;
	}

	protected Component getActiveWindow() {
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
			new Thread(() -> getUI().recViewer.syncPathManagerList()).start();
		}
		if (getUI().sciViewSNT != null) {
			new Thread(() -> getUI().sciViewSNT.syncPathManagerList()).start();
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
			new Thread(pathAndFillManager::update3DViewerContents).start();
		}
	}

	/**
	 * Gets the instance of the legacy 3D viewer universe. Note that the legacy 3D
	 * viewer is now deprecated.
	 *
	 * @return a reference to the 3DUniverse or null if no universe has been set
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
		selectedColor3f = Utils.toColor3f(newColor);
		updateTracingViewers(true);
	}

	protected void setDeselectedColor(final Color newColor) {
		deselectedColor = newColor;
		deselectedColor3f = Utils.toColor3f(newColor);
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
	public void setPathList(final List<Path> pathList, final Path justAdded,
		final boolean expandAll) // ignored
	{}

	@Override
	public void setFillList(final List<Fill> fillList) {}  // ignored

	// Note that rather unexpectedly the p.setSelected calls make sure that
	// the colour of the path in the 3D viewer is right... (FIXME)
	@Override
	public void setSelectedPaths(final Collection<Path> selectedPathsSet,
		final Object source)
	{
		if (source == this) return;
		for (int i = 0; i < pathAndFillManager.size(); ++i) {
			final Path p = pathAndFillManager.getPath(i);
				p.setSelected(selectedPathsSet.contains(p));
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

	protected synchronized void findSnappingPointInXView(final double x_in_pane,
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
		@SuppressWarnings("unchecked")
		final RandomAccess<? extends RealType<?>> access = this.ctSlice3d.randomAccess();
		final ArrayList<int[]> pointsAtMaximum = new ArrayList<>();
		double currentMaximum = stats.min;
		for (int x = startx; x < stopx; ++x) {
			for (int y = starty; y < stopy; ++y) {
				for (int z = startz; z < stopz; ++z) {
					double v = access.setPositionAndGet(x, y, z).getRealDouble();
					if (v == stats.min) {
						continue;
					}
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
		final int plane, final boolean join)
	{

		SNTUtils.log("Looking for maxima at x=" + x_in_pane + " y=" + y_in_pane + " on pane " + plane);
		final int[][] pointsToConsider = findAllPointsAlongLine(x_in_pane, y_in_pane, plane);
		@SuppressWarnings("unchecked")
		final RandomAccess<? extends RealType<?>> access = this.ctSlice3d.randomAccess();
		final ArrayList<int[]> pointsAtMaximum = new ArrayList<>();
		double currentMaximum = stats.min;
		for (int[] ints : pointsToConsider) {
			double v = access.setPositionAndGet(ints[0], ints[1], ints[2]).getRealDouble();
			if (v == stats.min) {
				continue;
			} else if (v > currentMaximum) {
				pointsAtMaximum.add(ints);
				currentMaximum = v;
			}
			else if (v == currentMaximum) {
				pointsAtMaximum.add(ints);
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
		SNTUtils.log(" Detected: x=" + p[0] + ", y=" + p[1] + ", z=" + p[2] + ", value=" + stats.max);
		setZPositionAllPanes(p[0], p[1], p[2]);
		if (!tracingHalted) { // click only if tracing
			clickForTrace(p[0] * x_spacing, p[1] * y_spacing, p[2] * z_spacing, join);
		}
	}

	private ImagePlus[] getXYZYXZDataGray8(final boolean filteredData) {
		ImagePlus xy8;
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
		ImpUtils.convertTo8bit(xy8);
		final ImagePlus[] views = (single_pane) ? new ImagePlus[] { null, null } : MultiDThreePanes.getZYXZ(xy8, 1);
		return new ImagePlus[] { xy8, views[0], views[1] };
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
		if ((is2D() && !filteredData) || !accessToValidImageData()) return;
		final String identifer = (filteredData) ? MIP_OVERLAY_IDENTIFIER_PREFIX + "2"
				: MIP_OVERLAY_IDENTIFIER_PREFIX + "1";
		if (opacity == 0d) {
			removeMIPOverlayAllPanes(identifer);
			//this.unzoomAllPanes();
			return;
		}
		final ImagePlus[] paneImps = new ImagePlus[] { xy, zy, xz };
		final ImagePlus[] paneMips = getXYZYXZDataGray8(filteredData);
		if (paneMips != null) showMIPOverlays(filteredData, paneImps, paneMips, identifer,opacity);
	}

	private void showMIPOverlays(final boolean filteredData, ImagePlus[] paneImps, ImagePlus[] paneMips,
			final String overlayIdentifier, final double opacity) {
		// Create a MIP Z-projection of the active channel
		for (int i = 0; i < paneImps.length; i++) {
			final ImagePlus paneImp = paneImps[i];
			final ImagePlus mipImp = paneMips[i];
			if (paneImp == null || mipImp == null || (paneImp.getNSlices() == 1 && !filteredData))
				continue;
			Overlay existingOverlay = paneImp.getOverlay();
			if (existingOverlay == null) existingOverlay = new Overlay();
			final ImagePlus overlay = ImpUtils.getMIP(mipImp);

			// (This logic is taken from OverlayCommands)
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
			new GuiUtils(getActiveWindow()).tempMsg(msg);
	}

	protected boolean getConfirmation(final String msg, final String title) {
		return new GuiUtils(getActiveWindow()).getConfirmation(msg, title);
	}

	protected void toggleSnapCursor() {
		enableSnapCursor(!snapCursor);
	}

    /**
     * Enables/Disables SNT overlays over tracing views.
     * Note that disabling overlays will also suppress most GUI-related operations.
     *
     * @param visible whether overlays should be rendered
     */
    public synchronized void setAnnotationsVisible(final boolean visible) {
        // We are recycling the 'headless' flag. Most GUI-related updates will be suppressed
        getPathAndFillManager().enableUIupdates = visible;
        if (xy_canvas != null) xy_canvas.repaint();
        if (xz_canvas != null) xz_canvas.repaint();
        if (zy_canvas != null) zy_canvas.repaint();
    }

	/**
	 * Enables SNT's XYZ snap cursor feature. Does nothing if no image data is
	 * available or currently loaded image is binary
	 *
	 * @param enable whether cursor snapping should be enabled
	 */
	public synchronized void enableSnapCursor(final boolean enable) {
		final boolean validImage = accessToValidImageData();
		final boolean isBinary = validImage && ImpUtils.isBinary(xy);
		snapCursor = enable && validImage && !isBinary;
		if (isUIready()) {
			if (enable && !validImage) {
				ui.noValidImageDataError();
			}
			ui.useSnapWindow.setSelected(snapCursor);
			ui.useSnapWindow.setEnabled(!isBinary);
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
		return doSearchOnSecondaryData && isSecondaryDataAvailable();
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
	 * @return true if the image currently loaded does not have a depth (Z)
	 *         dimension
	 */
	public boolean is2D() {
		return singleSlice;
	}

	public void setDrawDiameters(final boolean draw) {
		drawDiameters = draw;
		repaintAllPanes();
	}

	public boolean getDrawDiameters() {
		return drawDiameters;
	}

	@Override
	public void closeAndResetAllPanes() {
		// Dispose xz/zy images unless the user stored some annotations (ROIs)
		// on the image overlay or modified them somehow.
		removeMIPOverlayAllPanes();
		if (!single_pane) {
			final ImagePlus[] impPanes = { xz, zy };
			for (final ImagePlus imp : impPanes) {
				if (imp == null)
					continue;
				final Overlay overlay = imp.getOverlay();
				if (!imp.changes && (overlay == null || imp.getOverlay().size() == 0)
						&& !(imp.getRoi() != null && (imp.getRoi() instanceof PointRoi)))
					imp.close();
				else
					rebuildWindow(imp);
			}
		}
		// Restore main view
		final Overlay overlay = (xy == null) ? null : xy.getOverlay();
		final Roi roi = (xy == null) ? null : xy.getRoi();
		if (xy != null && overlay == null && roi == null && !accessToValidImageData()) {
			xy.changes = false;
			xy.close();
		} else if (xy != null && xy.getImage() != null) {
			rebuildWindow(xy);
		}
	}

	private void rebuildWindow(final ImagePlus imp) {
		// hiding the image will force the rebuild of its ImageWindow next time show() is
		// called. We need to remove any PointRoi to bypass the "Save changes?" dialog.
		// If spine/varicosity counts exist, set the images has changed to avoid data loss
		final Roi roi = imp.getRoi();
		final boolean existingChanges = imp.changes;
		imp.changes = false;
		imp.deleteRoi();
		imp.hide();
		imp.setRoi(roi);
		imp.show();
		imp.changes = existingChanges || roi instanceof PointRoi;
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

	protected double getOneMinusErfZFudge() {
		return oneMinusErfZFudge;
	}

	public ImageStatistics getStats() {
		return stats;
	}

	public ImageStatistics getStatsSecondary() {
		return statsSecondary;
	}

	public void setUseSubVolumeStats(final boolean useSubVolumeStatistics) {
		this.isUseSubVolumeStats = useSubVolumeStatistics;
	}

	public boolean getUseSubVolumeStats() {
		return isUseSubVolumeStats;
	}

	public SearchType getSearchType() {
		return searchType;
	}

	public void setSearchType(final SearchType searchType) {
		this.searchType = searchType;
	}

	public CostType getCostType() {
		return costType;
	}

	public void setCostType(final CostType costType) {
		this.costType = costType;
	}

	public HeuristicType getHeuristicType() {
		return heuristicType;
	}

	public void setHeuristicType(final HeuristicType heuristicType) {
		this.heuristicType = heuristicType;
	}

	public SearchImageType getSearchImageType() {
		return searchImageType;
	}

	public void setSearchImageType(final SearchImageType searchImageType) {
		this.searchImageType = searchImageType;
	}

	public FilterType getFilterType() {
		return filterType;
	}

	public void setFilterType(final FilterType filterType) {
		this.filterType = filterType;
	}

	public int getWidth() {
		return width;
	}

	public int getHeight() {
		return height;
	}

	public int getDepth() {
		return depth;
	}

	public double getPixelWidth() {
		return x_spacing;
	}

	public double getPixelHeight() {
		return y_spacing;
	}

	public double getPixelDepth() {
		return z_spacing;
	}

	public String getSpacingUnits() {
		return spacing_units;
	}

	public int getChannel() {
		return channel;
	}

	public int getFrame() {
	 return frame;
	}

	/**
	 * Retrieves a WYSIWYG 'snapshot' of a tracing canvas.
	 *
	 * @param view A case-insensitive string specifying the canvas to be captured.
	 *          Either "xy" (or "main"), "xz", "zy" or "3d" (for legacy's 3D
	 *          Viewer).
	 * @param project whether the snapshot of 3D image stacks should include its
	 *          projection (MIP), or just the current plane
	 * @return the snapshot capture of the canvas as an RGB image
	 * @throws UnsupportedOperationException if SNT is not running
	 * @throws IllegalArgumentException if view is not a recognized option
	 */
	@SuppressWarnings("unused")
	public ImagePlus captureView(final String view, final boolean project) {
		if (view == null || view.trim().isEmpty())
			throw new IllegalArgumentException("Invalid view");

		if (view.toLowerCase().contains("3d")) {
			if (get3DUniverse() == null || get3DUniverse().getWindow() == null)
				throw new IllegalArgumentException("Legacy 3D viewer is not available");
			//plugin.get3DUniverse().getWindow().setBackground(background);
			return get3DUniverse().takeSnapshot();
		}

		final int viewPlane = getView(view);
		final ImagePlus imp = getImagePlus(viewPlane);
		if (imp == null) throw new IllegalArgumentException(
			"view is not available");

		ImagePlus holdingView;
		if (accessToValidImageData()) {
			holdingView = ImpUtils.getMIP(imp, (project) ? 1 : imp.getZ(), (project) ? imp.getNSlices() : imp.getZ())
					.flatten();
		} else {
			holdingView = ImpUtils.create("Holding view", imp.getWidth(), imp.getHeight(), 1, 8);
		}
		holdingView.copyScale(imp);
		return captureView(holdingView, view, viewPlane);
	}

	/**
	 * Retrieves a WYSIWYG 'snapshot' of a tracing canvas without voxel data.
	 *
	 * @param view            A case-insensitive string specifying the canvas to be
	 *                        captured. Either "xy" (or "main"), "xz", "zy" or "3d"
	 *                        (for legacy's 3D Viewer).
	 * @param backgroundColor the background color of the canvas (string, hex, or
	 *                        html)
	 * @return the snapshot capture of the canvas as an RGB image
	 * @throws UnsupportedOperationException if SNT is not running
	 * @throws IllegalArgumentException      if {@code view} or
	 *                                       {@code backgroundColor} are not
	 *                                       recognized
	 */
	public ImagePlus captureView(final String view, final ColorRGB backgroundColor) throws IllegalArgumentException {
		if (view == null || view.trim().isEmpty())
			throw new IllegalArgumentException("Invalid view");
		if (backgroundColor == null)
			throw new IllegalArgumentException("Invalid backgroundColor");

		final Color backgroundColorAWT = new Color(backgroundColor.getRed(), backgroundColor.getGreen(),
				backgroundColor.getBlue(), 255);
		if (view.toLowerCase().contains("3d")) {
			if (get3DUniverse() == null || get3DUniverse().getWindow() == null)
				throw new IllegalArgumentException("Legacy 3D viewer is not available");
			final Color existingBackground = get3DUniverse().getWindow().getBackground();
			get3DUniverse().getWindow().setBackground(backgroundColorAWT);
			final ImagePlus imp = get3DUniverse().takeSnapshot();
			get3DUniverse().getWindow().setBackground(existingBackground);
			return imp;
		}

		final int viewPlane = getView(view);
		final ImagePlus imp = getImagePlus(viewPlane);
		if (imp == null) throw new IllegalArgumentException(
			"view is not available");
		final ColorProcessor ip = new ColorProcessor(imp.getWidth(), imp.getHeight());
		ip.setColor(backgroundColorAWT);
		ip.fill();
		final ImagePlus holdingView = new ImagePlus("Holder", ip);
		holdingView.copyScale(imp);
		return captureView(holdingView, view, viewPlane);
	}

	private ImagePlus captureView(final ImagePlus holdingImp, final String viewDescription, final int viewPlane) {
		// NB: overlay will be flattened but not active ROI
		final TracerCanvas canvas = new TracerCanvas(holdingImp, this, viewPlane, pathAndFillManager);
		if (getXYCanvas() != null)
			canvas.setNodeDiameter(getXYCanvas().nodeDiameter());
		final BufferedImage bi = new BufferedImage(holdingImp.getWidth(), holdingImp
			.getHeight(), BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = canvas.getGraphics2D(bi.getGraphics());
		g.drawImage(holdingImp.getImage(), 0, 0, null);
		for (final Path p : pathAndFillManager.getPaths()) {
			p.drawPathAsPoints(g, canvas, this);
		}
		// this is taken from ImagePlus.flatten()
		final ImagePlus result = new ImagePlus(viewDescription + " view snapshot",
			new ColorProcessor(bi));
		result.copyScale(holdingImp);
		result.setProperty("Info", holdingImp.getProperty("Info"));
		return result;
	}

	private static int getView(final String view) {
        return switch (view.toLowerCase()) {
            case "xy", "main" -> MultiDThreePanes.XY_PLANE;
            case "xz" -> MultiDThreePanes.XZ_PLANE;
            case "zy" -> MultiDThreePanes.ZY_PLANE;
            default -> throw new IllegalArgumentException("Unrecognized view");
        };
	}

    private Calibration getCalibration() {
        final Calibration calibration = new Calibration();
        calibration.pixelWidth = x_spacing;
        calibration.pixelHeight = y_spacing;
        calibration.pixelDepth = z_spacing;
        calibration.setUnit(spacing_units);
        return calibration;
    }
}
