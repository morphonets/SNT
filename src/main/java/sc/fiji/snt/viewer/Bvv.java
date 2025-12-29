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

package sc.fiji.snt.viewer;

import bdv.tools.HelpDialog;
import bdv.util.AxisOrder;
import bdv.viewer.ViewerState;
import bvv.core.BigVolumeViewer;
import bvv.core.VolumeViewerFrame;
import bvv.core.VolumeViewerPanel;
import bvv.vistools.*;
import ij.ImagePlus;
import net.imagej.ImgPlus;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealPoint;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.RealType;
import org.scijava.util.ColorRGB;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.graph.DirectedWeightedGraph;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.IconFactory;
import sc.fiji.snt.util.*;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.List;

/**
 * Experimental support for Big Volume Viewer
 **/
public class Bvv {

    static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

    private final SNT snt;

    private final BvvOptions options;
    private final Map<String, Tree> renderedTrees;
    private final PathRenderingOptions renderingOptions;
    private PathOverlay pathOverlay;
    private AnnotationOverlay annotationOverlay;
    private BigVolumeViewer currentBvv;
    private BvvHandle bvvHandle;

    /**
     * Constructor for standalone BVV instance.
     */
    public Bvv() {
        this(null); // standalone viewer
    }

    /**
     * Constructor for assembling a BVV instance tethered to SNT.
     *
     * @param snt the snt instance providing paths and imagery to be rendered
     */
    public Bvv(final SNT snt) {
        this.snt = snt;
        this.renderedTrees = new HashMap<>();
        options = bvv.vistools.Bvv.options();
        this.renderingOptions = new PathRenderingOptions();
        options.preferredSize(1024, 1024);
        options.frameTitle("SNT BVV");
        options.cacheBlockSize(32); // GPU cache tile size
        options.maxCacheSizeInMB(300); // GPU cache size (in MB)
        options.ditherWidth(1); // dither window. 1 = full resolution; 8 = coarsest resolution
        options.numDitherSamples(8); // no. of nearest neighbors to interpolate from when dithering
        //options.maxAllowedStepInVoxels(1); // FIXME: function?
    }

    private static AxisOrder getAxisOrder(final ImagePlus imp) {
        if (imp.getNSlices() == 1 && imp.getNChannels() == 1 && imp.getNFrames() == 1) {
            return AxisOrder.XY;
        } else if (imp.getNSlices() > 1 && imp.getNChannels() == 1 && imp.getNFrames() == 1) {
            return AxisOrder.XYZ;
        } else if (imp.getNSlices() == 1 && imp.getNChannels() > 1 && imp.getNFrames() == 1) {
            return AxisOrder.XYC;
        } else if (imp.getNSlices() == 1 && imp.getNChannels() == 1 && imp.getNFrames() > 1) {
            return AxisOrder.XYT;
        } else if (imp.getNSlices() == 1 && imp.getNChannels() > 1 && imp.getNFrames() > 1) {
            return AxisOrder.XYCT;
        } else if (imp.getNSlices() > 1 && imp.getNChannels() == 1 && imp.getNFrames() > 1) {
            return AxisOrder.XYZT;
        } else {
            return AxisOrder.XYZCT;
        }
    }

    /**
     * Displays the BVV viewer with the specified image.
     *
     * @param <T> the numeric type of the image data
     * @param img the image data to display
     * @param calibration optional calibration values for x, y, z dimensions. If null, defaults to {1, 1, 1}
     * @return the BvvSource representing the displayed image
     */
    public <T extends RealType<T>> BvvSource show(final RandomAccessibleInterval<T> img, final double... calibration) {
        final double[] cal = (calibration == null) ? new double[]{1, 1, 1} : calibration;
        final BvvSource source = BvvFunctions.show(img, "SNT Bvv", options.sourceTransform(cal));
        attachControlPanel(source);
        if (bvvHandle == null)  bvvHandle = source.getBvvHandle();
        return source;
    }

    public <T extends RealType<T>> BvvSource show(final ImgPlus<T> imgPlus) {
        final double[] cal = new double[Math.min(3, imgPlus.numDimensions())];
        for (int d = 0; d < cal.length; d++) {
            cal[d] = imgPlus.averageScale(d);
        }
        return show(imgPlus, cal);  // ImgPlus is a RandomAccessibleInterval<T>
    }

    /**
     * Displays the BVV viewer with the specified image.
     *
     * @param imp the ImagePlus to display
     * @return the BvvSource representing the displayed image
     * @throws IllegalArgumentException if the image type is unsupported (COLOR_256)
     */
    public BvvSource show(final ImagePlus imp) {
        return showImagePlus(imp);
    }

    private <T extends RealType<T>> BvvSource displayData(final boolean secondary) {
        if (snt == null)
            throw new IllegalArgumentException("This function is only available in snt-aware Bvv instances");
        if (!snt.accessToValidImageData()) throw new IllegalArgumentException("No valid image data available");
        final RandomAccessibleInterval<T> data = (secondary) ? snt.getSecondaryData() : snt.getLoadedData();
        final String label = String.format("Tracing Data (%s): C%d, T%d",
                (secondary) ? "Secondary layer" : "Main image", snt.getChannel(), snt.getFrame());
        final BvvSource source = BvvFunctions.show(data, label,
                options.sourceTransform(snt.getPixelWidth(), snt.getPixelHeight(), snt.getPixelDepth()));
        if (secondary && snt.getStatsSecondary().max > 0) {
            source.setDisplayRange(snt.getStatsSecondary().min, snt.getStatsSecondary().max);
        } else if (snt.getStats().max > 0) {
            source.setDisplayRange(snt.getStats().min, snt.getStats().max);
        }
        attachControlPanel(source);
        if (bvvHandle == null)  bvvHandle = source.getBvvHandle();
        return source;
    }

    @SuppressWarnings("UnusedReturnValue")
    private BvvSource showImagePlus(final ImagePlus imp) {
        final BvvOptions opt = options.axisOrder(getAxisOrder(imp)).sourceTransform(
                imp.getCalibration().pixelWidth, imp.getCalibration().pixelHeight, imp.getCalibration().pixelDepth);
        final BvvStackSource<?> source = switch (imp.getType()) {
            case ImagePlus.COLOR_256 -> throw new IllegalArgumentException("Unsupported image type (COLOR_256).");
            case ImagePlus.GRAY8 -> BvvFunctions.show(ImageJFunctions.wrapByte(imp), imp.getTitle(), opt);
            case ImagePlus.GRAY16 -> BvvFunctions.show(ImageJFunctions.wrapShort(imp), imp.getTitle(), opt);
            case ImagePlus.GRAY32 -> BvvFunctions.show(ImageJFunctions.wrapFloat(imp), imp.getTitle(), opt);
            default -> BvvFunctions.show(ImageJFunctions.wrapRGBA(imp), imp.getTitle(), opt);
        };
        if (imp.getLuts().length == imp.getNChannels()) {
            for (int i = 0; i < imp.getNChannels(); i++) {
                final int rgb = imp.getLuts()[i].getRGB(255);
                source.getConverterSetups().get(i).setColor(new ARGBType(rgb));
                source.getConverterSetups().get(i).setDisplayRange(imp.getLuts()[i].min, imp.getLuts()[i].max);
            }
        }
        attachControlPanel(source);
        if (bvvHandle == null)  bvvHandle = source.getBvvHandle();
        return source;
    }

    private void attachControlPanel(final BvvSource source) {
        final BigVolumeViewer bvv = ((BvvHandleFrame) source.getBvvHandle()).getBigVolumeViewer();
        if (currentBvv != bvv) { // Initialize overlay if not already done
            currentBvv = bvv;
            initializePathOverlay(currentBvv);
            initializeAnnotationOverlay(currentBvv);
            pathOverlay.updatePaths();
        }
        final VolumeViewerFrame bvvFrame = bvv.getViewerFrame();
        final BvvActions actions = new BvvActions(bvv);
        bvvFrame.getCardPanel().addCard("Camera Controls",
                new CameraControls(this, source, pathOverlay.overlayRenderer).getToolbar(actions), true);
        bvvFrame.getCardPanel().addCard("SNT Annotations", sntToolbar(actions), true);
        SwingUtilities.invokeLater(bvv::expandAndFocusCardPanel);
    }

