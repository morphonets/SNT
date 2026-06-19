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

import bdv.tools.InitializeViewerState;
import mpicbg.spim.data.generic.AbstractSpimData;
import net.imglib2.type.numeric.NumericType;
import sc.fiji.snt.*;
import sc.fiji.snt.io.SpimDataUtils;
import sc.fiji.snt.util.BoundingBox;
import bdv.util.BdvFunctions;
import bdv.util.BdvHandle;
import bdv.util.BdvOptions;
import bdv.util.BdvStackSource;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.ViewerPanel;
import bdv.viewer.animate.SimilarityTransformAnimator;
import ij.ImagePlus;
import net.imagej.ImgPlus;
import net.imagej.axis.Axes;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealPoint;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.view.Views;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import org.scijava.command.CommandService;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.IconFactory;
import sc.fiji.snt.gui.cmds.BdvRenderingOptionsCmd;
import sc.fiji.snt.util.ImpUtils;
import sc.fiji.snt.util.SNTPoint;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.File;
import java.util.*;
import java.util.List;

/**
 * SNT's BigDataViewer-based 2D slice viewer.
 *
 * <p>Complements {@link Bvv} for images where orthographic 2D slice navigation
 * is preferable over GPU volume rendering. Because BDV uses orthographic
 * projection, cursor coordinates returned by {@link ViewerPanel#getGlobalMouseCoordinates}
 * are unambiguous at the current slice depth, making marker placement more
 * accurate than in BVV.
 *
 * <p>This class extends {@link AbstractBigViewer} and shares tree management,
 * calibration, and bookmark infrastructure with {@link Bvv}.
 *
 * <p>Path and annotation overlays reuse {@link Bvv.PathOverlay} and
 * {@link Bvv.AnnotationOverlay} via the {@link BigViewerPanel} adapter, with
 * {@code dCam = Double.MAX_VALUE} so the perspective factor evaluates to 1.0
 * (orthographic rendering, no foreshortening).
 *
 * <h3>Usage (scripting)</h3>
 * <pre>
 * bdv = new Bdv()
 * bdv.show(imp)
 * bdv.addTree(tree)
 * bdv.getMarkerManager().toggleViewerPanel()
 * </pre>
 *
 * @author Tiago Ferreira
 * @see Bvv
 * @see AbstractBigViewer
 */
public class Bdv extends AbstractBigViewer {

    static { net.imagej.patcher.LegacyInjector.preinit(); }

    /**
     * Returns the most recently created {@link Bdv} instance, or {@code null}.
     * Convenience accessor for scripts.
     */
    public static Bdv getInstance() {
        return (lastInstance instanceof Bdv b) ? b : null;
    }

    // -- BDV-specific state --

    private BdvHandle bdvHandle;
    private ViewerPanel viewerPanel;

    // Shared overlay infrastructure (same classes as BVV; dCam = MAX_VALUE => orthographic)
    private Bvv.PathOverlay pathOverlay;
    private Bvv.AnnotationOverlay annotationOverlay;
    private final Bvv.PathRenderingOptions renderingOptions = new Bvv.PathRenderingOptions();

    // Guard so addTransformListener is called only once across reinits
    private boolean sliceClipListenerRegistered = false;

    // -- Construction --

    /** Creates a standalone BDV viewer (not tethered to an SNT instance). */
    public Bdv() {
        this(null);
    }

    /** Creates a BDV viewer tethered to an SNT instance. */
    public Bdv(final SNT snt) {
        super(snt);
    }

