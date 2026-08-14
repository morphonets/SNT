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
import mpicbg.spim.data.generic.AbstractSpimData;
import net.imagej.Dataset;
import net.imagej.DatasetService;
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
import sc.fiji.snt.io.SpimDataUtils;
import sc.fiji.snt.plugin.ShollAnalysisTreeCmd;
import sc.fiji.snt.seed.SeedOverlay;
import sc.fiji.snt.seed.SeedOverlayCanvasHandler;
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
import java.util.function.BiFunction;
import java.util.function.Consumer;


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
	@Parameter
	private DatasetService datasetService;

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

		public static CostType fromString(final String text) {
			for (final CostType c : CostType.values()) {
				if (c.toString().equalsIgnoreCase(text))
					return c;
			}
			return null;
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
		TUBENESS, FRANGI, GAUSS, MEDIAN, SPECTRAL;
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
	private final SeedOverlay seedOverlay = new SeedOverlay();
	/**
	 * Coalesces seed-overlay-change repaint requests to one per ~16ms so a
	 * dragged slider doesn't fire 20 listener events per second x 3 canvases.
	 * Lazy-created the first time the overlay fires; restartable.
	 */
	private javax.swing.Timer seedOverlayRepaintTimer;
	{
		// Repaint all canvases whenever the seed overlay changes (add/clear/  threshold/visibility).
		// Coalesced through a Swing Timer so rapid fires (e.g. slider drag) collapse to one repaint per frame.
		// repaintAllPanes() null-guards each canvas, so this is safe to fire before canvases are assigned
		seedOverlay.addListener(o -> {
			if (seedOverlayRepaintTimer == null) {
				seedOverlayRepaintTimer = new javax.swing.Timer(16, e -> repaintAllPanes());
				seedOverlayRepaintTimer.setRepeats(false);
			}
			seedOverlayRepaintTimer.restart();
		});
	}
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
	protected volatile boolean panMode;
	protected volatile boolean snapCursor;
	protected volatile boolean showOnlySelectedPaths;
	protected volatile boolean showOnlyActiveCTposPaths;
	protected volatile boolean autoCT;
	private boolean drawDiameters;
	protected double manualRadius;
	private double startNodeRadius = -1; // manualRadius at the time of startPath()

	private boolean manualOverride = false;
	private double fillThresholdDistance;

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
	/*
	 * True once x_spacing/y_spacing/z_spacing have been set from a source that actually reported real voxel dimensions
	 * (setImageMetadata(...) called with all three spacing args > 0), as opposed to staying at their hardwired default
	 * of 1 because a streamed N5/Zarr source's own  getVoxelDimensions() returned null
	 * (see BigDataLoaderCmd#applyFallbackCalibration). getCalibration() uses this to decide whether
	 * this session's own spacing fields can be trusted outright, or whether falling back to a loaded Path's own
	 * calibration is warranted
	 */
	private boolean spacingKnownFromSource;
	/*
	 * World-space origin offset (calibrated units), applied on top of voxelIndex * spacing.
	 * Non-zero only when the loaded image's own coordinate frame is not anchored at world
	 * (0,0,0) - e.g. a BigDataViewer/N5 Source whose sourceTransform carries a translation
	 * that plain size/calibration wiring (see BigDataLoaderCmd#applyFallbackCalibration)
	 * does not otherwise capture. See getWorldOriginOffset()/setWorldOriginOffset().
	 */
	private double originOffsetX, originOffsetY, originOffsetZ;

	/* all tracing and filling-related functions are performed on the Imgs */
	@SuppressWarnings("rawtypes")
	RandomAccessibleInterval ctSlice3d;

	/*
	 * A cached reference to the ORIGINAL, full-resolution Stream-mode source data, kept alongside ctSlice3d so that
	 * materializeDisplayCanvas(BoundingBox) can always crop from the pristine source, not from a previous crop.
	 * ctSlice3d itself gets overwritten with the (small) crop's own pixel data every time initialize(ImagePlus) runs
	 * (see materializeDisplayCanvas). without this separate reference, re-materializing after closing/discarding
	 * a crop would progressively crop from the last crop instead of the full source, shrinking the result on every
	 * call. Set once, lazily, the first  time materializeDisplayCanvas runs against a genuine (not-yet-materialized)
	 * Stream-mode source; left null otherwise
	 */
	@SuppressWarnings("rawtypes")
	private RandomAccessibleInterval streamedSourceData;

	/* statistics for main image*/
	private final ImageStatistics stats = new ImageStatistics();

	/*
	 * Global statistics for streamedSourceData, computed lazily (see getStreamedOrLoadedStats()) the
	 * first time a crop-independent (BDV/BVV) search needs them. stats itself gets overwritten with a
	 * materialized crop's own (smaller) statistics every time loadDatasetFromImagePlus() runs against
	 * it, so a crop-independent search normalizing its cost function against stats while a crop is
	 * materialized would search the full volume but calibrate against the crop's statistics instead.
	 * Never reset once computed: the full streamed source's own pixel data does not change for the
	 * lifetime of the session (unlike stats, which legitimately changes every time a different crop is
	 * materialized), so there is nothing that would ever make a cached value here stale
	 */
	private final ImageStatistics streamedStats = new ImageStatistics();
	private boolean streamedStatsComputed;

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
    AbstractSearch currentSearchThread = null;
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
	// Tracks the disk cache backing secondaryData when it was built via Lazy's "lazy" strategy.
	// Deleted explicitly in flushSecondaryData()
	private java.nio.file.Path secondaryDataCacheDir;
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

	// True while currentPath is a brand-new path being built from scratch (see startPath()), false while it is an
	// existing, already-finished path being extended (see replaceCurrentPath()). The 2 cases need opposite offset
	// handling in confirmTemporary()/undoLastSegment(): a new path's nodes are still in the intermediate "world -
	// worldOriginOffset" frame until finishedPath() completes them, while an extended path's pre-existing nodes are
	// already complete world, se cropRelativeCanvasOffset()
	private boolean currentPathIsNew = true;

	protected double last_start_point_x;
	protected double last_start_point_y;
	protected double last_start_point_z;

	// Any method that deals with these two fields should be synchronized.
	protected Path temporaryPath = null; // result of A* search that hasn't yet been confirmed
	protected Path currentPath = null;

	/* GUI */
	protected SNTUI ui;
	// Whether this session is running in stream mode ("SNT Stream"), i.e., without access to a full
	// in-core materialized image (see BigDataLoaderCmd)
	private boolean bigDataMode;
	protected volatile boolean tracingHalted = false; // Tracing functions paused?
	protected volatile boolean rubberBandTracing = false; // Rubber band (live preview) tracing mode

	/* Insertion order is used to assign label values in a labeling image */
	Set<FillerThread> fillerSet = new LinkedHashSet<>();
	ExecutorService fillerThreadPool;

	ExecutorService tracerThreadPool;

	protected Color3f selectedColor3f = SNTPrefs.DEFAULT_SELECTED_COLOR3F;
	protected Color3f deselectedColor3f = SNTPrefs.DEFAULT_DESELECTED_COLOR3F;

	/* Undo mechanism */
	protected final Deque<Integer> confirmedSegmentSizes = new ArrayDeque<>();

	/**
	 * Script-friendly constructor for Instantiating and initializing SNT in
	 * 'Tracing Mode' (typically headless operations). The channel/frame to
	 * be traced is assumed to be the image's active CT position.
	 * <p>
	 *     Note that the image is not displayed. For interactive display of the image call
	 *     {@link #initialize(ImagePlus)}/{@link #startUI()} directly.
	 * </p>
	 *
	 * @param sourceImage the source image
	 * @throws IllegalArgumentException If sourceImage is of type 'RGB'
	 */
	public SNT(final ImagePlus sourceImage) throws IllegalArgumentException {
		this(SNTUtils.getContext(), sourceImage);
		// NB: Previous versions called: initialize(true, sourceImage.getChannel(), sourceImage.getFrame());
		// which caused the image to be displayed even in non-interactive scripts. This call was removed
		// for consistency: callers wanting full UI should use startUI() or initialize(imp) explicitly
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
			this.channel = sliceResult.channelIndex() + 1; // 1-based index
		} else {
			this.channel = 1;
		}
		if (sliceResult.timeIndex() >= 0) {
			SNTUtils.log("Using timepoint " + sliceResult.timeIndex());
			this.frame = sliceResult.timeIndex() + 1; // 1-based index
		} else {
			this.frame = 1;
		}
		SNTUtils.getContext().inject(this);
		SNTUtils.setPlugin(this);
		pathAndFillManager = new PathAndFillManager(this);
		prefs = new SNTPrefs(this);
		setFieldsFromImgPlus(processedImage);
		prefs.loadPluginPrefs();
		single_pane = true; // avoid NPE on side panes logic
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
			// assignSpatialSettings() just zeroed every existing Path's own canvasOffset (a new image resets the
			// paths' coordinate frame). Keep activeCanvasPixelOffset in sync  with that, or tracing against this new
			// image would silently use whatever offset was left over from before (e.g. a worldOriginOffset set for an
			// unrelated prior source)
			syncActivePathCanvasState(defaultCanvasPixelOffset(), null);
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
		if (accessToValidImageData() && !ImpUtils.isDisplayCanvas(sourceImage) && !ImpUtils.isMaterializedCrop(sourceImage)) {
			pathAndFillManager.assignSpatialSettings(sourceImage);
			// See setFieldsFromImgPlus()'s identical fix: assignSpatialSettings() just zeroed every
			// existing Path's own canvasOffset - keep activeCanvasPixelOffset in sync with that
			syncActivePathCanvasState(defaultCanvasPixelOffset(), null);
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

	protected synchronized void undoLastSegment() {
		if (confirmedSegmentSizes.isEmpty()) {
			showCanvasWarning("No segment to undo");
			return;
		}
		if (temporaryPath != null) {
			if (rubberBandTracing)
				temporaryPath = null; // discard live preview silently
			else {
				showCanvasWarning("Confirm or cancel the current segment before undoing");
				return;
			}
		}
		final int nodesToRemove = confirmedSegmentSizes.pop();
		for (int i = 0; i < nodesToRemove; i++)
			currentPath.removeNode(currentPath.size() - 1);

		if (currentPath.size() == 0) {
			// Undone back to the very first point, equivalent to cancelling
			cancelPath();
			return;
		}
		// Restore last_start_point to the new last node. For a brand-new path (currentPathIsNew),
		// currentPath is still in-progress (applyWorldOriginOffsetIfAny() has not run yet), so its
		// nodes are in the same pre-final "world - worldOriginOffset" state confirmTemporary() leaves
		// them in. We use cropRelativeCanvasOffset(), not currentPath.getCanvasOffset() or this double-subtracts
		// worldOriginOffset once last_start_point_x/y/z is next used to seed a search. When extending
		// an existing path instead, confirmTemporary() already made every merged node complete world
		// (confirmedSegmentSizes is cleared in replaceCurrentPath(), so undo can never reach back past
		// the extension point into the path's own original, pre-existing nodes). recovering the
		// active-grid pixel index from a complete-world node needs the FULL offset instead
		final PointInImage last = currentPath.lastNode();
		final PointInCanvas recoveryOffset = currentPathIsNew ? cropRelativeCanvasOffset() : activeCanvasPixelOffset;
		last_start_point_x = last.x / x_spacing + recoveryOffset.x;
		last_start_point_y = last.y / y_spacing + recoveryOffset.y;
		last_start_point_z = last.z / z_spacing + recoveryOffset.z;
		lastStartPointSet = true;
		setPathUnfinished(true);
		changeUIState(SNTUI.PARTIAL_PATH);
		updateTracingViewers(true);
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

	/**
	 * Checks whether valid image data exists.
	 *
	 * @return true if a tracing image exists, or (for headless/API usage)
	 *         cached pixel data remains in memory.
	 */
	public boolean accessToValidImageData() {
		return ctSlice3d != null || (xy != null && !ImpUtils.isDisplayCanvas(xy) && !isDummy());
	}

	private void setIsDisplayCanvas(final ImagePlus imp) {
		assert imp != null;
		ImpUtils.setIsDisplayCanvas(imp);
		getPrefs().setTemp("ignore-close-" + imp.getID(), true);
	}

	/*
	 * Single source of truth for "the pixel offset (in the current xy canvas's own local grid) of (0,0,0) in whatever
	 * true/world grid Path node coordinates are stored in". Kept in sync with the  identical value
	 * assembleDisplayCanvases()/installMaterializedCrop() already set on every Path's own canvasOffset, and reset to
	 * 0 by dematerializeDisplayCanvas(). Exists so  pixel-based tracing interactions that must compare against a
	 * Path's own (true-world) node coordinates (e.g. clickForTrace, mouseMovedTo's join-point lookup, etc.) can correct
	 * a raw pane-pixel index back to the true grid before doing so. Without this, those interactions silently used the
	 * crop-local pixel index as if it were the true.
	 */
	private PointInCanvas activeCanvasPixelOffset = new PointInCanvas(0, 0, 0);

	/**
	 * @return the pixel offset (in whatever grid {@link #ctSlice3d}/{@link #getLoadedData()} is
	 *         currently indexed by - the crop-local grid when a materialized crop is active, or
	 *         the raw streamed source's own voxel grid otherwise) of (0,0,0) in the true/world grid
	 *         {@link Path} node coordinates are stored in
	 */
	public PointInCanvas getActiveCanvasPixelOffset() {
		return activeCanvasPixelOffset;
	}

	/**
	 * Public wrapper around {@link #defaultCanvasPixelOffset()}, for callers outside this class that
	 * need the crop-independent baseline offset even while a materialized crop is active on the
	 * classic 2D canvas - e.g. a BDV/BVV interaction that should behave the same regardless of what
	 * the classic canvas currently has materialized, mirroring how {@link #createSearch(double,
	 * double, double, double, double, double, SearchSettingsSnapshot, boolean)}'s
	 * {@code useStreamedSource} parameter picks between the two.
	 *
	 * @return the same value as {@link #getActiveCanvasPixelOffset()} whenever no crop is
	 *         materialized; the crop-independent baseline otherwise
	 */
	public PointInCanvas getDefaultCanvasPixelOffset() {
		return defaultCanvasPixelOffset();
	}

	/**
	 * The baseline {@link #activeCanvasPixelOffset} whenever no materialized crop is active: converts a
	 * true-world coordinate into {@link #streamedSourceData}/{@link #ctSlice3d}'s own raw voxel-index grid
	 * (see {@link #getWorldOriginOffset()}'s contract, {@code world = voxelIndex * spacing + offset}, so
	 * {@code voxelIndex = world/spacing - offset/spacing}). All-zero when the source has no world origin
	 * offset (the common case).
	 */
	private PointInCanvas defaultCanvasPixelOffset() {
		final double[] o = getWorldOriginOffset();
		return new PointInCanvas(-o[0] / x_spacing, -o[1] / y_spacing, -o[2] / z_spacing);
	}

	/**
	 * The crop-only portion of {@link #activeCanvasPixelOffset}: everything in it except the
	 * {@link #getWorldOriginOffset()} correction that {@link #defaultCanvasPixelOffset()} already
	 * contributes on its own. Zero whenever no materialized crop is active ({@code
	 * activeCanvasPixelOffset == defaultCanvasPixelOffset()} in that case).
	 * <p>
	 * {@link #applyWorldOriginOffsetIfAny(Path)} is the single, dedicated place
	 * {@link #getWorldOriginOffset()} gets added to a freshly-built Path's node coordinates.
	 * {@link #confirmTemporary(boolean)}, {@link #finishedPath()}'s single-point/soma branch, and
	 * {@link #autoTraceSync(List, PointInImage, SearchSettingsSnapshot)} must therefore correct raw
	 * search-thread output ({@code asPath()}: {@code pixelIndex * spacing}, where {@code pixelIndex}
	 * was computed using the FULL live {@link #activeCanvasPixelOffset}) by only this crop-relative
	 * portion, not the full offset - subtracting the full offset here would double-count
	 * {@link #getWorldOriginOffset()} once {@link #applyWorldOriginOffsetIfAny(Path)} runs afterward.
	 */
	private PointInCanvas cropRelativeCanvasOffset() {
		final PointInCanvas def = defaultCanvasPixelOffset();
		return new PointInCanvas(activeCanvasPixelOffset.x - def.x, activeCanvasPixelOffset.y - def.y,
				activeCanvasPixelOffset.z - def.z);
	}

	/**
	 * Keep {@link #activeCanvasPixelOffset} and every currently-loaded {@link Path}'s own {@code canvasOffset} (and,
	 * optionally, its own spacing) in sync with each other,  used by {@link #assembleDisplayCanvases()}
	 * {@link #installMaterializedCrop(MaterializedCrop)} and  {@link #dematerializeDisplayCanvas()}.
	 *
	 * @param offset  the new canvasOffset for every loaded Path, and the new  {@link #activeCanvasPixelOffset}
	 * @param calOrNull if non-null, also stamps every loaded Path's own spacing (see
	 *                  {@link Path#setSpacing(Calibration)}) from this calibration. Pass null when only the
	 *                  offset is changing (e.g. {@link #assembleDisplayCanvases()}, where per-Path spacing
	 *                  is already correct - it's what the canvas's own bounding box was aggregated from -
	 *                  or {@link #dematerializeDisplayCanvas()}, where it should already match the restored
	 *                  stream source).
	 */
	private void syncActivePathCanvasState(final PointInCanvas offset, final Calibration calOrNull) {
		for (final Path p : pathAndFillManager.getPaths()) {
			p.setCanvasOffset(offset);
			if (calOrNull != null) p.setSpacing(calOrNull);
		}
		activeCanvasPixelOffset = offset;
	}

	/**
	 * @return true if this stream session's own XY canvas is currently a materialized crop (see
	 * {@link #materializeDisplayCanvas(BoundingBox)}), i.e. {@link #ctSlice3d} holds the crop's own (small) pixel data
	 * rather  than the full Stream-mode source. Checked by  {@link sc.fiji.snt.viewer.AbstractBigViewer}'s click-tracer
	 * to refuse tracing against stale pixel data while its own rendering still shows the full (unaffected) volume, and
	 * by pixel-scoped commands to warn that they will run against the crop's bounds only.
	 */
	public boolean isMaterializedCrop() {
		return ImpUtils.isMaterializedCrop(xy);
	}

	/*
	 * Reverts a materialized crop back to a plain Stream-mode session: restores  ctSlice3d to the original
	 * streamedSourceData cached by materializeDisplayCanvas(BoundingBox), resets every Path's canvasOffset back to 0
	 * and clears this session's own XY canvas bookkeeping (nullifyCanvases(false). Called by SNTUI when the crop's
	 * ImagePlus window is closed, so BDV/BVV  tracing (blocked by isMaterializedCrop() while a crop is open) and any
	 * "whole dataset" command work correctly again.
	 *
	 * canvasOffset reset: installMaterializedCrop(...) sets every Path's canvasOffset to -voxelMin, corrected
	 * for getWorldOriginOffset(), so node coordinates line up with the crop's local pixel grid. Resetting to
	 * defaultCanvasPixelOffset() here (not a flat (0,0,0)) restores the same world-origin-offset correction
	 * for the restored streamedSourceData's own raw grid - without it, paths would keep carrying the closed
	 * crop's offset while nominally back in plain Stream mode, and any canvasOffset-consuming code (BDV/BVV
	 * tracing included, via startPath()/testPathTo()) would silently search/compare against the wrong voxel
	 * index on any dataset with a non-zero world origin offset, until a new crop re-establishes it.
	 *
	 * Fragile ordering!!: re-materializing while a crop is already open closes the old crop's window using the
	 * initialize(ImagePlus)/nullifyCanvases(true) flow (installMaterializedCrop). That close() synchronously fires
	 * SNTUI's ImageListener, which  matches isMaterializedCrop(xy) (still true) and  calls this method NESTED,
	 * mid-install, then immediately overwritten again by the *new* crop's own
	 * setFieldsFromImage(...)/loadDatasetFromImagePlus(...) once initialize(ImagePlus) resumes. Ends
	 * up correct only because the ordering  works, but  reordering initialize(ImagePlus)'s internals could break this.
	 */
	void dematerializeDisplayCanvas() {
		if (!isMaterializedCrop()) return;
		if (streamedSourceData != null) {
			ctSlice3d = streamedSourceData;
			width = (int) ctSlice3d.dimension(0);
			height = (int) ctSlice3d.dimension(1);
			depth = (int) ctSlice3d.dimension(2);
		}
		syncActivePathCanvasState(defaultCanvasPixelOffset(), null);
		nullifyCanvases(false);
	}

	/**
	 * Returns the dimensions of the full loaded image/dataset, even while a materialized crop
	 * (see {@link #materializeDisplayCanvas(BoundingBox)}) is installed as this session's own
	 * canvas. Unlike {@link #width}/{@link #height}/{@link #depth}, which temporarily reflect
	 * the crop's own (small) size for as long as it stays open (see {@link #installMaterializedCrop}),
	 * this always resolves against {@link #streamedSourceData} (the pristine, pre-crop source)
	 * when one is cached, falling back to width/height/depth otherwise (i.e. no crop is open, or
	 * this is a classic/non-streamed session where streamedSourceData is never populated).
	 * <p>
	 * Intended for callers that need the true dataset extent regardless of any transient crop,
	 * e.g. {@link PathAndFillManager#writeXML} when writing the {@code <imagesize>} element of a
	 * .traces file.
	 *
	 * @return {@code {width, height, depth}} of the full dataset
	 */
	public int[] getFullImageDimensions() {
		if (isMaterializedCrop() && streamedSourceData != null) {
			return new int[]{(int) streamedSourceData.dimension(0), (int) streamedSourceData.dimension(1),
					(int) streamedSourceData.dimension(2)};
		}
		return new int[]{width, height, depth};
	}

	private void setIsCachedData(final ImagePlus imp) {
		assert imp != null;
		// NB: somehow setProperty/getProperty does not work with virtual stacks,
		// so we'll brand the image title instead
		imp.setTitle(String.format("Cached Data [C%dT%d]", channel, frame));
		getPrefs().setTemp("ignore-close-" + imp.getID(), true);
	}

	protected boolean isCachedData(final ImagePlus imp) {
		return imp.getTitle().equals(String.format("Cached Data [C%dT%d]", channel, frame));
	}

	private void assembleDisplayCanvases() {
		nullifyCanvases(true);
		if (pathAndFillManager.size() == 0) {
			// not enough information to proceed. Assemble a dummy canvas instead. No Paths to loop over
			// yet, but activeCanvasPixelOffset must still be established now (not left at its (0,0,0)
			// field default): with zero loaded paths this is also the state of a freshly-opened Stream-mode
			// session, and BDV/BVV tracing (which funnels into startPath()/testPathTo(), both of which add
			// activeCanvasPixelOffset to convert a true-world coordinate into a raw source voxel index) can
			// start well before any Path exists or any crop is materialized
			syncActivePathCanvasState(defaultCanvasPixelOffset(), null);
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
		syncActivePathCanvasState(canvasOffset, null);

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

	/**
	 * Estimates the number of bytes required to materialize a crop of the given voxel dimensions from  this session's
	 * streamed source (see {@link #materializeDisplayCanvas(BoundingBox)}), using the source's actual pixel type.
	 *
	 * @param width  the crop's width, in voxels
	 * @param height the crop's height, in voxels
	 * @param depth  the crop's depth, in voxels
	 * @return the estimated byte count, or -1 if no streamed source is available to estimate from
	 * @see #isStreamMode()
	 */
	@SuppressWarnings("rawtypes")
	public long estimateMaterializationBytes(final long width, final long height, final long depth) {
		final RandomAccessibleInterval source = (streamedSourceData != null) ? streamedSourceData : ctSlice3d;
		if (source == null) return -1;
		@SuppressWarnings("unchecked") final RandomAccess<? extends RealType<?>> access = source.randomAccess();
		final int type = getImagePlusType(access.get());
		final int bytesPerPixel = switch (type) {
			case ImagePlus.GRAY16 -> 2;
			case ImagePlus.GRAY32, ImagePlus.COLOR_RGB -> 4;
			default -> 1;
		};
		return width * height * depth * bytesPerPixel;
	}

	/**
	 * @return the number of bytes currently available for a {@link #materializeDisplayCanvas(BoundingBox)} call, i.e.
	 * {@code MEM_FRACTION} of free heap. {@link Long#MAX_VALUE} if the JVM's max memory cannot be determined.
	 * @see #isStreamMode()
	 */
	public long getMaterializationMemoryBudget() {
		final double MEM_FRACTION = 0.8d;
		final long memMax = ij.IJ.maxMemory();
		if (memMax <= 0) return Long.MAX_VALUE;
		final long memInUse = ij.IJ.currentMemory();
		return (long) (MEM_FRACTION * (memMax - memInUse));
	}

	/**
	 * @return this session's current spatial calibration (pixel width/height/depth and unit), for
	 *         converting between pixel and world/calibrated coordinates without affecting world origin: see
	 *         {@link #setWorldOriginOffset(double, double, double)} for the separate mechanism that handles a
	 *         non-zero-anchored source.
	 *         <p>
	 *         Calibration is trusted from initialized ImagePlus (traditional mode) or from the stream source in Stream
	 *         mode ({@link #isSpacingKnownFromSource()}); If the streamed N5/Zarr source whose own
	 *         {@code getVoxelDimensions()} returned null (see {@code BigDataLoaderCmd#applyFallbackCalibration} ),
	 *         this falls back to a representative loaded {@link Path}'s own calibration.
	 *         <p>
	 *         That fallback is inherently a guess SNT cannot verify: nothing guarantees a loaded Path
	 *         (e.g. imported from an SWC/traces file) was actually traced against the currently streamed
	 *         source, so its calibration could be wrong for this data. Callers driving user-facing
	 *         operations should warn accordingly, see {@code SNTUI#materializeDisplayCanvas()}'s use of
	 *         {@link #isSpacingKnownFromSource()}.
	 */
	public Calibration getCalibration() {
		final Calibration cal = new Calibration();
		if (spacingKnownFromSource) {
			// This session's own spacing is verified to have come from the streamed source itself - always
			// prefer it, even if a loaded Path's own (possibly foreign/mismatched) calibration disagrees.
			cal.pixelWidth = x_spacing;
			cal.pixelHeight = y_spacing;
			cal.pixelDepth = z_spacing;
			cal.setUnit(spacing_units);
			return cal;
		}
		// Source did not report real voxel dimensions: fall back to a representative loaded Path's own
		// calibration when available (see this method's own javadoc caveat), else this session's (default) fields.
		final Path referencePath = pathAndFillManager.getPaths().stream().findFirst().orElse(null);
		final Calibration pathCal = (referencePath == null) ? null : referencePath.getCalibration();
		cal.pixelWidth = (pathCal != null && pathCal.pixelWidth > 0) ? pathCal.pixelWidth : x_spacing;
		cal.pixelHeight = (pathCal != null && pathCal.pixelHeight > 0) ? pathCal.pixelHeight : y_spacing;
		cal.pixelDepth = (pathCal != null && pathCal.pixelDepth > 0) ? pathCal.pixelDepth : z_spacing;
		cal.setUnit((pathCal != null && pathCal.getUnit() != null) ? pathCal.getUnit() : spacing_units);
		return cal;
	}

	/**
	 * @return true if {@link #getCalibration()} can trust this session's own spacing fields
	 *         outright (the streamed source itself reported real voxel dimensions at some point); false if it
	 *         instead has to fall back to a loaded Path's own (unverifiable) calibration, or this session's
	 *         hardwired 1-unit default. Used by {@code SNTUI} to warn before an operation that depends on this
	 *         calibration being correct (e.g. materializing a region), since SNT itself cannot confirm that a
	 *         loaded Path was actually traced against the currently streamed source.
	 */
	public boolean isSpacingKnownFromSource() {
		return spacingKnownFromSource;
	}

	/**
	 * The result of {@link #resolveVoxelBounds(BoundingBox, Calibration)}: a world-space region resolved and clamped to
	 * voxel-index bounds within this session's streamed source.
	 *
	 * @param min     the resolved region's minimum voxel index, per axis (inclusive)
	 * @param max     the resolved region's maximum voxel index, per axis (inclusive)
	 * @param clamped {@code true} if the requested region had to be trimmed on at least one side to
	 *                fit within the loaded source's own extent (e.g. padding, or a fixed-size/center
	 *                region, pushed past an edge) - i.e. the resolved region is smaller than what was
	 *                asked for, though never empty (an empty result throws instead, see below)
	 */
	public record VoxelBounds(long[] min, long[] max, boolean clamped) {
	}

	/**
	 * @return this session's main streamed source, caching it from {@link #ctSlice3d} on first call -
	 *         see {@link #streamedSourceData}'s own javadoc for why
	 * @throws IllegalStateException if no streamed pixel data is available
	 */
	@SuppressWarnings("rawtypes")
	private RandomAccessibleInterval resolveMainStreamedSource() {
		if (streamedSourceData == null) {
			if (ctSlice3d == null) {
				throw new IllegalStateException("No streamed pixel data available to materialize from");
			}
			streamedSourceData = ctSlice3d;
		}
		return streamedSourceData;
	}

	/**
	 * @return {@link #getSecondaryData()}, never null
	 * @throws IllegalStateException if no secondary image is currently loaded
	 */
	private RandomAccessibleInterval<?> resolveSecondarySourceOrFail() {
		final RandomAccessibleInterval<?> secondary = getSecondaryData();
		if (secondary == null) {
			throw new IllegalStateException("No secondary image data available to materialize from");
		}
		return secondary;
	}

	/**
	 * Resolves a world-space region to voxel-index bounds within this session's streamed source,
	 * clamping to the source's own extent - a requested region can partially or fully exceed the
	 * loaded volume (e.g. from padding, or a fixed-size/center region placed near or past an edge).
	 * Shared by {@link #materializeDisplayCanvas(BoundingBox)} (which performs the actual read) and
	 * {@code MaterializeRegionDialog}'s live estimate (which needs to preview the REAL, possibly
	 * smaller, result before the user commits to it), so the two can never disagree about what a
	 * given region will actually produce.
	 *
	 * @param worldBox the requested region, in the same (uncalibrated) coordinate frame as Path node coordinates, i.e.,
	 *                 already corrected for {@link #getWorldOriginOffset()} if that offset is non-zero, matching how
	 *                 Paths reaching {@link #pathAndFillManager} are now uniformly built (see
	 *                 {@link #applyWorldOriginOffsetIfAny(Path)}). This method subtracts that offset itself before
	 *                 converting to the source's own raw voxel grid. See {@link #materializeDisplayCanvas(BoundingBox)}
	 * @param cal      the calibration to convert {@code worldBox} with - normally
	 *                 {@link #getCalibration()}, passed in explicitly so a caller that
	 *                 already computed it (e.g. to also build the crop's own output {@link Calibration})
	 *                 does not need to compute it twice
	 * @return the resolved, clamped voxel bounds
	 * @throws IllegalStateException    if this session has no loaded pixel data (not in Stream mode, or
	 *                                  the source is unavailable)
	 * @throws IllegalArgumentException if {@code worldBox} does not overlap the loaded source at all
	 */
	public VoxelBounds resolveVoxelBounds(final BoundingBox worldBox, final Calibration cal) {
		return resolveVoxelBounds(worldBox, cal, false);
	}

	/**
	 * As {@link #resolveVoxelBounds(BoundingBox, Calibration)}, but resolving against either this
	 * session's main streamed source or its secondary (filtered) image - see
	 * {@link #buildMaterializedCrop(BoundingBox, boolean)}.
	 *
	 * @param useSecondary if true, resolves against {@link #getSecondaryData()} instead of the main
	 *                      streamed source
	 * @throws IllegalStateException if {@code useSecondary} is true but no secondary image is loaded
	 */
	public VoxelBounds resolveVoxelBounds(final BoundingBox worldBox, final Calibration cal,
			final boolean useSecondary) {
		final RandomAccessibleInterval<?> source = useSecondary ? resolveSecondarySourceOrFail()
				: resolveMainStreamedSource();

		// Coordinate -> voxel-index conversion, in source's own (raw source) grid. Subtracts getWorldOriginOffset() 1st
		// Every Path reaching pathAndFillManager is offset-corrected: GWDT-traced ones via
		// GWDTTracerCommonCmd.applyWorldOriginOffsetIfAny, manually-traced/A*-searched ones via
		// SNT#applyWorldOriginOffsetIfAny(Path) (see autoTraceSync/runHeadlessTrace/ finishedPath())
		// So a Path's node coordinates are in that corrected frame, not the source's raw voxel*spacing grid. A
		// selection mixing newly-built paths with paths loaded from an older session/file that predates this correction
		// will still be off for whichever subset doesn't match.
		// There is no per-Path record of which frame its coordinates are actually in
		final double[] worldOriginOffset = getWorldOriginOffset();
		final PointInImage lo = worldBox.origin();
		final PointInImage hi = worldBox.originOpposite();
		final long[] voxelMin = new long[3];
		final long[] voxelMax = new long[3];
		voxelMin[0] = (long) Math.floor((Math.min(lo.x, hi.x) - worldOriginOffset[0]) / cal.pixelWidth);
		voxelMin[1] = (long) Math.floor((Math.min(lo.y, hi.y) - worldOriginOffset[1]) / cal.pixelHeight);
		voxelMin[2] = (long) Math.floor((Math.min(lo.z, hi.z) - worldOriginOffset[2]) / cal.pixelDepth);
		voxelMax[0] = (long) Math.ceil((Math.max(lo.x, hi.x) - worldOriginOffset[0]) / cal.pixelWidth);
		voxelMax[1] = (long) Math.ceil((Math.max(lo.y, hi.y) - worldOriginOffset[1]) / cal.pixelHeight);
		voxelMax[2] = (long) Math.ceil((Math.max(lo.z, hi.z) - worldOriginOffset[2]) / cal.pixelDepth);
		if (SNTUtils.isDebugMode()) {
			SNTUtils.log("resolveVoxelBounds: worldBox lo=(" + lo.x + "," + lo.y + "," + lo.z + ") hi=(" + hi.x + ","
					+ hi.y + "," + hi.z + ") cal=(" + cal.pixelWidth + "," + cal.pixelHeight + "," + cal.pixelDepth
					+ " " + cal.getUnit() + ") worldOriginOffset=" + java.util.Arrays.toString(getWorldOriginOffset())
					+ " rawVoxelMin=" + java.util.Arrays.toString(voxelMin) + " rawVoxelMax="
					+ java.util.Arrays.toString(voxelMax) + " sourceMin=(" + source.min(0) + "," + source.min(1) + ","
					+ source.min(2) + ") sourceMax=(" + source.max(0) + "," + source.max(1) + "," + source.max(2) + ")");
		}
		boolean clamped = false;
		for (int d = 0; d < 3; d++) {
			final long clampedMin = Math.max(voxelMin[d], source.min(d));
			final long clampedMax = Math.min(voxelMax[d], source.max(d));
			if (clampedMin != voxelMin[d] || clampedMax != voxelMax[d]) clamped = true;
			voxelMin[d] = clampedMin;
			voxelMax[d] = clampedMax;
			if (voxelMax[d] < voxelMin[d]) {
				throw new IllegalArgumentException("Requested crop does not overlap the loaded source");
			}
		}
		return new VoxelBounds(voxelMin, voxelMax, clamped);
	}

	/**
	 * The read-only, EDT-independent half of materializing a region, produced by
	 * {@link #buildMaterializedCrop(BoundingBox)} and consumed by
	 * {@link #installMaterializedCrop(MaterializedCrop)}.
	 *
	 * @param imp      the eagerly-copied, calibrated, {@link ImpUtils#setIsMaterializedCrop(ImagePlus) tagged}
	 *                 crop, not yet installed as this session's canvas
	 * @param voxelMin the crop's minimum voxel index in the source's own (raw, world-origin-offset-free)
	 *                 grid, per axis - needed by {@link #installMaterializedCrop(MaterializedCrop)}, together
	 *                 with {@link #getWorldOriginOffset()}, to position every {@link Path} relative to the
	 *                 crop's local grid
	 */
	public record MaterializedCrop(ImagePlus imp, long[] voxelMin) {
	}

	/**
	 * Reads and builds a bounded pixel region of this Stream-mode session's source into a real,
	 * eagerly-copied {@link ImagePlus}, without installing it as this session's canvas yet - see
	 * {@link #installMaterializedCrop(MaterializedCrop)} for that half, and
	 * {@link #materializeDisplayCanvas(BoundingBox)} for the combined convenience call most callers
	 * want. Split out specifically so the (potentially slow, disk-/network-bound) read can run off
	 * the EDT: unlike {@link #installMaterializedCrop(MaterializedCrop)}, this method touches no
	 * Swing/AWT state (no {@code ImageWindow}, no {@link #initialize(ImagePlus)}) and is safe to call
	 * from a background thread
	 *
	 * @param worldBox the region to materialize, in the same (uncalibrated) coordinate frame as Path node coordinates,
	 *                 i.e., already corrected for {@link #getWorldOriginOffset()} if that offset is non-zero, matching
	 *                 how Paths reaching {@link #pathAndFillManager} are built (see
	 *                 {@link #resolveVoxelBounds(BoundingBox, Calibration)} which subtracts that offset before
	 *                 converting to {@link #ctSlice3d}'s own raw voxel grid). Typically, the (padded) bounding box of a
	 *                 path selection.
	 * @return the built crop, ready for {@link #installMaterializedCrop(MaterializedCrop)}
	 * @throws IllegalStateException    if this session has no loaded pixel data
	 *                                  (not in Stream mode, or the source is unavailable)
	 * @throws IllegalArgumentException if {@code worldBox} does not overlap the
	 *                                  loaded source, or the requested crop exceeds the
	 *                                  materialization memory budget
	 */
	public MaterializedCrop buildMaterializedCrop(final BoundingBox worldBox) {
		return buildMaterializedCrop(worldBox, false);
	}

	/**
	 * As {@link #buildMaterializedCrop(BoundingBox)}, but reading from either this session's main
	 * streamed source or its secondary (filtered) image.
	 *
	 * @param useSecondary if true, crops {@link #getSecondaryData()} instead of the main streamed source
	 * @throws IllegalStateException if {@code useSecondary} is true but no secondary image is loaded
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public MaterializedCrop buildMaterializedCrop(final BoundingBox worldBox, final boolean useSecondary) {
		// Spacing for the coordinate conversion below: see getCalibration() for why this may come from
		// a Path's own calibration rather than this session's fields. Path#getXUnscaledDouble()/
		// getYUnscaledDouble()/getZUnscaledDouble() divide by that per-Path value, not this session's -
		// so installMaterializedCrop()'s own canvasOffset must be computed with the exact same value, or
		// paths land at the wrong position. For an anisotropic mismatch this is not a uniform shift: for
		// a diagonally-oriented structure it can look like a flipped/scaled start point rather than an
		// obvious offset. Reused below to stamp the crop's own output Calibration too, so the two can
		// never disagree (this used to be a real, separate bug: the crop's pixel data was read using
		// this calibration, but the resulting ImagePlus was stamped with plain getCalibration()'s old,
		// non-fallback-aware value - fine when they matched, silently wrong when they didn't).
		final Calibration cal = getCalibration();
		if (SNTUtils.isDebugMode()) {
			SNTUtils.log("buildMaterializedCrop called: worldBox: " + worldBox + " useSecondary: " + useSecondary
					+ " >> spacingKnownFromSource: " + spacingKnownFromSource);
		}

		// Coordinate -> voxel-index conversion (deliberately NOT subtracting getWorldOriginOffset(), see
		// resolveVoxelBounds() for the full reasoning plus clamping to the loaded source's own extent (padding,
		// or a fixed-size/center region, can overshoot it)
		final VoxelBounds bounds = resolveVoxelBounds(worldBox, cal, useSecondary);
		final long[] voxelMin = bounds.min();
		final long[] voxelMax = bounds.max();
		final RandomAccessibleInterval source = useSecondary ? resolveSecondarySourceOrFail() : resolveMainStreamedSource();

		// Pre-flight memory check. Mirrors assembleDisplayCanvases() above, but sized from the actual
		// crop dimensions/bit-depth, not a fixed 1-byte/pixel GRAY8 guess: a materialized crop is a
		// real image. Uses the same estimateMaterializationBytes(...)/ getMaterializationMemoryBudget() pair
		// MaterializeRegionDialog's live estimate calls, so they won't disagree
		final long cropWidth = voxelMax[0] - voxelMin[0] + 1;
		final long cropHeight = voxelMax[1] - voxelMin[1] + 1;
		final long cropDepth = voxelMax[2] - voxelMin[2] + 1;
		final long memNeeded = estimateMaterializationBytes(cropWidth, cropHeight, cropDepth);
		final long memAvailable = getMaterializationMemoryBudget();
		if (memNeeded > memAvailable) {
			throw new IllegalArgumentException(String.format(
					"Selection bounding box (%.2f GB) exceeds the materialization budget (%.2f GB available); "
							+ "select fewer/shorter paths, or reduce padding.",
					memNeeded / 1e9, memAvailable / 1e9));
		}

		final RandomAccessibleInterval cropView = Views.interval(source, new FinalInterval(voxelMin, voxelMax));
		// Crop + eager copy. raiToImp() alone is a *lazy* ImageJFunctions.wrap(...); without an eager copy the
		// virtual image is not writable and e.g. any subsequent pixel operations will either fail or will be
		// silently ignored. raiToImp().duplicate() gets there but copies every slice TWICE, single-threaded
		// (once when the virtual stack projects it, once more when duplicate()'s ip.crop() copies it again).
		// raiToImpFast() does the same eager, writable copy in one, potentially multithreaded, pass; see
		// SNTPrefs#isFastCropMaterializationEnabled()
		// any failure here (e.g. an unsupported pixel type) falls back to the slow path.
		ImagePlus fastCropImp = null;
		if (getPrefs().isFastCropMaterializationEnabled()) {
			try {
				fastCropImp = ImgUtils.raiToImpFast(cropView, "Materialized Region");
			} catch (final Throwable t) {
				SNTUtils.log("raiToImpFast() failed (" + t + "); falling back to raiToImp().duplicate()");
			}
		}
		final ImagePlus cropImp = (fastCropImp != null) ? fastCropImp
				: ImgUtils.raiToImp(cropView, "Materialized Region").duplicate();
		cropImp.setTitle("Materialized Region"); // duplication/raiToImpFast may not preserve title
		final double[] worldOriginOffset = getWorldOriginOffset();
		// Image subtitle already lists cal.unit, so no need to include it in the origin label
		ImpUtils.setSliceLabels(cropImp.getStack(), String.format("Origin: %.2f,%.2f,%.2f", // will be flanked by ()
				voxelMin[0] * cal.pixelWidth + worldOriginOffset[0],
				voxelMin[1] * cal.pixelHeight + worldOriginOffset[1],
				voxelMin[2] * cal.pixelDepth + worldOriginOffset[2]));
		cropImp.resetDisplayRange();
		cropImp.setCalibration(cal);
		ImpUtils.setIsMaterializedCrop(cropImp);
		return new MaterializedCrop(cropImp, voxelMin);
	}

	/**
	 * Installs a crop built by {@link #buildMaterializedCrop(BoundingBox)} as this session's own XY
	 * canvas, in place, the same mechanism {@link #rebuildDisplayCanvases()} uses for the blank
	 * placeholder, but with real pixel data instead of an empty image. This allows right-click
	 * editing (extend/fork/join/delete nodes, wired into {@link InteractiveTracerCanvas}) real pixel
	 * context in Stream mode, where BDV/BVV have no equivalent context menu.
	 * <p>
	 * Touches Swing/AWT state ({@link #initialize(ImagePlus)}, showing/fronting the {@code
	 * ImageWindow}) and so, unlike {@link #buildMaterializedCrop(BoundingBox)}, MUST be called on the
	 * EDT.
	 * <p>
	 * Deliberately scoped to editing, not tracing: this reuses the normal {@link #initialize(ImagePlus)} pipeline,
	 * which also replaces {@link #ctSlice3d} with the crop's own (small) pixel data. A* search/GWDT tracing initiated
	 * after materializing is therefore scoped to the crop's own bounds, not the full streamed volume, until a different
	 * region is materialized (or the session is reconnected). There is no path for pixel edits to write back into the
	 * canonical streamed source: only geometry/paths persist, pixel edits are local to this session.
	 *
	 * @param crop the crop to install, from {@link #buildMaterializedCrop(BoundingBox)}
	 */
	public void installMaterializedCrop(final MaterializedCrop crop) {
		// Cancel any A* search still in flight on the classic canvas before this method proceeds to
		// swap ctSlice3d/activeCanvasPixelOffset below: createSearch() captures img=getLoadedData() once,
		// by reference, when a search is constructed, so a still-running search keeps reading the OLD
		// (pre-crop) data safely rather than crashing, and confirmTemporary() inverts its result using
		// the offset stamped on the in-progress currentPath at start time, not this method's new one -
		// so the immediate result stays self-consistent even without this call. What is NOT safe is
		// leaving that search (and the temporaryPath/currentPath state it feeds) running across the
		// swap: once installed, this session's canvas, spacing and per-Path canvasOffset are all the
		// crop's, and a currentPath finished afterward via finishedPath() would still carry whatever
		// (now-superseded) canvasOffset it started with. Simplest to just not let that race happen.
		// BDV/BVV tracing is unaffected either way - see getStreamedOrLoadedData()/manualTraceHeadless(),
		// which never read the live, crop-local activeCanvasPixelOffset/getLoadedData() in the first place
		cancelSearch(false);
		// Replace this session's own XY canvas in place. The isMaterializedCrop tag makes setFieldsFromImage skip
		// pathAndFillManager.assignSpatialSettings(...), which would otherwise reset every Path's canvasOffset to zero
		// and overwrite their spacing. No new world origin offset is needed either
		initialize(crop.imp());

		// Position every Path relative to the crop's own local pixel grid: its own (0,0,0) is voxelMin in the full
		// dataset's grid, not the dataset's own origin. Paths outside these bounds simply will not render on the
		// (small) crop canvas, same as any other image only showing what falls within its own dimensions.
		// Also (re)stamp every Path's own spacing from the crop's calibration: the classic 2D canvas draws nodes via
		// PathNodeCanvas#getScreenCoordinateX/Y: it reads each Path's OWN x_spacing/y_spacing/z_spacing field, not
		// this session's. setFieldsFromImage() above deliberately skips assignSpatialSettings() for a tagged
		// materialized crop (see comment above), so a Path  that still carries whatever spacing it had at
		// import (e.g. the SWC-import default of 1,1,1) would be off. BVV/BDV are unaffected
		// (they draw from a Path's raw node coordinates directly, never consulting its per-Path spacing)
		// voxelMin is in raw source-voxel-index space (world origin offset already subtracted, see
		// resolveVoxelBounds()), but a Path's own node coordinates (pim.x/y/z, read directly by
		// PathNodeCanvas#getScreenCoordinateX/Y) carry that offset baked in as a translation (see
		// GWDTTracerCommonCmd#applyWorldOriginOffsetIfAny / SNT#applyWorldOriginOffsetIfAny(Path)), so
		// that they align with BDV/BVV's real-world frame. Left uncorrected, canvasOffset and pim.x/y/z
		// would be in two different frames
		final long[] voxelMin = crop.voxelMin();
		final double[] originOffset = getWorldOriginOffset();
		final Calibration cropCal = crop.imp().getCalibration();
		final PointInCanvas canvasOffset = new PointInCanvas(
				-voxelMin[0] - originOffset[0] / cropCal.pixelWidth,
				-voxelMin[1] - originOffset[1] / cropCal.pixelHeight,
				-voxelMin[2] - originOffset[2] / cropCal.pixelDepth);
		syncActivePathCanvasState(canvasOffset, cropCal);

		// The classic canvas's "Materialize Region"/"Create Canvas" button is not disabled while a path is mid-trace
		// (PARTIAL_PATH), so this method can run with lastStartPointSet still true. last_start_point_x/y/z was computed
		// against whatever activeCanvasPixelOffset/x_spacing was live before the swap above: recompute it now, the same
		// way confirmTemporary() does, or the  next click's testPathTo() would seed its search from a start point in
		// the OLD frame against an end point in the NEW one
		if (lastStartPointSet && currentPath != null && currentPath.size() > 0) {
			final PointInImage last = currentPath.lastNode();
			final PointInCanvas recoveryOffset = currentPathIsNew ? cropRelativeCanvasOffset() : activeCanvasPixelOffset;
			last_start_point_x = last.x / x_spacing + recoveryOffset.x;
			last_start_point_y = last.y / y_spacing + recoveryOffset.y;
			last_start_point_z = last.z / z_spacing + recoveryOffset.z;
		}
		// An already-completed-but-unconfirmed segment preview was also built against the OLD frame and would confirm
		// into the wrong place if kept - discard it rather than risk it being silently accepted afterward
		if (temporaryPath != null) {
			temporaryPath = null; // direct field access: canvas is about to be rebuilt/repainted anyway
		}

		if (xy != null && !xy.isVisible()) xy.show();
		if (xy != null && xy.getWindow() != null) xy.getWindow().toFront();
	}

	/**
	 * Convenience call combining {@link #buildMaterializedCrop(BoundingBox)} and
	 * {@link #installMaterializedCrop(MaterializedCrop)} - reads the region and installs it as this  session's canvas,
	 * in one (synchronous, EDT-blocking if called from the EDT) call. Fine for scripts and other non-interactive
	 * callers; a UI wanting to keep the interface responsive during a potentially slow read should instead call the
	 * two halves directly, backgrounding {@link #buildMaterializedCrop(BoundingBox)}.
	 *
	 * @param worldBox see {@link #buildMaterializedCrop(BoundingBox)}
	 * @throws IllegalStateException see {@link #buildMaterializedCrop(BoundingBox)}
	 * @throws IllegalArgumentException see {@link #buildMaterializedCrop(BoundingBox)}
	 */
	public void materializeDisplayCanvas(final BoundingBox worldBox) {
		materializeDisplayCanvas(worldBox, false);
	}

	/**
	 * As {@link #materializeDisplayCanvas(BoundingBox)}, but reading from either this session's main
	 * streamed source or its secondary (filtered) image - see
	 * {@link #buildMaterializedCrop(BoundingBox, boolean)}.
	 */
	public void materializeDisplayCanvas(final BoundingBox worldBox, final boolean useSecondary) {
		installMaterializedCrop(buildMaterializedCrop(worldBox, useSecondary));
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
		if (!sameImp && !seedOverlay.isEmpty()) {
			// Imported seeds are tied to a specific image's coordinate space; discard them when switching to a
			// different primary image so we never render stale points on the wrong canvas.
			final boolean discard = (getUI() == null) || getConfirmation(
					"The " + seedOverlay.size() + " seed point(s) currently in memory come from a different image."
							+"<br><br>"
							+ "If the new image is a different channel/time-point of the same dataset, the seeds remain "
							+ "valid and can be kept. Otherwise they will likely sit in the wrong place on the new image."
							+ " <br><br>Discard existing seed points?",
					"Discard Seeds?");
			if (discard) seedOverlay.clear();
		}
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
		// Alt+Click -> edit the nearest seed under the cursor. Defers to SeedOverlay.nearest(...).
		// Does nothing when  the overlay is empty. Should not interact with existing tracing handlers.
		SeedOverlayCanvasHandler.install(xy_tracer_canvas, this);

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
			SeedOverlayCanvasHandler.install(xz_tracer_canvas, this);
			SeedOverlayCanvasHandler.install(zy_tracer_canvas, this);
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
		this.ctSlice3d = ImgUtils.getCtSlice3d(imp, channel, frame); // 1-index as per IJ convention.

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
		startUI(false);
	}

	public void startUI(final boolean bigDataMode) {
		if (SwingUtilities.isEventDispatchThread()) {
			startUIOnEDT(bigDataMode);
		} else {
			try {
				SwingUtilities.invokeAndWait(() -> startUIOnEDT(bigDataMode));
			} catch (final Throwable e) {
				throw new RuntimeException("Failed to start UI", e);
			}
		}
	}

	private void startUIOnEDT(final boolean bigDataMode) {
		setBigDataMode(bigDataMode);
		GuiUtils.setLookAndFeel();
		final SNT thisPlugin = this;
		ui = new SNTUI(thisPlugin, bigDataMode);
		guiUtils = new GuiUtils(ui);
		ui.displayOnStarting();
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

	/**
	 * Returns the {@link SeedOverlay} associated with this SNT instance. The
	 * overlay holds candidate {@link sc.fiji.snt.seed.SeedPoint}s (e.g., output of upstream
	 * point detectors) and is rendered on the tracing canvas when non-empty.
	 * <p>
	 * The overlay is <b>transient</b>: it is automatically cleared when the
	 * active image changes (see {@link #initialize(ImagePlus)}) and is not
	 * persisted with the {@code .traces} file. Importers should use
	 * {@link SeedOverlay#addAll(java.util.Collection)} for bulk loads.
	 *
	 * @return the seed overlay (never {@code null})
	 */
	public SeedOverlay getSeedOverlay() {
		return seedOverlay;
	}

	InteractiveTracerCanvas getXYCanvas() {return xy_tracer_canvas;}

	InteractiveTracerCanvas getXZCanvas() {
		return xz_tracer_canvas;
	}

	InteractiveTracerCanvas getZYCanvas() {
		return zy_tracer_canvas;
	}

	public TracerCanvas getCanvas(final int pane) {
        return switch (pane) {
            case XY_PLANE -> xy_tracer_canvas;
            case XZ_PLANE -> xz_tracer_canvas;
            case ZY_PLANE -> zy_tracer_canvas;
            default -> null;
        };
	}

	/**
	 * Gets the Image being traced as Dataset. If the loaded image has been closed,  cached pixel data is returned as
	 * per {@link #getLoadedDataAsImp()}. If no resident {@link ImagePlus} exists at all (e.g., data streamed from
	 * disk/network, backed only by {@link #ctSlice3d}), a Dataset is instead built directly from {@link
	 * #getLoadedDataAsImg(boolean)}, so Dataset-only commands. Null is returned if no image data exists at all.
	 * <p>
	 * NB: in the streamed-data fallback, the returned Dataset only ever contains a single channel/
	 * frame (whichever is currently active): {@link #ctSlice3d} is a single C/T slice by construction. Commands that
	 * need every channel or frame simultaneously will still require a resident {@link ImagePlus}.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public Dataset getDataset() {
		final ImagePlus imp = getImagePlus();
		if (imp != null) return convertService.convert(imp, Dataset.class);
		if (datasetService == null) return null;
		final ImgPlus<?> imgPlus = getLoadedDataAsImg(false);
		return (imgPlus == null) ? null : datasetService.create((ImgPlus) imgPlus);
	}

	/**
	 * Gets the Image being traced. If the loaded image has been closed,
	 * cached pixel data is returned as per {@link #getLoadedDataAsImp()}. Null is returned
	 * if no image exists.
	 */
	public ImagePlus getImagePlus() {
		return getImagePlus(XY_PLANE);
	}

	protected double getImpDiagonalLength(final boolean scaled, final boolean xyOnly) {
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
	public InteractiveTracerCanvas createCanvas(final ImagePlus imagePlus, final int plane) {
		return new InteractiveTracerCanvas(imagePlus, this, plane, pathAndFillManager);
	}

	protected void dispose() {
		getPrefs().savePluginPrefs(true);
		// dispose data structures
		cancelSearch(true); // will discard tracerThreadPool, fillerThreadPool, currentSearchThread, manualSearchThread, tubularGeodesicsThread
		flushSecondaryData(); // will discard secondaryData
		if (searchArtists != null) searchArtists.clear();
		if (fillerSet != null) fillerSet.clear();
		// Close the tracing panes (removing the canvases) BEFORE disposing the
		// PathAndFillManager: pathAndFillManager.dispose() nulls its plugin
		// reference, and a still-open canvas receiving a late system repaint would
		// otherwise NPE in TracerCanvas.drawOverlay (plugin == null).
		closeAndResetAllPanes();
		if (pathAndFillManager != null) pathAndFillManager.dispose();
		if (univ != null && univ.getWindow() != null) univ.getWindow().dispose();
		if (ui != null) ui.dispose();
		notifyListeners(new SNTEvent(SNTEvent.QUIT));
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
				//  of the main while-loop. Increasing the timeout is just a temporary Band-Aid until we find a
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
		changeUIState(tracingHalted ? SNTUI.TRACING_PAUSED : SNTUI.WAITING_TO_START_PATH);
		if (getUI() != null)
			getUI().getFillManager().changeState(FillManagerUI.State.READY);
	}

	protected synchronized void discardFill() {
		if (fillerSet.isEmpty()) {
			SNTUtils.log("No Fill(s) to discard...");
			return;
		}
		for (FillerThread filler : fillerSet) {
			removeThreadToDraw(filler);
		}
		fillerSet.clear();
		pathAndFillManager.getLoadedFills().clear();
		fillerThreadPool = null;
		changeUIState(tracingHalted ? SNTUI.TRACING_PAUSED : SNTUI.WAITING_TO_START_PATH);
		if (getUI() != null)
			getUI().getFillManager().changeState(FillManagerUI.State.READY);
	}

	protected synchronized void stopFilling(boolean updateUIState) {

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
			if (updateUIState && getUI() != null)
				getUI().getFillManager().changeState(FillManagerUI.State.STOPPED);
		}

	}

	protected synchronized void stopFilling() {
		stopFilling(true); // backward compatible
	}

	protected synchronized void startFilling() {
		if (fillerSet.isEmpty()) {
			throw new IllegalArgumentException("No Filters loaded.");
		}
		if (fillerThreadPool != null) {
			throw new IllegalArgumentException("Filler already running.");
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
				stopFilling(false); // Don't change state yet
				if (ui != null) {
					if (fillerSet.isEmpty()) {
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
				// result's nodes are raw search output (crop-local pixel * spacing, no worldOriginOffset baked in yet
				// (see applyWorldOriginOffsetIfAny()), same convention confirmTemporary() corrects for). Without this
				// stamp the live preview renders using canvasOffset(0,0,0) (Path's default), landing near
				// the crop's own pixel-index origin instead of the clicked location whenever activeCanvasPixelOffset is
				// non-zero (i.e. tracing on a materialized crop)
				result.setCanvasOffset(currentPathIsNew ? cropRelativeCanvasOffset() : activeCanvasPixelOffset);
				setTemporaryPath(result);
				// Hook 2: Run plausibility checks on candidate segment
				if (ui != null && ui.getPlausibilityMonitor().isEnabled() && currentPath != null) {
					ui.getPlausibilityMonitor().onSegmentCompleted(result);
				}
				if (rubberBandTracing) {
					// In rubber band mode: show preview, stay in PARTIAL_PATH.
					// The user clicks to accept; no Y confirmation needed.
					changeUIState(SNTUI.PARTIAL_PATH);
					updateTracingViewers(true);
				} else if (ui == null) {
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
				if (!rubberBandTracing) // suppress error state chatter during live preview
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
		// STREAMING is Stream mode's own "ready to trace"/"tracing paused" state: WAITING_TO_START_PATH
		// and TRACING_PAUSED are both redirected to it by SNTUI#changeState(int) while isStreamMode() true
		return isUIready() && (getUIState() == SNTUI.WAITING_TO_START_PATH ||
				getUIState() == SNTUI.TRACING_PAUSED || getUIState() == SNTUI.STREAMING);
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

	/**
	 * Checks if edit mode can be enabled.
	 * @param warnUserIfNot if true, shows error messages when edit mode is not allowed
	 * @return true if edit mode is allowed, false otherwise
	 */
	protected boolean editModeAllowed(final boolean warnUserIfNot) {
		return editModeAllowed(warnUserIfNot, null);
	}

	/**
	 * Checks if edit mode can be enabled, optionally using a specific path.
	 * @param warnUserIfNot if true, shows error messages when edit mode is not allowed
	 * @param pathToEdit the path to edit, or null to auto-detect from selection
	 * @return true if edit mode is allowed, false otherwise
	 */
	protected boolean editModeAllowed(final boolean warnUserIfNot, final Path pathToEdit) {
		final boolean uiReady = uiReadyForModeChange() || isEditModeEnabled();
		if (warnUserIfNot && !uiReady) {
			showCanvasWarning("Please finish current operation before editing paths");
			return false;
		}
		// Use provided path or detect from selection
		if (pathToEdit != null) {
			editingPath = pathToEdit;
		} else {
			detectEditingPath();
		}
		final boolean pathExists = editingPath != null;
		if (warnUserIfNot && !pathExists) {
			showCanvasWarning("You must select a single path in order to edit it");
			return false;
		}
		return uiReady && pathExists;
	}

	/**
	 * Returns whether the path currently being edited is displayed as its fitted
	 * version. Callers should use this to guard edit operations that cannot
	 * meaningfully propagate between fitted and unfitted representations (e.g.,
	 * structural edits such as insert/delete, or tree-level operations like
	 * split/re-root).
	 *
	 * @return true if the editing path is in fitted-display mode
	 */
	protected boolean isEditingFittedPath() {
		return editingPath != null && editingPath.getUseFitted();
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
		}
		else {
			if (ui != null) ui.resetState();
		}

		// Only re-detect editingPath if not already set (e.g., by editModeAllowed)
		if (enable) {
			if (editingPath == null && pathAndFillManager.getSelectedPaths().size() == 1) {
				editingPath = getSelectedPaths().iterator().next();
			}
			// If still null, edit mode shouldn't be enabled
			if (editingPath == null) {
				if (ui != null) ui.resetState();
				return;
			}
		} else {
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
			// find the nearest node to this cursor position. x,y,z are pane-local pixel indices;
			// existing paths' node coordinates are stored in the true/world grid (see
			// getActiveCanvasPixelOffset()'s javadoc), so correct before comparing.
			pim = pathAndFillManager.nearestJoinPointOnSelectedPaths(x - activeCanvasPixelOffset.x,
					y - activeCanvasPixelOffset.y, z - activeCanvasPixelOffset.z);
		}
		else if (editing && !editingPath.isEditableNodeLocked()) {
			// find the nearest node to this cursor 2D position.
			// then activate the Z-slice of the retrieved node.
			// When the fitted version is displayed, match against fitted nodes
			// so the user clicks on what they see
			final Path matchPath = editingPath.getUseFitted() ? editingPath.getFitted() : editingPath;
			final int eNode = matchPath.indexNearestToCanvasPosition2D(x, y,
					getXYCanvas().nodeDiameter());
			if (eNode != -1) {
				pim = matchPath.getNodeWithoutChecks(eNode);
				editingPath.setEditableNode(eNode);
			}
		}
		if (pim != null) {
			// pim came from an existing path's true-world node coordinates; convert back to this
			// canvas's own local pixel indices (inverse of the correction above) for the cross-hair
			// sync/status-message/labelData lookups below, which all expect local pixel space
			x = pim.x / x_spacing + activeCanvasPixelOffset.x;
			y = pim.y / y_spacing + activeCanvasPixelOffset.y;
			z = pim.z / z_spacing + activeCanvasPixelOffset.z;
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

		// ix/iy/iz are local canvas-pixel indices (crop-local when a crop is materialized);
		// subtract activeCanvasPixelOffset to report the true-world coordinate, same
		// convention as getActiveCanvasPixelOffset()'s javadoc
		statusMessage += String.format("World: (%.2f, %.2f, %.2f);",
				(ix - activeCanvasPixelOffset.x) * x_spacing,
				(iy - activeCanvasPixelOffset.y) * y_spacing,
				(iz - activeCanvasPixelOffset.z) * z_spacing);
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

		if (!rubberBandTracing && temporaryPath != null) {
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

		// last_start_point_x/y/z already include activeCanvasPixelOffset (see e.g. finishedPath()/
		// undoPoint()), so x_end/y_end/z_end must too, or the two ends of the search land in different
		// frames whenever a materialized crop's canvasOffset is non-zero: real_x_end/y_end/z_end is
		// either a true-world coordinate (plain click, see clickForTrace()) or an existing Path's own
		// raw node coordinate (joinPoint.x/y/z), neither of which is crop-local pixel space on its own
		final int x_end = (int) Math.round(real_x_end / x_spacing + activeCanvasPixelOffset.x);
		final int y_end = (int) Math.round(real_y_end / y_spacing + activeCanvasPixelOffset.y);
		final int z_end = (int) Math.round(real_z_end / z_spacing + activeCanvasPixelOffset.z);

		if (SNTUtils.isDebugMode()) {
			SNTUtils.log("testPathTo: world_x/y/z=(" + world_x + "," + world_y + "," + world_z + ") joinPoint="
					+ joinPoint + " real_end=(" + real_x_end + "," + real_y_end + "," + real_z_end + ") "
					+ "activeCanvasPixelOffset=(" + activeCanvasPixelOffset.x + "," + activeCanvasPixelOffset.y + ","
					+ activeCanvasPixelOffset.z + ") worldOriginOffset=" + java.util.Arrays.toString(getWorldOriginOffset())
					+ " last_start_point=(" + last_start_point_x + "," + last_start_point_y + "," + last_start_point_z
					+ ") >> start=(" + x_start + "," + y_start + "," + z_start + ") end=(" + x_end + "," + y_end + ","
					+ z_end + ")");
		}

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
		if (!rubberBandTracing)
			addThreadToDraw(currentSearchThread);
		currentSearchThread.addProgressListener(this);
		return tracerThreadPool.submit(currentSearchThread);
	}

	protected synchronized void startRubberBandSearch(final double x_in_pane,
													  final double y_in_pane, final int plane) {
		if (currentSearchThread != null) return; // previous search still running
		//setTemporaryPath(null); // clear stale preview so testPathTo can proceed
		temporaryPath = null; // direct field access, bypasses setTemporaryPath's repaint

		final double[] p = new double[3];
		findPointInStackPrecise(x_in_pane, y_in_pane, plane, p);
		// p[] is a pane-local pixel index in the current active grid (crop-local when materialized,
		// see mouseMovedTo()) - subtract activeCanvasPixelOffset before scaling to true world, or
		// testPathTo() re-adds it on top of an already-offset value (see its own x_end/y_end/z_end)
		testPathTo((p[0] - activeCanvasPixelOffset.x) * x_spacing, (p[1] - activeCanvasPixelOffset.y) * y_spacing,
				(p[2] - activeCanvasPixelOffset.z) * z_spacing, null);
		// NB: no changeUIState: stays in PARTIAL_PATH
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

	// Promoted from private to public so external callers (e.g. CostPalette,
	// the cost-function wizard) can compute the same per-CostType sub-volume
	// statistics SNT uses internally when building an A* search.
	public <T extends RealType<T>> ImageStatistics computeImgStats(final Iterable<T> in,
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
		return createSearch(world_x_start, world_y_start, world_z_start, world_x_end, world_y_end, world_z_end, null);
	}

	private AbstractSearch createSearch(final double world_x_start,
										final double world_y_start,
										final double world_z_start,
										final double world_x_end,
										final double world_y_end,
										final double world_z_end,
										final SearchSettingsSnapshot settings)
	{
		return createSearch(world_x_start, world_y_start, world_z_start, world_x_end, world_y_end, world_z_end,
				settings, false);
	}

	/**
	 * @param useStreamedSource if true, resolves the world-to-pixel offset via {@link
	 *          #defaultCanvasPixelOffset()} (stable across materialization) and searches {@link
	 *          #getStreamedOrLoadedData()} instead of the live {@link #activeCanvasPixelOffset}/{@link
	 *          #getLoadedData()} (crop-local while a crop is materialized). Set by BDV/BVV's own
	 *          headless tracing (see {@link #autoTraceHeadless(List, PointInImage,
	 *          SearchProgressCallback, Consumer)}), whose rendering always shows the full, unaffected
	 *          volume regardless of what the classic canvas currently has materialized.
	 */
	private AbstractSearch createSearch(final double world_x_start,
										final double world_y_start,
										final double world_z_start,
										final double world_x_end,
										final double world_y_end,
										final double world_z_end,
										final SearchSettingsSnapshot settings,
										final boolean useStreamedSource)
	{
		// world_x/y/z_start/end are true-world coordinates (see runHeadlessTrace()'s SNTPoint start/end,
		// sourced from e.g. BDV/BVV's own getGlobalMouseCoordinates()). Converting to a pixel index needs
		// an offset into whichever grid will actually be searched: the live activeCanvasPixelOffset (the
		// crop-local pixel grid when a materialized crop is active, or defaultCanvasPixelOffset()'s raw
		// streamed source grid otherwise), or, when useStreamedSource is set, always
		// defaultCanvasPixelOffset() paired with getStreamedOrLoadedData() - see that method's javadoc
		final PointInCanvas offset = useStreamedSource ? defaultCanvasPixelOffset() : activeCanvasPixelOffset;
		final int x_start = (int) Math.round(world_x_start / x_spacing + offset.x);
		final int y_start = (int) Math.round(world_y_start / y_spacing + offset.y);
		final int z_start = (int) Math.round(world_z_start / z_spacing + offset.z);
		final int x_end = (int) Math.round(world_x_end / x_spacing + offset.x);
		final int y_end = (int) Math.round(world_y_end / y_spacing + offset.y);
		final int z_end = (int) Math.round(world_z_end / z_spacing + offset.z);
		if (SNTUtils.isDebugMode()) {
			SNTUtils.log("createSearch(double...): world_start=(" + world_x_start + "," + world_y_start + ","
					+ world_z_start + ") world_end=(" + world_x_end + "," + world_y_end + "," + world_z_end
					+ ") useStreamedSource=" + useStreamedSource + " offset=(" + offset.x + "," + offset.y + ","
					+ offset.z + ") worldOriginOffset="
					+ java.util.Arrays.toString(getWorldOriginOffset()) + " >> start=(" + x_start + "," + y_start
					+ "," + z_start + ") end=(" + x_end + "," + y_end + "," + z_end + ")");
		}
		return createSearch(x_start, y_start, z_start, x_end, y_end, z_end, settings, useStreamedSource);
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
		return createSearch(x_start, y_start, z_start, x_end, y_end, z_end, null);
	}

	/**
	 * Immutable snapshot of the search settings that {@link #createSearch} would otherwise read
	 * live from the enclosing {@link SNT} instance ({@link #costType}, {@link #searchImageType},
	 * whether tracing on the secondary/filtered image is active).
	 * <p>
	 * Interactive, click-driven tracing is meant to always use whatever is currently configured, so
	 * it never needs this: {@link #createSearch} falls back to the live fields when no snapshot is
	 * given. Batch callers that run over a long, possibly unattended period (e.g. {@code
	 * AStarRefiner}, invoked from {@code PathManagerUI}'s "Re-trace with A*...") should instead take
	 * one snapshot when the batch is queued and reuse it for every path/segment, so a setting
	 * changed mid-run can't silently produce a batch with inconsistent per-path settings.
	 *
	 * @see #snapshotSearchSettings()
	 * @see #autoTraceSync(List, PointInImage, SearchSettingsSnapshot)
	 */
	public static final class SearchSettingsSnapshot {
		final CostType costType;
		final SearchImageType searchImageType;
		final boolean useSecondary;

		private SearchSettingsSnapshot(final CostType costType, final SearchImageType searchImageType,
										final boolean useSecondary) {
			this.costType = costType;
			this.searchImageType = searchImageType;
			this.useSecondary = useSecondary;
		}
	}

	/**
	 * Captures the search settings currently configured on this instance, for use by long-running
	 * batch callers that need to stay internally consistent even if these settings are changed
	 * (via the UI) partway through the run. See {@link SearchSettingsSnapshot}.
	 *
	 * @return an immutable snapshot of the current cost function, data structure, and secondary
	 *         (filtered) image state
	 */
	public SearchSettingsSnapshot snapshotSearchSettings() {
		return new SearchSettingsSnapshot(costType, searchImageType, isTracingOnSecondaryImageActive());
	}

	private <T extends RealType<T>> AbstractSearch createSearch(final int x_start,
																final int y_start,
																final int z_start,
																final int x_end,
																final int y_end,
																final int z_end,
																final SearchSettingsSnapshot settings)
	{
		return createSearch(x_start, y_start, z_start, x_end, y_end, z_end, settings, false);
	}

	/**
	 * @param useStreamedSource if true, searches {@link #getStreamedOrLoadedData()} instead of {@link
	 *          #getLoadedData()} for the primary image (secondary/filtered-image tracing is already
	 *          crop-independent - see {@link #secondaryData} - so this only affects the non-secondary
	 *          case). See {@link #createSearch(double, double, double, double, double, double,
	 *          SearchSettingsSnapshot, boolean)}.
	 */
	private <T extends RealType<T>> AbstractSearch createSearch(final int x_start,
																final int y_start,
																final int z_start,
																final int x_end,
																final int y_end,
																final int z_end,
																final SearchSettingsSnapshot settings,
																final boolean useStreamedSource)
	{
		final boolean useSecondary = (settings != null) ? settings.useSecondary : isTracingOnSecondaryImageActive();
		final CostType effCostType = (settings != null) ? settings.costType : costType;
		final SearchImageType effSearchImageType = (settings != null) ? settings.searchImageType : searchImageType;

		final RandomAccessibleInterval<T> img = useSecondary ? getSecondaryData()
				: (useStreamedSource ? getStreamedOrLoadedData() : getLoadedData());

		// getStreamedOrLoadedStats(): stats gets overwritten with a materialized crop's own (smaller) statistics,
		// which would miscalibrate a search over the full streamed source. Only matters when isUseSubVolumeStats is off
		// below computes fresh sub-volume-local statistics from img itself either way, already correctly source-aware
		ImageStatistics imgStats = useSecondary ? statsSecondary
				: (useStreamedSource ? getStreamedOrLoadedStats() : stats);
		if (isUseSubVolumeStats)
		{
			SNTUtils.log("Computing local statistics...");
			// NB: compute into a fresh, local instance rather than mutating the shared stats/ statsSecondary fields
			// in place. createSearch() can now be invoked concurrently from multiple threads (AStarRefiner/autoTraceSync),
			// and those fields are shared SNT-instance state: mutating them here would race across threads!
			imgStats = computeImgStats(
					ImgUtils.subInterval(img, new Point(x_start, y_start, z_start), new Point(x_end, y_end, z_end), 10),
					new ImageStatistics(), effCostType);
		}

		Cost costFunction;
		switch (effCostType)
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
				throw new IllegalArgumentException("BUG: Unknown cost function " + effCostType);
		}

		Heuristic heuristic = switch (heuristicType) {
			case EUCLIDEAN -> new Euclidean(getCalibration());
			case DIJKSTRA -> new Dijkstra();
			default -> throw new IllegalArgumentException("BUG: Unknown heuristic " + heuristicType);
		};

		if (settings == null) {
			// Interactive tracing
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

		// Batch caller with a frozen snapshot: use the explicit-parameter constructors so
		// effSearchImageType is honored instead of whatever searchImageType currently is live.
		// timeoutSeconds=0, reportEveryMilliseconds=1000 match the defaults the SNT-aware
		// constructors above use internally (see AbstractSearch(SNT, RandomAccessibleInterval)).
		//
		// NB: deliberately NOT getCalibration() here: x_start/y_start/z_start/x_end/y_end/z_end above
		// (and createSearch(double...), which computed them) always use the raw x_spacing/y_spacing/
		// z_spacing fields. getCalibration() can legitimately return something else when
		// !isSpacingKnownFromSource() (falls back to a loaded Path's own calibration) - passing that
		// here would give this search's own internal xSep/ySep/zSep a different scale than the pixel
		// indices it was just seeded with, corrupting asPath()'s pixelIndex*spacing conversion by a
		// factor of calibration/x_spacing
		final Calibration searchCal = new Calibration();
		searchCal.pixelWidth = x_spacing;
		searchCal.pixelHeight = y_spacing;
		searchCal.pixelDepth = z_spacing;
		searchCal.setUnit(spacing_units);
		return switch (searchType) {
			case ASTAR -> new TracerThread(
					img,
					searchCal,
					x_start,
					y_start,
					z_start,
					x_end,
					y_end,
					z_end,
					0,
					1000,
					effSearchImageType,
					costFunction,
					heuristic);
			case NBASTAR -> new BiSearch(
					img,
					searchCal,
					x_start,
					y_start,
					z_start,
					x_end,
					y_end,
					z_end,
					0,
					1000,
					effSearchImageType,
					costFunction,
					heuristic);
			default -> throw new IllegalArgumentException("BUG: Unknown search class");
		};
	}

	public synchronized void confirmTemporary(final boolean updateTracingViewers) {

		if (temporaryPath == null)
			// Just ignore the request to confirm a path (there isn't one):
			return;

		// Adjust temporaryPath node coordinates before merging: testPathTo() built it in pixel * spacing space, using
		// whichever activeCanvasPixelOffset was live when that search started. Read the LIVE offset here too, not
		// currentPath.getCanvasOffset() (a value captured once, back at startPath()/replaceCurrentPath() time): the two
		// are normally identical, but can drift if a crop is materialized/re-materialized while this path is still
		// unconfirmed - the search that just produced temporaryPath always used the live offset (see testPathTo()).
		//
		// Only the CROP-RELATIVE portion of that offset is subtracted here for a brand-new path (see
		// cropRelativeCanvasOffset()'s javadoc), not the full offset: applyWorldOriginOffsetIfAny() is
		// the single place getWorldOriginOffset() gets added, right before this path reaches
		// pathAndFillManager (see finishedPath()) - subtracting the full offset here would leave nodes
		// already complete, and applyWorldOriginOffsetIfAny() would then double-count that offset.
		//
		// currentPath is different when extending an EXISTING, already-finished path (see
		// replaceCurrentPath(), currentPathIsNew): its pre-existing nodes are already complete world,
		// and finishedPath() will NOT call applyWorldOriginOffsetIfAny() on it (its ID is already
		// registered) - so the newly merged nodes must be made complete world right here, by
		// subtracting the FULL offset instead, or they are left stuck at world - worldOriginOffset
		// forever (a permanent discontinuity from the path's own pre-existing nodes)
		final PointInCanvas offset = activeCanvasPixelOffset;
		final PointInCanvas nodeCorrection = currentPathIsNew ? cropRelativeCanvasOffset() : offset;
		if (nodeCorrection.x != 0 || nodeCorrection.y != 0 || nodeCorrection.z != 0) {
			for (int i = 0; i < temporaryPath.size(); i++) {
				final PointInImage node = temporaryPath.getNodeWithoutChecks(i);
				node.x -= nodeCorrection.x * x_spacing;
				node.y -= nodeCorrection.y * y_spacing;
				node.z -= nodeCorrection.z * z_spacing;
			}
		}
		// Keep currentPath's own canvasOffset fresh too, so its in-progress rendering/hit-testing
		// (Path#getXUnscaledDouble/Y/Z, indexNearestToCanvasPosition2D) stay correct even if it was
		// started under a different offset - not just the final stamp in finishedPath(). For a
		// brand-new path (still in the intermediate frame above) this must be the crop-relative
		// portion only, matching nodeCorrection, not the full live offset - see confirmTemporary()'s
		// canvasOffset-displacement fix; an extended path's nodes are already complete world, so the
		// full offset is correct there (and should already match what the path was stamped with)
		currentPath.setCanvasOffset(nodeCorrection);

		final int sizeBefore = currentPath.size();
		currentPath.add(temporaryPath);
		confirmedSegmentSizes.push(currentPath.size() - sizeBefore); // nodes actually added
		if (confirmedSegmentSizes.size() > SNTPrefs.MAX_UNDO_STEPS)
			confirmedSegmentSizes.removeLast(); // drop oldest
		if (manualRadius > 0) {
			if (currentPath.size() == temporaryPath.size()) {
				// First segment: stamp node 0 with whatever radius was current at start
				final double r0 = (startNodeRadius > 0) ? startNodeRadius : manualRadius;
				currentPath.setRadius(r0, 0);
				startNodeRadius = -1;
			}
			// Stamp the end-node with current manualRadius
			currentPath.setRadius(manualRadius, currentPath.size() - 1);
		}

		// last_start_point_x/y/z must be the pixel index in the CURRENT active grid (matching
		// x_start/x_end's own convention in testPathTo()); since last.x/y/z were only corrected by
		// nodeCorrection above, recovering that same pixel index needs nodeCorrection added back
		// (whichever of cropOffset/full-offset that was, per currentPathIsNew)
		final PointInImage last = currentPath.lastNode();
		last_start_point_x = last.x / x_spacing + nodeCorrection.x;
		last_start_point_y = last.y / y_spacing + nodeCorrection.y;
		last_start_point_z = last.z / z_spacing + nodeCorrection.z;

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
							GuiUtils.ctrlKey() + "-click if the start of the path should join another.");
			return;
		}

		if (temporaryPath == null) {
			//showCanvasWarning("There is no temporary path to discard");
			return; // nothing else to do
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
			if (!rubberBandTracing) {
				showCanvasWarning("You need to confirm the last segment before canceling the path.");
				return;
			}
			temporaryPath = null; // discard rubber band preview silently
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
		confirmedSegmentSizes.clear();
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
		// Tracked separately from secondaryDataCacheDir (only overwritten below, after the old cache is
		// flushed) so that flushSecondaryData() still sees and removes the PREVIOUS lazy cache, not this new one
		final java.nio.file.Path newSecondaryDataCacheDir;
		if (strategy.equalsIgnoreCase("lazy")) {
			try {
				newSecondaryDataCacheDir = java.nio.file.Files.createTempDirectory(
						SNTUtils.getCacheDir().toPath(), "secondary-lazy-");
			} catch (final IOException e) {
				throw new RuntimeException("Failed to create temp directory for secondary image disk cache", e);
			}
			filtered = Lazy.process(
					data,
					data,
					new int[]{32, 32, 32},
					new FloatType(),
					op,
					newSecondaryDataCacheDir);
		} else if (strategy.equalsIgnoreCase("preprocess")) {
			newSecondaryDataCacheDir = null;
			filtered = opService.create().img(data, new FloatType());
			op.compute(data, filtered);
		} else {
			throw new IllegalArgumentException("Unknown strategy: " + strategy);
		}
		flushSecondaryData(); // deletes the previous secondaryDataCacheDir, if any
		secondaryDataCacheDir = newSecondaryDataCacheDir;
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
	 * Manually traces a straight path from a point A to a point B, bypassing A* (or
	 * any other configured {@link SearchInterface}) entirely: the two points are
	 * simply connected via {@link ManualTracerThread}. Unlike {@link #autoTrace(SNTPoint,
	 * SNTPoint, PointInImage, boolean)}, this method does not depend on, or alter,
	 * {@link #isAstarEnabled()}. Runs headless (no SNTUI state changes) and, since it does
	 * not search the image, is cheap enough to be safely called off the EDT.
	 * <p>
	 * The returned path is <i>not</i> added to the Path Manager: callers are responsible
	 * for that (see {@link PathAndFillManager#addPath(Path, boolean, boolean)}).
	 *
	 * @param start the {@link SNTPoint} start of the path
	 * @param end the {@link SNTPoint} end of the path
	 * @param forkPoint the {@link PointInImage} fork point of the parent {@link Path}
	 *          from which the path should branch off, or null if the path should not
	 *          have any parent.
	 * @return the path a reference to the computed path.
	 */
	@SuppressWarnings("unused") // used for snt scripts
	public Path manualTrace(final SNTPoint start, final SNTPoint end, final PointInImage forkPoint) {
		return manualTraceHeadless(List.of(start, end), forkPoint);
	}

	/**
	 * Headless auto-trace variant that additionally reports progress and exposes the underlying
	 * {@link Future} for cancellation. Always runs headless (regardless of {@link
	 * GraphicsEnvironment#isHeadless()}); intended for callers (e.g., BVV's tracing toggle) that
	 * need live feedback and/or the ability to cancel a slow search, e.g., over a lazily-loaded,
	 * network-backed image where a single segment can take a long time to converge (or never
	 * converge, if the goal is unreachable).
	 *
	 * @param start the start point
	 * @param end the end point
	 * @param forkPoint the fork point of the parent {@link Path} from which the path should
	 *          branch off, or null if the path should not have any parent
	 * @param progressCallback receives periodic {@code pointsInSearch}/{@code finished}/{@code
	 *          threadStatus} updates (roughly once a second; see {@code reportEveryMilliseconds}),
	 *          or null to skip progress reporting
	 * @param onSubmit called with the search's {@link Future} immediately after it is submitted to
	 *          the tracer thread pool, so the caller can cancel it later (e.g. {@code
	 *          future.cancel(true)}); the search loop already checks for interruption, so this
	 *          actually stops the search rather than merely abandoning it. May be null.
	 * @return the path, or null if the search failed or was cancelled
	 */
	public Path autoTrace(final SNTPoint start, final SNTPoint end, final PointInImage forkPoint,
			final SearchProgressCallback progressCallback, final Consumer<Future<?>> onSubmit) {
		return autoTraceHeadless(List.of(start, end), forkPoint, progressCallback, onSubmit);
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
		return autoTraceHeadless(pointList, forkPoint, null, null);
	}

	/**
	 * Synchronous, thread-agnostic counterpart to {@link #autoTrace(List, PointInImage, boolean)}:
	 * traces each consecutive pair of waypoints using the currently configured A* search parameters
	 * (cost function, hessian, image data) entirely on the calling thread, without submitting to
	 * {@link #tracerThreadPool}.
	 * <p>
	 * Intended for background/batch callers that already run on their own dedicated worker threads
	 * (e.g. {@code AStarRefiner}) and want genuine  cross-path parallelism
	 *
	 * @param pointList the waypoints to connect, start to end
	 * @param forkPoint optional fork point of the parent Path, or null
	 * @return the stitched path, or null if any segment failed to produce a result
	 */
	public Path autoTraceSync(final List<SNTPoint> pointList, final PointInImage forkPoint) {
		return autoTraceSync(pointList, forkPoint, null);
	}

	/**
	 * Shifts a single freshly-built Path by this session's current {@link #getWorldOriginOffset()}, if any.
	 * Manual tracing and A* search do not go through {@code GWDTTracerCommonCmd#applyWorldOriginOffsetIfAny}, which
	 * does the same thing for GWDT-traced {@link Tree}s right after tracing. Without this, such Paths would be
	 * shape-correct but uniformly mispositioned relative to GWDT-traced ones and relative to what BDV/BVV render
	 * (see {@link #autoTraceSync}, {@link #runHeadlessTrace}, {@link #finishedPath()}, the 3 places a manually/A*-built
	 * Path is considered "finished"). Must be called once, right after a Path finishes being built, before it reaches
	 * {@link #pathAndFillManager} or is exported and NOT re-applied on load, since the offset is a transient,
	 * per-session correction, fully baked into node coordinates before the Path is ever persisted
	 *
	 * @param path the freshly-built Path to correct in place
	 */
	private void applyWorldOriginOffsetIfAny(final Path path) {
		final double[] offset = getWorldOriginOffset();
		if (offset[0] == 0 && offset[1] == 0 && offset[2] == 0) return;
		for (int i = 0; i < path.size(); i++) {
			final PointInImage node = path.getNodeWithoutChecks(i);
			path.moveNode(i, node.x + offset[0], node.y + offset[1], node.z + offset[2]);
		}
	}

	/**
	 * Variant of {@link #autoTraceSync(List, PointInImage)} that uses a frozen
	 * {@link SearchSettingsSnapshot} instead of the live cost function/data structure/secondary-image
	 * settings, so every segment of every path in a long-running batch (e.g. {@code AStarRefiner})
	 * is traced with identical settings, immune to changes made mid-run via the (deliberately still
	 * enabled) A* controls.
	 *
	 * @param pointList the waypoints to connect, start to end
	 * @param forkPoint optional fork point of the parent Path, or null
	 * @param settings  a snapshot from {@link #snapshotSearchSettings()}, or null to read the live
	 *                  settings (equivalent to {@link #autoTraceSync(List, PointInImage)})
	 * @return the stitched path, or null if any segment failed to produce a result
	 */
	public Path autoTraceSync(final List<SNTPoint> pointList, final PointInImage forkPoint,
							   final SearchSettingsSnapshot settings) {
		if (pointList == null || pointList.isEmpty())
			throw new IllegalArgumentException("pointList cannot be null or empty");
		final Path fullPath = new Path(x_spacing, y_spacing, z_spacing, spacing_units);
		for (int i = 0; i < pointList.size() - 1; i++) {
			final SNTPoint start = pointList.get(i);
			final SNTPoint end = pointList.get(i + 1);
			final AbstractSearch search = createSearch(start.getX(), start.getY(), start.getZ(),
					end.getX(), end.getY(), end.getZ(), settings);
			search.run(); // synchronous: runs on the calling (already-background) thread
			final Path pathResult = search.getResult();
			if (pathResult == null) {
				SNTUtils.log("Trace result was null.");
				return null;
			}
			// createSearch(...settings) above is crop-aware (does not use useStreamedSource), so
			// pathResult's raw nodes are missing both the crop-relative and worldOriginOffset
			// components (see cropRelativeCanvasOffset()'s javadoc). Correct only the crop-relative
			// portion here; applyWorldOriginOffsetIfAny() completes the rest, once, below
			final PointInCanvas segCropOffset = cropRelativeCanvasOffset();
			if (segCropOffset.x != 0 || segCropOffset.y != 0 || segCropOffset.z != 0) {
				for (int j = 0; j < pathResult.size(); j++) {
					final PointInImage node = pathResult.getNodeWithoutChecks(j);
					node.x -= segCropOffset.x * x_spacing;
					node.y -= segCropOffset.y * y_spacing;
					node.z -= segCropOffset.z * z_spacing;
				}
			}
			fullPath.add(pathResult);
		}
		if (forkPoint != null) {
			fullPath.setBranchFrom(forkPoint.getPath(), forkPoint);
		}
		fullPath.setCTposition(channel, frame);
		applyWorldOriginOffsetIfAny(fullPath);
		// See runHeadlessTrace()'s equivalent stamp: canvasOffset defaults to (0,0,0) on a freshly
		// created Path and nothing else sets it before this returns
		fullPath.setCanvasOffset(activeCanvasPixelOffset);
		return fullPath;
	}

	private Path autoTraceHeadless(final List<SNTPoint> pointList, final PointInImage forkPoint,
			final SearchProgressCallback progressCallback, final Consumer<Future<?>> onSubmit) {
		// useStreamedSource=true: this overload is BDV/BVV's own headless entry point (see autoTrace(
		// SNTPoint, SNTPoint, PointInImage, SearchProgressCallback, Consumer), its only caller), whose
		// rendering always shows the full, unaffected volume - it must keep tracing correctly regardless
		// of whether a crop happens to be materialized on the classic canvas at the same time. See
		// getStreamedOrLoadedData()/createSearch(..., boolean)
		return runHeadlessTrace(pointList, forkPoint, (start, end) -> createSearch(
				start.getX(), start.getY(), start.getZ(),
				end.getX(), end.getY(), end.getZ(), null, true), progressCallback, onSubmit);
	}

	/**
	 * Manual-tracing counterpart of {@link #autoTraceHeadless(List, PointInImage)}: connects
	 * each consecutive pair of points with a straight segment via {@link ManualTracerThread}
	 * instead of searching for a path with {@link #createSearch(double, double, double, double,
	 * double, double)}. See {@link #manualTrace(SNTPoint, SNTPoint, PointInImage)}.
	 */
	private Path manualTraceHeadless(final List<SNTPoint> pointList, final PointInImage forkPoint) {
		// This is BDV/BVV's own headless entry point (manualTrace()'s only caller), so - same reasoning
		// as autoTraceHeadless()'s useStreamedSource=true branch above - always use
		// defaultCanvasPixelOffset() (stable across materialization), not the live, possibly crop-local
		// activeCanvasPixelOffset. The goal must also be checked against the full streamed source's own
		// dimensions, not plugin.getWidth()/getHeight()/getDepth() (the crop's own smaller canvas while
		// one is installed) - see the ManualTracerThread overload below
		final PointInCanvas offset = defaultCanvasPixelOffset();
		final RandomAccessibleInterval<?> src = getStreamedOrLoadedData();
		// NB: src can be genuinely 2-dimensional (a single-Z-slice source, with its size-1 Z axis
		// already dropped by getStreamedOrLoadedData()/getLoadedData()'s Views.dropSingletonDimensions()),
		// in which case dimension(2) would throw - default depth to 1 (matching plugin.getDepth()'s own
		// convention for a single-slice image) rather than treating it as "unknown" (0)
		final long boundsWidth = (src == null) ? 0 : src.dimension(0);
		final long boundsHeight = (src == null) ? 0 : src.dimension(1);
		final long boundsDepth = (src == null) ? 0 : (src.numDimensions() > 2 ? src.dimension(2) : 1);
		return runHeadlessTrace(pointList, forkPoint, (start, end) -> new ManualTracerThread(this,
				start.getX() / x_spacing + offset.x,
				start.getY() / y_spacing + offset.y,
				start.getZ() / z_spacing + offset.z,
				end.getX() / x_spacing + offset.x,
				end.getY() / y_spacing + offset.y,
				end.getZ() / z_spacing + offset.z,
				boundsWidth, boundsHeight, boundsDepth), null, null);
	}

	/**
	 * Shared skeleton for headless, point-to-point tracing. For each consecutive pair of
	 * points in {@code pointList}, builds a {@link SearchInterface} via {@code searchFactory},
	 * submits it to {@link #tracerThreadPool}, blocks until it completes, and stitches the
	 * per-segment {@link Path} results into a single path. Used by both {@link
	 * #autoTraceHeadless(List, PointInImage)} (A* search, or whichever {@link SearchInterface}
	 * is currently configured) and {@link #manualTraceHeadless(List, PointInImage)} (straight
	 * segments, ignoring {@link #isAstarEnabled()} entirely).
	 * <p>
	 * This blocks the calling thread on {@link Future#get()} for each segment: callers running
	 * on the EDT should only do so if {@code searchFactory} is guaranteed to be cheap (as
	 * {@link ManualTracerThread} is). A real search (A*, Tubular Geodesics, etc.) can be slow,
	 * especially against lazily-loaded data, and should be dispatched from a background thread.
	 *
	 * @param pointList the nodes to connect, start to end
	 * @param forkPoint optional fork point of the parent Path, or null
	 * @param searchFactory builds the per-segment search for a given start/end pair
	 * @param progressCallback registered on each per-segment search via {@link
	 *          SearchInterface#addProgressListener}, or null to skip progress reporting
	 * @param onSubmit called with each per-segment search's {@link Future} immediately after
	 *          submission (so a caller can cancel it later), or null
	 * @return the stitched path, or null if any segment failed to produce a result
	 */
	private <S extends Runnable & SearchInterface> Path runHeadlessTrace(final List<SNTPoint> pointList,
			final PointInImage forkPoint, final BiFunction<SNTPoint, SNTPoint, S> searchFactory,
			final SearchProgressCallback progressCallback, final Consumer<Future<?>> onSubmit)
	{
		if (pointList == null || pointList.isEmpty())
			throw new IllegalArgumentException("pointList cannot be null or empty");

		if (tracerThreadPool == null || tracerThreadPool.isShutdown()) {
			tracerThreadPool = Executors.newSingleThreadExecutor();
		}

		final Path fullPath = new Path(x_spacing, y_spacing, z_spacing, spacing_units);

		// Now keep appending nodes to temporary path
		for (int i = 0; i < pointList.size() - 1; i++) {
			// Append node and wait for search to be finished
			final SNTPoint start = pointList.get(i);
			final SNTPoint end = pointList.get(i + 1);
			Path pathResult = null;
			try {
				final S search = searchFactory.apply(start, end);
				if (progressCallback != null) search.addProgressListener(progressCallback);
				final Future<?> result = tracerThreadPool.submit(search);
				if (onSubmit != null) onSubmit.accept(result);
				result.get();
				pathResult = search.getResult();
			} catch (InterruptedException | ExecutionException | IllegalArgumentException
					| CancellationException e) {
				SNTUtils.error("Error during trace", e);
			}
			if (pathResult == null) {
				SNTUtils.log("Trace result was null.");
				return null;
			}
			fullPath.add(pathResult);
		}

		if (forkPoint != null) {
			fullPath.setBranchFrom(forkPoint.getPath(), forkPoint);
		}
		fullPath.setCTposition(channel, frame);
		applyWorldOriginOffsetIfAny(fullPath);
		// Stamp with the live activeCanvasPixelOffset so this path renders correctly if a caller adds it
		// to pathAndFillManager as-is (see finishedPath()'s equivalent stamp). Callers that instead merge
		// this into a larger, already-existing Path (e.g. AbstractBigViewer's tempPath, via Path#add(),
		// which does not copy canvasOffset) should stamp that outer Path themselves right before adding it
		fullPath.setCanvasOffset(activeCanvasPixelOffset);
		return fullPath;
	}

	protected synchronized void replaceCurrentPath(final Path path) {
		if (currentPath != null) {
			showCanvasWarning("An active temporary path already exists...");
			return;
		}
		lastStartPointSet = true;
		currentPathIsNew = false;
		selectPath(path, false);
		setPathUnfinished(true);
		setCurrentPath(path);
		confirmedSegmentSizes.clear();
		startNodeRadius = manualRadius;
		final PointInCanvas offset = path.getCanvasOffset();
		final PointInImage lastNode = path.lastNode();
		last_start_point_x = lastNode.x / x_spacing + offset.x;
		last_start_point_y = lastNode.y / y_spacing + offset.y;
		last_start_point_z = lastNode.z / z_spacing + offset.z;
		setTemporaryPath(null);
		changeUIState(SNTUI.PARTIAL_PATH);
		updateAllViewers();
	}

	protected synchronized void finishedPath() {

		if (currentPath == null) {
			// this can happen through repeated hotkey presses
			if (ui != null) showCanvasWarning("No temporary path to finish...");
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
			// last_start_point_x/y/z are pixel indices in the current active grid (see
			// confirmTemporary()); subtract only the crop-relative portion here, leaving
			// world - worldOriginOffset, which applyWorldOriginOffsetIfAny() below completes
			final PointInCanvas cropOffset = cropRelativeCanvasOffset();
			final PointInImage p = new PointInImage(
					(last_start_point_x - cropOffset.x) * x_spacing,
					(last_start_point_y - cropOffset.y) * y_spacing,
					(last_start_point_z - cropOffset.z) * z_spacing);
			p.onPath = currentPath;
			currentPath.addPointDouble(p.x, p.y, p.z);
			currentPath.setSWCType(Path.SWC_SOMA);
			if (manualRadius > 0)
				currentPath.setRadius(manualRadius, 0);
			// Branch point will be set when path is connected to parent
			cancelSearch(false);
		}
		else {
			removeSphere(startBallName);
		}

		removeSphere(targetBallName);
		if (manualRadius > 0 && currentPath.hasRadii()) {
			currentPath.sanitizeRadii(true); // interpolates everything in between
		}
		if (pathAndFillManager.getPathFromID(currentPath.getID()) == null) {
			// Interactive manual/A* tracing builds currentPath incrementally over many clicks (unlike autoTraceSync/
			// runHeadlessTrace's single-shot construction), so this is the point it is considered "finished": every
			// node it will ever have already exists, and it is about to reach pathAndFillManager for the 1st time
			applyWorldOriginOffsetIfAny(currentPath);
			// A freshly created Path's own canvasOffset defaults to (0,0,0) (see startPath()) and is never
			// touched while it is being built - only syncActivePathCanvasState() keeps it in sync, and that
			// only loops over paths already IN pathAndFillManager. Stamp it here, from the live
			// activeCanvasPixelOffset, right before this path reaches pathAndFillManager for the first time,
			// so it renders correctly on the classic canvas alongside every other currently-loaded path
			// (all sharing this same, current value) regardless of what canvasOffset was in effect when this
			// path was started - even if a re-materialization happened while it was still being built
			currentPath.setCanvasOffset(activeCanvasPixelOffset);
			pathAndFillManager.addPath(currentPath, true, false, false);
			// Hook 3: Run holistic plausibility check on completed path
			if (ui != null && ui.getPlausibilityMonitor().isEnabled()) {
				ui.getPlausibilityMonitor().onPathFinalized(currentPath);
			}
		}
		lastStartPointSet = false;
		if (getPrefs().getAutoSelectionOfFinishedPath()) selectPath(currentPath, false);
		setPathUnfinished(false);
		confirmedSegmentSizes.clear();
		setCurrentPath(null);

		// ... and change the state of the UI
		changeUIState(SNTUI.WAITING_TO_START_PATH);
		updateTracingViewers(true);
		if (getUI() != null && getUI().getRecorder(false) != null) {
			// Read the true-world end coordinate from the just-added path's own last node, not
			// last_start_point_x/y/z: that variable is a pre-applyWorldOriginOffsetIfAny() pixel
			// index in whatever grid was active when the path was last extended (see testPathTo()),
			// and would misreport this comment whenever getWorldOriginOffset() is non-zero
			final Path finishedPathRef = pathAndFillManager.getPath(pathAndFillManager.size() - 1);
			final PointInImage lastNode = finishedPathRef.lastNode();
			final String cmmnt = String.format("  (%3f,%.3f,%.3f)\nEnd of new path [%s]",
					lastNode.x, lastNode.y, lastNode.z, finishedPathRef.getName());
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
		if (currentSearchThread != null)
			return;
		if (rubberBandTracing && temporaryPath != null) {
			if (pathUnfinished && !join) {
				// User clicked to accept the rubber band preview: confirm it
				confirmTemporary(true);
				return;
			}
			temporaryPath = null; // discard preview so fork or other operation can proceed
		}
		if (temporaryPath != null)
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
				if (!rubberBandTracing) // in rubber band mode stay in PARTIAL_PATH for clean live preview
					changeUIState(SNTUI.SEARCHING);
			} catch (final Exception ex) {
				if (getUI() != null) {
					getUI().error(ex.getMessage());
					getUI().reset();
				}
				SNTUtils.error(ex.getMessage(), ex);
			} finally {
				if (!rubberBandTracing && getUI() != null && getUI().getRecorder(false) != null) {
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

		// p[] is a pane-local pixel index (0,0,0 at the current xy canvas's own top-left corner), which
		// only equals the true grid Path node coordinates are stored in when activeCanvasPixelOffset is zero
		// (no materialized crop/Display Canvas active, or one whose origin happens to sit at (0,0,0)).
		// Correcting here, once, fixes both new-node creation below and the join-point lookup nested
		// inside the 3-arg clickForTrace() overload (which compares against existing paths' true-world
		// node coordinates).
		final double world_x = (p[0] - activeCanvasPixelOffset.x) * x_spacing;
		final double world_y = (p[1] - activeCanvasPixelOffset.y) * y_spacing;
		final double world_z = (p[2] - activeCanvasPixelOffset.z) * z_spacing;

		if (SNTUtils.isDebugMode()) {
			SNTUtils.log("clickForTrace(pane): x_in_pane_precise=" + x_in_pane_precise + " y_in_pane_precise="
					+ y_in_pane_precise + " plane=" + plane + " >> p=(" + p[0] + "," + p[1] + "," + p[2]
					+ ") activeCanvasPixelOffset=(" + activeCanvasPixelOffset.x + "," + activeCanvasPixelOffset.y
					+ "," + activeCanvasPixelOffset.z + ") >> world=(" + world_x + "," + world_y + "," + world_z
					+ ")");
		}

		clickForTrace(world_x, world_y, world_z, join);
	}

	public void setFillThresholdFrom(final double world_x, final double world_y,
									 final double world_z)
	{
		double min_dist = Double.POSITIVE_INFINITY;
		for (FillerThread fillerThread : fillerSet) {
			// getDistanceAtPoint() looks up its own search's pixel-index grid directly (seeded from
			// source-path nodes via Path#getXUnscaled(), i.e. node/spacing + canvasOffset - the live,
			// crop-local activeCanvasPixelOffset grid), not world/spacing alone
			final double distance = fillerThread.getDistanceAtPoint(world_x / x_spacing + activeCanvasPixelOffset.x,
					world_y / y_spacing + activeCanvasPixelOffset.y, world_z / z_spacing + activeCanvasPixelOffset.z);
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

	public double getFillThreshold() {
		if (fillThresholdDistance == 0d) fillThresholdDistance = 0.03d;
		return fillThresholdDistance;
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
		currentPathIsNew = true;
		startNodeRadius = manualRadius; // -1 if not set, that's fine
		confirmedSegmentSizes.clear(); // just in case of abnormal prior state

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
			// Clear fork context: this is a root path, not a fork
			if (ui != null) ui.getPlausibilityMonitor().clearForkContext();
		}
		else {
			real_last_start_x = joinPoint.x;
			real_last_start_y = joinPoint.y;
			real_last_start_z = joinPoint.z;
			path.setBranchFrom(joinPoint.onPath, joinPoint);
			// Hook 1: Notify plausibility monitor of fork initiation
			if (ui != null && ui.getPlausibilityMonitor().isEnabled()) {
				final int branchIdx = path.getBranchPointIndex();
				ui.getPlausibilityMonitor().onForkInitiated(joinPoint.onPath, branchIdx);
			}
			ballColor = Color.GREEN;
		}
		// NB: the new Path's own canvasOffset field is irrelevant here (always (0,0,0) on a freshly
		// created Path), but activeCanvasPixelOffset is not: real_last_start_x/y/z is a true-world
		// coordinate (world_x/y/z, or an existing Path's own joinPoint.x/y/z), while last_start_point_x/y/z
		// must be crop-local pixel space, same convention as x_start/y_start/z_start in testPathTo() and
		// the "continue an existing path" assignments above (see e.g. confirmTemporary()). Missing this
		// term left the very first search of a new path seeded in the wrong frame on a materialized crop
		last_start_point_x = real_last_start_x / x_spacing + activeCanvasPixelOffset.x;
		last_start_point_y = real_last_start_y / y_spacing + activeCanvasPixelOffset.y;
		last_start_point_z = real_last_start_z / z_spacing + activeCanvasPixelOffset.z;
		if (SNTUtils.isDebugMode()) {
			SNTUtils.log("startPath: world_x/y/z=(" + world_x + "," + world_y + "," + world_z + ") joinPoint="
					+ joinPoint + " real_last_start=(" + real_last_start_x + "," + real_last_start_y + ","
					+ real_last_start_z + ") activeCanvasPixelOffset=(" + activeCanvasPixelOffset.x + ","
					+ activeCanvasPixelOffset.y + "," + activeCanvasPixelOffset.z + ") worldOriginOffset="
					+ java.util.Arrays.toString(getWorldOriginOffset()) + " >> last_start_point=("
					+ last_start_point_x + "," + last_start_point_y + "," + last_start_point_z + ")");
		}

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
			input.put("tree", TreeUtils.merge(trees));
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
		imp.setCalibration(getCalibration());
		imp.setDisplayRange(0, fillerSet.size());
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

	public synchronized void initPathsToFill(final Set<Path> fromPaths, final boolean splitFillerThreads) {
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
		if (splitFillerThreads) {
			// Create one FillerThread per path for unique labels
			for (final Path path : fromPaths) {
				final FillerThread filler = new FillerThread(
						data,
						getCalibration(),
						fillThresholdDistance,
						1000,
						costFunction);
				addThreadToDraw(filler);
				filler.addProgressListener(this);
				if (getUI() != null) filler.addProgressListener(ui.getFillManager());
				filler.setSourcePaths(Collections.singleton(path));  // One path per filler
				fillerSet.add(filler);
			}
		} else {
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
		}
		if (getUI() != null) ui.setFillListVisible(true);
		changeUIState(SNTUI.FILLING_PATHS);
	}

	protected <T extends RealType<T>> boolean invalidStatsError(final boolean isSecondary) {
		if (isSecondary && getSecondaryData() == null || !isSecondary && getLoadedData() == null) {
			error("This option requires valid image data to be loaded.");
			return true;
		}
		final boolean invalidStats = (isSecondary) ? getStatsSecondary().max == 0 : getStats().max == 0;
		final boolean compute = invalidStats && getUI() != null && new GuiUtils(getActiveWindow()).getConfirmation(
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

	/**
	 * The primary image data BDV/BVV's own crop-independent tracing should search (see the
	 * {@code useStreamedSource} parameter of {@link #createSearch(int, int, int, int, int, int,
	 * SearchSettingsSnapshot, boolean)} and {@link #manualTraceHeadless(List, PointInImage)}): the
	 * original, full streamed source cached by the first materialized crop this session ever built
	 * ({@link #streamedSourceData}), or, if none has been built yet, simply {@link #getLoadedData()}
	 * - equivalent in that case, since {@link #ctSlice3d} has never been swapped for anything else.
	 * Pairs with {@link #defaultCanvasPixelOffset()}: BDV/BVV's own rendering always shows the full,
	 * unaffected volume regardless of whatever crop is materialized on the classic canvas, so its
	 * tracing must search this data with that offset, not {@link #getLoadedData()} with the live,
	 * possibly crop-local {@link #activeCanvasPixelOffset}.
	 *
	 * @return the full, uncropped primary image data, or null if none is loaded
	 */
	@SuppressWarnings("unchecked")
	private <T extends RealType<T>> RandomAccessibleInterval<T> getStreamedOrLoadedData() {
		// NB: streamedSourceData is cached as a raw reference to ctSlice3d (see resolveVoxelBounds()),
		// without getLoadedData()'s own Views.dropSingletonDimensions(). Apply it here too, or a session
		// that has materialized at least one crop would search a differently-shaped RAI (e.g. for a
		// single-Z-slice source) than one that never has, or than the classic canvas's own getLoadedData()
		return (streamedSourceData != null) ? Views.dropSingletonDimensions(streamedSourceData) : getLoadedData();
	}

	/**
	 * Non-generic wrapper around {@link #getStreamedOrLoadedData()}, for callers outside this
	 * class that need BDV/BVV's own crop-independent view of the pixel data currently being traced -
	 * e.g. the Sigma preview palette (see {@code AbstractBigViewer#pickSigmaPointAction()}), which
	 * must show data around the point actually clicked in BDV/BVV, not clamped to whatever (possibly
	 * smaller) crop happens to be materialized on the classic canvas at the same time. Pair with
	 * {@link #getDefaultCanvasPixelOffset()} for converting a click into a pixel index into this data.
	 *
	 * @return the same data as {@link #getStreamedOrLoadedData()}
	 */
	public RandomAccessibleInterval<?> getBdvTracingData() {
		return getStreamedOrLoadedData();
	}

	/**
	 * The global image statistics a crop-independent (BDV/BVV) search should normalize its cost
	 * function against, paired with {@link #getStreamedOrLoadedData()}. {@link #stats} itself gets
	 * overwritten with a materialized crop's own (smaller) statistics every time {@code
	 * loadDatasetFromImagePlus()} runs against it (see {@link #installMaterializedCrop}), so a search
	 * over the full streamed source would otherwise normalize against the wrong (crop-sized)
	 * statistics while a crop happens to be materialized. Computed lazily, once, the first time it is
	 * needed - the full source's own pixel data never changes for the lifetime of the session, so
	 * there is nothing that could make a cached value here stale.
	 *
	 * @return the full, uncropped primary image's own global statistics
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private ImageStatistics getStreamedOrLoadedStats() {
		if (streamedSourceData == null) {
			// No crop has ever been materialized this session: getStreamedOrLoadedData() == getLoadedData(),
			// so the regular stats field (computed from that same data - see loadDatasetFromImagePlus())
			// is already correct; nothing to compute separately
			return stats;
		}
		if (!streamedStatsComputed) {
			// Raw type, matching how loadDatasetFromImagePlus() itself calls computeImgStats(ctSlice3d, ...):
			// computeImgStats() expects an Iterable<T>, which a directly-parameterized
			// RandomAccessibleInterval<T> does not satisfy, but a raw one is accepted unchecked
			final RandomAccessibleInterval raw = getStreamedOrLoadedData();
			computeImgStats(raw, streamedStats);
			streamedStatsComputed = true;
		}
		return streamedStats;
	}

	public ImgPlus<?> getLoadedDataAsImg(final boolean secondaryLayer) {
		final RandomAccessibleInterval<?> loadedData = (secondaryLayer) ? getSecondaryData() : getLoadedData();
		return (loadedData == null) ? null :
				ImgUtils.wrapWithSpacing(loadedData,
						new double[]{getPixelWidth(), getPixelHeight(), getPixelDepth()}, getSpacingUnits());
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
		if (!SNTUtils.fileAvailable(file)) {
			throw new IllegalArgumentException("File path of input data unknown");
		}
		// Big-data formats (N5, Zarr, BDV .xml/.h5, IMS, remote URLs) aren't openable via the classic
		// SCIFIO/Bio-Formats path openCachedDataImage() relies on, e.g.m  a BDV .xml companion of a
		// large .h5 dataset fails there with "Could not find H5 file location in XML" (Bio-Formats
		// trying, and failing, to parse it as its own HDF5-companion XML dialect). Try SpimDataUtils'
		// resolution first (the same one BigDataLoaderCmd's own volume fields use) and, on success,
		// route through the RAI-based loadSecondaryImage() overload below, which also sidesteps
		// openCachedDataImage()'s need for a classic tracing  canvas (xy) entirely.
		// Falls back to the conventional path for anything it can't resolve
		// (resolvePathToSource() itself ends in a conventional ImgPlus-open fallback too, but keeping
		// our own fallback here avoids any behavior change for formats that already worked before).
		// Resolution and dimension-checking are deliberately two separate steps here: only a failure to
		// resolve/parse the source at all should fall back to the conventional (RAM-unsafe-for-big-data)
		// path below. A dimension mismatch on an already-resolved big-data source must NOT fall back to it
		Object resolvedSource = null;
		try {
			resolvedSource = SpimDataUtils.resolvePathToSource(file.getAbsolutePath());
		} catch (final Exception e) {
			SNTUtils.log("Secondary layer: '" + file.getName() + "' could not be resolved as a big-data "
					+ "container (" + e.getMessage() + "); falling back to conventional image opening");
		}
		if (resolvedSource != null) {
			final RandomAccessibleInterval<?> data = extractSecondaryData(resolvedSource);
			if (data != null) {
				// computeStatistics=false: data here may be a lazily-backed (N5/Zarr/BDV) source, and eager
				// min/max/mean/stdDev via generic OpService calls is 3 separate full-volume passes -- real
				// disk I/O per chunk, done 3x over, with no caching between passes. That may work for the
				// classic ImagePlus but not for lazy data.
				//noinspection unchecked,rawtypes
				loadSecondaryImage((RandomAccessibleInterval) data, true, false);
				setSecondaryImage(isSecondaryDataAvailable() ? file : null);
				return;
			}
		}
		final ImagePlus imp = openCachedDataImage(file);
		loadSecondaryImage(imp, true);
		setSecondaryImage(isSecondaryDataAvailable() && isSecondaryImageFileLoaded() ? file : null);
	}

	/**
	 * Extracts a {@link RandomAccessibleInterval} for the currently active channel/frame from a
	 * resolved big-data source (see {@link SpimDataUtils#resolvePathToSource(String)}), or
	 * {@code null} if {@code source} isn't one of the recognized types (the caller, {@link
	 * #loadSecondaryImage(File)}, then falls back to conventional image opening).
	 * <p>
	 * If {@code source} <i>is</i> one of the recognized types but its XYZ dimensions don't match the
	 * data currently being traced (see {@link #sameXYZDimensionsAsTracingData}), this throws instead
	 * of returning {@code null}: unlike a genuinely unresolved source, this data was already
	 * successfully resolved as big data, so silently falling back to the conventional path would mean
	 * fully loading a potentially huge dataset into RAM via SCIFIO/Bio-Formats just to discover its
	 * dimensions are wrong. Failing fast here, with the same message {@link #openCachedDataImage(File)}
	 * gives for the same condition, avoids that.
	 * <p>
	 * For {@link AbstractSpimData} (BDV .xml/IMS) and {@link SpimDataUtils.N5Sources} (ambiguous
	 * N5/Zarr layouts), only the first setup/source and timepoint 0 are used. This is the same
	 * simplification {@code BigDataLoaderCmd#applyFallbackCalibration} already makes for the
	 * <i>primary</i> volume in this same situation. A genuinely multichannel secondary layer of
	 * either of these types would thus only expose its first channel.
	 *
	 * @throws IllegalArgumentException if the resolved source's dimensions don't match the data currently being traced
	 */
	private RandomAccessibleInterval<?> extractSecondaryData(final Object source) {
		final RandomAccessibleInterval<?> data;
        switch (source) {
            case ImgPlus<?> img ->
                    data = ImgUtils.getCtSlice((Dataset) img, Math.max(0, channel - 1), Math.max(0, frame - 1));
            case AbstractSpimData<?> spimData -> {
                final var setups = spimData.getSequenceDescription().getViewSetupsOrdered();
                if (setups.isEmpty()) return null;
                final var setup = setups.getFirst();
                try {
                    data = spimData.getSequenceDescription().getImgLoader()
                            .getSetupImgLoader(setup.getId()).getImage(0); // timepoint 0
                } catch (final Exception e) {
                    SNTUtils.log("Secondary layer: could not access pixel data from SpimData ImgLoader ("
                            + e.getMessage() + ")");
                    return null;
                }
            }
            case SpimDataUtils.N5Sources n5 when !n5.sources.isEmpty() ->
                    data = n5.sources.getFirst().getSpimSource().getSource(0, 0); // timepoint 0, full-resolution level 0
            case null, default -> {
                return null; // unresolved / unrecognized type
            }
        }
		if (!sameXYZDimensionsAsTracingData(data)) { // NB: throws rather than returning null
			final String msg = (isStreamMode())
					? "" : " If this is unexpected, check under 'Image>Properties...' that CZT axes are not swapped.";
			throw new IllegalArgumentException("Dimensions do not match those of the image being traced." + msg);
		}
		return data;
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
		// important! sets the channel/frame to be imported. Does nothing if image is not a hyperstack.
		// The 2ng arg Z position is irrelevant to ImgUtils#getCtSlice(ImagePlus) below, so any in-range
		// value works. In stream mode xy is null so we fallback to 1 rather than dereferencing xy.getSlice()
		imp.setPosition( channel, (xy != null) ? xy.getSlice() : 1, frame );
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
				// In Stream mode there is no classic canvas/MIP-overlay to give visual feedback that a
				// secondary layer was loaded (see SNTUI#secondaryDataPanel()'s load actions), so push it
				// into the tethered viewer as an additional source instead. displayData(true)/showSecondaryData()
				// on Bdv/Bvv will replace any previously-shown secondary layer.
				// NB: enableSecondaryLayerTracing() is reached from several off-EDT callers but showSecondaryData()
				// does BVV/BDV Swing/OpenGL setup that. Calling it directly from a worker thread is a silent EDT/GL
				// threading violation (no exception, just a hang), hence the explicit invokeLater() below
				if (isStreamMode() && getUI() != null) {
					final var viewer = getUI().getActiveBigViewer();
					if (viewer != null) {
						SwingUtilities.invokeLater(() -> {
							try {
								viewer.showSecondaryData();
							} catch (final Exception e) {
								SNTUtils.log("Could not display secondary layer in Stream viewer: " + e.getMessage());
							}
						});
					}
				}
			}
		} else {
			doSearchOnSecondaryData = false;
		}
		if (getUI() != null) {
			getUI().setSecondaryLayerTracingSelected(doSearchOnSecondaryData);
		}
	}

	public void flushSecondaryData() {
		if (secondaryData instanceof DiskCachedCellImg<?, ?> img) {
			SNTUtils.log("Shutting down IoSync...");
			img.shutdown();
		}
		if (secondaryData instanceof CachedCellImg<?, ?> img) {
			SNTUtils.log("Invalidating cache...");
			if (img.getCache() != null)
				img.getCache().invalidateAll();
		}
		if (secondaryDataCacheDir != null) {
			// NB: shutdown()/invalidateAll() above stop I/O and drop the in-memory cache map, but neither
			// removes the on-disk cache files -- without this, they would otherwise only be cleaned up by
			// the JVM-exit shutdown hook registered in Lazy (see Lazy#createImg(..., Path)), so every prior
			// "lazy" secondary image computed within the same session would keep occupying disk space
			try {
				org.apache.commons.io.FileUtils.deleteDirectory(secondaryDataCacheDir.toFile());
				SNTUtils.log("Deleted secondary image disk cache: " + secondaryDataCacheDir);
			} catch (final IOException e) {
				SNTUtils.log("Could not delete secondary image disk cache (will be removed at JVM exit): "
						+ e.getMessage());
			} finally {
				secondaryDataCacheDir = null;
			}
		}
		secondaryData = null;
		setSecondaryImage(null);
		if (getUI() != null) {
			getUI().disableSecondaryLayerComponents();
			// In Stream mode the secondary layer is also displayed as an extra source in the tethered
			// Bdv/Bvv viewer (see enableSecondaryLayerTracing()'s showSecondaryData() hook); flushing it
			// here should remove that source toot. Same invokeLater() rationale as that hook:
			// hideSecondaryData() touches Swing/OpenGL state and may be reached from off-EDT callers.
			if (isStreamMode()) {
				final var viewer = getUI().getActiveBigViewer();
				if (viewer != null) {
					SwingUtilities.invokeLater(() -> {
						try {
							viewer.hideSecondaryData();
						} catch (final Exception e) {
							SNTUtils.log("Could not remove secondary layer from Stream viewer: " + e.getMessage());
						}
					});
				}
			}
		}
	}

	private ImagePlus openCachedDataImage(final File file) throws IOException {
		if (!accessToValidImageData()) throw new IllegalArgumentException(
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
		if (!sameXYZDimensionsAsTracingData(imp)) {
			// We are imposing only XYZ dimensions to e.g., allow for loading of single-channel
			// p-maps. Hopefully, being lax about CT dimensions won't cause issues downstream
			final String msg = (isStreamMode())
					? "" : " If this is unexpected, check under 'Image>Properties...' that CZT axes are not swapped.";
			throw new IllegalArgumentException("Dimensions do not match those of the image being traced." + msg);
		}
		return imp;
	}

	/**
	 * Checks if {@code imp} shares the same XYZ voxel dimensions as the image currently being traced. Compares against
	 * the classic tracing {@link ImagePlus} ({@link #xy}) when one exists; in Stream mode compares against the streamed
	 * primary volume's own dimensions ({@link #getLoadedData()}) instead. Used to validate a candidate secondary layer
	 * before loading it.
	 *
	 * @param imp the candidate image
	 * @return true if dimensions match; false if they don't, or if no tracing data is loaded to compare against
	 */
	public boolean sameXYZDimensionsAsTracingData(final ImagePlus imp) {
		if (xy != null) {
			return ImpUtils.sameXYZDimensions(imp, xy);
		}
		final RandomAccessibleInterval<?> loadedData = getLoadedData();
		if (loadedData == null) return false;
		final long z = (loadedData.numDimensions() > 2) ? loadedData.dimension(2) : 1;
		return loadedData.dimension(0) == imp.getWidth() && loadedData.dimension(1) == imp.getHeight()
				&& z == imp.getNSlices();
	}

	/**
	 * RAI-based overload of {@link #sameXYZDimensionsAsTracingData(ImagePlus)}, for candidate
	 * sources that never go through an {@link ImagePlus} at all (e.g. a big-data secondary layer
	 * resolved via {@link SpimDataUtils#resolvePathToSource(String)}). Compares XYZ dimensions
	 * directly against {@link #getLoadedData()}, so unlike the {@link ImagePlus} overload this
	 * works the same regardless of whether {@link #xy} exists.
	 *
	 * @param data the candidate image data
	 * @return true if dimensions match; false if they don't, or if no tracing data is loaded to compare against
	 */
	public boolean sameXYZDimensionsAsTracingData(final RandomAccessibleInterval<?> data) {
		final RandomAccessibleInterval<?> loadedData = getLoadedData();
		if (loadedData == null || data == null) return false;
		final long dataZ = (data.numDimensions() > 2) ? data.dimension(2) : 1;
		final long refZ = (loadedData.numDimensions() > 2) ? loadedData.dimension(2) : 1;
		return loadedData.dimension(0) == data.dimension(0) && loadedData.dimension(1) == data.dimension(1)
				&& refZ == dataZ;
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

	/**
	 * @return whether only paths matching the current channel/frame position are being displayed
	 *         (classic canvas), or, for Bvv/Bdv, only paths matching the current timepoint
	 */
	public boolean isShowOnlyActiveCTposPaths() {
		return showOnlyActiveCTposPaths;
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
	 *         not available. If the view is XY_PLANE, and the image has been closed,
	 *         cached pixel data is returned as per {@link #getLoadedDataAsImp()}
	 */
	public ImagePlus getImagePlus(final int pane) {
		ImagePlus imp = null;
		switch (pane) {
			case XY_PLANE:
				if (xy != null && isDummy()) {
					return null;
				}
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
		updateTracingViewers(includeLegacy3Dviewer, false);
	}

	/**
	 * @param includeLegacy3Dviewer whether to also refresh the legacy 3D viewer
	 * @param selectionOnly if true, this update is known to be a pure Path Manager selection change
	 *                      (no paths added/removed/edited): BVV is refreshed via the cheaper
	 *                      {@code Bvv#updateSelection()} (patches color/thickness in place) instead
	 *                      of a full {@code Bvv#syncPathManagerList()} rebuild.
	 */
	protected void updateTracingViewers(final boolean includeLegacy3Dviewer, final boolean selectionOnly) {
		repaintAllPanes();
		if (getUI() != null && getUI().bvvSNT != null) {
			if (selectionOnly) {
				new Thread(() -> getUI().bvvSNT.updateSelection()).start();
			} else {
				new Thread(() -> getUI().bvvSNT.syncPathManagerList()).start();
			}
		}
		if (getUI() != null && getUI().bdvSNT != null) {
			// Bdv has no updateSelection()-equivalent yet (unlike Bvv): always do a full
			// rebuild, even for selectionOnly changes. Hopefully this is cheap enough
			new Thread(() -> getUI().bdvSNT.syncPathManagerList()).start();
		}
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
		SNTPrefs.setSelectedPathColor(newColor);
		selectedColor3f = Utils.toColor3f(SNTPrefs.selectedPathColor());
		if (getUI() != null && getUI().bvvSNT != null) {
			getUI().bvvSNT.getRenderingOptions().selectedColor = SNTPrefs.selectedPathColor();
		}
		updateTracingViewers(true);
	}

	protected void setDeselectedColor(final Color newColor) {
		SNTPrefs.setDeselectedPathColor(newColor);
		deselectedColor3f = Utils.toColor3f(SNTPrefs.deselectedPathColor());
		if (getUI() != null && getUI().recViewer != null) {
			getUI().recViewer.setDefaultColor(new ColorRGB(SNTPrefs.deselectedPathColor().getRed(),
					SNTPrefs.deselectedPathColor().getGreen(), SNTPrefs.deselectedPathColor().getBlue()));
			if (pathAndFillManager.size() > 0) getUI().recViewer.syncPathManagerList();
		}
		if (getUI() != null && getUI().bvvSNT != null) {
			getUI().bvvSNT.getRenderingOptions().fallbackColor = SNTPrefs.deselectedPathColor();
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
		throw new IllegalArgumentException("getSelectedPaths was called when PathManagerUI was null");
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
														 final double y_in_pane, final double[] point) {

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
		final boolean useSecondary = isTracingOnSecondaryImageActive();

		@SuppressWarnings("unchecked")
		final RandomAccess <? extends RealType<?>> access = ((useSecondary) ? secondaryData : ctSlice3d).randomAccess();
		final ImageStatistics accessStats = (useSecondary) ? statsSecondary : stats;
		final ArrayList<int[]> pointsAtMaximum = new ArrayList<>();
		double currentMaximum = accessStats.min;
		for (int x = startx; x < stopx; ++x) {
			for (int y = starty; y < stopy; ++y) {
				for (int z = startz; z < stopz; ++z) {
					double v = access.setPositionAndGet(x, y, z).getRealDouble();
					if (v == accessStats.min) {
						continue;
					} else if (v > currentMaximum) {
						pointsAtMaximum.add(new int[] { x, y, z });
						currentMaximum = v;
					} else if (v == currentMaximum) {
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
		final boolean useSecondary = isTracingOnSecondaryImageActive();

		@SuppressWarnings("unchecked")
		final RandomAccess <? extends RealType<?>> access = ((useSecondary) ? secondaryData : ctSlice3d).randomAccess();
		final ImageStatistics accessStats = (useSecondary) ? statsSecondary : stats;
		final ArrayList<int[]> pointsAtMaximum = new ArrayList<>();
		double currentMaximum = accessStats.min;
		for (int[] ints : pointsToConsider) {
			double v = access.setPositionAndGet(ints[0], ints[1], ints[2]).getRealDouble();
			if (v == accessStats.min) {
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
			showCanvasWarning("No maxima at " + x_in_pane + ", " + y_in_pane);
			return;
		}
		final int[] p = pointsAtMaximum.get(pointsAtMaximum.size() / 2);
		SNTUtils.log(" Detected: x=" + p[0] + ", y=" + p[1] + ", z=" + p[2] + ", value=" + stats.max);
		setZPositionAllPanes(p[0], p[1], p[2]);
		if (!tracingHalted) { // click only if tracing
			// p[] indexes directly into ctSlice3d/secondaryData (see access above) - a crop-local
			// pixel index, same convention as mouseMovedTo()'s pane coordinates. Subtract
			// activeCanvasPixelOffset before scaling to true world, or the 4-arg clickForTrace()
			// (which expects true world, see its own 3-arg pane overload) re-adds it on top
			clickForTrace((p[0] - activeCanvasPixelOffset.x) * x_spacing,
					(p[1] - activeCanvasPixelOffset.y) * y_spacing,
					(p[2] - activeCanvasPixelOffset.z) * z_spacing, join);
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

	private static final int CANVAS_MSG_DURATION = 3000;
	private String activeCanvasMessage;
	private javax.swing.Timer canvasMessageTimer;

	/**
	 * Displays a timed message on the NW corner of all canvas panes.
	 * Unlike {@link #discreteMsg(String)}, which uses a transient popup near
	 * the lower-left that is easy to miss when zoomed in, this renders the
	 * label directly into the canvas paint cycle so it is always visible.
	 * <p>
	 * Successive calls reset the timer. The label is auto-cleared after the
	 * given duration. Callers that need to clear earlier can pass {@code null}.
	 *
	 * @param msg        the message text, or {@code null} to clear immediately
	 * @param background the background color for the banner
	 * @param durationMs how long the label stays visible (milliseconds)
	 */
	private void showCanvasMessage(final String msg, final Color background, final int durationMs) {
		if (canvasMessageTimer != null) canvasMessageTimer.stop();
		if (msg == null) {
			clearCanvasMessage();
			return;
		}
		// If the canvas is not available (e.g., image closed), fall back to
		// discreteMsg which anchors a popup to whatever window is active, or
		// logs to the console if no window is available either
		if (getXYCanvas() == null) {
			discreteMsg(msg);
			return;
		}
		activeCanvasMessage = msg;
		setCanvasLabelBackgroundAllPanes(background);
		setCanvasLabelAllPanes(activeCanvasMessage);
		canvasMessageTimer = new javax.swing.Timer(durationMs, e -> clearCanvasMessage());
		canvasMessageTimer.setRepeats(false);
		canvasMessageTimer.start();
	}

	/**
	 * Displays a timed warning (amber background) on the canvas banner.
	 *
	 * @param msg        the warning text, or {@code null} to clear immediately
	 * @param durationMs how long the label stays visible (milliseconds)
	 */
	protected void showCanvasWarning(final String msg, final int durationMs) {
		showCanvasMessage(msg, GuiUtils.warningColor(), durationMs);
	}

	/** @see #showCanvasWarning(String, int) */
	protected void showCanvasWarning(final String msg) {
		showCanvasWarning(msg, CANVAS_MSG_DURATION);
	}

	/**
	 * Displays a timed informational message (blue background) on the canvas
	 * banner. Use for confirmations, status updates, and non-critical feedback.
	 *
	 * @param msg        the info text, or {@code null} to clear immediately
	 * @param durationMs how long the label stays visible (milliseconds)
	 */
	protected void showCanvasInfo(final String msg, final int durationMs) {
		final Color base = GuiUtils.linkColor();
		showCanvasMessage(msg, new Color(base.getRed(), base.getGreen(), base.getBlue(), 100), durationMs);
	}

	/** @see #showCanvasInfo(String, int) */
	protected void showCanvasInfo(final String msg) {
		showCanvasInfo(msg, CANVAS_MSG_DURATION);
	}

	private void clearCanvasMessage() {
		if (canvasMessageTimer != null) {
			canvasMessageTimer.stop();
			canvasMessageTimer = null;
		}
		if (activeCanvasMessage == null) return;
		// Only clear if the label is still ours (not overwritten by edit/pause mode)
		final InteractiveTracerCanvas canvas = getXYCanvas();
		if (canvas == null || activeCanvasMessage.equals(canvas.getCanvasLabel())) {
			// Restore the mode label if still in a labeled mode
			setCanvasLabelAllPanes(getModeLabel());
		}
		setCanvasLabelBackgroundAllPanes(null);
		activeCanvasMessage = null;
	}

	private String getModeLabel() {
		if (isEditModeEnabled()) {
			final InteractiveTracerCanvas c = getXYCanvas();
			if (c != null && c.isPaintMode())
				return InteractiveTracerCanvas.PAINT_MODE_LABEL + " r=" + c.getPaintBrushRadius();
			return InteractiveTracerCanvas.EDIT_MODE_LABEL;
		}
		return null;
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

	public boolean isTracingOnSecondaryImageActive() {
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
		final boolean bvvMode = isStreamMode();
		if (!single_pane) {
			final ImagePlus[] impPanes = { xz, zy };
			for (final ImagePlus imp : impPanes) {
				if (imp == null)
					continue;
				if (bvvMode) {
					imp.close();
					continue;
				}
				final Overlay overlay = imp.getOverlay();
				if (!imp.changes && (overlay == null || imp.getOverlay().size() == 0)
						&& !(imp.getRoi() != null && (imp.getRoi() instanceof PointRoi)))
					imp.close();
				else
					rebuildWindow(imp);
			}
		}
		if (bvvMode && xy != null) {
			xy.close();
		} else {
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
		// Clear all image data references to prevent stale state detection
		xy = null;
		xz = null;
		zy = null;
		ctSlice3d = null;
		flushSecondaryData();
		nullifyCanvases(false);
	}

	private void rebuildWindow(final ImagePlus imp) {
		// hiding the image will force the rebuild of its ImageWindow next time show() is
		// called. We need to remove any PointRoi to bypass the "Save changes?" dialog.
		// If spine/varicosity counts exist, set the images has changed to avoid data loss
		try {
			final Roi roi = imp.getRoi();
			final boolean existingChanges = imp.changes;
			imp.changes = false;
			imp.deleteRoi();
			imp.hide();
			imp.setRoi(roi);
			imp.show();
			imp.changes = existingChanges || roi instanceof PointRoi;
		} catch (final Exception ignored) {
			// we use this method to return the image back to IJ when the program is closed
			// but the image was left open. ImagePlus may be partially disposed during
			// shutdown (e.g., CompositeImage with null LUTs). Not much we can do here other
			// than let the exit proceed.
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

	/**
	 * Returns whether this session is running in stream mode ("SNT Stream"), i.e., without access to
	 * a full in-core materialized image but rather a lazily-loaded BDV/BVV-backed source (typically
	 * an OME-Zarr/N5 dataset; see {@link sc.fiji.snt.gui.cmds.BigDataLoaderCmd}).
	 *
	 * @return true if in stream mode
	 */
	public boolean isStreamMode() {
		return bigDataMode;
	}

	/**
	 * Sets the stream-mode flag returned by {@link #isStreamMode()}. Package-private: the only
	 * legitimate callers are {@link #startUIOnEDT(boolean)} (before constructing {@link SNTUI}) and
	 * {@link SNTUI}'s own constructor (covering direct {@code new SNTUI(plugin, bigDataMode)}
	 * construction that bypasses {@link #startUI(boolean)}).
	 *
	 * @param bigDataMode the new stream-mode flag
	 */
	void setBigDataMode(final boolean bigDataMode) {
		this.bigDataMode = bigDataMode;
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

	/**
	 * Sets image dimensions and calibration without providing actual pixel data. Useful when the
	 * source image is not directly accessible as random-access data (e.g., a BigDataViewer
	 * {@code AbstractSpimData}/IMS source, or an ambiguous N5/Zarr layout) but its metadata (size,
	 * voxel spacing) is still known. This allows features that only need bounds/scale (e.g., manual
	 * tracing, bounds/distance checks) to work correctly with the image's real dimensions, even
	 * though {@link #accessToValidImageData()} keeps reporting no data is available (this does NOT
	 * set {@code ctSlice3d}, so A* search remains unavailable).
	 *
	 * @param width    width in pixels (ignored if &le;0)
	 * @param height   height in pixels (ignored if &le;0)
	 * @param depth    depth (number of slices) in pixels (ignored if &le;0)
	 * @param xSpacing pixel width (ignored if &le;0)
	 * @param ySpacing pixel height (ignored if &le;0)
	 * @param zSpacing pixel depth (ignored if &le;0)
	 * @param units    spatial calibration units (ignored if null/blank)
	 */
	public void setImageMetadata(final int width, final int height, final int depth, final double xSpacing,
			final double ySpacing, final double zSpacing, final String units) {
		if (SNTUtils.isDebugMode()) {
			final StackTraceElement caller = Thread.currentThread().getStackTrace()[2];
			SNTUtils.log("setImageMetadata called from " + caller.getClassName() + "." + caller.getMethodName() + "("
					+ caller.getLineNumber() + "): width=" + width + " height=" + height + " depth=" + depth
					+ " xSpacing=" + xSpacing + " ySpacing=" + ySpacing + " zSpacing=" + zSpacing + " units=" + units
					+ " >> spacingKnownFromSource (before this call): " + spacingKnownFromSource
					+ " ; current x/y/z_spacing (before this call): " + this.x_spacing + "/" + this.y_spacing + "/"
					+ this.z_spacing);
		}
		// While a crop is materialized, width/height/depth describe the crop's own (smaller) canvas, matching its
		// actual ctSlice3d - not the full source BDV/BVV's own syncChannelFromActiveSource() reports here on every
		// channel switch. Overwriting them would desync plugin.getWidth()/getHeight()/getDepth() from the classic
		// canvas's real data without touching ctSlice3d itself (see setImageData())
		if (!isMaterializedCrop()) {
			if (width > 0) this.width = width;
			if (height > 0) this.height = height;
			if (depth > 0) {
				this.depth = depth;
				this.singleSlice = depth == 1;
			}
		}
		if (xSpacing > 0) this.x_spacing = xSpacing;
		if (ySpacing > 0) this.y_spacing = ySpacing;
		if (zSpacing > 0) this.z_spacing = zSpacing;
		// Only a fully-known triple counts: every current caller (BigDataLoaderCmd, Bdv, Bvv) reports
		// all three axes from the same VoxelDimensions object, so a partial report is not expected in
		// practice; treating it as "still unverified" is the conservative choice if it ever happens.
		if (xSpacing > 0 && ySpacing > 0 && zSpacing > 0) spacingKnownFromSource = true;
		if (units != null && !units.isBlank()) this.spacing_units = SNTUtils.getSanitizedUnit(units);
		// Propagate the (possibly corrected) dimensions/spacing to pathAndFillManager's own copy/BoundingBox,
		// otherwise anything reading spacing through that route stays stuck at whatever was set at construction time
		pathAndFillManager.syncSpatialSettingsWithPlugin();
	}

	/**
	 * Sets a rigid world-space origin offset (in calibrated units) to be applied on top of the
	 * usual {@code voxelIndex * spacing} coordinate mapping. Not needed for a normally-loaded
	 * {@link ImgPlus} (its calibration/origin is assumed anchored at world (0,0,0)), but required
	 * when pixel data was wired in via {@link #setImageData}/{@link #setImageMetadata} from a
	 * source whose own coordinate frame is <em>not</em> anchored at (0,0,0) - e.g. a
	 * BigDataViewer/N5 {@code Source} whose {@code sourceTransform} carries a translation (see
	 * {@code BigDataLoaderCmd#applyFallbackCalibration}).
	 * <p>
	 * This offset is not applied automatically to coordinates read elsewhere in this class (e.g.
	 * manual tracing, A* search); callers that produce {@link Tree}/{@link Path} results from such
	 * a source are responsible for applying it themselves, e.g. via {@link Tree#translate}.
	 * <p>
	 * <b>Not the same thing as {@code ij.measure.Calibration#xOrigin/yOrigin/zOrigin}</b> (nor a
	 * drop-in replacement for it), despite the similar name/purpose. The two use different
	 * conventions and are not numerically interchangeable:
	 * <ul>
	 * <li>This offset is applied in <b>world</b> space, after scaling:
	 * {@code world = voxelIndex * spacing + offset}.</li>
	 * <li>{@code Calibration}'s origin is applied in <b>pixel</b> space, before scaling
	 * (see {@code Calibration#getRawX/Y/Z}, and {@code ShollPoint#rawZ} for the equivalent
	 * hand-written formula): {@code world = (voxelIndex - origin) * spacing}, i.e.
	 * {@code voxelIndex = world / spacing + origin}.</li>
	 * </ul>
	 * Converting between the two requires the spacing too ({@code offset = -origin * spacing}); they
	 * are not the same number and must not be assigned to/read from each other directly.
	 * {@link #getCalibration()} always returns spacing-only calibration objects with
	 * {@code xOrigin/yOrigin/zOrigin} left at their default of 0, regardless of this offset; callers
	 * needing pixel&lt;-&gt;world conversions that account for this offset must use
	 * {@link #getWorldOriginOffset()} directly (see {@code BookmarkManager#pixelToWorld}/
	 * {@code #worldToPixel} for a worked example), not {@code getCalibration()} alone.
	 *
	 * @param xOffset x offset, in calibrated units
	 * @param yOffset y offset, in calibrated units
	 * @param zOffset z offset, in calibrated units
	 * @see #getWorldOriginOffset()
	 */
	public void setWorldOriginOffset(final double xOffset, final double yOffset, final double zOffset) {
		this.originOffsetX = xOffset;
		this.originOffsetY = yOffset;
		this.originOffsetZ = zOffset;
		// BigDataLoaderCmd calls this (via applyWorldOriginOffset()) AFTER snt.initialize(null) has already
		// run assembleDisplayCanvases() with pathAndFillManager empty, which stamped activeCanvasPixelOffset
		// from a still-zero offset (this method hadn't been called yet). Refresh it now that the real offset
		// is known, unless a materialized crop is active - that owns activeCanvasPixelOffset itself (see
		// installMaterializedCrop()) and setWorldOriginOffset() is not expected to run while one is open anyway
		if (!isMaterializedCrop()) {
			syncActivePathCanvasState(defaultCanvasPixelOffset(), null);
		}
	}

	/**
	 * @return the world-space origin offset (calibrated units) previously set via
	 *         {@link #setWorldOriginOffset}, as {@code {xOffset, yOffset, zOffset}}. All-zero
	 *         (the default) unless explicitly set. See {@link #setWorldOriginOffset} for why this is
	 *         distinct from {@code ij.measure.Calibration}'s own {@code xOrigin/yOrigin/zOrigin} fields.
	 */
	public double[] getWorldOriginOffset() {
		return new double[] { originOffsetX, originOffsetY, originOffsetZ };
	}

	/**
	 * Directly sets the image data backing A* search, without requiring an {@link ImgPlus} wrapper.
	 * Useful when pixel data comes from a source whose {@link RandomAccessibleInterval} is already
	 * resolved elsewhere (e.g., a BigDataViewer/SpimData {@code ImgLoader}, or a BDV/BVV
	 * {@code Source}), and building/discarding a full {@code ImgPlus} just to satisfy
	 * {@link #SNT(ImgPlus)} would be wasteful, or would not preserve lazy/chunked access the way the
	 * original object does.
	 * <p>
	 * Callers remain responsible for also calling {@link #setImageMetadata} (or equivalent) with matching
	 * dimensions/calibration; this method does not attempt to infer them from {@code data}.
	 * <p>
	 * {@code data}'s pixel type must be a {@link RealType}; other types (e.g. {@code ARGBType}) will
	 * throw a {@code ClassCastException} later, e.g., the first time A* search accesses a pixel.
	 *
	 * @param data the (possibly lazily-backed) image data, in the same pixel/voxel grid implied by
	 *             the dimensions/calibration set via {@link #setImageMetadata}
	 */
	public void setImageData(final RandomAccessibleInterval<?> data) {
		if (isMaterializedCrop()) {
			// A materialized crop owns ctSlice3d/activeCanvasPixelOffset for the classic 2D canvas
			// (see installMaterializedCrop()). BDV/BVV's own crop-independent tracing calls this
			// method every time the active source/channel changes (see AbstractTracer#
			// syncChannelFromActiveSource()) - it must not clobber the crop's own pixel data.
			// Route it to streamedSourceData instead, which getStreamedOrLoadedData() already
			// prefers over ctSlice3d/getLoadedData() for crop-independent (useStreamedSource) search
			streamedSourceData = data;
		} else {
			this.ctSlice3d = data;
			// No crop is active, so any previously-cached streamedSourceData (from a since-closed
			// crop) is stale relative to this new data too - drop it; getStreamedOrLoadedData()
			// falls back to getLoadedData() (i.e. this ctSlice3d) until a future crop repopulates it
			streamedSourceData = null;
		}
		// Either way, this is new pixel data - any cached streamedStats (see getStreamedOrLoadedStats())
		// was computed against whatever streamedSourceData held before and is now stale
		streamedStatsComputed = false;
	}

	/**
	 * Sets the channel/frame (1-based, IJ hyperstack convention) to be associated with subsequently
	 * traced paths (via {@link Path#setCTposition(int, int)}), and, for {@link #manualTrace}, with
	 * the pixel data A* search reads (see {@link #setImageData}).
	 * <p>
	 * Useful alongside {@link #setImageData}/{@link #setImageMetadata} when pixel data comes from a
	 * source with no single, already-loaded multichannel/multi-timepoint array to index into (e.g.
	 * each channel of a BigDataViewer/SpimData or N5/Zarr source is its own separate object): callers
	 * are responsible for keeping the three in sync when the active channel/timepoint changes.
	 *
	 * @param channel the channel (1-based index; coerced to 1 if &le;0)
	 * @param frame   the frame/timepoint (1-based index; coerced to 1 if &le;0)
	 */
	public void setChannelAndFrame(final int channel, final int frame) {
		this.channel = Math.max(1, channel);
		this.frame = Math.max(1, frame);
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
	 * Channel/frame ({@code {channel, frame}}, 1-based) that a background batch re-trace (e.g.
	 * {@code PathManagerUI}'s "Re-trace with A*...", see {@code AStarRefiner}) is currently running
	 * against, or {@code null} if no such batch is active.
	 * <p>
	 * Batch workers read this instance's shared image-data fields ({@link #ctSlice3d}, {@link
	 * #channel}/{@link #frame}, {@link #stats}) concurrently via {@link #createSearch}. Interactive
	 * callers that would otherwise mutate those fields mid-batch (e.g. BVV starting a new trace on a
	 * different active source) should check this first and refuse to do so while it is non-null and
	 * differs from the channel/frame they'd switch to.
	 */
	private volatile int[] batchRetraceChannelFrame;

	/**
	 * Sets or clears the channel/frame lock described in {@link #getBatchRetraceChannelFrame()}.
	 *
	 * @param channel 1-based channel, or null to clear the lock
	 * @param frame   1-based frame, or null to clear the lock
	 */
	public void setBatchRetraceChannelFrame(final Integer channel, final Integer frame) {
		this.batchRetraceChannelFrame = (channel == null || frame == null) ? null : new int[]{channel, frame};
	}

	/**
	 * @return {@code {channel, frame}} (1-based) the active batch re-trace is running against, or
	 *         {@code null} if none is active. See {@link #setBatchRetraceChannelFrame}.
	 */
	public int[] getBatchRetraceChannelFrame() {
		return batchRetraceChannelFrame;
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
			if (p == null || p.isFittedVersionOfAnotherPath()) continue;
			final Path drawPath = (p.getUseFitted() && p.getFitted() != null) ? p.getFitted() : p;
			drawPath.drawPathAsPoints(g, canvas, this);
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
}
