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
import sc.fiji.snt.*;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.IconFactory;
import sc.fiji.snt.tracing.SearchInterface;
import sc.fiji.snt.util.PointInImage;
import sc.fiji.snt.util.SNTColor;
import sc.fiji.snt.util.SNTPoint;
import sc.fiji.snt.analysis.graph.DirectedWeightedGraph;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.*;
import java.util.List;
import java.util.concurrent.Future;

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

    /** Most recently instantiated viewer; scripting convenience. */
    protected static volatile AbstractBigViewer lastInstance;

    /** The SNT instance this viewer is tethered to, or null if no SNT instance is available. */
    protected final SNT snt;

    protected boolean tracingEnabled;

    /** @return SNT instance this viewer is tethered to, or null if no SNT instance is available. */
    public SNT getSNT() { return snt; }

    // AWT only synthesizes mouseClicked if the pointer does not move *at all* between press and release.
    // Trackpads (macOS in particular) routinely introduce a pixel or two of drift during what feels like a
    // stationary click, which silently suppresses mouseClicked entirely: no event is delivered at all, not
    // even to a listener sitting at the very top of mousePressed/mouseReleased. AbstractTracer and Bvv's own
    // registerCenterOnDoubleClickListener listener both detect clicks themselves from press+release within
    // this tolerance instead of relying on that zero-movement guarantee
    protected static final int CLICK_MOVE_TOLERANCE_PX = 4;

    /**
     * Trees currently rendered in this viewer, keyed by unique display label.
     * Insertion order is preserved so the first-added tree stays first.
     */
    protected final Map<String, Tree> renderedTrees = new LinkedHashMap<>();

    /**
     * Labels of the trees rendered by the last {@link #syncPathManagerList()} call. Needed because
     * that method's own diff (remove labels that are still current, then re-add) never catches a
     * tree that has been entirely deleted from the Path Manager (all its paths removed): such a tree
     * simply stops appearing in {@link sc.fiji.snt.PathAndFillManager#getTrees()} altogether, so it
     * would otherwise never be pruned from {@link #renderedTrees} and would linger in the scene
     * forever. Comparing against this set is what lets a fully-deleted tree be detected and removed
     */
    protected final Set<String> syncedPathManagerLabels = new HashSet<>();

    /** Maps SpimData sources back to the file that produced them. */
    protected final Map<AbstractSpimData<?>, String> spimDataFilePaths = new IdentityHashMap<>();

    /** Voxel sizes [x, y, z] for the primary loaded volume, in {@link #calUnit} units. */
    protected double[] cal;

    /** Pixel dimensions [x, y, z] of the primary loaded volume. */
    protected long[] dims;

    /** Rendering options shared across all path/annotation overlays in this viewer. */
    protected PathRenderingOptions renderingOptions = new PathRenderingOptions();

    /** Physical unit for calibration values (e.g., "um", "pixel"). */
    protected String calUnit;

    /** Lazily initialized bookmark/marker manager panel. */
    protected sc.fiji.snt.BookmarkManager markerManager;


    protected AbstractBigViewer() {
        this(null);
    }

    protected AbstractBigViewer(final SNT snt) {
        this.snt = snt;
        lastInstance = this;
    }

    /**
     * Replaces the rendered trees with the current contents of the Path Manager.
     * Only available in SNT-tethered instances.
     *
     * @return true if paths were synced; false if the path manager is empty
     * @throws IllegalArgumentException if this is a standalone viewer
     */
    public boolean syncPathManagerList() {
        if (snt == null)
            throw new IllegalArgumentException("Only available in SNT-tethered instances");
        final java.util.Collection<Tree> trees = snt.getPathAndFillManager().getTrees();
        final java.util.Set<String> currentLabels = trees.stream().map(Tree::getLabel)
                .collect(java.util.stream.Collectors.toSet());
        // A tree that has been entirely deleted from the Path Manager no longer appears in getTrees() at all,
        // so it would never be matched by the  "refresh existing labels" step. Prune it here by diffing against
        // what was last rendered
        final java.util.Set<String> stale = new java.util.HashSet<>(syncedPathManagerLabels);
        stale.removeAll(currentLabels);
        stale.forEach(renderedTrees.keySet()::remove);
        // Force a refresh of the trees that do still exist, so edits (nodes, color, selection) are picked up
        // rather than just additions/removals
        currentLabels.forEach(renderedTrees.keySet()::remove);
        syncedPathManagerLabels.clear();
        syncedPathManagerLabels.addAll(currentLabels);
        if (trees.isEmpty()) {
            syncOverlays();
            return false;
        }
        addCollection(trees, true);
        return true;
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

    /**
     * Displays the main tracing data (the currently active channel/frame of the image
     * being traced) from the associated SNT instance. Only available in SNT-tethered
     * instances.
     *
     * @throws IllegalArgumentException if this is a standalone viewer, or no valid
     *                                  image data is available
     */
    public abstract void showLoadedData();

    /**
     * Displays the secondary tracing data (the filtered/processed layer used for
     * cost-function-based tracing) from the associated SNT instance. Only available
     * in SNT-tethered instances.
     *
     * @throws IllegalArgumentException if this is a standalone viewer, or no
     *                                  secondary data is available
     */
    public abstract void showSecondaryData();

    /**
     * Removes the secondary tracing data layer previously added by {@link #showSecondaryData()}
     * from this viewer, if one is currently displayed. No-op otherwise (e.g., standalone
     * viewers, untethered instances, or when no secondary layer has been shown yet).
     *
     * @see SNT#flushSecondaryData()
     */
    public abstract void hideSecondaryData();

    /**
     * Refreshes the persistent "secondary layer active" indicator shown in this viewer's
     * SNT Controls card, reflecting {@link SNT#isTracingOnSecondaryImageActive()}. No-op if
     * this is not a tethered, tracer-enabled instance (i.e., the indicator was never built).
     * Safe to call from any thread.
     */
    public abstract void updateSecondaryLayerIndicator();

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
     * Sets whether paths are rendered as frusta (tubes) or simple centerlines,
     * and triggers an overlay cache invalidation.
     *
     * @param display {@code true} to render frusta using per-node radii;
     *                {@code false} for fast centerline rendering
     */
    public abstract void setDisplayRadii(boolean display);

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
     * Returns this viewer's current timepoint (1-based, matching {@link sc.fiji.snt.Path#getFrame()}'s
     * convention), or 1 if no data is loaded / timepoint tracking is unavailable.
     */
    public abstract int getCurrentTimepoint();

    /**
     * Navigates this viewer to the specified timepoint (1-based, matching
     * {@link sc.fiji.snt.Path#getFrame()}'s convention). Does nothing if no data is loaded.
     *
     * @param timepoint the 1-based timepoint to navigate to; values &lt; 1 are coerced to 1
     */
    public abstract void setCurrentTimepoint(int timepoint);

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

    /** Returns true if path/tree overlay rendering is currently enabled. */
    protected abstract boolean isPathRenderingEnabled();

    /** Enables or disables path/tree overlay rendering. */
    protected abstract void setPathRenderingEnabled(boolean enabled);

    /**
     * Applies a world-space offset to all rendered path annotations.
     *
     * @param offsetX x offset in calibrated units
     * @param offsetY y offset in calibrated units
     * @param offsetZ z offset in calibrated units
     */
    public abstract void setCanvasOffset(double offsetX, double offsetY, double offsetZ);

    /** Returns the rendering options shared across this viewer's overlays. */
    public PathRenderingOptions getRenderingOptions() { return renderingOptions; }

    protected class Actions {
        private GuiUtils guiUtils;
        // State for hide-annotations (H key) press/release tracking
        float lastClippingDistance = 100f;
        private boolean hideActive;
        private boolean pathsWereVisible;
        private boolean annotationsWereVisible;


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
         * Creates an action that fits the view to the bounding box of the currently
         * selected source, with a short animation.
         */
        Action fitToCurrentSourceAction() {
            return new AbstractAction("Fit Source", IconFactory.menuIcon(IconFactory.GLYPH.EXPAND)) {
                @Override
                public void actionPerformed(final ActionEvent e) {
                    final SourceAndConverter<?> src = getCurrentSource();
                    if (src == null) {
                        showViewerMessage("No source selected");
                        return;
                    }
                    final int cw = getViewerWidth(), ch = getViewerHeight();
                    if (cw <= 0 || ch <= 0) return;
                    final AffineTransform3D srcToWorld = new AffineTransform3D();
                    src.getSpimSource().getSourceTransform(0, 0, srcToWorld);
                    final net.imglib2.RandomAccessibleInterval<?> rai = src.getSpimSource().getSource(0, 0);
                    if (rai == null) return;
                    final long[] min = new long[3], max = new long[3];
                    for (int d = 0; d < 3; d++) {
                        min[d] = rai.min(d);
                        max[d] = rai.max(d);
                    }
                    double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
                    double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
                    double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
                    final double[] corner = new double[3], world = new double[3];
                    for (int i = 0; i < 8; i++) {
                        corner[0] = (i & 1) == 0 ? min[0] : max[0];
                        corner[1] = (i & 2) == 0 ? min[1] : max[1];
                        corner[2] = (i & 4) == 0 ? min[2] : max[2];
                        srcToWorld.apply(corner, world);
                        minX = Math.min(minX, world[0]);
                        maxX = Math.max(maxX, world[0]);
                        minY = Math.min(minY, world[1]);
                        maxY = Math.max(maxY, world[1]);
                        minZ = Math.min(minZ, world[2]);
                        maxZ = Math.max(maxZ, world[2]);
                    }
                    final double physW = maxX - minX, physH = maxY - minY, physZ = maxZ - minZ;
                    if (physW <= 0 || physH <= 0) return;
                    final double scale = Math.min(cw / physW, ch / physH);
                    final AffineTransform3D target = new AffineTransform3D();
                    target.set(scale, 0, 0);
                    target.set(scale, 1, 1);
                    target.set(scale, 2, 2);
                    target.set(cw / 2.0 - scale * (minX + physW / 2.0), 0, 3);
                    target.set(ch / 2.0 - scale * (minY + physH / 2.0), 1, 3);
                    target.set(-scale * (minZ + physZ / 2.0), 2, 3);
                    setViewerTransform(target, 300);
                }
            };
        }

        Action syncPathManagerAction() {
            return new AbstractAction("Sync Path Manager Changes", IconFactory.menuIcon(IconFactory.GLYPH.SYNC)) {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    if (syncPathManagerList()) {
                        showViewerMessage("Path Manager synced");
                    } else {
                        showViewerMessage("No paths or SNT unavailable");
                    }
                }
            };
        }

        Action clearAllPathsAction() {
            return new AbstractAction("Remove All Annotations...", IconFactory.menuIcon(IconFactory.GLYPH.TRASH)) {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    if (getGuiUtils().getConfirmation("Remove all reconstructions? (undoable action)",
                            "Remove All Annotations?")) {
                        clearAllTrees();
                        showViewerMessage("Annotations cleared");
                    }
                }
            };
        }

        Action showMarkerManagerAction() {
            return new AbstractAction("Marker Manager", IconFactory.menuIcon(IconFactory.GLYPH.MARKER)) {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    getMarkerManager().toggleViewerPanel();
                }
            };
        }

        Action importAction() {
            return new AbstractAction("Import Reconstructions...", IconFactory.menuIcon(IconFactory.GLYPH.IMPORT)) {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    final File[] files = getGuiUtils().getReconstructionFiles(getDefaultDir());
                    if (files == null || files.length == 0) return;
                    setDefaultDir(files[0]);
                    add(files);
                }
            };
        }

        Action hideAnnotationsPressAction() {
            return new AbstractAction("Hide annotations (hold)") {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    if (hideActive) return;
                    final boolean hasPaths = isPathRenderingEnabled() && !getRenderedTrees().isEmpty();
                    final boolean hasAnnotations = annotations() != null
                            && annotations().isVisible() && annotations().getCount() > 0;
                    if (!hasPaths && !hasAnnotations) {
                        showViewerMessage("Nothing to hide");
                        return;
                    }
                    pathsWereVisible = isPathRenderingEnabled();
                    annotationsWereVisible = annotations() != null && annotations().isVisible();
                    setPathRenderingEnabled(false);
                    if (annotations() != null) annotations().setVisible(false);
                    hideActive = true;
                }
            };
        }

        Action hideAnnotationsReleaseAction() {
            return new AbstractAction("Restore annotations") {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    if (!hideActive) return;
                    setPathRenderingEnabled(pathsWereVisible);
                    if (annotations() != null) annotations().setVisible(annotationsWereVisible);
                    hideActive = false;
                }
            };
        }

        /**
         * Toggles {@link SNT#isTracingOnSecondaryImageActive()}, mirroring the 'L' hotkey and
         * "Trace/Fill on Secondary Layer" checkbox in the classic (in-core image) UI. Unlike
         * classic mode, where activating the secondary layer requires selecting it in SNTUI,
         * here the layer is already displayed as a source in this viewer (see
         * {@link #showSecondaryData()}), so this action just flips whether the tracer should
         * search on it.
         */
        Action toggleSecondaryLayerTracingAction() {
            return new AbstractAction("Trace/Fill on Secondary Layer") {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    if (snt == null) return;
                    snt.enableSecondaryLayerTracing(!snt.isTracingOnSecondaryImageActive());
                    showViewerMessage("Secondary layer tracing "
                            + ((snt.isTracingOnSecondaryImageActive()) ? "enabled" : "disabled"));
                }
            };
        }

        Action toggleVisibilityAction(final JComponent... componentsToDisableWhenHidden) {
            return new AbstractAction("Show/hide All Annotations") {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    final boolean hasContent = !getRenderedTrees().isEmpty()
                            || (annotations() != null && annotations().getCount() > 0);
                    final AbstractButton btn = (e.getSource() instanceof AbstractButton) ? (AbstractButton)e.getSource() : null;
                    if (!hasContent) {
                        if (btn != null) btn.setSelected(false);
                        showViewerMessage("No annotations exist");
                        return;
                    }
                    final boolean hide = (btn != null) ? btn.isSelected() : isPathRenderingEnabled();
                    setPathRenderingEnabled(!hide);
                    if (annotations() != null) annotations().setVisible(!hide);
                    if (componentsToDisableWhenHidden != null) {
                        for (final JComponent component : componentsToDisableWhenHidden) component.setEnabled(!hide);
                    }
                    showViewerMessage(hide ? "Annotations hidden" : "Annotations visible");
                }
            };
        }

        Action togglePersistentAnnotationsAction() {
            return new AbstractAction("Toggle Annotations Around Cursor") {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    if (!(e.getSource() instanceof AbstractButton toggleButton)) return;
                    if (renderingOptions.isClippingEnabled()) {
                        lastClippingDistance = renderingOptions.clippingDistance;
                        renderingOptions.setClippingDistance(0);
                    } else {
                        final Double newDist = getGuiUtils().getDouble(
                                "<html>Only annotations within this distance from the cursor will be displayed.<br>"
                                        + "Set it to 0, or cancel this prompt to disable this option.",
                                "Annotations Near Cursor",
                                lastClippingDistance, 0d,
                                java.util.Arrays.stream(dims != null ? dims : new long[]{1000})
                                        .asDoubleStream().max().orElse(1000d),
                                calUnit != null ? calUnit : "px");
                        if (newDist == null) {
                            toggleButton.setSelected(false);
                            return;
                        }
                        renderingOptions.setClippingDistance(newDist == 0 ? 0 : newDist.floatValue());
                    }
                    toggleButton.setSelected(renderingOptions.isClippingEnabled());
                    repaint();
                    showViewerMessage(renderingOptions.isClippingEnabled()
                            ? "Visibility: Around cursor" : "Visibility: All visible");
                }
            };
        }

        Action setCanvasOffsetAction() {
            return new AbstractAction("Annotations Offset...") {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    if (!(e.getSource() instanceof AbstractButton toggleButton)) return;

                    final SNTPoint offset = getGuiUtils().getCoordinates(
                            "Offsets (" + calUnit + "): ", "Annotations Offset (Calibrated Distances)",
                            renderingOptions.canvasOffset, 2, SNTPoint.of(0, 0, 0));
                    if (offset == null) {
                        toggleButton.setSelected(false);
                        return;
                    }
                    setCanvasOffset(offset.getX(), offset.getY(), offset.getZ());
                    showViewerMessage((renderingOptions.canvasOffset==null) ? "Offset removed" : "Offset applied");
                    toggleButton.setSelected(renderingOptions.canvasOffset != null);
                }
            };
        }

        GuiUtils getGuiUtils() {
            if (guiUtils == null) guiUtils = new GuiUtils(getViewerFrame());
            return guiUtils;
        }

        protected static File getDefaultDir() {
            return SNTPrefs.lastKnownDir(); // never null
        }

        protected static void setDefaultDir(final File newDir) {
            SNTPrefs.setLastKnownDir(newDir);
        }

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
        bar.add(GuiUtils.Buttons.toolbarButton(new Actions().fitToCurrentSourceAction(),
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
                Actions.overlayToggleAction("Minimap", Prefs.showMultibox(),
                        show -> { Prefs.showMultibox(show); repaint(); }),
                "Show/hide minimap", IconFactory.GLYPH.NAVIGATE, IconFactory.GLYPH.NAVIGATE);
        multiboxToggle.setSelected(Prefs.showMultibox());
        bar.add(multiboxToggle);
        final JToggleButton textToggle = GuiUtils.Buttons.toolbarToggleButton(
                Actions.overlayToggleAction("Text Overlay", Prefs.showTextOverlay(),
                        show -> { Prefs.showTextOverlay(show); repaint(); }),
                "Show/hide text overlay", IconFactory.GLYPH.TEXT, IconFactory.GLYPH.TEXT);
        textToggle.setSelected(Prefs.showTextOverlay());
        bar.add(textToggle);
        final JToggleButton scaleBarToggle = GuiUtils.Buttons.toolbarToggleButton(
                Actions.overlayToggleAction("Scale Bar", Prefs.showScaleBar(),
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

    /**
     * Auto-recenter strategy for BVV's per-click "center the view on the clicked point" behavior
     * (see {@code Bvv#registerCenterOnDoubleClickListener}). Not used by {@link Bdv}.
     */
    public enum RecenterStrategy {
        /** Never auto-recenter the view on a tracing click. */
        NEVER,
        /** Skip the recenter when the click already lands close to the focal plane; gate subsequent
         *  tracing clicks behind a brief settle window after a recenter does fire, so tile streaming
         *  has a chance to catch up. Recommended default for data streamed from disk/network. */
        ADAPTIVE,
        /** Recenter on every tracing click, regardless of distance from the focal plane. */
        ALWAYS,
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
        SNTPoint canvasOffset;
        public Color fallbackColor = SNTPrefs.deselectedPathColor();
        public Color selectedColor = SNTPrefs.selectedPathColor();
        public boolean displayCustomPathColors;
        public boolean requireShiftToFork;
        public boolean activateFinishedPath;
        /** See {@link RecenterStrategy}. Only consulted by {@link Bvv}. */
        public RecenterStrategy strategy = RecenterStrategy.ADAPTIVE;

        float clippingDistance;

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
            this.transparency = Math.clamp(transparency, 0.0f, 1.0f);
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
        @SuppressWarnings("unused")
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
         * @param minThickness minimum thickness in physical (world-space) units
         */
        @SuppressWarnings("unused")
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
        @SuppressWarnings("unused")
        public void setMaxThickness(float maxThickness) {
            this.maxThickness = Math.max(1.0f, maxThickness);
        }

        /**
         * Returns whether paths are rendered as tapered frustums (true) or simple
         * lines (false). Line rendering is dramatically faster for large datasets.
         *
         * @return true if frustum/radius rendering is active
         */
        public boolean isDisplayRadii() {
            return displayRadii;
        }

        /**
         * Controls whether paths are rendered as tapered frustums with per-node
         * radii ({@code true}) or as simple anti-aliased lines ({@code false}).
         * <p>
         * Line rendering uses Java2D's {@link java.awt.BasicStroke} with
         * {@code ROUND_CAP} / {@code ROUND_JOIN}, which is GPU-accelerated and
         * avoids all manual geometry and per-node {@code fillOval} calls.
         * This is the preferred mode for datasets with many paths.
         *
         * @param displayRadii {@code true} for frustum rendering, {@code false}
         *                     for fast line rendering
         */
        public void setDisplayRadii(final boolean displayRadii) {
            this.displayRadii = displayRadii;
        }
        private boolean displayRadii = true;


        /**
         * Enables or disables 'clipped visibility' for path overlays.
         * When enabled, only path nodes within the specified distance from cursor are displayed.
         * When disabled, paths are always visible regardless of cursor positon
         *
         * @param clippingDistance the clippingDistance (in real world units). Set to zero to disable clipping
         */
        @SuppressWarnings("unused")
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

        // Slab clipping
        private double slabZMin = Double.NEGATIVE_INFINITY;
        private double slabZMax = Double.POSITIVE_INFINITY;
        /** Controls whether paths (not annotations) are clipped to the slab. */
        private boolean clipPathsToSlab = false;
        /** Controls whether annotations/markers are clipped to the slab. */
        private boolean clipAnnotationsToSlab = false;

        /**
         * Sets the world-Z bounds of the current slab. Called by the slab position/
         * thickness controls so the overlay renderer can cull paths outside the slab.
         */
        public void setSlabZBounds(final double zMin, final double zMax) {
            this.slabZMin = zMin;
            this.slabZMax = zMax;
        }

        /** Clears slab Z bounds (reverts to no slab culling). */
        public void clearSlabZBounds() {
            this.slabZMin = Double.NEGATIVE_INFINITY;
            this.slabZMax = Double.POSITIVE_INFINITY;
        }

        /** Returns true if a slab view is currently active. */
        public boolean isSlabActive() {
            return slabZMin != Double.NEGATIVE_INFINITY;
        }

        /** Returns {@code true} if path rendering is restricted to the slab Z range. */
        public boolean isClipPathsToSlab() { return clipPathsToSlab; }

        /** Restricts path rendering to the slab Z range when {@code true}. */
        public void setClipPathsToSlab(final boolean clip) { this.clipPathsToSlab = clip; }

        /** Returns {@code true} if annotation/marker rendering is restricted to the slab Z range. */
        public boolean isClipAnnotationsToSlab() { return clipAnnotationsToSlab; }

        /** Restricts annotation/marker rendering to the slab Z range when {@code true}. */
        public void setClipAnnotationsToSlab(final boolean clip) { this.clipAnnotationsToSlab = clip; }

        public double getSlabZMin() { return slabZMin; }
        public double getSlabZMax() { return slabZMax; }
    }

    /**
     * Viewer-agnostic tracing state machine shared by {@link Bvv} and {@link Bdv}: manual/A*-assisted
     * path construction from mouse clicks, fork-point resolution against currently rendered paths,
     * single-step segment undo, and the batch-retrace channel/frame lock (see
     * {@link sc.fiji.snt.SNT#getBatchRetraceChannelFrame()}).
     * <p>
     * Concrete subclasses  supply only the genuinely
     * viewer-specific bits: how a click resolves to a world position, how the path/click-highlight
     * overlays are obtained, how the active channel/frame is derived from the viewer's own
     * source-selection UI, and (optionally) how a status row/undo button reflect tracing state.
     */
    protected abstract class AbstractTracer extends MouseAdapter {

        private Path tempPath;
        private Path.PathNode previousNode;
        private PointInImage previousForkPoint;

        // Undo mechanism, ported from SNT#confirmedSegmentSizes/SNT#undoLastSegment: each traced
        // segment auto-confirms into tempPath (there is no separate temporary/confirm step here, as
        // in the classic 2D canvas), so the "confirmed" node count of each segment is pushed here as
        // it lands, and popped by undoLastSegment() to walk the path back one click at a time. Single-
        // step undo only; no redo.
        private final Deque<Integer> confirmedSegmentSizes = new ArrayDeque<>();

        // Guards against a click being processed while a segment is still being traced in the background
        // This keeps tempPath mutations single-threaded
        private volatile boolean computing;
        // Set when a finish (clickCount >= 2) arrives while computing is true: honored by done() once
        // the in-flight segment lands, instead of silently dropping the finish request.
        private volatile boolean pendingFinish;
        private boolean manualTrace; // true: purely manual trace; false: A* search
        // The Future for the A* search currently active (if any), so a Cancel button (if the viewer has
        // one; see Bvv#tracingStatusRow()) can stop it. Null when idle, or during manual tracing
        private volatile Future<?> currentSearchFuture;

        // Where the button went down, used by mouseReleased to decide whether this was a "click"
        // (see AbstractBigViewer#CLICK_MOVE_TOLERANCE_PX) rather than a drag (e.g. BVV's own rotate/pan).
        private Point pressPoint;

        // Screen-space pick radius for snapping a click to a fork point (see #handleClick). It gets
        // converted to a world-space distance via pickRadiusWorld() so it stays perceptually constant
        // across zoom levels. Safe to keep generous: the search that consumes this is a single path
        // (findNearestRenderedPath()), so a large radius can only ever match somewhere along that one
        // path. Manually-placed tracing waypoints can be much sparser than densely auto-traced ones: a
        // click needs to land within this radius of *some* node for a fork point to be found at all in
        // the first place (see NearPoint#getInsertionIndex())
        private static final double FORK_POINT_PICK_RADIUS_SCREEN_PX = 150;

        // Wall-clock time (System.currentTimeMillis()) until which trace clicks are deferred because a
        // recenter animation is either still running or has just finished (tiles for the new focal
        // region may still be streaming in). 0 (or already elapsed) means the scene is considered
        // settled. Only meaningful for viewers with a recenter-on-click behavior (currently just Bvv;
        // see Bvv#registerCenterOnDoubleClickListener); left permanently at 0 otherwise
        protected volatile long tracingSettleUntilMs = 0;

        protected AbstractTracer(final SNT snt) {
            if (snt == null || snt.getPathAndFillManager() == null) {
                throw new IllegalArgumentException("Tracer can only be initialized with a valid SNT instance");
            }
            addMouseListenerToDisplay(this);
        }

        @Override
        public void mousePressed(final MouseEvent e) {
            pressPoint = e.getPoint();
        }

        @Override
        public void mouseReleased(final MouseEvent e) {
            final Point start = pressPoint;
            pressPoint = null;
            if (start == null) return; // press wasn't seen by this listener; nothing to compare against
            if (start.distance(e.getPoint()) > CLICK_MOVE_TOLERANCE_PX) return; // a real drag, not a click
            handleClick(e);
        }

        /** Click-handling logic, formerly {@code mouseClicked(MouseEvent)}; see {@link #mouseReleased} */
        private void handleClick(final MouseEvent e) {
            if (!tracingEnabled) {
                return;
            }

            // AWT's click count keeps incrementing for any click that lands within the platform's multi-click
            // time/distance window of the previous one. Treating >=2 as "finish" ensures fast multiple clicking
            // does not silently fall through as ordinary tracing input
            if (e.getClickCount() >= 2) { // Path Finished
                if (computing) {
                    // A segment triggered by this same click sequence is still being traced in the background (every
                    // finish is preceded by a clickCount==1 event that itself extends the path). Defer instead of
                    // dropping: done() will finish once that segment lands, so the commit always sees a fully
                    // up-to-date tempPath
                    pendingFinish = true;
                    return;
                }
                finishPath();
                return;
            }
            if (computing) {
                return; // a segment is still being traced; ignore further clicks until it lands
            }

            if (renderingOptions.strategy == RecenterStrategy.ADAPTIVE && System.currentTimeMillis() < tracingSettleUntilMs) {
                // A recenter animation triggered by a previous click is still running, or has just
                // finished but tiles for the new focal region may still be streaming in (see
                // Bvv#registerCenterOnDoubleClickListener). Sampling now risks the exact zig-zag this gate
                // exists to prevent, so the click is dropped rather than silently placing a bad node;
                // the status row (set by the recenter listener) already reads "Stabilizing..."
                showViewerMessage("Scene still stabilizing, please click again");
                return;
            }

            if (previousNode == null) {
                // If a background A* batch re-trace is running (PathManagerUI's "Re-trace with A*..."),
                // its workers are reading this SNT instance's shared image-data fields concurrently.
                // Refuse to start a new path on a *different* channel/frame than the batch is using.
                // That would trigger the resync below and race with it
                final int[] batchLock = snt.getBatchRetraceChannelFrame();
                if (batchLock != null) {
                    final int[] candidate = peekActiveChannelFrame();
                    if (candidate == null || candidate[0] != batchLock[0] || candidate[1] != batchLock[1]) {
                        new GuiUtils(getViewerFrame()).error("An A* batch re-trace is running on channel "
                                + batchLock[0] + " / frame " + batchLock[1] + ". Starting a new path on a "
                                + "different channel/frame is disabled until it finishes.");
                        return;
                    }
                }

                confirmedSegmentSizes.clear(); // just in case of abnormal prior state
                updateUndoButtonState();

                // this is the first click: we are starting a new Path. Lock in the channel/frame (and, for A*,
                // the pixel data) for the whole path now, rather than re-checking on every segment, so a single
                // path can't silently span two channels if the user switches the active source mid-trace
                syncChannelFromActiveSource();
                final double[] cc1 = resolveClickWorldPosition(e);
                if (cc1 == null) return; // msg already displayed

                // Is this new starting node supposed to be a fork point on an existing path?
                final boolean joiner_modifier_down = (renderingOptions.requireShiftToFork) ? e.isShiftDown() && e.isAltDown() : e.isAltDown();

                if (!joiner_modifier_down) {
                    previousNode = new Path.PathNode(cc1);
                    previousForkPoint = null;
                    highlightClickedLocation(previousNode, false);
                    return;
                }

                // Fork requested: resolve it *before* committing to starting a path at all. The fork
                // modifier is an explicit request to attach to an existing branch, so if that request
                // can't be honored, the click should be a clean no-op
                showViewerMessage("Forking...");
                // Auto-select the rendered path nearest the click, then restrict the fork-point search
                // to just that one path, instead of scanning every path in the project
                final Path nearestPath = findNearestRenderedPath(cc1);
                // Pick radius is defined in screen pixels (see pickRadiusWorld()) rather than a fixed
                // world-space distance, so "close enough to fork" stays consistent across zoom levels
                final double pickRadius = pickRadiusWorld(FORK_POINT_PICK_RADIUS_SCREEN_PX);
                final NearPoint nearPoint = (nearestPath == null) ? null : snt.getPathAndFillManager()
                        .nearestPointOnAnyPath(Collections.singletonList(nearestPath), cc1[0], cc1[1], cc1[2], pickRadius);
                if (nearPoint == null) {
                    final String detail = (nearestPath == null) ? "No paths are currently rendered to fork from."
                            : String.format("No point within %.2f%s of the cursor was found on the nearest path ('%s').",
                                    pickRadius, calUnit, nearestPath.getName());
                    final String mod = (renderingOptions.requireShiftToFork) ? "Alt+Shift" : "Alt";
                    new GuiUtils(getViewerFrame()).error("<html>" + detail
                            + "<br>Move closer to the target path, or click without " + mod + " to start an unconnected path.</html>",
                            "No Fork Point Found");
                    return;
                }

                // Fork point found: only now commit to starting the path
                snt.selectPath(nearestPath, false);
                previousNode = new Path.PathNode(cc1);
                highlightClickedLocation(previousNode, true);
                // Prefer the interpolated point on the path's line segments over the nearest raw node:
                // manually-placed waypoints can be sparser than pickRadius, so the closest actual node
                // may sit well past it even when the click lands right on the path itself. Materialize
                // that position as a real node so the branch point is geometrically accurate and the
                // parent path visibly gains a node at the fork location
                final int insertionIndex = nearPoint.getInsertionIndex();
                if (insertionIndex < 0) {
                    previousForkPoint = nearPoint.getNode(); // already an existing node
                } else {
                    nearestPath.insertNode(insertionIndex, nearPoint.getClosestIntersectionPoint());
                    previousForkPoint = nearestPath.getNode(insertionIndex);
                    syncOverlays(); // so the new node is reflected next repaint, not just on the next full sync
                }
                return;
            }

            // this is the second (or Nth) click: highlight it and trace the segment from the
            // previous click to here, forking off an existing path if the join modifier is down
            final double[] cc2 = resolveClickWorldPosition(e);
            if (cc2 == null) {
                return; // msg already displayed
            }
            final Path.PathNode currentNode = new Path.PathNode(cc2);
            highlightClickedLocation(currentNode, false);
            traceSegmentAsync(previousNode, currentNode, previousForkPoint);
            previousNode = currentNode;
            previousForkPoint = null; // reset fork point
        }

        /**
         * Resolves the mouse event to a calibrated (x,y,z) world position. Viewer-specific: BVV resolves
         * this via ray-max intensity picking with a focal-plane fallback ; BDV
         * resolves it directly from the orthographic slice cursor position .
         *
         * @param e the mouse event that triggered the click
         * @return calibrated world position, or {@code null} if it could not be resolved (a message has
         *         already been shown to the user in that case)
         */
        protected abstract double[] resolveClickWorldPosition(MouseEvent e);

        /**
         * Returns the path overlay used to preview/draw the in-progress {@code tempPath}, lazily
         * initializing it first if necessary.
         */
        protected abstract Bvv.PathOverlay ensurePathOverlay();

        /**
         * Returns the annotation overlay used to highlight clicked node locations, lazily initializing
         * it first if necessary.
         */
        protected abstract Bvv.AnnotationOverlay ensureTracingOverlay();

        /** Clears the click-highlight overlay's contents, if one has been initialized. No-op otherwise. */
        protected abstract void clearTracingOverlayContents();

        /** Disposes of the click-highlight overlay, if one has been initialized, and forgets it. */
        protected abstract void disposeTracingOverlay();

        /**
         * Refreshes the path overlay so a stale preview segment (from a just-discarded/finished path) is
         * no longer shown, if a path overlay has already been initialized. Deliberately does *not* force
         * lazy initialization (unlike {@link #ensurePathOverlay()}): if nothing has been drawn yet, there
         * is nothing to clear and no reason to instantiate the overlay machinery early.
         */
        protected void clearPathOverlayPreview() {
            // no-op by default
        }

        /**
         * Re-derives the pixel data, calibration, and channel/frame that A* search and path metadata
         * should use, from whichever source is currently "active" in the viewer's own source-selection
         * UI and the viewer's current timepoint. If the current source can't be resolved for any reason
         * (should not normally happen), this should leave whatever image data/channel/frame SNT already
         * had.
         */
        protected abstract void syncChannelFromActiveSource();

        /**
         * Resolves what {@link #syncChannelFromActiveSource()} would set channel/frame to, without
         * mutating anything. Used to check whether starting a new path now would change the
         * channel/frame away from one a background batch re-trace is locked to.
         *
         * @return {@code {channel, frame}} (1-based), or null if the active source can't be resolved
         */
        protected abstract int[] peekActiveChannelFrame();

        /**
         * Updates a tracing status row (progress bar/label + Cancel button), if the viewer has one.
         * No-op by default; overridden by viewers that expose such UI
         *
         * @param busy    {@code true} while a search is ongoing; {@code false} to reset to idle
         * @param message short status text (ignored/cleared when {@code !busy})
         */
        protected void setTracingStatus(final boolean busy, final String message) {
            // no-op by default
        }

        /**
         * Notifies the viewer that the manual-vs-A* tracing mode was just toggled, so it can enable/
         * disable any UI that only makes sense for one mode (e.g. the status row/Cancel button, which
         * only apply to A* search). No-op by default; overridden by viewers with such UI (see
         */
        protected void onManualTraceModeChanged(final boolean manualTraceFlag) {
            // no-op by default
        }

        /**
         * Notifies the viewer that whether there is a confirmed segment available to undo has changed,
         * so it can enable/disable an Undo button. No-op by default; overridden by viewers with such UI
         *
         * @param hasUndo {@code true} if {@link #undoLastSegment()} would currently do something
         */
        protected void updateUndoButtonState(final boolean hasUndo) {
            // no-op by default
        }

        /**
         * Finds the rendered Path nearest a 3D world position, restricted to what's currently rendered
         * in this viewer's scene ({@link #getRenderedTrees()}) rather than every path in the whole
         * project. Used to auto-select the path a fork click is most likely aimed at, before running the
         * precise fork-point search scoped to just that one path.
         *
         * @param worldPos calibrated (x,y,z) position to search from
         * @return the nearest rendered path, or {@code null} if nothing is currently rendered
         * @see #handleClick(MouseEvent) Mirrors {@code InteractiveTracerCanvas#selectNearestPathToMousePointer},
         */
        private Path findNearestRenderedPath(final double[] worldPos) {
            Path nearest = null;
            final PointInImage wPos = SNTPoint.of(worldPos[0], worldPos[1], worldPos[2]);
            double nearestDistSq = Double.MAX_VALUE;
            for (final Tree tree : getRenderedTrees()) {
                for (final Path path : tree.list()) {
                    for (int i = 0; i < path.size(); i++) {
                        final Path.PathNode node = path.getNode(i);
                        final double distSq = node.distanceSquaredTo(wPos);
                        if (distSq < nearestDistSq) {
                            nearestDistSq = distSq;
                            nearest = path;
                        }
                    }
                }
            }
            return nearest;
        }

        /**
         * Converts a screen-space pixel radius into a world-space distance, using the scale of the
         * current viewer transform. Used to make on-screen "pick" tolerances (e.g. snapping to an
         * existing node to fork from) scale-invariant with respect to zoom.
         * <p>
         * Same technique as BvvUtils#colMagnitudes(AffineTransform3D): sum-of-squares of a
         * transform's linear part, applied to the viewer transform rather than a source transform.
         * BVV/BDV viewer transforms are similarity transforms (rotation + uniform scale), so the three
         * columns should agree closely; averaging them is a simplification, not an exact per-axis fix.
         * <p>
         * NB> this ignores perspective foreshortening (scale technically also depends on depth along
         * the camera axis in BVV). Good enough here because clicks are first centered onto the focal
         * plane (see {@code Bvv#registerCenterOnDoubleClickListener}), where foreshortening is minimal
         *
         * @param screenPx the desired pick radius, in screen pixels
         * @return the equivalent world-space distance at the current zoom level, or {@code screenPx}
         *          unscaled if the viewer transform isn't available (should not normally happen)
         */
        protected double pickRadiusWorld(final double screenPx) {
            final AffineTransform3D t = getViewerTransform();
            if (t == null) return screenPx;
            double sumSq = 0;
            for (int r = 0; r < 3; r++)
                for (int c = 0; c < 3; c++) { final double v = t.get(r, c); sumSq += v * v; }
            final double screenPxPerWorldUnit = Math.sqrt(sumSq / 3.0);
            return (screenPxPerWorldUnit > 0) ? screenPx / screenPxPerWorldUnit : screenPx;
        }

        /**
         * Commits {@code tempPath} to the Path Manager and resets tracing state. Called directly
         * from {@code mouseReleased} when no segment is in flight, or deferred from {@code done()}
         * (via {@code pendingFinish}) when a finish click arrived while one was still being traced.
         */
        private void finishPath() {
            // Nothing pending: ignore a stray finish click
            final boolean inProgress = previousNode != null || (tempPath != null && tempPath.size() > 0);
            if (!inProgress) return;

            // edge case: single click followed directly by finish, with no segment ever traced: tempPath
            // was never initialized, so build a fresh one-node path here
            if (tempPath == null) {
                tempPath = new Path(cal[0], cal[1], cal[2], calUnit);
            }
            // A single node is treated as a single-point soma
            if (tempPath.size() == 0 && previousNode != null) tempPath.addNode(previousNode);
            if (tempPath.size() == 1) tempPath.setSWCType(Path.SWC_SOMA);

            // Add path to path manager and reset
            snt.getPathAndFillManager().addPath(tempPath);
            if (renderingOptions.activateFinishedPath) snt.selectPath(tempPath, false);
            clearTracingOverlayContents();
            syncPathManagerList();
            tempPath = null;
            previousNode = null;
            confirmedSegmentSizes.clear();
            updateUndoButtonState();
            showViewerMessage("Path finished");
        }

        /**
         * Builds the Enter-key action: finishes the in-progress path, same as a double click, but
         * without the spurious node a double click's own first (clickCount==1) click would leave
         * behind. Deferred via {@code pendingFinish} if a segment is still being traced, exactly
         * like the click-based finish path.
         */
        protected AbstractAction getFinishPathAction() {
            return new AbstractAction("Finish Path") {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    if (!tracingEnabled) return;
                    if (computing) {
                        pendingFinish = true;
                        return;
                    }
                    finishPath();
                }
            };
        }

        /**
         * Builds the Escape-key action: discards the in-progress path (see {@link
         * #discardCurrentPath()}) without exiting tracing mode, so the user can immediately start a
         * new path.
         */
        protected AbstractAction getDiscardPathAction() {
            return new AbstractAction("Discard Path") {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    discardCurrentPath();
                }
            };
        }

        /**
         * Discards the current in-progress path: cancels any in-flight segment search, clears
         * {@code tempPath}/{@code previousNode} and the preview overlay, and resets the tracing
         * status row. Unlike {@link #exit()}, {@code tracingEnabled} is left untouched, so the user
         * stays in tracing mode and can start a new path right away. No-op if tracing is disabled
         * or there is nothing pending.
         */
        protected void discardCurrentPath() {
            if (!tracingEnabled) return;
            final boolean hadSomethingToDiscard = previousNode != null || (tempPath != null && tempPath.size() > 0);
            cancelCurrentSearch(); // no-op if idle; stops an in-flight A* search rather than orphaning it
            previousNode = null;
            tempPath = null;
            pendingFinish = false;
            computing = false;
            confirmedSegmentSizes.clear();
            updateUndoButtonState();
            setTracingStatus(false, null);
            clearTracingOverlayContents();
            clearPathOverlayPreview(); // get rid of the stale preview segment
            if (hadSomethingToDiscard) showViewerMessage("Path discarded");
        }

        /**
         * Runs {@link SNT#manualTrace(SNTPoint, SNTPoint, PointInImage)} (or, for A*, {@link
         * SNT#autoTrace(SNTPoint, SNTPoint, PointInImage, SearchProgressCallback, java.util.function.Consumer)})
         * off the EDT and applies the result (stitching into {@code tempPath} and refreshing the
         * preview overlay) back on the EDT once done. Keeps the click handler itself non-blocking.
         * <p>
         * For A* search, also drives a tracing status row if the viewer has one (see {@link #setTracingStatus}):
         * a progress callback updates it roughly once a second and the {@link Future} handle captured on submission
         * lets {@link #cancelCurrentSearch()} stop a runaway search.
         */
        private void traceSegmentAsync(final Path.PathNode start, final Path.PathNode end,
                                       final PointInImage forkPoint) {
            computing = true;
            setTracingStatus(!manualTrace, "Tracing...");
            final SearchProgressCallback progress = new SearchProgressCallback() {
                @Override
                public void pointsInSearch(final SearchInterface source, final long inOpen, final long inClosed) {
                    setTracingStatus(true, String.format("Tracing... %,d nodes explored", inClosed));
                }

                @Override
                public void finished(final SearchInterface source, final boolean success) {
                    // no-op: done() below already handles completion/result application
                }

                @Override
                public void threadStatus(final SearchInterface source, final int currentStatus) {
                    // no-op
                }
            };
            new SwingWorker<Path, Void>() {
                @Override
                protected Path doInBackground() {
                    // NB: must use the headless autoTrace overload here, NOT autoTrace(start,end,forkPoint).
                    // The 3-arg overload is the *interactive* entry point (meant for SNTUI/2D-canvas tracing)
                    return (manualTrace)
                            ? snt.manualTrace(start, end, forkPoint)
                            : snt.autoTrace(start, end, forkPoint, progress, f -> currentSearchFuture = f);
                }

                @Override
                protected void done() {
                    computing = false;
                    final boolean cancelled = currentSearchFuture != null && currentSearchFuture.isCancelled();
                    currentSearchFuture = null;
                    setTracingStatus(false, null);
                    try {
                        final Path result = get();
                        if (result == null) {
                            showViewerMessage(cancelled
                                    ? "Search cancelled" : "Segment could not be traced (out of bounds?)");
                        } else if (tempPath == null) {
                            tempPath = result;
                            pushConfirmedSegmentSize(result.size());
                        } else {
                            final int sizeBefore = tempPath.size();
                            tempPath.add(result);
                            pushConfirmedSegmentSize(tempPath.size() - sizeBefore);
                        }
                        if (result != null) drawSegment(tempPath);
                    } catch (final Exception ex) {
                        showViewerMessage(ex.getMessage());
                        SNTUtils.error("Error tracing segment", ex); // only displayed in debug mode
                    } finally {
                        // Honor a finish request that arrived while this segment was still in flight,
                        // regardless of whether it succeeded: the user's finish click should never be
                        // silently lost, and tempPath (even if this segment failed) still reflects
                        // whatever was successfully traced so far.
                        if (pendingFinish) {
                            pendingFinish = false;
                            finishPath();
                        }
                    }
                }
            }.execute();
        }

        /**
         * Cancels the A* search currently in flight (if any), via {@link Future#cancel(boolean)}.
         * {@code BiSearch}'s loop already checks for thread interruption, so this actually stops the
         * search. Has no effect during manual tracing (nothing to cancel) or when idle.
         */
        protected void cancelCurrentSearch() {
            final Future<?> f = currentSearchFuture;
            if (f != null) f.cancel(true);
        }

        /**
         * Records the node count of a segment that was just auto-confirmed into {@code tempPath}, for
         * {@link #undoLastSegment()} to later pop. Mirrors {@code SNT#confirmedSegmentSizes}/{@code
         * SNT#confirmTemporary}, capped the same way at {@link SNTPrefs#MAX_UNDO_STEPS}.
         *
         * @param nodesAdded number of nodes the segment actually contributed to {@code tempPath}
         */
        private void pushConfirmedSegmentSize(final int nodesAdded) {
            confirmedSegmentSizes.push(nodesAdded);
            if (confirmedSegmentSizes.size() > SNTPrefs.MAX_UNDO_STEPS)
                confirmedSegmentSizes.removeLast(); // drop oldest
            updateUndoButtonState();
        }

        /**
         * Recomputes whether anything is currently available to undo and notifies the viewer (see
         * {@link #updateUndoButtonState(boolean)}). Safe to call at any time, including before any
         * undo-related UI exists.
         */
        protected void updateUndoButtonState() {
            updateUndoButtonState(!confirmedSegmentSizes.isEmpty());
        }

        /**
         * Undoes the most recently confirmed segment, one click at a time (single-step; no redo).
         * Ported from {@code SNT#undoLastSegment()}: pops the last segment's node count off {@link
         * #confirmedSegmentSizes} and removes that many trailing nodes from {@code tempPath}. If that
         * empties {@code tempPath} entirely (undone back to the very first point), this falls back to
         * {@link #discardCurrentPath()}, exactly as the classic 2D canvas cancels the whole path in
         * that case. No-op if tracing is disabled, nothing has been confirmed yet, or a segment is
         * currently being traced (undoing mid-search would race with {@code done()}).
         */
        protected void undoLastSegment() {
            if (!tracingEnabled) return;
            if (confirmedSegmentSizes.isEmpty()) {
                showViewerMessage("No segment to undo");
                return;
            }
            if (computing) {
                showViewerMessage("A segment is still being traced; please wait before undoing");
                return;
            }
            final int nodesToRemove = confirmedSegmentSizes.pop();
            for (int i = 0; i < nodesToRemove; i++)
                tempPath.removeNode(tempPath.size() - 1);

            if (tempPath.size() == 0) {
                // Undone back to the very first point: equivalent to discarding the path outright
                discardCurrentPath();
                return;
            }
            previousNode = new Path.PathNode(tempPath.lastNode());
            previousForkPoint = null;
            updateUndoButtonState();
            drawSegment(tempPath);
            showViewerMessage("Segment undone");
        }

        /**
         * Builds the undo action shared by an Undo button (if any) and the Cmd/Ctrl+Z hotkey.
         *
         * @see #undoLastSegment()
         */
        protected AbstractAction getUndoSegmentAction() {
            return new AbstractAction("Undo Last Segment") {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    undoLastSegment();
                }
            };
        }

        private void highlightClickedLocation(final Path.PathNode node, final boolean highlightAsForkPoint) {
            ensureTracingOverlay().addAnnotation(node,
                    (highlightAsForkPoint) ? getDefaultMarkerSize() * 2 : getDefaultMarkerSize(),
                    (highlightAsForkPoint) ? SNTColor.contrastHueColor(getDefaultMarkerColor(), Color.BLACK) : getDefaultMarkerColor());
        }

        private void drawSegment(final Path segment) {
            final Tree tree = new Tree();
            tree.add(segment);
            //color will be set to getDefaultMarkerColor()
            ensurePathOverlay().updatePaths(tree);
        }

        protected void exit() {
            cancelCurrentSearch(); // stops active A* search if any
            previousNode = null;
            tempPath = null;
            computing = false;
            pendingFinish = false;
            confirmedSegmentSizes.clear();
            updateUndoButtonState();
            setTracingStatus(false, null);
            disposeTracingOverlay();
            clearPathOverlayPreview(); // get rid of temp path
        }

        protected AbstractAction getToggleAction(final boolean manualTraceFlag) {
            return new AbstractAction("Start/Stop tracing") {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {

                    final AbstractButton button = (e.getSource() instanceof AbstractButton) ? (AbstractButton) e.getSource() : null;
                    tracingEnabled = (button == null) ? tracingEnabled : button.isSelected();
                    AbstractTracer.this.manualTrace = manualTraceFlag;

                    onManualTraceModeChanged(manualTraceFlag);

                    final boolean sntAware = snt != null && snt.getPathAndFillManager() != null;
                    final boolean tracingPossible = manualTraceFlag || (sntAware && snt.accessToValidImageData());
                    final String tracingDescription = (manualTraceFlag) ? "Manual tracing" : "Semi-automated tracing";

                    if (!tracingPossible && tracingEnabled) {
                        new GuiUtils(getViewerFrame()).error(tracingDescription + " is not available.");
                        tracingEnabled = false;
                        AbstractTracer.this.manualTrace = true;
                        if (button != null) {
                            button.setSelected(false);
                            button.setEnabled(false);
                        }
                    } else if (tracingEnabled) {
                        showViewerMessage(tracingDescription + " enabled");
                    } else {
                        final boolean exited = exitedWithConfirmationPrompt();
                        if (!exited && button != null) button.setSelected(true);
                    }
                }
            };
        }

        private boolean exitedWithConfirmationPrompt() {
            final boolean promptUser = tempPath != null && tempPath.size() > 0;
            if (promptUser) {
                final int ans = new GuiUtils(getViewerFrame())
                        .yesNoDialog("An unfinished path exists. Would you like to finish it?", "Unfinished Path",
                        "Yes. Finish Path", "No. Discard Path");
                if (ans == JOptionPane.YES_OPTION) {
                    finishPath();
                } else if (ans == JOptionPane.NO_OPTION) {
                    discardCurrentPath();
                }
            }
            exit();
            showViewerMessage("Tracing disabled");
            return true;
        }

    }
}