    /**
     * Opens an ImagePlus in the BDV viewer.
     *
     * @param imp the image to display
     * @return the BdvStackSource for the displayed image
     */
    public BdvStackSource<?> show(final ImagePlus imp) {
        if (imp == null) throw new IllegalArgumentException("ImagePlus cannot be null");
        cal = new double[]{imp.getCalibration().pixelWidth,
                           imp.getCalibration().pixelHeight,
                           imp.getCalibration().pixelDepth};
        dims = new long[]{imp.getWidth(), imp.getHeight(), Math.max(imp.getNSlices(), 1)};
        calUnit = imp.getCalibration().getUnit();
        final BdvStackSource<?> src;
        if (imp.getNChannels() > 1) {
            // Multichannel: show each channel as a separate BDV source.
            // Passing the full [W,H,C] RAI directly to CalibratedSource treats C as Z.
            BdvStackSource<?> firstSrc = null;
            for (int c = 1; c <= imp.getNChannels(); c++) {
                final ImagePlus ch = ImpUtils.getChannel(imp, c);
                final BdvOptions chOpts = (firstSrc == null)
                        ? baseOpts()
                        : BdvOptions.options().addTo(bdvHandle);
                // BDV requires 3D sources; add singleton Z for 2D images
                RandomAccessibleInterval<?> chRai = ImpUtils.toImgPlus(ch);
                if (chRai.numDimensions() < 3) chRai = Views.addDimension(chRai, 0, 0);
                final BdvStackSource<?> cs = showCalibratedBdvSource(
                        chRai, imp.getTitle() + " [C" + c + "]", chOpts);
                if (firstSrc == null) {
                    firstSrc = cs;
                    if (bdvHandle == null) {
                        bdvHandle = cs.getBdvHandle();
                        viewerPanel = bdvHandle.getViewerPanel();
                        initializeOverlays();
                        InitializeViewerState.initBrightness(0.001, 0.999,
                                viewerPanel.state(), bdvHandle.getConverterSetups());
                    }
                }
            }
            src = firstSrc;
        } else {
            src = showCalibratedBdvSource(ImpUtils.toImgPlus(imp), imp.getTitle(), baseOpts());
            if (bdvHandle == null) {
                bdvHandle = src.getBdvHandle();
                viewerPanel = bdvHandle.getViewerPanel();
                initializeOverlays();
                InitializeViewerState.initBrightness(0.001, 0.999,
                        viewerPanel.state(), bdvHandle.getConverterSetups());
            }
        }
        return src;
    }

    /**
     * Opens a calibrated ImgPlus in the BDV viewer.
     *
     * @param img the image to display
     * @return the BdvStackSource for the displayed image
     */
    public <T extends RealType<T> & NativeType<T>> BdvStackSource<?> show(final ImgPlus<T> img) {
        if (img == null) throw new IllegalArgumentException("ImgPlus cannot be null");
        // Axis-aware calibration extraction (same convention as BVV)
        final int xDim = img.dimensionIndex(Axes.X);
        final int yDim = img.dimensionIndex(Axes.Y);
        final int zDim = img.dimensionIndex(Axes.Z);
        cal = new double[]{
            xDim >= 0 ? img.averageScale(xDim) : 1.0,
            yDim >= 0 ? img.averageScale(yDim) : 1.0,
            zDim >= 0 ? img.averageScale(zDim) : 1.0
        };
        dims = new long[]{
            xDim >= 0 ? img.dimension(xDim) : img.dimension(0),
            yDim >= 0 ? img.dimension(yDim) : img.dimension(1),
            zDim >= 0 ? img.dimension(zDim) : 1
        };
        if (xDim >= 0) {
            final net.imagej.axis.CalibratedAxis ax = img.axis(xDim);
            if (ax != null && ax.unit() != null && !ax.unit().isBlank())
                calUnit = ax.unit();
        }
        final String title = img.getName() != null ? img.getName() : "Image";
        final int chDim = img.dimensionIndex(Axes.CHANNEL);
        BdvStackSource<?> src;
        if (chDim >= 0 && img.dimension(chDim) > 1) {
            // Multichannel: show each channel as a separate BDV source.
            // BdvFunctions.show(ImgPlus) has no ImgPlus-specific overload and falls back
            // to the RAI overload, which treats the channel dim as Z regardless of axis labels.
            BdvStackSource<?> firstSrc = null;
            final int nC = (int) img.dimension(chDim);
            for (int c = 0; c < nC; c++) {
                // BDV requires 3D sources; add singleton Z for 2D images
                RandomAccessibleInterval<T> ch = Views.hyperSlice(img, chDim, c);
                if (ch.numDimensions() < 3) ch = Views.addDimension(ch, 0, 0);
                final BdvOptions chOpts = (firstSrc == null)
                        ? baseOpts().sourceTransform(calToTransform())
                        : BdvOptions.options().addTo(bdvHandle).sourceTransform(calToTransform());
                final BdvStackSource<?> cs = showCalibratedBdvSource(
                        ch, title + " [C" + (c + 1) + "]", chOpts);
                if (firstSrc == null) {
                    firstSrc = cs;
                    if (bdvHandle == null) {
                        bdvHandle = cs.getBdvHandle();
                        viewerPanel = bdvHandle.getViewerPanel();
                        initializeOverlays();
                    }
                }
            }
            src = firstSrc;
        } else {
            // Single-channel: wrap in CalibratedSource so unit propagates to scale bar
            src = showCalibratedBdvSource(img, title, baseOpts());
        }
        // Single-channel path init (multichannel handles this inside the loop above)
        if (bdvHandle == null) {
            bdvHandle = src.getBdvHandle();
            viewerPanel = bdvHandle.getViewerPanel();
            initializeOverlays();
        }
        return src;
    }