    /** Initializes the path overlay system for drawing traced paths. */
    private void initializePathOverlay(final BigVolumeViewer bvv) {
        if (pathOverlay != null) {
            pathOverlay.dispose();
        }
        pathOverlay = new PathOverlay(bvv, this);
    }

    /** Initializes the annotation overlay system for drawing spheres at SNTPoint locations. */
    private void initializeAnnotationOverlay(final BigVolumeViewer bvv) {
        if (annotationOverlay != null) {
            annotationOverlay.dispose();
        }
        annotationOverlay = new AnnotationOverlay(bvv, this);
    }

    private JToolBar sntToolbar(final BvvActions actions) {
        final JToolBar toolbar = new JToolBar();
        toolbar.add(GuiUtils.Buttons.toolbarToggleButton(actions.togggleVisibilityAction(),
                "Show/hide annotations",
                IconFactory.GLYPH.EYE, IconFactory.GLYPH.EYE_SLASH));
        toolbar.add(GuiUtils.Buttons.toolbarToggleButton(actions.togglePersistentAnnotationsAction(),
                "Restrict display of annotations around cursor",
                IconFactory.GLYPH.COMPUTER_MOUSE, IconFactory.GLYPH.COMPUTER_MOUSE));
        toolbar.addSeparator();
        toolbar.add(Box.createHorizontalStrut(10));
        toolbar.add(GuiUtils.Buttons.toolbarButton(actions.setDefaultColorAction(),
                "Change default annotation color"));
        toolbar.add(GuiUtils.Buttons.undo(actions.resetDefaultColorAction()));
        toolbar.add(GuiUtils.Buttons.toolbarButton(actions.setTransparencyAction(),
                "Change transparency of annotations"));
        toolbar.add(GuiUtils.Buttons.undo(actions.resetTransparencyAction()));
        toolbar.add(Box.createHorizontalStrut(10));
        toolbar.addSeparator();
        toolbar.add(Box.createHorizontalStrut(10));
        toolbar.add(GuiUtils.Buttons.toolbarButton(actions.setCanvasOffsetAction(),
                "Change annotations offset"));
        toolbar.add(GuiUtils.Buttons.undo(actions.resetCanvasOffsetAction()));
        toolbar.add(GuiUtils.Buttons.toolbarButton(actions.setThicknessMultiplierAction(),
                "Change thickness of annotations"));
        toolbar.add(GuiUtils.Buttons.undo(actions.resetThicknessMultiplierAction()));
        toolbar.add(Box.createHorizontalStrut(10));
        toolbar.addSeparator();
        toolbar.add(Box.createHorizontalGlue());
        toolbar.addSeparator();
        final JButton optionsButton = optionsButton(actions);
        toolbar.add(optionsButton);
        return toolbar;
    }

    private JButton optionsButton(final BvvActions actions) {
        final JPopupMenu menu = new JPopupMenu();
        final JButton oButton = GuiUtils.Buttons.OptionsButton(IconFactory.GLYPH.OPTIONS, 1f, menu);
        menu.add(new JMenuItem(actions.importAction()));
        if (snt != null) {
            menu.addSeparator();
            menu.add(new JMenuItem(actions.loadBookmarksAction()));
            menu.add(new JMenuItem(actions.syncPathManagerAction()));
        }
        menu.addSeparator();
        menu.add(new JMenuItem(actions.clearAllPathsAction()));
        return oButton;
    }


    /**
     * Adds a Tree to the viewer as overlay
     *
     * @param tree the Tree to render
     */
    public void addTree(final Tree tree) {
        addTree(tree, true);
    }

    /**
     * Script friendly method to add a supported object ({@link Tree},
     * {@link DirectedWeightedGraph}) to the viewer overlay.
     * Collections are also supported, which is an effective way of adding
     * multiple items since the scene is only updated once all items
     * have been added.
     *
     * @param object the object to be added. Null objects are ignored
     * @throws IllegalArgumentException if object is not supported
     */
    public void add(final Object object) {
        add(object, true);
    }

    private void add(final Object object, final boolean updateScene) {
        switch (object) {
            case null -> SNTUtils.log("Null object ignored for scene addition");
            case Tree tree1 -> addTree(tree1, updateScene);
            case Path path -> {
                final Tree tree = new Tree();
                tree.add(path);
                addTree(tree, updateScene);
            }
            case DirectedWeightedGraph directedWeightedGraph -> {
                final Tree tree = directedWeightedGraph.getTree();
                tree.setColor(SNTColor.getDistinctColors(1)[0]);
                addTree(tree, updateScene);
            }
            case Collection<?> collection -> addCollection(collection, true);
            default -> throw new IllegalArgumentException("Unsupported object: " + object.getClass().getName());
        }
    }

    private void addCollection(final Collection<?> collection, final boolean updateView) {
        for (final Object o : collection)
            add(o, false);
        if (updateView) syncOverlays();
    }

    private void addTree(final Tree tree, final boolean updateScene) {
        if (tree == null || tree.isEmpty()) {
            throw new IllegalArgumentException("Tree cannot be null or empty");
        }
        final String label = getUniqueLabel(tree);
        renderedTrees.put(label, tree);
        if (updateScene) syncOverlays();
    }

    /**
     * Updates the viewer display to reflect changes in rendered trees and paths.
     * This method should be called after modifying the collection of rendered objects
     * to ensure the display is synchronized.
     */
    public void syncOverlays() {
        if (pathOverlay != null) pathOverlay.updatePaths();
        if (annotationOverlay != null) annotationOverlay.updateScene();
    }

    public AnnotationOverlay annotations() {
        return annotationOverlay;
    }

    /**
     * Forces a repaint of the viewer, updating volume renderings but not overlays.
     */
    public void repaint() {
        if (currentBvv != null) {
            currentBvv.getViewer().requestRepaint();
        }
    }

    /**
     * Removes a rendered tree from the viewer.
     *
     * @param treeLabel the label of the tree to remove
     * @return true if the tree was successfully removed
     */
    public boolean removeTree(final String treeLabel) {
        final Tree removedTree = renderedTrees.remove(treeLabel);
        if (removedTree != null && pathOverlay != null) {
            syncOverlays();
            return true;
        }
        return false;
    }

    /**
     * Clears all rendered trees from the overlay.
     */
    public void clearAllTrees() {
        renderedTrees.clear();
        syncOverlays();
    }

    private String getUniqueLabel(final Tree tree) {
        String baseLabel = tree.getLabel();
        if (baseLabel == null || baseLabel.trim().isEmpty()) {
            baseLabel = "Tree";
        }

        String label = baseLabel;
        int counter = 1;
        while (renderedTrees.containsKey(label)) {
            label = baseLabel + " (" + (++counter) + ")";
        }
        return label;
    }

    /**
     * Gets all the trees currently rendered.
     *
     * @return collection of rendered trees
     */
    public Collection<Tree> getRenderedTrees() {
        return renderedTrees.values();
    }

    /**
     * @return a reference to the viewer's frame.
     */
    public VolumeViewerFrame getViewerFrame() {
        return (currentBvv == null) ? null : currentBvv.getViewerFrame();
    }

    /**
     * @return a reference to the viewer's options.
     */
    public BvvOptions getOptions() {
        return options;
    }

    /**
     * @return a reference to the underlying BigVolumeViewer instance.
     */
    public BigVolumeViewer getViewer() {
        return currentBvv;
    }

    /**
     * @return a reference to the viewer's panel.
     */
    public VolumeViewerPanel getViewerPanel() {
        return (currentBvv == null) ? null : currentBvv.getViewer();
    }

    /**
     * Gets the path rendering options for controlling thickness, transparency, etc.
     *
     * @return the rendering options
     */
    public PathRenderingOptions getRenderingOptions() {
        return renderingOptions;
    }

    /**
     * Offsets all paths being rendered.
     * This allows for 'dislodging' paths from their underlying signal without altering their coordinates.
     *
     * @param offsetX X offset (calibrated distance)
     * @param offsetY Y offset (calibrated distance)
     * @param offsetZ Z offset (calibrated distance)
     */
    public void setCanvasOffset(final double offsetX, final double offsetY, final double offsetZ) {
        for (final Tree tree : renderedTrees.values()) {
            tree.applyCanvasOffset(offsetX, offsetY, offsetZ);
        }
        syncOverlays();
        renderingOptions.canvasOffset = (offsetX == 0 && offsetY == 0d && offsetZ == 0d) ? null : SNTPoint.of(offsetX, offsetY, offsetZ);
    }

