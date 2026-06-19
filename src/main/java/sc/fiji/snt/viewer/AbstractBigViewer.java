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

import bdv.util.Prefs;
import bdv.viewer.SourceAndConverter;
import mpicbg.spim.data.generic.AbstractSpimData;
import net.imglib2.RealPoint;
import net.imglib2.realtransform.AffineTransform3D;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTUtils;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.IconFactory;
import sc.fiji.snt.util.SNTColor;
import sc.fiji.snt.util.SNTPoint;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.graph.DirectedWeightedGraph;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.util.*;
import java.util.List;

/**
 * Abstract base for SNT's BigDataViewer-family viewers ({@link Bvv}, {@link Bdv}, etc.).
 *
 * <p>Provides shared infrastructure for tree/path management, calibration, and bookmark
 * support, leaving viewer-specific rendering, source loading, and camera control to
 * concrete subclasses.
 *
 * <p>The {@link AnnotationOverlay} interface defined here is the common contract that
 * all viewer overlays must satisfy so that {@code BookmarkManager} can drive them
 * without knowing the concrete viewer type.
 *
 * @author Tiago Ferreira
 * @see Bvv
 * @see Bdv
 */
public abstract class AbstractBigViewer {

    static { net.imagej.patcher.LegacyInjector.preinit(); }

    // ---- Shared state -------------------------------------------------------

    /** Most recently instantiated viewer; scripting convenience. */
    protected static volatile AbstractBigViewer lastInstance;

    /** The SNT instance this viewer is tethered to (null in standalone mode). */
    protected final SNT snt;

    /**
     * Trees currently rendered in this viewer, keyed by unique display label.
     * Insertion order is preserved so the first-added tree stays first.
     */
    protected final Map<String, Tree> renderedTrees = new LinkedHashMap<>();

    /** Maps SpimData sources back to the file that produced them. */
    protected final Map<AbstractSpimData<?>, String> spimDataFilePaths = new IdentityHashMap<>();

    /** Voxel sizes [x, y, z] for the primary loaded volume, in {@link #calUnit} units. */
    protected double[] cal;

    /** Pixel dimensions [x, y, z] of the primary loaded volume. */
    protected long[] dims;

    /** Physical unit for calibration values (e.g., "um", "pixel"). */
    protected String calUnit;

    /** Lazily initialized bookmark/marker manager panel. */
    protected sc.fiji.snt.BookmarkManager markerManager;

    // ---- Construction -------------------------------------------------------

    protected AbstractBigViewer() {
        this(null);
    }

    protected AbstractBigViewer(final SNT snt) {
        this.snt = snt;
        lastInstance = this;
    }

    /**
     * Creates a {@link JToolBar} whose minimum width is zero, allowing
     * horizontal glue components to absorb all available shrinkage before
     * any buttons are clipped at the panel edge.
     */
    static JToolBar createToolbar() {
        return new JToolBar() {
            @Override
            public Dimension getMinimumSize() {
                return new Dimension(0, super.getPreferredSize().height);
            }
        };
    }

    void resizeCardPanelsAsNeeded(final JComponent refPanel) {
        // Ensure the card panel is wide enough to show all controls without clipping.
        // Use the Scene Controls panel's own preferred width since it's the widest,
        // and its GridBagLayout has already computed the correct natural width.
        final int cardPrefW = refPanel.getMinimumSize().width + 16; // minor padding
        SwingUtilities.invokeLater(() -> {
            final javax.swing.JSplitPane split = getViewerSplitPanel();
            if (split == null) return;
            final java.awt.Component cards = split.getRightComponent();
            if (cards == null) return;
            cards.setPreferredSize(new java.awt.Dimension(cardPrefW, cards.getPreferredSize().height));
            final JFrame frame = getViewerFrame();
            final int frameW = frame != null ? frame.getWidth() : 0;
            if (frameW > cardPrefW)
                split.setDividerLocation(frameW - cardPrefW - split.getDividerSize());
            if (frame != null) frame.revalidate();
        });
    }

    /**
     * Returns the top-level Swing window for this viewer, or null if not yet open.
     */
    public abstract JFrame getViewerFrame();