    /**
     * Opens a 3D RAI with explicit calibration in the BDV viewer.
     *
     * @param rai         the 3D volume
     * @param calibration voxel sizes [x, y, z]
     * @return the BdvStackSource for the displayed image
     */
    public <T extends RealType<T> & NativeType<T>> BdvStackSource<?> show(
            final RandomAccessibleInterval<T> rai, final double... calibration) {
        cal = (calibration == null || calibration.length == 0)
                ? new double[]{1, 1, 1} : calibration;
        dims = new long[]{rai.dimension(0), rai.dimension(1),
                          rai.numDimensions() > 2 ? rai.dimension(2) : 1};
        final BdvStackSource<?> src = showCalibratedBdvSource(rai, "Image", baseOpts());
        if (bdvHandle == null) {
            bdvHandle = src.getBdvHandle();
            viewerPanel = bdvHandle.getViewerPanel();
            initializeOverlays();
        }
        return src;
    }

    /**
     * Opens a SpimData dataset (HDF5/N5/Zarr/IMS/OME-TIFF via XML) in the BDV viewer.
     * Populates {@link #cal}, {@link #dims}, and {@link #calUnit} from the first setup's
     * metadata so that calibration is available for tree/marker rendering.
     *
     * @param spimData the dataset to display
     * @return list of BdvStackSources, one per setup, in setup order
     */
    public List<BdvStackSource<?>> show(final AbstractSpimData<?> spimData) {
        final BdvOptions opts = (bdvHandle == null)
                ? BdvOptions.options()
                : BdvOptions.options().addTo(bdvHandle);
        final List<BdvStackSource<?>> sources = BdvFunctions.show(spimData, opts);
        if (sources.isEmpty()) return sources;

        if (dims == null || cal == null) {
            try {
                final var setups = spimData.getSequenceDescription().getViewSetupsOrdered();
                if (!setups.isEmpty()) {
                    final var setup = setups.getFirst();
                    if (setup.hasSize()) {
                        final var sz = setup.getSize();
                        dims = new long[]{sz.dimension(0), sz.dimension(1), sz.dimension(2)};
                    }
                    if (setup.hasVoxelSize()) {
                        final var vs = setup.getVoxelSize();
                        cal = new double[]{vs.dimension(0), vs.dimension(1), vs.dimension(2)};
                        calUnit = (vs.unit() != null && !vs.unit().isBlank())
                                ? BoundingBox.sanitizedUnit(vs.unit()) : "pixel";
                    }
                }
            } catch (final Exception ignored) {} // never break rendering for a metadata hiccup
        }

        if (bdvHandle == null) {
            bdvHandle = sources.getFirst().getBdvHandle();
            viewerPanel = bdvHandle.getViewerPanel();
            initializeOverlays();
        }
        return sources;
    }

    /**
     * Opens a SpimData dataset and records its source file path for later reference
     * (e.g., for export or re-opening). Delegates to {@link #show(AbstractSpimData)}.
     *
     * @param spimData   the dataset to display
     * @param sourcePath the absolute file path from which spimData was loaded; ignored if null or blank
     * @return list of BdvStackSources, one per setup
     */
    public List<BdvStackSource<?>> show(final AbstractSpimData<?> spimData, final String sourcePath) {
        if (sourcePath != null && !sourcePath.isBlank())
            spimDataFilePaths.put(spimData, sourcePath);
        return show(spimData);
    }

    @Override
    public JFrame getViewerFrame() {
        return (bdvHandle == null) ? null
                : bdvHandle.getSplitPanel().getTopLevelAncestor() instanceof JFrame f ? f : null;
    }