    // ---- methods for SNT Bvv instance
    /**
     * Displays the main tracing data from the associated SNT instance.
     * This method is only available for BVV instances that are tethered to an SNT instance.
     *
     * @return the BvvSource representing the displayed tracing data
     * @throws IllegalArgumentException if this is a standalone viewer or no valid image data is available
     */
    @SuppressWarnings("UnusedReturnValue")
    public BvvSource showLoadedData() {
        return displayData(false);
    }


    /**
     * Displays the secondary tracing data from the associated SNT instance.
     * This method is only available for BVV instances that are tethered to an SNT instance.
     *
     * @return the BvvSource representing the displayed secondary data
     * @throws IllegalArgumentException if this is a standalone viewer or no valid image data is available
     */
    @SuppressWarnings("UnusedReturnValue")
    public BvvSource showSecondaryData() {
        return displayData(true);
    }

    /**
     * Synchronizes the Path Manager contents with BVV display.
     *
     * @return true if synchronization was successful
     * @throws IllegalArgumentException if this is a standalone viewer not tethered to a SNT instance
     */
    public boolean syncPathManagerList() {
        if (snt == null)
            throw new IllegalArgumentException("This function is only available in snt-aware Bvv instances");
        if (snt.getPathAndFillManager().size() == 0)
            return false;
        final Collection<Tree> trees = snt.getPathAndFillManager().getTrees();
        final List<String> existingTreeLabels = trees.stream().map(Tree::getLabel).toList();
        existingTreeLabels.forEach(renderedTrees.keySet()::remove);
        addCollection(trees, true);
        return true;
    }