    /**
     * Returns the JSplitPane that separates the viewer canvas from the card panel.
     * Both BDV and BVV frames expose this via their own getSplitPanel() methods,
     * but those classes share no common supertype above JFrame, so this method
     * lets subclasses expose the split pane without the abstract method returning
     * a viewer-specific frame type.
     */
    protected abstract javax.swing.JSplitPane getViewerSplitPanel();

    /**
     * Returns the width of the viewer canvas in logical pixels,
     * or 0 if the viewer is not yet initialized.
     */
    public abstract int getViewerWidth();

    /**
     * Returns the height of the viewer canvas in logical pixels,
     * or 0 if the viewer is not yet initialized.
     */
    public abstract int getViewerHeight();

    /**
     * Returns a snapshot of the current viewer-to-screen (world-to-screen) transform.
     * The returned object is a copy; callers may modify it freely.
     */
    public abstract AffineTransform3D getViewerTransform();

    /**
     * Animates the viewer transform to {@code target} over {@code durationMs} milliseconds.
     * Use {@code durationMs = 0} for an immediate jump.
     *
     * @param target     the desired world-to-screen transform
     * @param durationMs animation duration in milliseconds (0 = immediate)
     */
    public abstract void setViewerTransform(AffineTransform3D target, long durationMs);

    /**
     * Displays a short status message in the viewer's overlay area.
     *
     * @param msg the message to show
     */
    public abstract void showViewerMessage(String msg);

    /** Resets the view to frame all loaded data. */
    public abstract void resetView();

    /** Returns true if the viewer window is currently visible and usable. */
    public abstract boolean isOpen();

    /** Requests a repaint of the viewer canvas. */
    public abstract void repaint();

    /**
     * Synchronizes all active rendering overlays (paths, markers) with the current
     * state of {@link #renderedTrees} and any pending annotation changes.
     */
    public abstract void syncOverlays();

    /**
     * Returns the annotation overlay for this viewer.
     * The overlay renders point markers in the viewer's world coordinate space.
     * May return null if the viewer has not been opened yet.
     */
    public abstract AnnotationOverlay annotations();

    /**
     * Returns the default sphere radius (in physical units) for newly placed markers.
     * Implementations typically derive this from their rendering-options or a sensible default.
     */
    public abstract float getDefaultMarkerSize();

    /**
     * Returns the default color for newly placed markers, or null to use the viewer's
     * own fallback color.
     */
    public abstract Color getDefaultMarkerColor();

    /**
     * Creates and returns a new {@link sc.fiji.snt.BookmarkManager} for this viewer.
     * Called exactly once (lazily) by {@link #getMarkerManager()}.
     */
    protected abstract sc.fiji.snt.BookmarkManager createMarkerManager();

    /**
     * Writes the current global (world-space) mouse position into {@code pos}.
     * Callers must supply a pre-allocated {@link RealPoint} with at least 3 dimensions.
     *
     * @param pos 3D point to receive the world-space cursor position
     */
    public abstract void getGlobalMouseCoordinates(RealPoint pos);


    /**
     * Creates an {@link Action} that calls {@code onToggle} with the toggle button's
     * selected state whenever triggered. Useful for wiring toolbar toggle buttons to
     * viewer overlay state (scale bar, text overlay, etc.).
     *
     * @param name         action name (used for accessibility)
     * @param initialState initial selected state (returned when source is not a button)
     * @param onToggle     consumer called with the new boolean state on each action event
     * @return the constructed action
     */
    protected static Action overlayToggleAction(final String name, final boolean initialState,
                                                final java.util.function.Consumer<Boolean> onToggle) {
        return new AbstractAction(name) {
            @Override
            public void actionPerformed(final ActionEvent e) {
                final boolean selected = (e.getSource() instanceof AbstractButton btn)
                        ? btn.isSelected() : !initialState;
                onToggle.accept(selected);
            }
        };
    }

    /**
     * Returns the currently active source, or null if none.
     */
    protected abstract SourceAndConverter<?> getCurrentSource();

    /**
     * Adds a mouse listener to the viewer's canvas component so that
     * click events on the display surface can be handled (e.g. for hit testing
     * annotation markers).
     *
     * @param ml the listener to add
     */
    public abstract void addMouseListenerToDisplay(java.awt.event.MouseListener ml);