    @Override
    public int getViewerWidth() {
        return (viewerPanel == null) ? 0 : viewerPanel.getDisplay().getWidth();
    }

    @Override
    public int getViewerHeight() {
        return (viewerPanel == null) ? 0 : viewerPanel.getDisplay().getHeight();
    }

    @Override
    public AffineTransform3D getViewerTransform() {
        final AffineTransform3D t = new AffineTransform3D();
        if (viewerPanel != null) viewerPanel.state().getViewerTransform(t);
        return t;
    }

    @Override
    public void setViewerTransform(final AffineTransform3D target, final long durationMs) {
        if (viewerPanel == null) return;
        final AffineTransform3D current = getViewerTransform();
        viewerPanel.setTransformAnimator(
                new SimilarityTransformAnimator(current, target, 0, 0, durationMs));
    }

    @Override
    public void showViewerMessage(final String msg) {
        if (viewerPanel != null) viewerPanel.showMessage(msg);
    }

    @Override
    public void getGlobalMouseCoordinates(final RealPoint pos) {
        if (viewerPanel != null) viewerPanel.getGlobalMouseCoordinates(pos);
    }

    @Override
    protected SourceAndConverter<?> getCurrentSource() {
        return (viewerPanel == null) ? null : viewerPanel.state().getCurrentSource();
    }

    @Override
    protected Action getViewerAction(final String name) {
        if (bdvHandle.getSplitPanel().getTopLevelAncestor() instanceof bdv.viewer.ViewerFrame vf)
            return vf.getKeybindings().getConcatenatedActionMap().get(name);
        return null;
    }

    @Override
    public void addMouseListenerToDisplay(final java.awt.event.MouseListener ml) {
        if (viewerPanel != null) viewerPanel.getDisplay().addMouseListener(ml);
    }

    @Override
    public void resetView() {
        if (viewerPanel == null) return;
        InitializeViewerState.initTransform(
                viewerPanel.getDisplay().getWidth(),
                viewerPanel.getDisplay().getHeight(),
                false, viewerPanel.state());
        viewerPanel.requestRepaint();
    }

    @Override
    public boolean isOpen() {
        final JFrame f = getViewerFrame();
        return f != null && f.isDisplayable();
    }

    @Override
    public void repaint() {
        if (viewerPanel != null) viewerPanel.requestRepaint();
    }

    @Override
    public void syncOverlays() {
        if (pathOverlay != null) pathOverlay.updatePaths();
        if (annotationOverlay != null) annotationOverlay.updateScene();
    }

    @Override
    public Bvv.AnnotationOverlay annotations() {
        return annotationOverlay;
    }

    @Override
    public float getDefaultMarkerSize() {
        return 5f;
    }

    @Override
    public Color getDefaultMarkerColor() {
        return null; // let BookmarkManager use its own color cycle
    }

    @Override
    protected BookmarkManager createMarkerManager() {
        return new BookmarkManager(this);
    }

    /**
     * Returns the underlying {@link BdvHandle} that manages sources and the viewer state.
     */
    public BdvHandle getBdvHandle() {
        return bdvHandle;
    }

    /**
     * Returns the BDV display panel.
     * Unlike BVV, BDV uses a {@link ViewerPanel} (orthographic slice viewer).
     */
    public ViewerPanel getViewerPanel() {
        return viewerPanel;
    }

    /**
     * Returns the rendering options controlling path thickness, transparency, etc.
     * For BDV, slab clipping options have no effect (slab mode is BVV-only).
     */
    public Bvv.PathRenderingOptions getRenderingOptions() {
        return renderingOptions;
    }

    /**
     * Shifts all rendered trees by (offsetX, offsetY, offsetZ) in world coordinates
     * and records the offset in the rendering options so it survives a syncOverlays() call.
     */
    public void setCanvasOffset(final double offsetX, final double offsetY, final double offsetZ) {
        for (final Tree tree : renderedTrees.values())
            tree.applyCanvasOffset(offsetX, offsetY, offsetZ);
        syncOverlays();
        renderingOptions.canvasOffset = (offsetX == 0 && offsetY == 0 && offsetZ == 0)
                ? null : SNTPoint.of(offsetX, offsetY, offsetZ);
    }

