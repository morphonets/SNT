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

package sc.fiji.snt.viewer;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.DisplayMode;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.border.Border;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;

import org.jzy3d.bridge.awt.FrameAWT;
import org.jzy3d.chart.AWTNativeChart;
import org.jzy3d.chart.Chart;
import org.jzy3d.chart.Settings;
import org.jzy3d.chart.controllers.ControllerType;
import org.jzy3d.chart.controllers.camera.AbstractCameraController;
import org.jzy3d.chart.controllers.mouse.AWTMouseUtilities;
import org.jzy3d.chart.controllers.mouse.camera.AWTCameraMouseController;
import org.jzy3d.chart.controllers.thread.camera.CameraThreadController;
import org.jzy3d.chart.factories.AWTChartFactory;
import org.jzy3d.chart.factories.ChartFactory;
import org.jzy3d.chart.factories.EmulGLChartFactory;
import org.jzy3d.chart.factories.IChartFactory;
import org.jzy3d.chart.factories.IFrame;
import org.jzy3d.chart.factories.OffscreenChartFactory;
import org.jzy3d.colors.Color;
import org.jzy3d.colors.ColorMapper;
import org.jzy3d.colors.ISingleColorable;
import org.jzy3d.debugGL.tracers.DebugGLChart3d;
import org.jzy3d.events.ViewPointChangedEvent;
import org.jzy3d.maths.BoundingBox3d;
import org.jzy3d.maths.Coord2d;
import org.jzy3d.maths.Coord3d;
import org.jzy3d.maths.Rectangle;
import org.jzy3d.plot2d.primitive.AWTColorbarImageGenerator;
import org.jzy3d.plot3d.primitives.Composite;
import org.jzy3d.plot3d.primitives.Drawable;
import org.jzy3d.plot3d.primitives.LineStrip;
import org.jzy3d.plot3d.primitives.Point;
import org.jzy3d.plot3d.primitives.Shape;
import org.jzy3d.plot3d.primitives.Sphere;
import org.jzy3d.plot3d.primitives.Tube;
import org.jzy3d.plot3d.primitives.Wireframeable;
import org.jzy3d.plot3d.primitives.axis.layout.fonts.HiDPITwoFontSizesPolicy;
import org.jzy3d.plot3d.primitives.axis.layout.providers.ITickProvider;
import org.jzy3d.plot3d.primitives.axis.layout.providers.RegularTickProvider;
import org.jzy3d.plot3d.primitives.axis.layout.providers.SmartTickProvider;
import org.jzy3d.plot3d.primitives.axis.layout.renderers.FixedDecimalTickRenderer;
import org.jzy3d.plot3d.primitives.axis.layout.renderers.ITickRenderer;
import org.jzy3d.plot3d.primitives.axis.layout.renderers.ScientificNotationTickRenderer;
import org.jzy3d.plot3d.primitives.enlightables.AbstractEnlightable;
import org.jzy3d.plot3d.primitives.vbo.drawable.DrawableVBO;
import org.jzy3d.plot3d.rendering.canvas.ICanvas;
import org.jzy3d.plot3d.rendering.canvas.INativeCanvas;
import org.jzy3d.plot3d.rendering.canvas.OffscreenCanvas;
import org.jzy3d.plot3d.rendering.canvas.Quality;
import org.jzy3d.plot3d.rendering.legends.colorbars.AWTColorbarLegend;
import org.jzy3d.plot3d.rendering.lights.Light;
import org.jzy3d.plot3d.rendering.lights.LightSet;
import org.jzy3d.plot3d.rendering.scene.Scene;
import org.jzy3d.plot3d.rendering.view.AWTRenderer3d;
import org.jzy3d.plot3d.rendering.view.AWTView;
import org.jzy3d.plot3d.rendering.view.HiDPI;
import org.jzy3d.plot3d.rendering.view.Renderer3d;
import org.jzy3d.plot3d.rendering.view.View;
import org.jzy3d.plot3d.rendering.view.ViewportMode;
import org.jzy3d.plot3d.rendering.view.annotation.CameraEyeOverlayAnnotation;
import org.jzy3d.plot3d.rendering.view.modes.CameraMode;
import org.jzy3d.plot3d.rendering.view.modes.ViewBoundMode;
import org.jzy3d.plot3d.rendering.view.modes.ViewPositionMode;
import org.jzy3d.plot3d.transform.Transform;
import org.jzy3d.plot3d.transform.Translate;
import org.jzy3d.plot3d.transform.squarifier.ISquarifier;
import org.jzy3d.plot3d.transform.squarifier.XYSquarifier;
import org.jzy3d.plot3d.transform.squarifier.XZSquarifier;
import org.jzy3d.plot3d.transform.squarifier.ZYSquarifier;
import org.jzy3d.ui.editors.LightEditor;
import org.scijava.Context;
import org.scijava.command.Command;
import org.scijava.command.CommandModule;
import org.scijava.command.CommandService;
import org.scijava.plugin.Parameter;
import org.scijava.prefs.PrefService;
import org.scijava.ui.awt.AWTWindows;
import org.scijava.util.*;

import com.jidesoft.swing.CheckBoxList;
import com.jidesoft.swing.CheckBoxListCellRenderer;
import com.jidesoft.swing.CheckBoxListSelectionModel;
import com.jidesoft.swing.CheckBoxTree;
import com.jidesoft.swing.ListSearchable;
import com.jidesoft.swing.SearchableBar;
import com.jidesoft.swing.TreeSearchable;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLException;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.util.FPSAnimator;

import ij.ImagePlus;
import net.imagej.display.ColorTables;
import net.imglib2.display.ColorTable;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.TreeProperties;
import sc.fiji.snt.analysis.MultiTreeColorMapper;
import sc.fiji.snt.analysis.SNTTable;
import sc.fiji.snt.analysis.TreeAnalyzer;
import sc.fiji.snt.analysis.TreeColorMapper;
import sc.fiji.snt.analysis.TreeStatistics;
import sc.fiji.snt.analysis.graph.DirectedWeightedGraph;
import sc.fiji.snt.annotation.AllenCompartment;
import sc.fiji.snt.annotation.AllenUtils;
import sc.fiji.snt.annotation.VFBUtils;
import sc.fiji.snt.annotation.ZBAtlasUtils;
import sc.fiji.snt.gui.SNTCommandFinder;
import sc.fiji.snt.gui.DemoRunner;
import sc.fiji.snt.gui.FileDrop;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.IconFactory;
import sc.fiji.snt.gui.MeasureUI;
import sc.fiji.snt.gui.IconFactory.GLYPH;
import sc.fiji.snt.gui.SNTSearchableBar;
import sc.fiji.snt.gui.SaveMeasurementsCmd;
import sc.fiji.snt.gui.ScriptRecorder;
import sc.fiji.snt.gui.DemoRunner.Demo;
import sc.fiji.snt.gui.cmds.*;
import sc.fiji.snt.io.FlyCircuitLoader;
import sc.fiji.snt.io.NeuroMorphoLoader;
import sc.fiji.snt.plugin.BrainAnnotationCmd;
import sc.fiji.snt.plugin.ConvexHullCmd;
import sc.fiji.snt.plugin.GroupAnalyzerCmd;
import sc.fiji.snt.plugin.ShollAnalysisBulkTreeCmd;
import sc.fiji.snt.plugin.ShollAnalysisTreeCmd;
import sc.fiji.snt.plugin.StrahlerCmd;
import sc.fiji.snt.util.ImpUtils;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SNTColor;
import sc.fiji.snt.util.SNTPoint;
import sc.fiji.snt.util.SWCPoint;
import sc.fiji.snt.viewer.OBJMesh.RemountableDrawableVBO;

/**
 * Implements SNT's Reconstruction Viewer. Relies heavily on the
 * {@code org.jzy3d} package.
 *
 * @author Tiago Ferreira
 */
public class Viewer3D {

	static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

	private enum Engine {
		JOGL(new String[] { "jogl", "gpu" }), EMUL_GL(new String[] { "cpu", "emulgl" }),
		OFFSCREEN(new String[] { "offscreen", "headless" });

		final String[] labels;

		Engine(final String[] labels) {
			this.labels = labels;
		}

		static Engine fromString(final String label) {
			for (final Engine e : Engine.values()) {
				if (Arrays.stream(e.labels).anyMatch(l -> l.equalsIgnoreCase(label))) {
					return e;
				}
			}
			throw new IllegalArgumentException("'" + label + "': not a recognizable engine");
		}
	}

	/**
	 * Presets of a scene's view point.
	 */
	public enum ViewMode {
		
		/** Enforce a XY view point of the scene. Rotation(s) are disabled. */
		XY("XY Constrained", DefCoords.XY),
		/** Enforce a XZ view point of the scene. */
		XZ("XZ Constrained", DefCoords.XZ),
		/** Enforce a ZY view point of the scene. */
		YZ("YZ Constrained", DefCoords.YZ),
		/** No enforcement of view point: freely turn around the scene. */
		DEFAULT("Default", DefCoords.DEF), //
		/** Enforce an 'overview' (two-point perspective) view point of the scene. */
		PERSPECTIVE("Perspective", DefCoords.PERSPECTIVE),

		/** @deprecated Use YZ instead */
		@Deprecated
		SIDE("Side Constrained",  DefCoords.YZ),
		/** @deprecated Use XY instead */
		@Deprecated
		TOP("Top Constrained", DefCoords.XY);

		private final String description;
		private Coord3d coord;

		private ViewMode next() {
			switch (this) {
			case DEFAULT:
				return XY;
			case XY:
			case TOP:
				return XZ;
			case XZ:
			case SIDE:
				return YZ;
			case YZ:
				return PERSPECTIVE;
			default:
				return DEFAULT;
			}
		}

		ViewMode(final String description, final Coord3d coord) {
			this.description = description;
			this.coord = coord;
		}

		static class DefCoords {
			static final Coord3d XY = new Coord3d(-View.PI_div2, -View.PI_div2, View.DISTANCE_DEFAULT); // //new Coord3d(0, Math.PI, View.DISTANCE_DEFAULT)
			static final Coord3d XZ = new Coord3d(-View.PI_div2, 1, View.DISTANCE_DEFAULT); // new Coord3d(-Math.PI / 2, -1, View.DISTANCE_DEFAULT)
			static final Coord3d YZ = new Coord3d(-Math.PI, 0, View.DISTANCE_DEFAULT); // new Coord3d(-Math.PI *2, 0, View.DISTANCE_DEFAULT)
			static final Coord3d PERSPECTIVE = new Coord3d(-Math.PI / 2.675, -0.675, View.DISTANCE_DEFAULT);
			static final Coord3d DEF = View.VIEWPOINT_AXIS_CORNER_TOUCH_BORDER;
		}
	}

	private static final String MESH_LABEL_ALLEN = "Whole Brain (CCFv" + AllenUtils.VERSION + ")";
	private static final String MESH_LABEL_ZEBRAFISH = "Outline (MP ZBA)";
	private static final String MESH_LABEL_JFRC2018 = "JRC 2018 (Unisex)";
	private static final String MESH_LABEL_JFRC2 = "JFRC2";
	private static final String MESH_LABEL_JFRC3 = "JFRC3";
	private static final String MESH_LABEL_FCWB = "FCWB";
	private static final String MESH_LABEL_VNS = "VNS";
	private static final String MESH_LABEL_L1 = "L1";
	private static final String MESH_LABEL_L3 = "L3";

	private static final String PATH_MANAGER_TREE_LABEL = "Path Manager Contents";
	protected static final float DEF_NODE_RADIUS = 3f;
	private static final Color DEF_COLOR = new Color(1f, 1f, 1f, 0.05f);
	private static final Color INVERTED_DEF_COLOR = new Color(0f, 0f, 0f, 0.05f);

	/* Identifiers for multiple viewers */
	private static int currentID = 0;
	private int id;
	private boolean sntInstance;

	/* Maps for plotted objects */
	private Map<String, ShapeTree> plottedTrees;
	private Map<String, RemountableDrawableVBO> plottedObjs;
	private Map<String, Annotation3D> plottedAnnotations;

	/* Settings */
	private Color defColor;
	private float defThickness = DEF_NODE_RADIUS;
	private Prefs prefs;

	/* Color Bar */
	private ColorLegend cBar;

	/* Manager */
	private CheckboxListEditable managerList;

	private AChart chart;
	private View view;
	private ViewerFrame frame;
	private GuiUtils gUtils;
	private KeyController keyController;
	private MouseController mouseController;
	private boolean viewUpdatesEnabled = true;
	private ViewMode currentView;
	private FileDropWorker fileDropWorker;
	private boolean abortCurrentOperation;
	private final Engine ENGINE;
	private SNTCommandFinder cmdFinder;
	private ScriptRecorder recorder;

	@Parameter
	private CommandService cmdService;

	@Parameter
	private PrefService prefService;

	private Viewer3D(final Engine engine) {
		SNTUtils.log("Initializing Viewer3D...");
		ENGINE = engine;
		if (Engine.JOGL == engine || Engine.OFFSCREEN == engine) {
			workaroundIntelGraphicsBug();
			Settings.getInstance().setGLCapabilities(new GLCapabilities(GLProfile.getDefault()));
			Settings.getInstance().setHardwareAccelerated(true);
		}
		plottedTrees = new TreeMap<>();
		plottedObjs = new TreeMap<>();
		plottedAnnotations = new TreeMap<>();
		initView();
		prefs = new Prefs(this);
		setID();
		SNTUtils.addViewer(this);
	}

	/**
	 * Instantiates Viewer3D without the 'Controls' dialog ('kiosk mode'). Such
	 * a viewer is more suitable for large datasets and allows for {@link Tree}s to
	 * be added concurrently.
	 */
	public Viewer3D() {
		this(Engine.JOGL);
	}

	/**
	 * Instantiates an interactive Viewer3D with GUI Controls to import, manage
	 * and customize the Viewer's scene.
	 *
	 * @param context the SciJava application context providing the services
	 *          required by the class
	 */
	public Viewer3D(final Context context) {
		this();
		init(context);
		if (!SNTUtils.isContextSet()) SNTUtils.setContext(context);
	}

	/**
	 * Script-friendly constructor.
	 *
	 * @param interactive if true, the viewer is displayed with GUI Controls to
	 *                    import, manage and customize the Viewer's scene.
	 */
	public Viewer3D(final boolean interactive) {
		this(interactive, "jogl");
	}

	/**
	 * Script-friendly constructor.
	 *
	 * @param interactive if true, the viewer is displayed with GUI Controls to
	 *                    import, manage and customize the Viewer's scene.
	 * @param engine      the rendering engine. Either "gpu" (JOGL), "cpu" (EmulGL),
	 *                    or "offscreen" (headless). "cpu" and "offscreen" are highly
	 *                    experimental.
	 */
	public Viewer3D(final boolean interactive, final String engine) {
		this(Engine.fromString(engine));
		if (interactive) {
			if (ENGINE == Engine.OFFSCREEN)
				throw new IllegalArgumentException("Offscreen engine cannot be used interactively");
			init(SNTUtils.getContext());
		}
	}

	protected Viewer3D(final SNT snt) {
		this(snt.getContext());
		sntInstance = true;
	}

	private void init(final Context context) {
		GuiUtils.setLookAndFeel();
		initManagerList();
		context.inject(this);
		prefs.setPreferences();
		cmdFinder = new SNTCommandFinder(this);
	}

	protected static void workaroundIntelGraphicsBug() { // FIXME: This should go away with jogl 2.40?
		/*
		 * In a fresh install of ubuntu 20.04 displaying a 3DViewer triggers a
		 * ```com.jogamp.opengl.GLException: Profile GL4bc is not available on
		 * X11GraphicsDevice (...)``` The workaround discussed here works:
		 * https://github.com/processing/processing/issues/5476. Since it has no
		 * (apparent) side effects, we'll use it here for all platforms
		 */
		System.setProperty("jogl.disable.openglcore", System.getProperty("jogl.disable.openglcore", "false"));
	}

	/**
	 * Returns this Viewer's id.
	 *
	 * @return this Viewer's unique numeric ID.
	 */
	public int getID() {
		return id;
	}

	private void setID() {
		id = ++currentID;
	}

	/**
	 * Sets whether the scene should update (refresh) every time a new
	 * reconstruction (or mesh) is added/removed from the scene.
	 *
	 * @param enabled Whether scene updates should be enabled. Should be set to
	 *                {@code false} when performing bulk operations. Scene will
	 *                update if set to {@code true}
	 */
	public void setSceneUpdatesEnabled(final boolean enabled) {
		viewUpdatesEnabled = enabled;
		if (enabled) view.shoot(); // same as char.render();
		if (managerList != null) {
			managerList.model.setListenersEnabled(viewUpdatesEnabled);
			//setListenersEnabled already calls managerList.model.update();
		}
		if (viewUpdatesEnabled) {
			chart.getView().updateBounds();
		}
	}

	private boolean chartExists() {
		return chart != null && chart.getCanvas() != null;
	}

	/* returns true if chart was initialized */
	private boolean initView() {
		if (chartExists()) return false;
		final Quality quality = Quality.Nicest();
		quality.setHiDPIEnabled(true); // requires java 9+
		chart = new AChart(quality, this);
		chart.black();
		view = chart.getView();
		view.setBoundMode(ViewBoundMode.AUTO_FIT);
		keyController = new KeyController(chart);
		mouseController = new MouseController(chart);
		chart.getCanvas().addKeyController(keyController);
		chart.getCanvas().addMouseController(mouseController);
		chart.setAxeDisplayed(false);
		chart.setAnimated(true);
		squarify("none", false);
		currentView = ViewMode.DEFAULT;
		if ( !(chart.getCanvas() instanceof OffscreenCanvas)) {
			gUtils = new GuiUtils((Component) chart.getCanvas());
			fileDropWorker = new FileDropWorker((Component) chart.getCanvas(), gUtils);
		}
		return true;
	}

	private void squarify(final String axes, final boolean enable) {
		final String parsedAxes = (enable) ? axes.toLowerCase() : "none";
		switch (parsedAxes) {
		case "xy":
			view.setSquarifier(new XYSquarifier());
			view.setSquared(true);
			return;
		case "zy":
			view.setSquarifier(new ZYSquarifier());
			view.setSquared(true);
			return;
		case "xz":
			view.setSquarifier(new XZSquarifier());
			view.setSquared(true);
			return;
		default:
			view.setSquarifier(null);
			view.setSquared(false);
			return;
		}
	}

	private void flipAxis(final String axis, final boolean enable) {
		final String parsedAxis = axis.toLowerCase();
		if (parsedAxis.contains("horizontal"))
			view.get2DLayout().setHorizontalAxisFlip(enable);
		else if (parsedAxis.contains("vertical"))
			view.get2DLayout().setVerticalAxisFlip(enable);
		else if (parsedAxis.contains("all") || parsedAxis.contains("both"))
			view.get2DLayout().setBothAxisFlip(enable);
	}

	public void setAxesLabels(final String... labels) {
		if (labels == null) {
			view.getAxisLayout().setXAxisLabel("X");
			view.getAxisLayout().setYAxisLabel("Y");
			view.getAxisLayout().setZAxisLabel("Z");
			return;
		}
		if (labels.length > 0) {
			view.getAxisLayout().setXAxisLabel(labels[0]);
		}
		if (labels.length > 1) {
			view.getAxisLayout().setYAxisLabel(labels[1]);
		}
		if (labels.length > 2) {
			view.getAxisLayout().setZAxisLabel(labels[2]);
		}
	}

	private String[] getAxesLabels() {
		return new String[] { view.getAxisLayout().getXAxisLabel(), view.getAxisLayout().getYAxisLabel(),
				view.getAxisLayout().getZAxisLabel() };
	}

	private void rebuild() {
		SNTUtils.log("Rebuilding scene...");
		try {
			// remember settings so that they can be restored
			final boolean lighModeOn = !isDarkModeOn();
			final boolean axesOn = view.isAxisDisplayed();
			final float currentZoomStep = keyController.zoomStep;
			final double currentRotationStep = keyController.rotationStep;
			final float currentPanStep = mouseController.panStep;
			final ISquarifier squarifier = view.getSquarifier();
			final boolean squared = view.getSquared();
			final CameraMode currentCameraMode = view.getCameraMode();
			final ViewportMode viewPortMode = view.getCamera().getViewportMode();
			final Coord3d currentViewPoint = view.getViewPoint();
			final BoundingBox3d currentBox = view.getBounds();
			final boolean isAnimating = mouseController.isAnimating();
			setAnimationEnabled(false);
			chart.stopAllThreads();
			chart.dispose();
			chart = null;
			if (managerList != null) {
				// update manager list to reflect that all objects are going to be visible
				final int allIdx = managerList.getCheckBoxListSelectionModel().getAllEntryIndex();
				managerList.getCheckBoxListSelectionModel().setSelectionInterval(allIdx, allIdx);
			}
			initView();
			keyController.zoomStep = currentZoomStep;
			keyController.rotationStep = currentRotationStep;
			mouseController.panStep = currentPanStep;
			if (lighModeOn) keyController.toggleDarkMode();
			if (axesOn) chart.setAxeDisplayed(axesOn);
			view.setSquarifier(squarifier);
			view.setSquared(squared);
			view.setCameraMode(currentCameraMode);
			view.getCamera().setViewportMode(viewPortMode);
			view.setViewPoint(currentViewPoint);
			view.setBoundsManual(currentBox);
			addAllObjects();
			setAnimationEnabled(isAnimating);
			if (frame != null && frame.managerPanel != null && frame.managerPanel.debugger != null) {
				frame.managerPanel.debugger.setWatchedChart(chart);
			}
		}
		catch (final GLException | NullPointerException exc) {
			SNTUtils.error("Rebuild Error", exc);
		}
		if (frame != null) frame.replaceCurrentChart((AChart)chart);
		updateView();
	}

