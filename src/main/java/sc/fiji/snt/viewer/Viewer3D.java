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

package sc.fiji.snt.viewer;

import com.jidesoft.swing.*;
import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GL2GL3;
import com.jogamp.opengl.GL3;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLException;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.fixedfunc.GLPointerFunc;
import com.jogamp.opengl.util.awt.AWTGLReadBufferUtil;
import ij.ImagePlus;
import net.imagej.display.ColorTables;
import net.imglib2.display.ColorTable;
import org.jzy3d.bridge.swing.FrameSwing;
import org.jzy3d.chart.AWTNativeChart;
import org.jzy3d.chart.Chart;
import org.jzy3d.chart.Settings;
import org.jzy3d.chart.controllers.ControllerType;
import org.jzy3d.chart.controllers.camera.AbstractCameraController;
import org.jzy3d.chart.controllers.mouse.AWTMouseUtilities;
import org.jzy3d.chart.controllers.mouse.camera.AWTCameraMouseController;
import org.jzy3d.chart.controllers.thread.camera.CameraThreadController;
import org.jzy3d.chart.factories.*;
import org.jzy3d.colors.Color;
import org.jzy3d.colors.ColorMapper;
import org.jzy3d.colors.ISingleColorable;
import org.jzy3d.debugGL.tracers.DebugGLChart3d;
import org.jzy3d.events.ViewPointChangedEvent;
import org.jzy3d.maths.BoundingBox2d;
import org.jzy3d.maths.BoundingBox3d;
import org.jzy3d.maths.Coord2d;
import org.jzy3d.maths.Coord3d;
import org.jzy3d.maths.Rectangle;
import org.jzy3d.plot2d.primitive.AWTColorbarImageGenerator;
import org.jzy3d.io.IGLLoader;
import org.jzy3d.painters.IPainter;
import org.jzy3d.painters.NativeDesktopPainter;
import org.jzy3d.plot3d.primitives.Composite;
import org.jzy3d.plot3d.primitives.Shape;
import org.jzy3d.plot3d.primitives.*;
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
import org.jzy3d.plot3d.rendering.view.*;
import org.jzy3d.plot3d.rendering.view.annotation.CameraEyeOverlayAnnotation;
import org.jzy3d.plot3d.rendering.view.modes.CameraMode;
import org.jzy3d.plot3d.rendering.view.modes.ViewBoundMode;
import org.jzy3d.plot3d.rendering.view.modes.ViewPositionMode;
import org.jzy3d.plot3d.transform.Transform;
import org.jzy3d.plot3d.transform.Translate;
import org.jzy3d.plot3d.transform.squarifier.*;
import org.jzy3d.ui.editors.LightEditor;
import org.scijava.Context;
import org.scijava.command.Command;
import org.scijava.command.CommandModule;
import org.scijava.command.CommandService;
import org.scijava.plugin.Parameter;
import org.scijava.prefs.PrefService;
import org.scijava.ui.awt.AWTWindows;
import org.scijava.util.ColorRGB;
import org.scijava.util.ColorRGBA;
import org.scijava.util.FileUtils;
import org.scijava.util.PlatformUtils;
import sc.fiji.snt.*;
import sc.fiji.snt.analysis.MultiTreeColorMapper;
import sc.fiji.snt.analysis.SNTTable;
import sc.fiji.snt.analysis.TreeColorMapper;
import sc.fiji.snt.analysis.TreeStatistics;
import sc.fiji.snt.analysis.graph.DirectedWeightedGraph;
import sc.fiji.snt.annotation.AllenCompartment;
import sc.fiji.snt.annotation.AllenUtils;
import sc.fiji.snt.annotation.VFBUtils;
import sc.fiji.snt.annotation.ZBAtlasUtils;
import sc.fiji.snt.gui.*;
import sc.fiji.snt.gui.DemoRunner.Demo;
import sc.fiji.snt.gui.IconFactory.GLYPH;
import sc.fiji.snt.gui.cmds.*;
import sc.fiji.snt.io.FlyCircuitLoader;
import sc.fiji.snt.io.NeuroMorphoLoader;
import sc.fiji.snt.plugin.*;
import sc.fiji.snt.util.*;
import sc.fiji.snt.viewer.OBJMesh.RemountableDrawableVBO;

