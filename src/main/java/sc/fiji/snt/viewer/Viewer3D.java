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

package sc.fiji.snt.viewer;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.DisplayMode;
import java.awt.FlowLayout;
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
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import javax.swing.AbstractAction;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.border.Border;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;

import org.jzy3d.bridge.awt.FrameAWT;
import org.jzy3d.chart.AWTChart;
import org.jzy3d.chart.Chart;
import org.jzy3d.chart.Settings;
import org.jzy3d.chart.controllers.ControllerType;
import org.jzy3d.chart.controllers.camera.AbstractCameraController;
import org.jzy3d.chart.controllers.mouse.AWTMouseUtilities;
import org.jzy3d.chart.controllers.mouse.camera.AWTCameraMouseController;
import org.jzy3d.chart.controllers.thread.camera.CameraThreadController;
import org.jzy3d.chart.factories.AWTChartComponentFactory;
import org.jzy3d.chart.factories.IChartComponentFactory;
import org.jzy3d.chart.factories.IFrame;
import org.jzy3d.colors.Color;
import org.jzy3d.colors.ColorMapper;
import org.jzy3d.colors.ISingleColorable;
import org.jzy3d.maths.BoundingBox3d;
import org.jzy3d.maths.Coord2d;
import org.jzy3d.maths.Coord3d;
import org.jzy3d.maths.Rectangle;
import org.jzy3d.plot2d.primitive.AWTColorbarImageGenerator;
import org.jzy3d.plot3d.primitives.AbstractDrawable;
import org.jzy3d.plot3d.primitives.AbstractWireframeable;
import org.jzy3d.plot3d.primitives.LineStrip;
import org.jzy3d.plot3d.primitives.Point;
import org.jzy3d.plot3d.primitives.Shape;
import org.jzy3d.plot3d.primitives.Sphere;
import org.jzy3d.plot3d.primitives.Tube;
import org.jzy3d.plot3d.primitives.axes.layout.providers.ITickProvider;
import org.jzy3d.plot3d.primitives.axes.layout.providers.RegularTickProvider;
import org.jzy3d.plot3d.primitives.axes.layout.providers.SmartTickProvider;
import org.jzy3d.plot3d.primitives.axes.layout.renderers.FixedDecimalTickRenderer;
import org.jzy3d.plot3d.primitives.axes.layout.renderers.ITickRenderer;
import org.jzy3d.plot3d.primitives.axes.layout.renderers.ScientificNotationTickRenderer;
import org.jzy3d.plot3d.primitives.vbo.drawable.DrawableVBO;
import org.jzy3d.plot3d.rendering.canvas.ICanvas;
import org.jzy3d.plot3d.rendering.canvas.IScreenCanvas;
import org.jzy3d.plot3d.rendering.canvas.Quality;
import org.jzy3d.plot3d.rendering.legends.colorbars.AWTColorbarLegend;
import org.jzy3d.plot3d.rendering.lights.Light;
import org.jzy3d.plot3d.rendering.lights.LightSet;
import org.jzy3d.plot3d.rendering.scene.Scene;
import org.jzy3d.plot3d.rendering.view.AWTView;
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
import org.scijava.display.DisplayService;
import org.scijava.plugin.Parameter;
import org.scijava.prefs.PrefService;
import org.scijava.ui.UIService;
import org.scijava.ui.awt.AWTWindows;
import org.scijava.ui.swing.script.TextEditor;
import org.scijava.util.ColorRGB;
import org.scijava.util.ColorRGBA;
import org.scijava.util.Colors;
import org.scijava.util.FileUtils;
import org.scijava.util.PlatformUtils;
import org.scijava.widget.FileWidget;

import com.jidesoft.swing.CheckBoxList;
import com.jidesoft.swing.CheckBoxTree;
import com.jidesoft.swing.ListSearchable;
import com.jidesoft.swing.SearchableBar;
import com.jidesoft.swing.TreeSearchable;
import com.jogamp.opengl.FPSCounter;
import com.jogamp.opengl.GLAnimatorControl;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLException;
import com.jogamp.opengl.GLProfile;

