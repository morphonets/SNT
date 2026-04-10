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

import bdv.spimdata.XmlIoSpimDataMinimal;
import bdv.tools.InitializeViewerState;
import bdv.tools.brightness.ConverterSetup;
import bdv.util.Prefs;
import bdv.util.AxisOrder;
import mpicbg.spim.data.generic.AbstractSpimData;
import bdv.viewer.Source;
import bdv.viewer.animate.SimilarityTransformAnimator;
import bvv.core.BigVolumeViewer;
import bvv.core.VolumeViewerFrame;
import bvv.core.VolumeViewerPanel;
import bvv.vistools.*;
import ij.ImagePlus;
import net.imagej.ImgPlus;
import net.imagej.axis.Axes;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealPoint;
import net.imglib2.RealRandomAccessible;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.display.imagej.ImageJFunctions;
import net.imglib2.interpolation.randomaccess.NLinearInterpolatorFactory;
import net.imglib2.loops.LoopBuilder;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.view.Views;
import org.jdom2.JDOMException;
import org.scijava.command.CommandService;
import org.scijava.util.ColorRGB;
import sc.fiji.snt.*;
import sc.fiji.snt.BookmarkManager;
import sc.fiji.snt.analysis.graph.DirectedWeightedGraph;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.IconFactory;
import sc.fiji.snt.gui.ScriptInstaller;
import sc.fiji.snt.gui.cmds.BvvRenderingOptionsCmd;
import sc.fiji.snt.util.*;

import javax.swing.*;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.geom.Path2D;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;

/**
 * Support for Big Volume Viewer.
 **/
public class Bvv {

    static { net.imagej.patcher.LegacyInjector.preinit(); } // required for _every_ class that imports ij. classes

    /** The most recently created Bvv instance, for scripting convenience. */
    private static volatile Bvv lastInstance;

    /**
     * Returns the most recently created {@link Bvv} instance, or {@code null}
     * if none has been created yet. This is a convenience accessor for scripts
     * that need to reference the active BVV viewer without passing it around.
     *
     * @return the last initialised Bvv, or {@code null}
     */
    public static Bvv getInstance() {
        return lastInstance;
    }

    /** Auto-incrementing counter for keyframe identifiers (K hotkey). */
    private static final java.util.concurrent.atomic.AtomicInteger keyframeCounter =
            new java.util.concurrent.atomic.AtomicInteger(1);

    private final SNT snt;