    /**
     * Switches between frustum (radius-based) and centerline rendering.
     * Invalidates the overlay cache and requests a repaint.
     */
    public void setDisplayRadii(final boolean display) {
        renderingOptions.setDisplayRadii(display);
        if (pathOverlay != null) pathOverlay.overlayRenderer.invalidateCache();
        syncOverlays();
    }

    /** Returns whether paths are rendered as frusta (true) or centerlines (false). */
    public boolean getDisplayRadii() {
        return renderingOptions.isDisplayRadii();
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
        if (snt.getPathAndFillManager().size() == 0) return false;
        final java.util.Collection<Tree> trees = snt.getPathAndFillManager().getTrees();
        trees.stream().map(Tree::getLabel).toList().forEach(renderedTrees.keySet()::remove);
        addCollection(trees, true);
        return true;
    }

    /**
     * Initializes path and annotation overlays using the shared BVV renderer classes.
     * dCam is set to Double.MAX_VALUE so the perspective formula dCam/(dCam+z) = 1.0
     * exactly (IEEE 754: MAX_VALUE + finite = MAX_VALUE), giving orthographic rendering.
     */
    private void initializeOverlays() {
        final BigViewerPanel bvp = adapt(viewerPanel);

        if (pathOverlay != null) pathOverlay.dispose();
        pathOverlay = new Bvv.PathOverlay(bvp, this, renderingOptions);
        pathOverlay.overlayRenderer.dCam = Double.MAX_VALUE; // orthographic projection

        if (annotationOverlay != null) annotationOverlay.dispose();
        annotationOverlay = new Bvv.AnnotationOverlay(bvp, renderingOptions);
        // dCam=MAX_VALUE => orthographic (same formula as pathOverlay); near/far set by updateSliceClip
        annotationOverlay.setCamParams(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE);

        // Slice-aware clipping: paths and annotations outside +-0.5 voxels are hidden.
        renderingOptions.setClipPathsToSlab(true);
        renderingOptions.setClipAnnotationsToSlab(true);

        // Seed clip values from the current transform, then keep them live.
        final AffineTransform3D initialTransform = new AffineTransform3D();
        viewerPanel.state().getViewerTransform(initialTransform);
        updateSliceClip(initialTransform);

        if (!sliceClipListenerRegistered) {
            sliceClipListenerRegistered = true;
            viewerPanel.transformListeners().add(this::updateSliceClip);
        }

        initializeCardPanel();
    }

    /** Returns the base BdvOptions: either a fresh instance or addTo existing handle. */
    private BdvOptions baseOpts() {
        return bdvHandle == null ? BdvOptions.options() : BdvOptions.options().addTo(bdvHandle);
    }

    /**
     * Builds a diagonal AffineTransform3D from the current {@link #cal} values.
     * Used to bake voxel calibration into a CalibratedSource or BdvOptions.sourceTransform.
     */
    private AffineTransform3D calToTransform() {
        final AffineTransform3D t = new AffineTransform3D();
        if (cal != null) {
            t.set(cal[0], 0, 0);
            t.set(cal[1], 1, 1);
            t.set(cal[2], 2, 2);
        }
        return t;
    }