import javax.swing.Timer;
import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.Position;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
        XY("XY Constrained", ViewPositionMode.TOP),
        /** Enforce a XZ view point of the scene. Rotation(s) are disabled. */
        XZ("XZ Constrained", ViewPositionMode.XZ),
        /** Enforce a YZ view point of the scene. Rotation(s) are disabled. */
        YZ("YZ Constrained", ViewPositionMode.YZ),
        /** No enforcement of view point: freely turn around the scene. */
        DEFAULT("Default", ViewPositionMode.FREE),
        /** Enforce an 'overview' (two-point perspective) view point of the scene. */
        PERSPECTIVE("Perspective", ViewPositionMode.FREE),

        /** @deprecated Use YZ instead */
        @Deprecated
        SIDE("Side Constrained", ViewPositionMode.YZ),
        /** @deprecated Use XY instead */
        @Deprecated
        TOP("Top Constrained", ViewPositionMode.TOP);

        private final String description;
        private final ViewPositionMode positionMode;

        private ViewMode next() {
            return switch (this) {
                case DEFAULT -> XY;
                case XY -> XZ;
                case XZ -> YZ;
                case YZ -> PERSPECTIVE;
                default -> DEFAULT;
            };
        }

        ViewMode(final String description, final ViewPositionMode positionMode) {
            this.description = description;
            this.positionMode = positionMode;
        }

        /** Default viewpoints for FREE-mode views (DEFAULT, PERSPECTIVE). */
        static class DefCoords {
            static final Coord3d PERSPECTIVE = new Coord3d(-Math.PI / 2.675, -0.675, View.DISTANCE_DEFAULT);
            static final Coord3d DEF = View.VIEWPOINT_AXIS_CORNER_TOUCH_BORDER;
        }
    }

    /**
     * Axes of rotation for animated sequences and live rotations.
     */
    public enum RotationAxis {
        /** Rotation around the Z-axis (azimuth sweep). This is the default. */
        Z,
        /** Rotation around the X-axis (elevation sweep from front). */
        X,
        /** Rotation around the Y-axis (elevation sweep from side). */
        Y;

        /**
         * @param text the axis description
         * @return a Rotation axis from a string description, returning {@code Z} as fallback value
         */
        public static RotationAxis fromString(final String text) {
            if (text == null || text.isBlank())
                return Z;
            for (final RotationAxis r : RotationAxis.values()) {
                if (text.contains(r.name()))
                    return r;
            }
            return Z; // default
        }
    }

    /**
     * Animation styles for live and recorded rotations.
     */
    public enum AnimationMode {
        /** Continuous 360° rotation (the default). */
        FULL_ROTATION,
        /** Oscillating rotation that rocks back and forth over a fixed arc. */
        PING_PONG;

        private static final double DEFAULT_PING_PONG_ARC = Math.toRadians(60); // ±30°
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
    /** The anatomical up vector for the scene, or {@code null} for default Z-up.
     *  Set by {@link #applyAxisMapping} or when a reference brain is loaded. */
    private Coord3d sceneUpVector;
    private FileDropWorker fileDropWorker;
    private boolean abortCurrentOperation;
    private final Engine ENGINE;
    private SNTCommandFinder cmdFinder;
    private ScriptRecorder recorder;
    private MeasureUI measureUI;

    @Parameter
    private CommandService cmdService;

    @Parameter
    private PrefService prefService;

    private static final String PREF_TUBE_MODE = "viewer3d.tubeMode";
    private static final String PREF_TUBE_WIREFRAME = "viewer3d.tubeWireframe";
    private static final String PREF_TUBE_SIDES = "viewer3d.tubeSides";
    private static final int DEFAULT_TUBE_SIDES = 12;
    private static final String PREF_DEPTH_FOG = "viewer3d.depthFog";
    private static final String PREF_FOG_INTENSITY = "viewer3d.fogIntensity";
    private static final float DEFAULT_FOG_INTENSITY = 0.7f;
    private static final String PREF_PSEUDO_LIGHTING = "viewer3d.pseudoLighting";
    private static final String PREF_UPSAMPLING = "viewer3d.upsamplingFactor";
    private static final String PREF_MESH_SHADING = "viewer3d.meshShading";
    private static final String PREF_MESH_BACKFACE_CULL = "viewer3d.meshBackfaceCull";
    private boolean tubeModeEnabled;
    private boolean tubeWireframeEnabled;
    private boolean depthFogEnabled;
    private boolean pseudoLightingEnabled;
    private int upsamplingFactorPref = 1;
    private int tubeSidesPref = DEFAULT_TUBE_SIDES;
    private float fogIntensityPref = DEFAULT_FOG_INTENSITY;
    private int meshShadingPref = OBJMesh.SHADING_DEFAULT;
    private boolean meshBackfaceCullPref = false;

    private Viewer3D(final Engine engine) {
        SNTUtils.log("Initializing Viewer3D...");
        ENGINE = engine;
        if (Engine.JOGL == engine || Engine.OFFSCREEN == engine) {
            // Prefer GL3 Core Profile for geometry shader support (tube rendering).
            // Falls back to the platform default (typically GL2) if unavailable.
            // On current macOS/JOGL combinations GL3 may still silently resolve to
            // GL2, in which case tube shaders will fail gracefully at init time.
            GLProfile profile;
            try {
                profile = GLProfile.get(GLProfile.GL3);
            } catch (final GLException e) {
                profile = GLProfile.getDefault();
            }
            Settings.getInstance().setGLCapabilities(new GLCapabilities(profile));
            Settings.getInstance().setHardwareAccelerated(true);
        }
        plottedTrees = new TreeMap<>();
        plottedObjs = new TreeMap<>();
        plottedAnnotations = new TreeMap<>();
        initView();
        prefs = new Prefs(this);
        setAnimationMode(prefs.getAnimationModePref());
        setID();
        SNTUtils.addViewer(this);
    }

    /**
     * Instantiates Viewer3D without the 'Controls' dialog ('kiosk mode'). Such
     * a viewer is more suitable for large datasets and allows for {@link Tree}s to
     * be added concurrently.
     */
    public Viewer3D() {
        this((GraphicsEnvironment.isHeadless()) ? Engine.OFFSCREEN : Engine.JOGL);
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
        tubeModeEnabled = prefService != null && prefService.getBoolean(Viewer3D.class, PREF_TUBE_MODE, false);
        tubeWireframeEnabled = prefService != null && prefService.getBoolean(Viewer3D.class, PREF_TUBE_WIREFRAME, false);
        depthFogEnabled = prefService != null && prefService.getBoolean(Viewer3D.class, PREF_DEPTH_FOG, false);
        pseudoLightingEnabled = prefService != null && prefService.getBoolean(Viewer3D.class, PREF_PSEUDO_LIGHTING, false);
        if (prefService != null) {
            fogIntensityPref = (float) prefService.getDouble(Viewer3D.class, PREF_FOG_INTENSITY, DEFAULT_FOG_INTENSITY);
            upsamplingFactorPref = prefService.getInt(Viewer3D.class, PREF_UPSAMPLING, 1);
            tubeSidesPref = prefService.getInt(Viewer3D.class, PREF_TUBE_SIDES, DEFAULT_TUBE_SIDES);
            meshShadingPref = prefService.getInt(Viewer3D.class, PREF_MESH_SHADING, OBJMesh.SHADING_DEFAULT);
            meshBackfaceCullPref = prefService.getBoolean(Viewer3D.class, PREF_MESH_BACKFACE_CULL, false);
        }
        cmdFinder = new SNTCommandFinder(this);
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

    /**
     * Whether the scene uses an anatomical atlas whose dorsal-ventral axis maps
     * to the Cartesian Y axis, requiring Y-up camera orientation. This is true when
     * an axis mapping has assigned Dorsal-Ventral to Y (via
     * {@link #applyAxisMapping}) or when the Allen CCF reference brain is loaded.
     */
    boolean isAnatomicalYUp() {
        return sceneUpVector != null
                && sceneUpVector.x == 0 && sceneUpVector.y != 0 && sceneUpVector.z == 0;
    }

    /**
     * Applies an anatomical axis mapping to the scene. This sets the camera up
     * vector so that dorsal points to the top of the screen, updates the axis
     * labels, and ensures that constrained 2D views use the correct aspect
     * ratio.
     *
     * @param mapping a map with keys "xAxis", "yAxis", "zAxis" (each mapped to
     *                an anatomical label such as "Dorsal-Ventral"), and
     *                "dorsalDir" (mapped to a direction string). Typically
     *                produced by {@link CustomizeAxesCmd}.
     * @see CustomizeAxesCmd
     */
    void applyAxisMapping(final java.util.HashMap<String, String> mapping) {
        // Update axis labels
        setAxesLabels(CustomizeAxesCmd.getLabels(mapping));

        // Determine the up vector from the D-V axis assignment
        final String dvAxis = CustomizeAxesCmd.getDVAxis(mapping);
        if (dvAxis == null || view == null) {
            // No D-V axis assigned: reset to default Z-up
            if (view != null) view.setUpVector(View.UP_VECTOR_Z);
            return;
        }
        final int sign = CustomizeAxesCmd.getDorsalSign(mapping);
        sceneUpVector = switch (dvAxis) {
            case "X" -> new Coord3d(sign, 0, 0);
            case "Y" -> new Coord3d(0, sign, 0);
            case "Z" -> new Coord3d(0, 0, sign);
            default -> null;
        };
        if (sceneUpVector != null) {
            view.setUpVector(sceneUpVector);
        }
        // Refresh the current view mode to apply the new up vector
        if (chartExists()) {
            chart.setViewMode(currentView);
        }
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
        squarify("none");
        currentView = ViewMode.DEFAULT;
        if (!(chart.getCanvas() instanceof OffscreenCanvas)) {
            gUtils = new GuiUtils((Component) chart.getCanvas());
            fileDropWorker = new FileDropWorker((Component) chart.getCanvas(), gUtils);
        }
        return true;
    }

    private void squarify(final String axes) {
        final String parsedAxes = (axes == null) ? "none" : axes.toLowerCase();
        switch (parsedAxes) {
            case "xy" -> {
                view.setSquarifier(new XYSquarifier());
                view.setSquared(true);
            }
            case "xz" -> {
                view.setSquarifier(new XZSquarifier());
                view.setSquared(true);
            }
            case "yx" -> {
                view.setSquarifier(new YXSquarifier());
                view.setSquared(true);
            }
            case "yz" -> {
                view.setSquarifier(new YZSquarifier());
                view.setSquared(true);
            }
            case "zx" -> {
                view.setSquarifier(new ZXSquarifier());
                view.setSquared(true);
            }
            case "zy" -> {
                view.setSquarifier(new ZYSquarifier());
                view.setSquared(true);
            }
            default -> {
                view.setSquarifier(null);
                view.setSquared(false);
            }
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

    /**
     * Sets custom labels for the 3D coordinate axes.
     *
     * @param labels the axis labels in order: X-axis, Y-axis, Z-axis.
     *               If null, defaults to "X", "Y", "Z". If fewer than 3 labels
     *               are provided, only the available axes are labeled.
     */
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
        return new String[]{view.getAxisLayout().getXAxisLabel(), view.getAxisLayout().getYAxisLabel(),
                view.getAxisLayout().getZAxisLabel()};
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
            // Invalidate GL buffer handles from the disposed context so all VBOs
            // re-upload their data on first draw in the new context.
            plottedTrees.values().forEach(ShapeTree::invalidateGL);
            addAllObjects();
            setAnimationEnabled(isAnimating);
            if (frame != null && frame.managerPanel != null && frame.managerPanel.debugger != null) {
                frame.managerPanel.debugger.setWatchedChart(chart);
            }
        } catch (final GLException | NullPointerException exc) {
            SNTUtils.error("Rebuild Error", exc);
        }
        if (frame != null) frame.replaceCurrentChart(chart);
        updateView();
    }

    /**
     * Creates a duplicate of this viewer containing only visible objects.
     * <p>
     * This method creates a new Viewer3D instance and copies all currently visible
     * objects (trees, meshes, annotations) from this viewer to the new one. The
     * duplicate viewer maintains the same visual settings and object properties
     * but operates independently from the original.
     * </p>
     *
     * @return a new Viewer3D instance containing copies of all visible objects
     */
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
        if (frame != null) {
            final Runnable r = () -> {
                dup.frame = new ViewerFrame(dup.chart, frame.getWidth(), frame.getHeight(), dup.managerList != null,
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
            };
            if (SwingUtilities.isEventDispatchThread()) {
                r.run();
            } else {
                try {
                    SwingUtilities.invokeAndWait(r);
                } catch (final Exception e) {
                    SNTUtils.error("Failed to create duplicate frame", e);
                }
            }
        }
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
        final Runnable r = () -> {
            if (frame != null && frame.managerPanel != null) {
                frame.managerPanel.setDebuggerEnabled(enable);
            }
        };
        if (SwingUtilities.isEventDispatchThread()) r.run();
        else SwingUtilities.invokeLater(r);
        SNTUtils.setDebugMode(enable);
    }

    /**
     * Enables/disables "Dark Mode" mode
     *
     * @param enable true to enable "Dark Mode", "Light Mode" otherwise
     */
    public void setEnableDarkMode(final boolean enable) {
        final Runnable r = () -> {
            final boolean toggle = keyController != null && isDarkModeOn() != enable;
            if (toggle) keyController.toggleDarkMode();
        };
        if (SwingUtilities.isEventDispatchThread()) r.run();
        else SwingUtilities.invokeLater(r);
    }

    /**
     * Enables/disables the display of X,Y,Z axis grid.
     *
     * @param enable true to enable axes
     */
    public void setEnableAxes(final boolean enable){
        chart.setAxeDisplayed(enable);
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
        recordRotation(angle, frames, destinationDirectory, RotationAxis.Z);
    }

    /**
     * Records an animated rotation of the scene around the specified axis as a
     * sequence of images.
     *
     * @param angle the rotation angle (e.g., 360 for a full rotation)
     * @param frames the number of frames in the animated sequence
     * @param destinationDirectory the directory where the image sequence will be
     *          stored.
     * @param axis the axis of rotation (X, Y, or Z)
     * @throws IllegalArgumentException if no view exists, or current view is
     *           constrained and does not allow rotation
     * @throws SecurityException if it was not possible to save files to
     *           {@code destinationDirectory}
     */
    public void recordRotation(final float angle, final int frames, final File destinationDirectory,
                               final RotationAxis axis) throws IllegalArgumentException, SecurityException
    {
        if (!chartExists()) {
            throw new IllegalArgumentException("Viewer is not visible");
        }
        // Release any constrained view mode so that rotation is not blocked.
        // The current viewpoint should be preserved: only the constraint is removed
        if (chart.getView().getViewMode() != ViewPositionMode.FREE) {
            chart.getView().setViewPositionMode(ViewPositionMode.FREE);
            currentView = ViewMode.DEFAULT;
        }
        mouseController.stopThreadController();
        mouseController.recordRotation(angle, frames, destinationDirectory, axis);

        // Log instructions on how to assemble video
        logVideoInstructions(destinationDirectory);

    }

    private void logVideoInstructions(final File destinationDirectory) {
        final StringBuilder sb = new StringBuilder("The image sequence can be converted into a video using ffmpeg (www.ffmpeg.org):");
        sb.append("\n");
        sb.append("  cd \"").append(destinationDirectory).append("\"\n");
        sb.append("  ffmpeg -framerate ").append(prefs.getFPS()).append(" -i %5d.png -vf \"scale=-1:-1,format=yuv420p\" video.mp4");
        sb.append("\n\n");
		sb.append("- Parameters that can be included in the comma-separated list of -vf \"\" options:\n");
		sb.append("  hflip		flip sequence horizontally\n");
		sb.append("  vflip		flip sequence vertically\n");
		sb.append("  transpose=0	90° counterclockwise and vertical flip (default)\n");
		sb.append("  transpose=1	90° clockwise\n");
		sb.append("  transpose=2	90° counterclockwise\n");
		sb.append("  transpose=3	90° clockwise and vertical flip\n");
		sb.append("\n");
		sb.append("- To use all images in a folder use e.g.:\n");
		sb.append("  ffmpeg -framerate ").append(prefs.getFPS()).append(" -pattern_type glob -i \"*.png\" -vf \"(...)\" video.mp4");
        sb.append("\n\n");
        sb.append("\nAlternatively, ImageJ built-in commands can also be used, e.g.:\n");
        sb.append("\"File>Import>Image Sequence...\", followed by \"File>Save As>AVI...\"");
        try {
            Files.writeString(Paths.get(new File(destinationDirectory, "-build-video.txt").getAbsolutePath()),
                    sb.toString());
        } catch (final IOException e) {
            System.out.println(sb);
        }
    }

    /**
     * Checks if scene is being rendered under dark or light background.
     *
     * @return true, if "Dark Mode" is active
     */
    public boolean isDarkModeOn() {
        return Color.BLACK.equals(view.getBackgroundColor());
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
                    final boolean show = selectedKeys.contains(k);
                    if (shapeTree.isDisplayed() != show) shapeTree.setDisplayed(show);
                });
                plottedObjs.forEach((k, drawableVBO) -> {
                    final boolean show = selectedKeys.contains(k);
                    if (drawableVBO.isDisplayed() != show) drawableVBO.setDisplayed(show);
                });
                plottedAnnotations.forEach((k, annot) -> {
                    final boolean show = selectedKeys.contains(k);
                    if (annot.getDrawable().isDisplayed() != show) annot.getDrawable().setDisplayed(show);
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
     *                   in the control panel list).
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
            case "unique" -> TreeUtils.assignUniqueColors(trees);
            case "", "none" -> {}
            default -> trees.forEach(tree -> tree.setColor(color));
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
        if (fileDropWorker == null)
            throw new IllegalStateException("File importing is not available for the current rendering engine.");
        fileDropWorker.importFilesWithoutDrop(files, c);
    }

    private GuiUtils guiUtils() {
        return gUtils;
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
            trees = new ArrayList<>(plottedTrees.size());
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
            meshes = new ArrayList<>(plottedObjs.size());
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
        annotation.getDrawable().setDisplayed(true);
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
        annotation.getDrawable().setDisplayed(true);
        chart.add(annotation.getDrawable(), viewUpdatesEnabled);
        return annotation;
    }

    public Annotation3D annotateMidPlane(final BoundingBox boundingBox, final int axis, final String label) {
        final PointInImage[] plane = AnnotPrompt.getPlane(boundingBox.toBoundingBox3d(), "xyz".substring(axis, axis + 1));
        return annotatePlane(plane[0], plane[1], label);
    }

    public Annotation3D annotatePlane(final SNTPoint origin, final SNTPoint originOpposite, final String label) {
        if (origin == null || originOpposite == null) return null;
        final Annotation3D annotation = new Annotation3D(this, List.of(origin, originOpposite), Annotation3D.PLANE);
        final String uniqueLabel = getUniqueLabel(plottedAnnotations, "Plane Annot.", label);
        annotation.setLabel(uniqueLabel);
        plottedAnnotations.put(uniqueLabel, annotation);
        addItemToManager(uniqueLabel);
        annotation.getDrawable().setDisplayed(true);
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
            removeAnnotation(labelAndManagerEntry[0], labelAndManagerEntry[1]);
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
        final Runnable r = () -> {
            final int[] indices = managerList.getCheckBoxListSelectedIndices();
            final int index = managerList.model.size() - 1;
            managerList.model.insertElementAt(label, index);
        // Batch-update selection without firing the visibility listener on each call
        final var selModel = managerList.getCheckBoxListSelectionModel();
        selModel.setValueIsAdjusting(true);
        managerList.addCheckBoxListSelectedIndex(index);
            for (final int i : indices)
                managerList.addCheckBoxListSelectedIndex(i);
            selModel.setValueIsAdjusting(false);
        };
        if (SwingUtilities.isEventDispatchThread()) r.run();
        else SwingUtilities.invokeLater(r);
    }

    private boolean deleteItemFromManager(final String managerEntry) {
        if (managerList == null || managerList.model == null)
            return false;
        final boolean[] result = new boolean[1];
        final Runnable r = () -> {
            if (!managerList.model.removeElement(managerEntry)) {
                // managerEntry was not found. It is likely associated
                // with a tagged element. Retry:
            for (int i = 0; i < managerList.model.getSize(); i++) {
                    final Object entry = managerList.model.getElementAt(i);
                    if (CheckBoxList.ALL_ENTRY.equals(entry)) continue;
                    if (TagUtils.removeAllTags(entry.toString()).equals(managerEntry)) {
                        result[0] = managerList.model.removeElement(entry);
                        return;
                    }
                }
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(r);
            } catch (final Exception e) {
                SNTUtils.error("Failed to delete manager entry", e);
            }
        }
        return result[0];
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
                if (obj == shapeTree.tree) {
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
            try {
                // jzy3d can refresh bounds without forcing a full visible-drawable scan.
                view.updateBounds();
            } catch (final Throwable ignored) {
                fitToVisibleObjects(false, false);
            }
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
     *                defining objects listed in the control panel.
     *                Collections of supported objects are also supported.
     *                If Null view is reset. If "visible" view is zoomed to visible
     *                objects
     */
    public void zoomTo(final Object... objects) {
        if (objects == null) {
            resetView();
            return;
        }
        if (objects.length == 1 && "visible".equalsIgnoreCase(objects[0].toString())) {
            fitToVisibleObjects(true, false);
            return;
        }
        Object[] objs;
        if (objects.length == 1 && objects[0] instanceof Collection<?>) {
            objs = ((Collection<?>)objects[0]).toArray(new Object[0]);
        } else {
            objs = objects;
        }
        final BoundingBox3d bounds = new BoundingBox3d();
        for (final Object obj : objs) {
            switch (obj) {
                case BoundingBox3d box3d -> bounds.add(box3d);
                case BoundingBox box -> bounds.add(box.toBoundingBox3d());
                case Path path -> bounds.add(new Tree(List.of(path)).getBoundingBox().toBoundingBox3d());
                case null, default -> {
                    final Drawable d = getDrawableFromObject(obj);
                    if (d != null && d.isDisplayed() && d.getBounds() != null && !d.getBounds().isReset()) {
                        bounds.add(d.getBounds());
                    }
                }
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

    /**
     * Gets the frame containing this viewer, optionally controlling its visibility.
     * <p>
     * Returns the AWT Frame that contains this viewer's 3D canvas and UI components.
     * If no frame exists, one will be created with the specified visibility setting.
     * This method is useful when you need to control whether the viewer window
     * appears immediately or remains hidden for programmatic manipulation.
     * </p>
     *
     * @param visible whether the frame should be visible when created
     * @return the frame containing the viewer
     * @throws IllegalArgumentException if using an offscreen rendering engine
     */
    public Frame getFrame(final boolean visible) {
        if (frame == null) {
            if (Engine.OFFSCREEN == ENGINE) {
                throw new IllegalArgumentException("Offscreen canvas cannot be displayed.");
            }
            final Runnable r = () -> {
                final JFrame dummy = new JFrame();
                frame = (ViewerFrame) show(0, 0, dummy.getGraphicsConfiguration(), visible);
                dummy.dispose();
            };
            if (SwingUtilities.isEventDispatchThread()) {
                r.run();
            } else {
                try {
                    SwingUtilities.invokeAndWait(r);
                } catch (final Exception e) {
                    SNTUtils.error("Failed to create frame", e);
                }
            }
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
        final Frame[] result = new Frame[1];
        final Runnable r = () -> {
            final JFrame dummy = new JFrame();
            result[0] = show(width, height, dummy.getGraphicsConfiguration(), true);
            dummy.dispose();
        };
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(r);
            } catch (final Exception e) {
                SNTUtils.error("Failed to show viewer", e);
            }
        }
        return result[0];
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
            plottedTrees.forEach((k, shapeTree) -> chart.add(shapeTree.get(), viewUpdatesEnabled));
            plottedObjs.forEach((k, drawableVBO) -> chart.add(drawableVBO, viewUpdatesEnabled));
            plottedAnnotations.forEach((k, annot) -> chart.add(annot.getDrawable(), viewUpdatesEnabled));
        }
        if (width == 0 || height == 0) {
            frame = new ViewerFrame(chart, managerList != null, gConfiguration);
        } else {
            final DisplayMode dm = gConfiguration.getDevice().getDisplayMode();
            final int w = (width < 0) ? dm.getWidth() : width;
            final int h = (height < 0) ? dm.getHeight() : height;
            frame = new ViewerFrame(chart, w, h, managerList != null, gConfiguration);
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
        final Runnable r = () -> {
            if (width == -1 && height == -1) {
                frame.setLocation(0, 0);
                frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
            }
            final int w = (width == 0) ? (int) (ViewerFrame.DEF_WIDTH * Prefs.SCALE_FACTOR) : width;
            final int h = (height == 0) ? (int) (ViewerFrame.DEF_HEIGHT * Prefs.SCALE_FACTOR) : height;
            if (width == -1) {
                frame.setExtendedState(JFrame.MAXIMIZED_HORIZ);
                frame.setSize((frame.getWidth() == 0) ? w : frame.getWidth(), h);
            } else if (height == -1) {
                frame.setExtendedState(JFrame.MAXIMIZED_VERT);
                frame.setSize(w, (frame.getHeight() == 0) ? h : frame.getHeight());
            } else {
                frame.setSize(w, h);
            }
        };
        if (SwingUtilities.isEventDispatchThread()) r.run();
        else SwingUtilities.invokeLater(r);
    }

    public void setLookAndFeel(final String lookAndFeelName) {
        if (frame == null) return;
        final Runnable r = () -> {
            final ArrayList<Component> components = new ArrayList<>();
            components.add(frame);
            if (frame.hasManager())
            components.add(frame.managerPanel);
        if (frame.allenNavigator != null) {
            components.add(frame.allenNavigator.dialog);
        }
            if (managerList != null)
                components.add(managerList.getComponentPopupMenu());
            GuiUtils.setLookAndFeel(lookAndFeelName, false, components.toArray(new Component[0]));
        };
        if (SwingUtilities.isEventDispatchThread()) r.run();
        else SwingUtilities.invokeLater(r);
    }

    private void syncLookAndFeel(final boolean dark) {
        if (SNTUtils.isStandaloneContext()) {
            setLookAndFeel(dark ? GuiUtils.LAF_DARK : GuiUtils.LAF_LIGHT);
        }
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
                    frame.updateStatusMsg();
                    return;
                }
                final Timer timer = new Timer(msecs, e -> frame.updateStatusMsg());
                timer.setRepeats(false);
                timer.start();
                frame.status.setText(msg);
            });
        } else {
            System.out.println(msg);
        }
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

    /**
     * Removes the annotation with the specified label from the viewer.
     *
     * @param annotLabel the label identifying the annotation to remove
     * @return true if the annotation was successfully removed, false if no
     *         annotation with the specified label was found
     */
    public boolean removeAnnotation(final String annotLabel) {
        final String[] labelAndManagerEntry = TagUtils.getUntaggedAndTaggedLabels(annotLabel);
        return removeAnnotation(labelAndManagerEntry[0], labelAndManagerEntry[1]);
    }

    /**
     * Removes the specified annotation from the viewer.
     *
     * @param annot the Annotation3D object to remove
     * @return true if the annotation was successfully removed, false if the
     *         annotation was not found in this viewer
     */
    public boolean removeAnnotation(final Annotation3D annot) {
        if (annot == null) return false;
        for (final Entry<String, Annotation3D> entry : plottedAnnotations.entrySet()) {
            if (entry.getValue() == annot) {
                return removeAnnotation(entry.getKey(), entry.getValue().getLabel());
            }
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
        setSceneUpdatesEnabled(false);
        final Iterator<Entry<String, RemountableDrawableVBO>> it = plottedObjs.entrySet().iterator();
        while (it.hasNext()) {
            final Entry<String, RemountableDrawableVBO> entry = it.next();
            if (chart != null) {
                chart.getScene().getGraph().remove(entry.getValue(), false);
            }
            deleteItemFromManager(entry.getKey());
            if (frame != null && frame.allenNavigator != null) {
                frame.allenNavigator.meshRemoved(entry.getKey());
            }
            it.remove();
        }
        // All meshes gone: reset to default Z-up camera
        sceneUpVector = null;
        if (view != null) view.setUpVector(View.UP_VECTOR_Z);
        setSceneUpdatesEnabled(updateStatus);
        validate();
    }

    /**
     * Removes all the Trees from current viewer
     */
    public void removeAllTrees() {
        final boolean updateStatus = viewUpdatesEnabled;
        setSceneUpdatesEnabled(false);
        final Iterator<Entry<String, ShapeTree>> it = plottedTrees.entrySet().iterator();
        while (it.hasNext()) {
            final Entry<String, ShapeTree> entry = it.next();
            if (chart != null) {
                chart.getScene().getGraph().remove(entry.getValue().get(), false);
            }
            deleteItemFromManager(entry.getKey());
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

    /**
     * Resets the view to its default position and orientation.
     * <p>
     * This method restores the camera to its initial position, orientation, and
     * zoom level, providing a consistent starting point for viewing the scene.
     * </p>
     */
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
        switch (object) {
            case null -> {
                SNTUtils.log("Null object ignored for scene addition");
                return;
            }
            case Tree tree1 -> addTree(tree1);
            case Path path -> {
                final Tree tree = new Tree();
                tree.add(path);
                addTree(tree);
            }
            case DirectedWeightedGraph directedWeightedGraph -> {
                final Tree tree = directedWeightedGraph.getTree();
                tree.setColor(SNTColor.getDistinctColors(1)[0]);
                addTree(tree);
            }
            case Annotation3D annotation3D -> addAnnotation(annotation3D);
            case SNTPoint point -> annotatePoint(point, null);
            case OBJMesh objMesh -> addMesh(objMesh);
            case String s -> addLabel(s);
            case Drawable drawable -> chart.add(drawable, viewUpdatesEnabled);
            case Collection<?> collection -> addCollection(collection);
            default -> throw new IllegalArgumentException("Unsupported object: " + object.getClass().getName());
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
        switch (object) {
            case Tree tree -> removeTree(tree);
            case OBJMesh objMesh -> removeMesh(objMesh);
            case Annotation3D annotation3D -> removeAnnotation(annotation3D);
            case String s -> {
                final String[] labelAndManagerEntry = TagUtils.getUntaggedAndTaggedLabels(s);
                removeSceneObject(labelAndManagerEntry[0], labelAndManagerEntry[1]);
            }
            case Drawable drawable when chart != null ->
                    chart.getScene().getGraph().remove(drawable, viewUpdatesEnabled);
            case Collection<?> collection -> removeCollection(collection);
            case null, default -> {
                validate();
                throw new IllegalArgumentException((object == null) ? "Unsupported object: null" :
                        "Unsupported object: " + object.getClass().getName());
            }
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
                // Reset to default Z-up if anatomical Y-up atlas was removed
                if (!isAnatomicalYUp() && view != null) {
                    sceneUpVector = null;
                    view.setUpVector(View.UP_VECTOR_Z);
                }
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
                    allTreeDrawablesRendered(viewBounds, visibleKeys) &&
                    allAnnotationDrawablesRendered(viewBounds, visibleKeys);
        }
        catch (final GLException | ArrayIndexOutOfBoundsException ignored) {
            SNTUtils.log("Upate view failed...");
            return false;
        }

    }

    private boolean allTreeDrawablesRendered(final BoundingBox3d viewBounds, final Set<String> selectedKeys) {
        for (final Entry<String, ShapeTree> entry : plottedTrees.entrySet()) {
            final Shape drawable = entry.getValue().get();
            if (drawable == null) return false;
            final BoundingBox3d bounds = drawable.getBounds();
            if (bounds == null || !viewBounds.contains(bounds)) return false;
            if (selectedKeys.contains(entry.getKey()) && !drawable.isDisplayed()) {
                drawable.setDisplayed(true);
                if (!drawable.isDisplayed()) return false;
            }
        }
        return true;
    }

    private boolean allAnnotationDrawablesRendered(final BoundingBox3d viewBounds, final Set<String> selectedKeys) {
        for (final Entry<String, Annotation3D> entry : plottedAnnotations.entrySet()) {
            final Drawable drawable = entry.getValue().getDrawable();
            if (drawable == null) return false;
            final BoundingBox3d bounds = drawable.getBounds();
            if (bounds == null || !viewBounds.contains(bounds)) return false;
            if (selectedKeys.contains(entry.getKey()) && !drawable.isDisplayed()) {
                drawable.setDisplayed(true);
                if (!drawable.isDisplayed()) return false;
            }
        }
        return true;
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
            if (frame != null && frame.managerPanel != null) {
                final Runnable r = () -> frame.managerPanel.setVisible(label, visible);
                if (SwingUtilities.isEventDispatchThread()) r.run();
                else SwingUtilities.invokeLater(r);
            }
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
        if (enabled) {
            // Release any constrained view mode (TOP/PROFILE) so that animation is not silently
            // ignored. The current viewpoint is preserved, only the constraint is removed
            if (chartExists() && chart.getView().getViewMode() != ViewPositionMode.FREE) {
                chart.getView().setViewPositionMode(ViewPositionMode.FREE);
                currentView = ViewMode.DEFAULT;
            }
            // Infer best rotation axis from current viewpoint if auto-detection
            // is enabled (i.e., no explicit axis was set by the caller)
            if (mouseController.getThread() instanceof CameraThreadControllerPlus) {
                ((CameraThreadControllerPlus) mouseController.getThread()).updateAxisIfAuto();
            }
            mouseController.startThreadController();
        }
        else mouseController.stopThreadController();
    }

    /**
     * Sets the rotation axis for the live animation (double-click spin).
     * Calling this method disables automatic axis detection.
     *
     * @param axis the axis of rotation (X, Y, or Z). null defaults to Z.
     * @see #setAutoDetectAnimationAxis(boolean)
     */
    public void setAnimationAxis(final RotationAxis axis) {
        if (mouseController != null && mouseController.getThread() instanceof CameraThreadControllerPlus) {
            ((CameraThreadControllerPlus) mouseController.getThread()).setRotationAxis(axis);
        }
    }

    /**
     * Gets the current rotation axis for the live animation.
     *
     * @return the current rotation axis, or Z if not set
     */
    public RotationAxis getAnimationAxis() {
        if (mouseController != null && mouseController.getThread() instanceof CameraThreadControllerPlus) {
            return ((CameraThreadControllerPlus) mouseController.getThread()).getRotationAxis();
        }
        return RotationAxis.Z;
    }

    /**
     * Enables or disables automatic detection of the best rotation axis based
     * on the current viewpoint. When enabled (the default), double-clicking to
     * start a live animation will infer the most natural axis by picking the
     * world axis most aligned with the camera's viewing direction. Calling
     * {@link #setAnimationAxis(RotationAxis)} disables auto-detection.
     *
     * @param auto whether to auto-detect the axis before each animation start
     * @see #setAnimationAxis(RotationAxis)
     */
    public void setAutoDetectAnimationAxis(final boolean auto) {
        if (mouseController != null && mouseController.getThread() instanceof CameraThreadControllerPlus) {
            ((CameraThreadControllerPlus) mouseController.getThread()).setAutoDetectAxis(auto);
        }
    }

    /**
     * Returns whether automatic rotation axis detection is enabled.
     *
     * @return true if auto-detection is active
     */
    public boolean isAutoDetectAnimationAxis() {
        if (mouseController != null && mouseController.getThread() instanceof CameraThreadControllerPlus) {
            return ((CameraThreadControllerPlus) mouseController.getThread()).isAutoDetectAxis();
        }
        return true;
    }

    /**
     * Sets the animation mode for both live (double-click) and recorded
     * rotations.
     *
     * @param mode the animation mode ({@link AnimationMode#FULL_ROTATION} or
     *             {@link AnimationMode#PING_PONG}). null defaults to
     *             FULL_ROTATION.
     */
    public void setAnimationMode(final AnimationMode mode) {
        if (mouseController != null && mouseController.getThread() instanceof CameraThreadControllerPlus) {
            ((CameraThreadControllerPlus) mouseController.getThread())
                    .setAnimationMode(mode == null ? AnimationMode.FULL_ROTATION : mode);
        }
    }

    /**
     * Gets the current animation mode.
     *
     * @return the current animation mode
     */
    public AnimationMode getAnimationMode() {
        if (mouseController != null && mouseController.getThread() instanceof CameraThreadControllerPlus) {
            return ((CameraThreadControllerPlus) mouseController.getThread()).getAnimationMode();
        }
        return AnimationMode.FULL_ROTATION;
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
        chart.setViewMode(viewMode);
    }

    /**
     * Sets the title of the Viewer's frame.
     * @param title the viewer's title. Ignored if Viewer's frame does not exist
     */
    public void setTitle(final String title) {
        if (frame == null) return;
        final Runnable r = () -> frame.setTitle(title);
        if (SwingUtilities.isEventDispatchThread()) r.run();
        else SwingUtilities.invokeLater(r);
    }

    /**
     * Renders the scene from a specified camera angle (script-friendly).
     *
     * @param viewMode the view mode (case-insensitive): "xy"; "xz"; "yz";
     *                 "perspective" or "overview"; "default" or "".
     */
    public void setViewMode(final String viewMode) {
        setViewMode(resolveViewMode(viewMode));
    }

    private ViewMode resolveViewMode(final String viewMode) {
        if (viewMode == null || viewMode.trim().isEmpty()) {
            return ViewMode.DEFAULT;
        }
        final String vMode = viewMode.toLowerCase();

        // if an axis label is specified use that instead
        final String[] axesLabels = getAxesLabels();
        final int axisLabelIndex = IntStream.range(0, axesLabels.length)
                .filter(i -> axesLabels[i] != null && axesLabels[i].toLowerCase().contains(vMode))
                .findFirst()
                .orElse(-1);
        if (axisLabelIndex == 0) return ViewMode.XY;
        if (axisLabelIndex == 1) return ViewMode.XZ;
        if (axisLabelIndex == 2) return ViewMode.YZ;

        // otherwise fallback to defaults
        if (vMode.contains("xz") || vMode.contains("side") || vMode.contains("sag")) { // sagittal kept for backwards compatibility
            return ViewMode.XZ;
        }
        if (vMode.contains("xy") || vMode.contains("top") || vMode.contains("cor")) { // coronal kept for backwards compatibility
            return ViewMode.XY;
        }
        if (vMode.contains("yz")) {
            return ViewMode.YZ;
        }
        if (vMode.contains("pers") || vMode.contains("ove")) {
            return ViewMode.PERSPECTIVE;
        }
        return ViewMode.DEFAULT;
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
        chart.overlayAnnotation.label = label;
    }

    /**
     * Sets the location for annotation labels
     *
     * @param x the x position of the label
     * @param y the y position of the label
     */
    public void setLabelLocation(final float x, final float y) {
        chart.overlayAnnotation.labelX = x;
        chart.overlayAnnotation.labelY = y;
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
        chart.overlayAnnotation.setFont(font, angle);
        chart.overlayAnnotation.setLabelColor(new java.awt.Color(color.getRed(), color
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
        boolean saved;
        try {
            saved = saveSnapshot(file);
        } catch (final IllegalArgumentException | IOException | GLException e) {
            SNTUtils.error("IOException", e);
            saved = false;
        }
        if (!isAnatomicalYUp() && currentView == ViewMode.YZ) {
            new Thread(() -> {
                // Legacy fallback: save a rotated snapshot for non-Y-up atlases
                // whose cartesian views don't reflect sensible anatomical views
                final ij.ImagePlus imp = sc.fiji.snt.util.ImpUtils.open(file.getAbsolutePath());
                if (imp != null) {
                    ImpUtils.rotate90(imp, "left");
                    ImpUtils.save(imp, file.getAbsolutePath().replace(".png", "_rotated.png"));
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

    /**
     * Retrieves the current scene as an image using a screen capture that
     * matches the on-screen rendering exactly (WYSIWYG). This bypasses the
     * OpenGL framebuffer readback, avoiding color discrepancies from
     * premultiplied alpha and display color management. Falls back to
     * {@link #snapshot()} when the viewer is not visible (e.g., offscreen
     * rendering or headless environments).
     *
     * @return the bitmap image of the current scene as displayed on screen
     */
    public ImagePlus snapshotWYSIWYG() {
        return chart.screenshotWYSIWYG();
    }

    /**
     * Retrieves the specified scene view as an image.
     *
     * @param viewMode the view mode (case-insensitive) ("xy", "yz", etc.). Anatomical axes ("sagittal",
     *                 "coronal", "transverse/horizontal") are also accepted when a reference brain is loaded.
     * @return the bitmap image of the scene view
     */
    public ImagePlus snapshot(final String viewMode) {

        // store settings to restore
        final ViewportMode prevViewPort = view.getCamera().getViewportMode();
        final Coord3d prevViewPoint = view.getViewPoint().clone();
        final String prevViewMode = currentView.description;

        final boolean isCCF = plottedObjs.containsKey(MESH_LABEL_ALLEN);
        final String vMode = (isCCF) ? AllenUtils.getCartesianPlane(viewMode) : viewMode;
        setViewMode((vMode != null) ? vMode : viewMode);
        final ImagePlus result = chart.screenshotImp();
        ImpUtils.crop(result, isDarkModeOn() ? 0 : 255);
        // With Y-up camera the views are already anatomically correct;
        // only apply legacy rotation for non-Y-up CCF rendering
        if (isCCF && !isAnatomicalYUp()) {
            switch (currentView) {
                case XZ -> ImpUtils.rotate90(result, "right");
                case YZ -> ImpUtils.rotate90(result, "left");
            }
        }
        result.setTitle(viewMode);
        setViewMode(prevViewMode);
        view.getCamera().setViewportMode(prevViewPort);
        view.setViewPoint(prevViewPoint);
        return result;
    }


    protected boolean saveSnapshot(final File file) throws IllegalArgumentException, IOException {
        if (!chartExists()) {
            throw new IllegalArgumentException("Viewer is not visible");
        }
        SNTUtils.log("Saving snapshot to " + file);
        if (SNTUtils.isDebugMode() && frame != null) {
            logSceneControls(false);
        }
        if (prefs.snapshotWYSIWYG) {
            final ImagePlus imp = chart.screenshotWYSIWYG();
            if (imp == null) {
                return false;
            }
            final String path = file.getAbsolutePath();
            ImpUtils.save(imp, (path.toLowerCase().endsWith(".png") ? path : path + ".png"));
            return true;
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
        objMesh.meshShadingMode = meshShadingPref;
        objMesh.backfaceCull = meshBackfaceCullPref;
        chart.add(objMesh.drawable, false); // this used to trigger a GLException when true?
        final String label = getUniqueLabel(plottedObjs, "Mesh", objMesh.label());
        plottedObjs.put(label, objMesh.drawable);
        addItemToManager(label);
        if (frame != null && frame.allenNavigator != null) {
            final Runnable r = () -> frame.allenNavigator.meshLoaded(label);
            if (SwingUtilities.isEventDispatchThread()) r.run();
            else SwingUtilities.invokeLater(r);
        }
        }
        if (viewUpdatesEnabled) validate();
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
            if (managerList != null) {
                final Runnable r = () -> managerList.addCheckBoxListSelectedValue(label, true);
                if (SwingUtilities.isEventDispatchThread()) r.run();
                else SwingUtilities.invokeLater(r);
            }
            return plottedObjs.get(label).objMesh;
        }
        OBJMesh objMesh;
        String[] labels = null;
        switch (label) {
            case MESH_LABEL_JFRC2018 -> {
                objMesh = VFBUtils.getRefBrain("jfrc2018");
                labels = VFBUtils.getXYZLabels();
            }
            case MESH_LABEL_JFRC2 -> {
                objMesh = VFBUtils.getRefBrain("jfrc2");
                labels = VFBUtils.getXYZLabels();
            }
            case MESH_LABEL_JFRC3 -> {
                objMesh = VFBUtils.getRefBrain("jfrc3");
                labels = VFBUtils.getXYZLabels();
            }
            case MESH_LABEL_FCWB -> {
                objMesh = VFBUtils.getRefBrain("fcwb");
                labels = VFBUtils.getXYZLabels();
            }
            case MESH_LABEL_L1 -> objMesh = VFBUtils.getMesh("VFB_00050000");
            case MESH_LABEL_L3 -> objMesh = VFBUtils.getMesh("VFB_00049000");
            case MESH_LABEL_VNS -> objMesh = VFBUtils.getMesh("VFB_00100000");
            case MESH_LABEL_ALLEN -> {
                objMesh = AllenUtils.getRootMesh(null);
                labels = AllenUtils.getXYZLabels();
                // Allen CCF: Y is Dorsal-Ventral (increasing ventrally),
                // so use (0,-1,0) to keep dorsal at the top of the screen
                sceneUpVector = new Coord3d(0, -1, 0);
                if (view != null) view.setUpVector(sceneUpVector);
            }
            case MESH_LABEL_ZEBRAFISH -> {
                objMesh = ZBAtlasUtils.getRefBrain();
                labels = ZBAtlasUtils.getXYZLabels();
            }
            default -> throw new IllegalArgumentException("Invalid option: " + label);
        }
        objMesh.setLabel(label);
        objMesh.drawable.setColor(getNonUserDefColor());
        if (labels != null) setAxesLabels(labels);
        if (addMesh(objMesh) && viewUpdatesEnabled) validate();
        return objMesh;
    }

    private static String getNormalizedBrainLabel(final String input) {
        return switch (input.toLowerCase()) {
            case "jrc2018", "jfrc2018", "jfrc 2018", "jfrctemplate2018" -> MESH_LABEL_JFRC2018;
            case "jfrc2", "jfrc2010", "jfrctemplate2010", "vfb" -> MESH_LABEL_JFRC2;
            case "jfrc3", "jfrc2013", "jfrctemplate2013" -> MESH_LABEL_JFRC3;
            case "fcwb", "flycircuit" -> MESH_LABEL_FCWB;
            case "l1" -> MESH_LABEL_L1;
            case "l3" -> MESH_LABEL_L3;
            case "vns" -> MESH_LABEL_VNS;
            case "allen", "ccf", "allen ccf", "mouse" -> MESH_LABEL_ALLEN;
            case "zebrafish" -> MESH_LABEL_ZEBRAFISH;
            default -> null;
        };
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
                sb.append("Visible objects: ").append(visibleActors);
                sb.append("\n");
            }
            if (!hiddenActors.isEmpty()) {
                sb.append("Hidden  objects: ").append(hiddenActors);
                sb.append("\n");
            }
            System.out.println(sb);
            displayMsg("Scene details output to Console");
        } else {
            if (!visibleActors.isEmpty())
                recorder.recordComment("Visible objects: " + visibleActors);
            if (!hiddenActors.isEmpty())
                recorder.recordComment("Hidden objects: " + hiddenActors);
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

        public void setViewMode(final ViewMode newView) {
            // Set jzy3d view position mode from the enum
            getView().setViewPositionMode(newView.positionMode);

            // Reset pole-crossing state before setting a known orientation
            if (getView() instanceof ViewerFactory.AView)
                ((ViewerFactory.AView) getView()).resetPoleFlips();

            // When an anatomical up vector has been set (e.g. Y-up for Allen
            // CCF), restore it per-view. The XZ view looks along Y, which is
            // degenerate with Y-up, so it falls back to Z-up. For all other
            // views the stored scene up vector is applied.
            if (sceneUpVector != null) {
                final boolean yUp = sceneUpVector.x == 0 && sceneUpVector.y != 0 && sceneUpVector.z == 0;
                if (yUp && newView == ViewMode.XZ) {
                    getView().setUpVector(View.UP_VECTOR_Z);
                } else {
                    getView().setUpVector(sceneUpVector);
                }
            }

            // For constrained 2D modes (XY/XZ/YZ), jzy3d's computeCameraEye*
            // overrides the viewpoint. For FREE modes, set our viewpoint.
            if (newView == ViewMode.DEFAULT) {
                getView().setViewPoint(ViewMode.DefCoords.DEF);
            } else if (newView == ViewMode.PERSPECTIVE) {
                getView().setViewPoint(ViewMode.DefCoords.PERSPECTIVE);
            }
            // For XY/XZ/YZ: jzy3d handles eye position via the ViewPositionMode;
            // we still call setViewPoint to trigger shoot() and event notification
            getView().shoot();
            currentView = newView;
            if (frame != null) {
                frame.updateStatusMsg();
            }
        }

        /** Returns true if the current view mode allows free rotation. */
        boolean isRotationEnabled() {
            return getView().is3D();
        }

        ImagePlus screenshotImp() {
            final Object screen = screenshot();
            if (screen instanceof BufferedImage bi) {
                return new ImagePlus("RecViewerSnapshot", new ij.process.ColorProcessor(flattenAlpha(bi)));
            }
            if (chart.getCanvas() instanceof INativeCanvas) {
                final Renderer3d renderer = ((INativeCanvas) chart.getCanvas()).getRenderer();
                if (renderer instanceof AWTRenderer3d) {
                    return new ImagePlus("RecViewerSnapshot",
                            new ij.process.ColorProcessor(flattenAlpha(((AWTRenderer3d) renderer).getLastScreenshotImage())));
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

        /**
         * Captures the canvas exactly as it appears on screen using
         * {@link java.awt.Robot}, bypassing the GL framebuffer readback
         * pipeline entirely. This avoids premultiplied-alpha and color
         * management discrepancies. Falls back to {@link #screenshotImp()}
         * when the canvas is not showing on screen (e.g., headless/offscreen).
         */
        ImagePlus screenshotWYSIWYG() {
            final Component c = (Component) chart.getCanvas();
            if (c.isShowing()) {
                try {
                    final java.awt.Point loc = c.getLocationOnScreen();
                    final java.awt.Rectangle rect = new java.awt.Rectangle(
                            loc.x, loc.y, c.getWidth(), c.getHeight());
                    final BufferedImage capture = new java.awt.Robot().createScreenCapture(rect);
                    return ImpUtils.fromBufferedImage("RecViewerSnapshot", capture);
                } catch (final java.awt.AWTException | SecurityException ignored) {
                    // fall through to GL-based screenshot
                }
            }
            return screenshotImp();
        }

        /**
         * Strips the alpha channel from a premultiplied-alpha image, keeping the
         * raw premultiplied RGB values unchanged. jzy3d's AWTRenderer3d uses
         * AWTGLReadBufferUtil with alpha=true, producing TYPE_INT_ARGB_PRE
         * images. The screen compositor displays these by sending the
         * premultiplied RGB directly to the display, ignoring alpha. We
         * replicate that by accessing the underlying DataBuffer directly
         * (bypassing getRGB()'s automatic un-premultiplication) and copying the
         * raw pixel data with alpha masked off.
         */
        private static BufferedImage flattenAlpha(final BufferedImage img) {
            if (img == null || img.getType() != BufferedImage.TYPE_INT_ARGB_PRE)
                return img;
            final int w = img.getWidth();
            final int h = img.getHeight();
            // Access raw premultiplied ARGB data directly from the raster,
            // avoiding ColorModel conversion that getRGB() performs
            final int[] srcPixels = ((java.awt.image.DataBufferInt) img.getRaster().getDataBuffer()).getData();
            final BufferedImage rgb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            final int[] dstPixels = ((java.awt.image.DataBufferInt) rgb.getRaster().getDataBuffer()).getData();
            for (int i = 0; i < srcPixels.length; i++)
                dstPixels[i] = srcPixels[i] & 0x00FFFFFF;
            return rgb;
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
            GuiUtils.setRenderingHints(graphic);
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

    private class ViewerFrame extends FrameSwing implements IFrame {

        private static final long serialVersionUID = 1L;
        private static final int DEF_WIDTH = 800;
        private static final int DEF_HEIGHT = 600;
        private final String STATUS_DEF_UNLOCKED;
        private final String STATUS_DEF_LOCKED;

        private AChart chart;
        private Component canvas;
        private JSplitPane splitPane;
        private LightController lightController;
        private AllenCCFNavigator allenNavigator;
        private ManagerPanel managerPanel;
        private int savedDividerLocation = -1;
        private JLabel status;
        private boolean managerVisible;
        private boolean statusVisible = true;

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
            setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            GuiUtils.setLookAndFeel();
            GuiUtils.removeIcon(this);
            final String title = (chart.viewer.isSNTInstance()) ? " (SNT)" : " ("+ chart.viewer.getID() + ")";
            STATUS_DEF_UNLOCKED = (includeManager)
                    ? "Press H (or F1) for Help. Press " + GuiUtils.ctrlKey() + "+Shift+P for Command Palette..."
                    : "Press H (or F1) for Help...";
            STATUS_DEF_LOCKED = "Rotation disabled: Use Ctrl+click to change view mode";
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
                chart.viewer.managerList.selectAll();
                // Dispatch scene shortcut keys to keyController when focus is on
                // the manager panel's children (e.g., managerList). A JDialog would
                // propagate events from children; an embedded JPanel does not.
                // We only intercept single-letter shortcuts and suppress the
                // subsequent KEY_TYPED so JIDE Searchable doesn't trigger.
                // Arrow keys, Escape, and other navigation keys are left to the
                // focused component (e.g., JList scrolling, search bar dismiss).
                final boolean[] lastPressConsumed = {false};
                KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
                    if (!ViewerFrame.this.isActive()) return false;
                    final Component focused = e.getComponent();
                    // Only intercept if focus is inside this frame (not in owned
                    // dialogs like AllenCCFNavigator, LightController, etc.)
                    if (focused == null || SwingUtilities.getWindowAncestor(focused) != ViewerFrame.this)
                        return false;
                    // Skip if canvas has focus (has its own keyController),
                    // or focus is on a text component (let user type normally),
                    // or focus is on a button (let activation work normally)
                    if (focused == canvas
                            || focused instanceof javax.swing.text.JTextComponent
                            || focused instanceof javax.swing.AbstractButton)
                        return false;
                    if (e.getID() == KeyEvent.KEY_PRESSED) {
                        final int code = e.getKeyCode();
                        // Let arrow keys, Escape, Enter, Tab, and Space pass through
                        // to the focused component for normal Swing interaction
                        if (code == KeyEvent.VK_UP || code == KeyEvent.VK_DOWN
                                || code == KeyEvent.VK_LEFT || code == KeyEvent.VK_RIGHT
                                || code == KeyEvent.VK_ESCAPE || code == KeyEvent.VK_ENTER
                                || code == KeyEvent.VK_TAB || code == KeyEvent.VK_SPACE)
                            return false;
                        keyController.keyPressed(e);
                        lastPressConsumed[0] = e.isConsumed();
                        return lastPressConsumed[0];
                    } else if (e.getID() == KeyEvent.KEY_TYPED && lastPressConsumed[0]) {
                        lastPressConsumed[0] = false;
                        return true; // suppress KEY_TYPED so Searchable doesn't trigger
                    }
                    return false;
                });
            }
            if (gUtils != null) {
                gUtils.setParent(this);
            }
            toFront();
        }

        /** Applies the current theme colors (derived from the chart background) to the status bar. */
        void applyStatusColors(final Color bg, final Color fg) {
            final java.awt.Color awtBg = toAWTColor(bg);
            final java.awt.Color awtFg = toAWTColor(fg);
            if (status.getParent() != null)
                status.getParent().setBackground(awtBg);
            status.setBackground(awtBg);
            status.setForeground(awtFg);
        }

        void updateStatusMsg() {
            final boolean locked = !chart.isRotationEnabled();
            SwingUtilities.invokeLater(() -> {
                status.setIcon(IconFactory.menuIcon(
                        locked ? GLYPH.LOCK : GLYPH.LOCK_OPEN, status.getForeground()));
                status.setToolTipText(locked
                        ? "Rotation locked (constrained view)"
                        : "Rotation unlocked (free view)");
                status.setText((locked) ?
                        currentView.description + " View | " + STATUS_DEF_LOCKED :
                        STATUS_DEF_UNLOCKED);
            });
        }

        public void replaceCurrentChart(final AChart chart) {
            this.chart = chart;
            canvas = (Component) chart.getCanvas();
            if (splitPane != null) {
                final JPanel leftPanel = (JPanel) splitPane.getLeftComponent();
                leftPanel.removeAll();
                leftPanel.add(canvas, BorderLayout.CENTER);
                leftPanel.add(status, BorderLayout.SOUTH);
            } else {
                removeAll();
                add(canvas, BorderLayout.CENTER);
                add(status, BorderLayout.SOUTH);
            }
            revalidate();
            repaint();
        }

        private void initManager() {
            managerPanel = new ManagerPanel();
            splitPane.setRightComponent(managerPanel);
            managerList.addKeyListener(getCmdFinderKeyAdapter());
            chart.getCanvas().addKeyController(getCmdFinderKeyAdapter());
            managerVisible = true;
        }

        boolean hasManager() {
            return managerPanel != null;
        }

        boolean isManagerVisible() {
            return managerVisible;
        }

        private int defaultDividerSize() {
            return UIManager.getInt("SplitPane.dividerSize");
        }

        void showManagerPanel() {
            if (managerPanel == null || managerVisible) return;
            managerPanel.setVisible(true);
            splitPane.setDividerSize(defaultDividerSize());
            if (savedDividerLocation > 0 && savedDividerLocation < splitPane.getWidth() - 50)
                splitPane.setDividerLocation(savedDividerLocation);
            else
                splitPane.setDividerLocation(splitPane.getWidth() - managerPanel.getPreferredSize().width);
            managerVisible = true;
        }

        void hideManagerPanel() {
            if (managerPanel == null || !managerVisible) return;
            savedDividerLocation = splitPane.getDividerLocation();
            managerPanel.setVisible(false);
            splitPane.setDividerSize(0);
            splitPane.setDividerLocation(1.0d);
            managerVisible = false;
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
         * @see org.jzy3d.bridge.swing.FrameSwing#initialize(org.jzy3d.chart.Chart,
         * org.jzy3d.maths.Rectangle, java.lang.String)
         */
        @Override
        public void initialize(final Chart chart, final Rectangle bounds, final String title) {
            this.chart = (AChart)chart;
            canvas = (Component) chart.getCanvas();
            setTitle(title);
            final BorderLayout layout = new BorderLayout();
            setLayout(layout);
            status = new JLabel(STATUS_DEF_UNLOCKED);
            status.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 8, 0, 0));
            status.setFocusable(false);
            status.setBackground(toAWTColor(chart.view().getBackgroundColor()));
            status.setForeground(toAWTColor(chart.view().getBackgroundColor().negative()));
            if (managerList != null) {
                if (splitPane == null) { // Interactive mode: use split pane for embedded manager panel
                   splitPane = GuiUtils.SplitPanes.nonDraggableRightSplitPane();
                }
                final JPanel leftPanel = new JPanel(new BorderLayout());
                leftPanel.setBackground(status.getBackground());
                leftPanel.add(canvas, BorderLayout.CENTER);
                leftPanel.add(status, BorderLayout.SOUTH);
                splitPane.setLeftComponent(leftPanel);
                // Create and attach manager panel before pack() so the frame
                // is sized correctly on first display, avoiding flicker
                initManager();
                add(splitPane, BorderLayout.CENTER);
            } else {
                // Kiosk/non-interactive mode: canvas and status bar directly in frame
                add(canvas, BorderLayout.CENTER);
                add(status, BorderLayout.SOUTH);
            }
            setBackground(status.getBackground());
            final int panelWidth = (managerPanel != null)
                    ? managerPanel.getMinimumSize().width + splitPane.getDividerSize() : 0;
            canvas.setPreferredSize(new Dimension(bounds.width, bounds.height));
            setPreferredSize(new Dimension(bounds.width + panelWidth, bounds.height + status.getPreferredSize().height));
            pack();
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
            if (splitPane != null) {
                splitPane.removeAll();
                splitPane = null;
            }
            canvas = null;
            if (managerPanel != null) {
                managerPanel.setDebuggerEnabled(false);
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
         * @see org.jzy3d.bridge.swing.FrameSwing#initialize(org.jzy3d.chart.Chart, org.jzy3d.maths.Rectangle, java.lang.String, java.lang.String)
         */
        @Override
        public void initialize(final Chart chart, final Rectangle bounds,
                               final String title, final String message)
        {
            initialize(chart, bounds, title + message);
        }

        void resizeScene(final GuiUtils guiUtils) {
            // Prompt user to resize the scene (canvas) area, not the entire frame.
            // After the canvas is resized, the frame adjusts to accommodate it.
            final Dimension canvasSize = canvas.getSize();
            final int extraW = getWidth() - canvasSize.width;
            final int extraH = getHeight() - canvasSize.height;
            guiUtils().adjustComponentThroughPrompt(canvas);
            final Dimension newCanvasSize = canvas.getSize();
            if (newCanvasSize.width != canvasSize.width || newCanvasSize.height != canvasSize.height) {
                canvas.setPreferredSize(newCanvasSize);
                setSize(newCanvasSize.width + extraW, newCanvasSize.height + extraH);
            }
        }

        void exitFullScreen() {
            if (isFullScreen) {
                setExtendedState(JFrame.NORMAL);
                setSize(dim);
                setLocation(loc);
                setVisible(true);
                if (managerPanel != null && managerVisible) showManagerPanel();
                if (lightController != null) lightController.setVisible(true);
                if (allenNavigator != null) allenNavigator.dialog.setVisible(true);
                status.setVisible(statusVisible);
                isFullScreen = false;
            }
        }

        void enterFullScreen() {
            if (!isFullScreen) {
                dim = frame.getSize();
                loc = frame.getLocation();
                // remember visibility but don't update the flag so exitFullScreen can restore
                if (managerPanel != null && managerVisible) {
                    savedDividerLocation = splitPane.getDividerLocation();
                    managerPanel.setVisible(false);
                    splitPane.setDividerSize(0);
                    splitPane.setDividerLocation(1.0d);
                    // leave managerVisible = true so exitFullScreen restores it
                }
                if (lightController != null) lightController.setVisible(false);
                if (allenNavigator != null) allenNavigator.dialog.setVisible(false);
                statusVisible = status.isVisible();
                status.setVisible(false);
                setExtendedState(JFrame.MAXIMIZED_BOTH);
                isFullScreen = true;
                displayBanner("Entered Full Screen. Press Shift+F (or \"Esc\") to exit...");
            }
        }

        @Override
        public void setVisible(final boolean b) {
            SNTUtils.setIsLoading(false);
            super.setVisible(b);
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
            final boolean hasManager = frame.hasManager();
            final boolean wasManagerVisible = hasManager && frame.isManagerVisible();
            final boolean darkMode = isDarkModeOn();
            frame.dispose();
            if (hasManager) {
                initManagerList();
            }
            show((int) dim.getWidth(), (int) dim.getHeight(), gConfiguration, true);
            setEnableDarkMode(darkMode);
            if (hasManager && !wasManagerVisible) {
                frame.hideManagerPanel();
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
        protected boolean snapshotWYSIWYG;

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
            snapshotWYSIWYG = (tp.prefService != null)
                    && tp.prefService.getBoolean(RecViewerPrefsCmd.class, "snapshotWYSIWYG",
                    RecViewerPrefsCmd.DEF_SNAPSHOT_WYSIWYG);
        }

        private float getSnapshotRotationAngle() {
            return tp.prefService.getFloat(RecViewerPrefsCmd.class, "rotationAngle",
                    RecViewerPrefsCmd.DEF_ROTATION_ANGLE);
        }

        private AnimationMode getAnimationModePref() {
            if (tp == null || tp.prefService == null) return AnimationMode.FULL_ROTATION;
            final String mode = tp.prefService.get(RecViewerPrefsCmd.class,
                    "animationMode", RecViewerPrefsCmd.DEF_ANIMATION_MODE);
            return "Ping-pong".equals(mode) ? AnimationMode.PING_PONG : AnimationMode.FULL_ROTATION;
        }

        private void setAnimationModePref(final AnimationMode mode) {
            if (tp == null || tp.prefService == null) return;
            tp.prefService.put(RecViewerPrefsCmd.class, "animationMode",
                    mode == AnimationMode.PING_PONG ? "Ping-pong" : "Full Rotation");
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
            return switch (getControlsSensitivity()) {
                case "Highest" -> PAN.HIGHEST.step;
                case "Hight" -> PAN.HIGH.step;
                case "Medium" -> PAN.MEDIUM.step;
                case "Low" -> PAN.LOW.step;
                default -> PAN.DEF_PAN_STEP;
            };
        }

        private float getZoomStep() {
            return switch (getControlsSensitivity()) {
                case "Highest" -> ZOOM_STEPS[0];
                case "Hight" -> ZOOM_STEPS[1];
                case "Medium" -> ZOOM_STEPS[2];
                case "Low" -> ZOOM_STEPS[3];
                default -> DEF_ZOOM_STEP;
            };
        }

        private double getRotationStep() {
            return switch (getControlsSensitivity()) {
                case "Highest" -> ROTATION_STEPS[0];
                case "Hight" -> ROTATION_STEPS[1];
                case "Medium" -> ROTATION_STEPS[2];
                case "Low" -> ROTATION_STEPS[3];
                default -> DEF_ROTATION_STEP;
            };
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
            final String[] result = guiUtils().getStrings(title, labels.toArray(new String[0]),
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
                guiUtils().error("Invalid parameter(s).");
                ex.printStackTrace();
            }
            return false;
        }

        Integer getPromptTransparency(final String title) {
            final double def = Double.parseDouble(prefs.getGuiPref("aTransparency", "50"));
            final Integer t = guiUtils().getPercentage("Transparency (%)", title + " Transparency", (int)def);
            if (t == null)
                return null;
            if (t < 0 || t > 100) {
                guiUtils().error("Invalid transparency value: Only [0, 100] accepted.");
                return null;
            }
            prefs.setGuiPref("aTransparency", "" + t);
            return t;
        }

        ColorRGB getPromptColor(final String title) {
            final ColorRGB def = ColorRGB.fromHTMLColor(prefs.getGuiPref("aColor", "red"));
            final ColorRGB res = guiUtils().getColorRGB(title, def, (String[])null);
            if (res != null)
                prefs.setGuiPref("aColor", res.toHTMLColor());
            return res;
        }

        String[] getPromptGradient() {
            final Map<String, List<String>> map = new LinkedHashMap<>();
            map.put("Color gradient ", Annotation3D.COLORMAPS);
            map.put("Gradient axis  ", List.of("X", "Y", "Z"));
            final String[] res = guiUtils().getStrings("Color Gradient", map,
                    prefs.getGuiPref("aGradient", "hotcold"), prefs.getGuiPref("aAxis", "Z"));
            if (res != null) {
                prefs.setGuiPref("aGradient", res[0]);
                prefs.setGuiPref("aAxis", res[1]);
            }
            return res;
        }

        List<String> getPromptPlaneAxes(final boolean includeSomaChoices) {
            final List<String> res = guiUtils().getMultipleChoices("Cross-section Plane Axis...",
                    (includeSomaChoices)
                            ? new String[] { "X (midplane)", "Y (midplane)", "Z (midplane)", "X (at root/soma)", "Y (at root/soma)", "Z (at root/soma)" }
                            : new String[] { "X (midplane)", "Y (midplane)", "Z (midplane)" },
                    prefs.getGuiPref("pAxis", "Z (midplane)"));
            if (res == null)
                return null;
            prefs.setGuiPref("pAxis", res.getFirst());
            return res;
        }

        static PointInImage[] getPlane(final BoundingBox3d bounds, final String axis) {
            PointInImage p1;
            PointInImage p2 = switch (axis.split(" ")[0].toLowerCase()) {
                case "x" -> {
                    p1 = new PointInImage((bounds.getXmin() + bounds.getXmax()) / 2, bounds.getYmin(), bounds.getZmin());
                    yield new PointInImage((bounds.getXmin() + bounds.getXmax()) / 2, bounds.getYmax(), bounds.getZmax());
                }
                case "y" -> {
                    p1 = new PointInImage(bounds.getXmin(), (bounds.getYmin() + bounds.getYmax()) / 2, bounds.getZmin());
                    yield new PointInImage(bounds.getXmax(), (bounds.getYmin() + bounds.getYmax()) / 2, bounds.getZmax());
                }
                default -> {
                    p1 = new PointInImage(bounds.getXmin(), bounds.getYmin(), (bounds.getZmin() + bounds.getZmax()) / 2);
                    yield new PointInImage(bounds.getXmax(), bounds.getYmax(), (bounds.getZmin() + bounds.getZmax()) / 2);
                }
            };
            return new PointInImage[] { p1, p2 };
        }

        PointInImage[] getSomaPlane(final BoundingBox3d bounds, final Tree tree, final String axis) {
            PointInImage p1;
            PointInImage p2;
            final PointInImage root = tree.getRoot();
            p2 = switch (axis.split(" ")[0].toLowerCase()) {
                case "x" -> {
                    p1 = new PointInImage(root.x, bounds.getYmin(), bounds.getZmin());
                    yield new PointInImage(root.x, bounds.getYmax(), bounds.getZmax());
                }
                case "y" -> {
                    p1 = new PointInImage(bounds.getXmin(), root.y, bounds.getZmin());
                    yield new PointInImage(bounds.getXmax(), root.y, bounds.getZmax());
                }
                default -> {
                    p1 = new PointInImage(bounds.getXmin(), bounds.getYmin(), root.z);
                    yield new PointInImage(bounds.getXmax(), bounds.getYmax(), root.z);
                }
            };
            return new PointInImage[] { p1, p2 };
        }

    }

    /**
     * Returns a reference to control panel.
     *
     * @return the ManagerPanel associated with this Viewer, or null if the 'RV
     *         Controls' dialog is not being displayed.
     */
    public ManagerPanel getManagerPanel() {
        return (frame == null) ? null : frame.managerPanel;
    }

    /**
     * Gets the script recorder for this viewer, optionally creating one if needed.
     *
     * @param createIfNeeded if true, creates a new recorder if one doesn't exist;
     *                      if false, returns null when no recorder exists
     * @return the ScriptRecorder instance, or null if none exists and
     *         createIfNeeded is false
     */
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
        private SNTTable table;
        private JCheckBoxMenuItem debugCheckBox;
        private final SNTSearchableBar searchableBar;
        private final ProgressBar progressBar;
        private final GuiUtils mgrGuiUtils = new GuiUtils(this);
        private Debugger debugger;
        private boolean disableActions;

        /* Convenience factory for menu items backed by an Action with an icon. */
        private JMenuItem menuItem(final Action action, final GLYPH glyph) {
            final JMenuItem mi = new JMenuItem(action);
            if (glyph != null) mi.setIcon(IconFactory.menuIcon(glyph));
            return mi;
        }

        /* Convenience factory for menu items with a label, icon, and action listener. */
        private JMenuItem menuItem(final String label, final GLYPH glyph, final ActionListener listener) {
            final JMenuItem mi = new JMenuItem(label);
            if (glyph != null) mi.setIcon(IconFactory.menuIcon(glyph));
            mi.addActionListener(listener);
            return mi;
        }

        private ManagerPanel() {
            super();
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            searchableBar = new SNTSearchableBar(new ListSearchable(managerList));
            searchableBar.setGuiUtils(mgrGuiUtils);
            searchableBar.setVisibleButtons(//SNTSearchableBar.SHOW_CLOSE |
                    SNTSearchableBar.SHOW_NAVIGATION | SNTSearchableBar.SHOW_HIGHLIGHTS | SNTSearchableBar.SHOW_STATUS);
            setFixedHeight(searchableBar);
            searchableBar.setVisible(false);
            searchableBar.setInstaller(new SearchableBar.Installer() {

                @Override
                public void openSearchBar(final SearchableBar searchableBar) {
                    searchableBar.setVisible(true);
                    searchableBar.focusSearchField();
                }

                @Override
                public void closeSearchBar(final SearchableBar searchableBar) {
                    searchableBar.setVisible(false);
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
            fileDropWorker = new FileDropWorker(managerList, mgrGuiUtils);
        }

        private JCheckBoxMenuItem getDebugCheckBox() {
            debugCheckBox = GuiUtils.MenuItems.debugMode();
            debugCheckBox.setSelected(SNTUtils.isDebugMode() && debugger != null);
            debugCheckBox.setEnabled(!isSNTInstance());
            debugCheckBox.setMnemonic('d');
            debugCheckBox.addItemListener(e -> {
                final boolean debug = debugCheckBox.isSelected();
                if (isSNTInstance()) {
                    SNTUtils.getInstance().getUI().setEnableDebugMode(debug);
                } else {
                    SNTUtils.setDebugMode(debug);
                }
                if (debug) {
                    if (Objects.requireNonNull(ENGINE) == Engine.JOGL) {
                        logGLDetails();
                        setDebuggerEnabled(debug);
                    } else {
                        SNTUtils.log("Rendering engine: " + ENGINE);
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
            static final String FIT = "Fit To Visible Objects";
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
            static final String TOGGLE_CONTROL_PANEL= "Toggle Control Panel";
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
                    case ALL -> {
                        showPanelAsNeeded();
                        managerList.selectAll();
                    }
                    case AXES_TOGGLE -> {
                        if (!keyController.emptySceneMsg()) keyController.toggleAxes();
                    }
                    case FIND -> {
                        showPanelAsNeeded();
                        if (searchableBar.isShowing()) {
                            searchableBar.getInstaller().closeSearchBar(searchableBar);
                        } else {
                            searchableBar.getInstaller().openSearchBar(searchableBar);
                            searchableBar.focusSearchField();
                        }
                    }
                    case PROGRESS -> {
                        showPanelAsNeeded();
                        progressBar.setVisible(!progressBar.isShowing());
                    }
                    case ENTER_FULL_SCREEN -> frame.enterFullScreen();
                    case RESIZE -> frame.resizeScene(mgrGuiUtils);
                    case FIT -> fitToVisibleObjects(true, true);
                    case LOG_TO_RECORDER -> {
                        openRecorder(false);
                        logSceneControls(true);
                    }
                    case NONE -> {
                        showPanelAsNeeded();
                        managerList.clearSelection();
                    }
                    case REBUILD -> {
                        if (mgrGuiUtils.getConfirmation("Rebuild 3D Scene Completely?", "Force Rebuild")) {
                            rebuild();
                        }
                    }
                    case RELOAD -> {
                        if (!sceneIsOK()
                                && mgrGuiUtils.getConfirmation("Scene was reloaded but some objects have invalid attributes. "
                                + "Rebuild 3D Scene Completely?", "Rebuild Required")) {
                            rebuild();
                        } else {
                            displayMsg("Scene reloaded");
                        }
                    }
                    case RESET_VIEW -> keyController.resetView();
                    case SCENE_SHORTCUTS_LIST -> keyController.showHelp(true);
                    case SCENE_SHORTCUTS_NOTIFICATION -> keyController.showHelp(false);
                    case RECORDER -> openRecorder(true);
                    case SNAPSHOT_DISK -> keyController.saveScreenshot();
                    case SNAPSHOT_SHOW -> (prefs.snapshotWYSIWYG ? snapshotWYSIWYG() : snapshot()).show();
                    case SYNC -> {
                        try {
                            if (!syncPathManagerList())
                                rebuild();
                            displayMsg("Path Manager contents updated");
                        } catch (final IllegalArgumentException ex) {
                            mgrGuiUtils.error(ex.getMessage());
                        }
                    }
                    case TAG -> {
                        if (noLoadedItemsGuiError())
                            return;
                        final boolean all = managerList.isSelectionEmpty() && isSelectAllIfNoneSelected();
                        if (managerList.isSelectionEmpty() && !all) return;
                        final String tags = mgrGuiUtils.getString("Enter one or more tags (space or "
                                        + "comma- separated list) to be assigned to selected items. Tags encoding "
                                        + "a color (e.g., 'red', 'lightblue') will be use to highlight entries. "
                                        + "After dismissing this dialog:<ul>"
                                        + "<li>Double-click on an object to edit its tags</li>"
                                        + "<li>Double-click on '" + CheckBoxList.ALL_ENTRY
                                        + "' to add tags to the entire list</li></ul>",
                                "Add Tag(s)", "");
                        if (tags == null)
                            return; // user pressed cancel
                        managerList.applyTagToSelectedItems(tags, all);
                    }
                    case TOGGLE_DARK_MODE -> setEnableDarkMode(!isDarkModeOn());
                    case TOGGLE_CONTROL_PANEL -> toggleControlPanel();
                    default -> throw new IllegalArgumentException("Unrecognized action: " + name);
                }
            }

            private void run() {
                actionPerformed(new ActionEvent(this, ActionEvent.ACTION_PERFORMED, null) {
                    private static final long serialVersionUID = 1L;
                });
            }

            private void showPanelAsNeeded() {
                if (frame.hasManager() && !frame.isManagerVisible())
                    toggleControlPanel(); // e.g, if action called via cmdFinder
            }

            private void openRecorder(final boolean warnIfOpen) {
                if (getRecorder(false) != null) {
                    if (warnIfOpen) mgrGuiUtils.error("Script Recorder is already open.");
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
            // panel should not collapse buttons otherwise their icons gets scrambled
            setMinimumSize(new Dimension(buttonPanel.getPreferredSize().width, getMinimumSize().height));
            return buttonPanel;
        }

        private void setFixedHeight(final JComponent c) {
            // do not allow component to resize vertically
            c.setMaximumSize(new Dimension(c.getMaximumSize().width, (int) c.getPreferredSize().getHeight()));
        }

        private JButton menuButton(final GLYPH glyph, final JPopupMenu menu, final String tooltipMsg) {
            cmdFinder.register(menu, new ArrayList<>(Collections.singletonList(tooltipMsg)));
            final JButton button = new JButton(IconFactory.buttonIcon(glyph, 1.8f));
            button.setToolTipText(tooltipMsg);
            button.addActionListener(e -> menu.show(button, button.getWidth() / 2, button.getHeight() / 2));
            return button;
        }

        private JPopupMenu sceneMenu() {
            final JPopupMenu sceneMenu = new JPopupMenu();
            GuiUtils.addSeparator(sceneMenu, "View");
            sceneMenu.add(menuItem(new Action(Action.FIT, KeyEvent.VK_F, false, false), GLYPH.EXPAND));
            sceneMenu.add(zoomToSelectionMenuItem());
            sceneMenu.add(menuItem(new Action(Action.RESIZE), GLYPH.RESIZE));
            sceneMenu.add(menuItem(new Action(Action.ENTER_FULL_SCREEN, KeyEvent.VK_F, false, true), GLYPH.EXPAND_ARROWS2));

            GuiUtils.addSeparator(sceneMenu, "Appearance");
            sceneMenu.add(squarifyMenu()); // Aspect-ratio controls
            sceneMenu.add(axesMenu());
            sceneMenu.add(scaleBarMenu());
            sceneMenu.add(menuItem(new Action(Action.TOGGLE_DARK_MODE, KeyEvent.VK_D, false, false), GLYPH.SUN));

            GuiUtils.addSeparator(sceneMenu, "Scene");
            sceneMenu.add(menuItem(new Action(Action.RESET_VIEW, KeyEvent.VK_R, false, false), GLYPH.BROOM));
            sceneMenu.add(menuItem(new Action(Action.RELOAD, KeyEvent.VK_R, false, true), GLYPH.REDO));
            sceneMenu.add(menuItem(new Action(Action.REBUILD, KeyEvent.VK_R, true, true), GLYPH.RECYCLE));
            sceneMenu.add(menuItem("Wipe Scene...", GLYPH.DANGER, e -> wipeWithPrompt()));

            GuiUtils.addSeparator(sceneMenu, "Utilities");
            final JMenuItem sup = menuItem("Duplicate Scene", GLYPH.COPY, e -> {
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
                            mgrGuiUtils.error("There is not enough memory to complete command. See Console for details.");
                        } catch (NullPointerException | InterruptedException | ExecutionException e2) {
                            e2.printStackTrace();
                            mgrGuiUtils.error("Unfortunately an error occurred. See Console for details.");
                        } finally {
                            removeProgressLoad(-1);
                        }
                    }
                }
                addProgressLoad(-1);
                new DupWorker().execute();
            });
            sceneMenu.add(sup);
            final JMenuItem sync = menuItem(new Action(Action.SYNC, KeyEvent.VK_S, true, true), GLYPH.SYNC);
            sync.setEnabled(isSNTInstance());
            sceneMenu.add(sync);
            return sceneMenu;
        }

        private JMenuItem zoomToSelectionMenuItem() {
            final JMenuItem fitToSelection = new JMenuItem("Fit To Selection", IconFactory.menuIcon(GLYPH.CROSSHAIR));
            fitToSelection.addActionListener(e -> {
                List<?> selection = managerList.getSelectedValuesList();
                if (managerList.getSelectedIndex() == -1 || selection.isEmpty()) {
                    mgrGuiUtils.error("No items are currently selected.");
                    return;
                }
                if (selection.size() == 1 && CheckBoxList.ALL_ENTRY.equals(selection.getFirst())) {
                    selection.clear();
                    selection = IntStream.range(0, managerList.getModel().getSize())
                            .mapToObj(managerList.getModel()::getElementAt).collect(Collectors.toList());
                }
                zoomTo(selection.toArray(new Object[0]));
            });
            return fitToSelection;
        }

        private JMenu squarifyMenu() {
            final JMenu menu = new JMenu("Aspect Ratio/Scaling");
            menu.setIcon(IconFactory.menuIcon(GLYPH.EQUALS));
            final ButtonGroup cGroup = new ButtonGroup();
            final String[] axes = new String[] { "XY", "XZ", "YX", "YZ", "ZX", "ZY", "Default"};
            for (final String axis : axes) {
                final String label = axis.equals("Default") ? "Default" : "Isotropic " + axis;
                final JMenuItem jcbmi = new JCheckBoxMenuItem(label, axis.startsWith("Default"));
                cGroup.add(jcbmi);
                jcbmi.addItemListener(e -> squarify(axis));
                menu.add(jcbmi);
            }
            menu.addSeparator();
            final JMenuItem jcbmiFill = new JCheckBoxMenuItem("Stretch-to-Fill");
            jcbmiFill.setIcon(IconFactory.menuIcon(GLYPH.EXPAND_ARROWS1));
            jcbmiFill.addItemListener(e -> {
                final ViewportMode mode = (jcbmiFill.isSelected()) ? ViewportMode.STRETCH_TO_FILL
                        : ViewportMode.RECTANGLE_NO_STRETCH;
                view.getCamera().setViewportMode(mode);
            });
            menu.add(jcbmiFill);
            return menu;
        }

        private JMenu axesMenu() {
            final JMenu menu = new JMenu("Axes");
            menu.setIcon(IconFactory.menuIcon(GLYPH.CHART_LINE));
            menu.add(menuItem("Anatomical Mapping...", null, e -> runAxisMappingCmd()));
            final JMenuItem jmi = new JMenuItem("Axes Labels...");
            jmi.addActionListener(e -> {
                final String[] defaults = { view.getAxisLayout().getXAxisLabel(), view.getAxisLayout().getYAxisLabel(),
                        view.getAxisLayout().getZAxisLabel() };
                final String[] labels = mgrGuiUtils.getStrings("Axes Labels...",
                        new String[] { "X axis ", "Y axis ", "Z axis " }, defaults);
                if (labels != null)
                    setAxesLabels(labels);
            });
            menu.add(jmi);
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

        private void runAxisMappingCmd() {
            if (cmdService == null)
                SNTUtils.getContext().inject(Viewer3D.this);
            class AxisMappingWorker extends SwingWorker<Object, Object> {
                CommandModule cmdModule;

                @Override
                public Object doInBackground() {
                    try {
                        cmdModule = cmdService.run(CustomizeAxesCmd.class, true).get();
                    } catch (final InterruptedException | ExecutionException ignored) {
                        return null;
                    }
                    return null;
                }

                @Override
                @SuppressWarnings("unchecked")
                protected void done() {
                    if (cmdModule == null || cmdModule.isCanceled()) return;
                    final HashMap<String, String> outMap =
                            (HashMap<String, String>) cmdModule.getInput("outMap");
                    if (outMap == null) return;
                    applyAxisMapping(outMap);
                }
            }
            new AxisMappingWorker().execute();
        }

        private JMenu scaleBarMenu() {
            final JMenu menu = new JMenu("Scale Bar");
            menu.setIcon(IconFactory.menuIcon(GLYPH.RULER));
            final JCheckBoxMenuItem toggle = new JCheckBoxMenuItem("Display Scale Bar");
            toggle.addItemListener(e -> {
                chart.overlayAnnotation.scaleBarEnabled = toggle.isSelected();
                if (toggle.isSelected() && !chart.overlayAnnotation.scaleBarUnitSet)
                    mgrGuiUtils.tempMsg("Scale bar assumes " + chart.overlayAnnotation.scaleBarUnit
                            + ". Use Set Base Unit... to change.");
            });
            menu.add(toggle);
            final JMenuItem unitItem = menuItem("Set Base Unit...", GLYPH.PEN, e -> {
                final String[] choices = { "µm", "nm", "mm", "pixels" };
                final String choice = mgrGuiUtils.getChoice(
                        "Specify the base unit of the scene coordinates:",
                        "Scale Bar Unit", choices, chart.overlayAnnotation.scaleBarUnit);
                if (choice == null) return;
                chart.overlayAnnotation.scaleBarUnit = choice;
                chart.overlayAnnotation.scaleBarUnitSet = true;
                switch (choice) {
                    case "nm" -> chart.overlayAnnotation.scaleBarToUm = 0.001;
                    case "mm" -> chart.overlayAnnotation.scaleBarToUm = 1000.0;
                    default -> chart.overlayAnnotation.scaleBarToUm = 1.0; // um, pixelw
                }
            });
            menu.add(unitItem);
            return menu;
        }

        private JPopupMenu popupMenu() {
            final JMenuItem renderIcons = new JCheckBoxMenuItem("Label Categories",
                    IconFactory.menuIcon(GLYPH.MARKER), managerList.renderer.iconVisible);
            renderIcons.addItemListener(e -> managerList.setIconsVisible(renderIcons.isSelected()));

            final JPopupMenu pMenu = new JPopupMenu();
            pMenu.add(selectSubMenu());
            pMenu.add(showSubMenu());
            pMenu.add(hideSubMenu());
            pMenu.add(zoomToSelectionMenuItem());
            pMenu.addSeparator();
            pMenu.add(menuItem(new Action(Action.TAG, KeyEvent.VK_T, true, true), GLYPH.TAG));
            pMenu.add(menuItem("Apply Scene-based Tags", GLYPH.COLOR, e -> {
                if (noLoadedItemsGuiError()) return;
                final boolean all = managerList.isSelectionEmpty() && isSelectAllIfNoneSelected();
                managerList.applyRenderedColorsToSelectedItems(all);
            }));
            pMenu.add(menuItem("Remove Tags...", GLYPH.BROOM, e -> {
                if (noLoadedItemsGuiError()) return;
                final boolean all = managerList.isSelectionEmpty() && isSelectAllIfNoneSelected();
                if (managerList.isSelectionEmpty() && !all) return;
                if (mgrGuiUtils.getConfirmation("Remove all tags from " + ((all) ? "all" : "selected") + " items?",
                        "Dispose All Tags?")) {
                    managerList.removeTagsFromSelectedItems(all);
                }
            }));
            pMenu.addSeparator();
            pMenu.add(renderIcons);
            pMenu.add(menuItem("Sort List", GLYPH.SORT, e -> {
                if (!noLoadedItemsGuiError()) managerList.model.sort();
            }));
            pMenu.addSeparator();
            pMenu.add(menuItem(new Action(Action.PROGRESS), GLYPH.SPINNER));
            pMenu.add(menuItem(new Action(Action.FIND, KeyEvent.VK_F, true, false), GLYPH.BINOCULARS));
            pMenu.addSeparator();
            pMenu.add(menuItem("Remove Selected...", GLYPH.TRASH, e -> {
                if (managerList.getSelectedIndex() == managerList.getCheckBoxListSelectionModel().getAllEntryIndex()) {
                    wipeWithPrompt();
                    return;
                }
                final List<?> selectedKeys = managerList.getSelectedValuesList();
                if (managerList.getSelectedIndex() == -1 || selectedKeys.isEmpty()) {
                    mgrGuiUtils.error("There are no selected entries.");
                    return;
                }
                if (mgrGuiUtils.getConfirmation("Remove selected item(s)?", "Confirm Deletion?")) {
                    managerList.model.setListenersEnabled(false);
                    selectedKeys.forEach(k -> {
                        if (k.equals(CheckBoxList.ALL_ENTRY))
                            return; // continue in lambda expression
                        final String[] labelAndManagerEntry = TagUtils.getUntaggedAndTaggedLabels(k.toString());
                        removeSceneObject(labelAndManagerEntry[0], labelAndManagerEntry[1]);
                    });
                    managerList.model.setListenersEnabled(true); // will call managerList.model.update();
                }
            }));
            cmdFinder.register(pMenu, new ArrayList<>(Collections.singletonList("Control Panel Contextual Menu")));
            return pMenu;
        }

        private JMenu selectSubMenu() {
            final JMenu menu = new JMenu("Select");
            menu.setIcon(IconFactory.menuIcon(GLYPH.POINTER));
            menu.add(new JMenuItem("Trees")).addActionListener(e -> selectRows(plottedTrees));
            menu.add(new JMenuItem("Meshes")).addActionListener(e -> selectRows(plottedObjs));
            menu.add(new JMenuItem("Annotations")).addActionListener(e -> selectRows(plottedAnnotations));
            menu.addSeparator();
            // Register keyboard shortcuts for select all/none
            new Action(Action.ALL, KeyEvent.VK_A, true, false);
            new Action(Action.NONE, KeyEvent.VK_A, true, true);
            final JMenuItem selectNone = new JMenuItem("None");
            selectNone.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_A,
                    Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | KeyEvent.SHIFT_DOWN_MASK));
            selectNone.addActionListener(e -> managerList.clearSelection());
            menu.add(selectNone);
            return menu;
        }

        private JMenu showSubMenu() {
            final JMenu menu = new JMenu("Show");
            menu.setIcon(IconFactory.menuIcon(GLYPH.EYE));
            menu.add(new JMenuItem("Only Trees")).addActionListener(e -> show(plottedTrees));
            menu.add(new JMenuItem("Only Meshes")).addActionListener(e -> show(plottedObjs));
            menu.add(new JMenuItem("Only Annotations")).addActionListener(e -> show(plottedAnnotations));
            menu.addSeparator();
            menu.add(new JMenuItem("Soma of Selected Trees")).addActionListener(e -> setSomasDisplayedOfSelectedTrees(true));
            menu.add(new JMenuItem("Bounding Box of Selected Meshes")).addActionListener(e -> setBoundingBoxDisplayedOfSelectedMeshes(true));
            menu.addSeparator();
            menu.add(new JMenuItem("Selected Items")).addActionListener(e -> displaySelectedObjects(true));
            return menu;
        }

        private JMenu hideSubMenu() {
            final JMenu menu = new JMenu("Hide");
            menu.setIcon(IconFactory.menuIcon(GLYPH.EYE_SLASH));
            menu.add(new JMenuItem("Trees")).addActionListener(e -> hide(plottedTrees));
            menu.add(new JMenuItem("Meshes")).addActionListener(e -> hide(plottedObjs));
            menu.add(new JMenuItem("Annotations")).addActionListener(e -> hide(plottedAnnotations));
            menu.addSeparator();
            menu.add(new JMenuItem("Soma of Selected Trees")).addActionListener(e -> setSomasDisplayedOfSelectedTrees(false));
            menu.add(new JMenuItem("Bounding Box of Selected Meshes")).addActionListener(e -> setBoundingBoxDisplayedOfSelectedMeshes(false));
            final JMenu hemiMenu = new JMenu("Hemisphere of Selected Meshes");
            //hemiMenu.setIcon(IconFactory.menuIcon(GLYPH.BRAIN));
            List.of("Left", "Right").forEach( label -> { // exclude "Both": Hiding full meshes is listed elsewhere
                hemiMenu.add(new JMenuItem(label)).addActionListener(e -> {
                    final List<String> keys = getSelectedMeshLabels();
                    if (keys != null) setHemisphereOfSelectedMeshes(keys, label);
                });
            });
            menu.add(hemiMenu);
            menu.addSeparator();
            menu.add(new JMenuItem("Selected Items")).addActionListener(e -> displaySelectedObjects(false));
            return menu;
        }

        private void wipeWithPrompt() {
            if (mgrGuiUtils.getConfirmation("Remove all items from scene? This action cannot be undone.", "Wipe Scene?"))
                wipeScene();
        }

        private boolean noLoadedItemsGuiError() {
            final boolean noItems = plottedTrees.isEmpty() && plottedObjs.isEmpty() && plottedAnnotations.isEmpty();
            if (noItems) {
                mgrGuiUtils.error("There are no loaded items.");
            }
            return noItems;
        }

        private void setSomasDisplayedOfSelectedTrees(final boolean display) {
            final List<String> keys = getSelectedTreeLabels();
            if (keys == null) return;
            setSomasDisplayed(keys, display);
            if (!display) return;
            // Collect trees whose soma is undefined (somaSubShape == null)
            final List<String> noSomaKeys = new ArrayList<>();
            for (final String k : keys) {
                final ShapeTree st = plottedTrees.get(k);
                if (st != null && st.somaSubShape == null)
                    noSomaKeys.add(k);
            }
            if (noSomaKeys.isEmpty()) return;
            final String msg = noSomaKeys.size() == 1
                    ? "1 of " + keys.size() + " trees has no soma defined. Highlight its root node instead?"
                    : noSomaKeys.size() + " of " + keys.size() + " trees have no soma defined. Highlight their root instead?";
            if (!mgrGuiUtils.getConfirmation(msg, "Missing Soma")) return;
            for (final String k : noSomaKeys) {
                final ShapeTree st = plottedTrees.get(k);
                final PointInImage root = st.tree.getRoot();
                if (root == null) continue;
                final Color arborColor = st.getArborColor();
                final ColorRGB color = (arborColor != null) ? toColorRGB(arborColor) : st.tree.getColor();
                final float treeThickness = (st.arborVBO == null) ? defThickness : st.arborVBO.getWidth();
                final float radius = ShapeTree.SOMA_SCALING_FACTOR * treeThickness;
                final String annotLabel = k + " [root]";
                final Annotation3D annot = annotatePoint(root, annotLabel, color.toString(), radius);
                annot.setColor(annot.getColor(), 30);
            }
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
            final int[] indices = new int[map.size()];
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
                for (int index : indices) {
                    if (display)
                        managerList.addCheckBoxListSelectedIndex(index);
                    else
                        managerList.removeCheckBoxListSelectedIndex(index);
                }
                managerList.setValueIsAdjusting(false);
            });

        }

        private void show(final Map<String, ?> map) {
            final int[] indices = new int[map.size()];
            int i = 0;
            for (final String k : map.keySet()) {
                indices[i++] = managerList.model.indexOf(k);
            }
            SwingUtilities.invokeLater(() -> {
                managerList.setValueIsAdjusting(true);
                managerList.setCheckBoxListSelectedIndices(indices);
                managerList.setValueIsAdjusting(false);
            });
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
            final int[] indices = map.keySet().stream()
                    .mapToInt(k -> managerList.model.indexOf(k))
                    .filter(idx -> idx >= 0)
                    .toArray();
            SwingUtilities.invokeLater(() -> {
                managerList.setValueIsAdjusting(true);
                for (final int idx : indices) {
                    managerList.removeCheckBoxListSelectedIndex(idx);
                }
                managerList.setValueIsAdjusting(false);
            });
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
                mgrGuiUtils.error(
                        "This command requires a single reconstruction to be selected.");
                return null;
            }
            return trees.getFirst();
        }

        private Tree getSingleSelectionTreeWithPromptForType() {
            Tree tree = getSingleSelectionTree();
            if (tree == null) return null;
            final Set<Integer> types = tree.getSWCTypes(false);
            if (types.size() == 1)
                return tree;
            final String compartment = mgrGuiUtils.getChoice("Compartment:", "Which Neuronal Processes?",
                    new String[] { "All", "Axon", "Dendrites" }, prefs.treeCompartmentChoice);
            if (compartment == null)
                return null;
            prefs.treeCompartmentChoice = compartment;
            if (!compartment.toLowerCase().contains("all")) {
                tree = tree.subTree(compartment);
                if (tree.isEmpty()) {
                    final String treeLabel = (tree.getLabel() == null) ? "Reconstruction" : tree.getLabel();
                    mgrGuiUtils.error(treeLabel + " does not contain processes tagged as \"" + compartment + "\".");
                    return null;
                }
            }
            return tree;
        }

        private List<Tree> getSelectedTrees() {
            return getSelectedTrees(!plottedTrees.isEmpty() && managerList.isSelectionEmpty() && isSelectAllIfNoneSelected());
        }

        private List<Tree> getSelectedTrees(final boolean promptForAllIfNone) {
            final List<String> keys = getSelectedKeys(plottedTrees, "reconstructions", promptForAllIfNone);
            if (keys == null) return null; // user pressed cancel on prompt
            if (keys.isEmpty()) { // a selection existed but it did not contain plottedTrees
                mgrGuiUtils.error("There are no selected reconstructions.");
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
            return getSelectedAnnotations(!plottedAnnotations.isEmpty() && managerList.isSelectionEmpty() && isSelectAllIfNoneSelected());
        }

        private List<Annotation3D> getSelectedAnnotations(final boolean allowAllIfNone) {
            final List<String> keys = getSelectedKeys(plottedAnnotations, "annotations", allowAllIfNone);
            if (keys == null) return null; // user pressed cancel on prompt
            if (keys.isEmpty()) { // a selection existed but it did not contain plottedTrees
                mgrGuiUtils.error("There are no selected annotations.");
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
            return getSelectedKeys(plottedTrees, "reconstructions", !plottedTrees.isEmpty() && managerList.isSelectionEmpty() && isSelectAllIfNoneSelected());
        }

        private List<String> getSelectedMeshLabels() {
            return getSelectedKeys(plottedObjs, "meshes", !plottedObjs.isEmpty() && managerList.isSelectionEmpty() && isSelectAllIfNoneSelected());
        }

        private List<String> getSelectedKeys(final Map<String, ?> map,
                                             final String mapDescriptor, final boolean allowAllIfNone)
        {
            if (map.isEmpty()) {
                mgrGuiUtils.error("There are no loaded " + mapDescriptor + ".");
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
                    || (selectedKeys.size() == 1 && CheckBoxList.ALL_ENTRY.toString().equals(selectedKeys.getFirst())))
                return allKeys;
            if (allowAllIfNone && selectedKeys.isEmpty()) {
                if (isSelectAllIfNoneSelected()) return allKeys;
                mgrGuiUtils.error("There are no selected " + mapDescriptor + ".");
                return null;
            }
            allKeys.retainAll(selectedKeys);
            return allKeys;
        }

        private boolean isSelectAllIfNoneSelected() {
            if (prefs.nagUserOnRetrieveAll) {
                final boolean[] options = mgrGuiUtils.getPersistentConfirmation("There are no items selected. "//
                        + "Run command on all eligible items?", "Extend to All If None Selected?");
                prefs.retrieveAllIfNoneSelected = options[0];
                prefs.nagUserOnRetrieveAll = !options[1];
            }
            return prefs.retrieveAllIfNoneSelected;
        }
        

        private void runMeasureOptionsAction() {
            if (measureUI != null) {
                measureUI.dispose();
                measureUI = null;
            }
            final List<Tree> trees = getSelectedTrees();
            if (trees == null) return;
            measureUI = new MeasureUI(trees);
            initTable();
            measureUI.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(final WindowEvent e) {
                    measureUI = null;
                }
            });
            measureUI.setTable(table);
            measureUI.setVisible(true);
        }

        private void runMeasureQuickAction() {
            final List<Tree> trees = getSelectedTrees();
            if (trees == null) return;
            initTable();
            trees.forEach(tree -> {
                final TreeStatistics tStats = new TreeStatistics(tree);
                tStats.setContext(SNTUtils.getContext());
                tStats.setTable(table);
                tStats.summarize(tree.getLabel(), true); // will display table
            });
        }

        private void runBranchPropertiesAnalysisAction() {
            final List<Tree> trees = getSelectedTrees();
            if (trees == null) return;
            final Map<String, Object> inputs = new HashMap<>();
            inputs.put("trees", trees);
            inputs.put("calledFromPathManagerUI", false);
            runCmd(DistributionBPCmd.class, inputs, CmdWorker.DO_NOTHING, false, true);
        }

        private void runCellPropertiesAnalysisAction() {
            final List<Tree> trees = getSelectedTrees();
            if (trees == null) return;
            final Map<String, Object> inputs = new HashMap<>();
            inputs.put("trees", trees);
            inputs.put("calledFromPathManagerUI", false);
            runCmd(DistributionCPCmd.class, inputs, CmdWorker.DO_NOTHING, false, true);
        }

        private void runConvexHullAction() {
            final List<Tree> trees = getSelectedTrees();
            if (trees == null) return;
            final Map<String, Object> inputs = new HashMap<>();
            inputs.put("trees", trees);
            inputs.put("calledFromRecViewerInstance", true);
            initTable();
            inputs.put("table", table);
            runCmd(ConvexHullCmd.class, inputs, CmdWorker.DO_NOTHING, true, true);
        }

        private void runDendrogramAction() {
            final Tree tree = getSingleSelectionTreeWithPromptForType();
            if (tree == null) return;
            final Map<String, Object> inputs = new HashMap<>();
            inputs.put("tree", tree);
            runCmd(GraphGeneratorCmd.class, inputs, CmdWorker.DO_NOTHING, false, true);
        }

        private void runPersistenceAnalysisAction() {
            final List<Tree> trees = getSelectedTrees();
            if (trees == null) return;
            final HashMap<String, Object> inputs = new HashMap<>();
            inputs.put("trees", trees);
            runCmd(PersistenceAnalyzerCmd.class, inputs, CmdWorker.DO_NOTHING, false, true);
        }

        private void runRootAngleAnalysisAction() {
            final List<Tree> trees = getSelectedTrees();
            if (trees == null) return;
            final HashMap<String, Object> inputs = new HashMap<>();
            inputs.put("trees", trees);
            runCmd(RootAngleAnalyzerCmd.class, inputs, CmdWorker.DO_NOTHING, true, true);
        }

        private void runShollAnalysisAction() {
            final List<Tree> trees = getSelectedTrees();
            if (trees == null) return;
            final Map<String, Object> input = new HashMap<>();
            if (trees.size() == 1) {
                input.put("snt", null);
                input.put("tree", trees.getFirst());
                runCmd(ShollAnalysisTreeCmd.class, input, CmdWorker.DO_NOTHING, false, false);
            } else {
                input.put("treeList", trees);
                runCmd(ShollAnalysisBulkTreeCmd.class, input, CmdWorker.DO_NOTHING, true, true);
            }
        }

        private void runStrahlerAnalysisAction() {
            final List<Tree> trees = getSelectedTrees();
            if (trees == null) return;
            final HashMap<String, Object> inputs = new HashMap<>();
            inputs.put("trees", trees);
            runCmd(StrahlerCmd.class, inputs, CmdWorker.DO_NOTHING, false, true);
        }

        private void runAnnotationGraphAction() {
            final List<Tree> trees = getSelectedTrees();
            if (trees == null) return;
            final Map<String, Object> inputs = new HashMap<>();
            inputs.put("trees", trees);
            runCmd(AnnotationGraphGeneratorCmd.class, inputs, CmdWorker.DO_NOTHING, false, true);
        }

        private void runBrainAreaAnalysisAction() {
            final Tree tree = getSingleSelectionTree();
            if (tree == null) return;
            final Map<String, Object> inputs = new HashMap<>();
            inputs.put("tree", tree);
            runCmd(BrainAnnotationCmd.class, inputs, CmdWorker.DO_NOTHING, false, true);
        }

        private JPopupMenu measureMenu() {
            final JPopupMenu measureMenu = new JPopupMenu();
            GuiUtils.addSeparator(measureMenu, "Tabular Results");
            JMenuItem mi = GuiUtils.MenuItems.measureOptions();
            mi.addActionListener(e -> runMeasureOptionsAction());
            measureMenu.add(mi);
            mi = GuiUtils.MenuItems.measureQuick();
            mi.addActionListener(e -> runMeasureQuickAction());
            measureMenu.add(mi);
            GuiUtils.addSeparator(measureMenu, "Distribution Analyses");
            final JMenuItem branchProps = menuItem("Branch Properties...", GLYPH.CHART,
                    e -> runBranchPropertiesAnalysisAction());
            branchProps.setToolTipText("Computes distributions of metrics from all the branches of selected trees");
            measureMenu.add(branchProps);
            final JMenuItem cellProps = menuItem("Cell Properties...", GLYPH.CHART,
                    e -> runCellPropertiesAnalysisAction());
            cellProps.setToolTipText("Computes distributions of metrics from individual cells");
            measureMenu.add(cellProps);
            GuiUtils.addSeparator(measureMenu, "Specialized Analyses");
            final JMenuItem convexHullMenuItem = GuiUtils.MenuItems.convexHull();
            convexHullMenuItem.addActionListener(e -> runConvexHullAction());
            measureMenu.add(convexHullMenuItem);
            mi = GuiUtils.MenuItems.createDendrogram();
            mi.addActionListener(e -> runDendrogramAction());
            measureMenu.add(mi);
            mi = GuiUtils.MenuItems.persistenceAnalysis();
            mi.addActionListener(e -> runPersistenceAnalysisAction());
            measureMenu.add(mi);
            mi = GuiUtils.MenuItems.rootAngleAnalysis();
            mi.addActionListener(e -> runRootAngleAnalysisAction());
            measureMenu.add(mi);

            mi = GuiUtils.MenuItems.shollAnalysis();
            mi.addActionListener(e -> runShollAnalysisAction());
            measureMenu.add(mi);
            mi = GuiUtils.MenuItems.strahlerAnalysis();
            mi.addActionListener(e -> runStrahlerAnalysisAction());
            measureMenu.add(mi);
            GuiUtils.addSeparator(measureMenu, "Atlas-based Analyses");
            mi = GuiUtils.MenuItems.createAnnotationGraph();
            mi.addActionListener(e -> runAnnotationGraphAction());
            measureMenu.add(mi);
            mi = GuiUtils.MenuItems.brainAreaAnalysis();
            mi.addActionListener(e -> runBrainAreaAnalysisAction());
            measureMenu.add(mi);
            GuiUtils.addSeparator(measureMenu, "Data Export");
            mi = GuiUtils.MenuItems.saveTablesAndPlots(GLYPH.SAVE);
            mi.addActionListener(e -> {
                runCmd(SaveMeasurementsCmd.class, null, CmdWorker.DO_NOTHING, false, true);
            });
            measureMenu.add(mi);
            //measureMenu.add(mgrGuiUtils.combineChartsMenuItem());
            return measureMenu;
        }

        private void initTable() {
            if (table == null) table =  new SNTTable();
        }

        private void customizeSelectedMeshesAllParametersAction() {
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
        }

        private void customizeSelectedMeshesColorAction() {
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
        }

        private void customizeSelectedMeshesTransparencyAction() {
            final List<String> keys = getSelectedMeshLabels();
            if (keys == null) return;
            final Integer t = new AnnotPrompt().getPromptTransparency("Mesh(es) Transparency...");
            if (t == null) {
                return; // user pressed cancel
            }
            final float fValue = 1 - (t.floatValue() / 100);
            for (final String label : keys) {
                plottedObjs.get(label).getColor().a = fValue;
            }
        }

        private void customizeSelectedMeshesHemisphereAction() {
            final List<String> keys = getSelectedMeshLabels();
            if (keys == null) return;
            final String[] choices = {"Both hemispheres", "Left hemisphere", "Right hemisphere"};
            // Pre-select current state if all selected meshes share the same hemisphere
            final String current = plottedObjs.get(keys.getFirst()).objMesh.getDisplayedHemisphere();
            final String def = "left".equals(current) ? choices[1] : "right".equals(current) ? choices[2] : choices[0];
            final String choice = mgrGuiUtils.getChoice("Display hemisphere:", "Mesh Hemisphere", choices, def);
            if (choice == null) return;
            final String hemi = choice.startsWith("Left") ? "left" : choice.startsWith("Right") ? "right" : "both";
            setHemisphereOfSelectedMeshes(keys, hemi);
        }

        private void setHemisphereOfSelectedMeshes(final List<String> keys, final String hemi) {
            keys.forEach(label -> plottedObjs.get(label).objMesh.setDisplayedHemisphere(hemi));
            if (chart != null && viewUpdatesEnabled) chart.render();
        }

        private void addCustomizeMeshCommands(final JPopupMenu menu) {
            GuiUtils.addSeparator(menu, "Customize");
            menu.add(menuItem("All Parameters...", GLYPH.SLIDERS, e -> customizeSelectedMeshesAllParametersAction()));
            menu.add(menuItem("Color...", GLYPH.COLOR, e -> customizeSelectedMeshesColorAction()));
            menu.add(menuItem("Transparency...", GLYPH.ADJUST, e -> customizeSelectedMeshesTransparencyAction()));
            menu.add(menuItem("Hemisphere...", GLYPH.BRAIN, e -> customizeSelectedMeshesHemisphereAction()));
        }

        private void customizeSelectedTreesAllParametersAction() {
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
                    assert cmdModule != null;
                    @SuppressWarnings("unchecked")
                    final HashMap<String, ColorRGBA> colorMap = (HashMap<String, ColorRGBA>) cmdModule.getInput("colorMap");
                    @SuppressWarnings("unchecked")
                    final HashMap<String, Double> sizeMap = (HashMap<String, Double>) cmdModule.getInput("sizeMap");
                    if (colorMap == null || sizeMap == null) {
                        mgrGuiUtils.error("Command execution failed.");
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
                        if (tree.arborVBO != null) {
                            if (dColor != null) tree.setArborColor(dColor, Path.SWC_DENDRITE);
                            if (aColor != null) tree.setArborColor(aColor, Path.SWC_AXON);
                            if (dSize > -1) tree.setThickness((float) dSize, Path.SWC_DENDRITE);
                            if (aSize > -1) tree.setThickness((float) aSize, Path.SWC_AXON);
                        }
                    }
                }
            }
            (new getTreeColors()).execute();
        }

        private void customizeSelectedTreesColorAction() {
            final List<String> keys = getSelectedTreeLabels();
            if (keys == null || !okToApplyColor(keys)) return;
            final ColorRGB c = new AnnotPrompt().getPromptColor("Tree(s) Color...");
            if (c != null)
                applyColorToPlottedTrees(keys, c);
        }

        private void applyColorMappingToSelectedTreesAction() {
            final List<String> keys = getSelectedTreeLabels();
            if (keys == null) return;
            final Map<String, Object> inputs = new HashMap<>();
            inputs.put("treeLabels", keys);
            runCmd(ColorMapReconstructionCmd.class, inputs, CmdWorker.DO_NOTHING, true, false);
        }

        private void removeSelectedTreeColorMappingsAction() {
            final List<Tree> trees = getSelectedTrees(true);
            if (trees != null) {
                trees.forEach(sc.fiji.snt.analysis.ColorMapper::unMap);
                displayMsg("Color mappings removed");
            }
        }

        private void assignUniqueColorsToSelectedTreesAction() {
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
        }

        private void setSelectedTreeThicknessAction() {
            final List<String> keys = getSelectedTreeLabels();
            if (keys == null) return;
            String msg = "<HTML><body><div style='width:500;'>" +
                    "Specify a thickness scaling factor (1–10) for the selected "
                    + keys.size() + " reconstruction(s). For line-based rendering (Simple Lines, "
                    + "Pseudo-Lighting), this sets the line width. For Shaded Tubes, this scales "
                    + "the node radii used to determine tube diameter.";
            if (isSNTInstance()) {
                msg += " This value only affects how Paths are displayed in the Reconstruction Viewer.";
            }
            final Double thickness = mgrGuiUtils.getDouble(msg, "Path Thickness",
                    getDefaultThickness(), 1d, 10d, "");
            if (thickness == null) {
                return; // user pressed cancel
            }
            if (Double.isNaN(thickness) || thickness <= 0) {
                mgrGuiUtils.error("Invalid thickness value.");
                return;
            }
            setRadiusScale(keys, thickness.floatValue());
            // jzy3d line widths are effectively capped below 10, so normalize the range
            final float normThick = (float) (((thickness-1) * 7 / 9 ) + 1);
            setTreeThickness(keys, normThick, null);
        }

        private void setSelectedSomaRadiusAction() {
            final List<String> keys = getSelectedTreeLabels();
            if (keys == null) return;
            String msg = "Please specify a constant radius (in physical units) to be applied to the soma(s) of selected reconstruction(s).";
            if (isSNTInstance()) {
                msg += " This value will only affect how Paths are displayed in the Reconstruction Viewer.";
            }
            final Double radius = mgrGuiUtils.getDouble(msg, "Soma radius", 10);
            if (radius == null) {
                return; // user pressed cancel
            }
            if (Double.isNaN(radius) || radius <= 0) {
                mgrGuiUtils.error("Invalid radius.");
                return;
            }
            setSomasDisplayed(keys, true);
            setSomaRadius(keys, radius.floatValue());
        }

        private void translateSelectedTreesAction() {
            final List<String> keys = getSelectedTreeLabels();
            if (keys == null) return;
            final Map<String, Object> inputs = new HashMap<>();
            inputs.put("treeLabels", keys);
            runCmd(TranslateReconstructionsCmd.class, inputs, CmdWorker.DO_NOTHING, true, false);
        }

        private void makeSelectedTreeUprightAction() {
            final List<Tree> trees = getSelectedTrees(false);
            if (trees == null || trees.isEmpty()) {
                return; // error already displayed
            }
            if (trees.size() > 1) {
                mgrGuiUtils.error("Please select a single reconstruction.");
                return;
            }
            final double angle = TreeUtils.computeUprightAngle(trees.getFirst(), false);
            if (Double.isNaN(angle)) {
                mgrGuiUtils.error("Could not compute upright angle.");
                return;
            }
            try {
                rotate((float) angle);
            } catch (final IllegalArgumentException e) {
                mgrGuiUtils.error(e.getMessage() + ".");
            }
        }

        private void addCustomizeTreeCommands(final JPopupMenu menu) {
            menu.add(menuItem("All Parameters...", GLYPH.SLIDERS, e -> customizeSelectedTreesAllParametersAction()));
            menu.add(menuItem("Color...", GLYPH.COLOR, e -> customizeSelectedTreesColorAction()));
            final JMenu ccMenu = new JMenu("Color Mapping");
            ccMenu.setIcon(IconFactory.menuIcon(GLYPH.COLOR2));
            menu.add(ccMenu);
            ccMenu.add(new JMenuItem("Apply Color Mapping...")).addActionListener(
                    e -> applyColorMappingToSelectedTreesAction());
            ccMenu.add(new JMenuItem("Remove Existing Color Mapping(s)")).addActionListener(
                    e -> removeSelectedTreeColorMappingsAction());
            ccMenu.addSeparator();
            ccMenu.add(new JMenuItem("Color Each Cell Uniquely")).addActionListener(
                    e -> assignUniqueColorsToSelectedTreesAction());
            menu.add(menuItem("Thickness...", GLYPH.DOTCIRCLE, e -> setSelectedTreeThicknessAction()));
            menu.add(menuItem("Soma radius...", GLYPH.CIRCLE, e -> setSelectedSomaRadiusAction()));
        }

        private JPopupMenu utilsMenu() {
            final JPopupMenu utilsMenu = new JPopupMenu();
            GuiUtils.addSeparator(utilsMenu, "Tools");
            utilsMenu.add(menuItem("Annotation Label...", GLYPH.PEN,
                    e -> runCmd(AddTextAnnotationCmd.class, null, CmdWorker.DO_NOTHING, true, false)));
            utilsMenu.add(legendMenu());
            utilsMenu.add(menuItem("Light Controls...", GLYPH.BULB, e -> frame.displayLightController(null)));
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
            GuiUtils.addSeparator(utilsMenu, "Capture");
            utilsMenu.add(recordAnimationMenuItem());
            utilsMenu.add(menuItem(new Action(Action.SNAPSHOT_SHOW, KeyEvent.VK_UNDEFINED, false, false), GLYPH.CAMERA));
            utilsMenu.add(menuItem(new Action(Action.SNAPSHOT_DISK, KeyEvent.VK_S, false, false), GLYPH.CAMERA));
            utilsMenu.add(menuItem("Show Snapshot Directory", GLYPH.OPEN_FOLDER,
                    e -> mgrGuiUtils.showDirectory(prefs.getSnapshotDir())));
            GuiUtils.addSeparator(utilsMenu, "Resources");
            new Action(Action.SCENE_SHORTCUTS_NOTIFICATION, KeyEvent.VK_H, false, false); // register alternative shortcut
            utilsMenu.add(menuItem(new Action(Action.SCENE_SHORTCUTS_LIST, KeyEvent.VK_F1, false, false), GLYPH.KEYBOARD));
            final JMenu helpMenu = GuiUtils.MenuItems.helpMenu(cmdFinder);
            helpMenu.setIcon(IconFactory.menuIcon(GLYPH.QUESTION));
            utilsMenu.add(helpMenu);
            return utilsMenu;
        }

        private JMenuItem recordAnimationMenuItem() {
            final JMenuItem mi = menuItem("Record Animation...", GLYPH.VIDEO, e -> {
                final String xLabel = view.getAxisLayout().getXAxisLabel();
                final String yLabel = view.getAxisLayout().getYAxisLabel();
                final String zLabel = view.getAxisLayout().getZAxisLabel();
                final String[] choices = {
                        "Z-axis" + anatomicalSuffix(zLabel, "Z") + " (azimuth sweep)",
                        "Y-axis" + anatomicalSuffix(yLabel, "Y") + " (elevation sweep from side)",
                        "X-axis" + anatomicalSuffix(xLabel, "X") + " (elevation sweep from front)"
                };
                final boolean pingPong = getAnimationMode() == AnimationMode.PING_PONG;
                final String modeLabel = pingPong ? "ping-pong" : "full";
                final String rAxis = mgrGuiUtils.getChoice(
                        "Record " + modeLabel + " rotation around which axis?\n"
                                + "(NB: Angle, duration, and animation mode can be adjusted in Preferences)",
                        "Record Animation", choices, choices[0]);
                if (rAxis == null) return;
                SwingUtilities.invokeLater(() -> {
                    displayMsg("Recording " + modeLabel + " animation...", 0);
                    new RecordWorker(RotationAxis.fromString(rAxis)).execute();
                });
            });
            return mi;
        }

        private static String anatomicalSuffix(final String label, final String defaultLabel) {
            if (label == null || label.isBlank() || label.equals(defaultLabel))
                return "";
            return ": " + label;
        }

        private JPopupMenu scriptingMenu() {
            final JPopupMenu scriptMenu = new JPopupMenu();
            GuiUtils.addSeparator(scriptMenu, "New Script");
            scriptMenu.add(menuItem(new Action(Action.RECORDER, KeyEvent.VK_OPEN_BRACKET, false, false), GLYPH.CODE));
            scriptMenu.add(menuItem(new Action(Action.LOG_TO_RECORDER, KeyEvent.VK_L, false, false), GLYPH.STREAM));
            GuiUtils.addSeparator(scriptMenu, "Resources");
            scriptMenu.add(GuiUtils.MenuItems.devResourceJavaAPI());
            scriptMenu.add(GuiUtils.MenuItems.devResourcePythonAPI());
            scriptMenu.add(GuiUtils.MenuItems.devResourceMain());
            return scriptMenu;
        }

        private JPopupMenu prefsMenu() {
            final JPopupMenu prefsMenu = new JPopupMenu();
            GuiUtils.addSeparator(prefsMenu, "Layout");
            prefsMenu.add(menuItem(new Action(Action.TOGGLE_CONTROL_PANEL, KeyEvent.VK_C, false, true), GLYPH.TABLE_COLUMNS));
            GuiUtils.addSeparator(prefsMenu, "Controls");
            prefsMenu.add(panMenu());
            prefsMenu.add(zoomMenu());
            prefsMenu.add(rotationMenu());
            prefsMenu.add(animationModeMenu());

            GuiUtils.addSeparator(prefsMenu, "Neurite Rendering");
            prefsMenu.add(neuriteRenderingMenu());
            prefsMenu.add(smoothingMenu());
            prefsMenu.add(depthFogMenu());

            GuiUtils.addSeparator(prefsMenu, "Mesh Rendering");
            prefsMenu.add(meshRenderingMenu());

            GuiUtils.addSeparator(prefsMenu, "Advanced");
            prefsMenu.add(getDebugCheckBox());
            if (ENGINE == Engine.JOGL) {
                final JMenuItem jcbmi2 = new JCheckBoxMenuItem("Enable Hardware Acceleration",
                        Settings.getInstance().isHardwareAccelerated());
                jcbmi2.setToolTipText("Use GPU rather than CPU");
                jcbmi2.setIcon(IconFactory.menuIcon(GLYPH.MICROCHIP));
                jcbmi2.setMnemonic('h');
                jcbmi2.addItemListener(e -> {
                    Settings.getInstance().setHardwareAccelerated(jcbmi2.isSelected());
                    logGLDetails();
                });
                prefsMenu.add(jcbmi2);
            }

            prefsMenu.add(menuItem("Preferences...", GLYPH.COGS,
                    e -> runCmd(RecViewerPrefsCmd.class, null, CmdWorker.RELOAD_PREFS, true, false)));
            return prefsMenu;
        }

        private JMenu panMenu() {
            final JMenu panMenu = new JMenu("Pan Accuracy");
            panMenu.setIcon(IconFactory.menuIcon(GLYPH.HAND));
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
            rotationMenu.setIcon(IconFactory.menuIcon(GLYPH.UNDO));
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

        private JMenu animationModeMenu() {
            final JMenu animMenu = new JMenu("Animation Type");
            animMenu.setIcon(IconFactory.menuIcon('\uf008', true));
            final ButtonGroup animGroup = new ButtonGroup();
            final AnimationMode persistedMode = prefs.getAnimationModePref();
            setAnimationMode(persistedMode); // sync runtime with persisted pref
            for (final AnimationMode mode : AnimationMode.values()) {
                final String label = (mode == AnimationMode.FULL_ROTATION) ? "Full Rotation" : "Ping-pong";
                final JMenuItem jcbmi = new JCheckBoxMenuItem(label);
                jcbmi.setSelected(mode == persistedMode);
                jcbmi.addItemListener(ev -> {
                    final boolean isAnimating = mouseController != null && mouseController.isAnimating();
                    setAnimationEnabled(false);
                    setAnimationMode(mode);
                    prefs.setAnimationModePref(mode);
                    setAnimationEnabled(isAnimating);
                });
                animGroup.add(jcbmi);
                animMenu.add(jcbmi);
            }
            return animMenu;
        }

        private JMenu zoomMenu() {
            final JMenu zoomMenu = new JMenu("Zoom Steps (+/- Keys)");
            zoomMenu.setIcon(IconFactory.menuIcon(GLYPH.SEARCH));
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

        /**
         * Creates a "Neurite Rendering" submenu with mutually exclusive radio items:
         * Simple Lines, Pseudo-Lighting, Shaded Tubes, Shaded Tubes (Wireframe).
         */
        private JMenu neuriteRenderingMenu() {
            final JMenu menu = new JMenu("Neurite Style");
            menu.setIcon(IconFactory.menuIcon('\ue085', true));
            menu.setToolTipText("Choose how neurite paths are rendered");
            final ButtonGroup group = new ButtonGroup();

            // Determine current mode
            final boolean isSimple = !tubeModeEnabled && !pseudoLightingEnabled;
            final boolean isLit = pseudoLightingEnabled;
            final boolean isTubes = tubeModeEnabled && !tubeWireframeEnabled;
            final boolean isTubesWf = tubeModeEnabled && tubeWireframeEnabled;

            // 1. Simple Lines
            final JCheckBoxMenuItem simpleItem = new JCheckBoxMenuItem("Simple Lines");
            simpleItem.setSelected(isSimple);
            simpleItem.addItemListener(e -> {
                if (((JCheckBoxMenuItem) e.getSource()).isSelected()) {
                    setTubeMode(false);
                    setTubeWireframe(false);
                    setPseudoLighting(false);
                }
            });
            group.add(simpleItem);
            menu.add(simpleItem);

            // 2. Pseudo-Lighting
            final JCheckBoxMenuItem litItem = new JCheckBoxMenuItem("Pseudo-Lighting");
            litItem.setToolTipText("Modulate neurite brightness based on orientation relative to the view");
            litItem.setSelected(isLit);
            litItem.addItemListener(e -> {
                if (((JCheckBoxMenuItem) e.getSource()).isSelected()) {
                    setTubeMode(false);
                    setTubeWireframe(false);
                    setPseudoLighting(true);
                }
            });
            group.add(litItem);
            menu.add(litItem);

            // 3. Shaded Tubes
            final JCheckBoxMenuItem tubesItem = new JCheckBoxMenuItem("Shaded Tubes");
            tubesItem.setToolTipText("Render neurites as illuminated 3D tubes (requires OpenGL 3.2+)");
            tubesItem.setSelected(isTubes);
            tubesItem.addItemListener(e -> {
                if (((JCheckBoxMenuItem) e.getSource()).isSelected()) {
                    setPseudoLighting(false);
                    setTubeWireframe(false);
                    setTubeMode(true);
                    // If shader init failed, revert to Simple Lines
                    if (!tubeModeEnabled) simpleItem.setSelected(true);
                }
            });
            group.add(tubesItem);
            menu.add(tubesItem);

            // 4. Shaded Tubes (Wireframe)
            final JCheckBoxMenuItem tubesWfItem = new JCheckBoxMenuItem("Shaded Tubes (Wireframe)");
            tubesWfItem.setToolTipText("Shaded tubes with wireframe overlay showing mesh edges");
            tubesWfItem.setSelected(isTubesWf);
            tubesWfItem.addItemListener(e -> {
                if (((JCheckBoxMenuItem) e.getSource()).isSelected()) {
                    setPseudoLighting(false);
                    setTubeWireframe(true);
                    setTubeMode(true);
                    // If shader init failed, revert to Simple Lines
                    if (!tubeModeEnabled) {
                        setTubeWireframe(false);
                        simpleItem.setSelected(true);
                    }
                }
            });
            group.add(tubesWfItem);
            menu.add(tubesWfItem);

            // Tube detail control (only meaningful in tube modes)
            menu.addSeparator();
            menu.add(menuItem("Tube Sides...", GLYPH.SLIDERS, e -> {
                final Integer sides = mgrGuiUtils.getInt(
                        "Number of sides for tube cross-sections (higher = smoother, slower).",
                        "Tube Detail", tubeSidesPref, 3, ArborVBO.MAX_TUBE_SIDES);
                if (sides != null) setTubeSides(sides);
            }));

            return menu;
        }

        private JMenu smoothingMenu() {
            final JMenu menu = new JMenu("Path Smoothing");
            menu.setIcon(IconFactory.menuIcon(GLYPH.BEZIER_CURVE));
            menu.setToolTipText("Catmull-Rom spline interpolation to reduce kinks at thick line joints");
            final ButtonGroup group = new ButtonGroup();
            final String[] labels = { "None", "Low", "Medium", "High" };
            final int[] factors = { 1, 2, 4, 8 };
            for (int i = 0; i < labels.length; i++) {
                final int factor = factors[i];
                final JMenuItem item = new JCheckBoxMenuItem(labels[i]);
                item.setSelected(factor == upsamplingFactorPref);
                item.addItemListener(e -> {
                    if (((JCheckBoxMenuItem) e.getSource()).isSelected()) setUpsamplingFactor(factor);
                });
                group.add(item);
                menu.add(item);
            }
            return menu;
        }

        private JMenu meshRenderingMenu() {
            final JMenu menu = new JMenu("Mesh Style");
            menu.setIcon(IconFactory.menuIcon('\ue085', true));
            menu.setToolTipText("Choose how surface meshes are rendered");
            final ButtonGroup group = new ButtonGroup();

            // 1. Default (jzy3d fixed-function)
            final JCheckBoxMenuItem defaultItem = new JCheckBoxMenuItem("Default");
            defaultItem.setToolTipText("Fixed-function rendering (jzy3d default)");
            defaultItem.setSelected(meshShadingPref == OBJMesh.SHADING_DEFAULT);
            defaultItem.addItemListener(e -> {
                if (((JCheckBoxMenuItem) e.getSource()).isSelected())
                    setMeshShadingMode(OBJMesh.SHADING_DEFAULT);
            });
            group.add(defaultItem);
            menu.add(defaultItem);

            // 2. Smooth Shading
            final JCheckBoxMenuItem smoothItem = new JCheckBoxMenuItem("Smooth Shading");
            smoothItem.setToolTipText("Per-fragment Phong lighting with hemispherical ambient");
            smoothItem.setSelected(meshShadingPref == OBJMesh.SHADING_SMOOTH);
            smoothItem.addItemListener(e -> {
                if (((JCheckBoxMenuItem) e.getSource()).isSelected())
                    setMeshShadingMode(OBJMesh.SHADING_SMOOTH);
            });
            group.add(smoothItem);
            menu.add(smoothItem);

            menu.addSeparator();

            // Backface Culling (independent of shading mode)
            final JCheckBoxMenuItem cullItem = new JCheckBoxMenuItem("Backface Culling", meshBackfaceCullPref);
            cullItem.setToolTipText("Skip back-facing triangles (faster for closed meshes)");
            cullItem.addItemListener(e -> setMeshBackfaceCull(((JCheckBoxMenuItem) e.getSource()).isSelected()));
            menu.add(cullItem);

            return menu;
        }

        private JMenu depthFogMenu() {
            final JMenu menu = new JMenu("Depth Perception");
            menu.setIcon(IconFactory.menuIcon(GLYPH.EYE));
            menu.setToolTipText("Fade distant neurites toward the background for depth perception");
            final ButtonGroup group = new ButtonGroup();
            // "None" option disables fog
            final JMenuItem noneItem = new JCheckBoxMenuItem("None");
            noneItem.setSelected(!depthFogEnabled);
            noneItem.addItemListener(e -> {
                if (((JCheckBoxMenuItem) e.getSource()).isSelected()) setDepthFog(false);
            });
            group.add(noneItem);
            menu.add(noneItem);
            // Intensity presets
            final String[] labels = { "Subtle", "Moderate", "Strong", "Full" };
            final float[] values = { 0.3f, 0.5f, 0.7f, 1.0f };
            for (int i = 0; i < labels.length; i++) {
                final float val = values[i];
                final JMenuItem item = new JCheckBoxMenuItem(labels[i]);
                item.setSelected(depthFogEnabled && Math.abs(val - fogIntensityPref) < 0.05f);
                item.addItemListener(e -> {
                    if (((JCheckBoxMenuItem) e.getSource()).isSelected()) {
                        setFogIntensity(val);
                        setDepthFog(true);
                    }
                });
                group.add(item);
                menu.add(item);
            }
            return menu;
        }

        private class RecordWorker extends SwingWorker<String, Object> {

            private boolean error = false;
            private final RotationAxis axis;

            RecordWorker(final RotationAxis axis) {
                this.axis = axis;
            }

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
                            .getSnapshotRotationSteps(), dir, axis);
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
                    mgrGuiUtils.error(doneMessage);
                }
                else displayMsg(doneMessage);
            }
        }

        private boolean okToApplyColor(final List<String> labelsOfselectedTrees) {
            if (!treesContainColoredNodes(labelsOfselectedTrees)) return true;
            return mgrGuiUtils.getConfirmation("Some of the selected reconstructions " +
                            "seem to be color-coded. Apply homogeneous color nevertheless?",
                    "Override Color Code?");
        }

        private JPopupMenu treesMenu() {
            final JPopupMenu tracesMenu = new JPopupMenu();
            GuiUtils.addSeparator(tracesMenu, "Add");
            final JMenuItem loadFile = menuItem("Load File...", GLYPH.IMPORT, e -> {
                final Map<String, Object> inputs = new HashMap<>();
                inputs.put("importDir", false);
                runImportCmd(LoadReconstructionCmd.class, inputs);
            });
            loadFile.setMnemonic('f');
            tracesMenu.add(loadFile);
            tracesMenu.add(menuItem("Load Directory...", GLYPH.FOLDER, e -> {
                final Map<String, Object> inputs = new HashMap<>();
                inputs.put("importDir", true);
                runImportCmd(LoadReconstructionCmd.class, inputs);
            }));
            tracesMenu.add(menuItem("Load & Compare Groups...", GLYPH.MAGIC,
                    e -> runImportCmd(GroupAnalyzerCmd.class, null)));
            if (!isSNTInstance()) tracesMenu.add(loadDemoMenuItem());
            tracesMenu.add(remoteDatabaseMenu());

            GuiUtils.addSeparator(tracesMenu, "Style");
            addCustomizeTreeCommands(tracesMenu);

            GuiUtils.addSeparator(tracesMenu, "Utilities");
            tracesMenu.add(menuItem("Translate...", GLYPH.MOVE, e -> translateSelectedTreesAction()));
            tracesMenu.add(menuItem("Align View to Tree", GLYPH.RULER_VERTICAL, e -> makeSelectedTreeUprightAction()));

            GuiUtils.addSeparator(tracesMenu, "Remove");
            tracesMenu.add(menuItem("Remove Selected...", GLYPH.DELETE, e -> {
                final List<String> keys = getSelectedTreeLabels();
                if (keys == null || keys.isEmpty()) {
                    mgrGuiUtils.error("There are no selected reconstructions.");
                    return;
                }
                if (!mgrGuiUtils.getConfirmation("Delete " + keys.size() +
                        " reconstruction(s)?", "Confirm Deletion"))
                    return;
                setSceneUpdatesEnabled(false);
                keys.forEach(Viewer3D.this::removeTree);
                setSceneUpdatesEnabled(true);
                updateView();
            }));
            tracesMenu.add(menuItem("Remove All...", GLYPH.TRASH, e -> {
                if (mgrGuiUtils.getConfirmation("Remove all reconstructions from scene?", "Remove All Reconstructions?"))
                    removeAllTrees();
            }));
            return tracesMenu;
        }

        private JMenu remoteDatabaseMenu() {
            final JMenu menu = new JMenu("Load from Database");
            menu.setMnemonic('d');
            menu.setDisplayedMnemonicIndex(10);
            menu.setIcon(IconFactory.menuIcon(GLYPH.DATABASE));
            menu.add(new JMenuItem("FlyCircuit...", 'f')).addActionListener(e -> {
                final Map<String, Object> inputs = new HashMap<>();
                inputs.put("loader", new FlyCircuitLoader());
                runImportCmd(RemoteSWCImporterCmd.class, inputs);
            });
            menu.add(new JMenuItem("InsectBrain...", 'I')).addActionListener(
                    e -> runImportCmd(InsectBrainImporterCmd.class, null));
            menu.add(new JMenuItem("MouseLight...", 'm')).addActionListener(
                    e -> runImportCmd(MLImporterCmd.class, null));
            menu.add(new JMenuItem("NeuroMorpho...", 'n')).addActionListener(e -> {
                final Map<String, Object> inputs = new HashMap<>();
                inputs.put("loader", new NeuroMorphoLoader());
                runImportCmd(RemoteSWCImporterCmd.class, inputs);
            });
            return menu;
        }

        private JMenuItem loadDemoMenuItem() {
            final JMenuItem mi = new JMenuItem("Load Demo(s)...", IconFactory.menuIcon(GLYPH.GRADUATION_CAP));
            mi.addActionListener(e -> {
                try {
                    final DemoRunner demoRunner = new DemoRunner(SNTUtils.getContext());
                    final Demo choice = demoRunner.getChoice();
                    if (choice != null)
                        addTrees(choice.getTrees(), "unique");
                } catch (final Throwable ex) {
                    mgrGuiUtils.error(ex.getMessage());
                    ex.printStackTrace();
                }
            });
            return mi;
        }

        private JMenu legendMenu() {
            final JMenu legendMenu = new JMenu("Color Mapping Legends");
            legendMenu.setIcon(IconFactory.menuIcon(GLYPH.COLOR2));
            legendMenu.add(menuItem("Add...", GLYPH.PLUS, e -> {
                final Map<String, Object> inputs = new HashMap<>();
                inputs.put("treeLabels", null);
                runCmd(ColorMapReconstructionCmd.class, inputs, CmdWorker.DO_NOTHING, true, false);
            }));
            final JMenuItem mi = new JMenuItem("Edit Last...", IconFactory.menuIcon(GLYPH.SLIDERS));
            mi.addActionListener(e -> {
                if (cBar == null) {
                    mgrGuiUtils.error("No Legend currently exists.");
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
                            mgrGuiUtils.error("Command execution failed.");
                            return;
                        }
                        updateColorBarLegend(outMap.get("min"), outMap.get("max"), outMap.get("fSize").floatValue());
                    }
                }
                (new GetLegendSettings()).execute();
            });
            legendMenu.add(mi);

            legendMenu.addSeparator();
            legendMenu.add(menuItem("Remove Last", GLYPH.DELETE, e -> removeColorLegends(true)));
            legendMenu.add(menuItem("Remove All...", GLYPH.TRASH, e -> {
                if (mgrGuiUtils.getConfirmation("Remove all color legends from scene?", "Remove All Legends?"))
                    removeColorLegends(false);
            }));
            return legendMenu;
        }

        private void loadMeshFilesAction() {
            runImportCmd(LoadObjCmd.class, null); // LoadObjCmd will call validate()
        }

        private void removeSelectedMeshesAction() {
            final List<String> keys = getSelectedMeshLabels();
            if (keys == null || keys.isEmpty()) {
                mgrGuiUtils.error("There are no selected meshes.");
                return;
            }
            if (!mgrGuiUtils.getConfirmation("Delete " + keys.size() + " mesh(es)?",
                    "Confirm Deletion"))
            {
                return;
            }
            setSceneUpdatesEnabled(false);
            keys.forEach(Viewer3D.this::removeMesh);
            setSceneUpdatesEnabled(true);
            updateView();
        }

        private void removeAllMeshesAction() {
            if (mgrGuiUtils.getConfirmation("Remove all meshes from scene?", "Remove All Meshes?"))
                removeAllMeshes();
        }

        private JPopupMenu meshMenu() {
            final JPopupMenu meshMenu = new JPopupMenu();
            GuiUtils.addSeparator(meshMenu, "Add");
            meshMenu.add(menuItem("Load OBJ File(s)...", GLYPH.IMPORT, e -> loadMeshFilesAction()));
            addCustomizeMeshCommands(meshMenu);
            GuiUtils.addSeparator(meshMenu, "Remove");
            meshMenu.add(menuItem("Remove Selected...", GLYPH.DELETE, e -> removeSelectedMeshesAction()));
            meshMenu.add(menuItem("Remove All...", GLYPH.TRASH, e -> removeAllMeshesAction()));
            return meshMenu;
        }

        private void addCellBasedCrossSectionPlaneAnnotationsAction() {
            final List<Tree> trees = getSelectedTrees(true);
            if (trees == null)
                return;
            final AnnotPrompt prompt = new AnnotPrompt();
            final List<String> userAxes = prompt.getPromptPlaneAxes(true);
            if (userAxes == null)
                return;
            for (final Tree tree : trees) {
                final BoundingBox3d bounds = tree.getBoundingBox().toBoundingBox3d();
                for (final String axis : userAxes) {
                    final PointInImage[] plane = (axis.toLowerCase().contains("soma")) ? prompt.getSomaPlane(bounds, tree, axis) : AnnotPrompt.getPlane(bounds, axis) ;
                    final Annotation3D annot = annotatePlane(plane[0], plane[1], tree.getLabel() + " [" + axis + "]");
                    annot.setColor(toColorRGB(Utils.contrastColor(fromColorRGB(tree.getColor()))), 25);
                }
            }
            updateView();
        }

        private void addCellBasedSurfaceAnnotationsAction() {
            final List<Tree> trees = getSelectedTrees(true);
            if (trees == null)
                return;
            final String[] choices = { "Branch points", "Tips" };
            final String choice = mgrGuiUtils.getChoice("Generate surface from which structures?",
                    "Add Surface...", choices, choices[0]);
            if (choice == null)
                return;
            final List <String> failures = new ArrayList<>();
            for (final Tree tree : trees) {
                if (!tree.is3D()) {
                    failures.add(tree.getLabel());
                    continue;
                }
                Annotation3D annot;
                if (choice.startsWith("Branch"))
                    annot = annotateSurface(new TreeStatistics(tree).getBranchPoints(),
                            tree.getLabel() + " [BPs surface]", false);
                else
                    annot = annotateSurface(new TreeStatistics(tree).getTips(),
                            tree.getLabel() + " [Tips surface]", false);
                final ColorRGB color = tree.getColor();
                if (color != null)
                    annot.setColor(toColorRGB(Utils.contrastColor(fromColorRGB(color))), 75);
            }
            if (!failures.isEmpty()) {
                mgrGuiUtils.error(("Surfaces cannot be assemble from these 2D reconstructions: "
                        + failures +". Only 3D reconstructions are supported."));
            }
            updateView();
        }

        private void addMeshBasedCrossSectionPlaneAnnotationsAction() {
            final List<String> meshLabels = getSelectedMeshLabels();
            if (meshLabels == null)
                return;
            final AnnotPrompt prompt = new AnnotPrompt();
            final List<String> userAxes = prompt.getPromptPlaneAxes(false);
            if (userAxes == null)
                return;
            for (final String mLabel : meshLabels) {
                final BoundingBox3d bounds = plottedObjs.get(mLabel).getBounds();
                for (final String axis : userAxes) {
                    final PointInImage[] plane = AnnotPrompt.getPlane(bounds, axis);
                    final Annotation3D annot = annotatePlane(plane[0], plane[1], mLabel + " [" + axis + "]");
                    annot.setColor(toColorRGB(Utils.contrastColor(plottedObjs.get(mLabel).objMesh.getDrawable().getColor())), 25);
                }
            }
            updateView();
        }

        private void addMeshBasedSurfaceAnnotationsAction() {
            final List<String> meshLabels = getSelectedMeshLabels();
            if (meshLabels == null)
                return;
            final String[] choices = { "Left hemisphere", "Right hemisphere", "Both hemispheres" };
            final String choice = mgrGuiUtils.getChoice("Generate surface from which vertices?",
                    "Add Surface...", choices, choices[0]);
            if (choice == null)
                return;
            for (final String mLabel : meshLabels) {
                final String key = choice.split(" ")[0];
                final Collection<? extends SNTPoint> vertices = plottedObjs.get(mLabel).objMesh.getVertices(key.toLowerCase());
                final Annotation3D annot = annotateSurface(vertices, mLabel + " [" + (("Both".equals(key)) ? "Full" : key) + " surface]", false);
                final Color color = plottedObjs.get(mLabel).objMesh.getDrawable().getColor();
                if (color != null)
                    annot.setColor(toColorRGB(Utils.contrastColor(color)), 75);
            }
            updateView();
        }

        private void addPrimitiveSphereAnnotationAction() {
            final AnnotPrompt ap = new AnnotPrompt();
            if (ap.validPrompt("Sphere Properties...", new String[] { "Center X ", "Center Y ", "Center Z "}, null, true)) {
                final Annotation3D point = annotatePoint(ap.pim1, "Sphere", ap.color, (float) ap.size);
                point.setColor(point.getColor(), ap.t);
            }
        }

        private void addPrimitiveVectorAnnotationAction() {
            final AnnotPrompt ap = new AnnotPrompt();
            if (ap.validPrompt("Vector Properties...", new String[] { "X1 ", "Y1 ", "Z1 " },
                    new String[] { "X2 ", "Y2 ", "Z2 " }, true)) {
                final Annotation3D line = annotateLine(List.of(ap.pim1, ap.pim2), "Vector");
                line.setColor(ap.color, ap.t);
                line.setSize((float) ap.size);
            }
        }

        private void addPrimitivePlaneAnnotationAction() {
            final AnnotPrompt ap = new AnnotPrompt();
            if (ap.validPrompt("Plane/Parallelepiped Properties...", new String[] { "Origin X ", "Origin Y ", "Origin Z " },
                    new String[] { "Origin Opposite X ", "Origin Opposite Y ", "Origin Opposite Z " }, false)) {
                final Annotation3D annot = annotatePlane(ap.pim1, ap.pim2, "Annot. Plane");
                annot.setColor(ap.color, ap.t);
            }
        }

        private void setSelectedAnnotationsColorAction() {
            final List<Annotation3D> annots = getSelectedAnnotations();
            if (annots == null)
                return;
            final ColorRGB c = new AnnotPrompt().getPromptColor("Annotation(s) Color...");
            if (c != null)
                annots.forEach(annot -> annot.setColor(c, -1));
        }

        private void applySelectedAnnotationsGradientAction() {
            final List<Annotation3D> annots = getSelectedAnnotations();
            if (annots == null)
                return;
            final String[] res = new AnnotPrompt().getPromptGradient();
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
                mgrGuiUtils.error(("The following annotations do not support color gradients: " + failures));
        }

        private void setSelectedAnnotationsTransparencyAction() {
            final List<Annotation3D> annots = getSelectedAnnotations();
            if (annots == null)
                return;
            final Integer t = new AnnotPrompt().getPromptTransparency("Annotation(s) Transparency...");
            if (t != null)
                annots.forEach(annot -> annot.setColor((ColorRGB) null, t));
        }

        private void adjustSelectedAnnotationSurfaceRenderingAction() {
            final List<Annotation3D> annots = getSelectedAnnotations();
            if (annots == null) return;
            final List<Annotation3D> enlightable = annots.stream()
                    .filter(a -> a.getDrawable() instanceof AbstractEnlightable)
                    .toList();
            if (enlightable.isEmpty()) {
                mgrGuiUtils.error("Selected annotation(s) do not support surface rendering adjustments. "
                        + "Only primitive shapes (spheres, disks, polygons, etc.) are supported.");
                return;
            }
            enlightable.forEach(a -> frame.displayLightController((AbstractEnlightable) a.getDrawable()));
        }

        private void renameSelectedAnnotationAction() {
            final List<Annotation3D> annots = getSelectedAnnotations();
            if (annots == null) return;
            if (annots.size() != 1) {
                mgrGuiUtils.error("Please select a single annotation to rename.");
                return;
            }
            final Annotation3D annot = annots.get(0);
            final String currentLabel = annot.getLabel();
            final String newLabel = mgrGuiUtils.getString(
                    "New label for \"" + currentLabel + "\":", "Rename Annotation", currentLabel);
            if (newLabel == null || newLabel.isBlank() || newLabel.equals(currentLabel)) return;
            final String[] labels = TagUtils.getUntaggedAndTaggedLabels(currentLabel);
            plottedAnnotations.remove(labels[0]);
            deleteItemFromManager(labels[1]);
            final String uniqueLabel = getUniqueLabel(plottedAnnotations, "Annot.", newLabel);
            annot.setLabel(uniqueLabel);
            plottedAnnotations.put(uniqueLabel, annot);
            addItemToManager(uniqueLabel);
        }

        private void setSelectedAnnotationsSizeAction() {
            final List<Annotation3D> annots = getSelectedAnnotations();
            if (annots == null) return;
            final double def = Double.parseDouble(prefs.getGuiPref("aSize", "1"));
            final Double size = mgrGuiUtils.getDouble(
                    "Specify a size/thickness scaling factor (1–10) for the selected annotation(s).",
                    "Annotation(s) Size...", def, 1d, 10d, "");
            if (size == null || size.isNaN()) return;
            prefs.setGuiPref("aSize", SNTUtils.formatDouble(size, 2));
            // GL line widths are effectively capped by the driver below 10, so normalize the range
            final float normSize = (float) (((size - 1) * 7 / 9) + 1);
            annots.forEach(annot -> annot.setSize(normSize));
            if (chart != null && viewUpdatesEnabled) chart.render();
        }

        private void removeSelectedAnnotationsAction() {
            final List<Annotation3D> annots = getSelectedAnnotations();
            if (annots == null || annots.isEmpty()) {
                mgrGuiUtils.error("There are no selected annotations.");
                return;
            }
            if (!mgrGuiUtils.getConfirmation("Delete " + annots.size() + " annotation(s)?", "Confirm Deletion")) {
                return;
            }
            setSceneUpdatesEnabled(false);
            annots.forEach(Viewer3D.this::removeAnnotation);
            setSceneUpdatesEnabled(true);
            updateView();
        }

        private JPopupMenu annotationsMenu() {
            final JPopupMenu annotMenu = new JPopupMenu();
            GuiUtils.addSeparator(annotMenu, "Add");
            final JMenu treeBased = new JMenu("Tree-based");
            treeBased.setIcon(IconFactory.menuIcon(GLYPH.TREE));
            final JMenuItem cellPlane = menuItem("Tree Cross-section Plane...", GLYPH.SCISSORS,
                    e -> addCellBasedCrossSectionPlaneAnnotationsAction());
            cellPlane.setToolTipText("Adds cross-section plane(s) to neuronal arbors");
            treeBased.add(cellPlane);
            final JMenuItem cellSurface = menuItem("Tree Surface...", GLYPH.DICE_20,
                    e -> addCellBasedSurfaceAnnotationsAction());
            cellSurface.setToolTipText("Adds convex-hull tessellations to neuronal arbors");
            treeBased.add(cellSurface);
            annotMenu.add(treeBased);
            final JMenu meshBased = new JMenu("Mesh-based");
            meshBased.setIcon(IconFactory.menuIcon(GLYPH.CUBE));
            final JMenuItem meshPlane = menuItem("Mesh Cross-section Plane...", GLYPH.SCISSORS,
                    e -> addMeshBasedCrossSectionPlaneAnnotationsAction());
            meshPlane.setToolTipText("Adds cross-section plane(s) to selected meshes");
            meshBased.add(meshPlane);
            final JMenuItem meshSurface = menuItem("Mesh Surface...", GLYPH.DICE_20,
                    e -> addMeshBasedSurfaceAnnotationsAction());
            meshSurface.setToolTipText("Adds convex-hull tessellations to selected meshes");
            meshBased.add(meshSurface);
            annotMenu.add(meshBased);
            final JMenu primitives = new JMenu("Shapes");
            primitives.setToolTipText("Adds basic geometry objects to the scene");
            primitives.setIcon(IconFactory.menuIcon('\uf61f', true));
            annotMenu.add(primitives);
            primitives.add(menuItem("Sphere...", GLYPH.GLOBE, e -> addPrimitiveSphereAnnotationAction()));
            primitives.add(menuItem("Vector...", GLYPH.ARROWS_LR, e -> addPrimitiveVectorAnnotationAction()));
            primitives.add(menuItem("Plane/Parallelepiped...", GLYPH.SQUARE, e -> addPrimitivePlaneAnnotationAction()));
            GuiUtils.addSeparator(annotMenu, "Customize");
            annotMenu.add(menuItem("Rename...", GLYPH.PEN, e -> renameSelectedAnnotationAction()));
            annotMenu.add(menuItem("Color...", GLYPH.COLOR, e -> setSelectedAnnotationsColorAction()));
            annotMenu.add(menuItem("Color Gradient...", GLYPH.COLOR2, e -> applySelectedAnnotationsGradientAction()));
            annotMenu.add(menuItem("Transparency...", GLYPH.ADJUST, e -> setSelectedAnnotationsTransparencyAction()));
            annotMenu.add(menuItem("Size...", GLYPH.RESIZE, e -> setSelectedAnnotationsSizeAction()));
            annotMenu.add(menuItem("Surface Rendering...", GLYPH.CUBES, e -> adjustSelectedAnnotationSurfaceRenderingAction()));
            GuiUtils.addSeparator(annotMenu, "Remove");
            annotMenu.add(menuItem("Remove Selected...", GLYPH.DELETE, e -> removeSelectedAnnotationsAction()));
            annotMenu.add(menuItem("Remove All...", GLYPH.TRASH, e -> {
                if (mgrGuiUtils.getConfirmation("Remove all annotations from scene?", "Remove All Annotations?"))
                    removeAllAnnotations();
            }));
            return annotMenu;
        }

        private JPopupMenu refBrainsMenu() {
            final JPopupMenu refMenu = new JPopupMenu("Reference Brains");
            GuiUtils.addSeparator(refMenu, "Mouse");
            JMenuItem mi = new JMenuItem("Allen CCF Navigator", IconFactory
                    .menuIcon(GLYPH.NAVIGATE));
            mi.addActionListener(e -> {
                assert SwingUtilities.isEventDispatchThread();
                if (frame.allenNavigator != null) {
                    frame.allenNavigator.dialog.toFront();
                    return;
                }
                //final JDialog tempSplash = mgrGuiUtils.floatingMsg("Loading ontologies...", false);
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

            GuiUtils.addSeparator(refMenu, "Zebrafish");
            refMenu.add(menuItem("Max Planck ZBA", GLYPH.ARCHIVE, e -> loadRefBrainAction(true, MESH_LABEL_ZEBRAFISH)));

            GuiUtils.addSeparator(refMenu, "Drosophila");
            refMenu.add(menuItem("Adult Brain: FlyCircuit", GLYPH.ARCHIVE, e -> loadRefBrainAction(true, MESH_LABEL_FCWB)));
            final JMenuItem jfrc2018 = menuItem("Adult Brain: JRC 2018 (Unisex)", GLYPH.ARCHIVE,
                    e -> loadRefBrainAction(true, MESH_LABEL_JFRC2018));
            jfrc2018.setToolTipText("<HTML>AKA <i>The Bogovic brain</i>");
            refMenu.add(jfrc2018);
            refMenu.add(menuItem("Adult Brain: JFRC2", GLYPH.ARCHIVE, e -> loadRefBrainAction(true, MESH_LABEL_JFRC2)));
            refMenu.add(menuItem("Adult Brain: JFRC3", GLYPH.ARCHIVE, e -> loadRefBrainAction(true, MESH_LABEL_JFRC3)));
            refMenu.add(menuItem("Adult VNS", GLYPH.CLOUD, e -> loadRefBrainAction(true, MESH_LABEL_VNS)));
            refMenu.add(menuItem("L1 Larva", GLYPH.CLOUD, e -> loadRefBrainAction(true, MESH_LABEL_L1)));
            refMenu.add(menuItem("L3 Larva", GLYPH.CLOUD, e -> loadRefBrainAction(true, MESH_LABEL_L3)));
            refMenu.addSeparator();
            refMenu.add(GuiUtils.MenuItems.openHelpURL("Reference Brains Help",
                    "https://imagej.net/plugins/snt/reconstruction-viewer#reference-brains"));
            return refMenu;
        }

        private void loadRefBrainAction(final boolean warnIfLoaded, final String label) {
            loadRefBrainAction(warnIfLoaded, label, true);
        }

        private void loadRefBrainAction(final boolean warnIfLoaded, final String label, final boolean setProgressBar) {
            final boolean canProceed;
            switch (label) {
                case MESH_LABEL_L1, MESH_LABEL_L3, MESH_LABEL_VNS -> canProceed = VFBUtils.isDatabaseAvailable();
                default -> canProceed = true;
            }
            if (!canProceed) {
                mgrGuiUtils.error("Remote server not reached. It is either down or you have no internet access.");
                return;
            }
            if (warnIfLoaded && getOBJs().containsKey(label)) {
                mgrGuiUtils.error(label + " is already loaded.");
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
                        mgrGuiUtils.error("An error occurred and mesh could not be retrieved. See Console for details.");
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
                            final Boolean prompt = mgrGuiUtils.getPersistentWarning(
                                    String.format("Cartesian axes relabeled to %s", Arrays.toString(getAxesLabels())),
                                    "Mapping of Cartesian Axes Changed");
                            if (prompt != null) // do nothing if user dismissed the dialog
                                prefs.nagUserOnAxesChanges = !prompt;
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
                        if (promptForConfirmation && guiUtils != null) {
                            SwingUtilities.invokeLater(() -> guiUtils().error("Dragged file(s) do not contain valid data."));
                        }
                        return INVALID;
                    }
                    if (promptForConfirmation && collection.size() > 10 && guiUtils != null) {
                        final boolean[][] confirmSplitHolder = new boolean[1][];
                        try {
                            SwingUtilities.invokeAndWait(() -> confirmSplitHolder[0] = guiUtils().getConfirmationAndOption(
                                    "Are you sure you would like to import " + collection.size() + " files?<br>"
                                            + "You can press 'Esc' at any time to interrupt import.",
                                    "Proceed with Batch Import?", "Import axons and dendrites separately",
                                    isSplitDendritesFromAxons()));
                        } catch (final InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            return ABORTED;
                        } catch (final InvocationTargetException ex) {
                            SNTUtils.error(ex.getMessage(), ex);
                            return ABORTED;
                        }
                        final boolean[] confirmSplit = confirmSplitHolder[0];
                        if (confirmSplit == null) {
                            return ABORTED;
                        }
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
                        switch (get()) {
                            case ABORTED -> displayMsg("Drag & drop operation aborted");
                            case COMPLETED -> {
                                if (failuresAndSuccesses[1] > 0) validate();
                                if (failuresAndSuccesses[0] > 0 && guiUtils != null)
                                    guiUtils().error(failuresAndSuccesses[0] + " of "
                                            + (failuresAndSuccesses[0] + failuresAndSuccesses[1])
                                            + " dropped file(s) could not be imported (Console may"
                                            + " have more details if you have enabled \"Debug mode\").");
                            }
                            default -> { // do nothing for now
                            }
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
                                TreeUtils.assignUniqueColors(treesInFile);
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
            tree.setRootVisible(false); // root mesh already loaded
            tree.setShowsRootHandles(true);
            searchableBar = new SNTSearchableBar(new TreeSearchable(tree));
            //searchableBar.getSearchable().setRepeats(false);
            searchableBar.setStatusLabelPlaceholder("CCF v"+ AllenUtils.VERSION);
            searchableBar.setHighlightAll(true);
            searchableBar.setShowMatchCount(true);
            searchableBar.setVisibleButtons(
                    SNTSearchableBar.SHOW_NAVIGATION | SNTSearchableBar.SHOW_HIGHLIGHTS | SNTSearchableBar.SHOW_STATUS);
            tree.setCellRenderer(new CustomRenderer(tree, searchableBar));
            refreshTree(false);
        }

        private List<AllenCompartment> getCheckedSelection() {
            final TreePath[] treePaths = tree.getCheckBoxTreeSelectionModel().getSelectionPaths();
            if (treePaths == null || treePaths.length == 0) {
                guiUtils().error("There are no checked ontologies.");
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
                            guiUtils().centeredMsg("Selected compartment(s) seem to be already loaded.", "Compartments Loaded");
                        }
                        else if (!failedCompartments.isEmpty()) {
                            final StringBuilder sb = new StringBuilder(String.valueOf(loadedCompartments)).append("/")
                                    .append(loadedCompartments + failedCompartments.size())
                                    .append(" meshes retrieved. The following compartments failed to load:")
                                    .append("<br>&nbsp;<br>").append(String.join("; ", failedCompartments))
                                    .append("<br>&nbsp;<br>")
                                    .append("Either such meshes are not available or file(s) could not be reached. Check Console logs for details.");
                            guiUtils().centeredMsg(sb.toString(), "Exceptions Occurred");
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
            cs.sort((c1, c2) -> c1.name().compareToIgnoreCase(c2.name()));
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
            guiUtils().showHTMLDialog(sb.toString(), "Info On Selected Compartments", false); // guiUtils is not null
        }

        private JDialog show() {
            dialog = new JDialog(frame, "Allen CCF Ontology");
            dialog.getRootPane().putClientProperty("Window.style", "small");
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
            dialog.pack();
            if (frame.hasManager()) {
                dialog.setPreferredSize(new Dimension(dialog.getPreferredSize().width,
                        frame.getHeight()));
                dialog.setLocationRelativeTo(frame.managerPanel);
            }
            GuiUtils.JTrees.expandToLevel(tree, 4);
            GuiUtils.JTrees.scrollToLastRow(tree);
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
            contentPane.setBorder(null);
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
            JButton button = new JButton(IconFactory.buttonIcon(GLYPH.INFO, 1f));
            button.addActionListener(e -> showSelectionInfo());
            buttonPanel.add(button);
            button = new JButton(IconFactory.buttonIcon(GLYPH.IMPORT, 1f));
            button.addActionListener(e -> downloadMeshes());
            buttonPanel.add(button);
            return buttonPanel;
        }

        private JPopupMenu popupMenu() {
            final JPopupMenu pMenu = new JPopupMenu();
            JMenuItem jmi = new JMenuItem("Clear Selection");
            jmi.addActionListener(e -> tree.clearSelection());
            pMenu.add(jmi);
            pMenu.addSeparator();
            jmi = new JMenuItem("Collapse All");
            jmi.addActionListener(e -> GuiUtils.JTrees.collapseAllNodes(tree));
            pMenu.add(jmi);
            jmi = new JMenuItem("Collapse Selected Level");
            jmi.addActionListener(e -> {
                final TreePath selectedPath = tree.getSelectionPath();
                if (selectedPath != null) GuiUtils.JTrees.collapseNodesOfSameLevel(tree, selectedPath);
            });
            pMenu.add(jmi);
            pMenu.addSeparator();
            jmi = new JMenuItem("Expand All");
            jmi.addActionListener(e -> {
                GuiUtils.JTrees.expandAllNodes(tree);
                if (!searchableBar.getSearchField().getText().isEmpty())
                    searchableBar.getSearchField().setText(searchableBar.getSearchField().getText());
            });
            pMenu.add(jmi);
            jmi = new JMenuItem("Expand Selected Level");
            jmi.addActionListener(e -> {
                final TreePath selectedPath = tree.getSelectionPath();
                if (selectedPath != null) GuiUtils.JTrees.expandNodesOfSameLevel(tree, selectedPath);
            });
            pMenu.add(jmi);
            pMenu.addSeparator();
            final JCheckBoxMenuItem jcmi = new JCheckBoxMenuItem("Auto-select Children", tree.getCheckBoxTreeSelectionModel().isDigIn());
            jcmi.addItemListener(e -> tree.getCheckBoxTreeSelectionModel().setDigIn(jcmi.isSelected()));
            pMenu.add(jcmi);
            pMenu.addSeparator();
            pMenu.add(GuiUtils.MenuItems.openHelpURL("Online 2D Atlas Viewer", "https://atlas.brain-map.org/atlas?atlas=602630314"));
            pMenu.add(GuiUtils.MenuItems.openHelpURL("Online 3D Atlas Viewer", "https://connectivity.brain-map.org/3d-viewer"));
            return pMenu;
        }

        private class NavigatorTree extends CheckBoxTree {
            private static final long serialVersionUID = 1L;

            public NavigatorTree(final DefaultTreeModel treeModel) {
                super(treeModel);
                setLargeModel(true);
                setDigIn(false);
                setClickInCheckBoxOnly(true);
                setEditable(false);
                setExpandsSelectedPaths(true);
                setRootVisible(true);
            }
            @Override
            public TreePath getNextMatch(String prefix, int startingRow, Position. Bias bias) {
                return null; // avoid conflict with search bar
            }
            @Override
            public boolean isCheckBoxEnabled(final TreePath treePath) {
                final DefaultMutableTreeNode selectedElement = (DefaultMutableTreeNode) treePath.getLastPathComponent();
                final AllenCompartment compartment = (AllenCompartment) selectedElement.getUserObject();
                return compartment.isMeshAvailable() && !getOBJs().containsKey(compartment.name());
            }
        }

        static class CustomRenderer extends DefaultTreeCellRenderer {
            private static final long serialVersionUID = 1L;
            private final SNTSearchableBar searchableBar;

            CustomRenderer(NavigatorTree tree, final SNTSearchableBar searchableBar) {
                super();
                this.searchableBar = searchableBar;
                searchableBar.getSearchField().getDocument().addDocumentListener(new DocumentListener() {
                    @Override
                    public void changedUpdate(DocumentEvent e) {
                        repaintWhenEmpty();
                    }
                    @Override
                    public void removeUpdate(DocumentEvent e) {
                        repaintWhenEmpty();
                    }
                    @Override
                    public void insertUpdate(DocumentEvent e) {
                        repaintWhenEmpty();
                    }
                    void repaintWhenEmpty() {
                        if (searchableBar.getSearchField().getText().isEmpty()) {
                            tree.repaint();
                        }
                    }
                });
            }

            @Override
            public Component getTreeCellRendererComponent(final JTree tree, final Object value, final boolean sel,
                                                          final boolean expanded, final boolean leaf, final int row, final boolean hasFocus) {
                final AllenCompartment ac = (AllenCompartment) ((DefaultMutableTreeNode) value).getUserObject();
                final Component treeCellRendererComponent = super.getTreeCellRendererComponent(tree, value, sel,
                        expanded, leaf, row, hasFocus);
                if (!searchableBar.getSearchingText().isEmpty() && !sel) {
                    treeCellRendererComponent.setEnabled(false);
                } else {
                    treeCellRendererComponent.setEnabled(ac.isMeshAvailable());
                }
                if (ac.id() == AllenUtils.BRAIN_ROOT_ID) { // mesh color has no ontological meaning
                    setIcon(null);
                } else if (ac.isMeshAvailable()) {
                    final ColorRGB color = ac.color();
                    if (color != null) setIcon(IconFactory.nodeIcon(new java.awt.Color(color.getARGB())));
                }
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
            renderer = new CustomListRenderer(this, (DefaultListCellRenderer) getActualCellRenderer());
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

        static class HandlerPlus extends CheckBoxList.Handler {

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
            editTextField = new JTextField();
            GuiUtils.addClearButton(editTextField);
            GuiUtils.addPlaceholder(editTextField, "Tags:");
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
                    if (editTextField.getText().isEmpty()) {
                        // clear button pressed without triggering actionlistener!?
                        removeTagsFromSelectedItems(false);
                    }
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
            private final Icon treeIcon;
            private final Icon meshIcon;
            private final Icon annotationIcon;
            private boolean iconVisible;

            CustomListRenderer(final CheckboxListEditable checkboxListEditable,
                               final DefaultListCellRenderer templateInstance) {
                super();
                // Apply properties from templateInstance
                setBorder(templateInstance.getBorder());
                treeIcon = IconFactory.listIcon(checkboxListEditable, GLYPH.TREE);
                meshIcon = IconFactory.listIcon(checkboxListEditable, GLYPH.CUBE);
                annotationIcon = IconFactory.listIcon(checkboxListEditable, GLYPH.MARKER);
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
            final int size = getSize();
            if (size <= 2) return; // 0-1 items + ALL_ENTRY: nothing to sort
            final List<T> list = new ArrayList<>(size - 1);
            for (int i = 0; i < size; i++) {
                final T o = get(i);
                if (!CheckBoxList.ALL_ENTRY.equals(o))
                    list.add(o);
            }
            list.sort(Comparator.comparing(T::toString));
            // Rewrite elements in place: sorted items first, ALL_ENTRY last
            setListenersEnabled(false);
            for (int i = 0; i < list.size(); i++) {
                set(i, list.get(i));
            }
            set(size - 1, (T) CheckBoxList.ALL_ENTRY);
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
                super.fireIntervalRemoved(source, index0, index1);
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
        if (measureUI != null) {
            measureUI.dispose();
            measureUI = null;
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
        return switch (compartment.toLowerCase().substring(0, 2)) {
            case "ax" -> ShapeTree.AXON;
            case "ap", "ba", "(b", "de" -> ShapeTree.DENDRITE;
            default -> ShapeTree.ANY;
        };
    }

    private void toggleControlPanel() {
        if (frame.hasManager()) {
            if (frame.isManagerVisible()) {
                frame.hideManagerPanel();
            } else {
                frame.showManagerPanel();
            }
        } else
            displayMsg("Controls are not available for this viewer");
    }

    private class MultiTreeShapeTree extends ShapeTree {

        final Collection<Tree> trees;

        MultiTreeShapeTree(final Collection<Tree> trees) {
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

        final Tree tree;
        private ArborVBO arborVBO;
        private Wireframeable somaSubShape;
        private Coord3d translationReset;

        ShapeTree(final Tree tree) {
            super();
            this.tree = tree;
            translationReset = new Coord3d(0f,0f,0f);
        }

        @Override
        public boolean isDisplayed() {
            return ((somaSubShape != null) && somaSubShape.isDisplayed()) ||
                    ((arborVBO != null) && arborVBO.isDisplayed());
        }

        @Override
        public void setDisplayed(final boolean displayed) {
            get();
            super.setDisplayed(displayed);
        }

        void setSomaDisplayed(final boolean displayed) {
            if (somaSubShape != null) somaSubShape.setDisplayed(displayed);
        }

        void setArborDisplayed(final boolean displayed) {
            if (arborVBO != null) arborVBO.setDisplayed(displayed);
        }

        Shape get() {
            if (components == null || components.isEmpty()) assembleShape();
            return this;
        }

        void translateTo(final Coord3d destination) {
            final Transform tTransform = new Transform(new Translate(destination));
            // ArborVBO uses model matrix; soma uses vertex transform
            if (arborVBO != null) arborVBO.applyGeometryTransform(tTransform);
            if (somaSubShape != null) ((Drawable) somaSubShape).applyGeometryTransform(tTransform);
            translationReset.subSelf(destination);
        }

        void resetTranslation() {
            translateTo(translationReset);
            translationReset = new Coord3d(0f, 0f, 0f);
        }

        private void assembleShape() {

            final List<SWCPoint> somaPoints = new ArrayList<>();
            final List<java.awt.Color> somaColors = new ArrayList<>();
            final boolean validSoma = tree.validSoma();
            final Color defaultFallbackColor = getDefColor().alpha(1f);

            // Collect soma data (soma is still rendered with Sphere/Tube)
            if (validSoma) {
                for (final Path p : tree.list()) {
                    if (Path.SWC_SOMA != p.getSWCType()) continue;
                    for (int i = 0; i < p.size(); i++) {
                        final PointInImage pim = p.getNode(i);
                        final SWCPoint swcPoint = new SWCPoint(-1, Path.SWC_SOMA, pim.x, pim.y, pim.z,
                                p.getNodeRadius(i), -1);
                        somaPoints.add(swcPoint);
                    }
                    if (p.hasNodeColors()) {
                        somaColors.addAll(Arrays.asList(p.getNodeColors()));
                    } else {
                        somaColors.add(p.getColor());
                    }
                }
            }

            // Pack arbor paths into a VBO
            arborVBO = new ArborVBO();
            arborVBO.setUpsamplingFactor(upsamplingFactorPref);
            arborVBO.setTreeData(tree, defaultFallbackColor, defThickness);
            if (arborVBO.vertexCount > 0) {
                arborVBO.setTubeMode(tubeModeEnabled);
                arborVBO.setTubeSides(tubeSidesPref);
                arborVBO.setTubeWireframe(tubeWireframeEnabled);
                arborVBO.setDepthFog(depthFogEnabled);
                arborVBO.setFogIntensity(fogIntensityPref);
                arborVBO.setPseudoLighting(pseudoLightingEnabled);
                add(arborVBO);
            } else {
                arborVBO = null;
            }

            assembleSoma(somaPoints, somaColors);
            if (somaSubShape != null) add(somaSubShape);
        }

        private void assembleSoma(final List<SWCPoint> somaPoints,
                                  final List<java.awt.Color> somaColors)
        {
            final Color color = fromAWTColor(SNTColor.average(somaColors));
            switch (somaPoints.size()) {
                case 0 -> {
                    //SNT.log(tree.getLabel() + ": No soma attribute");
                    somaSubShape = null;
                    return;
                }
                case 1 -> {
                    // single point soma: http://neuromorpho.org/SomaFormat.html
                    somaSubShape = sphere(somaPoints.getFirst(), color);
                    return;
                }
                case 3 -> {
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
                }
                default -> {
                    // just create a centroid sphere
                    somaSubShape = sphere(SNTPoint.average(somaPoints), color);
                    return;
                }
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
            final double r = (center instanceof SWCPoint) ? center.radius : center.v;
            final float treeThickness = (arborVBO == null) ? defThickness : arborVBO.getWidth();
            final float radius = (float) Math.max(r, SOMA_SCALING_FACTOR * treeThickness);
            s.setVolume(radius);
            setWireFrame(s, radius, color);
            return s;
        }

        void rebuildShape() {
            if (isDisplayed()) {
                clear();
                assembleShape();
                // Reset so jzy3d re-mounts the new child VBOs on next render
                hasMountedOnce = false;
            }
        }

        /** Invalidates GL resources so they are re-uploaded when added to a new GL context. */
        void invalidateGL() {
            if (arborVBO != null) arborVBO.invalidateGL();
        }

        void setSomaRadius(final float radius) {
            if (somaSubShape != null && somaSubShape instanceof Sphere)
                ((Sphere)somaSubShape).setVolume(radius);
        }

        private void setThickness(final float thickness, final int type) {
            if (arborVBO == null) return;
            if (type == ShapeTree.ANY) {
                arborVBO.setWidth(thickness);
            } else {
                arborVBO.setCompartmentWidth(type, thickness);
            }
        }

        private void setArborColor(final ColorRGB color, final int type) {
            setArborColor(fromColorRGB(color), type);
        }

        private void setArborColor(final Color color, final int type) {
            if (arborVBO == null) return;
            if (type == ANY) {
                arborVBO.setArborWireframeColor(color);
            } else {
                arborVBO.fillCompartmentColor(type, color);
            }
        }

        private Color getArborColor() {
            if (arborVBO == null) return null;
            final Color effective = arborVBO.getEffectiveColor();
            return (effective != null) ? effective : fromColorRGB(tree.getColor());
        }

        void setTubeMode(final boolean enabled) {
            if (arborVBO != null) arborVBO.setTubeMode(enabled);
        }

        boolean isTubeMode() {
            return arborVBO != null && arborVBO.isTubeMode();
        }

        void setTubeWireframe(final boolean enabled) {
            if (arborVBO != null) arborVBO.setTubeWireframe(enabled);
        }

        void setTubeSides(final int sides) {
            if (arborVBO != null) arborVBO.setTubeSides(sides);
        }

        void setRadiusScale(final float scale) {
            if (arborVBO != null) arborVBO.setRadiusScale(scale);
        }

        float getRadiusScale() {
            return arborVBO != null ? arborVBO.getRadiusScale() : 1.0f;
        }

        void setDepthFog(final boolean enabled) {
            if (arborVBO != null) arborVBO.setDepthFog(enabled);
        }

        boolean isDepthFog() {
            return arborVBO != null && arborVBO.isDepthFog();
        }

        void setFogIntensity(final float intensity) {
            if (arborVBO != null) arborVBO.setFogIntensity(intensity);
        }

        void setPseudoLighting(final boolean enabled) {
            if (arborVBO != null) arborVBO.setPseudoLighting(enabled);
        }

        boolean isPseudoLighting() {
            return arborVBO != null && arborVBO.isPseudoLighting();
        }

        void setUpsamplingFactor(final int factor) {
            if (arborVBO != null) arborVBO.setUpsamplingFactor(factor);
        }

        int getUpsamplingFactor() {
            return arborVBO != null ? arborVBO.getUpsamplingFactor() : 1;
        }

        private Color getSomaColor() {
            return (somaSubShape == null) ? null : somaSubShape.getWireframeColor();
        }

        private void setSomaColor(final Color color) {
            if (somaSubShape != null) somaSubShape.setWireframeColor(color);
        }

        void setSomaColor(final ColorRGB color) {
            setSomaColor(fromColorRGB(color));
        }

        double[] colorize(final String measurement,
                          final ColorTable colorTable)
        {
            final TreeColorMapper colorizer = new TreeColorMapper();
            colorizer.map(tree, measurement, colorTable);
            rebuildShape();
            return colorizer.getMinMax();
        }

    }

    /**
     * VBO-based drawable for tree arbors. Packs all line strips from a tree
     * into a single GPU buffer using GL_LINE_STRIP with primitive restart,
     * reducing thousands of immediate-mode draw calls to a handful of VBO draws.
     * <p>
     * Vertex layout per vertex: [x, y, z, r, g, b, a, radius, tx, ty, tz] (11 floats, 44 bytes).
     * Paths are separated by a primitive restart index in the element buffer.
     * Vertices are grouped by SWC compartment type so that per-compartment
     * color and thickness overrides can be applied with separate draw ranges.
     */
    private static class ArborVBO extends DrawableVBO {

        /** Floats per vertex: x, y, z, r, g, b, a, radius, tx, ty, tz, nx, ny, nz */
        private static final int FLOATS_PER_VERTEX = 14;
        private static final int STRIDE = FLOATS_PER_VERTEX * Buffers.SIZEOF_FLOAT;
        private static final int COLOR_OFFSET = 3 * Buffers.SIZEOF_FLOAT;
        private static final int RADIUS_OFFSET = 7 * Buffers.SIZEOF_FLOAT;
        private static final int TANGENT_OFFSET = 8 * Buffers.SIZEOF_FLOAT;
        private static final int NORMAL_OFFSET = 11 * Buffers.SIZEOF_FLOAT;

        /** Default radius used when SWC data has no radii. */
        private static final float DEFAULT_RADIUS = 1.0f;

        /** Per-compartment list of per-path draw ranges: [firstVertex, vertexCount]. */
        private final Map<Integer, List<int[]>> compartmentRanges = new LinkedHashMap<>();
        /** Per-compartment wireframe color override (null = use per-vertex colors). */
        private final Map<Integer, Color> compartmentColors = new HashMap<>();
        /** Per-compartment line width override (0 = use global width). */
        private final Map<Integer, Float> compartmentWidths = new HashMap<>();
        /** Total number of vertices (excluding restart markers). */
        private int vertexCount;
        /** Raw vertex data kept for color re-upload. */
        private float[] vertexData;
        /** Whether the color portion of the buffer needs re-upload. */
        private volatile boolean colorDirty;
        /** Uniform wireframe color override for the entire arbor (null = per-vertex). */
        private Color wireframeColor;

        /** Catmull-Rom upsampling factor: 1 = no upsampling, N = N-1 interpolated points per segment. */
        private int upsamplingFactor = 1;

        /** Whether to apply depth fog to lines. */
        private boolean depthFog;
        /** Fog intensity: 0 = subtle, 1 = aggressive. Controls fog range. */
        private float fogIntensity = DEFAULT_FOG_INTENSITY;

        /** Whether to render tubes instead of lines. */
        private boolean tubeMode;
        /** Whether to overlay wireframe edges on tubes. */
        private boolean tubeWireframe;
        /** Number of sides for tube cross-section (default 12). */
        private int tubeSides = 12;
        /** Global multiplier applied to per-vertex radii in tube mode. */
        private float radiusScale = 1.0f;
        /** Shared shader program handle (0 = not compiled). */
        private static int shaderProgram;
        /** Whether shader compilation has been attempted. */
        private static boolean shaderInitAttempted;
        /** User-visible message from last shader init attempt. */
        private static String shaderInitMessage;

        /** Maximum tube cross-section sides (compile-time limit for geometry shader). */
        private static final int MAX_TUBE_SIDES = 16;
        /** Geometry shader max_vertices = 2 * (MAX_TUBE_SIDES + 1). */
        private static final int MAX_GS_VERTICES = 2 * (MAX_TUBE_SIDES + 1);
        /** Generic vertex attribute indices. */
        private static final int ATTRIB_POSITION = 0;
        private static final int ATTRIB_COLOR = 1;
        private static final int ATTRIB_RADIUS = 2;
        private static final int ATTRIB_TANGENT = 3;
        private static final int ATTRIB_REFNORMAL = 4;

        private static final String VERT_SHADER =
                """
                #version 150
                in vec3 aPosition;
                in vec4 aColor;
                in float aRadius;
                in vec3 aRefNormal;
                uniform bool uUseColorOverride;
                uniform vec4 uColorOverride;
                out vec4 vsColor;
                out float vsRadius;
                out vec3 vsRefNormal;
                void main() {
                    gl_Position = vec4(aPosition, 1.0);
                    vsColor = uUseColorOverride ? uColorOverride : aColor;
                    vsRadius = aRadius;
                    vsRefNormal = aRefNormal;
                }
                """;

        private static final String GEOM_SHADER = """
                #version 150
                layout(lines) in;
                layout(triangle_strip, max_vertices = %d) out;
                uniform mat4 uMVP;
                uniform mat3 uNormalMatrix;
                uniform int uTubeSides;
                uniform float uRadiusScale;
                in vec4 vsColor[];
                in float vsRadius[];
                in vec3 vsRefNormal[];
                out vec4 gsColor;
                out vec3 gsNormal;
                const float PI = 3.14159265;
                void main() {
                    vec3 p0 = gl_in[0].gl_Position.xyz;
                    vec3 p1 = gl_in[1].gl_Position.xyz;
                    vec3 axis = p1 - p0;
                    float len = length(axis);
                    if (len < 1e-8) return;
                    axis /= len;
                    // Use parallel-transport reference normals from VBO
                    vec3 u0 = normalize(vsRefNormal[0] - axis * dot(vsRefNormal[0], axis));
                    vec3 v0 = cross(axis, u0);
                    vec3 u1 = normalize(vsRefNormal[1] - axis * dot(vsRefNormal[1], axis));
                    vec3 v1 = cross(axis, u1);
                    float r0 = vsRadius[0] * uRadiusScale;
                    float r1 = vsRadius[1] * uRadiusScale;
                    int sides = clamp(uTubeSides, 3, %d);
                    for (int i = 0; i <= sides; i++) {
                        float a = 2.0 * PI * float(i) / float(sides);
                        float c = cos(a);
                        float s = sin(a);
                        vec3 n0 = c * u0 + s * v0;
                        gsNormal = uNormalMatrix * n0;
                        gsColor = vsColor[0];
                        gl_Position = uMVP * vec4(p0 + r0 * n0, 1.0);
                        EmitVertex();
                        vec3 n1 = c * u1 + s * v1;
                        gsNormal = uNormalMatrix * n1;
                        gsColor = vsColor[1];
                        gl_Position = uMVP * vec4(p1 + r1 * n1, 1.0);
                        EmitVertex();
                    }
                    EndPrimitive();
                }
                """.formatted(MAX_GS_VERTICES, MAX_TUBE_SIDES);

        private static final String FRAG_SHADER =
            """
            #version 150
            in vec4 gsColor;
            in vec3 gsNormal;
            out vec4 fragColor;
            void main() {
                vec3 N = normalize(gsNormal);
                vec3 L = normalize(vec3(0.0, 0.0, 1.0));
                float NdotL = max(dot(N, L), 0.0);
                // Hemispherical ambient: blend between ground and sky
                // based on normal's vertical component
                vec3 skyColor = gsColor.rgb * 0.6;
                vec3 groundColor = gsColor.rgb * 0.35;
                float hemiBlend = 0.5 + 0.5 * N.y;
                vec3 ambient = mix(groundColor, skyColor, hemiBlend);
                // Diffuse from key light
                vec3 diffuse = gsColor.rgb * NdotL;
                vec3 lit = ambient + 0.55 * diffuse;
                fragColor = vec4(min(lit, vec3(1.0)), gsColor.a);
            }
            """;
        private static final String LIT_VERT_SHADER =
            """
            #version 120
            attribute vec3 aTangent;
            uniform bool uUseColorOverride;
            uniform vec4 uColorOverride;
            varying vec4 vColor;
            varying vec3 vTangentEye;
            void main() {
                gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
                vColor = uUseColorOverride ? uColorOverride : gl_Color;
                // Transform tangent to eye-space (direction, no translation)
                vTangentEye = mat3(gl_ModelViewMatrix) * aTangent;
            }
            """;
        private static final String LIT_FRAG_SHADER = """
                #version 120
                varying vec4 vColor;
                varying vec3 vTangentEye;
                void main() {
                    vec3 T = normalize(vTangentEye);
                    // View direction is (0,0,-1) in eye-space;
                    // brightness = how perpendicular the tangent is to the view
                    float alignment = abs(T.z); // 1 = pointing at camera, 0 = perpendicular
                    float brightness = 1.0 - 0.6 * alignment; // range: 0.4 (toward cam) to 1.0 (perpendicular)
                    vec3 lit = vColor.rgb * brightness;
                    gl_FragColor = vec4(lit, vColor.a);
                }
                """;

        // Pseudo-lighting shaders (GL 2.1 / GLSL 120)
        // Modulates line brightness based on tangent-to-view angle:
        // segments perpendicular to the view are brightest, segments
        // pointing toward/away from the camera are darkest.
        private static int litShaderProgram;
        private static boolean litShaderInitAttempted;
        private boolean pseudoLighting;

        ArborVBO() {
            super(new ArborVBOLoader());
            ((ArborVBOLoader) loader).vbo = this;
            geometry = GL.GL_LINE_STRIP;
            setPolygonOffsetFillEnable(false);
            setHasColorBuffer(true);
            colorChannelNumber = 4; // RGBA
        }

        void setDepthFog(final boolean enabled) {
            this.depthFog = enabled;
        }

        boolean isDepthFog() {
            return depthFog;
        }

        void setFogIntensity(final float intensity) {
            this.fogIntensity = Math.max(0f, Math.min(1f, intensity));
        }

        float getFogIntensity() {
            return fogIntensity;
        }

        /** Enables or disables tube rendering. Falls back to lines if shaders unavailable. */
        void setTubeMode(final boolean enabled) {
            this.tubeMode = enabled;
        }

        boolean isTubeMode() {
            return tubeMode;
        }

        void setTubeWireframe(final boolean enabled) {
            this.tubeWireframe = enabled;
        }

        boolean isTubeWireframe() {
            return tubeWireframe;
        }

        /** Sets the number of sides for the tube cross-section (clamped 3-16). */
        void setTubeSides(final int sides) {
            this.tubeSides = Math.max(3, Math.min(MAX_TUBE_SIDES, sides));
        }

        /** Sets the global radius multiplier for tube mode. */
        void setRadiusScale(final float scale) {
            this.radiusScale = scale;
        }

        float getRadiusScale() {
            return radiusScale;
        }

        void setPseudoLighting(final boolean enabled) {
            this.pseudoLighting = enabled;
        }

        boolean isPseudoLighting() {
            return pseudoLighting;
        }

        /**
         * Sets the Catmull-Rom upsampling factor. 1 = no interpolation (default),
         * N = insert N-1 intermediate points per segment for smoother rendering.
         * Requires re-uploading vertex data via {@link #setTreeData}.
         */
        void setUpsamplingFactor(final int factor) {
            this.upsamplingFactor = Math.max(1, Math.min(10, factor));
        }

        int getUpsamplingFactor() {
            return upsamplingFactor;
        }

        /** Compiles and links the tube shader program. Returns true on success. */
        private boolean initShaders(final GL2 gl2) {
            if (shaderInitAttempted) return shaderProgram != 0;
            shaderInitAttempted = true;
            // Geometry shaders require a true GL3 context. On macOS, Apple's Metal-based
            // OpenGL layer caps at GL 2.1 regardless of hardware!?, so this will always
            // fail there. Log the actual runtime version for diagnostics.
            if (!gl2.isGL3()) {
                shaderInitMessage = "Tube rendering requires OpenGL 3.2+. Reported version: "
                        + gl2.glGetString(GL2.GL_VERSION) + " (" + gl2.glGetString(GL2.GL_RENDERER) + ").";
                SNTUtils.log("ArborVBO: " + shaderInitMessage);
                return false;
            }
            final GL2GL3 gl3 = gl2.getGL3();
            try {
                final int vs = compileShader(gl3, GL2.GL_VERTEX_SHADER, VERT_SHADER);
                final int gs = compileShader(gl3, GL3.GL_GEOMETRY_SHADER, GEOM_SHADER);
                final int fs = compileShader(gl3, GL2.GL_FRAGMENT_SHADER, FRAG_SHADER);
                if (vs == 0 || gs == 0 || fs == 0) {
                    shaderInitMessage = "Tube rendering requires OpenGL 3.2+ (geometry shaders).";
                    SNTUtils.log("ArborVBO: " + shaderInitMessage);
                    return false;
                }
                final int prog = gl3.glCreateProgram();
                gl3.glAttachShader(prog, vs);
                gl3.glAttachShader(prog, gs);
                gl3.glAttachShader(prog, fs);
                gl3.glBindAttribLocation(prog, ATTRIB_POSITION, "aPosition");
                gl3.glBindAttribLocation(prog, ATTRIB_COLOR, "aColor");
                gl3.glBindAttribLocation(prog, ATTRIB_RADIUS, "aRadius");
                gl3.glBindAttribLocation(prog, ATTRIB_REFNORMAL, "aRefNormal");
                gl3.glLinkProgram(prog);
                final int[] status = new int[1];
                gl3.glGetProgramiv(prog, GL2.GL_LINK_STATUS, status, 0);
                if (status[0] == GL.GL_FALSE) {
                    final int[] len = new int[1];
                    gl3.glGetProgramiv(prog, GL2.GL_INFO_LOG_LENGTH, len, 0);
                    final byte[] log = new byte[len[0]];
                    gl3.glGetProgramInfoLog(prog, len[0], null, 0, log, 0);
                    shaderInitMessage = "Tube shader link failed: " + new String(log).trim();
                    SNTUtils.log("ArborVBO: " + shaderInitMessage);
                    gl3.glDeleteProgram(prog);
                    return false;
                }
                gl3.glDetachShader(prog, vs);
                gl3.glDetachShader(prog, gs);
                gl3.glDetachShader(prog, fs);
                gl3.glDeleteShader(vs);
                gl3.glDeleteShader(gs);
                gl3.glDeleteShader(fs);
                shaderProgram = prog;
                return true;
            } catch (final GLException e) {
                shaderInitMessage = "GL3 not available for tube shaders: " + e.getMessage();
                SNTUtils.log("ArborVBO: " + shaderInitMessage);
                return false;
            }
        }

        private static int compileShader(final GL2GL3 gl, final int type, final String source) {
            final int shader = gl.glCreateShader(type);
            gl.glShaderSource(shader, 1, new String[]{ source }, null, 0);
            gl.glCompileShader(shader);
            final int[] status = new int[1];
            gl.glGetShaderiv(shader, GL2.GL_COMPILE_STATUS, status, 0);
            if (status[0] == GL.GL_FALSE) {
                final int[] len = new int[1];
                gl.glGetShaderiv(shader, GL2.GL_INFO_LOG_LENGTH, len, 0);
                final byte[] log = new byte[len[0]];
                gl.glGetShaderInfoLog(shader, len[0], null, 0, log, 0);
                SNTUtils.log("ArborVBO: shader compile error: " + new String(log));
                gl.glDeleteShader(shader);
                return 0;
            }
            return shader;
        }

        /** Compiles and links the pseudo-lighting shader (GL 2.1). Returns true on success. */
        private boolean initLitShaders(final GL2 gl2) {
            if (litShaderInitAttempted) return litShaderProgram != 0;
            litShaderInitAttempted = true;
            final int vs = compileShader(gl2, GL2.GL_VERTEX_SHADER, LIT_VERT_SHADER);
            final int fs = compileShader(gl2, GL2.GL_FRAGMENT_SHADER, LIT_FRAG_SHADER);
            String litShaderInitMessage;
            if (vs == 0 || fs == 0) {
                litShaderInitMessage = "Pseudo-lighting shaders failed to compile. Falling back to plain lines.";
                SNTUtils.log("ArborVBO: " + litShaderInitMessage);
                return false;
            }
            final int prog = gl2.glCreateProgram();
            gl2.glAttachShader(prog, vs);
            gl2.glAttachShader(prog, fs);
            // aTangent uses a generic attribute; position and color use fixed-function
            gl2.glBindAttribLocation(prog, ATTRIB_TANGENT, "aTangent");
            gl2.glLinkProgram(prog);
            final int[] status = new int[1];
            gl2.glGetProgramiv(prog, GL2.GL_LINK_STATUS, status, 0);
            if (status[0] == GL.GL_FALSE) {
                final int[] len = new int[1];
                gl2.glGetProgramiv(prog, GL2.GL_INFO_LOG_LENGTH, len, 0);
                final byte[] log = new byte[len[0]];
                gl2.glGetProgramInfoLog(prog, len[0], null, 0, log, 0);
                litShaderInitMessage = "Pseudo-lighting shader link failed: " + new String(log).trim();
                SNTUtils.log("ArborVBO: " + litShaderInitMessage);
                gl2.glDeleteProgram(prog);
                return false;
            }
            gl2.glDetachShader(prog, vs);
            gl2.glDetachShader(prog, fs);
            gl2.glDeleteShader(vs);
            gl2.glDeleteShader(fs);
            litShaderProgram = prog;
            return true;
        }

        /** Populates vertex data from the tree's paths. */
        void setTreeData(final Tree tree, final Color defaultFallbackColor, final float defThickness) {
            compartmentRanges.clear();
            compartmentColors.clear();
            compartmentWidths.clear();

            final int N = upsamplingFactor; // shorthand

            // First pass: count vertices and group paths by compartment
            final Map<Integer, List<Path>> pathsByType = new LinkedHashMap<>();
            int totalVertices = 0;
            for (final Path p : tree.list()) {
                if (p.getSWCType() == Path.SWC_SOMA) continue;
                final int type = (p.getSWCType() == Path.SWC_APICAL_DENDRITE)
                        ? Path.SWC_DENDRITE : p.getSWCType();
                pathsByType.computeIfAbsent(type, k -> new ArrayList<>()).add(p);
                // With upsampling: each of (size-1) segments produces N sub-segments,
                // giving (size-1)*N + 1 vertices. Without upsampling (N=1): size vertices.
                final int pathVerts = (p.size() > 1) ? (p.size() - 1) * N + 1 : p.size();
                totalVertices += pathVerts;
                if (p.getBranchPoint() != null) {
                    // Branch-point joint adds 1 real vertex + (N-1) interpolated
                    // to smooth the transition to the first node
                    totalVertices += N;
                }
            }

            if (totalVertices == 0) {
                vertexCount = 0;
                vertexData = new float[0];
                return;
            }

            vertexData = new float[totalVertices * FLOATS_PER_VERTEX];

            int vi = 0; // vertex index
            float xMin = Float.MAX_VALUE, yMin = Float.MAX_VALUE, zMin = Float.MAX_VALUE;
            float xMax = -Float.MAX_VALUE, yMax = -Float.MAX_VALUE, zMax = -Float.MAX_VALUE;

            for (final Map.Entry<Integer, List<Path>> entry : pathsByType.entrySet()) {
                final int compartmentType = entry.getKey();
                final List<int[]> pathRanges = new ArrayList<>();
                final List<Path> paths = entry.getValue();

                for (final Path p : paths) {
                    final int pathStart = vi;
                    final boolean hasNodeColors = p.hasNodeColors();
                    final java.awt.Color pathColor = hasNodeColors ? null : p.getColor();
                    final boolean hasRadii = p.hasRadii();
                    final PointInImage branchPoint = p.getBranchPoint();

                    // Build an array of control points for this path
                    // (optionally prepended with the branch point)
                    final int bpOffset = (branchPoint != null) ? 1 : 0;
                    final int cpCount = bpOffset + p.size();
                    final float[] cpx = new float[cpCount];
                    final float[] cpy = new float[cpCount];
                    final float[] cpz = new float[cpCount];
                    final float[] cpr = new float[cpCount]; // radius
                    final float[][] cpc = new float[cpCount][4]; // RGBA

                    if (branchPoint != null) {
                        cpx[0] = (float) branchPoint.x;
                        cpy[0] = (float) branchPoint.y;
                        cpz[0] = (float) branchPoint.z;
                        cpr[0] = hasRadii ? (float) p.getNodeRadius(0) : DEFAULT_RADIUS;
                        final java.awt.Color c0 = hasNodeColors ? p.getNodeColor(0) : pathColor;
                        packColorToArray(cpc[0], c0, defaultFallbackColor);
                    }

                    for (int i = 0; i < p.size(); i++) {
                        final PointInImage pim = p.getNode(i);
                        final int ci = bpOffset + i;
                        cpx[ci] = (float) pim.x;
                        cpy[ci] = (float) pim.y;
                        cpz[ci] = (float) pim.z;
                        cpr[ci] = hasRadii ? (float) p.getNodeRadius(i) : DEFAULT_RADIUS;
                        final java.awt.Color c = hasNodeColors ? p.getNodeColor(i) : pathColor;
                        packColorToArray(cpc[ci], c, defaultFallbackColor);
                    }

                    // Emit vertices with Catmull-Rom interpolation on positions,
                    // linear interpolation on color/radius.
                    // Each segment emits N sub-steps (j=0..N). To avoid duplicates
                    // at boundaries: seg>0 skips j=0, non-final segs skip j=N.
                    final int lastSeg = cpCount - 2;
                    for (int seg = 0; seg <= lastSeg; seg++) {
                        final int i0 = Math.max(seg - 1, 0);
                        final int i1 = seg;
                        final int i2 = seg + 1;
                        final int i3 = Math.min(seg + 2, cpCount - 1);

                        // Emit j=0..N for first segment, j=1..N for subsequent segments.
                        // j=N (t=1) of segment K = control point K+1 = j=0 (t=0) of segment K+1.
                        final int jStart = (seg == 0) ? 0 : 1;
                        for (int j = jStart; j <= N; j++) {
                            final float t = (float) j / N;

                            // Catmull-Rom spline (position)
                            final float px = catmullRom(cpx[i0], cpx[i1], cpx[i2], cpx[i3], t);
                            final float py = catmullRom(cpy[i0], cpy[i1], cpy[i2], cpy[i3], t);
                            final float pz = catmullRom(cpz[i0], cpz[i1], cpz[i2], cpz[i3], t);

                            // Linear interpolation for radius and color
                            final float radius = cpr[i1] + t * (cpr[i2] - cpr[i1]);
                            final int off = vi * FLOATS_PER_VERTEX;
                            vertexData[off] = px;
                            vertexData[off + 1] = py;
                            vertexData[off + 2] = pz;
                            vertexData[off + 3] = cpc[i1][0] + t * (cpc[i2][0] - cpc[i1][0]);
                            vertexData[off + 4] = cpc[i1][1] + t * (cpc[i2][1] - cpc[i1][1]);
                            vertexData[off + 5] = cpc[i1][2] + t * (cpc[i2][2] - cpc[i1][2]);
                            vertexData[off + 6] = cpc[i1][3] + t * (cpc[i2][3] - cpc[i1][3]);
                            vertexData[off + 7] = (radius > 0) ? radius : DEFAULT_RADIUS;

                            xMin = Math.min(xMin, px);
                            yMin = Math.min(yMin, py);
                            zMin = Math.min(zMin, pz);
                            xMax = Math.max(xMax, px);
                            yMax = Math.max(yMax, py);
                            zMax = Math.max(zMax, pz);
                            vi++;
                        }
                    }

                    // Edge case: single-node path (no segments)
                    if (cpCount == 1) {
                        packVertex(vi, cpx[0], cpy[0], cpz[0], null, defaultFallbackColor, cpr[0]);
                        final int off = vi * FLOATS_PER_VERTEX;
                        vertexData[off + 3] = cpc[0][0];
                        vertexData[off + 4] = cpc[0][1];
                        vertexData[off + 5] = cpc[0][2];
                        vertexData[off + 6] = cpc[0][3];
                        xMin = Math.min(xMin, cpx[0]);
                        yMin = Math.min(yMin, cpy[0]);
                        zMin = Math.min(zMin, cpz[0]);
                        xMax = Math.max(xMax, cpx[0]);
                        yMax = Math.max(yMax, cpy[0]);
                        zMax = Math.max(zMax, cpz[0]);
                        vi++;
                    }

                    pathRanges.add(new int[]{ pathStart, vi - pathStart });
                }
                compartmentRanges.put(compartmentType, pathRanges);
            }

            vertexCount = vi;
            bbox = new BoundingBox3d(xMin, xMax, yMin, yMax, zMin, zMax);
            width = defThickness;

            computeTangents();

            // Store for loader to upload on GL thread
            ((ArborVBOLoader) loader).pendingVertices = vertexData;
            ((ArborVBOLoader) loader).pendingBounds = bbox;
            hasMountedOnce = false; // force remount
        }

        /** Catmull-Rom spline interpolation between p1 and p2. */
        private static float catmullRom(final float p0, final float p1,
                                         final float p2, final float p3, final float t) {
            // Standard Catmull-Rom with tau=0.5
            final float t2 = t * t;
            final float t3 = t2 * t;
            return 0.5f * ((2f * p1)
                    + (-p0 + p2) * t
                    + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2
                    + (-p0 + 3f * p1 - 3f * p2 + p3) * t3);
        }

        /** Packs an AWT color (or fallback) into a float[4] RGBA array. */
        private static void packColorToArray(final float[] out,
                                              final java.awt.Color awtColor, final Color fallback) {
            if (awtColor != null) {
                out[0] = awtColor.getRed() / 255f;
                out[1] = awtColor.getGreen() / 255f;
                out[2] = awtColor.getBlue() / 255f;
                out[3] = awtColor.getAlpha() / 255f;
            } else {
                out[0] = fallback.r;
                out[1] = fallback.g;
                out[2] = fallback.b;
                out[3] = fallback.a;
            }
        }

        /**
         * Computes normalized tangent vectors for each vertex in each path.
         * The tangent at vertex i is the direction from vertex i to vertex i+1.
         * The last vertex in each path copies from the previous vertex.
         */
        private void computeTangents() {
            for (final List<int[]> pathRanges : compartmentRanges.values()) {
                for (final int[] range : pathRanges) {
                    final int first = range[0];
                    final int count = range[1];
                    if (count < 2) {
                        // Single-vertex path: tangent and normal stay zero
                        continue;
                    }
                    // Forward difference for tangents at vertices 0..n-2
                    for (int i = 0; i < count - 1; i++) {
                        final int cur = (first + i) * FLOATS_PER_VERTEX;
                        final int nxt = (first + i + 1) * FLOATS_PER_VERTEX;
                        float dx = vertexData[nxt] - vertexData[cur];
                        float dy = vertexData[nxt + 1] - vertexData[cur + 1];
                        float dz = vertexData[nxt + 2] - vertexData[cur + 2];
                        final float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                        if (len > 1e-8f) {
                            dx /= len;
                            dy /= len;
                            dz /= len;
                        }
                        vertexData[cur + 8] = dx;
                        vertexData[cur + 9] = dy;
                        vertexData[cur + 10] = dz;
                    }
                    // Last vertex copies tangent from previous
                    final int last = (first + count - 1) * FLOATS_PER_VERTEX;
                    final int prev = (first + count - 2) * FLOATS_PER_VERTEX;
                    vertexData[last + 8] = vertexData[prev + 8];
                    vertexData[last + 9] = vertexData[prev + 9];
                    vertexData[last + 10] = vertexData[prev + 10];

                    // Parallel transport reference normals for seamless tube rings.
                    // First vertex: pick an arbitrary normal perpendicular to tangent
                    final int v0 = first * FLOATS_PER_VERTEX;
                    float tx = vertexData[v0 + 8], ty = vertexData[v0 + 9], tz = vertexData[v0 + 10];
                    // Choose seed vector least aligned with tangent
                    float nx, ny, nz;
                    if (Math.abs(ty) < 0.9f) {
                        // cross(tangent, (0,1,0))
                        nx = tz; ny = 0f; nz = -tx;
                    } else {
                        // cross(tangent, (1,0,0))
                        nx = 0f; ny = -tz; nz = ty;
                    }
                    float nLen = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                    if (nLen > 1e-8f) { nx /= nLen; ny /= nLen; nz /= nLen; }
                    vertexData[v0 + 11] = nx;
                    vertexData[v0 + 12] = ny;
                    vertexData[v0 + 13] = nz;

                    // Propagate normal along the path via parallel transport
                    for (int i = 1; i < count; i++) {
                        final int cur = (first + i) * FLOATS_PER_VERTEX;
                        final int prv = (first + i - 1) * FLOATS_PER_VERTEX;
                        // Previous tangent and normal
                        final float t0x = vertexData[prv + 8], t0y = vertexData[prv + 9], t0z = vertexData[prv + 10];
                        final float n0x = vertexData[prv + 11], n0y = vertexData[prv + 12], n0z = vertexData[prv + 13];
                        // Current tangent
                        final float t1x = vertexData[cur + 8], t1y = vertexData[cur + 9], t1z = vertexData[cur + 10];
                        // Rotation axis = cross(t0, t1)
                        float ax = t0y * t1z - t0z * t1y;
                        float ay = t0z * t1x - t0x * t1z;
                        float az = t0x * t1y - t0y * t1x;
                        final float sinA = (float) Math.sqrt(ax * ax + ay * ay + az * az);
                        if (sinA < 1e-8f) {
                            // Tangents parallel: normal unchanged
                            vertexData[cur + 11] = n0x;
                            vertexData[cur + 12] = n0y;
                            vertexData[cur + 13] = n0z;
                        } else {
                            // Normalize rotation axis
                            ax /= sinA; ay /= sinA; az /= sinA;
                            final float cosA = t0x * t1x + t0y * t1y + t0z * t1z;
                            // Rodrigues' rotation: n' = n*cos + (axis x n)*sin + axis*(axis.n)*(1-cos)
                            final float cxnx = ay * n0z - az * n0y;
                            final float cxny = az * n0x - ax * n0z;
                            final float cxnz = ax * n0y - ay * n0x;
                            final float dot = ax * n0x + ay * n0y + az * n0z;
                            float rnx = n0x * cosA + cxnx * sinA + ax * dot * (1f - cosA);
                            float rny = n0y * cosA + cxny * sinA + ay * dot * (1f - cosA);
                            float rnz = n0z * cosA + cxnz * sinA + az * dot * (1f - cosA);
                            // Re-normalize to prevent drift
                            nLen = (float) Math.sqrt(rnx * rnx + rny * rny + rnz * rnz);
                            if (nLen > 1e-8f) { rnx /= nLen; rny /= nLen; rnz /= nLen; }
                            vertexData[cur + 11] = rnx;
                            vertexData[cur + 12] = rny;
                            vertexData[cur + 13] = rnz;
                        }
                    }
                }
            }
        }

        private void packVertex(final int vi, final float x, final float y, final float z,
                                final java.awt.Color awtColor, final Color fallback, final float radius) {
            final int off = vi * FLOATS_PER_VERTEX;
            vertexData[off] = x;
            vertexData[off + 1] = y;
            vertexData[off + 2] = z;
            if (awtColor != null) {
                vertexData[off + 3] = awtColor.getRed() / 255f;
                vertexData[off + 4] = awtColor.getGreen() / 255f;
                vertexData[off + 5] = awtColor.getBlue() / 255f;
                vertexData[off + 6] = awtColor.getAlpha() / 255f;
            } else {
                vertexData[off + 3] = fallback.r;
                vertexData[off + 4] = fallback.g;
                vertexData[off + 5] = fallback.b;
                vertexData[off + 6] = fallback.a;
            }
            vertexData[off + 7] = (radius > 0) ? radius : DEFAULT_RADIUS;
            // tangent at off+8..off+10 filled by computeTangents()
        }

        /** Sets a uniform wireframe color for all compartments. Null restores per-vertex colors. */
        void setArborWireframeColor(final Color color) {
            this.wireframeColor = color;
            compartmentColors.clear();
        }

        /** Sets the wireframe color for a specific compartment. */
        void setCompartmentColor(final int swcType, final Color color) {
            compartmentColors.put(swcType, color);
        }

        /** Gets the current effective wireframe color (uniform or first compartment override). */
        Color getEffectiveColor() {
            if (wireframeColor != null) return wireframeColor;
            if (!compartmentColors.isEmpty()) return compartmentColors.values().iterator().next();
            return null;
        }

        /** Sets the line width for a specific compartment. */
        void setCompartmentWidth(final int swcType, final float w) {
            compartmentWidths.put(swcType, w);
        }

        /**
         * Replaces per-vertex colors matching {@code from} with {@code to} and
         * marks the color buffer for re-upload on next draw.
         */
        void replaceColor(final Color from, final Color to) {
            if (vertexData == null) return;
            for (int i = 0; i < vertexCount; i++) {
                final int off = i * FLOATS_PER_VERTEX + 3;
                if (Math.abs(vertexData[off] - from.r) < 0.01f
                        && Math.abs(vertexData[off + 1] - from.g) < 0.01f
                        && Math.abs(vertexData[off + 2] - from.b) < 0.01f) {
                    vertexData[off] = to.r;
                    vertexData[off + 1] = to.g;
                    vertexData[off + 2] = to.b;
                }
            }
            colorDirty = true;
        }

        /** Overrides all per-vertex colors with the given color and marks for re-upload. */
        void fillColor(final Color color) {
            if (vertexData == null) return;
            for (int i = 0; i < vertexCount; i++) {
                final int off = i * FLOATS_PER_VERTEX + 3;
                vertexData[off] = color.r;
                vertexData[off + 1] = color.g;
                vertexData[off + 2] = color.b;
                vertexData[off + 3] = color.a;
            }
            colorDirty = true;
        }

        /** Fills per-vertex colors for a specific compartment and marks for re-upload. */
        void fillCompartmentColor(final int swcType, final Color color) {
            if (vertexData == null || !compartmentRanges.containsKey(swcType)) return;
            // We need to map compartment index range back to vertex indices.
            // This is not straightforward with restart markers. Use the compartment
            // color override instead (more efficient, no buffer upload).
            compartmentColors.put(swcType, color);
        }

        /** Invalidates the GL buffer handles so they are re-uploaded on the next draw call. */
        void invalidateGL() {
            hasMountedOnce = false;
        }

        @Override
        public void draw(final IPainter painter) {
            if (!hasMountedOnce) {
                mount(painter); // lazy mount for VBOs added after scene init
                if (!hasMountedOnce) return;
            }
            final GL gl = ((NativeDesktopPainter) painter).getGL();
            final GL2 gl2 = gl.getGL2();

            // Re-upload color data if dirty
            if (colorDirty && vertexData != null) {
                gl2.glBindBuffer(GL.GL_ARRAY_BUFFER, arrayName[0]);
                final FloatBuffer buf = FloatBuffer.wrap(vertexData);
                gl2.glBufferSubData(GL.GL_ARRAY_BUFFER, 0,
                        (long) vertexCount * FLOATS_PER_VERTEX * Buffers.SIZEOF_FLOAT, buf);
                gl2.glBindBuffer(GL.GL_ARRAY_BUFFER, 0);
                colorDirty = false;
            }

            doTransform(painter);

            // Bind vertex buffer
            gl2.glBindBuffer(GL.GL_ARRAY_BUFFER, arrayName[0]);

            // Decide rendering path
            final boolean useTubes = tubeMode && initShaders(gl2);
            final boolean useLit = !useTubes && pseudoLighting && initLitShaders(gl2);
            if (useTubes) {
                drawTubes(gl2);
            } else if (useLit) {
                drawLinesLit(gl, gl2);
            } else {
                drawLines(gl, gl2);
            }

            gl2.glBindBuffer(GL.GL_ARRAY_BUFFER, 0);
            doDrawBoundsIfDisplayed(painter);
        }

        /** Line-mode rendering (fixed-function pipeline). */
        private void drawLines(final GL gl, final GL2 gl2) {
            gl2.glEnableClientState(GLPointerFunc.GL_VERTEX_ARRAY);
            gl2.glVertexPointer(3, GL.GL_FLOAT, STRIDE, 0L);

            gl.glEnable(GL.GL_LINE_SMOOTH);
            gl.glHint(GL.GL_LINE_SMOOTH_HINT, GL.GL_NICEST);

            // Depth fog: fade distant geometry toward background color.
            // We derive the fog range from the actual eye-space depth extent
            // of the bounding box, not the frustum clip planes, so the fog
            // gradient is concentrated on the geometry actually is.
            if (depthFog && getBounds() != null && getBounds().valid()) {
                final float[] mv = new float[16];
                gl2.glGetFloatv(GL2.GL_MODELVIEW_MATRIX, mv, 0);
                final float[] depthRange = computeEyeDepthRange(mv, getBounds());
                final float geoNear = depthRange[0]; // closest geometry
                final float geoFar = depthRange[1];  // farthest geometry
                final float range = geoFar - geoNear;
                if (range > 1e-6f) {
                    // Fog spans the full geometry range but the farthest
                    // geometry is only partially fogged (not fully invisible).
                    // fogIntensity controls the max fog at geoFar:
                    //   0.0 → farthest is ~10% fogged (very subtle)
                    //   0.5 → farthest is ~55% fogged (moderate)
                    //   1.0 → farthest is 100% fogged (fully into background)
                    final float maxFog = 0.1f + fogIntensity * 0.9f;
                    final float fogStart = geoNear;
                    // Extend fogEnd beyond geoFar so fog fraction at geoFar = maxFog
                    // fraction = (geoFar - fogStart) / (fogEnd - fogStart) = maxFog
                    final float fogEnd = geoNear + range / maxFog;
                    final float[] fogColor = fogColorFromView(gl2);
                    gl2.glEnable(GL2.GL_FOG);
                    gl2.glFogi(GL2.GL_FOG_MODE, GL2.GL_LINEAR);
                    gl2.glFogf(GL2.GL_FOG_START, fogStart);
                    gl2.glFogf(GL2.GL_FOG_END, fogEnd);
                    gl2.glFogfv(GL2.GL_FOG_COLOR, fogColor, 0);
                }
            }

            for (final Map.Entry<Integer, List<int[]>> entry : compartmentRanges.entrySet()) {
                final int type = entry.getKey();
                final List<int[]> pathRanges = entry.getValue();

                final float w = compartmentWidths.getOrDefault(type, width);
                gl2.glLineWidth(w);

                final Color compColor = (wireframeColor != null)
                        ? wireframeColor : compartmentColors.get(type);
                if (compColor != null) {
                    gl2.glDisableClientState(GL2.GL_COLOR_ARRAY);
                    gl2.glColor4f(compColor.r, compColor.g, compColor.b, compColor.a);
                } else {
                    gl2.glEnableClientState(GL2.GL_COLOR_ARRAY);
                    gl2.glColorPointer(4, GL.GL_FLOAT, STRIDE, COLOR_OFFSET);
                }

                for (final int[] range : pathRanges) {
                    gl2.glDrawArrays(GL.GL_LINE_STRIP, range[0], range[1]);
                }
            }

            if (depthFog) {
                gl2.glDisable(GL2.GL_FOG);
            }

            gl2.glDisableClientState(GLPointerFunc.GL_VERTEX_ARRAY);
            gl2.glDisableClientState(GL2.GL_COLOR_ARRAY);
        }

        /**
         * Line-mode rendering with pseudo-lighting (GL 2.1 shader pipeline).
         * Uses the tangent-to-view angle to modulate brightness.
         */
        private void drawLinesLit(final GL gl, final GL2 gl2) {
            gl2.glUseProgram(litShaderProgram);

            // Set up fixed-function vertex + color pointers (used by gl_Vertex / gl_Color)
            gl2.glEnableClientState(GLPointerFunc.GL_VERTEX_ARRAY);
            gl2.glVertexPointer(3, GL.GL_FLOAT, STRIDE, 0L);

            // Tangent via generic attribute
            gl2.glEnableVertexAttribArray(ATTRIB_TANGENT);
            gl2.glVertexAttribPointer(ATTRIB_TANGENT, 3, GL.GL_FLOAT, false,
                    STRIDE, (long) TANGENT_OFFSET);

            gl.glEnable(GL.GL_LINE_SMOOTH);
            gl.glHint(GL.GL_LINE_SMOOTH_HINT, GL.GL_NICEST);

            final int uUseOverride = gl2.glGetUniformLocation(litShaderProgram, "uUseColorOverride");
            final int uOverride = gl2.glGetUniformLocation(litShaderProgram, "uColorOverride");

            for (final Map.Entry<Integer, List<int[]>> entry : compartmentRanges.entrySet()) {
                final int type = entry.getKey();
                final List<int[]> pathRanges = entry.getValue();

                final float w = compartmentWidths.getOrDefault(type, width);
                gl2.glLineWidth(w);

                final Color compColor = (wireframeColor != null)
                        ? wireframeColor : compartmentColors.get(type);
                if (compColor != null) {
                    gl2.glUniform1i(uUseOverride, 1);
                    gl2.glUniform4f(uOverride, compColor.r, compColor.g, compColor.b, compColor.a);
                    gl2.glDisableClientState(GL2.GL_COLOR_ARRAY);
                    gl2.glColor4f(compColor.r, compColor.g, compColor.b, compColor.a);
                } else {
                    gl2.glUniform1i(uUseOverride, 0);
                    gl2.glEnableClientState(GL2.GL_COLOR_ARRAY);
                    gl2.glColorPointer(4, GL.GL_FLOAT, STRIDE, (long) COLOR_OFFSET);
                }

                for (final int[] range : pathRanges) {
                    gl2.glDrawArrays(GL.GL_LINE_STRIP, range[0], range[1]);
                }
            }

            gl2.glDisableVertexAttribArray(ATTRIB_TANGENT);
            gl2.glDisableClientState(GLPointerFunc.GL_VERTEX_ARRAY);
            gl2.glDisableClientState(GL2.GL_COLOR_ARRAY);
            gl2.glUseProgram(0);
        }

        /** Reads the current GL clear color to use as fog color. */
        private static float[] fogColorFromView(final GL2 gl2) {
            final float[] clearColor = new float[4];
            gl2.glGetFloatv(GL2.GL_COLOR_CLEAR_VALUE, clearColor, 0);
            return clearColor;
        }

        /**
         * Transforms the 8 corners of the bounding box into eye-space and
         * returns {minZ, maxZ} as positive eye-space distances.
         */
        private static float[] computeEyeDepthRange(final float[] mv,
                                                     final BoundingBox3d box) {
            final float[] xs = { box.getXmin(), box.getXmax() };
            final float[] ys = { box.getYmin(), box.getYmax() };
            final float[] zs = { box.getZmin(), box.getZmax() };
            float minZ = Float.MAX_VALUE;
            float maxZ = -Float.MAX_VALUE;
            for (final float x : xs) {
                for (final float y : ys) {
                    for (final float z : zs) {
                        // eye-space z = row 2 of modelview dot (x,y,z,1)
                        // negate because GL eye-space looks down -Z
                        final float ez = -(mv[2] * x + mv[6] * y + mv[10] * z + mv[14]);
                        if (ez < minZ) minZ = ez;
                        if (ez > maxZ) maxZ = ez;
                    }
                }
            }
            // GL fog coordinate = positive eye-space distance
            minZ = Math.abs(minZ);
            maxZ = Math.abs(maxZ);
            return new float[]{ Math.min(minZ, maxZ), Math.max(minZ, maxZ) };
        }

        /** Tube-mode rendering (geometry shader pipeline). */
        private void drawTubes(final GL2 gl2) {
            gl2.glUseProgram(shaderProgram);

            // Query current fixed-function matrices and upload as uniforms
            final float[] mv = new float[16];
            final float[] proj = new float[16];
            gl2.glGetFloatv(GL2.GL_MODELVIEW_MATRIX, mv, 0);
            gl2.glGetFloatv(GL2.GL_PROJECTION_MATRIX, proj, 0);
            // MVP = projection * modelview (column-major)
            final float[] mvp = new float[16];
            for (int r = 0; r < 4; r++) {
                for (int c = 0; c < 4; c++) {
                    mvp[c * 4 + r] = 0;
                    for (int k = 0; k < 4; k++) {
                        mvp[c * 4 + r] += proj[k * 4 + r] * mv[c * 4 + k];
                    }
                }
            }
            gl2.glUniformMatrix4fv(gl2.glGetUniformLocation(shaderProgram, "uMVP"),
                    1, false, mvp, 0);
            // Normal matrix = transpose(inverse(upper-left 3x3 of modelview))
            final float[] nm = computeNormalMatrix(mv);
            gl2.glUniformMatrix3fv(gl2.glGetUniformLocation(shaderProgram, "uNormalMatrix"),
                    1, false, nm, 0);

            // Set other uniforms
            final int uTubeSidesLoc = gl2.glGetUniformLocation(shaderProgram, "uTubeSides");
            final int uRadiusScaleLoc = gl2.glGetUniformLocation(shaderProgram, "uRadiusScale");
            final int uUseOverrideLoc = gl2.glGetUniformLocation(shaderProgram, "uUseColorOverride");
            final int uOverrideLoc = gl2.glGetUniformLocation(shaderProgram, "uColorOverride");
            gl2.glUniform1i(uTubeSidesLoc, tubeSides);
            gl2.glUniform1f(uRadiusScaleLoc, radiusScale);

            // Set up generic vertex attributes for position, color, radius, reference normal
            gl2.glEnableVertexAttribArray(ATTRIB_POSITION);
            gl2.glVertexAttribPointer(ATTRIB_POSITION, 3, GL.GL_FLOAT, false,
                    STRIDE, 0L);
            gl2.glEnableVertexAttribArray(ATTRIB_COLOR);
            gl2.glVertexAttribPointer(ATTRIB_COLOR, 4, GL.GL_FLOAT, false,
                    STRIDE, COLOR_OFFSET);
            gl2.glEnableVertexAttribArray(ATTRIB_RADIUS);
            gl2.glVertexAttribPointer(ATTRIB_RADIUS, 1, GL.GL_FLOAT, false,
                    STRIDE, RADIUS_OFFSET);
            gl2.glEnableVertexAttribArray(ATTRIB_REFNORMAL);
            gl2.glVertexAttribPointer(ATTRIB_REFNORMAL, 3, GL.GL_FLOAT, false,
                    STRIDE, NORMAL_OFFSET);

            gl2.glEnable(GL.GL_DEPTH_TEST);

            // Pass 1: filled tubes
            drawTubePass(gl2, uUseOverrideLoc, uOverrideLoc, false);

            // Pass 2: wireframe overlay (if enabled)
            if (tubeWireframe) {
                gl2.glPolygonMode(GL.GL_FRONT_AND_BACK, GL2.GL_LINE);
                gl2.glEnable(GL2.GL_POLYGON_OFFSET_LINE);
                gl2.glPolygonOffset(-1f, -1f); // pull wireframe slightly forward
                drawTubePass(gl2, uUseOverrideLoc, uOverrideLoc, true);
                gl2.glDisable(GL2.GL_POLYGON_OFFSET_LINE);
                gl2.glPolygonMode(GL.GL_FRONT_AND_BACK, GL2.GL_FILL);
            }

            // Cleanup
            gl2.glDisableVertexAttribArray(ATTRIB_POSITION);
            gl2.glDisableVertexAttribArray(ATTRIB_COLOR);
            gl2.glDisableVertexAttribArray(ATTRIB_RADIUS);
            gl2.glDisableVertexAttribArray(ATTRIB_REFNORMAL);
            gl2.glUseProgram(0);
        }

        /** Draws all tube segments in either filled or wireframe mode. */
        private void drawTubePass(final GL2 gl2, final int uUseOverrideLoc,
                                   final int uOverrideLoc, final boolean wireframe) {
            for (final Map.Entry<Integer, List<int[]>> entry : compartmentRanges.entrySet()) {
                final int type = entry.getKey();
                final List<int[]> pathRanges = entry.getValue();

                Color compColor;
                if (wireframe) {
                    // Wireframe uses contrast color for visibility
                    final Color base = (wireframeColor != null)
                            ? wireframeColor : compartmentColors.get(type);
                    compColor = (base != null) ? Utils.contrastColor(base) : new Color(0.5f, 0.5f, 0.5f);
                } else {
                    compColor = (wireframeColor != null)
                            ? wireframeColor : compartmentColors.get(type);
                }

                if (compColor != null) {
                    gl2.glUniform1i(uUseOverrideLoc, 1);
                    gl2.glUniform4f(uOverrideLoc, compColor.r, compColor.g, compColor.b,
                            wireframe ? compColor.a * 0.8f : compColor.a);
                } else {
                    gl2.glUniform1i(uUseOverrideLoc, wireframe ? 1 : 0);
                    if (wireframe) {
                        // No per-vertex contrast possible; use mid-grey
                        gl2.glUniform4f(uOverrideLoc, 0.5f, 0.5f, 0.5f, 0.8f);
                    }
                }

                for (final int[] range : pathRanges) {
                    gl2.glDrawArrays(GL.GL_LINE_STRIP, range[0], range[1]);
                }
            }
        }

        /** Computes the normal matrix (transpose of inverse of upper-left 3x3 of modelview). */
        private static float[] computeNormalMatrix(final float[] mv) {
            // Extract 3x3
            final float a00 = mv[0], a01 = mv[4], a02 = mv[8];
            final float a10 = mv[1], a11 = mv[5], a12 = mv[9];
            final float a20 = mv[2], a21 = mv[6], a22 = mv[10];
            // Cofactors
            final float c00 = a11 * a22 - a12 * a21;
            final float c01 = a12 * a20 - a10 * a22;
            final float c02 = a10 * a21 - a11 * a20;
            final float c10 = a02 * a21 - a01 * a22;
            final float c11 = a00 * a22 - a02 * a20;
            final float c12 = a01 * a20 - a00 * a21;
            final float c20 = a01 * a12 - a02 * a11;
            final float c21 = a02 * a10 - a00 * a12;
            final float c22 = a00 * a11 - a01 * a10;
            final float det = a00 * c00 + a01 * c01 + a02 * c02;
            final float invDet = (Math.abs(det) < 1e-10f) ? 1f : 1f / det;
            // transpose(inverse) = cofactor / det (column-major layout for GL)
            return new float[]{
                    c00 * invDet, c10 * invDet, c20 * invDet,
                    c01 * invDet, c11 * invDet, c21 * invDet,
                    c02 * invDet, c12 * invDet, c22 * invDet
            };
        }

        @Override
        public void applyGeometryTransform(final Transform transform) {
            // Use model matrix via setTransformBefore() instead of modifying vertices
            setTransformBefore(transform);
        }

        @Override
        public void updateBounds() {
            // bounds are computed during setTreeData()
        }

        /** Loader that uploads the pending vertex data to GPU. */
        private static class ArborVBOLoader implements IGLLoader<DrawableVBO> {
            ArborVBO vbo;
            float[] pendingVertices;
            BoundingBox3d pendingBounds;

            @Override
            public void load(final IPainter painter, final DrawableVBO drawable) {
                if (pendingVertices == null) return;
                final GL gl = ((NativeDesktopPainter) painter).getGL();

                final FloatBuffer vertices = Buffers.newDirectFloatBuffer(pendingVertices);
                final int vertexSize = pendingVertices.length * Buffers.SIZEOF_FLOAT;

                // Configure with our custom stride (no normals, no element buffer)
                drawable.doConfigure(0, vbo.vertexCount,
                        STRIDE, 0, 3);

                drawable.doLoadArrayFloatBuffer(gl, vertexSize, vertices);
                drawable.doSetBoundingBox(pendingBounds);
            }
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
            boolean status;
            try {
                status = get();
                if (status) {
                    switch (type) {
                        case VALIDATE_SCENE -> validate();
                        case RELOAD_PREFS -> prefs.setPreferences();
                        default -> {
                        }
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
            return recordRotation(endAngle, nSteps, dir, RotationAxis.Z);
        }

        /**
         * Records a rotation around the specified axis. For Z-axis rotation, this
         * is a simple azimuth sweep (the classic behavior). For X or Y axes, we
         * use Rodrigues' rotation formula to trace a great-circle path in
         * spherical-coordinate space, explicitly computing each frame's viewpoint.
         */
        private boolean recordRotation(final double endAngle, final int nSteps,
                                       final File dir, final RotationAxis axis)
        {
            if (!dir.exists()) dir.mkdirs();
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

            final View view = chart.getView();
            final Coord3d startVp = view.getViewPoint().clone();
            final double totalRad = Math.toRadians(endAngle);
            final boolean pingPong = getAnimationMode() == AnimationMode.PING_PONG;

            // Pre-compute Cartesian start for Rodrigues path (X/Y axes)
            final double az0 = startVp.x;
            final double el0 = startVp.y;
            final double cosEl = Math.cos(el0);
            final double vx = cosEl * Math.sin(az0);
            final double vy = cosEl * Math.cos(az0);
            final double vz = Math.sin(el0);
            final double kx = (axis == RotationAxis.X) ? 1 : 0;
            final double ky = (axis == RotationAxis.Y) ? 1 : 0;

            addProgressLoad(nSteps);
            while (step++ < nSteps) {
                try {
                    final File f = new File(dir, String.format("%05d.png", step));
                    final double theta;
                    if (pingPong) {
                        // Triangle wave: oscillate over ±(totalRad/2) completing
                        // one full back-and-forth cycle over nSteps frames
                        final double halfArc = totalRad / 2.0;
                        final double phase = (double) step / nSteps; // 0..1
                        // Triangle wave: 0→+1→0→-1→0 over one period
                        final double t = phase * 2.0; // 0..2
                        theta = (t <= 1.0) ? halfArc * t : halfArc * (2.0 - t);
                    } else {
                        theta = totalRad * step / nSteps;
                    }
                    if (axis == RotationAxis.Z) {
                        // Absolute azimuth positioning from start
                        view.setViewPoint(new Coord3d(az0 + theta, el0, startVp.z), false);
                        view.shoot();
                    } else {
                        // Rodrigues: v_rot = v*cos(θ) + (k×v)*sin(θ) + k*(k·v)*(1-cos(θ))
                        final double cosT = Math.cos(theta);
                        final double sinT = Math.sin(theta);
                        final double dot = kx * vx + ky * vy;
                        final double cx = ky * vz;
                        final double cy = -kx * vz;
                        final double cz = kx * vy - ky * vx;
                        final double rx = vx * cosT + cx * sinT + kx * dot * (1 - cosT);
                        final double ry = vy * cosT + cy * sinT + ky * dot * (1 - cosT);
                        final double rz = vz * cosT + cz * sinT;
                        final double newEl = Math.asin(Math.max(-1, Math.min(1, rz)));
                        final double newAz = Math.atan2(rx, ry);
                        view.setViewPoint(new Coord3d(newAz, newEl, startVp.z), false);
                        view.shoot();
                    }
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
                if (threadController instanceof CameraThreadControllerPlus tc) {
                    if (e.isShiftDown()) {
                        // Shift+double-click: legacy Z-axis rotation
                        tc.setRotationAxis(RotationAxis.Z);
                        tc.setAutoDetectAxis(true); // re-enable for next plain double-click
                    } else {
                        tc.updateAxisIfAuto();
                    }
                    tc.start();
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
            if (chart.isRotationEnabled()) {
               rotate(move, true);
            }
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
                rotateLive(move);
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

        @Override
        protected void drawCoord(final java.awt.Graphics2D g2d,
                final Coord2d screenPosition, final Coord3d modelPosition,
                final int interline, final boolean leftAlign) {
            // Guard against null coordinate labels during view-mode transitions
            // (jzy3d leaves d1/d2 null when the view is momentarily in 3D mode)
            if (view.is2D())
                super.drawCoord(g2d, screenPosition, modelPosition, interline, leftAlign);
        }
    }

    private class CameraThreadControllerPlus extends CameraThreadController {

        private RotationAxis rotationAxis = RotationAxis.Z;
        private boolean autoDetectAxis = true;
        private AnimationMode animationMode = AnimationMode.FULL_ROTATION;

        protected void setAnimationMode(final AnimationMode mode) {
            this.animationMode = mode;
        }

        protected AnimationMode getAnimationMode() {
            return animationMode;
        }

        public CameraThreadControllerPlus(final Chart chart) {
            super(chart);
        }

        protected boolean isAnimating() {
            return process != null && process.isAlive();
        }

        protected void setRotationAxis(final RotationAxis axis) {
            this.rotationAxis = (axis == null) ? RotationAxis.Z : axis;
            // Explicit axis selection disables auto-detection
            this.autoDetectAxis = false;
        }

        protected RotationAxis getRotationAxis() {
            return rotationAxis;
        }

        protected void setAutoDetectAxis(final boolean auto) {
            this.autoDetectAxis = auto;
        }

        protected boolean isAutoDetectAxis() {
            return autoDetectAxis;
        }

        /**
         * Infers the most intuitive rotation axis from the current viewpoint.
         * Picks the world axis most aligned with the camera's viewing direction,
         * giving a natural "turntable" spin around whatever axis the user is
         * looking down.
         */
        protected RotationAxis inferAxis() {
            final View view = chart.getView();
            if (view == null) return RotationAxis.Z;
            final Coord3d vp = view.getViewPoint();
            final double az = vp.x;
            final double el = vp.y;
            // Camera viewing direction in world coordinates (spherical to Cartesian)
            final double cosEl = Math.cos(el);
            final double compX = Math.abs(cosEl * Math.sin(az));
            final double compY = Math.abs(cosEl * Math.cos(az));
            final double compZ = Math.abs(Math.sin(el));
            if (compX >= compY && compX >= compZ) return RotationAxis.X;
            if (compY >= compX && compY >= compZ) return RotationAxis.Y;
            return RotationAxis.Z;
        }

        /**
         * Updates rotationAxis from the current viewpoint if auto-detection is
         * enabled. Called just before the animation thread starts.
         */
        protected void updateAxisIfAuto() {
            if (autoDetectAxis) {
                rotationAxis = inferAxis();
            }
        }

        @Override
        protected void doRun() {
            if (rotationAxis == RotationAxis.Z && animationMode == AnimationMode.FULL_ROTATION) {
                // Classic continuous azimuth sweep: delegate to parent
                super.doRun();
                return;
            }
            final View view = chart.getView();
            if (view == null) return;

            // For Rodrigues (X/Y), a 3× multiplier compensates for less apparent
            // on-screen motion at most viewpoints
            final double effectiveStep = (rotationAxis == RotationAxis.Z) ? step : step * 3.0;
            final boolean pingPong = (animationMode == AnimationMode.PING_PONG);
            final double halfArc = AnimationMode.DEFAULT_PING_PONG_ARC / 2.0; // ±30°

            double cumulativeAngle = 0;
            int direction = 1;
            final Coord3d startVp = view.getViewPoint().clone();
            // For Z ping-pong we also need the start so we can compute absolute positions
            final double az0 = startVp.x;
            final double el0 = startVp.y;
            final double cosEl = Math.cos(el0);
            final double vx = cosEl * Math.sin(az0);
            final double vy = cosEl * Math.cos(az0);
            final double vz = Math.sin(el0);
            final double kx = (rotationAxis == RotationAxis.X) ? 1 : 0;
            final double ky = (rotationAxis == RotationAxis.Y) ? 1 : 0;

            while (process != null) {
                try {
                    cumulativeAngle += effectiveStep * direction;
                    if (pingPong) {
                        if (cumulativeAngle > halfArc) {
                            cumulativeAngle = halfArc;
                            direction = -1;
                        } else if (cumulativeAngle < -halfArc) {
                            cumulativeAngle = -halfArc;
                            direction = 1;
                        }
                    }
                    if (rotationAxis == RotationAxis.Z) {
                        // Direct azimuth positioning (absolute, not incremental).
                        // Use updateView=false to match super.doRun()'s rotate()
                        // behavior: let the next repaint cycle handle rendering
                        view.setViewPoint(new Coord3d(az0 + cumulativeAngle, el0, startVp.z), false);
                    } else {
                        // Rodrigues: v_rot = v*cos(θ) + (k×v)*sin(θ) + k*(k·v)*(1-cos(θ))
                        final double cosT = Math.cos(cumulativeAngle);
                        final double sinT = Math.sin(cumulativeAngle);
                        final double dot = kx * vx + ky * vy;
                        final double cx = ky * vz;
                        final double cy = -kx * vz;
                        final double cz = kx * vy - ky * vx;
                        final double rx = vx * cosT + cx * sinT + kx * dot * (1 - cosT);
                        final double ry = vy * cosT + cy * sinT + ky * dot * (1 - cosT);
                        final double rz = vz * cosT + cz * sinT;
                        final double newEl = Math.asin(Math.max(-1, Math.min(1, rz)));
                        final double newAz = Math.atan2(rx, ry);
                        view.setViewPoint(new Coord3d(newAz, newEl, startVp.z), false);
                    }
                    Thread.sleep(sleep);
                } catch (final InterruptedException e) {
                    process = null;
                }
            }
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

        // Key command interface
        private interface KeyCommand {
            boolean canHandle(KeyEvent e, boolean doublePress);
            void execute(KeyEvent e, boolean doublePress);
        }

        // Key command registry
        private final List<KeyCommand> keyCommands = new ArrayList<>();

        // Initialize key commands
        private void initializeKeyCommands() {
            keyCommands.add(new ControlPanelToggleKeyCommand()); // Must be before CameraKeyCommand
            keyCommands.add(new AxesKeyCommand());
            keyCommands.add(new CameraKeyCommand());
            keyCommands.add(new DarkModeKeyCommand());
            keyCommands.add(new HelpKeyCommand());
            keyCommands.add(new LogKeyCommand());
            keyCommands.add(new ResetReloadKeyCommand());
            keyCommands.add(new ScreenshotStatusKeyCommand());
            keyCommands.add(new FitFullScreenKeyCommand());
            keyCommands.add(new ZoomKeyCommand());
            keyCommands.add(new NavigationKeyCommand());
            keyCommands.add(new EscapeKeyCommand());
        }

        public KeyController(final Chart chart) {
            register(chart);
            initializeKeyCommands();
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

            // Process key commands in order
            for (KeyCommand command : keyCommands) {
                if (command.canHandle(e, doublePress)) {
                    command.execute(e, doublePress);
                    e.consume();
                    return;
                }
            }
        }

        // Key command implementations
        private class AxesKeyCommand implements KeyCommand {
            @Override
            public boolean canHandle(KeyEvent e, boolean doublePress) {
                return e.getKeyChar() == 'a' || e.getKeyChar() == 'A';
            }

            @Override
            public void execute(KeyEvent e, boolean doublePress) {
                if (!emptySceneMsg()) toggleAxes();
            }
        }

        private class CameraKeyCommand implements KeyCommand {
            @Override
            public boolean canHandle(KeyEvent e, boolean doublePress) {
                return (e.getKeyChar() == 'c' || e.getKeyChar() == 'C') && !e.isShiftDown();
            }

            @Override
            public void execute(KeyEvent e, boolean doublePress) {
                if (!emptySceneMsg()) changeCameraMode();
            }
        }

        private class DarkModeKeyCommand implements KeyCommand {
            @Override
            public boolean canHandle(KeyEvent e, boolean doublePress) {
                return e.getKeyChar() == 'd' || e.getKeyChar() == 'D';
            }

            @Override
            public void execute(KeyEvent e, boolean doublePress) {
                toggleDarkMode();
            }
        }

        private class HelpKeyCommand implements KeyCommand {
            @Override
            public boolean canHandle(KeyEvent e, boolean doublePress) {
                return (e.getKeyChar() == 'h' || e.getKeyChar() == 'H') || e.getKeyCode() == KeyEvent.VK_F1;
            }

            @Override
            public void execute(KeyEvent e, boolean doublePress) {
                showHelp(e.getKeyCode() == KeyEvent.VK_F1);
            }
        }

        private class LogKeyCommand implements KeyCommand {
            @Override
            public boolean canHandle(KeyEvent e, boolean doublePress) {
                return e.getKeyChar() == 'l' || e.getKeyChar() == 'L';
            }

            @Override
            public void execute(KeyEvent e, boolean doublePress) {
                logSceneControls(true);
            }
        }

        private class ResetReloadKeyCommand implements KeyCommand {
            @Override
            public boolean canHandle(KeyEvent e, boolean doublePress) {
                return e.getKeyChar() == 'r' || e.getKeyChar() == 'R';
            }

            @Override
            public void execute(KeyEvent e, boolean doublePress) {
                if (doublePress || e.isShiftDown()) {
                    validate();
                    displayMsg("Scene reloaded");
                } else {
                    resetView();
                }
            }
        }

        private class ScreenshotStatusKeyCommand implements KeyCommand {
            @Override
            public boolean canHandle(KeyEvent e, boolean doublePress) {
                return e.getKeyChar() == 's' || e.getKeyChar() == 'S';
            }

            @Override
            public void execute(KeyEvent e, boolean doublePress) {
                if (e.isShiftDown() && frame != null) {
                    frame.statusVisible = !frame.status.isVisible();
                    frame.status.setVisible(frame.statusVisible);
                } else {
                    saveScreenshot();
                }
            }
        }

        private class FitFullScreenKeyCommand implements KeyCommand {
            @Override
            public boolean canHandle(KeyEvent e, boolean doublePress) {
                return e.getKeyChar() == 'f' || e.getKeyChar() == 'F';
            }

            @Override
            public void execute(KeyEvent e, boolean doublePress) {
                if (e.isShiftDown()) {
                    if (frame.isFullScreen)
                        frame.exitFullScreen();
                    else
                        frame.enterFullScreen();
                } else {
                    if (!emptySceneMsg()) fitToVisibleObjects(true, true);
                }
            }
        }

        private class ZoomKeyCommand implements KeyCommand {
            @Override
            public boolean canHandle(KeyEvent e, boolean doublePress) {
                return e.getKeyChar() == '+' || e.getKeyChar() == '=' ||
                        e.getKeyChar() == '-' || e.getKeyChar() == '_';
            }

            @Override
            public void execute(KeyEvent e, boolean doublePress) {
                if (e.getKeyChar() == '+' || e.getKeyChar() == '=') {
                    mouseController.zoom(1f - zoomStep);
                } else {
                    mouseController.zoom(1f + zoomStep);
                }
            }
        }

        private class NavigationKeyCommand implements KeyCommand {
            @Override
            public boolean canHandle(KeyEvent e, boolean doublePress) {
                return e.getKeyCode() == KeyEvent.VK_DOWN || e.getKeyCode() == KeyEvent.VK_UP ||
                        e.getKeyCode() == KeyEvent.VK_LEFT || e.getKeyCode() == KeyEvent.VK_RIGHT;
            }

            @Override
            public void execute(KeyEvent e, boolean doublePress) {
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_DOWN -> {
                        if (e.isShiftDown()) {
                            pan(new Coord2d(0, -1));
                        } else
                            mouseController.rotateLive(new Coord2d(0f, -rotationStep));
                    }
                    case KeyEvent.VK_UP -> {
                        if (e.isShiftDown()) {
                            pan(new Coord2d(0, 1));
                        } else
                            mouseController.rotateLive(new Coord2d(0f, rotationStep));
                    }
                    case KeyEvent.VK_LEFT -> {
                        if (e.isShiftDown()) {
                            pan(new Coord2d(-1, 0));
                        } else
                            mouseController.rotateLive(new Coord2d(-rotationStep, 0));
                    }
                    case KeyEvent.VK_RIGHT -> {
                        if (e.isShiftDown()) {
                            pan(new Coord2d(1, 0));
                        } else
                            mouseController.rotateLive(new Coord2d(rotationStep, 0));
                    }
                }
            }
        }

        private class EscapeKeyCommand implements KeyCommand {
            @Override
            public boolean canHandle(KeyEvent e, boolean doublePress) {
                return e.getKeyCode() == KeyEvent.VK_ESCAPE;
            }

            @Override
            public void execute(KeyEvent e, boolean doublePress) {
                if (frame.isFullScreen)
                    frame.exitFullScreen();
                else
                    abortCurrentOperation = true;
            }
        }

        // Special command for control panel toggle (Shift+C)
        private class ControlPanelToggleKeyCommand implements KeyCommand {
            @Override
            public boolean canHandle(KeyEvent e, boolean doublePress) {
                return (e.getKeyChar() == 'c' || e.getKeyChar() == 'C') && e.isShiftDown();
            }

            @Override
            public void execute(KeyEvent e, boolean doublePress) {
                toggleControlPanel();
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
                chart.setViewMode(ViewMode.DEFAULT);
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
                frame.applyStatusColors(newBackground, newForeground);
                frame.updateStatusMsg(); // refresh icon color to match new theme
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
                // Replace matching per-vertex colors in the VBO buffer
                if (shapeTree.arborVBO != null) {
                    shapeTree.arborVBO.replaceColor(newBackground, newForeground);
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

            // Sync FlatLaf with scene dark mode
            syncLookAndFeel(newBackground == Color.BLACK);
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
            sb.append("    <td>Cycle View Mode &nbsp; &nbsp;</td>");
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
            sb.append("    <td>Toggle <u>C</u>ontrol Panel</td>");
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

        private String label;
        private Font labelFont;
        private java.awt.Color labelColor;
        private float labelX = 2;
        private float labelY = 0;

        /** Whether the scale bar is displayed. */
        private boolean scaleBarEnabled;
        /** The base unit of scene coordinates (e.g., "um", "nm", "mm"). */
        private String scaleBarUnit = "µm"; // µm default
        private boolean scaleBarUnitSet = false; // true once user has explicitly chosen a unit
        /** Scale factor to convert scene coordinates to micrometers (for scaledMicrometer). */
        private double scaleBarToUm = 1.0;
        private double cachedUnitsPerPixel = Double.NaN;

        private OverlayAnnotation(final View view) {
            super(view);
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

        /** Formats a scale value as an integer if whole, otherwise with minimal decimals. */
        private String formatScaleValue(final double v) {
            if (v == Math.floor(v) && !Double.isInfinite(v)) return String.valueOf((int) v);
            return String.format("%.1f", v);
        }

        private void paintScaleBar(final Graphics2D g2d, final int canvasWidth, final int canvasHeight) {

            final double hdpiScale = g2d.getTransform().getScaleX();
            final BoundingBox3d bounds = view.getBounds();

            double worldWidth;
            if (bounds != null && !bounds.isReset()) {
                worldWidth = bounds.getXRange().getRange();
                if (worldWidth > 0) {
                    final double yMid = (bounds.getYmin() + bounds.getYmax()) / 2.0;
                    final double zMid = (bounds.getZmin() + bounds.getZmax()) / 2.0;
                    final Camera cam = view.getCamera();
                    final IPainter painter = view.getPainter();
                    final Coord3d sMin = cam.modelToScreen(painter, new Coord3d(bounds.getXmin(), yMid, zMid));
                    final Coord3d sMax = cam.modelToScreen(painter, new Coord3d(bounds.getXmax(), yMid, zMid));
                    final double devicePixels = Math.abs(sMax.x - sMin.x);
                    if (devicePixels > 0)
                        cachedUnitsPerPixel = worldWidth / (devicePixels / hdpiScale);
                }
            }

            if (Double.isNaN(cachedUnitsPerPixel)) return; // nothing valid yet, skip silently

            final double unitsPerPixel = cachedUnitsPerPixel; // already world units per logical pixel
            final double logicalCanvasWidth = canvasWidth / hdpiScale;
            // Target: bar should be ~15-25% of the logical canvas width
            final double targetWorldLength = unitsPerPixel * logicalCanvasWidth * 0.2;

            // Pick a "nice" length: 1, 2, 5, 10, 20, 50, 100, ...
            final double magnitude = Math.pow(10, Math.floor(Math.log10(targetWorldLength)));
            final double normalized = targetWorldLength / magnitude;
            final double niceLength;
            if (normalized < 1.5) niceLength = magnitude;
            else if (normalized < 3.5) niceLength = 2 * magnitude;
            else if (normalized < 7.5) niceLength = 5 * magnitude;
            else niceLength = 10 * magnitude;

            final int barPixels = (int) Math.round(niceLength / unitsPerPixel);
            if (barPixels < 10) return; // too small to draw

            // Format label: convert scene units to µm, then pick the best SI prefix
            final double um = niceLength * scaleBarToUm;
            final String barLabel;
            if (um >= 1000000) {
                barLabel = formatScaleValue(um / 1000000) + " m";
            } else if (um >= 10000) {
                barLabel = formatScaleValue(um / 10000) + " cm";
            } else if (um >= 1000) {
                barLabel = formatScaleValue(um / 1000) + " mm";
            } else if (um >= 1) {
                barLabel = formatScaleValue(um) + " µm";
            } else if (um >= 0.001) {
                barLabel = formatScaleValue(um * 1000) + " nm";
            } else {
                barLabel = formatScaleValue(um * 10000) + " Å";
            }

            // Draw in bottom-left corner using the same approach as debug text
            final java.awt.Color fgColor = toAWTColor(view.getAxisLayout().getMainColor());
            g2d.setColor(fgColor);
            final int margin = 20;
            final int barThickness = 3;
            final int barY = (int) (canvasHeight / hdpiScale) - margin;
            g2d.setStroke(new BasicStroke(barThickness));
            g2d.drawLine(margin, barY, margin + barPixels, barY);
            // Label above bar
            final FontMetrics fm = g2d.getFontMetrics();
            final int textX = margin + (barPixels - fm.stringWidth(barLabel)) / 2;
            g2d.drawString(barLabel, textX, barY - barThickness - 2);
        }

        @Override
        public void paint(final Graphics g, final int canvasWidth, final int canvasHeight) {
            final Graphics2D g2d = (Graphics2D) g;
            GuiUtils.setRenderingHints(g2d);
            if (SNTUtils.isDebugMode()) {
                g2d.setColor(toAWTColor(view.getAxisLayout().getMainColor()));
                g2d.setFont(g2d.getFont().deriveFont((float)view.getAxisLayout().getFont().getHeight()));
                final int lineHeight = g.getFontMetrics().getHeight();
                int lineNo = 1;
                final Camera dbgCam = view.getCamera();
                final double eyeDist = dbgCam.getEye().distance(dbgCam.getTarget());
                final double fov = 2.0 * Math.toDegrees(Math.atan2(
                        dbgCam.getRenderingSphereRadius() * 2, eyeDist));
                g2d.drawString("Camera: " + dbgCam.getEye(), 20, lineHeight * lineNo++);
                g2d.drawString(String.format("FOV: %.1f\u00B0  Sphere radius: %.2f",
                        fov, dbgCam.getRenderingSphereRadius()), 20, lineHeight * lineNo++);
                g2d.drawString(String.format("Near: %g  Far: %g  (ratio: %.0f)",
                        dbgCam.getNear(), dbgCam.getFar(),
                        dbgCam.getFar() / Math.max(dbgCam.getNear(), 1e-10)),
                        20, lineHeight * lineNo++);
                g2d.drawString(String.format("Eye\u2192Target: %.2f", eyeDist),
                        20, lineHeight * lineNo++);
//				g2d.drawString("Up Z: " + view.getCamera().getUp().z, 20, lineHeight * lineNo++);
//				g2d.drawString("Axe:" + view.getAxis().getBounds().toString(), 20, lineHeight * lineNo++);
//				g2d.drawString("Transformed axe: " + axisBox.getBounds().toString(), 20, lineHeight * lineNo++);

            }

            if (scaleBarEnabled) {
                paintScaleBar(g2d, canvasWidth, canvasHeight);
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

        LightController(final ViewerFrame viewerFrame, final AbstractEnlightable abstractEnlightable) {

            super(viewerFrame,
                    (abstractEnlightable == null) ? "Light Effects" : "Material Editor");
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
            toFront();
        }

        void resetLight() {
            // HACK: the scene does not seem to update when light is removed,
            // so we'll try our best to restore things to pre-prompt state
            try {
                final Light light = chart.getScene().getLightSet().get(0);
                light.setSpecularColor(existingSpecularColor);
                chart.getView().setBackgroundColor(existingSpecularColor);
                light.setEnabled(false);
                light.setAmbiantColor(null);
                light.setDiffuseColor(null);
                light.setSpecularColor(null);
                chart.render();
                chart.getScene().remove(light);
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
                reset.addActionListener(e-> dispose(false));
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
            displayMsg("Zoomed to " + sb);
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
        final Set<String> labelSet = (labels == null) ? null : new HashSet<>(labels);
        plottedTrees.forEach((k, shapeTree) -> {
            if (labelSet == null || labelSet.contains(k)) shapeTree.setThickness(thickness, comp);
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
        if (labels == null) {
            setSomaRadius(radius);
            return;
        }
        final Set<String> labelSet = new HashSet<>(labels);
        plottedTrees.forEach((k, shapeTree) -> {
            if (labelSet.contains(k)) shapeTree.setSomaRadius(radius);
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
        final Set<String> labelSet = (labels == null) ? null : new HashSet<>(labels);
        plottedTrees.forEach((k, shapeTree) -> {
            if (labelSet == null || labelSet.contains(k))
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
                if (shapeTree.arborVBO == null) {
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

    /**
     * Enables or disables tube rendering for all trees (current and future).
     * The preference is persisted across sessions.
     *
     * @param enabled whether to render neurites as 3D tubes
     */
    public void setTubeMode(final boolean enabled) {
        // If shaders already known to be unavailable, don't bother
        if (enabled && ArborVBO.shaderInitAttempted && ArborVBO.shaderProgram == 0) {
            final String msg = ArborVBO.shaderInitMessage != null
                    ? ArborVBO.shaderInitMessage
                    : "Tube rendering is not available on this system.";
            headlessSafeError(msg);
            return;
        }
        tubeModeEnabled = enabled;
        if (view instanceof ViewerFactory.AView aView)
            aView.tubeModeActive = enabled;
        if (plottedTrees != null) {
            plottedTrees.values().forEach(shapeTree -> shapeTree.setTubeMode(enabled));
        }
        if (chart != null) {
            chart.render();
            // Shader init happens on the GL thread during render. Defer the
            // check so the render has time to trigger initShaders.
            if (enabled) {
                javax.swing.SwingUtilities.invokeLater(() -> {
                    if (ArborVBO.shaderInitAttempted && ArborVBO.shaderProgram == 0) {
                        // Rever, shaders failed
                        tubeModeEnabled = false;
                        if (plottedTrees != null) {
                            plottedTrees.values().forEach(st -> st.setTubeMode(false));
                        }
                        final String msg = ArborVBO.shaderInitMessage != null
                                ? ArborVBO.shaderInitMessage
                                : "Tube rendering is not available on this system.";
                        headlessSafeError(msg);
                        return;
                    }
                    // Shaders succeeded: persist
                    if (prefService != null) {
                        prefService.put(Viewer3D.class, PREF_TUBE_MODE, true);
                    }
                });
            } else {
                if (prefService != null) {
                    prefService.put(Viewer3D.class, PREF_TUBE_MODE, false);
                }
            }
        }
    }

    /**
     * Enables or disables the wireframe overlay on tube rendering for all trees.
     * The preference is persisted across sessions.
     *
     * @param enabled whether to show wireframe edges on tubes
     */
    public void setTubeWireframe(final boolean enabled) {
        tubeWireframeEnabled = enabled;
        if (prefService != null) {
            prefService.put(Viewer3D.class, PREF_TUBE_WIREFRAME, enabled);
        }
        if (plottedTrees != null) {
            plottedTrees.values().forEach(st -> st.setTubeWireframe(enabled));
        }
        if (chart != null && viewUpdatesEnabled) chart.render();
    }

    /**
     * Sets the number of sides for tube cross-sections for all trees.
     * Only has a visual effect when tube rendering is active.
     * The preference is persisted across sessions.
     *
     * @param sides number of sides (clamped to [3, 16])
     */
    public void setTubeSides(final int sides) {
        tubeSidesPref = Math.max(3, Math.min(ArborVBO.MAX_TUBE_SIDES, sides));
        if (prefService != null) {
            prefService.put(Viewer3D.class, PREF_TUBE_SIDES, tubeSidesPref);
        }
        if (plottedTrees != null) {
            plottedTrees.values().forEach(st -> st.setTubeSides(tubeSidesPref));
        }
        if (chart != null && viewUpdatesEnabled) chart.render();
    }

    /**
     * Sets the shading mode for all OBJ meshes (current and future).
     * The preference is persisted across sessions.
     *
     * @param mode {@link OBJMesh#SHADING_DEFAULT} or {@link OBJMesh#SHADING_SMOOTH}
     */
    public void setMeshShadingMode(final int mode) {
        meshShadingPref = mode;
        if (prefService != null) prefService.put(Viewer3D.class, PREF_MESH_SHADING, mode);
        if (plottedObjs != null) plottedObjs.values().forEach(vbo -> vbo.objMesh.meshShadingMode = mode);
        if (chart != null && viewUpdatesEnabled) chart.render();
    }

    /**
     * Returns the current mesh shading mode.
     *
     * @return {@link OBJMesh#SHADING_DEFAULT} or {@link OBJMesh#SHADING_SMOOTH}
     */
    public int getMeshShadingMode() {
        return meshShadingPref;
    }

    /**
     * Enables or disables backface culling for all OBJ meshes (current and future).
     * Backface culling skips rendering of back-facing triangles, improving performance
     * for closed meshes. The preference is persisted across sessions.
     *
     * @param enabled whether to enable backface culling
     */
    public void setMeshBackfaceCull(final boolean enabled) {
        meshBackfaceCullPref = enabled;
        if (prefService != null) prefService.put(Viewer3D.class, PREF_MESH_BACKFACE_CULL, enabled);
        if (plottedObjs != null) plottedObjs.values().forEach(vbo -> vbo.objMesh.backfaceCull = enabled);
        if (chart != null && viewUpdatesEnabled) chart.render();
    }

    /**
     * Returns whether backface culling is enabled for OBJ meshes.
     *
     * @return true if backface culling is enabled
     */
    public boolean isMeshBackfaceCull() {
        return meshBackfaceCullPref;
    }

    /**
     * Applies a scaling factor to Trees rendered as {@link ArborVBO}.
     *
     * @param scale the radius scale
     */
    public void setRadiusScale(final float scale) {
        setRadiusScale(null, scale);
    }

    /**
     * Applies a tube radius scale to a subset of rendered trees.
     *
     * @param labels the Collection of keys specifying the subset of trees,
     *               or null to apply to all trees
     * @param scale the radius scale factor
     */
    public void setRadiusScale(final Collection<String> labels, final float scale) {
        if (plottedTrees != null) {
            if (labels == null) {
                plottedTrees.values().forEach(st -> st.setRadiusScale(scale));
            } else {
                final Set<String> labelSet = new HashSet<>(labels);
                plottedTrees.forEach((k, st) -> {
                    if (labelSet.contains(k)) st.setRadiusScale(scale);
                });
            }
        }
        if (chart != null && viewUpdatesEnabled) chart.render();
    }

    /**
     * Enables or disables depth fog for all trees (current and future).
     * The preference is persisted across sessions.
     *
     * @param enabled whether to apply depth fog to neurite rendering
     */
    public void setDepthFog(final boolean enabled) {
        depthFogEnabled = enabled;
        if (prefService != null) {
            prefService.put(Viewer3D.class, PREF_DEPTH_FOG, enabled);
        }
        if (enabled) {
            // Mutually exclusive: disable pseudo-lighting
            pseudoLightingEnabled = false;
            if (prefService != null) {
                prefService.put(Viewer3D.class, PREF_PSEUDO_LIGHTING, false);
            }
            if (plottedTrees != null) {
                plottedTrees.values().forEach(st -> {
                    st.setPseudoLighting(false);
                    st.setDepthFog(true);
                });
            }
        } else {
            if (plottedTrees != null) {
                plottedTrees.values().forEach(st -> st.setDepthFog(false));
            }
        }
        if (chart != null && viewUpdatesEnabled) chart.render();
    }

    /**
     * Sets the fog intensity for all trees and persists the preference.
     *
     * @param intensity 0 (very subtle) to 1 (fully fogged at far depth)
     */
    public void setFogIntensity(final float intensity) {
        fogIntensityPref = intensity;
        if (prefService != null) {
            prefService.put(Viewer3D.class, PREF_FOG_INTENSITY, (double) intensity);
        }
        if (plottedTrees != null) {
            plottedTrees.values().forEach(shapeTree -> shapeTree.setFogIntensity(intensity));
        }
        if (chart != null && viewUpdatesEnabled) chart.render();
    }

    /**
     * Enables or disables pseudo-lighting for all trees (current and future).
     * Mutually exclusive with depth fog. Enabling this disables fog.
     *
     * @param enabled whether to apply tangent-based pseudo-lighting
     */
    public void setPseudoLighting(final boolean enabled) {
        pseudoLightingEnabled = enabled;
        if (prefService != null) {
            prefService.put(Viewer3D.class, PREF_PSEUDO_LIGHTING, enabled);
        }
        if (enabled) {
            // Mutually exclusive: disable depth fog
            depthFogEnabled = false;
            if (prefService != null) {
                prefService.put(Viewer3D.class, PREF_DEPTH_FOG, false);
            }
            if (plottedTrees != null) {
                plottedTrees.values().forEach(st -> {
                    st.setDepthFog(false);
                    st.setPseudoLighting(true);
                });
            }
        } else {
            if (plottedTrees != null) {
                plottedTrees.values().forEach(st -> st.setPseudoLighting(false));
            }
        }
        if (chart != null && viewUpdatesEnabled) chart.render();
    }

    /**
     * Sets the Catmull-Rom upsampling factor for all trees and rebuilds them.
     * 1 = no smoothing, higher = smoother paths at vertex joints.
     *
     * @param factor upsampling factor (1-10)
     */
    public void setUpsamplingFactor(final int factor) {
        upsamplingFactorPref = Math.max(1, Math.min(10, factor));
        if (prefService != null) {
            prefService.put(Viewer3D.class, PREF_UPSAMPLING, upsamplingFactorPref);
        }
        if (plottedTrees != null) {
            // rebuildShape() creates a new ArborVBO with the current
            // upsamplingFactorPref, so no need to set it explicitly
            plottedTrees.values().forEach(ShapeTree::rebuildShape);
        }
        if (chart != null && viewUpdatesEnabled) chart.render();
    }

    private void headlessSafeError(final String msg) {
        if (ENGINE==Engine.OFFSCREEN || frame == null || guiUtils() == null) {
            SNTUtils.error(msg);
        } else {
            guiUtils().error(msg);
        }
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
        for (final Map.Entry<String, ShapeTree> entry : plottedTrees.entrySet()) {
            if (!labels.contains(entry.getKey())) continue;
            final ArborVBO vbo = entry.getValue().arborVBO;
            if (vbo == null || vbo.vertexData == null || vbo.vertexCount < 2) continue;
            // Compare first vertex color to all others
            final float r0 = vbo.vertexData[3], g0 = vbo.vertexData[4], b0 = vbo.vertexData[5];
            for (int i = 1; i < vbo.vertexCount; i++) {
                final int off = i * ArborVBO.FLOATS_PER_VERTEX + 3;
                if (vbo.vertexData[off] != r0 || vbo.vertexData[off + 1] != g0
                        || vbo.vertexData[off + 2] != b0)
                    return true;
            }
        }
        return false;
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
        if (getManagerPanel() != null) {
            final Runnable r = () -> frame.managerPanel.progressBar.addToGlobalValue(1);
            if (SwingUtilities.isEventDispatchThread()) r.run();
            else SwingUtilities.invokeLater(r);
        }
    }

    private int getCurrentProgress() {
        return (getManagerPanel() == null) ? -1 : frame.managerPanel.progressBar.globalValue;
    }

    /** will hide the bar if progress max becomes negative */
    private void removeProgressLoad(final int loadSize) {
        if (getManagerPanel() != null) {
            final Runnable r = () -> frame.managerPanel.progressBar.addToGlobalMax((loadSize < 0) ? loadSize : -loadSize);
            if (SwingUtilities.isEventDispatchThread()) r.run();
            else SwingUtilities.invokeLater(r);
        }
    }

    /** will show an undetermined bar if progress max is negative */
    private void addProgressLoad(final int loadSize) {
        if (getManagerPanel() != null) {
            final Runnable r = () -> frame.managerPanel.progressBar.addToGlobalMax(loadSize);
            if (SwingUtilities.isEventDispatchThread()) r.run();
            else SwingUtilities.invokeLater(r);
        }
    }

    /** Defines the type of render, and view used by jzy3d */
    private static class ViewerFactory {

        /** Returns ChartComponentFactory adopting {@link AView} */
        private ChartFactory getUpstreamFactory(final Engine render) {
            switch (render) {
                case EMUL_GL -> {
                    return new EmulGLFactory();
                }
                case OFFSCREEN -> {
                    return new OffScreenFactory();
                }
                case JOGL -> {
                    return new JOGLFactory();
                }
                default -> throw new IllegalArgumentException("Not a recognized render option: " + render);
            }

        }

        /**
         * Custom AWTRenderer3d that reads GL_RGB (no alpha) for screenshots.
         * The default uses alpha=true, producing TYPE_INT_ARGB_PRE images with
         * premultiplied RGB that appear washed out compared to on-screen rendering.
         */
        private static class ARenderer extends AWTRenderer3d {
            ARenderer(final View view, final boolean traceGL, final boolean debugGL) {
                super(view, traceGL, debugGL);
                screenshotMaker = new AWTGLReadBufferUtil(GLProfile.getGL2GL3(), false);
            }
        }

        private static class OffScreenFactory extends AWTChartFactory {

            OffScreenFactory() {
                super(new OffscreenWindowFactory() {
                    @Override
                    public Renderer3d newRenderer3D(final View view) {
                        return new ARenderer(view, traceGL, debugGL);
                    }
                });
                getPainterFactory().setOffscreen(1920, 1080);
            }

            @Override
            public Camera newCamera(final Coord3d center) {
                return new ACamera(center);
            }

            @Override
            public View newView(final Scene scene, final ICanvas canvas, final Quality quality) {
                return new AView(getFactory(), scene, canvas, quality);
            }
        }

        private static class EmulGLFactory extends EmulGLChartFactory {

            @Override
            public Camera newCamera(final Coord3d center) {
                return new ACamera(center);
            }

            @Override
            public View newView(final Scene scene, final ICanvas canvas, final Quality quality) {
                return new AView(getFactory(), scene, canvas, quality);
            }
        }

        private static class JOGLFactory extends AWTChartFactory {

            JOGLFactory() {
                super(new AWTPainterFactory() {
                    @Override
                    public Renderer3d newRenderer3D(final View view) {
                        return new ARenderer(view, traceGL, debugGL);
                    }
                });
            }

            @Override
            public Camera newCamera(final Coord3d center) {
                return new ACamera(center);
            }

            @Override
            public View newView(final Scene scene, final ICanvas canvas, final Quality quality) {
                return new AView(getFactory(), scene, canvas, quality);
            }
        }

        /**
         * Camera subclass that uses gluPerspective instead of glFrustum.
         * The default glFrustum path computes frustum half-width from
         * renderingSphereRadius/factorViewPointDistance, making the effective
         * FOV = 2*atan(r/near). When near is clamped to a small value (to
         * allow deep zoom), FOV degenerates to >180°. gluPerspective computes
         * FOV from renderingSphereRadius and eye-target distance independently
         * of near, keeping it stable at all zoom levels.
         */
        private static class ACamera extends Camera {

            public ACamera(final Coord3d center) {
                super(center);
            }

            @Override
            public void projectionPerspective(final IPainter painter, final ViewportConfiguration viewport) {
                final boolean stretchToFill = ViewportMode.STRETCH_TO_FILL.equals(viewport.getMode());
                final double fov = computeFieldOfView(renderingSphereRadius * 4, eye.distance(target));
                final float aspect = stretchToFill ? ((float) screenWidth) / ((float) screenHeight) : 1;
                final float nearCorrected = near <= 0 ? Float.MIN_VALUE : near;
                painter.gluPerspective(fov, aspect * 0.55, nearCorrected, far);
            }
        }

        /**
         * Adapted View for improved rotations of the scene.
         * <p>
         * Overrides the upstream rotation/camera logic to allow smooth rotation
         * across the elevation poles (+/-PI/2). Without this, dragging past the
         * top or bottom of the sphere causes a jarring mirror-snap because the
         * camera up vector does not account for the hemisphere change.
         * <p>
         * The fix tracks "pole crossings": each time an incremental rotation
         * would push the elevation past +/-PI/2, the elevation is reflected,
         * the azimuth is flipped by PI, and a counter is incremented. When
         * the counter is odd the up vector is negated, keeping the image
         * visually continuous.
         */
        private static class AView extends AWTView {

            /**
             * Number of times the viewpoint has crossed an elevation pole
             * (Z-axis) during incremental rotation. When odd, the camera up
             * vector is negated to maintain visual continuity.
             */
            private int poleFlips = 0;


            /** Whether tube/surface rendering is active, affecting near-plane clamping aggressiveness. */
            boolean tubeModeActive;

            /** Tracks the maximum scene radius seen, so the far plane always encompasses all geometry. */
            private float maxSceneRadius;

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
            public void rotate(final Coord2d move, final boolean updateView) {
                final Coord3d vp = getViewPoint();

                if (View.UP_VECTOR_Z.equals(upVector)) {
                    // After an odd number of pole crossings the up vector is
                    // negated by computeCameraUp(), so visual directions are
                    // reversed. Negate the move to keep keyboard/mouse input
                    // consistent with the on-screen result.
                    final float sign = (poleFlips % 2 == 0) ? 1f : -1f;
                    vp.x -= move.x * sign;
                    vp.y += move.y * sign;

                    // Wrap elevation across the poles instead of clamping.
                    // Each crossing flips azimuth by PI and increments the
                    // counter so computeCameraUp() can negate the up vector.
                    while (vp.y > PI_div2) {
                        vp.y = PI - vp.y;
                        vp.x += PI;
                        poleFlips++;
                    }
                    while (vp.y < -PI_div2) {
                        vp.y = -PI - vp.y;
                        vp.x += PI;
                        poleFlips++;
                    }
                } else if (View.UP_VECTOR_X.equals(upVector)) {
                    vp.y += move.x;
                    vp.x += move.y;
                } else if (upVector.x == 0 && upVector.y != 0 && upVector.z == 0) {
                    // Y-axis up (positive or negative). The upVector.y sign
                    // accounts for inverted-Y atlases (e.g. Allen CCF
                    // dorsal-ventral). Only Z-pole crossings negate input;
                    // OpenGL's gluLookAt naturally keeps the Y-up hint
                    // correct throughout azimuth rotation.
                    final float upSign = (upVector.y > 0) ? 1f : -1f;
                    final float sign = (poleFlips % 2 == 0) ? upSign : -upSign;
                    vp.x -= move.x * sign;
                    vp.y += move.y * sign;

                    // Z-pole wrapping (elevation crossing ±π/2). Identical to
                    // the Z-up case: the polar → cartesian mapping degenerates
                    // at the Z-axis regardless of the up vector.
                    while (vp.y > PI_div2) {
                        vp.y = PI - vp.y;
                        vp.x += PI;
                        poleFlips++;
                    }
                    while (vp.y < -PI_div2) {
                        vp.y = -PI - vp.y;
                        vp.x += PI;
                        poleFlips++;
                    }
                }

                setViewPoint(vp, updateView);
            }

            @Override
            public void setViewPoint(final Coord3d polar, final boolean updateView) {
                // see https://github.com/jzy3d/jzy3d-api/issues/214#issuecomment-975717207
                // If the elevation is out of range, this is an explicit (non-
                // incremental) positioning, so reset poleFlips. Values arriving
                // in-range (from our rotate() wrapping above) preserve the count.
                if (polar.y < -PI_div2 || polar.y > PI_div2) {
                    poleFlips = 0;
                }
                viewpoint = polar;
                if (updateView)
                    shoot();
                fireViewPointChangedEvent(new ViewPointChangedEvent(this, polar));
            }

            @Override
            protected Coord3d computeCameraUp(final Coord3d viewpoint) {
                if (is2D()) {
                    // For Y-up scenes (e.g. Allen CCF), override the up vector
                    // for XY (sagittal/TOP) and YZ (coronal) views so that the
                    // dorsal direction (negative Y) points to the top of the
                    // screen. XZ (transverse) looks along Y, so Z-up is correct
                    // and we can let super handle it.
                    if (upVector.x == 0 && upVector.y != 0 && upVector.z == 0) {
                        if (!is2D_XZ()) {
                            // XY and YZ: use Y-based up vector as-is.
                            // Do NOT apply verticalAxisFlip negation here:
                            // the flip already moves the camera eye to the
                            // opposite side (via computeCameraEye*), so
                            // negating up would double-flip the result.
                            return upVector;
                        }
                    }
                    return super.computeCameraUp(viewpoint);
                }

                if (upVector.x == 0 && upVector.y != 0 && upVector.z == 0) {
                    return computeCameraUpY(viewpoint);
                }

                // --- Z-up path (default) ---
                // Handle "on top" or "on bottom"
                if (Math.abs(viewpoint.y) == ELEVATION_ON_TOP) {
                    final Coord2d direction = new Coord2d(viewpoint.x, viewpoint.z).cartesian();
                    if (viewpoint.y > 0) {
                        return new Coord3d(-direction.x, -direction.y, 0);
                    } else {
                        return new Coord3d(direction.x, direction.y, 0);
                    }
                }
                // Standard 3D: negate the up vector when we have crossed a
                // pole an odd number of times
                if (poleFlips % 2 == 1) {
                    return new Coord3d(-upVector.x, -upVector.y, -upVector.z);
                }
                return upVector;
            }

            @Override
            protected void computeCamera2D_RenderingSquare(final Camera cam,
                    final ViewportConfiguration viewport, final BoundingBox3d bounds) {
                // When Y-up is active in YZ mode, the screen axes are swapped
                // relative to jzy3d's assumption: Z becomes horizontal and Y
                // becomes vertical. Swap Y and Z in the bounding box so that
                // super's hrange/vrange calculation matches the actual screen
                // layout. All other modes (including XY with Y-up) are correct
                // as-is because the axis-to-screen mapping doesn't change.
                BoundingBox3d effectiveBounds = bounds;
                if (is2D_YZ() && upVector.x == 0 && upVector.y != 0 && upVector.z == 0) {
                    effectiveBounds = new BoundingBox3d(
                            bounds.getXmin(), bounds.getXmax(),
                            bounds.getZmin(), bounds.getZmax(),  // Y ← Z
                            bounds.getYmin(), bounds.getYmax()); // Z ← Y
                }
                super.computeCamera2D_RenderingSquare(cam, viewport, effectiveBounds);

                // jzy3d's projectionOrtho2D maps the rendering square directly
                // to the viewport via glOrtho without correcting for viewport
                // aspect ratio, which distorts the scene when the data
                // proportions do not match the window proportions. Expand the
                // smaller dimension so that both axes have equal scaling.
                final BoundingBox2d rs = cam.getRenderingSquare();
                if (rs == null || viewport.getWidth() <= 0 || viewport.getHeight() <= 0)
                    return;
                final float rsW = rs.xrange();
                final float rsH = rs.yrange();
                if (rsW <= 0 || rsH <= 0) return;
                final float vpAspect = (float) viewport.getWidth() / viewport.getHeight();
                final float rsAspect = rsW / rsH;
                if (Math.abs(vpAspect - rsAspect) < 1e-4f) return; // already matching
                final float cx = (rs.xmin() + rs.xmax()) / 2f;
                final float cy = (rs.ymin() + rs.ymax()) / 2f;
                if (rsAspect < vpAspect) {
                    // viewport is wider: expand horizontal range
                    final float newW = rsH * vpAspect;
                    cam.setRenderingSquare(new BoundingBox2d(
                            cx - newW / 2, cx + newW / 2,
                            rs.ymin(), rs.ymax()), cam.getNear(), cam.getFar());
                } else {
                    // viewport is taller: expand vertical range
                    final float newH = rsW / vpAspect;
                    cam.setRenderingSquare(new BoundingBox2d(
                            rs.xmin(), rs.xmax(),
                            cy - newH / 2, cy + newH / 2), cam.getNear(), cam.getFar());
                }
            }

            /**
             * Compute the camera up vector when the up axis is Y. The Z-pole
             * (elevation = ±π/2) still requires the standard {@link #poleFlips}
             * negation. Additionally, the camera degenerates when the eye
             * direction aligns with Y (azimuth near ±π/2, elevation near 0).
             * Near that singularity a fallback vector in the XZ plane is used.
             */
            private Coord3d computeCameraUpY(final Coord3d viewpoint) {
                // At the Z elevation pole, compute a smooth up from azimuth
                // (same idea as the Z-up pole handler, but in the XZ plane)
                if (Math.abs(viewpoint.y) == ELEVATION_ON_TOP) {
                    final Coord2d direction = new Coord2d(viewpoint.x, viewpoint.z).cartesian();
                    if (viewpoint.y > 0) {
                        return new Coord3d(-direction.x, 0, -direction.y);
                    } else {
                        return new Coord3d(direction.x, 0, direction.y);
                    }
                }

                // Y-axis degeneracy: when the eye direction is nearly along Y,
                // the cross product (look × up) approaches zero. Fall back to
                // a vector in the XZ plane derived from the current azimuth.
                final Coord3d eyeDir = viewpoint.cartesian();
                final double xzLen = Math.sqrt(eyeDir.x * eyeDir.x + eyeDir.z * eyeDir.z);
                if (xzLen < 1e-4 * Math.abs(eyeDir.y)) {
                    // Camera nearly along ±Y: use Z (or -Z) as fallback up.
                    // The sign keeps a consistent horizon orientation.
                    final float zSign = (eyeDir.y > 0) ? 1f : -1f;
                    return new Coord3d(0, 0, zSign);
                }

                // Normal case: negate when combined pole count is odd
                final boolean negate = (poleFlips % 2 == 1);
                return negate ? new Coord3d(-upVector.x, -upVector.y, -upVector.z) : upVector;
            }

            /** Return the elevation pole-crossing counter. */
            int getPoleFlips() {
                return poleFlips;
            }

            /** Reset all pole-crossing counters (e.g. on explicit view mode change). */
            void resetPoleFlips() {
                poleFlips = 0;
            }

            @Override
            protected void renderAxeBox() {
                // jzy3d's axis tick label positioning can return null at extreme
                // zoom levels, causing an NPE in TextRenderer.drawText(). Guard
                // against this so the rest of the scene continues to render.
                try {
                    super.renderAxeBox();
                } catch (final NullPointerException ignored) {
                    // axis degenerated at this zoom level; skip rendering it
                    SNTUtils.log("Degenerated axes. You should zoom out.");
                }
            }

            @Override
            protected void computeCameraRenderingVolume(final Camera cam,
                    final ViewportConfiguration viewport, final BoundingBox3d bounds) {
                super.computeCameraRenderingVolume(cam, viewport, bounds);
                // Track the largest scene radius seen (before zoom scaling shrinks it)
                // so the far plane always encompasses all actual geometry.
                final float radius = (float) bounds.getRadius();
                if (radius > maxSceneRadius) maxSceneRadius = radius;
                // jzy3d computes near = eye.distance(target) - 2*radius, which goes
                // negative when zoomed in close. Clamp near to a tiny fraction of
                // the eye-target distance, and ensure far reaches all geometry.
                final float near = cam.getNear();
                if (near <= 0) {
                    final float eyeDist = (float) cam.getEye().distance(cam.getTarget());
                    final float factor = tubeModeActive ? 1e-4f : 1e-6f;
                    final float safeFar = eyeDist + maxSceneRadius * 2f;
                    cam.setRenderingDepth(Math.max(eyeDist * factor, 1e-6f), safeFar);
                }
            }
        }
    }

//	/* IDE debug method */
//	public static void main(final String[] args) throws InterruptedException {
//		 sc.fiji.snt.demo.RecViewerDemo.main(args);
//	}

}