    /**
     * Captures a screenshot of the BVV viewer.
     *
     * @return BufferedImage of the current view, or null if capture fails
     */
    public ImagePlus screenshot() {
        if (bvvHandle == null) return null;

        final Component panel = bvvHandle.getViewerPanel();

        // Get bounds on EDT
        final Rectangle[] boundsHolder = new Rectangle[1];
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                boundsHolder[0] = getScreenBounds(panel);
            } else {
                SwingUtilities.invokeAndWait(() -> boundsHolder[0] = getScreenBounds(panel));
            }
        } catch (final InterruptedException | InvocationTargetException e) {
            SNTUtils.error("Could not retrieved panel bounds", e);
            return null;
        }

        if (boundsHolder[0] == null) return null;

        // Robot capture can be called from any thread
        try {
            return new ImagePlus("BVV Screenshot", new Robot().createScreenCapture(boundsHolder[0]));
        } catch (final AWTException e) {
            SNTUtils.error("Screenshot not captured", e);
            return null;
        }
    }

    /**
     * Captures a screenshot and saves to file.
     *
     * @param filePath path to save PNG file
     * @return true if successful
     */
    public boolean screenshot(final String filePath) {
        final ImagePlus image = screenshot();
        if (image == null) return false;
        try {
            ImpUtils.save(image, filePath);
        } catch (final Exception e) {
            SNTUtils.error("Screenshot not saved", e);
            return false;
        }
        return true;
    }

    private static Rectangle getScreenBounds(final Component component) {
        if (!component.isShowing()) return null;
        try {
            final Point location = component.getLocationOnScreen();
            return new Rectangle(location.x, location.y, component.getWidth(), component.getHeight());
        } catch (final IllegalComponentStateException e) {
            return null;
        }
    }

    /**
     * Primitive Camera controls and camera parameter management
     */
    private static class CameraControls {

        private final Bvv bvvInstance;
        private final OverlayRenderer overlayRenderer;
        private final JSpinner dCamSpinner;
        private final JSpinner nearSpinner;
        private final JSpinner farSpinner;
        private final ViewerState snapshot;

        CameraControls(final Bvv bvvInstance, final BvvSource source, final OverlayRenderer overlayRenderer) {
            this.bvvInstance = bvvInstance;
            snapshot = source.getBvvHandle().getViewerPanel().state().snapshot();
            this.overlayRenderer = overlayRenderer;
            this.dCamSpinner = GuiUtils.integerSpinner((int) overlayRenderer.dCam, 10, 10000, 50, true);
            this.nearSpinner = GuiUtils.integerSpinner((int) overlayRenderer.nearClip, 100, 10000, 50, true);
            this.farSpinner = GuiUtils.integerSpinner((int) overlayRenderer.farClip, 100, 10000, 50, true);
            setupSpinners();
        }

        private double[] defaultCamParams() {
            return new double[]{2000, 1000, 1000}; // default in BBVOptions: dCam, dClipNear, dClipFar
        }

        private void setupSpinners() {
            dCamSpinner.addChangeListener(e -> updateCameraParameters(false));
            nearSpinner.addChangeListener(e -> updateCameraParameters(true));
            farSpinner.addChangeListener(e -> updateCameraParameters(true));
            dCamSpinner.setToolTipText("Distance from camera to z=0 plane in physical units");
            nearSpinner.setToolTipText("Near clipping plane in physical units");
            farSpinner.setToolTipText("Distant clipping plane in physical units");
        }

        private void updateCameraParameters(final boolean updatePlaneSpinners) {
            bvvInstance.getViewerFrame().getViewerPanel().setCamParams(
                    overlayRenderer.dCam = ((Number) dCamSpinner.getValue()).doubleValue(),
                    overlayRenderer.nearClip = ((Number) nearSpinner.getValue()).doubleValue(),
                    overlayRenderer.farClip = ((Number) farSpinner.getValue()).doubleValue()
            );
            bvvInstance.syncOverlays();
            if (updatePlaneSpinners) {
                SwingUtilities.invokeLater(() -> {
                    nearSpinner.setValue((int) Math.min(overlayRenderer.farClip, overlayRenderer.nearClip));
                    farSpinner.setValue((int) Math.max(overlayRenderer.farClip, overlayRenderer.nearClip));
                });
            }
        }

        private Action resetViewAction() {
            return new AbstractAction("Reset", IconFactory.menuIcon(IconFactory.GLYPH.RECYCLE)) {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    bvvInstance.currentBvv.getViewer().state().setViewerTransform(snapshot.getViewerTransform());
                    SwingUtilities.invokeLater(() -> {
                        dCamSpinner.setValue(defaultCamParams()[0]);
                        nearSpinner.setValue(defaultCamParams()[1]);
                        farSpinner.setValue(defaultCamParams()[2]);
                    });
                    updateCameraParameters(false); // will call repaint()
                }
            };
        }

        private Action resetCameraDistanceAction() {
            return new AbstractAction() {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    dCamSpinner.setValue(defaultCamParams()[0]);
                    updateCameraParameters(false);
                }
            };
        }

        private Action resetNearClipAction() {
            return new AbstractAction() {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    nearSpinner.setValue(defaultCamParams()[1]);
                    updateCameraParameters(true);
                }
            };
        }

        private Action resetFarClipAction() {
            return new AbstractAction() {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    farSpinner.setValue(defaultCamParams()[2]);
                    updateCameraParameters(true);
                }
            };
        }

        /**
         * Creates and returns a toolbar containing camera control components.
         * The toolbar includes spinners for camera distance, near clipping plane, and far clipping plane,
         * along with reset buttons and an options menu.
         *
         * @param bvvActions the BVV actions instance for accessing additional functionality
         * @return a configured JToolBar with camera controls
         */
        public JToolBar getToolbar(final BvvActions bvvActions) {
            final JButton dCamReset = GuiUtils.Buttons.undo(resetCameraDistanceAction());
            final JButton nearReset = GuiUtils.Buttons.undo(resetNearClipAction());
            final JButton farReset = GuiUtils.Buttons.undo(resetFarClipAction());
            final JToolBar toolbar = new JToolBar();
            addSpinnerToToolbar(toolbar, '\uf1e5', dCamSpinner, dCamReset);
            addSpinnerToToolbar(toolbar, '\ue4b8', nearSpinner, nearReset);
            addSpinnerToToolbar(toolbar, '\ue4c2', farSpinner, farReset);
            toolbar.add(Box.createHorizontalGlue());
            toolbar.addSeparator();
            toolbar.add(optionsButton(bvvActions));
            return toolbar;
        }

        private void addSpinnerToToolbar(final JToolBar toolbar, final char spinnerSolidIcon, final JSpinner spinner, final AbstractButton spinnerButton) {
            toolbar.add(new JLabel(IconFactory.buttonIcon(spinnerSolidIcon, true)));
            toolbar.add(Box.createHorizontalStrut(2));
            toolbar.add(spinner);
            toolbar.add(spinnerButton);
            toolbar.add(Box.createHorizontalStrut(10));
        }

        private JButton optionsButton(final BvvActions actions) {
            final JPopupMenu menu = new JPopupMenu();
            final JButton oButton = GuiUtils.Buttons.OptionsButton(IconFactory.GLYPH.OPTIONS, 1f, menu);
            GuiUtils.addSeparator(menu, "Restore View");
            menu.add(new JMenuItem(resetViewAction()));
            menu.add(new JMenuItem(actions.loadSettingsAction()));
            menu.add(new JMenuItem(actions.saveSettingsAction()));
            GuiUtils.addSeparator(menu, "Help");
            menu.add(new JMenuItem(actions.showHelpAction()));
            return oButton;
        }
    }

    /**
     * Configuration options for path rendering.
     * Controls thickness, transparency, and other visual properties.
     */
    public static class PathRenderingOptions {
        private float thicknessMultiplier = 1.0f;
        private float transparency = 1.0f; // 1.0 = opaque, 0.0 = transparent
        private boolean usePathRadius = true;
        private float minThickness = 1.0f;
        private float maxThickness = 100.0f;
        private SNTPoint canvasOffset;
        private Color fallbackColor = Color.MAGENTA;
        private float clippingDistance;

        /**
         * Gets the thickness multiplier for path rendering.
         *
         * @return thickness multiplier (default: 1.0)
         */
        public float getThicknessMultiplier() {
            return thicknessMultiplier;
        }

        /**
         * Sets the thickness multiplier for path rendering.
         *
         * @param multiplier thickness multiplier (1.0 = normal, 2.0 = double thickness, etc.)
         */
        public void setThicknessMultiplier(float multiplier) {
            this.thicknessMultiplier = Math.max(0.1f, multiplier);
        }

        /**
         * Gets the transparency level for path rendering.
         *
         * @return transparency (1.0 = opaque, 0.0 = fully transparent)
         */
        public float getTransparency() {
            return transparency;
        }

        /**
         * Sets the transparency level for path rendering.
         *
         * @param transparency transparency level (1.0 = opaque, 0.0 = fully transparent)
         */
        public void setTransparency(float transparency) {
            this.transparency = Math.max(0.0f, Math.min(1.0f, transparency));
        }

        /**
         * Gets whether to use path radius for thickness calculation.
         *
         * @return true if using path radius
         */
        public boolean isUsePathRadius() {
            return usePathRadius;
        }

        /**
         * Sets whether to use path radius for thickness calculation.
         *
         * @param usePathRadius true to use path radius, false for uniform thickness
         */
        public void setUsePathRadius(boolean usePathRadius) {
            this.usePathRadius = usePathRadius;
        }

        /**
         * Gets the minimum thickness for path rendering.
         *
         * @return minimum thickness in pixels
         */
        public float getMinThickness() {
            return minThickness;
        }

        /**
         * Sets the minimum thickness for path rendering.
         *
         * @param minThickness minimum thickness in pixels
         */
        public void setMinThickness(float minThickness) {
            this.minThickness = Math.max(0.1f, minThickness);
        }

        /**
         * Gets the maximum thickness for path rendering.
         *
         * @return maximum thickness in pixels
         */
        public float getMaxThickness() {
            return maxThickness;
        }

        /**
         * Sets the maximum thickness for path rendering.
         *
         * @param maxThickness maximum thickness in pixels
         */
        public void setMaxThickness(float maxThickness) {
            this.maxThickness = Math.max(1.0f, maxThickness);
        }


        /**
         * Enables or disables 'clipped visibility' for path overlays.
         * When enabled, only path nodes within the specified distance from cursor are displayed.
         * When disabled, paths are always visible regardless of cursor positon
         *
         * @param clippingDistance the clippingDistance (in real world units). Set to zero to disable clipping
         */
        public void setClippingDistance(final float clippingDistance) {
            this.clippingDistance = clippingDistance;
        }

        /**
         * Gets whether 'clipped visibility' is enabled
         *
         * @return true if persistent visibility is enabled
         * @see #setClippingDistance(float)
         */
        public boolean isClippingEnabled() {
            return clippingDistance > 0;
        }
    }

    /** Path overlay class for drawing traced paths on top of BVV volume data. */
    private static class PathOverlay {
        private final Bvv sntBvv;
        private final VolumeViewerPanel viewerPanel;
        private final OverlayRenderer overlayRenderer;

        PathOverlay(final BigVolumeViewer bvv, final Bvv sntBvv) {
            this.sntBvv = sntBvv;
            this.viewerPanel = bvv.getViewer();
            // Add the overlay renderer to the viewer
            this.overlayRenderer = new OverlayRenderer(viewerPanel, sntBvv.getRenderingOptions());
            viewerPanel.getDisplay().overlays().add(overlayRenderer);
        }

        void disableRendering(final boolean disable) {
            overlayRenderer.hide = disable;
            viewerPanel.requestRepaint();
        }

        boolean isRenderingEnable() {
            return !overlayRenderer.hide;
        }

        void updatePaths() {
            final Collection<Tree> trees = sntBvv.getRenderedTrees();
            overlayRenderer.updatePaths(trees);
            viewerPanel.requestRepaint();
        }

        void dispose() {
            if (viewerPanel != null && overlayRenderer != null) {
                viewerPanel.getDisplay().overlays().remove(overlayRenderer);
            }
        }
    }

    /**
     * Renders spherical annotations at {@link SNTPoint} world coordinates.
     * Rendered with CPU (Java2D) but optimized with caching and batched rendering.
     */
    public static class AnnotationOverlay {

        private final VolumeViewerPanel viewerPanel;
        private final AnnRenderer annRenderer;
        private final List<Annotation> annotations = new ArrayList<>();

        AnnotationOverlay(final BigVolumeViewer bvv, final Bvv sntBvv) {
            this.viewerPanel = bvv.getViewer();
            this.annRenderer = new AnnRenderer(viewerPanel, sntBvv.getRenderingOptions());
            viewerPanel.getDisplay().overlays().add(annRenderer);
        }

        /**
         * Replace the current annotations with the provided list.
         */
        public void setAnnotations(final Collection<SNTPoint> points, final float radius, final Color color) {
            annotations.clear();
            addAnnotations(points, radius, color);
        }

        /**
         * Add annotations to the current list.
         */
        public void addAnnotations(final Collection<SNTPoint> points, final float radius, final Color color) {
            if (points != null) {
                for (SNTPoint p : points) annotations.add(new Annotation(p, radius, color));
            }
            updateScene();
        }

        /**
         * Add a single annotation.
         */
        public void addAnnotation(final SNTPoint p, final float radius, final Color color) {
            if (p != null) annotations.add(new Annotation(p, radius, color));
            updateScene();
        }

        /**
         * Remove all annotations.
         */
        public void clear() {
            annotations.clear();
            updateScene();
        }

        public boolean isVisible() {
            return !annRenderer.hide;
        }

        /**
         * Show/hide annotations
         */
        public void setVisible(final boolean visible) {
            annRenderer.hide = !visible;
            viewerPanel.requestRepaint();
        }

        /**
         * Ensure renderers/nodes reflect current annotation list.
         */
        public void updateScene() {
            annRenderer.setAnnotations(annotations);
            viewerPanel.requestRepaint();
        }

        /**
         * Remove overlay/nodes from the viewer.
         */
        void dispose() {
            if (viewerPanel != null && annRenderer != null) {
                viewerPanel.getDisplay().overlays().remove(annRenderer);
            }
        }

        /**
         * Optimized annotation renderer with caching and batched drawing.
         */
        private static class AnnRenderer implements bdv.viewer.OverlayRenderer {

            // Camera parameters
            private static final double D_CAM = 2000;
            private final VolumeViewerPanel viewerPanel;
            private final PathRenderingOptions renderingOptions;
            private final AffineTransform3D viewerTransform = new AffineTransform3D();
            // === CACHING ===
            private final AffineTransform3D cachedTransform = new AffineTransform3D();
            // Reusable coordinate arrays
            private final double[] worldCoords = new double[3];
            private final double[] viewerCoords = new double[3];
            boolean hide;
            // Source annotations
            private List<Annotation> annotations = new ArrayList<>();
            private boolean cacheValid = false;
            // Cached screen data
            private AnnotationScreenData[] screenData;
            // Canvas dimensions cache
            private int canvasWidth, canvasHeight;
            private double centerX, centerY;

            AnnRenderer(final VolumeViewerPanel viewerPanel, final PathRenderingOptions renderingOptions) {
                this.viewerPanel = viewerPanel;
                this.renderingOptions = renderingOptions;
            }

            void setAnnotations(final List<Annotation> list) {
                this.annotations = (list != null) ? new ArrayList<>(list) : new ArrayList<>();
                invalidateCache();
            }

            void invalidateCache() {
                cacheValid = false;
                screenData = null;
            }

            @Override
            public void drawOverlays(final Graphics g) {
                if (annotations.isEmpty() || hide) return;

                // Update canvas dimensions
                canvasWidth = viewerPanel.getDisplay().getWidth();
                canvasHeight = viewerPanel.getDisplay().getHeight();
                centerX = canvasWidth / 2.0;
                centerY = canvasHeight / 2.0;

                // Check if transform changed
                synchronized (viewerTransform) {
                    viewerPanel.state().getViewerTransform(viewerTransform);
                }

                if (!transformEquals(viewerTransform, cachedTransform)) {
                    cachedTransform.set(viewerTransform);
                    cacheValid = false;
                }

                // Compute screen data if needed
                if (!cacheValid || screenData == null) {
                    computeScreenData();
                    cacheValid = true;
                }

                // Get clipping info
                final boolean doClip = renderingOptions.isClippingEnabled();
                final double[] clipPos = doClip ? getClipPosition() : null;
                final float clipDist = renderingOptions.clippingDistance;

                final Graphics2D g2d = setupGraphics(g);

                // Batch by color for more efficient rendering
                final Map<Color, List<Integer>> colorBatches = new HashMap<>();

                for (int i = 0; i < screenData.length; i++) {
                    final AnnotationScreenData data = screenData[i];

                    // Skip off-screen
                    if (!data.visible) continue;

                    // Z-clipping
                    if (doClip && Math.abs(data.worldZ - clipPos[2]) > clipDist) continue;

                    // Skip subpixel annotations
                    if (data.screenRadius < 0.5) continue;

                    colorBatches.computeIfAbsent(data.color, k -> new ArrayList<>()).add(i);
                }

                // Draw batches
                for (final Map.Entry<Color, List<Integer>> entry : colorBatches.entrySet()) {
                    g2d.setColor(entry.getKey());
                    for (final int idx : entry.getValue()) {
                        final AnnotationScreenData data = screenData[idx];
                        final int d = (int) Math.round(2 * data.screenRadius);
                        final int x = (int) Math.round(data.screenX - data.screenRadius);
                        final int y = (int) Math.round(data.screenY - data.screenRadius);
                        g2d.fillOval(x, y, d, d);
                    }
                }

                g2d.dispose();
            }

            private void computeScreenData() {
                screenData = new AnnotationScreenData[annotations.size()];

                final double margin = 100.0;

                for (int i = 0; i < annotations.size(); i++) {
                    final Annotation ann = annotations.get(i);
                    final AnnotationScreenData data = new AnnotationScreenData();

                    // World coordinates
                    worldCoords[0] = ann.p.getX();
                    worldCoords[1] = ann.p.getY();
                    worldCoords[2] = ann.p.getZ();
                    data.worldZ = worldCoords[2];

                    // Transform to viewer coordinates
                    viewerTransform.apply(worldCoords, viewerCoords);

                    // Perspective projection
                    final double pf = D_CAM / (D_CAM + viewerCoords[2]);
                    data.screenX = centerX + (viewerCoords[0] - centerX) * pf;
                    data.screenY = centerY + (viewerCoords[1] - centerY) * pf;
                    data.screenRadius = Math.max(1.0, ann.radiusUm * pf);
                    data.color = ann.color;

                    // Visibility check
                    data.visible = data.screenX >= -margin && data.screenY >= -margin &&
                            data.screenX <= canvasWidth + margin &&
                            data.screenY <= canvasHeight + margin;

                    screenData[i] = data;
                }
            }

            private double[] getClipPosition() {
                final double[] gPos = new double[3];
                viewerPanel.getGlobalMouseCoordinates(RealPoint.wrap(gPos));
                return gPos;
            }

            private boolean transformEquals(final AffineTransform3D a, final AffineTransform3D b) {
                for (int i = 0; i < 3; i++) {
                    for (int j = 0; j < 4; j++) {
                        if (Math.abs(a.get(i, j) - b.get(i, j)) > 1e-9) return false;
                    }
                }
                return true;
            }

            private Graphics2D setupGraphics(final Graphics g) {
                final Graphics2D g2d = (Graphics2D) g.create();
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (renderingOptions.getTransparency() < 1.0f) {
                    g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,
                            renderingOptions.getTransparency()));
                }
                return g2d;
            }

            @Override
            public void setCanvasSize(final int width, final int height) {
                if (width != canvasWidth || height != canvasHeight) {
                    invalidateCache();
                }
            }

            /**
             * Cached screen-space data for a single annotation
             */
            private static class AnnotationScreenData {
                double screenX, screenY;
                double screenRadius;
                double worldZ;
                Color color;
                boolean visible;
            }
        }

        private record Annotation(SNTPoint p, float radiusUm, Color color) {
        }
    }

    /**
     * Optimized overlay renderer for drawing SNT paths.
     * Uses caching, batching, bounding box culling, and LOD to improve performance.
     */
    private static class OverlayRenderer implements bdv.viewer.OverlayRenderer {

        final VolumeViewerPanel viewerPanel;
        final PathRenderingOptions renderingOptions;
        final AffineTransform3D viewerTransform = new AffineTransform3D();
        private final AffineTransform3D cachedTransform = new AffineTransform3D();
        // Cached screen data per tree
        private final Map<Tree, TreeScreenData> screenDataCache = new WeakHashMap<>();
        // Reusable coordinate arrays (avoid allocation in render loop)
        private final double[] worldCoords = new double[3];
        private final double[] viewerCoords = new double[3];
        private final double[] screenBoundsMin = new double[2];
        private final double[] screenBoundsMax = new double[2];
        // Reusable Path2D for batched drawing
        private final Path2D.Double batchPath = new Path2D.Double();
        boolean hide;
        // Camera parameters
        double dCam;
        double nearClip;
        double farClip;
        // === CACHING ===
        private Collection<Tree> trees = new ArrayList<>();
        private boolean cacheValid = false;
        // Canvas dimensions cache
        private int canvasWidth, canvasHeight;
        private double centerX, centerY;

        OverlayRenderer(final VolumeViewerPanel viewerPanel, final PathRenderingOptions renderingOptions) {
            this.viewerPanel = viewerPanel;
            this.renderingOptions = renderingOptions;
            viewerPanel.setCamParams(dCam = 2000, nearClip = 1000, farClip = 1000);
        }

        void updatePaths(final Collection<Tree> trees) {
            this.trees = new ArrayList<>(trees);
            invalidateCache();
        }

        void invalidateCache() {
            cacheValid = false;
            screenDataCache.clear();
        }

        @Override
        public void drawOverlays(final Graphics g) {
            if (trees.isEmpty() || hide) return;

            // Update canvas dimensions
            canvasWidth = viewerPanel.getDisplay().getWidth();
            canvasHeight = viewerPanel.getDisplay().getHeight();
            centerX = canvasWidth / 2.0;
            centerY = canvasHeight / 2.0;

            // Check if transform changed
            synchronized (viewerTransform) {
                viewerPanel.state().getViewerTransform(viewerTransform);
            }

            if (!transformEquals(viewerTransform, cachedTransform)) {
                cachedTransform.set(viewerTransform);
                cacheValid = false;
            }

            final Graphics2D g2d = setupGraphics(g);

            // Get current clipping state
            final double[] clipPos = renderingOptions.isClippingEnabled() ? getClipPosition() : null;

            for (final Tree tree : trees) {
                // tree level bounding box culling
                if (!isTreePotentiallyVisible(tree)) {
                    continue; // Skip entire tree
                }
                drawTreeOptimized(g2d, tree, clipPos);
            }

            g2d.dispose();
        }

        /**
         * Quick rejection test using tree's bounding box.
         * Projects all 8 corners to screen space and checks overlap with canvas.
         */
        private boolean isTreePotentiallyVisible(final Tree tree) {
            final BoundingBox bbox = tree.getBoundingBox();
            if (bbox == null) return true; // this should never happen

            final PointInImage origin = bbox.origin();
            final PointInImage opposite = bbox.originOpposite();

            if (origin == null || opposite == null) return true;

            final double[] worldMin = {origin.getX(), origin.getY(), origin.getZ()};
            final double[] worldMax = {opposite.getX(), opposite.getY(), opposite.getZ()};

            projectBoundsToScreen(worldMin, worldMax, screenBoundsMin, screenBoundsMax);

            // Check if projected bounds overlap with canvas (with margin for thick paths)
            final double margin = renderingOptions.getMaxThickness();
            return screenBoundsMax[0] >= -margin && screenBoundsMin[0] <= canvasWidth + margin &&
                    screenBoundsMax[1] >= -margin && screenBoundsMin[1] <= canvasHeight + margin;
        }

        /**
         * Projects a 3D bounding box to screen space, computing the 2D AABB.
         */
        private void projectBoundsToScreen(final double[] worldMin, final double[] worldMax,
                                           final double[] screenMin, final double[] screenMax) {
            screenMin[0] = Double.MAX_VALUE;
            screenMin[1] = Double.MAX_VALUE;
            screenMax[0] = -Double.MAX_VALUE;
            screenMax[1] = -Double.MAX_VALUE;

            // Project all 8 corners of the bounding box
            for (int i = 0; i < 8; i++) {
                worldCoords[0] = ((i & 1) == 0) ? worldMin[0] : worldMax[0];
                worldCoords[1] = ((i & 2) == 0) ? worldMin[1] : worldMax[1];
                worldCoords[2] = ((i & 4) == 0) ? worldMin[2] : worldMax[2];

                viewerTransform.apply(worldCoords, viewerCoords);

                final double pf = dCam / (dCam + viewerCoords[2]);
                final double sx = centerX + (viewerCoords[0] - centerX) * pf;
                final double sy = centerY + (viewerCoords[1] - centerY) * pf;

                screenMin[0] = Math.min(screenMin[0], sx);
                screenMin[1] = Math.min(screenMin[1], sy);
                screenMax[0] = Math.max(screenMax[0], sx);
                screenMax[1] = Math.max(screenMax[1], sy);
            }
        }

        private double[] getClipPosition() {
            final double[] gPos = new double[3];
            viewerPanel.getGlobalMouseCoordinates(RealPoint.wrap(gPos));
            return gPos;
        }

        private void drawTreeOptimized(final Graphics2D g2d, final Tree tree, final double[] clipPos) {
            // Get or compute screen data
            TreeScreenData screenData = screenDataCache.get(tree);
            if (screenData == null || !cacheValid) {
                screenData = computeScreenData(tree);
                screenDataCache.put(tree, screenData);
            }

            // Batch render by color
            final Map<Color, List<SegmentData>> colorBatches = new HashMap<>();

            for (final PathScreenData pathData : screenData.paths) {
                batchPathSegments(pathData, clipPos, colorBatches);
            }

            // Draw all batches
            for (final Map.Entry<Color, List<SegmentData>> entry : colorBatches.entrySet()) {
                drawBatch(g2d, entry.getKey(), entry.getValue());
            }
        }

        private TreeScreenData computeScreenData(final Tree tree) {
            final TreeScreenData data = new TreeScreenData();

            // Extract scale from transform once
            final double avgScale = getAverageScale();

            for (final Path path : tree.list()) {
                if (path.size() < 1) continue;

                final PathScreenData pathData = new PathScreenData(path.size());
                final Color defaultColor = path.getColor() != null ? path.getColor() : renderingOptions.fallbackColor;
                final boolean hasNodeColors = path.hasNodeColors();

                for (int i = 0; i < path.size(); i++) {
                    final PointInImage point = path.getNode(i);

                    // Transform to screen coordinates
                    worldCoords[0] = point.x;
                    worldCoords[1] = point.y;
                    worldCoords[2] = point.z;

                    // Apply canvas offset if present
                    final sc.fiji.snt.util.PointInCanvas offset = path.getCanvasOffset();
                    if (offset != null) {
                        worldCoords[0] += offset.x;
                        worldCoords[1] += offset.y;
                        worldCoords[2] += offset.z;
                    }

                    viewerTransform.apply(worldCoords, viewerCoords);

                    // Perspective projection
                    final double pf = dCam / (dCam + viewerCoords[2]);
                    pathData.screenX[i] = centerX + (viewerCoords[0] - centerX) * pf;
                    pathData.screenY[i] = centerY + (viewerCoords[1] - centerY) * pf;
                    pathData.worldZ[i] = worldCoords[2];

                    // Screen radius
                    final double nodeRadius = getNodeRadius(path, i);
                    pathData.screenRadius[i] = Math.max(0.5, nodeRadius * avgScale * pf);

                    // Color
                    pathData.colors[i] = hasNodeColors && path.getNodeColor(i) != null
                            ? path.getNodeColor(i) : defaultColor;

                    // Visibility (basic bounds check)
                    pathData.visible[i] = isOnScreen(pathData.screenX[i], pathData.screenY[i]);
                }

                data.paths.add(pathData);
            }

            return data;
        }

        private void batchPathSegments(final PathScreenData pathData, final double[] clipPos,
                                       final Map<Color, List<SegmentData>> batches) {

            final float clipDist = renderingOptions.clippingDistance;
            final boolean doClip = clipPos != null;

            for (int i = 0; i < pathData.size - 1; i++) {
                // Skip if both endpoints are off-screen
                if (!pathData.visible[i] && !pathData.visible[i + 1]) continue;

                // Z-clipping
                if (doClip) {
                    if (Math.abs(pathData.worldZ[i] - clipPos[2]) > clipDist &&
                            Math.abs(pathData.worldZ[i + 1] - clipPos[2]) > clipDist) {
                        continue;
                    }
                }

                // Skip subpixel segments
                final double dx = pathData.screenX[i + 1] - pathData.screenX[i];
                final double dy = pathData.screenY[i + 1] - pathData.screenY[i];
                final double length = Math.sqrt(dx * dx + dy * dy);
                if (length < 0.5) continue;

                // Create segment data
                final SegmentData seg = new SegmentData();
                seg.x1 = pathData.screenX[i];
                seg.y1 = pathData.screenY[i];
                seg.x2 = pathData.screenX[i + 1];
                seg.y2 = pathData.screenY[i + 1];
                seg.r1 = pathData.screenRadius[i];
                seg.r2 = pathData.screenRadius[i + 1];
                seg.color1 = pathData.colors[i];
                seg.color2 = pathData.colors[i + 1];

                // Batch by primary color
                final Color batchColor = seg.color1;
                batches.computeIfAbsent(batchColor, k -> new ArrayList<>()).add(seg);
            }
        }

        private void drawBatch(final Graphics2D g2d, final Color batchColor, final List<SegmentData> segments) {
            if (segments.isEmpty()) return;

            // Separate uniform-color segments from gradient segments
            final List<SegmentData> uniformSegments = new ArrayList<>();
            final List<SegmentData> gradientSegments = new ArrayList<>();

            for (final SegmentData seg : segments) {
                if (seg.color1.equals(seg.color2)) {
                    uniformSegments.add(seg);
                } else {
                    gradientSegments.add(seg);
                }
            }

            // Draw uniform segments as batched path
            if (!uniformSegments.isEmpty()) {
                batchPath.reset();
                for (final SegmentData seg : uniformSegments) {
                    addFrustumToPath(batchPath, seg);
                }
                g2d.setColor(batchColor);
                g2d.fill(batchPath);

                // === ADD NODE CAPS TO SMOOTH JOINTS ===
                for (final SegmentData seg : uniformSegments) {
                    // Draw circle at start node
                    final int d1 = (int) Math.round(2 * seg.r1);
                    if (d1 >= 1) {
                        g2d.fillOval((int) Math.round(seg.x1 - seg.r1),
                                (int) Math.round(seg.y1 - seg.r1), d1, d1);
                    }
                    // Draw circle at end node
                    final int d2 = (int) Math.round(2 * seg.r2);
                    if (d2 >= 1) {
                        g2d.fillOval((int) Math.round(seg.x2 - seg.r2),
                                (int) Math.round(seg.y2 - seg.r2), d2, d2);
                    }
                }
            }

            // Draw gradient segments individually
            for (final SegmentData seg : gradientSegments) {
                drawGradientFrustum(g2d, seg);
                // Add caps for gradient segments too
                g2d.setColor(seg.color1);
                final int d1 = (int) Math.round(2 * seg.r1);
                if (d1 >= 1) {
                    g2d.fillOval((int) Math.round(seg.x1 - seg.r1),
                            (int) Math.round(seg.y1 - seg.r1), d1, d1);
                }
                g2d.setColor(seg.color2);
                final int d2 = (int) Math.round(2 * seg.r2);
                if (d2 >= 1) {
                    g2d.fillOval((int) Math.round(seg.x2 - seg.r2),
                            (int) Math.round(seg.y2 - seg.r2), d2, d2);
                }
            }
        }

        private void addFrustumToPath(final Path2D.Double path, final SegmentData seg) {
            final double dx = seg.x2 - seg.x1;
            final double dy = seg.y2 - seg.y1;
            final double length = Math.sqrt(dx * dx + dy * dy);
            if (length < 0.1) return;

            final double perpX = -dy / length;
            final double perpY = dx / length;

            // Four corners of frustum
            final double x1a = seg.x1 + perpX * seg.r1;
            final double y1a = seg.y1 + perpY * seg.r1;
            final double x1b = seg.x1 - perpX * seg.r1;
            final double y1b = seg.y1 - perpY * seg.r1;
            final double x2a = seg.x2 + perpX * seg.r2;
            final double y2a = seg.y2 + perpY * seg.r2;
            final double x2b = seg.x2 - perpX * seg.r2;
            final double y2b = seg.y2 - perpY * seg.r2;

            path.moveTo(x1a, y1a);
            path.lineTo(x2a, y2a);
            path.lineTo(x2b, y2b);
            path.lineTo(x1b, y1b);
            path.closePath();
        }

        private void drawGradientFrustum(final Graphics2D g2d, final SegmentData seg) {
            batchPath.reset();
            addFrustumToPath(batchPath, seg);

            final GradientPaint gradient = new GradientPaint(
                    (float) seg.x1, (float) seg.y1, seg.color1,
                    (float) seg.x2, (float) seg.y2, seg.color2
            );

            final Paint original = g2d.getPaint();
            g2d.setPaint(gradient);
            g2d.fill(batchPath);
            g2d.setPaint(original);
        }

        private double getNodeRadius(final Path path, final int index) {
            double radius = 1.0;

            if (renderingOptions.isUsePathRadius()) {
                final double r = path.getNodeRadius(index);
                if (r > 0) radius = r;
            }

            radius *= renderingOptions.getThicknessMultiplier();
            radius = Math.max(renderingOptions.getMinThickness() / 2.0, radius);
            radius = Math.min(renderingOptions.getMaxThickness() / 2.0, radius);

            return radius;
        }

        private double getAverageScale() {
            final double scaleX = Math.sqrt(
                    viewerTransform.get(0, 0) * viewerTransform.get(0, 0) +
                            viewerTransform.get(1, 0) * viewerTransform.get(1, 0) +
                            viewerTransform.get(2, 0) * viewerTransform.get(2, 0)
            );
            final double scaleY = Math.sqrt(
                    viewerTransform.get(0, 1) * viewerTransform.get(0, 1) +
                            viewerTransform.get(1, 1) * viewerTransform.get(1, 1) +
                            viewerTransform.get(2, 1) * viewerTransform.get(2, 1)
            );
            return (scaleX + scaleY) / 2.0;
        }

        private boolean isOnScreen(final double x, final double y) {
            final double margin = 100.0;
            return x >= -margin && y >= -margin &&
                    x <= canvasWidth + margin && y <= canvasHeight + margin;
        }

        private boolean transformEquals(final AffineTransform3D a, final AffineTransform3D b) {
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 4; j++) {
                    if (Math.abs(a.get(i, j) - b.get(i, j)) > 1e-9) return false;
                }
            }
            return true;
        }

        private Graphics2D setupGraphics(final Graphics g) {
            final Graphics2D g2d = (Graphics2D) g.create();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            if (renderingOptions.getTransparency() < 1.0f) {
                g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,
                        renderingOptions.getTransparency()));
            }
            return g2d;
        }

        @Override
        public void setCanvasSize(final int width, final int height) {
            if (width != canvasWidth || height != canvasHeight) {
                invalidateCache();
            }
        }

        // === DATA CLASSES ===

        private static class TreeScreenData {
            final List<PathScreenData> paths = new ArrayList<>();
        }

        private static class PathScreenData {
            final int size;
            final double[] screenX;
            final double[] screenY;
            final double[] worldZ;
            final double[] screenRadius;
            final Color[] colors;
            final boolean[] visible;

            PathScreenData(int size) {
                this.size = size;
                this.screenX = new double[size];
                this.screenY = new double[size];
                this.worldZ = new double[size];
                this.screenRadius = new double[size];
                this.colors = new Color[size];
                this.visible = new boolean[size];
            }
        }

        private static class SegmentData {
            double x1, y1, x2, y2;
            double r1, r2;
            Color color1, color2;
        }
    }

    /** Actions for BVV GUI components. */
    private class BvvActions {
        private final BigVolumeViewer bvv;
        private final GuiUtils guiUtils;

        BvvActions(final BigVolumeViewer bvv) {
            this.bvv = bvv;
            this.guiUtils = new GuiUtils(bvv.getViewerFrame());
        }

        Action loadSettingsAction() {
            return new AbstractAction("Load Settings...", IconFactory.menuIcon(IconFactory.GLYPH.IMPORT)) {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    final File f = guiUtils.getFile(snt.getPrefs().getRecentDir(), "xml");
                    if (SNTUtils.fileAvailable(f)) {
                        try {
                            bvv.loadSettings(f.getAbsolutePath());
                            bvv.getViewer().showMessage(String.format("%s loaded", f.getName()));
                        } catch (final Exception ex) {
                            guiUtils.error(ex.getMessage());
                        }
                    }
                }
            };
        }

        Action saveSettingsAction() {
            return new AbstractAction("Save Settings...", IconFactory.menuIcon(IconFactory.GLYPH.EXPORT)) {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    final File f = guiUtils.getSaveFile("Save BVV Settings...", snt.getPrefs().getRecentDir(), "xml");
                    if (SNTUtils.fileAvailable(f)) {
                        try {
                            bvv.saveSettings(f.getAbsolutePath());
                            bvv.getViewer().showMessage(String.format("%s saved", f.getName()));
                        } catch (final Exception ex) {
                            guiUtils.error(ex.getMessage());
                        }
                    }
                }
            };
        }

        Action syncPathManagerAction() {
            return new AbstractAction("Sync Path Manager Changes", IconFactory.menuIcon(IconFactory.GLYPH.REDO)) {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    if (pathOverlay != null && syncPathManagerList()) {
                        bvv.getViewer().showMessage("Path Manager synced");
                    } else {
                        bvv.getViewer().showMessage("Error: No paths exist or SNT is unavailable");
                    }
                }
            };
        }

        Action clearAllPathsAction() {
            return new AbstractAction("Remove All Annotations...", IconFactory.menuIcon(IconFactory.GLYPH.TRASH)) {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    if (guiUtils.getConfirmation("Remove all reconstructions? (undoable action)",
                            "Remove All Annotations?")) {
                        clearAllTrees();
                        bvv.getViewer().showMessage("Annotations cleared");
                    }
                }
            };
        }

        Action importAction() {
            return new AbstractAction("Import Reconstructions...", IconFactory.menuIcon(IconFactory.GLYPH.IMPORT)) {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    final File[] files = guiUtils.getReconstructionFiles(snt.getPrefs().getRecentDir());
                    if (files == null || files.length == 0) return;
                    int failureCounter = 0;
                    final ColorRGB[] colors = SNTColor.getDistinctColors(files.length);
                    for (int i = 0; i < files.length; i++) {
                        try {
                            final Collection<Tree> trees = Tree.listFromFile(files[i].getAbsolutePath());
                            for (final Tree tree : trees) tree.setColor(colors[i]);
                            addCollection(trees, false);
                            bvv.getViewer().showMessage(String.format("%s loaded", files[i].getName()));
                        } catch (final Exception ex) {
                            bvv.getViewer().showMessage(String.format("%s failed", files[i].getName()));
                            failureCounter++;
                        }
                    }
                    syncOverlays();
                    if (failureCounter > 0) {
                        guiUtils.error(String.format("%d/%d file(s) successfully imported.", (files.length-failureCounter), files.length));
                    }
                }
            };
        }

        Action togggleVisibilityAction() {
            return new AbstractAction("Show/hide All Annotations") {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    if (pathOverlay == null || pathOverlay.sntBvv.getRenderedTrees().isEmpty()) {
                        bvv.getViewer().showMessage("No annotations exist.");
                        return;
                    }
                    if (e.getSource() instanceof AbstractButton toggleButton) {
                        pathOverlay.disableRendering(toggleButton.isSelected());
                        if (annotationOverlay != null) annotationOverlay.setVisible(toggleButton.isSelected());
                    } else {
                        pathOverlay.disableRendering(!pathOverlay.isRenderingEnable());
                        if (annotationOverlay != null) annotationOverlay.setVisible(!annotationOverlay.isVisible());
                    }
                    bvv.getViewer().showMessage((pathOverlay.isRenderingEnable()) ? "Annotations visible" : "Annotations hidden");
                }
            };
        }

        Action setTransparencyAction() {
            return new AbstractAction("Transparency...", IconFactory.buttonIcon(IconFactory.GLYPH.ADJUST, 1f)) {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    final Integer newValue = guiUtils.getPercentage("Annotations transparency (%):",
                            "Adjust Transparency",
                            (int) (100 - renderingOptions.getTransparency() * 100));
                    if (newValue == null) return;
                    renderingOptions.setTransparency(1 - (float) (newValue) / 100);
                    syncOverlays();
                    bvv.getViewer().showMessage(String.format("%d%% Transparency", newValue));
                }
            };
        }

        Action setCanvasOffsetAction() {
            return new AbstractAction("Annotations Offset...", IconFactory.buttonIcon(IconFactory.GLYPH.MOVE, 1f)) {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    final SNTPoint offset = guiUtils.getCoordinates("Offset (calibrated distances):", "Annotations Offset ",
                            renderingOptions.canvasOffset, 2);
                    if (offset == null) return;
                    if (offset.getX() == 0 && offset.getY() == 0 && offset.getZ() == 0) {
                        resetCanvasOffsetAction().actionPerformed(e);
                    } else {
                        setCanvasOffset(offset.getX(), offset.getY(), offset.getZ());
                        bvv.getViewer().showMessage("Offset applied");
                    }
                }
            };
        }

        Action setThicknessMultiplierAction() {
            return new AbstractAction("Thickness Multiplier", IconFactory.buttonIcon('\uf386', true)) {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    final Double multi = guiUtils.getDouble("Thickness multiplier (applied to radii of all annotations):",
                            "Thickness Multiplier", renderingOptions.getThicknessMultiplier());
                    if (multi == null)
                        return;
                    if (Double.isNaN(multi) || multi < 0.09f || multi > 100f) {
                        guiUtils.error("Invalid value: Multiplier must be better 0.1× and 100×.");
                    } else {
                        renderingOptions.setThicknessMultiplier(multi.floatValue());
                        bvv.getViewer().showMessage(
                                (1f==renderingOptions.getThicknessMultiplier())
                                ? "Thickness factor removed" : String.format("%.1f× Thickness", multi.floatValue()));
                    }
                }
            };
        }

        Action setDefaultColorAction() {
            return new AbstractAction("Default Annotation Color...", IconFactory.buttonIcon(IconFactory.GLYPH.COLOR, 1f)) {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    final Color c = guiUtils.getColor("Default Annotation Color", renderingOptions.fallbackColor, (String[]) null);
                    if (c != null && !c.equals(renderingOptions.fallbackColor)) {
                        // New color choice: refresh panel
                        renderingOptions.fallbackColor = c;
                        syncOverlays();
                    }
                }
            };
        }

        Action resetDefaultColorAction() {
            return new AbstractAction() {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    renderingOptions.fallbackColor = Color.MAGENTA;
                    syncOverlays();
                    bvv.getViewer().showMessage("Default color reset");
                }
            };
        }

        Action resetThicknessMultiplierAction() {
            return new AbstractAction() {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    renderingOptions.setThicknessMultiplier(1f);
                    syncOverlays();
                    bvv.getViewer().showMessage("Thickness factor removed");
                }
            };
        }

        Action resetCanvasOffsetAction() {
            return new AbstractAction() {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    setCanvasOffset(0, 0, 0);
                    bvv.getViewer().showMessage("Offset removed");
                }
            };
        }

        Action resetTransparencyAction() {
            return new AbstractAction() {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    renderingOptions.setTransparency(1);
                    syncOverlays();
                    bvv.getViewer().showMessage("Transparency reset");
                }
            };
        }

        Action togglePersistentAnnotationsAction() {
            return new AbstractAction("Toggle Annotations Around Cursor") {

                float lastInputDistance = 100f; // default. presumably 100um

                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {

                    if (e.getSource() instanceof AbstractButton toggleButton) {
                        if (renderingOptions.isClippingEnabled()) { // disable clippingDistance without prompt
                            lastInputDistance = renderingOptions.clippingDistance;
                            renderingOptions.clippingDistance = 0;
                        } else {
                            final Double newDist = guiUtils.getDouble(
                                    "<HTMl>Only annotations within this distance from cursor " +
                                            "(in spatially calibrated units)<br> will be displayed. " +
                                            "Set it to 0, or cancel this prompt to disable this option.",
                                    "Annotations Near Cursor",
                                    lastInputDistance);
                            lastInputDistance = (newDist == null || newDist < 0 || Double.isNaN(newDist))
                                    ? 0f : newDist.floatValue();
                            renderingOptions.clippingDistance = lastInputDistance;
                        }
                        toggleButton.setSelected(renderingOptions.isClippingEnabled());
                    }
                    bvv.getViewer().showMessage((renderingOptions.isClippingEnabled())
                            ? "Visibility: Around cursor" : "Visibility: All visible");
                }
            };
        }

        Action loadBookmarksAction() {
            return new AbstractAction("Annotate Bookmark Manager Locations ", IconFactory.menuIcon(IconFactory.GLYPH.BOOKMARK)) {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    try {
                        final List<SNTPoint> pos = snt.getUI().getBookmarkManager().getPositions(false);
                        if (pos.isEmpty()) {
                            guiUtils.error("Bookmark Manager is empty.");
                        } else {
                            final Color c = guiUtils.getColor("Bookmarks Color", Color.RED, (String[])null);
                            if (c == null) return;
                            annotations().setAnnotations(pos, (float) 3f * renderingOptions.getMinThickness(), c);
                            bvv.getViewer().showMessage(String.format("%d Bookmarks annotated", pos.size()));
                        }
                    } catch (NullPointerException ex) {
                        bvv.getViewer().showMessage("Bookmark Manager unavailable");
                    }
                }
            };
        }

        Action showHelpAction() {
            return new AbstractAction("Shortcuts...", IconFactory.menuIcon('\uf11c', true)) {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    final HelpDialog hDialog = new HelpDialog(bvv.getViewerFrame());
                    hDialog.setPreferredSize(bvv.getViewerFrame().getCardPanel().getComponent().getPreferredSize());
                    hDialog.setLocationRelativeTo(bvv.getViewerFrame());
                    SwingUtilities.invokeLater(() -> hDialog.setVisible(true));
                }
            };
        }
    }

}