    /**
     * Wraps {@code rai} in a {@link sc.fiji.snt.io.SpimDataUtils.CalibratedSource} so that
     * the BDV scale bar reads the correct physical unit (not "pixel"). The calibration
     * is taken from the current {@link #cal} and {@link #calUnit} fields.
     * <p>
     * This mirrors BVV's {@code showCalibratedSource()} pattern.
     * </p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private BdvStackSource<?> showCalibratedBdvSource(
            final RandomAccessibleInterval<?> rai,
            final String name,
            final BdvOptions opts) {
        final SpimDataUtils.CalibratedSource src =
                new SpimDataUtils.CalibratedSource(
                        rai, (NumericType) rai.getType(), calToTransform(), name,
                        cal != null ? cal : new double[]{1, 1, 1},
                        calUnit != null ? calUnit : "pixel");
        return BdvFunctions.show(src, 1, opts);
    }

    /**
     * Updates the overlay slice-clip bounds whenever the viewer transform changes.
     * In BDV, viewerCoords[2] = depth of a world point from the current viewing plane.
     * We allow paths within +-halfZ viewer units, where halfZ is half the voxel extent
     * projected onto the viewing direction. This accounts for oblique views: all three
     * world axes contribute to viewer-Z when the view is rotated.
     */
    private void updateSliceClip(final AffineTransform3D transform) {
        if (pathOverlay == null) return;
        final double cX = (cal != null && cal.length > 0) ? cal[0] : 1.0;
        final double cY = (cal != null && cal.length > 1) ? cal[1] : 1.0;
        final double cZ = (cal != null && cal.length > 2) ? cal[2] : 1.0;
        // Half-voxel extent along the viewing direction (Z row of the viewer transform)
        final double halfZ = 0.5 * (Math.abs(transform.get(2, 0)) * cX
                + Math.abs(transform.get(2, 1)) * cY
                + Math.abs(transform.get(2, 2)) * cZ);
        // Floor of 0.5 prevents all paths disappearing at degenerate transforms
        final double slabZ = Math.max(halfZ, 0.5);
        pathOverlay.overlayRenderer.nearClip = slabZ;
        pathOverlay.overlayRenderer.farClip  = slabZ;
        pathOverlay.overlayRenderer.invalidateCache();
        if (annotationOverlay != null)
            annotationOverlay.setCamParams(Double.MAX_VALUE, slabZ, slabZ);
    }