    private final BvvOptions options;
    private final Map<String, Tree> renderedTrees;
    private JProgressBar progressBar; // Docked at CardPanel bottom via addToCardPanelBottom().
    private JToggleButton slabAnnotationsToggle; // Slab Annotations toggle injected into BookmarkManager's toolbar
    private final PathRenderingOptions renderingOptions;
    private PathOverlay pathOverlay;
    private AnnotationOverlay annotationOverlay;
    private SceneOverlay sceneOverlay;
    private BigVolumeViewer currentBvv;
    private BvvHandle bvvHandle;
    private double[] cal; // Pixel size of the volume being rendered
    private long[] dims; // Dimensions in pixels of volume being rendered
    private String calUnit; // Physical unit string (e.g. "µm", "pixel") for the volume
    private final List<BvvMultiSource> multiSources = new ArrayList<>(); // grouped multi-channel/multi-image sources
    private BookmarkManager markerManager; // lazily initialized on first use
    private JComponent sceneControlsCard; // Stored for card reordering in CardPanel
    private JComponent sntAnnotationsCard; // Stored for card reordering in CardPanel
    private final Map<AbstractSpimData<?>, String> spimDataFilePaths = new IdentityHashMap<>(); // tracks source file path per dataset
    private final ChannelUnmixingCard unmixingCard = new ChannelUnmixingCard(this); // extracted unmixing UI

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
        options.preferredSize(BvvUtils.DEFAULT_WINDOW_SIZE, BvvUtils.DEFAULT_WINDOW_SIZE);
        options.frameTitle("SNT BVV");
        options.cacheBlockSize(32); // GPU cache tile size
        options.maxCacheSizeInMB(BvvUtils.DEFAULT_CACHE_SIZE_MB);
        options.ditherWidth(1); // dither window. 1 = full resolution; 8 = coarsest resolution
        options.numDitherSamples(8); // no. of nearest neighbors to interpolate from when dithering
        lastInstance = this;
    }

    /**
     * Displays the BVV viewer with the specified image.
     *
     * @param <T>         the numeric type of the image data
     * @param img         the image data to display
     * @param calibration optional calibration values for x, y, z dimensions. If null, defaults to {1, 1, 1}
     * @return the BvvSource representing the displayed image
     */
    @SuppressWarnings("unused")
    public <T extends RealType<T>> BvvSource show(final RandomAccessibleInterval<T> img, final double... calibration) {
        if (img.numDimensions() < 3)
            throw new IllegalArgumentException("BVV requires 3D volumetric data but the image has only "
                    + img.numDimensions() + " dimension(s). 2D images are not supported.");
        cal = (calibration == null) ? new double[]{1, 1, 1} : calibration;
        dims = new long[]{img.dimension(0), img.dimension(1), img.dimension(2)};
        final BvvOptions opt = (bvvHandle != null ? bvv.vistools.Bvv.options().addTo(bvvHandle) : options);
        calUnit = "pixel";
        final BvvStackSource<?> source = showCalibratedSource(img, "SNT Bvv", cal, "pixel", opt);
        if (bvvHandle == null) bvvHandle = source.getBvvHandle();
        attachControlPanel(source);
        multiSources.add(new BvvMultiSource(source));
        return source;
    }

    public <T extends RealType<T>> BvvSource show(final ImgPlus<T> imgPlus) {
        // BVV is a volume viewer: reject images that have no Z dimension.
        // Even multichannel 2D images are unsupported: each hypersliced channel
        // would be a 2D RAI, causing an ArrayIndexOutOfBoundsException inside
        // BVV's VolumeTextureU8.init when it tries to access dimension(2).
        final int zDim = imgPlus.dimensionIndex(Axes.Z);
        if (zDim < 0 || imgPlus.dimension(zDim) <= 1) {
            throw new IllegalArgumentException(
                    "BVV requires 3D volumetric data but '" +
                            (imgPlus.getName() != null ? imgPlus.getName() : "image") + "' appears to be a 2D image.");
        }
        // Multichannel: use native hyperSlice path to avoid the Views.permute →
        // ImageJFunctions.wrap dimension-ordering bug that offsets channels.
        final int chDim = imgPlus.dimensionIndex(Axes.CHANNEL);
        if (chDim >= 0 && imgPlus.dimension(chDim) > 1) {
            final BvvMultiSource multi = showImgPlusMultiChannel(imgPlus);
            return multi.getLeader();
        }
        // Single-channel path
        cal = new double[Math.min(3, imgPlus.numDimensions())];
        dims = new long[]{imgPlus.dimension(0), imgPlus.dimension(1), imgPlus.dimension(2)};
        for (int d = 0; d < cal.length; d++) {
            cal[d] = imgPlus.averageScale(d);
        }
        BvvUtils.checkVolumeSize(dims[0], dims[1], dims[2]);
        final String title = (imgPlus.getName() != null && !imgPlus.getName().isBlank())
                ? imgPlus.getName() : "SNT Bvv";
        // Extract unit from X axis metadata
        final net.imagej.axis.CalibratedAxis xAxis = imgPlus.numDimensions() > 0
                ? imgPlus.axis(0) : null;
        final String unit = (xAxis != null && xAxis.unit() != null) ? xAxis.unit() : "pixel";
        final BvvOptions opt = (bvvHandle != null ? bvv.vistools.Bvv.options().addTo(bvvHandle) : options);
        // Use showCalibratedSource so the unit propagates to the scale bar renderer
        calUnit = BoundingBox.sanitizedUnit(unit);
        final BvvStackSource<?> source = showCalibratedSource(imgPlus, title, cal, unit, opt);
        if (bvvHandle == null) bvvHandle = source.getBvvHandle();
        attachControlPanel(source);
        multiSources.add(new BvvMultiSource(source));
        return source;
    }

    /**
     * Displays a multichannel {@link ImgPlus} by extracting each channel as an
     * independent 3D XYZ source via {@link Views#hyperSlice}, avoiding the
     * dimension-ordering ambiguity of {@link net.imglib2.img.display.imagej.ImageJFunctions#wrap}.
     * Mirrors the logic of {@link #showImagePlusMultiChannel(ImagePlus)}.
     */
    private <T extends RealType<T>> BvvMultiSource showImgPlusMultiChannel(final ImgPlus<T> imgPlus) {
        final int chDim = imgPlus.dimensionIndex(Axes.CHANNEL);
        final int nC = (int) imgPlus.dimension(chDim);
        final int zDimIdx = imgPlus.dimensionIndex(Axes.Z);

        // Spatial calibration from axis metadata (X, Y, Z in that order)
        final double sx = imgPlus.averageScale(imgPlus.dimensionIndex(Axes.X));
        final double sy = imgPlus.averageScale(imgPlus.dimensionIndex(Axes.Y));
        final double sz = zDimIdx >= 0 ? imgPlus.averageScale(zDimIdx) : 1.0;
        cal = new double[]{sx, sy, sz};
        final long nZ = zDimIdx >= 0 ? imgPlus.dimension(zDimIdx) : 1;
        dims = new long[]{imgPlus.dimension(imgPlus.dimensionIndex(Axes.X)),
                imgPlus.dimension(imgPlus.dimensionIndex(Axes.Y)), nZ};
        BvvUtils.checkVolumeSize(dims[0], dims[1], nZ);

        final String imageName = (imgPlus.getName() != null && !imgPlus.getName().isBlank())
                ? imgPlus.getName() : "SNT Bvv";

        // Extract unit from X axis metadata for scale bar
        final net.imagej.axis.CalibratedAxis xAxis = imgPlus.axis(imgPlus.dimensionIndex(Axes.X));
        final String unit = (xAxis != null && xAxis.unit() != null && !xAxis.unit().isBlank())
                ? xAxis.unit() : "pixel";

        // Shared option builder: produces the log and handles prefs/blockSize.
        // No sourceTransform here, calibration is baked into each CalibratedSource.
        final BvvOptions baseOpt = configureBvvOptionsForImage(nZ, nC)
                .axisOrder(AxisOrder.XYZ);

        final boolean hasExistingWindow = bvvHandle != null;
        BvvStackSource<?> leaderSource = null;
        final List<BvvStackSource<?>> followerSources = new ArrayList<>();

        for (int c = 0; c < nC; c++) {
            // Extract this channel as a pure 3D XYZ RAI: no dimension-order ambiguity
            final RandomAccessibleInterval<T> channelRai = Views.hyperSlice(imgPlus, chDim, c);
            final String chTitle = imageName + " (Ch" + (c + 1) + ")";
            final BvvOptions chOpt = (!hasExistingWindow && leaderSource == null)
                    ? baseOpt
                    : baseOpt.addTo(bvvHandle != null ? bvvHandle : leaderSource.getBvvHandle());
            final BvvStackSource<?> chSource = showCalibratedSource(channelRai, chTitle, cal, unit, chOpt);

            if (leaderSource == null) {
                leaderSource = chSource;
                if (!hasExistingWindow) {
                    bvvHandle = chSource.getBvvHandle();
                    final BigVolumeViewer bvv2 = ((BvvHandleFrame) bvvHandle).getBigVolumeViewer();
                    // initTransform using canvas dimensions (not frame size which includes card panel)
                    SwingUtilities.invokeLater(() -> {
                        final int cw = bvv2.getViewer().getDisplay().getWidth();
                        final int ch = bvv2.getViewer().getDisplay().getHeight();
                        InitializeViewerState.initTransform(cw > 0 ? cw : 512, ch > 0 ? ch : 512,
                                false, bvv2.getViewer().state());
                    });
                    final int vpW = bvvHandle.getViewerPanel().getWidth();
                    final double[] cam = BvvUtils.computeCamParams(sx, sy, sz, nZ, Math.max(dims[0], dims[1]), vpW);
                    bvvHandle.getViewerPanel().setCamParams(cam[0], cam[1], cam[2]);
                    if (pathOverlay != null) {
                        pathOverlay.overlayRenderer.dCam = cam[0];
                        pathOverlay.overlayRenderer.nearClip = cam[1];
                        pathOverlay.overlayRenderer.farClip = cam[2];
                    }
                    attachControlPanel(chSource);
                }
            } else {
                followerSources.add(chSource);
            }
        }

        // Initialize brightness from data percentiles for all channels just added.
        // attachControlPanel only runs for the first window; subsequent volumes
        // added to an existing viewer would otherwise keep BVV's 0-65535 default.
        final BigVolumeViewer bvvForInit = ((BvvHandleFrame) bvvHandle).getBigVolumeViewer();
        SwingUtilities.invokeLater(() ->
                InitializeViewerState.initBrightness(0.001, 0.999,
                        bvvForInit.getViewer().state(), bvvForInit.getViewer().getConverterSetups()));

        // Group assignment: must run after all channels are added
        final int groupIdx = multiSources.size();
        final int startIdx = bvvHandle.getViewerPanel().state().getSources().size()
                - (followerSources.size() + 1);
        assignToNamedGroup(imageName, groupIdx, startIdx, followerSources.size() + 1,
                bvvHandle.getViewerPanel());
        final BvvMultiSource multi = new BvvMultiSource(leaderSource, followerSources);
        multiSources.add(multi);
        // Add channel unmixing card for 2+ channel images
        if (multi.size() >= 2 && currentBvv != null) {
            final String mixerTitle = unmixingCard.uniqueTitle(imageName);
            SwingUtilities.invokeLater(() -> {
                final bdv.ui.CardPanel cp = currentBvv.getViewerFrame().getCardPanel();
                cp.addCard(mixerTitle, unmixingCard.build(multi), false);
                // Reorder: move Scene Controls and SNT Annotations after the unmixing card
                if (sceneControlsCard != null) {
                    cp.removeCard("Scene Controls");
                    cp.addCard("Scene Controls", sceneControlsCard, true);
                }
                if (sntAnnotationsCard != null) {
                    cp.removeCard("SNT Annotations");
                    cp.addCard("SNT Annotations", sntAnnotationsCard, true);
                }
            });
        }
        return multi;
    }

    /**
     * Displays a list of {@link ImagePlus} volumes, each as a {@link BvvMultiSource}
     * (multichannel images have their channels grouped and transformed together).
     * All images are added to the same BVV window.
     *
     * @param imps the images to display
     * @return list of {@link BvvMultiSource}, one per image
     */
    private List<BvvMultiSource> showImagePlus(final List<ImagePlus> imps) {
        final List<BvvMultiSource> results = new ArrayList<>();
        for (final ImagePlus imp : imps) {
            showImagePlus(imp); // always appends to multiSources (single- and multichannel paths)
            results.add(multiSources.getLast());
        }
        return results;
    }

    /**
     * Displays a list of {@link ImgPlus} volumes, each as a {@link BvvMultiSource}.
     * Calibration (pixel sizes) is read automatically from each {@link ImgPlus}'s
     * metadata. All images are added to the same BVV window.
     *
     * @param <T>  the numeric type of the image data
     * @param imgs the volumes to display
     * @return list of {@link BvvMultiSource}, one per volume, in input order
     */
    private <T extends RealType<T>> List<BvvMultiSource> showImgPlus(final List<ImgPlus<T>> imgs) {
        final List<BvvMultiSource> results = new ArrayList<>();
        for (final ImgPlus<T> img : imgs) {
            final int sizeBefore = multiSources.size();
            // BvvFunctions.show() always returns BvvStackSource; cast is safe
            final BvvStackSource<?> src = (BvvStackSource<?>) show(img);
            if (multiSources.size() > sizeBefore) {
                // show(img) routed through showImagePlusMultiChannel: group assignment
                // and multiSources entry were already handled: just collect the result
                results.add(multiSources.getLast());
            } else {
                // Single-channel path: manage group and multiSources here
                final int groupIdx = multiSources.size();
                final int srcIdx = bvvHandle.getViewerPanel().state().getSources().size() - 1;
                final String title = img.getName() != null && !img.getName().isBlank() ? img.getName() : "SNT Bvv";
                assignToNamedGroup(title, groupIdx, srcIdx, 1, bvvHandle.getViewerPanel());
                final BvvMultiSource multi = new BvvMultiSource(src);
                multiSources.add(multi);
                results.add(multi);
            }
        }
        return results;
    }

    /**
     * Displays a list of {@link RandomAccessibleInterval} volumes, each as a
     * {@link BvvMultiSource}. All volumes are added to the same BVV window.
     *
     * @param <T>          the numeric type
     * @param imgs         the volumes to display
     * @param calibrations per-image calibration arrays (x, y, z pixel sizes);
     *                     may be {@code null} to use defaults of {1,1,1}
     * @return list of {@link BvvMultiSource}, one per volume
     */
    public <T extends RealType<T>> List<BvvMultiSource> show(
            final List<RandomAccessibleInterval<T>> imgs,
            final List<double[]> calibrations) {
        final List<BvvMultiSource> results = new ArrayList<>();
        for (int i = 0; i < imgs.size(); i++) {
            final double[] thisCal = (calibrations != null && i < calibrations.size()) ? calibrations.get(i) : null;
            this.cal = (thisCal == null) ? new double[]{1, 1, 1} : thisCal;
            this.dims = new long[]{imgs.get(i).dimension(0), imgs.get(i).dimension(1), imgs.get(i).dimension(2)};
            final BvvOptions chOpt = bvvHandle != null
                    ? bvv.vistools.Bvv.options().addTo(bvvHandle) : options;
            @SuppressWarnings({"unchecked", "rawtypes"})
            final BvvStackSource<?> src = showCalibratedSource(
                    (net.imglib2.RandomAccessibleInterval) imgs.get(i), "SNT Bvv " + (i + 1),
                    this.cal, "pixel", chOpt);
            if (bvvHandle == null) bvvHandle = src.getBvvHandle();
            final BvvMultiSource multi = new BvvMultiSource(src);
            multiSources.add(multi);
            results.add(multi);
        }
        return results;
    }

    /**
     * Returns all {@link BvvMultiSource} groups currently managed by this viewer.
     * This includes multichannel images and any grouped multi-image sources.
     *
     * @return unmodifiable list of {@link BvvMultiSource} groups
     */
    @SuppressWarnings("unused")
    public List<BvvMultiSource> getMultiSources() {
        return Collections.unmodifiableList(multiSources);
    }

    /**
     * Script-friendly method for setting per-channel colors
     *
     * @param colorNames color representations (HTML/css values, or hex)
     * @see #setChannelColors(Color...)
     */
    @SuppressWarnings("unused")
    public void setChannelColors(final String... colorNames) {
        setChannelColors(Arrays.stream(colorNames).map(SNTColor::fromString).toArray(Color[]::new));
    }

    /**
     * Sets per-channel colors for a specific source group. Each color is applied
     * to the corresponding channel in order; extra colors are ignored, and channels
     * without a supplied color are left unchanged.
     * Typical script usage:
     * <pre>
     *   def bvv = Bvv.open([reference, moving])
     *   bvv.setChannelColors(bvv.getMultiSources().get(0), Color.RED, Color.GREEN, Color.BLUE)
     * </pre>
     *
     * @param group  the target source group, obtained from {@link #getMultiSources()}
     * @param colors one color per channel, in channel order
     */
    @SuppressWarnings("unused")
    public void setChannelColors(final BvvMultiSource group, final Color... colors) {
        if (colors == null || colors.length == 0 || bvvHandle == null) return;
        applyChannelColors(group, colors);
        repaint();
    }

    private void applyChannelColors(final BvvMultiSource group, final Color... colors) {
        final List<ConverterSetup> setups = getConverterSetups(group);
        for (int i = 0; i < Math.min(colors.length, setups.size()); i++) {
            if (colors[i] != null)
                setups.get(i).setColor(new ARGBType(colors[i].getRGB()));
        }
    }

    /**
     * Sets per-channel colors across all source groups, assigning colors
     * sequentially by global channel order across all groups. For example,
     * with two 3-channel images and colors [R, G, B, C, M, Y], image 1 gets
     * R/G/B and image 2 gets C/M/Y. With a single multichannel image or a
     * dataset where each setup is its own group (e.g. IMS), colors are assigned
     * one-to-one in load order.
     * <p>
     * To apply the same color pattern to every group independently, call
     * {@link #setChannelColors(BvvMultiSource, Color...)} per group.
     * </p>
     * Typical script usage:
     * <pre>
     *   def bvv = Bvv.open(img)
     *   bvv.setChannelColors(Color.RED, Color.GREEN, Color.BLUE)
     * </pre>
     *
     * @param colors colors assigned sequentially across all channels in all groups
     */
    public void setChannelColors(final Color... colors) {
        if (colors == null || colors.length == 0 || bvvHandle == null) return;
        int colorIdx = 0;
        for (final BvvMultiSource group : multiSources) {
            final List<ConverterSetup> setups = getConverterSetups(group);
            for (int i = 0; i < setups.size() && colorIdx < colors.length; i++, colorIdx++) {
                if (colors[colorIdx] != null)
                    setups.get(i).setColor(new ARGBType(colors[colorIdx].getRGB()));
            }
        }
        repaint();
    }

    /**
     * Sets the display range (min/max intensity) for all channels of a specific
     * source group.
     *
     * @param group the target source group, obtained from {@link #getMultiSources()}
     * @param min   the minimum intensity value (maps to black)
     * @param max   the maximum intensity value (maps to full color)
     */
    @SuppressWarnings("unused")
    public void setDisplayRange(final BvvMultiSource group, final double min, final double max) {
        if (bvvHandle == null) return;
        for (final ConverterSetup setup : getConverterSetups(group))
            setup.setDisplayRange(min, max);
        repaint();
    }

    /**
     * Returns the {@link BookmarkManager} used for placing and managing markers
     * in this BVV instance. The manager is created lazily on first call.
     * Use {@link BookmarkManager#showPanel()} to display the floating marker panel,
     * and {@link BookmarkManager#save(File)} to persist markers to CSV.
     *
     * @return the marker manager (never null)
     */
    public BookmarkManager getMarkerManager() {
        if (markerManager == null) {
            markerManager = new BookmarkManager(this);
            slabAnnotationsToggle = new JToggleButton("Slab Annotations");
            slabAnnotationsToggle.setToolTipText("<html>Restrict marker rendering to the active slab.<br>"
                    + "Markers outside the slab bounds are hidden.<br>"
                    + "Only effective when Slab View is active.");
            slabAnnotationsToggle.setEnabled(false);
            slabAnnotationsToggle.addActionListener(e -> {
                renderingOptions.setClipAnnotationsToSlab(slabAnnotationsToggle.isSelected());
                syncOverlays();
            });
            markerManager.addBvvToolbarButton(slabAnnotationsToggle);
        }
        return markerManager;
    }

    /**
     * Returns the {@link ConverterSetup} list for all channels in a
     * {@link BvvMultiSource}, in source order. Uses {@link bvv.vistools.BvvHandle#getConverterSetups()}
     * to look up each setup directly by its {@link bdv.viewer.SourceAndConverter}.
     */
    private List<ConverterSetup> getConverterSetups(final BvvMultiSource group) {
        if (bvvHandle == null) return Collections.emptyList();
        try {
            final bdv.viewer.ConverterSetups converterSetups = bvvHandle.getConverterSetups();
            final List<ConverterSetup> result = new ArrayList<>();
            for (final BvvStackSource<?> src : group.getSources()) {
                for (final bdv.viewer.SourceAndConverter<?> sac : src.getSources()) {
                    final ConverterSetup setup = converterSetups.getConverterSetup(sac);
                    if (setup != null) result.add(setup);
                }
            }
            return result;
        } catch (final Exception ex) {
            SNTUtils.log("Could not retrieve converter setups: " + ex.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Convenience factory: creates a standalone BVV viewer and displays one or more
     * {@link ImagePlus} volumes in one step. Multichannel images have their channels
     * grouped and transformed together.
     * <p>
     * Equivalent to:
     * <pre>
     *   Bvv bvv = new Bvv();
     *   bvv.show(imp); // single image
     *   bvv.show(Arrays.asList(ref, moving)); // multiple images
     * </pre>
     * Typical Groovy/PySNT usage:
     * <pre>
     *   def bvv = Bvv.open(imp)               // single image
     *   def bvv = Bvv.open(reference, moving) // multiple images
     * </pre>
     *
     * @param imps one or more images to display; all are added to the same BVV window
     * @return the fully initialised {@link Bvv} instance
     * @throws IllegalArgumentException if an image type is unsupported (COLOR_256)
     */
    public static Bvv open(final ImagePlus... imps) {
        final Bvv bvv = new Bvv();
        bvv.showImagePlus(Arrays.asList(imps));
        return bvv;
    }

    /**
     * Convenience factory: creates a standalone BVV viewer and displays one or more
     * {@link ImgPlus} volumes in one step. Calibration (pixel sizes) is read
     * automatically from each {@link ImgPlus}'s metadata.
     * <p>
     * Equivalent to:
     * <pre>
     *   Bvv bvv = new Bvv();
     *   bvv.show(img);  // single image
     * </pre>
     * Typical Groovy/PySNT usage:
     * <pre>
     *   def bvv = Bvv.open(imgPlus)
     *   def bvv = Bvv.open(imgPlus1, imgPlus2)
     * </pre>
     *
     * @param <T>  the numeric type of the image data
     * @param imgs one or more volumes to display; all are added to the same BVV window
     * @return the fully initialised {@link Bvv} instance
     */
    @SafeVarargs
    public static <T extends RealType<T>> Bvv open(final ImgPlus<T>... imgs) {
        final Bvv bvv = new Bvv();
        bvv.showImgPlus(Arrays.asList(imgs));
        return bvv;
    }

    /**
     * Script friendly convenience factory: creates a standalone BVV viewer and
     * displays a list of images in one step. Accepts any mix of {@link ImagePlus},
     * {@link ImgPlus}, or {@link RandomAccessibleInterval} objects.
     * <p>
     * Typical Groovy/PySNT usage:
     * <pre>
     *   def bvv = Bvv.open([reference, moving])
     *   def bvv = Bvv.open(myImageList)
     * </pre>
     *
     * @param imgs the images to display; all are added to the same BVV window.
     *             Each element must be an {@link ImagePlus}, {@link ImgPlus},
     *             or {@link RandomAccessibleInterval}
     * @return the fully initialised {@link Bvv} instance
     * @throws IllegalArgumentException if any element is null or of an
     *                                  unsupported type
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Bvv open(final List<?> imgs) {
        final Bvv bvv = new Bvv();
        for (final Object item : imgs) {
            switch (item) {
                case ImagePlus imp -> bvv.showImagePlus(imp); // manages multiSources internally
                case ImgPlus<?> img -> bvv.showImgPlus(Collections.singletonList((ImgPlus) img)); // manages multiSources internally
                case RandomAccessibleInterval<?> rai -> {
                    // show(RAI, cal) doesn't manage multiSources or groups; handle both here
                    final BvvStackSource<?> src = (BvvStackSource<?>) bvv.show((RandomAccessibleInterval) rai, (double[]) null);
                    final int groupIdx = bvv.multiSources.size();
                    final int srcIdx = bvv.bvvHandle.getViewerPanel().state().getSources().size() - 1;
                    bvv.assignToNamedGroup("RAI " + (groupIdx + 1), groupIdx, srcIdx, 1,
                            bvv.bvvHandle.getViewerPanel());
                    bvv.multiSources.add(new BvvMultiSource(src));
                }
                case null -> throw new IllegalArgumentException("Null entries are not supported.");
                default -> throw new IllegalArgumentException("Unsupported type: " + item.getClass().getName());
            }
        }
        return bvv;
    }

    /**
     * Convenience factory: creates a BVV instance tethered to the given {@link SNT}
     * instance and immediately displays its currently loaded image data.
     * <p>
     * Equivalent to:
     * <pre>
     *   Bvv bvv = new Bvv(snt);
     *   bvv.showLoadedData();
     * </pre>
     * Typical Groovy/PySNT usage:
     * <pre>
     *   def bvv = Bvv.open(snt)
     *   bvv.add(snt.getPathAndFillManager().getTrees())
     * </pre>
     *
     * @param snt the {@link SNT} instance providing image data and paths
     * @return the fully initialised {@link Bvv} instance tethered to {@code snt}
     * @throws IllegalArgumentException if no valid image data is loaded in {@code snt}
     */
    public static Bvv open(final SNT snt) {
        final Bvv bvv = new Bvv(snt);
        bvv.showLoadedData();
        return bvv;
    }

    /**
     * Convenience factory: creates a standalone BVV viewer and displays a
     * {@link AbstractSpimData} dataset using BVV's pyramid-aware GPU cache manager.
     * Unlike the {@link ImgPlus}/{@link ImagePlus} paths, this does <em>not</em>
     * attempt to upload the entire volume at once: data is streamed as tiles on
     * demand, making it suitable for out-of-core datasets.
     * <p>
     * The caller is responsible for constructing the {@link AbstractSpimData}
     * object, typically via an XML/HDF5 loader:
     * </p>
     * <pre>
     *   import bdv.spimdata.XmlIoSpimDataMinimal
     *   spimData = new XmlIoSpimDataMinimal().load("/path/to/dataset.xml")
     *   def bvv = Bvv.open(spimData)
     * </pre>
     * or via Bio-Formats/IMS loaders that produce a {@link AbstractSpimData} with
     * a multiresolution pyramid.
     *
     * @param spimData the dataset to display
     * @return the fully initialised {@link Bvv} instance
     */
    public static Bvv open(final AbstractSpimData<?> spimData) {
        final Bvv bvv = new Bvv();
        bvv.show(spimData);
        return bvv;
    }

    /**
     * Convenience factory: opens a file path into a BVV viewer. See
     * {@link #open(String...)} for supported formats and behavior.
     *
     * @param filePathOrUrl the file path or URL to open
     * @return the fully initialised {@link Bvv} instance
     * @throws IllegalArgumentException if the file cannot be opened or an
     *                                  {@code .ims} directory is not writable
     */
    public static Bvv open(final String filePathOrUrl) {
        return open(new String[]{filePathOrUrl});
    }

    /**
     * Convenience factory: opens one or more file paths into the same BVV
     * viewer window, choosing the most appropriate loading strategy per file:
     * <ul>
     *   <li><b>.ims</b> Imaris HDF5: creates a BDV XML sidecar file next to
     *       the {@code .ims} file (using the same base name, e.g.
     *       {@code dataset.xml}), then loads it via {@link XmlIoSpimDataMinimal}.
     *       BVV's pyramid-aware GPU cache manager is used, so the full volume is
     *       never loaded into RAM. If the directory is not writable, an
     *       {@link IllegalArgumentException} is thrown with instructions to
     *       create the XML manually using
     *       {@code Plugins > BigDataViewer > Create XML for Imaris file}.</li>
     *   <li><b>.xml</b> BDV XML/HDF5: loaded directly via
     *       {@link XmlIoSpimDataMinimal} and displayed using BVV's cache
     *       manager. This covers BDV HDF5, BDV N5, and OME-Zarr datasets.</li>
     *   <li><b>anything else</b> delegated to {@link ImgUtils#open(String)}
     *       and displayed via the standard {@link ImgPlus} path. Very large
     *       flat volumes will fail with a descriptive error rather than a GL
     *       crash.</li>
     * </ul>
     * Typical Groovy/PySNT usage:
     * <pre>
     *   def bvv = Bvv.open("/path/to/reference.xml", "/path/to/moving.ims")
     * </pre>
     *
     * @param paths one or more file paths or URLs to open
     * @return the fully initialised {@link Bvv} instance
     * @throws IllegalArgumentException if any file cannot be opened or an
     *                                  {@code .ims} directory is not writable
     */
    public static Bvv open(final String... paths) {
        final Bvv bvv = new Bvv();
        for (final String path : paths) {
            final Object source = resolvePathToSource(path);
            if (source instanceof AbstractSpimData<?> sd) {
                bvv.spimDataFilePaths.put(sd, path);
                bvv.show(sd);
            } else {
                //noinspection unchecked,rawtypes
                bvv.show((ImgPlus) source);
            }
        }
        return bvv;
    }

    /**
     * Resolves a file path to either an {@link AbstractSpimData} (for
     * {@code .ims} and {@code .xml} files) or an {@link ImgPlus} (fallback).
     * Shared by {@link #open(String)} and {@link #open(String...)}.
     *
     * @see sc.fiji.snt.io.SpimDataUtils#resolvePathToSource(String)
     */
    private static Object resolvePathToSource(final String filePathOrUrl) {
        return sc.fiji.snt.io.SpimDataUtils.resolvePathToSource(filePathOrUrl);
    }

    /**
     * Displays a {@link AbstractSpimData} dataset using BVV's pyramid-aware GPU
     * cache manager. Each setup (channel/angle) is added as a source and wrapped
     * in a {@link BvvMultiSource}. Unlike the {@link ImgPlus}/{@link ImagePlus}
     * paths, the full volume is never loaded into RAM or GPU at once.
     *
     * @param spimData the dataset to display
     * @return list of {@link BvvMultiSource}, one per BDV setup, in setup order
     */
    public List<BvvMultiSource> show(final AbstractSpimData<?> spimData) {
        // Use addTo when a window already exists so all sources share the same viewer
        final BvvOptions opts = bvvHandle != null
                ? bvv.vistools.Bvv.options().addTo(bvvHandle) : options;
        final List<BvvStackSource<?>> sources = BvvFunctions.show(spimData, opts);
        if (sources.isEmpty()) return Collections.emptyList();

        // Populate dims/cal from the first setup's metadata so the marker bounds
        // check works for SpimData sources (where these are otherwise never set)
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
            } catch (final Exception ignored) {} // defensive: never break rendering for a metadata hiccup
        }

        // Derive a display name that matches what is shown in the Sources card.
        // Source names come from SpimData metadata (e.g., "MyBrain (Ch1)") which
        // may differ from the file/folder name (e.g., for IMS XML sidecars or
        // OME-Zarr datasets). Use the first source's name, stripping any channel
        // suffix, so the mixer title stays consistent with the Sources panel.
        String datasetName = null;
        try {
            if (!sources.isEmpty()) {
                final var sacs = sources.getFirst().getSources();
                if (sacs != null && !sacs.isEmpty()) {
                    datasetName = sacs.getFirst().getSpimSource().getName();
                    datasetName = datasetName.replaceAll("\\s*\\(Ch\\d+\\)$", "");
                }
            }
        } catch (final Exception ignored) {}
        if (datasetName == null || datasetName.isBlank()) {
            try {
                if (spimData.getBasePathURI() != null) {
                    datasetName = new File(spimData.getBasePathURI()).getName();
                    final int dot = datasetName.lastIndexOf('.');
                    if (dot > 0)
                        datasetName = datasetName.substring(0, dot);
                }
            } catch (final Exception ignored) {}
        }
        if (datasetName == null || datasetName.isBlank())
            datasetName = "Dataset " + (multiSources.size() + 1);

        // Attach control panel on first source of the first dataset
        if (bvvHandle == null) {
            bvvHandle = sources.getFirst().getBvvHandle();
            attachControlPanel(sources.getFirst());
        }

        // Group all setups from this SpimData into one BvvMultiSource, mirroring
        // showImagePlusMultiChannel: leader = first setup, followers = the rest
        final BvvStackSource<?> leaderSource = sources.getFirst();
        final List<BvvStackSource<?>> followerSources = sources.subList(1, sources.size());

        final int groupIdx = multiSources.size();
        final int startIdx = bvvHandle.getViewerPanel().state().getSources().size() - sources.size();
        assignToNamedGroup(datasetName, groupIdx, startIdx, sources.size(),
                bvvHandle.getViewerPanel());

        final BvvMultiSource multi = new BvvMultiSource(leaderSource,
                new ArrayList<>(followerSources));
        multiSources.add(multi);

        // Add channel unmixing card for 2+ channel SpimData sources
        if (multi.size() >= 2 && currentBvv != null) {
            final String mixerTitle = unmixingCard.uniqueTitle(datasetName);
            SwingUtilities.invokeLater(() -> {
                final bdv.ui.CardPanel cp = currentBvv.getViewerFrame().getCardPanel();
                cp.addCard(mixerTitle, unmixingCard.build(multi, spimData), false);
                if (sceneControlsCard != null) {
                    cp.removeCard("Scene Controls");
                    cp.addCard("Scene Controls", sceneControlsCard, true);
                }
                if (sntAnnotationsCard != null) {
                    cp.removeCard("SNT Annotations");
                    cp.addCard("SNT Annotations", sntAnnotationsCard, true);
                }
            });
        }

        return Collections.singletonList(multi);
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

        RandomAccessibleInterval<T> data = (secondary) ? snt.getSecondaryData() : snt.getLoadedData();
        cal = new double[]{snt.getPixelWidth(), snt.getPixelHeight(), snt.getPixelDepth()};
        dims = new long[]{data.dimension(0), data.dimension(1), data.dimension(2)};
        final int maxVal = switch (data.getType().getBitsPerPixel()) {
            case 8 -> BvvUtils.MAX_UINT8;
            case 16 -> BvvUtils.MAX_UINT16;
            default -> BvvUtils.MAX_UINT16; // 32-bit float handled by toUnsignedShortIfFloat, others fallback to 16-bit range
        };
        final double[] minMax = new double[]{0, maxVal}; // safe defaults
        if (secondary && snt.getStatsSecondary().max > 0) {
            minMax[0] = snt.getStatsSecondary().min;
            minMax[1] = snt.getStatsSecondary().max;
        } else if (snt.getStats().max > 0) {
            minMax[0] = snt.getStats().min;
            minMax[1] = snt.getStats().max;
        }
        final String label = String.format("Tracing Data (%s): C%d, T%d",
                (secondary) ? "Secondary layer" : "Main image", snt.getChannel(), snt.getFrame());
        final String unit = snt.getSpacingUnits();
        calUnit = BoundingBox.sanitizedUnit(unit);
        final BvvOptions opt = (bvvHandle != null ? bvv.vistools.Bvv.options().addTo(bvvHandle) : options);
        @SuppressWarnings({"unchecked", "rawtypes"})
        final BvvStackSource<?> source = showCalibratedSource(
                (net.imglib2.RandomAccessibleInterval) ImgUtils.toUnsignedShortIfFloat(data, minMax[0], minMax[1]),
                label, cal, unit, opt);
        source.setDisplayRange(minMax[0], minMax[1]);
        if (bvvHandle == null) bvvHandle = source.getBvvHandle();
        attachControlPanel(source);
        multiSources.add(new BvvMultiSource(source));
        return source;
    }

    private BvvOptions configureBvvOptionsForImage(final ImagePlus imp) {
        return configureBvvOptionsForImage(imp.getNSlices(), imp.getNChannels());
    }

    private BvvOptions configureBvvOptionsForImage(final long nSlices, final int nChannels) {
        final int blockSize = nSlices <= 32 ? 32 : nSlices <= 64 ? 64 : 128;
        // Read render quality preferences (set via Camera Controls options menu)
        final SNTPrefs prefs = (snt != null) ? snt.getPrefs() : null;
        final int renderW = BvvUtils.parseIntPref(prefs, SNTPrefs.BVV_RENDER_WIDTH, BvvUtils.DEFAULT_RENDER_SIZE);
        final int renderH = BvvUtils.parseIntPref(prefs, SNTPrefs.BVV_RENDER_HEIGHT, BvvUtils.DEFAULT_RENDER_SIZE);
        final int maxMillis = BvvUtils.parseIntPref(prefs, SNTPrefs.BVV_MAX_RENDER_MILLIS, BvvUtils.DEFAULT_MAX_RENDER_MILLIS);
        final double maxStep = BvvUtils.parseDoublePref(prefs, SNTPrefs.BVV_MAX_STEP_IN_VOXELS, BvvUtils.DEFAULT_MAX_STEP_IN_VOXELS);
        SNTUtils.log(String.format(
                "BVV: %d slices, %d ch → blockSize=%d renderRes=%dx%d maxMillis=%d maxStep=%.1f",
                nSlices, nChannels, blockSize, renderW, renderH, maxMillis, maxStep));
        return bvv.vistools.Bvv.options()
                .preferredSize(BvvUtils.DEFAULT_WINDOW_SIZE, BvvUtils.DEFAULT_WINDOW_SIZE)
                .frameTitle("SNT BVV")
                .maxCacheSizeInMB(BvvUtils.DEFAULT_CACHE_SIZE_MB)
                .ditherWidth(1)
                .cacheBlockSize(blockSize)
                .numDitherSamples(1)
                .renderWidth(renderW)
                .renderHeight(renderH)
                .maxRenderMillis(maxMillis)
                .maxAllowedStepInVoxels(maxStep);
    }


    @SuppressWarnings("UnusedReturnValue")
    private BvvSource showImagePlus(final ImagePlus imp) {
        if (imp.getNSlices() <= 1) {
            throw new IllegalArgumentException(
                    "BVV requires 3D volumetric data but '" + imp.getTitle() + "' is a 2D image.");
        }
        // ImageJFunctions.wrap* produces dims [W, H, C, Z, T] for hyperstacks,
        // but AxisOrder constants assume [X, Y, Z, C, T]. For multichannel images
        // this mismatch causes BVV to treat Z-slices as channels (60 sources → 61 samplers).
        // Fix: extract each channel as an independent 3D XYZ source.
        if (imp.getNChannels() > 1) {
            final BvvMultiSource multi = showImagePlusMultiChannel(imp);
            return multi.getLeader(); // return BvvSource for callers that just need a handle
            // multi is already stored in multiSources by showImagePlusMultiChannel
        }
        // Single channel: simple 3D or 4D wrap
        if (ImagePlus.GRAY32 == imp.getType())
            ImpUtils.convertTo16bit(imp);
        cal = new double[]{imp.getCalibration().pixelWidth, imp.getCalibration().pixelHeight, imp.getCalibration().pixelDepth};
        dims = new long[]{imp.getWidth(), imp.getHeight(), imp.getNSlices()};
        final String unit = imp.getCalibration().getUnit();
        calUnit = BoundingBox.sanitizedUnit(unit);
        final BvvOptions opt = configureBvvOptionsForImage(imp).axisOrder(BvvUtils.getAxisOrder(imp));
        final net.imglib2.RandomAccessibleInterval<?> wrappedImp = switch (imp.getType()) {
            case ImagePlus.COLOR_256 -> throw new IllegalArgumentException("Unsupported image type (COLOR_256).");
            case ImagePlus.GRAY8 -> ImageJFunctions.wrapByte(imp);
            case ImagePlus.GRAY16 -> ImageJFunctions.wrapShort(imp);
            default -> ImageJFunctions.wrapRGBA(imp);
        };
        @SuppressWarnings({"unchecked", "rawtypes"})
        final BvvStackSource<?> source = showCalibratedSource(
                (net.imglib2.RandomAccessibleInterval) wrappedImp, imp.getTitle(), cal, unit, imp.getNFrames(), opt);
        applyLuts(imp, source);
        if (bvvHandle == null) bvvHandle = source.getBvvHandle();
        final BigVolumeViewer bvv = ((BvvHandleFrame) bvvHandle).getBigVolumeViewer();
        // initTransform must use canvas dimensions (not frame dimensions which include the card panel)
        SwingUtilities.invokeLater(() -> {
            final int cw = bvv.getViewer().getDisplay().getWidth();
            final int ch = bvv.getViewer().getDisplay().getHeight();
            InitializeViewerState.initTransform(cw > 0 ? cw : 512, ch > 0 ? ch : 512,
                    false, bvv.getViewer().state());
        });
        // Compute and apply image-derived camera params
        final double[] cam = computeCamParams(imp);
        bvvHandle.getViewerPanel().setCamParams(cam[0], cam[1], cam[2]);
        // Sync overlayRenderer fields before attachControlPanel so CameraControls
        // reads correct initial values (not the hardwired 2000/1000/1000)
        if (pathOverlay != null) {
            pathOverlay.overlayRenderer.dCam = cam[0];
            pathOverlay.overlayRenderer.nearClip = cam[1];
            pathOverlay.overlayRenderer.farClip = cam[2];
        }
        attachControlPanel(source);
        // Wrap single-channel source as BvvMultiSource so show(List) can track it uniformly
        final int groupIdx = multiSources.size();
        final int srcIdx = bvvHandle.getViewerPanel().state().getSources().size() - 1;
        assignToNamedGroup(imp.getTitle(), groupIdx, srcIdx, 1, bvvHandle.getViewerPanel());
        final BvvMultiSource multi = new BvvMultiSource(source);
        multiSources.add(multi);
        return source;
    }

    private BvvMultiSource showImagePlusMultiChannel(final ImagePlus imp) {
        // Extract each channel as a separate 3D XYZ source to avoid AxisOrder
        // mismatch between ImageJFunctions' [W,H,C,Z,T] layout and BVV's [X,Y,Z,C] expectation
        cal = new double[]{imp.getCalibration().pixelWidth, imp.getCalibration().pixelHeight, imp.getCalibration().pixelDepth};
        dims = new long[]{imp.getWidth(), imp.getHeight(), imp.getNSlices()};
        final String unit = imp.getCalibration().getUnit();
        calUnit = BoundingBox.sanitizedUnit(unit);
        // Each channelImp has nChannels=1; derive axis order from Z and T only
        final AxisOrder channelAxisOrder = imp.getNSlices() == 1 && imp.getNFrames() == 1 ? AxisOrder.XY
                : imp.getNSlices() > 1 && imp.getNFrames() > 1 ? AxisOrder.XYZT
                  : imp.getNSlices() > 1 ? AxisOrder.XYZ
                    : AxisOrder.XYT;
        // No sourceTransform in opts, calibration is baked into each CalibratedSource
        final BvvOptions baseOpt = configureBvvOptionsForImage(imp)
                .axisOrder(channelAxisOrder);
        // Determine once whether we're adding to an existing window
        final boolean hasExistingWindow = bvvHandle != null;
        BvvStackSource<?> leaderSource = null;
        final List<BvvStackSource<?>> followerSources = new ArrayList<>();
        for (int c = 1; c <= imp.getNChannels(); c++) {
            final ImagePlus channelImp = ImpUtils.getChannel(imp, c);
            if (ImagePlus.GRAY32 == channelImp.getType()) ImpUtils.convertTo16bit(channelImp);
            final String title = imp.getTitle() + " (Ch" + c + ")";
            final BvvOptions chOpt = (!hasExistingWindow && leaderSource == null)
                    ? baseOpt
                    : baseOpt.addTo(bvvHandle != null ? bvvHandle : leaderSource.getBvvHandle());
            // Wrap as a RAI and use showCalibratedSource so unit propagates to scale bar
            final net.imglib2.RandomAccessibleInterval<?> wrappedRai = switch (channelImp.getType()) {
                case ImagePlus.GRAY8 -> ImageJFunctions.wrapByte(channelImp);
                case ImagePlus.GRAY16 -> ImageJFunctions.wrapShort(channelImp);
                default -> ImageJFunctions.wrapRGBA(channelImp);
            };
            @SuppressWarnings({"unchecked", "rawtypes"})
            final BvvStackSource<?> chSource = showCalibratedSource(
                    (net.imglib2.RandomAccessibleInterval) wrappedRai, title, cal, unit, imp.getNFrames(), chOpt);
            if (leaderSource == null) {
                leaderSource = chSource;
                if (!hasExistingWindow) {
                    bvvHandle = chSource.getBvvHandle();
                    final BigVolumeViewer bvv2 = ((BvvHandleFrame) bvvHandle).getBigVolumeViewer();
                    SwingUtilities.invokeLater(() ->
                            InitializeViewerState.initTransform(
                                    bvv2.getViewerFrame().getWidth(),
                                    bvv2.getViewerFrame().getHeight(),
                                    false, bvv2.getViewer().state()));
                    final double[] cam = computeCamParams(imp);
                    bvvHandle.getViewerPanel().setCamParams(cam[0], cam[1], cam[2]);
                    if (pathOverlay != null) {
                        pathOverlay.overlayRenderer.dCam = cam[0];
                        pathOverlay.overlayRenderer.nearClip = cam[1];
                        pathOverlay.overlayRenderer.farClip = cam[2];
                    }
                    attachControlPanel(chSource);
                }
            } else {
                followerSources.add(chSource);
            }
            // Apply LUT for this channel
            if (imp.getLuts().length >= c) {
                final int rgb = imp.getLuts()[c - 1].getRGB(255);
                chSource.getConverterSetups().getFirst().setColor(new ARGBType(rgb));
                chSource.getConverterSetups().getFirst().setDisplayRange(
                        imp.getLuts()[c - 1].min, imp.getLuts()[c - 1].max);
            }
        }
        // Group assignment must happen synchronously here: source count is correct
        // immediately after the channel loop, before any other sources are added
        final int groupIdx = multiSources.size();
        final int startIdx = bvvHandle.getViewerPanel().state().getSources().size() - (followerSources.size() + 1);
        assignToNamedGroup(imp.getTitle(), groupIdx, startIdx, followerSources.size() + 1,
                bvvHandle.getViewerPanel());
        final BvvMultiSource multi = new BvvMultiSource(leaderSource, followerSources);
        multiSources.add(multi);
        // Add Channel Unmixing card for 2+ channel images
        if (multi.size() >= 2 && currentBvv != null) {
            final String mixerTitle = unmixingCard.uniqueTitle(imp.getTitle());
            SwingUtilities.invokeLater(() -> {
                final bdv.ui.CardPanel cp = currentBvv.getViewerFrame().getCardPanel();
                cp.addCard(mixerTitle, unmixingCard.build(multi), false);
                // Reorder: move Scene Controls and SNT Annotations after the unmixing card
                if (sceneControlsCard != null) {
                    cp.removeCard("Scene Controls");
                    cp.addCard("Scene Controls", sceneControlsCard, true);
                }
                if (sntAnnotationsCard != null) {
                    cp.removeCard("SNT Annotations");
                    cp.addCard("SNT Annotations", sntAnnotationsCard, true);
                }
            });
        }
        return multi;
    }

    /**
     * Creates a named group in the viewer containing all channels of one image,
     * replacing the auto-generated "group N" label. Uses the modern
     * {@link bdv.viewer.SynchronizedViewerState} API.
     * <p>
     * BVV pre-allocates groups at construction time (one per source group). If a
     * group already exists at {@code groupIdx} we reuse and rename it; otherwise
     * we add a new one.
     * </p>
     */
    private void assignToNamedGroup(final String name,
                                    final int groupIdx,
                                    final int startIdx,
                                    final int numChannels,
                                    final VolumeViewerPanel viewerPanel) {
        if (viewerPanel == null) return;
        try {
            final bdv.viewer.SynchronizedViewerState state = viewerPanel.state();
            final List<bdv.viewer.SourceGroup> groups = state.getGroups();
            final bdv.viewer.SourceGroup handle;
            if (groupIdx < groups.size()) {
                // Reuse the pre-allocated group: clear its existing auto-assignments first
                handle = groups.get(groupIdx);
                state.removeSourcesFromGroup(new ArrayList<>(state.getSourcesInGroup(handle)), handle);
            } else {
                handle = new bdv.viewer.SourceGroup();
                state.addGroup(handle);
            }
            state.setGroupName(handle, name);
            state.setGroupActive(handle, true);
            final List<? extends bdv.viewer.SourceAndConverter<?>> allSources = state.getSources();
            final int endIdx = startIdx + numChannels - 1;
            for (int i = startIdx; i <= endIdx && i < allSources.size(); i++)
                state.addSourceToGroup(allSources.get(i), handle);
            SNTUtils.log("BVV group [" + groupIdx + "] '" + name + "': sources " + startIdx + "-" + endIdx);
        } catch (final Exception ex) {
            SNTUtils.log("BVV group assignment failed: " + ex.getMessage());
        }
        viewerPanel.requestRepaint();
    }

    private void applyLuts(final ImagePlus imp, final BvvStackSource<?> source) {
        if (imp.getLuts().length == imp.getNChannels()) {
            for (int i = 0; i < imp.getNChannels(); i++) {
                source.getConverterSetups().get(i).setColor(new ARGBType(imp.getLuts()[i].getRGB(255)));
                source.getConverterSetups().get(i).setDisplayRange(imp.getLuts()[i].min, imp.getLuts()[i].max);
            }
        }
    }

    private void attachControlPanel(final BvvSource source) {
        final BigVolumeViewer bvv = ((BvvHandleFrame) source.getBvvHandle()).getBigVolumeViewer();
        if (currentBvv != bvv) { // Initialize overlay and add cards only once per viewer instance
            currentBvv = bvv;
            initializePathOverlay(currentBvv);
            initializeAnnotationOverlay(currentBvv);
            sceneOverlay = new SceneOverlay();
            currentBvv.getViewer().getDisplay().overlays().add(sceneOverlay);
            pathOverlay.updatePaths();
            final VolumeViewerFrame bvvFrame = bvv.getViewerFrame();
            final BvvActions actions = new BvvActions(bvv);
            // Transforms toolbar: added first so it appears just below the Groups card, collapsed by default
            bvvFrame.getCardPanel().addCard("Source Transforms", sourceTransformsToolbar(actions), false);
            // Scene controls
            sceneControlsCard =
                    new CameraControls(this, pathOverlay.overlayRenderer).getToolbar(actions);
            bvvFrame.getCardPanel().addCard("Scene Controls", sceneControlsCard, true);
            // SNT toolbar
            sntAnnotationsCard = sntToolbar(actions);
            bvvFrame.getCardPanel().addCard("SNT Annotations", sntAnnotationsCard, true);
            // Progress bar: docked at the bottom of the card panel, below all
            // cards, without a card header.  This avoids the viewport flicker
            // caused by adding the bar to the frame's BorderLayout.SOUTH.
            progressBar = new JProgressBar(0, 100);
            progressBar.setStringPainted(true);
            progressBar.setString("");
            progressBar.setVisible(false);
            addToCardPanelBottom(bvvFrame.getCardPanel(), progressBar);
            // Register shortcuts through BDV's keybindings system so they are
            // handled at the same level as BVV's own shortcuts (e.g., Shift+B).
            // Using Swing's InputMap/ActionMap directly gets shadowed by BDV's
            // input trigger layer.
            final InputMap sntIMap = new InputMap();
            final ActionMap sntAMap = new ActionMap();
            sntIMap.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_M, 0), "snt-add-marker");
            sntAMap.put("snt-add-marker", actions.addMarkerAction());
            sntIMap.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_S,
                    java.awt.event.InputEvent.SHIFT_DOWN_MASK), "snt-bvv-snapshot");
            sntAMap.put("snt-bvv-snapshot", snapshotAction());
            sntIMap.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_H, 0, false),
                    "snt-hide-annotations-press");
            sntIMap.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_H, 0, true),
                    "snt-hide-annotations-release");
            sntAMap.put("snt-hide-annotations-press", actions.hideAnnotationsPressAction());
            sntAMap.put("snt-hide-annotations-release", actions.hideAnnotationsReleaseAction());
            sntIMap.put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_K, 0), "snt-capture-keyframe");
            sntAMap.put("snt-capture-keyframe", new AbstractAction() {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    try {
                        final Keyframe kf = captureKeyframe();
                        final String serialized = kf.toString();
                        final int id = keyframeCounter.getAndIncrement();
                        final String scriptLine = String.format(
                                "kf%02d = new Bvv.Keyframe(\"%s\")", id, serialized);
                        System.out.println(scriptLine);
                        bvv.getViewer().showMessage("Keyframe #" + id + " captured");
                        // Copy the script-ready line to system clipboard
                        final java.awt.datatransfer.StringSelection sel =
                                new java.awt.datatransfer.StringSelection(scriptLine);
                        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
                    } catch (final Exception ex) {
                        SNTUtils.log("Keyframe capture failed: " + ex.getMessage());
                    }
                }
            });
            bvvFrame.getKeybindings().addInputMap("snt", sntIMap);
            bvvFrame.getKeybindings().addActionMap("snt", sntAMap);
            SwingUtilities.invokeLater(bvv::expandAndFocusCardPanel);
            // Ensure the card panel is wide enough to show all controls without clipping.
            // Use the Scene Controls panel's own preferred width since it's the widest,
            // and its GridBagLayout has already computed the correct natural width.
            final int cardPrefW = sceneControlsCard.getMinimumSize().width + 16; // minor padding
            SwingUtilities.invokeLater(() -> {
                final javax.swing.JSplitPane split = bvv.getViewerFrame().getSplitPanel();
                final java.awt.Component cards = split.getRightComponent();
                if (cards == null) return;
                cards.setPreferredSize(new java.awt.Dimension(cardPrefW, cards.getPreferredSize().height));
                final int frameW = bvv.getViewerFrame().getWidth();
                if (frameW > cardPrefW)
                    split.setDividerLocation(frameW - cardPrefW - split.getDividerSize());
                bvv.getViewerFrame().revalidate();
            });
        }
        // Initialize brightness from data percentiles (BVV doesn't do this automatically)
        SwingUtilities.invokeLater(() ->
                InitializeViewerState.initBrightness(0.001, 0.999,
                        bvv.getViewer().state(), bvv.getViewer().getConverterSetups()));
    }

    /**
     * Computes appropriate camera parameters from image physical dimensions.
     * BVV camera params are in units of screen pixels; after initTransform
     * the image is scaled so its largest XY dimension fills the viewport,
     * so we derive depth extent in that space.
     *
     * @param imp the image being displayed
     * @return double[] {dCam, dClipNear, dClipFar}
     */
    double[] computeCamParams(final ImagePlus imp) {
        if (imp == null) return new double[]{BvvUtils.DEFAULT_D_CAM, BvvUtils.DEFAULT_NEAR_CLIP, BvvUtils.DEFAULT_FAR_CLIP};
        final double pw = (cal != null && cal[0] > 0) ? cal[0] : imp.getCalibration().pixelWidth;
        final double ph = (cal != null && cal[1] > 0) ? cal[1] : imp.getCalibration().pixelHeight;
        final double pd = (cal != null && cal[2] > 0) ? cal[2] : imp.getCalibration().pixelDepth;
        final int vpWidth = (bvvHandle != null) ? bvvHandle.getViewerPanel().getWidth() : 0;
        return BvvUtils.computeCamParams(pw, ph, pd, imp.getNSlices(), Math.max(imp.getWidth(), imp.getHeight()), vpWidth);
    }

    /**
     * Builds a {@link sc.fiji.snt.io.SpimDataUtils.CalibratedSource} from a RAI,
     * calibration, unit, and name, then calls
     * {@link BvvFunctions#show(bdv.viewer.Source, int, BvvOptions)} so the unit
     * propagates to the scale bar renderer. The {@code opts} passed in must NOT
     * contain a {@code sourceTransform}, as calibration is already baked into the
     * source.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T extends net.imglib2.type.numeric.RealType<T>> BvvStackSource<?> showCalibratedSource(
            final net.imglib2.RandomAccessibleInterval<T> rai,
            final String name,
            final double[] cal,
            final String unit,
            final BvvOptions opts) {
        final net.imglib2.realtransform.AffineTransform3D t = new net.imglib2.realtransform.AffineTransform3D();
        t.set(cal[0], 0, 0);
        t.set(cal[1], 1, 1);
        t.set(cal[2], 2, 2);
        final sc.fiji.snt.io.SpimDataUtils.CalibratedSource<T> src =
                new sc.fiji.snt.io.SpimDataUtils.CalibratedSource<>(rai, rai.getType(), t, name, cal, unit);
        return BvvFunctions.show((bdv.viewer.Source) src, 1, opts);
    }

    /**
     * Overload for timelapse data. {@code numTimepoints} sets the BVV time slider range.
     * The T axis is assumed to be the last dimension of {@code rai} (as produced by
     * {@code ImageJFunctions.wrapShort} on a single-channel hyperstack): the
     * {@link sc.fiji.snt.io.SpimDataUtils.CalibratedSource} will slice along
     * {@code rai.numDimensions()-1} to return the correct frame for each timepoint.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T extends net.imglib2.type.numeric.RealType<T>> BvvStackSource<?> showCalibratedSource(
            final net.imglib2.RandomAccessibleInterval<T> rai,
            final String name,
            final double[] cal,
            final String unit,
            final int numTimepoints,
            final BvvOptions opts) {
        final net.imglib2.realtransform.AffineTransform3D t = new net.imglib2.realtransform.AffineTransform3D();
        t.set(cal[0], 0, 0);
        t.set(cal[1], 1, 1);
        t.set(cal[2], 2, 2);
        // T is the outermost (last) dimension produced by ImageJFunctions.wrap for a
        // single-channel hyperstack [X, Y, Z, T]. timeDim=-1 for static volumes.
        final int timeDim = numTimepoints > 1 ? rai.numDimensions() - 1 : -1;
        final sc.fiji.snt.io.SpimDataUtils.CalibratedSource<T> src =
                new sc.fiji.snt.io.SpimDataUtils.CalibratedSource<>(rai, rai.getType(), t, name, cal, unit, timeDim);
        return BvvFunctions.show((bdv.viewer.Source) src, Math.max(1, numTimepoints), opts);
    }


    /**
     * Returns an {@link Action} that captures the current scene and saves it as
     * a timestamped PNG to {@code ~/Desktop/SNTsnapshots/}.
     * Runs the capture off the EDT via a {@link javax.swing.SwingWorker} so that
     * the render latch in {@link #screenshotImp()} can complete correctly.
     */
    private Action snapshotAction() {
        return new AbstractAction("BVV Snapshot") {
            @Override
            public void actionPerformed(final ActionEvent e) {
                new SwingWorker<ImagePlus, Void>() {
                    @Override
                    protected ImagePlus doInBackground() {
                        return snapshot("current");
                    }

                    @Override
                    protected void done() {
                        try {
                            final ImagePlus imp = get();
                            if (imp == null) {
                                SNTUtils.log("BVV snapshot: capture returned null");
                                return;
                            }
                            // Save to ~/Desktop/SNTsnapshots/
                            final java.io.File out = getSnapshotFile(null);
                            ImpUtils.save(imp, out.getAbsolutePath());
                            SNTUtils.log("BVV snapshot saved: " + out.getAbsolutePath());
                            if (currentBvv != null)
                                currentBvv.getViewer().showMessage("Snapshot saved: " + out.getName());
                        } catch (final Exception ex) {
                            SNTUtils.log("BVV snapshot error: " + ex.getMessage());
                        }
                    }
                }.execute();
            }
        };
    }

    private File getSnapshotFile(final String directory) throws IOException {
        final java.nio.file.Path dir = java.nio.file.Paths.get(
                (directory != null) ? directory : System.getProperty("user.home"), "Desktop", "SNTsnapshots");
        java.nio.file.Files.createDirectories(dir);
        final String ts = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS")
                .format(new java.util.Date());
        return dir.resolve("BVV-" + ts + ".png").toFile();
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

    /**
     * Creates a {@link JToolBar} whose minimum width is zero, allowing
     * horizontal glue components to absorb all available shrinkage before
     * any buttons are clipped at the panel edge.
     */
    private static JToolBar createToolbar() {
        return new JToolBar() {
            @Override
            public Dimension getMinimumSize() {
                return new Dimension(0, super.getPreferredSize().height);
            }
        };
    }

    private JToolBar sourceTransformsToolbar(final BvvActions actions) {
        final JToolBar toolbar = createToolbar();
        toolbar.add(GuiUtils.Buttons.toolbarButton(actions.exportTransformedSourceAction(multiSources),
                "<html>Apply the manual transform to a source image and export the result as a TIFF.<br>"
                        + "The image is resampled onto the pixel grid of a chosen reference source."));
        toolbar.addSeparator();
        toolbar.add(GuiUtils.Buttons.toolbarButton(actions.saveTransformAction(multiSources),
                "<html>Save manual transform of a source group to an XML file."));
        toolbar.add(GuiUtils.Buttons.toolbarButton(actions.loadTransformAction(multiSources),
                "<html>Load a previously saved manual transform and apply it to a source group."));
        toolbar.addSeparator();
        toolbar.add(Box.createHorizontalGlue());
        toolbar.addSeparator();
        toolbar.add(GuiUtils.Buttons.help("https://gist.github.com/tferr/2f4dbb7c52df154a6e14a1fecd1e785a"));
        return toolbar;
    }

    private JComponent sntToolbar(final BvvActions actions) {
        final JToolBar toolbar = createToolbar();
        toolbar.add(GuiUtils.Buttons.toolbarToggleButton(actions.togggleVisibilityAction(),
                "Show/hide annotations",
                IconFactory.GLYPH.EYE, IconFactory.GLYPH.EYE_SLASH));
        toolbar.add(GuiUtils.Buttons.toolbarToggleButton(actions.togglePersistentAnnotationsAction(),
                "Restrict display of annotations around cursor",
                IconFactory.GLYPH.COMPUTER_MOUSE, IconFactory.GLYPH.COMPUTER_MOUSE));
        toolbar.addSeparator();
        toolbar.add(Box.createHorizontalGlue());
        toolbar.addSeparator();
        toolbar.add(GuiUtils.Buttons.toolbarButton(actions.setCanvasOffsetAction(),
                "Change annotations offset"));
        toolbar.add(GuiUtils.Buttons.undo(actions.resetCanvasOffsetAction()));
        toolbar.addSeparator();
        toolbar.add(Box.createHorizontalGlue());
        toolbar.addSeparator();
        toolbar.add(GuiUtils.Buttons.toolbarButton(actions.PathRenderingOptionsAction(),
                "Set rendering options of annotations"));
        toolbar.addSeparator();
        toolbar.add(Box.createHorizontalGlue());
        toolbar.addSeparator();
        final JToggleButton markerButton = GuiUtils.Buttons.toolbarToggleButton(
                actions.showMarkerManagerAction(),
                "<html>Show/hide the Markers table.<br>"
                        + "Press M in the viewer to place a marker at the cursor position.",
                IconFactory.GLYPH.MARKER, IconFactory.GLYPH.MARKER);
        // Keep button state in sync with frame visibility
        getMarkerManager().getBvvPanel().addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentShown(java.awt.event.ComponentEvent e) {
                markerButton.setSelected(true);
            }

            @Override
            public void componentHidden(java.awt.event.ComponentEvent e) {
                markerButton.setSelected(false);
            }
        });
        toolbar.add(markerButton);
        toolbar.addSeparator();
        final JButton optionsButton = optionsButton(actions);
        toolbar.add(optionsButton);

        return toolbar;
    }

    private JButton optionsButton(final BvvActions actions) {
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
     * Loads reconstruction files and adds them to the viewer with live progress
     * feedback. Files are loaded one at a time on a background thread; the viewer
     * scene is updated after each file so the user sees trees appearing
     * incrementally. The progress bar in the SNT Annotations card tracks progress.
     *
     * @param reconstructionFiles the reconstruction files (SWC, JSON, etc.) to load
     */
    public void add(final java.io.File[] reconstructionFiles) {
        if (reconstructionFiles == null || reconstructionFiles.length == 0) return;
        new SwingWorker<Void, Tree>() {
            @Override
            protected Void doInBackground() {
                final ColorRGB[] colors = SNTColor.getDistinctColors(reconstructionFiles.length);
                if (reconstructionFiles.length > 50) {
                    getRenderingOptions().setUsePathRadius(false);
                    getRenderingOptions().setThicknessMultiplier(.1f);
                    getRenderingOptions().setDisplayRadii(false);
                }
                for (int i = 0; i < reconstructionFiles.length; i++) {
                    final File f = reconstructionFiles[i];
                    updateStatus("Loading " + f.getName() + "...", i, reconstructionFiles.length);
                    try {
                        final Collection<Tree> trees = Tree.listFromFile(f.getAbsolutePath());
                        if (trees != null) {
                            final int finalI = i;
                            trees.forEach(tree -> tree.setColor(colors[finalI]));
                            publish(trees.toArray(new Tree[0]));
                        }
                    } catch (final Exception ex) {
                        SNTUtils.log("BVV: could not load " + f.getName() + ": " + ex.getMessage());
                    }
                }
                return null;
            }

            @Override
            protected void process(final java.util.List<Tree> chunk) {
                // Called on EDT with batched trees: Add without individual repaints,
                // then sync once per chunk for a single repaint per publish() batch.
                for (final Tree t : chunk)
                    addTree(t, false);
                syncOverlays();
            }

            @Override
            protected void done() {
                updateStatus("", 0, 0); // clear / hide progress bar
            }
        }.execute();
    }

    /**
     * Docks a component at the bottom of a {@link bdv.ui.CardPanel}, below all
     * cards, without a card header.  Uses MigLayout's {@code "dock south"}
     * constraint.  If the CardPanel's container layout ever changes away from
     * MigLayout this will degrade gracefully: the component simply won't appear
     * (no crash, no viewport flicker).
     */
    private static void addToCardPanelBottom(final bdv.ui.CardPanel cardPanel, final JComponent comp) {
        final JComponent container = cardPanel.getComponent();
        try {
            container.add(comp, "growx, dock south");
        } catch (final Exception ignored) {
            // Layout manager does not support MigLayout constraints: fall back
            // to default add so the component is at least in the hierarchy
            SNTUtils.log("CardPanel layout is not MigLayout; progress bar may not render correctly");
            container.add(comp);
        }
        container.revalidate();
    }

    /**
     * Updates the progress bar at the bottom of the BVV frame.
     * <ul>
     *   <li>{@code nSteps = 0}: hides the bar</li>
     *   <li>{@code nSteps < 0}: indeterminate mode (animated, no percentage)</li>
     *   <li>{@code nSteps > 0}: determinate mode showing {@code step/nSteps}</li>
     * </ul>
     * Safe to call from any thread.
     *
     * @param message  short status message displayed inside the bar
     * @param step     current step (0-based; ignored in indeterminate mode)
     * @param nSteps   total steps (0 = hide, negative = indeterminate)
     */
    public void updateStatus(final String message, final int step, final int nSteps) {
        SwingUtilities.invokeLater(() -> {
            if (progressBar == null) return;
            if (nSteps == 0) {
                progressBar.setIndeterminate(false);
                progressBar.setVisible(false);
                progressBar.setValue(0);
                progressBar.setString("");
            } else if (nSteps < 0) {
                progressBar.setIndeterminate(true);
                progressBar.setString(message == null ? "" : message);
                progressBar.setVisible(true);
            } else {
                progressBar.setIndeterminate(false);
                progressBar.setMaximum(nSteps);
                progressBar.setValue(step);
                progressBar.setString(message == null ? "" : message);
                progressBar.setVisible(true);
            }
        });
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
     * Resets the viewer to a fit-to-viewport transform, centering the loaded
     * volume in the canvas. Equivalent to the Reset button in Camera Controls.
     * No-op if no volume has been loaded.
     */
    public void resetView() {
        if (currentBvv == null || bvvHandle == null) return;
        final VolumeViewerPanel viewerPanel = currentBvv.getViewer();
        final AffineTransform3D current = new AffineTransform3D();
        viewerPanel.state().getViewerTransform(current);
        final int cw = viewerPanel.getDisplay().getWidth();
        final int ch = viewerPanel.getDisplay().getHeight();
        final AffineTransform3D target;
        if (cal != null && dims != null && cw > 0 && ch > 0) {
            final double px = cal[0] > 0 ? cal[0] : 1;
            final double py = cal[1] > 0 ? cal[1] : 1;
            final double pz = cal[2] > 0 ? cal[2] : 1;
            final double physW = dims[0] * px;
            final double physH = dims[1] * py;
            final double physZ = dims[2] * pz;
            final double scale = Math.min(cw / physW, ch / physH);
            target = new AffineTransform3D();
            target.set(scale, 0, 0);
            target.set(scale, 1, 1);
            target.set(scale, 2, 2);
            target.set(cw / 2.0 - scale * physW / 2.0, 0, 3);
            target.set(ch / 2.0 - scale * physH / 2.0, 1, 3);
            target.set(-scale * physZ / 2.0, 2, 3);
        } else {
            target = new AffineTransform3D();
        }
        viewerPanel.setTransformAnimator(
                new SimilarityTransformAnimator(current, target, 0, 0, 200));
    }

    /**
     * Retrieves the current scene as an image.
     *
     * @return the bitmap image of the current scene
     */
    public ImagePlus snapshot() {
        return snapshot("current");
    }

    /**
     * Retrieves the specified scene view as an image.
     * <b>Must be called from a non-EDT thread</b> (e.g. a script or SwingWorker),
     * otherwise the render latch will time out.
     *
     * @param viewMode the view mode (case-insensitive): {@code "xy"}, {@code "xz"},
     *                 {@code "yz"}, {@code "default"} (fit-to-viewport), or
     *                 {@code "current"} (scene as-is).
     * @return the bitmap image of the scene view, or {@code null} if the viewer
     * is not initialized
     */
    public ImagePlus snapshot(final String viewMode) {
        if (currentBvv == null) return null;
        if (SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("snapshot() must not be called from the EDT");
        final String vMode = (viewMode == null) ? "current" : viewMode.strip().toLowerCase();
        final VolumeViewerPanel viewerPanel = currentBvv.getViewer();

        // Save transform for restore after non-current modes
        final AffineTransform3D savedTransform = new AffineTransform3D();
        viewerPanel.state().getViewerTransform(savedTransform);

        if (!"current".equals(vMode)) {
            // Apply the new transform on the EDT and wait for it to complete
            final AffineTransform3D target = computeAlignTransform(vMode);
            if (target != null) {
                try {
                    SwingUtilities.invokeAndWait(
                            () -> viewerPanel.state().setViewerTransform(target));
                } catch (final Exception ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        final ImagePlus result = screenshotImp();
        if (!"current".equals(vMode)) {
            try {
                SwingUtilities.invokeAndWait(() -> {
                    viewerPanel.state().setViewerTransform(savedTransform);
                    viewerPanel.requestRepaint();
                });
            } catch (final Exception ex) {
                Thread.currentThread().interrupt();
            }
        }
        if (result != null) {
            result.setTitle("BVV-" + vMode);
        }
        return result;
    }

    /**
     * Saves a snapshot of current scene as a PNG image to the specified path.
     *
     * @param filePath the absolute path of the destination file
     * @return true, if file was successfully saved
     */
    public boolean saveSnapshot(final String filePath) {
        try {
            final ImagePlus snapshot = snapshot();
            File outFile;
            if (filePath == null) {
                outFile = getSnapshotFile(null);
            } else if (filePath.toLowerCase().endsWith(".png")) {
                outFile = new File(filePath);
            } else {
                outFile = new File(filePath);
                if (outFile.isDirectory())
                    outFile = getSnapshotFile(filePath);
            }
            outFile.mkdirs();
            ImpUtils.save(snapshot, outFile.getAbsolutePath());
            if (currentBvv != null)
                currentBvv.getViewer().showMessage("Snapshot saved: " + outFile.getName());
            return true;
        } catch (final IllegalArgumentException | IOException e) {
            SNTUtils.error("IOException", e);
            return false;
        }
    }

    /**
     * Captures the current canvas as a {@link ImagePlus}.
     * Waits up to 3 s for the renderer to deliver a fresh frame.
     * Must be called from a non-EDT thread.
     */
    private ImagePlus screenshotImp() {
        if (currentBvv == null) return null;
        final VolumeViewerPanel viewerPanel = currentBvv.getViewer();
        final java.awt.Component canvas = viewerPanel.getDisplayComponent();
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
        final bdv.viewer.TransformListener<AffineTransform3D> renderListener =
                t -> latch.countDown();
        viewerPanel.renderTransformListeners().add(renderListener);
        viewerPanel.requestRepaint();
        try {
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS);
            // Brief pause: renderTransformListener fires when BVV writes the transform,
            // slightly before the GLJPanel has finished painting
            Thread.sleep(50);
        } catch (final InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            viewerPanel.renderTransformListeners().remove(renderListener);
        }
        final int w = canvas.getWidth();
        final int h = canvas.getHeight();
        if (w <= 0 || h <= 0) return null;
        final java.awt.image.BufferedImage bi =
                new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        try {
            SwingUtilities.invokeAndWait(() -> canvas.paint(bi.getGraphics()));
        } catch (final java.lang.reflect.InvocationTargetException | InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        }
        return new ImagePlus("BVV Snapshot", new ij.process.ColorProcessor(bi));
    }

    /**
     * Computes the target {@link AffineTransform3D} for the given plane alignment,
     * or for "default" (fit-to-viewport). Returns {@code null} if calibration is
     * unavailable or the plane string is unrecognized.
     */
    private AffineTransform3D computeAlignTransform(final String vMode) {
        final VolumeViewerPanel viewerPanel = currentBvv.getViewer();
        final int cw = viewerPanel.getDisplay().getWidth();
        final int ch = viewerPanel.getDisplay().getHeight();
        if (cw <= 0 || ch <= 0 || cal == null || dims == null) return null;
        final double px = cal[0] > 0 ? cal[0] : 1;
        final double py = cal[1] > 0 ? cal[1] : 1;
        final double pz = cal[2] > 0 ? cal[2] : 1;
        final double physX = dims[0] * px, physY = dims[1] * py, physZ = dims[2] * pz;
        final AffineTransform3D t = new AffineTransform3D();
        switch (vMode) {
            case "default", "xy" -> {
                final double s = Math.min(cw / physX, ch / physY);
                t.set(s, 0, 0);
                t.set(s, 1, 1);
                t.set(s, 2, 2);
                t.set(cw / 2.0 - s * physX / 2.0, 0, 3);
                t.set(ch / 2.0 - s * physY / 2.0, 1, 3);
                t.set(-s * physZ / 2.0, 2, 3);
            }
            case "xz" -> {
                final double s = Math.min(cw / physX, ch / physZ);
                t.set(s, 0, 0);
                t.set(s, 2, 1);
                t.set(s, 1, 2);
                t.set(cw / 2.0 - s * physX / 2.0, 0, 3);
                t.set(ch / 2.0 - s * physZ / 2.0, 1, 3);
                t.set(-s * physY / 2.0, 2, 3);
            }
            case "yz" -> {
                final double s = Math.min(cw / physZ, ch / physY);
                t.set(s, 2, 0);
                t.set(s, 1, 1);
                t.set(s, 0, 2);
                t.set(cw / 2.0 - s * physZ / 2.0, 0, 3);
                t.set(ch / 2.0 - s * physY / 2.0, 1, 3);
                t.set(-s * physX / 2.0, 2, 3);
            }
            default -> {
                return null;
            }
        }
        return t;
    }

    @SuppressWarnings("unused")
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

    // ── Package-private accessors for ChannelUnmixingCard ──

    double[] getCal() { return cal; }

    String getCalUnit() { return calUnit; }

    BvvHandle getBvvHandle() { return bvvHandle; }

    String getSpimDataFilePath(final AbstractSpimData<?> spimData) {
        String filePath = spimDataFilePaths.getOrDefault(spimData, "");
        if (filePath.isEmpty()) {
            try {
                if (spimData.getBasePathURI() != null) {
                    filePath = new File(spimData.getBasePathURI()).getAbsolutePath();
                }
            } catch (final Exception ignored) {}
        }
        return filePath;
    }



    /**
     * Sets whether paths are rendered as tapered frusta with per-node radii
     * ({@code true}) or as anti-aliased lines ({@code false}).
     * Line rendering is significantly faster for datasets with many paths.
     * Automatically invalidates the overlay cache and repaints.
     *
     * @param display {@code true} for frustum/radius rendering (default),
     *                {@code false} for fast line rendering
     */
    public void setDisplayRadii(final boolean display) {
        renderingOptions.setDisplayRadii(display);
        if (pathOverlay != null) pathOverlay.overlayRenderer.invalidateCache();
        syncOverlays();
    }

    /**
     * Returns whether paths are currently rendered with per-node radii as
     * tapered frusta ({@code true}) or as simple lines ({@code false}).
     *
     * @return {@code true} if frustum/radius rendering is active
     */
    public boolean getDisplayRadii() {
        return renderingOptions.isDisplayRadii();
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
     * Resolves the physical unit string for the currently loaded volume.
     * Checks (in order): calUnit field, SNT spacing units, first source's
     * VoxelDimensions (same source used by the scale bar renderer).
     */
    private String getPhysicalUnit() {
        if (calUnit != null && !calUnit.isBlank() && !"pixel".equalsIgnoreCase(calUnit))
            return calUnit;
        if (snt != null) {
            final String u = snt.getSpacingUnits();
            if (u != null && !u.isBlank() && !"pixel".equalsIgnoreCase(u))
                return BoundingBox.sanitizedUnit(u);
        }
        if (bvvHandle != null) {
            try {
                final var srcs = bvvHandle.getViewerPanel().state().getSources();
                if (!srcs.isEmpty()) {
                    final var vd = srcs.getFirst().getSpimSource().getVoxelDimensions();
                    if (vd != null && vd.unit() != null && !vd.unit().isBlank()
                            && !"pixel".equalsIgnoreCase(vd.unit()))
                        return BoundingBox.sanitizedUnit(vd.unit());
                }
            } catch (final Exception ignored) {
            }
        }
        return "pixel";
    }

    /**
     * Scene Controls card: 4-row layout.
     * <pre>
     * Row 1: Camera Depth   [slider %] [reset]
     * Row 2: Clipping       [range slider %] [reset]   (two thumbs = near + far)
     * Row 3: [Slab toggle]  [thickness spinner] [Z slider] [reset]
     * Row 4: [Reset] [Fit] | [Multibox] [Text] [ScaleBar] | [options]
     * </pre>
     * All percentage values are relative to the Z-extent of the first loaded volume.
     */
    private class CameraControls {

        private final Bvv bvvInstance;
        private final OverlayRenderer overlayRenderer;
        /**
         * Screen-space values at construction time, used by reset actions.
         */
        private final double[] initialCamParams;
        /**
         * Z depth of the initial volume in screen-space units.
         * Slider values (1-1000 integer ticks) map to 1-1000% of this.
         */
        private final double zExtent;

        // Row 1: camera depth slider
        private final JSlider dCamSlider;
        // Row 2: symmetric clipping range slider (low = near, high = far)
        private final JSlider nearSlider;
        private final JSlider farSlider;
        // Suppresses re-entrant slider updates
        private boolean updatingSliders = false;
        /**
         * Slab toggle; null when no calibration is available. Set by getToolbar().
         */
        private JToggleButton slabToggle = null;
        /** Slab Paths toggle; clips overlay rendering to slab Z range. Set by getToolbar(). */
        private JToggleButton slabPathsToggle = null;

        CameraControls(final Bvv bvvInstance, final OverlayRenderer overlayRenderer) {
            this.bvvInstance = bvvInstance;
            this.overlayRenderer = overlayRenderer;
            initialCamParams = new double[]{overlayRenderer.dCam, overlayRenderer.nearClip, overlayRenderer.farClip};
            zExtent = Math.max(1.0, initialCamParams[0] / BvvUtils.CAM_DISTANCE_SCALE);
            dCamSlider = new JSlider(1, 1000, pctToTick(toPct(initialCamParams[0])));
            final String caveat = "<br><i>% of the Z-extent of the initial volume.</i>";
            nearSlider = new JSlider(1, 1000, pctToTick(toPct(initialCamParams[1])));
            farSlider = new JSlider(1, 1000, pctToTick(toPct(initialCamParams[2])));
            dCamSlider.setToolTipText("<html>Camera depth. Increase if the volume appears cut off front-to-back." + caveat);
            nearSlider.setToolTipText("<html>Near clipping depth. Decrease to reveal structures close to the camera." + caveat);
            farSlider.setToolTipText("<html>Far clipping depth. Increase to reveal structures far from the camera." + caveat);
            dCamSlider.addChangeListener(e -> {
                if (!updatingSliders) updateCameraParameters();
            });
            // Enforce near <= far: when near is dragged past far, snap far to near and vice versa
            nearSlider.addChangeListener(e -> {
                if (!updatingSliders) {
                    if (nearSlider.getValue() > farSlider.getValue()) {
                        updatingSliders = true;
                        farSlider.setValue(nearSlider.getValue());
                        updatingSliders = false;
                    }
                    updateCameraParameters();
                }
            });
            farSlider.addChangeListener(e -> {
                if (!updatingSliders) {
                    if (farSlider.getValue() < nearSlider.getValue()) {
                        updatingSliders = true;
                        nearSlider.setValue(farSlider.getValue());
                        updatingSliders = false;
                    }
                    updateCameraParameters();
                }
            });
        }

        private double toPct(final double screenValue) {
            return Math.round((screenValue / zExtent) * 100.0 * 10.0) / 10.0;
        }

        private double toScreen(final double pct) {
            return (pct / 100.0) * zExtent;
        }

        private int pctToTick(final double pct) {
            return Math.max(1, Math.min(1000, (int) Math.round(pct)));
        }

        private double tickToPct(final int tick) { return tick; }

        private void updateCameraParameters() {
            overlayRenderer.dCam = toScreen(tickToPct(dCamSlider.getValue()));
            overlayRenderer.nearClip = toScreen(tickToPct(nearSlider.getValue()));
            overlayRenderer.farClip = toScreen(tickToPct(farSlider.getValue()));
            bvvInstance.getViewerFrame().getViewerPanel().setCamParams(
                    overlayRenderer.dCam, overlayRenderer.nearClip, overlayRenderer.farClip);
            if (bvvInstance.annotationOverlay != null)
                bvvInstance.annotationOverlay.setCamParams(overlayRenderer.nearClip, overlayRenderer.farClip);
            bvvInstance.syncOverlays();
        }

        private void setSliderValues(final int dCamTick, final int nearTick, final int farTick) {
            updatingSliders = true;
            dCamSlider.setValue(dCamTick);
            nearSlider.setValue(nearTick);
            farSlider.setValue(farTick);
            updatingSliders = false;
            updateCameraParameters();
        }

        private void applyZCenter(final VolumeViewerPanel viewerPanel,
                                  final double zCenter) {
            final AffineTransform3D t = new AffineTransform3D();
            viewerPanel.state().getViewerTransform(t);
            // Read current scale from the transform column magnitude. Using a cached
            // screenScale from toolbar-build time would cause a zoom artifact if the
            // user has zoomed since the slab controls were first shown.
            final double currentScale = Math.sqrt(
                    t.get(0, 0) * t.get(0, 0) +
                            t.get(1, 0) * t.get(1, 0) +
                            t.get(2, 0) * t.get(2, 0));
            t.set(-currentScale * zCenter, 2, 3);
            viewerPanel.state().setViewerTransform(t);
            viewerPanel.requestRepaint();
        }

        private void applySlab(final VolumeViewerPanel viewerPanel,
                               final double physZ, final double zCenter, final double thickness) {
            applyZCenter(viewerPanel, zCenter);
            final int tick = pctToTick(toPct(toScreen((thickness / physZ) * 100.0 / 2.0)));
            setSliderValues(dCamSlider.getValue(), tick, tick);
        }

        // Notes on Resetting view:
        // - InitializeViewerState.initTransform() is incompatible with BVV's perspective
        //   projection: it computes a 2D orthographic screen-space transform, but BVV renders
        //   in 3D perspective. The method DOES NOT WORK: tried with frame dimensions, canvas dimensions,
        //   ViewerState snapshots + different timing strategies (invokeLater, ComponentListener,
        //   renderTransformListeners): All attempts produce tx~0.5, ty~0.5 regardless
        // - SimilarityTransformAnimator adds cX/cY to the translation at each interpolation step,
        //   meaning the target transform must NOT already include the screen center offset. Passing
        //   cX=cY=0 lets the animator use the target translation as-is
        // - The approach below works: computes the reset transform directly from image physical
        //   geometry: scale = min(canvasW/physW, canvasH/physH), with translation set to center
        //   the image in the display
        private Action resetViewAction() {
            return new AbstractAction("Reset", IconFactory.menuIcon(IconFactory.GLYPH.BROOM)) {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    final VolumeViewerPanel viewerPanel = bvvInstance.currentBvv.getViewer();
                    final AffineTransform3D current = new AffineTransform3D();
                    viewerPanel.state().getViewerTransform(current);
                    final int cw = viewerPanel.getDisplay().getWidth();
                    final int ch = viewerPanel.getDisplay().getHeight();
                    final AffineTransform3D target;
                    if (cal != null && dims != null && cw > 0 && ch > 0) {
                        final double px = cal[0] > 0 ? cal[0] : 1, py = cal[1] > 0 ? cal[1] : 1, pz = cal[2] > 0 ? cal[2] : 1;
                        final double physW = dims[0] * px, physH = dims[1] * py, physZ = dims[2] * pz;
                        final double scale = Math.min(cw / physW, ch / physH);
                        target = new AffineTransform3D();
                        target.set(scale, 0, 0);
                        target.set(scale, 1, 1);
                        target.set(scale, 2, 2);
                        target.set(cw / 2.0 - scale * physW / 2.0, 0, 3);
                        target.set(ch / 2.0 - scale * physH / 2.0, 1, 3);
                        target.set(-scale * physZ / 2.0, 2, 3);
                        SNTUtils.log("BVV reset: scale=" + scale + " target=" + target);
                    } else {
                        target = new AffineTransform3D();
                    }
                    viewerPanel.setTransformAnimator(new SimilarityTransformAnimator(current, target, 0, 0, 200));
                    SwingUtilities.invokeLater(() -> {
                        setSliderValues(
                                pctToTick(toPct(initialCamParams[0])),
                                pctToTick(toPct(initialCamParams[1])),
                                pctToTick(toPct(initialCamParams[2])));
                        // Deactivate slab mode if active
                        if (slabToggle != null && slabToggle.isSelected())
                            slabToggle.doClick();
                    });
                }
            };
        }

        private Action fitToCurrentSourceAction() {
            return new AbstractAction("Fit Source", IconFactory.menuIcon(IconFactory.GLYPH.EXPAND)) {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    final VolumeViewerPanel viewerPanel = bvvInstance.currentBvv.getViewer();
                    final bdv.viewer.SourceAndConverter<?> current = viewerPanel.state().getCurrentSource();
                    if (current == null) {
                        viewerPanel.showMessage("No source selected");
                        return;
                    }
                    final int cw = viewerPanel.getDisplay().getWidth(), ch = viewerPanel.getDisplay().getHeight();
                    if (cw <= 0 || ch <= 0) return;
                    final AffineTransform3D srcToWorld = new AffineTransform3D();
                    current.getSpimSource().getSourceTransform(0, 0, srcToWorld);
                    final net.imglib2.RandomAccessibleInterval<?> rai = current.getSpimSource().getSource(0, 0);
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
                    final AffineTransform3D currentT = new AffineTransform3D();
                    viewerPanel.state().getViewerTransform(currentT);
                    final AffineTransform3D target = new AffineTransform3D();
                    target.set(scale, 0, 0);
                    target.set(scale, 1, 1);
                    target.set(scale, 2, 2);
                    target.set(cw / 2.0 - scale * (minX + physW / 2.0), 0, 3);
                    target.set(ch / 2.0 - scale * (minY + physH / 2.0), 1, 3);
                    target.set(-scale * (minZ + physZ / 2.0), 2, 3);
                    viewerPanel.setTransformAnimator(new SimilarityTransformAnimator(currentT, target, 0, 0, 300));
                }
            };
        }

        private static Action overlayToggleAction(final String name, final boolean initialState,
                                                  final java.util.function.Consumer<Boolean> onToggle) {
            return new AbstractAction(name) {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    final boolean selected = (e.getSource() instanceof AbstractButton btn)
                            ? btn.isSelected() : !initialState;
                    onToggle.accept(selected);
                }
            };
        }

        // Main toolbar
        public JComponent getToolbar(final BvvActions bvvActions) {

            // Reset actions
            final JButton dCamReset = GuiUtils.Buttons.undo(new AbstractAction() {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    setSliderValues(pctToTick(toPct(initialCamParams[0])),
                            nearSlider.getValue(), farSlider.getValue());
                }
            });
            dCamReset.setToolTipText("Reset camera depth");
            final JButton nearReset = GuiUtils.Buttons.undo(new AbstractAction() {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    setSliderValues(dCamSlider.getValue(),
                            pctToTick(toPct(initialCamParams[1])), farSlider.getValue());
                }
            });
            nearReset.setToolTipText("Reset near clipping");
            final JButton farReset = GuiUtils.Buttons.undo(new AbstractAction() {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    setSliderValues(dCamSlider.getValue(), nearSlider.getValue(),
                            pctToTick(toPct(initialCamParams[2])));
                }
            });
            farReset.setToolTipText("Reset far clipping");


            final JPanel main = new JPanel(new GridBagLayout());
            final GridBagConstraints c = new GridBagConstraints();

            // Camera rows
            final int[] row = {0};
            for (final Object[] r : new Object[][]{
                    {"Camera Depth (%)", dCamSlider, dCamReset},
                    {"Near Clipping (%)", nearSlider, nearReset},
                    {"Far Clipping (%)", farSlider, farReset}}) {
                c.gridy = row[0]++;
                c.gridx = 0;
                c.gridwidth = 1;
                c.fill = GridBagConstraints.NONE;
                c.weightx = 0;
                c.anchor = GridBagConstraints.EAST;
                main.add(new JLabel((String) r[0]), c);
                // col2 intentionally empty
                c.gridx = 2;
                c.fill = GridBagConstraints.HORIZONTAL;
                c.weightx = 1.0;
                c.anchor = GridBagConstraints.CENTER;
                main.add((JSlider) r[1], c);
                c.gridx = 4;
                c.fill = GridBagConstraints.NONE;
                c.weightx = 0;
                c.anchor = GridBagConstraints.WEST;
                main.add(new JLabel(""), c);
                c.gridx = 5;
                c.anchor = GridBagConstraints.WEST;
                main.add((JButton) r[2], c);
            }

            // Slab rows (only when calibrated)
            if (cal != null && dims != null && dims[2] > 0 && cal[2] > 0) {
                final double physZ = dims[2] * cal[2];
                final double zStep = cal[2];
                final double defThick = Math.max(zStep * 5, physZ * 0.05);
                final int nSlices = (int) Math.round(physZ / zStep);
                final String unit = bvvInstance.getPhysicalUnit();

                final JSpinner thickSpinner = GuiUtils.doubleSpinner(defThick, zStep, physZ, zStep, 1);
                thickSpinner.setToolTipText("<html>Thickness of the visible slab (" + unit + ").<br>"
                        + "Controls near/far clipping symmetrically around the current position.");
                thickSpinner.setEnabled(false);

                final JButton thickSpinnerReset = GuiUtils.Buttons.undo(new AbstractAction() {
                    @Override
                    public void actionPerformed(final java.awt.event.ActionEvent e) {
                        thickSpinner.setValue(defThick);
                    }
                });
                thickSpinnerReset.setToolTipText("Reset thickness to default");

                final JSlider posSlider = new JSlider(0, nSlices, nSlices / 2);
                posSlider.setToolTipText("<html>Slab position. Drag to move the slab through the volume.");
                posSlider.setEnabled(false);

                final JButton posSliderReset = GuiUtils.Buttons.undo(new AbstractAction() {
                    @Override
                    public void actionPerformed(final java.awt.event.ActionEvent e) {
                        posSlider.setValue(nSlices / 2);
                    }
                });
                posSliderReset.setToolTipText("Reset position to mid-volume");

                // Position value and unit are separate labels (col4 and col5-6)
                final JLabel posValue = new JLabel(String.format("%.1f ", (nSlices / 2.0) * zStep));
                final JLabel posUnit = new JLabel("");
                posSlider.addChangeListener(ev ->
                        posValue.setText(String.format("%.1f", posSlider.getValue() * zStep)));

                final int[] savedClip = {nearSlider.getValue(), farSlider.getValue()};
                final boolean[] slabOn = {false};

                final VolumeViewerPanel viewerPanel = bvvInstance.currentBvv.getViewer();

                // Re-sync posSlider to actual viewer Z on mouse press.  The viewer
                // transform can drift (e.g. scroll-wheel zoom/pan) while slab is on,
                // causing the slider to be out-of-date.  Reading the transform when the
                // user first touches the slider keeps them in sync with a one-time snap.
                posSlider.addMouseListener(new java.awt.event.MouseAdapter() {
                    @Override
                    public void mousePressed(final java.awt.event.MouseEvent e) {
                        if (!slabOn[0]) return;
                        final AffineTransform3D t = new AffineTransform3D();
                        viewerPanel.state().getViewerTransform(t);
                        final double scale = Math.sqrt(
                                t.get(0, 0) * t.get(0, 0) +
                                        t.get(1, 0) * t.get(1, 0) +
                                        t.get(2, 0) * t.get(2, 0));
                        if (scale <= 0) return;
                        final double zCenter = -t.get(2, 3) / scale;
                        final int tick = Math.max(0, Math.min(nSlices, (int) Math.round(zCenter / zStep)));
                        if (tick != posSlider.getValue()) {
                            posSlider.setValue(tick);
                        }
                    }
                });

                // "Thickness" label + spinner share col3, label WEST, spinner CENTER
                final JPanel thickPanel = new JPanel(new java.awt.BorderLayout(4, 0));
                thickPanel.setOpaque(false);
                // "Position" label + slider + value all share col3
                final JPanel posPanel = new JPanel(new java.awt.BorderLayout(4, 0));
                posPanel.setOpaque(false);

                slabToggle = new JToggleButton("Slab View"); // assign to field so resetViewAction can deactivate it
                //GuiUtils.Buttons.applyToolbarProps(slabToggle);
                slabToggle.setToolTipText("<html>Enable slab mode.<br>"
                        + "Restricts the visible scene to a thin slab at the selected position.<br>"
                        + "Disables manual near/far clipping while active.");

                // Row4: Col1=toggle  Col2=empty  Col3=[Thickness|spinner](fill)  Col5-6=unit
                c.gridy = row[0]++;
                c.gridx = 0;
                c.gridwidth = 1;
                c.fill = GridBagConstraints.NONE;
                c.weightx = 0;
                c.anchor = GridBagConstraints.EAST;
                main.add(slabToggle, c);

                final JLabel thickLabel = new JLabel(String.format("   Thickness (%s):", unit), JLabel.RIGHT);
                final JLabel posLabel = new JLabel(String.format("   Position (%s):", unit), JLabel.RIGHT);
                // Force both labels to the same preferred width so spinners/sliders left-align.
                ensureSameWidth(thickLabel, posLabel);
                thickPanel.add(thickLabel, java.awt.BorderLayout.WEST);
                thickPanel.add(thickSpinner, java.awt.BorderLayout.CENTER);
                c.gridx = 2;
                c.fill = GridBagConstraints.HORIZONTAL;
                c.weightx = 1.0;
                c.anchor = GridBagConstraints.CENTER;
                main.add(thickPanel, c);
                c.gridx = 4;
                c.gridwidth = 2;
                c.fill = GridBagConstraints.NONE;
                c.weightx = 0;
                c.anchor = GridBagConstraints.WEST;
                main.add(thickSpinnerReset, c);
                c.gridwidth = 1;

                // Row5: Col1=[Slab Paths]  Col2=empty  Col3=[Position|slider|value](fill)  Col5-6=reset
                c.gridy = row[0]++;

                slabPathsToggle = new JToggleButton("Slab Paths");
                slabPathsToggle.setToolTipText("<html>Restrict annotation rendering to the active slab.<br>"
                        + "Paths outside the slab bounds are hidden, improving performance on large datasets.<br>"
                        + "Only effective when Slab View is active.");
                slabPathsToggle.setEnabled(false); // enabled only when slab is active
                c.gridx = 0;
                c.gridwidth = 1;
                c.fill = GridBagConstraints.NONE;
                c.weightx = 0;
                c.anchor = GridBagConstraints.EAST;
                ensureSameWidth(slabToggle, slabPathsToggle);
                main.add(slabPathsToggle, c);

                posPanel.add(posLabel, java.awt.BorderLayout.WEST);
                posPanel.add(posSlider, java.awt.BorderLayout.CENTER);
                posPanel.add(posValue, java.awt.BorderLayout.EAST);
                c.gridx = 2;
                c.fill = GridBagConstraints.HORIZONTAL;
                c.weightx = 1.0;
                c.anchor = GridBagConstraints.CENTER;
                main.add(posPanel, c);
                c.gridx = 4;
                c.gridwidth = 2;
                c.fill = GridBagConstraints.NONE;
                c.weightx = 0;
                c.anchor = GridBagConstraints.WEST;
                main.add(posSliderReset, c);
                c.gridwidth = 1;

                slabToggle.addActionListener(ev -> {
                    slabOn[0] = slabToggle.isSelected();
                    GuiUtils.enableComponents(thickPanel, slabOn[0]);
                    GuiUtils.enableComponents(posPanel, slabOn[0]);
                    posUnit.setEnabled(slabOn[0]);
                    posValue.setEnabled(slabOn[0]);
                    nearSlider.setEnabled(!slabOn[0]);
                    farSlider.setEnabled(!slabOn[0]);
                    if (slabOn[0]) {
                        savedClip[0] = nearSlider.getValue();
                        savedClip[1] = farSlider.getValue();
                        final double zCenter = posSlider.getValue() * zStep;
                        final double halfThick = ((Number) thickSpinner.getValue()).doubleValue() / 2.0;
                        applySlab(viewerPanel, physZ, zCenter, halfThick * 2.0);
                        renderingOptions.setSlabZBounds(zCenter - halfThick, zCenter + halfThick);
                        slabPathsToggle.setEnabled(true);
                        if (sceneOverlay != null) sceneOverlay.showSlabPlanes = true;
                        if (slabAnnotationsToggle != null) slabAnnotationsToggle.setEnabled(true);
                    } else {
                        setSliderValues(dCamSlider.getValue(), savedClip[0], savedClip[1]);
                        applyZCenter(viewerPanel, physZ / 2);
                        renderingOptions.clearSlabZBounds();
                        if (sceneOverlay != null) sceneOverlay.showSlabPlanes = false;
                        // Deactivate and disable Slab Paths + Slab Annotations when slab is turned off
                        slabPathsToggle.setSelected(false);
                        slabPathsToggle.setEnabled(false);
                        if (slabAnnotationsToggle != null) {
                            slabAnnotationsToggle.setSelected(false);
                            slabAnnotationsToggle.setEnabled(false);
                        }
                        renderingOptions.setClipPathsToSlab(false);
                        renderingOptions.setClipAnnotationsToSlab(false);
                    }
                    overlayRenderer.invalidateCache();
                    syncOverlays();
                });

                final javax.swing.event.ChangeListener slabListener = ev -> {
                    if (slabOn[0]) {
                        final double zCenter = posSlider.getValue() * zStep;
                        final double halfThick = ((Number) thickSpinner.getValue()).doubleValue() / 2.0;
                        applySlab(viewerPanel, physZ, zCenter, halfThick * 2.0);
                        renderingOptions.setSlabZBounds(zCenter - halfThick, zCenter + halfThick);
                        overlayRenderer.invalidateCache();
                        bvvInstance.repaint(); // refresh slab plane overlays
                        syncOverlays();
                    }
                };
                posSlider.addChangeListener(slabListener);
                thickSpinner.addChangeListener(slabListener);

                slabPathsToggle.addActionListener(ev -> {
                    final boolean clip = slabPathsToggle.isSelected();
                    renderingOptions.setClipPathsToSlab(clip);
                    overlayRenderer.invalidateCache();
                    syncOverlays();
                });

                GuiUtils.enableComponents(thickPanel, false); // slab disabled by default
                GuiUtils.enableComponents(posPanel, false); // slab disabled at by default
            }

            // Icon toolbar spans all 6 columns
            final JToolBar iconBar = buildIconToolbar(bvvActions);
            c.gridx = 0;
            c.gridy = row[0];
            c.gridwidth = 6;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.weightx = 1.0;
            c.anchor = GridBagConstraints.CENTER;
            c.insets = new Insets(4, 0, 0, 0);
            main.add(iconBar, c);

            return main;
        }

        private void ensureSameWidth(final JComponent c1, final JComponent c2) {
            final int pW = Math.max(c1.getPreferredSize().width, c2.getPreferredSize().width);
            final Dimension pDim = new Dimension(pW, c1.getPreferredSize().height);
            c1.setPreferredSize(pDim);
            c2.setPreferredSize(pDim);
            final int mW = Math.max(c1.getMinimumSize().width, c2.getMinimumSize().width);
            final Dimension mDim = new Dimension(mW, c1.getMinimumSize().height);
            c1.setMinimumSize(mDim);
            c2.setMinimumSize(mDim);
        }

        /** Builds the icon toolbar row (reset/fit/overlay toggles/options). */
        private JToolBar buildIconToolbar(final BvvActions bvvActions) {
            final JToolBar bar = createToolbar();
            bar.add(GuiUtils.Buttons.toolbarButton(resetViewAction(), "Reset view to startup state"));
            bar.add(GuiUtils.Buttons.toolbarButton(fitToCurrentSourceAction(), "Fit view to the current (selected) source"));
            bar.addSeparator();
            bar.add(Box.createHorizontalGlue());
            bar.addSeparator();
            final JToggleButton multiboxToggle = GuiUtils.Buttons.toolbarToggleButton(
                    overlayToggleAction("Bounding Boxes", Prefs.showMultibox(),
                            show -> {
                                Prefs.showMultibox(show);
                                bvvInstance.repaint();
                            }),
                    "Show/hide minimap", IconFactory.GLYPH.NAVIGATE, IconFactory.GLYPH.NAVIGATE);
            multiboxToggle.setSelected(Prefs.showMultibox());
            bar.add(multiboxToggle);
            final JToggleButton textToggle = GuiUtils.Buttons.toolbarToggleButton(
                    overlayToggleAction("Text Overlay", Prefs.showTextOverlay(),
                            show -> {
                                Prefs.showTextOverlay(show);
                                bvvInstance.repaint();
                            }),
                    "Show/hide text overlay", IconFactory.GLYPH.TEXT, IconFactory.GLYPH.TEXT);
            textToggle.setSelected(Prefs.showTextOverlay());
            bar.add(textToggle);
            final JToggleButton scaleBarToggle = GuiUtils.Buttons.toolbarToggleButton(
                    overlayToggleAction("Scale Bar", Prefs.showScaleBar(),
                            show -> {
                                Prefs.showScaleBar(show);
                                bvvInstance.repaint();
                            }),
                    "Show/hide scale bar", IconFactory.GLYPH.RULER, IconFactory.GLYPH.RULER);
            scaleBarToggle.setSelected(Prefs.showScaleBar());
            bar.add(scaleBarToggle);
            final JToggleButton axesToggle = GuiUtils.Buttons.toolbarToggleButton(
                    overlayToggleAction("Axes", false,
                            show -> {
                                if (sceneOverlay != null) {
                                    sceneOverlay.showAxes = show;
                                    bvvInstance.repaint();
                                }
                            }),
                    "Show/hide coordinate axes at the volume origin.\n" +
                            "X: Red; Y: Green; Z: Blue",
                    IconFactory.GLYPH.CHART_LINE, IconFactory.GLYPH.CHART_LINE);
            axesToggle.setSelected(false);
            bar.add(axesToggle);
            final JToggleButton boxToggle = GuiUtils.Buttons.toolbarToggleButton(
                    overlayToggleAction("Volume Box", false,
                            show -> {
                                if (sceneOverlay != null) {
                                    sceneOverlay.showBox = show;
                                    bvvInstance.repaint();
                                }
                            }),
                    "Show/hide bounding box around all loaded volumes",
                    IconFactory.GLYPH.CUBE, IconFactory.GLYPH.CUBE);
            boxToggle.setSelected(false);
            bar.add(boxToggle);
            bar.addSeparator();
            bar.add(Box.createHorizontalGlue());
            bar.addSeparator();
            bar.add(optionsButton(bvvActions));
            return bar;
        }

        private JButton optionsButton(final BvvActions actions) {
            final JPopupMenu menu = new JPopupMenu();
            final JButton oButton = GuiUtils.Buttons.OptionsButton(IconFactory.GLYPH.OPTIONS, 1f, menu);
            addSeparator(menu, IconFactory.GLYPH.GAUGE, "Render Quality");
            // Render quality presets (renderWidth/Height/maxRenderMillis require restart)
            final ButtonGroup qualityGroup = new ButtonGroup();
            final String[][] presets = {
                    {"Low  (256×256, 15ms)", "256", "256", "15",
                            "<html>Lowest quality, fastest rendering.<br>Best for quick navigation on slow GPUs."},
                    {"Med  (512×512, 30ms)", "512", "512", "30",
                            "<html>Balanced quality and performance.<br>Suitable for most systems (default)."},
                    {"High (768×768, 60ms)", "768", "768", "60",
                            "<html>Higher quality rendering with more detail.<br>Recommended for analysis and screenshots."},
                    {"Max (1024×1024, 100ms)", "1024", "1024", "100",
                            "<html>Maximum quality, slowest rendering.<br>Best for publication-quality screenshots on high-end GPUs."}
            };
            final SNTPrefs prefs = (bvvInstance.snt != null) ? bvvInstance.snt.getPrefs() : null;
            final String defSize = String.valueOf(BvvUtils.DEFAULT_RENDER_SIZE);
            final String curW = (prefs != null) ? prefs.getTemp(SNTPrefs.BVV_RENDER_WIDTH, defSize) : defSize;
            for (final String[] preset : presets) {
                final JRadioButtonMenuItem rbmi = new JRadioButtonMenuItem(preset[0], preset[1].equals(curW));
                rbmi.setToolTipText(preset[4] + "<br><i>Takes effect on next open.</i>");
                qualityGroup.add(rbmi);
                menu.add(rbmi);
                rbmi.addActionListener(e -> {
                    if (prefs != null) {
                        prefs.setTemp(SNTPrefs.BVV_RENDER_WIDTH, preset[1]);
                        prefs.setTemp(SNTPrefs.BVV_RENDER_HEIGHT, preset[2]);
                        prefs.setTemp(SNTPrefs.BVV_MAX_RENDER_MILLIS, preset[3]);
                    }
                    bvvInstance.getViewerFrame().getViewerPanel().showMessage(
                            "Render quality: " + preset[0] + " (takes effect on next open)");
                });
            }
            // maxAllowedStepInVoxels: takes effect immediately
            addSeparator(menu, IconFactory.GLYPH.STAIRS, "Ray-Marching Step");
            final double curStep = BvvUtils.parseDoublePref(prefs, SNTPrefs.BVV_MAX_STEP_IN_VOXELS, 1.0);
            final JSpinner stepSpinner = GuiUtils.doubleSpinner(curStep, 0.1, 8.0, 0.5, 1);
            stepSpinner.setToolTipText("<html>Ray-marching step size in voxels.<br>"
                    + "Smaller = higher quality, slower. Larger = faster, lower quality.<br>Default: 1.0");
            stepSpinner.addChangeListener(e -> {
                final double step = ((Number) stepSpinner.getValue()).doubleValue();
                bvvInstance.getViewerFrame().getViewerPanel().setMaxAllowedStepInVoxels(step);
                if (prefs != null) prefs.setTemp(SNTPrefs.BVV_MAX_STEP_IN_VOXELS, String.valueOf(step));
                bvvInstance.getViewerFrame().getViewerPanel().requestRepaint();
            });
            menu.add(stepSpinner);
            addSeparator(menu, IconFactory.GLYPH.CLOCK_ROTATE_LEFT, "Restore View");
            menu.add(new JMenuItem(actions.loadSettingsAction()));
            menu.add(new JMenuItem(actions.saveSettingsAction()));
            addSeparator(menu, IconFactory.GLYPH.INFO, "Help");
            menu.add(new JMenuItem(actions.showHelpAction()));
            menu.add(new JMenuItem(actions.showMovieHelpAction()));
            return oButton;
        }
    }

    private static void addSeparator(final JPopupMenu menu, final IconFactory.GLYPH glyph, final String header) {
        if (menu.getComponentCount() > 0)
            menu.addSeparator();
        final JMenuItem sep = new JMenuItem(header);
        sep.setEnabled(false);
        sep.setIcon(IconFactory.menuIcon(glyph, GuiUtils.getDisabledComponentColor()));
        sep.setDisabledIcon(IconFactory.menuIcon(glyph, GuiUtils.getDisabledComponentColor()));
        menu.add(sep);
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
        public Color fallbackColor = Color.MAGENTA;
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
     * Java2D overlay that draws coordinate axes and a wire bounding box
     * using the same perspective projection as {@link AnnotationOverlay}.
     * Avoids OpenGL entirely to avoid depth-buffer contamination/ GL state issues.
     */
    private class SceneOverlay implements bdv.viewer.OverlayRenderer {

        volatile boolean showAxes = false;
        volatile boolean showBox = false;
        volatile boolean showSlabPlanes = false;

        private int canvasW, canvasH;

        @Override
        public void setCanvasSize(final int w, final int h) {
            canvasW = w;
            canvasH = h;
        }

        @Override
        public void drawOverlays(final java.awt.Graphics g) {
            if (!showAxes && !showBox) return;
            if (canvasW == 0 || canvasH == 0) return;

            // Get current viewer transform and camera depth
            final VolumeViewerPanel viewer = currentBvv.getViewer();
            final AffineTransform3D t = new AffineTransform3D();
            viewer.state().getViewerTransform(t);
            final double dCam = pathOverlay.overlayRenderer.dCam;
            final double cx = canvasW / 2.0;
            final double cy = canvasH / 2.0;

            final java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setStroke(new java.awt.BasicStroke(1.5f));

            // World → screen projection (same formula as AnnotationOverlay)
            final java.util.function.Function<double[], double[]> project = world -> {
                final double[] v = new double[3];
                t.apply(world, v);
                final double pf = dCam / (dCam + v[2]);
                return new double[]{cx + (v[0] - cx) * pf, cy + (v[1] - cy) * pf, pf};
            };

            // Compute world-space scene bounds from all sources
            final float[] b = sceneBounds();
            if (b == null) {
                g2.dispose();
                return;
            }
            final double x0 = b[0], y0 = b[1], z0 = b[2], x1 = b[3], y1 = b[4], z1 = b[5];

            if (showAxes) {
                final float axisLen = 0.20f * (float) Math.min(x1 - x0, Math.min(y1 - y0, z1 - z0));
                drawLine3D(g2, project, new double[]{x0, y0, z0}, new double[]{x0 + axisLen, y0, z0}, new java.awt.Color(220, 60, 60));
                drawLine3D(g2, project, new double[]{x0, y0, z0}, new double[]{x0, y0 + axisLen, z0}, new java.awt.Color(60, 200, 60));
                drawLine3D(g2, project, new double[]{x0, y0, z0}, new double[]{x0, y0, z0 + axisLen}, new java.awt.Color(80, 120, 255));
            }

            if (showBox) {
                g2.setColor(new java.awt.Color(255, 255, 255, 180));
                // 12 edges of the bounding box
                final double[][] corners = {
                        {x0, y0, z0}, {x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0},
                        {x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}
                };
                final int[][] edges = {
                        {0, 1}, {1, 2}, {2, 3}, {3, 0}, // bottom face
                        {4, 5}, {5, 6}, {6, 7}, {7, 4}, // top face
                        {0, 4}, {1, 5}, {2, 6}, {3, 7}  // verticals
                };
                for (final int[] e : edges)
                    drawLine3D(g2, project, corners[e[0]], corners[e[1]], null);
            }

            if (showBox && showSlabPlanes) {
                // Draw the slab parallelepiped in screen space.
                // Working in viewer-space XY then re-projecting at clip depths magnifies
                // incorrectly because perspective pf differs between the volume depth and
                // the clip plane depths. Correct approach: derive the 2D screen AABB from
                // the already-projected volume box, then build the slab faces within it.
                // This is always zoom-responsive and always bounded by the volume box.
                final double nc = pathOverlay.overlayRenderer.nearClip;
                final double fc = pathOverlay.overlayRenderer.farClip;

                // Screen AABB of the volume bounding box (8 corners already projected)
                double sxMin = Double.MAX_VALUE, sxMax = -Double.MAX_VALUE;
                double syMin = Double.MAX_VALUE, syMax = -Double.MAX_VALUE;
                final double[] wc2 = new double[3];
                for (int i = 0; i < 8; i++) {
                    wc2[0] = (i & 1) == 0 ? x0 : x1;
                    wc2[1] = (i & 2) == 0 ? y0 : y1;
                    wc2[2] = (i & 4) == 0 ? z0 : z1;
                    final double[] s = project.apply(wc2);
                    if (s[2] <= 0) continue;
                    if (s[0] < sxMin) sxMin = s[0];
                    if (s[0] > sxMax) sxMax = s[0];
                    if (s[1] < syMin) syMin = s[1];
                    if (s[1] > syMax) syMax = s[1];
                }
                if (sxMin == Double.MAX_VALUE) { /* all behind camera */ }
                else {
                    // Perspective factor of the volume center at the current zoom/position.
                    // pfVol = 1 is wrong when the volume is not at viewerZ=0 (e.g. zoomed out).
                    final double[] volCentre = {(x0 + x1) / 2.0, (y0 + y1) / 2.0, (z0 + z1) / 2.0};
                    final double[] vcProj = new double[3];
                    t.apply(volCentre, vcProj);
                    final double pfVol  = dCam / (dCam + vcProj[2]);
                    final double pfNear = dCam / (dCam - nc);  // viewerZ = -nc
                    final double pfFar  = dCam / (dCam + fc);  // viewerZ = +fc

                    // Helper: scale screen AABB corners by a perspective ratio around center
                    // (corner = centre + (corner-centre) * ratio)
                    final double ratioN = pfNear / pfVol;
                    final double ratioF = pfFar  / pfVol;

                    // 8 screen-space corners: 0-3 near face, 4-7 far face
                    final double[][] sc = {
                            {cx + (sxMin-cx)*ratioN, cy + (syMin-cy)*ratioN},  // 0
                            {cx + (sxMax-cx)*ratioN, cy + (syMin-cy)*ratioN},  // 1
                            {cx + (sxMax-cx)*ratioN, cy + (syMax-cy)*ratioN},  // 2
                            {cx + (sxMin-cx)*ratioN, cy + (syMax-cy)*ratioN},  // 3
                            {cx + (sxMin-cx)*ratioF, cy + (syMin-cy)*ratioF},  // 4
                            {cx + (sxMax-cx)*ratioF, cy + (syMin-cy)*ratioF},  // 5
                            {cx + (sxMax-cx)*ratioF, cy + (syMax-cy)*ratioF},  // 6
                            {cx + (sxMin-cx)*ratioF, cy + (syMax-cy)*ratioF}   // 7
                    };

                    final java.awt.Color slabColor = new java.awt.Color(40, 90, 160);
                    final java.awt.Stroke savedStroke = g2.getStroke();
                    g2.setStroke(new java.awt.BasicStroke(1.2f, java.awt.BasicStroke.CAP_BUTT,
                            java.awt.BasicStroke.JOIN_MITER, 4f, new float[]{6f, 4f}, 0f));
                    g2.setColor(slabColor);
                    final int[][] edges = {
                            {0,1},{1,2},{2,3},{3,0},  // near face
                            {4,5},{5,6},{6,7},{7,4},  // far face
                            {0,4},{1,5},{2,6},{3,7}   // pillars
                    };
                    for (final int[] e : edges) {
                        g2.drawLine((int)Math.round(sc[e[0]][0]), (int)Math.round(sc[e[0]][1]),
                                (int)Math.round(sc[e[1]][0]), (int)Math.round(sc[e[1]][1]));
                    }
                    g2.setStroke(savedStroke);
                }
            }

            g2.dispose();
        }

        private void drawLine3D(final java.awt.Graphics2D g2,
                                final java.util.function.Function<double[], double[]> project,
                                final double[] a, final double[] b,
                                final java.awt.Color color) {
            final double[] sa = project.apply(a);
            final double[] sb = project.apply(b);
            if (sa[2] <= 0 || sb[2] <= 0) return; // behind camera
            if (color != null) g2.setColor(color);
            g2.drawLine((int) Math.round(sa[0]), (int) Math.round(sa[1]),
                    (int) Math.round(sb[0]), (int) Math.round(sb[1]));
        }

        /** Returns [minX,minY,minZ, maxX,maxY,maxZ] in world space, or null if no sources. */
        private float[] sceneBounds() {
            if (multiSources.isEmpty()) return null;
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            final AffineTransform3D xfm = new AffineTransform3D();
            final double[] corner = new double[3], world = new double[3];
            for (final BvvMultiSource ms : multiSources) {
                for (final BvvStackSource<?> src : ms.getSources()) {
                    final bdv.viewer.SourceAndConverter<?> sac = src.getSources().getFirst();
                    final net.imglib2.RandomAccessibleInterval<?> rai = sac.getSpimSource().getSource(0, 0);
                    if (rai == null) continue;
                    sac.getSpimSource().getSourceTransform(0, 0, xfm);
                    for (int i = 0; i < 8; i++) {
                        corner[0] = (i & 1) == 0 ? rai.min(0) : rai.max(0);
                        corner[1] = (i & 2) == 0 ? rai.min(1) : rai.max(1);
                        corner[2] = (i & 4) == 0 ? rai.min(2) : rai.max(2);
                        xfm.apply(corner, world);
                        if (world[0] < minX) minX = (float) world[0];
                        if (world[0] > maxX) maxX = (float) world[0];
                        if (world[1] < minY) minY = (float) world[1];
                        if (world[1] > maxY) maxY = (float) world[1];
                        if (world[2] < minZ) minZ = (float) world[2];
                        if (world[2] > maxZ) maxZ = (float) world[2];
                    }
                }
            }
            return minX == Float.MAX_VALUE ? null : new float[]{minX, minY, minZ, maxX, maxY, maxZ};
        }
    }


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
                for (SNTPoint p : points) {
                    final Color pColor = (p instanceof Path.PathNode pn) ? pn.getColor() : null;
                    annotations.add(new Annotation(p, radius, (pColor == null) ? color : pColor));
                }
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

        /** Returns the number of annotations currently in the overlay. */
        public int getCount() {
            return annotations.size();
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

        /** Syncs slab clip planes from OverlayRenderer so both renderers clip consistently. */
        void setCamParams(final double nearClip, final double farClip) {
            annRenderer.nearClipAnn = nearClip;
            annRenderer.farClipAnn  = farClip;
        }

        void dispose() {
            if (viewerPanel != null && annRenderer != null) {
                viewerPanel.getDisplay().overlays().remove(annRenderer);
            }
        }

        /**
         * Optimized annotation renderer with caching and batched drawing.
         */
        private static class AnnRenderer implements bdv.viewer.OverlayRenderer {

            // Camera parameters kept in sync w/ OverlayRenderer via AnnotationOverlay.setCamParams()
            private static final double D_CAM = BvvUtils.DEFAULT_D_CAM;
            double nearClipAnn = BvvUtils.DEFAULT_NEAR_CLIP;
            double farClipAnn  = BvvUtils.DEFAULT_FAR_CLIP;
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

                    // Cursor Z-clipping (3D Euclidean distance)
                    if (doClip) {
                        final double dx = data.worldX - clipPos[0];
                        final double dy = data.worldY - clipPos[1];
                        final double dz = data.worldZ - clipPos[2];
                        if (dx*dx + dy*dy + dz*dz > clipDist * clipDist) continue;
                    }

                    // Slab clipping: hide annotations outside the slab's viewer-Z range
                    if (renderingOptions.isClipAnnotationsToSlab()) {
                        if (data.viewerZ > farClipAnn || data.viewerZ < -nearClipAnn) continue;
                    }

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

                // Extract the current pixels-per-world-unit scale from the viewer transform.
                // Column magnitude of the affine = scale factor; average X and Y columns.
                final double scaleX = Math.sqrt(
                        viewerTransform.get(0, 0) * viewerTransform.get(0, 0) +
                                viewerTransform.get(1, 0) * viewerTransform.get(1, 0) +
                                viewerTransform.get(2, 0) * viewerTransform.get(2, 0));
                final double scaleY = Math.sqrt(
                        viewerTransform.get(0, 1) * viewerTransform.get(0, 1) +
                                viewerTransform.get(1, 1) * viewerTransform.get(1, 1) +
                                viewerTransform.get(2, 1) * viewerTransform.get(2, 1));
                final double scale = (scaleX + scaleY) / 2.0;

                for (int i = 0; i < annotations.size(); i++) {
                    final Annotation ann = annotations.get(i);
                    final AnnotationScreenData data = new AnnotationScreenData();

                    // World coordinates
                    worldCoords[0] = ann.p.getX();
                    worldCoords[1] = ann.p.getY();
                    worldCoords[2] = ann.p.getZ();
                    data.worldX = worldCoords[0];
                    data.worldY = worldCoords[1];
                    data.worldZ = worldCoords[2];

                    // Transform to viewer coordinates
                    viewerTransform.apply(worldCoords, viewerCoords);
                    data.viewerZ = viewerCoords[2]; // screen-space Z for slab clipping

                    // Perspective projection.
                    // ann.radiusUm is in physical (world-space) units. Multiplying by
                    // scale converts it to screen pixels at the current zoom level, then
                    // pf applies perspective foreshortening, matching path node rendering.
                    final double pf = D_CAM / (D_CAM + viewerCoords[2]);
                    data.screenX = centerX + (viewerCoords[0] - centerX) * pf;
                    data.screenY = centerY + (viewerCoords[1] - centerY) * pf;
                    data.screenRadius = Math.max(1.0, ann.radiusUm * scale * pf);
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
                double worldX, worldY, worldZ;
                double viewerZ; // screen-space Z for slab clipping
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
        private final double[] clipPosReuse = new double[3];
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
            // Camera params are set externally via VolumeViewerPanel.setCamParams() after image loading;
            // store BVV defaults here as initial values. These will be overridden by computeCamParams()
            dCam = BvvUtils.DEFAULT_D_CAM;
            nearClip = BvvUtils.DEFAULT_NEAR_CLIP;
            farClip = BvvUtils.DEFAULT_FAR_CLIP;
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
                if (cacheValid) {
                    // Cache-hit frame: use stored visibility w/o bbox reprojection
                    final TreeScreenData cached = screenDataCache.get(tree);
                    if (cached != null && !cached.visible) continue;
                } else {
                    // Cache-miss frame: recompute visibility and store it
                    if (!isTreePotentiallyVisible(tree)) {
                        // Store invisible result so next cache-hit skips it too
                        final TreeScreenData existing = screenDataCache.get(tree);
                        if (existing != null) existing.visible = false;
                        continue;
                    }
                }
                drawTreeOptimized(g2d, tree, clipPos);
            }

            // Mark cache valid after all trees have been (re)computed this frame.
            cacheValid = true;

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

            // Fast Z-range cull against slab: No projection needed, just world-space comparison.
            // This can skip entire trees before the more expensive screen projection
            if (renderingOptions.isClipPathsToSlab()) {
                final double treeZMin = Math.min(origin.getZ(), opposite.getZ());
                final double treeZMax = Math.max(origin.getZ(), opposite.getZ());
                if (treeZMax < renderingOptions.getSlabZMin() ||
                        treeZMin > renderingOptions.getSlabZMax()) return false;
            }

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
            viewerPanel.getGlobalMouseCoordinates(RealPoint.wrap(clipPosReuse));
            return clipPosReuse;
        }

        private void drawTreeOptimized(final Graphics2D g2d, final Tree tree, final double[] clipPos) {
            // Get or compute screen data (cache miss = transform changed or new trees)
            TreeScreenData screenData = screenDataCache.get(tree);
            if (screenData == null || !cacheValid) {
                screenData = computeScreenData(tree);
                screenData.visible = true; // visible confirmed by isTreePotentiallyVisible above
                screenDataCache.put(tree, screenData);
            }

            // Use pre-built batches w/o HashMap/ArrayList/SegmentData allocation per frame.
            // Clipping is applied at draw time: skip segments w/ both endpoints outside the slab
            final boolean doClip = clipPos != null;
            final float clipDist = renderingOptions.clippingDistance;

            for (final Map.Entry<Color, List<SegmentData>> entry : screenData.batches.entrySet()) {
                drawBatch(g2d, entry.getKey(), entry.getValue(), doClip, clipPos, clipDist);
            }
        }

        private TreeScreenData computeScreenData(final Tree tree) {
            final TreeScreenData data = new TreeScreenData();

            // Extract scale from transform once
            final double avgScale = getAverageScale();

            for (final Path path : tree.list()) {
                final int n = path.size();
                if (n < 1) continue;

                final PathScreenData pathData = new PathScreenData(n);
                final Color defaultColor = path.getColor() != null ? path.getColor() : renderingOptions.fallbackColor;
                final boolean hasNodeColors = path.hasNodeColors();
                // Hoist offset outside inner loop: same value for all nodes (#1)
                final sc.fiji.snt.util.PointInCanvas offset = path.getCanvasOffset();
                final double offX = offset != null ? offset.x : 0;
                final double offY = offset != null ? offset.y : 0;
                final double offZ = offset != null ? offset.z : 0;

                // Hoist renderingOptions getters: avoid repeated virtual calls per node
                final double minR = renderingOptions.getMinThickness() / 2.0;
                final double maxR = renderingOptions.getMaxThickness() / 2.0;

                for (int i = 0; i < n; i++) {
                    final Path.PathNode node = path.getNode(i);

                    worldCoords[0] = node.x + offX;
                    worldCoords[1] = node.y + offY;
                    worldCoords[2] = node.z + offZ;

                    viewerTransform.apply(worldCoords, viewerCoords);

                    final double pf = dCam / (dCam + viewerCoords[2]);
                    pathData.screenX[i] = centerX + (viewerCoords[0] - centerX) * pf;
                    pathData.screenY[i] = centerY + (viewerCoords[1] - centerY) * pf;
                    pathData.worldZ[i] = worldCoords[2];
                    pathData.viewerZ[i] = viewerCoords[2]; // screen-space Z for slab clipping

                    final double r = renderingOptions.isUsePathRadius() && node.getRadius() > 0
                            ? node.getRadius() : minR;
                    pathData.screenRadius[i] = Math.max(0.5,
                            Math.min(r * renderingOptions.getThicknessMultiplier(), maxR) * avgScale * pf);

                    final Color nodeColor = hasNodeColors ? node.getColor() : null;
                    pathData.colors[i] = nodeColor != null ? nodeColor : defaultColor;

                    pathData.visible[i] = isOnScreen(pathData.screenX[i], pathData.screenY[i]);
                }

                // Pre-build SegmentData for each valid segment to avoid per-frame allocation
                for (int i = 0; i < pathData.size - 1; i++) {
                    if (!pathData.visible[i] && !pathData.visible[i + 1]) continue;
                    final double dx = pathData.screenX[i + 1] - pathData.screenX[i];
                    final double dy = pathData.screenY[i + 1] - pathData.screenY[i];
                    if (dx * dx + dy * dy < 0.25) continue; // sub-pixel
                    final SegmentData seg = new SegmentData();
                    seg.x1 = pathData.screenX[i];     seg.y1 = pathData.screenY[i];
                    seg.x2 = pathData.screenX[i + 1]; seg.y2 = pathData.screenY[i + 1];
                    seg.r1 = pathData.screenRadius[i]; seg.r2 = pathData.screenRadius[i + 1];
                    seg.worldZ1 = pathData.worldZ[i];  seg.worldZ2 = pathData.worldZ[i + 1];
                    seg.viewerZ1 = pathData.viewerZ[i]; seg.viewerZ2 = pathData.viewerZ[i + 1];
                    seg.color1 = pathData.colors[i];   seg.color2 = pathData.colors[i + 1];
                    pathData.segments[i] = seg;
                }

                data.paths.add(pathData);
            }

            data.buildBatches();
            return data;
        }



        /**
         * Draws a batch of pre-grouped segments in a single pass.
         * <p>
         * When {@link PathRenderingOptions#isDisplayRadii()} is {@code false}, uses
         * Java2D {@link java.awt.BasicStroke} line rendering: no frustum geometry.
         * <p>
         * When {@code true}: uniform segments are batched into a single
         * {@link Path2D} fill; gradient segments drawn inline. Caps are deduplicated
         * (each internal node drawn once, not twice) and skipped below 1.5 px radius.
         * <p>
         * Clipping is applied here (clip position changes independently of transform).
         */
        private void drawBatch(final Graphics2D g2d, final Color batchColor,
                               final List<SegmentData> segments,
                               final boolean doClip, final double[] clipPos, final float clipDist) {
            if (segments.isEmpty()) return;

            // Slab clipping via viewer-space Z: segments whose viewer-Z puts them
            // outside [−nearClip, farClip] are hidden by BVV's volume renderer, so we
            // match exactly the same range. World-space slabZMin/Max are only used for
            // the coarse tree-level bounding-box cull in isTreePotentiallyVisible.
            final boolean doSlabClip = renderingOptions.isClipPathsToSlab(); // path-specific flag
            final double slabNear = doSlabClip ? -nearClip : Double.NEGATIVE_INFINITY;
            final double slabFar  = doSlabClip ?  farClip  : Double.POSITIVE_INFINITY;

            // Fast path: BasicStroke lines
            if (!renderingOptions.isDisplayRadii()) {
                // Stroke width = average of all segment radii in this batch, doubled.
                // A single stroke per batch minimises setStroke() calls.
                double totalR = 0;
                int count = 0;
                for (final SegmentData seg : segments) {
                    if (doClip && Math.abs(seg.worldZ1 - clipPos[2]) > clipDist
                            && Math.abs(seg.worldZ2 - clipPos[2]) > clipDist) continue;
                    if (doSlabClip && seg.viewerZ1 > slabFar  && seg.viewerZ2 > slabFar)  continue;
                    if (doSlabClip && seg.viewerZ1 < slabNear && seg.viewerZ2 < slabNear) continue;
                    totalR += seg.r1 + seg.r2;
                    count += 2;
                }
                final float strokeW = count > 0 ? (float) Math.max(0.5, totalR / count * 2.0) : 1f;
                g2d.setStroke(new java.awt.BasicStroke(strokeW,
                        java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
                g2d.setColor(batchColor);
                batchPath.reset();
                for (final SegmentData seg : segments) {
                    if (doClip && Math.abs(seg.worldZ1 - clipPos[2]) > clipDist
                            && Math.abs(seg.worldZ2 - clipPos[2]) > clipDist) continue;
                    if (doSlabClip && seg.viewerZ1 > slabFar  && seg.viewerZ2 > slabFar)  continue;
                    if (doSlabClip && seg.viewerZ1 < slabNear && seg.viewerZ2 < slabNear) continue;
                    batchPath.moveTo(seg.x1, seg.y1);
                    batchPath.lineTo(seg.x2, seg.y2);
                }
                g2d.draw(batchPath);
                return;
            }

            // Frustum path: tapered tube with per-node radii
            // Single-pass: accumulate uniform segs, draw gradients inline.
            // Caps: deduplicated via a small set; each node position drawn once.
            // Caps below 1.5 px radius are skipped (frustum fill covers the joint).
            batchPath.reset();
            boolean hasUniform = false;
            // Use a simple long-key set to avoid allocating Point2D objects:
            // encode (x, y) as a single long, sufficient for pixel-level dedup.
            final java.util.HashSet<Long> drawnCaps = new java.util.HashSet<>();
            g2d.setColor(batchColor);

            for (final SegmentData seg : segments) {
                if (doClip && Math.abs(seg.worldZ1 - clipPos[2]) > clipDist
                        && Math.abs(seg.worldZ2 - clipPos[2]) > clipDist) continue;
                if (doSlabClip && seg.viewerZ1 > slabFar  && seg.viewerZ2 > slabFar)  continue;
                if (doSlabClip && seg.viewerZ1 < slabNear && seg.viewerZ2 < slabNear) continue;
                if (seg.color1.equals(seg.color2)) {
                    addFrustumToPath(batchPath, seg);
                    hasUniform = true;
                    // Queue caps for deduped draw after fill
                } else {
                    drawGradientFrustum(g2d, seg);
                    // Draw caps for gradient seg immediately (different colors)
                    drawCap(g2d, seg.x1, seg.y1, seg.r1, seg.color1, drawnCaps);
                    drawCap(g2d, seg.x2, seg.y2, seg.r2, seg.color2, drawnCaps);
                    g2d.setColor(batchColor);
                }
            }

            if (hasUniform) {
                g2d.setColor(batchColor);
                g2d.fill(batchPath);
                // Deduplicated caps for uniform segments
                for (final SegmentData seg : segments) {
                    if (!seg.color1.equals(seg.color2)) continue;
                    if (doClip && Math.abs(seg.worldZ1 - clipPos[2]) > clipDist
                            && Math.abs(seg.worldZ2 - clipPos[2]) > clipDist) continue;
                    if (doSlabClip && seg.viewerZ1 > slabFar  && seg.viewerZ2 > slabFar)  continue;
                    if (doSlabClip && seg.viewerZ1 < slabNear && seg.viewerZ2 < slabNear) continue;
                    drawCap(g2d, seg.x1, seg.y1, seg.r1, batchColor, drawnCaps);
                    drawCap(g2d, seg.x2, seg.y2, seg.r2, batchColor, drawnCaps);
                }
            }
        }

        /**
         * Draws a single circular cap, deduplicated by pixel position and skipping
         * radii below 1.5 px (frustum fill visually covers the joint at that scale).
         */
        private void drawCap(final Graphics2D g2d, final double cx, final double cy,
                             final double r, final Color color,
                             final java.util.HashSet<Long> drawn) {
            if (r < 1.5) return; // skip sub-pixel caps (#2)
            // Encode pixel position as long key for dedup (#1)
            final long key = (Math.round(cx) << 20) ^ Math.round(cy);
            if (!drawn.add(key)) return; // already drawn at this pixel
            g2d.setColor(color);
            final int d = (int) Math.round(2 * r);
            g2d.fillOval((int) Math.round(cx - r), (int) Math.round(cy - r), d, d);
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

        /**
         * Pre-computed screen data for a whole tree, including pre-batched segments
         * grouped by color. Batching is done once on cache-miss so per-frame drawing
         * requires no HashMap/ArrayList/SegmentData allocation.
         */
        private static class TreeScreenData {
            final List<PathScreenData> paths = new ArrayList<>();
            /** Pre-batched segments keyed by color. Populated by buildBatches(). */
            final Map<Color, List<SegmentData>> batches = new LinkedHashMap<>();
            boolean batchesBuilt = false;
            /** Cached screen-space visibility: avoids re-projecting 8 bbox corners on cache-hit frames. */
            boolean visible = true;

            void buildBatches() {
                batches.clear();
                for (final PathScreenData p : paths) {
                    for (int i = 0; i < p.size - 1; i++) {
                        final SegmentData seg = p.segments[i];
                        if (seg == null) continue; // sub-pixel or off-screen, pre-filtered
                        batches.computeIfAbsent(seg.color1, k -> new ArrayList<>()).add(seg);
                    }
                }
                batchesBuilt = true;
            }
        }

        private static class PathScreenData {
            final int size;
            final double[] screenX;
            final double[] screenY;
            final double[] worldZ;
            final double[] viewerZ; // screen-space Z (viewerCoords[2]) for slab clipping
            final double[] screenRadius;
            final Color[] colors;
            final boolean[] visible;
            /** Pre-built segment objects, null for sub-pixel/off-screen segments. */
            final SegmentData[] segments;

            PathScreenData(int size) {
                this.size = size;
                this.screenX = new double[size];
                this.screenY = new double[size];
                this.worldZ = new double[size];
                this.viewerZ = new double[size];
                this.screenRadius = new double[size];
                this.colors = new Color[size];
                this.visible = new boolean[size];
                this.segments = new SegmentData[Math.max(0, size - 1)];
            }
        }

        private static class SegmentData {
            double x1, y1, x2, y2;
            double r1, r2;
            double worldZ1, worldZ2; // retained for cursor Z-clipping in drawBatch
            double viewerZ1, viewerZ2; // screen-space Z for slab clipping vs nearClip/farClip
            Color color1, color2;
        }
    }

    /**
     * A snapshot of the BVV viewer state at a particular moment, used as a
     * keyframe for movie recording. Captures the viewer transform, camera/slab
     * parameters ({@code dCam}, {@code nearClip}, {@code farClip}), and which
     * "actors" (volume channels, paths, annotations) are visible.
     * <p>
     * Keyframes are serialized/deserialized via {@link #toString()} and
     * {@link #fromString(String)} so they can be dumped to the console with
     * the {@code K} hotkey and pasted into scripts.
     */
    public static class Keyframe {

        /** Viewer transform (camera position + zoom + rotation). */
        public final AffineTransform3D transform;
        /** Camera depth parameter (perspective). */
        public final double dCam;
        /** Near clipping distance (slab front). */
        public final double nearClip;
        /** Far clipping distance (slab back). */
        public final double farClip;
        /** Names of visible actors (e.g. "vol:Sample#1", "paths", "annotations"). */
        public final Set<String> visibleActors;
        /**
         * Easing type for the transition <em>into</em> this keyframe (0-5).
         * Can be set by name via {@link #setAccel(String)}.
         *
         * @see #ACCEL_SYMMETRIC
         * @see #ACCEL_SLOW_START
         * @see #ACCEL_SLOW_END
         * @see #ACCEL_SOFT_SYMMETRIC
         * @see #ACCEL_SOFT_SLOW_START
         * @see #ACCEL_SOFT_SLOW_END
         */
        public int accelType;

        public static final int ACCEL_SYMMETRIC       = 0;
        public static final int ACCEL_SLOW_START      = 1;
        public static final int ACCEL_SLOW_END        = 2;
        public static final int ACCEL_SOFT_SYMMETRIC  = 3;
        public static final int ACCEL_SOFT_SLOW_START = 4;
        public static final int ACCEL_SOFT_SLOW_END   = 5;

        private static final Map<String, Integer> ACCEL_NAMES = new LinkedHashMap<>();
        static {
            ACCEL_NAMES.put("symmetric",       ACCEL_SYMMETRIC);
            ACCEL_NAMES.put("slow start",      ACCEL_SLOW_START);
            ACCEL_NAMES.put("slow end",        ACCEL_SLOW_END);
            ACCEL_NAMES.put("soft symmetric",  ACCEL_SOFT_SYMMETRIC);
            ACCEL_NAMES.put("soft slow start", ACCEL_SOFT_SLOW_START);
            ACCEL_NAMES.put("soft slow end",   ACCEL_SOFT_SLOW_END);
        }

        /**
         * Normalises an accel name: lowercase, trim, underscores → spaces.
         */
        private static String normalizeAccelName(final String name) {
            return name.toLowerCase().trim().replace('_', ' ');
        }

        /**
         * Sets the easing type by name. Accepted values (case-insensitive,
         * spaces or underscores): "symmetric", "slow_start" / "slow start",
         * "slow_end", "soft_symmetric", "soft_slow_start", "soft_slow_end".
         *
         * @param name the easing name
         * @throws IllegalArgumentException if the name is not recognized
         */
        public void setAccel(final String name) {
            final Integer type = ACCEL_NAMES.get(normalizeAccelName(name));
            if (type == null)
                throw new IllegalArgumentException("Unknown accel type: '" + name
                        + "'. Valid: " + String.join(", ", ACCEL_NAMES.keySet()));
            this.accelType = type;
        }

        /**
         * Returns the current easing type as a human-readable name.
         *
         * @return the easing name, e.g. "slow start"
         */
        public String getAccelName() {
            for (final Map.Entry<String, Integer> e : ACCEL_NAMES.entrySet())
                if (e.getValue() == accelType) return e.getKey();
            return "symmetric";
        }

        /**
         * Returns the easing name in serialization-safe form (underscores, no spaces).
         */
        private String getAccelNameSerialized() {
            return getAccelName().replace(' ', '_');
        }

        /**
         * Number of frames for the transition from the previous keyframe into
         * this one. Ignored for the first keyframe in a sequence. Defaults to
         * 60 (~2 s at 30 fps).
         */
        public int frames;

        /**
         * Convenience constructor that deserializes a keyframe from a string.
         * Equivalent to {@link #fromString(String)} but usable as
         * {@code new Keyframe("transform=...|cam=...|...")} in scripts.
         *
         * @param serialized the string produced by {@link #toString()}
         * @throws IllegalArgumentException if parsing fails
         */
        public Keyframe(final String serialized) {
            final Keyframe parsed = fromString(serialized);
            if (parsed == null) throw new IllegalArgumentException("Invalid keyframe string");
            this.transform = parsed.transform;
            this.dCam = parsed.dCam;
            this.nearClip = parsed.nearClip;
            this.farClip = parsed.farClip;
            this.visibleActors = parsed.visibleActors;
            this.accelType = parsed.accelType;
            this.frames = parsed.frames;
        }

        public Keyframe(final AffineTransform3D transform,
                        final double dCam, final double nearClip, final double farClip,
                        final Set<String> visibleActors, final int accelType) {
            this(transform, dCam, nearClip, farClip, visibleActors, accelType, 60);
        }

        public Keyframe(final AffineTransform3D transform,
                        final double dCam, final double nearClip, final double farClip,
                        final Set<String> visibleActors, final int accelType, final int frames) {
            this.transform = new AffineTransform3D();
            this.transform.set(transform);
            this.dCam = dCam;
            this.nearClip = nearClip;
            this.farClip = farClip;
            this.visibleActors = new LinkedHashSet<>(visibleActors);
            this.accelType = accelType;
            this.frames = frames;
        }

        /**
         * Serializes this keyframe to a single-line string:
         * {@code transform=d0,d1,...,d11|cam=dCam,near,far|visible=a;b;c|accel=N}
         */
        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder("transform=");
            final double[] m = transform.getRowPackedCopy();
            for (int i = 0; i < m.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(m[i]);
            }
            sb.append("|cam=").append(dCam).append(',').append(nearClip).append(',').append(farClip);
            // Sanitize actor names: replace ';' to avoid breaking the delimiter
            sb.append("|visible=").append(visibleActors.stream()
                    .map(a -> a.replace(';', '_'))
                    .collect(java.util.stream.Collectors.joining(";")));
            sb.append("|accel=").append(getAccelNameSerialized());
            sb.append("|frames=").append(frames);
            return sb.toString();
        }

        /**
         * Deserializes a keyframe from the string produced by {@link #toString()}.
         *
         * @param s the serialised keyframe string
         * @return a new Keyframe, or {@code null} if parsing fails
         */
        public static Keyframe fromString(final String s) {
            try {
                final Map<String, String> parts = new LinkedHashMap<>();
                for (final String part : s.split("\\|")) {
                    final int eq = part.indexOf('=');
                    if (eq > 0) parts.put(part.substring(0, eq), part.substring(eq + 1));
                }
                // Transform
                final String[] td = parts.get("transform").split(",");
                final double[] m = new double[12];
                for (int i = 0; i < 12; i++) m[i] = Double.parseDouble(td[i]);
                final AffineTransform3D t = new AffineTransform3D();
                t.set(m);
                // Camera / slab params
                final String camStr = parts.getOrDefault("cam", "");
                double dc = BvvUtils.DEFAULT_D_CAM, nc = BvvUtils.DEFAULT_NEAR_CLIP, fc = BvvUtils.DEFAULT_FAR_CLIP;
                if (!camStr.isBlank()) {
                    final String[] cp = camStr.split(",");
                    dc = Double.parseDouble(cp[0]);
                    nc = Double.parseDouble(cp[1]);
                    fc = Double.parseDouble(cp[2]);
                }
                // Visible actors
                final Set<String> vis = new LinkedHashSet<>();
                final String visStr = parts.getOrDefault("visible", "");
                if (!visStr.isBlank()) Collections.addAll(vis, visStr.split(";"));
                // Accel (accepts "slow_start", "slow start", or int "1")
                final String accelStr = parts.getOrDefault("accel", "symmetric");
                int accel;
                final Integer named = ACCEL_NAMES.get(normalizeAccelName(accelStr));
                if (named != null) accel = named;
                else try { accel = Integer.parseInt(accelStr.trim()); } catch (final NumberFormatException nf) { accel = 0; }
                // Frames
                final int frames = Integer.parseInt(parts.getOrDefault("frames", "60"));
                return new Keyframe(t, dc, nc, fc, vis, accel, frames);
            } catch (final Exception e) {
                SNTUtils.log("Keyframe parse error: " + e.getMessage());
                return null;
            }
        }
    }

    /**
     * Captures the current viewer state as a {@link Keyframe}. The transform,
     * camera/slab parameters, and visible actor set are all snapshotted.
     *
     * @return the current state as a Keyframe
     */
    public Keyframe captureKeyframe() {
        if (currentBvv == null) throw new IllegalStateException("No BVV viewer active");
        final VolumeViewerPanel vp = currentBvv.getViewer();
        // Transform
        final AffineTransform3D t = new AffineTransform3D();
        vp.state().getViewerTransform(t);
        // Camera / slab params from the overlay renderer
        final double dc = pathOverlay != null ? pathOverlay.overlayRenderer.dCam : BvvUtils.DEFAULT_D_CAM;
        final double nc = pathOverlay != null ? pathOverlay.overlayRenderer.nearClip : BvvUtils.DEFAULT_NEAR_CLIP;
        final double fc = pathOverlay != null ? pathOverlay.overlayRenderer.farClip : BvvUtils.DEFAULT_FAR_CLIP;
        // Visible actors
        final Set<String> actors = new LinkedHashSet<>();
        final bdv.viewer.SynchronizedViewerState state = vp.state();
        // Collect sources that belong to at least one group (active or not)
        final List<bdv.viewer.SourceGroup> groups = state.getGroups();
        final Set<bdv.viewer.SourceAndConverter<?>> groupedSources = new HashSet<>();
        for (int g = 0; g < groups.size(); g++) {
            final bdv.viewer.SourceGroup grp = groups.get(g);
            groupedSources.addAll(state.getSourcesInGroup(grp));
            if (state.isGroupActive(grp)) {
                final String name = state.getGroupName(grp);
                actors.add("vol:" + (name != null ? name : "group" + g));
            }
        }
        // Only record individual sources that are active but NOT covered by any group
        final List<? extends bdv.viewer.SourceAndConverter<?>> allSrcs = state.getSources();
        for (int i = 0; i < allSrcs.size(); i++) {
            if (state.isSourceActive(allSrcs.get(i)) && !groupedSources.contains(allSrcs.get(i))) {
                actors.add("src:" + i);
            }
        }
        // Paths
        if (pathOverlay != null && pathOverlay.isRenderingEnable())
            actors.add("paths");
        // Annotations
        if (annotationOverlay != null && annotationOverlay.isVisible())
            actors.add("annotations");
        return new Keyframe(t, dc, nc, fc, actors, 0);
    }

    /**
     * Cosine easing function (symmetric).
     * @see <a href="https://github.com/maarzt/bigdataviewer-core-movie">BDV movie recorder</a>
     */
    private static double cos(final double x) {
        return 0.5 - 0.5 * Math.cos(Math.PI * x);
    }

    /**
     * Easing functions for keyframe transitions, adapted from BDV movie recorder.
     *
     * @param t    progress value in [0, 1]
     * @param type easing type: 0 = symmetric, 1 = slow start, 2 = slow end,
     *             3 = soft symmetric, 4 = soft slow start, 5 = soft slow end
     * @return eased progress value in [0, 1]
     */
    public static double accel(final double t, final int type) {
        return switch (type) {
            case 1 -> cos(t * t);                                // slow start
            case 2 -> 1.0 - cos(Math.pow(1.0 - t, 2));          // slow end
            case 3 -> cos(cos(t));                               // soft symmetric
            case 4 -> cos(cos(t * t));                           // soft slow start
            case 5 -> 1.0 - cos(cos(Math.pow(1.0 - t, 2)));     // soft slow end
            default -> cos(t);                                   // symmetric (type 0)
        };
    }

    /**
     * Plays back an animation between keyframes in the viewer without saving
     * frames. Useful for previewing a movie before committing to a render.
     * The frame count for each transition is read from {@link Keyframe#frames}
     * on the destination keyframe.
     *
     * @param keyframes ordered list of keyframes (at least 2)
     * @see #renderFrames(List, String)
     */
    public void playback(final List<Keyframe> keyframes) {
        renderFrames(keyframes, null);
    }

    /**
     * Plays back a subset of keyframes (from index {@code from} to index
     * {@code to}, inclusive). Useful for previewing a specific transition
     * without replaying the entire sequence.
     *
     * @param keyframes ordered list of all keyframes
     * @param from      start index (inclusive, 0-based)
     * @param to        end index (inclusive, 0-based)
     * @see #playback(List)
     */
    public void playback(final List<Keyframe> keyframes, final int from, final int to) {
        renderFrames(keyframes.subList(from, to + 1), null);
    }

    /**
     * Renders an animation between a list of keyframes, saving each frame as a
     * PNG screenshot. The transform is interpolated smoothly between keyframes
     * using {@link SimilarityTransformAnimator}; visibility and slab bounds snap
     * at keyframe boundaries (no interpolation).
     * <p>
     * The number of frames for each transition is read from {@link Keyframe#frames}
     * on each destination keyframe (the first keyframe's value is ignored).
     * <p>
     * This method must be called from a non-EDT thread. It blocks until all
     * frames have been rendered and saved.
     *
     * @param keyframes ordered list of keyframes (at least 2)
     * @param outputDir directory where PNGs will be saved (created if needed);
     *                  if {@code null}, frames are played back live without saving
     * @throws IllegalArgumentException if arguments are inconsistent
     * @throws IllegalStateException    if no BVV viewer is active
     */
    public void renderFrames(final List<Keyframe> keyframes, final String outputDir) {
        if (currentBvv == null) throw new IllegalStateException("No BVV viewer active");
        if (keyframes.size() < 2) throw new IllegalArgumentException("Need at least 2 keyframes");

        final boolean save = outputDir != null;
        final java.io.File dir = save ? new java.io.File(outputDir) : null;
        if (save && !dir.exists() && !dir.mkdirs())
            throw new IllegalArgumentException("Cannot create output directory: " + outputDir);

        final VolumeViewerPanel vp = currentBvv.getViewer();
        final java.awt.Component canvas = vp.getDisplayComponent();
        final int cX = canvas.getWidth() / 2;
        final int cY = canvas.getHeight() / 2;
        int globalFrame = 0;

        for (int k = 1; k < keyframes.size(); k++) {
            final Keyframe from = keyframes.get(k - 1);
            final Keyframe to = keyframes.get(k);
            final int nFrames = to.frames;
            final SimilarityTransformAnimator animator = new SimilarityTransformAnimator(
                    from.transform, to.transform, cX, cY, 0);

            // Apply visibility & camera/slab from the 'from' keyframe at the start
            // of each segment: visibility snaps at keyframe boundaries
            applyKeyframeState(from);
            if (!save) {
                vp.showMessage(String.format("Keyframe %d → %d  (%d frames, %s)",
                        k - 1, k, nFrames, to.getAccelName()));
            }

            for (int d = 0; d < nFrames; d++) {
                final double progress = (double) d / (double) nFrames;
                final double eased = accel(progress, to.accelType);
                final AffineTransform3D interpolated = animator.get(eased);
                // Set transform on EDT and wait for render
                try {
                    SwingUtilities.invokeAndWait(() -> {
                        vp.state().setViewerTransform(interpolated);
                        vp.requestRepaint();
                    });
                    // Wait for render to complete via the latch pattern
                    final java.util.concurrent.CountDownLatch latch =
                            new java.util.concurrent.CountDownLatch(1);
                    final bdv.viewer.TransformListener<AffineTransform3D> listener =
                            t -> latch.countDown();
                    vp.renderTransformListeners().add(listener);
                    vp.requestRepaint();
                    latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    Thread.sleep(50); // brief pause for GL paint to finish
                    vp.renderTransformListeners().remove(listener);
                } catch (final Exception e) {
                    SNTUtils.log("Movie render interrupted at frame " + globalFrame);
                    Thread.currentThread().interrupt();
                    return;
                }
                if (save) {
                    // Capture screenshot and save PNG
                    final int w = canvas.getWidth();
                    final int h = canvas.getHeight();
                    final java.awt.image.BufferedImage bi =
                            new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
                    try {
                        SwingUtilities.invokeAndWait(() -> canvas.paint(bi.getGraphics()));
                    } catch (final Exception e) {
                        SNTUtils.log("Screenshot failed at frame " + globalFrame);
                        globalFrame++;
                        continue;
                    }
                    final java.io.File outFile = new java.io.File(dir,
                            String.format("frame_%05d.png", globalFrame));
                    try {
                        javax.imageio.ImageIO.write(bi, "PNG", outFile);
                    } catch (final java.io.IOException e) {
                        SNTUtils.log("Failed to write " + outFile.getName() + ": " + e.getMessage());
                    }
                }
                globalFrame++;
            }
        }
        // Apply the final keyframe
        final Keyframe last = keyframes.getLast();
        applyKeyframeState(last);
        try {
            SwingUtilities.invokeAndWait(() -> {
                vp.state().setViewerTransform(last.transform);
                vp.requestRepaint();
            });
            if (save) {
                Thread.sleep(150); // allow final render
                final int w = canvas.getWidth();
                final int h = canvas.getHeight();
                final java.awt.image.BufferedImage bi =
                        new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
                SwingUtilities.invokeAndWait(() -> canvas.paint(bi.getGraphics()));
                javax.imageio.ImageIO.write(bi, "PNG",
                        new java.io.File(dir, String.format("frame_%05d.png", globalFrame)));
            }
        } catch (final Exception e) {
            SNTUtils.log("Failed to render final frame: " + e.getMessage());
        }
        if (save)
            System.out.println("Movie: " + (globalFrame + 1) + " frames saved to " + dir.getAbsolutePath());
        else
            System.out.println("Playback complete: " + (globalFrame + 1) + " frames");
    }

    /**
     * Applies the full non-transform state from a keyframe to the current viewer:
     * camera/slab parameters, source/group visibility, paths, and annotations.
     */
    private void applyKeyframeState(final Keyframe kf) {
        final VolumeViewerPanel vp = currentBvv.getViewer();
        // Camera / slab params: this is what actually controls the BVV volume clipping
        vp.setCamParams(kf.dCam, kf.nearClip, kf.farClip);
        if (pathOverlay != null) {
            pathOverlay.overlayRenderer.dCam = kf.dCam;
            pathOverlay.overlayRenderer.nearClip = kf.nearClip;
            pathOverlay.overlayRenderer.farClip = kf.farClip;
        }
        if (annotationOverlay != null)
            annotationOverlay.setCamParams(kf.nearClip, kf.farClip);
        syncOverlays();
        // Groups
        final bdv.viewer.SynchronizedViewerState state = vp.state();
        final List<bdv.viewer.SourceGroup> groups = state.getGroups();
        for (int g = 0; g < groups.size(); g++) {
            final bdv.viewer.SourceGroup grp = groups.get(g);
            final String name = state.getGroupName(grp);
            final String key = "vol:" + (name != null ? name : "group" + g);
            state.setGroupActive(grp, kf.visibleActors.contains(key));
        }
        // Individual sources
        final List<? extends bdv.viewer.SourceAndConverter<?>> allSrcs = state.getSources();
        for (int i = 0; i < allSrcs.size(); i++) {
            state.setSourceActive(allSrcs.get(i), kf.visibleActors.contains("src:" + i));
        }
        // Paths
        if (pathOverlay != null)
            pathOverlay.disableRendering(!kf.visibleActors.contains("paths"));
        // Annotations
        if (annotationOverlay != null)
            annotationOverlay.setVisible(kf.visibleActors.contains("annotations"));
    }

    /** Actions for BVV GUI components. */
    private class BvvActions {
        private final BigVolumeViewer bvv;
        private final GuiUtils guiUtils;
        private float lastClippingDistance = 100f;

        BvvActions(final BigVolumeViewer bvv) {
            this.bvv = bvv;
            this.guiUtils = new GuiUtils(bvv.getViewerFrame());
        }

        Action loadSettingsAction() {
            return new AbstractAction("Load Settings...", IconFactory.menuIcon(IconFactory.GLYPH.IMPORT)) {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    final File f = guiUtils.getFile(new File(getDefaultDir(), ".xml"), "xml");
                    if (SNTUtils.fileAvailable(f)) {
                        try {
                            bvv.loadSettings(f.getAbsolutePath());
                            bvv.getViewer().showMessage(String.format("%s loaded", f.getName()));
                            setDefaultDir(f);
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
                    final File f = guiUtils.getSaveFile("Save BVV Settings...",
                            new File(getDefaultDir(), "settings.xml"), "xml");
                    if (SNTUtils.fileAvailable(f)) {
                        try {
                            bvv.saveSettings(f.getAbsolutePath());
                            bvv.getViewer().showMessage(String.format("%s saved", f.getName()));
                            setDefaultDir(f);
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
                    final File[] files = guiUtils.getReconstructionFiles(getDefaultDir());
                    if (files == null || files.length == 0) return;
                    setDefaultDir(files[0]);
                    add(files); // delegates to SwingWorker with progress bar
                }
            };
        }

        Action togggleVisibilityAction() {
            return new AbstractAction("Show/hide All Annotations") {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    final boolean hasAnnotations = (pathOverlay != null && !pathOverlay.sntBvv.getRenderedTrees().isEmpty())
                            || (annotationOverlay != null && annotationOverlay.isVisible());
                    if (!hasAnnotations && (annotationOverlay == null || annotationOverlay.getCount() == 0)) {
                        bvv.getViewer().showMessage("No annotations exist.");
                        return;
                    }
                    // When the toggle button is selected, we are in "hide" state
                    final boolean hide = (e.getSource() instanceof AbstractButton btn)
                            ? btn.isSelected() : pathOverlay == null || pathOverlay.isRenderingEnable();
                    if (pathOverlay != null) pathOverlay.disableRendering(hide);
                    if (annotationOverlay != null) annotationOverlay.setVisible(!hide);
                    bvv.getViewer().showMessage(hide ? "Annotations hidden" : "Annotations visible");
                }
            };
        }

        /** Tracks whether paths were visible before H was pressed */
        private boolean pathsWereVisible;
        /** Tracks whether annotations were visible before H was pressed */
        private boolean annotationsWereVisible;
        /** Guard against key-repeat firing multiple press events */
        private boolean hideActive;

        Action hideAnnotationsPressAction() {
            return new AbstractAction("Hide annotations (hold)") {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    if (hideActive) return; // key repeat guard
                    // Check if there is anything to hide (markers are part of annotationOverlay)
                    final boolean hasPaths = pathOverlay != null && pathOverlay.isRenderingEnable()
                            && !pathOverlay.sntBvv.getRenderedTrees().isEmpty();
                    final boolean hasAnnotations = annotationOverlay != null
                            && annotationOverlay.isVisible() && annotationOverlay.getCount() > 0;
                    if (!hasPaths && !hasAnnotations) {
                        bvv.getViewer().showMessage("Nothing to hide");
                        return;
                    }
                    // Save current state
                    pathsWereVisible = pathOverlay != null && pathOverlay.isRenderingEnable();
                    annotationsWereVisible = annotationOverlay != null && annotationOverlay.isVisible();
                    // Hide overlays (markers table window stays open)
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
                    // Restore prior state
                    if (pathOverlay != null) pathOverlay.disableRendering(!pathsWereVisible);
                    if (annotationOverlay != null) annotationOverlay.setVisible(annotationsWereVisible);
                    hideActive = false;
                }
            };
        }

        Action setCanvasOffsetAction() {
            return new AbstractAction("Annotations Offset...", IconFactory.buttonIcon(IconFactory.GLYPH.MOVE, 1f)) {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    final SNTPoint offset = guiUtils.getCoordinates("Offsets:", "Annotations Offset (Calibrated Distances) ",
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

        Action PathRenderingOptionsAction() {
            return new AbstractAction("Path Rendering Options", IconFactory.buttonIcon(IconFactory.GLYPH.SLIDERS, 1f)) {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    final HashMap<String, Object> inputs = new HashMap<>();
                    inputs.put("bvv", Bvv.this);
                    SNTUtils.getContext().getService(CommandService.class).run(BvvRenderingOptionsCmd.class, true, inputs);
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

        Action togglePersistentAnnotationsAction() {
            return new AbstractAction("Toggle Annotations Around Cursor") {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    if (!(e.getSource() instanceof AbstractButton toggleButton)) return;

                    if (renderingOptions.isClippingEnabled()) {
                        // Turning OFF: save current distance and disable
                        lastClippingDistance = renderingOptions.clippingDistance;
                        renderingOptions.setClippingDistance(0);
                    } else {
                        // Turning ON: prompt for distance
                        final Double newDist = guiUtils.getDouble(
                                "<HTMl>Only annotations within this distance from the cursor will be displayed.<br>"
                                        + "Set it to 0, or cancel this prompt to disable this option.",
                                "Annotations Near Cursor",
                                lastClippingDistance,
                                0d,
                                Arrays.stream(dims).asDoubleStream().max().orElse(1000d),
                                calUnit);
                        if (newDist == null) {
                            // User cancelled: revert button state
                            toggleButton.setSelected(false);
                            return;
                        }
                        renderingOptions.setClippingDistance(newDist == 0 ? 0 : newDist.floatValue());
                    }

                    toggleButton.setSelected(renderingOptions.isClippingEnabled());
                    bvv.getViewer().requestRepaint();
                    bvv.getViewer().showMessage(renderingOptions.isClippingEnabled()
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
                            Color c = guiUtils.getColor("Fallback Color for Untagged Bookmarks", Color.RED, (String[]) null);
                            if (c == null) c = Color.MAGENTA;
                            annotations().setAnnotations(pos, 3.5f * renderingOptions.minThickness, c);
                            bvv.getViewer().showMessage(String.format("%d Bookmarks annotated", pos.size()));
                        }
                    } catch (NullPointerException ex) {
                        bvv.getViewer().showMessage("Bookmark Manager unavailable");
                    }
                }
            };
        }

        /**
         * Places a marker at the current mouse position in world coordinates.
         * Delegates to {@link BookmarkManager#add(double, double, double)}.
         * Wired to the {@code M} key in the BVV viewer.
         */
        Action addMarkerAction() {
            return new AbstractAction("Add Marker") {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    final RealPoint pos = new RealPoint(3);
                    bvv.getViewer().getGlobalMouseCoordinates(pos);
                    final double x = pos.getDoublePosition(0);
                    final double y = pos.getDoublePosition(1);
                    final double z = pos.getDoublePosition(2);
                    // Validate X and Y against the source's world-space bounding box.
                    // Only XY are checked: Z is controlled by the user's focal-plane navigation
                    // and can legitimately sit anywhere along the depth axis. Out-of-range XY
                    // indicates the view was obliquely rotated when M was pressed, making the
                    // focal-plane projection meaningless.
                    if (dims != null && cal != null) {
                        final bdv.viewer.SourceAndConverter<?> currentSac =
                                bvv.getViewer().state().getCurrentSource();
                        if (currentSac != null) {
                            final AffineTransform3D t = new AffineTransform3D();
                            currentSac.getSpimSource().getSourceTransform(0, 0, t);
                            double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
                            double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
                            final double[] corner = new double[3];
                            final double[] world = new double[3];
                            for (int i = 0; i < 8; i++) {
                                corner[0] = (i & 1) == 0 ? 0 : dims[0] - 1;
                                corner[1] = (i & 2) == 0 ? 0 : dims[1] - 1;
                                corner[2] = (i & 4) == 0 ? 0 : dims[2] - 1;
                                t.apply(corner, world);
                                minX = Math.min(minX, world[0]);
                                maxX = Math.max(maxX, world[0]);
                                minY = Math.min(minY, world[1]);
                                maxY = Math.max(maxY, world[1]);
                            }
                            if (x < minX || x > maxX || y < minY || y > maxY) {
                                bvv.getViewer().showMessage(
                                        "Outside image bounds: Align view to a principal axis before placing a marker.");
                                return;
                            }
                        }
                    }
                    getMarkerManager().add(x, y, z);
                    bvv.getViewer().showMessage(String.format("Marker placed at (%.1f, %.1f, %.1f)", x, y, z));
                }
            };
        }

        Action showMarkerManagerAction() {
            return new AbstractAction("Marker Manager", IconFactory.menuIcon(IconFactory.GLYPH.MARKER)) {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    getMarkerManager().toggleBvvPanel();
                }
            };
        }

        Action showHelpAction() {
            return new AbstractAction("Shortcuts...", IconFactory.menuIcon('\uf11c', true)) {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    guiUtils.showKeyboardShortcuts(
                            new InputMap[]{
                                    bvv.getViewerFrame().getKeybindings().getConcatenatedInputMap(),
                                    bvv.getViewer().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                            },
                            bvv.getViewerFrame().getKeybindings().getConcatenatedActionMap(),
                            bvv.getViewer().getActionMap()  // picks up snt-add-marker, snt-bvv-snapshot
                    );
                }
            };
        }

        Action showMovieHelpAction() {
            return new AbstractAction("Scripted Movies...", IconFactory.menuIcon('\uf008', true)) {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    final String script = "BvvRecording.groovy";
                    ScriptInstaller.newScript(BvvUtils.loadBoilerPlateScript(script), script);
                }
            };
        }

        /**
         * Manually propagates each group leader's current transform to its followers.
         * Use this when live sync is disabled or when GPU lag makes live sync impractical.
         */
        Action saveTransformAction(final List<BvvMultiSource> multiSources) {
            return new AbstractAction("Save Transform", IconFactory.menuIcon(IconFactory.GLYPH.EXPORT)) {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    final BvvMultiSource target = chooseMultiSource("Save transform of:");
                    if (target == null) return;
                    final File file = guiUtils.getSaveFile("Save Transform...",
                            new File(getDefaultDir(), "transform.xml"), "xml");
                    if (file == null) return;
                    setDefaultDir(file);
                    final AffineTransform3D t = new AffineTransform3D();
                    target.getLeaderTransform(t);
                    try {
                        final org.jdom2.Element root = new org.jdom2.Element("SNTTransforms");
                        root.setAttribute("version", "1");
                        final org.jdom2.Element manualT = new org.jdom2.Element("ManualTransformation");
                        manualT.setAttribute("group", multiSourceName(target, multiSources));
                        final StringBuilder sb = new StringBuilder();
                        for (int r = 0; r < 3; r++)
                            for (int c = 0; c < 4; c++) {
                                if (!sb.isEmpty()) sb.append(' ');
                                sb.append(t.get(r, c));
                            }
                        manualT.addContent(new org.jdom2.Element("affine").setText(sb.toString()));
                        root.addContent(manualT);
                        final org.jdom2.Document doc = new org.jdom2.Document(root);
                        try (final java.io.FileWriter fw = new java.io.FileWriter(
                                file.getName().endsWith(".xml") ? file : new File(file.getAbsolutePath() + ".xml"))) {
                            new org.jdom2.output.XMLOutputter(org.jdom2.output.Format.getPrettyFormat()).output(doc, fw);
                        }
                        bvv.getViewer().showMessage("Transform saved: " + file.getName());
                    } catch (final Exception ex) {
                        guiUtils.error("Could not save transform: " + ex.getMessage());
                    }
                }
            };
        }

        Action loadTransformAction(final List<BvvMultiSource> multiSources) {
            return new AbstractAction("Load Transform", IconFactory.menuIcon(IconFactory.GLYPH.IMPORT)) {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    if (multiSources.isEmpty()) {
                        guiUtils.error("No grouped sources available to apply a transform to.");
                        return;
                    }
                    final File file = guiUtils.getFile(new File(getDefaultDir(), ".xml"), "xml");
                    if (file == null) return;
                    try {
                        applyTransformFile(file, multiSources);
                        setDefaultDir(file);
                    } catch (final Exception ex) {
                        guiUtils.error("Could not load transform: " + ex.getMessage());
                    }
                }
            };
        }

        private File getDefaultDir() {
            return SNTPrefs.lastKnownDir(); // never null
        }

        private void setDefaultDir(final File newDir) {
            SNTPrefs.setLastKnownDir(newDir);
        }

        private boolean applyTransformFile(final File file, final List<BvvMultiSource> multiSources) throws JDOMException, IOException {
            final org.jdom2.Element root = new org.jdom2.input.SAXBuilder().build(file).getRootElement();
            if (!"SNTTransforms".equals(root.getName()))
                throw new IllegalArgumentException("Not a valid SNT transform file.");
            final org.jdom2.Element manualT = root.getChild("ManualTransformation");
            if (manualT == null)
                throw new IllegalArgumentException("Missing <ManualTransformation> element.");
            // Parse affine matrix
            final String[] vals = manualT.getChildText("affine").trim().split("\\s+");
            if (vals.length != 12)
                throw new IllegalArgumentException("Expected 12 affine values, got " + vals.length);
            final AffineTransform3D t = new AffineTransform3D();
            for (int r = 0; r < 3; r++)
                for (int c = 0; c < 4; c++)
                    t.set(Double.parseDouble(vals[r * 4 + c]), r, c);
            // Choose target group: prefer name match, then ask user
            final String savedGroup = manualT.getAttributeValue("group");
            BvvMultiSource target = multiSources.stream()
                    .filter(ms -> multiSourceName(ms, multiSources).equals(savedGroup))
                    .findFirst().orElse(null);
            if (target == null)
                target = chooseMultiSource("Apply transform to:");
            if (target == null) return false;
            target.applyTransform(t);
            bvv.getViewer().requestRepaint();
            bvv.getViewer().showMessage("Transform loaded: " + file.getName());
            return true;
        }

        /**
         * Applies the manual transform of a chosen moving source to its image data,
         * resamples the result onto the chosen reference source's pixel grid using
         * bilinear interpolation, and writes the output as a 16-bit TIFF file.
         * The export runs on a background thread to keep the UI responsive.
         */
        Action exportTransformedSourceAction(final List<BvvMultiSource> multiSources) {
            return new AbstractAction("Export Transformed Image...", IconFactory.menuIcon(IconFactory.GLYPH.FILE_IMAGE)) {
                @Override
                public void actionPerformed(final java.awt.event.ActionEvent e) {
                    if (multiSources.isEmpty()) {
                        guiUtils.error("No sources available.");
                        return;
                    }
                    // Choose the moving source (the one carrying the manual transform)
                    final Map<String, BvvMultiSource> choiceMap = multiSourceToChoiceMap(multiSources);
                    final String[] choiceKeys = choiceMap.keySet().toArray(new String[0]);
                    final String[] choices = guiUtils.getTwoChoices(
                            "Export Registered Image", // title
                            "Fixed image (output will match its dimensions):", choiceKeys, choiceKeys[0], // choice 1
                            "Moving image (to resample and export):", choiceKeys, choiceKeys[choiceKeys.length - 1] // choice 2
                    );
                    if (choices == null) return; // user pressed cancel

                    final BvvMultiSource reference = multiSourceFromChoice(choiceMap, choices[0]);
                    final BvvMultiSource moving = multiSourceFromChoice(choiceMap, choices[1]);

                    // Choose output file
                    final File proposed = new File(getDefaultDir(), String.format("%s_registered_to_%s.tif",
                            SNTUtils.stripExtension(choices[1]),   // moving
                            SNTUtils.stripExtension(choices[0]))); // fixed/reference
                    final File file = guiUtils.getSaveFile("Export Transformed Image...", proposed, "tif");
                    if (file == null) return;
                    final String outPath = (file.getName().endsWith(".tif") || file.getName().endsWith(".tiff"))
                            ? file.getAbsolutePath() : file.getAbsolutePath() + ".tif";

                    bvv.getViewer().showMessage("Exporting...");
                    new Thread(() -> {
                        try {
                            exportTransform(moving, reference, outPath);
                            setDefaultDir(file);
                        } catch (final Exception ex) {
                            updateStatus("", 0, 0); // clear progress bar on failure
                            guiUtils.error("Export failed: " + ex.getMessage());
                            SNTUtils.error("BVV transform export failed", ex);
                        }
                    }, "BVV-TransformExport").start();
                }
            };
        }

        @SuppressWarnings("unchecked")
        private void exportTransform(final BvvMultiSource moving, final BvvMultiSource reference, final String outPath) throws Exception {
            // Reference grid: dimensions and calibration transform come from the leader.
            // The guard against TB-scale output runs here since it defines the output dimensions.
            final var refSac = reference.getLeader().getSources().getFirst();
            final Source<RealType<?>> refSpim = (Source<RealType<?>>) refSac.getSpimSource();
            final RandomAccessibleInterval<RealType<?>> refRai = refSpim.getSource(0, 0);
            BvvUtils.checkVolumeSize(refRai.dimension(0), refRai.dimension(1), refRai.dimension(2));
            final AffineTransform3D refSrcToWorld = new AffineTransform3D();
            refSpim.getSourceTransform(0, 0, refSrcToWorld);

            // The manual transform is the same for all channels in the group (field sharing).
            // Compute totalT once: maps ref pixels → moving pixels.
            final AffineTransform3D manualT = new AffineTransform3D();
            moving.getLeaderTransform(manualT);
            final var leaderSac = moving.getLeader().getSources().getFirst();
            final Source<RealType<?>> leaderSpim = (Source<RealType<?>>) leaderSac.getSpimSource();
            final AffineTransform3D srcToWorld = new AffineTransform3D();
            leaderSpim.getSourceTransform(0, 0, srcToWorld);
            final AffineTransform3D movingToWorld = new AffineTransform3D();
            movingToWorld.set(manualT);
            movingToWorld.concatenate(srcToWorld);
            // totalT = (manualT ∘ srcToWorld)^{-1} ∘ refSrcToWorld
            final AffineTransform3D totalT = movingToWorld.inverse();
            totalT.concatenate(refSrcToWorld);

            // Resample every channel of the moving group onto the reference grid
            final List<RandomAccessibleInterval<RealType<?>>> channels = new ArrayList<>();
            final int nChannels = moving.getSources().size();
            for (int chIdx = 0; chIdx < nChannels; chIdx++) {
                updateStatus(String.format("Resampling channel %d/%d…", chIdx + 1, nChannels),
                        chIdx, nChannels);
                final var chSource = moving.getSources().get(chIdx);
                final Source<RealType<?>> chSpim = (Source<RealType<?>>) chSource.getSources().getFirst().getSpimSource();
                final RandomAccessibleInterval<RealType<?>> chRai = chSpim.getSource(0, 0);
                @SuppressWarnings({"rawtypes"}) final RealRandomAccessible<RealType<?>> realRai =
                        (RealRandomAccessible<RealType<?>>) RealViews.affine(
                                Views.interpolate((net.imglib2.RandomAccessible) Views.extendZero(
                                        (RandomAccessibleInterval) chRai), new NLinearInterpolatorFactory()),
                                totalT);
                channels.add(Views.interval(Views.raster(realRai), refRai));
            }
            updateStatus("Writing output…", nChannels - 1, nChannels);

            // Save result. For multichannel output, wrap as ImgPlus with explicit XYZC
            // axis metadata so SCIFIO writes a proper hyperstack rather than treating
            // channels as extra Z slices.
            final net.imglib2.type.numeric.integer.UnsignedShortType outType =
                    new net.imglib2.type.numeric.integer.UnsignedShortType();
            if (channels.size() == 1) {
                ImgUtils.save(channels.getFirst(), outPath, outType);
            } else {
                // Allocate [X, Y, Z, C] output array
                final long[] dims = new long[]{
                        refRai.dimension(0), refRai.dimension(1),
                        refRai.dimension(2), channels.size()};
                final net.imglib2.img.Img<net.imglib2.type.numeric.integer.UnsignedShortType> out =
                        new ArrayImgFactory<>(outType).create(dims);
                // Copy each channel into its slice
                for (int c = 0; c < channels.size(); c++) {
                    final RandomAccessibleInterval<net.imglib2.type.numeric.integer.UnsignedShortType> slice =
                            Views.hyperSlice(out, 3, c);
                    LoopBuilder.setImages(channels.get(c), slice).multiThreaded()
                            .forEachPixel((in, o) -> o.setReal(in.getRealDouble()));
                }
                // Convert to ImagePlus hyperstack and save via ImageJ1's FileSaver,
                // bypassing SCIFIO which misidentifies multi-plane ZC data as RGB.
                final ij.ImagePlus imp = net.imglib2.img.display.imagej.ImageJFunctions.wrapUnsignedShort(out, new File(outPath).getName());
                final ij.ImagePlus hyperstack = ij.plugin.HyperStackConverter.toHyperStack(
                        imp, channels.size(), (int) refRai.dimension(2), 1, "xyzct", "composite");
                new ij.io.FileSaver(hyperstack).saveAsTiff(outPath);
            }
            bvv.getViewer().showMessage("Saved: " + new File(outPath).getName()
                    + (channels.size() > 1 ? " (" + channels.size() + " channels)" : ""));
            updateStatus("", 0, 0); // clear progress bar
        }

        /** Returns the display name of a BvvMultiSource (image title of its leader). */
        private String multiSourceName(final BvvMultiSource ms, final List<BvvMultiSource> all) {
            // Use the source name from the leader's first SourceAndConverter
            final var sacs = ms.getLeader().getSources();
            if (sacs != null && !sacs.isEmpty()) {
                final String name = sacs.getFirst().getSpimSource().getName();
                // Strip " (Ch1)" suffix added by showImagePlusMultiChannel
                return name.replaceAll("\\s*\\(Ch\\d+\\)$", "");
            }
            return "Group " + (all.indexOf(ms) + 1);
        }

        /** Shows a choice dialog for selecting one of the available multi-sources. */
        private BvvMultiSource chooseMultiSource(final String prompt) {
            final Map<String, BvvMultiSource> choiceMap = multiSourceToChoiceMap(multiSources);
            if (choiceMap.size() == 1) return choiceMap.values().iterator().next();
            final String[] choices = choiceMap.keySet().toArray(new String[0]);
            final String chosen = guiUtils.getChoice(prompt, "Select Source Group", choices, choices[0]);
            if (chosen == null) return null;
            return choiceMap.get(chosen);
        }

        private Map<String, BvvMultiSource> multiSourceToChoiceMap(final List<BvvMultiSource> sources) {
            assert sources != null;
            final Map<String, BvvMultiSource> choiceMap = new LinkedHashMap<>(); // respect insertion order
            for (final BvvMultiSource source : sources) {
                String name = multiSourceName(source, sources);
                // Guard against duplicate titles: append a counter to keep keys unique
                if (choiceMap.containsKey(name)) {
                    int suffix = 2;
                    while (choiceMap.containsKey(name + " (" + suffix + ")")) suffix++;
                    name = name + " (" + suffix + ")";
                }
                choiceMap.put(name, source);
            }
            return choiceMap;
        }

        private BvvMultiSource multiSourceFromChoice(final Map<String, BvvMultiSource> choiceMap, final String chosen) {
            if (choiceMap == null || chosen == null) return null;
            return choiceMap.get(chosen);
        }


        /**
         * Looks up a named action registered in BVV's keybindings and invokes it.
         * This avoids direct access to package-private fields like {@code bookmarkEditor}.
         */
        @SuppressWarnings("unused")
        private void invokeRegisteredAction(final String actionName, final java.awt.event.ActionEvent e) {
            final javax.swing.Action action = bvv.getViewerFrame().getKeybindings()
                    .getConcatenatedActionMap().get(actionName);
            if (action != null)
                action.actionPerformed(new java.awt.event.ActionEvent(
                        bvv.getViewerFrame(), java.awt.event.ActionEvent.ACTION_PERFORMED, actionName));
            else
                SNTUtils.log("BVV action not found: " + actionName);
        }
    }

}