	public Viewer3D duplicate() {
		SNTUtils.log("Duplicating viewer... (visible objects only)");

		final Viewer3D dup = new Viewer3D();
		dup.initView();
		dup.setSceneUpdatesEnabled(false);
		if (this.cBar != null) {
			this.cBar.updateColors();
			dup.chart.add(cBar.duplicate(dup.chart).get(), false);
		}
		plottedTrees.forEach((k, shapeTree) -> {
			if (shapeTree.isDisplayed()) {
				final ShapeTree dupShapeTree = new ShapeTree(shapeTree.tree);
				dupShapeTree.setDisplayed(true);
				dup.chart.add(dupShapeTree.get(), false);
				dup.plottedTrees.put(k, dupShapeTree);
			}
		});
		plottedObjs.forEach((k, remountableDrawableVBO) -> {
			if (remountableDrawableVBO.isDisplayed()) {
				final OBJMesh dupMesh = remountableDrawableVBO.objMesh.duplicate();
				dupMesh.drawable.setDisplayed(true);
				dup.chart.add(dupMesh.drawable, false);
				dup.plottedObjs.put(k, dupMesh.drawable);
			}
		});
		plottedAnnotations.forEach((k, annot) -> {
			if (annot.getDrawable().isDisplayed()) {
				final Annotation3D dupAnnot = new Annotation3D(dup, Collections.singleton(annot));
				dupAnnot.getDrawable().setDisplayed(true);
				dup.chart.add(dupAnnot.getDrawable(), false);
				dup.plottedAnnotations.put(k, dupAnnot);
			}
		});
		dup.keyController.zoomStep = keyController.zoomStep;
		dup.keyController.rotationStep = keyController.rotationStep;
		dup.mouseController.panStep = mouseController.panStep;
		if (!isDarkModeOn())
			dup.keyController.toggleDarkMode();
		dup.chart.setAxeDisplayed(view.isAxisDisplayed());
		dup.view.setSquarifier(view.getSquarifier());
		dup.view.setSquared(view.getSquared());
		dup.view.setCameraMode(view.getCameraMode());
		dup.view.getCamera().setViewportMode(view.getCamera().getViewportMode());
		dup.view.setViewPoint(view.getViewPoint().clone());
		dup.setSceneUpdatesEnabled(viewUpdatesEnabled);
		dup.updateView();
		dup.frame = new ViewerFrame((AChart) dup.chart, frame.getWidth(), frame.getHeight(), dup.managerList != null,
				frame.getGraphicsConfiguration());
		dup.frame.setLocationRelativeTo(frame);
		final int spacer = frame.getInsets().top;
		dup.frame.setLocation(frame.getX() + spacer, frame.getY() + spacer);
		dup.frame.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(final WindowEvent e) {
				dup.dispose();
			}
		});
		return dup;
	}

	/**
	 * Checks if all drawables in the 3D scene are being rendered properly,
	 * rebuilding the entire scene if not. Useful to "hard-reset" the viewer, e.g.,
	 * to ensure all meshes are redrawn.
	 *
	 * @see #updateView()
	 */
	public void validate() {
		if (!sceneIsOK()) chart.getView().updateBoundsForceUpdate(true);
		if (!sceneIsOK()) rebuild();
	}

	/**
	 * Enables/disables debug mode
	 *
	 * @param enable true to enable debug mode, otherwise false
	 */
	public void setEnableDebugMode(final boolean enable) {
		if (frame != null && frame.managerPanel != null) {
			frame.managerPanel.setDebuggerEnabled(enable);
		}
		SNTUtils.setDebugMode(enable);
	}

	/**
	 * Enables/disables "Dark Mode" mode
	 *
	 * @param enable true to enable "Dark Mode", "Light Mode" otherwise
	 */
	public void setEnableDarkMode(final boolean enable) {
		final boolean toggle = keyController != null && isDarkModeOn() != enable;
		if (toggle) keyController.toggleDarkMode();
	}

	/**
	 * Sets whether axons and dendrites should be imported as separated objects.
	 *
	 * @param split true to segregate imported Trees into axonal and dendritic
	 *              subtrees. This is likely only relevant to allow for subtree
	 *              customizations using the GUI commands provided by the "RV
	 *              Controls" dialog. This parameter is ignored if Trees have no
	 *              annotations.
	 */
	public void setSplitDendritesFromAxons(final boolean split) {
		prefs.setSplitDendritesFromAxons(split);
	}

	/**
	 * Rotates the scene.
	 *
	 * @param degrees the angle, in degrees
	 * @throws IllegalArgumentException if current view mode does not allow
	 *           rotations
	 */
	public void rotate(final float degrees) throws IllegalArgumentException {
		if (!chart.isRotationEnabled()) {
			throw new IllegalArgumentException("Rotations not allowed under " +
				currentView.description);
		}
		mouseController.rotate(new Coord2d(-Math.toRadians(degrees), 0),
			viewUpdatesEnabled);
	}

	/**
	 * Records an animated rotation of the scene as a sequence of images.
	 *
	 * @param angle the rotation angle (e.g., 360 for a full rotation)
	 * @param frames the number of frames in the animated sequence
	 * @param destinationDirectory the directory where the image sequence will be
	 *          stored.
	 * @throws IllegalArgumentException if no view exists, or current view is
	 *           constrained and does not allow 360 degrees rotation
	 * @throws SecurityException if it was not possible to save files to
	 *           {@code destinationDirectory}
	 */
	public void recordRotation(final float angle, final int frames, final File destinationDirectory) throws IllegalArgumentException,
		SecurityException
	{
		if (!chartExists()) {
			throw new IllegalArgumentException("Viewer is not visible");
		}
		if (chart.getViewMode() == ViewPositionMode.TOP) {
			throw new IllegalArgumentException(
				"Current constrained view does not allow scene to be rotated.");
		}
		mouseController.stopThreadController();
		mouseController.recordRotation(angle, frames, destinationDirectory);

		// Log instructions on how to assemble video
		logVideoInstructions(destinationDirectory);

	}

	private void logVideoInstructions(final File destinationDirectory) {
		final StringBuilder sb = new StringBuilder("The image sequence can be converted into a video using ffmpeg (www.ffmpeg.org):");
		sb.append("\n===========================================\n");
		sb.append("cd \"").append(destinationDirectory).append("\"\n");
		sb.append("ffmpeg -framerate ").append(prefs.getFPS()).append(" -i %5d.png -vf \"");
		sb.append("scale=-1:-1,format=yuv420p\" video.mp4");
		sb.append("\n-------------------------------------------\n");
		sb.append("NB: hflip/vflip can be included in the comma-separated list of filter options to\n");
		sb.append("flip sequence horizontally/vertically, e.g.: hflip,vflip,scale=-1:-1,format=yuv420p");
		sb.append("\n===========================================\n");
		sb.append("\nAlternatively, IJ built-in commands can also be used, e.g.:\n");
		sb.append("\"File>Import>Image Sequence...\", followed by \"File>Save As>AVI...\"");
		try {
			Files.write(Paths.get(new File(destinationDirectory, "-build-video.txt").getAbsolutePath()),
					sb.toString().getBytes(StandardCharsets.UTF_8));
		} catch (final IOException e) {
			System.out.println(sb.toString());
		}
	}

	/**
	 * Checks if scene is being rendered under dark or light background.
	 *
	 * @return true, if "Dark Mode" is active
	 */
	public boolean isDarkModeOn() {
		return view.getBackgroundColor() == Color.BLACK;
	}

	/**
	 * Checks whether axons and dendrites of imported Trees are set to be imported
	 * as separated objects.
	 *
	 * @return if imported trees are set to be split into axonal and dendritic
	 *         subtrees.
	 */
	public boolean isSplitDendritesFromAxons() {
		return prefs.isSplitDendritesFromAxons();
	}

	/**
	 * Does not allow scene to be interactive. Only static orthogonal views allowed.
	 * 
	 * @see #unfreeze()
	 * 
	 */
	public void freeze() {
		chart.getQuality().setAnimated(false);
	}

	/**
	 * Allows scene to be interactive.
	 * 
	 * @see #freeze()
	 */
	public void unfreeze() {
		chart.getQuality().setAnimated(true);
	}

	/**
	 * 
	 *
	 * @param cmd the recorded command
	 */
	public void runCommand(final String cmd) {
		cmdFinder.runCommand(cmd);
	}


	private void addAllObjects() {
		if (cBar != null) {
			cBar.updateColors();
			chart.add(cBar.get(), false);
		}
		plottedObjs.forEach((k, drawableVBO) -> {
			drawableVBO.unmount();
			chart.add(drawableVBO, false);
		});
		plottedAnnotations.forEach((k, annot) -> {
			chart.add(annot.getDrawable(), false);
		});
		plottedTrees.values().forEach(shapeTree -> chart.add(shapeTree.get(),
			false));
	}

	private void initManagerList() {
		managerList = new CheckboxListEditable(new UpdatableListModel<>());
		managerList.getCheckBoxListSelectionModel().addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting() && getManagerPanel() != null) {
				final Set<String> selectedKeys = getLabelsCheckedInManager();
				plottedTrees.forEach((k, shapeTree) -> {
					shapeTree.setDisplayed(selectedKeys.contains(k));
				});
				plottedObjs.forEach((k, drawableVBO) -> {
					drawableVBO.setDisplayed(selectedKeys.contains(k));
				});
				plottedAnnotations.forEach((k, annot) -> {
					annot.getDrawable().setDisplayed(selectedKeys.contains(k));
				});
				if (getRecorder(false) != null) {
					// Record toggling of last checkbox interaction
					final java.awt.Point mPos = managerList.getMousePosition();
					if (null != mPos) {
						final int mIndex = managerList.locationToIndex(mPos);
						if (mIndex > -1) {
							getRecorder(false).recordCmd("viewer.setVisible(\""
									+ managerList.getModel().getElementAt(mIndex) + "\", "
									+ managerList.getCheckBoxListSelectionModel().isSelectedIndex(mIndex) + ")");
						}
					}
				}
				// view.shoot();
			}
		});
	}

	protected Color fromAWTColor(final java.awt.Color color) {
		return (color == null) ? getDefColor() : new Color(color.getRed(), color
			.getGreen(), color.getBlue(), color.getAlpha());
	}

	private Color fromAWTColor(final java.awt.Color color, final Color fallbackColor) {
		return (color == null) ? fallbackColor : new Color(color.getRed(), color
			.getGreen(), color.getBlue(), color.getAlpha());
	}

	private Color fromColorRGB(final ColorRGB color) {
		return (color == null) ? getDefColor() : new Color(color.getRed(), color
			.getGreen(), color.getBlue(), color.getAlpha());
	}

	private java.awt.Color toAWTColor(final Color color) {
		if (color.r < 0)
			color.r = 0;
		if (color.g < 0)
			color.g = 0;
		if (color.b < 0)
			color.b = 0;
		return new java.awt.Color(color.r, color.g, color.b, color.a);
	}

	private ColorRGB toColorRGB(final Color color) {
		if (color.r < 0)
			color.r = 0;
		if (color.g < 0)
			color.g = 0;
		if (color.b < 0)
			color.b = 0;
		return new ColorRGB((int) (color.r * 255 + 0.5), (int) (color.g * 255 + 0.5), (int) (color.b * 255 + 0.5));
	}

	private String makeUniqueKey(final Map<String, ?> map, final String key) {
		for (int i = 2; i <= 100; i++) {
			final String candidate = key + " (" + i + ")";
			if (!map.containsKey(candidate)) return candidate;
		}
		return key + " (" + UUID.randomUUID() + ")";
	}

	private String getUniqueLabel(final Map<String, ?> map,
		final String fallbackPrefix, final String candidate)
	{
		final String label = (candidate == null || candidate.trim().isEmpty())
			? fallbackPrefix : candidate;
		return (map.containsKey(label)) ? makeUniqueKey(map, label) : label;
	}

	/**
	 * Adds a tree to this viewer. Note that calling {@link #updateView()} may be
	 * required to ensure that the current View's bounding box includes the added
	 * Tree.
	 *
	 * @param tree the {@link Tree} to be added. The Tree's label will be used as
	 *          identifier. It is expected to be unique when rendering multiple
	 *          Trees, if not (or no label exists) a unique label will be
	 *          generated.
	 * @see Tree#getLabel()
	 * @see #removeTree(String)
	 * @see #updateView()
	 * @see #setSplitDendritesFromAxons(boolean)
	 */
	public void addTree(final Tree tree) {
		final String assignedType = tree.getProperties().getProperty(TreeProperties.KEY_COMPARTMENT, TreeProperties.UNSET);
		if (TreeProperties.UNSET.equals(assignedType) && prefs.isSplitDendritesFromAxons()) {
			int countFailures = 0;
			for (final String type : new String[] { "Dnd", "Axn"}) {
				final Tree subTree = tree.subTree(type, "soma");
				if (subTree == null || subTree.isEmpty())
					countFailures++;
				else {
					subTree.setLabel(tree.getLabel() + " " + type);
					addTreeInternal(subTree);
				}
			}
			if (countFailures == 2)
				addTreeInternal(tree);
		} else {
			addTreeInternal(tree);
		}
	}

	private void addTreeInternal(final Tree tree) {
		final String label = getUniqueLabel(plottedTrees, "Tree ", tree.getLabel());
		final ShapeTree shapeTree = new ShapeTree(tree);
		plottedTrees.put(label, shapeTree);
		addItemToManager(label);
		shapeTree.setDisplayed(true);
		chart.add(shapeTree.get(), viewUpdatesEnabled);
	}

	/**
	 * Adds a collection of trees.
	 *
	 * @param trees      the trees to be added
	 * @param color      Set it to {@code null}, {@code none} or {@code ""} to
	 *                   ignore this option altogether. Set it to {@code unique} to
	 *                   assign unique colors to each tree in the collection.
	 * @param commonTags common tag(s) to be assigned to the group (to be displayed
	 *                   in 'RV Controls' list).
	 */
	public void addTrees(final Collection<Tree> trees, final String color, final String... commonTags) {
		if (commonTags != null) {
			trees.forEach(tree -> {
				String label = tree.getLabel();
				if (label == null) label = "";
				tree.setLabel(label + " {" + String.join(",", commonTags)  + "}");
			});
		}
		addTrees(trees, color);
	}

	/**
	 * Adds a collection of trees.
	 *
	 * @param trees the trees to be added.
	 * @param color the rendering color. Set it to {@code null}, {@code none} or {@code ""} to
	 *              ignore this option altogether. Set it to {@code unique} to assign
	 *              unique colors to each tree in the collection.
	 */
	public void addTrees(final Collection<Tree> trees, final String color) {
		final String adjustedC = (color == null) ? "" : color.toLowerCase();
		switch (adjustedC) {
		case "unique":
			Tree.assignUniqueColors(trees);
			break;
		case "":
		case "none":
			break;
		default:
			trees.forEach(tree -> tree.setColor(color));
			break;
		}
		addCollection(trees);
	}

	/**
	 * Adds a collection of trees.
	 *
	 * @param trees               the trees to be added.
	 * @param color               the color to be applied, either an HTML color codes
	 *                            starting with hash ({@code #}), a color preset
	 *                            ("red", "blue", etc.), or integer triples of the
	 *                            form {@code r,g,b} and range {@code [0, 255]}
	 * @param transparencyPercent the color transparency (in percentage)
	 */
	public void addTrees(final Collection<Tree> trees, final String color, final double transparencyPercent) {
		trees.forEach(tree -> tree.setColor(color, transparencyPercent));
		addCollection(trees);
	}

	/**
	 * @deprecated use {@link #addTrees(Collection, String)} instead
	 */
	@Deprecated
	public void addTrees(final Collection<Tree> trees, final boolean assignUniqueColors) {
		addTrees(trees, (assignUniqueColors) ? null : "");
	}

	/**
	 * Adds a collection of trees or meshes from input files.
	 *
	 * @param files the files to be imported
	 * @param color the color to be assigned to imported reconstructions/meshes. If
	 *              empty, {@code null} or {@code unique}, objects will be assigned
	 *              a unique color.
	 * @see #setSplitDendritesFromAxons(boolean)
	 */
	public void add(final File[] files, final String color) {
		final ColorRGB c = (color == null || color.trim().isEmpty() || "unique".equalsIgnoreCase(color)) ? null
				: new ColorRGB(color);
		fileDropWorker.importFilesWithoutDrop(files, c);
	}

	private GuiUtils guiUtils() {
		return (frame.manager != null) ? frame.managerPanel.guiUtils : gUtils;
	}

	/**
	 * Gets the tree associated with the specified label.
	 *
	 * @param label the (unique) label as displayed in Viewer's list
	 * @return the Tree or null if no Tree is associated with the specified label
	 */
	public Tree getTree(final String label) {
		final ShapeTree shapeTree = plottedTrees.get(label);
		return (shapeTree == null) ? null : shapeTree.tree;
	}

	/**
	 * Gets the annotation associated with the specified label.
	 *
	 * @param label the (unique) label as displayed in Viewer's list
	 * @return the annotation or null if no annotation is associated with the specified label
	 */
	public Annotation3D getAnnotation(final String label) {
		return plottedAnnotations.get(label);
	}

	/**
	 * Gets the mesh associated with the specified label.
	 *
	 * @param label the (unique) label as displayed in Viewer's list
	 * @return the mesh or null if no mesh is associated with the specified label
	 */
	public OBJMesh getMesh(final String label) {
		final RemountableDrawableVBO vbo = plottedObjs.get(label);
		return (vbo == null) ? null : vbo.objMesh;
	}

	/**
	 * Returns all trees added to this viewer.
	 *
	 * @return the Tree list
	 */
	public List<Tree> getTrees() {
		return getTrees(false);
	}

	/**
	 * Returns all trees added to this viewer.
	 *
	 * @param visibleOnly If true, only visible Trees are retrieved.
	 * @return the Tree list
	 */
	public List<Tree> getTrees(final boolean visibleOnly) {
		final ArrayList<Tree> trees;
		if (visibleOnly) {
			trees = new ArrayList<>();
			plottedTrees.forEach((k, shapeTree) -> {
				if (shapeTree.isDisplayed())
					trees.add(shapeTree.tree);
			});
		} else {
			trees = new ArrayList<>(plottedTrees.values().size());
			plottedTrees.values().forEach(shapeTree -> trees.add(shapeTree.tree));
		}
		return trees;
	}

	/**
	 * Returns all meshes added to this viewer.
	 *
	 * @return the mesh list
	 */
	public List<OBJMesh> getMeshes() {
		return getMeshes(false);
	}

	/**
	 * Returns all meshes added to this viewer.
	 *
	 * @param visibleOnly If true, only visible meshes are retrieved.
	 *
	 * @return the mesh list
	 */
	public List<OBJMesh> getMeshes(final boolean visibleOnly) {
		final ArrayList<OBJMesh> meshes;
		if (visibleOnly) {
			meshes = new ArrayList<>();
			plottedObjs.values().forEach( vbo -> {
				if (vbo.isDisplayed())
					meshes.add(vbo.objMesh);
			});
		} else {
			meshes = new ArrayList<>(plottedObjs.values().size());
			plottedObjs.values().forEach( vbo -> meshes.add(vbo.objMesh));
		}
		return meshes;
	}

	/**
	 * Returns all annotations added to this viewer.
	 *
	 * @return the annotation list
	 */
	public List<Annotation3D> getAnnotations() {
		return new ArrayList<>(plottedAnnotations.values());
	}

	private void addAnnotation(final Annotation3D annotation) {
		final String label = (annotation.getLabel() == null) ? "Object" : annotation.getLabel();
		final String uniquelabel = getUniqueLabel(plottedAnnotations, "Annot.", label);
		annotation.setLabel(uniquelabel);
		plottedAnnotations.put(uniquelabel, annotation);
		addItemToManager(uniquelabel);
		chart.add(annotation.getDrawable(), viewUpdatesEnabled);
	}

	public Annotation3D annotateSurface(final Collection<? extends SNTPoint> points, final String label)
	{
		return annotateSurface(points, label, false);
	}
	
	/**
	 * Computes a convex hull from a collection of points and adds it to the
	 * scene as an annotation.
	 *
	 * @param points the collection of points defining the convex set.
	 * @param label  the (optional) annotation identifier. If null or empty, a
	 *               unique label will be generated.
	 * @param computeVolume whether to compute the volume of the convex hull.
	 * 				 If true, the volume is stored in the "volume" field of the 
	 * 				 returned {@link Annotation3D} object.
	 * @return the {@link Annotation3D}
	 */
	public Annotation3D annotateSurface(final Collection<? extends SNTPoint> points, final String label, final boolean computeVolume) {
		Annotation3D annotation;
		if (computeVolume) {
			annotation = new Annotation3D(this, points, Annotation3D.SURFACE_AND_VOLUME);
		} else { 
			annotation = new Annotation3D(this, points, Annotation3D.SURFACE);
		}
		final String uniqueLabel = getUniqueLabel(plottedAnnotations, "Surf. Annot.", label);
		annotation.setLabel(uniqueLabel);
		addAnnotation(annotation);
		return annotation;
	}

	/**
	 * Adds a highlighting point annotation to this viewer.
	 *
	 * @param point the point to be highlighted
	 * @param label the (optional) annotation identifier. If null or empty, a unique
	 *              label will be generated.
	 * @return the {@link Annotation3D}
	 */
	public Annotation3D annotatePoint(final SNTPoint point, final String label) {
		return annotatePoint(point, label, "red", getDefaultThickness() * 10);
	}

	/**
	 * Adds a highlighting point annotation to this viewer.
	 *
	 * @param node the node to be highlighted
	 * @param label the (optional) annotation identifier. If null or empty, a unique
	 *              label will be generated.
	 * @param color the annotation color
	 * @param size the annotation size
	 * @return the {@link Annotation3D}
	 */
	public Annotation3D annotatePoint(final SNTPoint node, final String label, final String color, final float size) {
		final Annotation3D annotation = new Annotation3D(this, Collections.singleton(node), Annotation3D.SCATTER);
		final String defLabel = String.format("(%.1f,%.1f,%.1f)", node.getX(), node.getY(), node.getZ());
		final String uniqueLabel = getUniqueLabel(plottedAnnotations, defLabel, label);
		annotation.setLabel(uniqueLabel);
		annotation.setColor(color, 30);
		annotation.setSize(size);
		plottedAnnotations.put(uniqueLabel, annotation);
		addItemToManager(uniqueLabel);
		chart.add(annotation.getDrawable(), viewUpdatesEnabled);
		return annotation;
	}

	/**
	 * Adds a scatter (point cloud) annotation to this viewer.
	 *
	 * @param points the collection of points in the annotation
	 * @param label  the (optional) annotation identifier. If null or empty, a
	 *               unique label will be generated.
	 * @return the {@link Annotation3D}
	 */
	public Annotation3D annotatePoints(final Collection<? extends SNTPoint> points, final String label) {
		if (points.size() == 1) {
			return annotatePoint(points.iterator().next(), label);
		}
		final Annotation3D annotation = new Annotation3D(this, points, Annotation3D.SCATTER);
		final String uniqueLabel = getUniqueLabel(plottedAnnotations, "Surf. Annot.", label);
		annotation.setLabel(uniqueLabel);
		plottedAnnotations.put(uniqueLabel, annotation);
		addItemToManager(uniqueLabel);
		chart.add(annotation.getDrawable(), viewUpdatesEnabled);
		return annotation;
	}

	/**
	 * Adds a line annotation to this viewer.
	 *
	 * @param points the collection of points in the line annotation (at least 2
	 *               elements required). Start and end of line are highlighted if 2
	 *               points are specified.
	 * @param label  the (optional) annotation identifier. If null or empty, a
	 *               unique label will be generated.
	 * @return the {@link Annotation3D} or null if collection contains less than 2
	 *         elements
	 */
	public Annotation3D annotateLine(final Collection<? extends SNTPoint> points, final String label) {
		if (points == null || points.size() < 2) return null;
		final int type = (points.size()==2) ? Annotation3D.Q_TIP : Annotation3D.STRIP;
		final Annotation3D annotation = new Annotation3D(this, points, type);
		final String uniqueLabel = getUniqueLabel(plottedAnnotations, "Line Annot.", label);
		annotation.setLabel(uniqueLabel);
		plottedAnnotations.put(uniqueLabel, annotation);
		addItemToManager(uniqueLabel);
		chart.add(annotation.getDrawable(), viewUpdatesEnabled);
		return annotation;
	}

	public Annotation3D annotatePlane(final SNTPoint origin, final SNTPoint originOpposite, final String label) {
		if (origin == null || originOpposite == null) return null;
		final Annotation3D annotation = new Annotation3D(this, List.of(origin, originOpposite), Annotation3D.PLANE);
		final String uniqueLabel = getUniqueLabel(plottedAnnotations, "Plane Annot.", label);
		annotation.setLabel(uniqueLabel);
		plottedAnnotations.put(uniqueLabel, annotation);
		addItemToManager(uniqueLabel);
		chart.add(annotation.getDrawable(), viewUpdatesEnabled);
		return annotation;
	}

	/**
	 * Merges a collection of annotations into a single object.
	 *
	 * @param annotations the collection of annotations.
	 * @param label       the (optional) identifier for the merged annotation. If
	 *                    null or empty, a unique label will be generated.
	 * @return the merged {@link Annotation3D}
	 */
	public Annotation3D mergeAnnotations(final Collection<Annotation3D> annotations, final String label) {
		final boolean updateFlag = viewUpdatesEnabled;
		setSceneUpdatesEnabled(false);
		annotations.forEach(annot -> {
			final String[] labelAndManagerEntry = TagUtils.getUntaggedAndTaggedLabels(annot.getLabel());
			removeDrawable(getAnnotationDrawables(), labelAndManagerEntry[0], labelAndManagerEntry[1]);
		});
		setSceneUpdatesEnabled(updateFlag);
		final Annotation3D annotation = new Annotation3D(this, annotations);
		final String uniqueLabel = getUniqueLabel(plottedAnnotations, "Merged Annot.", label);
		annotation.setLabel(uniqueLabel);
		plottedAnnotations.put(uniqueLabel, annotation);
		addItemToManager(uniqueLabel);
		chart.add(annotation.getDrawable(), viewUpdatesEnabled);
		return annotation;
	}

	private void addItemToManager(final String label) {
		if (managerList == null || managerList.model == null) return;
		final int[] indices = managerList.getCheckBoxListSelectedIndices();
		final int index = managerList.model.size() - 1;
		managerList.model.insertElementAt(label, index);
		// managerList.ensureIndexIsVisible(index);
		managerList.addCheckBoxListSelectedIndex(index);
		for (final int i : indices)
			managerList.addCheckBoxListSelectedIndex(i);
	}

	private boolean deleteItemFromManager(final String managerEntry) {
		if (managerList == null || managerList.model == null)
			return false;
		if (!managerList.model.removeElement(managerEntry)) {
			// managerEntry was not found. It is likely associated
			// with a tagged element. Retry:
			for (int i = 0; i < managerList.model.getSize(); i++) {
				final Object entry = managerList.model.getElementAt(i);
				if (CheckBoxList.ALL_ENTRY.equals(entry)) continue;
				if (TagUtils.removeAllTags(entry.toString()).equals(managerEntry)) {
					return managerList.model.removeElement(entry);
				}
			}
		}
		return false;
	}

	/**
	 * Rebuilds (repaints) a scene object (e.g., a Tree after being modified
	 * elsewhere)
	 * 
	 * @param obj the object to be re-rendered (or its label)
	 */
	public void rebuild(final Object obj) {
		if (obj instanceof String && plottedTrees.get(obj) != null) {
			plottedTrees.get(obj).rebuildShape();
		} else if (obj instanceof Tree) {
			plottedTrees.values().forEach(shapeTree -> {
				if (((Tree) obj) == shapeTree.tree) {
					shapeTree.rebuildShape();
					return;
				}
			});
		} else {
			final Drawable vbo = getDrawableFromObject(obj);
			if (vbo != null)
				vbo.draw(chart.getPainter());
		}
	}

	/**
	 * Updates the scene bounds to ensure all visible objects are displayed.
	 */
	public void updateView() {
		if (view != null) {
			view.shoot(); // !? without forceRepaint() dimensions are not updated
			fitToVisibleObjects(false, false); //TODO: Why not use view.updateBounds()?
		}
		if (managerList != null) managerList.update(); // force update the manager list
	}

	private Drawable getDrawableFromObject(final Object object) {
		if (object instanceof Drawable) {
			return (Drawable) object;
		} else if (object instanceof Annotation3D) {
			return ((Annotation3D) object).getDrawable();
		} else if (object instanceof OBJMesh) {
			return ((OBJMesh) object).getDrawable();
		} else if (object instanceof Tree) {
			return plottedTrees.get(((Tree) object).getLabel());
		} else if (object instanceof String) {
			final ShapeTree treeShape = plottedTrees.get((String) object);
			if (treeShape != null) return treeShape;
			final DrawableVBO obj = plottedObjs.get((String) object);
			if (obj != null) return obj;
			final Annotation3D annot = plottedAnnotations.get((String) object);
			if (annot != null) return annot.getDrawable();
		} else if (object instanceof Collection) {
			final Composite composite = new Shape();
			for(final Object o : (Collection<?>)object) {
				composite.add(getDrawableFromObject(o));
			}
			return composite;
		} else if (!CheckBoxList.ALL_ENTRY.equals(object)) {
			throw new IllegalArgumentException("Unsupported object: " + object.getClass().getName());
		}
		return null;
	}

	/**
	 * Zooms the scene into the bounding box enclosing the specified objects.
	 *
	 * @param objects the objects to be zoomed into. These can be {@link OBJMesh},
	 *                {@link Tree}, {@link Annotation3D}, etc., or string(s)
	 *                defining objects listed in the "RV Controls" dialog.
	 *                Collections of supported objects are also supported.
	 */
	public void zoomTo(final Object... objects) {
		final BoundingBox3d bounds = new BoundingBox3d();
		for (final Object obj : objects) {
			final Drawable d = getDrawableFromObject(obj);
			if (d != null && d.isDisplayed() && d.getBounds() != null && !d.getBounds().isReset()) {
				bounds.add(d.getBounds());
			}
		}
		if (bounds.isPoint())
			return;
		// chart.view().lookToBox(bounds); seems too 'loose'
		BoundingBox3d zoomedBox = bounds.scale(new Coord3d(.85f, .85f, .85f));
		zoomedBox = zoomedBox.shift((bounds.getCenter().sub(zoomedBox.getCenter())));
		chart.view().lookToBox(zoomedBox);
	}

	/**
	 * Adds a color bar legend (LUT ramp) from a {@link ColorMapper}.
	 *
	 * @param colorMapper the class extending ColorMapper ({@link TreeColorMapper},
	 *                    etc.)
	 */
	public <T extends sc.fiji.snt.analysis.ColorMapper> void addColorBarLegend(final T colorMapper) {
		final double[] minMax = colorMapper.getMinMax();
		addColorBarLegend(colorMapper.getColorTable(), minMax[0], minMax[1]);
	}

	/**
	 * Adds a color bar legend (LUT ramp) using default settings.
	 *
	 * @param colorTable the color table
	 * @param min the minimum value in the color table
	 * @param max the maximum value in the color table
	 */
	public void addColorBarLegend(final ColorTable colorTable, final double min,
		final double max)
	{
		addColorBarLegend(colorTable, min, max, false);
	}

	/**
	 * Adds a color bar legend (LUT ramp) using default settings.
	 *
	 * @param colorTable      the color table
	 * @param min             the minimum value in the color table
	 * @param max             the maximum value in the color table
	 * @param replaceExisting whether this legend should replace the last legend
	 *                        added to the scene, if any
	 */
	public void addColorBarLegend(final ColorTable colorTable, final double min,
		final double max, final boolean replaceExisting)
	{
		if (replaceExisting) removeColorLegends(true);
		cBar = new ColorLegend(new ColorTableMapper(colorTable, min, max));
		chart.add(cBar.get(), viewUpdatesEnabled);
	}

	/**
	 * Updates the existing color bar legend to new properties. Does nothing if no
	 * legend exists.
	 *
	 * @param min the minimum value in the color table
	 * @param max the maximum value in the color table
	 */
	public void updateColorBarLegend(final double min, final double max) {
		updateColorBarLegend(min, max, -1);
	}

	private void updateColorBarLegend(final double min, final double max, final float fSize)
	{
		if (cBar != null) cBar.update(min, max, fSize);
	}

	/**
	 * Adds a color bar legend (LUT ramp).
	 *
	 * @param colorTable the color table
	 * @param min the minimum value in the color table
	 * @param max the maximum value in the color table
	 * @param font the font the legend font.
	 * @param steps the number of ticks in the legend. Tick placement is computed
	 *          automatically if negative.
	 * @param precision the number of decimal places of tick labels. scientific
	 *          notation is used if negative.
	 */
	public void addColorBarLegend(final ColorTable colorTable, final double min,
		final double max, final Font font, final int steps, final int precision)
	{
		cBar = new ColorLegend(new ColorTableMapper(colorTable, min, max), font,
			steps, precision);
		chart.add(cBar.get(), viewUpdatesEnabled);
	}

	/**
	 * Displays the Viewer and returns a reference to its frame. If the frame has
	 * been made displayable, this will simply make the frame visible. Typically,
	 * it should only be called once all objects have been added to the scene. If
	 * this is an interactive viewer, the 'Controls' dialog is also displayed.
	 *
	 * @return the frame containing the viewer.
	 */
	public Frame show() {
		return show(0, 0);
	}

	public Frame getFrame() {
		return frame;
	}

	public Frame getFrame(final boolean visible) {
		if (frame == null) {
			if (Engine.OFFSCREEN == ENGINE) {
				throw new IllegalArgumentException("Offscreen canvas cannot be displayed.");
			}
			final JFrame dummy = new JFrame();
			frame = (ViewerFrame) show( 0, 0, dummy.getGraphicsConfiguration(), visible);
			dummy.dispose();
		}
		return frame;
	}

	/**
	 * Displays the viewer under specified dimensions. Useful when generating
	 * scene animations programmatically.
	 *
	 * @param width the width of the frame. {@code -1} will set width to its maximum.
	 * @param height the height of the frame. {@code -1} will set height to its maximum.
	 * @return the frame containing the viewer.
	 * @see #show()
	 */
	public Frame show(final int width, final int height) {
		if (Engine.OFFSCREEN == ENGINE) {
			throw new IllegalArgumentException("Offscreen canvas cannot be displayed.");
		}
		final JFrame dummy = new JFrame();
		final Frame frame = show( width, height, dummy.getGraphicsConfiguration(), true);
		dummy.dispose();
		return frame;
	}

	private Frame show(final int width, final int height, final GraphicsConfiguration gConfiguration,
					   final boolean visible) {

		final boolean viewInitialized = initView();
		if (!viewInitialized && frame != null) {
			updateView();
			frame.setVisible(true);
			setFrameSize(width, height);
			return frame;
		}
		else if (viewInitialized) {
			plottedTrees.forEach((k, shapeTree) -> {
				chart.add(shapeTree.get(), viewUpdatesEnabled);
			});
			plottedObjs.forEach((k, drawableVBO) -> {
				chart.add(drawableVBO, viewUpdatesEnabled);
			});
			plottedAnnotations.forEach((k, annot) -> {
				chart.add(annot.getDrawable(), viewUpdatesEnabled);
			});
		}
		if (width == 0 || height == 0) {
			frame = new ViewerFrame((AChart)chart, managerList != null, gConfiguration);
		} else {
			final DisplayMode dm = gConfiguration.getDevice().getDisplayMode();
			final int w = (width < 0) ? dm.getWidth() : width;
			final int h = (height < 0) ? dm.getHeight() : height;
			frame = new ViewerFrame((AChart)chart, w, h, managerList != null, gConfiguration);
		}
		updateView();
		frame.canvas.requestFocusInWindow();
		frame.setVisible(visible);
		if (visible) {
			if (SNTUtils.isDebugMode()) logGLDetails();
			gUtils.notifyIfNewVersion(0);
		}
		return frame;
	}

	/**
	 * Resizes the viewer to the specified dimensions. Useful when generating scene
	 * animations programmatically. Does nothing if viewer's frame does not exist.
	 * 
	 * @param width the width of the frame. {@code -1} will set width to its maximum.
	 * @param height the height of the frame. {@code -1} will set height to its maximum.
	 * @see #show(int, int)
	 */
	public void setFrameSize(final int width, final int height) {
		if (frame == null) return;
		if (width == -1 && height == -1) {
			frame.setLocation(0, 0);
			frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
		}
		final int w = (width == 0) ? (int) (ViewerFrame.DEF_WIDTH * Prefs.SCALE_FACTOR) : width;
		final int h = (height == 0) ? (int) (ViewerFrame.DEF_HEIGHT * Prefs.SCALE_FACTOR) : height;
		if (width == -1 ) {
			frame.setExtendedState(JFrame.MAXIMIZED_HORIZ);
			frame.setSize((frame.getWidth()==0) ? w : frame.getWidth(), h);
		} else if (height == -1 ) {
			frame.setExtendedState(JFrame.MAXIMIZED_VERT);
			frame.setSize(w, (frame.getHeight()==0) ? h : frame.getHeight());
		} else {
			frame.setSize(w, h);
		}
	}

	public void setLookAndFeel(final String lookAndFeelName) {
		if (frame == null) return;
		final ArrayList<Component> components = new ArrayList<>();
		components.add(frame);
		if (frame.manager != null)
			components.add(frame.manager);
		if (frame.allenNavigator != null) {
			components.add(frame.allenNavigator.dialog);
		}
		if (managerList != null)
			components.add(managerList.getComponentPopupMenu());
		GuiUtils.setLookAndFeel(lookAndFeelName, false, components.toArray(new Component[0]));
	}

	private void displayBanner(final String msg) {
		GuiUtils.displayBanner(msg, (isDarkModeOn()) ? java.awt.Color.BLACK : java.awt.Color.WHITE,
				(Component) chart.getCanvas());
	}

	private void displayMsg(final String msg) {
		displayMsg(msg, 3000);
	}

	private void displayMsg(final String msg, final int msecs) {
		if (frame != null) {
			SwingUtilities.invokeLater(() -> {
				if (msg == null || msg.isEmpty()) {
					frame.status.setText(frame.statusPlaceHolder);
					return;
				}
				final Timer timer = new Timer(msecs, e -> frame.status.setText(frame.statusPlaceHolder));
				timer.setRepeats(false);
				timer.start();
				frame.status.setText(msg);
			});
		} else {
			System.out.println(msg);
		}
	}

	/**
	 * Returns the Collection of Trees in this viewer.
	 *
	 * @return the rendered Trees (keys being the Tree identifier as per
	 *         {@link #addTree(Tree)})
	 */
	private Map<String, Shape> getTreeDrawables() {
		final Map<String, Shape> map = new HashMap<>();
		plottedTrees.forEach((k, shapeTree) -> {
			map.put(k, shapeTree.get());
		});
		return map;
	}

	private Map<String, Drawable> getAnnotationDrawables() {
		final Map<String, Drawable> map = new HashMap<>();
		plottedAnnotations.forEach((k, annot) -> {
			map.put(k, annot.getDrawable());
		});
		return map;
	}

	/**
	 * Returns the Collection of OBJ meshes imported into this viewer.
	 *
	 * @return the rendered Meshes (keys being the filename of the imported OBJ
	 *         file as per {@link #loadMesh(String, ColorRGB, double)}
	 */
	private Map<String, OBJMesh> getOBJs() {
		final Map<String, OBJMesh> newMap = new LinkedHashMap<>();
		plottedObjs.forEach((k, drawable) -> {
			newMap.put(k, drawable.objMesh);
		});
		return newMap;
	}

	/**
	 * Removes the specified Tree.
	 *
	 * @param tree the tree to be removed.
	 * @return true, if tree was successfully removed.
	 * @see #addTree(Tree)
	 */
	public boolean removeTree(final Tree tree) {
		return removeTree(getLabel(tree));
	}

	/**
	 * Removes the specified Tree.
	 *
	 * @param treeLabel the key defining the tree to be removed.
	 * @return true, if tree was successfully removed.
	 * @see #addTree(Tree)
	 */
	public boolean removeTree(final String treeLabel) {
		final String[] labelAndManagerEntry = TagUtils.getUntaggedAndTaggedLabels(treeLabel);
		return removeTree(labelAndManagerEntry[0], labelAndManagerEntry[1]);
	}

	private boolean removeTree(final String treeLabel, final String managerEntry) {
		final ShapeTree shapeTree = plottedTrees.get(treeLabel);
		if (shapeTree == null) return false;
		boolean removed = plottedTrees.remove(treeLabel) != null;
		if (chart != null) {
			removed = removed && chart.getScene().getGraph().remove(shapeTree.get(),
				viewUpdatesEnabled);
			if (removed) deleteItemFromManager(managerEntry);
		}
		return removed;
	}

	private boolean removeAnnotation(final String annotLabel, final String managerEntry) {
		final Annotation3D annot3D = plottedAnnotations.get(annotLabel);
		if (annot3D == null) return false;
		boolean removed = plottedAnnotations.remove(annotLabel) != null;
		if (chart != null) {
			removed = removed && chart.getScene().getGraph().remove(annot3D.getDrawable(),
				viewUpdatesEnabled);
			if (removed) deleteItemFromManager(managerEntry);
		}
		return removed;
	}

	public boolean removeAnnotation(final String annotLabel) {
		final String[] labelAndManagerEntry = TagUtils.getUntaggedAndTaggedLabels(annotLabel);
		return removeAnnotation(labelAndManagerEntry[0], labelAndManagerEntry[1]);
	}

	public boolean removeAnnotation(final Annotation3D annot) {
		if (plottedAnnotations.containsValue(annot)) {
			chart.remove(annot.getDrawable(), viewUpdatesEnabled);
			deleteItemFromManager(annot.getLabel());
			return true;
		}
		return false;
	}

	/**
	 * Removes the specified OBJ mesh.
	 *
	 * @param mesh the OBJ mesh to be removed.
	 * @return true, if mesh was successfully removed.
	 * @see #loadMesh(String, ColorRGB, double)
	 */
	public boolean removeMesh(final OBJMesh mesh) {
		final String meshLabel = getLabel(mesh);
		return removeDrawable(plottedObjs, meshLabel, meshLabel);
	}

	/**
	 * Removes the specified OBJ mesh.
	 *
	 * @param meshLabel the key defining the OBJ mesh to be removed.
	 * @return true, if mesh was successfully removed.
	 * @see #loadMesh(String, ColorRGB, double)
	 */
	public boolean removeMesh(final String meshLabel) {
		final String[] labelAndManagerEntry = TagUtils.getUntaggedAndTaggedLabels(meshLabel);
		return removeDrawable(plottedObjs, labelAndManagerEntry[0], labelAndManagerEntry[1]);
	}

	private boolean removeMesh(final String meshLabel, final String managerEntry) {
		return removeDrawable(plottedObjs, meshLabel, managerEntry);
	}

	/**
	 * Removes all loaded OBJ meshes from current viewer
	 */
	public void removeAllMeshes() {
		final boolean updateStatus = viewUpdatesEnabled;
		final Iterator<OBJMesh> it = getMeshes().iterator();
		setSceneUpdatesEnabled(false);
		while (it.hasNext()) {
			removeMesh(it.next());
			it.remove();
		}
		setSceneUpdatesEnabled(updateStatus);
		validate();
	}

	/**
	 * Removes all the Trees from current viewer
	 */
	public void removeAllTrees() {
		final boolean updateStatus = viewUpdatesEnabled;
		final Iterator<Tree> it = getTrees().iterator();
		setSceneUpdatesEnabled(false);
		while (it.hasNext()) {
			final Tree tree = it.next();
			removeTree(tree);
			it.remove();
		}
		setSceneUpdatesEnabled(updateStatus);
		validate();
	}

	/**
	 * Removes all the Annotations from current viewer
	 */
	protected void removeAllAnnotations() {
		final boolean updateStatus = viewUpdatesEnabled;
		setSceneUpdatesEnabled(false);
		final Iterator<Entry<String, Annotation3D>> it = plottedAnnotations.entrySet()
			.iterator();
		while (it.hasNext()) {
			final Map.Entry<String, Annotation3D> entry = it.next();
			chart.getScene().getGraph().remove(entry.getValue().getDrawable(), false);
			deleteItemFromManager(entry.getKey());
			it.remove();
		}
		setSceneUpdatesEnabled(updateStatus);
		if (viewUpdatesEnabled) view.shoot();
	}

	private void removeColorLegends(final boolean justLastOne) {
		final List<Drawable> allDrawables = chart.getScene().getGraph()
			.getAll();
		final Iterator<Drawable> iterator = allDrawables.iterator();
		while (iterator.hasNext()) {
			final Drawable drawable = iterator.next();
			if (drawable != null && drawable.hasLegend() && drawable
				.isLegendDisplayed())
			{
				iterator.remove();
				if (justLastOne) break;
			}
		}
		cBar = null;
	}

	private void removeSceneObject(final String label, final String managerEntry) {
		if (!removeTree(label, managerEntry) && (!removeMesh(label, managerEntry))) {
			removeAnnotation(label, managerEntry);
		}
	}

	private void wipeScene() {
		setSceneUpdatesEnabled(false);
		final Dimension dim = frame.getSize();
		setAnimationEnabled(false);
		removeAllTrees();
		removeAllMeshes();
		removeAllAnnotations();
		removeColorLegends(false);
		// Ensure nothing else remains
		chart.getScene().getGraph().getAll().clear();
		if (frame.lightController != null) {
			frame.lightController.dispose();
			frame.lightController = null;
		}
		// Workaround the possibility of a canvas collapse when empty(MacOS only!?)
		if (frame.getSize().height< dim.getSize().height) frame.setSize(dim);
		setSceneUpdatesEnabled(true);
	}

	private boolean isEmptyScene() {
		try {
			return view.getScene().getGraph().getAll().isEmpty();
		} catch (final Exception ignored) {
			return true;
		}
	}

	public void resetView() {
		keyController.resetView();
	}

	/**
	 * Script friendly method to add a supported object ({@link Tree},
	 * {@link OBJMesh}, AbstractDrawable, etc.) to this viewer. Note that
	 * collections of supported objects are also supported, which is an effective
	 * way of adding multiple items since the scene is only rebuilt once all items
	 * have been added.
	 *
	 * @param object the object to be added. No exception is triggered if null
	 * @throws IllegalArgumentException if object is not supported
	 */
	public void add(final Object object) {
		if (object == null) {
			SNTUtils.log("Null object ignored for scene addition");
			return;
		}
		if (object instanceof Tree) {
			addTree((Tree) object);
		} else if (object instanceof Path) {
			final Tree tree = new Tree();
			tree.add((Path) object);
			addTree(tree);
		} else if (object instanceof DirectedWeightedGraph) {
			final Tree tree = ((DirectedWeightedGraph)object).getTree();
			tree.setColor(SNTColor.getDistinctColors(1)[0]);
			addTree(tree);
		} else if (object instanceof Annotation3D) {
			addAnnotation((Annotation3D) object);
		} else if (object instanceof SNTPoint) {
			annotatePoint((SNTPoint) object, null);
		} else if (object instanceof OBJMesh) {
			addMesh((OBJMesh) object);
		} else if (object instanceof String) {
			addLabel((String) object);
		} else if (object instanceof Drawable) {
			chart.add((Drawable) object, viewUpdatesEnabled);
		} else if (object instanceof Collection) {
			addCollection(((Collection<?>) object));
		} else {
			throw new IllegalArgumentException("Unsupported object: " + object.getClass().getName());
		}
	}

	private void addCollection(final Collection<?> collection) {
		final boolean updateStatus = viewUpdatesEnabled;
		final boolean displayProgress = frame != null && frame.managerPanel != null;
		setSceneUpdatesEnabled(false);
		if (displayProgress) {
			addProgressLoad(collection.size());
		}
		for(final Object o : collection) {
			incrementProgress(); // will be inaccurate if collection contains other collections
			add(o);
		}
		removeProgressLoad(collection.size());
		setSceneUpdatesEnabled(updateStatus);
		validate();
	}

	/**
	 * Script friendly method to remove an object ({@link Tree}, {@link OBJMesh},
	 * AbstractDrawable, {@link String} (object label), etc.) from this viewer's scene.
	 *
	 * @param object the object to be removed, or the unique String identifying it.
	 *               Collections supported.
	 * @throws IllegalArgumentException if object is not supported
	 */
	public void remove(final Object object) {
		if (object instanceof Tree) {
			removeTree((Tree) object);
		} else if (object instanceof OBJMesh) {
			removeMesh((OBJMesh) object);
		} else if (object instanceof String) {
			final String[] labelAndManagerEntry = TagUtils.getUntaggedAndTaggedLabels((String)object);
			removeSceneObject(labelAndManagerEntry[0], labelAndManagerEntry[1]);
		} else if (object instanceof Drawable && chart != null) {
			chart.getScene().getGraph().remove((Drawable) object, viewUpdatesEnabled);
		} else if (object instanceof Collection) {
			removeCollection(((Collection<?>) object));
		} else {
			validate();
			throw new IllegalArgumentException("Unsupported object: " + object.getClass().getName());
		}
	}

	private void removeCollection(final Collection<?> collection) {
		final boolean updateStatus = viewUpdatesEnabled;
		setSceneUpdatesEnabled(false);
		for (final Object o : collection) {
			try {
				remove(o);
			} catch (final IllegalArgumentException ignored) {
				// do nothing
			}
		}
		setSceneUpdatesEnabled(updateStatus);
		validate();
	}

	private Set<String> getLabelsCheckedInManager() {
		return managerList.getCheckBoxListSelectedValuesAsSet();
	}

	private <T extends Drawable> boolean allDrawablesRendered(
		final BoundingBox3d viewBounds, final Map<String, T> map,
		final Set<String> selectedKeys)
	{
		final Iterator<Entry<String, T>> it = map.entrySet().iterator();
		while (it.hasNext()) {
			final Map.Entry<String, T> entry = it.next();
			final T drawable = entry.getValue();
			if (drawable instanceof RemountableDrawableVBO && !((RemountableDrawableVBO)drawable).hasMountedOnce())
				return false;
			final BoundingBox3d bounds = drawable.getBounds();
			if (bounds == null || !viewBounds.contains(bounds)) return false;
			if ((selectedKeys.contains(entry.getKey()) && !drawable.isDisplayed())) {
				drawable.setDisplayed(true);
				if (!drawable.isDisplayed()) return false;
			}
		}
		return true;
	}

	private synchronized <T extends Drawable> boolean removeDrawable(
		final Map<String, T> map, final String label, final String managerListEntry)
	{
		final T drawable = map.get(label);
		if (drawable == null) return false;
		boolean removed = map.remove(label) != null;
		if (chart != null) {
			removed = removed && chart.getScene().getGraph().remove(drawable,
				viewUpdatesEnabled);
			if (removed) {
				deleteItemFromManager(managerListEntry);
				if (frame != null && frame.allenNavigator != null)
					frame.allenNavigator.meshRemoved(label);
			}
		}
		return removed;
	}

	/**
	 * (Re)loads the current list of Paths in the Path Manager list.
	 *
	 * @return true, if synchronization was apparently successful, false otherwise
	 * @throws UnsupportedOperationException if SNT is not running
	 */
	public boolean syncPathManagerList() throws UnsupportedOperationException {
		if (SNTUtils.getInstance() == null) throw new IllegalArgumentException(
			"SNT is not running.");
		final Collection<Tree> trees = SNTUtils.getInstance().getPathAndFillManager().getTrees();
		final ShapeTree newShapeTree = new MultiTreeShapeTree(trees);
		if (plottedTrees.containsKey(PATH_MANAGER_TREE_LABEL)) {
			chart.getScene().getGraph().remove(plottedTrees.get(
				PATH_MANAGER_TREE_LABEL).get());
			plottedTrees.replace(PATH_MANAGER_TREE_LABEL, newShapeTree);
		}
		else {
			plottedTrees.put(PATH_MANAGER_TREE_LABEL, newShapeTree);
			addItemToManager(PATH_MANAGER_TREE_LABEL);
			newShapeTree.setDisplayed(true);
		}
		chart.add(newShapeTree.get(), viewUpdatesEnabled);
		updateView();
		return plottedTrees.get(PATH_MANAGER_TREE_LABEL).get().isDisplayed();
	}

	private boolean isValid(final Drawable drawable) {
		return drawable.getBounds() != null && drawable.getBounds().getRange()
			.distanceSq(new Coord3d(0f, 0f, 0f)) > 0f;
	}

	private boolean sceneIsOK() {
		try {
			updateView();
			if (managerList == null) return true;
			// now check that everything is visible
			final Set<String> visibleKeys = getLabelsCheckedInManager();
			final BoundingBox3d viewBounds = chart.view().getBounds();
			return allDrawablesRendered(viewBounds, plottedObjs, visibleKeys) &&
				allDrawablesRendered(viewBounds, getTreeDrawables(), visibleKeys) &&
				allDrawablesRendered(viewBounds, getAnnotationDrawables(), visibleKeys);
		}
		catch (final GLException | ArrayIndexOutOfBoundsException ignored) {
			SNTUtils.log("Upate view failed...");
			return false;
		}

	}

	/** returns true if a drawable was removed */
	@SuppressWarnings("unused")
	private <T extends Drawable> boolean removeInvalid(
		final Map<String, T> map)
	{
		final Iterator<Entry<String, T>> it = map.entrySet().iterator();
		final int initialSize = map.size();
		while (it.hasNext()) {
			final Entry<String, T> entry = it.next();
			if (!isValid(entry.getValue())) {
				if (chart.getScene().getGraph().remove(entry.getValue(), false))
					deleteItemFromManager(entry.getKey());
				it.remove();
			}
		}
		return initialSize > map.size();
	}

	/**
	 * Toggles the visibility of a rendered Tree, a loaded OBJ mesh, or an
	 * annotation.
	 *
	 * @param label   the unique identifier of the Tree (as per
	 *                {@link #addTree(Tree)}), the filename/identifier of the loaded
	 *                OBJ {@link #loadMesh(String, ColorRGB, double)}, or annotation
	 *                label.
	 * @param visible whether the Object should be displayed
	 */
	public void setVisible(final String label, final boolean visible) {
		if (CheckBoxList.ALL_ENTRY.toString().equals(label)) { // recorded command
			if (managerList != null && frame != null && frame.managerPanel != null) {
				SwingUtilities.invokeLater(() -> {
					if (visible)
						managerList.selectAll();
					else
						managerList.selectNone();
				});
			} else {
				chart.getScene().getGraph().getAll().forEach(d -> d.setDisplayed(visible));
			}
		} else {
			final ShapeTree treeShape = plottedTrees.get(label);
			if (treeShape != null)
				treeShape.setDisplayed(visible);
			final DrawableVBO obj = plottedObjs.get(label);
			if (obj != null)
				obj.setDisplayed(visible);
			final Annotation3D annot = plottedAnnotations.get(label);
			if (annot != null)
				annot.getDrawable().setDisplayed(visible);
			if (frame != null && frame.managerPanel != null)
				frame.managerPanel.setVisible(label, visible);
		}
	}

	/**
	 * Runs {@link MultiTreeColorMapper} on the specified collection of
	 * {@link Tree}s.
	 *
	 * @param treeLabels the collection of Tree identifiers (as per
	 *          {@link #addTree(Tree)}) specifying the Trees to be color mapped
	 * @param measurement the mapping measurement e.g.,
	 *          {@link MultiTreeColorMapper#CABLE_LENGTH}
	 *          {@link MultiTreeColorMapper#N_TIPS}, etc.
	 * @param colorTable the mapping color table (LUT), e.g.,
	 *          {@link ColorTables#ICE}), or any other known to LutService
	 * @return the double[] the limits (min and max) of the mapped values
	 */
	public double[] colorCode(final Collection<String> treeLabels,
		final String measurement, final ColorTable colorTable)
	{
		final List<ShapeTree> shapeTrees = new ArrayList<>();
		final List<Tree> trees = new ArrayList<>();
		treeLabels.forEach(label -> {
			final ShapeTree sTree = plottedTrees.get(label);
			if (sTree != null) {
				shapeTrees.add(sTree);
				trees.add(sTree.tree);
			}
		});
		final MultiTreeColorMapper mapper = new MultiTreeColorMapper(trees);
		mapper.map(measurement, colorTable);
		shapeTrees.forEach(ShapeTree::rebuildShape);
		return mapper.getMinMax();
	}

	/**
	 * Toggles the Viewer's animation.
	 *
	 * @param enabled if true animation starts. Stops if false
	 */
	public void setAnimationEnabled(final boolean enabled) {
		if (enabled && frame == null) show(); // TODO: Assume caller wanted scene to be visible when starting animation?
		if (mouseController == null) return;
		if (enabled) mouseController.startThreadController();
		else mouseController.stopThreadController();
	}

	/**
	 * Sets a manual bounding box for the scene. The bounding box determines the
	 * zoom and framing of the scene. Current view point is logged to the Console
	 * when interacting with the Reconstruction Viewer in debug mode.
	 *
	 * @param xMin the X coordinate of the box origin
	 * @param xMax the X coordinate of the box origin opposite
	 * @param yMin the Y coordinate of the box origin
	 * @param yMax the Y coordinate of the box origin opposite
	 * @param zMin the Z coordinate of the box origin
	 * @param zMax the X coordinate of the box origin opposite
	 */
	public void setBounds(final float xMin, final float xMax, final float yMin, final float yMax,
		final float zMin, final float zMax)
	{
		final BoundingBox3d bBox = new BoundingBox3d(xMin, xMax, yMin, yMax, zMin,
			zMax);
		chart.view().setBoundsManual(bBox);
		if (viewUpdatesEnabled) chart.view().shoot();
	}

	/**
	 * Runs {@link TreeColorMapper} on the specified {@link Tree}.
	 *
	 * @param treeLabel the identifier of the Tree (as per {@link #addTree(Tree)})to
	 *          be color mapped
	 * @param measurement the mapping measurement e.g.,
	 *          {@link TreeColorMapper#PATH_ORDER}
	 *          {@link TreeColorMapper#PATH_DISTANCE}, etc.
	 * @param colorTable the mapping color table (LUT), e.g.,
	 *          {@link ColorTables#ICE}), or any other known to LutService
	 * @return the double[] the limits (min and max) of the mapped values
	 */
	public double[] colorCode(final String treeLabel, final String measurement,
		final ColorTable colorTable)
	{
		final ShapeTree treeShape = plottedTrees.get(treeLabel);
		if (treeShape == null) return null;
		return treeShape.colorize(measurement, colorTable);
	}

	public void assignUniqueColors(final Collection<String> treeLabels)
		{
		final List<ShapeTree> shapeTrees = new ArrayList<>();
		treeLabels.forEach(label -> {
			final ShapeTree sTree = plottedTrees.get(label);
			if (sTree != null) {
				shapeTrees.add(sTree);
			}
		});
		final ColorRGB[] colors = SNTColor.getDistinctColors(shapeTrees.size());
		for (int i = 0; i < colors.length; i ++) {
			final ShapeTree shapeTree = shapeTrees.get(i);
			shapeTree.tree.setColor(colors[i]);
			shapeTree.setArborColor(fromColorRGB(colors[i]), -1);
		}
	}

	/**
	 * Renders the scene from a specified camera angle.
	 *
	 * @param viewMode the view mode, e.g., {@link ViewMode#DEFAULT},
	 *          {@link ViewMode#XY} , etc.
	 */
	public void setViewMode(final ViewMode viewMode) {
		if (!chartExists()) {
			throw new IllegalArgumentException("View was not initialized?");
		}
		((AChart) chart).setViewMode(viewMode);
	}

	/**
	 * Renders the scene from a specified camera angle (script-friendly).
	 *
	 * @param viewMode the view mode (case-insensitive): "xy"; "xz"; "yz";
	 *                 "perspective" or "overview"; "default" or "".
	 */
	public void setViewMode(final String viewMode) {
		if (viewMode == null || viewMode.trim().isEmpty()) {
			setViewMode(ViewMode.DEFAULT);
		}
		final String vMode = viewMode.toLowerCase();
		if (vMode.contains("xz") || vMode.contains("side") || vMode.contains("sag")) { // sagittal kept for backwards compatibility
			setViewMode(ViewMode.XZ);
		} else if (vMode.contains("xy") || vMode.contains("top") || vMode.contains("cor")) { // coronal kept for backwards compatibility
			setViewMode(ViewMode.XY);
		} else if (vMode.contains("yz")) {
			setViewMode(ViewMode.YZ);
		} else if (vMode.contains("pers") || vMode.contains("ove")) {
			setViewMode(ViewMode.PERSPECTIVE);
		} else {
			setViewMode(ViewMode.DEFAULT);
		}
	}

	/**
	 * Renders the scene from a specified camera angle using polar coordinates
	 * relative to the center of the scene. Only X and Y dimensions are
	 * required, as the distance to center is automatically computed. Current
	 * view point is logged to the Console by calling {@link #logSceneControls()}.
	 * 
	 * @param r the radial coordinate
	 * @param t the angle coordinate (in radians)
	 */
	public void setViewPoint(final double r, final double t) {
		if (!chartExists()) {
			throw new IllegalArgumentException("View was not initialized?");
		}
		chart.getView().setViewPoint(new Coord3d(r, t, Float.NaN));
		if (viewUpdatesEnabled) chart.getView().shoot();
	}

	/**
	 * Calls {@link #setViewPoint(double, double)} using Cartesian coordinates.
	 * 
	 * @param x the X coordinate
	 * @param y the Y coordinate
	 */
	public void setViewPointCC(final double x, final double y) {
		setViewPoint((float) Math.sqrt(x * x + y * y), (float) Math.atan2(y, x));
	}

	/**
	 * Adds an annotation label to the scene.
	 *
	 * @param label the annotation text
	 * @see #setFont(Font, float, ColorRGB)
	 * @see #setLabelLocation(float, float)
	 */
	public void addLabel(final String label) {
		((AChart)chart).overlayAnnotation.label = label;
	}

	/**
	 * Sets the location for annotation labels
	 *
	 * @param x the x position of the label
	 * @param y the y position of the label
	 */
	public void setLabelLocation(final float x, final float y) {
		((AChart)chart).overlayAnnotation.labelX = x;
		((AChart)chart).overlayAnnotation.labelY = y;
	}

	/**
	 * Sets the font for label annotations
	 *
	 * @param font the font label, e.g.,
	 *          {@code new Font(Font.SANS_SERIF, Font.ITALIC, 20)}
	 * @param angle the angle in degrees for rotated labels
	 * @param color the font color, e.g., {@code org.scijava.util.Colors.ORANGE}
	 */
	public void setFont(final Font font, final float angle, final ColorRGB color) {
		((AChart)chart).overlayAnnotation.setFont(font, angle);
		((AChart)chart).overlayAnnotation.setLabelColor(new java.awt.Color(color.getRed(), color
			.getGreen(), color.getBlue(), color.getAlpha()));
	}

	/**
	 * Saves a snapshot of current scene as a PNG image. Image is saved using a
	 * unique time stamp as a file name in the directory specified in the
	 * preferences dialog or through {@link #setSnapshotDir(String)}
	 *
	 * @return true, if successful
	 */
	public boolean saveSnapshot() {
		final String filename = String.format("RecViewer%s.png", SNTUtils.getTimeStamp());
		final File file = new File(prefs.getSnapshotDir(), filename);
		boolean saved = false;
		try {
			saved = saveSnapshot(file);
		} catch (final IllegalArgumentException | IOException | GLException e) {
			SNTUtils.error("IOException", e);
			saved = false;
		}
		if (currentView == ViewMode.YZ) {
			new Thread(() -> {
				// HACK: current cartesian views may not reflect sensible 'anatomical views'.
				// This is the case with the Allen CCF. While this is not addressed, we can
				// just save a rotated copy of the snapshot. //TODO: Handle this more properly
				final ij.ImagePlus imp = sc.fiji.snt.util.ImpUtils.open(file.getAbsolutePath());
				if (imp != null && ij.IJ.getInstance() != null) {
					ij.IJ.run(imp, "Rotate 90 Degrees Left", "");
					ij.IJ.saveAs(imp, "PNG", file.getAbsolutePath().replace(".png", "_rotated.png"));
				}
			}).start();
		}
		return saved;
	}

	/**
	 * Saves a snapshot of current scene as a PNG image to the specified path.
	 *
	 * @param filePath the absolute path of the destination file
	 * @return true, if file was successfully saved
	 */
	public boolean saveSnapshot(final String filePath) {
		try {
			final File file = new File(filePath);
			final File parent = file.getParentFile();
			if (parent != null && !parent.exists()) parent.mkdirs();
			return saveSnapshot(file);
		} catch (final IllegalArgumentException | IOException e) {
			SNTUtils.error("IOException", e);
			return false;
		}
	}

	/**
	 * Retrieves the current scene as an image
	 *
	 * @return the bitmap image of the current scene
	 */
	public ImagePlus snapshot() {
		return chart.screenshotImp();
	}

	protected boolean saveSnapshot(final File file) throws IllegalArgumentException, IOException {
		if (!chartExists()) {
			throw new IllegalArgumentException("Viewer is not visible");
		}
		SNTUtils.log("Saving snapshot to " + file);
		if (SNTUtils.isDebugMode() && frame != null) {
			logSceneControls(false);
		}
		chart.screenshot(file);
		return true;
	}

	/**
	 * Sets the directory for storing snapshots.
	 *
	 * @param path the absolute path to the new snapshot directory.
	 */
	public void setSnapshotDir(final String path) {
		prefs.snapshotDir = path;
	}

	/**
	 * Loads a Wavefront .OBJ file. Files should be loaded _before_ displaying the
	 * scene, otherwise, if the scene is already visible, {@link #validate()}
	 * should be called to ensure all meshes are visible.
	 *
	 * @param filePath the absolute file path (or URL) of the file to be imported.
	 *          The filename is used as unique identifier of the object (see
	 *          {@link #setVisible(String, boolean)})
	 * @param color the color to render the imported file
	 * @param transparencyPercent the color transparency (in percentage)
	 * @return the loaded OBJ mesh
	 * @throws IllegalArgumentException if filePath is invalid or file does not
	 *           contain a compilable mesh
	 */
	public OBJMesh loadMesh(final String filePath, final ColorRGB color,
		final double transparencyPercent) throws IllegalArgumentException
	{
		final OBJMesh objMesh = new OBJMesh(filePath);
		objMesh.setColor(color, transparencyPercent);
		return loadOBJMesh(objMesh);
	}

	/**
	 * Loads a Wavefront .OBJ file. Should be called before_ displaying the scene,
	 * otherwise, if the scene is already visible, {@link #validate()} should be
	 * called to ensure all meshes are visible.
	 *
	 * @param objMesh the mesh to be loaded
	 * @return true, if successful
	 * @throws IllegalArgumentException if mesh could not be compiled
	 */
	public boolean addMesh(final OBJMesh objMesh) throws IllegalArgumentException {
		return (objMesh != null) && loadOBJMesh(objMesh) != null;
	}

	private OBJMesh loadOBJMesh(final OBJMesh objMesh) {
		setAnimationEnabled(false);
		chart.add(objMesh.drawable, false); // this used to trigger a GLException when true?
		final String label = getUniqueLabel(plottedObjs, "Mesh", objMesh.getLabel());
		plottedObjs.put(label, objMesh.drawable);
		addItemToManager(label);
		if (frame != null && frame.allenNavigator != null) {
			frame.allenNavigator.meshLoaded(label);
		}
		if (objMesh != null && viewUpdatesEnabled) validate();
		return objMesh;
	}

	/**
	 * Loads the surface mesh of a supported reference brain/neuropil. Internet
	 * connection may be required.
	 *
	 * @param template the reference brain to be loaded (case-insensitive). E.g.,
	 *                 "zebrafish" (MP ZBA); "mouse" (Allen CCF); "JFRC2", "JFRC3"
	 *                 "JFRC2018", "FCWB"(adult), "L1", "L3", "VNC" (Drosophila)
	 * 
	 * @return a reference to the loaded mesh
	 * @throws IllegalArgumentException if {@code template} is not recognized
	 * @see AllenUtils
	 * @see VFBUtils
	 * @see ZBAtlasUtils
	 */
	public OBJMesh loadRefBrain(final String template) throws IllegalArgumentException {
		final String normLabel = getNormalizedBrainLabel(template);
		if (normLabel == null) throw new IllegalArgumentException("Not a valid template: "+ template);
		return loadRefBrainInternal(normLabel);
	}

	private OBJMesh loadRefBrainInternal(final String label) throws NullPointerException, IllegalArgumentException {
		if (getOBJs().containsKey(label)) {
			setVisible(label, true);
			if (managerList != null)
				managerList.addCheckBoxListSelectedValue(label, true);
			return plottedObjs.get(label).objMesh;
		}
		OBJMesh objMesh;
		String[] labels = null;
		switch (label) {
		case MESH_LABEL_JFRC2018:
			objMesh = VFBUtils.getRefBrain("jfrc2018");
			labels = VFBUtils.getXYZLabels();
			break;
		case MESH_LABEL_JFRC2:
			objMesh = VFBUtils.getRefBrain("jfrc2");
			labels = VFBUtils.getXYZLabels();
			break;
		case MESH_LABEL_JFRC3:
			objMesh = VFBUtils.getRefBrain("jfrc3");
			labels = VFBUtils.getXYZLabels();
			break;
		case MESH_LABEL_FCWB:
			objMesh = VFBUtils.getRefBrain("fcwb");
			labels = VFBUtils.getXYZLabels();
			break;
		case MESH_LABEL_L1:
			objMesh = VFBUtils.getMesh("VFB_00050000");
			break;
		case MESH_LABEL_L3:
			objMesh = VFBUtils.getMesh("VFB_00049000");
			break;
		case MESH_LABEL_VNS:
			objMesh = VFBUtils.getMesh("VFB_00100000");
			break;
		case MESH_LABEL_ALLEN:
			objMesh = AllenUtils.getRootMesh(null);
			labels = AllenUtils.getXYZLabels();
			break;
		case MESH_LABEL_ZEBRAFISH:
			objMesh = ZBAtlasUtils.getRefBrain();
			labels = ZBAtlasUtils.getXYZLabels();
			break;
		default:
			throw new IllegalArgumentException("Invalid option: " + label);
		}
		objMesh.setLabel(label);
		objMesh.drawable.setColor(getNonUserDefColor());
		if (labels != null) setAxesLabels(labels);
		if (addMesh(objMesh) && viewUpdatesEnabled) validate();
		return objMesh;
	}

	private static String getNormalizedBrainLabel(final String input) {
		switch (input.toLowerCase()) {
		case "jrc2018":
		case "jfrc2018":
		case "jfrc 2018":
		case "jfrctemplate2018":
			return MESH_LABEL_JFRC2018;
		case "jfrc2":
		case "jfrc2010":
		case "jfrctemplate2010":
		case "vfb":
			return MESH_LABEL_JFRC2;
		case "jfrc3":
		case "jfrc2013":
		case "jfrctemplate2013":
			return MESH_LABEL_JFRC3;
		case "fcwb":
		case "flycircuit":
			return MESH_LABEL_FCWB;
		case "l1":
			return MESH_LABEL_L1;
		case "l3":
			return MESH_LABEL_L3;
		case "vns":
			return MESH_LABEL_VNS;
		case "allen":
		case "ccf":
		case "allen ccf":
		case "mouse":
			return MESH_LABEL_ALLEN;
		case "zebrafish":
			return MESH_LABEL_ZEBRAFISH;
		default:
			return null;
		}
	}

	private Color getNonUserDefColor() {
		return (isDarkModeOn()) ? DEF_COLOR : INVERTED_DEF_COLOR;
	}

	protected Color getDefColor() {
		return (defColor == null) ? getNonUserDefColor() : defColor;
	}

	private void logGLDetails() {
		SNTUtils.log("GL capabilities: " + Settings.getInstance().getGLCapabilities().toString());
		SNTUtils.log("Hardware accelerated: " +  Settings.getInstance().isHardwareAccelerated());
	}

	/**
	 * Logs API calls controlling the scene (view point, bounds, etc.) to Script
	 * Recorder (or Console if Script Recorder is not running). Useful for
	 * programmatic control of animations.
	 */
	public void logSceneControls() {
		logSceneControls(false);
	}

	private void logSceneControls(final boolean abortIfRecorderNull) {
		if (abortIfRecorderNull && recorder == null && managerList != null) {
			gUtils.error("Script recorder is not running.");
			return;
		}
		final StringBuilder sb = new StringBuilder();
		if (recorder == null) {
			SNTUtils.log("Logging scene controls:");
			sb.append("\n");
		}
		final HashSet<String> visibleActors = new HashSet<>();
		final HashSet<String> hiddenActors = new HashSet<>();
		plottedTrees.forEach((k, shapeTree) -> {
			if (shapeTree.isDisplayed()) visibleActors.add("\"" + k +"\"");
			else hiddenActors.add("\"" + k +"\"");
		});
		plottedObjs.forEach((k, drawableVBO) -> {
			if (drawableVBO.isDisplayed()) visibleActors.add("\"" + k +"\"");
			else hiddenActors.add("\"" + k +"\"");
		});
		plottedAnnotations.forEach((k, annot) -> {
			if (annot.getDrawable().isDisplayed()) visibleActors.add("\"" + k +"\"");
			else hiddenActors.add("\"" + k +"\"");
		});
		if (frame != null) {
			sb.append("viewer.setFrameSize(");
			sb.append(frame.getWidth()).append(", ").append(frame.getHeight()).append(")");
			sb.append("\n");
		}
		if (currentView == ViewMode.XY) {
			sb.append("viewer.setViewMode(\"xy\")");
		} else {
			final Coord3d viewPoint = view.getViewPoint();
			sb.append("viewer.setViewPoint(");
			sb.append(viewPoint.x).append(", ");
			sb.append(viewPoint.y).append(")");
		}
		sb.append("\n");
		final BoundingBox3d bounds = view.getBounds();
		sb.append("viewer.setBounds(");
		sb.append(bounds.getXmin()).append(", ");
		sb.append(bounds.getXmax()).append(", ");
		sb.append(bounds.getYmin()).append(", ");
		sb.append(bounds.getYmax()).append(", ");
		sb.append(bounds.getZmin()).append(", ");
		sb.append(bounds.getZmax()).append(")");
		sb.append("\n");
		if (recorder == null) {
			if (!visibleActors.isEmpty()) {
				sb.append("Visible objects: ").append(visibleActors.toString());
				sb.append("\n");
			}
			if (!hiddenActors.isEmpty()) {
				sb.append("Hidden  objects: ").append(hiddenActors.toString());
				sb.append("\n");
			}
			System.out.println(sb.toString());
			displayMsg("Scene details output to Console");
		} else {
			if (!visibleActors.isEmpty())
				recorder.recordComment("Visible objects: " + visibleActors.toString());
			if (!hiddenActors.isEmpty())
				recorder.recordComment("Hidden objects: " + hiddenActors.toString());
			recorder.recordCmd(sb.toString());
		}
	}

	private class Debugger extends DebugGLChart3d {

		private Frame frame;

		public Debugger() {
			super(Viewer3D.this.chart, new ViewerFactory().getUpstreamFactory(Viewer3D.this.ENGINE));
			watchViewBounds();
		}

		public void show() {
			final java.awt.Rectangle awtRect = Viewer3D.this.frame.getBounds();
			open(new org.jzy3d.maths.Rectangle((int) awtRect.getWidth() / 2, (int) awtRect.getHeight() / 2));
			for (final java.awt.Frame f : java.awt.Frame.getFrames()) {
				if (f instanceof org.jzy3d.bridge.awt.FrameAWT && f.getTitle().equals("GL Debug")) {
					this.frame = f;
					f.addWindowListener(new WindowAdapter() {
						@Override
						public void windowClosing(final WindowEvent e) {
							setWatchedChart(null);
							setEnableDebugMode(false);
						}
					});
				}
			}
		}

		@SuppressWarnings("unused")
		private Chart getDebugChart() {
			try {
				final java.lang.reflect.Field field = DebugGLChart3d.class.getDeclaredField("debugChart");
				field.setAccessible(true);
				return (Chart) field.get(this);
			} catch (final Exception e) {
				SNTUtils.error(e.getMessage(), e);
			}
			return null;
		}

		private void dispose() {
			setWatchedChart(null);
			if (frame != null)
				frame.dispose();
		}

		private void setWatchedChart(final Chart chart) {
			try {
				final java.lang.reflect.Field field = DebugGLChart3d.class.getDeclaredField("watchedChart");
				field.setAccessible(true);
				field.set(this, chart);
			} catch (final Exception e) {
				SNTUtils.error(e.getMessage(), e);
			}
		}
	}

	/** AWTChart adopting {@link ViewerFactory.AView} */
	private class AChart extends AWTNativeChart {

		private final OverlayAnnotation overlayAnnotation;
		private final Viewer3D viewer;

		public AChart(final Quality quality, final Viewer3D viewer) {
			super(new ViewerFactory().getUpstreamFactory(viewer.ENGINE), quality);
			currentView = ViewMode.DEFAULT;
			addRenderer(overlayAnnotation = new OverlayAnnotation(getView()));
			this.viewer = viewer;
		}

		// see super.setViewMode(mode);
		public void setViewMode(final ViewMode view) {
			// Store current view mode and view point in memory
			currentView.coord = getView().getViewPoint();

			// set jzy3d fields
			if (currentView == ViewMode.XY) {
				previousViewPointTop = currentView.coord;
			}
			else if (currentView == ViewMode.XZ || currentView == ViewMode.YZ || currentView == ViewMode.SIDE) {
				previousViewPointProfile = currentView.coord;
			} else if (currentView == ViewMode.DEFAULT) {
				previousViewPointFree = currentView.coord;
			}

			// Set new view mode and former view point
			if (view == ViewMode.XY || view == ViewMode.TOP) {
				getView().setViewPositionMode(ViewPositionMode.TOP);
			}
			else if (view == ViewMode.XZ || view == ViewMode.YZ || view == ViewMode.SIDE) {
				getView().setViewPositionMode(ViewPositionMode.PROFILE);
			}
			else {
				getView().setViewPositionMode(ViewPositionMode.FREE);
			}
			getView().setViewPoint(view.coord);
			getView().shoot();
			currentView = view;
		}

		boolean isRotationEnabled() {
			return view.getViewMode() != ViewPositionMode.TOP;
		}

		ImagePlus screenshotImp() {
			final Object screen = screenshot();
			if (screen instanceof BufferedImage) {
				return new ImagePlus("RecViewerSnapshot", new ij.process.ColorProcessor((BufferedImage) screen));
			}
			if (chart.getCanvas() instanceof INativeCanvas) {
				final Renderer3d renderer = ((INativeCanvas) chart.getCanvas()).getRenderer();
				if (renderer instanceof AWTRenderer3d) {
					return new ImagePlus("RecViewerSnapshot",
							new ij.process.ColorProcessor(((AWTRenderer3d) renderer).getLastScreenshotImage()));
				}
			}
			// not sure how otherwise convert TextureData to bufferedImage in a simple way
			try {
				final File f = File.createTempFile("rv-screenshot", ".png");
				f.deleteOnExit();
				saveSnapshot(f.getAbsolutePath());
				return ImpUtils.open(f, "RecViewerSnapshot");
			} catch (final IOException ex) {
				throw new IllegalArgumentException("Data could not be temp. written to disk " + ex.getMessage());
			}
		}
	}

	/** AWTColorbarLegend with customizable font/ticks/decimals, etc. */
	private class ColorLegend extends AWTColorbarLegend {

		private final Shape shape;
		private final Font font;
		private final ColorTableMapper mapper;
		private final int precision;

		private ColorLegend(final ColorLegend colorLegend, final Chart chart) {
			super(new Shape(), chart);
			shape = (Shape) drawable;
			this.font = colorLegend.font;
			this.mapper = new ColorTableMapper(colorLegend.mapper.getColorTable(), colorLegend.mapper.getMin(), colorLegend.mapper.getMax());
			shape.setColorMapper(mapper);
			this.precision = colorLegend.precision;
			shape.setLegend(this);
			updateColors();
			if (colorLegend.provider instanceof SmartTickProvider)
				provider = new SmartTickProvider(colorLegend.provider.getSteps());
			else
				provider = new RegularTickProvider(colorLegend.provider.getSteps());
			if (colorLegend.renderer instanceof ScientificNotationTickRenderer)
				renderer = new ScientificNotationTickRenderer(-1 * colorLegend.precision);
			else
				renderer = new FixedDecimalTickRenderer(colorLegend.precision);
			if (imageGenerator == null)
				init();
			imageGenerator.setAWTFont(font);
		}

		public ColorLegend(final ColorTableMapper mapper, final Font font,
			final int steps, final int precision)
		{
			super(new Shape(), chart);
			shape = (Shape) drawable;
			this.font = font;
			this.precision = precision;
			shape.setColorMapper(this.mapper = mapper);
			shape.setLegend(this);
			updateColors();
			provider = (steps < 0) ? new SmartTickProvider(5)
				: new RegularTickProvider(steps);
			renderer = (precision < 0) ? new ScientificNotationTickRenderer(-1 *
				precision) : new FixedDecimalTickRenderer(precision);
			if (imageGenerator == null) init();
			imageGenerator.setAWTFont(font);
		}

		public ColorLegend(final ColorTableMapper mapper) {
			this(mapper, new Font(Font.SANS_SERIF, Font.PLAIN, (int)GuiUtils.uiFontSize()), 5, 2);
		}

		public void update(final double min, final double max, final float fontSize) {
			shape.getColorMapper().setMin(min);
			shape.getColorMapper().setMax(max);
			if (fontSize > 0)
				imageGenerator.setAWTFont(imageGenerator.getAWTFont().deriveFont(fontSize));
			((ColorbarImageGenerator) imageGenerator).setMin(min);
			((ColorbarImageGenerator) imageGenerator).setMax(max);
		}

		public ColorLegend duplicate(final Chart chart) {
			return new ColorLegend(this, chart);
		}

		public Shape get() {
			return shape;
		}

		private void init() {
			initImageGenerator(shape, provider, renderer);
		}

		private void updateColors() {
			setBackground(view.getBackgroundColor());
			setForeground(view.getBackgroundColor().negative());
		}

		@Override
		public void initImageGenerator(final Drawable parent,
			final ITickProvider provider, final ITickRenderer renderer)
		{
			if (shape != null) imageGenerator = new ColorbarImageGenerator(shape
					.getColorMapper(), provider, renderer, font.getSize());
		}

	}

	private static class ColorbarImageGenerator extends AWTColorbarImageGenerator {

		public ColorbarImageGenerator(final ColorMapper mapper,
			final ITickProvider provider, final ITickRenderer renderer,
			final int textSize)
		{
			super(mapper, provider, renderer);
			this.textSize = textSize;
		}

		@Override
		public BufferedImage toImage(final int width, final int height,
			final int barWidth)
		{
			if (barWidth > width) return null;
			BufferedImage image = new BufferedImage(width, height,
				BufferedImage.TYPE_INT_ARGB);
			Graphics2D graphic = image.createGraphics();
			configureText(graphic);
			final int maxWidth = graphic.getFontMetrics().stringWidth(getTickRenderer().format(
				max)) + barWidth + 1;
			// do we have enough space to display labels?
			if (maxWidth > width) {
				graphic.dispose();
				image.flush();
				image = new BufferedImage(maxWidth, height,
					BufferedImage.TYPE_INT_ARGB);
				graphic = image.createGraphics();
				configureText(graphic);
			}
			graphic.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
				RenderingHints.VALUE_ANTIALIAS_ON);
			graphic.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
				RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			drawBackground(width, height, graphic);
			drawBarColors(height, barWidth, graphic);
			drawBarContour(height, barWidth, graphic);
			drawTextAnnotations(height, barWidth, graphic);
			return image;
		}

		private void setMin(final double min) {
			this.min = min;
		}

		private void setMax(final double max) {
			this.max = max;
		}
	}

	// TODO: MouseController is more responsive with FrameAWT?
	private class ViewerFrame extends FrameAWT implements IFrame {

		private static final long serialVersionUID = 1L;
		private static final int DEF_WIDTH = 800;
		private static final int DEF_HEIGHT = 600;
		private final String statusPlaceHolder;

		private AChart chart;
		private Component canvas;
		private JDialog manager;
		private LightController lightController;
		private AllenCCFNavigator allenNavigator;
		private ManagerPanel managerPanel;
		private JLabel status;

		// displays and full screen
		private java.awt.Point loc;
		private Dimension dim;
		private boolean isFullScreen;

		/**
		 * Instantiates a new viewer frame.
		 *
		 * @param chart the chart to be rendered in the frame
		 * @param includeManager whether the "Reconstruction Viewer Manager" dialog
		 *          should be made visible
		 * @param gConfiguration 
		 */
		public ViewerFrame(final AChart chart, final boolean includeManager, final GraphicsConfiguration gConfiguration) {
			this(chart, (int) (DEF_WIDTH * Prefs.SCALE_FACTOR), (int) (DEF_HEIGHT * Prefs.SCALE_FACTOR), includeManager,
					gConfiguration);
		}

		public ViewerFrame(final AChart chart, final int width, final int height, final boolean includeManager,
				final GraphicsConfiguration gConfiguration) {
			super();
			GuiUtils.setLookAndFeel();
			GuiUtils.removeIcon(this);
			final String title = (chart.viewer.isSNTInstance()) ? " (SNT)" : " ("+ chart.viewer.getID() + ")";
			statusPlaceHolder = (includeManager)
					? "Press H (or F1) for Help. Press " + GuiUtils.ctrlKey() + "+Shift+P for Command Palette..."
					: "Press H (or F1) for Help...";
			initialize(chart, new Rectangle(width, height), "Reconstruction Viewer" + title);
			if (PlatformUtils.isLinux()) new MultiDisplayUtil(this);
			if (gConfiguration == null)
				AWTWindows.centerWindow(this);
			else
				AWTWindows.centerWindow(gConfiguration.getBounds(), this);
			//setLocationRelativeTo(null); // ensures frame will not appear in between displays on a multidisplay setup
			setMinimumSize(new Dimension((int) (DEF_WIDTH * .5), (int) (DEF_HEIGHT * .5))); // fix macOS issue in which
																							// frame is collapsed
			if (includeManager) {
				manager = getManager();
				chart.viewer.managerList.selectAll();
				manager.addKeyListener(keyController);
				snapPanelToSide();
			}
			if (gUtils != null) {
				gUtils.setParent(this);
			}
			toFront();
		}

		private void snapPanelToSide() {
			final java.awt.Point parentLoc = getLocation();
			manager.setLocation(parentLoc.x + getWidth() + 5, parentLoc.y);
		}

		public void replaceCurrentChart(final AChart chart) {
			this.chart = chart;
			canvas = (Component) chart.getCanvas();
			removeAll();
			initialize(chart, new Rectangle(getWidth(), getHeight()), getTitle());
		}

		public JDialog getManager() {
			final String title = (chart.viewer.isSNTInstance()) ? "RV Controls" : "RV Controls ("+ chart.viewer.getID() + ")";
			final JDialog dialog = new JDialog(this, title);
			GuiUtils.removeIcon(dialog);
			managerPanel = new ManagerPanel(new GuiUtils(dialog));
			dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
			dialog.addWindowListener(new WindowAdapter() {
				@Override
				public void windowClosing(final WindowEvent e) {
					exitRequested(managerPanel.guiUtils);
				}
				@Override
				public void windowClosed(final WindowEvent e) {
					if (managerPanel != null)
						managerPanel.setDebuggerEnabled(false);
				}
			});
			// dialog.setLocationRelativeTo(this);
			dialog.setMinimumSize(new Dimension(dialog.getMinimumSize().width, getHeight()/2));
			dialog.setPreferredSize(new Dimension(managerPanel.getPreferredSize().width, getHeight() + status.getFont().getSize()));
			dialog.setContentPane(managerPanel);
			managerList.addKeyListener(getCmdFinderKeyAdapter());
			chart.getCanvas().addKeyController(getCmdFinderKeyAdapter());
			dialog.pack();
			return dialog;
		}

		private KeyAdapter getCmdFinderKeyAdapter() {
			return new KeyAdapter() {
				@Override
				public void keyPressed(final KeyEvent ke) {
					if (KeyEvent.VK_ESCAPE == ke.getKeyCode()) {
						if (cmdFinder != null)
							cmdFinder.setVisible(false);
						chart.viewer.abortCurrentOperation = true;
					} else if (cmdFinder != null && KeyEvent.VK_P == ke.getKeyCode() && ke.isShiftDown()
							&& (ke.isControlDown() || ke.isMetaDown())) {
						cmdFinder.toggleVisibility();
					}
				}
			};
		}

		private void displayLightController(final AbstractEnlightable abstractEnlightable ) {
			lightController = new LightController(this, abstractEnlightable);
			lightController.display();
		}

		private void exitRequested(final GuiUtils gUtilsDefiningPrompt) {
			if (gUtilsDefiningPrompt != null && gUtilsDefiningPrompt.getConfirmation("Quit Reconstruction Viewer?",
					"Quit?", "Yes. Quit Now", "No. Keep Open")) {
				super.dispose();
				chart.viewer.dispose();
				chart = null;
				GuiUtils.restoreLookAndFeel();
			}
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see org.jzy3d.bridge.awt.FrameAWT#initialize(org.jzy3d.chart.Chart,
		 * org.jzy3d.maths.Rectangle, java.lang.String)
		 */
		@Override
		public void initialize(final Chart chart, final Rectangle bounds, final String title) {
			this.chart = (AChart)chart;
			canvas = (Component) chart.getCanvas();
			setTitle(title);
			final BorderLayout layout = new BorderLayout();
			setLayout(layout);
			add(canvas, BorderLayout.CENTER);
			add(status = new JLabel(statusPlaceHolder), BorderLayout.SOUTH);
			status.setFocusable(false);
			status.setBackground(toAWTColor(chart.view().getBackgroundColor()));
			status.setForeground(toAWTColor(chart.view().getBackgroundColor().negative()));
			setBackground(status.getBackground());
			pack();
			canvas.setPreferredSize(new Dimension(bounds.width, bounds.height));
			setPreferredSize(new Dimension(bounds.width, bounds.height + status.getHeight()));
			canvas.setSize(new Dimension(bounds.width, bounds.height));
			setSize(new Dimension(bounds.width, bounds.height + status.getHeight()));
			addWindowListener(new WindowAdapter() {
				@Override
				public void windowClosing(final WindowEvent e) {
					exitRequested(gUtils);
				}
			});
		}

		public void disposeFrame() {
			if (chart != null) {
				chart.stopAnimation();
				chart.dispose();
				chart = null;
			}
			remove(canvas);
			canvas = null;
			if (manager != null) {
				manager.dispose();
				manager = null;
			}
			if (recorder != null) {
				recorder.dispose();
				recorder = null;
			}
			if (lightController != null) {
				lightController.dispose();
				lightController = null;
			}
			if (allenNavigator != null) {
				allenNavigator.dispose();
				allenNavigator = null;
			}
			dispose();
			managerPanel = null;
			dim = null;
			loc = null;
			status = null;
		}

		/* (non-Javadoc)
		 * @see org.jzy3d.bridge.awt.FrameAWT#initialize(org.jzy3d.chart.Chart, org.jzy3d.maths.Rectangle, java.lang.String, java.lang.String)
		 */
		@Override
		public void initialize(final Chart chart, final Rectangle bounds,
			final String title, final String message)
		{
			initialize(chart, bounds, title + message);
		}

		void exitFullScreen() {
			if (isFullScreen) {
				setExtendedState(JFrame.NORMAL);
				setSize(dim);
				setLocation(loc);
				setVisible(true);
				if (lightController != null) lightController.setVisible(true);
				if (allenNavigator != null) allenNavigator.dialog.setVisible(true);
				isFullScreen = false;
			}
		}

		void enterFullScreen() {
			if (!isFullScreen) {
				dim = frame.getSize();
				loc = frame.getLocation();
				if (manager != null) manager.setVisible(false);
				if (lightController != null) lightController.setVisible(false);
				if (allenNavigator != null) allenNavigator.dialog.setVisible(false);
				frame.status.setVisible(false);
				setExtendedState(JFrame.MAXIMIZED_BOTH );
				isFullScreen = true;
				displayBanner("Entered Full Screen. Press Shift+F (or \"Esc\") to exit...");
			}
		}

		@Override
		public void setVisible(final boolean b) {
			setVisible(b, manager != null);
		}

		private void setVisible(final boolean b, final boolean managerVisible) {
			SNTUtils.setIsLoading(false);
			super.setVisible(b);
			if (manager != null) manager.setVisible(managerVisible);
		}
	}

	/* Workaround for nasty libx11 bug on linux */
	private class MultiDisplayUtil extends ComponentAdapter {

		final ViewerFrame frame;
		GraphicsConfiguration conf;
		GraphicsDevice curDisplay;
		GraphicsEnvironment ge;
		GraphicsDevice[] allDisplays;
		int currentDisplayIndex;

		MultiDisplayUtil(final ViewerFrame frame) {
			this.frame = frame;
			frame.addComponentListener(this);
			warnOnX11Bug();
			setCurrentDisplayIndex();
		}

		GraphicsDevice getCurrentDisplay() {
			ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
			conf = frame.getGraphicsConfiguration();
			curDisplay = conf.getDevice();
			return curDisplay;
		}

		GraphicsDevice[] getAllDisplays() {
			ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
			allDisplays = ge.getScreenDevices();
			return allDisplays;
		}

		boolean multipleDisplaysAvailable() {
			getAllDisplays();
			return allDisplays.length > 1;
		}

		private void setCurrentDisplayIndex() {
			currentDisplayIndex = 0;
			getAllDisplays();
			for (int i = 0; i < allDisplays.length; i++) {
				if (allDisplays[i].equals(curDisplay)) {
					currentDisplayIndex = i;
					break;
				}
			}
		}

		@Override
		public void componentMoved(final ComponentEvent evt) {
			if (!multipleDisplaysAvailable()) {
				return;
			}
			getCurrentDisplay();
			// https://stackoverflow.com/a/42529925
			for (int i = 0; i < allDisplays.length; i++) {
				if (allDisplays[i].equals(curDisplay)) {
					// the window has been dragged to another display
					if (i != currentDisplayIndex) {
						rebuildOnNewScreen(curDisplay.getDefaultConfiguration());
						currentDisplayIndex = i;
					}
				}
			}
		}

		void rebuildOnNewScreen(final GraphicsConfiguration gConfiguration) {
			final Dimension dim = frame.getSize();
			final boolean hasManager = frame.manager != null;
			final boolean visibleManager = hasManager && frame.manager.isVisible();
			final boolean darkMode = isDarkModeOn();
			frame.dispose();
			if (hasManager) {
				frame.manager.dispose();
				initManagerList();
			}
			show((int) dim.getWidth(), (int) dim.getHeight(), gConfiguration, true);
			setEnableDarkMode(darkMode);
			if (hasManager) {
				frame.snapPanelToSide();
				frame.manager.setVisible(visibleManager);
			}
			validate();
		}

		void warnOnX11Bug() {
			if (multipleDisplaysAvailable()) {
				System.out.println("*** Warning ***");
				System.out.println("In some X11 installations dragging the Reconstruction Viewer window");
				System.out.println("into a secondary display may freeze the entire UI. There is a workaround");
				System.out.println("in place but please avoid repeteaded movements of windows across displays.");
			}
		}
	}

	private static class Prefs {

		/* Pan accuracy control */
		private enum PAN {
				LOW(.25f, "Low"), //
				MEDIUM(.5f, "Medium"), //
				HIGH(1f, "High"), //
				HIGHEST(2.5f, "Highest");

			private static final float DEF_PAN_STEP = 1f;
			private final float step;
			private final String description;

			// the lowest the step the more responsive the pan
			PAN(final float step, final String description) {
				this.step = step;
				this.description = description;
			}
	
			// the highest the normalized step the more responsive the pan
			private static float getNormalizedPan(final float step) {
				return (HIGHEST.step + LOW.step - step) / (HIGHEST.step - LOW.step);
			}
		}

		/* Zoom control */
		private static final float[] ZOOM_STEPS = new float[] { .01f, .05f, .1f,
			.2f };
		private static final float DEF_ZOOM_STEP = ZOOM_STEPS[1];

		/* Rotation control */
		private static final double[] ROTATION_STEPS = new double[] { Math.PI / 180,
			Math.PI / 36, Math.PI / 18, Math.PI / 6 }; // 1, 5, 10, 30 degrees
		private static final double DEF_ROTATION_STEP = ROTATION_STEPS[1];

		/* GUI */
		private static final double SCALE_FACTOR = ij.Prefs.getGuiScale();
		private static final boolean DEF_NAG_USER_ON_RETRIEVE_ALL = true;
		private static final String DEF_TREE_COMPARTMENT_CHOICE = "Axon";
		private static final boolean DEF_RETRIEVE_ALL_IF_NONE_SELECTED = true;
		private static final boolean DEF_SPLIT_DENDRITES_FROM_AXONS = false;
		private boolean splitDendritesFromAxons;

		protected boolean nagUserOnRetrieveAll;
		protected boolean nagUserOnAxesChanges;
		protected boolean retrieveAllIfNoneSelected;
		protected String treeCompartmentChoice;
		protected String snapshotDir;

		private final Viewer3D tp;
		private final KeyController kc;
		private final MouseController mc;
		private String storedSensitivity;

		public Prefs(final Viewer3D tp) {
			this.tp = tp;
			kc = tp.keyController;
			mc = tp.mouseController;
		}

		private void setPreferences() {
			splitDendritesFromAxons = DEF_SPLIT_DENDRITES_FROM_AXONS;
			nagUserOnRetrieveAll = DEF_NAG_USER_ON_RETRIEVE_ALL;
			nagUserOnAxesChanges = DEF_NAG_USER_ON_RETRIEVE_ALL;
			retrieveAllIfNoneSelected = DEF_RETRIEVE_ALL_IF_NONE_SELECTED;
			treeCompartmentChoice = DEF_TREE_COMPARTMENT_CHOICE;
			try {
				setSnapshotDirectory();
				kc.zoomStep = getZoomStep();
				kc.rotationStep = getRotationStep();
				mc.panStep = getPanStep();
				storedSensitivity = null;
			} catch (final Throwable ignored) {
				kc.zoomStep = DEF_ZOOM_STEP;
				kc.rotationStep = DEF_ROTATION_STEP;
				mc.panStep = PAN.DEF_PAN_STEP;
				snapshotDir = RecViewerPrefsCmd.DEF_SNAPSHOT_DIR;
			}
		}

		private File getSnapshotDir() {
			if (snapshotDir == null)
				snapshotDir = RecViewerPrefsCmd.DEF_SNAPSHOT_DIR;
			final File file = new File(snapshotDir);
			if (!file.exists() || !file.isDirectory())
				file.mkdirs();
			return file;
		}

		private void setSnapshotDirectory() {
			snapshotDir = (tp.prefService == null) ? RecViewerPrefsCmd.DEF_SNAPSHOT_DIR
			: tp.prefService.get(RecViewerPrefsCmd.class, "snapshotDir",
				RecViewerPrefsCmd.DEF_SNAPSHOT_DIR);
//			final File dir = new File(snapshotDir);
//			if (!dir.exists() || !dir.isDirectory()) dir.mkdirs();
		}

		private float getSnapshotRotationAngle() {
			return tp.prefService.getFloat(RecViewerPrefsCmd.class, "rotationAngle",
				RecViewerPrefsCmd.DEF_ROTATION_ANGLE);
		}

		private String getScriptExtension() {
			return tp.prefService.get(RecViewerPrefsCmd.class,
					"scriptExtension", RecViewerPrefsCmd.DEF_SCRIPT_EXTENSION);
		}

		private int getFPS() {
			return tp.prefService.getInt(RecViewerPrefsCmd.class,
					"rotationFPS", RecViewerPrefsCmd.DEF_ROTATION_FPS);
		}

		private int getSnapshotRotationSteps() {
			final double duration = tp.prefService.getDouble(RecViewerPrefsCmd.class,
				"rotationDuration", RecViewerPrefsCmd.DEF_ROTATION_DURATION);
			return (int) Math.round(getFPS() * duration);
		}

		private String getControlsSensitivity() {
			return (storedSensitivity == null) ? tp.prefService.get(
				RecViewerPrefsCmd.class, "sensitivity",
				RecViewerPrefsCmd.DEF_CONTROLS_SENSITIVITY) : storedSensitivity;
		}

		private float getPanStep() {
			switch (getControlsSensitivity()) {
				case "Highest":
					return PAN.HIGHEST.step;
				case "Hight":
					return PAN.HIGH.step;
				case "Medium":
					return PAN.MEDIUM.step;
				case "Low":
					return PAN.LOW.step;
				default:
					return PAN.DEF_PAN_STEP;
			}
		}

		private float getZoomStep() {
			switch (getControlsSensitivity()) {
				case "Highest":
					return ZOOM_STEPS[0];
				case "Hight":
					return ZOOM_STEPS[1];
				case "Medium":
					return ZOOM_STEPS[2];
				case "Low":
					return ZOOM_STEPS[3];
				default:
					return DEF_ZOOM_STEP;
			}
		}

		private double getRotationStep() {
			switch (getControlsSensitivity()) {
				case "Highest":
					return ROTATION_STEPS[0];
				case "Hight":
					return ROTATION_STEPS[1];
				case "Medium":
					return ROTATION_STEPS[2];
				case "Low":
					return ROTATION_STEPS[3];
				default:
					return DEF_ROTATION_STEP;
			}
		}

		void setGuiPref(final String key, final String value) {
				if (tp.prefService == null)
					throw new IllegalArgumentException("setGuiPref requires a context-aware Viewer3D instance");
				tp.prefService.put(RecViewerPrefsCmd.class, key, value);
		}

		String getGuiPref(final String key, final String defaultValue) {
			if (tp.prefService == null)
				throw new IllegalArgumentException("setGuiPref requires a context-aware Viewer3D instance");
			return tp.prefService.get(RecViewerPrefsCmd.class, key, defaultValue);
		}

		boolean isSplitDendritesFromAxons() {
			return splitDendritesFromAxons;
		}

		void setSplitDendritesFromAxons(final boolean splitDendritesFromAxons) {
			this.splitDendritesFromAxons = splitDendritesFromAxons;
		}

	}

	class AnnotPrompt {

		PointInImage pim1;
		PointInImage pim2;
		private double size;
		String color;
		private float t;

		boolean validPrompt(final String title, final String[] firstCcLabel, final String[] secondCcLabel, final boolean includeSize) {
            List<String> labels = new ArrayList<>(Arrays.asList(firstCcLabel));
			if (secondCcLabel != null)
				labels.addAll(Arrays.asList(secondCcLabel));
			if (includeSize)
				labels.add("Size ");
			labels.addAll(List.of("Color ", "Transparency (%) "));
            List<String> values = new ArrayList<>(List.of(prefs.getGuiPref("aX1", "0"), prefs.getGuiPref("aY1", "0"),
                    prefs.getGuiPref("aZ1", "0")));
			if (secondCcLabel != null)
				values.addAll(List.of(prefs.getGuiPref("aX2", "0"), prefs.getGuiPref("aY2", "0"),
						prefs.getGuiPref("aZ2", "0")));
			if (includeSize)
				values.add(prefs.getGuiPref("aSize", SNTUtils.formatDouble(getDefaultThickness() * 30, 2))); // size
			values.add(prefs.getGuiPref("aColor", "red")); // color
			values.add(prefs.getGuiPref("aTransparency", "50")); // transparency
			final String[] result = getManagerPanel().guiUtils.getStrings(title, labels.toArray(new String[0]),
					values.toArray(new String[0]));
			if (result == null)
				return false;
			try {
				pim1 = new PointInImage(Double.parseDouble(result[0]), Double.parseDouble(result[1]),
						Double.parseDouble(result[2]));
				prefs.setGuiPref("aX1", result[0]);
				prefs.setGuiPref("aY1", result[1]);
				prefs.setGuiPref("aZ1", result[2]);
				if (secondCcLabel != null) {
					pim2 = new PointInImage(Double.parseDouble(result[3]), Double.parseDouble(result[4]),
							Double.parseDouble(result[5]));
					prefs.setGuiPref("aX2", result[3]);
					prefs.setGuiPref("aY2", result[4]);
					prefs.setGuiPref("aZ2", result[5]);
				}
				if (includeSize) {
					size = Double.parseDouble(result[result.length - 3]);
					prefs.setGuiPref("aSize", result[result.length - 3]);
				}
				color = result[result.length - 2];
				prefs.setGuiPref("aColor", result[result.length - 2]);
				t = Float.parseFloat(result[result.length - 1]);
				prefs.setGuiPref("aTransparency", result[result.length - 1]);
				return true;
			} catch (final Exception ex) {
				getManagerPanel().guiUtils.error("Invalid parameter(s).");
				ex.printStackTrace();
			}
			return false;
		}

		Double getPromptTransparency(final String title) {
			final double def = Double.parseDouble(prefs.getGuiPref("aTransparency", "50"));
			final Double t = getManagerPanel().guiUtils.getDouble("Transparency (%)", title + " Transparency", def);
			if (t == null)
				return null;
			if (Double.isNaN(t) || t < 0 || t > 100) {
				getManagerPanel().guiUtils.error("Invalid transparency value: Only [0, 100] accepted.");
				return null;
			}
			prefs.setGuiPref("aTransparency", "" + t);
			return t;
		}

		ColorRGB getPromptColor(final String title) {
			final ColorRGB def = ColorRGB.fromHTMLColor(prefs.getGuiPref("aColor", "red"));
			final ColorRGB res = getManagerPanel().guiUtils.getColorRGB(title, def, (String[])null);
			if (res != null)
				prefs.setGuiPref("aColor", res.toHTMLColor());
			return res;
		}
		
		String[] getPromptGradient(final String title) {
			final Map<String, List<String>> map = new LinkedHashMap<>();
			map.put("Color gradient ", Annotation3D.COLORMAPS);
			map.put("Gradient axis  ", List.of("X", "Y", "Z"));
			final String[] res = getManagerPanel().guiUtils.getStrings("", "Color Gradient", map,
					prefs.getGuiPref("aGradient", "hotcold"), prefs.getGuiPref("aAxis", "Z"));
			if (res != null) {
				prefs.setGuiPref("aGradient", res[0]);
				prefs.setGuiPref("aAxis", res[1]);
			}
			return res;
		}

		List<String> getPromptPlaneAxes(final boolean includeSomaChoices) {
			final List<String> res = getManagerPanel().guiUtils.getMultipleChoices("Cross-section Plane Axis...",
					(includeSomaChoices)
							? new String[] { "X (midplane)", "Y (midplane)", "Z (midplane)", "X (at root/soma)", "Y (at root/soma)", "Z (at root/soma)" }
							: new String[] { "X (midplane)", "Y (midplane)", "Z (midplane)" },
					prefs.getGuiPref("pAxis", "Z (midplane)"));
			if (res == null)
				return null;
			prefs.setGuiPref("pAxis", res.get(0));
			return res;
		}
		
		PointInImage[] getPlane(final BoundingBox3d bounds, final String axis) {
			PointInImage p1;
			PointInImage p2;
			switch ( axis.split(" ")[0].toLowerCase()) {
			case "x":
				p1 = new PointInImage((bounds.getXmin() + bounds.getXmax()) / 2, bounds.getYmin(), bounds.getZmin());
				p2 = new PointInImage((bounds.getXmin() + bounds.getXmax()) / 2, bounds.getYmax(), bounds.getZmax());
				break;
			case "y":
				p1 = new PointInImage(bounds.getXmin(), (bounds.getYmin() + bounds.getYmax()) / 2, bounds.getZmin());
				p2 = new PointInImage(bounds.getXmax(), (bounds.getYmin() + bounds.getYmax()) / 2, bounds.getZmax());
				break;
			default: // "z"
				p1 = new PointInImage(bounds.getXmin(), bounds.getYmin(), (bounds.getZmin() + bounds.getZmax()) / 2);
				p2 = new PointInImage(bounds.getXmax(), bounds.getYmax(), (bounds.getZmin() + bounds.getZmax()) / 2);
				break;
			}
			return new PointInImage[] { p1, p2 };
		}
		
		PointInImage[] getSomaPlane(final BoundingBox3d bounds, final Tree tree, final String axis) {
			PointInImage p1;
			PointInImage p2;
			final PointInImage root = tree.getRoot();
			switch (axis.split(" ")[0].toLowerCase()) {
			case "x":
				p1 = new PointInImage(root.x, bounds.getYmin(), bounds.getZmin());
				p2 = new PointInImage(root.x, bounds.getYmax(), bounds.getZmax());
				break;
			case "y":
				p1 = new PointInImage(bounds.getXmin(), root.y, bounds.getZmin());
				p2 = new PointInImage(bounds.getXmax(), root.y, bounds.getZmax());
				break;
			default: // "z"
				p1 = new PointInImage(bounds.getXmin(), bounds.getYmin(), root.z);
				p2 = new PointInImage(bounds.getXmax(), bounds.getYmax(), root.z);
				break;
			}
			return new PointInImage[] { p1, p2 };
		}

	}

	/**
	 * Returns a reference to 'RV Controls' panel.
	 *
	 * @return the ManagerPanel associated with this Viewer, or null if the 'RV
	 *         Controls' dialog is not being displayed.
	 */
	public ManagerPanel getManagerPanel() {
		return (frame == null) ? null : frame.managerPanel;
	}

	public ScriptRecorder getRecorder(final boolean createIfNeeded) {
		if (recorder == null && createIfNeeded) {
			recorder = new ScriptRecorder();
			recorder.setTitle("Reconstruction Viewer Script Recorder");
			recorder.setLanguage(prefs.getScriptExtension());
			cmdFinder.setRecorder(recorder);
			recorder.addWindowListener(new WindowAdapter() {
				@Override
				public void windowClosed(final WindowEvent e) {
					recorder = null;
					if (cmdFinder != null) cmdFinder.setRecorder(null);
				}
			});
		}
		return recorder;
	}

	public class ManagerPanel extends JPanel {

		private static final long serialVersionUID = 1L;
		private final GuiUtils guiUtils;
		private SNTTable table;
		private JCheckBoxMenuItem debugCheckBox;
		private final SNTSearchableBar searchableBar;
		private final ProgressBar progressBar;
		private Debugger debugger;
		private boolean disableActions;


		private ManagerPanel(final GuiUtils guiUtils) {
			super();
			this.guiUtils = guiUtils;
			setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
			searchableBar = new SNTSearchableBar(new ListSearchable(managerList));
			searchableBar.setGuiUtils(guiUtils);
			searchableBar.setVisibleButtons(//SNTSearchableBar.SHOW_CLOSE |
				SNTSearchableBar.SHOW_NAVIGATION | SNTSearchableBar.SHOW_HIGHLIGHTS |
				SNTSearchableBar.SHOW_SEARCH_OPTIONS | SNTSearchableBar.SHOW_STATUS);
			setFixedHeight(searchableBar);
			searchableBar.setVisible(false);
			searchableBar.setInstaller(new SearchableBar.Installer() {

				@Override
				public void openSearchBar(final SearchableBar searchableBar) {
					if (ManagerPanel.this.getWidth() < searchableBar.getWidth())
						ManagerPanel.this.setSize(searchableBar.getWidth(), ManagerPanel.this.getHeight());
					searchableBar.setVisible(true);
					searchableBar.focusSearchField();
				}

				@Override
				public void closeSearchBar(final SearchableBar searchableBar) {
					searchableBar.setVisible(false);
					if (ManagerPanel.this.getPreferredSize().width < searchableBar.getWidth())
						ManagerPanel.this.setSize(ManagerPanel.this.getPreferredSize().width, ManagerPanel.this.getHeight());
				}
			});
			searchableBar.getSearchField().addFocusListener(new FocusListener() {

				@Override
				public void focusGained(final FocusEvent e) {
					disableActions = true;
				}

				@Override
				public void focusLost(final FocusEvent e) {
					disableActions = false;
				}

			});
			final JScrollPane scrollPane = new JScrollPane(managerList);
			managerList.setComponentPopupMenu(popupMenu());
			scrollPane.setWheelScrollingEnabled(true);
			scrollPane.setViewportView(managerList);
			add(scrollPane);
			scrollPane.revalidate();
			add(searchableBar);
			searchableBar.setBackground(managerList.getBackground());
			progressBar = new ProgressBar();
			add(progressBar);
			add(buttonPanel());
			fileDropWorker = new FileDropWorker(managerList, guiUtils);
		}

		private JCheckBoxMenuItem getDebugCheckBox() {
			debugCheckBox = new JCheckBoxMenuItem("Debug Mode", SNTUtils.isDebugMode() && debugger != null);
			debugCheckBox.setEnabled(!isSNTInstance());
			debugCheckBox.setIcon(IconFactory.getMenuIcon(GLYPH.STETHOSCOPE));
			debugCheckBox.setMnemonic('d');
			debugCheckBox.addItemListener(e -> {
				final boolean debug = debugCheckBox.isSelected();
				if (isSNTInstance()) {
					SNTUtils.getInstance().getUI().setEnableDebugMode(debug);
				} else {
					SNTUtils.setDebugMode(debug);
				}
				if (debug) {
					switch(ENGINE) {
					case JOGL:
						logGLDetails();
						setDebuggerEnabled(debug);
						break;
					default:
						SNTUtils.log("Rendering engine: " +  ENGINE.toString());
					}
				}
			});
			return debugCheckBox;
		}

		private void setDebuggerEnabled(final boolean enable ) {
			if (enable && Engine.JOGL.equals(ENGINE)) {
				if (debugger == null) {
					debugger = new Debugger();
					debugger.show();
				} else {
					debugger.setWatchedChart(chart);
				}
			} else if (debugger != null) {
				debugger.dispose();
				debugger = null;
			}
			debugCheckBox.setSelected(enable);
			SNTUtils.setDebugMode(enable);
		}

	 	/** Updates the progress bar. */
		public void showProgress(final int value, final int maximum) {
			SwingUtilities.invokeLater( () -> {
				if (value == -1 && maximum == -1) {
					progressBar.setIndeterminate(true);
				} else {
					progressBar.setIndeterminate(false);
					progressBar.addToGlobalMax(maximum);
					progressBar.addToGlobalValue(value);
				}
			});
		}

		class ProgressBar extends JProgressBar {

			private static final long serialVersionUID = 1L;
			private int globalValue;
			private int globalMax;

			ProgressBar() {
				super();
				setStringPainted(true);
				setFocusable(false);
				reset();
				addMouseListener(new MouseAdapter() {
					@Override
					public void mousePressed(final MouseEvent e) {
						if (e.getClickCount() == 2 && isIndeterminate())
							showProgress(0, 0);
					}
				});
			}

			private void addToGlobalValue(final int increment) {
				globalValue = globalValue + increment;
				if (globalValue < 0 || globalValue > globalMax) {
					reset();
					return;
				}
				super.setValue(globalValue);
			}

			@Override
			public void setMinimum(final int ignored) {
				super.setMinimum(0);
			}

			@Override
			public int getMinimum() {
				return 0;
			}

			@Override
			public int getMaximum() {
				return globalMax;
			}

			@Override
			public int getValue() {
				return globalValue;
			}

			@Override
			public boolean isIndeterminate() {
				return super.isIndeterminate() || globalMax < 0;
			}
	
			@Override
			public String getString() {
				if (isIndeterminate())
					return "Background task...";
				if (globalMax == 0)
					return " ";
				return super.getString();
			}

			public void addToGlobalMax(final int increment) {
				globalMax = globalMax + increment;
				if (globalMax < 1) {
					reset();
				} else {
					setMaximum(globalMax);
				}
			}

			private void reset() {
				globalValue = 0;
				globalMax = 0;
				setValue(0);
				setMinimum(0);
				setMaximum(0);
			}
		}

		class Action extends AbstractAction {
			static final String ALL = "All";
			static final String AXES_TOGGLE = "Toggle Axes";
			static final String RESIZE = "Viewer Size...";
			static final String ENTER_FULL_SCREEN = "Full Screen";
			static final String FIND = "Toggle Selection Toolbar";
			static final String PROGRESS = "Toggle Progress Bar";
			static final String FIT = "Fit to Visible Objects";
			static final String LOG_TO_RECORDER = "Log Scene Details to Recorder";
			static final String NONE = "None";
			static final String REBUILD = "Rebuild Scene...";
			static final String RELOAD = "Reload Scene";
			static final String RESET_VIEW = "Reset View";
			static final String SCENE_SHORTCUTS_LIST = "Scene Shortcuts...";
			static final String SCENE_SHORTCUTS_NOTIFICATION = "Scene Shortcuts (Notification)...";
			static final String RECORDER = "Record Script... (Experimental)";
			static final String SNAPSHOT_DISK = "Take Snapshot & Save to Disk";
			static final String SNAPSHOT_SHOW = "Take Snapshot & Display";
			static final String SYNC = "Sync Path Manager Changes";
			static final String TAG = "Add Tag(s)...";
			static final String TOGGLE_DARK_MODE = "Toggle Dark Mode";
			static final String TOGGLE_CONTROL_PANEL= "Toggle RV Controls";
			private static final long serialVersionUID = 1L;
			final String name;

			Action(final String name) {
				super(name);
				this.name = name;
			}

			Action(final String name, final int key, final boolean requireCtrl, final boolean requireShift) {
				this(name);
				if (key != KeyEvent.VK_UNDEFINED) {
					int mod = 0;
					if (requireCtrl)
						mod = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
					if (requireShift)
						mod |= KeyEvent.SHIFT_DOWN_MASK;
					final KeyStroke ks = KeyStroke.getKeyStroke(key, mod);
					putValue(AbstractAction.ACCELERATOR_KEY, ks);
					if (mod == 0)
						putValue(AbstractAction.MNEMONIC_KEY, key);
					// register action in panel
					registerKeyboardAction(this, ks, JComponent.WHEN_IN_FOCUSED_WINDOW);
				}
			}

			@Override
			public void actionPerformed(final ActionEvent e) {
				if (disableActions) return;
				switch (name) {
				case ALL:
					showPanelAsNeeded();
					managerList.selectAll();
					return;
				case AXES_TOGGLE:
					if (!keyController.emptySceneMsg()) keyController.toggleAxes();
					return;
				case FIND:
					showPanelAsNeeded();
					if (searchableBar.isShowing()) {
						searchableBar.getInstaller().closeSearchBar(searchableBar);
					} else {
						searchableBar.getInstaller().openSearchBar(searchableBar);
						searchableBar.focusSearchField();
					}
					return;
				case PROGRESS:
					showPanelAsNeeded();
					progressBar.setVisible(!progressBar.isShowing());
					return;
				case ENTER_FULL_SCREEN:
					frame.enterFullScreen();
					return;
				case RESIZE:
					guiUtils.adjustComponentThroughPrompt(frame);
					return;
				case FIT:
					fitToVisibleObjects(true, true);
					return;
				case LOG_TO_RECORDER:
					logSceneControls(true);
					break;
				case NONE:
					showPanelAsNeeded();
					managerList.clearSelection();
					return;
				case REBUILD:
					if (guiUtils.getConfirmation("Rebuild 3D Scene Completely?", "Force Rebuild")) {
						rebuild();
					}
					return;
				case RELOAD:
					if (!sceneIsOK()
							&& guiUtils.getConfirmation("Scene was reloaded but some objects have invalid attributes. "//
									+ "Rebuild 3D Scene Completely?", "Rebuild Required")) {
						rebuild();
					} else {
						displayMsg("Scene reloaded");
					}
					return;
				case RESET_VIEW:
					keyController.resetView();
					return;
				case SCENE_SHORTCUTS_LIST:
					 keyController.showHelp(true);
					 return;
				case SCENE_SHORTCUTS_NOTIFICATION:
					 keyController.showHelp(false);
					 return;
				case RECORDER:
					openRecorder();
					return;
				case SNAPSHOT_DISK:
					keyController.saveScreenshot();
					return;
				case SNAPSHOT_SHOW:
					snapshot().show();
					return;
				case SYNC:
					try {
						if (!syncPathManagerList())
							rebuild();
						displayMsg("Path Manager contents updated");
					} catch (final IllegalArgumentException ex) {
						guiUtils.error(ex.getMessage());
					}
					return;
				case TAG:
					if (noLoadedItemsGuiError())
						return;
					final boolean all = managerList.isSelectionEmpty() && isSelectAllIfNoneSelected();
					if (managerList.isSelectionEmpty() && !all) return;
					final String tags = guiUtils.getString("<p>Enter one or more tags (space or " //
							+ "comma- separated list) to be assigned to selected items. Tags encoding "//
							+ "a color (e.g., 'red', 'lightblue') will be use to highligh entries. "//
							+ "After dismissing this dialog:</p><ul>" //
							+ "<li>Double-click on an object to edit its tags</li>" //
							+ "<li>Double-click on '" + CheckBoxList.ALL_ENTRY.toString()
							+ "' to add tags to the entire list</li></ul>", //
							"Add Tag(s)", "");
					if (tags == null)
						return; // user pressed cancel
					managerList.applyTagToSelectedItems(tags, all);
					return;
				case TOGGLE_DARK_MODE:
					setEnableDarkMode(!isDarkModeOn());
					return;
				case TOGGLE_CONTROL_PANEL:
					toggleControlPanel();
					return;
				default:
					throw new IllegalArgumentException("Unrecognized action");
				}
			}

			private void run() {
				actionPerformed(new ActionEvent(this, ActionEvent.ACTION_PERFORMED, null) {
					private static final long serialVersionUID = 1L;
				});
			}

			private void showPanelAsNeeded() {
				if (frame.manager != null && !frame.manager.isVisible())
					toggleControlPanel(); // e.g, if action called via cmdFinder
			}

			private void openRecorder() {
				if (getRecorder(false) != null) {
					guiUtils.error("Script Recorder is already open.");
					return;
				}
				final StringBuilder sb = new StringBuilder();
				sb.append("Rec. Viewer's API: https://javadoc.scijava.org/SNT/index.html?sc/fiji/snt/viewer/Viewer3D.html\n");
				sb.append("Tip: Scene details can be printed to Console using `viewer.logSceneControls()` or recorded to\n");
				sb.append("by pressing 'L' when viewer is frontmost\n\n");
				getRecorder(true).recordComment(sb.toString());
				sb.setLength(0);
				sb.append("viewer = snt.getRecViewer(");
				if (!isSNTInstance())
					sb.append(getID());
				sb.append(")");
				recorder.recordCmd(sb.toString());
				SwingUtilities.invokeLater(() -> recorder.setVisible(true));
				return;
			}

		}

		private JPanel buttonPanel() {
			final JPanel buttonPanel = new JPanel(new GridLayout(1, 7));
			buttonPanel.setBorder(null);
			// do not allow panel to resize vertically
			setFixedHeight(buttonPanel);
			buttonPanel.add(menuButton(GLYPH.MASKS, sceneMenu(), "Scene Controls"));
			buttonPanel.add(menuButton(GLYPH.TREE, treesMenu(), "Neuronal Arbors"));
			buttonPanel.add(menuButton(GLYPH.CUBE, meshMenu(), "3D Meshes"));
			buttonPanel.add(menuButton(GLYPH.MARKER, annotationsMenu(), "Geometric Annotations"));
			buttonPanel.add(menuButton(GLYPH.ATLAS, refBrainsMenu(), "Reference Brains"));
			buttonPanel.add(menuButton(GLYPH.CALCULATOR, measureMenu(), "Analyze & Measure"));
			buttonPanel.add(menuButton(GLYPH.TOOL, utilsMenu(), "Utilities & Actions"));
			buttonPanel.add(menuButton(GLYPH.CODE, scriptingMenu(), "Scripting"));
			buttonPanel.add(menuButton(GLYPH.COG, prefsMenu(), "Settings"));
			buttonPanel.add(cmdFinder.getButton());
			return buttonPanel;
		}

		private void setFixedHeight(final JComponent c) {
			// do not allow component to resize vertically
			c.setMaximumSize(new Dimension(c.getMaximumSize().width, (int) c.getPreferredSize().getHeight()));
		}

		private JButton menuButton(final GLYPH glyph, final JPopupMenu menu, final String tooltipMsg) {
			final JButton button = new JButton(IconFactory.getButtonIcon(glyph, 1.8f));
			button.setToolTipText(tooltipMsg);
			cmdFinder.register(menu, new ArrayList<>(Collections.singletonList(tooltipMsg)));
			button.addActionListener(e -> menu.show(button, button.getWidth() / 2, button.getHeight() / 2));
			return button;
		}

		private JPopupMenu sceneMenu() {
			final JPopupMenu sceneMenu = new JPopupMenu();
			final JMenuItem dark = new JMenuItem(new Action(Action.TOGGLE_DARK_MODE, KeyEvent.VK_D, false, false));
			dark.setIcon(IconFactory.getMenuIcon(GLYPH.SUN));
			sceneMenu.add(dark);
			sceneMenu.addSeparator();
			final JMenuItem fit = new JMenuItem(new Action(Action.FIT, KeyEvent.VK_F, false, false));
			fit.setIcon(IconFactory.getMenuIcon(GLYPH.EXPAND));
			sceneMenu.add(fit);
			sceneMenu.add(zoomToSelectionMenuItem());
			// Aspect-ratio controls
			sceneMenu.add(squarifyMenu());
			sceneMenu.add(axesMenu());
			final JMenuItem jcbmiFill = new JCheckBoxMenuItem("Stretch-to-Fill");
			jcbmiFill.setIcon(IconFactory.getMenuIcon(GLYPH.EXPAND_ARROWS1));
			jcbmiFill.addItemListener(e -> {
				final ViewportMode mode = (jcbmiFill.isSelected()) ? ViewportMode.STRETCH_TO_FILL
						: ViewportMode.RECTANGLE_NO_STRETCH;
				view.getCamera().setViewportMode(mode);
			});
			sceneMenu.add(jcbmiFill);
			final JMenuItem resize = new JMenuItem(new Action(Action.RESIZE));
			resize.setIcon(IconFactory.getMenuIcon(GLYPH.RESIZE));
			sceneMenu.add(resize);
			final JMenuItem fullScreen = new JMenuItem(new Action(Action.ENTER_FULL_SCREEN, KeyEvent.VK_F, false, true));
			fullScreen.setIcon(IconFactory.getMenuIcon(GLYPH.EXPAND_ARROWS2));
			sceneMenu.add(fullScreen);
			sceneMenu.addSeparator();

			final JMenuItem reset = new JMenuItem(new Action(Action.RESET_VIEW, KeyEvent.VK_R, false, false));
			reset.setIcon(IconFactory.getMenuIcon(GLYPH.BROOM));
			sceneMenu.add(reset);
			final JMenuItem reload = new JMenuItem(new Action(Action.RELOAD, KeyEvent.VK_R, false, true));
			reload.setIcon(IconFactory.getMenuIcon(GLYPH.REDO));
			sceneMenu.add(reload);
			final JMenuItem rebuild = new JMenuItem(new Action(Action.REBUILD, KeyEvent.VK_R, true, true));
			rebuild.setIcon(IconFactory.getMenuIcon(GLYPH.RECYCLE));
			sceneMenu.add(rebuild);
			final JMenuItem wipe = new JMenuItem("Wipe Scene...", IconFactory.getMenuIcon(GLYPH.DANGER));
			wipe.addActionListener(e -> wipeWithPrompt());
			sceneMenu.add(wipe);
			sceneMenu.addSeparator();

			final JMenuItem help = new JMenuItem(new Action(Action.SCENE_SHORTCUTS_LIST, KeyEvent.VK_F1, false, false));
			new Action(Action.SCENE_SHORTCUTS_NOTIFICATION, KeyEvent.VK_H, false, false); // register alternative shortcut
			help.setIcon(IconFactory.getMenuIcon(GLYPH.KEYBOARD));
			sceneMenu.add(help);
			sceneMenu.addSeparator();
			final JMenuItem sup = new JMenuItem("Duplicate Scene", IconFactory.getMenuIcon(GLYPH.COPY));
			sup.addActionListener(e -> {
				class DupWorker extends SwingWorker<Viewer3D, Object> {

					@Override
					protected Viewer3D doInBackground() {
						return duplicate();
					}

					@Override
					protected void done() {
						try {
							final Viewer3D dup = get();
							dup.show();
							dup.view.setBoundsManual(view.getBounds().clone());
						} catch (final OutOfMemoryError e1) {
							e1.printStackTrace();
							guiUtils.error("There is not enough memory to complete command. See Console for details.");
						} catch (NullPointerException | InterruptedException | ExecutionException e2) {
							e2.printStackTrace();
							guiUtils.error("Unfortunately an error occurred. See Console for details.");
						} finally {
							removeProgressLoad(-1);
						}
					}
				}
				addProgressLoad(-1);
				new DupWorker().execute();
			});
			sceneMenu.add(sup);
			final JMenuItem sync = new JMenuItem(new Action(Action.SYNC, KeyEvent.VK_S, true, true));
			sync.setIcon(IconFactory.getMenuIcon(GLYPH.SYNC));
			sync.setEnabled(isSNTInstance());
			sceneMenu.add(sync);
			return sceneMenu;
		}

		private JMenuItem zoomToSelectionMenuItem() {
			final JMenuItem fitToSelection = new JMenuItem("Fit To Selection", IconFactory.getMenuIcon(GLYPH.CROSSHAIR));
			fitToSelection.addActionListener(e -> {
				List<?> selection = managerList.getSelectedValuesList();
				if (managerList.getSelectedIndex() == -1 || selection.isEmpty()) {
					guiUtils.error("No items are currently selected.");
					return;
				}
				if (selection.size() == 1 && CheckBoxList.ALL_ENTRY.equals(selection.get(0))) {
					selection.clear();
					selection = IntStream.range(0, managerList.getModel().getSize())
							.mapToObj(managerList.getModel()::getElementAt).collect(Collectors.toList());
				}
				zoomTo(selection.toArray(new Object[0]));
			});
			return fitToSelection;
		}

		private JMenu squarifyMenu() {
			final JMenu menu = new JMenu("Impose Isotropic Scale");
			menu.setIcon(IconFactory.getMenuIcon(GLYPH.EQUALS));
			final ButtonGroup cGroup = new ButtonGroup();
			final String[] axes = new String[] { "XY", "ZY", "XZ", "None"};
			for (final String axis : axes) {
				final JMenuItem jcbmi = new JCheckBoxMenuItem(axis, axis.startsWith("None"));
				cGroup.add(jcbmi);
				jcbmi.addItemListener(e -> squarify(axis, jcbmi.isSelected()));
				menu.add(jcbmi);
			}
			return menu;
		}

		private JMenu axesMenu() {
			final JMenu menu = new JMenu("Axes");
			final JMenuItem jmi = new JMenuItem("Axes Labels...");
			jmi.addActionListener(e -> {
				final String[] defaults = { view.getAxisLayout().getXAxisLabel(), view.getAxisLayout().getYAxisLabel(),
						view.getAxisLayout().getZAxisLabel() };
				final String[] labels = guiUtils.getStrings("Axes Labels...",
						new String[] { "X axis ", "Y axis ", "Z axis " }, defaults);
				if (labels != null)
					setAxesLabels(labels);
			});
			menu.add(jmi);
			menu.setIcon(IconFactory.getMenuIcon(GLYPH.CHART_LINE));
			final JCheckBoxMenuItem jcbm = new JCheckBoxMenuItem("Display Frame", view.isDisplayAxisWholeBounds());
			jcbm.addActionListener(e -> {
				if (jcbm.isSelected()) chart.setAxeDisplayed(true);
				view.setDisplayAxisWholeBounds(jcbm.isSelected());
			});
			menu.add(jcbm);
			menu.add(new JMenuItem(new Action(Action.AXES_TOGGLE, KeyEvent.VK_A, false, false)));
			menu.addSeparator();
			final Map<String, Boolean> entries = Map.of("XY View: Flip Horizontal Axis",
					view.get2DLayout().isHorizontalAxisFlip(), "XY View: Flip Vertical Axis",
					view.get2DLayout().isVerticalAxisFlip());
			entries.forEach((k, v) -> {
				final JMenuItem jcbmi = new JCheckBoxMenuItem(k, v);
				jcbmi.addItemListener(e -> {
					setViewMode(ViewMode.XY);
					flipAxis(k, jcbmi.isSelected());
				});
				menu.add(jcbmi);
			});
			return menu;
		}

		private JPopupMenu popupMenu() {
			final JMenuItem renderIcons = new JCheckBoxMenuItem("Label Categories",
					IconFactory.getMenuIcon(GLYPH.MARKER), managerList.renderer.iconVisible);
			renderIcons.addItemListener(e -> {
				managerList.setIconsVisible((renderIcons.isSelected()));
			});
			final JMenuItem sort = new JMenuItem("Sort List", IconFactory.getMenuIcon(GLYPH.SORT));
			sort.addActionListener(e -> {
				if (noLoadedItemsGuiError()) {
					return;
				}		
				managerList.model.sort();
			});
			final JMenuItem addTag = new JMenuItem(new Action(Action.TAG, KeyEvent.VK_T, true, true));
			addTag.setIcon(IconFactory.getMenuIcon(GLYPH.TAG));
			final JMenuItem wipeTags = new JMenuItem("Remove Tags...", IconFactory.getMenuIcon(GLYPH.BROOM));
			wipeTags.addActionListener(e -> {
				if (noLoadedItemsGuiError())
					return;
				final boolean all = managerList.isSelectionEmpty() && isSelectAllIfNoneSelected();
				if (managerList.isSelectionEmpty() && !all) return;
				if (guiUtils.getConfirmation("Remove all tags from " + ((all) ? "all" : "selected") + " items?",
						"Dispose All Tags?")) {
					managerList.removeTagsFromSelectedItems(all);
				}
			});
			final JMenuItem assignSceneColorTags = new JMenuItem("Apply Scene-based Tags", IconFactory.getMenuIcon(GLYPH.COLOR));
			assignSceneColorTags.addActionListener(e -> {
				if (noLoadedItemsGuiError())
					return;
				final boolean all = managerList.isSelectionEmpty() && isSelectAllIfNoneSelected();
				managerList.applyRenderedColorsToSelectedItems(all);
			});
			
			// Select menu
			final JMenu selectMenu = new JMenu("Select");
			selectMenu.setIcon(IconFactory.getMenuIcon(GLYPH.POINTER));
			final JMenuItem selectAnnotations = new JMenuItem("Annotations");
			selectAnnotations.addActionListener(e -> selectRows(plottedAnnotations));
			final JMenuItem selectMeshes = new JMenuItem("Meshes");
			selectMeshes.addActionListener(e -> selectRows(plottedObjs));
			final JMenuItem selectTrees = new JMenuItem("Trees");
			selectTrees.addActionListener(e -> selectRows(plottedTrees));
			selectMenu.add(selectTrees);
			selectMenu.add(selectMeshes);
			selectMenu.add(selectAnnotations);
			selectMenu.addSeparator();
			final JMenuItem selectAll = new JMenuItem(new Action(Action.ALL, KeyEvent.VK_A, true, false));
			selectMenu.add(selectAll);
			final JMenuItem selectNone = new JMenuItem(new Action(Action.NONE, KeyEvent.VK_A, true, true));
			selectMenu.add(selectNone);

			// Hide menu
			final JMenu hideMenu = new JMenu("Hide");
			hideMenu.setIcon(IconFactory.getMenuIcon(GLYPH.EYE_SLASH));
			final JMenuItem hideMeshes = new JMenuItem("Meshes");
			hideMeshes.addActionListener(e -> hide(plottedTrees));
			final JMenuItem hideTrees = new JMenuItem("Trees");
			hideTrees.addActionListener(e -> setArborsDisplayed(getLabelsCheckedInManager(), false));
			final JMenuItem hideAnnotations = new JMenuItem("Annotations");
			hideAnnotations.addActionListener(e -> hide(plottedAnnotations));
			final JMenuItem hideSomas = new JMenuItem("Soma of Selected Trees");
			hideSomas.addActionListener(e -> setSomasDisplayedOfSelectedTrees(false));
			final JMenuItem hideBoxes = new JMenuItem("Bounding Box of Selected Meshes");
			hideBoxes.addActionListener(e -> setBoundingBoxDisplayedOfSelectedMeshes(false));
//			final JMenuItem hideAll = new JMenuItem("All");
//			hideAll.addActionListener(e -> managerList.selectNone());
			final JMenuItem hideSelected = new JMenuItem("Selected Items");
			hideSelected.addActionListener(e -> displaySelectedObjects(false));
			hideMenu.add(hideTrees);
			hideMenu.add(hideMeshes);
			hideMenu.add(hideAnnotations);
			hideMenu.addSeparator();
			hideMenu.add(hideSomas);
			hideMenu.add(hideBoxes);
			hideMenu.addSeparator();
//			hideMenu.add(hideAll);
			hideMenu.add(hideSelected);

			// Show Menu
			final JMenu showMenu = new JMenu("Show");
			showMenu.setIcon(IconFactory.getMenuIcon(GLYPH.EYE));
			final JMenuItem showMeshes = new JMenuItem("Only Meshes");
			showMeshes.addActionListener(e -> show(plottedObjs));
			final JMenuItem showTrees = new JMenuItem("Only Trees");
			showTrees.addActionListener(e -> show(plottedTrees));
			final JMenuItem showAnnotations = new JMenuItem("Only Annotations");
			showAnnotations.addActionListener(e -> show(plottedAnnotations));
			final JMenuItem showSomas = new JMenuItem("Soma of Selected Trees");
			showSomas.addActionListener(e -> setSomasDisplayedOfSelectedTrees(true));
			final JMenuItem showBoxes = new JMenuItem("Bounding Box of Selected Meshes");
			showBoxes.addActionListener(e -> setBoundingBoxDisplayedOfSelectedMeshes(true));
//			final JMenuItem showAll = new JMenuItem("All");
//			showAll.addActionListener(e -> managerList.selectAll());
			final JMenuItem showSelected = new JMenuItem("Selected Items");
			showSelected.addActionListener(e -> displaySelectedObjects(true));
			showMenu.add(showTrees);
			showMenu.add(showMeshes);
			showMenu.add(showAnnotations);
			showMenu.addSeparator();
			showMenu.add(showSomas);
			showMenu.add(showBoxes);
			showMenu.addSeparator();
//			showMenu.add(showAll);
			showMenu.add(showSelected);

			final JMenuItem remove = new JMenuItem("Remove Selected...", IconFactory.getMenuIcon(GLYPH.TRASH));
			remove.addActionListener(e -> {
				if (managerList.getSelectedIndex() == managerList.getCheckBoxListSelectionModel().getAllEntryIndex()) {
					wipeWithPrompt();
					return;
				}
				final List<?> selectedKeys = managerList.getSelectedValuesList();
				if (managerList.getSelectedIndex() == -1 || selectedKeys.isEmpty()) {
					guiUtils.error("There are no selected entries.");
					return;
				}
				if (guiUtils.getConfirmation("Remove selected item(s)?", "Confirm Deletion?")) {
					managerList.model.setListenersEnabled(false);
					selectedKeys.forEach(k -> {
						if (k.equals(CheckBoxList.ALL_ENTRY))
							return; // continue in lambda expression
						final String[] labelAndManagerEntry = TagUtils.getUntaggedAndTaggedLabels(k.toString());
						removeSceneObject(labelAndManagerEntry[0], labelAndManagerEntry[1]);
					});
					managerList.model.setListenersEnabled(true); // will call managerList.model.update();
				}
			});

			final JPopupMenu pMenu = new JPopupMenu();
			pMenu.add(selectMenu);
			pMenu.add(showMenu);
			pMenu.add(hideMenu);
			pMenu.add(zoomToSelectionMenuItem());
			pMenu.addSeparator();
			pMenu.add(addTag);
			pMenu.add(assignSceneColorTags);
			pMenu.add(wipeTags);
			pMenu.addSeparator();
			pMenu.add(renderIcons);
			pMenu.add(sort);
			pMenu.addSeparator();
			JMenuItem jmi = new JMenuItem(new Action(Action.PROGRESS));
			jmi.setIcon(IconFactory.getMenuIcon(GLYPH.SPINNER));
			pMenu.add(jmi);
			jmi = new JMenuItem(new Action(Action.FIND, KeyEvent.VK_F, true, false));
			jmi.setIcon(IconFactory.getMenuIcon(GLYPH.BINOCULARS));
			pMenu.add(jmi);
			pMenu.addSeparator();
			pMenu.add(remove);
			cmdFinder.register(pMenu, new ArrayList<>(Collections.singletonList("Control Panel Contextual Menu")));
			return pMenu;
		}

		private void wipeWithPrompt() {
			if (guiUtils.getConfirmation("Remove all items from scene? This action cannot be undone.", "Wipe Scene?"))
				wipeScene();
		}

		private boolean noLoadedItemsGuiError() {
			final boolean noItems = plottedTrees.isEmpty() && plottedObjs.isEmpty() && plottedAnnotations.isEmpty();
			if (noItems) {
				guiUtils.error("There are no loaded items.");
			}
			return noItems;
		}

		private void setSomasDisplayedOfSelectedTrees(final boolean display) {
			final List<String> keys = getSelectedTreeLabels();
			if (keys != null)
				setSomasDisplayed(keys, display);
		}

		private void setBoundingBoxDisplayedOfSelectedMeshes(final boolean display) {
			final List<String> keys = getSelectedMeshLabels();
			if (keys != null) {
				plottedObjs.forEach((k, mesh) -> {
					if (keys.contains(k)) {
						if (display && mesh.getBoundingBoxColor() == null)
							mesh.setBoundingBoxColor(mesh.getColor());
						mesh.setBoundingBoxDisplayed(display);
					}
				});
			}
		}				

		private void selectRows(final Map<String, ?> map) {
			final int[] indices = new int[map.keySet().size()];
			int i = 0;
			for (final String k : map.keySet()) {
				indices[i++] = managerList.model.indexOf(k);
			}
			managerList.setSelectedIndices(indices);
		}

		private void displaySelectedObjects(final boolean display) {
			if (noLoadedItemsGuiError()) {
				return;
			}
			final int[] indices = managerList.getSelectedIndices();
			SwingUtilities.invokeLater(() -> {
				managerList.setValueIsAdjusting(true);
				for (int i = 0; i < indices.length; i++) {
					if (display)
						managerList.addCheckBoxListSelectedIndex(indices[i]);
					else
						managerList.removeCheckBoxListSelectedIndex(indices[i]);
				}
				managerList.setValueIsAdjusting(false);
			});

		}

		private void show(final Map<String, ?> map) {
			final int[] indices = new int[map.keySet().size()];
			int i = 0;
			for (final String k : map.keySet()) {
				indices[i++] = managerList.model.indexOf(k);
			}
			managerList.setSelectedIndices(indices);
			managerList.setCheckBoxListSelectedIndices(indices);
		}

		private void setVisible(final String key, final boolean visible) {
			final int index =  managerList.model.indexOf(key);
			if (index == - 1) return;
			SwingUtilities.invokeLater(() -> {
			if (visible)
				managerList.addCheckBoxListSelectedIndex(index);
			else
				managerList.removeCheckBoxListSelectedIndex(index);
			});
		}

		private void hide(final Map<String, ?> map) {
			final List<String> selectedKeys = new ArrayList<>(getLabelsCheckedInManager());
			selectedKeys.removeAll(map.keySet());
			managerList.setSelectedObjects(selectedKeys.toArray());
		}

		private Tree getSingleSelectionTree() {
			if (plottedTrees.size() == 1) {
				final ShapeTree st = plottedTrees.values().iterator().next();
				if (!(st instanceof MultiTreeShapeTree))
					return st.tree;
			}
			final List<Tree> trees = getSelectedTrees(false);
			if (trees == null) return null;
			if (trees.size() != 1) {
				guiUtils.error(
					"This command requires a single reconstruction to be selected.");
				return null;
			}
			return trees.get(0);
		}

		private Tree getSingleSelectionTreeWithPromptForType() {
			Tree tree = getSingleSelectionTree();
			if (tree == null) return null;
			final Set<Integer> types = tree.getSWCTypes(false);
			if (types.size() == 1)
				return tree;
			final String compartment = guiUtils.getChoice("Compartment:", "Which Neuronal Processes?",
					new String[] { "All", "Axon", "Dendrites" }, prefs.treeCompartmentChoice);
			if (compartment == null)
				return null;
			prefs.treeCompartmentChoice = compartment;
			if (!compartment.toLowerCase().contains("all")) {
				tree = tree.subTree(compartment);
				if (tree.isEmpty()) {
					final String treeLabel = (tree.getLabel() == null) ? "Reconstruction" : tree.getLabel();
					guiUtils.error(treeLabel + " does not contain processes tagged as \"" + compartment + "\".");
					return null;
				}
			}
			return tree;
		}

		private List<Tree> getSelectedTrees() {
			return getSelectedTrees(managerList.isSelectionEmpty() && isSelectAllIfNoneSelected());
		}
	
		private List<Tree> getSelectedTrees(final boolean promptForAllIfNone) {
			final List<String> keys = getSelectedKeys(plottedTrees, "reconstructions", promptForAllIfNone);
			if (keys == null) return null; // user pressed cancel on prompt
			if (keys.isEmpty()) { // a selection existed but it did not contain plottedTrees
				guiUtils.error("There are no selected reconstructions.");
				return null;
			}
			final List<Tree> trees = new ArrayList<>();
			keys.forEach( k -> {
				final ShapeTree sTree = plottedTrees.get(k);
				if (sTree != null) {
					if (sTree instanceof MultiTreeShapeTree)
						trees.addAll(((MultiTreeShapeTree)sTree).trees);
					else
						trees.add(sTree.tree);
				}
			});
			return trees;
		}

		private List<Annotation3D> getSelectedAnnotations() {
			return getSelectedAnnotations(managerList.isSelectionEmpty() && isSelectAllIfNoneSelected());
		}
	
		private List<Annotation3D> getSelectedAnnotations(final boolean allowAllIfNone) {
			final List<String> keys = getSelectedKeys(plottedAnnotations, "annotations", allowAllIfNone);
			if (keys == null) return null; // user pressed cancel on prompt
			if (keys.isEmpty()) { // a selection existed but it did not contain plottedTrees
				guiUtils.error("There are no selected annotations.");
				return null;
			}
			final List<Annotation3D> annots = new ArrayList<>();
			keys.forEach( k -> {
				final Annotation3D annot = plottedAnnotations.get(k);
				if (annot != null)
					annots.add(annot);
			});
			return annots;
		}

		private List<String> getSelectedTreeLabels() {
			return getSelectedKeys(plottedTrees, "reconstructions", managerList.isSelectionEmpty() && isSelectAllIfNoneSelected());
		}

		private List<String> getSelectedMeshLabels() {
			return getSelectedKeys(plottedObjs, "meshes", managerList.isSelectionEmpty() && isSelectAllIfNoneSelected());
		}

		private List<String> getSelectedKeys(final Map<String, ?> map,
			final String mapDescriptor, final boolean allowAllIfNone)
		{
			if (map.isEmpty()) {
				guiUtils.error("There are no loaded " + mapDescriptor + ".");
				return null;
			}
			final List<?> selectedValues = managerList.getSelectedValuesList();
			if (selectedValues == null) return null;
			final List<String> selectedKeys= new ArrayList<>(selectedValues.size());
			selectedValues.forEach(sv -> {
				selectedKeys.add(TagUtils.removeAllTags(sv.toString()));
			});
			final List<String> allKeys = new ArrayList<>(map.keySet());
			if ((allowAllIfNone && map.size() == 1)
					|| (selectedKeys.size() == 1 && CheckBoxList.ALL_ENTRY.toString().equals(selectedKeys.get(0))))
				return allKeys;
			if (allowAllIfNone && selectedKeys.isEmpty()) {
				if (isSelectAllIfNoneSelected()) return allKeys;
				guiUtils.error("There are no selected " + mapDescriptor + ".");
				return null;
			}
			allKeys.retainAll(selectedKeys);
			return allKeys;
		}

		private boolean isSelectAllIfNoneSelected() {
			if (prefs.nagUserOnRetrieveAll) {
				final boolean[] options = guiUtils.getPersistentConfirmation("There are no items selected. "//
						+ "Run command on all eligible items?", "Extend to All If None Selected?");
				prefs.retrieveAllIfNoneSelected = options[0];
				prefs.nagUserOnRetrieveAll = !options[1];
			}
			return prefs.retrieveAllIfNoneSelected;
		}

		private JPopupMenu measureMenu() {
			final JPopupMenu measureMenu = new JPopupMenu();
			GuiUtils.addSeparator(measureMenu, "Tabular Results:");
			JMenuItem mi = GuiUtils.MenuItems.measureOptions();
			mi.addActionListener(e -> {
				List<Tree> trees = getSelectedTrees();
				if (trees == null || trees.isEmpty()) return;
				initTable();
				if (MeasureUI.instances != null && !MeasureUI.instances.isEmpty()) {
					guiUtils.error("A Measurements prompt seems to be already open.");
					MeasureUI.instances.get(MeasureUI.instances.size()-1).toFront();
					trees = null;
				} else {
					final MeasureUI measureUI = new MeasureUI(trees);
					measureUI.setTable(table);
					measureUI.setVisible(true);
				}
			});
			measureMenu.add(mi);
			mi = GuiUtils.MenuItems.measureQuick();
			mi.addActionListener(e -> {
				final List<Tree> trees = getSelectedTrees();
				if (trees == null || trees.isEmpty()) return;
				initTable();
				trees.forEach(tree -> {
					final TreeStatistics tStats = new TreeStatistics(tree);
					tStats.setContext(SNTUtils.getContext());
					tStats.setTable(table);
					tStats.summarize(tree.getLabel(), true); // will display table
				});
			});
			measureMenu.add(mi);
			GuiUtils.addSeparator(measureMenu, "Distribution Analyses:");
			mi = new JMenuItem("Branch Properties...", IconFactory.getMenuIcon(GLYPH.CHART));
			mi.setToolTipText("Computes distributions of metrics from all the branches of selected trees");
			mi.addActionListener(e -> {
				final List<Tree> trees = getSelectedTrees();
				if (trees == null || trees.isEmpty()) return;
				final Map<String, Object> inputs = new HashMap<>();
				inputs.put("trees", trees);
				inputs.put("calledFromPathManagerUI", false);
				runCmd(DistributionBPCmd.class, inputs, CmdWorker.DO_NOTHING, false, true);
			});
			measureMenu.add(mi);
			mi = new JMenuItem("Cell Properties...", IconFactory.getMenuIcon(GLYPH.CHART));
			mi.setToolTipText("Computes distributions of metrics from individual cells");
			mi.addActionListener(e -> {
				final List<Tree> trees = getSelectedTrees();
				if (trees == null || trees.isEmpty()) return;
				final Map<String, Object> inputs = new HashMap<>();
				inputs.put("trees", trees);
				inputs.put("calledFromPathManagerUI", false);
				runCmd(DistributionCPCmd.class, inputs, CmdWorker.DO_NOTHING, false, true);
			});
			measureMenu.add(mi);
			GuiUtils.addSeparator(measureMenu, "Specialized Analyses:");
			final JMenuItem convexHullMenuItem = GuiUtils.MenuItems.convexHull();
			convexHullMenuItem.addActionListener(e -> {
				final List<Tree> trees = getSelectedTrees();
				if (trees == null || trees.isEmpty()) return;
				final Map<String, Object> inputs = new HashMap<>();
				inputs.put("trees", trees);
				inputs.put("calledFromRecViewerInstance", true);
				initTable();
				inputs.put("table", table);
				runCmd(ConvexHullCmd.class, inputs, CmdWorker.DO_NOTHING, true, true);
			});
			measureMenu.add(convexHullMenuItem);
			mi = GuiUtils.MenuItems.createDendrogram();
			mi.addActionListener(e -> {
				final Tree tree = getSingleSelectionTreeWithPromptForType();
				if (tree == null) return;
				final Map<String, Object> inputs = new HashMap<>();
				inputs.put("tree", tree);
				runCmd(GraphGeneratorCmd.class, inputs, CmdWorker.DO_NOTHING, false, true);
			});
			measureMenu.add(mi);
			mi = GuiUtils.MenuItems.persistenceAnalysis();
			mi.addActionListener(e -> {
				final List<Tree> trees = getSelectedTrees();
				if (trees == null || trees.isEmpty()) return;
				final HashMap<String, Object> inputs = new HashMap<>();
				inputs.put("trees", trees);
				runCmd(PersistenceAnalyzerCmd.class, inputs, CmdWorker.DO_NOTHING, false, true);
			});
			measureMenu.add(mi);
			mi = GuiUtils.MenuItems.shollAnalysis();
			mi.addActionListener(e -> {
				final List<Tree> trees = getSelectedTrees();
				if (trees == null || trees.isEmpty()) return;
				final Map<String, Object> input = new HashMap<>();
				if (trees.size() == 1) {
					input.put("snt", null);
					input.put("tree", trees.get(0));
					runCmd(ShollAnalysisTreeCmd.class, input, CmdWorker.DO_NOTHING, false, false);
				} else {
					input.put("treeList", trees);
					runCmd(ShollAnalysisBulkTreeCmd.class, input, CmdWorker.DO_NOTHING, true, true);
				}
			});
			measureMenu.add(mi);
			mi = GuiUtils.MenuItems.strahlerAnalysis();
			mi.addActionListener(e -> {
				final List<Tree> trees = getSelectedTrees();
				if (trees == null || trees.isEmpty()) return;
				final HashMap<String, Object> inputs = new HashMap<>();
				inputs.put("trees", trees);
				runCmd(StrahlerCmd.class, inputs, CmdWorker.DO_NOTHING, false, true);
			});
			measureMenu.add(mi);
			GuiUtils.addSeparator(measureMenu, "Atlas-based Analyses:");
			mi = GuiUtils.MenuItems.createAnnotionGraph();
			mi.addActionListener(e -> {
				final List<Tree> trees = getSelectedTrees();
				if (trees == null || trees.isEmpty()) return;
				final Map<String, Object> inputs = new HashMap<>();
				inputs.put("trees", trees);
				runCmd(AnnotationGraphGeneratorCmd.class, inputs, CmdWorker.DO_NOTHING, false, true);
			});
			measureMenu.add(mi);
			mi = GuiUtils.MenuItems.brainAreaAnalysis();
			mi.addActionListener(e -> {
				final Tree tree = getSingleSelectionTree();
				if (tree == null) return;
				final Map<String, Object> inputs = new HashMap<>();
				inputs.put("tree", tree);
				runCmd(BrainAnnotationCmd.class, inputs, CmdWorker.DO_NOTHING, false, true);
			});
			measureMenu.add(mi);
			GuiUtils.addSeparator(measureMenu, "Data Export:");
			mi = GuiUtils.MenuItems.saveTablesAndPlots(GLYPH.SAVE);
			mi.addActionListener(e -> {
				runCmd(SaveMeasurementsCmd.class, null, CmdWorker.DO_NOTHING, false, true);
			});
			measureMenu.add(mi);
			//measureMenu.add(guiUtils.combineChartsMenuItem());
			return measureMenu;
		}

		private void initTable() {
			if (table == null) table =  new SNTTable();
		}

		private void addCustomizeMeshCommands(final JPopupMenu menu) {
			GuiUtils.addSeparator(menu, "Customize:");
			JMenuItem mi = new JMenuItem("All Parameters...", IconFactory.getMenuIcon(GLYPH.SLIDERS));
			mi.addActionListener(e -> {
				final List<String> keys = getSelectedMeshLabels();
				if (keys == null) return;
				if (cmdService == null)
					SNTUtils.getContext().inject(Viewer3D.this);
				class getMeshColors extends SwingWorker<Object, Object> {

					CommandModule cmdModule;

					@Override
					public Object doInBackground() {
						try {
							cmdModule = cmdService.run(CustomizeObjCmd.class, true).get();
						}
						catch (InterruptedException | ExecutionException ignored) {
							return null;
						}
						return null;
					}

					@Override
					protected void done() {
						if (cmdModule == null || cmdModule.isCanceled()) {
							return; // user pressed cancel or chose nothing
						}
						final ColorRGBA[] colors = (ColorRGBA[]) cmdModule.getInput("colors");
						if (colors == null) return;
						final Color surfaceColor = (colors[0]==null) ? null : fromColorRGB(colors[0]);
						for (final String label : keys) {
							if (surfaceColor != null) plottedObjs.get(label).setColor(surfaceColor);
							if (colors[1] != null) plottedObjs.get(label).objMesh.setBoundingBoxColor(colors[1]);
						}
					}
				}
				(new getMeshColors()).execute();
			});
			menu.add(mi);

			// Mesh customizations
			mi = new JMenuItem("Color...", IconFactory.getMenuIcon(GLYPH.COLOR));
			mi.addActionListener(e -> {
				final List<String> keys = getSelectedMeshLabels();
				if (keys == null) return;
				final ColorRGB c = new AnnotPrompt().getPromptColor("Mesh(es) Color");
				if (c == null) {
					return; // user pressed cancel
				}
				final Color color = fromColorRGB(c);
				for (final String label : keys) {
					final RemountableDrawableVBO obj = plottedObjs.get(label);
					color.a = obj.getColor().a;
					obj.setColor(color);
				}
			});
			menu.add(mi);
			mi = new JMenuItem("Transparency...", IconFactory.getMenuIcon(
				GLYPH.ADJUST));
			mi.addActionListener(e -> {
				final List<String> keys = getSelectedMeshLabels();
				if (keys == null) return;
				final Double t = new AnnotPrompt().getPromptTransparency("Mesh(es) Transparency...");
				if (t == null) {
					return; // user pressed cancel
				}
				final float fValue = 1 - (t.floatValue() / 100);
				for (final String label : keys) {
					plottedObjs.get(label).getColor().a = fValue;
				}
			});
			menu.add(mi);
		}

		private void addCustomizeTreeCommands(final JPopupMenu menu) {

			JMenuItem mi = new JMenuItem("All Parameters...", IconFactory.getMenuIcon(GLYPH.SLIDERS));
			mi.addActionListener(e -> {
				final List<String> keys = getSelectedTreeLabels();
				if (keys == null) return;
				if (cmdService == null)
					SNTUtils.getContext().inject(Viewer3D.this);
				class getTreeColors extends SwingWorker<Object, Object> {

					CommandModule cmdModule;

					@Override
					public Object doInBackground() {
						try {
							cmdModule = cmdService.run(CustomizeTreeCmd.class, true).get();
						}
						catch (final InterruptedException | ExecutionException ignored) {
							return null;
						}
						return null;
					}

					@Override
					protected void done() {
						if (cmdModule != null && cmdModule.isCanceled()) {
							return; // user pressed cancel or chose nothing
						}
						@SuppressWarnings("unchecked")
						final HashMap<String, ColorRGBA> colorMap = (HashMap<String, ColorRGBA>) cmdModule.getInput("colorMap");
						@SuppressWarnings("unchecked")
						final HashMap<String, Double> sizeMap = (HashMap<String, Double>) cmdModule.getInput("sizeMap");
						if (colorMap == null || sizeMap == null) {
							guiUtils.error("Command execution failed.");
							return;
						}
//						final boolean reflectLight = (boolean) cmdModule.getInput("reflectLight");
//						final boolean faceDisplayed = (boolean) cmdModule.getInput("faceDisplayed");
//						final boolean wireframeDisplayed = (boolean) cmdModule.getInput("wireframeDisplayed");
					
						final Color sColor = fromColorRGB(colorMap.get("soma"));
						final Color dColor = fromColorRGB(colorMap.get("dendrite"));
						final Color aColor = fromColorRGB(colorMap.get("axon"));
						final double sSize = sizeMap.get("soma");
						final double dSize = sizeMap.get("dendrite");
						final double aSize = sizeMap.get("axon");
						for (final String label : keys) {
							final ShapeTree tree = plottedTrees.get(label);
							if (tree.somaSubShape != null) {
								if (sColor != null) tree.setSomaColor(sColor);
								if (sSize > -1) tree.setSomaRadius((float) sSize);
							}
							if (tree.treeSubShape != null) {
								if (dColor != null) tree.setArborColor(dColor, Path.SWC_DENDRITE);
								if (aColor != null) tree.setArborColor(aColor, Path.SWC_AXON);
								if (dSize > -1) tree.setThickness((float) dSize, Path.SWC_DENDRITE);
								if (aSize > -1) tree.setThickness((float) aSize, Path.SWC_AXON);
							}
//							tree.setReflectLight(reflectLight);
//							tree.setFaceDisplayed(faceDisplayed);
//							tree.setWireframeDisplayed(wireframeDisplayed);
						}
					}
				}
				(new getTreeColors()).execute();
			});
			menu.add(mi);

			mi = new JMenuItem("Color...", IconFactory.getMenuIcon(GLYPH.COLOR));
			mi.addActionListener(e -> {
				final List<String> keys = getSelectedTreeLabels();
				if (keys == null || !okToApplyColor(keys)) return;
				final ColorRGB c = new AnnotPrompt().getPromptColor("Tree(s) Color...");
				if (c != null)
					applyColorToPlottedTrees(keys, c);
			});
			menu.add(mi);
			final JMenu ccMenu = new JMenu("Color Mapping");
			ccMenu.setIcon(IconFactory.getMenuIcon(GLYPH.COLOR2));
			menu.add(ccMenu);
			mi = new JMenuItem("Apply Color Mapping...");
			mi.addActionListener(e -> {
				final List<String> keys = getSelectedTreeLabels();
				if (keys == null) return;
				final Map<String, Object> inputs = new HashMap<>();
				inputs.put("treeLabels", keys);
				runCmd(ColorMapReconstructionCmd.class, inputs, CmdWorker.DO_NOTHING, true, false);
			});
			ccMenu.add(mi);
			mi = new JMenuItem("Remove Existing Color Mapping(s)");
			mi.addActionListener(e -> {
				final List<Tree> trees = getSelectedTrees(true);
				if (trees != null) {
					trees.forEach(sc.fiji.snt.analysis.ColorMapper::unMap);
					displayMsg("Color mappings removed");
				}
			});
			ccMenu.add(mi);
			ccMenu.addSeparator();
			mi = new JMenuItem("Color Each Cell Uniquely");
			mi.addActionListener(e -> {
				final List<String> keys = getSelectedTreeLabels();
				if (keys == null || !okToApplyColor(keys)) return;
				final ColorRGB[] colors = SNTColor.getDistinctColors(keys.size());
				final int[] counter = new int[] { 0 };
				plottedTrees.forEach((k, shapeTree) -> {
					shapeTree.setArborColor(colors[counter[0]], ShapeTree.ANY);
					shapeTree.setSomaColor(colors[counter[0]]);
					counter[0]++;
				});
				displayMsg("Unique colors assigned");
			});
			ccMenu.add(mi);

			mi = new JMenuItem("Thickness...", IconFactory.getMenuIcon(GLYPH.DOTCIRCLE));
			mi.addActionListener(e -> {
				final List<String> keys = getSelectedTreeLabels();
				if (keys == null) return;
				String msg = "<HTML><body><div style='width:500;'>" +
					"Please specify a constant thickness value [ranging from 1 (thinnest) to 8"
					+ " (thickest)] to be applied to selected " + keys.size() + " reconstruction(s).";
				if (isSNTInstance()) {
					msg += " This value will only affect how Paths are displayed " +
						"in the Reconstruction Viewer.";
				}
				final Double thickness = guiUtils.getDouble(msg, "Path Thickness",
					getDefaultThickness());
				if (thickness == null) {
					return; // user pressed cancel
				}
				if (Double.isNaN(thickness) || thickness <= 0) {
					guiUtils.error("Invalid thickness value.");
					return;
				}
				setTreeThickness(keys, thickness.floatValue(), null);
			});
			menu.add(mi);
			mi = new JMenuItem("Soma radius...", IconFactory.getMenuIcon(GLYPH.CIRCLE));
			mi.addActionListener(e -> {
				final List<String> keys = getSelectedTreeLabels();
				if (keys == null) return;
				String msg = "Please specify a constant radius (in physical units) to be applied to the soma(s) of selected reconstruction(s).";
				if (isSNTInstance()) {
					msg += " This value will only affect how Paths are displayed " +
						"in the Reconstruction Viewer.";
				}
				final Double radius = guiUtils.getDouble(msg, "Soma radius", 10);
				if (radius == null) {
					return; // user pressed cancel
				}
				if (Double.isNaN(radius) || radius <= 0) {
					guiUtils.error("Invalid radius.");
					return;
				}
				setSomasDisplayed(keys, true);
				setSomaRadius(keys, radius.floatValue());
			});
			menu.add(mi);
			mi = new JMenuItem("Translate...", IconFactory.getMenuIcon(GLYPH.MOVE));
			mi.addActionListener(e -> {
				final List<String> keys = getSelectedTreeLabels();
				if (keys == null) return;
				final Map<String, Object> inputs = new HashMap<>();
				inputs.put("treeLabels", keys);
				runCmd(TranslateReconstructionsCmd.class, inputs, CmdWorker.DO_NOTHING, true, false);
			});
			menu.add(mi);
		}

		private JPopupMenu utilsMenu() {
			final JPopupMenu utilsMenu = new JPopupMenu();
			GuiUtils.addSeparator(utilsMenu, "Utilities:");
			JMenuItem mi = new JMenuItem("Annotation Label...", IconFactory.getMenuIcon(GLYPH.PEN));
			mi.addActionListener(e -> {
				runCmd(AddTextAnnotationCmd.class, null, CmdWorker.DO_NOTHING, true, false);
			});
			utilsMenu.add(mi);
			utilsMenu.add(legendMenu());
			if (!isSNTInstance()) {
				final JMenuItem jmi = GuiUtils.MenuItems.renderQuick();
				jmi.addActionListener(e -> {
					final List<Tree> trees = getSelectedTrees();
					if (trees == null || trees.isEmpty()) return;
					final Map<String, Object> inputs = new HashMap<>();
					inputs.put("trees", trees);
					runCmd(FigCreatorCmd.class, inputs, CmdWorker.DO_NOTHING, false, true);
				});
				utilsMenu.add(jmi);
			}
			final JMenuItem light = new JMenuItem("Light Controls...", IconFactory.getMenuIcon(GLYPH.BULB));
			light.addActionListener(e -> frame.displayLightController(null));
			utilsMenu.add(light);
			mi = new JMenuItem("Record Rotation", IconFactory.getMenuIcon(GLYPH.VIDEO));
			mi.addActionListener(e -> {
				SwingUtilities.invokeLater(() -> {
					displayMsg("Recording rotation...", 0);
					new RecordWorker().execute();
				});
			});
			utilsMenu.add(mi);
			GuiUtils.addSeparator(utilsMenu, "Screenshots:");
			final JMenuItem snapshot2 = new JMenuItem(new Action(Action.SNAPSHOT_SHOW, KeyEvent.VK_UNDEFINED, false, false));
			snapshot2.setIcon(IconFactory.getMenuIcon(GLYPH.CAMERA));
			utilsMenu.add(snapshot2);
			final JMenuItem snapshot = new JMenuItem(new Action(Action.SNAPSHOT_DISK, KeyEvent.VK_S, false, false));
			snapshot.setIcon(IconFactory.getMenuIcon(GLYPH.CAMERA));
			utilsMenu.add(snapshot);
			final JMenuItem reveal = new JMenuItem("Show Snapshot Directory", IconFactory.getMenuIcon(GLYPH.OPEN_FOLDER));
			reveal.addActionListener(e -> {
				try {
					guiUtils.showDirectory(prefs.getSnapshotDir());
				} catch (final Exception ignored) {
					guiUtils.error("Snapshot directory does not seem to be accessible.");
				}
			});
			utilsMenu.add(reveal);

			GuiUtils.addSeparator(utilsMenu, "Resources:");
			final JMenu helpMenu = GuiUtils.helpMenu();
			helpMenu.setIcon( IconFactory.getMenuIcon(GLYPH.QUESTION));
			utilsMenu.add(helpMenu);
			return utilsMenu;
		}

		private JPopupMenu scriptingMenu() {
			final JPopupMenu scriptMenu = new JPopupMenu();
			GuiUtils.addSeparator(scriptMenu, "New Script:");
			JMenuItem mi = new JMenuItem(new Action(Action.RECORDER, KeyEvent.VK_OPEN_BRACKET, false, false));
			mi.setIcon(IconFactory.getMenuIcon(GLYPH.CODE));
			scriptMenu.add(mi);
			final JMenuItem log = new JMenuItem(new Action(Action.LOG_TO_RECORDER, KeyEvent.VK_L, false, false));
			log.setIcon(IconFactory.getMenuIcon(GLYPH.STREAM));
			scriptMenu.add(log);
			GuiUtils.addSeparator(scriptMenu, "Resources:");
			scriptMenu.add(GuiUtils.MenuItems.devResourceMain());
			scriptMenu.add(GuiUtils.MenuItems.devResourceNotebooks());
			scriptMenu.add(GuiUtils.MenuItems.devResourceAPI());
			return scriptMenu;
		}

		private JPopupMenu prefsMenu() {
			final JPopupMenu prefsMenu = new JPopupMenu();
			GuiUtils.addSeparator(prefsMenu, "Layout:");
			final JMenuItem hide = new JMenuItem(new Action(Action.TOGGLE_CONTROL_PANEL, KeyEvent.VK_C, false, true));
			hide.setIcon(IconFactory.getMenuIcon(GLYPH.WINDOWS2));
			prefsMenu.add(hide);
			GuiUtils.addSeparator(prefsMenu, "Keyboard & Mouse Sensitivity:");
			prefsMenu.add(panMenu());
			prefsMenu.add(zoomMenu());
			prefsMenu.add(rotationMenu());

			GuiUtils.addSeparator(prefsMenu, "Advanced Settings:");

			prefsMenu.add(getDebugCheckBox());
			if (ENGINE == Engine.JOGL) {
				final JMenuItem  jcbmi2= new JCheckBoxMenuItem("Enable Hardware Acceleration", Settings.getInstance().isHardwareAccelerated());
				//jcbmi2.setEnabled(!isSNTInstance());
				jcbmi2.setIcon(IconFactory.getMenuIcon(GLYPH.MICROCHIP));
				jcbmi2.setMnemonic('h');
				jcbmi2.addItemListener(e -> {
					Settings.getInstance().setHardwareAccelerated(jcbmi2.isSelected());
					logGLDetails();
				});
				prefsMenu.add(jcbmi2);
			}
			GuiUtils.addSeparator(prefsMenu, "Other:");
			final JMenuItem mi = new JMenuItem("Preferences...", IconFactory.getMenuIcon(GLYPH.COGS));
			mi.addActionListener(e -> {
				runCmd(RecViewerPrefsCmd.class, null, CmdWorker.RELOAD_PREFS, true, false);
			});
			prefsMenu.add(mi);
			return prefsMenu;
		}

		private JMenu panMenu() {
			final JMenu panMenu = new JMenu("Pan Accuracy");
			panMenu.setIcon(IconFactory.getMenuIcon(GLYPH.HAND));
			final ButtonGroup pGroup = new ButtonGroup();
			for (final Prefs.PAN pan : Prefs.PAN.values()) {
				final JMenuItem jcbmi = new JCheckBoxMenuItem(pan.description);
				jcbmi.setSelected(pan.step == mouseController.panStep);
				jcbmi.addItemListener(e -> mouseController.panStep = pan.step);
				pGroup.add(jcbmi);
				panMenu.add(jcbmi);
			}
			return panMenu;
		}

		private JMenu rotationMenu() {
			final JMenu rotationMenu = new JMenu("Rotation Steps (Arrow Keys)");
			rotationMenu.setIcon(IconFactory.getMenuIcon(GLYPH.UNDO));
			final ButtonGroup rGroup = new ButtonGroup();
			for (final double step : Prefs.ROTATION_STEPS) {
				final JMenuItem jcbmi = new JCheckBoxMenuItem(String.format("%.1f", Math
					.toDegrees(step)) + "\u00b0");
				jcbmi.setSelected(step == keyController.rotationStep);
				jcbmi.addItemListener(e -> keyController.rotationStep = step);
				rGroup.add(jcbmi);
				rotationMenu.add(jcbmi);
			}
			return rotationMenu;
		}

		private JMenu zoomMenu() {
			final JMenu zoomMenu = new JMenu("Zoom Steps (+/- Keys)");
			zoomMenu.setIcon(IconFactory.getMenuIcon(GLYPH.SEARCH));
			final ButtonGroup zGroup = new ButtonGroup();
			for (final float step : Prefs.ZOOM_STEPS) {
				final JMenuItem jcbmi = new JCheckBoxMenuItem(String.format("%.0f",
					step * 100) + "%");
				jcbmi.setSelected(step == keyController.zoomStep);
				jcbmi.addItemListener(e -> keyController.zoomStep = step);
				zGroup.add(jcbmi);
				zoomMenu.add(jcbmi);
			}
			return zoomMenu;
		}

		private class RecordWorker extends SwingWorker<String, Object> {

			private boolean error = false;

			@Override
			protected String doInBackground() {
				final File rootDir = new File(prefs.getSnapshotDir(), "SNTrecordings" + File.separator);
				if (!rootDir.exists()) rootDir.mkdirs();
				final int dirId = rootDir.list((current, name) -> new File(current,
					name).isDirectory() && name.startsWith("recording")).length + 1;
				final File dir = new File(rootDir + File.separator + "recording" +
					String.format("%01d", dirId));
				try {
					recordRotation(prefs.getSnapshotRotationAngle(), prefs
						.getSnapshotRotationSteps(), dir);
					return "Finished. Frames at " + dir.getParent();
				}
				catch (final IllegalArgumentException | SecurityException ex) {
					error = true;
					return ex.getMessage();
				}
			}

			@Override
			protected void done() {
				String doneMessage;
				try {
					doneMessage = get();
				}
				catch (InterruptedException | ExecutionException ex) {
					error = true;
					doneMessage = "Unfortunately an exception occured.";
					if (SNTUtils.isDebugMode())
						SNTUtils.error("Recording failure", ex);
				}
				if (error) {
					displayMsg("Recording failure...");
					guiUtils.error(doneMessage);
				}
				else displayMsg(doneMessage);
			}
		}

		private boolean okToApplyColor(final List<String> labelsOfselectedTrees) {
			if (!treesContainColoredNodes(labelsOfselectedTrees)) return true;
			return guiUtils.getConfirmation("Some of the selected reconstructions " +
				"seem to be color-coded. Apply homogeneous color nevertheless?",
				"Override Color Code?");
		}

		private JPopupMenu treesMenu() {
			final JPopupMenu tracesMenu = new JPopupMenu();
			GuiUtils.addSeparator(tracesMenu, "Add:");
			JMenuItem mi = new JMenuItem("Load File...", IconFactory.getMenuIcon(
				GLYPH.IMPORT));
			mi.setMnemonic('f');
			mi.addActionListener(e -> {
				final Map<String, Object> inputs = new HashMap<>();
				inputs.put("importDir", false);
				runImportCmd(LoadReconstructionCmd.class, inputs);
			});
			tracesMenu.add(mi);
			mi = new JMenuItem("Load Directory...", IconFactory.getMenuIcon(
				GLYPH.FOLDER));
			mi.addActionListener(e -> {
				final Map<String, Object> inputs = new HashMap<>();
				inputs.put("importDir", true);
				runImportCmd(LoadReconstructionCmd.class, inputs);
			});
			tracesMenu.add(mi);

			mi = new JMenuItem("Load & Compare Groups...", IconFactory.getMenuIcon(GLYPH.MAGIC));
			mi.addActionListener(e -> runImportCmd(GroupAnalyzerCmd.class, null));
			tracesMenu.add(mi);

			if (!isSNTInstance()) tracesMenu.add(loadDemoMenuItem());

			final JMenu remoteMenu = new JMenu("Load from Database");
			remoteMenu.setMnemonic('d');
			remoteMenu.setDisplayedMnemonicIndex(10);
			remoteMenu.setIcon(IconFactory.getMenuIcon(GLYPH.DATABASE));
			tracesMenu.add(remoteMenu);
			mi = new JMenuItem("FlyCircuit...", 'f');
			mi.addActionListener(e -> {
				final Map<String, Object> inputs = new HashMap<>();
				inputs.put("loader", new FlyCircuitLoader());
				runImportCmd(RemoteSWCImporterCmd.class, inputs);
			});
			remoteMenu.add(mi);
			mi = new JMenuItem("InsectBrain...", 'I');
			mi.addActionListener(e -> runImportCmd(InsectBrainImporterCmd.class, null));
			remoteMenu.add(mi);
			mi = new JMenuItem("MouseLight...", 'm');
			mi.addActionListener(e -> runImportCmd(MLImporterCmd.class, null));
			remoteMenu.add(mi);
			mi = new JMenuItem("NeuroMorpho...", 'n');
			mi.addActionListener(e -> {
				final Map<String, Object> inputs = new HashMap<>();
				inputs.put("loader", new NeuroMorphoLoader());
				runImportCmd(RemoteSWCImporterCmd.class, inputs);
			});
			remoteMenu.add(mi);

			GuiUtils.addSeparator(tracesMenu, "Customize & Adjust:");
			addCustomizeTreeCommands(tracesMenu);

			GuiUtils.addSeparator(tracesMenu, "Remove:");
			mi = new JMenuItem("Remove Selected...", IconFactory.getMenuIcon(
				GLYPH.DELETE));
			mi.addActionListener(e -> {
				final List<String> keys = getSelectedTreeLabels();
				if (keys == null || keys.isEmpty()) {
					guiUtils.error("There are no selected reconstructions.");
					return;
				}
				if (!guiUtils.getConfirmation("Delete " + keys.size() +
					" reconstruction(s)?", "Confirm Deletion"))
				{
					return;
				}
				setSceneUpdatesEnabled(false);
				keys.forEach(k -> removeTree(k));
				setSceneUpdatesEnabled(true);
				updateView();
			});
			tracesMenu.add(mi);
			mi = new JMenuItem("Remove All...", IconFactory.getMenuIcon(GLYPH.TRASH));
			mi.addActionListener(e -> {
				if (guiUtils.getConfirmation("Remove all reconstructions from scene?", "Remove All Reconstructions?"))
					removeAllTrees();
			});
			tracesMenu.add(mi);
			return tracesMenu;
		}

		private JMenuItem loadDemoMenuItem() {
			final JMenuItem mi = new JMenuItem("Load Demo(s)...", IconFactory.getMenuIcon(GLYPH.GRADUATION_CAP));
			mi.addActionListener(e -> {
				try {
					final DemoRunner demoRunner = new DemoRunner(SNTUtils.getContext());
					final Demo choice = demoRunner.getChoice();
					if (choice != null)
						addTrees(choice.getTrees(), "unique");
				} catch (final Throwable ex) {
					guiUtils.error(ex.getMessage());
					ex.printStackTrace();
				}
			});
			return mi;
		}

		private JMenu legendMenu() {
			// Legend Menu
			final JMenu legendMenu = new JMenu("Color Mapping Legends");
			legendMenu.setIcon(IconFactory.getMenuIcon(GLYPH.COLOR2));
			JMenuItem mi = new JMenuItem("Add...", IconFactory.getMenuIcon(GLYPH.PLUS));
			mi.addActionListener(e -> {
				final Map<String, Object> inputs = new HashMap<>();
				inputs.put("treeLabels", null);
				runCmd(ColorMapReconstructionCmd.class, inputs, CmdWorker.DO_NOTHING, true, false);
			});
			legendMenu.add(mi);
			mi = new JMenuItem("Edit Last...", IconFactory.getMenuIcon(GLYPH.SLIDERS));
			mi.addActionListener(e -> {
				if (cBar == null) {
					guiUtils.error("No Legend currently exists.");
					return;
				}
				if (cmdService == null)
					SNTUtils.getContext().inject(Viewer3D.this);
				class GetLegendSettings extends SwingWorker<Object, Object> {

					CommandModule cmdModule;

					@Override
					public Object doInBackground() {
						try {
							cmdModule = cmdService.run(CustomizeLegendCmd.class, true).get();
						}
						catch (final InterruptedException | ExecutionException ignored) {
							return null;
						}
						return null;
					}

					@Override
					protected void done() {
						if (cmdModule == null || cmdModule.isCanceled()) {
							return; // user pressed cancel or chose nothing
						}
						@SuppressWarnings("unchecked")
						final HashMap<String, Double> outMap = (HashMap<String, Double>) cmdModule.getInput("outMap");
						if (outMap == null) {
							guiUtils.error("Command execution failed.");
							return;
						}
						updateColorBarLegend(outMap.get("min"), outMap.get("max"), outMap.get("fSize").floatValue());
					}
				}
				(new GetLegendSettings()).execute();
			});
			legendMenu.add(mi);

			legendMenu.addSeparator();
			mi = new JMenuItem("Remove Last", IconFactory.getMenuIcon(GLYPH.DELETE));
			mi.addActionListener(e -> removeColorLegends(true));
			legendMenu.add(mi);
			mi = new JMenuItem("Remove All...", IconFactory.getMenuIcon(GLYPH.TRASH));
			mi.addActionListener(e -> {
				if (guiUtils.getConfirmation("Remove all color legends from scene?", "Remove All Legends?"))
					removeColorLegends(false);
			});
			legendMenu.add(mi);
			return legendMenu;
		}

		private JPopupMenu meshMenu() {
			final JPopupMenu meshMenu = new JPopupMenu();
			GuiUtils.addSeparator(meshMenu, "Add:");
			JMenuItem mi = new JMenuItem("Load OBJ File(s)...", IconFactory
				.getMenuIcon(GLYPH.IMPORT));
			mi.addActionListener(e -> runImportCmd(LoadObjCmd.class, null)); // LoadObjCmd will call validate()
			meshMenu.add(mi);
			addCustomizeMeshCommands(meshMenu);

			GuiUtils.addSeparator(meshMenu, "Remove:");
			mi = new JMenuItem("Remove Selected...", IconFactory.getMenuIcon(
				GLYPH.DELETE));
			mi.addActionListener(e -> {
				final List<String> keys = getSelectedMeshLabels();
				if (keys == null || keys.isEmpty()) {
					guiUtils.error("There are no selected meshes.");
					return;
				}
				if (!guiUtils.getConfirmation("Delete " + keys.size() + " mesh(es)?",
					"Confirm Deletion"))
				{
					return;
				}
				setSceneUpdatesEnabled(false);
				keys.forEach(k -> removeMesh(k));
				setSceneUpdatesEnabled(true);
				updateView();
			});
			meshMenu.add(mi);
			mi = new JMenuItem("Remove All...");
			mi.setIcon(IconFactory.getMenuIcon(GLYPH.TRASH));
			mi.addActionListener(e -> {
				if (guiUtils.getConfirmation("Remove all meshes from scene?", "Remove All Meshes?"))
					removeAllMeshes();
			});
			meshMenu.add(mi);
			return meshMenu;
		}

		private JPopupMenu annotationsMenu() {

			final JPopupMenu annotMenu = new JPopupMenu();
			GuiUtils.addSeparator(annotMenu, "Add:");
			JMenuItem mi = new JMenuItem("Cell-based Cross-section Plane...", IconFactory.getMenuIcon(GLYPH.SCISSORS));
			mi.setToolTipText("Adds cross-section plane(s) to neuronal arbors");
			mi.addActionListener(e -> {
				final List<Tree> trees = getSelectedTrees(true);
				if (trees == null)
					return;
				final AnnotPrompt prompt = new AnnotPrompt();
				final List<String> userAxes = prompt.getPromptPlaneAxes(true);
				if (userAxes == null)
					return;
				setSceneUpdatesEnabled(false);
				for (final Tree tree : trees) {
					final BoundingBox3d bounds = tree.getBoundingBox().toBoundingBox3d();
					for (final String axis : userAxes) {
						final PointInImage[] plane = (axis.toLowerCase().contains("soma")) ? prompt.getSomaPlane(bounds, tree, axis) : prompt.getPlane(bounds, axis) ;
						final Annotation3D annot = annotatePlane(plane[0], plane[1], tree.getLabel() + " [" + axis + "]");
						annot.setColor(toColorRGB(Utils.contrastColor(fromColorRGB(tree.getColor()))), 25);
					}
				}
				setSceneUpdatesEnabled(true);
			});
			annotMenu.add(mi);
			mi = new JMenuItem("Cell-based Surface...", IconFactory.getMenuIcon(GLYPH.DICE_20));
			mi.setToolTipText("Adds convex-hull tesselations to neuronal arbors");
			mi.addActionListener(e -> {
				final List<Tree> trees = getSelectedTrees(true);
				if (trees == null)
					return;
				final String[] choices = { "Branch points", "Tips" };
				final String choice = guiUtils.getChoice("Generate surface from which structures?",
						"Add Surface...", choices, choices[0]);
				if (choice == null)
					return;
				final List <String> failures = new ArrayList<>();
				setSceneUpdatesEnabled(false);
				for (final Tree tree : trees) {
					if (!tree.is3D()) {
						failures.add(tree.getLabel());
						continue;
					}
					Annotation3D annot;
					if (choice.startsWith("Branch"))
						annot = annotateSurface(new TreeAnalyzer(tree).getBranchPoints(),
								tree.getLabel() + " [BPs surface]", false);
					else
						annot = annotateSurface(new TreeAnalyzer(tree).getTips(),
								tree.getLabel() + " [Tips surface]", false);
					final ColorRGB color = tree.getColor();
					if (color != null)
						annot.setColor(toColorRGB(Utils.contrastColor(fromColorRGB(color))), 75);
				}
				setSceneUpdatesEnabled(true);
				if (!failures.isEmpty()) {
					guiUtils.error(("Surfaces cannot be assemble from these 2D reconstructions: " 
							+ failures.toString() +". Only 3D reconstructions are supported."));
				}
			});
			annotMenu.add(mi);
			mi = new JMenuItem("Mesh-based Cross-section Plane...", IconFactory.getMenuIcon(GLYPH.SCISSORS));
			mi.setToolTipText("Adds cross-section plane(s) to selected meshes");
			mi.addActionListener(e -> {
				final List<String> meshLabels = getSelectedMeshLabels();
				if (meshLabels == null)
					return;
				final AnnotPrompt prompt = new AnnotPrompt();
				final List<String> userAxes = prompt.getPromptPlaneAxes(false);
				if (userAxes == null)
					return;
				setSceneUpdatesEnabled(false);
				for (final String mLabel : meshLabels) {
					final BoundingBox3d bounds = plottedObjs.get(mLabel).getBounds();
					for (final String axis : userAxes) {
						final PointInImage[] plane = prompt.getPlane(bounds, axis);
						final Annotation3D annot = annotatePlane(plane[0], plane[1], mLabel + " [" + axis + "]");
						annot.setColor(toColorRGB(Utils.contrastColor(plottedObjs.get(mLabel).objMesh.getDrawable().getColor())), 25);
					}
				}
				setSceneUpdatesEnabled(true);
			});
			annotMenu.add(mi);
			mi = new JMenuItem("Mesh-based Surface...", IconFactory.getMenuIcon(GLYPH.DICE_20));
			mi.setToolTipText("Adds convex-hull tesselations to selected meshes");
			mi.addActionListener(e -> {
				final List<String> meshLabels = getSelectedMeshLabels();
				if (meshLabels == null)
					return;
				final String[] choices = { "Left hemisphere", "Right hemisphere", "Both hemispheres" };
				final String choice = guiUtils.getChoice("Generate surface from which vertices?",
						"Add Surface...", choices, choices[0]);
				if (choice == null)
					return;
				setSceneUpdatesEnabled(false);
				for (final String mLabel : meshLabels) {
					final String key = choice.split(" ")[0];
					final Collection<? extends SNTPoint> vertices = plottedObjs.get(mLabel).objMesh.getVertices(key.toLowerCase());
					final Annotation3D annot = annotateSurface(vertices, mLabel + " [" + (("Both".equals(key)) ? "Full" : key) + " surface]", false);
					final Color color = plottedObjs.get(mLabel).objMesh.getDrawable().getColor();
					if (color != null)
						annot.setColor(toColorRGB(Utils.contrastColor(color)), 75);
				}
				setSceneUpdatesEnabled(true);
			});
			annotMenu.add(mi);
			final JMenu primitives = new JMenu("Misc");
			primitives.setToolTipText("Adds basic geometry objects to the scene");
			primitives.setIcon(IconFactory.getMenuIcon(GLYPH.ELLIPSIS));
			annotMenu.add(primitives);
			mi = new JMenuItem("Sphere...", IconFactory.getMenuIcon(GLYPH.GLOBE));
			mi.addActionListener(e -> {
				final AnnotPrompt ap = new AnnotPrompt();
				if (ap.validPrompt("Sphere Properties...", new String[] { "Center X ", "Center Y ", "Center Z "}, null, true)) {
					final Annotation3D point = annotatePoint(ap.pim1, "Sphere", ap.color, (float) ap.size);
					point.setColor(point.getColor(), ap.t);
				}
			});
			primitives.add(mi);
			mi = new JMenuItem("Vector...", IconFactory.getMenuIcon(GLYPH.ARROWS_LR));
			mi.addActionListener(e -> {
				final AnnotPrompt ap = new AnnotPrompt();
				if (ap.validPrompt("Vector Properties...", new String[] { "X1 ", "Y1 ", "Z1 " },
						new String[] { "X2 ", "Y2 ", "Z2 " }, true)) {
					final Annotation3D line = annotateLine(List.of(ap.pim1, ap.pim2), "Vector");
					line.setColor(ap.color, ap.t);
					line.setSize((float) ap.size);
				}
			});
			primitives.add(mi);
			mi = new JMenuItem("Plane/Parallelepiped...", IconFactory.getMenuIcon(GLYPH.SQUARE));
			mi.addActionListener(e -> {
				final AnnotPrompt ap = new AnnotPrompt();
				if (ap.validPrompt("Plane/Parallelepiped Properties...", new String[] { "Origin X ", "Origin Y ", "Origin Z " },
						new String[] { "Origin Opposite X ", "Origin Opposite Y ", "Origin Opposite Z " }, false)) {
					final Annotation3D annot = annotatePlane(ap.pim1, ap.pim2, "Annot. Plane");
					annot.setColor(ap.color, ap.t);
				}
			});
			primitives.add(mi);
			GuiUtils.addSeparator(annotMenu, "Customize:");
			mi = new JMenuItem("Color...", IconFactory.getMenuIcon(GLYPH.COLOR));
			mi.addActionListener(e -> {
				final List<Annotation3D> annots = getSelectedAnnotations();
				if (annots == null)
					return;
				final ColorRGB c = new AnnotPrompt().getPromptColor("Annotation(s) Color...");
				if (c != null)
					annots.forEach(annot -> annot.setColor(c, -1));
			});
			annotMenu.add(mi);
			mi = new JMenuItem("Color Gradient...", IconFactory.getMenuIcon(GLYPH.COLOR2));
			mi.addActionListener(e -> {
				final List<Annotation3D> annots = getSelectedAnnotations();
				if (annots == null)
					return;
				final String[] res = new AnnotPrompt().getPromptGradient("Color Gradient..");
				if (res == null)
					return;
				final List <String> failures = new ArrayList<>();
				annots.forEach(annot -> {
					if (annot.isColorCodeAllowed())
						annot.colorCode(res[0], res[1]);
					else
						failures.add(annot.getLabel());
				});
				if (!failures.isEmpty())
					guiUtils.error(("The following annotations do not support color gradients: " + failures.toString()));
			});
			annotMenu.add(mi);
			mi = new JMenuItem("Transparency...", IconFactory.getMenuIcon(GLYPH.ADJUST));
			mi.addActionListener(e -> {
				final List<Annotation3D> annots = getSelectedAnnotations();
				if (annots == null)
					return;
				final Double t = new AnnotPrompt().getPromptTransparency("Annotation(s) Transparency...");
				if (t != null)
					annots.forEach(annot -> annot.setColor((ColorRGB) null, t));
			});
			annotMenu.add(mi);
			final JMenuItem material = new JMenuItem("Surface Rendering...", IconFactory.getMenuIcon(GLYPH.CUBES));
			material.addActionListener(e -> {
				if (noLoadedItemsGuiError()) {
					return;
				}
				if (!chart.getScene().getGraph().getAll().stream().anyMatch( g -> g instanceof AbstractEnlightable)) {
					guiUtils.error("Currently, only primitive shapes (spheres, disks, polygons, etc.) are supported. "
							+ "Current scene contains no such objects.");
					return;
				}
				final List<?> selectedKeys = managerList.getSelectedValuesList();
				if (selectedKeys.size() != 1 || CheckBoxList.ALL_ENTRY.equals(selectedKeys.get(0))) {
					guiUtils.error("This command operates on single objects.");
					return;
				}
				final String item = TagUtils.removeAllTags((String) selectedKeys.get(0));
				final Drawable d = getDrawableFromObject(item);
				if (d != null && d instanceof AbstractEnlightable)
					frame.displayLightController((AbstractEnlightable) d);
				else {
					guiUtils.error("Selected item does not support surface texture adjustments. "
							+ "Currently, only primitive shapes (spheres, disks, polygons, etc.) are supported.");
				}
			});
			annotMenu.add(material);
			GuiUtils.addSeparator(annotMenu, "Remove:");
			mi = new JMenuItem("Remove Selected...", IconFactory.getMenuIcon(GLYPH.DELETE));
			mi.addActionListener(e -> {
				final List<Annotation3D> annots = getSelectedAnnotations();
				if (annots == null || annots.isEmpty()) {
					guiUtils.error("There are no selected annotations.");
					return;
				}
				if (!guiUtils.getConfirmation("Delete " + annots.size() + " annotation(s)?", "Confirm Deletion")) {
					return;
				}
				setSceneUpdatesEnabled(false);
				annots.forEach(annot -> removeAnnotation(annot));
				setSceneUpdatesEnabled(true);
				updateView();
			});
			annotMenu.add(mi);
			mi = new JMenuItem("Remove All...");
			mi.setIcon(IconFactory.getMenuIcon(GLYPH.TRASH));
			mi.addActionListener(e -> {
				if (guiUtils.getConfirmation("Remove all annotations from scene?", "Remove All Annotations?"))
					removeAllAnnotations();
			});
			annotMenu.add(mi);
			return annotMenu;
		}

		private JPopupMenu refBrainsMenu() {
			final JPopupMenu refMenu = new JPopupMenu("Reference Brains");
			GuiUtils.addSeparator(refMenu, "Mouse:");
			JMenuItem mi = new JMenuItem("Allen CCF Navigator", IconFactory
					.getMenuIcon(GLYPH.NAVIGATE));
			mi.addActionListener(e -> {
				assert SwingUtilities.isEventDispatchThread();
				if (frame.allenNavigator != null) {
					frame.allenNavigator.dialog.toFront();
					return;
				}
				//final JDialog tempSplash = frame.managerPanel.guiUtils.floatingMsg("Loading ontologies...", false);
				addProgressLoad(-1);
				final SwingWorker<AllenCCFNavigator, ?> worker = new SwingWorker<>() {

					@Override
					protected AllenCCFNavigator doInBackground() {
						loadRefBrainAction(false, MESH_LABEL_ALLEN, false);
						setAxesLabels( AllenUtils.getXYZLabels());
						return new AllenCCFNavigator();
					}

					@Override
					protected void done() {
						try {
							get().show();
						} catch (final InterruptedException | ExecutionException e) {
							SNTUtils.error(e.getMessage(), e);
						} finally {
							//tempSplash.dispose();
							removeProgressLoad(-1);
						}
					}
				};
				worker.execute();
			});
			refMenu.add(mi);
			
			GuiUtils.addSeparator(refMenu, "Zebrafish:");
			mi = new JMenuItem("Max Planck ZBA", IconFactory.getMenuIcon(GLYPH.ARCHIVE));
			mi.addActionListener(e -> loadRefBrainAction(true, MESH_LABEL_ZEBRAFISH));
			refMenu.add(mi);

			GuiUtils.addSeparator(refMenu, "Drosophila:");
			mi = new JMenuItem("Adult Brain: FlyCircuit", IconFactory.getMenuIcon(GLYPH.ARCHIVE));
			mi.addActionListener(e -> loadRefBrainAction(true, MESH_LABEL_FCWB));
			refMenu.add(mi);
			mi = new JMenuItem("Adult Brain: JRC 2018 (Unisex)", IconFactory.getMenuIcon(GLYPH.ARCHIVE));
			mi.setToolTipText("<HTML>AKA <i>The Bogovic brain</i>");
			mi.addActionListener(e -> loadRefBrainAction(true, MESH_LABEL_JFRC2018));
			refMenu.add(mi);
			mi = new JMenuItem("Adult Brain: JFRC2", IconFactory.getMenuIcon(GLYPH.ARCHIVE));
			mi.addActionListener(e -> loadRefBrainAction(true, MESH_LABEL_JFRC2));
			refMenu.add(mi);
			mi = new JMenuItem("Adult Brain: JFRC3", IconFactory.getMenuIcon(GLYPH.ARCHIVE));
			mi.addActionListener(e -> loadRefBrainAction(true, MESH_LABEL_JFRC3));
			refMenu.add(mi);
			mi = new JMenuItem("Adult VNS", IconFactory.getMenuIcon(GLYPH.CLOUD));
			mi.addActionListener(e -> loadRefBrainAction(true, MESH_LABEL_VNS));
			refMenu.add(mi);
			mi = new JMenuItem("L1 Larva", IconFactory.getMenuIcon(GLYPH.CLOUD));
			mi.addActionListener(e -> loadRefBrainAction(true, MESH_LABEL_L1));
			refMenu.add(mi);
			mi = new JMenuItem("L3 Larva", IconFactory.getMenuIcon(GLYPH.CLOUD));
			mi.addActionListener(e -> loadRefBrainAction(true, MESH_LABEL_L3));
			refMenu.add(mi);
			refMenu.addSeparator();
			mi = GuiUtils.menuItemTriggeringHelpURL("Reference Brains Help",
					"https://imagej.net/plugins/snt/reconstruction-viewer#reference-brains");
			refMenu.add(mi);
			return refMenu;
		}

		private void loadRefBrainAction(final boolean warnIfLoaded, final String label) {
			loadRefBrainAction(warnIfLoaded, label, true);
		}

		private void loadRefBrainAction(final boolean warnIfLoaded, final String label, final boolean setProgressBar) {
			final boolean canProceed;
			switch (label) {
			case MESH_LABEL_L1:
			case MESH_LABEL_L3:
			case MESH_LABEL_VNS:
				canProceed = VFBUtils.isDatabaseAvailable();
				break;
			default:
				canProceed = true;
			}
			if (!canProceed) {
				guiUtils.error("Remote server not reached. It is either down or you have no internet access.");
				return;
			}
			if (warnIfLoaded && getOBJs().containsKey(label)) {
				guiUtils.error(label + " is already loaded.");
				return;
			}
			final String[] existingAxes = getAxesLabels();
			if (setProgressBar) addProgressLoad(-1);
			final SwingWorker<?, ?> worker = new SwingWorker<Boolean, Object>() {

				@Override
				protected Boolean doInBackground() {
					try {
						return loadRefBrainInternal(label) != null;
					} catch (final NullPointerException | IllegalArgumentException ex) {
						guiUtils.error("An error occurred and mesh could not be retrieved. See Console for details.");
						ex.printStackTrace();
						return false;
					} catch (final RuntimeException e2) {
						SNTUtils.error(e2.getMessage(), e2);
						return false;
					}
				}

				@Override
				protected void done() {
					try {
						if (get() && viewUpdatesEnabled) new Action(Action.RELOAD).run();
					} catch (final InterruptedException | ExecutionException e) {
						SNTUtils.error(e.getMessage(), e);
					} finally {
						if (setProgressBar) removeProgressLoad(-1);
						if (prefs.nagUserOnAxesChanges && !Arrays.equals(existingAxes, new String[] { "X", "Y", "Z" })
								&& !Arrays.equals(existingAxes, getAxesLabels())) {
							final Boolean prompt = guiUtils.getPersistentWarning(
									String.format("Cartesian axes relabeled to %s", Arrays.toString(getAxesLabels())),
									"Mapping of Cartesian Axes Changed");
							if (prompt != null) // do nothing if user dismissed the dialog
								prefs.nagUserOnAxesChanges = !prompt.booleanValue();
						}
					}
				}
			};
			worker.execute();
		}

		private void runImportCmd(final Class<? extends Command> cmdClass, final Map<String, Object> inputs) {
			runCmd(cmdClass, inputs, CmdWorker.DO_NOTHING, true, false); // cmd itself sets progressbar
		}

		private void runCmd(final Class<? extends Command> cmdClass,
			final Map<String, Object> inputs, final int cmdType,
			final boolean setRecViewerParamater, final boolean indeterminateProgress)
		{
			if (cmdService == null)
				SNTUtils.getContext().inject(Viewer3D.this);
			SwingUtilities.invokeLater(() -> {
				(new CmdWorker(cmdClass, inputs, cmdType, setRecViewerParamater, indeterminateProgress))
					.execute();
			});
		}

	}

	private class FileDropWorker {

		FileDropWorker(final Component component, final GuiUtils guiUtils) {
			new FileDrop(component, files -> {
				if (frame.managerPanel != null) {
					SwingUtilities.invokeLater(() ->  addProgressLoad(-1));
				}
				processFiles(files, true, null, guiUtils);
			});
		}

		void importFilesWithoutDrop(final File[] files, final ColorRGB baseColor) {
			processFiles(files, false, baseColor, guiUtils());
		}

		void processFiles(final File[] files, final boolean promptForConfirmation, final ColorRGB baseColor, final GuiUtils guiUtils) {

			final SwingWorker<?, ?> worker = new SwingWorker<Integer, Object>() {
				int[] failuresAndSuccesses = new int[2];
				private static final int ABORTED = 0;
				private static final int INVALID = 1;
				private static final int COMPLETED = 2;

				@Override
				protected Integer doInBackground() {

					final ArrayList<File> collection = new ArrayList<>();
					if (abortCurrentOperation || assembleFlatFileCollection(collection, files) == null) {
						return ABORTED;
					}
					if (collection.isEmpty()) {
						if (promptForConfirmation && guiUtils != null) guiUtils.error("Dragged file(s) do not contain valid data.");
						return INVALID;
					}
					if (promptForConfirmation && collection.size() > 10 && guiUtils != null) {
						assert SwingUtilities.isEventDispatchThread();
						final boolean[] confirmSplit = guiUtils.getConfirmationAndOption(
									"Are you sure you would like to import " + collection.size() + " files?<br>"
									+ "You can press 'Esc' at any time to interrupt import.",
									"Proceed with Batch Import?", "Import axons and dendrites separately",
									isSplitDendritesFromAxons());
						if (!confirmSplit[0]) {
							return ABORTED;
						}
						setSplitDendritesFromAxons(confirmSplit[1]);
					}
					setSceneUpdatesEnabled(false);
					failuresAndSuccesses = loadGuessingType(collection, baseColor);
					return COMPLETED;
				}

				@Override
				protected void done() {
					try {
						switch(get()) {
						case ABORTED:
							displayMsg("Drag & drop operation aborted");
							break;
						case COMPLETED:
							if (failuresAndSuccesses[1] > 0) validate();
							if (failuresAndSuccesses[0] > 0 && guiUtils != null)
								guiUtils.error("" + failuresAndSuccesses[0] + " of "
										+ (failuresAndSuccesses[0] + failuresAndSuccesses[1])
										+ " dropped file(s) could not be imported (Console may"
										+ " have more details if you have enabled \"Debug mode\").");
							break;
						default:
							break; // do nothing for now
						}
					} catch (InterruptedException | ExecutionException e) {
						e.printStackTrace();
					} finally {
						setSceneUpdatesEnabled(true);
						abortCurrentOperation = false;
						removeProgressLoad(-1);
					}
				}
			};
			addProgressLoad(-1);
			worker.execute();
		}

		private Collection<File> assembleFlatFileCollection(final Collection<File> collection, final File[] files) {
			if (files == null) return collection; // can happen while pressing 'Esc'!?
			for (final File file : files) {
				if (abortCurrentOperation) break;
				if (file == null) // can happen with large collections!?
					continue;
				else if (file.isDirectory())
					assembleFlatFileCollection(collection, file.listFiles());
				else //if (!file.isHidden())
					collection.add(file);
			}
			return (abortCurrentOperation) ? null : collection;
		}

		/**
		 * Returns {n. of failed imports, n. successful imports}. Assumes no directories
		 * in collection
		 */
		private int[] loadGuessingType(final Collection<File> files, final ColorRGB baseColor) {
			final int totalFiles = files.size();
			int previousProgress = Math.max(0, getCurrentProgress());
			addProgressLoad(totalFiles);
			final ColorRGB[] colors = getmportColors(baseColor, totalFiles);
			int failures = 0;
			int idx = 0;
			for (final File file : files) {

				if (abortCurrentOperation) {
					SNTUtils.log("Aborting...");
					displayMsg(String.format("%d file(s) loaded", (idx+1)));
					removeProgressLoad(getCurrentProgress() - previousProgress);
					previousProgress = 0;
					break;
				}

				if (!file.exists() || file.isDirectory()) {
					failures++;
					continue;
				}

				SNTUtils.log(String.format("Loading %d/%d: %s", (idx+1), totalFiles, file.getAbsolutePath()));
				final String fName = file.getName().toLowerCase();
				try {
					incrementProgress();
					final ColorRGB color = colors[idx];
					if(SNTUtils.isReconstructionFile(file)) {
						try {
							final Collection<Tree> treesInFile = Tree.listFromFile(file.getAbsolutePath());
							if (treesInFile.isEmpty()) {
								failures++;
							} else if (treesInFile.size() > 1) {
								addProgressLoad(treesInFile.size());
								Tree.assignUniqueColors(treesInFile);
								for (final Tree tree : treesInFile) {
									addTree(tree);
									incrementProgress();
								}
							} else {
								treesInFile.forEach(tree -> {
									tree.setColor(color);
									addTree(tree);
								});
							}
						} catch (final Exception ex) {
							failures++;
							SNTUtils.log("... failed");
						}
					} else if (fName.endsWith("obj")) {
						try {
							loadMesh(file.getAbsolutePath(), color, 75d);
						} catch (final Exception ex) {
							failures++;
							SNTUtils.log("... failed");
						}
					} else {
						failures++;
						SNTUtils.log("... failed. Not a supported file type");
					}

				} catch (final IllegalArgumentException ex) {
					SNTUtils.log("... failed " + ex.getMessage());
					failures++;
				}
				idx++;
			}
			removeProgressLoad(getCurrentProgress() - previousProgress);
			return new int[] { failures, (idx-failures) };
		}

		private ColorRGB[] getmportColors(final ColorRGB baseColor, final int n) {
			if (baseColor == null) {
				return SNTColor.getDistinctColors(n);
			} else {
				// this could be more sophisticated: Hue shading, LUT, gradient, etc.
				final ColorRGB[] colors = new ColorRGB[n];
				Arrays.fill(colors, baseColor);
				return colors;
			}
		}
	}

	private class AllenCCFNavigator {

		private SNTSearchableBar searchableBar;
		private DefaultTreeModel treeModel;
		private NavigatorTree tree;
		private JDialog dialog;
		private GuiUtils guiUtils;

		public AllenCCFNavigator() {
			treeModel = AllenUtils.getTreeModel(true);
			tree = new NavigatorTree(treeModel);
			tree.setVisibleRowCount(10);
			tree.setEditable(false);
			tree.getCheckBoxTreeSelectionModel().setDigIn(false);
			tree.setExpandsSelectedPaths(true);
			tree.setRootVisible(true);

			// Remove default folder/file icons on Windows L&F
			final DefaultTreeCellRenderer renderer = (DefaultTreeCellRenderer) tree.getActualCellRenderer();
			renderer.setLeafIcon(null);
			renderer.setClosedIcon(null);
			renderer.setOpenIcon(null);

			GuiUtils.expandAllTreeNodes(tree);
			tree.setClickInCheckBoxOnly(false);
			searchableBar = new SNTSearchableBar(new TreeSearchable(tree));
			searchableBar.setStatusLabelPlaceholder("CCF v"+ AllenUtils.VERSION);
			searchableBar.setVisibleButtons(
				SNTSearchableBar.SHOW_NAVIGATION | SNTSearchableBar.SHOW_HIGHLIGHTS |
				SNTSearchableBar.SHOW_SEARCH_OPTIONS | SNTSearchableBar.SHOW_STATUS);
			refreshTree(false);
		}

		private List<AllenCompartment> getCheckedSelection() {
			final TreePath[] treePaths = tree.getCheckBoxTreeSelectionModel().getSelectionPaths();
			if (treePaths == null || treePaths.length == 0) {
				guiUtils.error("There are no checked ontologies.");
				return null;
			}
			final List<AllenCompartment> list = new ArrayList<>(treePaths.length);
			for (final TreePath treePath : treePaths) {
				final DefaultMutableTreeNode selectedElement = (DefaultMutableTreeNode) treePath.getLastPathComponent();
				list.add((AllenCompartment) selectedElement.getUserObject());
			}
			return list;
		}

		private void refreshTree(final boolean repaint) {
			for (final String meshLabel : getOBJs().keySet())
				meshLoaded(meshLabel);
			if (repaint)
				tree.repaint();
		}

		private void meshLoaded(final String meshLabel) {
			setCheckboxSelected(meshLabel, true);
			setCheckboxEnabled(meshLabel);
		}

		private void meshRemoved(final String meshLabel) {
			setCheckboxSelected(meshLabel, false);
			setCheckboxEnabled(meshLabel);
		}

		private void setCheckboxEnabled(final String nodeLabel) {
			final DefaultMutableTreeNode node = getNode(nodeLabel);
			if (node == null)
				return;
			tree.isCheckBoxEnabled(new TreePath(node.getPath()));
		}

		private void setCheckboxSelected(final String nodeLabel, final boolean enable) {
			final DefaultMutableTreeNode node = getNode(nodeLabel);
			if (node == null)
				return;
			if (enable)
				tree.getCheckBoxTreeSelectionModel().addSelectionPath(new TreePath(node.getPath()));
			else
				tree.getCheckBoxTreeSelectionModel().removeSelectionPath(new TreePath(node.getPath()));
		}

		private DefaultMutableTreeNode getNode(final String nodeLabel) {
			final Enumeration<TreeNode> e = ((DefaultMutableTreeNode) tree.getModel().getRoot())
					.depthFirstEnumeration();
			while (e.hasMoreElements()) {
				final DefaultMutableTreeNode node = (DefaultMutableTreeNode) e.nextElement();
				final AllenCompartment compartment = (AllenCompartment) node.getUserObject();
				if (nodeLabel.equals(compartment.name())) {
					return node;
				}
			}
			return null;
		}

		private void downloadMeshes() {
			final List<AllenCompartment> compartments = getCheckedSelection();
			if (compartments == null || compartments.isEmpty())
				return;
			final SwingWorker<?, ?> worker = new SwingWorker<Void, Object>() {

				int loadedCompartments = 0;
				final ArrayList<String> failedCompartments = new ArrayList<>();

				@Override
				protected Void doInBackground() {
					setSceneUpdatesEnabled(compartments.size() == 1);
					for (final AllenCompartment compartment : compartments) {
						incrementProgress();
						if (plottedObjs.containsKey(compartment.toString())) {
							managerList.addCheckBoxListSelectedValue(compartment.toString(), true);
						} else {
							try {
								final OBJMesh msh = compartment.getMesh();
								if (msh == null) {
									failedCompartments.add(compartment.name());
									meshRemoved(compartment.name());
								} else {
									loadOBJMesh(msh);
									meshLoaded(compartment.name());
									loadedCompartments++;
								}
							} catch (final GLException | NullPointerException | IllegalArgumentException ex) {
								failedCompartments.add(compartment.name());
								meshRemoved(compartment.name());
							}
						}
					}
					return null;
				}

				@Override
				protected void done() {
					try {
						get();
						if (loadedCompartments > 0)
							Viewer3D.this.validate();
						if (loadedCompartments == 0 && failedCompartments.isEmpty()) {
							guiUtils.centeredMsg("Selected compartment(s) seem to be already loaded.", "Compartments Loaded");
						}
						else if (!failedCompartments.isEmpty()) {
							final StringBuilder sb = new StringBuilder(String.valueOf(loadedCompartments)).append("/")
									.append(loadedCompartments + failedCompartments.size())
									.append(" meshes retrieved. The following compartments failed to load:")
									.append("<br>&nbsp;<br>").append(String.join("; ", failedCompartments))
									.append("<br>&nbsp;<br>")
									.append("Either such meshes are not available or file(s) could not be reached. Check Console logs for details.");
							guiUtils.centeredMsg(sb.toString(), "Exceptions Occurred");
						}
					} catch (final InterruptedException | ExecutionException e) {
						SNTUtils.error(e.getMessage(), e);
					} finally {
						removeProgressLoad(compartments.size());
						setSceneUpdatesEnabled(true);
					}
				}
			};
			addProgressLoad(compartments.size());
			worker.execute();
		}

		private void showSelectionInfo() {
			final List<AllenCompartment> cs = getCheckedSelection();
			if (cs == null) return;
			cs.sort((c1, c2) -> {
				return c1.name().compareToIgnoreCase(c2.name());
			});
			final StringBuilder sb = new StringBuilder("<header>");
			sb.append(" <style>");
			sb.append("  tr:nth-of-type(odd) {background-color:#ccc;}");
			sb.append( " </style>");
			sb.append( "</header>");
			sb.append("<table>");
			sb.append("<tr>");
			sb.append("<th>Name</th>").append("<th>Acronym</th>").append("<th>Id</th>").append("<th>Parent</th>")
			.append("<th>Ontology depth</th>").append("<th>Alias(es)</th>");
			sb.append("</tr>");
			for (final AllenCompartment c : cs) {
				sb.append("<tr>");
				sb.append("<td style='text-align:left'>").append(c.name()).append("</td>");
				sb.append("<td style='text-align:center'>").append(c.acronym()).append("</td>");
				sb.append("<td style='text-align:center'>").append(c.id()).append("</td>");
				final AllenCompartment parent = c.getParent();
				sb.append("<td style='text-align:center'>").append((parent == null) ? "-" : parent.toString()).append("</td>");
				sb.append("<td style='text-align:center'>").append(c.getOntologyDepth()).append("</td>");
				sb.append("<td style='text-align:center'>").append(String.join(",", c.aliases())).append("</td>");
				sb.append("</tr>");
			}
			sb.append("</table>");
			guiUtils.showHTMLDialog(sb.toString(), "Info On Selected Compartments", false); // guiUtils is not null
		}

		private JDialog show() {
			dialog = new JDialog(frame, "Allen CCF Ontology");
			frame.allenNavigator = this;
			guiUtils = new GuiUtils(dialog);
			searchableBar.setGuiUtils(guiUtils);
			dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
			dialog.addWindowListener(new WindowAdapter() {

				@Override
				public void windowClosing(final WindowEvent e) {
					frame.allenNavigator = null;
					dialog.dispose();
				}
			});
			dialog.setContentPane(getContentPane());
			GuiUtils.collapseAllTreeNodes(tree); // compute sizes based on collapsed tree
			if (frame.manager != null) {
				dialog.setPreferredSize(frame.manager.getPreferredSize());
				dialog.setLocationRelativeTo(frame.manager);
			}
			dialog.pack();
			GuiUtils.expandAllTreeNodes(tree);
			cmdFinder.attach(dialog);
			dialog.setVisible(true);
			return dialog;
		}

		private void dispose() {
			if (dialog != null) dialog.dispose();
			searchableBar = null;
			treeModel = null;
			tree = null;
			dialog = null;
			guiUtils = null;
		}
		private JPanel getContentPane() {
			frame.managerPanel.setFixedHeight(searchableBar);
			final JScrollPane scrollPane = new JScrollPane(tree);
			tree.setComponentPopupMenu(popupMenu());
			tree.setVisibleRowCount(20);
			scrollPane.setWheelScrollingEnabled(true);
			final JPanel contentPane = new JPanel();
			contentPane.setLayout(new BoxLayout(contentPane, BoxLayout.Y_AXIS));
			contentPane.add(searchableBar);
			contentPane.add(scrollPane);
			contentPane.add(buttonPanel());
			return contentPane;
		}

		private JPanel buttonPanel() {
			final JPanel buttonPanel = new JPanel(new GridLayout(1,2));
			buttonPanel.setBorder(null);
			frame.managerPanel.setFixedHeight(buttonPanel);
			JButton button = new JButton(IconFactory.getButtonIcon(GLYPH.INFO));
			button.addActionListener(e -> showSelectionInfo());
			buttonPanel.add(button);
			button = new JButton(IconFactory.getButtonIcon(GLYPH.IMPORT));
			button.addActionListener(e -> {
				downloadMeshes();
			});
			buttonPanel.add(button);
			return buttonPanel;
		}

		private JPopupMenu popupMenu() {
			final JPopupMenu pMenu = new JPopupMenu();
			JMenuItem jmi = new JMenuItem("Clear Selection");
			jmi.addActionListener(e -> tree.clearSelection());
			pMenu.add(jmi);
			jmi = new JMenuItem("Collapse All");
			jmi.addActionListener(e -> GuiUtils.collapseAllTreeNodes(tree));
			pMenu.add(jmi);
			jmi = new JMenuItem("Expand All");
			jmi.addActionListener(e -> GuiUtils.expandAllTreeNodes(tree));
			pMenu.add(jmi);
			pMenu.addSeparator();
			pMenu.add(GuiUtils.menuItemTriggeringHelpURL("Online 2D Atlas Viewer", "https://atlas.brain-map.org/atlas?atlas=602630314"));
			pMenu.add(GuiUtils.menuItemTriggeringHelpURL("Online 3D Atlas Viewer", "https://connectivity.brain-map.org/3d-viewer"));
			return pMenu;
		}
		private class NavigatorTree extends CheckBoxTree {
			private static final long serialVersionUID = 1L;

			public NavigatorTree(final DefaultTreeModel treeModel) {
				super(treeModel);
				setCellRenderer(new CustomRenderer());
				super.setLargeModel(true);
			}

			@Override
			public boolean isCheckBoxEnabled(final TreePath treePath) {
				final DefaultMutableTreeNode selectedElement = (DefaultMutableTreeNode) treePath.getLastPathComponent();
				final AllenCompartment compartment = (AllenCompartment) selectedElement.getUserObject();
				return compartment.isMeshAvailable() && !getOBJs().containsKey(compartment.name());
			}
		}

		class CustomRenderer extends DefaultTreeCellRenderer {
			private static final long serialVersionUID = 1L;

			@Override
			public Component getTreeCellRendererComponent(final JTree tree, final Object value, final boolean sel,
					final boolean expanded, final boolean leaf, final int row, final boolean hasFocus) {
				final AllenCompartment ac = (AllenCompartment) ((DefaultMutableTreeNode) value).getUserObject();
				final Component treeCellRendererComponent = super.getTreeCellRendererComponent(tree, value, sel,
						expanded, leaf, row, hasFocus);
				treeCellRendererComponent.setEnabled(ac.isMeshAvailable());
				return treeCellRendererComponent;
			}
		}
	}

	/* Inspired by tips4java.wordpress.com/2008/10/19/list-editor/ */
	private class CheckboxListEditable extends CheckBoxList {

		private static final long serialVersionUID = 1L;
		private JPopupMenu editPopup;
		private javax.swing.JTextField editTextField;
		private final CustomListRenderer renderer;
		private final UpdatableListModel<Object> model;

		@SuppressWarnings("unchecked")
		public CheckboxListEditable(final UpdatableListModel<Object> model) {
			super(model);
			this.model = model;
			this.model.addElement(CheckBoxList.ALL_ENTRY);
			renderer = new CustomListRenderer((DefaultListCellRenderer) getActualCellRenderer());
			setCellRenderer(renderer);
			setVisibleRowCount(20);
			addMouseListener(new java.awt.event.MouseAdapter() {
				public void mouseClicked(final MouseEvent e) {
					if (e.getClickCount() == 2 && !((HandlerPlus) _handler).clicksInCheckBox(e) && model.getSize() > 1) {

						if (editPopup == null)
							createEditPopup();

						// Prepare the text field for editing
						final String selectedLabel = getSelectedValue().toString();
						if (ALL_ENTRY.toString().equals(selectedLabel)) {
							if (frame.managerPanel.noLoadedItemsGuiError())
								return;
							editTextField.setText("");
						} else {
							final String existingTags = TagUtils.getTagStringFromEntry(getSelectedValue().toString());
							if (!existingTags.isEmpty()) {
								editTextField.setText(existingTags);
								editTextField.selectAll();
							}
						}

						// Position the popup editor over top of the selected row
						final int row = getSelectedIndex();
						final java.awt.Rectangle r = getCellBounds(row, row);
						editPopup.setPreferredSize(new Dimension(r.width, r.height));
						editPopup.show(CheckboxListEditable.this, r.x, r.y);
						editTextField.requestFocusInWindow();
					} else {
						_handler.mouseClicked(e);
					}
				}
			});
		}

		@Override
		protected CheckBoxListCellRenderer createCellRenderer() {
			@SuppressWarnings("serial")
			class CheckBoxListCellRendererPlus extends CheckBoxListCellRenderer {
				public CheckBoxListCellRendererPlus() {
					super();
					_checkBox.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2 + new JLabel().getIconTextGap()));
				}
			}
			return new CheckBoxListCellRendererPlus();
		}

		@Override
		protected void paintComponent(final Graphics g) {
			super.paintComponent(g);
			if (isSNTInstance() && model.getSize() < 3 || model.getSize() < 2)
				GuiUtils.drawDragAndDropPlaceHolder(this, (Graphics2D) g);
		}

		@Override
		public int[] getCheckBoxListSelectedIndices() {
			final CheckBoxListSelectionModel listSelectionModel = getCheckBoxListSelectionModel();
			final int iMin = listSelectionModel.getMinSelectionIndex();
			final int iMax = listSelectionModel.getMaxSelectionIndex();
			if ((iMin < 0) || (iMax < 0)) {
				return new int[0];
			}
			final int[] temp = new int[1 + (iMax - iMin)];
			int n = 0;
			for (int i = iMin; i <= iMax; i++) {
				if (listSelectionModel.isAllEntryConsidered() && i == listSelectionModel.getAllEntryIndex()) {
					continue;
				}
				if (i > model.getSize() - 1) {
					// Workaround an OutOfBounds Exception
					break;
				}
				if (listSelectionModel.isSelectedIndex(i)) {
					temp[n] = i;
					n++;
				}
			}
			final int[] indices = new int[n];
			System.arraycopy(temp, 0, indices, 0, n);
			return indices;
		}

		@Override
		public Object[] getCheckBoxListSelectedValues() {
			final CheckBoxListSelectionModel listSelectionModel = getCheckBoxListSelectionModel();
			final int iMin = listSelectionModel.getMinSelectionIndex();
			final int iMax = listSelectionModel.getMaxSelectionIndex();
			if ((iMin < 0) || (iMax < 0)) {
				return new Object[0];
			}
			final Object[] temp = new Object[1 + (iMax - iMin)];
			int n = 0;
			for (int i = iMin; i <= iMax; i++) {
				if (listSelectionModel.isAllEntryConsidered() && i == listSelectionModel.getAllEntryIndex()) {
					continue;
				}
				if (i > model.getSize() - 1) {
					// Workaround an OutOfBounds Exception
					break;
				}
				if (listSelectionModel.isSelectedIndex(i)) {
					temp[n] = model.getElementAt(i);
					n++;
				}
			}
			final Object[] indices = new Object[n];
			System.arraycopy(temp, 0, indices, 0, n);
			return indices;
		}

		@SuppressWarnings("unchecked")
		private Set<String> getCheckBoxListSelectedValuesAsSet() {
			return (Set<String>) (Set<?>) Arrays.stream(getCheckBoxListSelectedValues()).collect(Collectors.toSet());
		}

		@Override
		protected Handler createHandler() {
			return new HandlerPlus(this);
		}

		class HandlerPlus extends CheckBoxList.Handler {

			public HandlerPlus(final CheckBoxList list) {
				super(list);
			}

			@Override
			protected boolean clicksInCheckBox(final MouseEvent e) {
				return super.clicksInCheckBox(e); // make method accessible
			}

		}

		public void setIconsVisible(final boolean b) {
			renderer.setIconsVisible(b);
			update();
		}

		private void update() {
			model.update();
		}

		@SuppressWarnings({ "unchecked", "rawtypes" })
		private void createEditPopup() {
			// Use a text field as the editor
			editTextField = GuiUtils.textField("Tags:");
			final Border border = javax.swing.UIManager.getBorder("List.focusCellHighlightBorder");
			editTextField.setBorder(border);
			// Add an Action to the text field to save the new value to the model
			editTextField.addActionListener(e -> {
				final String tag = editTextField.getText();
				final int row = getSelectedIndex();
				final String existingEntry = ((DefaultListModel) getModel()).get(row).toString();
				if (ALL_ENTRY.toString().equals(existingEntry) && !tag.trim().isEmpty()) {
					applyTagToSelectedItems(tag, true);
				} else {
					if (tag.trim().isEmpty()) {
						removeTagsFromSelectedItems(false);
					} else {
						// textfield contained all tags
						final String existingEntryWithoutTags = TagUtils.getUntaggedEntry(existingEntry);
						final String newEntry = TagUtils.applyTag(existingEntryWithoutTags, tag);
						((DefaultListModel<String>) getModel()).set(row, newEntry);
					}
				}
				editPopup.setVisible(false);
			});
			editTextField.addFocusListener(new FocusListener() {

				@Override
				public void focusGained(final FocusEvent e) {
					getManagerPanel().disableActions = true;
				}

				@Override
				public void focusLost(final FocusEvent e) {
					getManagerPanel().disableActions = false;
				}

			});
			// Add the editor to the popup
			editPopup = new JPopupMenu();
			editPopup.setBorder(new javax.swing.border.EmptyBorder(0, 0, 0, 0));
			editPopup.add(editTextField);
			editPopup.setPreferredSize(getPreferredSize());
			editTextField.setFocusable(true);
			editPopup.addPopupMenuListener(new PopupMenuListener() {
				// https://stackoverflow.com/a/16276936
				@Override
				public void popupMenuWillBecomeVisible(final PopupMenuEvent e) {
					SwingUtilities.invokeLater(() -> editTextField.requestFocusInWindow());
				}
				@Override
				public void popupMenuWillBecomeInvisible(final PopupMenuEvent e) {}
				@Override
				public void popupMenuCanceled(final PopupMenuEvent e) {}
			});
		}

		@Override
		public int[] getSelectedIndices() {
			if ((ALL_ENTRY == getSelectedValue()) || (null == getSelectedValue() && prefs.retrieveAllIfNoneSelected)) {
				final int[] selectedIndices = new int[super.getModel().getSize() - 1];
				for (int i = 0; i < selectedIndices.length; i++)
					selectedIndices[i] = i;
				return selectedIndices;
			} else {
				return super.getSelectedIndices();
			}
		}

		@SuppressWarnings({ "unchecked" })
		void applyRenderedColorsToSelectedItems(final boolean allOtherwiseSelectedOnly) {
			final int[] indices = (allOtherwiseSelectedOnly) ? IntStream.range(0, getModel().getSize()).toArray() : getSelectedIndices();
			for (final int i : indices) {
				if (CheckBoxList.ALL_ENTRY.equals(getModel().getElementAt(i)))
					continue;
				final String taggedEntry = (String) getModel().getElementAt(i);
				final String entry = TagUtils.getUntaggedEntry(taggedEntry);
				Color color = null;
				if (plottedTrees.get(entry) != null) {
					color = plottedTrees.get(entry).getArborColor();
					if (color == null)
						color = plottedTrees.get(entry).getSomaColor();
				} else if (plottedObjs.get(entry) != null && plottedObjs.get(entry).getColor() != null) {
					color = plottedObjs.get(entry).getColor();
					if (color == null)
						color = plottedObjs.get(entry).getBoundingBoxColor();
				} else if (plottedAnnotations.get(entry) != null) {
					color = plottedAnnotations.get(entry).getDrawableColor();
				}
				if (color != null) {
					((DefaultListModel<String>) getModel()).set(i, TagUtils.applyColor(taggedEntry, toColorRGB(color)));
				}
			}
		}
	
		@SuppressWarnings({ "unchecked" })
		void applyTagToSelectedItems(final String tag, final boolean allOtherwiseSelectedOnly) {
			final String cleansedTag = TagUtils.getCleansedTag(tag);
			if (cleansedTag.trim().isEmpty()) return;
			final int[] indices = (allOtherwiseSelectedOnly) ? IntStream.range(0, getModel().getSize()).toArray() : getSelectedIndices();
			for (final int i :indices) {
				if (i == getCheckBoxListSelectionModel().getAllEntryIndex())
					continue;
				final String entry = (String) ((DefaultListModel<?>) getModel()).get(i);
				((DefaultListModel<String>) getModel()).set(i, TagUtils.applyTag(entry, tag));
			}
		}

		@SuppressWarnings("unchecked")
		void removeTagsFromSelectedItems(final boolean allOtherwiseSelectedOnly) {
			final int[] indices = (allOtherwiseSelectedOnly) ? IntStream.range(0, getModel().getSize()).toArray() : getSelectedIndices();
			for (final int i : indices) {
				if (i == getCheckBoxListSelectionModel().getAllEntryIndex())
					continue;
				final String entry = (String) ((DefaultListModel<?>) getModel()).get(i);
				((DefaultListModel<String>) getModel()).set(i, TagUtils.removeAllTags(entry));
			}
		}


		class CustomListRenderer extends DefaultListCellRenderer {
			private static final long serialVersionUID = 1L;
			private final Icon treeIcon = IconFactory.getListIcon(GLYPH.TREE);
			private final Icon meshIcon = IconFactory.getListIcon(GLYPH.CUBE);
			private final Icon annotationIcon = IconFactory.getListIcon(GLYPH.MARKER);
			private boolean iconVisible;

			CustomListRenderer(final DefaultListCellRenderer templateInstance) {
				super();
				// Apply properties from templateInstance
				setBorder(templateInstance.getBorder());
				setIconsVisible(true); // label categories by default
			}

			void setIconsVisible(final boolean iconVisible) {
				this.iconVisible = iconVisible;
			}

			@Override
			public Component getListCellRendererComponent(final JList<?> list, final Object value, final int index,
					final boolean isSelected, final boolean cellHasFocus) {
				final JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected,
						cellHasFocus);
				final String labelText = TagUtils.removeAllTags(label.getText());
				if (CheckBoxList.ALL_ENTRY.toString().equals(labelText))
					return label;
				if (iconVisible) {
					if (plottedAnnotations.containsKey(labelText))
						label.setIcon(annotationIcon);
					else if (plottedObjs.containsKey(labelText))
						label.setIcon(meshIcon);
					else
						label.setIcon(treeIcon);
				} else {
					label.setIcon(null);
				}
				final String[] tags = TagUtils.getTagsFromEntry(label.getText());
				for (final String tag : tags) {
					final ColorRGB c = ColorRGB.fromHTMLColor(tag);
					if (c != null && c.getARGB() != label.getBackground().getRGB()) {
						label.setForeground(new java.awt.Color(c.getRed(), c.getGreen(), c.getBlue()));
						break;
					}
				}
				return label;
			}
		}

	}

	static final class TagUtils {

		private TagUtils(){}

		static String getCleansedTag(final String candidate) {
			return candidate.replace("{", "").replace("}", "");
		}

		static String applyColor(final String entry, final ColorRGB color) {
			final String[] existingTags = getTagsFromEntry(entry);
			for (final String existingTag : existingTags) {
				final ColorRGB c = ColorRGB.fromHTMLColor(existingTag);
				if (c != null) {
					entry.replace(existingTag, color.toHTMLColor());
					return entry;
				}
			}
			if (!entry.contains("}")) {
				final StringBuilder sb = new StringBuilder(entry);
				sb.append(" {").append(color.toHTMLColor()).append("}");
				return sb.toString();
			} else {
				return entry.replace("}", ", " + color.toHTMLColor() + "}");
			}
		}

		static String applyTag(final String entry, final String tag) {
			final String cleansedTag = getCleansedTag(tag);
			if (cleansedTag.trim().isEmpty()) return entry;
			if (!entry.contains("}")) {
				final StringBuilder sb = new StringBuilder(entry);
				sb.append(" {").append(cleansedTag).append("}");
				return sb.toString();
			} else {
				return entry.replace("}", ", " + cleansedTag + "}");
			}
		}

		static String removeAllTags(final String entry) {
			final int delimiterIdx = entry.indexOf(" {");
			if (delimiterIdx == -1) {
				return entry;
			} else {
				return entry.substring(0, delimiterIdx);
			}
		}

		static String[] getTagsFromEntry(final String entry) {
			final String tagString = getTagStringFromEntry(entry);
			if (tagString.isEmpty()) return new String[] {};
			return tagString.split("\\s*(,|\\s)\\s*");
		}

		static String getTagStringFromEntry(final String entry) {
			final int openingDlm = entry.indexOf(" {");
			final int closingDlm = entry.lastIndexOf("}");
			if (closingDlm > openingDlm) {
				return entry.substring(openingDlm + 2, closingDlm);
			}
			return "";
		}

		static String getUntaggedEntry(final String entry) {
			final int openingDlm = entry.indexOf(" {");
			if (openingDlm == -1) {
				return entry;
			} else {
				return entry.substring(0, openingDlm);
			}
		}

		static String[] getUntaggedAndTaggedLabels(final String entry) {
			final int openingDlm = entry.indexOf(" {");
			if (openingDlm == -1) {
				return new String[] {entry, entry};
			} else {
				return new String[] {entry.substring(0, openingDlm), entry};
			}
		}

	}

	class UpdatableListModel<T> extends DefaultListModel<T> {
		private static final long serialVersionUID = 1L;
		private boolean listenersEnabled = true;

		@SuppressWarnings("unchecked")
		public void sort() {
			final List<T> list = new ArrayList<>();
			for (int i = 0; i < getSize(); i++) {
				final T o = get(i);
				if (!CheckBoxList.ALL_ENTRY.equals(o))
					list.add(o);
			}
			list.sort(Comparator.comparing(T::toString));
			list.add((T) CheckBoxList.ALL_ENTRY);
			setListenersEnabled(false);
			removeAllElements();
			addAll(list);
			setListenersEnabled(true);
		}

		public void update() {
			callFireContentsChangedOnSuper(this, 0, getSize() - 1);
		}

		public boolean getListenersEnabled() {
			return listenersEnabled;
		}

		public void setListenersEnabled(final boolean enabled) {
			listenersEnabled = enabled;
			if (frame != null && frame.managerPanel != null && managerList != null) {
				managerList.getCheckBoxListSelectionModel().setValueIsAdjusting(!listenersEnabled);
				if (listenersEnabled) update();
			}
		}

		private void callFireContentsChangedOnSuper(final Object source, final int index0, final int index1) {
			super.fireContentsChanged(source, index0, index1);
			if (frame != null && frame.managerPanel != null) {
				frame.managerPanel.searchableBar.setStatusLabelPlaceholder(String.format(
						"%d item(s) listed", size() - 1));
			}
		}

		@Override
		public void fireContentsChanged(final Object source, final int index0, final int index1) {
			if (getListenersEnabled()) {
				callFireContentsChangedOnSuper(source, index0, index1);
			}
		}

		@Override
		public void fireIntervalAdded(final Object source, final int index0, final int index1) {
			if (getListenersEnabled()) {
				super.fireIntervalAdded(source, index0, index1);
			}
		}

		@Override
		public void fireIntervalRemoved(final Object source, final int index0, final int index1) {
			if (getListenersEnabled()) {
				super.fireIntervalAdded(source, index0, index1);
			}
		}
	}

	/**
	 * Closes and releases all the resources used by this viewer.
	 */
	public void dispose() {
		setAnimationEnabled(false);
		if (frame != null) {
			frame.disposeFrame();
			frame = null;
		}
		if (chart != null) {
			chart.dispose();
			chart = null;
		}
		if (view != null) {
			view.dispose();
			view = null;
		}
		if (recorder != null) {
			recorder.dispose();
			recorder = null;
		}
		if (cmdFinder != null) {
			cmdFinder.dispose();
			cmdFinder = null;
		}
		keyController = null;
		mouseController = null;
		plottedTrees = null;
		plottedObjs = null;
		plottedAnnotations = null;
		prefs = null;
		defColor = null;
		cBar = null;
		managerList = null;
		gUtils = null;
		currentView = null;
		fileDropWorker = null;
		SNTUtils.removeViewer(this);
	}

	private int getSubTreeCompartment(final String compartment) {
		if (compartment == null || compartment.length() < 2) return -1;
		switch (compartment.toLowerCase().substring(0, 2)) {
		case "ax":
			return ShapeTree.AXON;
		case "ap":
		case "ba":
		case "(b":
		case "de":
			return ShapeTree.DENDRITE;
		default:
			return ShapeTree.ANY;
		}
	}

	private void toggleControlPanel() {
		if (frame.manager!= null) {
			frame.manager.setVisible(!frame.manager.isVisible());
			if (frame.manager.isVisible()) {
				frame.manager.toFront();
				frame.manager.requestFocus();
			} else {
				frame.toFront();
				frame.requestFocus();
				frame.canvas.requestFocusInWindow();
			}
		} else
			displayMsg("Controls are not available for this viewer");
	}

	private class MultiTreeShapeTree extends ShapeTree {

		Collection<Tree> trees;

		public MultiTreeShapeTree(final Tree unused) {
			super(unused);
		}

		public MultiTreeShapeTree(final Collection<Tree> trees) {
			super(new Tree());
			this.trees = trees;
			trees.forEach(t -> tree.list().addAll(t.list()));
		}
	}

	private class ShapeTree extends Shape {

		private static final float SOMA_SCALING_FACTOR = 2.5f;
		private static final float SOMA_SLICES = 15f; // Sphere default;
		private static final int DENDRITE = Path.SWC_DENDRITE;
		private static final int AXON = Path.SWC_AXON;
		private static final int ANY = -1;

		protected final Tree tree;
		private Shape treeSubShape;
		private Wireframeable somaSubShape;
		private Coord3d translationReset;

		public ShapeTree(final Tree tree) {
			super();
			this.tree = tree;
			translationReset = new Coord3d(0f,0f,0f);
		}

		@Override
		public boolean isDisplayed() {
			return ((somaSubShape != null) && somaSubShape.isDisplayed()) ||
					((treeSubShape != null) && treeSubShape.isDisplayed());
		}

		@Override
		public void setDisplayed(final boolean displayed) {
			get();
			super.setDisplayed(displayed);
		}

		public void setSomaDisplayed(final boolean displayed) {
			if (somaSubShape != null) somaSubShape.setDisplayed(displayed);
		}

		public void setArborDisplayed(final boolean displayed) {
			if (treeSubShape != null) treeSubShape.setDisplayed(displayed);
		}

		public Shape get() {
			if (components == null || components.isEmpty()) assembleShape();
			return this;
		}

		public void translateTo(final Coord3d destination) {
			final Transform tTransform = new Transform(new Translate(destination));
			get().applyGeometryTransform(tTransform);
			translationReset.subSelf(destination);
		}

		public void resetTranslation() {
			translateTo(translationReset);
			translationReset = new Coord3d(0f, 0f, 0f);
		}

		private void assembleShape() {

			final List<LineStripPlus> lines = new ArrayList<>();
			final List<SWCPoint> somaPoints = new ArrayList<>();
			final List<java.awt.Color> somaColors = new ArrayList<>();
			final boolean validSoma = tree.validSoma();

			for (final Path p : tree.list()) {

				// Stash soma coordinates
				if (validSoma && Path.SWC_SOMA == p.getSWCType()) {
					for (int i = 0; i < p.size(); i++) {
						final PointInImage pim = p.getNode(i);
						final SWCPoint swcPoint = new SWCPoint(-1, Path.SWC_SOMA, pim.x, pim.y, pim.z,
								p.getNodeRadius(i), -1);
						somaPoints.add(swcPoint);
					}
					if (p.hasNodeColors()) {
						somaColors.addAll(Arrays.asList(p.getNodeColors()));
					}
					else {
						somaColors.add(p.getColor());
					}
					continue;
				}

				// Assemble arbor(s)
				final LineStripPlus line = new LineStripPlus(p.size(), p.getSWCType());
				final Color fallbackColor = getDefColor().alpha(1f);
				for (int i = 0; i < p.size(); ++i) {
					final PointInImage pim = p.getNode(i);
					final Color color =
							fromAWTColor(p.hasNodeColors() ? p.getNodeColor(i) : p.getColor(), fallbackColor);
					final float width = Math.max((float) p.getNodeRadius(i), DEF_NODE_RADIUS);
					if (i == 0 && p.getStartJoinsPoint() != null) {
						final Coord3d joint = new Coord3d(p.getStartJoinsPoint().x, p.getStartJoinsPoint().y, p.getStartJoinsPoint().z);
						line.add(new Point(joint, color, width));
					}
					line.add(new Point(new Coord3d(pim.x, pim.y, pim.z), color, width));
				}
				line.setShowPoints(false);
				line.setWireframeWidth(defThickness);
				lines.add(line);
			}

			// Group all lines into a Composite. BY default the composite
			// will have no wireframe color, to allow colors for Paths/
			// nodes to be revealed. Once a wireframe color is explicit
			// set it will be applied to all the paths in the composite
			if (!lines.isEmpty()) {
				treeSubShape = new Shape();
				treeSubShape.setWireframeColor(null);
				treeSubShape.add(lines);
				add(treeSubShape);
			}
			assembleSoma(somaPoints, somaColors);
			if (somaSubShape != null) add(somaSubShape);
			// shape.setFaceDisplayed(true);
			// shape.setWireframeDisplayed(true);
		}

		private void assembleSoma(final List<SWCPoint> somaPoints,
			final List<java.awt.Color> somaColors)
		{
			final Color color = fromAWTColor(SNTColor.average(somaColors));
			switch (somaPoints.size()) {
				case 0:
					//SNT.log(tree.getLabel() + ": No soma attribute");
					somaSubShape = null;
					return;
				case 1:
					// single point soma: http://neuromorpho.org/SomaFormat.html
					somaSubShape = sphere(somaPoints.get(0), color);
					return;
				case 3:
					// 3 point soma representation: http://neuromorpho.org/SomaFormat.html
					final SWCPoint p1 = somaPoints.get(0);
					final SWCPoint p2 = somaPoints.get(1);
					final SWCPoint p3 = somaPoints.get(2);
					final Tube t1 = tube(p2, p1, color);
					final Tube t2 = tube(p1, p3, color);
					final Shape composite = new Shape();
					composite.add(t1);
					composite.add(t2);
					somaSubShape = composite;
					return;
				default:
					// just create a centroid sphere
					somaSubShape = sphere(SNTPoint.average(somaPoints), color);
					return;
			}
		}

		private <T extends Wireframeable & ISingleColorable> void
			setWireFrame(final T t, final float r, final Color color)
		{
			t.setColor(Utils.contrastColor(color).alphaSelf(0.4f));
			t.setWireframeColor(color.alphaSelf(0.8f));
			t.setWireframeWidth(Math.max(1f, r / SOMA_SLICES / 3));
			t.setWireframeDisplayed(true);
		}

		private Tube tube(final SWCPoint bottom, final SWCPoint top,
			final Color color)
		{
			final Tube tube = new Tube();
			tube.setPosition(new Coord3d((bottom.x + top.x) / 2, (bottom.y + top.y) /
				2, (bottom.z + top.z) / 2));
			final float height = (float) bottom.distanceTo(top);
			tube.setVolume((float) bottom.radius, (float) top.radius, height);
			tube.setColor(color);
			return tube;
		}

		private Sphere sphere(final PointInImage center, final Color color) {
			final Sphere s = new Sphere();
			s.setPosition(new Coord3d(center.x, center.y, center.z));
			final double r = (center instanceof SWCPoint) ? ((SWCPoint) center).radius : center.v;
			final float treeThickness = (treeSubShape == null) ? defThickness : treeSubShape.getWireframeWidth();
			final float radius = (float) Math.max(r, SOMA_SCALING_FACTOR * treeThickness);
			s.setVolume(radius);
			setWireFrame(s, radius, color);
			return s;
		}

		public void rebuildShape() {
			if (isDisplayed()) {
				clear();
				assembleShape();
			}
		}

		public void setSomaRadius(final float radius) {
			if (somaSubShape != null && somaSubShape instanceof Sphere)
				((Sphere)somaSubShape).setVolume(radius);
		}

		private void setThickness(final float thickness, final int type) {
			if (treeSubShape == null) return;
			if (type == ShapeTree.ANY) {
				treeSubShape.setWireframeWidth(thickness);
			}
			else for (int i = 0; i < treeSubShape.size(); i++) {
				final LineStripPlus ls = ((LineStripPlus) treeSubShape.get(i));
				if (ls.type == type) {
					ls.setWireframeWidth(thickness);
				}
			}
		}

		private void setArborColor(final ColorRGB color, final int type) {
			setArborColor(fromColorRGB(color), type);
		}

		private void setArborColor(final Color color, final int type) {
			if (treeSubShape == null) return;
			if (type == -1) {
				treeSubShape.setWireframeColor(color);
			}
			else for (int i = 0; i < treeSubShape.size(); i++) {
				final LineStripPlus ls = ((LineStripPlus) treeSubShape.get(i));
				if (ls.type == type) {
					ls.setColor(color);
				}
			}
		}

		private Color getArborColor() {
			if (treeSubShape == null)
				return null;
			if (treeSubShape.getWireframeColor() != null)
				return treeSubShape.getWireframeColor();
			LineStripPlus ls = ((LineStripPlus) treeSubShape.get(0));
			if (ls.getWireframeColor() != null) {
				return ls.getWireframeColor();
			}
			ls = ((LineStripPlus) treeSubShape.get(treeSubShape.size() - 1));
			if (ls.getWireframeColor() != null)
				return ls.getWireframeColor();
			return fromColorRGB(tree.getColor());
		}

		private Color getSomaColor() {
			return (somaSubShape == null) ? null : somaSubShape.getWireframeColor();
		}

		private void setSomaColor(final Color color) {
			if (somaSubShape != null) somaSubShape.setWireframeColor(color);
		}

		public void setSomaColor(final ColorRGB color) {
			setSomaColor(fromColorRGB(color));
		}

		public double[] colorize(final String measurement,
			final ColorTable colorTable)
		{
			final TreeColorMapper colorizer = new TreeColorMapper();
			colorizer.map(tree, measurement, colorTable);
			rebuildShape();
			return colorizer.getMinMax();
		}

	}

	private static class LineStripPlus extends LineStrip {
		final int type;

		LineStripPlus(final int size, final int type) {
			super(size);
			this.type = (type == Path.SWC_APICAL_DENDRITE) ? Path.SWC_DENDRITE : type;
		}

		@SuppressWarnings("unused")
		boolean isDendrite() {
			return type == Path.SWC_DENDRITE;
		}

		@SuppressWarnings("unused")
		boolean isAxon() {
			return type == Path.SWC_AXON;
		}

	}

	protected static class Utils {

		protected static Color contrastColor(final Color color) {
			final float factor = 0.75f;
			return new Color(factor - color.r, factor - color.g, factor - color.b);
		}

	}

	private class CmdWorker extends SwingWorker<Boolean, Object> {

		private static final int DO_NOTHING = 0;
		private static final int VALIDATE_SCENE = 1;
		private static final int RELOAD_PREFS = 2;


		private final Class<? extends Command> cmd;
		private final Map<String, Object> inputs;
		private final int type;
		private final boolean setRecViewerParamater;
		private final boolean indeterminateProgress;

		public CmdWorker(final Class<? extends Command> cmd,
			final Map<String, Object> inputs, final int type,
			final boolean setRecViewerParamater, final boolean indeterminateProgress)
		{
			this.cmd = cmd;
			this.inputs = inputs;
			this.type = type;
			this.setRecViewerParamater = setRecViewerParamater;
			this.indeterminateProgress = indeterminateProgress && getManagerPanel() != null;
			if (this.indeterminateProgress) getManagerPanel().showProgress(-1, -1);
		}

		@Override
		public Boolean doInBackground() {
			try {
				final Map<String, Object> input = new HashMap<>();
				if (setRecViewerParamater) input.put("recViewer", Viewer3D.this);
				if (inputs != null) input.putAll(inputs);
				cmdService.run(cmd, true, input).get(); //FIXME: This returns null all the time with DynamicCommands and does not wait for get()
				return true;
			}
			catch (final InterruptedException | ExecutionException e2) {
				if (gUtils != null) {
					gUtils.error("Unfortunately an exception occurred. See console for details.");
					if (indeterminateProgress) getManagerPanel().showProgress(0, 0);
				}
				e2.printStackTrace();
				return false;
			}
		}

		@Override
		protected void done() {
			boolean status = false;
			try {
				status = get();
				if (status) {
					switch (type) {
						case VALIDATE_SCENE:
							validate();
							break;
						case RELOAD_PREFS:
							prefs.setPreferences();
							break;
						case DO_NOTHING:
						default:
							break;
					}
				}
			}
			catch (final Exception ignored) {
				// do nothing
			} finally {
				if (indeterminateProgress) getManagerPanel().showProgress(0, 0);
			}
		}
	}

	private class MouseController extends AWTCameraMouseController {

		private float panStep = Prefs.PAN.MEDIUM.step;
		private boolean panDone;
		private Coord3d prevMouse3d;

		public MouseController(final Chart chart) {
			super(chart);
			addThread(new CameraThreadControllerPlus(chart)); // will removeThreadController
		}

		private int getY(final MouseEvent e) {
			return -e.getY() + chart.getCanvas().getRendererHeight();
		}

		private boolean recordRotation(final double endAngle, final int nSteps,
			final File dir)
		{

			if (!dir.exists()) dir.mkdirs();
			final double inc = Math.toRadians(endAngle) / nSteps;
			int step = 0;
			boolean status = true;

			// Make canvas dimensions divisible by 2 as most video encoders will
			// request it. Also, do not allow it to resize during the recording
			if (frame != null) {
				final int w = frame.canvas.getWidth() - (frame.canvas.getWidth() % 2);
				final int h = frame.canvas.getHeight() - (frame.canvas.getHeight() % 2);
				frame.canvas.setSize(w, h);
				frame.setResizable(false);
			}
			addProgressLoad(nSteps);
			while (step++ < nSteps) {
				try {
					final File f = new File(dir, String.format("%05d.png", step));
					rotate(new Coord2d(inc, 0d), false);
					chart.screenshot(f);
					incrementProgress();
				}
				catch (final IOException e) {
					status = false;
				}
			}
			if (frame != null) frame.setResizable(true);
			removeProgressLoad(nSteps);
			return status;
		}

		@Override
		public boolean handleSlaveThread(final MouseEvent e) {
			if (!e.isConsumed() && SwingUtilities.isLeftMouseButton(e) && e.getClickCount() > 1) {
				if (!chart.isRotationEnabled()) {
					displayMsg("Rotations disabled in current view");
					return false;
				}
				if (threadController != null) {
					threadController.start();
					return true;
				}
			}
			if (threadController != null) threadController.stop();
			return false;
		}

		private void rotateLive(final Coord2d move) {
			if (currentView == ViewMode.XY) {
				displayMsg("Rotation disabled in constrained view");
				return;
			}
			rotate(move, true);
		}

		@Override
		protected void rotate(final Coord2d move, final boolean updateView){
			// make method visible
			super.rotate(move, updateView);
		}

		/* see AWTMousePickingPan2dController */
		public void pan(final Coord3d from, final Coord3d to) {
			final BoundingBox3d viewBounds = view.getBounds();
			final Coord3d offset = to.sub(from).div(-panStep);
			final BoundingBox3d newBounds = viewBounds.shift(offset);
			view.setBoundsManual(newBounds);
			view.shoot();
			fireControllerEvent(ControllerType.PAN, offset);
		}

		public void zoom(final float factor) {
			final BoundingBox3d viewBounds = view.getBounds();
			BoundingBox3d newBounds = viewBounds.scale(new Coord3d(factor, factor,
				factor));
			newBounds = newBounds.shift((viewBounds.getCenter().sub(newBounds
				.getCenter())));
			view.setBoundsManual(newBounds);
			view.shoot();
			fireControllerEvent(ControllerType.ZOOM, factor);
		}

		public void snapToNextView() {
			try {
				stopThreadController();
				chart.setViewMode(currentView.next());
				displayMsg("View Mode: " + currentView.description);
			} catch (final Throwable t) {
				t.printStackTrace();
			}
		}

		/*
		 * (non-Javadoc)
		 *
		 * @see org.jzy3d.chart.controllers.mouse.camera.AWTCameraMouseController#
		 * mousePressed(java.awt.event.MouseEvent)
		 */
		@Override
		public void mousePressed(final MouseEvent e) {
			if (e.isControlDown() && AWTMouseUtilities.isLeftDown(e) && !e.isConsumed()) {
				snapToNextView();
				e.consume();
				return;
			}
			super.mousePressed(e);
			prevMouse3d = view.projectMouse(e.getX(), getY(e));
		}

		public boolean isAnimating() {
			return threadController != null && threadController instanceof CameraThreadControllerPlus
					&& ((CameraThreadControllerPlus) threadController).isAnimating();
		}

		/*
		 * (non-Javadoc)
		 *
		 * @see org.jzy3d.chart.controllers.mouse.camera.AWTCameraMouseController#
		 * mouseWheelMoved(java.awt.event.MouseWheelEvent)
		 */
		@Override
		public void mouseWheelMoved(final MouseWheelEvent e) {
			stopThreadController();
			final float factor = 1 + e.getWheelRotation() * keyController.zoomStep;
			zoom(factor);
			prevMouse3d = view.projectMouse(e.getX(), getY(e));
		}

		/*
		 * (non-Javadoc)
		 *
		 * @see org.jzy3d.chart.controllers.mouse.camera.AWTCameraMouseController#
		 * mouseDragged(java.awt.event.MouseEvent)
		 */
		@Override
		public void mouseDragged(final MouseEvent e) {

			final Coord2d mouse = xy(e);

			// Rotate on left-click
			if (AWTMouseUtilities.isLeftDown(e)) {
				final Coord2d move = mouse.sub(prevMouse).div(100);
				rotate(move);
				//((AView)view).logViewPoint();
			}

			// Pan on right-click
			else if (AWTMouseUtilities.isRightDown(e)) {
				final Coord3d thisMouse3d = view.projectMouse(e.getX(), getY(e));
				if (!panDone) { // 1/2 pan for cleaner rendering
					pan(prevMouse3d, thisMouse3d);
					panDone = true;
				}
				else {
					panDone = false;
				}
				prevMouse3d = thisMouse3d;
			}
			prevMouse = mouse;
		}
	}

	private static class CameraThreadControllerPlus extends CameraThreadController {

		//TODO: here we should be able to override #move and #doRun to improve
		// rotations, namely along anatomical axes rather than azymuths
		public CameraThreadControllerPlus(final Chart chart) {
			super(chart);
		}

		protected boolean isAnimating() {
			return process != null && process.isAlive();
		}
	}

	private class KeyController extends AbstractCameraController implements
		KeyListener
	{

		private float zoomStep = Prefs.DEF_ZOOM_STEP;
		private double rotationStep;
		private static final int DOUBLE_PRESS_INTERVAL = 300; // ms
		private long timeKeyDown = 0; // last time key was pressed
		private int lastKeyPressedCode;

		public KeyController(final Chart chart) {
			register(chart);
		}

		private boolean isDoublePress(final KeyEvent ke) {
			if (lastKeyPressedCode == ke.getKeyCode() && ((ke.getWhen() -
				timeKeyDown) < DOUBLE_PRESS_INTERVAL)) return true;
			timeKeyDown = ke.getWhen();
			lastKeyPressedCode = ke.getKeyCode();
			return false;
		}
	
		/*
		 * (non-Javadoc)
		 *
		 * @see java.awt.event.KeyListener#keyPressed(java.awt.event.KeyEvent)
		 */
		@Override
		public void keyPressed(final KeyEvent e) {
			final boolean doublePress = isDoublePress(e);
			switch (e.getKeyChar()) {
				case 'a':
				case 'A':
					if (!emptySceneMsg()) toggleAxes();
					break;
				case 'c':
				case 'C':
					if (e.isShiftDown()) {
						toggleControlPanel();
					} else if (!emptySceneMsg())
						changeCameraMode();
					break;
				case 'd':
				case 'D':
					toggleDarkMode();
					break;
				case 'h':
				case 'H':
					showHelp(false);
					break;
				case 'l':
				case 'L':
					logSceneControls(true);
					break;
				case 'r':
				case 'R':
					if (doublePress || e.isShiftDown()) {
						validate();
						displayMsg("Scene reloaded");
					} else {
						resetView();
					}
					break;
				case 's':
				case 'S':
					if (e.isShiftDown() && frame != null) {
						frame.status.setVisible(!frame.status.isVisible());
					} else {
						saveScreenshot();
					}
					break;
				case 'f':
				case 'F':
					if (e.isShiftDown()) {
						if (frame.isFullScreen)
							frame.exitFullScreen();
						else
							frame.enterFullScreen();
					} else {
						if (!emptySceneMsg()) fitToVisibleObjects(true, true);
					}
					break;
				case '+':
				case '=':
					mouseController.zoom(1f - zoomStep);
					break;
				case '-':
				case '_':
					mouseController.zoom(1f + zoomStep);
					break;
				default:
					switch (e.getKeyCode()) {
						case KeyEvent.VK_F1:
							showHelp(true);
							break;
						case KeyEvent.VK_DOWN:
							if (e.isShiftDown()) {
								pan(new Coord2d(0, -1));
							} else
								mouseController.rotateLive(new Coord2d(0f, -rotationStep));
							break;
						case KeyEvent.VK_UP:
							if (e.isShiftDown()) {
								pan(new Coord2d(0, 1));
							} else
								mouseController.rotateLive(new Coord2d(0f, rotationStep));
							break;
						case KeyEvent.VK_LEFT:
							if (e.isShiftDown()) {
								pan(new Coord2d(-1, 0));
							} else
								mouseController.rotateLive(new Coord2d(-rotationStep, 0));
							break;
						case KeyEvent.VK_RIGHT:
							if (e.isShiftDown()) {
								pan(new Coord2d(1, 0));
							} else
								mouseController.rotateLive(new Coord2d(rotationStep, 0));
							break;
						case KeyEvent.VK_ESCAPE:
							if (frame.isFullScreen)
								frame.exitFullScreen();
							else
								abortCurrentOperation = true;
							break;
						default:
							break;
					}
			}

		}

		private void pan(final Coord2d direction) {
			final float normPan = Prefs.PAN.getNormalizedPan(mouseController.panStep) / 100;
			final BoundingBox3d bounds = view.getBounds();
			final Coord3d offset = new Coord3d(
					bounds.getXRange().getRange() * direction.x * normPan, 
					bounds.getYRange().getRange() * direction.y * normPan, 0);
			view.setBoundsManual(bounds.shift(offset));
			view.shoot();
		}

		private void toggleAxes() {
			chart.setAxeDisplayed(!view.isAxisDisplayed());
		}

		private boolean emptySceneMsg() {
			final boolean empty = isEmptyScene();
			if (empty) displayMsg("Scene is empty");
			return empty;
		}

		private void saveScreenshot() {
			saveSnapshot();
			displayMsg("Snapshot saved to " + FileUtils.limitPath(
				prefs.snapshotDir, 50));
		}

		private void resetView() {
			try {
				chart.setViewPoint(View.VIEWPOINT_DEFAULT);
				chart.setViewMode(ViewPositionMode.FREE);
				view.setBoundMode(ViewBoundMode.AUTO_FIT);
				displayMsg("View reset");
			} catch (final GLException ex) {
				SNTUtils.error("Is scene empty? ", ex);
			}
		}

		/*
		 * (non-Javadoc)
		 *
		 * @see java.awt.event.KeyListener#keyTyped(java.awt.event.KeyEvent)
		 */
		@Override
		public void keyTyped(final KeyEvent e) {}

		/*
		 * (non-Javadoc)
		 *
		 * @see java.awt.event.KeyListener#keyReleased(java.awt.event.KeyEvent)
		 */
		@Override
		public void keyReleased(final KeyEvent e) {
			if (KeyEvent.VK_ESCAPE == e.getKeyCode()) {
				abortCurrentOperation = false;
			}
		}

		private void changeCameraMode() {
			final CameraMode newMode = (view.getCameraMode() == CameraMode.ORTHOGONAL)
				? CameraMode.PERSPECTIVE : CameraMode.ORTHOGONAL;
			view.setCameraMode(newMode);
			final String mode = (newMode == CameraMode.ORTHOGONAL) ? "Orthogonal"
				: "Perspective";
			displayMsg("Camera mode changed to \"" + mode + "\"");
		}

		/* This seems to work only at initialization */
		@SuppressWarnings("unused")
		private void changeQuality() {
			final Quality[] levels = { Quality.Fastest(), Quality.Intermediate(),
				Quality.Advanced(), Quality.Nicest() };
			final String[] grades = { "Fastest", "Intermediate", "High", "Best" };
			final Quality currentLevel = chart.getQuality();
			int nextLevelIdx = 0;
			for (int i = 0; i < levels.length; i++) {
				if (levels[i] == currentLevel) {
					nextLevelIdx = i + 1;
					break;
				}
			}
			if (nextLevelIdx == levels.length) nextLevelIdx = 0;
			chart.setQuality(levels[nextLevelIdx]);
			displayMsg("Quality level changed to '" + grades[nextLevelIdx] + "'");
		}

		private void toggleDarkMode() {
//			if (chart == null)
//				return;
			Color newForeground;
			Color newBackground;
			if (view.getBackgroundColor() == Color.BLACK) {
				newForeground = Color.BLACK;
				newBackground = Color.WHITE;
			}
			else {
				newForeground = Color.WHITE;
				newBackground = Color.BLACK;
			}
			if (frame != null) {
				frame.status.getParent().setBackground(toAWTColor(newBackground));
				frame.status.setBackground(toAWTColor(newBackground));
				frame.status.setForeground(toAWTColor(newForeground));
			}
			view.setBackgroundColor(newBackground);
			view.getAxis().getLayout().setGridColor(newForeground);
			view.getAxis().getLayout().setMainColor(newForeground);
			if (cBar != null) cBar.updateColors();

			// Apply foreground color to trees with background color
			plottedTrees.values().forEach(shapeTree -> {
				if (isSameRGB(shapeTree.getSomaColor(), newBackground)) shapeTree
					.setSomaColor(newForeground);
				if (isSameRGB(shapeTree.getArborColor(), newBackground)) {
					shapeTree.setArborColor(newForeground, -1);
					return; // replaces continue in lambda expression;
				}
				final Shape shape = shapeTree.treeSubShape;
				if (shape == null) return;
				for (int i = 0; i < shape.size(); i++) {
					final List<Point> points = ((LineStripPlus) shape.get(i)).getPoints();
					points.forEach(p -> {
						final Color pColor = p.getColor();
						if (isSameRGB(pColor, newBackground)) {
							changeRGB(pColor, newForeground);
						}
					});
				}
			});

			// Apply foreground color to meshes with background color
			plottedObjs.values().forEach(obj -> {
				final Color objColor = obj.getColor();
				if (isSameRGB(objColor, newBackground)) {
					changeRGB(objColor, newForeground);
				}
			});

			// Apply foreground to annotation labels
			((AChart)chart).overlayAnnotation.labelColor = toAWTColor(newForeground);

		}

		private boolean isSameRGB(final Color c1, final Color c2) {
			return c1 != null && c1.r == c2.r && c1.g == c2.g && c1.b == c2.b;
		}

		private void changeRGB(final Color from, final Color to) {
			from.r = to.r;
			from.g = to.g;
			from.b = to.b;
		}

		private void showHelp(final boolean showInDialog) {
			final StringBuilder sb = new StringBuilder("<HTML>");
			sb.append("<table>");
			sb.append("  <tr>");
			sb.append("    <td>Pan</td>");
			sb.append("    <td>Right-click &amp; drag (or Shift+arrow keys)</td>");
			sb.append("  </tr>");
			sb.append("  <tr>");
			sb.append("    <td>Rotate</td>");
			sb.append("    <td>Left-click &amp; drag (or arrow keys)</td>");
			sb.append("  </tr>");
			sb.append("  <tr>");
			sb.append("    <td>Zoom (Scale)</td>");
			sb.append("    <td>Scroll wheel (or + / - keys)</td>");
			sb.append("  </tr>");
			sb.append("  <tr>");
			sb.append("    <td>Animate</td>");
			sb.append("    <td>Double left-click</td>");
			sb.append("  </tr>");
			sb.append("  <tr>");
			sb.append("    <td>Snap to Top/Side View &nbsp; &nbsp;</td>");
			sb.append("    <td>Ctrl + left-click</td>");
			sb.append("  </tr>");
			if (showInDialog) sb.append("  <tr>");
			sb.append("  <tr>");
			sb.append("    <td>Toggle <u>A</u>xes</td>");
			sb.append("    <td>Press 'A'</td>");
			sb.append("  </tr>");
			sb.append("  <tr>");
			sb.append("    <td>Toggle <u>C</u>amera Mode</td>");
			sb.append("    <td>Press 'C'</td>");
			sb.append("  </tr>");
			sb.append("  <tr>");
			sb.append("    <td>Toggle <u>D</u>ark Mode</td>");
			sb.append("    <td>Press 'D'</td>");
			sb.append("  </tr>");
			sb.append("  <tr>");
			sb.append("    <td><u>F</u>it to Visible Objects</td>");
			sb.append("    <td>Press 'F'</td>");
			sb.append("  </tr>");
			sb.append("  <tr>");
			sb.append("    <td><u>L</u>og Scene Details</td>");
			sb.append("    <td>Press 'L'</td>");
			sb.append("  </tr>");
			sb.append("    <td><u>R</u>eset View</td>");
			sb.append("    <td>Press 'R'</td>");
			sb.append("  </tr>");
			sb.append("  </tr>");
			sb.append("    <td><u>R</u>eload Scene</td>");
			sb.append("    <td>Press 'R' twice</td>");
			sb.append("  </tr>");
			sb.append("  <tr>");
			sb.append("    <td><u>S</u>napshot</td>");
			sb.append("    <td>Press 'S'</td>");
			sb.append("  </tr>");
			if (showInDialog) sb.append("  <tr>");
			sb.append("  <tr>");
			sb.append("    <td>Toggle RV <u>C</u>ontrols</td>");
			sb.append("    <td>Shift+C</td>");
			sb.append("  </tr>");
			sb.append("  <tr>");
			sb.append("    <td>Toggle <u>F</u>ull Screen</td>");
			sb.append("    <td>Shift+F</td>");
			sb.append("  </tr>");
			sb.append("  <tr>");
			sb.append("    <td>Toggle <u>S</u>tatus Bar</td>");
			sb.append("    <td>Shift+S</td>");
			sb.append("  </tr>");
			sb.append("  <tr>");
			sb.append("    <td>Command <u>P</u>alette </td>");
			sb.append("    <td>").append(GuiUtils.ctrlKey()).append("+Shift+P</td>");
			sb.append("  </tr>");
			if (showInDialog) {
				sb.append("  <tr>");
				sb.append("  <tr>");
				sb.append("    <td><u>H</u>elp</td>");
				sb.append("    <td>Press 'H' (notification) or F1 (list), or see " +
						"<a href=\"https://imagej.net/plugins/snt/key-shortcuts#reconstruction-viewer\">online list</a></td>");
				sb.append("  </tr>");
			}
			sb.append("</table>");
			if (showInDialog) {
				if (gUtils == null) 
					GuiUtils.showHTMLDialog(sb.toString(), "Viewer Shortcuts");
				else
					gUtils.showHTMLDialog(sb.toString(), "Viewer Shortcuts", false);
			}
			else {
				displayBanner(sb.toString());
			}

		}
	}

	private class OverlayAnnotation extends CameraEyeOverlayAnnotation {

		private FPSAnimator joglAnimator;
		private String label;
		private Font labelFont;
		private java.awt.Color labelColor;
		private float labelX = 2;
		private float labelY = 0;
//		private CubeComposite axisBox;

		private OverlayAnnotation(final View view) {
			super(view);
			if (ENGINE == Engine.JOGL) {
				try {
					// this requires requires jzy v2.0.1
					// FIXME: joglAnimator = (FPSAnimator) chart.getCanvas().getAnimation().getAnimator();
				} catch (final Exception ignored) {
					// do nothing
				}
			}
//			axisBox = new CubeComposite(view.getSpaceTransformer().compute(view.getBounds()),
//					view.getAxis().getLayout().getGridColor(), view.getAxis().getLayout().getMainColor());
		}

		private void setFont(final Font font, final float angle) {
			if (angle == 0) {
				this.labelFont = font;
				return;
			}
			final AffineTransform affineTransform = new AffineTransform();
			affineTransform.rotate(Math.toRadians(angle), 0, 0);
			labelFont = font.deriveFont(affineTransform);
		}

		private void setLabelColor(final java.awt.Color labelColor) {
			this.labelColor = labelColor;
		}

		@Override
		public void paint(final Graphics g, final int canvasWidth,
			final int canvasHeight)
		{
			final Graphics2D g2d = (Graphics2D) g;
			g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
				RenderingHints.VALUE_ANTIALIAS_ON);
			g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
				RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			if (SNTUtils.isDebugMode()) {
				g2d.setColor(toAWTColor(view.getAxisLayout().getMainColor()));
				g2d.setFont(g2d.getFont().deriveFont((float)view.getAxisLayout().getFont().getHeight()));
				final int lineHeight = g.getFontMetrics().getHeight();
				int lineNo = 1;
				g2d.drawString("Camera: " + view.getCamera().getEye(), 20, lineHeight * lineNo++);
				g2d.drawString("FOV: " + view.getCamera().getRenderingSphereRadius(), 20, lineHeight * lineNo++);
				g2d.drawString("Near: " + view.getCamera().getNear(), 20, lineHeight * lineNo++);
				g2d.drawString("Far: " + view.getCamera().getFar(), 20, lineHeight * lineNo++);
//				g2d.drawString("Up Z: " + view.getCamera().getUp().z, 20, lineHeight * lineNo++);
//				g2d.drawString("Axe:" + view.getAxis().getBounds().toString(), 20, lineHeight * lineNo++);
//				g2d.drawString("Transformed axe: " + axisBox.getBounds().toString(), 20, lineHeight * lineNo++);
				if (joglAnimator != null) {
					g2d.drawString(joglAnimator.getLastFPS() + " FPS", 20, lineHeight * lineNo);
				}

			}
			if (label == null || label.isEmpty()) return;
			if (labelColor != null) g2d.setColor(labelColor);
			if (labelFont != null) g2d.setFont(labelFont);
			final int lineHeight = g2d.getFontMetrics().getHeight();
			float ypos = labelY; //(labelY < lineHeight) ? lineHeight : labelY;
			for (final String line : label.split("\n")) {
				g2d.drawString(line, labelX, ypos += lineHeight);
			}
		}
	}

	private class LightController extends JDialog {

		private static final long serialVersionUID = 1L;
		private final Chart chart;
		private final Color existingSpecularColor;
		private final ViewerFrame viewerFrame;

		LightController(final ViewerFrame viewerFrame, final AbstractEnlightable abstractEnlightable) {

			super((viewerFrame.manager == null) ? viewerFrame : viewerFrame.manager,
					(abstractEnlightable == null) ? "Light Effects" : "Material Editor");
			this.viewerFrame = viewerFrame;
			this.chart = viewerFrame.chart;
			existingSpecularColor = chart.getView().getBackgroundColor();
			assignDefaultLightIfNoneExists();
			final JPanel panel = (abstractEnlightable == null) ? lightEditorPane() : materialEditorPane(abstractEnlightable);
			final JScrollPane scrollPane = new JScrollPane(panel);
			scrollPane.setWheelScrollingEnabled(true);
			scrollPane.setBorder(null);
			scrollPane.setViewportView(panel);
			add(scrollPane);
			setLocationRelativeTo(getParent());
			setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
			getRootPane().registerKeyboardAction(e -> {
				dispose(true);
			}, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
			addWindowListener(new WindowAdapter() {
				@Override
				public void windowClosing(final WindowEvent e) {
					dispose(true);
				}
			});

		}

		private JPanel lightEditorPane() {
			return new LightEditorPlus(chart, chart.getScene().getLightSet().get(0));
		}

		private JPanel materialEditorPane(final AbstractEnlightable abstractEnlightable) {
			final MaterialEditor enlightableEditor = new MaterialEditor(chart);
			enlightableEditor.setTarget(abstractEnlightable);
			return enlightableEditor;
		}

		void assignDefaultLightIfNoneExists() {
			// LightEditor requires chart to have a light, so we'll set it here
			try {
				if (chart.getScene().getLightSet() == null)
					chart.getScene().setLightSet(new LightSet());
				chart.getScene().getLightSet().get(0);
			} catch (final IndexOutOfBoundsException ignored) {
				final Light light = new Light();
				light.setPosition(chart.getView().getBounds().getCenter());
				light.setAmbiantColor(existingSpecularColor.negative());
				light.setDiffuseColor(new Color(0.8f, 0.8f, 0.8f));
				light.setSpecularColor(existingSpecularColor);
				light.setRepresentationDisplayed(false);
				light.setRepresentationRadius(1);
				chart.getScene().add(light);
			}
		}

		void display() {
			pack();
			setVisible(true);
			if (viewerFrame.manager != null) {
				viewerFrame.manager .toBack(); // byPasses macOS bug?
				toFront();
			}
		}

		void resetLight() {
			// HACK: the scene does not seem to update when light is removed,
			// so we'll try our best to restore things to pre-prompt state
			try {
				Light light = chart.getScene().getLightSet().get(0);
				light.setSpecularColor(existingSpecularColor);
				chart.getView().setBackgroundColor(existingSpecularColor);
				light.setEnabled(false);
				light.setAmbiantColor(null);
				light.setDiffuseColor(null);
				light.setSpecularColor(null);
				chart.render();
				chart.getScene().remove(light);
				light = null;
				chart.getScene().setLightSet(new LightSet());
				chart.render();
			} catch (final IndexOutOfBoundsException | NullPointerException ignored) {
				// do nothing. Somehow Light was already removed from scene
			}
		}

		void dispose(final boolean prompt) {
			if (prompt && !new GuiUtils(this).getConfirmation("Keep new lightning scheme? "
					+ "(If you choose \"Discard\" you may need to rebuild the scene for changes " + "to take effect)",
					"Keep Changes?", "Yes. Keep", "No. Discard")) {
				resetLight();
			}
			dispose();
		}

		class LightEditorPlus extends LightEditor {
			private static final long serialVersionUID = 1L;

			public LightEditorPlus(final Chart chart, final Light light) {
				super(chart);
				removeAll();
				setLayout(new GridBagLayout());
				final GridBagConstraints gbc = GuiUtils.defaultGbc();
				add(ambiantColorControl, gbc);
				gbc.gridy++;
				add(diffuseColorControl, gbc);
				gbc.gridy++;
				add(positionControl, gbc);
				gbc.gridy++;
				correctPanels(this);
				final JPanel buttonPanel = new JPanel();
				final JButton apply = new JButton("Cancel");
				apply.addActionListener(e->{
					dispose(false);
					resetLight();
				});
				buttonPanel.add(apply);
				final JButton reset = new JButton("Apply");
				reset.addActionListener(e->{
					dispose(false);
				});
				buttonPanel.add(reset);
				add(new JLabel(" "), gbc);
				gbc.gridy++;
				add(buttonPanel, gbc);
				setTarget(light);
			}

			void correctPanels(final Container container) {
				for (final Component c : container.getComponents()) {
					if (c instanceof JLabel) {
						adjustLabel(((JLabel) c));
					} else if (c instanceof JSlider) {
						adjustSlider(((JSlider) c));
					} else if (c instanceof Container) {
						correctPanels((Container) c);
					}
				}
			}

			private void adjustSlider(final JSlider jSlider) {
				jSlider.setPaintLabels(false);
			}

			void adjustLabel(final JLabel label) {
				String text = label.getText();
				if (text.startsWith("Pos")) {
					text = "Position (X,Y,Z): ";
				} else {
					text += " Color (R,G,B): ";
				}
				label.setText(text);
			}
		}
	}

	private String getLabel(final OBJMesh mesh) {
		for (final Entry<String, RemountableDrawableVBO> entry : plottedObjs.entrySet()) {
			if (entry.getValue().objMesh == mesh) return entry.getKey();
		}
		return null;
	}

	private String getLabel(final Tree tree) {
		for (final Map.Entry<String, ShapeTree> entry : plottedTrees.entrySet()) {
			if (entry.getValue().tree == tree) return entry.getKey();
		}
		return null;
	}

	/**
	 * Sets the line thickness for rendering {@link Tree}s that have no specified
	 * radius.
	 *
	 * @param thickness the new line thickness. Note that this value only applies
	 *          to Paths that have no specified radius
	 */
	public void setDefaultThickness(final float thickness) {
		this.defThickness = thickness;
	}

	private synchronized void fitToVisibleObjects(final boolean beGreedy, final boolean showMsg)
		throws NullPointerException
	{
		final List<Drawable> all = chart.getView().getScene().getGraph()
			.getAll();
		final BoundingBox3d bounds = new BoundingBox3d();
		all.forEach(d -> {
			if (d != null && d.isDisplayed() && d.getBounds() != null && !d
				.getBounds().isReset())
			{
				bounds.add(d.getBounds());
			}
		});
		if (bounds.isPoint()) return;
		if (beGreedy) {
			BoundingBox3d zoomedBox = bounds.scale(new Coord3d(.85f, .85f, .85f));
			zoomedBox = zoomedBox.shift((bounds.getCenter().sub(zoomedBox.getCenter())));
			chart.view().lookToBox(zoomedBox);
		}
		else {
			chart.view().lookToBox(bounds);
		}
		if (showMsg) {
			final BoundingBox3d newBounds = chart.view().getScene().getGraph().getBounds();
			final StringBuilder sb = new StringBuilder();
			sb.append("X: ").append(String.format("%.2f", newBounds.getXmin())).append(
				"-").append(String.format("%.2f", newBounds.getXmax()));
			sb.append(" Y: ").append(String.format("%.2f", newBounds.getYmin()))
				.append("-").append(String.format("%.2f", newBounds.getYmax()));
			sb.append(" Z: ").append(String.format("%.2f", newBounds.getZmin()))
				.append("-").append(String.format("%.2f", newBounds.getZmax()));
			displayMsg("Zoomed to " + sb.toString());
		}
	}

	/**
	 * Sets the default color for rendering {@link Tree}s.
	 *
	 * @param color the new color. Note that this value only applies to Paths that
	 *          have no specified color and no colors assigned to its nodes
	 */
	public void setDefaultColor(final ColorRGB color) {
		this.defColor = fromColorRGB(color);
	}

	/**
	 * Returns the default line thickness.
	 *
	 * @return the default line thickness used to render Paths without radius
	 */
	protected float getDefaultThickness() {
		return defThickness;
	}

	/**
	 * Applies a constant thickness (line width) to a subset of rendered trees.
	 *
	 * @param labels      the Collection of keys specifying the subset of trees. If
	 *                    null, thickness is applied to all the trees in the viewer.
	 * @param thickness   the thickness (line width)
	 * @param compartment a string with at least 2 characters describing the Tree
	 *                    compartment (e.g., 'axon', 'axn', 'dendrite', 'dend',
	 *                    etc.)
	 * @see #setTreeThickness(float, String)
	 */
	public void setTreeThickness(final Collection<String> labels,
		final float thickness, final String compartment)
	{
		final int comp = getSubTreeCompartment(compartment);
		plottedTrees.forEach((k, shapeTree) -> {
			if (labels == null || labels.contains(k)) shapeTree.setThickness(thickness, comp);
		});
	}

	/**
	 * Applies a constant thickness to all rendered trees. Note that by default,
	 * trees are rendered using their nodes' diameter.
	 *
	 * @param thickness the thickness (line width)
	 * @see #setTreeThickness(float, String)
	 */
	public void setTreeThickness(final float thickness) {
		plottedTrees.values().forEach(shapeTree -> shapeTree.setThickness(
			thickness, -1));
	}

	/**
	 * Applies a constant thickness (line width) to all rendered trees.Note that by
	 * default, trees are rendered using their nodes' diameter.
	 *
	 * @param thickness   the thickness (line width)
	 * @param compartment a string with at least 2 characters describing the Tree
	 *                    compartment (e.g., 'axon', 'axn', 'dendrite', 'dend',
	 *                    etc.)
	 * @see #setTreeThickness(float)
	 */
	public void setTreeThickness(final float thickness, final String compartment) {
		final int comp = getSubTreeCompartment(compartment);
		plottedTrees.values().forEach(shapeTree -> shapeTree.setThickness(
				thickness, comp));
	}

	/**
	 * Applies a constant radius to all rendered somas being rendered as spheres.
	 *
	 * @param radius the radius (physical units)
	 */
	public void setSomaRadius(final float radius) {
		plottedTrees.values().forEach(shapeTree -> shapeTree.setSomaRadius(radius));
	}

	/**
	 * Applies a constant radius to the somas of a subset of rendered trees.
	 *
	 * @param labels the Collection of keys specifying the subset of trees.
	 * @param radius the radius (physical units)
	 * @see #setSomaRadius(float)
	 */
	public void setSomaRadius(final Collection<String> labels, final float radius) {
		plottedTrees.forEach((k, shapeTree) -> {
			if (labels.contains(k)) shapeTree.setSomaRadius(radius);
		});
	}

	/**
	 * Recolors a subset of rendered trees.
	 *
	 * @param labels      the Collection of keys specifying the subset of trees
	 * @param color       the color to be applied, either a 1) HTML color codes
	 *                    starting with hash ({@code #}), a color preset ("red",
	 *                    "blue", etc.), or integer triples of the form
	 *                    {@code r,g,b} and range {@code [0, 255]}
	 * @param compartment a string with at least 2 characters describing the Tree
	 *                    compartment (e.g., 'axon', 'axn', 'dendrite', 'dend',
	 *                    etc.)
	 */
	public void setTreeColor(final Collection<String> labels, final String color, final String compartment) {
		setTreeColor(labels, new ColorRGB(color), compartment);
	}

	/**
	 * Recolors a subset of rendered trees.
	 *
	 * @param labels      the Collection of keys specifying the subset of trees
	 * @param color       the color to be applied.
	 * @param compartment a string with at least 2 characters describing the Tree
	 *                    compartment (e.g., 'axon', 'axn', 'dendrite', 'dend',
	 *                    etc.)
	 */
	public void setTreeColor(final Collection<String> labels, final ColorRGB color, final String compartment) {
		final int comp = getSubTreeCompartment(compartment);
		plottedTrees.forEach((k, shapeTree) -> {
			if (labels.contains(k))
				shapeTree.setArborColor((color == null) ? defColor : fromColorRGB(color), comp);
		});
	}

	/**
	 * Translates the specified Tree. Does nothing if tree is not present in scene.
	 *
	 * @param tree the Tree to be translated
	 * @param offset the translation offset. If null, tree position will be reset to
	 *               their original location.
	 */
	public void translate(final Tree tree, final SNTPoint offset){
		final String treeLabel = getLabel(tree);
		if (treeLabel != null) this.translate(Collections.singletonList(treeLabel), offset);
	}

//	private void translate(final OBJMesh mesh, final SNTPoint offset) {
//		mesh.drawable.unmount();
//		final TranslateDrawable td = new TranslateDrawable(mesh.drawable, false);
//		td.compute(new Coord3d(offset.getX(), offset.getY(), offset.getZ()));
//		td.execute(view.getCurrentGL());
//	}

	/**
	 * Translates the specified collection of {@link Tree}s.
	 *
	 * @param treeLabels the collection of Tree identifiers (as per
	 *          {@link #addTree(Tree)}) specifying the Trees to be translated
	 * @param offset the translation offset. If null, trees position will be reset
	 *          to their original location.
	 */
	public void translate(final Collection<String> treeLabels,
		final SNTPoint offset)
	{
		if (offset == null) {
			plottedTrees.forEach((k, shapeTree) -> {
				if (treeLabels.contains(k)) shapeTree.resetTranslation();
			});
		}
		else {
			final Coord3d coord = new Coord3d(offset.getX(), offset.getY(), offset
				.getZ());
			plottedTrees.forEach((k, shapeTree) -> {
				if (treeLabels.contains(k)) shapeTree.translateTo(coord);
			});
		}
		if (viewUpdatesEnabled) {
			view.shoot();
			fitToVisibleObjects(true, false);
		}
	}

	private void setArborsDisplayed(final Collection<String> labels,
		final boolean displayed)
	{
		plottedTrees.forEach((k, shapeTree) -> {
			if (labels.contains(k)) setArborDisplayed(k, displayed);
		});
	}

	private void setArborDisplayed(final String treeLabel,
		final boolean displayed)
	{
		final ShapeTree shapeTree = plottedTrees.get(treeLabel);
		if (shapeTree != null) shapeTree.setArborDisplayed(displayed);
	}

	/**
	 * Toggles the visibility of somas for subset of trees.
	 *
	 * @param labels the Collection of keys specifying the subset of trees to be
	 *          affected
	 * @param displayed whether soma should be displayed
	 */
	public void setSomasDisplayed(final Collection<String> labels,
		final boolean displayed)
	{
		plottedTrees.forEach((k, shapeTree) -> {
			if (labels.contains(k)) {
				setSomaDisplayed(k, displayed);
				// if this tree is only composed of soma
				if (shapeTree.treeSubShape == null) {
					setVisible(k, displayed);
				}
			}
		});
	}

	/**
	 * Toggles the visibility of somas for all trees in the scene.
	 *
	 * @param displayed whether soma should be displayed
	 */
	public void setSomasDisplayed(final boolean displayed) {
		plottedTrees.values().forEach(shapeTree -> shapeTree.setSomaDisplayed(displayed));
	}

	private void setSomaDisplayed(final String treeLabel,
		final boolean displayed)
	{
		final ShapeTree shapeTree = plottedTrees.get(treeLabel);
		if (shapeTree != null) shapeTree.setSomaDisplayed(displayed);
	}

	/**
	 * Applies a color to a subset of rendered trees.
	 *
	 * @param labels the Collection of keys specifying the subset of trees
	 * @param color the color
	 */
	private void applyColorToPlottedTrees(final List<String> labels,
		final ColorRGB color)
	{
		plottedTrees.forEach((k, shapeTree) -> {
			if (labels.contains(k)) {
				shapeTree.setArborColor(color, ShapeTree.ANY);
				shapeTree.setSomaColor(color);
			}
		});
	}

	private boolean treesContainColoredNodes(final List<String> labels) {
		if (plottedTrees == null || plottedTrees.isEmpty()) return false;
		Color refColor = null;
		for (final Map.Entry<String, ShapeTree> entry : plottedTrees.entrySet()) {
			if (labels.contains(entry.getKey())) {
				final Shape shape = entry.getValue().treeSubShape;
				if (shape == null) continue; // e.g., soma-only tree
				for (int i = 0; i < shape.size(); i++) {
					// treeSubShape is only composed of LineStripPluses so this is a safe
					// casting
					final Color color = getNodeColor((LineStripPlus) shape.get(i));
					if (color == null) continue;
					if (refColor == null) {
						refColor = color;
						continue;
					}
					if (color.r != refColor.r || color.g != refColor.g ||
						color.b != refColor.b) return true;
				}
			}
		}
		return false;
	}

	private Color getNodeColor(final LineStripPlus lineStrip) {
		for (final Point p : lineStrip.getPoints()) {
			if (p != null) return p.rgb;
		}
		return null;
	}

	/**
	 * Checks whether this instance is SNT's Reconstruction Viewer.
	 *
	 * @return true, if SNT instance, false otherwise
	 */
	public boolean isSNTInstance() {
		return sntInstance;
	}

	/**
	 * Checks whether this instance is currently active 
	 *
	 * @return true, if active, false otherwise
	 */
	public boolean isActive() {
		return (frame != null && frame.isActive());
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + id;
		return result;
	}

	@Override
	public boolean equals(final Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		return id ==  ((Viewer3D) obj).id;
	}

	private void incrementProgress() {
		if (getManagerPanel()!= null)
			frame.managerPanel.progressBar.addToGlobalValue(1);
	}

	private int getCurrentProgress() {
		return (getManagerPanel() == null) ? -1 : frame.managerPanel.progressBar.globalValue;
	}

	/** will hide the bar if progress max becomes negative */
	private void removeProgressLoad(final int loadSize) {
		if (getManagerPanel()!= null) {
			frame.managerPanel.progressBar.addToGlobalMax((loadSize < 0) ? loadSize : -loadSize);
		}
	}

	/** will show an undetermined bar if progress max is negative */
	private void addProgressLoad(final int loadSize) {
		if (getManagerPanel()!= null) {
			frame.managerPanel.progressBar.addToGlobalMax(loadSize);
		}
	}

	/** Defines the type of render, and view used by jzy3d */
	private static class ViewerFactory {

		/** Returns ChartComponentFactory adopting {@link AView} */
		private ChartFactory getUpstreamFactory(final Engine render) {
			switch (render) {
			case EMUL_GL:
				return new EmulGLFactory();
			case OFFSCREEN:
				return new OffScreenFactory();
			case JOGL:
				return new JOGLFactory();
			default:
				throw new IllegalArgumentException("Not a recognized render option: " + render.toString());
			}

		}

		private static class OffScreenFactory extends OffscreenChartFactory {

			public OffScreenFactory() {
				super(1920, 1080);
			}

			@Override
			public View newView(final Scene scene, final ICanvas canvas, final Quality quality) {
				return new AView(getFactory(), scene, canvas, quality);
			}
		}

		private static class EmulGLFactory extends EmulGLChartFactory {

			@Override
			public View newView(final Scene scene, final ICanvas canvas, final Quality quality) {
				return new AView(getFactory(), scene, canvas, quality);
			}
		}

		private static class JOGLFactory extends AWTChartFactory {

			@Override
			public View newView(final Scene scene, final ICanvas canvas, final Quality quality) {
				return new AView(getFactory(), scene, canvas, quality);
			}
		}

		/** Adapted View for improved rotations of the scene */
		private static class AView extends AWTView {

			public AView(final IChartFactory factory, final Scene scene, final ICanvas canvas, final Quality quality) {
				super(factory, scene, canvas, quality);
				//setDisplayAxisWholeBounds(true);
				setCameraRenderingSphereRadiusFactor(.85f);
				setHiDPIenabled(Prefs.SCALE_FACTOR > 1);
				setMaximized(false);
				get2DLayout().setVerticalAxisFlip(true); // backwards compatibility
			}

			void setHiDPIenabled(final boolean enabled) {
				super.hidpi = (enabled) ? HiDPI.ON : HiDPI.OFF;
				if (enabled)
					axis.getLayout().setFontSizePolicy(new HiDPITwoFontSizesPolicy(this));
				axis.getLayout().applyFontSizePolicy();
			}
			
			@Override
			public void setViewPoint(final Coord3d polar, final boolean updateView) {
				// see https://github.com/jzy3d/jzy3d-api/issues/214#issuecomment-975717207
				viewpoint = polar;
				if (updateView)
					shoot();
				fireViewPointChangedEvent(new ViewPointChangedEvent(this, polar));
			}
		}
	}

//	/* IDE debug method */
//	public static void main(final String[] args) throws InterruptedException {
//		 sc.fiji.snt.demo.RecViewerDemo.main(args);
//	}

}