    /**
     * Looks up a named action from the viewer's keybindings action map.
     * Returns null if the action is not registered or the viewer is not ready.
     *
     * @param name the action key (e.g., "align XY plane")
     */
    protected abstract Action getViewerAction(String name);

    /**
     * Registers an M-key binding on {@code component} that places a marker at the
     * current cursor position whenever the viewer is focused.
     *
     * <p>The action is stored under the key {@code "snt-add-marker"} in the component's
     * action map so it participates in the standard Swing keybinding chain.
     *
     * @param component the component to register the binding on (typically the viewer panel)
     */
    protected final void registerMarkerKeyBinding(final JComponent component) {
        final InputMap  im = component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        final ActionMap am = component.getActionMap();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_M, 0), "snt-add-marker");
        am.put("snt-add-marker", new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent e) {
                final RealPoint pos = new RealPoint(3);
                getGlobalMouseCoordinates(pos);
                final double x = pos.getDoublePosition(0);
                final double y = pos.getDoublePosition(1);
                final double z = pos.getDoublePosition(2);
                getMarkerManager().add(x, y, z);
                showViewerMessage(String.format("Marker placed at (%.1f, %.1f, %.1f)", x, y, z));
            }
        });
    }

    /**
     * Adds a Tree to the viewer overlay, assigning it a unique display label.
     *
     * @param tree the Tree to render; must not be null or empty
     */
    public void addTree(final Tree tree) {
        addTree(tree, true);
    }

    /**
     * Internal add with optional immediate overlay sync.
     * Subclasses may override if they need to track per-tree state beyond the shared map.
     */
    protected void addTree(final Tree tree, final boolean syncNow) {
        if (tree == null || tree.isEmpty())
            throw new IllegalArgumentException("Tree cannot be null or empty");
        final String label = getUniqueLabel(tree);
        renderedTrees.put(label, tree);
        if (syncNow) syncOverlays();
    }

    /**
     * Script-friendly dispatcher: accepts a {@link Tree}, {@link DirectedWeightedGraph},
     * {@code File[]}, or any {@code Collection} of supported objects.
     *
     * @param o the object to add
     * @throws IllegalArgumentException if the type is not supported
     */
    public void add(final Object o) {
        add(o, true);
    }

    /**
     * Internal dispatcher with deferred sync support for batch operations.
     */
    protected void add(final Object o, final boolean syncNow) {
        switch (o) {
            case Tree t -> addTree(t, syncNow);
            case DirectedWeightedGraph g -> addTree(g.getTree(), syncNow);
            case Collection<?> c -> addCollection(c, syncNow);
            case null, default -> {
                assert o != null;
                throw new IllegalArgumentException("Unsupported type: " + o.getClass().getSimpleName());
            }
        }
    }

    /** Adds all elements of a collection, optionally syncing once at the end. */
    protected void addCollection(final Collection<?> collection, final boolean syncNow) {
        for (final Object o : collection)
            add(o, false);
        if (syncNow) syncOverlays();
    }

    /**
     * Loads reconstruction files (SWC, JSON, TRACES) and adds them to the viewer.
     * Trees are colored with distinct colors and the overlay is synced once at the end.
     * Subclasses may override for async loading with progress feedback (see Bvv).
     *
     * @param reconstructionFiles the files to load; null or empty is silently ignored
     */
    public void add(final File[] reconstructionFiles) {
        if (reconstructionFiles == null || reconstructionFiles.length == 0) return;
        final org.scijava.util.ColorRGB[] colors = SNTColor.getDistinctColors(reconstructionFiles.length);
        for (int i = 0; i < reconstructionFiles.length; i++) {
            try {
                final Collection<Tree> trees = Tree.listFromFile(reconstructionFiles[i].getAbsolutePath());
                if (trees == null) continue;
                final int idx = i;
                trees.forEach(t -> t.setColor(colors[idx]));
                addCollection(trees, false);
            } catch (final Exception ex) {
                SNTUtils.log("Could not load " + reconstructionFiles[i].getName() + ": " + ex.getMessage());
            }
        }
        syncOverlays();
    }

    /**
     * Removes the tree with the given label from the overlay.
     *
     * @param treeLabel the display label of the tree to remove
     * @return true if a tree with that label existed and was removed
     */
    public boolean removeTree(final String treeLabel) {
        final boolean existed = renderedTrees.remove(treeLabel) != null;
        if (existed) syncOverlays();
        return existed;
    }

    /** Removes all rendered trees from the overlay. */
    public void clearAllTrees() {
        renderedTrees.clear();
        syncOverlays();
    }

    /**
     * Returns an unmodifiable view of the currently rendered trees.
     *
     * @return collection of rendered trees (insertion order)
     */
    public Collection<Tree> getRenderedTrees() {
        return Collections.unmodifiableCollection(renderedTrees.values());
    }

    /**
     * Returns the marker manager panel, creating it lazily on first call via
     * {@link #createMarkerManager()}.
     *
     * @return the marker manager for this viewer
     */
    public sc.fiji.snt.BookmarkManager getMarkerManager() {
        if (markerManager == null)
            markerManager = createMarkerManager();
        return markerManager;
    }


    /**
     * Sets the voxel calibration for the viewer.
     *
     * @param spacing voxel sizes [x, y, z]
     * @param unit    physical unit string (e.g., "um")
     */
    public void setCalibration(final double[] spacing, final String unit) {
        this.cal = spacing;
        this.calUnit = unit;
    }

    /** Returns the current voxel sizes, or null if not set. */
    public double[] getCalibration() {
        return cal;
    }

    /** Returns the physical unit string, or null if not set. */
    public String getCalUnit() {
        return calUnit;
    }

    /**
     * Derives the best available physical unit string.
     * Subclasses may override to add viewer-specific fallbacks
     * (e.g., reading units from source VoxelDimensions).
     */
    protected String getPhysicalUnit() {
        if (calUnit != null && !calUnit.isBlank() && !"pixel".equalsIgnoreCase(calUnit))
            return calUnit;
        if (snt != null) {
            final String u = snt.getSpacingUnits();
            if (u != null && !u.isBlank() && !"pixel".equalsIgnoreCase(u))
                return sc.fiji.snt.util.BoundingBox.sanitizedUnit(u);
        }
        return "px";
    }

    /**
     * Returns a display label for the tree that is unique within {@link #renderedTrees}.
     * Derived from the tree's own label, appending "(2)", "(3)" etc. as needed.
     */
    protected String getUniqueLabel(final Tree tree) {
        String base = tree.getLabel();
        if (base == null || base.isBlank()) base = "Tree";
        if (!renderedTrees.containsKey(base)) return base;
        int n = 2;
        while (renderedTrees.containsKey(base + " (" + n + ")")) n++;
        return base + " (" + n + ")";
    }

    /**
     * Creates an action that fits the view to the bounding box of the currently
     * selected source, with a short animation.
     */
    protected Action fitToCurrentSourceAction() {
        return new AbstractAction("Fit Source", IconFactory.menuIcon(IconFactory.GLYPH.EXPAND)) {
            @Override
            public void actionPerformed(final ActionEvent e) {
                final SourceAndConverter<?> src = getCurrentSource();
                if (src == null) { showViewerMessage("No source selected"); return; }
                final int cw = getViewerWidth(), ch = getViewerHeight();
                if (cw <= 0 || ch <= 0) return;
                final AffineTransform3D srcToWorld = new AffineTransform3D();
                src.getSpimSource().getSourceTransform(0, 0, srcToWorld);
                final net.imglib2.RandomAccessibleInterval<?> rai = src.getSpimSource().getSource(0, 0);
                if (rai == null) return;
                final long[] min = new long[3], max = new long[3];
                for (int d = 0; d < 3; d++) { min[d] = rai.min(d); max[d] = rai.max(d); }
                double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
                double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
                double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
                final double[] corner = new double[3], world = new double[3];
                for (int i = 0; i < 8; i++) {
                    corner[0] = (i & 1) == 0 ? min[0] : max[0];
                    corner[1] = (i & 2) == 0 ? min[1] : max[1];
                    corner[2] = (i & 4) == 0 ? min[2] : max[2];
                    srcToWorld.apply(corner, world);
                    minX = Math.min(minX, world[0]); maxX = Math.max(maxX, world[0]);
                    minY = Math.min(minY, world[1]); maxY = Math.max(maxY, world[1]);
                    minZ = Math.min(minZ, world[2]); maxZ = Math.max(maxZ, world[2]);
                }
                final double physW = maxX - minX, physH = maxY - minY, physZ = maxZ - minZ;
                if (physW <= 0 || physH <= 0) return;
                final double scale = Math.min(cw / physW, ch / physH);
                final AffineTransform3D target = new AffineTransform3D();
                target.set(scale, 0, 0); target.set(scale, 1, 1); target.set(scale, 2, 2);
                target.set(cw / 2.0 - scale * (minX + physW / 2.0), 0, 3);
                target.set(ch / 2.0 - scale * (minY + physH / 2.0), 1, 3);
                target.set(-scale * (minZ + physZ / 2.0), 2, 3);
                setViewerTransform(target, 300);
            }
        };
    }

    /**
     * Prompts the user for voxel spacing and updates calibration.
     *
     * @param parent component used to anchor the dialog
     */
    protected void showCalibrationDialog(final java.awt.Component parent) {
        final double[] curCal = getCalibration();
        final Number[] defaults = {
                curCal != null && curCal.length > 0 ? curCal[0] : 1.0,
                curCal != null && curCal.length > 1 ? curCal[1] : 1.0,
                curCal != null && curCal.length > 2 ? curCal[2] : 1.0
        };
        final GuiUtils gu = new GuiUtils(SwingUtilities.getWindowAncestor(parent));
        final Number[] result = gu.getThreeNumbers(
                "Voxel spacing (" + GuiUtils.micrometer() + "):",
                "Set Calibration", defaults, new String[]{"X", "Y", "Z"}, 4);
        if (result == null) return;
        final double[] spacing = { result[0].doubleValue(), result[1].doubleValue(), result[2].doubleValue() };
        setCalibration(spacing, GuiUtils.micrometer());
        SNTUtils.log("Calibration overridden: " + spacing[0] + "x" + spacing[1] + "x" + spacing[2]
                + " " + GuiUtils.micrometer());
    }

    /**
     * Builds the shared scene-control toolbar: fit-source button, align-plane
     * buttons (XY, XZ, YZ), minimap toggle, text-overlay toggle, scale-bar toggle.
     * Subclasses call this and may prepend or append viewer-specific buttons.
     *
     * @return a partially populated JToolBar ready for additional buttons
     */
    protected JToolBar buildBaseSceneControlToolbar() {
        final JToolBar bar = createToolbar();
        bar.add(GuiUtils.Buttons.toolbarButton(fitToCurrentSourceAction(),
                "Fit view to the current (selected) source"));
        bar.addSeparator();
        // Action names match those registered by BDV/BVV NavigationActions
        final java.util.LinkedHashMap<String, List<IconFactory.GLYPH>> planes = new java.util.LinkedHashMap<>();
        planes.put("align XY plane", List.of(IconFactory.GLYPH.X, IconFactory.GLYPH.Y));
        planes.put("align XZ plane", List.of(IconFactory.GLYPH.X, IconFactory.GLYPH.Z));
        planes.put("align ZY plane", List.of(IconFactory.GLYPH.Z, IconFactory.GLYPH.Y));
        final javax.swing.ButtonGroup alignGroup = new javax.swing.ButtonGroup();
        for (final Map.Entry<String, List<IconFactory.GLYPH>> entry : planes.entrySet()) {
            final Action a = getViewerAction(entry.getKey());
            if (a == null) continue;
            final JButton btn = GuiUtils.Buttons.toolbarButton(a, entry.getKey());
            btn.setIcon(IconFactory.doubleIcon(entry.getValue().get(0), entry.getValue().get(1), .75f, null));
            alignGroup.add(btn);
            bar.add(btn);
        }
        bar.addSeparator();
        bar.add(Box.createHorizontalGlue());
        bar.addSeparator();
        final JToggleButton multiboxToggle = GuiUtils.Buttons.toolbarToggleButton(
                overlayToggleAction("Minimap", Prefs.showMultibox(),
                        show -> { Prefs.showMultibox(show); repaint(); }),
                "Show/hide minimap", IconFactory.GLYPH.NAVIGATE, IconFactory.GLYPH.NAVIGATE);
        multiboxToggle.setSelected(Prefs.showMultibox());
        bar.add(multiboxToggle);
        final JToggleButton textToggle = GuiUtils.Buttons.toolbarToggleButton(
                overlayToggleAction("Text Overlay", Prefs.showTextOverlay(),
                        show -> { Prefs.showTextOverlay(show); repaint(); }),
                "Show/hide text overlay", IconFactory.GLYPH.TEXT, IconFactory.GLYPH.TEXT);
        textToggle.setSelected(Prefs.showTextOverlay());
        bar.add(textToggle);
        final JToggleButton scaleBarToggle = GuiUtils.Buttons.toolbarToggleButton(
                overlayToggleAction("Scale Bar", Prefs.showScaleBar(),
                        show -> { Prefs.showScaleBar(show); repaint(); }),
                "Show/hide scale bar (right-click: set calibration)",
                IconFactory.GLYPH.RULER, IconFactory.GLYPH.RULER);
        scaleBarToggle.setSelected(Prefs.showScaleBar());
        scaleBarToggle.addMouseListener(new java.awt.event.MouseAdapter() {
            private void handlePopup(final java.awt.event.MouseEvent ev) {
                if (ev.isPopupTrigger()) { ev.consume(); showCalibrationDialog(scaleBarToggle); }
            }
            @Override public void mousePressed(final java.awt.event.MouseEvent ev)  { handlePopup(ev); }
            @Override public void mouseReleased(final java.awt.event.MouseEvent ev) { handlePopup(ev); }
        });
        bar.add(scaleBarToggle);
        return bar;
    }

    /**
     * Common contract for all viewer annotation overlays.
     *
     * <p>Concrete implementations live inside each viewer subclass (e.g.,
     * {@link Bvv.AnnotationOverlay}, {@link Bdv.AnnotationOverlay}) and handle the
     * viewer-specific projection and rendering. Components such as
     * {@link sc.fiji.snt.BookmarkManager} depend only on this interface.
     */
    public interface AnnotationOverlay {

        /**
         * Adds a single annotation marker at the given world-space position.
         *
         * @param p      the position (world coordinates)
         * @param radius sphere radius in physical units
         * @param color  fill color
         */
        void addAnnotation(SNTPoint p, float radius, Color color);

        /**
         * Removes all annotations from the overlay and triggers a repaint.
         */
        void clear();

        /** Returns the number of annotations currently in the overlay. */
        int getCount();

        /** Returns true if the overlay is currently rendered. */
        boolean isVisible();

        /**
         * Shows or hides the overlay without removing its annotations.
         *
         * @param visible true to show, false to hide
         */
        void setVisible(boolean visible);

        /**
         * Propagates the current annotation list to the underlying renderer
         * and requests a repaint. Call after bulk modifications.
         */
        void updateScene();

        /**
         * Highlights the annotation at the given model index by rendering it in
         * a contrasting color. Pass -1 to clear any existing highlight.
         *
         * @param index model index of the annotation to highlight, or -1 for none
         */
        default void setSelectedIndex(int index) {}

        /**
         * Returns the model index of the annotation whose rendered circle/sphere
         * contains the given screen point, or -1 if none is hit.
         *
         * @param screenX x coordinate in viewer-display pixels
         * @param screenY y coordinate in viewer-display pixels
         */
        default int hitTest(int screenX, int screenY) { return -1; }

        /**
         * Replaces all annotations atomically and requests a single repaint.
         * Prefer this over calling clear() + addAnnotation() in a loop, which
         * would trigger one repaint per call and may cause visible flicker.
         *
         * @param points list of positions (world coordinates); null entries are skipped
         * @param sizes  sphere radii in physical units, parallel to points
         * @param colors fill colors, parallel to points; null entries use Color.YELLOW
         */
        default void replaceAll(final java.util.List<SNTPoint> points,
                                final java.util.List<Float>    sizes,
                                final java.util.List<Color>    colors) {
            clear(); // clears data; subclasses may suppress the repaint here
            for (int i = 0; i < points.size(); i++) {
                final Color c = (colors.get(i) != null) ? colors.get(i) : Color.YELLOW;
                addAnnotation(points.get(i), sizes.get(i), c);
            }
        }
    }
}