import net.imagej.ImageJ;
import net.imagej.display.ColorTables;
import net.imglib2.display.ColorTable;
import sc.fiji.snt.analysis.MultiTreeColorMapper;
import sc.fiji.snt.analysis.SNTTable;
import sc.fiji.snt.analysis.TreeAnalyzer;
import sc.fiji.snt.analysis.TreeColorMapper;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.SNTService;
import sc.fiji.snt.Tree;
import sc.fiji.snt.TreeProperties;
import sc.fiji.snt.analysis.TreeStatistics;
import sc.fiji.snt.annotation.AllenCompartment;
import sc.fiji.snt.annotation.AllenUtils;
import sc.fiji.snt.annotation.ZBAtlasUtils;
import sc.fiji.snt.annotation.VFBUtils;
import sc.fiji.snt.gui.FileDrop;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.IconFactory;
import sc.fiji.snt.gui.IconFactory.GLYPH;
import sc.fiji.snt.gui.SNTSearchableBar;
import sc.fiji.snt.gui.cmds.*;
import sc.fiji.snt.io.FlyCircuitLoader;
import sc.fiji.snt.io.MouseLightLoader;
import sc.fiji.snt.io.NeuroMorphoLoader;
import sc.fiji.snt.plugin.AnalyzerCmd;
import sc.fiji.snt.plugin.BrainAnnotationCmd;
import sc.fiji.snt.plugin.GroupAnalyzerCmd;
import sc.fiji.snt.plugin.ShollAnalysisTreeCmd;
import sc.fiji.snt.plugin.StrahlerCmd;
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

	/**
	 * Presets of a scene's view point.
	 */
	public enum ViewMode {
			/**
			 * No enforcement of view point: let the user freely turn around the
			 * scene.
			 */
			DEFAULT("Default"), //
			/**
			 * Enforce a lateral view point of the scene.
			 */
			SIDE("Side Constrained"), //
			/**
			 * Enforce a top view point of the scene with disabled rotation.
			 */
			TOP("Top Constrained"),
			/**
			 * Enforce an 'overview (two-point perspective) view point of the scene.
			 */
			PERSPECTIVE("Perspective");

		private String description;

		private ViewMode next() {
			switch (this) {
				case DEFAULT:
					return TOP;
				case TOP:
					return SIDE;
				case SIDE:
					return PERSPECTIVE;
				default:
					return DEFAULT;
			}
		}

		ViewMode(final String description) {
			this.description = description;
		}

	}

	private final static String MESH_LABEL_ALLEN = "Whole Brain";
	private final static String MESH_LABEL_ZEBRAFISH = "Outline (MP ZBA)";
	private final static String MESH_LABEL_JFRC2018 = "JFRC 2018";
	private final static String MESH_LABEL_JFRC2 = "JFRC2 (VFB)";
	private final static String MESH_LABEL_JFRC3 = "JFRC3";
	private final static String MESH_LABEL_FCWB = "FCWB";
	private final static String MESH_LABEL_VNS = "VNS";
	private final static String MESH_LABEL_L1 = "L1";
	private final static String MESH_LABEL_L3 = "L3";

	private final static String PATH_MANAGER_TREE_LABEL = "Path Manager Contents";
	private final static float DEF_NODE_RADIUS = 3f;
	private static final Color DEF_COLOR = new Color(1f, 1f, 1f, 0.05f);
	private static final Color INVERTED_DEF_COLOR = new Color(0f, 0f, 0f, 0.05f);

	/* Identifiers for multiple viewers */
	private static int currentID = 0;
	private int id;
	private boolean sntInstance;


	/* Maps for plotted objects */
	private final Map<String, ShapeTree> plottedTrees;
	private final Map<String, RemountableDrawableVBO> plottedObjs;
	private final Map<String, Annotation3D> plottedAnnotations;

	/* Settings */
	private Color defColor;
	private float defThickness = DEF_NODE_RADIUS;
	private final Prefs prefs;

	/* Color Bar */
	private ColorLegend cBar;

	/* Manager */
	private CheckboxListEditable managerList;

	private Chart chart;
	private View view;
	private ViewerFrame frame;
	private GuiUtils gUtils;
	private KeyController keyController;
	private MouseController mouseController;
	private boolean viewUpdatesEnabled = true;
	private ViewMode currentView;
	private FileDropWorker fileDropWorker;

	@Parameter
	private Context context;

	@Parameter
	private CommandService cmdService;

	@Parameter
	private DisplayService displayService;

	@Parameter
	private SNTService sntService;

	@Parameter
	private PrefService prefService;

	/**
	 * Instantiates Viewer3D without the 'Controls' dialog ('kiosk mode'). Such
	 * a viewer is more suitable for large datasets and allows for {@link Tree}s to
	 * be added concurrently.
	 */
	public Viewer3D() {
		SNTUtils.log("Initializing Viewer3D...");
		workaroundIntelGraphicsBug();
		Settings.getInstance().setGLCapabilities(new GLCapabilities(GLProfile.getDefault()));
		Settings.getInstance().setHardwareAccelerated(true);
		logGLDetails();
		plottedTrees = new TreeMap<>();
		plottedObjs = new TreeMap<>();
		plottedAnnotations = new TreeMap<>();
		initView();
		prefs = new Prefs(this);
		prefs.setPreferences();
		setID();
		SNTUtils.addViewer(this);
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
	}

	/**
	 * Script-friendly constructor.
	 *
	 * @param interactive if true, the viewer is displayed with GUI Controls to
	 *                    import, manage and customize the Viewer's scene.
	 */
	public Viewer3D(final boolean interactive) {
		this();
		if (interactive) {
			init(new Context(CommandService.class, DisplayService.class, PrefService.class, SNTService.class,
					UIService.class));
		}
	}

	protected Viewer3D(final SNT snt) {
		this(snt.getContext());
		sntInstance = true;
	}

	private void init(final Context context) {
		GuiUtils.setSystemLookAndFeel();
		initManagerList();
		context.inject(this);
		prefs.setPreferences();
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
	 * @return this this Viewer's unique numeric ID.
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
		}
		if (viewUpdatesEnabled) {
			chart.getView().updateBounds();
			managerList.model.update();
		}
	}

	private boolean chartExists() {
		return chart != null && chart.getCanvas() != null;
	}

	/* returns true if chart was initialized */
	private boolean initView() {
		if (chartExists()) return false;
		chart = new AChart(Quality.Nicest); // There does not seem to be a swing
																					// implementation of
		// ICameraMouseController so we are stuck with AWT
		chart.black();
		view = chart.getView();
		view.setBoundMode(ViewBoundMode.AUTO_FIT);
		keyController = new KeyController(chart);
		mouseController = new MouseController(chart);
		chart.getCanvas().addKeyController(keyController);
		chart.getCanvas().addMouseController(mouseController);
		chart.setAxeDisplayed(false);
		squarify("none", false);
		currentView = ViewMode.DEFAULT;
		gUtils = new GuiUtils((Component) chart.getCanvas());
		fileDropWorker = new FileDropWorker((Component) chart.getCanvas(), gUtils);
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

	private void rebuild() {
		SNTUtils.log("Rebuilding scene...");
		try {
			// remember settings so that they can be restored
			final boolean lighModeOn = !isDarkModeOn();
			final boolean axesOn = view.isAxeBoxDisplayed();
			final float currentZoomStep = keyController.zoomStep;
			final double currentRotationStep = keyController.rotationStep;
			final float currentPanStep = mouseController.panStep;
			final ISquarifier squarifier = view.getSquarifier();
			final boolean squared = view.getSquared();
			final CameraMode currentCameraMode = view.getCameraMode();
			final ViewportMode viewPortMode = view.getCamera().getMode();
			final Coord3d currentViewPoint = view.getViewPoint();
			final BoundingBox3d currentBox = view.getBounds();
			final boolean isAnimating = mouseController.isAnimating();
			chart.stopAnimator();
			chart.dispose();
			chart = null;
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
			view.setBoundManual(currentBox);
			addAllObjects();
			updateView();
			if (isAnimating) setAnimationEnabled(true);
			//if (managerList != null) managerList.selectAll();
		}
		catch (final GLException | NullPointerException exc) {
			SNTUtils.error("Rebuild Error", exc);
		}
		if (frame != null) frame.replaceCurrentChart(chart);
		updateView();
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
			frame.managerPanel.debugCheckBox.setSelected(enable);
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
		if (currentView == ViewMode.TOP) {
			throw new IllegalArgumentException("Rotations not allowed under " +
				ViewMode.TOP.description);
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
		sb.append("\n-------------------------------------------\n");
		sb.append("cd \"").append(destinationDirectory).append("\"\n");
		sb.append("ffmpeg -framerate ").append(prefs.getFPS()).append(" -i %5d.png -vf \"");
		if (currentView == ViewMode.SIDE && !view.isAxeBoxDisplayed()) sb.append("vflip,");
		sb.append("scale=-1:-1,format=yuv420p\" video.mp4");
		sb.append("\n-------------------------------------------\n");
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
		managerList = new CheckboxListEditable(new DefaultUpdatableListModel<>());
		managerList.getCheckBoxListSelectionModel().addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting()) {
				final List<String> selectedKeys = getLabelsCheckedInManager();
				plottedTrees.forEach((k1, shapeTree) -> {
					shapeTree.setDisplayed(selectedKeys.contains(k1));
				});
				plottedObjs.forEach((k2, drawableVBO) -> {
					drawableVBO.setDisplayed(selectedKeys.contains(k2));
				});
				plottedAnnotations.forEach((k2, annot) -> {
					annot.getDrawable().setDisplayed(selectedKeys.contains(k2));
				});
				// view.shoot();
			}
		});
	}

	protected Color fromAWTColor(final java.awt.Color color) {
		return (color == null) ? getDefColor() : new Color(color.getRed(), color
			.getGreen(), color.getBlue(), color.getAlpha());
	}

	private Color fromColorRGB(final ColorRGB color) {
		return (color == null) ? getDefColor() : new Color(color.getRed(), color
			.getGreen(), color.getBlue(), color.getAlpha());
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
			for (final String type : new String[] { "Dnd", "Axn" }) {
				final Tree subTree = tree.subTree(type);
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
	 * @param trees     the trees to be added
	 * @param color     Set it to {@code null}, {@code none} or {@code ""} to ignore
	 *                  this option altogether. Set it to {@code unique} to assign unique
	 *                  colors to each tree in the collection.
	 * @param commonTag a common tag to be assigned to the group (to be displayed in
	 *                  'RV Controls' list.
	 */
	public void addTrees(final Collection<Tree> trees, final String color, final String commonTag) {
		if (commonTag != null) {
			trees.forEach(tree -> {
				String label = tree.getLabel();
				if (label == null) label = "";
				tree.setLabel(label + "{" + commonTag + "}");
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
			final ColorRGB c = new ColorRGB(color);
			trees.forEach(tree -> tree.setColor(c));
			break;
		}
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
	 * Adds a collection of trees from reconstruction files
	 *
	 * @param files the reconstruction files to be imported
	 * @param color the color to be assigned to imported reconstructions. If null,
	 *              trees will be assigned a unique color
	 * @see #setSplitDendritesFromAxons(boolean)
	 */
	public void addTrees(final File[] files, final String color) {
		final ColorRGB c = (color == null || color.trim().isEmpty()) ? null : new ColorRGB(color);
		final GuiUtils g = (frame.managerPanel == null) ? frame.managerPanel.guiUtils : gUtils;
		fileDropWorker.importTreesWithoutDrop(files, c, g);
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
		if (visibleOnly) {
			final ArrayList<Tree> trees = new ArrayList<>();
			plottedTrees.forEach((k, shapeTree) -> {
				if (shapeTree.isDisplayed())
					trees.add(shapeTree.tree);
			});
			return trees;
		} else {
			final ArrayList<Tree> trees = new ArrayList<>(plottedTrees.values().size());
			plottedTrees.values().forEach(shapeTree -> trees.add(shapeTree.tree));
			return trees;
		}
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
		if (visibleOnly) {
			final ArrayList<OBJMesh> meshes = new ArrayList<>();
			plottedObjs.values().forEach( vbo -> {
				if (vbo.isDisplayed())
					meshes.add(vbo.objMesh);
			});
			return meshes;
		} else {
			final ArrayList<OBJMesh> meshes = new ArrayList<>(plottedObjs.values().size());
			plottedObjs.values().forEach( vbo -> meshes.add(vbo.objMesh));
			return meshes;
		}
	}

	/**
	 * Returns all annotations added to this viewer.
	 *
	 * @return the annotation list
	 */
	public List<Annotation3D> getAnnotations() {
		return new ArrayList<>(plottedAnnotations.values());
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
	 * @param computeVolume whether or not to compute the volume of the convex hull.
	 * 				 If true, the volume is stored in the "volume" field of the 
	 * 				 returned {@link Annotation3D} object.
	 * @return the {@link Annotation3D}
	 */
	public Annotation3D annotateSurface(final Collection<? extends SNTPoint> points, final String label, boolean computeVolume) {
		Annotation3D annotation;
		if (computeVolume) {
			annotation = new Annotation3D(this, points, Annotation3D.SURFACE_AND_VOLUME);
		} else { 
			annotation = new Annotation3D(this, points, Annotation3D.SURFACE);
		}
		final String uniquelabel = getUniqueLabel(plottedAnnotations, "Point Annot.", label);
		annotation.setLabel(uniquelabel);
		plottedAnnotations.put(uniquelabel, annotation);
		addItemToManager(uniquelabel);
		chart.add(annotation.getDrawable(), viewUpdatesEnabled);
		return annotation;
	}

	/**
	 * Adds an highlighting point annotation to this viewer.
	 *
	 * @param point the point to be highlighted
	 * @param label the (optional) annotation identifier. If null or empty, a unique
	 *              label will be generated.
	 * @return the {@link Annotation3D}
	 */
	public Annotation3D annotatePoint(final SNTPoint point, final String label) {
		final Annotation3D annotation = annotatePoints(Collections.singleton(point), label);
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
		setSceneUpdatesEnabled(false);;
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
	 * Updates the scene bounds to ensure all visible objects are displayed.
	 * 
	 * @see #rebuild()
	 */
	public void updateView() {
		if (view != null) {
			view.shoot(); // !? without forceRepaint() dimensions are not updated
			fitToVisibleObjects(false, false); //TODO: Why not use view.updateBounds()?
		}
		if (managerList != null) managerList.update(); // force update the manager list
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
		final JFrame dummy = new JFrame();
		final Frame frame = show( width, height, dummy.getGraphicsConfiguration());
		dummy.dispose();
		return frame;
	}

	private Frame show(final int width, final int height, final GraphicsConfiguration gConfiguration) {

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
			frame = new ViewerFrame(chart, managerList != null, gConfiguration);
		} else {
			final DisplayMode dm = gConfiguration.getDevice().getDisplayMode();
			final int w = (width < 0) ? dm.getWidth() : width;
			final int h = (height < 0) ? dm.getHeight() : height;
			frame = new ViewerFrame(chart, w, h, managerList != null, gConfiguration);
		}
		displayMsg("Press 'H' or 'F1' for help", 3000);
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

	private void displayMsg(final String msg) {
		displayMsg(msg, 2500);
	}

	private void delayedMsg(final int delay, final String msg, final int duration) {
		final Timer timer = new Timer(delay, e -> displayMsg(msg, duration));
		timer.setRepeats(false);
		timer.start();
	}

	private void displayMsg(final String msg, final int msecs) {
		if (gUtils != null && chartExists()) {
			gUtils.setTmpMsgTimeOut(msecs);
			if (frame.isFullScreen)
				gUtils.tempMsg(msg, SwingConstants.SOUTH_WEST);
			else
				gUtils.tempMsg(msg);
		}
		else {
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

	private Map<String, AbstractDrawable> getAnnotationDrawables() {
		final Map<String, AbstractDrawable> map = new HashMap<>();
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
			Tree tree = it.next();
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

	private void removeSceneObject(final String label, final String managerEntry) {
		if (!removeTree(label, managerEntry)) {
			if (!removeMesh(label, managerEntry))
				removeDrawable(getAnnotationDrawables(), label, managerEntry);
		}
	}

	private boolean isEmptyScene() {
		try {
			return view.getScene().getGraph().getAll().isEmpty();
		} catch (final Exception ignored) {
			return true;
		}
	}

	/**
	 * Script friendly method to add a supported object ({@link Tree},
	 * {@link OBJMesh}, {@link AbstractDrawable}, etc.) to this viewer. Note that
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
		try {
			if (object instanceof Tree) {
				addTree((Tree) object);
			} else if (object instanceof OBJMesh) {
				addMesh((OBJMesh) object);
			} else if (object instanceof String) {
				addLabel((String) object);
			} else if (object instanceof AbstractDrawable) {
				chart.add((AbstractDrawable) object, viewUpdatesEnabled);
			} else if (object instanceof Collection) {
				addCollection(((Collection<?>) object));
			} else {
				throw new IllegalArgumentException("Unsupported object: " + object.getClass().getName());
			}
		} catch (final ClassCastException ex) {
			throw new IllegalArgumentException(ex.getMessage());
		}
	}

	private void addCollection(final Collection<?> collection) {
		final boolean updateStatus = viewUpdatesEnabled;
		final boolean displayProgress = frame != null && frame.managerPanel != null;
		setSceneUpdatesEnabled(false);
		if (displayProgress)
			frame.managerPanel.setProgressLimit(0, ((Collection<?>) collection).size());
		int index = 0; // will be inaccurate if collection contains other collections
		for(final Object o : collection) {
			if (displayProgress) frame.managerPanel.setProgress(index++);
			add(o);
		}
		if (displayProgress) frame.managerPanel.resetProgressBar();
		setSceneUpdatesEnabled(updateStatus);
		validate();
	}

	/**
	 * Script friendly method to remove an object ({@link Tree}, {@link OBJMesh},
	 * {@link AbstractDrawable}, {@link String} (object label), etc.) from this viewer's scene.
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
		} else if (object instanceof AbstractDrawable && chart != null) {
			chart.getScene().getGraph().remove((AbstractDrawable) object, viewUpdatesEnabled);
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

	@SuppressWarnings("unchecked")
	private List<String> getLabelsCheckedInManager() {
		final Object[] values = managerList.getCheckBoxListSelectedValues();
		return (List<String>) (List<?>) Arrays.asList(values);
	}

	private <T extends AbstractDrawable> boolean allDrawablesRendered(
		final BoundingBox3d viewBounds, final Map<String, T> map,
		final List<String> selectedKeys)
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

	private synchronized <T extends AbstractDrawable> boolean removeDrawable(
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
		if (SNTUtils.getPluginInstance() == null) throw new IllegalArgumentException(
			"SNT is not running.");
		final Tree tree = new Tree(SNTUtils.getPluginInstance().getPathAndFillManager()
			.getPathsFiltered());
		if (plottedTrees.containsKey(PATH_MANAGER_TREE_LABEL)) {
			chart.getScene().getGraph().remove(plottedTrees.get(
				PATH_MANAGER_TREE_LABEL).get());
			final ShapeTree newShapeTree = new ShapeTree(tree);
			plottedTrees.replace(PATH_MANAGER_TREE_LABEL, newShapeTree);
			chart.add(newShapeTree.get(), viewUpdatesEnabled);
		}
		else {
			tree.setLabel(PATH_MANAGER_TREE_LABEL);
			addTree(tree);
		}
		updateView();
		return plottedTrees.get(PATH_MANAGER_TREE_LABEL).get().isDisplayed();
	}

	private boolean isValid(final AbstractDrawable drawable) {
		return drawable.getBounds() != null && drawable.getBounds().getRange()
			.distanceSq(new Coord3d(0f, 0f, 0f)) > 0f;
	}

	private boolean sceneIsOK() {
		try {
			updateView();
			if (managerList == null) return true;
			// now check that everything is visible
			final List<String> selectedKeys = getLabelsCheckedInManager();
			final BoundingBox3d viewBounds = chart.view().getBounds();
			return allDrawablesRendered(viewBounds, plottedObjs, selectedKeys) &&
				allDrawablesRendered(viewBounds, getTreeDrawables(), selectedKeys) &&
				allDrawablesRendered(viewBounds, getAnnotationDrawables(), selectedKeys);
		}
		catch (final GLException | ArrayIndexOutOfBoundsException ignored) {
			SNTUtils.log("Upate view failed...");
			return false;
		}

	}

	/** returns true if a drawable was removed */
	@SuppressWarnings("unused")
	private <T extends AbstractDrawable> boolean removeInvalid(
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
		final ShapeTree treeShape = plottedTrees.get(label);
		if (treeShape != null) treeShape.setDisplayed(visible);
		final DrawableVBO obj = plottedObjs.get(label);
		if (obj != null) obj.setDisplayed(visible);
		final Annotation3D annot = plottedAnnotations.get(label);
		if (annot != null) annot.getDrawable().setDisplayed(visible);
		if (frame != null && frame.managerPanel != null) {
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
	 *          {@link MultiTreeColorMapper#LENGTH}
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
		shapeTrees.forEach(st -> st.rebuildShape());
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
		chart.view().setBoundManual(bBox);
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
	 *          {@link ViewMode#SIDE} , etc.
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
	 * @param viewMode the view mode (case insensitive): "side" or "sagittal"; "top"
	 *                 or "coronal"; "perspective" or "overview"; "default" or "".
	 */
	public void setViewMode(final String viewMode) {
		if (viewMode == null || viewMode.trim().isEmpty()) {
			setViewMode(ViewMode.DEFAULT);
		}
		final String vMode = viewMode.toLowerCase();
		if (vMode.contains("side") || vMode.contains("sag")) {
			setViewMode(ViewMode.SIDE);
		} else if (vMode.contains("top") || vMode.contains("cor")) {
			setViewMode(ViewMode.TOP);
		} else if (vMode.contains("pers") || vMode.contains("ove")) {
			setViewMode(ViewMode.PERSPECTIVE);
		} else {
			setViewMode(ViewMode.DEFAULT);
		}
	}

	/**
	 * Renders the scene from a specified camera angle using polar coordinates
	 * relative to the the center of the scene. Only X and Y dimensions are
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
	 * Saves a snapshot of current scene as a PNG image. Image is saved using an
	 * unique time stamp as a file name in the directory specified in the
	 * preferences dialog or through {@link #setSnapshotDir(String)}
	 *
	 * @return true, if successful
	 */
	public boolean saveSnapshot() {
		final String filename = new SimpleDateFormat("'SNT 'yyyy-MM-dd HH-mm-ss'.png'")
				.format(new Date());
		final File file = new File(prefs.snapshotDir, filename);
		try {
			return saveSnapshot(file);
		} catch (final IllegalArgumentException | IOException e) {
			SNTUtils.error("IOException", e);
			return false;
		}
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

	protected boolean saveSnapshot(final File file) throws IllegalArgumentException, IOException {
		if (!chartExists()) {
			throw new IllegalArgumentException("Viewer is not visible");
		}
		SNTUtils.log("Saving snapshot to " + file);
		if (SNTUtils.isDebugMode() && frame != null) {
			logSceneControls();
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
		return (objMesh == null) ? false : loadOBJMesh(objMesh) != null;
	}

	private OBJMesh loadOBJMesh(final OBJMesh objMesh) {
		setAnimationEnabled(false);
		chart.add(objMesh.drawable, viewUpdatesEnabled); // this used to trigger a GLException when true?
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
		if (getOBJs().keySet().contains(label)) {
			setVisible(label, true);
			if (managerList != null)
				managerList.addCheckBoxListSelectedValue(label, true);
			return plottedObjs.get(label).objMesh;
		}
		OBJMesh objMesh;
		switch (label) {
		case MESH_LABEL_JFRC2018:
			objMesh = VFBUtils.getRefBrain("jfrc2018");
			break;
		case MESH_LABEL_JFRC2:
			objMesh = VFBUtils.getRefBrain("jfrc2");
			break;
		case MESH_LABEL_JFRC3:
			objMesh = VFBUtils.getRefBrain("jfrc3");
			break;
		case MESH_LABEL_FCWB:
			objMesh = VFBUtils.getRefBrain("fcwb");
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
			break;
		case MESH_LABEL_ZEBRAFISH:
			objMesh = ZBAtlasUtils.getRefBrain();
			break;
		default:
			throw new IllegalArgumentException("Invalid option: " + label);
		}
		objMesh.setLabel(label);
		objMesh.drawable.setColor(getNonUserDefColor());
		if (addMesh(objMesh) && viewUpdatesEnabled) validate();
		return objMesh;
	}

	private static String getNormalizedBrainLabel(final String input) {
		switch (input.toLowerCase()) {
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
		}
		return null;
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
	 * Logs API calls controlling the scene (view point, bounds, etc.) to Console.
	 * Useful for programmatic control of animations.
	 */
	public void logSceneControls() {
		SNTUtils.log("Logging scene controls:");
		final StringBuilder sb = new StringBuilder("\n");
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
		if (!visibleActors.isEmpty()) {
			sb.append("Visible objects: ").append(visibleActors.toString());
			sb.append("\n");
		}
		if (!hiddenActors.isEmpty()) {
			sb.append("Hidden  objects: ").append(hiddenActors.toString());
			sb.append("\n");
		}
		if (frame != null) {
			sb.append("viewer.setFrameSize(");
			sb.append(frame.getWidth()).append(", ").append(frame.getHeight()).append(");");
			sb.append("\n");
		}
		if (currentView == ViewMode.TOP) {
			sb.append("viewer.setViewMode(Viewer3D.ViewMode.TOP);");
		} else {
			final Coord3d viewPoint = view.getViewPoint();
			sb.append("viewer.setViewPoint(");
			sb.append(viewPoint.x).append(", ");
			sb.append(viewPoint.y).append(");");
		}
		sb.append("\n");
		final BoundingBox3d bounds = view.getBounds();
		sb.append("viewer.setBounds(");
		sb.append(bounds.getXmin()).append(", ");
		sb.append(bounds.getXmax()).append(", ");
		sb.append(bounds.getYmin()).append(", ");
		sb.append(bounds.getYmax()).append(", ");
		sb.append(bounds.getZmin()).append(", ");
		sb.append(bounds.getZmax()).append(");");
		sb.append("\n");
		System.out.println(sb.toString());
	}

//	/**
//	 * Returns this viewer's {@link View} holding {@link Scene}, {@link LightSet},
//	 * {@link ICanvas}, etc.
//	 *
//	 * @return this viewer's View, or null if it was disposed after {@link #show()}
//	 *         has been called
//	 */
//	public View getView() {
//		return (chart == null) ? null : view;
//	}

	/** ChartComponentFactory adopting {@link AView} */
	private class AChartComponentFactory extends AWTChartComponentFactory {

		@Override
		public View newView(final Scene scene, final ICanvas canvas,
			final Quality quality)
		{
			return new AView(getFactory(), scene, canvas, quality);
		}
	}

	/** AWTChart adopting {@link AView} */
	private class AChart extends AWTChart {

		private final Coord3d TOP_VIEW = new Coord3d(Math.PI / 2, 0.5, 3000);
		private final Coord3d PERSPECTIVE_VIEW = new Coord3d(Math.PI / 2, 0.5, 3000);
		private final Coord3d SIDE_VIEW = new Coord3d(Math.PI, 0, 3000);

		private Coord3d previousViewPointPerspective;
		private OverlayAnnotation overlayAnnotation;

		public AChart(final Quality quality) {
			super(new AChartComponentFactory(), quality, DEFAULT_WINDOWING_TOOLKIT,
				org.jzy3d.chart.Settings.getInstance().getGLCapabilities());
			currentView = ViewMode.DEFAULT;
			addRenderer(overlayAnnotation = new OverlayAnnotation(getView()));
		}

		// see super.setViewMode(mode);
		public void setViewMode(final ViewMode view) {
			// Store current view mode and view point in memory
			if (currentView == ViewMode.DEFAULT) previousViewPointFree = getView()
				.getViewPoint();
			else if (currentView == ViewMode.TOP) previousViewPointTop = getView()
				.getViewPoint();
			else if (currentView == ViewMode.SIDE) previousViewPointProfile = getView()
				.getViewPoint();
			else if (currentView == ViewMode.PERSPECTIVE) previousViewPointPerspective =
				getView().getViewPoint();

			// Set new view mode and former view point
			getView().setViewPositionMode(null);
			if (view == ViewMode.DEFAULT) {
				getView().setViewPositionMode(ViewPositionMode.FREE);
				getView().setViewPoint(previousViewPointFree == null ? View.DEFAULT_VIEW
					.clone() : previousViewPointFree);
			}
			else if (view == ViewMode.TOP) {
				getView().setViewPositionMode(ViewPositionMode.TOP);
				getView().setViewPoint(previousViewPointTop == null ? TOP_VIEW.clone()
					: previousViewPointTop);
			}
			else if (view == ViewMode.SIDE) {
				getView().setViewPositionMode(ViewPositionMode.PROFILE);
				getView().setViewPoint(previousViewPointProfile == null
					? SIDE_VIEW.clone() : previousViewPointProfile);
			}
			else if (view == ViewMode.PERSPECTIVE) {
				getView().setViewPositionMode(ViewPositionMode.FREE);
				getView().setViewPoint(previousViewPointPerspective == null
					? PERSPECTIVE_VIEW.clone() : previousViewPointPerspective);
			}
			getView().shoot();
			currentView = view;
		}

	}


	/** AWTColorbarLegend with customizable font/ticks/decimals, etc. */
	private class ColorLegend extends AWTColorbarLegend {

		private final Shape shape;
		private final Font font;

		public ColorLegend(final ColorTableMapper mapper, final Font font,
			final int steps, final int precision)
		{
			super(new Shape(), chart);
			shape = (Shape) drawable;
			this.font = font;
			shape.setColorMapper(mapper);
			shape.setLegend(this);
			updateColors();
			provider = (steps < 0) ? new SmartTickProvider(5)
				: new RegularTickProvider(steps);
			renderer = (precision < 0) ? new ScientificNotationTickRenderer(-1 *
				precision) : new FixedDecimalTickRenderer(precision);
			if (imageGenerator == null) init();
			imageGenerator.setFont(font);
		}

		public ColorLegend(final ColorTableMapper mapper) {
			this(mapper, new Font(Font.SANS_SERIF, Font.PLAIN, (int) (12 * Prefs.SCALE_FACTOR)), 5, 2);
		}

		public void update(final double min, final double max, final float fontSize) {
			shape.getColorMapper().setMin(min);
			shape.getColorMapper().setMax(max);
			if (fontSize > 0)
				imageGenerator.setFont(imageGenerator.getFont().deriveFont(fontSize));
			((ColorbarImageGenerator) imageGenerator).setMin(min);
			((ColorbarImageGenerator) imageGenerator).setMax(max);
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
		public void initImageGenerator(final AbstractDrawable parent,
			final ITickProvider provider, final ITickRenderer renderer)
		{
			if (shape != null) imageGenerator = new ColorbarImageGenerator(shape
				.getColorMapper(), provider, renderer, font.getSize());
		}

	}

	private class ColorbarImageGenerator extends AWTColorbarImageGenerator {

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
			final int maxWidth = graphic.getFontMetrics().stringWidth(renderer.format(
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

	/**
	 * Adapted AWTView so that top/side views better match to coronal/sagittal
	 * ones
	 */
	private class AView extends AWTView {

		public AView(final IChartComponentFactory factory, final Scene scene,
			final ICanvas canvas, final Quality quality)
		{
			super(factory, scene, canvas, quality);
			//super.DISPLAY_AXE_WHOLE_BOUNDS = true;
			//super.MAINTAIN_ALL_OBJECTS_IN_VIEW = true;
			//setBoundMode(ViewBoundMode.AUTO_FIT);
		}

		@Override
		protected Coord3d computeCameraEyeTop(final Coord3d viewpoint,
			final Coord3d target)
		{
			Coord3d eye = viewpoint;
			eye.x = -(float) Math.PI / 2; // on x
			eye.y = -(float) Math.PI / 2; // on bottom
			eye = eye.cartesian().add(target);
			return eye;
		}
	}

	// TODO: MouseController is more responsive with FrameAWT?
	private class ViewerFrame extends FrameAWT implements IFrame {

		private static final long serialVersionUID = 1L;
		private static final int DEF_WIDTH = 800;
		private static final int DEF_HEIGHT = 600;

		private Chart chart;
		private Component canvas;
		private JDialog manager;
		private LightController lightController;
		private AllenCCFNavigator allenNavigator;
		private ManagerPanel managerPanel;

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
		public ViewerFrame(final Chart chart, final boolean includeManager, final GraphicsConfiguration gConfiguration) {
			this(chart, (int) (DEF_WIDTH * Prefs.SCALE_FACTOR), (int) (DEF_HEIGHT * Prefs.SCALE_FACTOR), includeManager,
					gConfiguration);
		}

		public ViewerFrame(final Chart chart, final int width, final int height, final boolean includeManager,
				final GraphicsConfiguration gConfiguration) {
			super();
			final String title = (isSNTInstance()) ? " (SNT)" : " ("+ getID() + ")";
			initialize(chart, new Rectangle(width, height), "Reconstruction Viewer" +
				title);
			if (PlatformUtils.isLinux()) new MultiDisplayUtil(this);
			AWTWindows.centerWindow(gConfiguration.getBounds(), this);
			//setLocationRelativeTo(null); // ensures frame will not appear in between displays on a multidisplay setup
			if (includeManager) {
				manager = getManager();
				managerList.selectAll();
				snapPanelToSide();
				manager.setVisible(true);
			}
			toFront();
		}

		private void snapPanelToSide() {
			final java.awt.Point parentLoc = getLocation();
			manager.setLocation(parentLoc.x + getWidth() + 5, parentLoc.y);
		}

		public void replaceCurrentChart(final Chart chart) {
			this.chart = chart;
			canvas = (Component) chart.getCanvas();
			removeAll();
			add(canvas);
			// doLayout();
			revalidate();
			// update(getGraphics());
		}

		public JDialog getManager() {
			final JDialog dialog = new JDialog(this, "RV Controls");
			managerPanel = new ManagerPanel(new GuiUtils(dialog));
			dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
			dialog.addWindowListener(new WindowAdapter() {
				@Override
				public void windowClosing(final WindowEvent e) {
					exitRequested(managerPanel.guiUtils);
				}
			});
			// dialog.setLocationRelativeTo(this);
			dialog.setMinimumSize(new Dimension(dialog.getMinimumSize().width, getHeight()/2));
			dialog.setContentPane(managerPanel);
			dialog.pack();
			return dialog;
		}

		private void displayLightController() {
			lightController = new LightController(this);
			lightController.display();
		}
	
		private void exitRequested(final GuiUtils gUtilsDefiningPrompt) {
			if (gUtilsDefiningPrompt.getConfirmation("Quit Reconstruction Viewer?", "Quit?", "Yes. Quit Now",
					"No. Keep Open")) {
				Viewer3D.this.dispose();
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
			this.chart = chart;
			canvas = (Component) chart.getCanvas();
			setTitle(title);
			add(canvas);
			pack();
			setSize(new Dimension(bounds.width, bounds.height));
			AWTWindows.centerWindow(this);
			addWindowListener(new WindowAdapter() {
				@Override
				public void windowClosing(final WindowEvent e) {
					exitRequested(gUtils);
				}
			});
			setVisible(true);
		}

		public void disposeFrame() {
			chart.stopAnimator();
			ViewerFrame.this.remove(canvas);
			ViewerFrame.this.chart.dispose();
			ViewerFrame.this.chart = null;
			if (ViewerFrame.this.manager != null) ViewerFrame.this.manager.dispose();
			ViewerFrame.this.dispose();
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
				if (manager!= null) manager.setVisible(true);
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
				setExtendedState(JFrame.MAXIMIZED_BOTH );
				isFullScreen = true;
				delayedMsg(300, "Press \"Esc\" to exit Full Screen", 3000); // without delay popup is not shown?
			}
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
			final boolean darkMode = isDarkModeOn();
			frame.dispose();
			if (hasManager) {
				frame.manager.dispose();
				Viewer3D.this.initManagerList();
			}
			Viewer3D.this.show((int) dim.getWidth(), (int) dim.getHeight(), gConfiguration);
			setEnableDarkMode(darkMode);
			if (hasManager) {
				Viewer3D.this.frame.snapPanelToSide();
				Viewer3D.this.frame.manager.setVisible(true);
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
		public boolean nagUserOnRetrieveAll;
		public boolean retrieveAllIfNoneSelected;
		public String treeCompartmentChoice;

		private final Viewer3D tp;
		private final KeyController kc;
		private final MouseController mc;
		private String storedSensitivity;
		private String snapshotDir;
		private File lastDir;


		public Prefs(final Viewer3D tp) {
			this.tp = tp;
			kc = tp.keyController;
			mc = tp.mouseController;
		}

		private void setPreferences() {
			splitDendritesFromAxons = DEF_SPLIT_DENDRITES_FROM_AXONS;
			nagUserOnRetrieveAll = DEF_NAG_USER_ON_RETRIEVE_ALL;
			retrieveAllIfNoneSelected = DEF_RETRIEVE_ALL_IF_NONE_SELECTED;
			treeCompartmentChoice = DEF_TREE_COMPARTMENT_CHOICE;
			setSnapshotDirectory();
			lastDir = new File(System.getProperty("user.home"));
			if (tp.prefService == null) {
				kc.zoomStep = DEF_ZOOM_STEP;
				kc.rotationStep = DEF_ROTATION_STEP;
				mc.panStep = PAN.DEF_PAN_STEP;
			}
			else {
				kc.zoomStep = getZoomStep();
				kc.rotationStep = getRotationStep();
				mc.panStep = getPanStep();
				storedSensitivity = null;
			}
		}

		private String getScriptExtension() {
			return tp.prefService.get(RecViewerPrefsCmd.class, "scriptExtension",
					RecViewerPrefsCmd.DEF_SCRIPT_EXTENSION);
		}

		private String getBoilerplateScript(final String ext) {
			final HashMap<String, String> map = new HashMap<>();
			map.put(".bsh", "BSH.bsh");
			map.put(".groovy", "GVY.groovy");
			map.put(".py", "PY.py");
			final ClassLoader classloader = Thread.currentThread().getContextClassLoader();
			final InputStream is = classloader.getResourceAsStream("script_templates/Neuroanatomy/Boilerplate/"
					+ map.get(ext));
			return  new BufferedReader(new InputStreamReader(is)).lines().collect(Collectors.joining("\n"));
		}

		private void setSnapshotDirectory() {
			snapshotDir = (tp.prefService == null) ? RecViewerPrefsCmd.DEF_SNAPSHOT_DIR
			: tp.prefService.get(RecViewerPrefsCmd.class, "snapshotDir",
				RecViewerPrefsCmd.DEF_SNAPSHOT_DIR);
			final File dir = new File(snapshotDir);
			if (!dir.exists() || !dir.isDirectory()) dir.mkdirs();
		}

		private float getSnapshotRotationAngle() {
			return tp.prefService.getFloat(RecViewerPrefsCmd.class, "rotationAngle",
				RecViewerPrefsCmd.DEF_ROTATION_ANGLE);
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

		public boolean isSplitDendritesFromAxons() {
			return splitDendritesFromAxons;
		}

		public void setSplitDendritesFromAxons(boolean splitDendritesFromAxons) {
			this.splitDendritesFromAxons = splitDendritesFromAxons;
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

	public class ManagerPanel extends JPanel {

		private static final long serialVersionUID = 1L;
		private final GuiUtils guiUtils;
		private SNTTable table;
		private JCheckBoxMenuItem debugCheckBox;
		private final SNTSearchableBar searchableBar;
		private final JProgressBar progressBar;

		private ManagerPanel(final GuiUtils guiUtils) {
			super();
			this.guiUtils = guiUtils;
			setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
			searchableBar = new SNTSearchableBar(new ListSearchable(managerList));
			searchableBar.setGuiUtils(guiUtils);
			searchableBar.setVisibleButtons(SNTSearchableBar.SHOW_CLOSE |
				SNTSearchableBar.SHOW_NAVIGATION | SNTSearchableBar.SHOW_HIGHLIGHTS |
				SNTSearchableBar.SHOW_SEARCH_OPTIONS | SNTSearchableBar.SHOW_STATUS);
			final JPanel barPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
			setFixedHeightToPanel(barPanel);
			barPanel.add(searchableBar);
			barPanel.setVisible(false);
			searchableBar.setInstaller(new SearchableBar.Installer() {

				@Override
				public void openSearchBar(final SearchableBar searchableBar) {
					final Container dialog = getRootPane().getParent();
					dialog.setSize(searchableBar.getWidth(), dialog.getHeight());
					barPanel.setVisible(true);
					searchableBar.focusSearchField();
				}

				@Override
				public void closeSearchBar(final SearchableBar searchableBar) {
					final Container dialog = getRootPane().getParent();
					barPanel.setVisible(false);
					dialog.setSize(getPreferredSize().width, dialog.getHeight());
				}
			});
			final JScrollPane scrollPane = new JScrollPane(managerList);
			managerList.setComponentPopupMenu(popupMenu());
			scrollPane.setWheelScrollingEnabled(true);
			scrollPane.setBorder(null);
			scrollPane.setViewportView(managerList);

			progressBar = new JProgressBar();
			progressBar.setStringPainted(true);
			progressBar.setFocusable(false);
			resetProgressBar();

			add(scrollPane);
			scrollPane.revalidate();
			add(progressBar);
			add(barPanel);
			add(buttonPanel());
			fileDropWorker = new FileDropWorker(managerList, guiUtils);
		}

		public void resetProgressBar() {
			progressBar.setIndeterminate(true);
			progressBar.setVisible(false);
			progressBar.setMinimum(0);
			progressBar.setMaximum(100);
		}

		public void setProgressLimit(final int min, final int max) {
			progressBar.setMinimum(min);
			progressBar.setMaximum(max);
			progressBar.setVisible(true);
		}
	
		public void setProgress(final int value) {
			if (value == -1) {
				progressBar.setIndeterminate(true);
				progressBar.setString("Loading...");
				progressBar.setVisible(true);
			} else {
				progressBar.setIndeterminate(false);
				progressBar.setString(null);
				progressBar.setValue(value); // assume it is visible already
			}
		}

		class Action extends AbstractAction {
			static final String ALL = "All";
			static final String ENTER_FULL_SCREEN = "Full Screen";
			static final String FIND = "Toggle Selection Toolbar";
			static final String FIT = "Fit to Visible Objects";
			static final String LOG = "Log Scene Details";
			static final String NONE = "None";
			static final String REBUILD = "Rebuild Scene...";
			static final String RELOAD = "Reload Scene";
			static final String RESET = "Reset Scene";
			static final String SCENE_SHORTCUTS_LIST = "Scene Shortcuts...";
			static final String SCENE_SHORTCUTS_NOTIFICATION = "Scene Shortcuts (Notification)...";
			static final String SCRIPT = "Script This Viewer";
			static final String SNAPSHOT = "Take Snapshot";
			static final String SYNC = "Sync Path Manager Changes";
			static final String TAG = "Add Tag(s)...";
			static final long serialVersionUID = 1L;
			final String name;

			Action(final String name) {
				super(name);
				this.name = name;
			}

			Action(final String name, final int key, final boolean requireCtrl, final boolean requireShift) {
				this(name);
				int mod = 0;
				if (requireCtrl)
					mod = Toolkit.getDefaultToolkit().getMenuShortcutKeyMask();
				if (requireShift)
					mod |= KeyEvent.SHIFT_MASK;
				final KeyStroke ks = KeyStroke.getKeyStroke(key, mod);
				putValue(AbstractAction.ACCELERATOR_KEY, ks);
				if (mod == 0) putValue(AbstractAction.MNEMONIC_KEY, key);
				// register action in panel
				registerKeyboardAction(this, ks, JComponent.WHEN_IN_FOCUSED_WINDOW);
			}

			@Override
			public void actionPerformed(final ActionEvent e) {
				switch (name) {
				case ALL:
					managerList.selectAll();
					return;
				case FIND:
					if (searchableBar.isShowing()) {
						searchableBar.getInstaller().closeSearchBar(searchableBar);
					} else {
						searchableBar.getInstaller().openSearchBar(searchableBar);
						searchableBar.focusSearchField();
					}
					return;
				case ENTER_FULL_SCREEN:
					frame.enterFullScreen();
					return;
				case FIT:
					fitToVisibleObjects(true, true);
					return;
				case LOG:
					logSceneControls();
					try {
						context.getService(UIService.class).getDefaultUI().getConsolePane().show();
					} catch (final NullPointerException ignored) {
						// do nothing
					}
					break;
				case NONE:
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
				case RESET:
					keyController.resetView();
					return;
				case SCENE_SHORTCUTS_LIST:
					 keyController.showHelp(true);
					 return;
				case SCENE_SHORTCUTS_NOTIFICATION:
					 keyController.showHelp(false);
					 return;
				case SCRIPT:
					runScriptEditor(prefs.getScriptExtension());
					return;
				case SNAPSHOT:
					keyController.saveScreenshot();
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
					if (managerList.isSelectionEmpty()) {
						checkRetrieveAllOptions("objects");
						if (!prefs.retrieveAllIfNoneSelected)
							return;
					}
					final String tags = guiUtils.getString("Enter one or more tags (space or comma-separated list)\n"//
							+ "to be assigned to selected items. Tags encoding a color\n"//
							+ "(e.g., 'red', 'lightblue') will be use to highligh entries.\n"//
							+ "After dismissing this dialog:\n" //
							+ "  - Double-click on an object to edit its tags\n" //
							+ "  - Double-click on '" + CheckBoxList.ALL_ENTRY.toString()
							+ "' to add tags to the entire list", //
							"Add Tag(s)", "");
					if (tags == null)
						return; // user pressed cancel
					managerList.applyTagToSelectedItems(tags);
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
		}

		private JPanel buttonPanel() {
			final JPanel buttonPanel = new JPanel(new GridLayout(1, 6));
			buttonPanel.setBorder(null);
			// do not allow panel to resize vertically
			setFixedHeightToPanel(buttonPanel);
			buttonPanel.add(menuButton(GLYPH.MASKS, sceneMenu(), "Scene Controls"));
			buttonPanel.add(menuButton(GLYPH.TREE, treesMenu(), "Neuronal Arbors"));
			buttonPanel.add(menuButton(GLYPH.CUBE, meshMenu(), "3D Meshes"));
			buttonPanel.add(menuButton(GLYPH.ATLAS, refBrainsMenu(), "Reference Brains"));
			buttonPanel.add(menuButton(GLYPH.CALCULATOR, measureMenu(), "Analyze & Measure"));
			buttonPanel.add(menuButton(GLYPH.TOOL, utilsMenu(), "Utilities"));
			buttonPanel.add(menuButton(GLYPH.COG, prefsMenu(), "Settings"));
			return buttonPanel;
		}

		private void setFixedHeightToPanel(final JPanel panel) {
			// do not allow panel to resize vertically
			panel.setMaximumSize(
					new Dimension(panel.getMaximumSize().width, (int) panel.getPreferredSize().getHeight()));
		}

		private JButton menuButton(final GLYPH glyph, final JPopupMenu menu, final String tooltipMsg) {
			final JButton button = new JButton(IconFactory.getButtonIcon(glyph));
			button.setToolTipText(tooltipMsg);
			button.addActionListener(e -> menu.show(button, button.getWidth() / 2, button.getHeight() / 2));
			return button;
		}

		private JPopupMenu sceneMenu() {
			final JPopupMenu sceneMenu = new JPopupMenu();
			final JMenuItem fit = new JMenuItem(new Action(Action.FIT, KeyEvent.VK_F, false, false));
			fit.setIcon(IconFactory.getMenuIcon(GLYPH.EXPAND));
			sceneMenu.add(fit);
			// Aspect-ratio controls
			final JMenuItem jcbmiFill = new JCheckBoxMenuItem("Stretch-to-Fill");
			jcbmiFill.setIcon(IconFactory.getMenuIcon(GLYPH.EXPAND_ARROWS1));
			jcbmiFill.addItemListener(e -> {
				final ViewportMode mode = (jcbmiFill.isSelected()) ? ViewportMode.STRETCH_TO_FILL
						: ViewportMode.RECTANGLE_NO_STRETCH;
				view.getCamera().setViewportMode(mode);
			});
			sceneMenu.add(jcbmiFill);
			sceneMenu.add(squarifyMenu());
			final JMenuItem fullScreen = new JMenuItem(new Action(Action.ENTER_FULL_SCREEN, KeyEvent.VK_F, false, true));
			fullScreen.setIcon(IconFactory.getMenuIcon(GLYPH.EXPAND_ARROWS2));
			sceneMenu.add(fullScreen);
			sceneMenu.addSeparator();

			final JMenuItem reset = new JMenuItem(new Action(Action.RESET, KeyEvent.VK_R, false, false));
			reset.setIcon(IconFactory.getMenuIcon(GLYPH.BROOM));
			sceneMenu.add(reset);
			final JMenuItem reload = new JMenuItem(new Action(Action.RELOAD, KeyEvent.VK_R, false, true));
			reload.setIcon(IconFactory.getMenuIcon(GLYPH.REDO));
			sceneMenu.add(reload);
			final JMenuItem rebuild = new JMenuItem(new Action(Action.REBUILD, KeyEvent.VK_R, true, true));
			rebuild.setIcon(IconFactory.getMenuIcon(GLYPH.RECYCLE));
			sceneMenu.add(rebuild);;
			JMenuItem wipe = new JMenuItem("Wipe Scene...", IconFactory.getMenuIcon(GLYPH.DANGER));
			wipe.addActionListener(e -> wipeScene());
			sceneMenu.add(wipe);
			sceneMenu.addSeparator();

			final JMenuItem help = new JMenuItem(new Action(Action.SCENE_SHORTCUTS_LIST, KeyEvent.VK_F1, false, false));
			new Action(Action.SCENE_SHORTCUTS_NOTIFICATION, KeyEvent.VK_H, false, false); // register alternative shortcut
			help.setIcon(IconFactory.getMenuIcon(GLYPH.KEYBOARD));
			sceneMenu.add(help);
			sceneMenu.addSeparator();

			final JMenuItem sync = new JMenuItem(new Action(Action.SYNC, KeyEvent.VK_S, true, true));
			sync.setIcon(IconFactory.getMenuIcon(GLYPH.SYNC));
			sync.setEnabled(isSNTInstance());
			sceneMenu.add(sync);
			return sceneMenu;
		}

		private void wipeScene() {
			if (guiUtils.getConfirmation("Remove all items from scene? This action cannot be undone.", "Wipe Scene?")) {
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
			}
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

		private JPopupMenu popupMenu() {
			final JMenuItem sort = new JMenuItem("Sort List", IconFactory.getMenuIcon(GLYPH.SORT));
			sort.addActionListener(e -> {
				if (noLoadedItemsGuiError()) {
					return;
				}
				if (!guiUtils.getConfirmation("Sort List by categories? (any existing tags will be lost)",
						"Sort List?")) {
					return;
				}
				final List<String> checkedLabels = getLabelsCheckedInManager();
				try {
					managerList.setValueIsAdjusting(true);
					managerList.model.removeAllElements();
					plottedTrees.keySet().forEach(k -> {
						managerList.model.addElement(k);
					});
					plottedObjs.keySet().forEach(k -> {
						managerList.model.addElement(k);
					});
					plottedAnnotations.keySet().forEach(k -> {
						managerList.model.addElement(k);
					});
					managerList.model.addElement(CheckBoxList.ALL_ENTRY);
				} finally {
					managerList.setValueIsAdjusting(false);
				}
				managerList.addCheckBoxListSelectedValues(checkedLabels.toArray());
			});

			final JMenuItem addTag = new JMenuItem(new Action(Action.TAG, KeyEvent.VK_T, true, true));
			addTag.setIcon(IconFactory.getMenuIcon(GLYPH.TAG));
			final JMenuItem wipeTags = new JMenuItem("Remove Tags...");
			wipeTags.addActionListener(e -> {
				if (noLoadedItemsGuiError())
					return;
				if (managerList.isSelectionEmpty()) {
					checkRetrieveAllOptions("objects");
					if (!prefs.retrieveAllIfNoneSelected)
						return;
				}
				if (guiUtils.getConfirmation("Remove all tags from selected items?", "Dispose All Tags?")) {
					managerList.removeTagsFromSelectedItems();
				}
			});
			final JMenuItem renderIcons = new JCheckBoxMenuItem("Label Categories",
					IconFactory.getMenuIcon(GLYPH.MARKER));
			renderIcons.addItemListener(e -> {
				managerList.setIconsVisible((renderIcons.isSelected()));
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
			hideTrees.addActionListener(e -> {
				setArborsDisplayed(getLabelsCheckedInManager(), false);
			});
			final JMenuItem hideAnnotations = new JMenuItem("Annotations");
			hideAnnotations.addActionListener(e -> hide(plottedAnnotations));
			final JMenuItem hideSomas = new JMenuItem("Soma of Visible Trees");
			hideSomas.addActionListener(e -> displaySomas(false));
			final JMenuItem hideBoxes = new JMenuItem("Bounding Box of Visible Meshes");
			hideBoxes.addActionListener(e -> displayMeshBoundingBoxes(false));
//			final JMenuItem hideAll = new JMenuItem("All");
//			hideAll.addActionListener(e -> managerList.selectNone());
			final JMenuItem hideSelected = new JMenuItem("Selected");
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
			final JMenuItem showSomas = new JMenuItem("Soma of Visible Trees");
			showSomas.addActionListener(e -> displaySomas(true));
			final JMenuItem showBoxes = new JMenuItem("Bounding Box of Visible Meshes");
			showBoxes.addActionListener(e -> displayMeshBoundingBoxes(true));
//			final JMenuItem showAll = new JMenuItem("All");
//			showAll.addActionListener(e -> managerList.selectAll());
			final JMenuItem showSelected = new JMenuItem("Selected");
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
				if (noLoadedItemsGuiError()) {
					return;
				}
				final List<?> selectedKeys = managerList.getSelectedValuesList();
				if (selectedKeys.isEmpty()) {
					guiUtils.error("There are no selected entries.");
					return;
				}
				if (selectedKeys.size() == 1 && CheckBoxList.ALL_ENTRY.equals(selectedKeys.get(0))) {
					wipeScene();
					return;
				}
				if (guiUtils.getConfirmation("Remove selected item(s)?", "Confirm Deletion?")) {
					selectedKeys.forEach(k -> {
						if (k.equals(CheckBoxList.ALL_ENTRY))
							return; // continue in lambda expression
						final String[] labelAndManagerEntry = TagUtils.getUntaggedAndTaggedLabels(k.toString());
						removeSceneObject(labelAndManagerEntry[0], labelAndManagerEntry[1]);
					});
					managerList.update();
				}
			});

			final JPopupMenu pMenu = new JPopupMenu();
			pMenu.add(selectMenu);
			pMenu.add(showMenu);
			pMenu.add(hideMenu);
			pMenu.addSeparator();
			pMenu.add(addTag);
			pMenu.add(wipeTags);
			pMenu.add(renderIcons);
			pMenu.addSeparator();
			final JMenuItem find = new JMenuItem(new Action(Action.FIND, KeyEvent.VK_F, true, false));
			find.setIcon(IconFactory.getMenuIcon(GLYPH.BINOCULARS));
			pMenu.add(find);
			pMenu.addSeparator();
			pMenu.add(sort);
			pMenu.addSeparator();
			pMenu.add(remove);
			return pMenu;
		}

		private boolean noLoadedItemsGuiError() {
			final boolean noItems = plottedTrees.isEmpty() && plottedObjs.isEmpty() && plottedAnnotations.isEmpty();
			if (noItems) {
				guiUtils.error("There are no loaded items.");
			}
			return noItems;
		}

		private void displaySomas(final boolean displayed) {
			final List<String> labels = getLabelsCheckedInManager();
			if (labels.isEmpty()) {
				displayMsg("There are no visible reconstructions");
				return;
			}
			setSomasDisplayed(labels, displayed);
		}

		private void displayMeshBoundingBoxes(final boolean display) {
			final List<String> labels = getLabelsCheckedInManager();
			if (labels.isEmpty()) {
				displayMsg("There are no items selectedt");
				return;
			}
			plottedObjs.forEach((k, mesh) -> {
				if (labels.contains(k)) {
					if (display && mesh.getBoundingBoxColor() == null)
						mesh.setBoundingBoxColor(mesh.getColor());
					mesh.setBoundingBoxDisplayed(display);
				}
			});
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
						managerList.addCheckBoxListSelectedIndex(i);
					else
						managerList.removeCheckBoxListSelectedIndex(i);
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
			final List<String> selectedKeys = new ArrayList<String>(getLabelsCheckedInManager());
			selectedKeys.removeAll(map.keySet());
			managerList.setSelectedObjects(selectedKeys.toArray());
		}

		private Tree getSingleSelectionTree() {
			if (plottedTrees.size() == 1) return plottedTrees.values().iterator()
				.next().tree;
			final List<Tree> trees = getSelectedTrees(false);
			if (trees == null) return null;
			if (trees.isEmpty() || trees.size() > 1) {
				guiUtils.error(
					"This command requires a single recontruction to be selected.");
				return null;
			}
			return trees.get(0);
		}

		private Tree getSingleSelectionTreeWithPromptForType() {
			Tree tree = getSingleSelectionTree();
			if (tree == null) return null;
			final Set<Integer> types = tree.getSWCTypes();
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
			return getSelectedTrees(prefs.retrieveAllIfNoneSelected);
		}
	
		private List<Tree> getSelectedTrees(final boolean promptForAllIfNone) {
			final List<String> keys = getSelectedKeys(plottedTrees, "reconstructions", promptForAllIfNone);
			if (keys == null) return null;
			final List<Tree> trees = new ArrayList<>();
			keys.forEach( k -> {
				final ShapeTree sTree = plottedTrees.get(k);
				if (sTree != null) trees.add(sTree.tree);
			});
			return trees;
		}

		private List<String> getSelectedTreeLabels() {
			return getSelectedKeys(plottedTrees, "reconstructions", prefs.retrieveAllIfNoneSelected);
		}

		private List<String> getSelectedMeshes(final boolean promptForAllIfNone) {
			return getSelectedKeys(plottedObjs, "meshes", promptForAllIfNone);
		}

		private List<String> getSelectedKeys(final Map<String, ?> map,
			final String mapDescriptor, final boolean promptForAllIfNone)
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
			if ((promptForAllIfNone && map.size() == 1) || (selectedKeys
				.size() == 1 && selectedKeys.get(0) == CheckBoxList.ALL_ENTRY))
				return allKeys;
			if (promptForAllIfNone && selectedKeys.isEmpty()) {
				checkRetrieveAllOptions(mapDescriptor);
				if (prefs.retrieveAllIfNoneSelected) return allKeys;
				guiUtils.error("There are no selected " + mapDescriptor + ".");
				return null;
			}
			allKeys.retainAll(selectedKeys);
			return allKeys;
		}

		private void checkRetrieveAllOptions(final String mapDescriptor) {
			if (!prefs.nagUserOnRetrieveAll) return;
			final boolean[] options = guiUtils.getPersistentConfirmation(
				"There are no items selected. "//
					+ "Apply changes to all " + mapDescriptor + "?", "Apply to All?");
			prefs.retrieveAllIfNoneSelected = options[0];
			prefs.nagUserOnRetrieveAll = !options[1];
		}

		private JPopupMenu measureMenu() {
			final JPopupMenu measureMenu = new JPopupMenu();
			addSeparator(measureMenu, "Tabular Results:");
			JMenuItem mi = new JMenuItem("Measure...", IconFactory.getMenuIcon(GLYPH.TABLE));
			mi.setToolTipText("Computes detailed metrics from single cells");
			mi.addActionListener(e -> {
				final List<Tree> trees = getSelectedTrees();
				if (trees == null || trees.isEmpty()) return;
				final Map<String, Object> inputs = new HashMap<>();
				inputs.put("trees", trees);
				initTable();
				inputs.put("table", table);
				inputs.put("calledFromPathManagerUI", false);
				runCmd(AnalyzerCmd.class, inputs, CmdWorker.DO_NOTHING, false);
			});
			measureMenu.add(mi);
			mi = new JMenuItem("Quick Measurements", IconFactory.getMenuIcon(GLYPH.ROCKET));
			mi.setToolTipText("Runs \"Measure...\" on a pre-set list of metrics");
			mi.addActionListener(e -> {
				final List<Tree> trees = getSelectedTrees();
				if (trees == null || trees.isEmpty()) return;
				initTable();
				trees.forEach(tree -> {
					final TreeStatistics tStats = new TreeStatistics(tree);
					tStats.setContext(context);
					tStats.setTable(table);
					tStats.summarize(tree.getLabel(), true); // will display table
				});
			});
			measureMenu.add(mi);
			mi = new JMenuItem("Save Table...", IconFactory.getMenuIcon(GLYPH.SAVE));
			mi.addActionListener(e -> {
				if (table == null || table.isEmpty() || table.getRowCount() == 0) {
					guiUtils.error("No data in measurements table.");
				} else {
					final File file = context.getService(UIService.class)
							.chooseFile(new File(prefs.lastDir, "SNT-Measurements.csv"), FileWidget.SAVE_STYLE);
					if (file != null)
						try {
							table.save(file);
							prefs.lastDir = file.getParentFile();
						} catch (final IOException e1) {
							guiUtils.error("An error occured while saving table. See Console for details and data.");
							e1.printStackTrace();
							System.out.println("###   ###   ###   Tabular Data:    ###   ###   ###");
							System.out.println(table);
						}
				}
			});
			measureMenu.add(mi);
			addSeparator(measureMenu, "Distribution Analysis:");
			mi = new JMenuItem("Branch Properties...", IconFactory.getMenuIcon(GLYPH.CHART));
			mi.setToolTipText("Computes metrics from all the branches of selected trees");
			mi.addActionListener(e -> {
				final List<Tree> trees = getSelectedTrees();
				if (trees == null || trees.isEmpty()) return;
				final Map<String, Object> inputs = new HashMap<>();
				inputs.put("trees", trees);
				inputs.put("calledFromPathManagerUI", false);
				runCmd(DistributionBPCmd.class, inputs, CmdWorker.DO_NOTHING, false);
			});
			measureMenu.add(mi);
			mi = new JMenuItem("Cell Properties...", IconFactory.getMenuIcon(GLYPH.CHART));
			mi.setToolTipText("Computes metrics from individual cells");
			mi.addActionListener(e -> {
				final List<Tree> trees = getSelectedTrees();
				if (trees == null || trees.isEmpty()) return;
				final Map<String, Object> inputs = new HashMap<>();
				inputs.put("trees", trees);
				inputs.put("calledFromPathManagerUI", false);
				runCmd(DistributionCPCmd.class, inputs, CmdWorker.DO_NOTHING, false);
			});
			measureMenu.add(mi);
			addSeparator(measureMenu, "Single-Cell Analysis:");
			mi = new JMenuItem("Brain Area Analysis...", IconFactory.getMenuIcon(GLYPH.BRAIN));
			mi.addActionListener(e -> {
				final Tree tree = getSingleSelectionTree();
				if (tree == null) return;
				final Map<String, Object> inputs = new HashMap<>();
				inputs.put("tree", tree);
				runCmd(BrainAnnotationCmd.class, inputs, CmdWorker.DO_NOTHING, false);
			});
			measureMenu.add(mi);
			mi = new JMenuItem("Create Dendrogram...", IconFactory.getMenuIcon(GLYPH.DIAGRAM));
			mi.addActionListener(e -> {
				final Tree tree = getSingleSelectionTreeWithPromptForType();
				if (tree == null) return;
				final Map<String, Object> inputs = new HashMap<>();
				inputs.put("tree", tree);
				runCmd(GraphGeneratorCmd.class, inputs, CmdWorker.DO_NOTHING, false);
			});
			measureMenu.add(mi);
			mi = new JMenuItem("Sholl Analysis...", IconFactory.getMenuIcon(GLYPH.BULLSEYE));
			mi.addActionListener(e -> {
				final Tree tree = getSingleSelectionTree();
				if (tree == null) return;
				final Map<String, Object> input = new HashMap<>();
				input.put("snt", null);
				input.put("tree", tree);
				runCmd(ShollAnalysisTreeCmd.class, input, CmdWorker.DO_NOTHING, false);
			});
			measureMenu.add(mi);
			mi = new JMenuItem("Strahler Analysis...", IconFactory.getMenuIcon(GLYPH.BRANCH_CODE));
			mi.addActionListener(e -> {
				final Tree tree = getSingleSelectionTreeWithPromptForType();
				if (tree == null) return;
				final StrahlerCmd sa = new StrahlerCmd(tree);
				sa.setContext(context);
				SwingUtilities.invokeLater(() -> sa.run());
			});
			measureMenu.add(mi);
			addSeparator(measureMenu, "Annotation Graphs:");
			mi = new JMenuItem("Create Annotation Graph...", IconFactory.getMenuIcon(GLYPH.BRAIN));
			mi.addActionListener(e -> {
				final List<Tree> trees = getSelectedTrees();
				if (trees == null || trees.isEmpty()) return;
				final Map<String, Object> inputs = new HashMap<>();
				inputs.put("trees", trees);
				runCmd(AnnotationGraphGeneratorCmd.class, inputs, CmdWorker.DO_NOTHING, false);
			});
			measureMenu.add(mi);
			return measureMenu;
		}

		private void initTable() {
			if (table == null) table =  new SNTTable();
		}

		private void addCustomizeMeshCommands(final JPopupMenu menu) {
			addSeparator(menu, "Customize:");
			JMenuItem mi = new JMenuItem("All Parameters...", IconFactory.getMenuIcon(GLYPH.SLIDERS));
			mi.addActionListener(e -> {
				final List<String> keys = getSelectedMeshes(true);
				if (keys == null) return;
				if (cmdService == null) {
					guiUtils.error(
						"This command requires Reconstruction Viewer to be aware of a Scijava Context");
					return;
				}
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
						if (cmdModule != null && cmdModule.isCanceled()) {
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
			menu.add(mi);;

			// Mesh customizations
			mi = new JMenuItem("Color...", IconFactory.getMenuIcon(
				GLYPH.COLOR));
			mi.addActionListener(e -> {
				final List<String> keys = getSelectedMeshes(true);
				if (keys == null) return;
				final java.awt.Color c = guiUtils.getColor("Mesh(es) Color",
					java.awt.Color.WHITE, "HSB");
				if (c == null) {
					return; // user pressed cancel
				}
				final Color color = fromAWTColor(c);
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
				final List<String> keys = getSelectedMeshes(true);
				if (keys == null) return;
				final Double t = guiUtils.getDouble("Mesh Transparency (%)",
					"Transparency (%)", 95);
				if (t == null) {
					return; // user pressed cancel
				}
				final float fValue = 1 - (t.floatValue() / 100);
				if (Float.isNaN(fValue) || fValue <= 0 || fValue >= 1) {
					guiUtils.error("Invalid transparency value: Only ]0, 100[ accepted.");
					return;
				}
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
				if (cmdService == null) {
					guiUtils.error(
						"This command requires Reconstruction Viewer to be aware of a Scijava Context");
					return;
				}
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
				final ColorRGB c = guiUtils.getColorRGB("Reconstruction(s) Color",
					java.awt.Color.RED, "HSB");
				if (c == null) {
					return; // user pressed cancel
				}
				applyColorToPlottedTrees(keys, c);
			});
			menu.add(mi);
			final JMenu ccMenu = new JMenu("Color Coding");
			ccMenu.setIcon(IconFactory.getMenuIcon(GLYPH.SIGNS));
			menu.add(ccMenu);
			mi = new JMenuItem("Individual Cells...");
			mi.addActionListener(e -> {
				final List<String> keys = getSelectedTreeLabels();
				if (keys == null) return;
				final Map<String, Object> inputs = new HashMap<>();
				inputs.put("treeMappingLabels", keys);
				runCmd(ColorMapReconstructionCmd.class, inputs, CmdWorker.DO_NOTHING);
			});
			ccMenu.add(mi);
			mi = new JMenuItem("Group of Cells...");
			mi.addActionListener(e -> {
				final List<String> keys = getSelectedTreeLabels();
				if (keys == null) return;
				final Map<String, Object> inputs = new HashMap<>();
				inputs.put("multiTreeMappingLabels", keys);
				runCmd(ColorMapReconstructionCmd.class, inputs, CmdWorker.DO_NOTHING);
			});
			ccMenu.add(mi);
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

			mi = new JMenuItem("Translate...", IconFactory.getMenuIcon(GLYPH.MOVE));
			mi.addActionListener(e -> {
				final List<String> keys = getSelectedTreeLabels();
				if (keys == null) return;
				final Map<String, Object> inputs = new HashMap<>();
				inputs.put("treeLabels", keys);
				runCmd(TranslateReconstructionsCmd.class, inputs, CmdWorker.DO_NOTHING);
			});
			menu.add(mi);
		}

		private JPopupMenu utilsMenu() {
			final JPopupMenu utilsMenu = new JPopupMenu();
			addSeparator(utilsMenu, "Actions & Utilities:");
			final JMenuItem snapshot = new JMenuItem(new Action(Action.SNAPSHOT, KeyEvent.VK_S, false, false));
			snapshot.setIcon(IconFactory.getMenuIcon(GLYPH.CAMERA));
			utilsMenu.add(snapshot);
			JMenuItem mi = new JMenuItem("Record Rotation", IconFactory
				.getMenuIcon(GLYPH.VIDEO));
			mi.addActionListener(e -> {
				SwingUtilities.invokeLater(() -> {
					displayMsg("Recording rotation...", 0);
					new RecordWorker().execute();
				});
			});
			utilsMenu.add(mi);
			utilsMenu.add(legendMenu());
			mi = new JMenuItem("Annotation Label...", IconFactory.getMenuIcon(GLYPH.PEN));
			mi.addActionListener(e -> {
				runCmd(AddTextAnnotationCmd.class, null, CmdWorker.DO_NOTHING);
			});
			utilsMenu.add(mi);
			final JMenuItem light = new JMenuItem("Light Controls...", IconFactory.getMenuIcon(GLYPH.BULB));
			light.addActionListener(e -> {
//				guiUtils.centeredMsg(
//						"Adjustments of light and shadows remain experimental features, some of which undoable.",
//						"Reminder", "OK. I'll be careful");
				frame.displayLightController();
			});
			utilsMenu.add(light);
			addSeparator(utilsMenu, "Scripting:");
			final JMenuItem script = new JMenuItem(new Action(Action.SCRIPT, KeyEvent.VK_OPEN_BRACKET, false, false));
			script.setIcon(IconFactory.getMenuIcon(GLYPH.CODE));
			utilsMenu.add(script);
			mi = new JMenuItem("Script This Viewer In...");
			mi.addActionListener(e -> runScriptEditor(null));
			utilsMenu.add(mi);
			final JMenuItem log = new JMenuItem(new Action(Action.LOG, KeyEvent.VK_L, false, false));
			log.setIcon(IconFactory.getMenuIcon(GLYPH.STREAM));
			utilsMenu.add(log);
			final JMenuItem jcbmi = new JCheckBoxMenuItem("Debug Mode", SNTUtils.isDebugMode());
			jcbmi.setEnabled(!isSNTInstance());
			jcbmi.setIcon(IconFactory.getMenuIcon(GLYPH.STETHOSCOPE));
			jcbmi.setMnemonic('d');
			jcbmi.addItemListener(e -> {
				final boolean debug = jcbmi.isSelected();
				if (isSNTInstance()) {
					sntService.getPlugin().getUI().setEnableDebugMode(debug);
				} else {
					SNTUtils.setDebugMode(debug);
				}
				if (debug) logGLDetails();
			});
			utilsMenu.add(jcbmi);
	
			addSeparator(utilsMenu, "Online Resources:");
			final JMenu helpMenu = GuiUtils.helpMenu();
			helpMenu.setIcon( IconFactory.getMenuIcon(GLYPH.QUESTION));
			//utilsMenu.add(helpMenu.getItem(0));
			utilsMenu.add(helpMenu.getItem(helpMenu.getItemCount()-1));
			utilsMenu.add(helpMenu);
			return utilsMenu;
		}

		private JPopupMenu prefsMenu() {
			final JPopupMenu prefsMenu = new JPopupMenu();
			addSeparator(prefsMenu, "Keyboard & Mouse Sensitivity:");
			prefsMenu.add(panMenu());
			prefsMenu.add(zoomMenu());
			prefsMenu.add(rotationMenu());
			prefsMenu.addSeparator();
			final JMenuItem  jcbmi2= new JCheckBoxMenuItem("Enable Hardware Acceleration", Settings.getInstance().isHardwareAccelerated());
			//jcbmi2.setEnabled(!isSNTInstance());
			jcbmi2.setIcon(IconFactory.getMenuIcon(GLYPH.MICROCHIP));
			jcbmi2.setMnemonic('h');
			jcbmi2.addItemListener(e -> {
				Settings.getInstance().setHardwareAccelerated(jcbmi2.isSelected());
				logGLDetails();
			});
			prefsMenu.add(jcbmi2);
			prefsMenu.addSeparator();
			final JMenuItem mi = new JMenuItem("Global Preferences...", IconFactory.getMenuIcon(GLYPH.COGS));
			mi.addActionListener(e -> {
				runCmd(RecViewerPrefsCmd.class, null, CmdWorker.RELOAD_PREFS, false);
			});
			prefsMenu.add(mi);
			return prefsMenu;
		}

		private void runScriptEditor(String extension) {
			if (extension == null) {
				extension = guiUtils.getChoice("Which scripting language?", "Language?",
						new String[] { ".bsh", ".groovy", ".py" }, prefs.getScriptExtension());
				if (extension == null) return;
			}
			final TextEditor editor = new TextEditor(context);
			final boolean needsSemiColon = extension.endsWith("bsh");
			final String commentPrefix = (extension.endsWith("py")) ? "# " : "// ";
			final StringBuilder sb = new StringBuilder(prefs.getBoilerplateScript(extension));
			sb.append("\n").append(commentPrefix);
			sb.append("Rec. Viewer's API: https://morphonets.github.io/SNT/index.html?sc/fiji/snt/viewer/Viewer3D.html");
			sb.append("\n").append(commentPrefix);
			sb.append("Tip: Programmatic control of the Viewer's scene can be set using the Console info");
			sb.append("\n").append(commentPrefix);
			sb.append("produced when calling viewer.logSceneControls() or pressing 'L' when viewer is frontmost");
			sb.append("\n");
			sb.append("\n").append("viewer = snt.getRecViewer(");
			if (!isSNTInstance()) sb.append(getID());
			sb.append(")");
			if (needsSemiColon) sb.append(";");
			sb.append("\n");
			editor.createNewDocument("RecViewerScript" + extension, sb.toString());
			//HACK: Reset the filename because createNewDocument() currently appends multiple extensions
			editor.setEditorPaneFileName("RecViewerScript" + extension);
			//editor.newTab(sb.toString(), extension);
			editor.setVisible(true);
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
				final File rootDir = new File(prefs.snapshotDir +
					File.separator + "SNTrecordings");
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
			addSeparator(tracesMenu, "Add:");
			JMenuItem mi = new JMenuItem("Import File...", IconFactory.getMenuIcon(
				GLYPH.IMPORT));
			mi.setMnemonic('f');
			mi.addActionListener(e -> {
				final Map<String, Object> inputs = new HashMap<>();
				inputs.put("importDir", false);
				runCmd(LoadReconstructionCmd.class, inputs, CmdWorker.DO_NOTHING);
			});
			tracesMenu.add(mi);
			mi = new JMenuItem("Import Directory...", IconFactory.getMenuIcon(
				GLYPH.FOLDER));
			mi.addActionListener(e -> {
				final Map<String, Object> inputs = new HashMap<>();
				inputs.put("importDir", true);
				runCmd(LoadReconstructionCmd.class, inputs, CmdWorker.DO_NOTHING);
			});
			tracesMenu.add(mi);

			mi = new JMenuItem("Import & Compare Groups...", IconFactory.getMenuIcon(GLYPH.MAGIC));
			mi.addActionListener(e -> {
				runCmd(GroupAnalyzerCmd.class, null, CmdWorker.DO_NOTHING);
			});
			tracesMenu.add(mi);

			final JMenu remoteMenu = new JMenu("Load from Database");
			remoteMenu.setMnemonic('d');
			remoteMenu.setDisplayedMnemonicIndex(10);
			remoteMenu.setIcon(IconFactory.getMenuIcon(GLYPH.DATABASE));
			tracesMenu.add(remoteMenu);
			mi = new JMenuItem("FlyCircuit...", 'f');
			mi.addActionListener(e -> {
				final Map<String, Object> inputs = new HashMap<>();
				inputs.put("loader", new FlyCircuitLoader());
				runCmd(RemoteSWCImporterCmd.class, inputs, CmdWorker.DO_NOTHING);
			});
			remoteMenu.add(mi);
			mi = new JMenuItem("InsectBrain...", 'I');
			mi.addActionListener(e -> {
				runCmd(InsectBrainImporterCmd.class, null, CmdWorker.DO_NOTHING); // will assign "recViewer" input
			});
			remoteMenu.add(mi);
			mi = new JMenuItem("MouseLight...", 'm');
			mi.addActionListener(e -> {
				runCmd(MLImporterCmd.class, null, CmdWorker.DO_NOTHING);
			});
			remoteMenu.add(mi);
			mi = new JMenuItem("NeuroMorpho...", 'n');
			mi.addActionListener(e -> {
				final Map<String, Object> inputs = new HashMap<>();
				inputs.put("loader", new NeuroMorphoLoader());
				runCmd(RemoteSWCImporterCmd.class, inputs, CmdWorker.DO_NOTHING);
			});
			remoteMenu.add(mi);

			addSeparator(tracesMenu, "Customize & Adjust:");
			addCustomizeTreeCommands(tracesMenu);

			addSeparator(tracesMenu, "Remove:");
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
				Viewer3D.this.setSceneUpdatesEnabled(false);
				keys.forEach(k -> Viewer3D.this.removeTree(k));
				Viewer3D.this.setSceneUpdatesEnabled(true);
				Viewer3D.this.updateView();
			});
			tracesMenu.add(mi);
			mi = new JMenuItem("Remove All...", IconFactory.getMenuIcon(GLYPH.TRASH));
			mi.addActionListener(e -> {
				if (!guiUtils.getConfirmation("Remove all reconstructions from scene?",
					"Remove All Reconstructions?"))
				{
					return;
				}
				Viewer3D.this.removeAllTrees();
			});
			tracesMenu.add(mi);
			return tracesMenu;
		}

		private JMenu legendMenu() {
			// Legend Menu
			final JMenu legendMenu = new JMenu("Color Legends");
			legendMenu.setIcon(IconFactory.getMenuIcon(GLYPH.COLOR2));
			JMenuItem mi = new JMenuItem("Add...", IconFactory.getMenuIcon(GLYPH.PLUS));
			mi.addActionListener(e -> {
				final Map<String, Object> inputs = new HashMap<>();
				inputs.put("treeMappingLabels", null);
				inputs.put("multiTreeMappingLabels", null);
				runCmd(ColorMapReconstructionCmd.class, inputs, CmdWorker.DO_NOTHING);
			});
			legendMenu.add(mi);
			mi = new JMenuItem("Edit Last...", IconFactory.getMenuIcon(GLYPH.SLIDERS));
			mi.addActionListener(e -> {
				if (cmdService == null) {
					guiUtils.error(
						"This command requires Reconstruction Viewer to be aware of a Scijava Context.");
					return;
				}
				if (cBar == null) {
					guiUtils.error("No Legend currently exists.");
					return;
				}
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
						if (cmdModule != null && cmdModule.isCanceled()) {
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
				if (!guiUtils.getConfirmation("Remove all color legends from scene?",
					"Remove All Legends?"))
				{
					return;
				}
				removeColorLegends(false);
			});
			legendMenu.add(mi);
			return legendMenu;
		}

		private void addSeparator(final JPopupMenu menu, final String header) {
			final JLabel label = GuiUtils.leftAlignedLabel(header, false);
			if (menu.getComponentCount() > 1) menu.addSeparator();
			menu.add(label);
		}

		private JPopupMenu meshMenu() {
			final JPopupMenu meshMenu = new JPopupMenu();
			addSeparator(meshMenu, "Add:");
			JMenuItem mi = new JMenuItem("Import OBJ File(s)...", IconFactory
				.getMenuIcon(GLYPH.IMPORT));
			mi.addActionListener(e -> runCmd(LoadObjCmd.class, null,
				CmdWorker.DO_NOTHING)); // LoadObjCmd will call validate()
			meshMenu.add(mi);
			addCustomizeMeshCommands(meshMenu);

			addSeparator(meshMenu, "Remove:");
			mi = new JMenuItem("Remove Selected...", IconFactory.getMenuIcon(
				GLYPH.DELETE));
			mi.addActionListener(e -> {
				final List<String> keys = getSelectedMeshes(false);
				if (keys == null || keys.isEmpty()) {
					guiUtils.error("There are no selected meshes.");
					return;
				}
				if (!guiUtils.getConfirmation("Delete " + keys.size() + " mesh(es)?",
					"Confirm Deletion"))
				{
					return;
				}
				Viewer3D.this.setSceneUpdatesEnabled(false);
				keys.forEach(k -> Viewer3D.this.removeMesh(k));
				Viewer3D.this.setSceneUpdatesEnabled(true);
				Viewer3D.this.updateView();
			});
			meshMenu.add(mi);
			mi = new JMenuItem("Remove All...");
			mi.setIcon(IconFactory.getMenuIcon(GLYPH.TRASH));
			mi.addActionListener(e -> {
				if (!guiUtils.getConfirmation("Remove all meshes from scene?",
					"Remove All Meshes?"))
				{
					return;
				}
				removeAllMeshes();
			});
			meshMenu.add(mi);
			return meshMenu;
		}

		private JPopupMenu refBrainsMenu() {
			final JPopupMenu refMenu = new JPopupMenu("Reference Brains");
			addSeparator(refMenu, "Mouse:");
			JMenuItem mi = new JMenuItem("Allen CCF Navigator", IconFactory
					.getMenuIcon(GLYPH.NAVIGATE));
			mi.addActionListener(e -> {
				assert SwingUtilities.isEventDispatchThread();
				if (frame.allenNavigator != null) {
					frame.allenNavigator.dialog.toFront();
					return;
				}
				//final JDialog tempSplash = frame.managerPanel.guiUtils.floatingMsg("Loading ontologies...", false);
				getManagerPanel().setProgress(-1);
				final SwingWorker<AllenCCFNavigator, ?> worker = new SwingWorker<AllenCCFNavigator, Object>() {

					@Override
					protected AllenCCFNavigator doInBackground() {
						loadRefBrainAction(false, MESH_LABEL_ALLEN);
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
							frame.managerPanel.resetProgressBar();
						}
					}
				};
				worker.execute();
			});
			refMenu.add(mi);
			
			addSeparator(refMenu, "Zebrafish:");
			mi = new JMenuItem("Max Planck ZBA", IconFactory.getMenuIcon(GLYPH.ARCHIVE));
			mi.addActionListener(e -> loadRefBrainAction(true, MESH_LABEL_ZEBRAFISH));
			refMenu.add(mi);

			addSeparator(refMenu, "Drosophila:");
			mi = new JMenuItem("Adult Brain: FlyCircuit", IconFactory.getMenuIcon(GLYPH.ARCHIVE));
			mi.addActionListener(e -> loadRefBrainAction(true, MESH_LABEL_FCWB));
			refMenu.add(mi);
			mi = new JMenuItem("Adult Brain: JFRC 2018", IconFactory.getMenuIcon(GLYPH.ARCHIVE));
			mi.setToolTipText("<HTML>AKA <i>The Bogovic brain</i>");
			mi.addActionListener(e -> loadRefBrainAction(true, MESH_LABEL_JFRC2018));
			refMenu.add(mi);
			mi = new JMenuItem("Adult Brain: JFRC2 (VFB)", IconFactory.getMenuIcon(GLYPH.ARCHIVE));
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

			return refMenu;
		}

		private void loadRefBrainAction(final boolean warnIfLoaded, final String label) {
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
			if (warnIfLoaded && getOBJs().keySet().contains(label)) {
				guiUtils.error(label + " is already loaded.");
				return;
			}
			getManagerPanel().setProgress(-1);
			final SwingWorker<?, ?> worker = new SwingWorker<Boolean, Object>() {

				@Override
				protected Boolean doInBackground() {
					try {
						return loadRefBrainInternal(label) != null;
					} catch (final NullPointerException | IllegalArgumentException ex) {
						guiUtils.error("An error occured and mesh could not be retrieved. See Console for details.");
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
						if (getManagerPanel() != null)
							getManagerPanel().resetProgressBar();
					}
				}
			};
			worker.execute();
		}

		private void removeColorLegends(final boolean justLastOne) {
			final List<AbstractDrawable> allDrawables = chart.getScene().getGraph()
				.getAll();
			final Iterator<AbstractDrawable> iterator = allDrawables.iterator();
			while (iterator.hasNext()) {
				final AbstractDrawable drawable = iterator.next();
				if (drawable != null && drawable.hasLegend() && drawable
					.isLegendDisplayed())
				{
					iterator.remove();
					if (justLastOne) break;
				}
			}
			cBar = null;
		}

		private void runCmd(final Class<? extends Command> cmdClass,
			final Map<String, Object> inputs, final int cmdType)
		{
			runCmd(cmdClass, inputs, cmdType, true);
		}

		private void runCmd(final Class<? extends Command> cmdClass,
			final Map<String, Object> inputs, final int cmdType,
			final boolean setRecViewerParamater)
		{
			if (cmdService == null) {
				guiUtils.error(
					"This command requires Reconstruction Viewer to be aware of a Scijava Context");
				return;
			}
			SwingUtilities.invokeLater(() -> {
				(new CmdWorker(cmdClass, inputs, cmdType, setRecViewerParamater))
					.execute();
			});
		}
	}

	private class FileDropWorker {

		private boolean escapePressed;

		FileDropWorker(final Component component, final GuiUtils guiUtils) {
			addEscListener(component);
			new FileDrop(component, files -> {
				processFiles(files, true, null, guiUtils);
			});
		}
	
		void importTreesWithoutDrop(final File[] files, final ColorRGB baseColor, final GuiUtils guiUtils) {
			processFiles(files, false, baseColor, guiUtils);
		}

		void processFiles(final File[] files, final boolean promptForConfirmation, final ColorRGB baseColor, final GuiUtils guiUtils) {
			final ArrayList<File> collection = new ArrayList<>();
			assembleFlatFileCollection(collection, files);
			if (promptForConfirmation && collection.size() > 10) {
				final boolean[] confirmSplit = guiUtils.getConfirmationAndOption(
							"Are you sure you would like to import " + collection.size() + " files?<br>"
							+ "You can press 'Esc' at any time to interrupt import.",
							"Proceed with Batch Import?", "Import axons and dendrites separately",
							isSplitDendritesFromAxons());
				if (!confirmSplit[0]) return;
				setSplitDendritesFromAxons(confirmSplit[1]);
			}
			final SwingWorker<?, ?> worker = new SwingWorker<Object, Object>() {
				int[] failuresAndSuccesses = new int[2];

				@Override
				protected Object doInBackground() {
					setSceneUpdatesEnabled(false);
					failuresAndSuccesses = loadGuessingType(collection, baseColor);
					return null;
				}

				@Override
				protected void done() {
					setSceneUpdatesEnabled(true);
					validate();
					if (failuresAndSuccesses[0] > 0)
						guiUtils.error("" + failuresAndSuccesses[0] + " of "
								+ (failuresAndSuccesses[0] + failuresAndSuccesses[1])
								+ " dropped file(s) could not be imported (Console log will"
								+ " have more details if you have enabled \"Debug mode\").");
					resetEscape();
				}
			};
			worker.execute();
		}
		private void addEscListener(final Component c) {
			final KeyAdapter listener = new KeyAdapter() {
				@Override
				public void keyPressed(final KeyEvent e) {
					if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
						escapePressed = true;
					}
				}
			};
			c.addKeyListener(listener);
			if (c instanceof Container) {
				final Container container = (Container) c;
				final Component[] children = container.getComponents();
				for (final Component child : children)
					child.addKeyListener(listener);
			}
		}

		private Collection<File> assembleFlatFileCollection(final Collection<File> collection, final File[] files) {
			for (final File file : files) {
				if (file.isDirectory())
					assembleFlatFileCollection(collection, file.listFiles());
				else
					collection.add(file);
			}
			return collection;
		}

		/**
		 * Returns {n. of failed imports, n. successful imports}. Assumes no directories
		 * in collection
		 */
		private int[] loadGuessingType(final Collection<File> files, final ColorRGB baseColor) {
			final int totalFiles = files.size();
			final ColorRGB[] colors = getmportColors(baseColor, totalFiles);
			int failures = 0;
			int idx = 0;
			if (frame.managerPanel != null) {
				frame.managerPanel.setProgress(-1);
			}
			for (final File file : files) {
				if (escapePressed()) {
					SNTUtils.log("Aborting...");
					break;
				}
				if (!file.exists() || file.isDirectory())
					continue;
				SNTUtils.log(String.format("Loading %d/%d: %s", (idx+1), totalFiles, file.getAbsolutePath()));
				final String fName = file.getName().toLowerCase();
				try {
					final ColorRGB color = colors[idx];
					if (fName.endsWith("swc") || fName.endsWith(".traces") || fName.endsWith(".json")) { // reconstruction:
						try {
							final Collection<Tree> treesInFile = Tree.listFromFile(file.getAbsolutePath());
							if (treesInFile.size() > 1) {
								if (frame.managerPanel != null)
									frame.managerPanel.setProgressLimit(0, totalFiles + treesInFile.size());
								Tree.assignUniqueColors(treesInFile);
								int internalIdx = 0;
								for (final Tree tree : treesInFile) {
									addTree(tree);
									if (frame.managerPanel != null)
										frame.managerPanel.setProgress(idx + internalIdx++);
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
					if (frame.managerPanel != null)
						frame.managerPanel.setProgress(idx);
				} catch (final IllegalArgumentException ex) {
					SNTUtils.log("... failed " + ex.getMessage());
					failures++;
				}
				idx++;
			}
			return new int[] { failures, idx-1 };
		}

		private boolean escapePressed() {
			return escapePressed;
		}

		private void resetEscape() {
			escapePressed = false;
			if (frame != null && frame.managerPanel != null)
				frame.managerPanel.resetProgressBar();
		}

		private ColorRGB[] getmportColors(ColorRGB baseColor, final int n) {
			if (baseColor == null) {
				return SNTColor.getDistinctColors(n);
			} else {
				// this cold be more sophisticated: Hue shading, LUT, gradient, etc.
				final ColorRGB[] colors = new ColorRGB[n];
				Arrays.fill(colors, baseColor);
				return colors;
			}
		}
	}

	
	private class AllenCCFNavigator {

		private final SNTSearchableBar searchableBar;
		private final DefaultTreeModel treeModel;
		private final NavigatorTree tree;
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
			@SuppressWarnings("unchecked")
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
					viewUpdatesEnabled = compartments.size() == 1;
					for (final AllenCompartment compartment : compartments) {
						if (getOBJs().keySet().contains(compartment.name())) {
							managerList.addCheckBoxListSelectedValue(compartment.name(), true);
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
						if (failedCompartments.size() > 0) {
							final StringBuilder sb = new StringBuilder(String.valueOf(loadedCompartments)).append("/")
									.append(loadedCompartments + failedCompartments.size())
									.append(" meshes retrieved. The following compartments failed to load:")
									.append("<br>&nbsp;<br>").append(String.join("; ", failedCompartments))
									.append("<br>&nbsp;<br>")
									.append("Either such meshes are not available or file(s) could not be reached. Check Console logs for details.");
							guiUtils.centeredMsg(sb.toString(), "Exceptions Occured");
						}
					} catch (final InterruptedException | ExecutionException e) {
						SNTUtils.error(e.getMessage(), e);
					} finally {
						if (getManagerPanel() != null)
							getManagerPanel().resetProgressBar();
						viewUpdatesEnabled = true;
					}
				}
			};
			getManagerPanel().setProgress(-1);
			worker.execute();
		}

		private void showSelectionInfo() {
			final List<AllenCompartment> cs = getCheckedSelection();
			Collections.sort(cs, (c1, c2) -> {
				return c1.name().compareToIgnoreCase(c2.name());
			});
			if (cs == null) return;
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
			dialog.setLocationRelativeTo(frame);
			dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
			dialog.addWindowListener(new WindowAdapter() {

				@Override
				public void windowClosing(final WindowEvent e) {
					frame.allenNavigator = null;
					dialog.dispose();
				}
			});
			dialog.setContentPane(getContentPane());
			dialog.pack();
			dialog.setSize(new Dimension(searchableBar.getWidth(), dialog.getHeight()));
			dialog.setVisible(true);
			return dialog;
		}

		private JPanel getContentPane() {
			final JPanel barPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
			frame.managerPanel.setFixedHeightToPanel(barPanel);
			barPanel.add(searchableBar);
			final JScrollPane scrollPane = new JScrollPane(tree);
			tree.setComponentPopupMenu(popupMenu());
			scrollPane.setWheelScrollingEnabled(true);
			final JPanel contentPane = new JPanel();
			contentPane.setLayout(new BoxLayout(contentPane, BoxLayout.Y_AXIS));
			contentPane.add(barPanel);
			contentPane.add(scrollPane);
			contentPane.add(buttonPanel());
			return contentPane;
		}

		private JPanel buttonPanel() {
			final JPanel buttonPanel = new JPanel(new GridLayout(1,2));
			buttonPanel.setBorder(null);
			frame.managerPanel.setFixedHeightToPanel(buttonPanel);
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
			jmi.addActionListener(e -> {
				tree.clearSelection();
			});
			pMenu.add(jmi);
			jmi = new JMenuItem("Collapse All");
			jmi.addActionListener(e -> {
				GuiUtils.collapseAllTreeNodes(tree);
			});
			pMenu.add(jmi);
			jmi = new JMenuItem("Expand All");
			jmi.addActionListener(e -> GuiUtils.expandAllTreeNodes(tree));
			pMenu.add(jmi);
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
		private final DefaultUpdatableListModel<Object> model;

		@SuppressWarnings("unchecked")
		public CheckboxListEditable(final DefaultUpdatableListModel<Object> model) {
			super(model);
			this.model = model;
			this.model.addElement(CheckBoxList.ALL_ENTRY);
			renderer = new CustomListRenderer((DefaultListCellRenderer) getActualCellRenderer());
			setCellRenderer(renderer);
			addMouseListener(new java.awt.event.MouseAdapter() {
				public void mouseClicked(final MouseEvent e) {
					if (e.getClickCount() == 2 && !((HandlerPlus) _handler).clicksInCheckBox(e)) {

						if (editPopup == null)
							createEditPopup();

						// Prepare the text field for editing
						final String selectedLabel = getSelectedValue().toString();
						if (ALL_ENTRY.toString().equals(selectedLabel)) {
							if (Viewer3D.this.frame.managerPanel.noLoadedItemsGuiError())
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
			editTextField = new GuiUtils(this).textField("Tags:");
			final Border border = javax.swing.UIManager.getBorder("List.focusCellHighlightBorder");
			editTextField.setBorder(border);
			// Add an Action to the text field to save the new value to the model
			editTextField.addActionListener(e -> {
				final String tag = editTextField.getText();
				final int row = getSelectedIndex();
				final String existingEntry = ((DefaultListModel) getModel()).get(row).toString();
				if (ALL_ENTRY.toString().equals(existingEntry)) {
					// textfield was empty
					applyTagToSelectedItems(tag);
				} else {
					if (tag.trim().isEmpty()) {
						removeTagsFromSelectedItems();
					} else {
						// textfield contained all tags
						final String existingEntryWithoutTags = TagUtils.getUntaggedStringFromTags(existingEntry);
						final String newEntry = TagUtils.applyTag(existingEntryWithoutTags, tag);
						((DefaultListModel<String>) getModel()).set(row, newEntry);
					}
				}
				editPopup.setVisible(false);
			});
			// Add the editor to the popup
			editPopup = new JPopupMenu();
			editPopup.setBorder(new javax.swing.border.EmptyBorder(0, 0, 0, 0));
			editPopup.add(editTextField);
			editPopup.setPreferredSize(getPreferredSize());
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
		void applyTagToSelectedItems(final String tag) {
			final String cleansedTag = TagUtils.getCleansedTag(tag);
			if (cleansedTag.trim().isEmpty()) return;
			for (final int i : getSelectedIndices()) {
				final String entry = (String) ((DefaultListModel<?>) getModel()).get(i);
				((DefaultListModel<String>) getModel()).set(i, TagUtils.applyTag(entry, tag));
			}
		}

		@SuppressWarnings("unchecked")
		void removeTagsFromSelectedItems() {
			for (final int i : getSelectedIndices()) {
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
			}

			void setIconsVisible(final boolean iconVisible) {
				this.iconVisible = iconVisible;
			}

			@Override
			public Component getListCellRendererComponent(final JList<?> list, final Object value, final int index,
					final boolean isSelected, final boolean cellHasFocus) {
				final JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected,
						cellHasFocus);
				final String labelText = label.getText();
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
				final String[] tags = TagUtils.getTagsFromEntry(labelText);
				if (tags.length > 0) {
					for (final String tag : tags) {
						final ColorRGB c = ColorRGB.fromHTMLColor(tag);
						if (c != null) {
							label.setForeground(new java.awt.Color(c.getRed(), c.getGreen(), c.getBlue()));
							break;
						}
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

		static String applyTag(final String entry, final String tag) {
			final String cleansedTag = getCleansedTag(tag);
			if (cleansedTag.trim().isEmpty()) return entry;
			if (entry.indexOf("}") == -1) {
				final StringBuilder sb = new StringBuilder(entry);
				sb.append("{").append(cleansedTag).append("}");
				return sb.toString();
			} else {
				return entry.replace("}", ", " + cleansedTag + "}");
			}
		}

		static String removeAllTags(final String entry) {
			final int delimiterIdx = entry.indexOf("{");
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
			final int openingDlm = entry.indexOf("{");
			final int closingDlm = entry.lastIndexOf("}");
			if (closingDlm > openingDlm) {
				return entry.substring(openingDlm + 1, closingDlm);
			}
			return "";
		}

		static String getUntaggedStringFromTags(final String entry) {
			final int openingDlm = entry.indexOf("{");
			if (openingDlm == -1) {
				return entry;
			} else {
				return entry.substring(0, openingDlm);
			}
		}

		static String[] getUntaggedAndTaggedLabels(final String entry) {
			final int openingDlm = entry.indexOf("{");
			if (openingDlm == -1) {
				return new String[] {entry, entry};
			} else {
				return new String[] {entry.substring(0, openingDlm), entry};
			}
		}

	}

	class DefaultUpdatableListModel<T> extends DefaultListModel<T> {
		private static final long serialVersionUID = 1L;
		private boolean listenersEnabled = true;

		public void update() {
			callFireContentsChangedOnSuper(this, 0, getSize() - 1);
		}

		public boolean getListenersEnabled() {
			return listenersEnabled;
		}

		public void setListenersEnabled(final boolean enabled) {
			listenersEnabled = enabled;
			if (frame != null && frame.managerPanel != null) {
				if (listenersEnabled)
					update();
				else
					frame.managerPanel.resetProgressBar();
			}
		}

		private void callFireContentsChangedOnSuper(final Object source, final int index0, final int index1) {
			super.fireContentsChanged(source, index0, index1);
			if (frame != null && frame.managerPanel != null) {
				frame.managerPanel.searchableBar.setStatusLabelPlaceholder(String.format(
						"%d item(s) listed", managerList.model.size() - 1));
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
		frame.disposeFrame();
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

	private class ShapeTree extends Shape {

		private static final float SOMA_SCALING_FACTOR = 2.5f;
		private static final float SOMA_SLICES = 15f; // Sphere default;
		private static final int DENDRITE = Path.SWC_DENDRITE;
		private static final int AXON = Path.SWC_AXON;
		private static final int ANY = -1;

		private final Tree tree;
		private Shape treeSubShape;
		private AbstractWireframeable somaSubShape;
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

			for (final Path p : tree.list()) {

				// Stash soma coordinates
				if (Path.SWC_SOMA == p.getSWCType()) {
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
				for (int i = 0; i < p.size(); ++i) {
					final PointInImage pim = p.getNode(i);
					final Coord3d coord = new Coord3d(pim.x, pim.y, pim.z);
					final Color color = fromAWTColor(p.hasNodeColors() ? p.getNodeColor(i)
						: p.getColor());
					final float width = Math.max((float) p.getNodeRadius(i),
						DEF_NODE_RADIUS);
					line.add(new Point(coord, color, width));
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

		private <T extends AbstractWireframeable & ISingleColorable> void
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

		private Color getArborWireFrameColor() {
			return (treeSubShape == null) ? null : treeSubShape.getWireframeColor();
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

	private class LineStripPlus extends LineStrip {
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

		public CmdWorker(final Class<? extends Command> cmd,
			final Map<String, Object> inputs, final int type,
			final boolean setRecViewerParamater)
		{
			this.cmd = cmd;
			this.inputs = inputs;
			this.type = type;
			this.setRecViewerParamater = setRecViewerParamater;
		}

		@Override
		public Boolean doInBackground() {
			try {
				final Map<String, Object> input = new HashMap<>();
				if (setRecViewerParamater) input.put("recViewer", Viewer3D.this);
				if (inputs != null) input.putAll(inputs);
				cmdService.run(cmd, true, input).get();
				return true;
			}
			catch (final NullPointerException e1) {
				return false;
			}
			catch (InterruptedException | ExecutionException e2) {
				gUtils.error(
					"Unfortunately an exception occured. See console for details.");
				SNTUtils.error("Error", e2);
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
			}
		}
	}

	private class MouseController extends AWTCameraMouseController {

		private float panStep = Prefs.PAN.MEDIUM.step;
		private boolean panDone;
		private Coord3d prevMouse3d;

		public MouseController(final Chart chart) {
			super(chart);
			addSlaveThreadController(new CameraThreadControllerPlus(chart)); // will removeThreadController
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

			while (step++ < nSteps) {
				try {
					final File f = new File(dir, String.format("%05d.png", step));
					rotate(new Coord2d(inc, 0d), false);
					chart.screenshot(f);
				}
				catch (final IOException e) {
					status = false;
				}
				finally {
					if (frame != null) frame.setResizable(true);
				}
			}
			return status;
		}

		@Override
		public boolean handleSlaveThread(final MouseEvent e) {
			if (AWTMouseUtilities.isDoubleClick(e)) {
				if (currentView == ViewMode.TOP) {
					displayMsg("Rotation disabled in constrained view");
					return true;
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
			if (currentView == ViewMode.TOP) {
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
			view.setBoundManual(newBounds);
			view.shoot();
			fireControllerEvent(ControllerType.PAN, offset);
		}

		public void zoom(final float factor) {
			final BoundingBox3d viewBounds = view.getBounds();
			BoundingBox3d newBounds = viewBounds.scale(new Coord3d(factor, factor,
				factor));
			newBounds = newBounds.shift((viewBounds.getCenter().sub(newBounds
				.getCenter())));
			view.setBoundManual(newBounds);
			view.shoot();
			fireControllerEvent(ControllerType.ZOOM, factor);
		}

		public void snapToNextView() {
			stopThreadController();
			((AChart)chart).setViewMode(currentView.next());
			displayMsg("View Mode: " + currentView.description);
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

	private class CameraThreadControllerPlus extends CameraThreadController {

		//TODO: here we should be bale to override #move and #doRun to improve
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

		private float zoomStep;
		private double rotationStep;

		public KeyController(final Chart chart) {
			register(chart);
		}

		/*
		 * (non-Javadoc)
		 *
		 * @see java.awt.event.KeyListener#keyPressed(java.awt.event.KeyEvent)
		 */
		@Override
		public void keyPressed(final KeyEvent e) {
			switch (e.getKeyChar()) {
				case 'a':
				case 'A':
					if (!emptySceneMsg()) toggleAxes();
					break;
				case 'c':
				case 'C':
					if (!emptySceneMsg()) changeCameraMode();
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
					logSceneControls();
					break;
				case 'r':
				case 'R':
					if (e.isShiftDown()) {
						validate();
						displayMsg("Scene reloaded");
					} else {
						resetView();
					}
					break;
				case 's':
				case 'S':
					saveScreenshot();
					break;
				case 'f':
				case 'F':
					if (e.isShiftDown()) {
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
							frame.exitFullScreen();
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
			view.setBoundManual(bounds.shift(offset));
			view.shoot();
		}

		private void toggleAxes() {
			chart.setAxeDisplayed(!view.isAxeBoxDisplayed());
		}

		private boolean emptySceneMsg() {
			final boolean empty = Viewer3D.this.isEmptyScene();
			if (empty) displayMsg("Scene is empty");
			return empty;
		}

		private void saveScreenshot() {
			Viewer3D.this.saveSnapshot();
			displayMsg("Snapshot saved to " + FileUtils.limitPath(
				prefs.snapshotDir, 50));
		}

		private void resetView() {
			try {
				chart.setViewPoint(View.DEFAULT_VIEW);
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
		public void keyReleased(final KeyEvent e) {}

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
			final Quality[] levels = { Quality.Fastest, Quality.Intermediate,
				Quality.Advanced, Quality.Nicest };
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
			view.setBackgroundColor(newBackground);
			view.getAxe().getLayout().setGridColor(newForeground);
			view.getAxe().getLayout().setMainColor(newForeground);
			((AChart)chart).overlayAnnotation.setForegroundColor(newForeground);
			if (cBar != null) cBar.updateColors();

			// Apply foreground color to trees with background color
			plottedTrees.values().forEach(shapeTree -> {
				if (isSameRGB(shapeTree.getSomaColor(), newBackground)) shapeTree
					.setSomaColor(newForeground);
				if (isSameRGB(shapeTree.getArborWireFrameColor(), newBackground)) {
					shapeTree.setArborColor(newForeground, -1);
					return; // replaces continue in lambda expression;
				}
				final Shape shape = shapeTree.treeSubShape;
				if (shape == null) return;
				for (int i = 0; i < shape.size(); i++) {
					final List<Point> points = ((LineStripPlus) shape.get(i)).getPoints();
					points.stream().forEach(p -> {
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
			sb.append("  <tr>");
			sb.append("    <td>Full Screen</td>");
			sb.append("    <td>Shift+F (Esc to exit)</td>");
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
			sb.append("    <td><u>R</u>eload View</td>");
			sb.append("    <td>Press Shift+'R'</td>");
			sb.append("  </tr>");
			sb.append("  <tr>");
			sb.append("    <td><u>S</u>napshot</td>");
			sb.append("    <td>Press 'S'</td>");
			sb.append("  </tr>");
			if (showInDialog) {
				sb.append("  <tr>");
				sb.append("  <tr>");
				sb.append("    <td><u>H</u>elp</td>");
				sb.append("    <td>Press 'H' (notification) or F1 (list)</td>");
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
				displayMsg(sb.toString(), 10000);
			}

		}
	}

	private class OverlayAnnotation extends CameraEyeOverlayAnnotation {

		private final GLAnimatorControl control;
		private java.awt.Color color;
		private String label;
		private Font labelFont;
		private java.awt.Color labelColor;
		private float labelX = 2;
		private float labelY = 0;

		private OverlayAnnotation(final View view) {
			super(view);
			control = ((IScreenCanvas) view.getCanvas()).getAnimator();
			control.setUpdateFPSFrames(FPSCounter.DEFAULT_FRAMES_PER_INTERVAL, null);
		}

		private void setForegroundColor(final Color c) {
			color = new java.awt.Color(c.r, c.g, c.b);
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
			g2d.setColor(color);
			if (SNTUtils.isDebugMode()) {
				int lineHeight = g.getFontMetrics().getHeight();
				g2d.drawString("Camera: " + view.getCamera().getEye(), 20, lineHeight);
				g2d.drawString("FOV: " + view.getCamera().getRenderingSphereRadius(),
					20, lineHeight += lineHeight);
				g2d.drawString(control.getLastFPS() + " FPS", 20, lineHeight +=
					lineHeight);
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

		LightController(final ViewerFrame viewerFrame) {

			super((viewerFrame.manager == null)?viewerFrame:viewerFrame.manager, "Light Effects");
			this.viewerFrame = viewerFrame;
			this.chart = viewerFrame.chart;
			existingSpecularColor = chart.getView().getBackgroundColor();
			assignDefaultLightIfNoneExists();
			final LightEditorPlus lightEditor = new LightEditorPlus(chart, chart.getScene().getLightSet().get(0));
			final JScrollPane scrollPane = new JScrollPane(lightEditor);
			scrollPane.setWheelScrollingEnabled(true);
			scrollPane.setBorder(null);
			scrollPane.setViewportView(lightEditor);
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
				viewerFrame.manager .toBack(); // byPasses MacOS bug?
				toFront();
			}
		}

		void resetLight() {
			// HACK: the scene does not seem to update when light is removed
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
		final List<AbstractDrawable> all = chart.getView().getScene().getGraph()
			.getAll();
		final BoundingBox3d bounds = new BoundingBox3d(0, 0, 0, 0, 0, 0);
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
	 * @param labels      the Collection of keys specifying the subset of trees
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
			if (labels.contains(k)) shapeTree.setThickness(thickness, comp);
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
		Color refColor = null;
		for (final Map.Entry<String, ShapeTree> entry : plottedTrees.entrySet()) {
			if (labels.contains(entry.getKey())) {
				final Shape shape = entry.getValue().treeSubShape;
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

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + id;
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		Viewer3D other = (Viewer3D) obj;
		if (id != other.id)
			return false;
		return true;
	}

	/* IDE debug method */
	public static void main(final String[] args) throws InterruptedException {
		GuiUtils.setSystemLookAndFeel();
		final ImageJ ij = new ImageJ();
		final Tree tree = new SNTService().demoTrees().get(0);
		final TreeColorMapper colorizer = new TreeColorMapper(ij.getContext());
		colorizer.map(tree, TreeColorMapper.PATH_ORDER, ColorTables.ICE);
		final double[] bounds = colorizer.getMinMax();
		SNTUtils.setDebugMode(true);
		final Viewer3D jzy3D = new Viewer3D(ij.context());
		jzy3D.addColorBarLegend(ColorTables.ICE, (float) bounds[0],
			(float) bounds[1], new Font("Arial", Font.PLAIN, 24), 3, 4);
		jzy3D.add(tree);
		final OBJMesh brainMesh = jzy3D.loadRefBrain("Allen CCF");
		OBJMesh mesh = AllenUtils.getCompartment("Thalamus").getMesh();
		if (mesh != null) { // server is online and reachable
			jzy3D.addMesh(mesh);
			SNTPoint centroid = mesh.getCentroid("l");
			Annotation3D cAnnot = jzy3D.annotatePoint(centroid, "l");
			cAnnot.setSize(30);
			cAnnot.setColor("green");
			centroid = mesh.getCentroid("a");
			cAnnot = jzy3D.annotatePoint(centroid, "a");
			cAnnot.setSize(30);
			cAnnot.setColor("cyan");
			centroid = mesh.getCentroid("r");
			cAnnot = jzy3D.annotatePoint(centroid, "r");
			cAnnot.setSize(30);
			cAnnot.setColor("red");
		}
		brainMesh.setBoundingBoxColor(Colors.RED);
		final TreeAnalyzer analyzer = new TreeAnalyzer(tree);
		final ArrayList<SNTPoint> selectedTips = new ArrayList<>();
		selectedTips.add(SNTPoint.average(analyzer.getTips()));
		selectedTips.add(AllenUtils.brainCenter());
		final Annotation3D annotation1 = jzy3D.annotateLine(selectedTips, "dummy");
		annotation1.setColor("orange");
		annotation1.setSize(10);
		Annotation3D annotation2 = jzy3D.annotatePoints(analyzer.getTips(), "tips");
		annotation2.setColor("green");
		annotation2.setSize(20);
		ArrayList<Annotation3D> list = new ArrayList<Annotation3D>();
		list.add(annotation1);
		list.add(annotation2);
		Annotation3D a = jzy3D.mergeAnnotations(list, "");
		a.setSize(4);
		a.setColor("pink");
		brainMesh.translate(new PointInImage(800, 0, 0));
		if (jzy3D.viewUpdatesEnabled) {
			jzy3D.view.shoot();
			jzy3D.fitToVisibleObjects(true, false);
		}
		MouseLightLoader loader = new MouseLightLoader("AA1044");
		Tree aa1044 = loader.getTree("axon");
		Annotation3D hull = jzy3D.annotateSurface(new TreeAnalyzer(aa1044).getTips(), "Convex Hull", true);
		System.out.println("AA1044: Convex hull volume = " + hull.getVolume());
		jzy3D.addTree(aa1044);
		jzy3D.show();
		jzy3D.setAnimationEnabled(true);
		jzy3D.setViewPoint(-1.5707964f, -1.5707964f);
		jzy3D.updateColorBarLegend(-8, 88);
		jzy3D.setEnableDarkMode(false);
		brainMesh.translate(new PointInImage(800, 0, 0));
	}

}
