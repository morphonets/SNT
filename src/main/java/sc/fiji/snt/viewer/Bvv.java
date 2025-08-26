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
import bvv.core.BigVolumeViewer;
import bvv.core.VolumeViewerFrame;
import bvv.core.VolumeViewerPanel;
import bvv.vistools.*;
import ij.ImagePlus;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.RealType;
import sc.fiji.snt.Path;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.graph.DirectedWeightedGraph;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.IconFactory;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SNTColor;
import sc.fiji.snt.util.SNTPoint;

import javax.swing.*;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.File;
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
    private BigVolumeViewer currentBvv;

    /**
     * Constructor for standalone BVV instance.
     */
    public Bvv() {
        this(null); // standalone viewer
    }

    /**
     * Constructor for assembling a BVV instance tethered to SNT.
     *
     * @param snt the snt instance providing paths and imagery for rendering
     */
    public Bvv(final SNT snt) {
        this.snt = snt;
        this.renderedTrees = new HashMap<>();
        this.renderingOptions = new PathRenderingOptions();
        options = bvv.vistools.Bvv.options();
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

    public <T extends RealType<T>> BvvSource show(final RandomAccessibleInterval<T> img, final double... calibration) {
        final double[] cal = (calibration == null) ? new double[]{1, 1, 1} : calibration;
        final BvvSource source = BvvFunctions.show(img, "SNT Bvv", options.sourceTransform(cal));
        attachControlPanel(source);
        return source;
    }

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
        return source;
    }

    private void attachControlPanel(final BvvSource source) {
        final BigVolumeViewer bvv = ((BvvHandleFrame) source.getBvvHandle()).getBigVolumeViewer();
        if (currentBvv != bvv) { // Initialize overlay if not already done
            currentBvv = bvv;
            initializePathOverlay(currentBvv);
            pathOverlay.updatePaths();
        }
        final VolumeViewerFrame bvvFrame = bvv.getViewerFrame();
        bvvFrame.getCardPanel().addCard("Camera Controls", cameraPanel(bvvFrame), true);
        bvvFrame.getCardPanel().addCard("SNT Annotations", sntToolbar(bvv), true);
        SwingUtilities.invokeLater(bvv::expandAndFocusCardPanel);
    }

    /**
     * Initializes the path overlay system for drawing traced paths.
     */
    private void initializePathOverlay(final BigVolumeViewer bvv) {
        if (pathOverlay != null) {
            pathOverlay.dispose();
        }
        pathOverlay = new PathOverlay(bvv, this);
    }

    private JToolBar sntToolbar(final BigVolumeViewer bvv) {
        final BvvActions actions = new BvvActions(bvv);
        final JToolBar toolbar = new JToolBar();
        toolbar.add(GuiUtils.Buttons.toolbarToggleButton(actions.togggleVisibilityAction(),
                "Show/hide annotations", IconFactory.GLYPH.EYE, IconFactory.GLYPH.EYE_SLASH));
        toolbar.addSeparator();
        toolbar.add(Box.createHorizontalStrut(10));
        toolbar.add(GuiUtils.Buttons.toolbarButton(actions.setDefaultColorAction(),
                "Default annotation color"));
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
        toolbar.add(Box.createHorizontalStrut(optionsButton.getPreferredSize().width)); // otherwise occluded in card panel
        return toolbar;
    }

    private JButton optionsButton(final BvvActions actions) {
        final JPopupMenu menu = new JPopupMenu();
        final JButton oButton = new JButton(IconFactory.dropdownMenuIcon(IconFactory.GLYPH.OPTIONS));
        oButton.addActionListener(e -> menu.show(oButton, oButton.getWidth() / 2, oButton.getHeight() / 2));
        GuiUtils.addSeparator(menu, "Actions");
        menu.add(new JMenuItem(actions.clearAllPathsAction()));
        if (snt != null) {
            menu.add(new JMenuItem(actions.syncPathManagerAction()));
        }
        GuiUtils.addSeparator(menu, "Settings");
        menu.add(new JMenuItem(actions.loadSettingsAction()));
        menu.add(new JMenuItem(actions.saveSettingsAction()));
        GuiUtils.addSeparator(menu, "Help");
        menu.add(new JMenuItem(actions.showHelpAction()));
        return oButton;
    }

    private JPanel cameraPanel(final VolumeViewerFrame bvvFrame) {
        final CameraControls controls = new CameraControls(bvvFrame);
        return controls.createPanel();
    }


    /**
     * Adds a Tree to the BVV viewer as overlay paths.
     * Uses an overlay system similar to BigTrace to draw paths on top of volume data.
     *
     * @param tree the Tree to render
     */
    public void addTree(final Tree tree) {
        addTree(tree, true);
    }

    /**
     * Script friendly method to add a supported object ({@link Tree},
     * {@link DirectedWeightedGraph}) to this viewer.
     * collections of are also supported, which is an effective
     * way of adding multiple items since the scene is only updated once all items
     * have been added.
     *
     * @param object the object to be added. No exception is triggered if null
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
            case Collection<?> collection -> addCollection(collection);
            default -> throw new IllegalArgumentException("Unsupported object: " + object.getClass().getName());
        }
    }

    private void addCollection(final Collection<?> collection) {
        for (final Object o : collection)
            add(o, false);
        updateView();
    }

    private void addTree(final Tree tree, final boolean updateScene) {
        if (tree == null || tree.isEmpty()) {
            throw new IllegalArgumentException("Tree cannot be null or empty");
        }
        final String label = getUniqueLabel(tree);
        renderedTrees.put(label, tree);
        if (updateScene) updateView();
    }

    public void updateView() {
        if (pathOverlay != null) pathOverlay.updatePaths();
    }

    public VolumeViewerFrame getViewerFrame() {
        return (currentBvv == null) ? null : currentBvv.getViewerFrame();
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
            updateView();
            return true;
        }
        return false;
    }

    /**
     * Clears all rendered trees from the overlay.
     */
    public void clearAllTrees() {
        renderedTrees.clear();
        updateView();
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
     * Gets all currently rendered trees.
     *
     * @return collection of rendered trees
     */
    public Collection<Tree> getRenderedTrees() {
        return renderedTrees.values();
    }

    /**
     * Checks if any paths are currently being displayed.
     *
     * @return true if paths are being displayed
     */
    public boolean hasAnnotations() {
        return !renderedTrees.isEmpty();
    }

    public BvvOptions getOptions() {
        return options;
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
     * Sets a canvas offset on all currently rendered paths for testing offset functionality.
     * This allows visual verification that canvas offset translation is working correctly.
     *
     * @param offsetX X offset in pixels
     * @param offsetY Y offset in pixels
     * @param offsetZ Z offset in pixels
     */
    public void setCanvasOffset(final double offsetX, final double offsetY, final double offsetZ) {
        for (final Tree tree : renderedTrees.values()) {
            tree.applyCanvasOffset(offsetX, offsetY, offsetZ);
        }
        updateView();
        renderingOptions.canvasOffset = (offsetX == 0 && offsetY == 0d && offsetZ == 0d) ? null : SNTPoint.of(offsetX, offsetY, offsetZ);
    }

    // ---- methods for SNT Bvv instance

    @SuppressWarnings("UnusedReturnValue")
    public BvvSource showLoadedData() {
        return displayData(false);
    }

    @SuppressWarnings("UnusedReturnValue")
    public BvvSource showSecondaryData() {
        return displayData(true);
    }

    /**
     * Synchronizes the Path Manager contents with BVV display.
     * Similar to SciViewSNT's syncPathManagerList() method.
     *
     * @return true if synchronization was successful
     */
    public boolean syncPathManagerList() {
        if (snt == null)
            throw new IllegalArgumentException("This function is only available in snt-aware Bvv instances");
        if (snt.getPathAndFillManager().size() == 0)
            return false;
        final Collection<Tree> trees = snt.getPathAndFillManager().getTrees();
        final List<String> existingTreeLabels = trees.stream().map(Tree::getLabel).toList();
        existingTreeLabels.forEach(renderedTrees.keySet()::remove);
        addCollection(trees);
        updateView();
        return true;
    }

    /**
     * Camera controls for BVV viewer.
     * Encapsulates camera parameter management and UI creation.
     */
    private static class CameraControls {
        private static final double DEFAULT_CAM_DISTANCE = 2000.0;
        private static final double DEFAULT_CLIP_NEAR = 1000.0;
        private static final double DEFAULT_CLIP_FAR = 1000.0;

        private final VolumeViewerFrame bvvFrame;
        private final JSpinner dCamSpinner;
        private final JSpinner nearSpinner;
        private final JSpinner farSpinner;

        CameraControls(final VolumeViewerFrame bvvFrame) {
            this.bvvFrame = bvvFrame;
            this.dCamSpinner = GuiUtils.doubleSpinner(DEFAULT_CAM_DISTANCE, DEFAULT_CAM_DISTANCE / 5, DEFAULT_CAM_DISTANCE * 5, DEFAULT_CAM_DISTANCE / 4, 0);
            this.nearSpinner = GuiUtils.doubleSpinner(DEFAULT_CLIP_NEAR, DEFAULT_CLIP_NEAR / 5, DEFAULT_CLIP_NEAR * 5, DEFAULT_CLIP_NEAR / 4, 0);
            this.farSpinner = GuiUtils.doubleSpinner(DEFAULT_CLIP_FAR, DEFAULT_CLIP_FAR / 5, DEFAULT_CLIP_FAR * 5, DEFAULT_CLIP_FAR / 4, 0);
            setupSpinners();
        }

        private void setupSpinners() {
            final ChangeListener spinnerListener = e -> updateCameraParameters();
            dCamSpinner.addChangeListener(spinnerListener);
            nearSpinner.addChangeListener(spinnerListener);
            farSpinner.addChangeListener(spinnerListener);

            dCamSpinner.setToolTipText("Distance from camera to z=0 plane in physical units");
            nearSpinner.setToolTipText("Near clipping plane in physical units");
            farSpinner.setToolTipText("Distant clipping plane in physical units");
        }

        private void updateCameraParameters() {
            bvvFrame.getViewerPanel().setCamParams(
                    (double) dCamSpinner.getValue(),
                    (double) nearSpinner.getValue(),
                    (double) farSpinner.getValue()
            );
            bvvFrame.getViewerPanel().requestRepaint();
        }

        private Action createResetCameraDistanceAction() {
            return new AbstractAction() {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    dCamSpinner.setValue(DEFAULT_CAM_DISTANCE);
                    updateCameraParameters();
                }
            };
        }

        private Action createResetNearClipAction() {
            return new AbstractAction() {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    nearSpinner.setValue(DEFAULT_CLIP_NEAR);
                    updateCameraParameters();
                }
            };
        }

        private Action createResetFarClipAction() {
            return new AbstractAction() {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    farSpinner.setValue(DEFAULT_CLIP_FAR);
                    updateCameraParameters();
                }
            };
        }

        public JPanel createPanel() {
            final JButton dCamReset = GuiUtils.Buttons.undo(createResetCameraDistanceAction());
            final JButton nearReset = GuiUtils.Buttons.undo(createResetNearClipAction());
            final JButton farReset = GuiUtils.Buttons.undo(createResetFarClipAction());
            final JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, dCamSpinner.getFont().getSize() / 2));
            panel.add(new JLabel(IconFactory.buttonIcon('\uf1e5', true)));
            panel.add(dCamSpinner);
            panel.add(dCamReset);
            panel.add(new JLabel(IconFactory.buttonIcon('\ue4b8', true)));
            panel.add(nearSpinner);
            panel.add(nearReset);
            panel.add(new JLabel(IconFactory.buttonIcon('\ue4c2', true)));
            panel.add(farSpinner);
            panel.add(farReset);
            return panel;
        }
    }

    /**
     * Configuration options for path rendering in BVV.
     * Controls thickness, transparency, and other visual properties.
     */
    public static class PathRenderingOptions {
        private float thicknessMultiplier = 1.0f;
        private float transparency = 1.0f; // 1.0 = opaque, 0.0 = transparent
        private boolean usePathRadius = true;
        private float minThickness = 1.0f;
        private float maxThickness = 100.0f;
        private boolean antiAliasing = true;
        private SNTPoint canvasOffset;
        private Color fallbackColor = Color.MAGENTA;

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
         * Gets whether anti-aliasing is enabled.
         *
         * @return true if anti-aliasing is enabled
         */
        public boolean isAntiAliasing() {
            return antiAliasing;
        }

        /**
         * Sets whether to enable anti-aliasing for smoother path rendering.
         *
         * @param antiAliasing true to enable anti-aliasing
         */
        public void setAntiAliasing(boolean antiAliasing) {
            this.antiAliasing = antiAliasing;
        }
    }

    /**
     * Path overlay class for drawing traced paths on top of BVV volume data.
     * Inspired by BigTrace's overlay system.
     */
    private static class PathOverlay {
        private final Bvv sntBvv;
        private final VolumeViewerPanel viewerPanel;
        private final OverlayRenderer overlayRenderer;

         PathOverlay(final BigVolumeViewer bvv, final Bvv sntBvv) {
            this.sntBvv = sntBvv;
            this.viewerPanel = bvv.getViewer(); // Fix: use bvv parameter directly
            this.overlayRenderer = new OverlayRenderer(viewerPanel, sntBvv.getRenderingOptions());
            // Add the overlay renderer to the viewer
            viewerPanel.getDisplay().overlays().add(overlayRenderer);
            // Listen for viewer changes to trigger repaints
            viewerPanel.addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent e) {
                    viewerPanel.requestRepaint();
                }
            });
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
     * Custom overlay renderer for drawing SNT paths.
     */
    private static class OverlayRenderer implements bdv.viewer.OverlayRenderer {
        private final VolumeViewerPanel viewerPanel;
        private final PathRenderingOptions renderingOptions;
        private final AffineTransform3D viewerTransform = new AffineTransform3D();
        private final double[] worldCoords = new double[3];
        private final double[] viewerCoords = new double[3];
        private Collection<Tree> trees = new ArrayList<>();
        private double currentCameraDistance = 2000.0; // Default BVV camera distance
        private boolean hide;

        OverlayRenderer(final VolumeViewerPanel viewerPanel, final PathRenderingOptions renderingOptions) {
            this.viewerPanel = viewerPanel;
            this.renderingOptions = renderingOptions;
        }

        void updatePaths(final Collection<Tree> trees) {
            this.trees = new ArrayList<>(trees);
        }

        @Override
        public void drawOverlays(final Graphics g) {
            if (trees.isEmpty() || hide) return;

            final Graphics2D g2d = (Graphics2D) g.create();

            // Get viewer transform and camera information
            synchronized (viewerTransform) {
                viewerPanel.state().getViewerTransform(viewerTransform);
                currentCameraDistance = viewerPanel.getOptionValues().getDCam();
            }
            // Apply rendering options
            if (renderingOptions.isAntiAliasing()) {
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            } else {
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            }

            // Set global transparency if needed
            if (renderingOptions.getTransparency() < 1.0f) {
                g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, renderingOptions.getTransparency()));
            }

            // Draw each tree
            for (final Tree tree : trees) {
                drawTree(g2d, tree);
            }
            g2d.dispose();
        }

        private void drawTree(final Graphics2D g2d, final Tree tree) {
            for (final Path path : tree.list()) {
                drawPath(g2d, path);
            }
        }

        private void drawPath(final Graphics2D g2d, final Path path) {
            if (path.size() < 1) return;
            // Transform is updated in each BvvPathNode constructor to ensure zoom changes are captured
            drawPathUsingExistingLogic(g2d, path);
        }

        /**
         * Draws a path using logic similar to Path.drawPathAsPoints() but adapted for BVV 3D-to-2D projection.
         * Supports node colors and color interpolation between nodes.
         */
        private void drawPathUsingExistingLogic(final Graphics2D g2d, final Path path) {

            // Set default path color
            Color defaultPathColor = path.getColor();
            if (defaultPathColor == null) {
                defaultPathColor = renderingOptions.fallbackColor;
            }

            // Check if path has node colors
            final boolean hasNodeColors = path.hasNodeColors();

            // Draw nodes and connections with per-node radius support
            BvvPathNode previousNode = null;
            Color previousNodeColor = null;

            for (int i = 0; i < path.size(); i++) {
                final BvvPathNode currentNode = new BvvPathNode(path, i);
                // Determine current node color
                Color currentNodeColor = defaultPathColor;
                if (hasNodeColors) {
                    try {
                        final Color pathNodeColor = path.getNodeColor(i);
                        if (pathNodeColor != null) {
                            currentNodeColor = pathNodeColor;
                        }
                    } catch (final Exception e) {
                        // Fallback to default color if node color access fails
                        SNTUtils.error("Warning: Could not get node color for index " + i, e);
                    }
                }

                // Draw the node as a sphere if visible
                if (currentNode.isVisible()) {
                    currentNode.drawNodeSphere(g2d, currentNodeColor);
                }

                // Draw connection to previous node as frustum-like segment
                if (previousNode != null && previousNode.isVisible() && currentNode.isVisible()) {
                    if (hasNodeColors && previousNodeColor != null && currentNodeColor != null) {
                        // Draw frustum segment with color interpolation
                        currentNode.drawFrustumSegment(g2d, previousNode, previousNodeColor, currentNodeColor);
                    } else {
                        // Draw frustum segment with single color
                        currentNode.drawFrustumSegment(g2d, previousNode, currentNodeColor, currentNodeColor);
                    }
                }

                previousNode = currentNode;
                previousNodeColor = currentNodeColor;
            }
        }

        private boolean isPointVisible(final double[] screenCoords) {
            // Get canvas dimensions for proper visibility check
            final int canvasWidth = viewerPanel.getDisplay().getWidth();
            final int canvasHeight = viewerPanel.getDisplay().getHeight();

            // Check if point is within canvas bounds with some margin for large nodes
            final double margin = 100.0; // Allow margin for nodes that extend beyond canvas
            return screenCoords[0] >= -margin && screenCoords[0] <= canvasWidth + margin &&
                    screenCoords[1] >= -margin && screenCoords[1] <= canvasHeight + margin;
        }

        @Override
        public void setCanvasSize(final int width, final int height) {
            // Handle canvas size changes if needed
        }

        /**
         * BVV-specific path node that handles coordinate transformation.
         * Tests both manual transformation and direct world coordinates.
         */
        private class BvvPathNode {
            private final double x, y;
            private final boolean visible;
            private final double screenRadius;

            BvvPathNode(final Path path, final int index) {

                final PointInImage point = path.getNode(index);

                // Apply canvas offset translation if present
                // Canvas offset allows paths to be displayed with a visual offset without changing their actual coordinates
                final sc.fiji.snt.util.PointInCanvas canvasOffset = path.getCanvasOffset();
                double offsetX = 0, offsetY = 0, offsetZ = 0;
                if (canvasOffset != null) {
                    offsetX = canvasOffset.x;
                    offsetY = canvasOffset.y;
                    offsetZ = canvasOffset.z;
                }

                // Store world coordinates with canvas offset applied
                double worldX = point.x + offsetX;
                double worldY = point.y + offsetY;
                double worldZ = point.z + offsetZ;

                // Apply viewer transformation (rotation, translation, camera position)
                worldCoords[0] = worldX;
                worldCoords[1] = worldY;
                worldCoords[2] = worldZ;

                viewerTransform.apply(worldCoords, viewerCoords);

                // Get canvas dimensions for proper centering
                final int canvasWidth = viewerPanel.getDisplay().getWidth();
                final int canvasHeight = viewerPanel.getDisplay().getHeight();
                final double centerX = canvasWidth / 2.0;
                final double centerY = canvasHeight / 2.0;

                // Apply perspective projection centered around canvas center
                // BVV uses perspective projection: screen = center + (viewer - center) * (cameraDistance / (cameraDistance + viewerZ))
                final double perspectiveFactor = currentCameraDistance / (currentCameraDistance + viewerCoords[2]);

                this.x = centerX + (viewerCoords[0] - centerX) * perspectiveFactor;
                this.y = centerY + (viewerCoords[1] - centerY) * perspectiveFactor;
                this.visible = isPointVisible(new double[]{this.x, this.y, viewerCoords[2]});

                // Debug output (uncomment for debugging)
                // if (index < 2) {
                //     System.out.printf("  Centered perspective - viewer(%.1f,%.1f,%.1f) center(%.1f,%.1f) * %.3f -> screen(%.1f,%.1f) canvas=%dx%d\n",
                //         viewerCoords[0], viewerCoords[1], viewerCoords[2], centerX, centerY, perspectiveFactor, this.x, this.y, canvasWidth, canvasHeight);
                // }

                // Get node radius
                double radius = getNodeRadius(path, index);
                this.screenRadius = calculateScreenRadius(radius);
            }

            /**
             * Gets the radius for a specific node, considering rendering options.
             */
            double getNodeRadius(final Path path, final int index) {
                double nodeRadius = 1.0; // Default radius

                if (renderingOptions.isUsePathRadius()) {
                    try {
                        // Access the PathNode directly from the path's nodes list
                        final List<PointInImage> nodes = path.getNodes();
                        if (index < nodes.size()) {
                            final Path.PathNode pathNode = (Path.PathNode) nodes.get(index);
                            final double radius = pathNode.getRadius();
                            if (radius > 0) {
                                nodeRadius = radius;
                            }
                        }
                    } catch (Exception e) {
                        // Fallback to path mean radius
                        try {
                            final double meanRadius = path.getMeanRadius();
                            if (meanRadius > 0) {
                                nodeRadius = meanRadius;
                            }
                        } catch (Exception ex) {
                            // Use default radius if all access fails
                        }
                    }
                }

                // Apply thickness multiplier and clamp
                nodeRadius *= renderingOptions.getThicknessMultiplier();
                nodeRadius = Math.max(renderingOptions.getMinThickness() / 2.0, nodeRadius);
                nodeRadius = Math.min(renderingOptions.getMaxThickness() / 2.0, nodeRadius);

                return nodeRadius;
            }

            /**
             * Converts world radius to screen radius using perspective projection.
             * Uses the same perspective factor that's applied to coordinates.
             */
            double calculateScreenRadius(final double worldRadius) {
                try {
                    // Extract the scale from the transform matrix
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

                    // Use average of X and Y scales
                    final double avgScale = (scaleX + scaleY) / 2.0;

                    // Apply perspective projection (same as coordinates)
                    final double perspectiveFactor = currentCameraDistance / (currentCameraDistance + viewerCoords[2]);

                    // Apply both transform scale and perspective to world radius
                    final double screenRadius = worldRadius * avgScale * perspectiveFactor;

                    return Math.max(0.5, screenRadius);

                } catch (Exception e) {
                    // Fallback to simple scaling
                    return Math.max(1.0, worldRadius * 2.0);
                }
            }

            boolean isVisible() {
                return visible;
            }

            /**
             * Draws the node as a sphere (circle in 2D) with appropriate radius.
             */
            void drawNodeSphere(final Graphics2D g2d, final Color color) {
                if (!visible) return;

                g2d.setColor(color);
                final int diameter = (int) (screenRadius * 2);
                g2d.fillOval(
                        (int) (x - screenRadius),
                        (int) (y - screenRadius),
                        diameter,
                        diameter
                );
            }

            /**
             * Draws a frustum-like segment between two nodes with different radii.
             * Creates a tapered connection that respects the radius of each node.
             */
            void drawFrustumSegment(final Graphics2D g2d, final BvvPathNode other,
                                    final Color fromColor, final Color toColor) {
                if (!visible || !other.visible) return;

                // Calculate perpendicular vectors for the frustum edges
                final double dx = x - other.x;
                final double dy = y - other.y;
                final double length = Math.sqrt(dx * dx + dy * dy);

                if (length < 0.1) return; // Skip very short segments

                // Normalized perpendicular vector
                final double perpX = -dy / length;
                final double perpY = dx / length;

                // Calculate the four corners of the frustum
                final Polygon frustum = getFrustum(other, perpX, perpY);

                // Fill the frustum with gradient if colors are different
                if (!fromColor.equals(toColor)) {
                    final GradientPaint gradient = new GradientPaint(
                            (float) other.x, (float) other.y, fromColor,
                            (float) x, (float) y, toColor
                    );

                    final Paint originalPaint = g2d.getPaint();
                    g2d.setPaint(gradient);
                    g2d.fill(frustum);
                    g2d.setPaint(originalPaint);
                } else {
                    g2d.setColor(fromColor);
                    g2d.fill(frustum);
                }
            }

            Polygon getFrustum(BvvPathNode other, double perpX, double perpY) {
                final double x1a = other.x + perpX * other.screenRadius;
                final double y1a = other.y + perpY * other.screenRadius;
                final double x1b = other.x - perpX * other.screenRadius;
                final double y1b = other.y - perpY * other.screenRadius;

                final double x2a = x + perpX * screenRadius;
                final double y2a = y + perpY * screenRadius;
                final double x2b = x - perpX * screenRadius;
                final double y2b = y - perpY * screenRadius;

                // Create the frustum shape
                final Polygon frustum = new Polygon();
                frustum.addPoint((int) x1a, (int) y1a);
                frustum.addPoint((int) x2a, (int) y2a);
                frustum.addPoint((int) x2b, (int) y2b);
                frustum.addPoint((int) x1b, (int) y1b);
                return frustum;
            }
        }
    }

    /**
     * Actions for BVV GUI components.
     * Separates business logic from GUI component creation.
     */
    private class BvvActions {
        private final BigVolumeViewer bvv;
        private final GuiUtils guiUtils;

        public BvvActions(final BigVolumeViewer bvv) {
            this.bvv = bvv;
            this.guiUtils = new GuiUtils(bvv.getViewerFrame());
        }

        public Action loadSettingsAction() {
            return new AbstractAction("Load...", IconFactory.menuIcon(IconFactory.GLYPH.IMPORT)) {
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

        public Action saveSettingsAction() {
            return new AbstractAction("Save...", IconFactory.menuIcon(IconFactory.GLYPH.EXPORT)) {
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

        public Action syncPathManagerAction() {
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

        public Action clearAllPathsAction() {
            return new AbstractAction("Remove All Annotations", IconFactory.menuIcon(IconFactory.GLYPH.TRASH)) {
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

        public Action togggleVisibilityAction() {
            return new AbstractAction("Show/hide All Paths", null) {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    if (!syncPathManagerList() || pathOverlay == null) {
                        bvv.getViewer().showMessage("Error: No paths exist or SNT is unavailable");
                    } else if (e.getSource() instanceof AbstractButton toggleButton) {
                        pathOverlay.disableRendering(toggleButton.isSelected());
                        bvv.getViewer().showMessage((pathOverlay.isRenderingEnable()) ? "Annotations visible" : "Annotations hidden");
                    }
                }
            };
        }

        public Action setTransparencyAction() {
            return new AbstractAction("Transparency...", IconFactory.buttonIcon(IconFactory.GLYPH.ADJUST, 1f)) {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    final Integer newValue = guiUtils.getPercentage("Annotations transparency (%):",
                            "Adjust Transparency",
                            (int) (100 - renderingOptions.getTransparency() * 100));
                    if (newValue == null) return;
                    renderingOptions.setTransparency(1 - (float) (newValue) / 100);
                    updateView();
                }
            };
        }

        public Action setCanvasOffsetAction() {
            return new AbstractAction("Annotations Offset...", IconFactory.buttonIcon(IconFactory.GLYPH.MOVE, 1f)) {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    final SNTPoint offset = guiUtils.getCoordinates("Offset (pixels):", "Annotations Offset",
                            renderingOptions.canvasOffset, 0);
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

        public Action setThicknessMultiplierAction() {
            return new AbstractAction("Thickness Multiplier", IconFactory.buttonIcon('\uf386', true)) {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    final Double multi = guiUtils.getDouble("Thickness multiplier (applied to radii of all annotations):",
                            "Thickness Multiplier", renderingOptions.thicknessMultiplier);
                    if (multi == null)
                        return;
                    if (Double.isNaN(multi) || multi < renderingOptions.minThickness || multi > renderingOptions.maxThickness) {
                        guiUtils.error("Invalid multiplier.");
                    } else {
                        renderingOptions.setThicknessMultiplier(multi.floatValue());
                        bvv.getViewer().showMessage(String.format("Thickness factor: %.1f×", multi.floatValue()));
                    }
                }
            };
        }

        public Action setDefaultColorAction() {
            return new AbstractAction("Default Annotation Color...", IconFactory.buttonIcon(IconFactory.GLYPH.COLOR, 1f)) {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    final Color c = guiUtils.getColor("Default Annotation Color", renderingOptions.fallbackColor, (String[])null);
                    if (c != null && !c.equals(renderingOptions.fallbackColor)) {
                        // New color choice: refresh panel
                        renderingOptions.fallbackColor = c;
                        updateView();
                    }
                }
            };
        }

        Action resetDefaultColorAction() {
            return new AbstractAction() {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    renderingOptions.fallbackColor = Color.MAGENTA;
                    updateView();
                    bvv.getViewer().showMessage("Default color reset");
                }
            };
        }

        Action resetThicknessMultiplierAction() {
            return new AbstractAction() {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    renderingOptions.setThicknessMultiplier(1f);
                    updateView();
                    bvv.getViewer().showMessage("Thickness factor: 1×");
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
                    updateView();
                    bvv.getViewer().showMessage("Transparency reset");
                }
            };
        }

        public Action showHelpAction() {
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