    private void initializeCardPanel() {
        bdvHandle.getSplitPanel().setCollapsed(false);
        final BdvActions actions = new BdvActions();
        final bdv.ui.CardPanel cp = bdvHandle.getCardPanel();
        if (cp != null) {
            cp.addCard("Scene Controls", buildSceneControlToolbar(), true);
            cp.addCard("SNT Annotations", sntAnnotationsCard(actions), true);
            cp.setCardExpanded("Groups", false);
            SwingUtilities.invokeLater(() -> {
                cp.setCardExpanded("Scene Controls", true);
                cp.setCardExpanded("SNT Annotations", true);
            });
        }

        // M and H keys via BDV's keybindings system so the trigger layer sees them
        if (bdvHandle.getSplitPanel().getTopLevelAncestor() instanceof bdv.viewer.ViewerFrame vf) {
            final javax.swing.InputMap sntIMap = new javax.swing.InputMap();
            final javax.swing.ActionMap sntAMap = new javax.swing.ActionMap();
            sntIMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_M, 0), "snt-add-marker");
            sntAMap.put("snt-add-marker", new AbstractAction() {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    final RealPoint pos = new RealPoint(3);
                    getGlobalMouseCoordinates(pos);
                    final double x = pos.getDoublePosition(0);
                    final double y = pos.getDoublePosition(1);
                    final double z = pos.getDoublePosition(2);
                    getMarkerManager().add(x, y, z);
                    showViewerMessage(String.format("Marker placed at (%.1f, %.1f, %.1f)", x, y, z));
                }
            });
            sntIMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_H, 0, false), "snt-hide-annotations-press");
            sntIMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_H, 0, true),  "snt-hide-annotations-release");
            sntAMap.put("snt-hide-annotations-press",   actions.hideAnnotationsPressAction());
            sntAMap.put("snt-hide-annotations-release", actions.hideAnnotationsReleaseAction());
            vf.getKeybindings().addInputMap("snt", sntIMap);
            vf.getKeybindings().addActionMap("snt", sntAMap);
        }
    }

    private JComponent sntAnnotationsCard(final BdvActions actions) {
        final JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.add(GuiUtils.Buttons.toolbarToggleButton(actions.toggleVisibilityAction(),
                "Show/hide annotations",
                IconFactory.GLYPH.EYE, IconFactory.GLYPH.EYE_SLASH));
        bar.add(GuiUtils.Buttons.toolbarToggleButton(actions.togglePersistentAnnotationsAction(),
                "Restrict display of annotations around cursor",
                IconFactory.GLYPH.COMPUTER_MOUSE, IconFactory.GLYPH.COMPUTER_MOUSE));
        bar.addSeparator();
        bar.add(Box.createHorizontalGlue());
        bar.addSeparator();
        bar.add(GuiUtils.Buttons.toolbarButton(actions.setCanvasOffsetAction(),
                "Change annotations offset"));
        bar.add(GuiUtils.Buttons.undo(actions.resetCanvasOffsetAction()));
        bar.addSeparator();
        bar.add(Box.createHorizontalGlue());
        bar.addSeparator();
        bar.add(GuiUtils.Buttons.toolbarButton(actions.pathRenderingOptionsAction(),
                "Set rendering options of annotations"));
        bar.addSeparator();
        bar.add(Box.createHorizontalGlue());
        bar.addSeparator();

        final JToggleButton markerButton = GuiUtils.Buttons.toolbarToggleButton(
                actions.showMarkerManagerAction(),
                "<html>Show/hide the Markers table.<br>"
                        + "Press M in the viewer to place a marker at the cursor position.",
                IconFactory.GLYPH.MARKER, IconFactory.GLYPH.MARKER);
        // Sync button state with panel visibility
        getMarkerManager().getViewerDialogPanel().addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentShown(final java.awt.event.ComponentEvent e) {
                markerButton.setSelected(true);
            }
            @Override
            public void componentHidden(final java.awt.event.ComponentEvent e) {
                markerButton.setSelected(false);
            }
        });
        bar.add(markerButton);
        bar.addSeparator();
        bar.add(optionsButton(actions));
        return bar;
    }

    private JButton optionsButton(final BdvActions actions) {
        final JPopupMenu menu = new JPopupMenu();
        menu.add(new JMenuItem(actions.importAction()));
        if (snt != null) {
            menu.addSeparator();
            menu.add(new JMenuItem(actions.loadBookmarksAction()));
            menu.add(new JMenuItem(actions.syncPathManagerAction()));
        }
        menu.addSeparator();
        menu.add(new JMenuItem(actions.clearAllPathsAction()));
        return GuiUtils.Buttons.OptionsButton(IconFactory.GLYPH.TOOL, 1f, menu);
    }

    private JToolBar buildSceneControlToolbar() {
        return buildBaseSceneControlToolbar();
    }

    private class BdvActions {
        private final GuiUtils guiUtils;
        private float lastClippingDistance = 100f;
        private boolean hideActive;
        private boolean pathsWereVisible;
        private boolean annotationsWereVisible;

        BdvActions() {
            guiUtils = new GuiUtils(getViewerFrame());
        }

        Action hideAnnotationsPressAction() {
            return new AbstractAction("Hide annotations (hold)") {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    if (hideActive) return;
                    final boolean hasPaths = pathOverlay != null && pathOverlay.isRenderingEnable()
                            && !getRenderedTrees().isEmpty();
                    final boolean hasAnnotations = annotationOverlay != null
                            && annotationOverlay.isVisible() && annotationOverlay.getCount() > 0;
                    if (!hasPaths && !hasAnnotations) {
                        showViewerMessage("Nothing to hide");
                        return;
                    }
                    pathsWereVisible = pathOverlay != null && pathOverlay.isRenderingEnable();
                    annotationsWereVisible = annotationOverlay != null && annotationOverlay.isVisible();
                    if (pathOverlay != null) pathOverlay.disableRendering(true);
                    if (annotationOverlay != null) annotationOverlay.setVisible(false);
                    hideActive = true;
                }
            };
        }

        Action hideAnnotationsReleaseAction() {
            return new AbstractAction("Restore annotations") {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    if (!hideActive) return;
                    if (pathOverlay != null) pathOverlay.disableRendering(!pathsWereVisible);
                    if (annotationOverlay != null) annotationOverlay.setVisible(annotationsWereVisible);
                    hideActive = false;
                }
            };
        }

        Action toggleVisibilityAction() {
            return new AbstractAction("Show/hide All Annotations") {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    final boolean hasContent =
                            (pathOverlay != null && !getRenderedTrees().isEmpty())
                            || (annotationOverlay != null && annotationOverlay.getCount() > 0);
                    if (!hasContent) {
                        showViewerMessage("No annotations exist.");
                        return;
                    }
                    final boolean hide = (e.getSource() instanceof AbstractButton btn)
                            ? btn.isSelected()
                            : pathOverlay == null || pathOverlay.isRenderingEnable();
                    if (pathOverlay != null) pathOverlay.disableRendering(hide);
                    if (annotationOverlay != null) annotationOverlay.setVisible(!hide);
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
                        final Double newDist = guiUtils.getDouble(
                                "<html>Only annotations within this distance from the cursor will be displayed.<br>"
                                        + "Set it to 0, or cancel this prompt to disable this option.",
                                "Annotations Near Cursor",
                                lastClippingDistance, 0d,
                                Arrays.stream(dims != null ? dims : new long[]{1000}).asDoubleStream().max().orElse(1000d),
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
            return new AbstractAction("Annotations Offset...", IconFactory.buttonIcon(IconFactory.GLYPH.MOVE, 1f)) {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    final SNTPoint offset = guiUtils.getCoordinates("Offsets:", "Annotations Offset (Calibrated Distances)",
                            renderingOptions.canvasOffset, 2);
                    if (offset == null) return;
                    if (offset.getX() == 0 && offset.getY() == 0 && offset.getZ() == 0) {
                        resetCanvasOffsetAction().actionPerformed(e);
                    } else {
                        setCanvasOffset(offset.getX(), offset.getY(), offset.getZ());
                        showViewerMessage("Offset applied");
                    }
                }
            };
        }

        Action resetCanvasOffsetAction() {
            return new AbstractAction() {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    setCanvasOffset(0, 0, 0);
                    showViewerMessage("Offset removed");
                }
            };
        }

        Action pathRenderingOptionsAction() {
            return new AbstractAction("Path Rendering Options", IconFactory.buttonIcon(IconFactory.GLYPH.SLIDERS, 1f)) {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    final HashMap<String, Object> inputs = new HashMap<>();
                    inputs.put("bdv", Bdv.this);
                    SNTUtils.getContext().getService(CommandService.class).run(BdvRenderingOptionsCmd.class, true, inputs);
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
                    final File[] files = guiUtils.getReconstructionFiles(SNTPrefs.lastKnownDir());
                    if (files == null || files.length == 0) return;
                    SNTPrefs.setLastKnownDir(files[0]);
                    add(files);
                }
            };
        }

        Action loadBookmarksAction() {
            return new AbstractAction("Annotate Bookmark Manager Locations", IconFactory.menuIcon(IconFactory.GLYPH.BOOKMARK)) {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    try {
                        final java.util.List<SNTPoint> pos = snt.getUI().getBookmarkManager().getPositions(false);
                        if (pos.isEmpty()) {
                            guiUtils.error("Bookmark Manager is empty.");
                        } else {
                            Color c = guiUtils.getColor("Fallback Color for Untagged Bookmarks", Color.RED, (String[]) null);
                            if (c == null) c = Color.MAGENTA;
                            annotations().setAnnotations(pos, 3.5f * renderingOptions.getMinThickness(), c);
                            showViewerMessage(String.format("%d Bookmarks annotated", pos.size()));
                        }
                    } catch (final NullPointerException ex) {
                        showViewerMessage("Bookmark Manager unavailable");
                    }
                }
            };
        }

        Action syncPathManagerAction() {
            return new AbstractAction("Sync Path Manager Changes", IconFactory.menuIcon(IconFactory.GLYPH.REDO)) {
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
                    if (guiUtils.getConfirmation("Remove all reconstructions? (undoable action)",
                            "Remove All Annotations?")) {
                        clearAllTrees();
                        showViewerMessage("Annotations cleared");
                    }
                }
            };
        }
    }

    /**
     * Adapts a BDV {@code ViewerPanel} to the {@link BigViewerPanel} interface
     * so that {@link Bvv.OverlayRenderer} and {@link Bvv.AnnotationOverlay.AnnRenderer}
     * can be shared between BDV and BVV.
     */
    static BigViewerPanel adapt(final ViewerPanel p) {
        return new BigViewerPanel() {
            @Override public int getDisplayWidth()  { return p.getDisplay().getWidth();  }
            @Override public int getDisplayHeight() { return p.getDisplay().getHeight(); }
            @Override public void addOverlay(final bdv.viewer.OverlayRenderer r)    { p.getDisplay().overlays().add(r); }
            @Override public void removeOverlay(final bdv.viewer.OverlayRenderer r) { p.getDisplay().overlays().remove(r); }
            @Override public void getViewerTransform(final AffineTransform3D t) { p.state().getViewerTransform(t); }
            @Override public void getGlobalMouseCoordinates(final RealPoint pos) { p.getGlobalMouseCoordinates(pos); }
            @Override public void requestRepaint() { p.requestRepaint(); }
        };
    }
}
